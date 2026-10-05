"""Production HTTP/scheduler/evidence integration with deterministic fake engines."""
from concurrent.futures import ThreadPoolExecutor
import json
import threading
import unittest
from unittest.mock import patch, Mock
from urllib.request import Request, urlopen
from urllib.error import HTTPError

import server


class TrafficPortalTests(unittest.TestCase):
    def setUp(self):
        self.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot',
            traffic_profile=server.TrafficProfile(public_burst=1000, peer_burst=1000))
        self.thread = threading.Thread(target=self.app.serve_forever, daemon=True)
        self.thread.start()
        self.addCleanup(self.stop)
        self.url = 'http://127.0.0.1:' + str(self.app.server_port)
        self.record = self.app.exercises['graphs-inv5']

    def stop(self):
        self.app.shutdown(); self.app.server_close(); self.thread.join()

    def request(self, path, data):
        req = Request(self.url + path, data=json.dumps(data).encode(), headers={'Content-Type':'application/json'})
        try: response = urlopen(req, timeout=8)
        except HTTPError as error: response = error
        with response: return response.status, json.loads(response.read())

    def channel(self):
        status, data = self.request('/api/channel', {})
        self.assertEqual(status, 200)
        return data['channel']

    def payload(self, channel=None, revision=1, metric='canonical'):
        data = {'exerciseId': self.record['id'], 'body': 'some Node', 'revision': revision, 'metric':metric}
        if channel: data['channel'] = channel
        return data

    def raw(self):
        count = len(self.app.correct_pools[self.record['id']])
        return {'status':'ok','metric':server.METRICS['canonical'],'distance':1,
                'canonicalForm':[],'operations':[],
                'comparison':{'strategy':'nearest-known-correct','poolSize':count,
                              'evaluatedCandidates':count,'complete':True}}

    def test_pinned_feedback_does_not_resolve_after_cache_eviction(self):
        channel = self.channel(); payload = self.payload(channel)
        with patch.object(self.app, '_engine', return_value=self.raw()) as engine:
            status, result = self.request('/api/feedback', payload)
        self.assertEqual(status, 200); self.assertEqual(engine.call_count, 1)
        self.app.cache.clear()
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Unnecessary second solve')), \
             patch.object(self.app.explainer, 'explain', return_value={'status':'ok','summary':'Inspect this condition'}) as explain:
            status, guidance = self.request('/api/explain', dict(payload, evidenceToken=result['evidenceToken']))
        self.assertEqual(guidance['status'], 'ok')
        self.assertEqual(explain.call_args.args[0]['distance'], 1)
        self.assertEqual(self.app.scheduler.stats()['computations'], 1)

    def test_pin_is_bound_to_channel_exact_text_metric_and_generation(self):
        channel = self.channel(); payload = self.payload(channel)
        with patch.object(self.app, '_engine', return_value=self.raw()):
            _, result = self.request('/api/feedback', payload)
        token = result['evidenceToken']
        other = self.channel()
        with patch.object(self.app, 'evaluate', side_effect=AssertionError('Must not solve')), \
             patch.object(self.app.explainer, 'explain', side_effect=AssertionError('Wrong pin reached Luna')):
            for change in ({'channel':other}, {'channel':other,'revision':2,'body':' some Node'},
                           {'channel':other,'revision':3,'metric':'ast'}):
                _, response = self.request('/api/explain', dict(payload, evidenceToken=token, **change))
                self.assertEqual(response['status'], 'unavailable')
            self.app.snapshot = self.app.snapshot
            _, response = self.request('/api/explain', dict(payload, evidenceToken=token, revision=2))
            self.assertEqual(response['status'], 'unavailable')

    def test_duplicate_http_checks_join_and_cancellation_preserves_other_caller(self):
        a,b = self.channel(),self.channel()
        started, release = threading.Event(), threading.Event()
        def slow(kind, payload):
            started.set(); release.wait(3); return self.raw()
        with patch.object(self.app, '_engine', side_effect=slow) as engine, ThreadPoolExecutor(2) as executor:
            first = executor.submit(self.request, '/api/feedback', self.payload(a))
            self.assertTrue(started.wait(1))
            second = executor.submit(self.request, '/api/feedback', self.payload(b))
            import time
            deadline=time.monotonic()+2
            while self.app.scheduler.stats()['subscribers']<2:
                self.assertLess(time.monotonic(),deadline); time.sleep(.005)
            self.request('/api/cancel', {'channel':a,'revision':2})
            self.assertEqual(first.result(1)[1]['status'],'superseded')
            release.set()
            self.assertEqual(second.result(2)[1]['status'],'ok')
            self.assertEqual(engine.call_count,1)

    def test_snapshot_generation_changes_key_and_capacity_is_explicit(self):
        payload=self.payload()
        with patch.object(self.app, '_engine', return_value=self.raw()) as engine:
            self.request('/api/feedback',payload)
            self.request('/api/feedback',payload)
            self.app.snapshot=self.app.snapshot
            self.request('/api/feedback',payload)
        self.assertEqual(engine.call_count,2)
        self.app.scheduler.max_jobs=0
        code,result=self.request('/api/feedback',dict(payload,body='some Node // uncached'))
        self.assertEqual(code,503)
        self.assertEqual((result['code'],result['retryable'],result['dispatched']),('capacity',True,False))


