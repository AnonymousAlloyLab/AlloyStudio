"""The corpus gate cannot turn missing/mismatched/failing evidence into a pass."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('worker_equivalence', ROOT / 'scripts/check_worker_equivalence.py')
runner = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runner)


class HarnessTests(unittest.TestCase):
    def test_difference_report_contains_paths_without_private_values(self):
        result = runner.diff_paths({'x': {'value': 'SECRET_ONE'}}, {'x': {'value': 'SECRET_TWO'}})
        self.assertEqual(result, ['$.x.value'])
        self.assertNotIn('SECRET', json.dumps(result))
        self.assertEqual(runner.diff_paths([1], [True]), ['$[0]:type'])

    def test_aggregate_requires_complete_matching_nonfailure_stable_evidence(self):
        row = {'case': 'e/starter/canonical', 'metric': 'canonical', 'outcome': 'MATCH',
               'freshStatus': 'ok', 'warmStatus': 'ok', 'freshSeconds': .2, 'warmSeconds': .1,
               'serverSourceFileSha256': 'synthetic'}
        self.assertEqual(runner.aggregate({}, [row], 1, {}, True)['status'], 'PASS')
        for outcome, count, stable in (('MISMATCH', 1, True), ('UNRESOLVED', 1, True),
                                        ('MATCH', 2, True), ('MATCH', 1, False)):
            changed = dict(row, outcome=outcome)
            self.assertEqual(runner.aggregate({}, [changed], count, {}, stable)['status'], 'FAIL')

    def test_qualified_source_hash_ignores_unrelated_line_movement(self):
        scratch = ROOT / 'build' / 'traffic-http-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as name:
            path = Path(name) / 'target.py'
            declaration = 'class A:\n    def calculate(self, x):\n        return x + 1\n'
            path.write_text(declaration)
            original = runner.qualified_source(path, 'A.calculate')
            path.write_text('def unrelated():\n    return 0\n\n' + declaration)
            self.assertEqual(runner.qualified_source(path, 'A.calculate'), original)
            path.write_text(declaration.replace('x + 1', 'x + 2'))
            self.assertNotEqual(runner.qualified_source(path, 'A.calculate'), original)
            path.write_text(declaration + declaration)
            with self.assertRaises(ValueError):
                runner.qualified_source(path, 'A.calculate')

    def test_checkpoint_refuses_changed_identity_input_or_order(self):
        scratch = ROOT / 'build' / 'traffic-http-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as name:
            path = Path(name) / 'checkpoint.jsonl'
            identity = {'synthetic': 1}
            cases = [('case', None, None, None, {'input': 1})]
            row = {'case': 'case', 'inputSha256': runner.sha(runner.encoded({'input': 1}))}
            def write(header, value):
                path.write_bytes(runner.encoded({'type': 'manifest', 'identity': header}) + b'\n'
                                 + runner.encoded(value) + b'\n')
            write(identity, row)
            self.assertEqual(runner.load_checkpoint(path, identity, cases), [row])
            for header, value in (({'synthetic': 2}, row), (identity, dict(row, case='other')),
                                  (identity, dict(row, inputSha256='changed'))):
                write(header, value)
                with self.assertRaises(ValueError):
                    runner.load_checkpoint(path, identity, cases)


if __name__ == '__main__':
    unittest.main()
