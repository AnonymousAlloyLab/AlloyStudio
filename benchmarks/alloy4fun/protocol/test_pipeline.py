"""Independent checks of cohort extraction, crossfit isolation and worker deadlines."""
from __future__ import annotations

from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.prepare import extract_one
from benchmarks.alloy4fun.run_live import pool_key, reference_pools
from benchmarks.alloy4fun.live import adapter


def payload(cid, body, *, fold_status='CORRECT', environment='environment', oracle='teacher'):
    return {'case_id': cid, 'model_id': cid, 'group': 'family', 'predicate': 'inv1',
            'cohort_status': fold_status, 'extraction_status': 'ok',
            'environment_sha256': environment, 'oracle_token_sha256': oracle,
            'oracle_body': 'teacher', 'body_token_sha256': body, 'body': body}


class PoolIsolationTests(unittest.TestCase):
    def test_correct_pool_excludes_entire_query_fold_and_keeps_oracle(self):
        learner = payload('query', 'incorrect', fold_status='BOTH')
        same_path = payload('same-path', 'held-out-correct')
        training = payload('training', 'training-correct')
        duplicate_teacher = payload('teacher-copy', 'teacher')
        incompatible = payload('other-environment', 'wrong-context', environment='other')
        cases = [learner, same_path, training, duplicate_teacher, incompatible]
        folds = {'query': 2, 'same-path': 2, 'training': 3, 'teacher-copy': 1, 'other-environment': 1}
        pools = reference_pools(cases, folds)
        self.assertEqual(pools[(*pool_key(learner), 2)], ['training-correct', 'teacher'])

    def test_same_formula_in_training_paths_is_valid_but_duplicate_bodies_are_collapsed(self):
        learner = payload('query', 'body', fold_status='BOTH')
        a, b = payload('a', 'correct'), payload('b', 'correct')
        cases = [learner, a, b]
        pools = reference_pools(cases, {'query': 0, 'a': 1, 'b': 2})
        self.assertEqual(pools[(*pool_key(learner), 0)], ['correct', 'teacher'])

    def test_no_training_truth_yields_explicit_teacher(self):
        learner = payload('query', 'incorrect', fold_status='BOTH')
        pools = reference_pools([learner], {'query': 0})
        self.assertEqual(pools[(*pool_key(learner), 0)], ['teacher'])

    def test_oracle_identity_is_part_of_pool_isolation(self):
        learner = payload('query', 'incorrect', fold_status='BOTH')
        alien = payload('alien', 'different-solution', oracle='other-teacher')
        pools = reference_pools([learner, alien], {'query': 0, 'alien': 1})
        self.assertEqual(pools[(*pool_key(learner), 0)], ['teacher'])

    def test_extraction_failure_keeps_case_and_excludes_it_from_truth_pool(self):
        learner = payload('query', 'incorrect', fold_status='BOTH')
        broken = {'case_id': 'broken', 'model_id': 'broken', 'extraction_status': 'ValueError',
                  'cohort_status': 'CORRECT'}
        pools = reference_pools([learner, broken], {'query': 0, 'broken': 1})
        self.assertEqual(pools[(*pool_key(learner), 0)], ['teacher'])


class ExtractionTests(unittest.TestCase):
    def test_extraction_error_remains_eligible_record(self):
        with tempfile.TemporaryDirectory() as temp:
            relative = 'family/both/m_inv1.als'
            path = Path(temp, relative)
            path.parent.mkdir(parents=True)
            path.write_text('not a model')
            record, data = extract_one((temp, relative, False))
            self.assertTrue(record['eligible'])
            self.assertEqual(record['cohort_status'], 'BOTH')
            self.assertNotEqual(record['extraction_status'], 'ok')
            self.assertEqual(data['case_id'], relative)
            self.assertIn('extraction_error', data)

    def test_inherited_exclusion_is_recorded_even_for_invalid_file(self):
        with tempfile.TemporaryDirectory() as temp:
            relative = 'family/correct/m_inv1.als'
            path = Path(temp, relative)
            path.parent.mkdir(parents=True)
            path.write_text('not a model')
            record, data = extract_one((temp, relative, True))
            self.assertFalse(record['eligible'])
            self.assertIsNone(data)
            self.assertEqual(len(record['source_sha256']), 64)


