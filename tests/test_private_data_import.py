"""Private source import publication and fail-closed checks."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import warnings
import zipfile

from scripts import prepare_private_data
from scripts.import_exercises import verify_record


ROOT = Path(__file__).resolve().parents[1]
PRIVATE_MEMBERS = ('backend/exercises/catalogue.json',
                   'backend/exercises/correct-pools.json')
PRIVATE_SENTINEL = 'PRIVATE_FIXTURE_CONTENT_MUST_NEVER_APPEAR'
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

    def private_pair_bytes(self):
        self.write_corpus()
        catalogue = prepare_private_data.build_catalogue(self.source)
        pools = prepare_private_data.build_document(catalogue, self.source)
        return {PRIVATE_MEMBERS[0]: (json.dumps(catalogue, indent=2) + '\n').encode(),
                PRIVATE_MEMBERS[1]: prepare_private_data.canonical(pools)}

    def write_bundle(self, entries, *, manifest_change=None, omit=(), duplicate=None):
        manifest = {
            'schemaVersion': 1,
            'application': 'Alloy Studio',
            'distribution': 'IIS 10',
            'privateArchive': True,
            'exerciseCount': len(json.loads(entries[PRIVATE_MEMBERS[0]])['exercises']),
            'knownCorrectPoolCount': len(json.loads(entries[PRIVATE_MEMBERS[1]])['pools']),
            'files': [{'path': name, 'bytes': len(data),
                       'sha256': hashlib.sha256(data).hexdigest()}
                      for name, data in entries.items()],
        }
        if manifest_change:
            manifest_change(manifest)
        bundle = Path(self.temp.name) / 'private bundle.zip'
        with zipfile.ZipFile(bundle, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(manifest))
            for name, data in entries.items():
                if name not in omit:
                    archive.writestr(name, data)
            if duplicate:
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore', UserWarning)
                    archive.writestr(duplicate, entries[duplicate])
        return bundle

    def run_cli(self, *arguments):
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR',
                   'HOME', 'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {name: os.environ[name] for name in allowed if name in os.environ}
        source_arguments = [] if '--from-bundle' in arguments else ['--source-root', str(self.source)]
        return subprocess.run(
            [sys.executable, str(ROOT / 'scripts/prepare_private_data.py'),
             '--root', str(self.root), *source_arguments, *arguments],
            cwd=self.temp.name, env=environment, capture_output=True,
            encoding='utf-8', timeout=30, check=False)

    def assert_no_private_pair(self):
        for name in ('catalogue.json', 'correct-pools.json'):
            self.assertFalse((self.root / 'exercises' / name).exists())
        self.assertFalse(list(self.root.rglob('.private-import-*')))

    def assert_real_private_pair(self):
        catalogue = json.loads((self.root / 'exercises/catalogue.json').read_text())
        pools = json.loads((self.root / 'exercises/correct-pools.json').read_text())
        for record in catalogue['exercises']:
            verify_record(record)
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
        for directory in ('scripts', 'engine/src', 'vendor/acgn', 'web', 'deploy/iis'):
            shutil.copytree(ROOT / directory, self.root / directory,
                            ignore=shutil.ignore_patterns('__pycache__'))
        shutil.copy2(ROOT / 'engine/build.sh', self.root / 'engine/build.sh')
        for name in ('server.py', 'luna.py', 'runtime_dependencies.py', 'openai.example.json', 'LICENSE'):
            shutil.copy2(ROOT / name, self.root / name)
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
        self.write_corpus()
        result = prepare_private_data.prepare(self.root, self.source)
        before = [(self.root / 'exercises' / name).read_bytes()
                  for name in ('catalogue.json', 'correct-pools.json')]
        second = prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(result['action'], 'imported')
        self.assertEqual(second['action'], 'validated-existing')
        self.assert_real_private_pair()
        self.assertEqual(before[0], (self.root / 'exercises/catalogue.json').read_bytes())
        self.assertEqual(before[1], (self.root / 'exercises/correct-pools.json').read_bytes())
        self.assertFalse(list((self.root / 'exercises').glob('.private-import-*')))
        if os.name != 'nt':
            self.assertEqual((self.root / 'exercises/catalogue.json').stat().st_mode & 0o777, 0o600)
            self.assertEqual((self.root / 'exercises/correct-pools.json').stat().st_mode & 0o777, 0o600)

    def test_existing_valid_pair_does_not_require_original_source_checkout(self):
        self.write_corpus()
        first = prepare_private_data.prepare(self.root, self.source)
        before = {path.name: path.read_bytes()
                  for path in (self.root / 'exercises').iterdir()}
        shutil.rmtree(self.source)
        second = prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(second['action'], 'validated-existing')
        self.assertEqual(second['sha256'], first['sha256'])
        self.assertEqual({path.name: path.read_bytes()
                          for path in (self.root / 'exercises').iterdir()}, before)
        self.assert_real_private_pair()

    def test_partial_pair_refuses_to_overwrite(self):
        exercises = self.root / 'exercises'
        exercises.mkdir()
        catalogue = exercises / 'catalogue.json'
        catalogue.write_text(PRIVATE_SENTINEL, encoding='utf-8')
        shutil.rmtree(self.source)
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, self.source)
        self.assertIsInstance(raised.exception, ValueError)
        self.assertEqual(raised.exception.code, 'PARTIAL_PRIVATE_DATA')
        self.assertNotIn(PRIVATE_SENTINEL, str(raised.exception))
        self.assertEqual(catalogue.read_text(encoding='utf-8'), PRIVATE_SENTINEL)
        self.assertFalse((exercises / 'correct-pools.json').exists())

    def test_missing_classified_data_fails_before_creating_private_files(self):
        empty = Path(self.temp.name) / 'empty'
        empty.mkdir()
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, empty)
        self.assertEqual(raised.exception.code, 'SOURCE_CORPUS_MISSING')
        self.assertIn('--from-bundle', str(raised.exception))
        self.assertFalse((self.root / 'exercises').exists())

    def test_missing_source_root_has_safe_actionable_cli_error(self):
        shutil.rmtree(self.source)
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(raised.exception.code, 'SOURCE_CORPUS_MISSING')
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn('SOURCE_CORPUS_MISSING', completed.stderr)
        self.assertIn('--from-bundle', completed.stderr)
        self.assertIn('--source-root', completed.stderr)
        self.assertNotIn('Traceback', completed.stderr)
        self.assertEqual(completed.stdout, '')
        self.assertFalse((self.root / 'exercises').exists())

    def test_empty_classified_data_has_distinct_error(self):
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(raised.exception.code, 'EMPTY_CORPUS')
        self.assertFalse((self.root / 'exercises').exists())

    def test_bundle_restore_preserves_bytes_and_witnesses_without_source_checkout(self):
        entries = self.private_pair_bytes()
        bundle = self.write_bundle(entries)
        shutil.rmtree(self.source)
        result = prepare_private_data.prepare(self.root, bundle=bundle)
        self.assertEqual(result['action'], 'restored-bundle')
        self.assertEqual(result['exercises'], 1)
        self.assertEqual(result['correctCandidates'], 1)
        for name, data in entries.items():
            self.assertEqual((self.root / 'exercises' / Path(name).name).read_bytes(), data)
        self.assert_real_private_pair()

    def test_bundle_cli_restores_only_private_pair_without_extracting_unrelated_paths(self):
        entries = self.private_pair_bytes()
        entries.update({'backend/server.py': PRIVATE_SENTINEL.encode(),
                        '../restore-escaped.txt': PRIVATE_SENTINEL.encode(),
                        'wwwroot/unrelated.txt': PRIVATE_SENTINEL.encode()})
        bundle = self.write_bundle(entries)
        shutil.rmtree(self.source)
        completed = self.run_cli('--from-bundle', str(bundle))
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(json.loads(completed.stdout)['action'], 'restored-bundle')
        self.assertNotIn(PRIVATE_SENTINEL, completed.stdout + completed.stderr)
        self.assertEqual(sorted(path.relative_to(self.root).as_posix()
                                for path in self.root.rglob('*') if path.is_file()),
                         ['exercises/catalogue.json', 'exercises/correct-pools.json'])
        self.assertFalse((Path(self.temp.name) / 'restore-escaped.txt').exists())
        self.assert_real_private_pair()

    def test_bad_bundle_hash_duplicate_or_missing_member_fails_before_publication(self):
        entries = self.private_pair_bytes()
        cases = {
            'bad-hash': {'manifest_change': lambda manifest:
                         manifest['files'][0].update(sha256='0' * 64)},
            'bad-byte-count': {'manifest_change': lambda manifest:
                               manifest['files'][0].update(bytes=1)},
            'duplicate-member': {'duplicate': PRIVATE_MEMBERS[0]},
            'missing-member': {'omit': (PRIVATE_MEMBERS[1],)},
            'duplicate-manifest-entry': {'manifest_change': lambda manifest:
                                         manifest['files'].append(dict(manifest['files'][0]))},
            'missing-manifest-entry': {'manifest_change': lambda manifest:
                                       manifest['files'].pop()},
            'wrong-application': {'manifest_change': lambda manifest:
                                  manifest.update(application=PRIVATE_SENTINEL)},
            'wrong-schema': {'manifest_change': lambda manifest:
                             manifest.update(schemaVersion=2)},
            'public-archive': {'manifest_change': lambda manifest:
                               manifest.update(privateArchive=False)},
            'wrong-exercise-count': {'manifest_change': lambda manifest:
                                     manifest.update(exerciseCount=2)},
            'wrong-pool-count': {'manifest_change': lambda manifest:
                                 manifest.update(knownCorrectPoolCount=2)},
        }
        for name, options in cases.items():
            with self.subTest(name=name):
                bundle = self.write_bundle(entries, **options)
                with self.assertRaises(prepare_private_data.PreparationError) as raised:
                    prepare_private_data.prepare(self.root, self.source, bundle=bundle)
                self.assertEqual(raised.exception.code, 'BUNDLE_INVALID')
                self.assertNotIn(PRIVATE_SENTINEL, str(raised.exception))
                self.assert_no_private_pair()

    def test_bundle_source_must_be_a_zip_archive(self):
        entries = self.private_pair_bytes()
        directory = Path(self.temp.name) / 'unpacked bundle'
        directory.mkdir()
        for name, data in entries.items():
            destination = directory / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, bundle=directory)
        self.assertEqual(raised.exception.code, 'BUNDLE_MISSING')
        self.assert_no_private_pair()

    def test_bundle_with_valid_hashes_but_corrupt_source_witness_is_rejected(self):
        entries = self.private_pair_bytes()
        catalogue = json.loads(entries[PRIVATE_MEMBERS[0]])
        catalogue['exercises'][0]['originalSource'] += PRIVATE_SENTINEL
        entries[PRIVATE_MEMBERS[0]] = json.dumps(catalogue).encode()
        bundle = self.write_bundle(entries)
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, bundle=bundle)
        self.assertEqual(raised.exception.code, 'BUNDLE_INVALID')
        self.assertNotIn(PRIVATE_SENTINEL, str(raised.exception))
        self.assert_no_private_pair()

    def test_malformed_existing_pair_has_safe_error_and_is_never_overwritten(self):
        entries = self.private_pair_bytes()
        target = self.root / 'exercises'
        target.mkdir()
        catalogue = json.loads(entries[PRIVATE_MEMBERS[0]])
        catalogue['exercises'][0]['id'] = PRIVATE_SENTINEL
        catalogue['exercises'][0]['environmentBefore'] += PRIVATE_SENTINEL
        wrong_schema = json.loads(entries[PRIVATE_MEMBERS[0]])
        wrong_schema['schemaVersion'] = 2
        duplicate_ids = json.loads(entries[PRIVATE_MEMBERS[0]])
        duplicate_ids['exercises'].append(dict(duplicate_ids['exercises'][0]))
        malformed_catalogues = {
            'invalid-json': ('{"' + PRIVATE_SENTINEL).encode(),
            'missing-fields': json.dumps({'schemaVersion': 1,
                                         'exercises': [{'id': PRIVATE_SENTINEL}]}).encode(),
            'invalid-witness': json.dumps(catalogue).encode(),
            'wrong-schema': json.dumps(wrong_schema).encode(),
            'duplicate-ids': json.dumps(duplicate_ids).encode(),
        }
        for name, data in malformed_catalogues.items():
            with self.subTest(name=name):
                (target / 'catalogue.json').write_bytes(data)
                (target / 'correct-pools.json').write_bytes(entries[PRIVATE_MEMBERS[1]])
                with self.assertRaises(prepare_private_data.PreparationError) as raised:
                    prepare_private_data.prepare(self.root, self.source)
                self.assertEqual(raised.exception.code, 'PRIVATE_DATA_INVALID')
                self.assertNotIn(PRIVATE_SENTINEL, str(raised.exception))
                completed = self.run_cli()
                self.assertNotEqual(completed.returncode, 0)
                self.assertIn('PRIVATE_DATA_INVALID', completed.stderr)
                self.assertNotIn(PRIVATE_SENTINEL, completed.stdout + completed.stderr)
                self.assertNotIn('Traceback', completed.stderr)
                self.assertEqual(completed.stdout, '')
                self.assertEqual((target / 'catalogue.json').read_bytes(), data)
                self.assertEqual((target / 'correct-pools.json').read_bytes(),
                                 entries[PRIVATE_MEMBERS[1]])

    def test_malformed_existing_correct_pool_is_rejected_without_replacement(self):
        entries = self.private_pair_bytes()
        target = self.root / 'exercises'
        target.mkdir()
        catalogue_bytes = entries[PRIVATE_MEMBERS[0]]
        (target / 'catalogue.json').write_bytes(catalogue_bytes)
        malformed = ('{"' + PRIVATE_SENTINEL).encode()
        (target / 'correct-pools.json').write_bytes(malformed)
        with self.assertRaises(prepare_private_data.PreparationError) as raised:
            prepare_private_data.prepare(self.root, self.source)
        self.assertEqual(raised.exception.code, 'PRIVATE_DATA_INVALID')
        self.assertNotIn(PRIVATE_SENTINEL, str(raised.exception))
        completed = self.run_cli()
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn('PRIVATE_DATA_INVALID', completed.stderr)
        self.assertNotIn(PRIVATE_SENTINEL, completed.stdout + completed.stderr)
        self.assertEqual((target / 'catalogue.json').read_bytes(), catalogue_bytes)
        self.assertEqual((target / 'correct-pools.json').read_bytes(), malformed)


if __name__ == '__main__':
    unittest.main(verbosity=2)
