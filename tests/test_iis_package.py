"""Finite IIS archive checks; actual Windows/IIS acceptance runs on the host."""
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
import zipfile

from scripts.package_iis import (
    DEPLOY_FILES, JAR_FILES, REQUIRED_CLASSES, RUNTIME_HELPERS, WEB_FILES, ZIP_TIME,
    PackageError, build_package,
)


ROOT = Path(__file__).resolve().parents[1]


class IisPackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'source'
        self.output = self.base / 'private/alloy-studio-iis.zip'
        self.fixture()

    def write(self, name, data):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data if isinstance(data, bytes) else data.encode('utf-8'))

    def fixture(self):
        for name in WEB_FILES:
            self.write(f'web/{name}', 'public learner application')
        for name in DEPLOY_FILES:
            self.write(f'deploy/iis/{name}', '<configuration />' if name == 'web.config' else 'operator deployment guide')
        self.write('LICENSE', 'Portal licence')
        self.write('server.py', '"""Private backend."""\n')
        self.write('luna.py', '"""Private explanation client."""\n')
        self.write('runtime_dependencies.py', (ROOT / 'runtime_dependencies.py').read_bytes())
        self.write('openai.example.json', json.dumps({'api_key': ''}))
        for name in RUNTIME_HELPERS:
            self.write(f'scripts/{name}', (ROOT / 'scripts' / name).read_bytes())
        from scripts.import_exercises import extract_model
        from scripts.import_correct_pools import build_document

        def source(body):
            return ('sig Node {}\n'
                    'pred inv1 {\n' + body + '\n}\n'
                    'pred inv1c {\nno Node // PRIVATE_ORACLE_EXPRESSION\n}\n'
                    'check correct { inv1 <=> inv1c }\n'
                    'pred under { inv1 and !inv1c }\nrun under\n'
                    'pred over { !inv1 and inv1c }\nrun over\n')

        original = source('some Node')
        record = {'id': 'fixture-inv1', 'title': 'Fixture', 'group': 'fixture',
                  'predicate': 'inv1', 'description': 'Fixture source', 'sourceClassification': 'under',
                  'source': {'path': 'classified-data/fixture/under/example_inv1.als',
                             'sha256': hashlib.sha256(original.encode()).hexdigest()},
                  **extract_model(original.encode(), 'inv1')}
        self.catalogue = {'schemaVersion': 1, 'exercises': [record]}
        self.write('exercises/catalogue.json', json.dumps(self.catalogue))
        correct_source = source('not some Node // PRIVATE_CORRECT_EXPRESSION')
        self.write('classified-data/fixture/correct/example_inv1.als', correct_source)
        self.pools = build_document(self.catalogue, self.root)
        self.write('exercises/correct-pools.json', json.dumps(self.pools))
        dependencies = {'LICENSE': b'ACGN licence'}
        dependencies.update({f'lib/{name}': b'fixture jar: ' + name.encode() for name in JAR_FILES})
        for name, data in dependencies.items():
            self.write(f'vendor/acgn/{name}', data)
        self.snapshot = {'commit': '1' * 40, 'files': [
            {'path': name, 'sha256': hashlib.sha256(data).hexdigest()}
            for name, data in dependencies.items()
        ]}
        self.write('vendor/acgn/snapshot.json', json.dumps(self.snapshot))
        for name in REQUIRED_CLASSES:
            self.write(f'build/engine/classes/{name}', bytes.fromhex('cafebabe0000003d') + b'fixture')

    def archive(self):
        result = build_package(self.root, self.output)
        with zipfile.ZipFile(self.output) as archive:
            contents = {name: archive.read(name) for name in archive.namelist()}
        return result, contents

    def test_only_allowlisted_payloads_and_public_files_are_packaged(self):
        for name in ('web/catalogue.json', 'web/correct-pools.json', 'web/.env', 'web/leaked.key', 'server.log',
                     'build/engine/classes/credentials.txt', 'deploy/iis/local-secrets.ps1',
                     'vendor/acgn/lib/untracked.jar', 'secrets/openai.key', 'config/openai.json'):
            self.write(name, 'PRIVATE_UNLISTED_SENTINEL')
        self.write('openai.local.json', json.dumps({'api_key': 'PRIVATE_UNLISTED_SENTINEL'}))
        _, entries = self.archive()
        self.assertEqual({name for name in entries if name.startswith('wwwroot/')},
                         {f'wwwroot/{name}' for name in (*WEB_FILES, 'web.config')})
        self.assertEqual({name for name in entries if name.startswith('deploy/')},
                         {f'deploy/iis/{name}' for name in DEPLOY_FILES})
        self.assertTrue(all(b'PRIVATE_UNLISTED_SENTINEL' not in data for data in entries.values()))
        self.assertTrue(all(b'PRIVATE_ORACLE_EXPRESSION' not in data for name, data in entries.items()
                            if name.startswith('wwwroot/')))
        self.assertIn(b'PRIVATE_ORACLE_EXPRESSION', entries['backend/exercises/catalogue.json'])
        self.assertIn(b'PRIVATE_CORRECT_EXPRESSION', entries['backend/exercises/correct-pools.json'])
        self.assertTrue(all(b'PRIVATE_CORRECT_EXPRESSION' not in data for name, data in entries.items()
                            if name.startswith('wwwroot/')))
        self.assertIn('backend/server.py', entries)
        self.assertIn('backend/runtime_dependencies.py', entries)
        self.assertEqual(json.loads(entries['backend/openai.example.json']), {'api_key': ''})
        self.assertNotIn('backend/openai.local.json', entries)
        self.assertEqual({name for name in entries if name.startswith('backend/scripts/')},
                         {f'backend/scripts/{name}' for name in RUNTIME_HELPERS})
        self.assertNotIn('backend/web/index.html', entries)

    def test_manifest_hashes_every_payload_and_binds_provenance(self):
        result, entries = self.archive()
        manifest = json.loads(entries.pop('manifest.json'))
        expected = [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                    for name, data in sorted(entries.items())]
        self.assertEqual(manifest['files'], expected)
        self.assertEqual(manifest['acgnCommit'], self.snapshot['commit'])
        self.assertEqual(manifest['exerciseCount'], 1)
        self.assertEqual(manifest['knownCorrectPoolCount'], 1)
        self.assertTrue(manifest['privateArchive'])
        self.assertEqual(manifest['publicDirectory'], 'wwwroot')
        self.assertEqual(result['sha256'], hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertEqual(self.output.with_suffix('.zip.sha256').read_text(),
                         result['sha256'] + '  alloy-studio-iis.zip\n')

    def test_archive_bytes_ignore_source_permissions_mtime_and_destination(self):
        first, _ = self.archive()
        first_bytes = self.output.read_bytes()
        for path in self.root.rglob('*'):
            if path.is_file():
                os.utime(path, (1700000000, 1700000000))
                path.chmod(0o600)
        second_output = self.base / 'elsewhere/second.zip'
        second = build_package(self.root, second_output)
        self.assertEqual(first['sha256'], second['sha256'])
        self.assertEqual(first_bytes, second_output.read_bytes())
        with zipfile.ZipFile(second_output) as archive:
            self.assertEqual(archive.namelist(), sorted(archive.namelist()))
            self.assertTrue(all(info.date_time == ZIP_TIME for info in archive.infolist()))
            self.assertTrue(all(info.compress_type == zipfile.ZIP_STORED for info in archive.infolist()))

    def test_missing_input_refuses_package_and_preserves_previous_archive(self):
        self.archive()
        original = self.output.read_bytes()
        for name in ('web/app.js', 'deploy/iis/web.config', 'deploy/iis/Start-AlloyStudio.ps1',
                     'openai.example.json', 'exercises/catalogue.json', 'exercises/correct-pools.json',
                     'scripts/import_correct_pools.py', 'scripts/import_exercises.py',
                     'build/engine/classes/live/LiveFeedback.class'):
            with self.subTest(name=name):
                path = self.root / name
                payload = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(PackageError, 'Missing deployment input'):
                    build_package(self.root, self.output)
                self.assertEqual(self.output.read_bytes(), original)
                self.write(name, payload)

    def test_malformed_catalogue_is_rejected(self):
        incomplete = {'schemaVersion': 1, 'exercises': [{'id': 'incomplete'}]}
        duplicates = {'schemaVersion': 1, 'exercises': self.catalogue['exercises'] * 2}
        for data in ('not JSON', '[]', '{}', json.dumps(incomplete), json.dumps(duplicates),
                     json.dumps({'schemaVersion': 1, 'exercises': []})):
            with self.subTest(data=data):
                self.write('exercises/catalogue.json', data)
                with self.assertRaises(PackageError):
                    build_package(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_dependency_hash_and_snapshot_inventory_are_enforced(self):
        for name in JAR_FILES:
            with self.subTest(jar=name):
                path = self.root / 'vendor/acgn/lib' / name
                original = path.read_bytes()
                path.unlink()
                with self.assertRaisesRegex(PackageError, 'Missing deployment input'):
                    build_package(self.root, self.output)
                self.write(f'vendor/acgn/lib/{name}', 'modified dependency')
                with self.assertRaisesRegex(PackageError, 'differs from its snapshot'):
                    build_package(self.root, self.output)
                path.write_bytes(original)
        self.fixture()
        self.snapshot['files'] = self.snapshot['files'][:-1]
        self.write('vendor/acgn/snapshot.json', json.dumps(self.snapshot))
        with self.assertRaisesRegex(PackageError, 'missing licensed runtime dependencies'):
            build_package(self.root, self.output)
        self.write('vendor/acgn/snapshot.json', '{')
        with self.assertRaisesRegex(PackageError, 'Invalid UTF-8 JSON'):
            build_package(self.root, self.output)

    def test_correct_pools_must_cover_exact_catalogue_exercises(self):
        for variant in ('invalid-json', 'empty', 'duplicate', 'wrong-exercise'):
            with self.subTest(variant=variant):
                document = json.loads(json.dumps(self.pools))
                if variant == 'empty':
                    document['pools'] = []
                elif variant == 'duplicate':
                    document['pools'] *= 2
                elif variant == 'wrong-exercise':
                    document['pools'][0]['exerciseId'] = 'unknown-inv1'
                self.write('exercises/correct-pools.json', '{' if variant == 'invalid-json' else json.dumps(document))
                with self.assertRaises(PackageError):
                    build_package(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_pool_context_oracle_and_original_witness_are_bound(self):
        for variant in ('context', 'missing-oracle', 'duplicate-oracle', 'oracle-body', 'witness', 'malformed-candidate'):
            with self.subTest(variant=variant):
                document = json.loads(json.dumps(self.pools))
                pool = document['pools'][0]
                if variant == 'context':
                    pool['environmentSha256'] = '0' * 64
                elif variant == 'missing-oracle':
                    pool['candidates'] = pool['candidates'][:-1]
                elif variant == 'duplicate-oracle':
                    pool['candidates'].append(pool['candidates'][-1])
                elif variant == 'oracle-body':
                    pool['candidates'][-1]['body'] = 'some univ'
                elif variant == 'witness':
                    pool['candidates'][0]['originalSource'] += '\nfact { no Node }\n'
                elif variant == 'malformed-candidate':
                    pool['candidates'][0] = None
                self.write('exercises/correct-pools.json', json.dumps(document))
                with self.assertRaisesRegex(PackageError, 'pool validation failed'):
                    build_package(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_invalid_or_newer_java_class_is_rejected(self):
        for data in (b'uncompiled', bytes.fromhex('cafebabe00000041')):
            with self.subTest(data=data):
                self.write('build/engine/classes/live/LiveFeedback.class', data)
                with self.assertRaisesRegex(PackageError, 'Java 17 compatible'):
                    build_package(self.root, self.output)

    @unittest.skipIf(os.name == 'nt', 'Windows symlink creation requires additional local privilege.')
    def test_symlink_input_is_rejected(self):
        path = self.root / 'web/app.js'
        path.unlink()
        outside = self.base / 'outside.js'
        outside.write_text('outside input', encoding='utf-8')
        path.symlink_to(outside)
        with self.assertRaisesRegex(PackageError, 'Symlink deployment input'):
            build_package(self.root, self.output)

    def test_token_shaped_value_refuses_package_without_echoing_value(self):
        marker = 'sk-' + 'proj-' + 'syntheticcredential' * 4
        for name in ('luna.py', 'exercises/correct-pools.json'):
            with self.subTest(name=name):
                original = (self.root / name).read_bytes()
                self.write(name, marker)
                with self.assertRaises(PackageError) as raised:
                    build_package(self.root, self.output)
                self.assertNotIn(marker, str(raised.exception))
                self.assertFalse(self.output.exists())
                self.write(name, original)

    def test_openai_configuration_template_must_be_empty_and_exact(self):
        for document in ({'api_key': 'short-secret'}, {'api_key': None},
                         {'api_key': '', 'private_key': 'another-secret'}, {}, []):
            with self.subTest(document=document):
                self.write('openai.example.json', json.dumps(document))
                with self.assertRaises(PackageError):
                    build_package(self.root, self.output)
                self.assertFalse(self.output.exists())
        self.write('openai.example.json', '{"api_key":"short-secret","api_key":""}')
        with self.assertRaises(PackageError):
            build_package(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_private_archive_cannot_be_written_beneath_public_root(self):
        for output in (self.root / 'web/download.zip', self.root / 'wwwroot/download.zip'):
            with self.subTest(output=output):
                with self.assertRaisesRegex(PackageError, 'outside the public directory'):
                    build_package(self.root, output)
                self.assertFalse(output.exists())

    @unittest.skipIf(os.name == 'nt', 'NTFS permissions are set by the deployment installer.')
    def test_archive_and_checksum_have_private_posix_permissions(self):
        self.archive()
        for path in (self.output, self.output.with_suffix('.zip.sha256')):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_current_project_packages_all_exercises_and_runtime_dependencies(self):
        result = build_package(ROOT, self.output)
        with zipfile.ZipFile(self.output) as archive:
            manifest = json.loads(archive.read('manifest.json'))
            catalogue = json.loads(archive.read('backend/exercises/catalogue.json'))
            self.assertEqual(manifest['exerciseCount'], len(catalogue['exercises']))
            self.assertEqual(len(catalogue['exercises']), 181)
            self.assertEqual(manifest['knownCorrectPoolCount'], 181)
            self.assertEqual(json.loads(archive.read('backend/openai.example.json')), {'api_key': ''})
            self.assertNotIn('backend/openai.local.json', archive.namelist())
            self.assertEqual(archive.read('backend/exercises/catalogue.json'),
                             (ROOT / 'exercises/catalogue.json').read_bytes())
            self.assertEqual(archive.read('backend/exercises/correct-pools.json'),
                             (ROOT / 'exercises/correct-pools.json').read_bytes())
            pools = json.loads(archive.read('backend/exercises/correct-pools.json'))['pools']
            self.assertEqual({pool['exerciseId'] for pool in pools},
                             {record['id'] for record in catalogue['exercises']})
            self.assertTrue(all(f'backend/vendor/acgn/lib/{name}' in archive.namelist() for name in JAR_FILES))
            self.assertGreater(result['files'], len(REQUIRED_CLASSES) + len(JAR_FILES))

    def test_packaged_backend_runs_from_unrelated_directory_and_keeps_private_paths_closed(self):
        build_package(ROOT, self.output)
        extracted = self.base / 'installed package with spaces'
        with zipfile.ZipFile(self.output) as archive:
            archive.extractall(extracted)
        unrelated = self.base / 'unrelated working directory'
        unrelated.mkdir()
        java = shutil.which('java')
        self.assertIsNotNone(java, 'The packaged runtime smoke test requires Java 17+.')
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith('OPENAI_') and key not in ('PYTHONPATH', 'PYTHONHOME')}
        environment['OPENAI_DISABLED'] = '1'
        process = subprocess.Popen(
            [sys.executable, '-E', '-s', str(extracted / 'backend/server.py'),
             '--host', '127.0.0.1', '--port', '0', '--java', java,
             '--public-origin', 'https://alloy.example.invalid'],
            cwd=unrelated, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            encoding='utf-8',
        )
        try:
            startup = queue.Queue()
            reader = threading.Thread(target=lambda: startup.put(process.stdout.readline()), daemon=True)
            reader.start()
            line = startup.get(timeout=10)
            self.assertRegex(line, r'^Alloy practice: http://127\.0\.0\.1:[0-9]+\n$')
            base_url = line.strip().removeprefix('Alloy practice: ')

            def request(path, data=None, origin=None):
                headers = {'Content-Type': 'application/json'}
                if origin:
                    headers['Origin'] = origin
                req = Request(base_url + path, headers=headers,
                              data=json.dumps(data).encode('utf-8') if data is not None else None)
                try:
                    response = urlopen(req, timeout=20)
                except HTTPError as exc:
                    response = exc
                with response:
                    return response.status, json.loads(response.read())

            status, health = request('/api/health')
            self.assertEqual(status, 200)
            self.assertEqual(health['exercises'], 181)
            status, exercise = request('/api/exercises/graphs-inv1')
            self.assertEqual(status, 200)
            self.assertNotIn('oracleBody', exercise)
            payload = {'exerciseId': 'graphs-inv1', 'body': 'some Node // café π', 'revision': 1}
            status, feedback = request('/api/feedback', payload, 'https://alloy.example.invalid')
            self.assertEqual(status, 200)
            self.assertEqual(feedback['status'], 'ok', feedback)
            pools = json.loads((extracted / 'backend/exercises/correct-pools.json').read_text(encoding='utf-8'))['pools']
            expected_size = next(len(pool['candidates']) for pool in pools if pool['exerciseId'] == 'graphs-inv1')
            self.assertGreater(expected_size, 1, 'The runtime smoke exercise must include correct student references.')
            self.assertEqual(feedback['comparison'], {'strategy': 'nearest-known-correct',
                             'poolSize': expected_size, 'evaluatedCandidates': expected_size, 'complete': True})
            self.assertEqual(sum(operation['cost'] for operation in feedback['operations']), feedback['distance'])
            self.assertEqual(request('/api/feedback', payload, 'https://other.example.invalid')[0], 403)
            for path in ('/server.py', '/luna.py', '/exercises/catalogue.json', '/exercises/correct-pools.json',
                         '/scripts/import_correct_pools.py', '/vendor/acgn/lib/alloy.jar',
                         '/openai.local.json', '/openai.example.json',
                         '/manifest.json', '/deploy/iis/Common.ps1', '/.env', '/openai.key', '/index.html'):
                with self.subTest(path=path):
                    self.assertEqual(request(path)[0], 404)
        finally:
            process.terminate()
            try:
                process.communicate(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate(timeout=5)

    def test_iis_rules_proxy_only_application_relative_api_and_allow_only_public_assets(self):
        configuration = ET.parse(ROOT / 'deploy/iis/web.config').getroot()
        server = configuration.find('system.webServer')
        self.assertEqual(server.find('directoryBrowse').get('enabled'), 'false')
        documents = server.findall('defaultDocument/files/add')
        self.assertEqual([item.get('value') for item in documents], ['index.html'])
        rules = server.findall('rewrite/rules/rule')
        self.assertEqual(len(rules), 2)
        proxy, boundary = rules
        self.assertEqual(proxy.get('stopProcessing'), 'true')
        self.assertEqual(proxy.find('match').get('ignoreCase'), 'false')
        expression = re.compile(proxy.find('match').get('url'))
        for path in ('api', 'api/health', 'api/exercises/graphs-inv1', 'api/feedback', 'api/explain'):
            self.assertIsNotNone(expression.fullmatch(path))
        for path in ('other-api/health', 'apiX/health', 'alloy/api/health', '/api/health', 'server.py'):
            self.assertIsNone(expression.fullmatch(path))
        action = proxy.find('action')
        self.assertEqual(action.get('type'), 'Rewrite')
        self.assertEqual(action.get('url'), 'http://127.0.0.1:8080/{R:0}')
        self.assertEqual(action.get('appendQueryString'), 'true')
        blocked = re.compile(boundary.find('match').get('url'))
        self.assertEqual(boundary.find('action').get('statusCode'), '404')
        for path in ('', 'index.html', 'app.js', 'styles.css'):
            self.assertIsNone(blocked.fullmatch(path))
        for path in ('web.config', 'manifest.json', 'backend/server.py', 'exercises/catalogue.json',
                     'exercises/correct-pools.json', 'scripts/import_correct_pools.py',
                     'openai.local.json', 'openai.example.json',
                     '.env', 'openai.key', 'app.js.map', '../server.py', 'INDEX.HTML'):
            self.assertIsNotNone(blocked.fullmatch(path))


if __name__ == '__main__':
    unittest.main()
