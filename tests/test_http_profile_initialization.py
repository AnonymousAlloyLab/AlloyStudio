"""Finite HTTP profile/initial-state integration evidence for TCFG02.

These tests perform no socket binding, JVM launch or provider call. The formal
profile/projection bridge, rather than these finite cases, carries the universal
restricted-program claim.
"""
from dataclasses import asdict, fields
import unittest
from types import SimpleNamespace
from unittest.mock import Mock, patch

from traffic_http import Admission, BoundedHTTPServer
from traffic_profile import TrafficProfile, initial_admission, normalized_profile


class IntegerSubclass(int):
    pass


class FloatSubclass(float):
    pass


class UntrustedValue:
    def __bool__(self):
        raise AssertionError('Configuration validation evaluated user-defined truthiness.')

    def __repr__(self):
        raise AssertionError('Rejected configuration must not be rendered.')


class ProfileSubclass(TrafficProfile):
    pass


class ProfileTypeImpersonator(type):
    def __eq__(cls, other):
        return other is TrafficProfile


class ForgedProfile(metaclass=ProfileTypeImpersonator):
    def __getattr__(self, name):
        raise AssertionError('A forged profile was inspected before its type was rejected.')


PROFILE_FIELDS = {
    'public_handlers', 'control_handlers', 'public_burst', 'public_rate',
    'control_burst', 'control_rate', 'peer_burst', 'peer_rate', 'peer_entries',
    'peer_idle_seconds', 'backlog', 'control_backlog', 'line_bytes',
    'header_bytes', 'header_count', 'header_seconds', 'body_seconds',
    'write_seconds', 'idle_seconds', 'json_depth', 'response_bytes',
    'public_cache_bytes', 'public_cache_entries',
}
STATE_KEYS = {
    'limit', 'capacity', 'rate', 'credit', 'last', 'active', 'peak',
    'accepted', 'rejected', 'owners', 'anonymous_owners', 'peers',
}


def corrupted_profile(**changes):
    profile = TrafficProfile()
    for name, value in changes.items():
        object.__setattr__(profile, name, value)
    return profile


