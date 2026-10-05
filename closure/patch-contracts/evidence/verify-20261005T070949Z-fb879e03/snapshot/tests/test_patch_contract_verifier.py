"""Finite gate negative controls; these are not production correctness proofs."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_patch_contracts as gate


class PatchGateTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/claude-contracts-20261005/verifier-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.name = 'AlloyStudio.PatchContracts.Work.example'
        self.spec = {'schemaVersion': 1, 'id': 'AP01', 'scope': 'proposed-contracts',
                     'closesProductionObligations': False,
                     'claims': [{'id': 'C1', 'statement': 'A finite example.',
                                 'requiredTheorems': [self.name], 'publicClaim': 'docs/test.md#c1'}],
                     'productionBridges': [{'id': 'P1', 'status': 'OPEN', 'statement': 'Not refined.'}],
                     'trusted': [{'id': item, 'description': 'Fixture trust.'} for item in sorted(gate.TRUST)],
                     'inputs': ['docs/test.md']}
        for relative in gate.required_inputs(self.spec):
            self.write(relative, '{}')
        self.write('docs/test.md', 'C1: A finite fixture claim.\n')
        self.save_spec()
        self.inputs = {p: gate.digest(self.root / p) for p in gate.required_inputs(self.spec)}

    def write(self, relative, text):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def save_spec(self):
        self.write(gate.SPEC, json.dumps(self.spec))

    def row(self, kind='theorem'):
        return {'kind': kind, 'name': self.name, 'module': 'Work',
                'type': 'True', 'levelParameters': [], 'axioms': []}

    def test_complete_manifest_accepts_then_missing_extra_or_changed_input_blocks(self):
        gate.check_input_manifest(self.root, self.spec, self.inputs)
        for mutation in ('omit', 'extra', 'wrong_hash'):
            with self.subTest(mutation=mutation):
                inputs = dict(self.inputs)
                if mutation == 'omit':
                    del inputs['scripts/lean_offline.py']
                elif mutation == 'extra':
                    inputs['unregistered.txt'] = '0' * 64
                else:
                    inputs['scripts/lean_offline.py'] = '0' * 64
                with self.assertRaises(gate.Rejected):
                    gate.check_input_manifest(self.root, self.spec, inputs)
        self.write('scripts/lean_offline.py', 'changed')
        with self.assertRaises(gate.Rejected):
            gate.check_input_manifest(self.root, self.spec, self.inputs)

    def test_scope_and_open_bridges_cannot_be_promoted(self):
        gate.load_spec(self.root)
        for mutation in ('production', 'closed_bridge', 'no_bridge'):
            original = copy.deepcopy(self.spec)
            if mutation == 'production':
                self.spec['closesProductionObligations'] = True
            elif mutation == 'closed_bridge':
                self.spec['productionBridges'][0]['status'] = 'VERIFIED'
            else:
                self.spec['productionBridges'] = []
            self.save_spec()
            with self.assertRaises(gate.Rejected):
                gate.load_spec(self.root)
            self.spec = original

    def test_missing_trust_or_public_provenance_is_rejected(self):
        for mutation in ('trust', 'public'):
            original = copy.deepcopy(self.spec)
            if mutation == 'trust':
                self.spec['trusted'].pop()
            else:
                self.spec['claims'][0]['publicClaim'] = 'docs/unregistered.md'
            self.save_spec()
            with self.assertRaises(gate.Rejected):
                gate.load_spec(self.root)
            self.spec = original

    def test_axiom_dependencies_and_unknown_module_rejected(self):
        for field, value in [('axioms', ['propext']), ('kind', 'forbidden-project-axiom'),
                             ('module', 'Unregistered')]:
            row = self.row()
            row[field] = value
            with self.assertRaises(gate.Rejected):
                gate.audit_inventory([row])

    def test_duplicate_and_missing_theorems_or_definition_as_theorem_rejected(self):
        row = self.row()
        with self.assertRaises(gate.Rejected):
            gate.audit_inventory([row, row])
        inventory = gate.audit_inventory([row])
        gate.check_claim_theorems(self.spec, inventory)
        with self.assertRaises(gate.Rejected):
            gate.check_claim_theorems(self.spec, {})
        inventory[self.name]['kind'] = 'definition'
        with self.assertRaises(gate.Rejected):
            gate.check_claim_theorems(self.spec, inventory)

    def test_type_change_changes_registered_inventory(self):
        before = gate.audit_inventory([self.row()])
        row = self.row()
        row['type'] = 'False'
        self.assertNotEqual(before, gate.audit_inventory([row]))

    def test_unsafe_proof_language_is_refused(self):
        for source in ('axiom fabricated : False', 'theorem bad : False := by sorry',
                       'theorem bad : True := by native_decide', 'import Lean',
                       'set_option debug.skipKernelTC true', 'unsafe def bypass := 0'):
            with self.subTest(source=source), self.assertRaises(gate.Rejected):
                gate.check_proof_source(source, set(gate.MODULES))

    def test_secret_traversal_and_duplicate_json_inputs_rejected(self):
        for relative in ('.env.example', '.env', 'admin.local.json', 'openai.local.json', '../outside'):
            if relative != '../outside':
                self.write(relative, 'synthetic fixture only')
            with self.subTest(relative=relative), self.assertRaises(gate.Rejected):
                gate.safe_input(self.root, relative)
        duplicate = self.write('duplicate.json', '{"x":1,"x":2}')
        with self.assertRaises(gate.Rejected):
            gate.read_json(duplicate)

    def test_parent_symlink_is_rejected(self):
        target = self.root / 'directory'
        target.mkdir()
        (target / 'input').write_text('fixture')
        try:
            (self.root / 'alias').symlink_to(target, target_is_directory=True)
        except OSError:
            self.skipTest('Symlink creation unavailable on this test host')
        with self.assertRaises(gate.Rejected):
            gate.safe_input(self.root, 'alias/input')


if __name__ == '__main__':
    unittest.main()
