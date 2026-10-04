#!/usr/bin/env python3
"""Check CI preparation evidence; optionally reproduce only the missing-Lean skip."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


HERE = Path(__file__).resolve().parent
REPOSITORY = HERE.parents[3]


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify():
    raw = (HERE / 'index.json').read_bytes()
    require(sha(raw) == (HERE / 'index.sha256').read_text().split()[0], 'Index hash mismatch')
    index = json.loads(raw)
    files = index['files']
    actual = {path.relative_to(HERE).as_posix() for path in HERE.rglob('*') if path.is_file()}
    require(actual == set(files) | {'index.json', 'index.sha256'}, 'Missing or unindexed file')
    require(not any(path.is_symlink() for path in HERE.rglob('*')), 'Linked archive file')
    for name, record in files.items():
        path = Path(name)
        require(not path.is_absolute() and '..' not in path.parts, 'Unsafe archive path')
        data = (HERE / path).read_bytes()
        require(len(data) == record['bytes'] and sha(data) == record['sha256'], 'Payload hash mismatch: ' + name)
    report = json.loads((HERE / 'artifacts/missing-toolchain-report.json').read_bytes())
    require(report['tests_run'] == 1 and report['successful'] is False
            and list(report['outcomes'].values()) == ['BLOCK'], 'Unexpected witness result')
    execution = json.loads((HERE / 'artifacts/prepare-execution.json').read_bytes())
    require(execution['status'] == 'PASS' and execution['exitCode'] == 0,
            'Provisioning shell did not pass')
    lines = (HERE / 'after/workflow.yml').read_text().splitlines(keepends=True)
    starts = [i for i, line in enumerate(lines)
              if 'Prepare pinned Lean before offline proof checks' in line]
    require(len(starts) == 1, 'Preparation workflow step missing or duplicated')
    body = []
    for line in lines[starts[0] + 2:]:
        if not line.startswith('          '):
            break
        body.append(line[10:])
    require((HERE / 'artifacts/prepare-lean.sh').read_text()
            == 'set -euo pipefail\n' + ''.join(body),
            'Executed preparation body differs from workflow snapshot')
    before = json.loads((HERE / 'artifacts/inputs.json').read_bytes())
    require(sha((HERE / 'before/workflow.yml').read_bytes()) == before['.github/workflows/ci.yml'],
            'Before-workflow binding mismatch')
    return {'status': 'PASS', 'scope': 'archive integrity', 'files': len(files),
            'indexSha256': sha(raw), 'closureClaim': False}


def reproduce(scratch):
    scratch = scratch.absolute()
    owned = REPOSITORY / 'build/trf-closure'
    require(owned in scratch.parents and not scratch.exists(),
            'Replay needs a new directory below repository build/trf-closure')
    require(not any(path.is_symlink() for path in [scratch, *scratch.parents]),
            'Linked replay destination')
    bindings = json.loads((HERE / 'replay-inputs.json').read_bytes())
    for name, expected in bindings.items():
        require(sha((REPOSITORY / name).read_bytes()) == expected,
                'Repository replay input changed: ' + name)
    code = (HERE / 'artifacts/reproduce.py').read_text()
    old = "WORK = ROOT / 'build/trf-closure/ci-lean-provisioning-20261004'"
    require(code.count(old) == 1, 'Original witness scratch assignment changed')
    # Change only the output destination. Test and required-test runner bytes,
    # the empty-ELAN_HOME condition, and narrowed discovery remain unchanged.
    code = code.replace(old, 'WORK = Path(sys.argv[1]).resolve()', 1)
    scratch.mkdir(parents=True)
    result = subprocess.run([sys.executable, '-I', '-S', '-B', '-c', code, str(scratch)],
                            cwd=REPOSITORY, capture_output=True, text=True, timeout=30)
    (scratch / 'replay.stdout').write_text(result.stdout)
    (scratch / 'replay.stderr').write_text(result.stderr)
    require(result.returncode == 0, 'Witness reproduction failed; inspect owned scratch logs')
    print(result.stdout, end='')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reproduce', action='store_true')
    parser.add_argument('--scratch', type=Path)
    args = parser.parse_args()
    require(args.reproduce == bool(args.scratch), '--reproduce and --scratch are required together')
    print(json.dumps(verify(), sort_keys=True))
    if args.reproduce:
        reproduce(args.scratch)


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(json.dumps({'status': 'FAIL', 'error': str(error), 'closureClaim': False}), file=sys.stderr)
        sys.exit(1)
