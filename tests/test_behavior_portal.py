"""Behavioral score, witness disclosure, and independent HTTP worker boundaries."""
import copy
import json
import math
import subprocess
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server
from luna import prompt_trace


PRIVATE = 'PRIVATE_BEHAVIOR_CANARY'
CATEGORY_FLAGS = {'both': (True, True), 'undercoverage': (True, False),
                  'overcoverage': (False, True), 'neither': (False, False)}


def valid_behavior():
    """A finite sample with four true positives/negatives and four errors."""
    witness = {'traceLength': 1, 'loopState': 0, 'truncated': False, 'states': [
        {'index': 0, 'signatures': [{'label': 'A', 'atoms': ['A$0', 'A$1']}],
         'relations': [{'label': 'A.r', 'arity': 2, 'tuples': [['A$0', 'A$1']]}]}]}
    return {'status': 'ok', 'metric': 'acgn-reward', 'score': 0.25,
            'scoreStatus': 'ok', 'scoreReason': 'OK',
            'scope': {'overall': 3, 'bitwidth': 3, 'maxSequence': 3, 'poolSize': 100,
                      'minTrace': 1, 'maxTrace': 10, 'moduleFacts': True},
            'sampling': {'positiveTested': 4, 'positiveAccepted': 2,
                         'negativeTested': 4, 'negativeRejected': 2,
                         'semanticCounterexamples': 0},
            'categories': [{'id': name, 'oracle': flags[0], 'student': flags[1],
                            'status': 'sat', 'enumerationComplete': True,
                            'instances': [copy.deepcopy(witness)]}
                           for name, flags in CATEGORY_FLAGS.items()]}


def completed(answer):
    return subprocess.CompletedProcess([], 0, json.dumps(answer), '')