class HTTPProfileProjectionTests(unittest.TestCase):
    def test_instance_dataclass_metadata_cannot_replace_registered_values(self):
        source = TrafficProfile(public_handlers=17, control_handlers=3)
        object.__setattr__(source, '__dataclass_fields__', {})
        copied = normalized_profile(source)
        self.assertIsNot(source, copied)
        self.assertEqual((copied.public_handlers, copied.control_handlers), (17, 3))

    def test_complete_profile_is_copied_without_changing_accepted_values(self):
        source = TrafficProfile(header_seconds=0.125, body_seconds=300,
                                write_seconds=2.5, idle_seconds=1)
        copied = normalized_profile(source)
        self.assertIs(type(copied), TrafficProfile)
        self.assertIsNot(copied, source)
        self.assertEqual({field.name for field in fields(copied)}, PROFILE_FIELDS)
        self.assertEqual(asdict(copied), asdict(source))
        for name in PROFILE_FIELDS:
            self.assertIs(getattr(copied, name), getattr(source, name))

    def test_every_field_is_revalidated_at_ingress(self):
        for name in sorted(PROFILE_FIELDS):
            for invalid in (0, -1, True, float('nan'), float('inf'), UntrustedValue()):
                with self.subTest(field=name, kind=type(invalid).__name__):
                    with self.assertRaises(ValueError):
                        normalized_profile(corrupted_profile(**{name: invalid}))

    def test_exact_numeric_types_and_existing_field_maxima_are_checked(self):
        for name in sorted(PROFILE_FIELDS):
            invalid_values = (IntegerSubclass(1), FloatSubclass(1))
            maximum = 300 if name in {
                'header_seconds', 'body_seconds', 'write_seconds', 'idle_seconds'
            } else 67108864
            for invalid in (*invalid_values, maximum + 1):
                with self.subTest(field=name, kind=type(invalid).__name__):
                    with self.assertRaises(ValueError):
                        normalized_profile(corrupted_profile(**{name: invalid}))

    def test_existing_three_relationships_are_rechecked_on_corrupted_profiles(self):
        changes = (
            {'line_bytes': 32769},
            {'header_count': 101},
            {'public_handlers': 31, 'control_handlers': 256},
        )
        for change in changes:
            with self.subTest(change=change), self.assertRaises(ValueError):
                normalized_profile(corrupted_profile(**change))
        boundary = TrafficProfile(public_handlers=128, control_handlers=128,
                                  line_bytes=32768, header_bytes=32768,
                                  header_count=100)
        self.assertEqual(asdict(normalized_profile(boundary)), asdict(boundary))

    def test_profile_type_cannot_be_duck_typed_subclassed_or_impersonated(self):
        invalid_profiles = (
            SimpleNamespace(**asdict(TrafficProfile())),
            ProfileSubclass(), ForgedProfile(), UntrustedValue(),
        )
        for value in invalid_profiles:
            with self.subTest(kind=type(value).__name__), self.assertRaises(ValueError):
                normalized_profile(value)

    def test_bad_profile_and_selector_are_rejected_before_clock_sampling(self):
        invalid_inputs = (
            (SimpleNamespace(**asdict(TrafficProfile())), False),
            (corrupted_profile(control_handlers=0), False),
            (corrupted_profile(public_handlers=31, control_handlers=256), False),
            (TrafficProfile(), 'false'),
            (TrafficProfile(), 0),
            (TrafficProfile(), 1),
            (TrafficProfile(), None),
            (TrafficProfile(), UntrustedValue()),
        )
        for profile, control in invalid_inputs:
            clock = Mock(side_effect=AssertionError('Invalid configuration sampled the clock.'))
            with self.subTest(profile=type(profile).__name__, control=type(control).__name__):
                with self.assertRaises(ValueError):
                    initial_admission(profile, control, clock)
                clock.assert_not_called()

    def test_initial_projection_has_exact_selected_values_and_empty_occupancy(self):
        profile = TrafficProfile(public_handlers=17, control_handlers=3,
                                 public_burst=19, control_burst=5,
                                 public_rate=7, control_rate=2)
        for control in (False, True):
            for tick in (-10**30, -1, 0, 123456789, 10**30):
                with self.subTest(control=control, tick=tick):
                    clock = Mock(return_value=tick)
                    copied, state = initial_admission(profile, control, clock)
                    clock.assert_called_once_with()
                    self.assertIs(type(copied), TrafficProfile)
                    self.assertIsNot(copied, profile)
                    self.assertEqual(set(state), STATE_KEYS)
                    self.assertEqual(state['limit'], 3 if control else 17)
                    self.assertEqual(state['capacity'], (5 if control else 19) * 1000000000)
                    self.assertEqual(state['rate'], 2 if control else 7)
                    self.assertEqual(state['credit'], state['capacity'])
                    self.assertIs(state['last'], tick)
                    for name in ('active', 'peak', 'accepted', 'rejected'):
                        self.assertIs(type(state[name]), int)
                        self.assertEqual(state[name], 0)
                    for name in ('owners', 'anonymous_owners', 'peers'):
                        self.assertEqual(len(state[name]), 0)

    def test_clock_result_requires_exact_builtin_integer(self):
        invalid_ticks = (True, False, None, 'not-an-integer-tick', 1.0,
                         float('nan'), float('inf'), IntegerSubclass(1),
                         UntrustedValue())
        for tick in invalid_ticks:
            with self.subTest(kind=type(tick).__name__):
                clock = Mock(return_value=tick)
                with self.assertRaises(ValueError):
                    initial_admission(TrafficProfile(), False, clock)
                clock.assert_called_once_with()


