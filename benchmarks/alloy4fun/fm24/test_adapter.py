import json
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from types import SimpleNamespace
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).parent))
from lineage import build
from native import Native, build_classpath
from prepare_graphs import policy
from worker import Worker as FMWorker

class PolicyTests(unittest.TestCase):
    def test_normalizing_edges_changes_path_choice(self):
        # Raw weights prefer A->G (3) to A->B->G (4). Native MIN-TED
        # normalizes min=2,max=3, making the indirect path cost zero.
        p = policy({'G': {'A': 3, 'B': 2}, 'B': {'A': 2}}, {'G'})
        self.assertEqual(p['next']['A'], 'B')
        self.assertEqual(p['scores']['A'], 0)
        self.assertEqual(p['hops']['A'], 2)

    def test_undefined_equal_weight_policy_is_not_credited(self):
        p = policy({'G': {'A': 2}}, {'G'})
        self.assertEqual(p['policy_status'], 'upstream_equal_weight_graph')
        self.assertEqual(p['next'], {})

    def test_oracle_without_observed_edge_does_not_invent_hint(self):
        p = policy({}, {'teacher'})
        self.assertNotIn('learner', p['scores'])
        self.assertFalse(p['next'])

class LineageTests(unittest.TestCase):
    def test_branches_keep_all_questions_and_excluded_successors_together(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            records = [{'_id': 'teacher', 'original': 'teacher'},
                       {'_id': 'A', 'original': 'teacher', 'derivationOf': 'teacher'},
                       {'_id': 'B-other-question', 'original': 'teacher', 'derivationOf': 'A'},
                       {'_id': 'C-teacher-exact', 'original': 'teacher', 'derivationOf': 'B-other-question'}]
            (root / 'data.json').write_text(''.join(json.dumps(r) + '\n' for r in records))
            cases = root / 'cases.jsonl'
            cases.write_text(''.join(json.dumps({'model_id': r['_id']}) + '\n' for r in records[1:]))
            output = root / 'lineage.output'
            build(root, cases, output)
            result = json.loads(output.read_text())
            self.assertEqual({r['root_id'] for r in result.values()}, {'A'})
            self.assertEqual(len({r['fold'] for r in result.values()}), 1)

class FakeNative(Native):
    def __init__(self, code, **options):
        self.code = code
        super().__init__('unused-test-classpath', **options)
    def _command(self):
        return [sys.executable, '-c', self.code, self.scratch.name]

class ScratchTests(unittest.TestCase):
    RESPONDER = """import json,sys
from pathlib import Path
for line in sys.stdin:
    (Path(sys.argv[1])/'alloy_heredoc_test.als').write_text('private test data')
    print(json.dumps({'status':'ok','scratch':sys.argv[1]}),flush=True)
"""

    def test_close_removes_only_owned_scratch_after_worker_exit(self):
        with tempfile.TemporaryDirectory() as root:
            sentinel = Path(root) / 'neighbour.txt'
            sentinel.write_text('preserve')
            worker = FakeNative(self.RESPONDER, scratch_root=root)
            directory = Path(worker.scratch.name)
            process = worker.process
            try:
                response = worker.ask({})
                self.assertEqual(Path(response['scratch']), directory)
                self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
                self.assertTrue((directory / 'alloy_heredoc_test.als').exists())
            finally:
                worker.close()
            self.assertIsNotNone(process.poll())
            self.assertFalse(directory.exists())
            self.assertEqual(sentinel.read_text(), 'preserve')
            worker.close()

    def test_recycle_bounds_files_and_charges_restart_to_next_request(self):
        with tempfile.TemporaryDirectory() as root:
            worker = FakeNative(self.RESPONDER, scratch_root=root, recycle_after=2)
            first_directory = Path(worker.scratch.name)
            try:
                first = worker.ask({})
                second = worker.ask({})
                self.assertTrue(first['native_cold_worker'])
                self.assertFalse(second['native_cold_worker'])
                self.assertTrue(second['native_worker_recycled'])
                self.assertEqual(second['native_request_number'], 2)
                self.assertFalse(first_directory.exists())
                self.assertIsNone(worker.process)
                third = worker.ask({})
                self.assertTrue(third['native_cold_worker'])
                self.assertEqual(third['native_request_number'], 1)
                self.assertNotEqual(Path(third['scratch']), first_directory)
                self.assertGreater(third['native_wall_s'], 0)
            finally:
                worker.close()
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_timeout_cleans_files_and_does_not_restart_outside_request(self):
        with tempfile.TemporaryDirectory() as root:
            worker = FakeNative("import sys,time;from pathlib import Path;"
                                "(Path(sys.argv[1])/'private.als').write_text('private');"
                                "sys.stdout.write('{');sys.stdout.flush();time.sleep(10)", scratch_root=root)
            directory = Path(worker.scratch.name)
            try:
                with self.assertRaises(TimeoutError):
                    worker.ask({}, timeout=.1)
                self.assertFalse(directory.exists())
                self.assertIsNone(worker.process)
                self.assertIsNone(worker.scratch)
            finally:
                worker.close()

    def test_spawn_failure_cleans_owned_directory(self):
        with tempfile.TemporaryDirectory() as root:
            with mock.patch('native.subprocess.Popen', side_effect=OSError('spawn failed')):
                with self.assertRaises(OSError):
                    Native('unused', scratch_root=root)
            self.assertEqual(list(Path(root).iterdir()), [])

    def test_invalid_response_cleans_owned_directory(self):
        with tempfile.TemporaryDirectory() as root:
            worker = FakeNative("import sys;sys.stdin.readline();print('[]',flush=True)", scratch_root=root)
            try:
                with self.assertRaises(RuntimeError):
                    worker.ask({})
                self.assertEqual(list(Path(root).iterdir()), [])
            finally:
                worker.close()

class DeadlineTests(unittest.TestCase):
    def test_partial_output_cannot_escape_deadline(self):
        worker = FakeNative("import sys,time; sys.stdout.write('{');sys.stdout.flush();time.sleep(10)")
        start = time.perf_counter()
        try:
            with self.assertRaises(TimeoutError):
                worker.ask({'x': 1}, timeout=0.1)
            self.assertLess(time.perf_counter() - start, 1)
        finally:
            worker.close()

    def test_blocked_input_cannot_escape_deadline(self):
        worker = FakeNative('import time;time.sleep(10)')
        start = time.perf_counter()
        try:
            with self.assertRaises(TimeoutError):
                worker.ask({'x': 'a' * 1000000}, timeout=0.1)
            self.assertLess(time.perf_counter() - start, 1)
        finally:
            worker.close()


class WorkerMetadataTests(unittest.TestCase):
    def worker(self, responses, *, cold=False):
        worker = FMWorker.__new__(FMWorker)
        worker.mutations = False
        worker.rows = {'fixture': {'fold': 0, 'graph': 'graph'}}
        worker.graphs = [{'graph': {'valid': [], 'next': {'query': 'goal'}}}]
        worker.native = SimpleNamespace(process=None if cold else object(), completed_requests=0 if cold else 4,
                                        ask=mock.Mock(side_effect=responses))
        return worker

    def test_query_aggregates_native_restart_and_recycle_between_actions(self):
        for first_cold, second_cold in ((False, True), (True, False)):
            with self.subTest(first_cold=first_cold):
                worker = self.worker([
                    {'status': 'ok', 'formula': 'query', 'native_cold_worker': first_cold,
                     'native_worker_recycled': True},
                    {'status': 'ok', 'hint_available': True, 'hint': 'Inspect this part.',
                     'native_cold_worker': second_cold, 'native_worker_recycled': False},
                ])
                result = worker.evaluate({'case_id': 'fixture', 'path': 'unused', 'predicate': 'inv1'})
                self.assertEqual(result['status'], 'hint')
                self.assertTrue(result['cold_worker'])
                self.assertTrue(result['worker_recycled'])
                self.assertEqual(result['native_action_count'], 2)
                self.assertEqual(result['target'], 'goal')

    def test_initial_native_timeout_retains_cold_and_attempt_count(self):
        worker = self.worker([TimeoutError('deadline')], cold=True)
        result = worker.evaluate({'case_id': 'fixture', 'path': 'unused', 'predicate': 'inv1'})
        self.assertEqual(result['status'], 'timeout')
        self.assertTrue(result['cold_worker'])
        self.assertFalse(result['worker_recycled'])
        self.assertEqual(result['native_action_count'], 1)

    def test_unsupported_graph_does_not_claim_a_native_request(self):
        worker = self.worker([], cold=True)
        worker.graphs[0]['graph']['policy_status'] = 'upstream_equal_weight_graph'
        result = worker.evaluate({'case_id': 'fixture', 'path': 'unused', 'predicate': 'inv1'})
        self.assertFalse(result['cold_worker'])
        self.assertFalse(result['worker_recycled'])
        self.assertEqual(result['native_action_count'], 0)
        worker.native.ask.assert_not_called()

class NativeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        baselines = Path('/home/augustus/lp_baselines')
        if not (baselines / 'SpecAssistant').is_dir():
            raise unittest.SkipTest('Fetch pinned public FM24 resources first')
        cls.worker = Native(build_classpath(baselines, compile_adapter=False))
    @classmethod
    def tearDownClass(cls):
        cls.worker.close()

    def test_original_normalizer_empty_expression_and_oracle_separation(self):
        q = {'code': 'sig A {} pred inv1 {} pred inv1c {some A}', 'predicate': 'inv1', 'oracle': 'inv1c'}
        result = self.worker.ask(q)
        self.assertEqual(result['formula'], '')
        self.assertEqual(result['oracle_formula'], '(some A)')
        q['code'] = 'sig A {} pred inv1 {} pred inv1c {no A}'
        self.assertEqual(self.worker.ask(q)['formula'], result['formula'])
        self.assertTrue(any(Path(self.worker.scratch.name).glob('alloy_heredoc*.als')))
        self.assertIn('-Djava.io.tmpdir=' + self.worker.scratch.name, self.worker.process.args)

    def test_original_apted_hint_and_mutation_apis(self):
        base = {'code': 'sig A {} pred inv1 {some A}', 'source': 'some A', 'target': 'no A'}
        distance = self.worker.ask({**base, 'action': 'distance'})
        self.assertEqual(distance['status'], 'ok')
        self.assertGreater(distance['distance'], 0)
        hint = self.worker.ask({**base, 'action': 'hint'})
        self.assertTrue(hint['hint_available'])
        self.assertIn('quantifier', hint['hint'])
        mutants = self.worker.ask({**base, 'action': 'mutations', 'predicate': 'inv1'})
        self.assertIn('(no A)', [c['formula'] for c in mutants['mutations']])

if __name__ == '__main__':
    unittest.main()
