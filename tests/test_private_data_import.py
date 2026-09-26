"""Private source import publication and fail-closed checks."""
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from scripts import prepare_private_data


class PrivateDataImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'project'
        self.source = Path(self.temp.name) / 'ACGN'
        self.root.mkdir()
        (self.source / 'classified-data').mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def test_import_publishes_verified_pair_and_second_run_preserves_it(self):
        catalogue = {'schemaVersion': 1, 'exercises': [{'id': 'fixture'}], 'droppedGroups': []}
        pools = {'pools': [], 'provenance': {'correctStudentCandidates': 4}}
        with patch.object(prepare_private_data, 'build_catalogue', return_value=catalogue), \
             patch.object(prepare_private_data, 'build_document', return_value=pools), \
             patch.object(prepare_private_data, 'verify_document') as verify:
            result = prepare_private_data.prepare(self.root, self.source)
            before = [(self.root / 'exercises' / name).read_bytes()
                      for name in ('catalogue.json', 'correct-pools.json')]
            second = prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(result['action'], 'imported')
        self.assertEqual(second['action'], 'validated-existing')
        self.assertEqual(verify.call_count, 2)
        self.assertEqual(before[0], (self.root / 'exercises/catalogue.json').read_bytes())
        self.assertEqual(before[1], (self.root / 'exercises/correct-pools.json').read_bytes())
        self.assertFalse(list((self.root / 'exercises').glob('.private-import-*')))
        if os.name != 'nt':
            self.assertEqual((self.root / 'exercises/catalogue.json').stat().st_mode & 0o777, 0o600)
            self.assertEqual((self.root / 'exercises/correct-pools.json').stat().st_mode & 0o777, 0o600)

    def test_partial_pair_refuses_to_overwrite(self):
        exercises = self.root / 'exercises'
        exercises.mkdir()
        catalogue = exercises / 'catalogue.json'
        catalogue.write_text('{}', encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'Only one private data file'):
            prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(catalogue.read_text(encoding='utf-8'), '{}')

    def test_missing_classified_data_fails_before_creating_private_files(self):
        empty = Path(self.temp.name) / 'empty'
        empty.mkdir()
        with self.assertRaisesRegex(ValueError, 'classified-data'):
            prepare_private_data.prepare(self.root, empty)
        self.assertFalse((self.root / 'exercises').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)
