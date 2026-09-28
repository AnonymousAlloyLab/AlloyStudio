"""Finite bridge registry, generation and corruption controls; no Lean download."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import bridge_policies as bridge


class BridgePolicyTests(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((ROOT / 'formal/bridges/policies.json').read_text())

    def test_generated_kernels_match_current_registered_table(self):
        bridge.validate(self.data)
        bridge.check_generated(ROOT, self.data)

    def test_tables_have_unique_bounded_integer_indices_and_exact_arity(self):
        for field, bad in [('arity', True), ('arity', 11), ('acceptedMasks', [True]),
                           ('acceptedMasks', [-1]), ('acceptedMasks', [1024]),
                           ('acceptedMasks', [1, 1]), ('acceptedMasks', [2, 1])]:
            data = copy.deepcopy(self.data)
            data['feedbackSuccess'][field] = bad
            with self.subTest(field=field, bad=bad), self.assertRaises(ValueError):
                bridge.validate(data)
        for name in bridge.SCHEMA:
            data = copy.deepcopy(self.data); del data[name]
            with self.subTest(missing=name), self.assertRaises(ValueError):
                bridge.validate(data)

    def test_ambiguous_or_absent_generated_region_rejected(self):
        region = bridge.render_js(self.data)
        for source in ['', region + region, bridge.END + bridge.BEGIN]:
            with self.subTest(source=source[:40]), self.assertRaises(ValueError):
                bridge.js_region(source)

    def test_corrupted_runtime_cannot_inherit_export_check(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for path in ['web/app.js', 'engine/src/live/BridgePolicies.java', 'formal/bridges/policies.json']:
                target = root / path; target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((ROOT / path).read_bytes())
            bridge.check_generated(root, self.data)
            java = root / 'engine/src/live/BridgePolicies.java'
            java.write_text(java.read_text().replace('true, false, true, true', 'true, true, true, true'))
            with self.assertRaisesRegex(ValueError, 'Java policy kernel'):
                bridge.check_generated(root, self.data)

    def test_certificate_covers_every_boolean_vector_including_false_rows(self):
        source = bridge.certificate_source(self.data)
        self.assertEqual(source.count('\ntheorem '), 9224)
        for name, (arity, function) in bridge.SCHEMA.items():
            self.assertEqual(source.count('theorem ' + name + '_'), 2 ** arity)
            self.assertIn(function, source)
        self.assertIn('poolChoose_1 : AlloyStudio.PoolBridge.choosePolicy [true, false] = false', source)
        self.assertIn('guidanceSuccess_8191', source)

    def test_registry_is_one_to_one_and_stays_within_narrow_scope(self):
        registry = json.loads((ROOT / 'formal/bridges/registry.json').read_text())
        objects = registry['objects']
        self.assertEqual(len(objects), 4)
        self.assertEqual({o['policy'] for o in objects}, set(bridge.SCHEMA))
        self.assertEqual(len({o['id'] for o in objects}), 4)
        self.assertEqual(len({o['implementation'] for o in objects}), 4)
        for obj in objects:
            self.assertEqual(obj['arity'], bridge.SCHEMA[obj['policy']][0])
            self.assertTrue(obj['theorems'])
            self.assertIn('L22', obj['supports'])
            for path in obj['implementationFiles']:
                self.assertTrue((ROOT / path).is_file())
        self.assertTrue(registry['trust'])
        self.assertTrue(registry['excluded'])


if __name__ == '__main__':
    unittest.main()
