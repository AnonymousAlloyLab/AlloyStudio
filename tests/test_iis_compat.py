"""Portable IIS-facing contracts; these tests do not claim to run Windows or IIS."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import luna
import server


class IISCompatibilityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0),
                                public_origins=('https://Alloy.Example:443/',
                                                'https://alloy.example:8443'))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()

    def post(self, headers=None):
        payload = {'exerciseId': 'graphs-inv1', 'body': 'some Node', 'revision': 42}
        request = Request(self.url + '/api/feedback', data=json.dumps(payload).encode('utf-8'),
                          headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = urlopen(request, timeout=20)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def test_origin_normalization(self):
        cases = {'https://ALLOY.example:443/': 'https://alloy.example',
                 'http://ALLOY.example:80': 'http://alloy.example',
                 'https://alloy.example:8443': 'https://alloy.example:8443',
                 'http://127.0.0.1:8080/': 'http://127.0.0.1:8080',
                 'http://[::1]:8080': 'http://[::1]:8080'}
        for value, expected in cases.items():
            with self.subTest(value=value):
                self.assertEqual(server.normalize_origin(value), expected)

    def test_origin_rejects_non_origins_and_ambiguous_values(self):
        for value in ('null', '*', '', 'alloy.example', 'ftp://alloy.example',
                      'https://user:pass@alloy.example', 'https://alloy.example/alloy',
                      'https://alloy.example?x=1', 'https://alloy.example#fragment',
                      'https://alloy.example:65536', 'https://alloy.example:abc',
                      'https://', 'https://alloy.example\\evil',
                      'https://alloy.example\n', ' https://alloy.example'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                server.normalize_origin(value)

    def test_external_origin_works_with_rewritten_loopback_host(self):
        # ARR normally rewrites Host to its loopback backend. The explicit public
        # origin must authorize the browser without trusting forwarded headers.
        with patch.object(self.app, 'evaluate', return_value={'status': 'ok', 'distance': 0}) as evaluate:
            for origin in ('https://alloy.example', 'https://alloy.example:8443'):
                with self.subTest(origin=origin):
                    status, answer = self.post({'Origin': origin})
                    self.assertEqual(status, 200)
                    self.assertEqual(answer['revision'], 42)
            self.assertEqual(evaluate.call_count, 2)

    def test_untrusted_origins_remain_denied_with_forged_proxy_headers(self):
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Rejected before engine')):
            for origin in ('https://attacker.invalid', 'http://alloy.example',
                           'https://alloy.example:8444', self.url, 'null',
                           'https://alloy.example.attacker.invalid'):
                headers = {'Origin': origin, 'Host': origin.removeprefix('https://').removeprefix('http://'),
                           'X-Forwarded-Host': 'alloy.example', 'X-Forwarded-Proto': 'https',
                           'Forwarded': 'host=alloy.example;proto=https'}
                with self.subTest(origin=origin):
                    status, answer = self.post(headers)
                    self.assertEqual(status, 403)
                    self.assertNotIn('oracle', json.dumps(answer))

    def test_originless_operator_checks_remain_usable(self):
        with patch.object(self.app, 'evaluate', return_value={'status': 'ok', 'distance': 0}):
            status, answer = self.post()
        self.assertEqual(status, 200)
        self.assertEqual(answer['status'], 'ok')

    def test_java_path_and_protocol_are_explicitly_utf8(self):
        java = r'C:\Program Files\Java\jdk-17\bin\java.exe'
        app = server.Portal(('127.0.0.1', 0), java=java)
        raw = {'status': 'ok', 'distance': 1,
               'comparison': {'strategy': 'nearest-known-correct',
                              'poolSize': len(app.correct_pools['graphs-inv1']),
                              'evaluatedCandidates': len(app.correct_pools['graphs-inv1']), 'complete': True},
               'operations': [{'action': 'Replace the operator “some” with “no”.'}]}
        body = 'some Node // learner comment: λ ∀'
        try:
            with patch('server.subprocess.run', return_value=subprocess.CompletedProcess(
                    [], 0, json.dumps(raw, ensure_ascii=False), '')) as run:
                answer = app.evaluate(app.exercises['graphs-inv1'], body)
            self.assertEqual(answer, raw)
            command = run.call_args.args[0]
            self.assertEqual(command[0], java)
            self.assertIn('-Dfile.encoding=UTF-8', command)
            self.assertEqual(run.call_args.kwargs['encoding'].lower(), 'utf-8')
            self.assertIn(body, json.loads(run.call_args.kwargs['input'])['studentSource'])
            self.assertNotIn('shell', run.call_args.kwargs)
        finally:
            app.server_close()

    def test_windows_key_file_accepts_utf8_bom_without_posix_mode_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            key = Path(folder) / 'fixture.key'
            key.write_text('UNIT_TEST_PRIVATE_KEY\r\n', encoding='utf-8-sig')
            key.chmod(0o644)
            with patch.dict(os.environ, {'OPENAI_API_KEY_FILE': str(key)}, clear=True), \
                    patch('luna.sys.platform', 'win32'):
                self.assertEqual(luna.read_key(), 'UNIT_TEST_PRIVATE_KEY')
                with patch.dict(os.environ, {'OPENAI_DISABLED': '1'}):
                    self.assertEqual(luna.read_key(), '')

    def test_posix_key_file_still_requires_private_permissions(self):
        if os.name == 'nt':
            self.skipTest('POSIX permission checks require a POSIX filesystem')
        with tempfile.TemporaryDirectory() as folder:
            key = Path(folder) / 'fixture.key'
            key.write_text('UNIT_TEST_PRIVATE_KEY\n', encoding='utf-8')
            with patch.dict(os.environ, {'OPENAI_API_KEY_FILE': str(key)}, clear=True), \
                    patch('luna.sys.platform', 'linux'):
                key.chmod(0o644)
                self.assertEqual(luna.read_key(), '')
                key.chmod(0o600)
                self.assertEqual(luna.read_key(), 'UNIT_TEST_PRIVATE_KEY')


if __name__ == '__main__':
    unittest.main()
