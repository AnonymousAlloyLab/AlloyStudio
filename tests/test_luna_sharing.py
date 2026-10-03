"""Bounded concurrent explanation reuse with synthetic credentials/providers only."""
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
import json
import threading
import time
import unittest

import luna
from test_education import behavior, expected, response, trace


def until(predicate):
    deadline = time.monotonic() + 5
    while not predicate():
        if time.monotonic() >= deadline:
            raise AssertionError('Synthetic explanation concurrency deadline exceeded')
        time.sleep(.001)


class FakeProvider:
    def __init__(self, blocked=False, failure=None):
        self.release = threading.Event()
        if not blocked:
            self.release.set()
        self.lock = threading.Lock()
        self.calls = []
        self.failure = failure

    def __call__(self, request, timeout):
        payload = json.loads(request.data)
        with self.lock:
            self.calls.append(payload)
        if not self.release.wait(5):
            raise AssertionError('Synthetic provider was not released')
        if self.failure:
            raise self.failure
        return response(expected(json.loads(payload['input'])))


class ExplanationSharingTests(unittest.TestCase):
    def client(self, provider, **kwargs):
        return luna.Explainer(transport=provider, key_reader=lambda: 'SYNTHETIC_ACCOUNT_A', **kwargs)

    def assert_released(self, client):
        self.assertEqual(client.followers, 0)
        self.assertEqual(client.pending, {})
        self.assertTrue(client.slots.acquire(blocking=False))
        self.assertTrue(client.slots.acquire(blocking=False))
        self.assertFalse(client.slots.acquire(blocking=False))
        client.slots.release(); client.slots.release()

    def test_twelve_identical_callers_share_one_provider_and_receive_independent_results(self):
        provider = FakeProvider(blocked=True)
        client = self.client(provider)
        with ThreadPoolExecutor(max_workers=12) as pool:
            futures = [pool.submit(client.explain, trace(), student_body='some A')]
            until(lambda: len(provider.calls) == 1)
            futures += [pool.submit(client.explain, trace(), student_body='some A') for _ in range(11)]
            try:
                until(lambda: client.followers == 11)
                self.assertEqual(len(client.pending), 1)
                self.assertEqual(len(provider.calls), 1)
            finally:
                provider.release.set()
            results = [future.result(timeout=5) for future in futures]
        self.assertTrue(all(result['status'] == 'ok' for result in results))
        results[0]['operations'][0]['description'] = 'Mutated caller result'
        self.assertNotIn('Mutated caller result', json.dumps(results[1:]))
        repeated = client.explain(trace(), student_body='some A')
        self.assertNotIn('Mutated caller result', json.dumps(repeated))
        self.assertEqual(len(provider.calls), 1)
        self.assertNotIn('SYNTHETIC_ACCOUNT_A', repr(client.cache))
        self.assert_released(client)

    def test_join_is_allowed_when_both_provider_slots_are_busy_but_new_work_is_rejected(self):
        provider = FakeProvider(blocked=True)
        client = self.client(provider)
        with ThreadPoolExecutor(max_workers=3) as pool:
            first = pool.submit(client.explain, trace(), student_body='some A')
            second = pool.submit(client.explain, trace(), student_body='no A')
            until(lambda: len(provider.calls) == 2)
            follower = pool.submit(client.explain, trace(), student_body='some A')
            try:
                until(lambda: client.followers == 1)
                self.assertEqual(client.explain(trace(), student_body='one A')['status'], 'busy')
                self.assertEqual(len(provider.calls), 2)
                self.assertEqual(len(client.pending), 2)
            finally:
                provider.release.set()
            self.assertEqual(first.result(timeout=5), follower.result(timeout=5))
            self.assertEqual(second.result(timeout=5)['status'], 'ok')
        self.assert_released(client)

    def test_global_follower_cap_rejects_without_provider_call_or_resource_leak(self):
        provider = FakeProvider(blocked=True)
        client = self.client(provider, max_followers=2)
        with ThreadPoolExecutor(max_workers=3) as pool:
            leader = pool.submit(client.explain, trace())
            until(lambda: len(provider.calls) == 1)
            followers = [pool.submit(client.explain, trace()) for _ in range(2)]
            try:
                until(lambda: client.followers == 2)
                self.assertEqual(client.explain(trace())['status'], 'busy')
                self.assertEqual(client.followers, 2)
                self.assertEqual(len(provider.calls), 1)
            finally:
                provider.release.set()
            self.assertEqual(leader.result(timeout=5)['status'], 'ok')
            self.assertTrue(all(f.result(timeout=5)['status'] == 'ok' for f in followers))
        self.assert_released(client)

    def test_body_metric_witness_and_account_variations_do_not_join(self):
        for variation in ('body', 'metric', 'witness', 'credential', 'instructions'):
            with self.subTest(variation=variation):
                provider = FakeProvider(blocked=True)
                client = self.client(provider)
                original_instructions = luna.INSTRUCTIONS
                original = trace()
                changed = deepcopy(original)
                first_options = {'student_body': 'some A', 'behavior': behavior()}
                options = deepcopy(first_options)
                with ThreadPoolExecutor(max_workers=2) as pool:
                    first = pool.submit(client.explain, original, **first_options)
                    until(lambda: len(provider.calls) == 1)
                    if variation == 'body':
                        options['student_body'] = 'some  A'
                    elif variation == 'metric':
                        changed.update(metric='acgn-raw-ast-zhang-shasha-distance', breakdown={'ast': 2})
                        for operation in changed['operations']:
                            operation['component'] = 'ast'
                    elif variation == 'witness':
                        options['behavior']['categories'][0]['instances'][0]['states'][0]['signatures'][0]['atoms'] = ['A$9']
                    elif variation == 'credential':
                        client.key_reader = lambda: 'SYNTHETIC_ACCOUNT_B'
                    else:
                        luna.INSTRUCTIONS += '\nAdditional test instruction.'
                    second = pool.submit(client.explain, changed, **options)
                    try:
                        until(lambda: len(provider.calls) == 2)
                        self.assertEqual(len(client.pending), 2)
                        self.assertEqual(client.followers, 0)
                        self.assertNotIn('SYNTHETIC_ACCOUNT', repr(client.pending))
                    finally:
                        provider.release.set()
                        luna.INSTRUCTIONS = original_instructions
                    self.assertEqual(first.result(timeout=5)['status'], 'ok')
                    self.assertEqual(second.result(timeout=5)['status'], 'ok')
                self.assert_released(client)

    def test_provider_failure_is_shared_but_not_cached(self):
        provider = FakeProvider(blocked=True, failure=TimeoutError('SYNTHETIC_PRIVATE_FAILURE'))
        client = self.client(provider)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(client.explain, trace())
            until(lambda: len(provider.calls) == 1)
            follower = pool.submit(client.explain, trace())
            try:
                until(lambda: client.followers == 1)
            finally:
                provider.release.set()
            results = [first.result(timeout=5), follower.result(timeout=5)]
        self.assertEqual(results[0], results[1])
        self.assertEqual(results[0]['status'], 'unavailable')
        self.assertNotIn('SYNTHETIC_PRIVATE_FAILURE', json.dumps(results))
        self.assertEqual(len(client.cache), 0)
        provider.failure = None
        self.assertEqual(client.explain(trace())['status'], 'ok')
        self.assertEqual(len(provider.calls), 2)
        self.assert_released(client)

    def test_unexpected_terminal_exception_still_releases_followers_and_slots(self):
        provider = FakeProvider(blocked=True, failure=RuntimeError('SYNTHETIC_PRIVATE_FAILURE'))
        client = self.client(provider)
        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(client.explain, trace())
            until(lambda: len(provider.calls) == 1)
            follower = pool.submit(client.explain, trace())
            try:
                until(lambda: client.followers == 1)
            finally:
                provider.release.set()
            with self.assertRaises(RuntimeError):
                first.result(timeout=5)
            result = follower.result(timeout=5)
        self.assertEqual(result['status'], 'unavailable')
        self.assertNotIn('SYNTHETIC_PRIVATE_FAILURE', json.dumps(result))
        self.assertEqual(len(client.cache), 0)
        self.assert_released(client)

    def test_follower_wait_has_a_deadline_and_does_not_release_running_provider_slot(self):
        provider = FakeProvider(blocked=True)
        client = self.client(provider, timeout=.03)
        with ThreadPoolExecutor(max_workers=1) as pool:
            leader = pool.submit(client.explain, trace())
            until(lambda: len(provider.calls) == 1)
            try:
                self.assertEqual(client.explain(trace())['status'], 'unavailable')
                self.assertEqual(client.followers, 0)
                self.assertEqual(len(client.pending), 1)
                self.assertTrue(client.slots.acquire(blocking=False))
                self.assertFalse(client.slots.acquire(blocking=False))
                client.slots.release()
            finally:
                provider.release.set()
            self.assertEqual(leader.result(timeout=5)['status'], 'ok')
        self.assert_released(client)

    def test_cache_byte_budget_counts_exact_evidence_keys_and_oversize_bypasses(self):
        provider = FakeProvider()
        measured = self.client(provider)
        measured.explain(trace(), student_body='some A')
        size = measured.cache.bytes
        self.assertGreater(size, len(luna.INSTRUCTIONS.encode()))
        tiny = self.client(provider, cache_bytes=size - 1)
        self.assertEqual(tiny.explain(trace(), student_body='some A')['status'], 'ok')
        self.assertEqual(len(tiny.cache), 0)
        self.assertEqual(tiny.cache.bytes, 0)
        bounded = self.client(provider, cache_bytes=size + 8)
        bounded.explain(trace(), student_body='some A')
        bounded.explain(trace(), student_body='lone A')
        self.assertEqual(len(bounded.cache), 1)
        self.assertLessEqual(bounded.cache.bytes, size + 8)
        calls = len(provider.calls)
        bounded.explain(trace(), student_body='some A')
        self.assertEqual(len(provider.calls), calls + 1)
        bounded.cache.clear()
        self.assertEqual(bounded.cache.bytes, 0)

    def test_cache_expires_at_120_seconds_without_sliding_on_hits(self):
        provider = FakeProvider()
        now = [0.0]
        client = self.client(provider, clock=lambda: now[0])
        client.explain(trace())
        now[0] = 119.999
        self.assertEqual(client.explain(trace())['status'], 'ok')
        self.assertEqual(len(provider.calls), 1)
        now[0] = 120
        self.assertEqual(client.explain(trace())['status'], 'ok')
        self.assertEqual(len(provider.calls), 2)
        self.assertEqual(len(client.cache), 1)

    def test_default_limits_and_129_unique_prompts_evict_without_exceeding_budget(self):
        provider = FakeProvider()
        client = self.client(provider)
        self.assertEqual(client.max_followers, 32)
        self.assertEqual(client.cache.limit, 128)
        self.assertEqual(client.cache.budget, 8 * 1048576)
        self.assertEqual(client.cache.ttl, 120)
        for index in range(129):
            client.explain(trace(0), student_body=f'some A // {index}')
            self.assertLessEqual(len(client.cache), 128)
            self.assertLessEqual(client.cache.bytes, 8 * 1048576)
        self.assertEqual(len(client.cache), 128)
        client.explain(trace(0), student_body='some A // 0')
        self.assertEqual(len(provider.calls), 130)

    def test_malformed_evidence_and_disabled_account_do_not_enter_pending_ledger(self):
        provider = FakeProvider()
        client = self.client(provider)
        self.assertEqual(client.explain({})['status'], 'unavailable')
        client.key_reader = lambda: ''
        self.assertEqual(client.explain(trace())['status'], 'disabled')
        self.assertEqual(provider.calls, [])
        self.assert_released(client)


if __name__ == '__main__':
    unittest.main()
