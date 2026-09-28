"""Metric selection binds worker requests, caches, HTTP responses, and Luna evidence."""
import copy
import json
import subprocess
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import luna
import server
import test_education as education


class MetricModeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()
        cls.url = 'http://127.0.0.1:' + str(cls.app.server_port)
        cls.record = cls.app.exercises['graphs-inv5']

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()

    def setUp(self):
        self.app.cache.clear()
        self.app.behavior_cache.clear()

    def feedback(self, metric):
        result = education.trace(1)
        result['metric'] = server.METRICS[metric]
        if metric == 'ast':
            result['breakdown'] = {'ast': 1}
            result['operations'][0]['component'] = 'ast'
            result['canonicalForm'] = []
            result['astSize'] = 3
        size = len(self.app.correct_pools[self.record['id']])
        result['comparison'] = {'strategy': 'nearest-known-correct', 'poolSize': size,
                                'evaluatedCandidates': size, 'complete': True}
        return result

    def completed(self, metric):
        return subprocess.CompletedProcess([], 0, json.dumps(self.feedback(metric)), '')

    def request(self, path, **updates):
        data = dict(exerciseId=self.record['id'], body='some Node', revision=3, **updates)
        request = Request(self.url + path, data=json.dumps(data).encode(),
                          headers={'Content-Type': 'application/json'})
        try:
            response = urlopen(request, timeout=10)
        except HTTPError as error:
            response = error
        with response:
            return response.status, json.loads(response.read())

    def test_metric_cache_never_reuses_the_other_distance_or_trace(self):
        def worker(*args, **kwargs):
            request = json.loads(kwargs['input'])
            self.assertEqual(len(request['referenceBodies']), len(self.app.correct_pools[self.record['id']]))
            return self.completed(request['metric'])
        with patch('server.subprocess.run', side_effect=worker) as run:
            for metric in ('canonical', 'ast', 'canonical', 'ast'):
                result = self.app.evaluate(self.record, 'some Node', metric)
                self.assertEqual(result['metric'], server.METRICS[metric])
                self.assertEqual(set(result['breakdown']), {'ast'} if metric == 'ast' else {'temporal', 'quantifier', 'matrix'})
            self.assertEqual(run.call_count, 2)

    def test_http_echoes_metric_and_preserves_default_canonical(self):
        for selected in (None, 'canonical', 'ast'):
            metric = selected or 'canonical'
            with patch('server.subprocess.run', return_value=self.completed(metric)):
                code, result = self.request('/api/feedback', **({} if selected is None else {'metric': metric}))
            self.assertEqual(code, 200)
            self.assertEqual(result['requestedMetric'], metric)
            self.assertEqual(result['metric'], server.METRICS[metric])
            self.assertEqual(result['revision'], 3)
            if metric == 'ast':
                self.assertEqual(result['canonicalForm'], [])
                self.assertEqual(result['astSize'], 3)

    def test_unknown_metrics_and_behavior_metric_fields_are_rejected_before_worker(self):
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Invalid metric reached worker')):
            for value in ('AST', '', 'oracle', None, 0, [], {}, True):
                for path in ('/api/feedback', '/api/explain'):
                    code, _ = self.request(path, metric=value)
                    self.assertEqual(code, 400)
        code, _ = self.request('/api/behavior', metric='ast')
        self.assertEqual(code, 400)

    def test_wrong_worker_metric_is_not_cached_or_given_to_luna(self):
        with patch('server.subprocess.run', return_value=self.completed('canonical')):
            answer = self.app.evaluate(self.record, 'some Node', 'ast')
        self.assertEqual(answer['status'], 'error')
        self.assertNotIn('distance', answer)
        self.assertEqual(len(self.app.cache), 0)

    def test_ast_explanation_receives_only_the_selected_trace_and_returns_identity(self):
        raw = self.feedback('ast')
        raw.update(oracleSource='PRIVATE_TARGET', referenceBodies=['PRIVATE_TARGET'])
        with patch('server.subprocess.run', return_value=subprocess.CompletedProcess([], 0, json.dumps(raw), '')), \
             patch.object(self.app.explainer, 'explain', return_value={'status': 'ok'}) as explain:
            code, answer = self.request('/api/explain', metric='ast')
        self.assertEqual(code, 200)
        self.assertEqual(answer['requestedMetric'], 'ast')
        evidence = explain.call_args.args[0]
        self.assertEqual(evidence['metric'], server.METRICS['ast'])
        self.assertNotIn('PRIVATE_TARGET', json.dumps(evidence))
        self.assertEqual(explain.call_args.kwargs['student_body'], 'some Node')

    def test_luna_ast_evidence_is_complete_and_never_calls_it_canonical(self):
        feedback = self.feedback('ast')
        feedback['operations'][0].update(targetTerm='PRIVATE_TARGET', path='PRIVATE_PATH')
        result = luna.prompt_education(feedback, 'some Node', education.behavior())
        self.assertEqual(result['trace']['metric'], 'ACGN raw AST Zhang-Shasha')
        self.assertEqual(result['trace']['breakdown'], {'ast': 1})
        self.assertEqual(result['trace']['operations'][0]['id'], 'operation-1')
        self.assertEqual(result['canonicalForm'], [])
        self.assertEqual(sum(len(category['instances']) for category in result['behavior']['categories']), 12)
        self.assertNotIn('PRIVATE_', json.dumps(result))
        for invalid in ('invalid', 'acgn-reward'):
            with self.assertRaises(ValueError):
                luna.prompt_education(dict(feedback, metric=invalid))
        with self.assertRaises(ValueError):
            luna.prompt_education(dict(feedback, metric=server.METRICS['canonical']))

    def test_ast_multiplicity_operator_hints_are_accepted_without_target_expressions(self):
        feedback = self.feedback('ast')
        for operator in ('some->some', 'one->', '->one', 'lone->lone'):
            feedback['operations'][0].update(kind='replace', replacementOperator=operator)
            result = luna.prompt_education(feedback, 'some Node')
            self.assertEqual(result['trace']['operations'][0]['replacementOperator'], operator)

    def test_luna_transport_cache_and_per_step_ids_are_metric_bound(self):
        bodies = [self.feedback(metric) for metric in ('canonical', 'ast')]
        calls = []
        def provider(request, **kwargs):
            payload = json.loads(request.data)
            evidence = json.loads(payload['input'])
            calls.append(evidence['trace']['metric'])
            return education.response(education.expected(evidence))
        client = luna.Explainer(transport=provider, key_reader=lambda: 'test-placeholder')
        for trace in bodies + bodies:
            result = client.explain(trace, student_body='some Node')
            self.assertEqual(result['status'], 'ok')
            self.assertEqual([operation['id'] for operation in result['operations']], ['operation-1'])
        self.assertEqual(calls, ['ACGN CanDis Fast Rewrite IR', 'ACGN raw AST Zhang-Shasha'])


if __name__ == '__main__':
    unittest.main()
