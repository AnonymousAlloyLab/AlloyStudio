#!/usr/bin/env python3
"""Offline SQLeanParser compilation of the portal's finite SQL registry.

This is a development-time generator. Deployment needs only the checked-in
artifacts and Python's sqlite3, not this executable or a Lean installation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from exercise_sql import MAX_ARTIFACT_BYTES, MAX_SQL_BYTES, _strict_json

START = '# BEGIN GENERATED SQL INTEGRITY\n'
END = '# END GENERATED SQL INTEGRITY\n'
INPUTS = ('sql/schema.json', 'sql/queries.json', 'vendor/sqlean/provenance.json')
COMPILED = 'sql/compiled-queries.json'


class ParserRejected(ValueError):
    """The pinned CLI reported a SQL parse/type validation rejection."""


class ParserInfrastructureError(RuntimeError):
    """Parser execution could not establish a SQL acceptance or rejection."""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def json_bytes(value):
    return (json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + '\n').encode('utf-8')


def _literal_tokens(sql):
    """Lex canonical output only; this is not a replacement SQL parser.

    SQLeanParser has already accepted this exact SQL. The small scanner locates
    complete string/integer literals while ignoring quoted identifiers. It does
    not accept comments, placeholders, unbalanced quotes or unknown punctuation.
    """
    result, at = [], 0
    while at < len(sql):
        character = sql[at]
        if character.isspace():
            at += 1
            continue
        start = at
        if character in "'\"":
            quote = character
            at += 1
            while at < len(sql):
                if sql[at] == quote:
                    if at + 1 < len(sql) and sql[at + 1] == quote:
                        at += 2
                        continue
                    at += 1
                    break
                at += 1
            else:
                raise ValueError('Unterminated canonical quoted token')
            if quote == "'":
                result.append((start, at, 'text', sql[start + 1:at - 1].replace("''", "'")))
        elif '0' <= character <= '9':
            at += 1
            while at < len(sql) and '0' <= sql[at] <= '9':
                at += 1
            if at < len(sql) and (sql[at].isalnum() or sql[at] in '_.'):
                raise ValueError('Ambiguous canonical integer token')
            result.append((start, at, 'int', int(sql[start:at])))
        elif character.isascii() and (character.isalpha() or character == '_'):
            at += 1
            while at < len(sql) and sql[at].isascii() and (sql[at].isalnum() or sql[at] == '_'):
                at += 1
        elif character in '(),;.*+-/=<>':
            if sql[at:at + 2] in ('--', '/*', '*/'):
                raise ValueError('Comments are not canonical tokens')
            at += 1
        else:
            raise ValueError('Unsupported canonical token')
    return result


def parameterize(canonical, parameters):
    if type(canonical) is not str or len(canonical.encode('utf-8')) > MAX_SQL_BYTES:
        raise ValueError('Oversized canonical query')
    if type(parameters) is not list:
        raise ValueError('Invalid parameter registry')
    registered = {}
    names = set()
    for parameter in parameters:
        kind = parameter.get('type')
        expected_keys = {'name', 'type', 'sentinel'} | ({'maxBytes'} if kind == 'text' else set())
        if set(parameter) != expected_keys or not re.fullmatch(r'[a-z_][a-z0-9_]*', parameter['name']):
            raise ValueError('Invalid parameter fields')
        sentinel = parameter['sentinel']
        if kind == 'text':
            valid = (type(sentinel) is str and re.fullmatch(r'__SQL_BIND_[A-Z0-9_]+__', sentinel)
                     and type(parameter['maxBytes']) is int and 0 < parameter['maxBytes'] <= MAX_ARTIFACT_BYTES)
        elif kind == 'int':
            valid = type(sentinel) is int and 9000000000000000 <= sentinel < 9000000000001000
        else:
            valid = False
        key = (kind, sentinel)
        if not valid or key in registered or parameter['name'] in names:
            raise ValueError('Invalid or duplicated parameter sentinel')
        registered[key] = len(registered)
        names.add(parameter['name'])
    replacements, observed = [], []
    for start, end, kind, literal in _literal_tokens(canonical):
        key = (kind, literal)
        if key in registered:
            replacements.append((start, end))
            observed.append(registered[key])
        elif ((kind == 'text' and '__SQL_BIND_' in literal)
              or (kind == 'int' and 9000000000000000 <= literal < 9000000000001000)):
            raise ValueError('Unknown or embedded parameter sentinel')
    if observed != list(range(len(parameters))):
        raise ValueError('Missing, duplicated or out-of-order parameter sentinel')
    fragments, cursor = [], 0
    for start, end in replacements:
        fragments.extend((canonical[cursor:start], '?'))
        cursor = end
    fragments.append(canonical[cursor:])
    return ''.join(fragments)


def _read(root, relative):
    path = root / relative
    if path.is_symlink() or not path.is_file():
        raise ValueError('Missing regular generation input')
    with path.open('rb') as stream:
        contents = stream.read(MAX_ARTIFACT_BYTES + 1)
    if len(contents) > MAX_ARTIFACT_BYTES:
        raise ValueError('Oversized generation input')
    return contents


def _run_parser(parser, arguments):
    if sys.platform != 'linux' or not shutil.which('unshare'):
        raise ParserInfrastructureError('Offline generation requires Linux network namespaces')
    environment = {key: os.environ[key] for key in ('LANG', 'LC_ALL', 'TZ') if key in os.environ}
    environment['PATH'] = os.defpath
    completed = subprocess.run(['unshare', '--user', '--map-root-user', '--net', '--', str(parser),
                                *arguments], input='', text=True, encoding='utf-8',
                               capture_output=True, timeout=30, env=environment, check=False)
    if completed.returncode != 0:
        # `unshare` can also exit 1. Only the pinned CLI's explicit syntax/type
        # diagnostics establish rejection; an execution failure is not evidence.
        diagnostic = completed.stderr.strip()
        if completed.returncode == 1 and diagnostic.startswith(('parse error:', 'validation error:')):
            raise ParserRejected('SQLeanParser refused a registered SQL template')
        if completed.returncode == 1 and diagnostic.startswith('schema error:'):
            raise ValueError('SQLeanParser rejected the supplied schema input')
        raise ParserInfrastructureError('SQLeanParser execution failed')
    if len(completed.stdout.encode('utf-8')) > MAX_ARTIFACT_BYTES:
        raise ValueError('Oversized SQLeanParser result')
    return completed.stdout.rstrip('\n')


def compile_registry(parser, root=ROOT):
    root, parser = Path(root), Path(parser)
    raw = {path: _read(root, path) for path in INPUTS}
    source = _strict_json(raw['sql/queries.json'])
    provenance = _strict_json(raw['vendor/sqlean/provenance.json'])
    if provenance['upstreamCommit'] != '6da54ef2874cbe7e0069bf8c192f12de3a8644f9':
        raise ValueError('Wrong SQLeanParser source revision')
    if provenance['leanToolchain'] != 'leanprover/lean4:v4.34.0':
        raise ValueError('Wrong SQLeanParser toolchain')
    if not parser.is_file():
        raise FileNotFoundError('The pinned SQLeanParser executable is unavailable')
    if digest(parser.read_bytes()) != provenance['parserBinary']['sha256']:
        raise ValueError('The parser executable does not match pinned provenance')
    for entry in provenance['files']:
        path = Path(entry['path'])
        if path.is_absolute() or '..' in path.parts:
            raise ValueError('Invalid parser source path')
        if digest(_read(root, Path('vendor/sqlean') / path)) != entry['sha256']:
            raise ValueError('Changed vendored SQLeanParser source')
    if source['schemaVersion'] != 1 or not source['queries']:
        raise ValueError('Invalid query registry')
    records, names = [], set()
    for query in source['queries']:
        if set(query) != {'id', 'source', 'parameters'}:
            raise ValueError('Invalid query fields')
        name, sql = query['id'], query['source']
        if not re.fullmatch(r'[a-z_][a-z0-9_]*', name) or name in names:
            raise ValueError('Invalid or repeated query ID')
        names.add(name)
        if len(sql.encode('utf-8')) > MAX_SQL_BYTES:
            raise ValueError('Oversized source query')
        checked = _run_parser(parser, ['--schema', str(root / 'sql/schema.json'), sql])
        certificate, canonical = checked.split('\n', 1)
        if not certificate.startswith('Valid ') or not canonical.endswith(';'):
            raise ValueError('Unexpected parser certificate output')
        ast = _run_parser(parser, ['--ast', sql])
        canonical_ast = _run_parser(parser, ['--ast', canonical])
        rechecked = _run_parser(parser, ['--schema', str(root / 'sql/schema.json'), canonical])
        if ast != canonical_ast or checked != rechecked:
            raise ValueError('SQLeanParser AST round trip changed')
        prepared = parameterize(canonical, query['parameters'])
        records.append({'id': name, 'canonical': canonical, 'ast': ast, 'certificate': certificate,
                        'sql': prepared, 'parameters': query['parameters'], 'roundTrip': True})
    artifact = json_bytes({'schemaVersion': 1, 'parser': 'SQLeanParser',
                           'boundary': 'Generation-time parsing/type checking; runtime bound parameters.',
                           'inputs': {path: digest(data) for path, data in raw.items()}, 'queries': records})
    if len(artifact) > MAX_ARTIFACT_BYTES:
        raise ValueError('Oversized compiled query artifact')
    return artifact


def integrity_source(source, hashes):
    if source.count(START) != 1 or source.count(END) != 1:
        raise ValueError('Ambiguous generated integrity region')
    before, remaining = source.split(START)
    _, after = remaining.split(END)
    body = 'ARTIFACT_HASHES = ' + repr(dict(sorted(hashes.items()))) + '\n'
    return before + START + body + END + after


def check_parser_rejections(parser, root=ROOT):
    """Actual parser negative controls, separate from portable unit tests."""
    fixtures = (
        'SELECT id FROM exercises WHERE id = ?',
        'SELECT id FROM exercises WHERE id = :id',
        'CREATE TABLE extra (value TEXT)', 'PRAGMA user_version', 'BEGIN',
        'SELECT absent_column FROM exercises',
        "INSERT INTO metadata (key, value) VALUES ('key', 17)",
    )
    for statement in fixtures:
        try:
            _run_parser(parser, ['--schema', str(Path(root) / 'sql/schema.json'), statement])
        except ParserRejected:
            continue
        raise ValueError('SQLeanParser accepted a negative control')
    return len(fixtures)


def main():
    arguments = argparse.ArgumentParser(description=__doc__)
    arguments.add_argument('--parser', type=Path, required=True)
    arguments.add_argument('--check', action='store_true', help='Compare generated bytes without rewriting')
    args = arguments.parse_args()
    try:
        artifact = compile_registry(args.parser)
        rejection_checks = check_parser_rejections(args.parser)
        hashes = {path: digest(_read(ROOT, path)) for path in INPUTS}
        hashes[COMPILED] = digest(artifact)
        runtime = ROOT / 'exercise_sql.py'
        rendered = integrity_source(runtime.read_text(encoding='utf-8'), hashes)
        if args.check:
            if _read(ROOT, COMPILED) != artifact or runtime.read_text(encoding='utf-8') != rendered:
                raise ValueError('Generated SQLeanParser artifacts are stale')
        else:
            (ROOT / COMPILED).write_bytes(artifact)
            runtime.write_text(rendered, encoding='utf-8')
        print(json.dumps({'status': 'PASS', 'queries': len(_strict_json(artifact)['queries']),
                          'parser': 'SQLeanParser', 'offline': True, 'checked': args.check,
                          'rejectionChecks': rejection_checks}))
        return 0
    except (OSError, RuntimeError, subprocess.SubprocessError):
        print('SQL generation infrastructure is unavailable.', file=sys.stderr)
        return 2
    except (ValueError, KeyError):
        print('SQL generation failed: pinned inputs, parser or generated artifacts were rejected.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