class BehaviorProjectionTests(unittest.TestCase):
    def test_known_schema_preserves_four_categories_and_finite_score(self):
        result = server.project_behavior(valid_behavior())
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(result['score'], 0.25)
        self.assertEqual(result['metric'], 'acgn-reward')
        self.assertEqual(result['scope']['moduleFacts'], True)
        self.assertEqual({category['id'] for category in result['categories']}, set(CATEGORY_FLAGS))
        for category in result['categories']:
            self.assertEqual((category['oracle'], category['student']), CATEGORY_FLAGS[category['id']])
            self.assertEqual(category['instances'][0]['states'][0]['relations'][0]['tuples'], [['A$0', 'A$1']])

    def test_unknown_fields_are_stripped_at_every_witness_level(self):
        answer = valid_behavior()
        def inject(value):
            if isinstance(value, dict):
                for child in list(value.values()):
                    inject(child)
                value.update(oracleSource=PRIVATE, command=PRIVATE, skolems=[PRIVATE], xml=PRIVATE)
            elif isinstance(value, list):
                for child in value:
                    inject(child)
        inject(answer)
        result = server.project_behavior(answer)
        self.assertNotIn(PRIVATE, json.dumps(result))
        self.assertNotIn('oracleSource', json.dumps(result))
        self.assertEqual(result['score'], 0.25)
        self.assertEqual(len(result['categories']), 4)

    def test_nonfinite_out_of_range_and_inconsistent_scores_are_rejected(self):
        for score in (True, False, '0.25', -0.001, 1.001, math.nan, math.inf, -math.inf, None, 0.5):
            with self.subTest(score=score):
                answer = valid_behavior()
                answer['score'] = score
                with self.assertRaises(ValueError):
                    server.project_behavior(answer)

    def test_sampling_and_scope_cannot_claim_unperformed_or_different_analysis(self):
        variants = [('sampling', 'positiveTested', 101), ('sampling', 'positiveAccepted', 5),
                    ('sampling', 'negativeTested', -1), ('sampling', 'negativeRejected', True),
                    ('sampling', 'semanticCounterexamples', 3),
                    ('scope', 'overall', 4), ('scope', 'bitwidth', 32),
                    ('scope', 'moduleFacts', False), ('scope', 'poolSize', 0),
                    ('scope', 'minTrace', 0), ('scope', 'maxTrace', 100)]
        for section, key, value in variants:
            with self.subTest(section=section, key=key, value=value):
                answer = valid_behavior()
                answer[section][key] = value
                with self.assertRaises(ValueError):
                    server.project_behavior(answer)

    def test_category_polarities_status_and_cardinality_are_verified(self):
        variants = []
        answer = valid_behavior(); answer['categories'].pop(); variants.append(answer)
        answer = valid_behavior(); answer['categories'][1] = copy.deepcopy(answer['categories'][0]); variants.append(answer)
        for key, value in (('id', 'unknown'), ('oracle', False), ('student', 1), ('status', 'unknown'),
                           ('enumerationComplete', 'yes'), ('status', 'unsat'), ('instances', [])):
            answer = valid_behavior(); answer['categories'][0][key] = value; variants.append(answer)
        answer = valid_behavior(); answer['categories'][0]['instances'] *= 4; variants.append(answer)
        for index, answer in enumerate(variants):
            with self.subTest(index=index):
                with self.assertRaises(ValueError):
                    server.project_behavior(answer)

    def test_temporal_state_bounds_and_tuple_arities_cannot_be_forged(self):
        changes = [('traceLength', 0), ('traceLength', 11), ('traceLength', 2),
                   ('loopState', -2), ('loopState', 1), ('loopState', True),
                   ('truncated', 'yes'), ('stringsAnonymized', 'yes')]
        variants = []
        for key, value in changes:
            answer = valid_behavior(); answer['categories'][0]['instances'][0][key] = value; variants.append(answer)
        answer = valid_behavior(); answer['categories'][0]['instances'][0]['states'][0]['index'] = 1; variants.append(answer)
        answer = valid_behavior(); answer['categories'][0]['instances'][0]['states'] *= 2; variants.append(answer)
        for key, value in (('arity', 0), ('arity', True), ('tuples', [['A$0']]),
                           ('tuples', [[0, 'A$1']]), ('label', '\ud800')):
            answer = valid_behavior()
            answer['categories'][0]['instances'][0]['states'][0]['relations'][0][key] = value
            variants.append(answer)
        for index, answer in enumerate(variants):
            with self.subTest(index=index):
                with self.assertRaises(ValueError):
                    server.project_behavior(answer)

    def test_rounding_and_semantic_correction_are_bound_to_sample_counts(self):
        answer = valid_behavior()
        answer['sampling'].update(positiveAccepted=1, negativeRejected=1)
        answer['score'] = 0.063  # 1 / 16 = 0.0625, ACGN display rounds half up.
        self.assertEqual(server.project_behavior(answer)['score'], 0.063)
        answer['score'] = 0.062
        with self.assertRaises(ValueError):
            server.project_behavior(answer)
        answer = valid_behavior()
        answer['sampling'].update(positiveTested=8, positiveAccepted=8,
                                  negativeTested=8, negativeRejected=8,
                                  semanticCounterexamples=2)
        answer['score'] = 0.970  # Both mismatch solvers SAT although samples agreed.
        self.assertEqual(server.project_behavior(answer)['score'], 0.970)
        answer['sampling']['semanticCounterexamples'] = 0
        answer['score'] = 1.0
        with self.assertRaises(ValueError):
            server.project_behavior(answer)

    def test_caps_and_truncation_are_explicit_not_silent_partial_evidence(self):
        answer = valid_behavior()
        instance = answer['categories'][0]['instances'][0]
        instance.update(traceLength=2, loopState=1, truncated=True, stringsAnonymized=True)
        projected = server.project_behavior(answer)['categories'][0]['instances'][0]
        self.assertTrue(projected['truncated'])
        self.assertTrue(projected['stringsAnonymized'])
        self.assertEqual(projected['traceLength'], 2)
        self.assertEqual(len(projected['states']), 1)
        answer['categories'][0]['enumerationComplete'] = False
        with self.assertRaises(ValueError):
            server.project_behavior(answer)
        answer['categories'][0]['instances'] *= 3
        self.assertFalse(server.project_behavior(answer)['categories'][0]['enumerationComplete'])
        for kind, count in (('signatures', 129), ('relations', 129), ('atoms', 129), ('tuples', 513)):
            with self.subTest(kind=kind):
                answer = valid_behavior()
                state = answer['categories'][0]['instances'][0]['states'][0]
                if kind in ('signatures', 'relations'):
                    state[kind] *= count
                elif kind == 'atoms':
                    state['signatures'][0]['atoms'] = ['A$0'] * count
                else:
                    state['relations'][0]['tuples'] *= count
                with self.assertRaises(ValueError):
                    server.project_behavior(answer)

    def test_unavailable_reward_keeps_sat_witnesses_without_fake_zero_score(self):
        answer = valid_behavior()
        answer.update(score=None, scoreStatus='unavailable', scoreReason='ORACLE_POSITIVE_UNSAT')
        answer['sampling'].update(positiveTested=0, positiveAccepted=0)
        for category in answer['categories']:
            if category['oracle']:
                category.update(status='unsat', enumerationComplete=True, instances=[])
        result = server.project_behavior(answer)
        self.assertIsNone(result['score'])
        self.assertEqual(result['scoreStatus'], 'unavailable')
        self.assertEqual([item['status'] for item in result['categories']], ['unsat', 'unsat', 'sat', 'sat'])
        answer['score'] = 0
        with self.assertRaises(ValueError):
            server.project_behavior(answer)

    def test_private_worker_errors_and_behavior_data_do_not_enter_luna_prompt(self):
        feedback = {'distance': 0, 'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': 0},
                    'operations': [], 'behavior': dict(valid_behavior(), privateMetadata=PRIVATE),
                    'behavioralSimilarity': PRIVATE, 'categories': [PRIVATE]}
        result = prompt_trace(feedback)
        self.assertNotIn(PRIVATE, json.dumps(result))
        self.assertNotIn('behavior', result)
        self.assertNotIn('categories', result)


class BehaviorWorkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', timeout=2, workers=1)
        cls.record = cls.app.exercises['graphs-inv5']

    @classmethod
    def tearDownClass(cls):
        cls.app.server_close()

    def setUp(self):
        self.app.cache.clear()
        self.app.behavior_cache.clear()

    def test_worker_receives_original_oracle_and_learner_body_without_correct_pool(self):
        body = 'all n: Node | n not in n.adj'
        with patch('server.subprocess.run', return_value=completed(valid_behavior())) as run:
            result = self.app.evaluate_behavior(self.record, body)
        self.assertEqual(result['status'], 'ok')
        payload = json.loads(run.call_args.kwargs['input'])
        self.assertEqual(set(payload), {'studentSource', 'oracleSource', 'studentBody', 'predicate'})
        self.assertEqual(payload['studentBody'], body)
        self.assertEqual(payload['studentSource'], server.model(self.record, body))
        self.assertEqual(payload['oracleSource'], server.model(self.record, self.record['oracleBody']))
        self.assertEqual(payload['predicate'], self.record['predicate'])
        self.assertIn('live.BehaviorFeedback', run.call_args.args[0])
        self.assertGreater(run.call_args.kwargs['timeout'], 29)
        self.assertLessEqual(run.call_args.kwargs['timeout'], 30)
        self.assertFalse(run.call_args.kwargs['check'])

    def test_evidence_store_is_separate_and_new_requests_always_observe_worker(self):
        body = 'some Node'
        other_record = self.app.exercises['graphs-inv1']
        comparison = {'strategy': 'nearest-known-correct', 'poolSize': len(self.app.correct_pools[self.record['id']]),
                      'evaluatedCandidates': len(self.app.correct_pools[self.record['id']]), 'complete': True}
        canonical = {'status': 'ok', 'distance': 1, 'comparison': comparison}
        with patch('server.subprocess.run', side_effect=[completed(canonical)] + [completed(valid_behavior())] * 4) as run:
            self.assertEqual(self.app.evaluate(self.record, body)['distance'], 1)
            first = self.app.evaluate_behavior(self.record, body)
            self.assertEqual(first, self.app.evaluate_behavior(self.record, body))
            self.app.evaluate_behavior(self.record, body + ' // changed')
            self.app.evaluate_behavior(other_record, body)
            self.assertEqual(self.app.evaluate(self.record, body)['distance'], 1)
        self.assertEqual(run.call_count, 5)
        self.assertEqual(len(self.app.cache), 1)
        self.assertEqual(len(self.app.behavior_cache), 3)

    def test_timeout_worker_failure_and_invalid_json_are_sanitized_and_not_cached(self):
        failures = [subprocess.TimeoutExpired('java', 1, output=PRIVATE, stderr=PRIVATE),
                    subprocess.CompletedProcess([], 1, PRIVATE, PRIVATE),
                    subprocess.CompletedProcess([], 0, PRIVATE, PRIVATE),
                    completed({'status': 'engine_error', 'diagnostics': [{'message': PRIVATE}], 'oracleSource': PRIVATE}),
                    completed(dict(valid_behavior(), score=math.nan)),
                    OSError(PRIVATE)]
        for index, failure in enumerate(failures):
            with self.subTest(index=index):
                kwargs = {'side_effect': failure} if isinstance(failure, Exception) else {'return_value': failure}
                with patch('server.subprocess.run', **kwargs):
                    result = self.app.evaluate_behavior(self.record, 'some Node')
                self.assertIn(result['status'], ('timeout', 'error', 'unavailable'))
                self.assertNotIn('score', result)
                self.assertNotIn('categories', result)
                self.assertNotIn(PRIVATE, json.dumps(result))
                self.assertEqual(len(self.app.behavior_cache), 0)
        with patch('server.subprocess.run', return_value=completed(valid_behavior())):
            self.assertEqual(self.app.evaluate_behavior(self.record, 'some Node')['status'], 'ok')

    def test_behavior_and_canonical_worker_capacity_are_independent(self):
        started, release = threading.Event(), threading.Event()
        def slow_behavior():
            started.set()
            release.wait(3)
            return {'status': 'ok'}
        from concurrent.futures import ThreadPoolExecutor
        with ThreadPoolExecutor(1) as executor:
            running = executor.submit(self.app.scheduler.run, 'behavior', ('occupied',), slow_behavior)
            self.assertTrue(started.wait(1))
            try:
                comparison = {'strategy': 'nearest-known-correct', 'poolSize': len(self.app.correct_pools[self.record['id']]),
                              'evaluatedCandidates': len(self.app.correct_pools[self.record['id']]), 'complete': True}
                with patch('server.subprocess.run', return_value=completed({'status': 'ok', 'distance': 0, 'comparison': comparison})):
                    self.assertEqual(self.app.evaluate(self.record, 'some Node')['status'], 'ok')
            finally:
                release.set()
            self.assertEqual(running.result(1)['status'], 'ok')



class BehaviorHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', public_origins=('https://as.555.is',))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)
        cls.base = {'exerciseId': 'graphs-inv5', 'body': 'some Node', 'revision': 21}

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown(); cls.app.server_close(); cls.thread.join()

    def request(self, path='/api/behavior', data=None, headers=None):
        request = Request(self.url + path, data=json.dumps(data or self.base).encode(),
                          headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, json.loads(response.read())

    def test_route_uses_only_behavior_worker_and_preserves_exact_revision_identity(self):
        with patch.object(self.app, 'evaluate_behavior', return_value=valid_behavior()) as behavior, \
             patch.object(self.app, 'evaluate', side_effect=AssertionError('Canonical worker must not run')), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Luna must not run')):
            for revision, body in ((21, 'some Node'), (22, 'no Node')):
                code, headers, answer = self.request(data=dict(self.base, revision=revision, body=body),
                                                     headers={'Origin': 'https://as.555.is'})
                self.assertEqual(code, 200)
                self.assertEqual(answer['revision'], revision)
                self.assertEqual(answer['exerciseId'], self.base['exerciseId'])
                self.assertEqual(behavior.call_args.args[1], body)
                self.assertEqual(headers['Cache-Control'], 'no-store')
                self.assertIn("default-src 'self'", headers['Content-Security-Policy'])

    def test_private_inputs_credentials_and_invalid_revisions_are_rejected(self):
        with patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Invalid request reached worker')):
            for key in ('oracleBody', 'oracleSource', 'studentSource', 'studentBody', 'predicate', 'referenceBodies',
                        'apiKey', 'openaiKey', 'model', 'scope', 'categories'):
                with self.subTest(key=key):
                    self.assertEqual(self.request(data=dict(self.base, **{key: PRIVATE}))[0], 400)
            for revision in (True, -1, 2**53, '22', None):
                self.assertEqual(self.request(data=dict(self.base, revision=revision))[0], 400)
            self.assertEqual(self.request(data=dict(self.base, exerciseId='unknown'))[0], 404)
            self.assertEqual(self.request('/api/behavior-extra')[0], 404)
            self.assertEqual(self.request(headers={'Content-Type': 'text/plain'})[0], 415)

    def test_body_escape_and_cross_origin_spoofing_never_reach_behavior_worker(self):
        with patch.object(self.app, 'evaluate_behavior', side_effect=AssertionError('Invalid request reached worker')):
            for body in ('} pred injected {', '', '/*', '\x00', 'x' * 8193):
                code, _, answer = self.request(data=dict(self.base, body=body))
                self.assertEqual(code, 200)
                self.assertEqual(answer['status'], 'invalid')
            self.assertEqual(self.request(data=dict(self.base, body='x' * 20000))[0], 413)
            for origin in ('https://attacker.invalid', 'https://as.555.is.attacker.invalid',
                           'https://user:password@as.555.is', 'null'):
                self.assertEqual(self.request(headers={'Origin': origin,
                    'X-Forwarded-Host': 'as.555.is', 'X-Forwarded-Proto': 'https'})[0], 403)


if __name__ == '__main__':
    unittest.main()
