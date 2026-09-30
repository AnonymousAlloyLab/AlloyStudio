"""Exact-source label corrections must not migrate to unreviewed submissions."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from benchmarks.alloy4fun.prepare import (
    LABEL_CORRECTIONS, apply_label_correction, extract_one,
    load_label_corrections, validate_label_corrections,
)
from benchmarks.alloy4fun.run_live import pool_key, reference_pools


MODEL = b'''sig A {}
pred inv1 {}
pred inv1c { no A }
check correct { inv1 <=> inv1c }
pred over { !inv1 and inv1c }
pred under { inv1 and !inv1c }
run over
run under
'''


class LabelCorrectionTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.case_id = 'family/correct/reviewed_inv1.als'
        self.source = self.root / self.case_id
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(MODEL)
        self.entry = {'case_id': self.case_id, 'source_sha256': hashlib.sha256(MODEL).hexdigest(),
                      'original_label': 'CORRECT', 'corrected_label': 'UNDERCONSTRAINED'}
        self.registry = self.root / 'registry.json'

    def load(self, entries=None):
        self.registry.write_text(json.dumps({'schema_version': 1,
                                            'corrections': [self.entry] if entries is None else entries}))
        return load_label_corrections(self.registry)

    def test_registry_hash_binds_exact_registry_bytes(self):
        entries, digest = self.load()
        self.assertEqual(digest, hashlib.sha256(self.registry.read_bytes()).hexdigest())
        self.assertEqual(entries[self.case_id], self.entry)

    def test_reviewed_registry_retains_exact_two_ids_and_source_hash(self):
        entries, _ = load_label_corrections(LABEL_CORRECTIONS)
        self.assertEqual(set(entries), {
            'socialMedia/correct/6j7rC3GMvjoGpyX7u_inv1.als',
            'socialMedia/correct/RjBNwdcpzytR8C39D_inv1.als',
        })
        self.assertEqual({e['source_sha256'] for e in entries.values()},
                         {'3832147bb3c35b0fcc15615abaaa4d8d7abda89cff27cd03f658fbdab7fffd1c'})

    def test_duplicate_registry_id_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            self.load([self.entry, self.entry])

    def test_empty_registry_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'empty'):
            self.load([])

    def test_registry_original_label_must_match_source_path(self):
        self.entry['original_label'] = 'BOTH'
        with self.assertRaisesRegex(ValueError, 'original label'):
            self.load()

    def test_malformed_source_hash_is_rejected(self):
        self.entry['source_sha256'] = 'any source'
        with self.assertRaisesRegex(ValueError, 'source hash'):
            self.load()

    def test_unknown_or_unchanged_target_label_is_rejected(self):
        for value in ('CORRECT', 'UNREVIEWED', None):
            self.entry['corrected_label'] = value
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'corrected label'):
                self.load()

    def test_missing_expected_record_fails_preflight(self):
        entries, _ = self.load()
        with self.assertRaisesRegex(ValueError, 'missing'):
            validate_label_corrections(self.root, [], set(), entries)

    def test_excluding_reviewed_record_fails_preflight(self):
        entries, _ = self.load()
        with self.assertRaisesRegex(ValueError, 'excluded'):
            validate_label_corrections(self.root, [self.case_id], {self.case_id}, entries)

    def test_changed_source_fails_preflight(self):
        entries, _ = self.load()
        self.source.write_bytes(MODEL + b'\n')
        with self.assertRaisesRegex(ValueError, 'source_sha256'):
            validate_label_corrections(self.root, [self.case_id], set(), entries)

    def test_source_change_after_preflight_is_rechecked_by_extractor(self):
        entries, _ = self.load()
        validate_label_corrections(self.root, [self.case_id], set(), entries)
        self.source.write_bytes(MODEL + b'\n')
        with self.assertRaisesRegex(ValueError, 'source_sha256'):
            extract_one((self.root, self.case_id, False, entries[self.case_id]))

    def test_matching_hash_on_another_id_does_not_receive_correction(self):
        other = 'family/correct/other_inv1.als'
        (self.root / other).write_bytes(MODEL)
        with self.assertRaisesRegex(ValueError, 'case_id'):
            extract_one((self.root, other, False, self.entry))
        row, payload = extract_one((self.root, other, False))
        self.assertEqual(row['cohort_status'], 'CORRECT')
        self.assertEqual(payload['source_cohort_status'], 'CORRECT')

    def test_original_label_binding_is_checked_at_application(self):
        row = {'case_id': self.case_id, 'source_sha256': self.entry['source_sha256'],
               'source_cohort_status': 'BOTH', 'cohort_status': 'BOTH'}
        with self.assertRaisesRegex(ValueError, 'source_cohort_status'):
            apply_label_correction(row, self.entry)

    def test_exact_correction_preserves_source_bytes_and_original_label(self):
        row, payload = extract_one((self.root, self.case_id, False, self.entry))
        self.assertEqual(row['extraction_status'], 'ok')
        for value in (row, payload):
            self.assertEqual(value['source_cohort_status'], 'CORRECT')
            self.assertEqual(value['cohort_status'], 'UNDERCONSTRAINED')
            self.assertEqual(value['source_sha256'], self.entry['source_sha256'])
        self.assertEqual(self.source.read_bytes(), MODEL)

    def test_corrected_body_cannot_enter_correct_training_pool(self):
        _, repaired_label = extract_one((self.root, self.case_id, False, self.entry))
        query = {**repaired_label, 'case_id': 'query', 'model_id': 'query', 'body': 'some A',
                 'body_token_sha256': 'query-body', 'cohort_status': 'BOTH'}
        cases = [query, repaired_label]
        pools = reference_pools(cases, {'query': 0, 'reviewed': 1})
        self.assertEqual(pools[(*pool_key(query), 0)], [query['oracle_body']])


if __name__ == '__main__':
    unittest.main()
