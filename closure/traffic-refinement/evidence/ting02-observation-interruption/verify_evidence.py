#!/usr/bin/env python3
"""Check this archive's byte integrity and optionally replay its local witness."""
import argparse
import hashlib
import inspect
import json
import platform
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import threading


ROOT = Path(__file__).resolve().parent


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_json(path):
    return json.loads(path.read_bytes())


def check_integrity():
    index = read_json(ROOT / 'evidence-index.json')
    require(index['schemaVersion'] == 1, 'unsupported index schema')
    require(index['kind'] == 'local-regression-evidence-archive', 'unexpected archive kind')
    entries = index['files']
    paths = [entry['path'] for entry in entries]
    require(paths == sorted(set(paths)), 'file list is not sorted and unique')
    actual = sorted(str(path.relative_to(ROOT)) for path in ROOT.rglob('*')
                    if path.is_file() and path != ROOT / 'evidence-index.json')
    require(actual == paths, 'missing or unindexed archive files')
    require(not any(path.is_symlink() for path in ROOT.rglob('*')), 'symlink in archive')
    for entry in entries:
        relative = Path(entry['path'])
        require(not relative.is_absolute() and '..' not in relative.parts,
                'unsafe index path')
        path = ROOT / relative
        require(path.stat().st_size == entry['bytes'], 'size mismatch: ' + entry['path'])
        require(digest(path) == entry['sha256'], 'hash mismatch: ' + entry['path'])
    canonical = json.dumps(entries, sort_keys=True, separators=(',', ':')).encode()
    require(hashlib.sha256(canonical).hexdigest() == index['payloadRootSha256'],
            'payload root mismatch')
    for phase, source in [('before', 'traffic_http.py'),
                          ('after', 'after-source/traffic_http.py')]:
        require(read_json(ROOT / (phase + '.json'))['sourceSha256'] == digest(ROOT / source),
                phase + ' result/source mismatch')
    freeze = read_json(ROOT / 'spec-first/freeze.json')
    require(freeze['productionBeforeRepairSha256'] == digest(ROOT / 'traffic_http.py'),
            'freeze/before-source mismatch')
    for original, archived in [
            ('closure/traffic-refinement/admission-spec.json', 'admission-spec.json'),
            ('docs/admission-contract.md', 'admission-contract.md')]:
        require(freeze['inputs'][original] == digest(ROOT / 'spec-first' / archived),
                'specification freeze mismatch: ' + archived)
    proof = ROOT / 'spec-first/preimplementation-proof'
    for path, expected in read_json(proof / 'inputs.json').items():
        require(digest(proof / path) == expected, 'preimplementation input mismatch: ' + path)
    block = read_json(ROOT / 'block.json')
    for name in ['traffic_profile.py', 'traffic_limits.py']:
        require(digest(ROOT / 'replay-support' / name) == block['inputs'][name],
                'manifest/replay dependency mismatch: ' + name)
    return {'check': 'archive_integrity', 'status': 'PASS', 'files': len(entries),
            'indexSha256': digest(ROOT / 'evidence-index.json'),
            'payloadRootSha256': index['payloadRootSha256'],
            'closureClaim': False}


def replay(scratch):
    runtime = read_json(ROOT / 'runtime.json')
    require(platform.python_implementation() == runtime['implementation'],
            'replay requires the recorded CPython runtime')
    require(sys.version.split()[0] == runtime['python'],
            'replay requires recorded Python ' + runtime['python'])
    helper = inspect.getsource(threading.Thread._wait_for_tstate_lock).encode()
    require(hashlib.sha256(helper).hexdigest() == runtime['helperSourceSha256'],
            'replay requires the recorded thread-status helper')
    scratch = scratch.resolve()
    require(scratch.is_dir(), 'scratch directory must already exist')
    require(scratch != ROOT and ROOT not in scratch.parents,
            'scratch must be outside the immutable archive')
    require(('build', 'trf-closure') in list(zip(scratch.parts, scratch.parts[1:])),
            'scratch must be under build/trf-closure')
    with tempfile.TemporaryDirectory(prefix='observation-replay-', dir=scratch) as temp:
        base = Path(temp)
        # Recreate the original three-level witness location, so its ROOT is
        # this isolated directory and never the live repository.
        fixture = base / 'build/trf-closure/witness'
        fixture.mkdir(parents=True)
        shutil.copyfile(ROOT / 'witness.py', fixture / 'witness.py')
        for name in ['traffic_profile.py', 'traffic_limits.py']:
            shutil.copyfile(ROOT / 'replay-support' / name, base / name)
        for phase, source in [('before', 'traffic_http.py'),
                              ('after', 'after-source/traffic_http.py')]:
            target = fixture / (phase + '-traffic_http.py')
            shutil.copyfile(ROOT / source, target)
            completed = subprocess.run(
                [sys.executable, '-I', '-S', '-B', str(fixture / 'witness.py'), str(target)],
                cwd=base, capture_output=True, text=True, timeout=20)
            require(completed.returncode == 0,
                    phase + ' replay failed: ' + completed.stderr)
            require(not completed.stderr, phase + ' replay wrote unexpected stderr')
            actual = json.loads(completed.stdout)
            require(actual == read_json(ROOT / (phase + '.json')),
                    phase + ' replay differs from preserved result: ' + completed.stdout)
            print(json.dumps({'check': 'isolated_local_replay', 'phase': phase,
                              'status': 'PASS', 'result': actual,
                              'closureClaim': False}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--replay', action='store_true',
                        help='also replay both snapshots on the recorded CPython helper')
    parser.add_argument('--scratch', type=Path,
                        help='existing build/trf-closure directory for isolated temporary files')
    args = parser.parse_args()
    require(not args.replay or args.scratch is not None, '--replay requires --scratch')
    print(json.dumps(check_integrity(), sort_keys=True))
    if args.replay:
        replay(args.scratch)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(json.dumps({'check': 'archive_verification', 'status': 'FAIL',
                          'error': str(error), 'closureClaim': False}, sort_keys=True),
              file=sys.stderr)
        sys.exit(1)
