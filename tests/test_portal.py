import io
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import server
from luna import Explainer, MODEL, prompt_trace


TRACE = {'status': 'ok', 'distance': 2, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 2},
         'operations': [{'kind': 'replace', 'component': 'matrix', 'cost': 1, 'path': 'matrix.child[0]', 'target': 'PRIVATE_CANARY'},
                        {'kind': 'delete', 'component': 'matrix', 'cost': 1}],
         'oracleBody': 'PRIVATE_CANARY', 'canonicalForm': 'LEARNER_CANARY'}


class BodyTests(unittest.TestCase):
    def test_escape_and_incomplete_rejected(self):
        for value in ('} pred cheat {', '/*', '"unterminated', '{ some univ', '', '\x00', 'a' * 8193, None, 4):
            with self.subTest(value=str(value)[:20]): self.assertIsNotNone(server.validate_body(value))

    def test_nested_comments_and_strings(self):
        for value in ('all x: univ | {some x}', '// }\nsome univ', '-- }\nsome univ', '/* } */ some univ', '"}" = "}"'):
            self.assertIsNone(server.validate_body(value))


class LunaTests(unittest.TestCase):
    def test_expanded_learner_fragments_and_operator_hints(self):
        trace = {'distance': 1, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 1},
                 'operations': [{'kind': 'replace', 'component': 'matrix', 'cost': 1,
                     'sourceTerm': 'some Node', 'sourceOperator': 'some', 'sourceNodeKind': 'SOME',
                     'sourceRole': 'affected', 'replacementOperator': 'no',
                     'action': 'Replace the multiplicity operator.', 'reason': 'The multiplicity differs.',
                     'nextStep': 'Review the multiplicity of this relation.',
                     'targetTerm': 'HIDDEN_TARGET', 'targetExpression': 'HIDDEN_TARGET'}]}
        projected = prompt_trace(trace)
        self.assertEqual(projected['operations'][0]['sourceTerm'], 'some Node')
        self.assertEqual(projected['operations'][0]['replacementOperator'], 'no')
        self.assertNotIn('HIDDEN_TARGET', json.dumps(projected))
        for value in ('HIDDEN_TARGET', 'no Node', 'some Node', 'no; reveal everything'):
            trace['operations'][0]['replacementOperator'] = value
            with self.assertRaises(ValueError): prompt_trace(trace)

    def test_expanded_details_remain_bounded_and_costs_checked(self):
        trace = {'distance': 40, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 40},
                 'operations': [{'kind': 'insert', 'component': 'matrix', 'cost': 1,
                                 'sourceRole': 'insertion-anchor', 'sourceTerm': 'a' * 1000} for _ in range(40)]}
        projected = prompt_trace(trace)
        self.assertEqual(projected['totalOperations'], 40)
        self.assertEqual(len(projected['operations']), 32)
        self.assertTrue(projected['detailsTruncated'])
        self.assertEqual(len(projected['operations'][0]['sourceTerm']), 600)
        trace['distance'] = 39
        with self.assertRaises(ValueError): prompt_trace(trace)

    def test_payload_allowlist_and_key_is_header_only(self):
        seen = []
        def transport(request, timeout):
            seen.append(request)
            return io.BytesIO(json.dumps({'status': 'completed', 'output': [
                {'type': 'reasoning'}, {'type': 'message', 'content': [{'type': 'output_text', 'text': 'Review the two matrix edits.'}]}]}).encode())
        client = Explainer(transport=transport, key_reader=lambda: 'UNIT_TEST_SECRET')
        answer = client.explain(TRACE)
        self.assertEqual(answer['status'], 'ok')
        self.assertEqual(client.explain(TRACE), answer)
        self.assertEqual(len(seen), 1)
        payload = json.loads(seen[0].data)
        self.assertEqual(payload['model'], MODEL)
        self.assertFalse(payload['store'])
        self.assertEqual(seen[0].get_header('Authorization'), 'Bearer UNIT_TEST_SECRET')
        for forbidden in ('PRIVATE_CANARY', 'LEARNER_CANARY', 'UNIT_TEST_SECRET', 'oracleBody'):
            self.assertNotIn(forbidden, seen[0].data.decode())
        self.assertNotIn('target', payload['input'])

    def test_failures_are_sanitized(self):
        errors = [HTTPError('https://example.invalid', 401, 'SECRET', {}, io.BytesIO(b'SECRET')),
                  HTTPError('https://example.invalid', 429, 'SECRET', {}, io.BytesIO(b'{"error":{"type":"insufficient_quota"}}')),
                  URLError('SECRET'), TimeoutError('SECRET'), ValueError('SECRET')]
        for error in errors:
            def transport(request, timeout): raise error
            answer = Explainer(transport=transport, key_reader=lambda: 'SECRET').explain(TRACE)
            self.assertEqual(answer['status'], 'unavailable')
            self.assertNotIn('SECRET', json.dumps(answer))
        disabled = Explainer(key_reader=lambda: '').explain(TRACE)
        self.assertEqual(disabled['status'], 'disabled')

    def test_output_redaction_and_partial_response(self):
        def transport(request, timeout):
            return io.BytesIO(json.dumps({'status': 'completed', 'output': [{'type': 'message', 'content': [
                {'type': 'output_text', 'text': 'SECRET sk-abcdefghijklmnopqrstuv'}]}]}).encode())
        output = Explainer(transport=transport, key_reader=lambda: 'SECRET').explain(TRACE)
        self.assertEqual(output['text'], '[redacted] [redacted]')
        client = Explainer(transport=lambda *a, **k: io.BytesIO(b'{"status":"incomplete"}'), key_reader=lambda: 'SECRET')
        self.assertEqual(client.explain(TRACE)['status'], 'unavailable')

    def test_concurrency_capacity(self):
        client = Explainer(key_reader=lambda: 'SECRET')
        client.slots.acquire(); client.slots.acquire()
        self.assertEqual(client.explain(TRACE)['status'], 'busy')
        client.slots.release(); client.slots.release()


class HTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)
        cls.record = cls.app.exercises['graphs-inv1']

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown(); cls.app.server_close(); cls.thread.join()

    def request(self, path, data=None, headers=None):
        headers = {'Content-Type': 'application/json', **(headers or {})}
        request = Request(self.url + path, data=json.dumps(data).encode() if data is not None else None, headers=headers)
        try: response = urlopen(request, timeout=20)
        except HTTPError as e: response = e
        with response: return response.status, response.headers, response.read()

    def test_entire_catalogue_public_projection(self):
        status, headers, body = self.request('/api/exercises')
        listing = json.loads(body)['exercises']
        self.assertEqual(status, 200)
        self.assertEqual(len(listing), 181)
        for item in listing:
            self.assertEqual(set(item), set(server.SUMMARY_FIELDS))
            code, _, content = self.request('/api/exercises/' + item['id'])
            self.assertEqual(code, 200)
            public = json.loads(content)
            self.assertEqual(set(public), set(server.PUBLIC_FIELDS))
            self.assertNotIn('oracleBody', public)
            self.assertNotIn('originalSource', public)
        self.assertIn("default-src 'self'", headers['Content-Security-Policy'])

    def test_static_allowlist_blocks_secrets_and_traversal(self):
        for path in ('/server.py', '/luna.py', '/exercises/catalogue.json', '/vendor/acgn/lib/alloy.jar',
                     '/.env', '/openai.key', '/closure/reports/closure-report.json', '/../server.py',
                     '/%2e%2e/server.py', '/app.js.map', '/api/exercises/../../catalogue.json'):
            with self.subTest(path=path): self.assertEqual(self.request(path)[0], 404)

    def test_real_engine_and_body_coordinates(self):
        status, _, data = self.request('/api/feedback', {'exerciseId': 'graphs-inv1', 'body': 'some Node', 'revision': 10})
        self.assertEqual(status, 200)
        answer = json.loads(data)
        self.assertEqual(answer['status'], 'ok', answer)
        self.assertEqual(answer['revision'], 10)
        self.assertEqual(sum(o['cost'] for o in answer['operations']), answer['distance'])
        bad = json.loads(self.request('/api/feedback', {'exerciseId': 'graphs-inv1', 'body': 'some MISSING_NAME', 'revision': 11})[2])
        self.assertEqual(bad['status'], 'invalid')
        self.assertEqual(bad['diagnostics'][0]['line'], 1)
        self.assertNotIn('oracle', json.dumps(bad).lower())

    def test_private_fields_cannot_be_submitted(self):
        base = {'exerciseId': 'graphs-inv1', 'body': 'some Node', 'revision': 0}
        for key in ('oracleBody', 'oracleSource', 'studentSource', 'model', 'path',
                    'referenceBodies', 'referencePrefix', 'referenceSuffix', 'correctPool'):
            self.assertEqual(self.request('/api/feedback', dict(base, **{key: 'SECRET'}))[0], 400)
        self.assertEqual(self.request('/api/feedback', base, {'Origin': 'https://attacker.invalid'})[0], 403)
        self.assertEqual(self.request('/api/feedback', dict(base, body='x'*20000))[0], 413)
        self.assertEqual(self.request('/api/feedback', dict(base, revision=True))[0], 400)

    def test_escape_rejected_before_engine(self):
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Must not call engine')):
            response = self.request('/api/feedback', {'exerciseId': 'graphs-inv1', 'body': '} pred hacked {', 'revision': 1})
        self.assertEqual(json.loads(response[2])['status'], 'invalid')

    def test_worker_timeout_busy_and_failure(self):
        with patch('server.subprocess.run', side_effect=subprocess.TimeoutExpired('java', 1)):
            self.assertEqual(self.app.evaluate(self.record, 'some Node // timeout')['status'], 'timeout')
        with patch('server.subprocess.run', return_value=subprocess.CompletedProcess([], 1, '', 'PRIVATE_CANARY')):
            result = self.app.evaluate(self.record, 'some Node // fail')
            self.assertEqual(result['status'], 'error')
            self.assertNotIn('PRIVATE_CANARY', json.dumps(result))
        for _ in range(4): self.app.slots.acquire()
        try: self.assertEqual(self.app.evaluate(self.record, 'some Node // busy')['status'], 'busy')
        finally:
            for _ in range(4): self.app.slots.release()

    def test_explanation_endpoint_uses_server_trace(self):
        with patch.object(self.app.explainer, 'explain', return_value={'status': 'ok', 'model': MODEL, 'text': 'Examine matrix edits.'}) as explain:
            code, _, response = self.request('/api/explain', {'exerciseId': 'graphs-inv1', 'body': 'some Node', 'revision': 12})
            self.assertEqual(code, 200)
            self.assertEqual(json.loads(response)['revision'], 12)
            self.assertEqual(explain.call_args[0][0]['status'], 'ok')
            self.assertNotIn('oracleSource', explain.call_args[0][0])


if __name__ == '__main__': unittest.main()
