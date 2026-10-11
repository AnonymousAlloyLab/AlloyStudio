"""Historical replay boundary tests; fake runners do not constitute proof evidence."""
from copy import deepcopy
import json
from pathlib import Path
import shutil
import tempfile
from types import SimpleNamespace
import unittest

from scripts import replay_reward_contract as replay

ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/reward-replay-tests'


class RewardReplayTests(unittest.TestCase):
    def setUp(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        temporary = tempfile.TemporaryDirectory(prefix='fixture-', dir=SCRATCH)
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        shutil.copytree(ROOT / replay.HISTORY, self.root / replay.HISTORY)
        for relative in replay.CURRENT_MODEL:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes((ROOT / relative).read_bytes())
        self.historical = json.loads((self.root / replay.HISTORY / 'report.json').read_bytes())

    def runner(self, report=None, *, exit_code=0, mutate=None, count=1):
        def execute(command, **kwargs):
            self.assertEqual(command[1:3], ['-I', '-c'])
            self.assertEqual(command[3], replay.BOOTSTRAP)
            self.assertEqual(command[4], str(kwargs['cwd'] / 'scripts'))
            self.assertEqual(kwargs['timeout'], 600)
            self.assertNotIn('PYTHONPATH', kwargs['env'])
            self.assertNotIn('OPENAI_API_KEY', kwargs['env'])
            self.assertEqual(kwargs['env']['OPENAI_DISABLED'], '1')
            for index in range(count):
                destination = kwargs['cwd'] / 'build/reward-contract' / f'unit-fixture-{index}'
                destination.mkdir(parents=True)
                value = deepcopy(self.historical if report is None else report)
                value['id'] = 'unit-test-result-without-lean-execution'
                (destination / 'report.json').write_text(json.dumps(value))
            if mutate:
                mutate()
            return SimpleNamespace(returncode=exit_code)
        return execute

    def assert_blocked(self, reason, runner=None):
        report, directory = replay.replay(self.root, runner=runner)
        self.assertEqual(report['status'], 'BLOCKED')
        self.assertEqual(report['blockingReasons'], [reason])
        self.assertFalse(report['productionBindingRenewed'])
        self.assertEqual(report['productionRefinementStatus'], 'NOT_ESTABLISHED')
        if directory:
            self.assertEqual(json.loads((directory / 'report.json').read_bytes()), report)
        return report

    def test_current_model_and_historical_input_pins_are_checked(self):
        manifest, files, report = replay.historical_inputs(self.root)
        self.assertEqual(replay.digest(replay.encoded(manifest)), replay.INPUT_ROOT)
        self.assertEqual(replay.digest(files['scripts/verify_reward_contract.py']), replay.VERIFIER_SHA256)
        self.assertEqual(report['provedTheorems'], 92)

    def test_changed_production_does_not_become_a_model_claim(self):
        (self.root / 'server.py').write_text('different production; not a replay input')
        report, directory = replay.replay(self.root, runner=self.runner())
        self.assertEqual(report['status'], 'REPLAY_PASS')
        self.assertEqual(report['scope'], 'frozen-historical-reward-lfu-model-replay')
        self.assertFalse(report['productionBindingRenewed'])
        self.assertEqual(report['productionRefinementStatus'], 'NOT_ESTABLISHED')
        original = self.root / replay.HISTORY / 'inputs/server.py'
        self.assertEqual((directory / 'snapshot/server.py').read_bytes(), original.read_bytes())
        self.assertEqual(report['historicalInputRootHash'], replay.INPUT_ROOT)

    def test_report_and_manifest_have_independent_fixed_byte_pins(self):
        for filename, reason in [('manifest.json', 'HISTORICAL_MANIFEST_MUTATION'),
                                 ('report.json', 'HISTORICAL_REPORT_MUTATION')]:
            path = self.root / replay.HISTORY / filename
            data = path.read_bytes()
            path.write_bytes(data + b'\n')
            self.assert_blocked(reason)
            path.write_bytes(data)

    def test_changed_frozen_verifier_is_never_executed(self):
        path = self.root / replay.HISTORY / 'inputs/scripts/verify_reward_contract.py'
        path.chmod(0o600)
        path.write_bytes(path.read_bytes() + b'\n# mutation\n')
        self.assert_blocked('HISTORICAL_INPUT_MUTATION', runner=lambda *a, **k: self.fail('Executed mutated verifier'))

    def test_current_proof_and_trust_spec_changes_block_replay(self):
        for relative in ('formal/reward/Reward.lean', 'formal/reward/Audit.lean',
                         'formal/reward/claims.json', 'docs/reward-lfu-contract.md'):
            path = self.root / relative
            data = path.read_bytes()
            path.write_bytes(data + b'\n')
            self.assert_blocked('CURRENT_MODEL_MUTATION')
            path.write_bytes(data)

    def test_extra_current_model_lean_source_is_not_hidden_by_snapshot(self):
        (self.root / 'formal/reward/Hidden.lean').write_text('theorem extra : True := by trivial')
        self.assert_blocked('CURRENT_MODEL_INVENTORY_MUTATION')

    def test_nested_current_model_lean_source_is_not_hidden_by_snapshot(self):
        directory = self.root / 'formal/reward/extra'
        directory.mkdir()
        (directory / 'Hidden.lean').write_text('theorem extra : True := by trivial')
        self.assert_blocked('CURRENT_MODEL_INVENTORY_MUTATION')

    def test_process_success_without_full_evidence_is_blocked(self):
        self.assert_blocked('REPLAY_REPORT_INCOMPLETE', runner=self.runner({'status': 'VERIFIED'}))

    def test_nonzero_exit_cannot_promote_a_successful_report(self):
        self.assert_blocked('REPLAY_VERIFIER_REJECTED', runner=self.runner(exit_code=1))

    def test_missing_or_multiple_reports_are_blocking(self):
        self.assert_blocked('REPLAY_REPORT_MISSING_OR_AMBIGUOUS', runner=self.runner(count=0))
        self.assert_blocked('REPLAY_REPORT_MISSING_OR_AMBIGUOUS', runner=self.runner(count=2))

    def test_second_build_negative_control_and_toolchain_mutations_block(self):
        mutations = [lambda report: report['builds'][1].update(theorems=91),
                     lambda report: report['negativeControls'].pop('rogue_axiom'),
                     lambda report: report['toolchain'].update(rootHash='0' * 64),
                     lambda report: report['claims'][0].update(productionRefinement='PROVED')]
        for mutate in mutations:
            report = deepcopy(self.historical)
            mutate(report)
            self.assert_blocked('REPLAY_EVIDENCE_MISMATCH', runner=self.runner(report))

    def test_current_model_mutation_during_replay_is_detected(self):
        path = self.root / 'formal/reward/LFU.lean'
        self.assert_blocked('CURRENT_MODEL_MUTATION',
            runner=self.runner(mutate=lambda: path.write_bytes(path.read_bytes() + b'\n')))

    def test_infrastructure_failure_retains_inner_report_without_promotion(self):
        inner = deepcopy(self.historical)
        inner['status'] = 'INFRASTRUCTURE_FAILURE'
        report, directory = replay.replay(self.root, runner=self.runner(inner, exit_code=2))
        self.assertEqual(report['status'], 'INFRASTRUCTURE_FAILURE')
        self.assertEqual(report['infrastructureErrors'], ['REPLAY_EXECUTION_UNAVAILABLE'])
        self.assertFalse(report['productionBindingRenewed'])
        self.assertTrue((directory / report['replayedReport']).is_file())

    def test_original_history_remains_byte_identical_after_replay(self):
        manifest_before = (self.root / replay.HISTORY / 'manifest.json').read_bytes()
        report_before = (self.root / replay.HISTORY / 'report.json').read_bytes()
        replay.replay(self.root, runner=self.runner())
        self.assertEqual((self.root / replay.HISTORY / 'manifest.json').read_bytes(), manifest_before)
        self.assertEqual((self.root / replay.HISTORY / 'report.json').read_bytes(), report_before)


if __name__ == '__main__':
    unittest.main(verbosity=2)
