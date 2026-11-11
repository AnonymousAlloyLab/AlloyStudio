"""A fresh rerun seals previous evidence without rewriting interrupted streams."""
from pathlib import Path
from contextlib import ExitStack
import json
from unittest.mock import patch
import tempfile
import unittest

from benchmarks.alloy4fun import run_pinned_acgn as runner

ROOT = Path(__file__).resolve().parents[1]


class PinnedAcgnPreservationTests(unittest.TestCase):
    def test_canonical_only_and_resource_profile_are_explicit(self):
        self.assertEqual(runner.selected_arms(['canonical']), [('canonical', 'run_live.py')])
        for names in ([], ['canonical', 'canonical'], ['unknown']):
            with self.assertRaises(ValueError):
                runner.selected_arms(names)
        profile = runner.run_policy('2g', '1g')
        self.assertEqual(profile['heap'], '2g')
        self.assertEqual(profile['initial_heap'], '1g')
        self.assertEqual(profile['memory_max_bytes'], 12 * runner.GIB)
        self.assertEqual(profile['memory_high_bytes'], 10 * runner.GIB)
        self.assertEqual(profile['memory_swap_max_bytes'], 0)
        self.assertLess(profile['runtime_available_bytes'], profile['startup_available_bytes'])
        self.assertEqual(runner.run_policy()['memory_max_bytes'], 6 * runner.GIB)
        with self.assertRaises(ValueError):
            runner.run_policy('512m', '2g')

    def setUp(self):
        base = ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        temp = tempfile.TemporaryDirectory(prefix='pinned-preservation-', dir=base)
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.prior = self.root / 'prior'
        (self.prior / 'ast').mkdir(parents=True)
        (self.prior / 'manifest.json').write_text('{}')
        (self.prior / 'ast/results.jsonl').write_bytes(b'{"case_id":"one"}\n')
        # Deliberately interrupted; preservation must not try to read/repair it.
        (self.prior / 'ast/responses.jsonl.gz').write_bytes(b'\x1f\x8bpartial')

    def test_seals_original_bytes_and_detects_changed_evidence(self):
        before = {str(p): p.read_bytes() for p in self.prior.rglob('*') if p.is_file()}
        sealed = runner.preserved_run_artifacts(self.prior, self.root / 'new')
        self.assertEqual(set(sealed), set(before))
        runner.check_preserved({'preserved_previous_results': sealed})
        self.assertEqual({p: Path(p).read_bytes() for p in before}, before)
        (self.prior / 'ast/results.jsonl').write_text('changed')
        with self.assertRaisesRegex(RuntimeError, 'preserved prior result changed'):
            runner.check_preserved({'preserved_previous_results': sealed})

    def test_rejects_missing_or_overlapping_experiment(self):
        for prior, new in ((self.root / 'missing', self.root / 'new'),
                           (self.prior, self.prior), (self.prior, self.prior / 'new'),
                           (self.prior, self.root)):
            with self.subTest(prior=prior, new=new), self.assertRaises(ValueError):
                runner.preserved_run_artifacts(prior, new)

    def test_old_results_are_not_used_as_new_observations(self):
        sealed = runner.preserved_run_artifacts(self.prior, self.root / 'new')
        self.assertEqual(len(sealed), 3)
        self.assertFalse((self.root / 'new').exists())

    def test_missing_live_telemetry_stops_owned_workload(self):
        experiment = self.root / 'new'
        experiment.mkdir()
        (experiment / 'manifest.json').write_text(json.dumps({
            'arms': ['canonical'], 'policy': runner.run_policy('2g', '1g')}))
        for group in ('', '/missing-live-telemetry-test'):
            state = {'ActiveState': 'active', 'SubState': 'running', 'ControlGroup': group}
            with self.subTest(group=group), ExitStack() as stack:
                stack.enter_context(patch.object(runner, 'check_preserved'))
                stack.enter_context(patch.object(runner, 'check_snapshot'))
                stack.enter_context(patch.object(runner, 'available', return_value=32 * runner.GIB))
                stack.enter_context(patch.object(runner, 'oom_kills', return_value=0))
                stack.enter_context(patch.object(runner.shutil, 'disk_usage',
                                                return_value=type('Disk', (), {'free': 100 * runner.GIB})()))
                stack.enter_context(patch.object(runner, 'properties', return_value=state))
                calls = stack.enter_context(patch.object(runner.subprocess, 'run'))
                with self.assertRaisesRegex(RuntimeError, 'UNKNOWN live service'):
                    runner.supervise(experiment, 'test-owned')
                calls.assert_any_call(['systemctl', '--user', 'stop', 'test-owned-workload.service'],
                                      check=False, timeout=15)
                report = json.loads((experiment / 'resource-guard.json').read_text())
                self.assertEqual(report['status'], 'STOPPED')
                self.assertNotIn('swap_bytes', report)
        self.assertIsNone(runner.live_cgroup({'ActiveState': 'inactive', 'ControlGroup': ''}))
