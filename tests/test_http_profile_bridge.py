"""TCFG02 mutation controls against its immutable, source-bound proof fixture.

Successor request-reader changes have their own TING01 bridge. The historical
verifier intentionally rejects that new layout instead of inheriting VERIFIED.
"""
import hashlib
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

WORKSPACE = Path(__file__).resolve().parents[1]
ROOT = WORKSPACE / 'closure/traffic-refinement/evidence/tcfg02-20261004T161717Z-ff079208/inputs'
sys.path.insert(0, str(WORKSPACE / 'scripts'))
import http_profile_bridge as bridge


class ProfileBridgeTests(unittest.TestCase):
    def setUp(self):
        self.source = (ROOT / 'traffic_profile.py').read_text()
        self.runtime = (ROOT / 'traffic_http.py').read_text()

    def test_historical_fixture_and_verifier_match_their_frozen_manifest(self):
        manifest = json.loads((ROOT.parent / 'manifest.json').read_text())
        for name, expected in manifest.items():
            with self.subTest(name=name):
                self.assertEqual(hashlib.sha256((ROOT / name).read_bytes()).hexdigest(), expected)
        self.assertEqual(hashlib.sha256((WORKSPACE / 'scripts/http_profile_bridge.py').read_bytes()).hexdigest(),
                         manifest['scripts/http_profile_bridge.py'])

    def test_successor_reader_layout_requires_its_own_bridge(self):
        with self.assertRaisesRegex(bridge.BridgeRejected, 'layout changed'):
            bridge.linkage(ROOT, (WORKSPACE / 'traffic_http.py').read_text())

    def test_exact_source_schema_and_three_constructor_bindings(self):
        result = bridge.check(ROOT)
        self.assertEqual((result['status'], result['fields'], result['relations']), ('PASS', 23, 3))
        self.assertEqual(set(result['constructors']), {
            'Admission.__init__', 'TokenBucket.from_initial', 'BoundedHTTPServer.__init__'})

    def test_missing_duplicate_miswired_or_misclassified_field_rejected(self):
        for old, new in (
            ('    peer_entries: int = 1024\n', ''),
            ('    peer_entries: int = 1024\n', '    peer_entries: int = 1024\n    peer_entries: int = 1024\n'),
            ("'peer_entries': self.peer_entries", "'peer_entries': self.peer_burst"),
            ("'peer_entries': profile.peer_entries", "'peer_entries': profile.peer_burst"),
            ("name != 'peer_idle_seconds'", "name != 'idle_seconds'"),
            ('maximum=300', 'maximum=301'),
            ('header_seconds: float = 5.0', 'header_seconds: float = 5'),
        ):
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(ROOT, self.source.replace(old, new))

    def test_type_copy_clock_or_return_bypass_rejected(self):
        for old, new in (
            ('type(profile) is not TrafficProfile', 'not isinstance(profile, TrafficProfile)'),
            ('return TrafficProfile(**values)', 'return profile'),
            ('type(control) is not bool', 'False'),
            ('type(now) is not int', 'not isinstance(now, int)'),
            ('now = clock()', 'now = clock()\n    now = clock()'),
            ("'owners': set()", "'owners': []"),
            ('return profile, {', 'return None, {'),
        ):
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(ROOT, self.source.replace(old, new))

    def test_additional_execution_rebinding_and_dynamic_diagnostic_rejected(self):
        for source in (self.source + '\nTrafficProfile = object\n',
                       self.source + '\nnormalized_profile = lambda x: x\n',
                       self.source.replace("'An exact HTTP traffic profile is required.'", 'str(profile)'),
                       self.source.replace('    now = clock()', '    print(profile)\n    now = clock()')):
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(ROOT, source)

    def test_each_relation_and_initial_state_arithmetic_changes_lowering(self):
        original = bridge.generate(ROOT, self.source)
        for old, new in (
            ('self.line_bytes > self.header_bytes', 'self.line_bytes >= self.header_bytes'),
            ('self.header_count > 100', 'self.header_count > 101'),
            ('self.public_handlers + self.control_handlers > 256', 'self.public_handlers + self.control_handlers > 257'),
            ('profile.control_handlers if control else profile.public_handlers', 'profile.public_handlers if control else profile.control_handlers'),
            ('burst * 1000000000', 'burst * 999999999'),
            ("'active': 0", "'active': 1"),
            ("'credit': capacity", "'credit': 0"),
        ):
            with self.subTest(old=old):
                self.assertNotEqual(bridge.generate(ROOT, self.source.replace(old, new)), original)

    def test_unknown_arithmetic_and_dynamic_lane_rejected(self):
        for old, new in (
            ('burst * 1000000000', 'float(burst) * 1000000000'),
            ('if control else profile.public_rate', 'if bool(control) else profile.public_rate'),
            ("'active': 0", "'active': rate"),
            ('self.header_count > 100', 'self.header_count is None'),
        ):
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(ROOT, self.source.replace(old, new))

    def test_runtime_consumer_bypass_miswire_and_allocation_reordering_rejected(self):
        for old, new in (
            ('profile, state = initial_admission(profile, control, clock)', 'profile, state = (profile, {})'),
            ("self.limit = state['limit']", "self.limit = state['rate']"),
            ("bucket.credit, bucket.last = state['credit'], state['last']", "bucket.credit, bucket.last = 0, state['last']"),
            ('self.bucket = TokenBucket.from_initial(state)', 'self.bucket = TokenBucket(1, 1, 0)'),
            ('profile = normalized_profile(TrafficProfile() if traffic_profile is None else traffic_profile)', 'profile = traffic_profile or TrafficProfile()'),
            ('profile, state = initial_admission(profile, control, clock)', 'self.lock = threading.Lock()\n        profile, state = initial_admission(profile, control, clock)'),
        ):
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.linkage(ROOT, self.runtime.replace(old, new))

    def test_runtime_rebinding_extra_methods_and_dynamic_class_defaults_rejected(self):
        for source in (self.runtime + '\nAdmission = object\n',
                       self.runtime + '\nglobals()["initial_admission"] = lambda *a: None\n',
                       self.runtime.replace('class Admission:', 'class Admission:\n    def __getattribute__(self, name):\n        return 0'),
                       self.runtime.replace('clock=time.monotonic_ns', 'clock=print("side effect")')):
            with self.assertRaises(bridge.BridgeRejected):
                bridge.linkage(ROOT, source)

    def test_independent_schema_domain_cannot_silently_override_runtime_dispatch(self):
        scratch = WORKSPACE / 'build/trf-closure/http-profile-bridge-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            root = Path(directory)
            spec_path = root / 'closure/traffic-refinement/http-profile-spec.json'
            spec_path.parent.mkdir(parents=True)
            spec = json.loads((ROOT / spec_path.relative_to(root)).read_text())
            next(f for f in spec['fields'] if f['name'] == 'peer_idle_seconds')['domain'] = 'positive_seconds'
            spec_path.write_text(json.dumps(spec))
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(root, self.source)


if __name__ == '__main__':
    unittest.main()
