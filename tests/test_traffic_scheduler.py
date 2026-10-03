"""Ownership and reuse tests using bounded fake evaluators, never JVMs/providers."""
from concurrent.futures import ThreadPoolExecutor
import threading
import time
import unittest

from traffic_scheduler import Scheduler, ResultCache, EvidenceStore, CapacityError, Superseded


class SchedulerTests(unittest.TestCase):
    def setUp(self):
        self.scheduler = Scheduler({'feedback': 1, 'behavior': 1}, queue_seconds=0.3)
        self.addCleanup(self.scheduler.close)
        self.pool = ThreadPoolExecutor(24)
        self.addCleanup(self.pool.shutdown)

    def wait_for(self, predicate):
        end = time.monotonic() + 3
        while not predicate():
            self.assertLess(time.monotonic(), end)
            time.sleep(0.005)

    def test_twenty_identical_checks_have_one_computation_and_private_results(self):
        started, release = threading.Event(), threading.Event()
        def work():
            started.set(); release.wait(3)
            return {'status': 'ok', 'operations': [1]}
        answers = [self.pool.submit(self.scheduler.run, 'feedback', ('K',), work) for _ in range(20)]
        self.assertTrue(started.wait(1))
        self.wait_for(lambda: self.scheduler.stats()['subscribers'] == 20)
        release.set()
        values = [future.result(2) for future in answers]
        values[0]['operations'].append(2)
        self.assertEqual(values[1]['operations'], [1])
        self.assertEqual(self.scheduler.stats()['computations'], 1)
        self.assertEqual(self.scheduler.stats()['joined'], 19)
        self.assertEqual(self.scheduler.stats()['jobs'], 0)

    def test_cancel_A_preserves_B_and_running_owner(self):
        a, b = [self.scheduler.issue_channel('peer') for _ in range(2)]
        self.scheduler.observe(a, 1); self.scheduler.observe(b, 1)
        started, release = threading.Event(), threading.Event()
        def work():
            started.set(); release.wait(3); return {'status': 'ok'}
        fa = self.pool.submit(self.scheduler.run, 'feedback', ('K',), work, channel=a, revision=1)
        self.assertTrue(started.wait(1))
        fb = self.pool.submit(self.scheduler.run, 'feedback', ('K',), work, channel=b, revision=1)
        self.wait_for(lambda: self.scheduler.stats()['subscribers'] == 2)
        self.scheduler.observe(a, 2)
        with self.assertRaises(Superseded): fa.result(1)
        self.assertEqual(self.scheduler.stats()['running'], 1)
        release.set()
        self.assertEqual(fb.result(1)['status'], 'ok')
        self.assertEqual(self.scheduler.stats()['computations'], 1)

    def test_queued_orphan_never_dispatches(self):
        started, release = threading.Event(), threading.Event()
        def blocker():
            started.set(); release.wait(3); return {'status': 'ok'}
        first = self.pool.submit(self.scheduler.run, 'feedback', ('blocker',), blocker)
        self.assertTrue(started.wait(1))
        channel = self.scheduler.issue_channel('peer'); self.scheduler.observe(channel, 1)
        called = []
        future = self.pool.submit(self.scheduler.run, 'feedback', ('old',), lambda: called.append(1), channel=channel, revision=1)
        self.wait_for(lambda: self.scheduler.stats()['jobs'] == 2)
        self.scheduler.observe(channel, 2)
        with self.assertRaises(Superseded): future.result(1)
        release.set(); first.result(1)
        self.assertFalse(called)
        self.assertEqual(self.scheduler.stats()['inputBytes'], 0)

    def test_whitespace_metric_generation_and_pool_identity_do_not_alias(self):
        count = []
        def work():
            count.append(1); return {'status': 'ok'}
        for key in [('g1','canonical','some A','pool1'), ('g1','canonical',' some A','pool1'),
                    ('g1','ast','some A','pool1'), ('g2','canonical','some A','pool1'),
                    ('g1','canonical','some A','pool2')]:
            self.scheduler.run('feedback', key, work)
            self.scheduler.run('feedback', key, work)
        self.assertEqual(len(count), 5)
        self.assertEqual(self.scheduler.stats()['cacheHits'], 5)

    def test_failures_are_not_cached_and_bounds_refuse_before_dispatch(self):
        calls = []
        def failure(): calls.append(1); return {'status':'timeout'}
        self.scheduler.run('feedback', ('K',), failure)
        self.scheduler.run('feedback', ('K',), failure)
        self.assertEqual(len(calls), 2)
        self.scheduler.max_subscribers = 0
        with self.assertRaises(CapacityError):
            self.scheduler.run('feedback', ('other',), failure)
        self.assertEqual(len(calls), 2)

    def test_revision_is_monotone_and_cannot_rebind_draft(self):
        channel = self.scheduler.issue_channel('peer')
        self.scheduler.observe(channel, 5, ('draft A',))
        with self.assertRaises(Superseded): self.scheduler.observe(channel, 4)
        with self.assertRaises(Superseded): self.scheduler.observe(channel, 5, ('draft B',))
        self.scheduler.observe(channel, 6, ('draft B',))
        self.assertTrue(self.scheduler.current(channel, 6))

    def test_cache_expiry_byte_cap_and_copies(self):
        now = [0]
        cache = ResultCache(2, 200, ttl=2, clock=lambda: now[0])
        cache[('a',)] = {'value':[1]}
        cache[('b',)] = {'value':[2]}
        cache[('c',)] = {'value':[3]}
        self.assertEqual(len(cache), 2)
        self.assertLessEqual(cache.bytes, 200)
        one = cache[('c',)]; one['value'].append(8)
        self.assertEqual(cache[('c',)]['value'], [3])
        now[0] = 3
        self.assertIsNone(cache.get(('c',)))
        cache.clear(); self.assertEqual(cache.bytes, 0)

    def test_pinned_evidence_survives_cache_eviction_and_expires_explicitly(self):
        now = [0]
        pins = EvidenceStore(maximum=1, budget=1000, ttl=2, clock=lambda: now[0])
        token = pins.pin(('raw', 'metric', 'channel'), {'status':'ok'})
        self.assertIsNone(pins.pin(('other',), {'status':'ok'}))
        self.assertIsNone(pins.get(token, ('wrong channel',)))
        self.assertEqual(pins.get(token, ('raw','metric','channel')), {'status':'ok'})
        now[0] = 3
        self.assertIsNone(pins.get(token, ('raw','metric','channel')))
        self.assertEqual(pins.bytes, 0)


if __name__ == '__main__':
    unittest.main()
