#!/usr/bin/env python3
"""Verify preserved Windows-refusal regression evidence without executing tests."""
import hashlib
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parent


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(name):
    return json.loads((ROOT / name).read_bytes())


def verify():
    raw = (ROOT / 'index.json').read_bytes()
    require(digest(raw) == (ROOT / 'index.sha256').read_text().split()[0], 'Index digest mismatch')
    index = json.loads(raw)
    require(index['kind'] == 'windows-refusal-regression-evidence' and index['closureClaim'] is False,
            'Unexpected evidence scope')
    files = index['files']
    actual = {path.relative_to(ROOT).as_posix() for path in ROOT.rglob('*') if path.is_file()}
    require(actual == set(files) | {'index.json', 'index.sha256'}, 'Missing or unindexed archive file')
    require(not any(path.is_symlink() for path in ROOT.rglob('*')), 'Linked archive file')
    for name, record in files.items():
        path = Path(name)
        require(not path.is_absolute() and '..' not in path.parts, 'Unsafe archive path')
        data = (ROOT / path).read_bytes()
        require(digest(data) == record['sha256'] and len(data) == record['bytes'],
                'Payload digest mismatch: ' + name)
    job = read_json('ci/windows-job-111517882855.json')
    run = read_json('ci/run-37230133497.json')
    require(job['id'] == 111517882855 and job['run_id'] == 37230133497
            and job['conclusion'] == 'failure', 'Wrong failed native job')
    require(run['id'] == 37230133497
            and run['head_sha'] == 'b35aa68d31167e7311635cca75a62dda1c6be78f', 'Wrong failed revision')
    require(job['head_sha'] == run['head_sha'], 'Job/run revision mismatch')
    earlier = read_json('ci/prior-ad6-run-37228525806.json')
    require(earlier['headSha'] == 'ad6ea7ccc40f04d8022063fcf6012e6aef7fb3da', 'Wrong earlier revision')
    windows = [entry for entry in earlier['jobs'] if entry['databaseId'] == 111513106171]
    require(len(windows) == 1 and windows[0]['conclusion'] == 'success', 'Earlier Windows pass is absent')
    before = (ROOT / 'before/b35-test_ingress_admission.py').read_bytes()
    require(before == (ROOT / 'before/ad6-test_ingress_admission.py').read_bytes(),
            'Earlier/later native job test bytes differ')
    require(digest(before) == index['bindings']['beforeTestSha256'], 'Before-test binding mismatch')
    require(digest((ROOT / 'after/test_ingress_admission.py').read_bytes())
            == index['bindings']['afterTestSha256'], 'After-test binding mismatch')
    require(digest((ROOT / 'contract/admission-contract.md').read_bytes())
            == index['bindings']['existingContractSha256'], 'Contract binding mismatch')
    freeze = read_json('local/new-freeze.json')
    old = read_json('local/before/block.json')
    new = read_json('after/block.json')
    require(digest((ROOT / 'local/before/block.json').read_bytes()) == freeze['oldBlockSha256']
            and digest((ROOT / 'after/block.json').read_bytes()) == freeze['newBlockSha256'],
            'Frozen manifest binding mismatch')
    changed = sorted(name for name in set(old['inputs']) | set(new['inputs'])
                     if old['inputs'].get(name) != new['inputs'].get(name))
    require(len(old['inputs']) == len(new['inputs']) == 511
            and changed == freeze['changedFrozenInputs'] == ['tests/test_ingress_admission.py'],
            'Unexpected frozen-input change')
    require(new['inputs']['tests/test_ingress_admission.py'] == index['bindings']['afterTestSha256']
            and old['inputs']['tests/test_ingress_admission.py'] == index['bindings']['beforeTestSha256'],
            'Frozen test binding mismatch')
    spec = read_json('local/spec-first.json')
    for entry in spec['inputs'].values():
        require(digest((ROOT / 'local/before' / Path(entry['snapshot']).name).read_bytes())
                == entry['sha256'], 'Spec-first input snapshot mismatch')
    result = read_json('local/result.json')
    require(result['status'] == 'PASS'
            and result['before_sha256'] == index['bindings']['beforeTestSha256']
            and result['after_sha256'] == index['bindings']['afterTestSha256'],
            'Local witness source binding mismatch')
    controls = read_json('local/negative-controls.json')
    require(controls['status'] == 'PASS' and len(controls['controls']) == 10
            and all(item['status'] == 'PASS' for item in controls['controls']),
            'Local control result mismatch')
    failure = (ROOT / 'ci/windows-b35aa68-failure.txt').read_text()
    require('test_enabled_pair_actual_saturation_control_health_and_limits' in failure
            and 'ConnectionAbortedError: [WinError 10053]' in failure, 'Native failure record missing')
    print(json.dumps({'status': 'PASS', 'scope': 'archive integrity and recorded source/CI bindings',
                      'payloadFiles': len(files), 'indexSha256': digest(raw), 'closureClaim': False},
                     sort_keys=True))


if __name__ == '__main__':
    try:
        verify()
    except (OSError, ValueError, KeyError) as error:
        print(json.dumps({'status': 'FAIL', 'error': str(error), 'closureClaim': False}), file=sys.stderr)
        sys.exit(1)
