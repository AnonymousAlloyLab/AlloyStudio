"""Structured guidance uses the learner draft and exactly the displayed evidence."""
import copy
import hashlib
import io
import json
import subprocess
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server
from luna import Explainer


PRIVATE = 'PRIVATE_ORACLE_EDUCATION_CANARY'
PUBLIC_BODY = 'some Node // learner context 🌙'
PUBLIC_CANONICAL = 'root normal form := inv5((SOME Node))'


def behavior_evidence():
    witness = {'traceLength': 1, 'loopState': 0, 'truncated': False, 'stringsAnonymized': False,
               'states': [{'index': 0, 'signatures': [{'label': 'Node', 'atoms': ['Node$0']}],
                           'relations': [{'label': 'Node.adj', 'arity': 2, 'tuples': [['Node$0', 'Node$0']]}]}]}
    return {'status': 'ok', 'metric': 'acgn-reward', 'score': 0.25,
            'scoreStatus': 'ok', 'scoreReason': 'OK',
            'scope': {'overall': 3, 'bitwidth': 3, 'maxSequence': 3, 'poolSize': 100,
                      'minTrace': 1, 'maxTrace': 10, 'moduleFacts': True},
            'sampling': {'positiveTested': 4, 'positiveAccepted': 2,
                         'negativeTested': 4, 'negativeRejected': 2, 'semanticCounterexamples': 0},
            'categories': [{'id': name, 'oracle': oracle, 'student': student, 'status': 'sat',
                            'enumerationComplete': True, 'instances': [copy.deepcopy(witness)]}
                           for name, oracle, student in (('both', True, True), ('undercoverage', True, False),
                                                        ('overcoverage', False, True), ('neither', False, False))]}


def education_reply():
    return {'status': 'ok', 'model': 'gpt-6-luna',
            'operations': [{'id': 'operation-1', 'description': 'Inspect the multiplicity of Node.'}],
            'instances': [{'id': name + '-1', 'description': 'Review the self-related node in this example.'}
                          for name in ('both', 'undercoverage', 'overcoverage', 'neither')],
            'summary': 'Compare the classifications and inspect the highlighted condition.'}


class EducationHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', public_origins=('https://as.555.is',))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)
        cls.record = cls.app.exercises['graphs-inv5']
        cls.base = {'exerciseId': cls.record['id'], 'body': PUBLIC_BODY, 'revision': 17}

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown(); cls.app.server_close(); cls.thread.join()

    def setUp(self):
        self.app.cache.clear()
        self.app.behavior_cache.clear()

    def canonical(self):
        size = len(self.app.correct_pools[self.record['id']])
        return {'status': 'ok', 'distance': 1,
                'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 1},
                'canonicalForm': [PUBLIC_CANONICAL],
                'operations': [{'kind': 'replace', 'component': 'matrix', 'cost': 1,
                                'sourceTerm': '(some Node)', 'sourceOperator': 'some',
                                'replacementOperator': 'no'}],
                'comparison': {'strategy': 'nearest-known-correct', 'poolSize': size,
                               'evaluatedCandidates': size, 'complete': True},
                'oracleBody': PRIVATE, 'oracleSource': PRIVATE, 'referenceBodies': [PRIVATE]}

    def request(self, path, data=None, headers=None):
        request = Request(self.url + path, data=json.dumps(self.base if data is None else data).encode(),
                          headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, json.loads(response.read())

    def completed(self, result):
        return subprocess.CompletedProcess([], 0, json.dumps(result), '')

    def fetch_behavior(self, data=None, answer=None):
        with patch('server.subprocess.run', return_value=self.completed(answer or behavior_evidence())):
            code, _, result = self.request('/api/behavior', data)
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'ok')
        self.assertRegex(result['behaviorToken'], r'^[0-9a-f]{64}$')
        return result

    def test_guidance_receives_exact_displayed_snapshot_and_learner_context_only(self):
        raw = behavior_evidence()
        raw.update(oracleSource=PRIVATE, command=PRIVATE)
        raw['categories'][0]['instances'][0]['states'][0]['skolems'] = [PRIVATE]
        displayed = self.fetch_behavior(answer=raw)
        expected = {key: value for key, value in displayed.items()
                    if key not in ('exerciseId', 'revision', 'behaviorToken', 'contentVersion')}
        with patch('server.subprocess.run', return_value=self.completed(self.canonical())), \
             patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Do not regenerate displayed instances')), \
             patch.object(self.app.explainer, 'explain', return_value=education_reply()) as explain:
            code, headers, result = self.request('/api/explain', dict(self.base, behaviorToken=displayed['behaviorToken']))
        self.assertEqual(code, 200)
        self.assertEqual(result['behaviorToken'], displayed['behaviorToken'])
        self.assertEqual(result['revision'], self.base['revision'])
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(explain.call_args.kwargs['student_body'], PUBLIC_BODY)
        self.assertEqual(explain.call_args.kwargs['behavior'], expected)
        self.assertEqual(explain.call_args.kwargs['question'], self.record['description'])
        self.assertIsNone(explain.call_args.kwargs['solution_comparison'])
        self.assertEqual(explain.call_args.args[0]['canonicalForm'], [PUBLIC_CANONICAL])
        encoded = json.dumps({'trace': explain.call_args.args[0], **explain.call_args.kwargs})
        self.assertNotIn(PRIVATE, encoded)
        self.assertNotIn('oracleSource', encoded)
        self.assertNotIn('referenceBodies', encoded)

    def test_http_to_structured_provider_covers_each_displayed_instance_and_operation(self):
        displayed = self.fetch_behavior()
        seen = []
        def transport(request, timeout):
            seen.append(json.loads(request.data))
            public = education_reply()
            document = {key: public[key] for key in ('operations', 'instances', 'summary')}
            return io.BytesIO(json.dumps({'status': 'completed', 'output': [
                {'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps(document)}]}]}).encode())
        client = Explainer(transport=transport, key_reader=lambda: 'UNIT_TEST_EDUCATION_KEY')
        with patch('server.subprocess.run', return_value=self.completed(self.canonical())), \
             patch.object(self.app, 'explainer', client):
            code, _, result = self.request('/api/explain', dict(self.base, behaviorToken=displayed['behaviorToken']))
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual([item['id'] for item in result['operations']], ['operation-1'])
        self.assertEqual([item['id'] for item in result['instances']],
                         ['both-1', 'undercoverage-1', 'overcoverage-1', 'neither-1'])
        self.assertEqual(len(seen), 1)
        provider = seen[0]
        self.assertTrue(provider['text']['format']['strict'])
        self.assertFalse(provider['store'])
        evidence = json.loads(provider['input'])
        self.assertEqual(evidence['studentBody'], PUBLIC_BODY)
        self.assertEqual(evidence['question'], self.record['description'])
        self.assertEqual(evidence['solutionComparison'], {'status': 'unavailable'})
        self.assertEqual(evidence['canonicalForm'], [PUBLIC_CANONICAL])
        self.assertEqual(evidence['behavior']['categories'][1]['instances'][0]['id'], 'undercoverage-1')
        self.assertNotIn(PRIVATE, json.dumps(provider))
        self.assertNotIn('oracleBody', json.dumps(provider))
        self.assertNotIn('UNIT_TEST_EDUCATION_KEY', json.dumps(provider))

    def test_token_stable_for_revisions_but_changes_for_draft_exercise_or_evidence(self):
        first = self.fetch_behavior()
        second = self.fetch_behavior(dict(self.base, revision=18))
        self.assertEqual(first['behaviorToken'], second['behaviorToken'])
        changed_body = self.fetch_behavior(dict(self.base, body=PUBLIC_BODY + ' '))
        changed_exercise = self.fetch_behavior(dict(self.base, exerciseId='graphs-inv1'))
        self.assertNotEqual(first['behaviorToken'], changed_body['behaviorToken'])
        self.assertNotEqual(first['behaviorToken'], changed_exercise['behaviorToken'])
        self.app.behavior_cache.clear()
        replacement = behavior_evidence()
        replacement['categories'][0]['instances'][0]['states'][0]['relations'][0]['tuples'] = []
        replaced = self.fetch_behavior(answer=replacement)
        self.assertNotEqual(first['behaviorToken'], replaced['behaviorToken'])
        with patch.object(self.app, 'evaluate', return_value=self.canonical()), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Stale evidence reached Luna')):
            code, _, result = self.request('/api/explain', dict(self.base, behaviorToken=first['behaviorToken']))
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'unavailable')
        self.assertNotIn('behaviorToken', result)

    def test_token_mismatch_with_other_draft_or_exercise_never_calls_luna(self):
        displayed = self.fetch_behavior()
        with patch.object(self.app, 'evaluate', return_value=self.canonical()), \
             patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Must not compute new evidence')), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Mismatched evidence reached Luna')):
            requests = [dict(self.base, behaviorToken='0' * 64),
                        dict(self.base, behaviorToken=displayed['behaviorToken'], body='no Node'),
                        dict(self.base, behaviorToken=displayed['behaviorToken'], exerciseId='graphs-inv1')]
            for payload in requests:
                with self.subTest(exercise=payload['exerciseId'], body=payload['body']):
                    code, _, result = self.request('/api/explain', payload)
                    self.assertEqual(code, 200)
                    self.assertEqual(result['status'], 'unavailable')
                    self.assertNotIn('operations', result)
                    self.assertNotIn('instances', result)

    def test_evicted_snapshot_requires_refresh_without_regenerating_examples(self):
        displayed = self.fetch_behavior()
        with patch('server.subprocess.run', return_value=self.completed(behavior_evidence())):
            for index in range(32):
                self.app.evaluate_behavior(self.record, 'some Node // later draft ' + str(index))
        key = (self.record['id'], hashlib.sha256(PUBLIC_BODY.encode()).hexdigest())
        self.assertNotIn(key, self.app.behavior_cache)
        with patch.object(self.app, 'evaluate', return_value=self.canonical()), \
             patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Eviction must not silently choose new examples')), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Evicted evidence reached Luna')):
            code, _, result = self.request('/api/explain', dict(self.base, behaviorToken=displayed['behaviorToken']))
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'unavailable')
        self.assertIn('refresh', result['message'])

    def test_no_token_requests_operations_only_even_when_behavior_is_cached(self):
        self.fetch_behavior()
        reply = education_reply()
        reply['instances'] = []
        with patch('server.subprocess.run', return_value=self.completed(self.canonical())), \
             patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Operation explanation must not solve examples')), \
             patch.object(self.app.explainer, 'explain', return_value=reply) as explain:
            code, _, result = self.request('/api/explain')
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(result['instances'], [])
        self.assertNotIn('behaviorToken', result)
        self.assertIsNone(explain.call_args.kwargs['behavior'])
        self.assertEqual(explain.call_args.kwargs['student_body'], PUBLIC_BODY)

    def test_failed_behavior_has_no_token_and_invalid_canonical_never_reaches_luna(self):
        with patch.object(self.app, 'evaluate_behavior', return_value={'status': 'timeout', 'message': 'Try again.'}):
            code, _, result = self.request('/api/behavior')
        self.assertEqual(code, 200)
        self.assertNotIn('behaviorToken', result)
        with patch.object(self.app, 'evaluate', return_value={'status': 'invalid'}), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Invalid draft reached Luna')):
            code, _, result = self.request('/api/explain', dict(self.base, behaviorToken='0' * 64))
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'unavailable')

    def test_pinned_zero_guidance_uses_captured_question_and_pool_without_new_analysis(self):
        record = dict(self.record, description='Every node has an outgoing connection.')
        pools = ['some ' + PRIVATE, 'some Node', 'some Node and some Node']
        captured = SimpleNamespace(exercises={record['id']: record}, correct_pools={record['id']: pools})
        payload = dict(self.base, body='some Node and some Node')
        matching = {'status': 'ok', 'metric': server.METRICS['canonical'], 'distance': 0,
                    'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 0},
                    'canonicalForm': ['some Node'], 'operations': [],
                    'comparison': {'strategy': 'nearest-known-correct', 'poolSize': 3,
                                   'evaluatedCandidates': 3, 'complete': True}}
        with patch.object(self.app, 'capture', return_value=(captured, 'captured-guidance-generation')), \
             patch.object(self.app, 'evaluate', return_value=matching) as evaluate:
            code, _, checked = self.request('/api/feedback', payload)
        self.assertEqual(code, 200)
        self.assertEqual(evaluate.call_count, 1)
        self.app.cache.clear()
        with patch.object(self.app, 'capture', return_value=(captured, 'captured-guidance-generation')), \
             patch.object(self.app, 'evaluate', side_effect=AssertionError('Pinned guidance must not rerun feedback')), \
             patch.object(self.app, '_engine', side_effect=AssertionError('Guidance must not start an analysis')), \
             patch('server.subprocess.run', side_effect=AssertionError('Guidance must not launch a JVM')), \
             patch.object(self.app.explainer, 'explain', return_value={'status': 'ok', 'summary': 'Try another expression.'}) as explain:
            code, _, result = self.request('/api/explain', dict(payload, evidenceToken=checked['evidenceToken']))
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(explain.call_args.kwargs['question'], record['description'])
        self.assertEqual(explain.call_args.kwargs['solution_comparison'],
            {'status': 'ok', 'measure': 'lexical-tokens', 'studentTokens': 5,
             'mostConciseKnownTokens': 2, 'longerThanMostConciseKnown': True})
        self.assertNotIn(PRIVATE, json.dumps({'feedback': explain.call_args.args[0], **explain.call_args.kwargs}))
        self.assertEqual(explain.call_args.kwargs['student_body'], payload['body'])

    def test_positive_distance_never_computes_hidden_pool_length_for_luna(self):
        with patch.object(self.app, 'evaluate', return_value=self.canonical()), \
             patch('portal_routes.solution_length_comparison', side_effect=AssertionError('Nonzero guidance has no success comparison')), \
             patch.object(self.app.explainer, 'explain', return_value={'status': 'ok'}) as explain:
            code, _, result = self.request('/api/explain')
        self.assertEqual(code, 200)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(explain.call_args.kwargs['question'], self.record['description'])
        self.assertIsNone(explain.call_args.kwargs['solution_comparison'])

    def test_only_explain_accepts_valid_shape_token_and_never_client_generated_evidence(self):
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Invalid request reached canonical worker')), \
             patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Invalid request reached behavioral worker')):
            for path in ('/api/feedback', '/api/behavior'):
                self.assertEqual(self.request(path, dict(self.base, behaviorToken='a' * 64))[0], 400)
            for token in ('', 'a' * 63, 'a' * 65, 'A' * 64, '../secret', None, 42, ['a' * 64]):
                with self.subTest(token=repr(token)[:20]):
                    self.assertEqual(self.request('/api/explain', dict(self.base, behaviorToken=token))[0], 400)
            for field in ('feedback', 'operations', 'behavior', 'instances', 'categories', 'canonicalForm',
                          'oracleBody', 'studentSource', 'apiKey', 'model', 'question', 'solutionComparison',
                          'solution_comparison', 'complexity'):
                with self.subTest(field=field):
                    self.assertEqual(self.request('/api/explain', dict(self.base, **{field: PRIVATE}))[0], 400)
            for origin in ('https://attacker.invalid', 'https://as.555.is.attacker.invalid'):
                self.assertEqual(self.request('/api/explain', dict(self.base, behaviorToken='a' * 64),
                    {'Origin': origin, 'X-Forwarded-Host': 'as.555.is'})[0], 403)


if __name__ == '__main__':
    unittest.main()
