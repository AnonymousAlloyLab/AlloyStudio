"""The current review ladder requires actual 6.1 Sol records and hash links."""
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import review_ladder as ladder


class ReviewLadderTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build/review-ladder-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.block = 'block.json'
        (self.root / self.block).write_text(json.dumps({'id': 'Fixture'}))
        directory = self.root / 'formal/reviews/Fixture'
        directory.mkdir(parents=True)
        prior = {}
        for tier, (prefix, model) in enumerate(ladder.TIERS, 1):
            current = {}
            for suffix in ('a', 'b'):
                relative = f'formal/reviews/Fixture/{prefix}-{suffix}.json'
                notes = (self.root / relative).with_suffix('.md')
                notes.write_text('Finite advisory fixture; no proof claim.\n')
                record = {'reviewerModel': model, 'tier': tier,
                    'blockManifestSha256': ladder.digest(self.root / self.block),
                    'priorReviews': prior, 'notesSha256': ladder.digest(notes),
                    'verdict': 'no_constructed_breach', 'findings': []}
                (self.root / relative).write_text(json.dumps(record))
                current[relative] = ladder.digest(self.root / relative)
            prior.update(current)

    def mutate(self, name, field, value):
        p = self.root / f'formal/reviews/Fixture/{name}.json'
        record = json.loads(p.read_text())
        record[field] = value
        p.write_text(json.dumps(record))

    def test_six_current_reviews_and_six_notes_are_bound(self):
        self.assertEqual(len(ladder.check_reviews(self.root, self.block)), 12)
        self.assertEqual(ladder.TIERS[1], ('sol', 'gpt-6.1-sol'))

    def test_previous_sol_model_is_rejected(self):
        self.mutate('sol-a', 'reviewerModel', 'gpt-6-sol')
        with self.assertRaises(ladder.Rejected):
            ladder.check_reviews(self.root, self.block)

    def test_missing_prior_tier_hashes_are_rejected(self):
        self.mutate('astra-a', 'priorReviews', {})
        with self.assertRaises(ladder.Rejected):
            ladder.check_reviews(self.root, self.block)

    def test_review_notes_mutation_is_rejected(self):
        (self.root / 'formal/reviews/Fixture/luna-a.md').write_text('Changed coverage.')
        with self.assertRaises(ladder.Rejected):
            ladder.check_reviews(self.root, self.block)

    def test_stale_block_and_unresolved_findings_are_rejected(self):
        for field, value in [('blockManifestSha256', 'stale'),
                             ('verdict', 'constructed_breach'),
                             ('findings', [{'witness': 'actual failure'}])]:
            with self.subTest(field=field):
                p = self.root / 'formal/reviews/Fixture/astra-b.json'
                original = p.read_bytes()
                self.mutate('astra-b', field, value)
                with self.assertRaises(ladder.Rejected):
                    ladder.check_reviews(self.root, self.block)
                p.write_bytes(original)


if __name__ == '__main__':
    unittest.main()
