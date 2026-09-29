"""Private-pool HTTP boundary, validation-cache, and Luna projection contracts."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import luna
import server

ROOT = Path(__file__).resolve().parents[1]


class PoolPortalTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.record = cls.app.exercises['graphs-inv5']

    @classmethod
    def tearDownClass(cls):
        cls.app.server_close()

    def setUp(self):
        self.app.cache.clear()

    def comparison(self):
        size = len(self.app.correct_pools[self.record['id']])
        return {'strategy': 'nearest-known-correct', 'poolSize': size,
                'evaluatedCandidates': size, 'complete': True}

    def test_engine_receives_complete_private_pool_including_oracle(self):
        response = {'status': 'ok', 'distance': 0, 'comparison': self.comparison(),
                    'referenceBodies': ['PRIVATE_CANARY'], 'selectedTarget': 'PRIVATE_CANARY'}
        with patch('server.subprocess.run', return_value=subprocess.CompletedProcess(
                [], 0, json.dumps(response), '')) as run:
            answer = self.app.evaluate(self.record, 'some Node')
        sent = json.loads(run.call_args.kwargs['input'])
        self.assertNotIn('oracleSource', sent)
        self.assertEqual(tuple(sent['referenceBodies']), self.app.correct_pools[self.record['id']])
        self.assertGreater(len(sent['referenceBodies']), 1)
        self.assertEqual(sent['referenceBodies'][-1], self.record['oracleBody'])
        self.assertEqual(sent['referencePrefix'] + 'some Node' + sent['referenceSuffix'],
                         server.model(self.record, 'some Node'))
        self.assertEqual(answer['comparison'], self.comparison())
        self.assertNotIn('PRIVATE_CANARY', json.dumps(answer))

    def test_missing_partial_or_oracle_only_worker_results_are_rejected(self):
        size = self.comparison()['poolSize']
        variants = [None, {}, {**self.comparison(), 'strategy': 'oracle-only'},
                    {**self.comparison(), 'evaluatedCandidates': size - 1},
                    {**self.comparison(), 'poolSize': size + 1},
                    {**self.comparison(), 'complete': False},
                    {**self.comparison(), 'selectedTarget': 'PRIVATE_CANARY'}]
        for comparison in variants:
            with self.subTest(variant=variants.index(comparison)):
                response = {'status': 'ok', 'distance': 0, 'comparison': comparison}
                with patch('server.subprocess.run', return_value=subprocess.CompletedProcess(
                        [], 0, json.dumps(response), '')):
                    answer = self.app.evaluate(self.record, 'some Node')
                self.assertEqual(answer['status'], 'error')
                self.assertNotIn('distance', answer)
                self.assertNotIn('PRIVATE_CANARY', json.dumps(answer))
        self.assertEqual(len(self.app.cache), 0)

    def test_alternative_correct_predicate_is_accepted_by_real_pool(self):
        result = self.app.evaluate(self.record, 'all n: Node | n not in n.adj')
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(result['distance'], 0)
        self.assertEqual(result['operations'], [])
        self.assertEqual(result['comparison'], self.comparison())

    def test_database_validation_does_not_trust_paths_or_preserved_timestamps(self):
        import sqlite3
        from exercise_store import load_store, StoreError
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root / 'exercises').mkdir()
            path = root / 'exercises/exercises.sqlite3'
            shutil.copy2(ROOT / 'exercises/exercises.sqlite3', path)
            self.assertEqual(load_store(root).exercise_count, 181)
            timestamp = path.stat()
            with sqlite3.connect(path) as connection:
                connection.execute("UPDATE solutions SET body = body || ' // PRIVATE_MUTATION' WHERE ordinal=0")
            os.utime(path, ns=(timestamp.st_atime_ns, timestamp.st_mtime_ns))
            with self.assertRaises(StoreError):
                load_store(root)

    def test_server_instances_cannot_mutate_each_others_pool_registry(self):
        other = server.Portal(('127.0.0.1', 0))
        try:
            other.correct_pools[self.record['id']] = ()
            self.assertGreater(len(self.app.correct_pools[self.record['id']]), 1)
        finally:
            other.server_close()

    def test_luna_only_receives_completed_pool_counts_without_private_target(self):
        feedback = {'distance': 0, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 0},
                    'operations': [], 'comparison': {**self.comparison(), 'selectedBody': 'PRIVATE_CANARY'},
                    'referenceBodies': ['PRIVATE_CANARY']}
        prompt = luna.prompt_trace(feedback)
        self.assertEqual(prompt['comparison'], self.comparison())
        self.assertNotIn('PRIVATE_CANARY', json.dumps(prompt))
        for value in (False, None, 1):
            feedback['comparison']['complete'] = value
            with self.assertRaises(ValueError):
                luna.prompt_trace(feedback)


if __name__ == '__main__':
    unittest.main()
