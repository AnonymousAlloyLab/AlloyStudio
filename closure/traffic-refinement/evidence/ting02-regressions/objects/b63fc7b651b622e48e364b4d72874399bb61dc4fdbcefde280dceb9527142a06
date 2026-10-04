"""Finite profile counterexamples must fail before runtime resources exist."""
import math
import unittest
from unittest.mock import patch

from engine_workers import EnginePool
from luna import Explainer
from runtime_dependencies import ProcessBudget, run_engine
from server import Portal
from traffic_http import TrafficProfile
from traffic_limits import validated_int, validated_seconds
from traffic_scheduler import EvidenceStore, ResultCache, Scheduler


class IntegerSubclass(int):
    pass


class FloatSubclass(float):
    pass


class PrivateValue:
    def __repr__(self):
        raise AssertionError('A rejected configuration must not be rendered.')


class NumericTypeImpersonator(type):
    def __eq__(cls, other):
        return other is int or other is float


class ForgedNumber(metaclass=NumericTypeImpersonator):
    ratio_calls = 0

    def as_integer_ratio(self):
        self.ratio_calls += 1
        return (1, 1)


class NumericGuardTests(unittest.TestCase):
    def test_overloaded_metaclass_equality_cannot_impersonate_builtin_numbers(self):
        value = ForgedNumber()
        self.assertIsNot(type(value), int)
        self.assertIsNot(type(value), float)
        with self.assertRaises(ValueError):
            validated_seconds(value)
        self.assertEqual(value.ratio_calls, 0)

    def test_counts_use_exact_bounded_integer_domain(self):
        for value in (True, False, None, '1', 1.0, IntegerSubclass(1),
                      -1, 0, 67108865, 10**1000, float('nan'),
                      float('inf'), PrivateValue()):
            with self.subTest(kind=type(value).__name__), self.assertRaises(ValueError):
                validated_int(value)
        for value in (1, 512, 67108864):
            self.assertIs(validated_int(value), value)
        self.assertEqual(validated_int(0, minimum=0), 0)

    def test_integer_bound_parameters_are_checked(self):
        for bounds in ({'minimum': True}, {'maximum': True}, {'minimum': -1},
                       {'minimum': 2, 'maximum': 1}, {'maximum': float('inf')},
                       {'minimum': 1.0}):
            with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                validated_int(1, **bounds)

    def test_duration_exact_ratio_rejects_nonfinite_and_huge_integer(self):
        for value in (True, False, None, '1', IntegerSubclass(1), FloatSubclass(1),
                      -1, 0, -0.0, 86401, 10**1000, float('nan'),
                      float('inf'), -float('inf'), PrivateValue()):
            with self.subTest(kind=type(value).__name__), self.assertRaises(ValueError):
                validated_seconds(value)
        for value in (1, 0.1, math.nextafter(0.0, 1.0), 86400, 86400.0):
            self.assertIs(validated_seconds(value), value)
        with self.assertRaises(ValueError):
            validated_seconds(math.nextafter(86400.0, math.inf))

    def test_zero_is_only_admissible_for_explicit_nonnegative_duration(self):
        for value in (0, 0.0, -0.0):
            self.assertIs(validated_seconds(value, minimum_zero=True), value)
        for value in (-1, math.nextafter(0.0, -math.inf)):
            with self.assertRaises(ValueError):
                validated_seconds(value, minimum_zero=True)

    def test_duration_bound_parameters_are_checked(self):
        for bounds in ({'minimum_zero': 1}, {'maximum': True}, {'maximum': 0},
                       {'maximum': -1}, {'maximum': float('inf')}, {'maximum': 1.0}):
            with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                validated_seconds(1, **bounds)


