"""The learner projection and unsupported import aliases remain private."""
from http.client import HTTPConnection
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import server
import exercise_store as store
from runtime_dependencies import runtime_classpath
from test_sqlite_store import ROOT, fixture, authored


class SQLiteHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.root = Path(cls.temporary.name)
        fixture(cls.root)
        with patch('exercise_store.runtime_classpath',return_value=runtime_classpath(ROOT)):
            store.add_exercise(cls.root,authored())
        cls.app = server.Portal(('127.0.0.1',0),root=cls.root)
        cls.thread = threading.Thread(target=cls.app.serve_forever,daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()
        cls.temporary.cleanup()

    def request(self,method,path,payload=None):
        connection = HTTPConnection('127.0.0.1',self.app.server_port,timeout=10)
        try:
            connection.request(method,path,body=json.dumps(payload) if payload else None,
                               headers={'Content-Type':'application/json'})
            response = connection.getresponse()
            return response.status,json.loads(response.read())
        finally:
            connection.close()

    def test_public_projection_of_custom_exercise_excludes_all_oracles_and_certificates(self):
        status, value = self.request('GET','/api/exercises/private-one')
        self.assertEqual(status,200)
        self.assertEqual(set(value),set(server.PUBLIC_FIELDS))
        wire = json.dumps(value)
        for private in authored()['oracleSolutions']+authored()['correctSolutions']:
            self.assertNotIn(private,wire)
        for field in ('oracleBody','originalSource','validation','engineSha256','database_path'):
            self.assertNotIn(field,value)
        self.assertEqual(len(self.request('GET','/api/exercises')[1]['exercises']),2)

    def test_no_unauthenticated_import_aliases(self):
        for route in ('/api/admin','/admin/','/api/exercises','/api/exercises/private-one','/api/import'):
            self.assertEqual(self.request('POST',route,authored())[0],404)

    def test_private_database_sidecars_parser_and_cli_routes_denied(self):
        for route in ('/exercises/exercises.sqlite3','/exercises/exercises.sqlite3-wal',
                      '/exercises/exercises.sqlite3-shm','/exercises/exercises.sqlite3-journal',
                      '/sql/queries.json','/sql/schema.json','/sql/compiled-queries.json',
                      '/scripts/manage_exercises.py','/exercise_store.py','/vendor/sqlean/provenance.json',
                      '/examples/private-exercise.json','/openai.local.json','/backend/exercises/exercises.sqlite3',
                      '/%2e%2e/exercises/exercises.sqlite3'):
            with self.subTest(route=route):
                status,result = self.request('GET',route)
                self.assertEqual(status,404)
                self.assertEqual(result,{'error':'Not found.'})

    def test_both_metrics_receive_every_oracle_and_correct_reference(self):
        record = self.app.exercises['private-one']
        for metric in ('canonical','ast'):
            comparison = dict(strategy='nearest-known-correct',poolSize=3,evaluatedCandidates=3,complete=True)
            response = dict(status='ok',distance=0,comparison=comparison)
            with patch('server.subprocess.run',return_value=subprocess.CompletedProcess([],0,json.dumps(response),'')) as worker:
                self.app.evaluate(record,'some Node',metric=metric)
            sent = json.loads(worker.call_args.kwargs['input'])
            self.assertEqual(sent['referenceBodies'],authored()['oracleSolutions']+authored()['correctSolutions'])
            self.assertEqual(sent['metric'],metric)

    def test_behavior_uses_first_designated_oracle(self):
        record = self.app.exercises['private-one']
        with patch('server.subprocess.run',return_value=subprocess.CompletedProcess([],0,'{}','')) as worker:
            self.app.evaluate_behavior(record,'some Node')
        sent = json.loads(worker.call_args.kwargs['input'])
        self.assertEqual(sent['oracleSource'],server.model(record,authored()['oracleSolutions'][0]))
        self.assertNotIn(authored()['oracleSolutions'][1],sent['oracleSource'])


if __name__ == '__main__':
    unittest.main()