class HTTPConstructorIntegrationTests(unittest.TestCase):
    def assert_admission_rejected_before_allocation(self, profile, control=False, clock=None):
        instance = Admission.__new__(Admission)
        if clock is None:
            clock = Mock(return_value=0)
        with patch('traffic_http.threading.Lock') as lock, \
                patch('traffic_http.threading.Thread') as thread, \
                patch('traffic_http.ThreadingHTTPServer.__init__') as socket_init:
            with self.assertRaises(ValueError):
                Admission.__init__(instance, profile, control=control, clock=clock)
            lock.assert_not_called()
            thread.assert_not_called()
            socket_init.assert_not_called()
        self.assertEqual(vars(instance), {})

    def test_admission_rejects_bypassed_profile_and_truthy_selector_before_allocation(self):
        namespace = SimpleNamespace(**asdict(TrafficProfile()))
        namespace.control_handlers = 0
        for profile, control in (
            (namespace, False),
            (corrupted_profile(public_handlers=31, control_handlers=256), False),
            (TrafficProfile(), 'false'),
            (TrafficProfile(), UntrustedValue()),
        ):
            clock = Mock(side_effect=AssertionError('Invalid configuration sampled the clock.'))
            with self.subTest(profile=type(profile).__name__, control=type(control).__name__):
                self.assert_admission_rejected_before_allocation(profile, control, clock)
                clock.assert_not_called()

    def test_invalid_or_raising_clock_precedes_admission_allocation(self):
        for tick in ('not-an-integer-tick', True, 1.0, float('nan'), IntegerSubclass(0)):
            with self.subTest(kind=type(tick).__name__):
                clock = Mock(return_value=tick)
                self.assert_admission_rejected_before_allocation(TrafficProfile(), clock=clock)
                clock.assert_called_once_with()
        clock = Mock(side_effect=ValueError('Synthetic initial clock failure.'))
        self.assert_admission_rejected_before_allocation(TrafficProfile(), clock=clock)
        clock.assert_called_once_with()

    def test_bounded_server_rejects_invalid_ingress_before_locks_threads_or_socket_init(self):
        namespace = SimpleNamespace(**asdict(TrafficProfile()))
        namespace.line_bytes = namespace.header_bytes + 1
        for profile, control in (
            (namespace, False),
            (corrupted_profile(public_handlers=31, control_handlers=256), False),
            (TrafficProfile(), 'false'),
            (TrafficProfile(), UntrustedValue()),
        ):
            instance = BoundedHTTPServer.__new__(BoundedHTTPServer)
            with self.subTest(profile=type(profile).__name__, control=type(control).__name__):
                with patch('traffic_http.threading.Lock') as lock, \
                        patch('traffic_http.threading.Thread') as thread, \
                        patch('traffic_http.ThreadingHTTPServer.__init__') as socket_init:
                    with self.assertRaises(ValueError):
                        BoundedHTTPServer.__init__(instance, ('127.0.0.1', 0), object,
                                                   traffic_profile=profile, control=control)
                    lock.assert_not_called()
                    thread.assert_not_called()
                    socket_init.assert_not_called()
                self.assertEqual(vars(instance), {})

    def test_runtime_state_matches_projection_and_is_detached_from_caller_profile(self):
        for control in (False, True):
            original = TrafficProfile()
            expected_profile, expected = initial_admission(original, control, lambda: -7)
            clock = Mock(return_value=-7)
            gate = Admission(original, control=control, clock=clock)
            clock.assert_called_once_with()
            self.assertIsNot(gate.profile, original)
            self.assertEqual(asdict(gate.profile), asdict(expected_profile))
            self.assertEqual(gate.limit, expected['limit'])
            for name in ('capacity', 'rate', 'credit', 'last'):
                self.assertEqual(getattr(gate.bucket, name), expected[name])
            for name in ('active', 'peak', 'accepted', 'rejected'):
                self.assertEqual(getattr(gate, name), expected[name])
            for name in ('owners', 'anonymous_owners', 'peers'):
                self.assertEqual(len(getattr(gate, name)), 0)
            object.__setattr__(original, 'public_handlers', 999)
            object.__setattr__(original, 'control_handlers', 999)
            object.__setattr__(original, 'peer_burst', 999)
            object.__setattr__(original, 'header_seconds', 0)
            self.assertEqual(asdict(gate.profile), asdict(expected_profile))
            self.assertEqual(gate.limit, expected['limit'])
            self.assertEqual(gate.bucket.capacity, expected['capacity'])

    def test_separate_admission_instances_do_not_share_occupied_collections(self):
        left = Admission(TrafficProfile(), clock=lambda: 0)
        right = Admission(TrafficProfile(), clock=lambda: 0)
        for name in ('owners', 'anonymous_owners', 'peers'):
            self.assertIsNot(getattr(left, name), getattr(right, name))
        self.assertIsNone(left.reserve('synthetic-peer'))
        self.assertEqual(left.active, 1)
        self.assertEqual(right.active, 0)
        self.assertEqual(len(right.owners), 0)
        self.assertEqual(len(right.peers), 0)
        left.release()

    def test_bounded_server_and_public_views_retain_detached_profile_values(self):
        original = TrafficProfile()
        expected = asdict(original)
        with patch('traffic_http.ThreadingHTTPServer.__init__', return_value=None) as socket_init:
            server = BoundedHTTPServer(('127.0.0.1', 0), object, traffic_profile=original)
        socket_init.assert_called_once()
        self.assertIsNot(server.traffic_profile, original)
        object.__setattr__(original, 'public_handlers', 999)
        object.__setattr__(original, 'header_seconds', 0)
        object.__setattr__(original, 'public_cache_bytes', 0)
        self.assertEqual(asdict(server.traffic_profile), expected)
        self.assertEqual(asdict(server.http_admission.profile), expected)
        self.assertEqual(asdict(server.http_public_views.profile), expected)


if __name__ == '__main__':
    unittest.main()
