"""Private, normalized SQLite exercise storage. Public projection lives in server.py.

Queries are finite SQLeanParser-generated statements in exercise_sql. Fixed
connection controls and schema creation are explicit trusted SQLite operations.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import tempfile
import time

import exercise_sql as sql
from runtime_dependencies import runtime_classpath, JAR_FILES, run_engine
from scripts.import_correct_pools import (body_token_sha256, candidate,
                                         environment_sha256, verify_document)
from scripts.import_exercises import tokens, verify_record

DATABASE_RELATIVE = 'exercises/exercises.sqlite3'
MAX_DATABASE = 128 * 1024 * 1024
MAX_IMPORT = 2 * 1024 * 1024
MAX_ENGINE = 1048576
LOCK_SECONDS = 5
SCHEMA_VERSION = '1'
ID = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')
NAME = re.compile(r'[A-Za-z_][A-Za-z0-9_]{0,127}\Z')
FIELD_MAP = {'id': 'id', 'title': 'title', 'group': 'model_group',
             'predicate': 'predicate_name', 'description': 'description',
             'environmentBefore': 'environment_before', 'environmentAfter': 'environment_after',
             'predicateHeader': 'predicate_header', 'starter': 'starter',
             'originalSource': 'original_source'}
IMPORT_REQUIRED = frozenset(('id', 'title', 'group', 'predicate', 'description',
                             'environmentBefore', 'environmentAfter', 'predicateHeader',
                             'starter', 'oracleSolutions'))
IMPORT_OPTIONAL = frozenset(('correctSolutions', 'equivalenceScope'))
PUBLIC_VERSION_FIELDS = ('id', 'title', 'group', 'predicate', 'description',
                         'environmentBefore', 'environmentAfter', 'predicateHeader', 'starter')
MAX_APPROVALS_PER_EXERCISE = 100
MAX_APPROVALS = 10000


class StoreError(ValueError):
    """Sanitized private-store failure. Never expose solver or SQL diagnostics."""


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'))


def sha(value):
    return hashlib.sha256(value if isinstance(value, bytes) else value.encode('utf-8')).hexdigest()


def content_version(record):
    """Public content identity; never hash private solution material into it."""
    return sha(encoded({key: record[key] for key in PUBLIC_VERSION_FIELDS}))


def exercise_version(record):
    """Stable private authority identity, independent of approved pool additions."""
    return sha(encoded(dict(public={key: record[key] for key in PUBLIC_VERSION_FIELDS},
                            oracleBody=record['oracleBody'], source=record['source'])))


def _page(offset, limit, maximum):
    if type(offset) is not int or offset < 0 or type(limit) is not int or not 1 <= limit <= maximum:
        raise StoreError('Invalid library page.')


def library_list(snapshot, offset=0, limit=50):
    _page(offset, limit, 50)
    records = list(snapshot.exercises.values())
    return dict(items=[dict(id=r['id'], predicate=r['predicate'], group=r['group'], title=r['title'],
                            exerciseVersion=exercise_version(r), contentVersion=content_version(r))
                       for r in records[offset:offset + limit]], total=len(records), offset=offset, limit=limit)


def library_detail(snapshot, identifier, expected_version=None):
    record = snapshot.exercises.get(identifier) if type(identifier) is str else None
    if record is None:
        raise StoreError('Question not found.')
    if expected_version is not None and expected_version != exercise_version(record):
        raise StoreError('This question changed. Refresh it before continuing.')
    return dict(id=record['id'], predicate=record['predicate'], group=record['group'], title=record['title'],
                question=record['description'], exerciseVersion=exercise_version(record),
                contentVersion=content_version(record))


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise StoreError('Duplicate input field.')
        result[key] = value
    return result


def parse_json(value):
    try:
        return json.loads(value, object_pairs_hook=unique_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(StoreError('Invalid number.')))
    except (ValueError, UnicodeError, TypeError, RecursionError) as error:
        raise StoreError('Invalid private data encoding.') from error


def read_bounded(path, limit):
    # A size precheck alone does not bound allocation if a supplied file grows
    # between stat and read. Never consume more than the envelope plus one byte.
    with Path(path).open('rb') as stream:
        contents = stream.read(limit + 1)
    if len(contents) > limit:
        raise StoreError('Private input exceeds its byte limit.')
    return contents


def safe_path(path):
    """Inspect lexical ancestors before resolution, including Windows junctions."""
    path = Path(os.path.abspath(os.path.expanduser(str(path))))
    for part in (*reversed(path.parents), path):
        try:
            info = part.lstat()
        except FileNotFoundError:
            continue
        if (stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400):
            raise StoreError('Private data paths must not contain links or junctions.')
    return path


def text(value, limit, *, empty=True):
    try:
        valid = (type(value) is str and '\0' not in value
                 and len(value.encode('utf-8')) <= limit and (empty or bool(value.strip())))
    except UnicodeError:
        valid = False
    if not valid:
        raise StoreError('Invalid or oversized text field.')
    return value


def body(value):
    text(value, 8192, empty=False)
    try:
        tokens(value.encode('utf-8'))  # catches escape braces and incomplete comments/strings
    except ValueError as error:
        raise StoreError('Predicate body is not contained or is malformed.') from error
    return value


def model(record, value):
    return record['environmentBefore'] + record['predicateHeader'] + '{\n' + value + '\n}' + record['environmentAfter']


def validate_record_fields(record, references, *, authored=False):
    if not ID.fullmatch(text(record['id'], 128, empty=False)):
        raise StoreError('Invalid exercise identifier.')
    if not NAME.fullmatch(text(record['predicate'], 128, empty=False)):
        raise StoreError('Invalid predicate name.')
    for key in ('title', 'group'):
        text(record[key], 256, empty=False)
    text(record['description'], 8192, empty=False)
    for key in ('environmentBefore', 'environmentAfter', 'originalSource'):
        text(record.get(key, ''), 262144)
    text(record['predicateHeader'], 8192)
    if authored and not re.fullmatch(r'\s*pred\s+' + re.escape(record['predicate']) + r'\s*', record['predicateHeader']):
        raise StoreError('Only a parameterless predicate header is supported.')
    # Some legacy starters deliberately contain syntax errors; keep their exact
    # witnessed text, but new imports must compile and stay within their body.
    text(record['starter'], 8192)
    if authored:
        try:
            tokens(record['starter'].encode('utf-8'))
        except ValueError as error:
            raise StoreError('Starter body is malformed.') from error
    if not 1 <= len(references) <= (256 if authored else 4096):
        raise StoreError('Invalid solution count.')
    for value in references:
        body(value)
        if len(model(record, value).encode('utf-8')) > 262144:
            raise StoreError('Model exceeds the private import limit.')
    # Reserve the worst JSON encoding of any future allowed learner body (a
    # control byte expands to six ASCII bytes), for all engine entry points.
    learner = '\x01' * 8192
    prefix = record['environmentBefore'] + record['predicateHeader'] + '{\n'
    suffix = '\n}' + record['environmentAfter']
    payloads = [dict(studentSource=prefix + learner + suffix,
                     referenceBodies=references, referencePrefix=prefix,
                     referenceSuffix=suffix, predicate=record['predicate'], metric='canonical'),
                dict(studentSource=prefix + learner + suffix, studentBody=learner,
                     oracleSource=model(record, record.get('oracleBody', references[0])),
                     predicate=record['predicate'])]
    if max(len(json.dumps(p).encode('utf-8')) for p in payloads) > MAX_ENGINE:
        raise StoreError('Solutions exceed the aggregate engine request limit.')


def connect(path, *, writable=False):
    path = safe_path(path)
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_DATABASE:
        raise StoreError('Private exercise database is missing or exceeds its limit.')
    # URI quoting is handled by pathlib (including spaces, # and Windows drives).
    connection = sqlite3.connect(path.as_uri() + ('?mode=rw' if writable else '?mode=ro'),
                                 uri=True, timeout=LOCK_SECONDS, isolation_level=None)
    connection.row_factory = sqlite3.Row
    connection.execute('PRAGMA foreign_keys = ON')
    connection.execute('PRAGMA trusted_schema = OFF')
    if not writable:
        connection.execute('PRAGMA query_only = ON')
    if hasattr(connection, 'setlimit'):
        connection.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, 4 * 1024 * 1024)
        connection.setlimit(sqlite3.SQLITE_LIMIT_ATTACHED, 0)
    return connection


@dataclass
class Snapshot:
    catalogue: dict
    pools_document: dict
    exercises: dict
    correct_pools: dict
    admin_uploads: list = field(default_factory=list)
    raw_exercises: dict = field(default_factory=dict)
    pool_tokens: dict = field(default_factory=dict)
    equivalence_scopes: dict = field(default_factory=dict)
    exercise_ordinals: dict = field(default_factory=dict)

    @property
    def exercise_count(self):
        return len(self.exercises)

    @property
    def candidate_count(self):
        return sum(map(len, self.correct_pools.values()))


def _rows(connection, table):
    return [dict(row) for row in sql.execute(connection, 'select_' + table)]


def _snapshot(connection):
    sql.validate_schema(connection)
    metadata = {row['key']: parse_json(row['value']) for row in _rows(connection, 'metadata')}
    if set(metadata) != {'schemaVersion', 'catalogue', 'pools'} or metadata['schemaVersion'] != SCHEMA_VERSION:
        raise StoreError('Unsupported exercise database version or metadata.')
    catalogue = dict(metadata['catalogue'], exercises=[])
    pools = dict(metadata['pools'], pools=[], sourceInventory=[], excludedSources=[])
    if 'exercises' in metadata['catalogue'] or any(k in metadata['pools'] for k in ('pools','sourceInventory','excludedSources')):
        raise StoreError('Predicates cannot be stored in metadata.')
    auxiliary = _rows(connection, 'auxiliary')
    ordinals = {'sourceInventory': 0, 'excludedSources': 0, 'adminUpload': 0}
    admin_uploads = []
    extensions = []
    for row in auxiliary:
        kind = row['kind']
        if kind in ('adminRemoved', 'studentCandidate') or kind.startswith('adminApproval:'):
            extensions.append(row)
            continue
        if kind not in ordinals or row['ordinal'] != ordinals[kind]:
            raise StoreError('Invalid auxiliary ordering.')
        item = parse_json(row['payload'])
        if 'originalSource' in item:
            raise StoreError('Source text must use its own column.')
        if kind == 'adminUpload':
            item['originalSource'] = row['original_source']
            admin_uploads.append(item)
            ordinals[kind] += 1
            continue
        if kind == 'excludedSources':
            item['originalSource'] = row['original_source']
        elif row['original_source']:
            raise StoreError('Unexpected source text.')
        pools[kind].append(item)
        ordinals[kind] += 1
    solutions = {}
    for row in _rows(connection, 'solutions'):
        items = solutions.setdefault(row['exercise_id'], [])
        if row['ordinal'] != len(items):
            raise StoreError('Invalid solution order.')
        item = parse_json(row['metadata'])
        if set(item) != {'id','bodySha256','environmentSha256','source'}:
            raise StoreError('Invalid solution metadata.')
        item.update(body=row['body'], originalSource=row['original_source'],
                    kind=row['kind'], tokenSha256=row['token_hash'])
        items.append(item)
    legacy_records, legacy_pools = [], []
    authored_documents = {}
    records = _rows(connection, 'exercises')
    if not 1 <= len(records) <= 10000:
        raise StoreError('Invalid exercise count.')
    for index, row in enumerate(records):
        if row['ordinal'] != index:
            raise StoreError('Invalid exercise order.')
        record = parse_json(row['metadata'])
        if set(record) != {'source','descriptionProvenance','sourceClassification','preservation'}:
            raise StoreError('Invalid exercise metadata.')
        record.update({key: row[column] for key, column in FIELD_MAP.items()})
        entries = solutions.pop(record['id'], [])
        if not entries or len({x['tokenSha256'] for x in entries}) != len(entries):
            raise StoreError('Missing or duplicate solutions.')
        primary = [x for x in entries if x['tokenSha256'] == row['primary_token'] and x['kind'] == 'oracle']
        if len(primary) != 1:
            raise StoreError('Missing primary oracle.')
        record['oracleBody'] = primary[0]['body']
        validate_record_fields(record, [x['body'] for x in entries], authored=row['origin'] == 'authored')
        pool = dict(exerciseId=record['id'], environmentSha256=environment_sha256(record), candidates=entries)
        if row['origin'] == 'legacy':
            if parse_json(row['validation']) != {'kind':'legacy-classification'}:
                raise StoreError('Invalid legacy validation record.')
            verify_record(record)
            legacy_records.append(record)
            legacy_pools.append(pool)
        elif row['origin'] == 'authored':
            certificate = parse_json(row['validation'])
            _validate_authored(record, entries, certificate)
            authored_documents[record['id']] = (record, entries, certificate)
        else:
            raise StoreError('Invalid exercise origin.')
        catalogue['exercises'].append(record)
        pools['pools'].append(pool)
    if solutions:
        raise StoreError('Orphaned solutions.')
    verify_document(dict(catalogue, exercises=legacy_records), dict(pools, pools=legacy_pools))
    _validate_upload_archives(admin_uploads, authored_documents)
    result = Snapshot(catalogue, pools, {r['id']:r for r in catalogue['exercises']},
                      {p['exerciseId']:tuple(c['body'] for c in p['candidates']) for p in pools['pools']},
                      admin_uploads)
    result.raw_exercises = dict(result.exercises)
    result.pool_tokens = {p['exerciseId']: frozenset(c['tokenSha256'] for c in p['candidates'])
                          for p in pools['pools']}
    result.equivalence_scopes = {identifier: certificate['result']['scope']
                                 for identifier, (_, _, certificate) in authored_documents.items()}
    result.exercise_ordinals = {row['id']: row['ordinal'] for row in records}
    _apply_extensions(result, extensions)
    return result


def load_store(root, database_path=None):
    try:
        with closing(connect(database_path or Path(root) / DATABASE_RELATIVE)) as connection:
            connection.execute('BEGIN')
            return _snapshot(connection)
    except (sqlite3.Error, ValueError, TypeError, KeyError, AttributeError, UnicodeError) as error:
        if isinstance(error, StoreError):
            raise
        raise StoreError('Private exercise database validation failed.') from error


def _insert(connection, table, values):
    sql.execute(connection, 'insert_' + table, tuple(values[field] for field in sql.FIELDS[table]))


def _auxiliary_kind(connection, kind):
    return [dict(row) for row in sql.execute(connection, 'select_auxiliary_kind', (kind,))]


def _size_guard(connection):
    if (connection.execute('PRAGMA page_count').fetchone()[0]
            * connection.execute('PRAGMA page_size').fetchone()[0] > MAX_DATABASE):
        raise StoreError('Exercise database size limit reached.')


def _approval_document(record, candidate_body, scope):
    return {**{key: record[key] for key in IMPORT_REQUIRED if key != 'oracleSolutions'},
            'starter': candidate_body, 'oracleSolutions': [record['oracleBody']],
            'correctSolutions': [candidate_body], 'equivalenceScope': scope}


def validate_approval(record, candidate_body, certificate, *, minimum_scope=5):
    """Pure validation of a backend-produced bounded-equivalence certificate."""
    body(candidate_body)
    keys = {'kind','version','result','environmentSha256','bodySha256','starterSha256',
            'engineSha256','dependencySha256'}
    if type(certificate) is not dict or set(certificate) != keys:
        raise StoreError('Missing candidate equivalence provenance.')
    result = certificate['result']
    scope = result.get('scope') if type(result) is dict else None
    expected = dict(scope=scope, bitwidth=5, maxSequence=scope, minTrace=1, maxTrace=10,
                    solver='SAT4J', oracleCount=1, correctCount=1, evaluatedCandidates=2,
                    moduleFacts=True, factsSatisfiable=True)
    if (certificate['kind'] != 'alloy-bounded-equivalence' or type(certificate['version']) is not int
            or certificate['version'] != 1 or type(scope) is not int
            or not max(5, minimum_scope) <= scope <= 8 or result != expected
            or any(type(result[k]) is not type(v) for k, v in expected.items())
            or certificate['environmentSha256'] != environment_sha256(record)
            or certificate['bodySha256'] != [sha(record['oracleBody']), sha(candidate_body)]
            or certificate['starterSha256'] != sha(candidate_body)
            or type(certificate['engineSha256']) is not str
            or not re.fullmatch('[a-f0-9]{64}', certificate['engineSha256'])):
        raise StoreError('Candidate equivalence provenance changed.')
    dependencies = certificate['dependencySha256']
    if (type(dependencies) is not dict or set(dependencies) != set(JAR_FILES)
            or any(type(value) is not str or not re.fullmatch('[a-f0-9]{64}', value)
                   for value in dependencies.values())):
        raise StoreError('Missing candidate engine provenance.')
    return certificate


def prepare_approval(root, record, candidate_body, *, java='java', timeout=60):
    snapshot = load_store(root)
    current = snapshot.exercises.get(record['id'])
    if current is None or exercise_version(current) != exercise_version(record):
        raise StoreError('This question changed. Refresh it before continuing.')
    scope = max(5, snapshot.equivalence_scopes.get(record['id'], 5))
    certificate = validate_import(root, _approval_document(record, candidate_body, scope), java=java, timeout=timeout)
    validate_approval(record, candidate_body, certificate, minimum_scope=scope)
    return certificate


def _apply_extensions(snapshot, rows):
    removed = set()
    approvals = {}
    cache_rows = []
    for row in rows:
        if type(row['ordinal']) is not int or row['ordinal'] < 0:
            raise StoreError('Invalid administrative record order.')
        kind = row['kind']
        if kind == 'studentCandidate':
            cache_rows.append(row)
            continue
        value = parse_json(row['payload'])
        if type(value) is not dict:
            raise StoreError('Invalid administrative record.')
        identifier = value.get('exerciseId')
        record = snapshot.raw_exercises.get(identifier) if type(identifier) is str else None
        if record is None or value.get('exerciseVersion') != exercise_version(record):
            # Prose editing intentionally invalidates old candidate work. Approved
            # solution witnesses bind immutable context separately and remain valid.
            if not (kind.startswith('adminApproval:') and record is not None):
                raise StoreError('Administrative record context changed.')
        if kind == 'adminRemoved':
            if (set(value) != {'exerciseId','exerciseVersion','removedAt'} or row['original_source']
                    or type(value['removedAt']) is not int or value['removedAt'] < 0
                    or identifier in removed or row['ordinal'] != snapshot.exercise_ordinals[identifier]):
                raise StoreError('Invalid removed question witness.')
            removed.add(identifier)
        else:
            if (kind != 'adminApproval:' + identifier or set(value) !=
                    {'exerciseId','exerciseVersion','candidateId','candidateHash','tokenSha256','certificate','approvedAt'}
                    or type(value['exerciseVersion']) is not str
                    or not re.fullmatch('[a-f0-9]{64}', value['exerciseVersion'])
                    or type(value['candidateId']) is not str or not re.fullmatch('[A-Za-z0-9_-]{43}', value['candidateId'])
                    or type(value['approvedAt']) is not int or value['approvedAt'] < 0
                    or sha(row['original_source']) != value['candidateHash']
                    or body_token_sha256(row['original_source']) != value['tokenSha256']):
                raise StoreError('Invalid approved candidate witness.')
            validate_approval(record, row['original_source'], value['certificate'],
                              minimum_scope=snapshot.equivalence_scopes.get(identifier, 5))
            extras = approvals.setdefault(identifier, [])
            if row['ordinal'] != len(extras):
                raise StoreError('Invalid approved predicate order.')
            if value['tokenSha256'] in snapshot.pool_tokens[identifier] or any(x[0] == value['tokenSha256'] for x in extras):
                raise StoreError('Duplicate approved predicate.')
            extras.append((value['tokenSha256'], row['original_source']))
    if sum(map(len, approvals.values())) > MAX_APPROVALS or any(len(values) > MAX_APPROVALS_PER_EXERCISE
                                                               for values in approvals.values()):
        raise StoreError('Approved predicate limit exceeded.')
    for identifier, extras in approvals.items():
        record = snapshot.raw_exercises[identifier]
        references = list(snapshot.correct_pools[identifier]) + [item[1] for item in extras]
        validate_record_fields(record, references, authored=identifier in snapshot.equivalence_scopes)
        snapshot.correct_pools[identifier] = tuple(references)
        snapshot.pool_tokens[identifier] = snapshot.pool_tokens[identifier] | frozenset(item[0] for item in extras)
    from candidate_store import validate_rows
    cached = validate_rows(cache_rows)
    if any(record['exerciseId'] not in snapshot.raw_exercises for record in cached):
        raise StoreError('Candidate belongs to an unknown question.')
    snapshot.exercises = {key: value for key, value in snapshot.raw_exercises.items() if key not in removed}
    snapshot.catalogue = dict(snapshot.catalogue, exercises=list(snapshot.exercises.values()))
    snapshot.correct_pools = {key: value for key, value in snapshot.correct_pools.items() if key not in removed}


def edit_question(root, identifier, expected_version, title, question, *, guard):
    text(title, 256, empty=False)
    text(question, 8192, empty=False)
    return _change_question(root, identifier, expected_version, title, question, guard=guard)


def remove_question(root, identifier, expected_version, *, guard):
    return _change_question(root, identifier, expected_version, None, None, guard=guard)


def _change_question(root, identifier, expected_version, title, question, *, guard):
    if type(expected_version) is not str or not re.fullmatch('[a-f0-9]{64}', expected_version):
        raise StoreError('Provide the current question version.')
    connection = connect(Path(root) / DATABASE_RELATIVE, writable=True)
    try:
        connection.execute('BEGIN IMMEDIATE')
        snapshot = _snapshot(connection)
        library_detail(snapshot, identifier, expected_version)
        if title is None:
            _insert(connection, 'auxiliary', dict(kind='adminRemoved', ordinal=snapshot.exercise_ordinals[identifier],
                    payload=encoded(dict(exerciseId=identifier, exerciseVersion=expected_version,
                                         removedAt=int(time.time() * 1000))), original_source=''))
        else:
            sql.execute(connection, 'update_question_metadata', (title, question, identifier))
        result = _snapshot(connection)
        _size_guard(connection)
        with guard():
            connection.commit()
        return result
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()


def _add_rows(connection, record, entries, ordinal, origin, validation):
    row = {column: record[key] for key, column in FIELD_MAP.items()}
    row.update(ordinal=ordinal, origin=origin, primary_token=body_token_sha256(record['oracleBody']),
               metadata=encoded({k:v for k,v in record.items() if k not in FIELD_MAP and k != 'oracleBody'}),
               validation=encoded(validation))
    _insert(connection, 'exercises', row)
    for position, item in enumerate(entries):
        _insert(connection, 'solutions', dict(exercise_id=record['id'], ordinal=position,
                kind=item['kind'], body=item['body'], original_source=item['originalSource'],
                token_hash=item['tokenSha256'], metadata=encoded({k:v for k,v in item.items()
                    if k not in ('kind','body','originalSource','tokenSha256')})))


def _publish(temporary, destination):
    safe_path(destination)
    # Exclusive hard-link publication cannot overwrite a concurrent admin's DB.
    os.link(temporary, destination)


def migrate_legacy(root, catalogue_path=None, pools_path=None):
    destination = safe_path(Path(root) / DATABASE_RELATIVE)
    if destination.exists():
        load_store(root)
        return destination
    catalogue_path = safe_path(catalogue_path or Path(root) / 'exercises/catalogue.json')
    pools_path = safe_path(pools_path or Path(root) / 'exercises/correct-pools.json')
    if max(catalogue_path.stat().st_size, pools_path.stat().st_size) > MAX_DATABASE:
        raise StoreError('Legacy data exceeds migration limits.')
    catalogue = parse_json(read_bounded(catalogue_path, MAX_DATABASE))
    pools = parse_json(read_bounded(pools_path, MAX_DATABASE))
    for record in catalogue['exercises']:
        verify_record(record)
    verify_document(catalogue, pools)
    destination.parent.mkdir(parents=True, exist_ok=True)
    descriptor, name = tempfile.mkstemp(prefix='.exercise-migration-', dir=destination.parent)
    os.close(descriptor)
    temporary = Path(name)
    try:
        connection = sqlite3.connect(temporary)
        try:
            sql.create_schema(connection)
            _insert(connection, 'metadata', dict(key='schemaVersion',value=encoded(SCHEMA_VERSION)))
            _insert(connection, 'metadata', dict(key='catalogue',value=encoded({k:v for k,v in catalogue.items() if k != 'exercises'})))
            _insert(connection, 'metadata', dict(key='pools',value=encoded({k:v for k,v in pools.items() if k not in ('pools','sourceInventory','excludedSources')})))
            by_id = {p['exerciseId']:p for p in pools['pools']}
            for index, record in enumerate(catalogue['exercises']):
                _add_rows(connection, record, by_id[record['id']]['candidates'], index, 'legacy', {'kind':'legacy-classification'})
            for kind in ('sourceInventory','excludedSources'):
                for index,item in enumerate(pools[kind]):
                    _insert(connection, 'auxiliary', dict(kind=kind,ordinal=index,
                            payload=encoded({k:v for k,v in item.items() if k != 'originalSource'}),
                            original_source=item.get('originalSource','')))
            connection.commit()
        finally:
            connection.close()
        load_store(root, temporary)
        _publish(temporary, destination)
        return destination
    finally:
        temporary.unlink(missing_ok=True)


def ensure_store(root):
    path = safe_path(Path(root) / DATABASE_RELATIVE)
    if not path.exists():
        migrate_legacy(root)
    return load_store(root)


def backup_store(root, destination, *, database_path=None):
    destination = safe_path(destination)
    if destination.exists():
        raise StoreError('Snapshot destination already exists.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    started = time.monotonic()
    def progress(status, remaining, total):
        if time.monotonic() - started >= LOCK_SECONDS:
            raise StoreError('Exercise snapshot timed out.')
    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    os.close(descriptor)
    try:
        source = connect(database_path or Path(root) / DATABASE_RELATIVE)
        target = sqlite3.connect(destination)
        try:
            source.backup(target, pages=128, progress=progress, sleep=0.02)
        finally:
            target.close()
            source.close()
        return load_store(root, destination)
    except BaseException:
        destination.unlink(missing_ok=True)
        raise


def restore_store(root, database_path, *, expected_exercises=None, expected_pools=None, expected_candidates=None):
    destination = safe_path(Path(root) / DATABASE_RELATIVE)
    if destination.exists():
        raise StoreError('Refusing to replace an existing exercise database.')
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.exercise-restore-', dir=destination.parent) as folder:
        temporary = Path(folder) / 'snapshot.sqlite3'
        result = backup_store(root, temporary, database_path=database_path)
        if (expected_exercises is not None and result.exercise_count != expected_exercises
                or expected_pools is not None and len(result.correct_pools) != expected_pools
                or expected_candidates is not None and result.candidate_count != expected_candidates):
            raise StoreError('Snapshot counts do not match the deployment manifest.')
        _publish(temporary, destination)
        return result


def normalize_import(document):
    if (type(document) is not dict or not IMPORT_REQUIRED <= set(document)
            or set(document) - IMPORT_REQUIRED - IMPORT_OPTIONAL):
        raise StoreError('Missing or unknown exercise input fields.')
    oracles = document['oracleSolutions']
    correct = document.get('correctSolutions', [])
    if type(oracles) is not list or not oracles or type(correct) is not list:
        raise StoreError('Provide a nonempty oracleSolutions list.')
    scope = document.get('equivalenceScope', 5)
    if type(scope) is not int or not 1 <= scope <= 8:
        raise StoreError('Equivalence scope must be an integer from 1 to 8.')
    record = {k:document[k] for k in IMPORT_REQUIRED if k != 'oracleSolutions'}
    validate_record_fields(record, oracles + correct, authored=True)
    hashes = [body_token_sha256(value) for value in oracles + correct]
    if len(set(hashes)) != len(hashes):
        raise StoreError('Duplicate solution bodies.')
    return record, oracles, correct, scope


def _engine_identity(classpath):
    entries = [Path(value) for value in classpath.split(os.pathsep)]
    if len(entries) != 1 + len(JAR_FILES) or [p.name for p in entries[1:]] != list(JAR_FILES):
        raise StoreError('Unregistered Alloy engine classpath.')
    classes = [(p.relative_to(entries[0]).as_posix(), sha(p.read_bytes()))
               for p in sorted(entries[0].rglob('*.class'))]
    if not classes or not any(name == 'live/ExerciseValidator.class' for name,_ in classes):
        raise StoreError('Build the exercise validator before importing.')
    return {'engineSha256':sha(encoded(classes)),
            'dependencySha256':{p.name:sha(p.read_bytes()) for p in entries[1:]}}


def validate_import(root, document, *, java='java', timeout=60):
    if type(timeout) not in (int, float) or not 0 < timeout <= 60:
        raise StoreError('Alloy validation deadline expired.')
    record, oracles, correct, scope = normalize_import(document)
    payload = dict(predicate=record['predicate'],
                   sourcePrefix=record['environmentBefore'] + record['predicateHeader'] + '{\n',
                   sourceSuffix='\n}' + record['environmentAfter'], starter=record['starter'],
                   oracleBodies=oracles, correctBodies=correct, scope=scope)
    request = json.dumps(payload)
    if len(request.encode('utf-8')) > MAX_ENGINE:
        raise StoreError('Validation request exceeds the engine limit.')
    environment = {k:v for k,v in os.environ.items() if k.upper() not in (
        'CLASSPATH','JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','JDK_JAVAC_OPTIONS')}
    classpath = runtime_classpath(Path(root))
    try:
        identity = _engine_identity(classpath)
        completed = run_engine([str(java), '-Dfile.encoding=UTF-8', '-Xmx256m',
                    '-XX:ActiveProcessorCount=2','-cp',classpath,
                    'live.ExerciseValidator'], input=request, capture_output=True,
                    text=True, encoding='utf-8', timeout=timeout, env=environment, check=False, root=root)
        result = parse_json(completed.stdout)
        if identity != _engine_identity(classpath):
            raise StoreError('Engine changed during validation.')
    except (OSError, subprocess.TimeoutExpired, UnicodeError, StoreError) as error:
        raise StoreError('Alloy validation failed or timed out; nothing was imported.') from error
    if completed.returncode or type(result) is not dict or result.get('status') != 'ok':
        code = str(result.get('code', 'validation_failed')).lower() if isinstance(result, dict) else 'validation_failed'
        if not re.fullmatch('[a-z_]{1,64}', str(code)):
            code = 'validation_failed'
        raise StoreError('Alloy rejected the import: ' + code + '. Nothing was imported.')
    expected = dict(scope=scope,bitwidth=5,maxSequence=scope,minTrace=1,maxTrace=10,
                    solver='SAT4J',oracleCount=len(oracles),correctCount=len(correct),
                    evaluatedCandidates=len(oracles)+len(correct),moduleFacts=True,factsSatisfiable=True)
    if any(type(result.get(k)) is not type(v) or result[k] != v for k,v in expected.items()):
        raise StoreError('Incomplete Alloy validation result.')
    # Do not retain unrestricted worker strings in the validation record.
    certificate = dict(kind='alloy-bounded-equivalence',version=1,result=expected,
                       environmentSha256=environment_sha256(record),
                       bodySha256=[sha(v) for v in oracles + correct],starterSha256=sha(record['starter']),
                       **identity)
    return certificate


def _validate_authored(record, entries, certificate):
    provenance = record['descriptionProvenance']
    preservation = record['preservation']
    if (record['sourceClassification'] != 'author-validated'
            or provenance not in ('Administrator supplied', 'Administrator reviewed')
            or (provenance == 'Administrator supplied' and preservation != {})
            or (provenance == 'Administrator reviewed' and (type(preservation) is not dict
                or set(preservation) != {'adminUploadSha256', 'modelId'}))):
        raise StoreError('Invalid authored source provenance.')
    if set(certificate) != {'kind','version','result','environmentSha256','bodySha256','starterSha256','engineSha256','dependencySha256'}:
        raise StoreError('Missing equivalence provenance.')
    if certificate['kind'] != 'alloy-bounded-equivalence' or type(certificate['version']) is not int or certificate['version'] != 1:
        raise StoreError('Unsupported equivalence provenance.')
    oracle_count = sum(x['kind'] == 'oracle' for x in entries)
    if (not oracle_count or any(x['kind'] != ('oracle' if i < oracle_count else 'correct-author') for i,x in enumerate(entries))
            or record['oracleBody'] != entries[0]['body']):
        raise StoreError('Invalid authored oracle order.')
    result = certificate['result']
    scope = result.get('scope')
    expected = dict(scope=scope,bitwidth=5,maxSequence=scope,minTrace=1,maxTrace=10,solver='SAT4J',
                    oracleCount=oracle_count,correctCount=len(entries)-oracle_count,
                    evaluatedCandidates=len(entries),moduleFacts=True,factsSatisfiable=True)
    if (type(scope) is not int or not 1 <= scope <= 8 or result != expected
            or any(type(result[k]) is not type(v) for k,v in expected.items())):
        raise StoreError('Invalid equivalence bounds or completion.')
    if (certificate['environmentSha256'] != environment_sha256(record)
            or certificate['bodySha256'] != [sha(x['body']) for x in entries]
            or certificate['starterSha256'] != sha(record['starter'])
            or not re.fullmatch('[a-f0-9]{64}', certificate['engineSha256'])):
        raise StoreError('Equivalence provenance changed.')
    dependencies = certificate['dependencySha256']
    if (type(dependencies) is not dict or set(dependencies) != set(JAR_FILES)
            or any(type(value) is not str or not re.fullmatch('[a-f0-9]{64}',value) for value in dependencies.values())):
        raise StoreError('Missing Alloy engine provenance.')
    for item in entries:
        expected_item = candidate(item['kind'],item['body'],environment_sha256(record),item['source'],model(record,item['body']))
        if item != expected_item or item['source'] != dict(path='private-import/' + record['id'],sha256=sha(item['originalSource']),status='author-validated'):
            raise StoreError('Authored solution witness changed.')
    if record['originalSource'] != model(record,record['oracleBody']) or record['source'] != dict(path='private-import/' + record['id'],sha256=sha(record['originalSource'])):
        raise StoreError('Authored source witness changed.')


def add_exercise(root, document, *, java='java'):
    record, oracles, correct, scope = normalize_import(document)
    certificate = validate_import(root, document, java=java)
    record.update(oracleBody=oracles[0], originalSource=model(record,oracles[0]),
                  descriptionProvenance='Administrator supplied',sourceClassification='author-validated',preservation={})
    record['source'] = dict(path='private-import/' + record['id'],sha256=sha(record['originalSource']))
    entries = [candidate('oracle' if index < len(oracles) else 'correct-author',value,
               environment_sha256(record),dict(path='private-import/' + record['id'],
               sha256=sha(model(record,value)),status='author-validated'),model(record,value))
               for index,value in enumerate(oracles+correct)]
    connection = connect(Path(root) / DATABASE_RELATIVE, writable=True)
    try:
        connection.execute('BEGIN IMMEDIATE')
        snapshot = _snapshot(connection)
        if record['id'] in snapshot.raw_exercises:
            raise StoreError('Exercise identifier already exists; no records were replaced.')
        if len(snapshot.raw_exercises) >= 10000:
            raise StoreError('Exercise limit reached.')
        _add_rows(connection,record,entries,len(snapshot.raw_exercises),'authored',certificate)
        _snapshot(connection)  # check constraints and complete logical state before commit
        # Fixed connection introspection, outside the SQLean DML grammar. Include
        # pages allocated by this transaction even when WAL has not checkpointed.
        pages = connection.execute('PRAGMA page_count').fetchone()[0]
        page_size = connection.execute('PRAGMA page_size').fetchone()[0]
        if pages * page_size > MAX_DATABASE:
            raise StoreError('Exercise database size limit reached; nothing was committed.')
        connection.commit()
        return dict(id=record['id'],oracleCount=len(oracles),correctCount=len(correct),validation=certificate['result'])
    except (sqlite3.Error, ValueError, TypeError, KeyError) as error:
        connection.rollback()
        if isinstance(error, StoreError):
            raise
        raise StoreError('Exercise import failed; nothing was committed.') from error
    finally:
        connection.close()


def read_import(path):
    path = safe_path(path)
    if not path.is_file() or path.stat().st_size > MAX_IMPORT:
        raise StoreError('Import file is missing or exceeds 2 MiB.')
    value = parse_json(read_bounded(path, MAX_IMPORT))
    normalize_import(value)
    return value


def _upload_document(record, entries, certificate):
    return {**{key: record[key] for key in IMPORT_REQUIRED if key != 'oracleSolutions'},
            'oracleSolutions': [item['body'] for item in entries if item['kind'] == 'oracle'],
            'correctSolutions': [item['body'] for item in entries if item['kind'] != 'oracle'],
            'equivalenceScope': certificate['result']['scope']}


def _validate_upload_archives(uploads, authored):
    """Bind the immutable source TEXT witness to every projected exercise."""
    bound = set()
    for witness in uploads:
        if type(witness) is not dict or type(witness.get('groups')) is not list:
            raise StoreError('Invalid upload archive.')
        documents = []
        for group in witness['groups']:
            identifier = group.get('exerciseId') if type(group) is dict else None
            if type(identifier) is not str or identifier in bound or identifier not in authored:
                raise StoreError('Missing or repeated uploaded exercise.')
            record, entries, certificate = authored[identifier]
            if record['preservation'] != {'adminUploadSha256': witness.get('sourceSha256'),
                                           'modelId': witness.get('modelId')}:
                raise StoreError('Upload archive binding changed.')
            documents.append(_upload_document(record, entries, certificate))
            bound.add(identifier)
        # Lazy import avoids a store/parser import cycle. This verifier is pure:
        # no solver/provider/file reads occur when loading a committed snapshot.
        from admin_upload import validate_witness
        validate_witness(witness, documents)
    for identifier, (record, _, _) in authored.items():
        if identifier not in bound and record['preservation'] != {}:
            raise StoreError('Missing upload source witness.')


def commit_upload(root, prepared, metadata, *, guard):
    """Publish an already validated, backend-owned upload as one guarded batch.

    This is an internal capability: HTTP callers never provide `prepared` or its
    certificates. Only reviewed public prose enters through `metadata`.
    """
    from admin_upload import validate_witness
    if (type(prepared) is not dict or set(prepared) != {'documents','certificates','witness'}
            or type(prepared['documents']) is not list
            or not 1 <= len(prepared['documents']) <= 8
            or type(prepared['certificates']) is not list
            or len(prepared['certificates']) != len(prepared['documents'])):
        raise StoreError('Incomplete upload validation.')
    documents = prepared['documents']
    validate_witness(prepared['witness'], documents)
    if type(metadata) is not list or len(metadata) != len(documents):
        raise StoreError('Review every exercise before publishing.')
    by_name = {}
    for item in metadata:
        if (type(item) is not dict or set(item) != {'predicate','title','question'}
                or type(item['predicate']) is not str or item['predicate'] in by_name):
            raise StoreError('Invalid reviewed exercise metadata.')
        text(item['title'], 256, empty=False)
        text(item['question'], 8192, empty=False)
        by_name[item['predicate']] = item
    if set(by_name) != {doc['predicate'] for doc in documents}:
        raise StoreError('Exercise names cannot change during review.')
    batch = []
    for doc, certificate in zip(documents, prepared['certificates']):
        public = by_name[doc['predicate']]
        doc = dict(doc, title=public['title'], description=public['question'])
        record, oracles, correct, _ = normalize_import(doc)
        record.update(oracleBody=oracles[0], originalSource=model(record,oracles[0]),
                      descriptionProvenance='Administrator reviewed',
                      sourceClassification='author-validated',
                      preservation={'adminUploadSha256': prepared['witness']['sourceSha256'],
                                    'modelId': prepared['witness']['modelId']})
        record['source'] = dict(path='private-import/' + record['id'],sha256=sha(record['originalSource']))
        entries = [candidate('oracle' if index < len(oracles) else 'correct-author', value,
                   environment_sha256(record), dict(path='private-import/' + record['id'],
                   sha256=sha(model(record,value)),status='author-validated'), model(record,value))
                   for index,value in enumerate(oracles + correct)]
        _validate_authored(record, entries, certificate)
        batch.append((record, entries, certificate))
    connection = connect(Path(root) / DATABASE_RELATIVE, writable=True)
    try:
        connection.execute('BEGIN IMMEDIATE')
        snapshot = _snapshot(connection)
        identifiers = [record['id'] for record,_,_ in batch]
        if len(set(identifiers)) != len(identifiers) or set(identifiers) & snapshot.raw_exercises.keys():
            raise StoreError('An exercise identifier already exists; no records were replaced.')
        if len(snapshot.raw_exercises) + len(batch) > 10000:
            raise StoreError('Exercise limit reached.')
        for index, (record, entries, certificate) in enumerate(batch):
            _add_rows(connection,record,entries,len(snapshot.raw_exercises)+index,'authored',certificate)
        witness = prepared['witness']
        _insert(connection, 'auxiliary', dict(kind='adminUpload',ordinal=len(snapshot.admin_uploads),
                payload=encoded({key:value for key,value in witness.items() if key != 'originalSource'}),
                original_source=witness['originalSource']))
        result = _snapshot(connection)
        if (connection.execute('PRAGMA page_count').fetchone()[0]
                * connection.execute('PRAGMA page_size').fetchone()[0] > MAX_DATABASE):
            raise StoreError('Exercise database size limit reached.')
        with guard():
            connection.commit()
        return result
    except BaseException as error:
        connection.rollback()
        if isinstance(error, (sqlite3.Error, ValueError, TypeError, KeyError)) and not isinstance(error, StoreError):
            raise StoreError('Upload publication failed; nothing was committed.') from error
        raise
    finally:
        connection.close()
