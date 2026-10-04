"""Independent worker-level FM24 review regressions; no JVM/data dependency."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest import mock

DIRECTORY = Path(__file__).resolve().parents[1] / 'fm24'


def _load(name, filename):
    spec = importlib.util.spec_from_file_location(name, DIRECTORY / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


NATIVE = _load('_review_fm24_native', 'native.py')
with mock.patch.dict(sys.modules, {'native': NATIVE}):
    WORKER = _load('_review_fm24_worker', 'worker.py')
    PREPARE = _load('_review_fm24_prepare', 'prepare_graphs.py')


class WorkerReviewTests(unittest.TestCase):
    def worker(self, graph, responses=(), mutations=False):
        worker = WORKER.Worker.__new__(WORKER.Worker)
        worker.mutations = mutations
        worker.rows = {'case': {'fold': 0, 'graph': 'graph'}}
        worker.graphs = [{'graph': graph}]
        worker.native = mock.Mock()
        worker.native.ask.side_effect = list(responses)
        return worker

    def request(self, worker):
        return worker.evaluate({'case_id': 'case', 'path': 'unused.als', 'predicate': 'inv1'})

    def test_undefined_policy_is_explicitly_unsupported_not_missing_hint(self):
        graph = {'policy_status': 'upstream_equal_weight_graph', 'zero_range_policy': True}
        worker = self.worker(graph, mutations=True)
        result = self.request(worker)
        self.assertEqual(result['status'], 'upstream_equal_weight_graph')
        self.assertFalse(result['hint_available'])
        worker.native.ask.assert_not_called()

    def test_mutations_pick_fewest_hops_before_lowest_policy_score(self):
        graph = {'valid': [], 'next': {}, 'scores': {'a': 0.0, 'b': .9}, 'hops': {'a': 2, 'b': 1}}
        worker = self.worker(graph, [
            {'status': 'ok', 'formula': 'query', 'engine_s': .01},
            {'status': 'ok', 'mutations': [{'formula': 'a', 'hint': 'first'},
                                          {'formula': 'b', 'hint': 'second'}], 'engine_s': .02},
        ], mutations=True)
        result = self.request(worker)
        self.assertEqual(result['target'], 'b')
        self.assertEqual(result['hint'], 'second')
        self.assertEqual(result['source'], 'mutation')
        self.assertAlmostEqual(result['java_engine_s'], .03)

    def test_unknown_formula_without_mutations_is_no_hint(self):
        graph = {'valid': ['teacher'], 'next': {}, 'scores': {'teacher': 0}, 'hops': {'teacher': 0}}
        worker = self.worker(graph, [{'status': 'ok', 'formula': 'query'}])
        result = self.request(worker)
        self.assertEqual(result['status'], 'no_hint')
        self.assertFalse(result['hint_available'])
        self.assertEqual(worker.native.ask.call_count, 1)

    def test_no_synthetic_edges_connect_disconnected_goal(self):
        result = PREPARE.policy({'unreachable': {'learner': 1}, 'other': {'root': 2}}, {'teacher'})
        self.assertEqual(result['scores'], {'teacher': 0})
        self.assertEqual(result['next'], {})

    def test_zero_cost_cycle_cannot_make_policy_cycle(self):
        result = PREPARE.policy({'A': {'B': 1}, 'B': {'A': 1}, 'G': {'A': 2, 'B': 1}}, {'G'})
        self.assertEqual(result['next']['B'], 'G')
        self.assertEqual(result['next']['A'], 'B')
        self.assertNotEqual(result['next'].get(result['next']['A']), 'A')


if __name__ == '__main__':
    unittest.main()