class ConstructorBoundaryTests(unittest.TestCase):
    def rejected_without_state(self, cls, args=(), kwargs=None):
        instance = cls.__new__(cls)
        with self.assertRaises(ValueError):
            cls.__init__(instance, *args, **(kwargs or {}))
        self.assertEqual(vars(instance), {}, 'Invalid profile allocated runtime state.')

    def test_nan_and_fractional_process_capacity_counterexamples_are_rejected(self):
        for lane in ('feedback', 'behavior', 'admin'):
            for value in (float('nan'), float('inf'), .5, True, 0, -1, 5):
                with self.subTest(lane=lane, kind=type(value).__name__):
                    self.rejected_without_state(ProcessBudget, kwargs={lane: value})

    def test_http_duration_huge_integer_is_sanitized_value_error_not_overflow(self):
        for field in ('header_seconds', 'body_seconds', 'write_seconds', 'idle_seconds'):
            for value in (10**1000, float('nan'), float('inf'), True, FloatSubclass(1)):
                with self.subTest(field=field, kind=type(value).__name__):
                    with self.assertRaisesRegex(ValueError, '^HTTP deadlines must be positive, finite'):
                        TrafficProfile(**{field: value})

    def test_process_profile_initial_state_and_combined_bound(self):
        for feedback in (1, 2):
            budget = ProcessBudget(feedback=feedback)
            self.assertEqual(budget.stats(), {
                'reserved': 0, 'limit': feedback + 2, 'highWater': 0,
                'lanes': {'feedback': 0, 'behavior': 0, 'admin': 0}})
            self.assertLessEqual(budget.total_limit, 4)

    def test_worker_recycling_cannot_be_disabled_with_nonfinite_profile(self):
        for field in ('max_tasks', 'max_parse_units', 'max_age_seconds', 'startup_timeout'):
            for value in (float('nan'), float('inf'), True, 0, -1, 10**1000):
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(EnginePool, (PrivateValue(), 'unused'), {field: value})
        for field in ('max_tasks', 'max_parse_units'):
            self.rejected_without_state(EnginePool, (PrivateValue(), 'unused'), {field: 1.5})

    def test_worker_counts_reject_boolean_fractional_and_excess_lanes(self):
        for field, values in (('feedback_workers', (True, .5, 0, 3)),
                              ('behavior_workers', (True, .5, 0, 2))):
            for value in values:
                self.rejected_without_state(EnginePool, (PrivateValue(), 'unused'), {field: value})

    def test_scheduler_rejects_invalid_lanes_before_any_threads(self):
        for lanes in ({}, {'feedback': 1}, {'feedback': 1, 'other': 1},
                      {'feedback': 0, 'behavior': 1}, {'feedback': True, 'behavior': 1},
                      {'feedback': 1, 'behavior': 0}, {'feedback': 3, 'behavior': 1},
                      {'feedback': 1, 'behavior': 2}, [('feedback', 1)]):
            with self.subTest(lanes=lanes):
                self.rejected_without_state(Scheduler, (lanes,))

    def test_scheduler_rejects_invalid_ownership_bounds_before_any_threads(self):
        for field in ('max_jobs', 'max_subscribers', 'input_bytes', 'queue_seconds'):
            for value in (float('nan'), float('inf'), True, 0, -1, 10**1000):
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(Scheduler, kwargs={field: value})

    def test_zero_entry_and_nan_ttl_cache_counterexamples_are_rejected(self):
        for field in ('count', 'budget', 'ttl'):
            for value in (float('nan'), float('inf'), True, 0, -1, 10**1000):
                kwargs = {'count': 1, 'budget': 100, field: value}
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(ResultCache, kwargs=kwargs)

    def test_evidence_pins_have_finite_entry_byte_and_age_limits(self):
        for field in ('maximum', 'budget', 'ttl'):
            for value in (float('nan'), float('inf'), True, 0, -1, 10**1000):
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(EvidenceStore, kwargs={field: value})

    def test_explainer_rejects_invalid_profile_before_provider_state(self):
        for field in ('timeout', 'cache_bytes', 'cache_ttl', 'max_followers'):
            for value in (float('nan'), float('inf'), True, 0, -1, 10**1000):
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(Explainer, kwargs={field: value})

    def test_portal_validates_direct_constructor_before_store_or_threads(self):
        for field, values in (('timeout', (float('nan'), float('inf'), True, 0, -1, 121, 10**1000)),
                              ('workers', (float('nan'), float('inf'), True, 0, -1, 33, 1.5))):
            for value in values:
                with self.subTest(field=field, kind=type(value).__name__):
                    self.rejected_without_state(Portal, (('127.0.0.1', 0),),
                                                {'root': PrivateValue(), field: value})

    def test_invalid_oneshot_deadline_precedes_admission_and_child_launch(self):
        for timeout in (float('nan'), float('inf'), True, 0, -1, 10**1000):
            with patch('runtime_dependencies._root_key') as root_key:
                with self.assertRaises(ValueError):
                    run_engine(['unused'], root=PrivateValue(), timeout=timeout)
                root_key.assert_not_called()

    def test_invalid_reservation_deadline_preserves_empty_state(self):
        budget = ProcessBudget()
        for timeout in (float('nan'), float('inf'), True, -1, 10**1000):
            with self.assertRaises(ValueError):
                budget.reserve('feedback', timeout)
            self.assertEqual(budget.stats()['reserved'], 0)


if __name__ == '__main__':
    unittest.main()
