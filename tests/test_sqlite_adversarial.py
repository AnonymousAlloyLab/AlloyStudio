"""Constructed persistence counterexamples and hostile import transitions."""
import copy
from http.client import HTTPConnection
import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import exercise_sql
import exercise_store as store
from runtime_dependencies import runtime_classpath
import server
from test_sqlite_store import authored, fixture


ROOT = Path(__file__).resolve().parents[1]


class SQLiteAdversarialTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        fixture(self.root)
        self.database = self.root / store.DATABASE_RELATIVE
        self.classpath = patch('exercise_store.runtime_classpath', return_value=runtime_classpath(ROOT))
        self.classpath.start()
        self.addCleanup(self.classpath.stop)

    def test_behavior_reserves_body_twice_in_aggregate_engine_budget(self):
        # This valid UTF-8 comment is cheap in source bytes but expensive in
        # ensure_ascii JSON. The previous reservation omitted studentBody and
        # admitted a future real behavioral request of 1,058,461 bytes.
        document = authored(environmentBefore='//' + 'é' * 80000 + '\nsig Node {}\n',
                            oracleSolutions=['some Node'], correctSolutions=[])
        record = {key: document[key] for key in store.IMPORT_REQUIRED if key != 'oracleSolutions'}
        learner = '\x01' * 8192
        actual = dict(studentSource=store.model(record, learner), studentBody=learner,
                      oracleSource=store.model(record, document['oracleSolutions'][0]),
                      predicate=record['predicate'])
        self.assertGreater(len(json.dumps(actual).encode()), store.MAX_ENGINE)
        with self.assertRaisesRegex(store.StoreError, 'aggregate engine'):
            store.normalize_import(document)

    def test_database_growth_cannot_commit_an_unloadable_database(self):
        # Reduce only the registered byte threshold to construct the exact
        # 128-MiB boundary transition without allocating a huge fixture. Before
        # the fix, this committed growth from 45,056 to 53,248 bytes and then
        # rejected the database on its next load.
        before = self.database.read_bytes()
        with patch.object(store, 'MAX_DATABASE', len(before)):
            with self.assertRaises(store.StoreError):
                store.add_exercise(self.root, authored(description='D' * 8192))
            self.assertEqual(self.database.read_bytes(), before)
            self.assertEqual(store.load_store(self.root).exercise_count, 1)

    def test_worker_completion_fields_require_exact_types_and_counts(self):
        valid = store.validate_import(self.root, authored())['result']
        mutations = [dict(valid, scope=5.0), dict(valid, factsSatisfiable=1),
                     dict(valid, evaluatedCandidates=2), dict(valid, oracleCount=True),
                     dict(valid, maxTrace=9), dict(valid, solver='UNKNOWN'),
                     {key: value for key, value in valid.items() if key != 'moduleFacts'}]
        before = self.database.read_bytes()
        for result in mutations:
            with self.subTest(changed=sorted(key for key in valid if result.get(key) != valid[key]
                                           or type(result.get(key)) is not type(valid[key]))):
                completed = subprocess.CompletedProcess([], 0, json.dumps(dict(status='ok', **result)), '')
                with patch('exercise_store.subprocess.run', return_value=completed), self.assertRaises(store.StoreError):
                    store.add_exercise(self.root, authored())
                self.assertEqual(self.database.read_bytes(), before)

    def test_authored_certificate_types_and_source_bindings_are_rechecked(self):
        store.add_exercise(self.root, authored())
        with sqlite3.connect(self.database) as connection:
            raw = connection.execute('SELECT validation FROM exercises WHERE id=?', ('private-one',)).fetchone()[0]
        original = json.loads(raw)
        mutations = []
        for key, value in [('version', True), ('environmentSha256', '0' * 64),
                           ('starterSha256', '0' * 64), ('bodySha256', ['0' * 64] * 3)]:
            changed = copy.deepcopy(original)
            changed[key] = value
            mutations.append(changed)
        for key, value in [('bitwidth', 5.0), ('factsSatisfiable', 1), ('oracleCount', True)]:
            changed = copy.deepcopy(original)
            changed['result'][key] = value
            mutations.append(changed)
        for changed in mutations:
            with sqlite3.connect(self.database) as connection:
                connection.execute('UPDATE exercises SET validation=? WHERE id=?',
                                   (json.dumps(changed), 'private-one'))
            with self.assertRaises(store.StoreError):
                store.load_store(self.root)
        with sqlite3.connect(self.database) as connection:
            connection.execute('UPDATE exercises SET validation=? WHERE id=?', (raw, 'private-one'))
        self.assertEqual(store.load_store(self.root).exercise_count, 2)

    def test_carriage_return_comment_escape_and_context_dependency_cannot_publish(self):
        escape = 'some Node // end comment\r} fact injected { no Node } pred extra { some Node'
        documents = [authored(oracleSolutions=[escape], correctSolutions=[]),
                     authored(environmentAfter='\npred hiddenDependency { inv }\n'),
                     authored(environmentBefore='sig Node { adj: set Node }\nfact { inv }\n')]
        before = self.database.read_bytes()
        for document in documents:
            with self.assertRaises(store.StoreError):
                store.add_exercise(self.root, document)
            self.assertEqual(self.database.read_bytes(), before)

    def test_string_universe_counterexample_cannot_publish_a_false_equivalence(self):
        document = authored(environmentBefore='sig Node {}\nfact { #String = 1 and "A" in String }\n',
                            oracleSolutions=['"A" in String', '"B" in String and no Node'],
                            correctSolutions=[])
        before = self.database.read_bytes()
        with self.assertRaises(store.StoreError):
            store.add_exercise(self.root, document)
        self.assertEqual(self.database.read_bytes(), before)

    def test_read_transaction_cannot_mix_concurrent_catalogue_generations(self):
        store.add_exercise(self.root, authored())
        # WAL permits a writer to commit while the reader retains its snapshot.
        with sqlite3.connect(self.database) as connection:
            connection.execute('PRAGMA journal_mode=WAL')
        execute = exercise_sql.execute
        committed = []
        def interleave(connection, identifier, params=()):
            if identifier == 'select_solutions' and not committed:
                with sqlite3.connect(self.database) as writer:
                    writer.execute('UPDATE exercises SET title=? WHERE id=?',
                                   ('New committed title', 'private-one'))
                committed.append(True)
            return execute(connection, identifier, params)
        with patch.object(exercise_sql, 'execute', side_effect=interleave):
            old = store.load_store(self.root)
        self.assertEqual(committed, [True])
        self.assertEqual(old.exercises['private-one']['title'], authored()['title'])
        self.assertEqual(store.load_store(self.root).exercises['private-one']['title'], 'New committed title')

    def test_sql_looking_text_is_bound_data_and_wrong_parameter_types_fail_before_execution(self):
        malicious = "x'); DELETE FROM solutions; --"
        document = authored(title=malicious, group=malicious, description=malicious)
        store.add_exercise(self.root, document)
        snapshot = store.load_store(self.root)
        self.assertEqual(snapshot.exercises['private-one']['title'], malicious)
        self.assertEqual(snapshot.correct_pools['private-one'], tuple(document['oracleSolutions'] + document['correctSolutions']))
        with sqlite3.connect(self.database) as connection:
            executed = []
            connection.set_trace_callback(executed.append)
            for parameters in [('sourceInventory', True, '{}', ''), ('sourceInventory', 1.5, '{}', ''),
                               ('sourceInventory', 2 ** 63, '{}', ''), ('sourceInventory', 1, '\ud800', ''),
                               ('sourceInventory', 1, 'x\0', '')]:
                with self.assertRaises(exercise_sql.QueryError):
                    exercise_sql.execute(connection, 'insert_auxiliary', parameters)
            with self.assertRaises(exercise_sql.QueryError):
                exercise_sql.execute(connection, 'DELETE FROM solutions')
            self.assertEqual(executed, [])

    def test_public_http_projection_and_private_sqlite_artifacts_do_not_leak(self):
        store.add_exercise(self.root, authored())
        with patch('server.Explainer'):
            instance = server.Portal(('127.0.0.1', 0), root=self.root)
        thread = threading.Thread(target=instance.serve_forever, daemon=True)
        thread.start()
        client = HTTPConnection(*instance.server_address, timeout=5)
        try:
            client.request('GET', '/api/exercises/private-one')
            response = client.getresponse()
            raw = response.read()
            self.assertEqual(response.status, 200)
            public = json.loads(raw)
            self.assertEqual(set(public), set(server.PUBLIC_FIELDS))
            for solution in authored()['oracleSolutions'] + authored()['correctSolutions']:
                self.assertNotIn(solution.encode(), raw)
            for hidden in ('validation', 'oracleBody', 'oracleSolutions', 'correctSolutions', 'originalSource'):
                self.assertNotIn(hidden, public)
            for route in ('/exercises/exercises.sqlite3', '/exercises/exercises.sqlite3-wal',
                          '/exercises/exercises.sqlite3-shm', '/exercises/exercises.sqlite3-journal',
                          '/sql/compiled-queries.json', '/sql/queries.json', '/vendor/sqlean/provenance.json',
                          '/private/import.json', '/api/admin/exercises', '/%2e%2e/exercises/exercises.sqlite3'):
                client.request('GET', route)
                response = client.getresponse()
                response.read()
                self.assertEqual(response.status, 404, route)
        finally:
            client.close()
            instance.shutdown()
            instance.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main(verbosity=2)
