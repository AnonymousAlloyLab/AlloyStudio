"""A fresh rerun seals previous evidence without rewriting interrupted streams."""
from pathlib import Path
import tempfile
import unittest

from benchmarks.alloy4fun import run_pinned_acgn as runner

ROOT = Path(__file__).resolve().parents[1]


class PinnedAcgnPreservationTests(unittest.TestCase):
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
