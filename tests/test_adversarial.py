"""Bounded malformed-input and privacy cases for the HTTP and Luna closure claims."""
import copy
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import luna
import server


TRACE = {'status': 'ok', 'distance': 1,
         'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 1},
         'operations': [{'kind': 'replace', 'component': 'matrix', 'cost': 1,
                         'path': 'PRIVATE_PATH_SENTINEL', 'description': 'PRIVATE_DESCRIPTION_SENTINEL'}],
         'canonicalForm': ['LEARNER_CANONICAL_SENTINEL'], 'oracleBody': 'PRIVATE_BODY_SENTINEL'}


class AdversarialHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.app.handle_error = lambda *args: None
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()

    def post_bytes(self, payload):
        request = Request(self.url + '/api/feedback', data=payload,
                          headers={'Content-Type': 'application/json'})
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def test_malformed_utf8_is_a_sanitized_http_error(self):
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Must reject before engine')):
            code, result = self.post_bytes(b'{"body":"\xff"}')
        self.assertEqual(code, 400)
        self.assertEqual(set(result), {'error'})

    def test_lone_surrogates_are_rejected_before_engine(self):
        for value in ('\ud800', '\udfff'):
            with self.subTest(codepoint=ord(value)):
                payload = json.dumps({'exerciseId': 'graphs-inv1', 'revision': 1, 'body': value}).encode()
                with patch.object(self.app, 'evaluate', side_effect=AssertionError('Must reject before engine')):
                    code, result = self.post_bytes(payload)
                self.assertEqual(code, 200)
                self.assertEqual(result['status'], 'invalid')
                self.assertNotIn('distance', result)

    def test_deep_json_within_size_limit_is_a_sanitized_http_error(self):
        payload = b'[' * 1200 + b']' * 1200
        self.assertLess(len(payload), server.MAX_REQUEST_BYTES)
        code, result = self.post_bytes(payload)
        self.assertEqual(code, 400)
        self.assertEqual(set(result), {'error'})

    def test_nested_comment_escape_is_rejected(self):
        body = 'some Node /* outer /* inner */ } pred escaped { some Node /* */'
        self.assertIsNotNone(server.validate_body(body))
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Must reject before engine')):
            code, result = self.post_bytes(json.dumps(
                {'exerciseId': 'graphs-inv1', 'body': body, 'revision': 1}).encode())
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'invalid')


class AdversarialLunaTests(unittest.TestCase):
    def test_prompt_kind_is_an_exact_allowlist(self):
        for kind in ('PRIVATE-CANARY', '', 'replace\nSECRET', 'unknown-operation'):
            with self.subTest(kind=kind):
                feedback = copy.deepcopy(TRACE)
                feedback['operations'][0]['kind'] = kind
                with self.assertRaises((TypeError, ValueError)):
                    luna.prompt_trace(feedback)
        for kind in ('insert', 'delete', 'replace', 'modify', 'component-edit'):
            feedback = copy.deepcopy(TRACE)
            feedback['operations'][0]['kind'] = kind
            self.assertEqual(luna.prompt_trace(feedback)['operations'][0]['kind'], kind)

    def test_prompt_drops_every_unregistered_string_field(self):
        payload = json.dumps(luna.prompt_trace(TRACE))
        for sentinel in ('PRIVATE_PATH_SENTINEL', 'PRIVATE_DESCRIPTION_SENTINEL',
                         'LEARNER_CANONICAL_SENTINEL', 'PRIVATE_BODY_SENTINEL'):
            self.assertNotIn(sentinel, payload)

    def test_malformed_provider_shapes_are_sanitized(self):
        malformed = [[], None, {'status': 'completed', 'output': [None]},
                     {'status': 'completed', 'output': [{'type': 'message', 'content': [None]}]},
                     {'status': 'completed', 'output': [{'type': 'message', 'content': 'PRIVATE_SENTINEL'}]},
                     {'status': 'incomplete', 'error': 'PRIVATE_SENTINEL'}]
        for index, value in enumerate(malformed):
            with self.subTest(fixture=index):
                def transport(*args, **kwargs):
                    return io.BytesIO(json.dumps(value).encode())
                client = luna.Explainer(transport=transport, key_reader=lambda: 'UNIT_TEST_KEY')
                result = client.explain(TRACE)
                self.assertEqual(result['status'], 'unavailable')
                self.assertNotIn('PRIVATE_SENTINEL', json.dumps(result))
                # The slot must be released even after malformed responses.
                self.assertTrue(client.slots.acquire(blocking=False))
                self.assertTrue(client.slots.acquire(blocking=False))
                client.slots.release()
                client.slots.release()

    def test_invalid_utf8_key_file_is_unavailable(self):
        with tempfile.TemporaryDirectory() as directory:
            key_path = Path(directory) / 'test.key'
            key_path.write_bytes(b'\xff\xfe')
            key_path.chmod(0o600)
            with patch.dict(os.environ, {'OPENAI_API_KEY_FILE': str(key_path)}, clear=True):
                self.assertEqual(luna.read_key(), '')


if __name__ == '__main__':
    unittest.main(verbosity=2)