class PortalLifecycleTests(unittest.TestCase):
    def test_auth_or_prior_drain_failure_allocates_no_scheduler_or_worker(self):
        for failing in ('AuthManager', 'open_engine_admission'):
            with self.subTest(failing=failing), patch.object(server, 'load_store', return_value=object()), \
                 patch.object(server, 'AuthManager'), patch.object(server, 'open_engine_admission'), \
                 patch.object(server, 'Scheduler') as scheduler, patch.object(server, 'EnginePool') as workers:
                getattr(server, failing).side_effect = OSError('safe startup rejection')
                with self.assertRaises(OSError):
                    server.Portal(('127.0.0.1', 0), engine_mode='persistent')
                scheduler.assert_not_called()
                workers.assert_not_called()

    def test_unreaped_process_prevents_successful_shutdown_acknowledgement(self):
        app = object.__new__(server.Portal)
        app.root = server.ROOT
        app.scheduler = Mock()
        app.engine_pool = Mock()
        app.engine_pool.close.return_value = {'unreaped': 1, 'starting': 0}
        app.admin = Mock()
        app.admin.close.return_value = True
        with patch.object(server, 'close_engine_admission') as admission, \
             patch.object(server, 'wait_for_oneshots', return_value=True) as drain, \
             patch.object(server.BoundedHTTPServer, 'server_close') as sockets:
            with self.assertRaisesRegex(RuntimeError, 'could not drain'):
                app.server_close()
            admission.assert_called_once_with(server.ROOT)
            app.scheduler.close.assert_called_once_with()
            app.engine_pool.close.assert_called_once()
            self.assertGreater(app.engine_pool.close.call_args.kwargs['timeout'], 0)
            self.assertLessEqual(app.engine_pool.close.call_args.kwargs['timeout'], 65)
            app.admin.close.assert_called_once()
            drain.assert_called_once()
            sockets.assert_called_once_with()

    def test_successful_shutdown_uses_one_absolute_drain_deadline(self):
        app = object.__new__(server.Portal)
        app.root = server.ROOT
        app.scheduler = Mock()
        app.engine_pool = Mock()
        app.engine_pool.close.return_value = {'unreaped': 0, 'starting': 0}
        app.admin = Mock()
        app.admin.close.return_value = True
        with patch.object(server, 'close_engine_admission'), \
             patch.object(server, 'wait_for_oneshots', return_value=True) as drain, \
             patch.object(server.BoundedHTTPServer, 'server_close'), \
             patch.object(server.time, 'monotonic', side_effect=[100, 105, 110, 120]):
            app.server_close()
        app.engine_pool.close.assert_called_once_with(timeout=60)
        app.admin.close.assert_called_once_with(timeout=55)
        drain.assert_called_once_with(server.ROOT, timeout=45)

    def test_shutdown_stage_allowances_clamp_rounding_and_never_renew_expired_budget(self):
        cases = (
            ('coarse-clock-rounding', [100.3, 100.3, 100.3, 100.3], [65, 65, 65]),
            ('exact-deadline', [100, 165, 165, 165], [0, 0, 0]),
            ('expired-before-pool', [100, 166, 170, 200], [0, 0, 0]),
            ('expired-between-stages', [100, 160, 166, 170], [5, 0, 0]),
        )
        # Reproduce the Windows failure using exact deterministic samples.
        self.assertGreater((100.3 + 65) - 100.3, 65)
        for name, samples, expected in cases:
            with self.subTest(scenario=name):
                app = object.__new__(server.Portal)
                app.root = server.ROOT
                app.scheduler = Mock()
                app.engine_pool = Mock()
                app.engine_pool.close.return_value = {'unreaped': 0, 'starting': 0}
                app.admin = Mock()
                app.admin.close.return_value = True
                with patch.object(server, 'close_engine_admission'), \
                     patch.object(server, 'wait_for_oneshots', return_value=True) as drain, \
                     patch.object(server.BoundedHTTPServer, 'server_close'), \
                     patch.object(server.time, 'monotonic', side_effect=samples):
                    app.server_close()
                app.engine_pool.close.assert_called_once_with(timeout=expected[0])
                app.admin.close.assert_called_once_with(timeout=expected[1])
                drain.assert_called_once_with(server.ROOT, timeout=expected[2])

    def test_starting_worker_prevents_successful_shutdown_acknowledgement(self):
        app = object.__new__(server.Portal)
        app.root = server.ROOT
        app.scheduler = Mock()
        app.engine_pool = Mock()
        app.engine_pool.close.return_value = {'unreaped': 0, 'starting': 1}
        app.admin = Mock()
        app.admin.close.return_value = True
        with patch.object(server, 'close_engine_admission'), \
             patch.object(server, 'wait_for_oneshots', return_value=True), \
             patch.object(server.BoundedHTTPServer, 'server_close'):
            with self.assertRaisesRegex(RuntimeError, 'could not drain'):
                app.server_close()


if __name__=='__main__':unittest.main()
