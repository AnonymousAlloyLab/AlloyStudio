"""Bounded witnesses for stale, missing and misleading formal overview evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('obligation_overview', ROOT / 'scripts/obligation_overview.py')
overview = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(overview)


class ObligationOverviewTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='obligation-overview-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.ledger = json.loads((ROOT / overview.LEDGER).read_text())
        self.ledger['supportingProofBlocks'] = {identity: f'formal/blocks/{identity}.json'
                                               for identity in ('B01', 'B02', 'B03')}
        self.ledger['activeProofBlocks'] = ['B01', 'B03']
        self.write(overview.LEDGER, self.ledger)
        self.write('formal/blocks/B01.json', {
            'id': 'B01', 'doesNotCloseOriginalObligations': True,
            'sources': {'formal/AlloyStudio/Foundation.lean': 'unused'},
            'implementationInputs': {}, 'supports': ['L00', 'L01', 'L04', 'L11', 'L23'],
        })
        self.write('formal/blocks/B03.json', {
            'id': 'B03', 'doesNotCloseOriginalObligations': True,
            'sources': {'formal/AlloyStudio/SessionBridge.lean': 'unused'},
            'implementationInputs': {'web/app.js': 'unused'}, 'supports': ['L11', 'L20', 'L22'],
        })
        objects = []
        for identity, (policy, arity) in overview.BRIDGE_SCHEMA.items():
            objects.append({'id': identity, 'policy': policy, 'arity': arity,
                            'implementationFiles': ['web/app.js'],
                            'supports': ['L11', 'L22'] if 'POOL' in identity else ['L20', 'L22']})
        self.write(overview.REGISTRY, {'schemaVersion': 1, 'kind': 'finite-policy-correspondence',
                                     'objects': objects, 'trust': ['Test runtime'],
                                     'excluded': ['Host loop and wire decoding']})
        paths, _, _ = overview.registration(self.root, self.ledger)
        for path in paths:
            if not (self.root / path).exists():
                target = self.root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('fixture source\n')
        inputs = {path: overview.digest(self.root / path) for path in sorted(paths)}
        build = {'bridges': {'status': 'VERIFIED', 'kernelCheckedRows': 9224,
                             'javascriptValuations': 9224, 'javaValuations': 8}}
        self.report = {
            'schemaVersion': 1, 'kind': 'offline-formal-block-verification',
            'status': 'BLOCKED', 'blockStatus': 'VERIFIED', 'bridgeStatus': 'VERIFIED',
            'blocks': ['B01', 'B03'], 'bridgeClaims': list(overview.BRIDGE_SCHEMA),
            'correspondence': {'required_objects': 4, 'mapped_objects': 4,
                               'unmapped_objects': 0, 'ambiguous_objects': 0},
            'builds': [copy.deepcopy(build), copy.deepcopy(build)],
            'inputs': inputs, 'inputRootSha256': overview.hashlib.sha256(overview.canonical(inputs)).hexdigest(),
            'trust': ['Test verifier'],
        }

    def write(self, relative, data):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(data))

    def rebind(self):
        self.report['inputs'] = {path: overview.digest(self.root / path)
                                 for path in self.report['inputs']}
        self.report['inputRootSha256'] = overview.hashlib.sha256(overview.canonical(self.report['inputs'])).hexdigest()

    def test_no_report_never_inherits_a_verified_status(self):
        result = overview.build_overview(self.root)
        self.assertEqual(result['blockStatus'], 'NOT_RUN')
        self.assertEqual(result['bridgeStatus'], 'NOT_RUN')
        self.assertFalse(result['reportCurrent'])
        self.assertEqual(result['counts']['unresolvedBridges'], 4)
        self.assertEqual(result['counts']['provedEndToEnd'], 0)

    def test_current_complete_finite_bridge_does_not_close_original_obligations(self):
        result = overview.build_overview(self.root, self.report)
        self.assertTrue(result['reportCurrent'])
        self.assertEqual(result['blockStatus'], 'VERIFIED')
        self.assertEqual(result['bridgeStatus'], 'VERIFIED')
        self.assertEqual(result['counts']['verifiedBridges'], 4)
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertEqual(result['formalClosureStatus'], 'NOT_ESTABLISHED')
        self.assertEqual(result['counts']['openEndToEnd'], 24)
        self.assertTrue(all(item['endToEndStatus'] == 'OPEN' for item in result['obligations']))
        self.assertEqual(result['evidenceProblems'], [])

    def test_every_known_obligation_has_requirements_implementation_and_remaining_work(self):
        result = overview.build_overview(self.root)
        self.assertEqual({item['id'] for item in result['obligations']}, overview.EXPECTED_IDS)
        self.assertEqual(len(result['obligations']), 24)
        for item in result['obligations']:
            self.assertTrue(item['title'])
            self.assertTrue(item['implementation'])
            self.assertTrue(item['remaining'])
            self.assertTrue(item['requiredEvidence'])
        rendered = overview.markdown(result)
        for identity in overview.EXPECTED_IDS:
            self.assertEqual(rendered.count(f'| {identity} |'), 1)
        self.assertIn('0/24 proved', rendered)

    def test_changed_source_rejects_previously_green_report(self):
        (self.root / 'web/app.js').write_text('changed policy\n')
        result = overview.build_overview(self.root, self.report)
        self.assertFalse(result['reportCurrent'])
        self.assertEqual(result['bridgeStatus'], 'BLOCKED')
        self.assertEqual(result['blockStatus'], 'BLOCKED')
        self.assertEqual(result['counts']['verifiedBridges'], 0)
        self.assertIn('STALE_INPUT: web/app.js', result['evidenceProblems'])

    def test_changed_ledger_cannot_promote_full_obligations(self):
        self.ledger['obligations'][0]['status'] = 'PROVED'
        self.write(overview.LEDGER, self.ledger)
        self.rebind()
        result = overview.build_overview(self.root, self.report)
        self.assertEqual(result['counts']['provedEndToEnd'], 0)
        self.assertEqual(result['obligations'][0]['status'], 'OPEN')
        self.assertEqual(result['obligations'][0]['ledgerStatus'], 'PROVED')
        self.assertIn('UNSUPPORTED_LEDGER_DISCHARGE: L00', result['evidenceProblems'])

    def test_missing_hash_is_unresolved(self):
        del self.report['inputs']['web/app.js']
        result = overview.build_overview(self.root, self.report)
        self.assertFalse(result['reportCurrent'])
        self.assertEqual(result['bridgeStatus'], 'BLOCKED')
        self.assertTrue(any('MISSING_INPUT_BINDING' in item for item in result['evidenceProblems']))

    def test_unregistered_secret_or_escape_path_is_rejected_before_open(self):
        for path in ('.env', '../secret', '/tmp/secret'):
            with self.subTest(path=path):
                report = copy.deepcopy(self.report)
                report['inputs'][path] = '0' * 64
                with patch.object(overview, 'digest', wraps=overview.digest) as hashing:
                    result = overview.build_overview(self.root, report)
                # Only the independently known ledger is hashed for the output;
                # report paths are rejected together before opening any of them.
                self.assertEqual(hashing.call_args_list[0].args[0], self.root / overview.LEDGER)
                self.assertEqual(hashing.call_count, 1)
                self.assertFalse(result['reportCurrent'])
                self.assertEqual(result['bridgeStatus'], 'BLOCKED')
                self.assertTrue(result['evidenceProblems'][0].startswith('UNREGISTERED_INPUT'))

    def test_registered_symlink_cannot_redirect_source_read(self):
        source = self.root / 'web/app.js'
        source.unlink()
        source.symlink_to(self.root / overview.LEDGER)
        result = overview.build_overview(self.root, self.report)
        self.assertFalse(result['reportCurrent'])
        self.assertTrue(any('Linked input' in problem for problem in result['evidenceProblems']))

    def test_missing_or_ambiguous_bridge_counts_are_unresolved(self):
        for key, value in (('mapped_objects', 3), ('required_objects', 3),
                           ('unmapped_objects', 1), ('ambiguous_objects', 1),
                           ('mapped_objects', None)):
            with self.subTest(key=key, value=value):
                report = copy.deepcopy(self.report)
                report['correspondence'][key] = value
                result = overview.build_overview(self.root, report)
                self.assertEqual(result['bridgeStatus'], 'BLOCKED')
                self.assertEqual(result['counts']['unresolvedBridges'], 4)
        del self.report['correspondence']
        self.assertEqual(overview.build_overview(self.root, self.report)['bridgeStatus'], 'BLOCKED')

    def test_incomplete_finite_rows_or_builds_are_unresolved(self):
        for field in ('kernelCheckedRows', 'javascriptValuations', 'javaValuations', 'status'):
            with self.subTest(field=field):
                report = copy.deepcopy(self.report)
                del report['builds'][1]['bridges'][field]
                self.assertEqual(overview.build_overview(self.root, report)['bridgeStatus'], 'BLOCKED')
        self.report['builds'].pop()
        self.assertEqual(overview.build_overview(self.root, self.report)['bridgeStatus'], 'BLOCKED')

    def test_missing_bridge_claim_does_not_turn_green(self):
        self.report['bridgeClaims'].pop()
        result = overview.build_overview(self.root, self.report)
        self.assertEqual(result['bridgeStatus'], 'BLOCKED')

    def test_absent_registry_keeps_all_four_bridges_unresolved(self):
        (self.root / overview.REGISTRY).unlink()
        result = overview.build_overview(self.root)
        self.assertEqual(result['counts']['registeredBridges'], 0)
        self.assertEqual(result['counts']['unresolvedBridges'], 4)
        self.assertEqual(result['bridgeStatus'], 'NOT_RUN')

    def test_historical_block_is_not_required_or_presented_as_current(self):
        result = overview.build_overview(self.root, self.report)
        self.assertEqual(result['blockStatus'], 'VERIFIED')
        self.assertTrue(all('B02' not in item['supportingProofBlocks'] for item in result['obligations']))
        self.report['blocks'] = ['B01', 'B02']
        self.assertEqual(overview.build_overview(self.root, self.report)['blockStatus'], 'BLOCKED')

    def test_unknown_or_missing_obligation_is_not_silently_omitted(self):
        self.ledger['obligations'].pop()
        self.write(overview.LEDGER, self.ledger)
        with self.assertRaises(overview.InvalidEvidence):
            overview.build_overview(self.root)


if __name__ == '__main__':
    unittest.main()
