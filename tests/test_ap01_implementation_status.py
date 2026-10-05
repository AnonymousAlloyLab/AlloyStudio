"""The AP01 implementation ledger stays bound to real files and never claims VERIFIED."""
import json
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ImplementationStatusTests(unittest.TestCase):
    def setUp(self):
        self.status = json.loads((ROOT / 'closure/patch-contracts/implementation-status.json').read_text())
        self.spec = json.loads((ROOT / 'closure/patch-contracts/spec.json').read_text())

    def test_every_contract_bridge_has_exactly_one_entry(self):
        expected = [bridge['id'] for bridge in self.spec['productionBridges']]
        self.assertEqual([entry['id'] for entry in self.status['bridges']], expected)

    def test_no_bridge_claims_verification(self):
        self.assertEqual(self.status['formalRefinement'], 'NOT_ESTABLISHED')
        self.assertEqual(self.status['deploymentValidation'], 'NOT_PERFORMED')
        for entry in self.status['bridges']:
            self.assertIn(entry['status'], ('IMPLEMENTED_TESTED', 'OPEN'))
            self.assertTrue(entry['open'], entry['id'] + ' must state what remains open')
        # The frozen AP01 contract itself keeps every production bridge OPEN.
        self.assertTrue(all(bridge['status'] == 'OPEN' for bridge in self.spec['productionBridges']))

    def test_referenced_files_and_anchors_exist(self):
        for entry in self.status['bridges']:
            for reference in entry['code'] + entry['tests'] + entry['evidence']:
                path, _, anchor = reference.partition('#')
                with self.subTest(bridge=entry['id'], reference=reference):
                    self.assertTrue((ROOT / path).is_file())
                    if anchor:
                        headings = re.findall(r'(?m)^#{1,6} (.+)$', (ROOT / path).read_text())
                        slugs = {re.sub(r'[^a-z0-9 -]', '', heading.lower()).replace(' ', '-') for heading in headings}
                        self.assertIn(anchor, slugs)


if __name__ == '__main__':
    unittest.main()
