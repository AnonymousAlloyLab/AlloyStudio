"""Private source import publication and fail-closed checks."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts import prepare_private_data


ROOT = Path(__file__).resolve().parents[1]
MODEL = '''sig Node { adj: set Node }
pred inv1 { STUDENT }
pred inv1c { no iden & adj }
check correct { inv1 <=> inv1c }
pred under { inv1 and !inv1c }
pred over { !inv1 and inv1c }
run over
run under
'''


class PrivateDataImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name) / 'project'
        self.source = Path(self.temp.name) / 'ACGN'
        self.root.mkdir()
        (self.source / 'classified-data').mkdir(parents=True)

    def tearDown(self):
        self.temp.cleanup()

    def write_corpus(self):
        for classification, body in (('under', 'some Node'),
                                      ('correct', 'all n: Node | n not in n.adj')):
            folder = self.source / 'classified-data/graphs' / classification
            folder.mkdir(parents=True)
            (folder / 'fixture_inv1.als').write_text(
                MODEL.replace('STUDENT', body), encoding='utf-8')

    def assert_real_private_pair(self):
        catalogue = json.loads((self.root / 'exercises/catalogue.json').read_text())
        pools = json.loads((self.root / 'exercises/correct-pools.json').read_text())
        prepare_private_data.verify_document(catalogue, pools)
        self.assertEqual([item['id'] for item in catalogue['exercises']], ['graphs-inv1'])
        self.assertEqual(catalogue['exercises'][0]['source']['path'],
                         'classified-data/graphs/under/fixture_inv1.als')
        self.assertEqual([item['kind'] for item in pools['pools'][0]['candidates']],
                         ['correct-student', 'oracle'])

    def test_real_corpus_import_preserves_correct_candidate_and_explicit_oracle(self):
        self.write_corpus()
        result = prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(result['action'], 'imported')
        self.assertEqual(result['exercises'], 1)
        self.assertEqual(result['correctCandidates'], 1)
        self.assert_real_private_pair()

    @unittest.skipIf(os.name == 'nt', 'POSIX entrypoint is covered here; Windows dispatch has separate witnesses.')
    def test_linux_fresh_build_imports_original_corpus_and_compiles_vendored_dependencies(self):
        self.source = Path(self.temp.name) / 'original corpus with spaces'
        self.write_corpus()
        for directory in ('scripts', 'engine/src', 'vendor/acgn', 'web'):
            shutil.copytree(ROOT / directory, self.root / directory,
                            ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copy2(ROOT / 'engine/build.sh', self.root / 'engine/build.sh')
        shutil.copy2(ROOT / 'server.py', self.root / 'server.py')
        shutil.copy2(ROOT / 'package.json', self.root / 'package.json')
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR', 'HOME',
                   'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {name: os.environ[name] for name in allowed if name in os.environ}
        environment.update(ACGN_ROOT=str(self.source), OPENAI_DISABLED='1')
        completed = subprocess.run(
            ['bash', str(self.root / 'scripts/build.sh'), 'compiled output with spaces'],
            cwd=self.temp.name, env=environment, capture_output=True,
            encoding='utf-8', timeout=90, check=False)
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assert_real_private_pair()
        self.assertTrue((self.root / 'compiled output with spaces/live/LiveFeedback.class').is_file())
        self.assertFalse((self.source / 'lib').exists(),
                         'The original corpus cannot provide accidental compiler dependencies.')

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
