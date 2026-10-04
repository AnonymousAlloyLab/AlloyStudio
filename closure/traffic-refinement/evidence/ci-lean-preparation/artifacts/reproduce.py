"""Run only the existing Lean mutant test with an empty owned ELAN_HOME."""
import contextlib
import io
import json
import os
from pathlib import Path
import sys
import unittest
from unittest.mock import patch


ROOT = Path.cwd()
WORK = ROOT / 'build/trf-closure/ci-lean-provisioning-20261004'
EMPTY_ELAN = WORK / 'empty-elan'
EMPTY_ELAN.mkdir(exist_ok=True)
assert not any(EMPTY_ELAN.iterdir())
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts'), str(ROOT / 'tests')]
import verify_closure
from lean_offline import installed_toolchain

TEST_ID = ('test_admission_bridge.AdmissionBridgeTests.'
           'test_translated_mutants_fail_independent_lean_obligations_offline')
existing_pin, existing_toolchain = installed_toolchain(ROOT)
out = io.StringIO()
with patch.dict(os.environ, {'ELAN_HOME': str(EMPTY_ELAN),
                            'OPENAI_DISABLED': '1', 'PYTHONDONTWRITEBYTECODE': '1'}):
    try:
        installed_toolchain(ROOT)
    except FileNotFoundError as error:
        missing_reason = str(error)
    else:
        raise AssertionError('Empty ELAN_HOME unexpectedly found a toolchain')
    suite = unittest.defaultTestLoader.loadTestsFromName(TEST_ID)
    assert suite.countTestCases() == 1
    # Narrow discovery in this scratch process only; exercise the unchanged
    # production report function and the unchanged real regression test.
    with patch.object(unittest.defaultTestLoader, 'discover', return_value=suite):
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(out):
            return_code = verify_closure.python_tests(WORK / 'missing-toolchain-report.json')
(WORK / 'missing-toolchain-log.txt').write_text(out.getvalue())
report = json.loads((WORK / 'missing-toolchain-report.json').read_bytes())
assert return_code == 1
assert report == {'tests_run': 1, 'outcomes': {TEST_ID: 'BLOCK'}, 'successful': False}
assert 'skipped' in out.getvalue() and 'Pinned Lean unavailable' in out.getvalue()
result = {'exitCode': return_code, 'report': report, 'missingReason': missing_reason,
          'testId': TEST_ID, 'elanHomeOverride': str(EMPTY_ELAN.relative_to(ROOT)),
          'ordinaryUnittestSummary': 'OK (skipped=1)',
          'existingToolchain': str(existing_toolchain), 'pin': existing_pin,
          'method': 'Only discovery is narrowed in the scratch process; test and report code are unchanged.'}
(WORK / 'reproduction.json').write_text(json.dumps(result,indent=2,sort_keys=True)+'\n')
print(json.dumps(result,sort_keys=True))
