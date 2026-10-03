"""Finite negative controls for the isolated traffic model verifier.

These tests exercise metadata rejection, not Lean theorem validity. The actual
offline verifier separately runs the kernel, axiom audit and clean builds.
"""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import verify_traffic_proofs as gate


class TrafficProofGateTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/traffic-proof-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value) if not isinstance(value, str) else value)
        return path

    def block(self):
        for name in gate.REQUIRED_INPUTS:
            self.write(name, 'fixture\n')
        block = {'schemaVersion': 1, 'id': 'TB01', 'scope': 'traffic-model-only',
                 'flags': gate.FLAGS, 'allowlistedAxioms': [], 'requiredCleanBuilds': 2,
                 'requiredReviews': 6, 'productionRefinementEstablished': False,
                 'modules': list(gate.MODULES),
                 'inputs': {name: gate.digest(self.root / name) for name in gate.REQUIRED_INPUTS}}
        self.write(gate.BLOCK, block)
        return block

    def test_frozen_input_mutation_rejected(self):
        block = self.block()
        gate.inputs(self.root, block)
        self.write(gate.SOURCE_PATHS[0], 'mutated proof\n')
        with self.assertRaisesRegex(gate.Rejected, 'changed'):
            gate.inputs(self.root, block)

    def test_unregistered_module_rejected(self):
        block = self.block()
        self.write('formal/traffic/Traffic/Extra.lean', 'import Std\n')
        with self.assertRaisesRegex(gate.Rejected, 'Unexpected or missing'):
            gate.inputs(self.root, block)

    def test_transient_source_swap_cannot_be_copied_under_old_hash(self):
        block = self.block()
        manifest = gate.inputs(self.root, block)
        relative = gate.SOURCE_PATHS[0]
        self.write(relative, 'Different implementation with the same theorem type.\n')
        with self.assertRaisesRegex(gate.Rejected, 'changed before snapshot'):
            gate.snapshot_inputs(self.root, self.root / 'checked-copy', manifest)

    def test_checked_snapshot_is_independent_of_later_worktree_edits(self):
        block = self.block()
        manifest = gate.inputs(self.root, block)
        frozen = gate.snapshot_inputs(self.root, self.root / 'checked-copy', manifest)
        relative = gate.SOURCE_PATHS[0]
        self.write(relative, 'Changed worktree after private snapshot.\n')
        self.assertEqual(gate.frozen_bytes(frozen, relative, manifest), b'fixture\n')

    def test_missing_verifier_input_rejected(self):
        block = self.block()
        del block['inputs']['scripts/verify_traffic_proofs.py']
        with self.assertRaisesRegex(gate.Rejected, 'inventory'):
            gate.inputs(self.root, block)

    def test_production_claim_and_relaxed_policies_rejected(self):
        base = self.block()
        for key, value in [('productionRefinementEstablished', True),
                           ('requiredCleanBuilds', 1), ('requiredReviews', 0),
                           ('allowlistedAxioms', ['sorryAx']), ('scope', 'whole-portal')]:
            block = copy.deepcopy(base)
            block[key] = value
            with self.subTest(key=key), self.assertRaises(gate.Rejected):
                gate.inputs(self.root, block)

    def test_theorem_inventory_rejects_axioms_duplicates(self):
        row = {'name': 'AlloyStudio.Traffic.example', 'module': 'Traffic.Resources',
               'levelParameters': [], 'typeSha256': '0' * 64, 'axioms': []}
        path = 'formal/traffic/theorems.json'
        self.write(path, {'theorems': [row]})
        self.assertEqual(set(gate.inventory(self.root)), {row['name']})
        for rows in ([row, row], [{**row, 'axioms': ['propext']}], []):
            self.write(path, {'theorems': rows})
            with self.assertRaises(gate.Rejected):
                gate.inventory(self.root)

    def test_claim_cannot_assert_production_or_reference_absent_theorem(self):
        theorem = 'AlloyStudio.Traffic.example'
        claim = {'id': 'C1', 'class': 'model_theorem', 'productionRefinement': 'OPEN',
                 'theorems': [theorem], 'verifier': 'V-TRF-MODEL',
                 'passPredicate': 'all_registered_theorems_kernel_checked_with_empty_axioms'}
        self.write('formal/traffic/witnesses.json', {'witnesses': [{
            'id': 'W', 'classification': 'kernel_checked_model_witness',
            'theorems': [theorem], 'scope': 'model only'}]})
        self.write('formal/traffic/claims.json', {'claims': [claim]})
        gate.mappings(self.root, {theorem: {}})
        with self.assertRaisesRegex(gate.Rejected, 'Unmapped'):
            gate.mappings(self.root, {theorem: {}, 'unmappedHelper': {}})
        for change in ({'productionRefinement': 'VERIFIED'}, {'theorems': ['absent']}):
            self.write('formal/traffic/claims.json', {'claims': [{**claim, **change}]})
            with self.assertRaises(gate.Rejected):
                gate.mappings(self.root, {theorem: {}})

    def test_ambiguous_claim_ownership_rejected(self):
        theorem = 'AlloyStudio.Traffic.example'
        claim = {'id': 'C1', 'class': 'model_theorem', 'productionRefinement': 'OPEN',
                 'theorems': [theorem], 'verifier': 'V-TRF-MODEL',
                 'passPredicate': 'all_registered_theorems_kernel_checked_with_empty_axioms'}
        self.write('formal/traffic/claims.json', {'claims': [claim, {**claim, 'id': 'C2'}]})
        self.write('formal/traffic/witnesses.json', {'witnesses': []})
        with self.assertRaisesRegex(gate.Rejected, 'Ambiguous'):
            gate.mappings(self.root, {theorem: {}})

    def test_review_notes_replacement_rejected(self):
        self.block()
        prior = {}
        for tier, (name, model) in enumerate((('luna', 'gpt-6-luna'),
                ('sol', 'gpt-6-sol'), ('astra', 'gpt-6-astra')), 1):
            current = {}
            for suffix in ('a', 'b'):
                relative = f'formal/reviews/TB01/{name}-{suffix}.json'
                notes = relative.removesuffix('.json') + '.md'
                self.write(notes, 'Coverage of the original candidate.\n')
                self.write(relative, {'reviewerModel': model, 'tier': tier,
                    'blockManifestSha256': gate.digest(self.root / gate.BLOCK),
                    'priorReviews': prior, 'verdict': 'no_constructed_breach',
                    'findings': [], 'notesSha256': gate.digest(self.root / notes)})
                current[relative] = gate.digest(self.root / relative)
            prior.update(current)
        self.assertEqual(len(gate.traffic_reviews(self.root)), 6)
        self.write('formal/reviews/TB01/luna-a.md', 'Unrelated replacement notes.\n')
        with self.assertRaisesRegex(gate.Rejected, 'coverage notes changed'):
            gate.traffic_reviews(self.root)


if __name__ == '__main__':
    unittest.main()
