"""Consistency of the OPEN Lean roadmap; these tests do not establish Lean proofs."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class LeanObligationPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.ledger = json.loads((ROOT / 'closure/lean-obligations.json').read_text())
        cls.obligations = cls.ledger['obligations']
        cls.document = (ROOT / 'docs/lean-closure.md').read_text()

    def test_open_register_does_not_claim_formal_closure(self):
        self.assertEqual(self.ledger['schemaVersion'], 1)
        self.assertEqual(self.ledger['kind'], 'formal-closure-plan')
        self.assertEqual(self.ledger['formalClosureStatus'], 'NOT_ESTABLISHED')
        self.assertEqual(len(self.obligations), 24)
        for item in self.obligations:
            self.assertEqual(item['status'], 'OPEN')
            self.assertEqual(item['evidence'], [])
            self.assertGreaterEqual(len(item['requiredEvidence']), 3)
            self.assertIn('implementation correspondence witness', item['requiredEvidence'])
        policy = json.loads((ROOT / 'closure/policy.json').read_text())
        self.assertEqual(policy['proof_inventory'], [])
        self.assertEqual(policy['correspondence']['required_objects'], 0)
        self.assertIn('not a completed proof package', self.document)
        self.assertIn('NOT_ESTABLISHED', self.document)

    def test_identifiers_and_planned_theorems_are_unique_and_well_formed(self):
        ids = [item['id'] for item in self.obligations]
        self.assertEqual(set(ids), {f'L{number:02d}' for number in range(24)})
        self.assertEqual(len(ids), len(set(ids)))
        theorems = [item['plannedTheorem'] for item in self.obligations]
        self.assertEqual(len(theorems), len(set(theorems)))
        for item in self.obligations:
            self.assertRegex(item['plannedModule'], r'\AAlloyStudio\.[A-Za-z][A-Za-z0-9_]*\Z')
            self.assertRegex(item['plannedTheorem'], r'\AAlloyStudio\.[A-Za-z][A-Za-z0-9_]*\.[A-Za-z][A-Za-z0-9_]*\Z')
            self.assertTrue(item['plannedTheorem'].startswith(item['plannedModule'] + '.'))
            self.assertGreater(len(item['implementationDetails']), 60)

    def test_dependency_references_form_an_acyclic_graph(self):
        records = {item['id']: item for item in self.obligations}
        visited, active = set(), set()

        def visit(identity):
            self.assertIn(identity, records)
            self.assertNotIn(identity, active, 'The obligation register contains a dependency cycle')
            if identity in visited:
                return
            active.add(identity)
            dependencies = records[identity]['dependsOn']
            self.assertEqual(len(dependencies), len(set(dependencies)))
            for dependency in dependencies:
                visit(dependency)
            active.remove(identity)
            visited.add(identity)

        for identity in records:
            visit(identity)
        self.assertEqual(visited, set(records))

    def test_planned_profiles_are_complete_and_closed_under_dependencies(self):
        records = {item['id']: item for item in self.obligations}
        profiles = self.ledger['plannedProfiles']
        self.assertEqual(set(profiles), {'raw-ast', 'full-portal'})
        self.assertEqual(set(profiles['full-portal']), set(records))
        self.assertEqual(set(profiles['raw-ast']),
                         {f'L{number:02d}' for number in range(12)} | {'L22', 'L23'})
        self.assertLess(set(profiles['raw-ast']), set(profiles['full-portal']))
        for name, members in profiles.items():
            self.assertEqual(len(members), len(set(members)), f'{name} repeats an obligation')
            self.assertTrue({'L00', 'L22', 'L23'} <= set(members))
            for identity in members:
                self.assertIn(identity, records)
                self.assertTrue(set(records[identity]['dependsOn']) <= set(members),
                                f'{name} omits a dependency of {identity}')
        self.assertIn('raw-ast', self.document)
        self.assertIn('full-portal', self.document)

    def test_implementation_bindings_exist_inside_this_repository(self):
        for item in self.obligations:
            self.assertTrue(item['implementation'])
            self.assertEqual(len(item['implementation']), len(set(item['implementation'])))
            for relative in item['implementation']:
                path = Path(relative)
                self.assertFalse(path.is_absolute())
                self.assertNotIn('..', path.parts)
                target = ROOT / path
                self.assertTrue(target.resolve().is_relative_to(ROOT))
                self.assertTrue(target.is_file(), f"{item['id']} names a missing implementation input")

    def test_document_table_matches_ledger_titles_dependencies_and_count(self):
        rows = re.findall(r'^\| (L\d\d) \| ([^|]+) \| ([^|]+) \|$', self.document, re.MULTILINE)
        self.assertEqual(len(rows), len(self.obligations))
        actual = {identity: (title.strip(), [] if deps.strip() == '—' else deps.strip().split(', '))
                  for identity, title, deps in rows}
        self.assertEqual(len(actual), len(rows))
        expected = {item['id']: (item['title'], item['dependsOn']) for item in self.obligations}
        self.assertEqual(actual, expected)
        self.assertIn(f'All {len(self.obligations)} obligations', self.document)
        self.assertIn(self.ledger['proposedLeanToolchain'], self.document)
        self.assertEqual(self.ledger['proposedLeanToolchain'], 'leanprover/lean4:v4.33.0')
        self.assertEqual(set(self.ledger['allowlistedAxioms']), {'propext', 'Classical.choice', 'Quot.sound'})
        self.assertIn('sorryAx', self.ledger['forbiddenProofDependencies'])

    def test_dashboard_baseline_reports_open_counts_without_proof_status(self):
        baseline = json.loads((ROOT / 'web/dashboard/data.json').read_text())
        self.assertEqual(baseline['lean'], {'total': len(self.obligations),
            'open': sum(item['status'] == 'OPEN' for item in self.obligations)})
        self.assertEqual(baseline['closure']['status'], 'NOT_AVAILABLE')
        self.assertFalse(baseline['closure']['current'])
