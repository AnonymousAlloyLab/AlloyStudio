"""Frozen-input and scope-escalation negative controls for the TING01 gate."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_ingress_deadlines as gate


class IngressDeadlineGateTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/trf-closure/ingress-deadline-gate-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        prior = json.loads((ROOT / gate.PRIOR_MANIFEST).read_text())['files']
        self.source_names = set(prior)
        for name in gate.REQUIRED_INPUTS | self.source_names:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('frozen fixture\n')
        for name in (gate.PRIOR_MANIFEST, gate.DEPENDENCY_BLOCK, gate.DEPENDENCY_REPORT,
                     'closure/traffic-obligations.json', gate.SPEC):
            shutil.copyfile(ROOT / name, self.root / name)
        (self.root / gate.SOURCE_MANIFEST).write_text(json.dumps({'files': {
            name: gate.digest(self.root / name) for name in self.source_names}}))
        self.block = {'schemaVersion': 1, 'id': 'TING01', 'scope': gate.SCOPE,
            'flags': gate.FLAGS, 'leanToolchain': gate.LEAN_TOOLCHAIN, 'allowlistedAxioms': [], 'requiredCleanBuilds': 2,
            'requiredReviews': 6, 'umbrellaObligationsClosed': [],
            'modules': list(gate.MODULES), 'claims': [gate.CLAIM], 'trust': gate.TRUST,
            'excluded': gate.EXCLUDED, 'inputs': {
                name: gate.digest(self.root / name) for name in gate.required_inputs(self.root)}}
        (self.root / gate.BLOCK).write_text(json.dumps(self.block))

    def update_hash(self, relative):
        self.block['inputs'][relative] = gate.digest(self.root / relative)

    def test_valid_fixture_retains_child_only_scope(self):
        result = gate.inputs(self.root, self.block)
        self.assertIn(gate.BLOCK, result)
        self.assertFalse(gate.CLAIM['closesParent'])
        self.assertEqual(self.block['umbrellaObligationsClosed'], [])

    def test_frozen_source_change_is_rejected(self):
        (self.root / 'traffic_http.py').write_text('changed')
        with self.assertRaisesRegex(gate.Rejected, 'changed'):
            gate.inputs(self.root, self.block)

    def test_every_registered_core_input_is_required(self):
        for name in gate.REQUIRED_INPUTS:
            changed = copy.deepcopy(self.block)
            del changed['inputs'][name]
            with self.subTest(name=name), self.assertRaises(gate.Rejected):
                gate.inputs(self.root, changed)

    def test_source_manifest_cannot_drop_a_historical_dependency(self):
        name = next(name for name in sorted(self.source_names) if name not in gate.REQUIRED_INPUTS)
        path = self.root / gate.SOURCE_MANIFEST
        source = json.loads(path.read_text())
        del source['files'][name]
        path.write_text(json.dumps(source))
        del self.block['inputs'][name]
        self.update_hash(gate.SOURCE_MANIFEST)
        with self.assertRaisesRegex(gate.Rejected, 'dropped'):
            gate.inputs(self.root, self.block)

    def test_source_manifest_hashes_must_match_actual_frozen_input(self):
        path = self.root / gate.SOURCE_MANIFEST
        source = json.loads(path.read_text())
        source['files']['traffic_http.py'] = '0' * 64
        path.write_text(json.dumps(source))
        self.update_hash(gate.SOURCE_MANIFEST)
        with self.assertRaisesRegex(gate.Rejected, 'manifest hash mismatch'):
            gate.inputs(self.root, self.block)

    def test_scope_trust_and_proof_policy_cannot_be_relaxed(self):
        for name, value in [('scope', 'full-TRF01'), ('allowlistedAxioms', ['propext']),
                            ('requiredCleanBuilds', 1), ('requiredReviews', 0),
                            ('leanToolchain', 'leanprover/lean4:v0.0.0'),
                            ('umbrellaObligationsClosed', ['TRF-01']), ('trust', []),
                            ('excluded', []), ('claims', [dict(gate.CLAIM, closesParent=True)])]:
            changed = copy.deepcopy(self.block)
            changed[name] = value
            with self.subTest(name=name), self.assertRaises(gate.Rejected):
                gate.inputs(self.root, changed)

    def test_original_obligation_cannot_be_rewritten_or_closed_by_spec(self):
        original = json.loads((self.root / gate.SPEC).read_text())
        for key, value in (('closesParent', True), ('original', {}), ('parent', 'TRF-00')):
            changed = copy.deepcopy(original)
            changed[key] = value
            (self.root / gate.SPEC).write_text(json.dumps(changed))
            self.update_hash(gate.SPEC)
            with self.subTest(key=key), self.assertRaisesRegex(gate.Rejected, 'Original TRF-01'):
                gate.inputs(self.root, self.block)

    def test_verified_dependency_cannot_be_replaced(self):
        path = self.root / gate.DEPENDENCY_REPORT
        report = json.loads(path.read_text())
        report['status'] = 'BLOCKED'
        path.write_text(json.dumps(report))
        self.update_hash(gate.DEPENDENCY_REPORT)
        with self.assertRaisesRegex(gate.Rejected, 'dependency identity'):
            gate.inputs(self.root, self.block)

    def test_unregistered_proof_module_is_rejected(self):
        (self.root / 'formal/ingress_deadlines/Hidden.lean').write_text('import Std\n')
        with self.assertRaisesRegex(gate.Rejected, 'Unregistered'):
            gate.inputs(self.root, self.block)

    def test_empty_missing_duplicate_and_axiomatic_inventories_are_rejected(self):
        rows = [{'name': name, 'module': 'IngressDeadlines.Spec', 'levelParameters': [],
                 'typeSha256': '0' * 64, 'axioms': []} for name in sorted(gate.REQUIRED_THEOREMS)]
        path = self.root / 'formal/ingress_deadlines/theorems.json'
        path.write_text(json.dumps({'theorems': rows}))
        self.assertEqual(set(gate.inventory(self.root)), gate.REQUIRED_THEOREMS)
        for label, changed in [('empty', []), ('missing', rows[:-1]), ('duplicate', rows + rows[:1]),
                               ('axiomatic', [dict(rows[0], axioms=['propext']), *rows[1:]])]:
            path.write_text(json.dumps({'theorems': changed}))
            with self.subTest(label=label), self.assertRaises(gate.Rejected):
                gate.inventory(self.root)

    def test_snapshot_refuses_source_swap_before_consumption(self):
        manifest = gate.inputs(self.root, self.block)
        (self.root / 'traffic_http.py').write_text('changed before snapshot')
        with self.assertRaises(gate.Rejected):
            gate.snapshot_inputs(self.root, self.root / 'snapshot', manifest)

    def test_snapshot_is_independent_of_later_worktree_edit(self):
        manifest = gate.inputs(self.root, self.block)
        frozen = gate.snapshot_inputs(self.root, self.root / 'snapshot', manifest)
        (self.root / 'traffic_http.py').write_text('changed after snapshot')
        self.assertEqual(gate.frozen_bytes(frozen, 'traffic_http.py', manifest), b'frozen fixture\n')

    def test_registered_language_rejects_proof_escapes(self):
        for source in ('axiom bad : False', 'theorem bad : True := by sorry',
                       'set_option maxHeartbeats 0', 'import Foreign.Module',
                       'theorem bad : True := by native_decide'):
            with self.subTest(source=source), self.assertRaises(gate.Rejected):
                gate.check_proof_source(source, set(gate.MODULES))


if __name__ == '__main__':
    unittest.main()
