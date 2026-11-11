"""Corpus scheduling retains a bounded window without losing or retrying cases."""
from concurrent.futures import Future, ThreadPoolExecutor
import unittest

from benchmarks.alloy4fun.live.adapter import bounded_results


class ImmediateExecutor:
    def __init__(self):
        self.submitted = []

    def submit(self, function, item):
        self.submitted.append(item)
        future = Future()
        try:
            future.set_result(function(item))
        except Exception as error:
            future.set_exception(error)
        return future


class BenchmarkSubmissionTests(unittest.TestCase):
    def test_large_lazy_input_never_submits_beyond_window(self):
        executor = ImmediateExecutor()
        results = bounded_results(executor, lambda item: item, iter(range(10000)), max_pending=8)
        seen = []
        for result in results:
            seen.append(result)
            self.assertLessEqual(len(executor.submitted) - len(seen), 7)
        self.assertEqual(sorted(seen), list(range(10000)))
        self.assertEqual(executor.submitted, list(range(10000)))

    def test_parallel_completion_keeps_each_case_and_its_response(self):
        with ThreadPoolExecutor(max_workers=4) as executor:
            results = list(bounded_results(executor, lambda i: (i, {'value': i * i}),
                                           range(100), max_pending=8))
        self.assertEqual(sorted(results), [(i, {'value': i * i}) for i in range(100)])

    def test_failure_propagates_without_retry_and_cancels_queued_work(self):
        failed, queued = Future(), Future()
        failed.set_exception(ValueError('fixture failure'))
        class Executor:
            calls = []
            def submit(self, function, item):
                self.calls.append(item)
                return (failed, queued)[item]
        executor = Executor()
        with self.assertRaisesRegex(ValueError, 'fixture failure'):
            list(bounded_results(executor, lambda i: i, range(10), max_pending=2))
        self.assertEqual(executor.calls, [0, 1])
        self.assertTrue(queued.cancelled())

    def test_consumer_stop_cancels_queued_work(self):
        complete, queued = Future(), Future()
        complete.set_result(0)
        class Executor:
            def submit(self, function, item):
                return (complete, queued)[item]
        results = bounded_results(Executor(), lambda i: i, range(2), max_pending=2)
        self.assertEqual(next(results), 0)
        results.close()
        self.assertTrue(queued.cancelled())

    def test_empty_input_and_invalid_windows(self):
        executor = ImmediateExecutor()
        self.assertEqual(list(bounded_results(executor, lambda i: i, [], max_pending=1)), [])
        for value in (0, -1, True, 1.5):
            with self.subTest(value=value), self.assertRaises(ValueError):
                list(bounded_results(executor, lambda i: i, range(1), max_pending=value))
        self.assertEqual(executor.submitted, [])
