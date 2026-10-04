#!/usr/bin/env python3
"""Validate archived bytes and materialize an explicitly recorded regression input set."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[3]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def safe_path(root, name):
    path = PurePosixPath(name)
    if path.is_absolute() or not path.parts or '..' in path.parts:
        raise ValueError('Unsafe archive path')
    if any(part in {'.git', 'secrets', 'admin.local.json', 'openai.local.json'}
           or part.startswith('.env') for part in path.parts):
        raise ValueError('Private input is forbidden')
    result = root
    for part in path.parts:
        result = result / part
        if result.is_symlink():
            raise ValueError('Linked archive path')
    return result


def input_manifest(index, run):
    spec = index['runs'][run]
    if 'replayInputSet' in spec:
        return input_manifest(index, spec['replayInputSet'])
    artifact = json.loads(safe_path(HERE, spec['manifestArtifact']).read_bytes())
    for field in spec.get('manifestFields', []):
        artifact = artifact[field]
    if not isinstance(artifact, dict):
        raise ValueError('Invalid input manifest')
    return artifact


def source_for(index, digest):
    item = index['objects'][digest]
    if item['kind'] == 'historical-evidence':
        if not item['path'].startswith('closure/traffic-refinement/evidence/'):
            raise ValueError('Historical input is outside the evidence registry')
        return safe_path(REPOSITORY, item['path'])
    if item['kind'] == 'local-object':
        if item['path'] != 'objects/' + digest:
            raise ValueError('Unexpected object location')
        return safe_path(HERE, item['path'])
    raise ValueError('Unknown input binding')


def verify():
    raw = (HERE / 'index.json').read_bytes()
    expected = (HERE / 'index.sha256').read_text().split()[0]
    if sha(raw) != expected:
        raise ValueError('Index checksum mismatch')
    index = json.loads(raw)
    if index['kind'] != 'finite-regression-preservation' or index['schemaVersion'] != 1:
        raise ValueError('Unexpected index schema')
    expected_files = set(index['artifacts']) | {'index.json', 'index.sha256'}
    expected_files.update(item['path'] for item in index['objects'].values()
                          if item['kind'] == 'local-object')
    actual_files = {path.relative_to(HERE).as_posix() for path in HERE.rglob('*')
                    if path.is_file()}
    if expected_files != actual_files or any(path.is_symlink() for path in HERE.rglob('*')):
        raise ValueError('Missing, unindexed, or linked archive file')
    for name, record in index['artifacts'].items():
        data = safe_path(HERE, name).read_bytes()
        if sha(data) != record['sha256'] or len(data) != record['bytes']:
            raise ValueError('Artifact checksum mismatch: ' + name)
    for digest, item in index['objects'].items():
        if not re.fullmatch('[0-9a-f]{64}', digest):
            raise ValueError('Invalid content digest')
        data = source_for(index, digest).read_bytes()
        if sha(data) != digest or len(data) != item['bytes']:
            raise ValueError('Input checksum mismatch: ' + digest)
    for run in index['runs']:
        for name, digest in input_manifest(index, run).items():
            safe_path(Path('/nonexistent-archive-validation-root'), name)
            if digest not in index['objects']:
                raise ValueError('Unbound input: ' + name)
    final = input_manifest(index, 'python-final')
    report = json.loads(safe_path(HERE, 'artifacts/python-final/report.json').read_bytes())
    if report['status'] != 'PASS' or report['changedInputPaths'] != [] or report['exitCode'] != 0:
        raise ValueError('Final Python report did not pass unchanged')
    for name, field in [('inputs.json', 'inputManifestSha256'), ('unittest.txt', 'logSha256')]:
        if sha(safe_path(HERE, 'artifacts/python-final/' + name).read_bytes()) != report[field]:
            raise ValueError('Final Python report binding mismatch')
    if report['tests'] != index['runs']['python-final']['tests']:
        raise ValueError('Final Python test count mismatch')
    browser = json.loads(safe_path(HERE, 'artifacts/browser/report.json').read_bytes())
    functional = json.loads(safe_path(HERE, 'artifacts/functional/report.json').read_bytes())
    runtime = json.loads(safe_path(HERE, 'artifacts/runtime/report.json').read_bytes())
    if any(record['status'] != 'PASS' for record in [browser, functional, runtime]):
        raise ValueError('A preserved finite regression did not pass')
    for subset in [browser['sourceFiles'], functional['sources'],
                   {item['path']: item['sha256'] for item in runtime['classes'] + runtime['dependencies']}]:
        if any(final.get(name) != digest for name, digest in subset.items()):
            raise ValueError('Regression input subsets disagree')
    for record, field in [(browser, 'sourceFiles'), (functional, 'sources')]:
        canonical = json.dumps(record[field], sort_keys=True, separators=(',', ':')).encode()
        if sha(canonical) != record['sourceIdentitySha256']:
            raise ValueError('Regression source identity mismatch')
    return index


def materialize(index, run, output):
    # A new destination prevents overwriting a checkout or older evidence.
    if output.exists() or output.is_symlink():
        raise ValueError('Replay destination must not exist')
    for parent in output.parents:
        if parent.is_symlink():
            raise ValueError('Linked replay destination')
    inputs = input_manifest(index, run)
    output.mkdir(parents=True)
    for name, digest in inputs.items():
        destination = safe_path(output, name)
        destination.parent.mkdir(parents=True, exist_ok=True)
        data = source_for(index, digest).read_bytes()
        if sha(data) != digest:
            raise ValueError('Input changed during materialization')
        destination.write_bytes(data)
        destination.chmod(0o755 if destination.suffix in {'.sh', '.py'} else 0o644)
    return len(inputs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', choices=['python-final', 'functional', 'browser', 'runtime'])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if bool(args.run) != bool(args.output):
        parser.error('--run and --output must be supplied together')
    index = verify()
    count = materialize(index, args.run, args.output.absolute()) if args.run else None
    print(json.dumps({'status': 'PASS', 'artifacts': len(index['artifacts']),
                      'objects': len(index['objects']), 'materializedInputs': count}))


if __name__ == '__main__':
    main()
