"""Input mutation and scope-escalation controls for the TCFG02 verifier."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_http_profile as gate


class ProfileGateTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/http-profile-gate-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in gate.REQUIRED_INPUTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('frozen fixture\n')
        dependency = {'id': 'TCFG01', 'inputs': {name: gate.digest(self.root / name) for name in ('traffic_limits.py', *gate.SOURCES[:3])}}
        (self.root / 'formal/traffic_config/block.json').write_text(json.dumps(dependency))
        self.block = {'schemaVersion': 1, 'id': 'TCFG02',
            'scope': 'http-profile-and-initial-state', 'flags': gate.FLAGS,
            'allowlistedAxioms': [], 'requiredCleanBuilds': 2, 'requiredReviews': 6,
            'umbrellaObligationsClosed': [], 'modules': list(gate.MODULES),
            'claims': [gate.CLAIM], 'trust': gate.TRUST, 'excluded': gate.EXCLUDED,
            'inputs': {name: gate.digest(self.root / name) for name in gate.REQUIRED_INPUTS}}
        (self.root / gate.BLOCK).write_text(json.dumps(self.block))

    def test_reused_dependency_cannot_be_silently_replaced(self):
        path = self.root / 'formal/traffic_config/block.json'
        value = json.loads(path.read_text())
        value['inputs']['traffic_limits.py'] = 'wrong'
        path.write_text(json.dumps(value))
        self.block['inputs']['formal/traffic_config/block.json'] = gate.digest(path)
        with self.assertRaisesRegex(gate.Rejected, 'dependency'):
            gate.inputs(self.root, self.block)

    def test_required_claim_theorems_cannot_be_dropped(self):
        value = json.loads((ROOT / 'formal/traffic_profile/theorems.json').read_text())
        value['theorems'] = [t for t in value['theorems'] if t['name'] not in gate.REQUIRED_THEOREMS]
        (self.root / 'formal/traffic_profile/theorems.json').write_text(json.dumps(value))
        with self.assertRaisesRegex(gate.Rejected, 'required'):
            gate.inventory(self.root)

    def test_frozen_input_changed_is_rejected(self):
        gate.inputs(self.root, self.block)
        (self.root / 'traffic_limits.py').write_text('changed')
        with self.assertRaisesRegex(gate.Rejected, 'changed'):
            gate.inputs(self.root, self.block)

    def test_every_registered_input_is_required(self):
        for name in gate.REQUIRED_INPUTS:
            changed = copy.deepcopy(self.block)
            del changed['inputs'][name]
            with self.subTest(name=name), self.assertRaises(gate.Rejected):
                gate.inputs(self.root, changed)

    def test_scope_trust_and_proof_policy_cannot_be_relaxed(self):
        for name, value in [('scope', 'entire-portal'), ('allowlistedAxioms', ['propext']),
                            ('requiredCleanBuilds', 1), ('requiredReviews', 0),
                            ('umbrellaObligationsClosed', ['TRF-00']), ('trust', []),
                            ('excluded', []), ('claims', [{'closesParent': True}])]:
            changed = copy.deepcopy(self.block)
            changed[name] = value
            with self.subTest(name=name), self.assertRaises(gate.Rejected):
                gate.inputs(self.root, changed)

    def test_unregistered_proof_module_is_rejected(self):
        (self.root / 'formal/traffic_profile/Extra.lean').write_text('import Std\n')
        with self.assertRaisesRegex(gate.Rejected, 'Unregistered'):
            gate.inputs(self.root, self.block)

    def test_snapshot_consumption_refuses_transient_input_swap(self):
        manifest = gate.inputs(self.root, self.block)
        (self.root / 'traffic_limits.py').write_text('changed before consumption')
        with self.assertRaises(gate.Rejected):
            gate.snapshot_inputs(self.root, self.root / 'snapshot', manifest)

    def test_snapshot_is_independent_of_later_worktree_edits(self):
        manifest = gate.inputs(self.root, self.block)
        frozen = gate.snapshot_inputs(self.root, self.root / 'snapshot', manifest)
        (self.root / 'traffic_limits.py').write_text('changed after snapshot')
        self.assertEqual(gate.frozen_bytes(frozen, 'traffic_limits.py', manifest), b'frozen fixture\n')


if __name__ == '__main__':
    unittest.main()
