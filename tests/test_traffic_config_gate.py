"""Input mutation and scope-escalation controls for the TCFG01 verifier."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_traffic_config as gate


class NumericGateTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/trf-config-gate-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        for name in gate.REQUIRED_INPUTS:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('frozen fixture\n')
        self.block = {'schemaVersion': 1, 'id': 'TCFG01',
            'scope': 'normalized-numeric-configuration', 'flags': gate.FLAGS,
            'allowlistedAxioms': [], 'requiredCleanBuilds': 2, 'requiredReviews': 6,
            'umbrellaObligationsClosed': [], 'modules': list(gate.MODULES),
            'claims': [gate.CLAIM], 'trust': gate.TRUST, 'excluded': gate.EXCLUDED,
            'inputs': {name: gate.digest(self.root / name) for name in gate.REQUIRED_INPUTS}}
        (self.root / gate.BLOCK).write_text(json.dumps(self.block))

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
        (self.root / 'formal/traffic_config/Extra.lean').write_text('import Std\n')
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