class ResponseMetricTests(unittest.TestCase):
    def test_aggregate_only_notice_is_not_native_hint_hit(self):
        result = adapter.metrics({'status': 'ok', 'operations': [{'aggregate': True}]}, 0.2)
        self.assertFalse(result['hint_available'])
        self.assertEqual(result['operation_count'], 0)
        self.assertEqual(result['aggregate_operation_count'], 1)

    def test_exact_node_location_is_counted_separately_from_context(self):
        operations = [
            {'sourceLocation': {'status': 'located', 'precision': 'node'}},
            {'sourceLocation': {'status': 'located', 'precision': 'context'}},
            {'sourceLocation': {'status': 'unavailable', 'precision': 'node'}},
        ]
        result = adapter.metrics({'status': 'ok', 'operations': operations}, .3)
        self.assertEqual(result['operation_count'], 3)
        self.assertEqual(result['located_operations'], 1)

    def test_timeout_cannot_be_hint_success(self):
        result = adapter.metrics({'status': 'timeout'}, 60.1)
        self.assertFalse(result['hint_available'])
        self.assertTrue(result['timed_out'])


@unittest.skipUnless(sys.platform.startswith('linux'), 'POSIX worker process-group transport')
class WorkerDeadlineTests(unittest.TestCase):
    def test_worker_recycling_removes_only_its_scratch(self):
        scratch = adapter.scratch_root()
        with tempfile.TemporaryDirectory(prefix='lifecycle-test-', dir=scratch) as directory:
            root = Path(directory)
            neighbor = root / 'neighbor'
            neighbor.mkdir()
            real_popen = subprocess.Popen
            def replacement(command, **kwargs):
                path = next(part.split('=', 1)[1] for part in command if part.startswith('-Djava.io.tmpdir='))
                child = ('import sys,json; from pathlib import Path; '
                         'p=Path(sys.argv[1]); (p/"retained.als").write_text("fixture"); '
                         '\nfor line in sys.stdin: print(json.dumps({"status":"ok","scratch":str(p)}),flush=True)')
                return real_popen([sys.executable, '-c', child, path], **kwargs)
            worker = adapter.Worker(timeout=3, recycle_after=2)
            try:
                with mock.patch.object(adapter, 'scratch_root', return_value=root), \
                     mock.patch.object(adapter.subprocess, 'Popen', side_effect=replacement):
                    first, _, cold = worker.request({})
                    self.assertTrue(cold)
                    old = Path(first['scratch'])
                    self.assertTrue((old / 'retained.als').exists())
                    self.assertFalse(worker.request({})[2])
                    third, _, cold = worker.request({})
                    self.assertTrue(cold)
                    self.assertNotEqual(first['scratch'], third['scratch'])
                    self.assertFalse(old.exists())
            finally:
                worker.close()
            self.assertEqual(list(root.iterdir()), [neighbor])

    def test_spawn_failure_removes_scratch(self):
        with tempfile.TemporaryDirectory(dir=adapter.scratch_root()) as directory:
            worker = adapter.Worker(command=['missing-java-test'])
            with mock.patch.object(adapter, 'scratch_root', return_value=Path(directory)), \
                 mock.patch.object(adapter.subprocess, 'Popen', side_effect=OSError('missing')):
                with self.assertRaises(OSError):
                    worker.request({})
            self.assertEqual(list(Path(directory).iterdir()), [])
            self.assertIsNone(worker.scratch)

    def run_child(self, child_code, request):
        real_popen = subprocess.Popen
        def replacement(*args, **kwargs):
            return real_popen([sys.executable, '-c', child_code], **kwargs)
        worker = adapter.Worker(timeout=.08)
        try:
            with mock.patch.object(adapter.subprocess, 'Popen', side_effect=replacement):
                return worker.request(request)
        finally:
            worker.close()

    def test_partial_output_has_absolute_read_deadline(self):
        result, elapsed, cold = self.run_child(
            "import sys,time; sys.stdin.readline(); sys.stdout.write('{'); sys.stdout.flush(); "
            "time.sleep(.6); sys.stdout.write('}\\n'); sys.stdout.flush()", {})
        self.assertEqual(result['status'], 'timeout')
        self.assertLess(elapsed, .4, 'partial-line read bypassed request timeout')

    def test_nonreading_worker_has_absolute_input_deadline(self):
        result, elapsed, cold = self.run_child('import time; time.sleep(.6)', {'payload': 'x' * 1_048_576})
        self.assertEqual(result['status'], 'timeout')
        self.assertLess(elapsed, .4, 'blocked pipe write bypassed request timeout')


if __name__ == '__main__':
    unittest.main()
