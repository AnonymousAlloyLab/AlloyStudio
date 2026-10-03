"""Finite IIS archive checks; actual Windows/IIS acceptance runs on the host."""
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import queue
import re
import shutil
import sqlite3
import stat
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET
import zipfile

from scripts import package_iis as package_module
from scripts.package_iis import (
    DEPLOY_FILES, JAR_FILES, REQUIRED_CLASSES, RUNTIME_HELPERS, WEB_FILES, ZIP_TIME,
    PackageError, STORE_FILES, ADMIN_FILES, ADMIN_MODULES, build_package,
)


ROOT = Path(__file__).resolve().parents[1]


class TimestampedArchiveNameTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix='alloy-dated-archive-')
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.clock = patch.object(package_module, 'datetime', wraps=datetime)
        clock = self.clock.start()
        self.addCleanup(self.clock.stop)
        clock.now.return_value = datetime(2026, 9, 29, 1, 2, 3, 456789, tzinfo=timezone.utc)
        self.now = clock.now

    def test_default_name_uses_utc_and_microseconds(self):
        output = package_module.default_archive_path(self.root)
        self.assertEqual(output, self.root / 'build/iis/alloy-studio-iis-20260929-010203-456789Z.zip')
        self.now.assert_called_once_with(timezone.utc)
        self.assertFalse(output.exists())

    def test_existing_archive_or_checksum_reserves_a_name_without_changing_either(self):
        first = package_module.default_archive_path(self.root)
        first.parent.mkdir(parents=True)
        first.write_bytes(b'previous archive')
        second = first.with_name(first.stem + '-2.zip')
        second_checksum = second.with_suffix('.zip.sha256')
        second_checksum.write_bytes(b'previous checksum without archive')
        selected = package_module.default_archive_path(self.root)
        self.assertEqual(selected, first.with_name(first.stem + '-3.zip'))
        self.assertEqual(first.read_bytes(), b'previous archive')
        self.assertEqual(second_checksum.read_bytes(), b'previous checksum without archive')
        self.assertFalse(second.exists())
        self.assertFalse(selected.exists())

    @unittest.skipIf(os.name == 'nt', 'Windows symlink creation requires additional local privilege.')
    def test_dangling_archive_and_checksum_links_reserve_their_names(self):
        first = package_module.default_archive_path(self.root)
        first.parent.mkdir(parents=True)
        first.symlink_to(self.root / 'absent archive target')
        second = first.with_name(first.stem + '-2.zip')
        second_checksum = second.with_suffix('.zip.sha256')
        second_checksum.symlink_to(self.root / 'absent checksum target')
        self.assertEqual(package_module.default_archive_path(self.root),
                         first.with_name(first.stem + '-3.zip'))
        self.assertTrue(first.is_symlink())
        self.assertTrue(second_checksum.is_symlink())


class IisPackageTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name).resolve()
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
        self.write('web/app.js', "import { renderInstanceGraph } from './instance-graph.js';\n")
        self.write('web/index.html', '<link rel="stylesheet" href="./styles.css">'
                   '<script type="module" src="./app.js"></script>')
        self.write('web/dashboard/index.html', '<link rel="stylesheet" href="./styles.css">'
                   '<script type="module" src="./app.js"></script>')
        self.write('web/admin/index.html', '<link rel="stylesheet" href="./styles.css">'
                   '<script type="module" src="./app.js"></script>')
        for name in DEPLOY_FILES:
            self.write(f'deploy/iis/{name}', '<configuration />' if name == 'web.config' else 'operator deployment guide')
        self.write('LICENSE', 'Portal licence')
        self.write('server.py', '"""Private backend."""\n')
        self.write('luna.py', '"""Private explanation client."""\n')
        for module in ('engine_workers.py', 'traffic_scheduler.py', 'traffic_http.py'):
            self.write(module, (ROOT / module).read_bytes())
        self.write('runtime_dependencies.py', (ROOT / 'runtime_dependencies.py').read_bytes())
        self.write('openai.example.json', json.dumps({'api_key': ''}))
        for name in (*STORE_FILES, *ADMIN_FILES, *ADMIN_MODULES):
            self.write(name, (ROOT / name).read_bytes())
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
                  'descriptionProvenance': 'Test fixture',
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
                     'vendor/acgn/lib/untracked.jar', 'secrets/openai.key', 'config/openai.json',
                     'admin.local.json', '.admin-config-fixture.tmp', 'web/admin/admin.local.json',
                     'web/admin/upload.als', 'web/admin/drafts.json'):
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
        self.assertIn(b'PRIVATE_ORACLE_EXPRESSION', entries['backend/exercises/exercises.sqlite3'])
        self.assertIn(b'PRIVATE_CORRECT_EXPRESSION', entries['backend/exercises/exercises.sqlite3'])
        self.assertTrue(all(b'PRIVATE_CORRECT_EXPRESSION' not in data for name, data in entries.items()
                            if name.startswith('wwwroot/')))
        self.assertIn('backend/server.py', entries)
        self.assertIn('backend/runtime_dependencies.py', entries)
        self.assertEqual(json.loads(entries['backend/openai.example.json']), {'api_key': ''})
        self.assertNotIn('backend/openai.local.json', entries)
        self.assertNotIn('backend/admin.local.json', entries)
        self.assertTrue(all(f'backend/{name}' in entries for name in ADMIN_MODULES))
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

    def test_changed_query_artifact_is_rejected_before_archive_publication(self):
        self.write('sql/compiled-queries.json', '{}')
        with self.assertRaisesRegex(PackageError, 'query artifacts'):
            build_package(self.root, self.output)
        self.assertFalse(self.output.exists())

    def test_database_remains_authoritative_when_legacy_json_is_removed(self):
        _, first = self.archive()
        (self.root / 'exercises/catalogue.json').unlink()
        (self.root / 'exercises/correct-pools.json').unlink()
        _, second = self.archive()
        self.assertEqual(first, second)
        self.assertNotIn('backend/exercises/catalogue.json', second)
        self.assertNotIn('backend/exercises/correct-pools.json', second)

    @unittest.skipIf(os.name == 'nt', 'Windows symlink creation requires additional local privilege.')
    def test_package_snapshot_supports_symlinked_system_temporary_directory(self):
        from exercise_store import ensure_store, load_store
        expected = ensure_store(self.root)
        physical = self.base / 'private' / 'var' / 'folders'
        physical.mkdir(parents=True)
        alias = self.base / 'var'
        alias.symlink_to(self.base / 'private' / 'var', target_is_directory=True)
        # macOS normally selects /var/folders/... as its temporary directory,
        # while /var itself is a system alias for /private/var. Exercise the
        # real database backup and packaging code through the same shape.
        with patch.object(tempfile, 'tempdir', str(alias / 'folders')):
            entries = package_module.collect_files(self.root)
        snapshot = self.base / 'packaged-snapshot.sqlite3'
        snapshot.write_bytes(entries['backend/exercises/exercises.sqlite3'])
        actual = load_store(self.root, snapshot)
        self.assertEqual(actual.exercises, expected.exercises)
        self.assertEqual(actual.correct_pools, expected.correct_pools)
        self.assertEqual(list(physical.iterdir()), [])

    @unittest.skipIf(os.name == 'nt', 'Windows symlink creation requires additional local privilege.')
    def test_caller_supplied_database_paths_still_reject_symlink_ancestors(self):
        from exercise_store import StoreError, backup_store, ensure_store
        ensure_store(self.root)
        database = self.root / 'exercises' / 'exercises.sqlite3'
        original = database.read_bytes()
        alias = self.base / 'linked-exercises'
        alias.symlink_to(database.parent, target_is_directory=True)
        with self.assertRaisesRegex(StoreError, 'links or junctions'):
            backup_store(self.root, alias / 'caller-snapshot.sqlite3')
        self.assertFalse((database.parent / 'caller-snapshot.sqlite3').exists())
        destination = self.base / 'safe-snapshot.sqlite3'
        with self.assertRaisesRegex(StoreError, 'links or junctions'):
            backup_store(self.root, destination, database_path=alias / database.name)
        self.assertFalse(destination.exists())
        self.assertEqual(database.read_bytes(), original)

    def test_consistent_wal_snapshot_preserves_admin_additions_and_excludes_uncommitted_writes(self):
        from exercise_store import add_exercise, ensure_store, load_store
        for name in ('LICENSE', 'snapshot.json', *(f'lib/{jar}' for jar in JAR_FILES)):
            self.write('vendor/acgn/' + name, (ROOT / 'vendor/acgn' / name).read_bytes())
        shutil.copytree(ROOT / 'build/engine/classes', self.root / 'build/engine/classes', dirs_exist_ok=True)
        ensure_store(self.root)
        document = {'id': 'private-added', 'title': 'Private addition', 'group': 'fixture',
                    'description': 'Committed description', 'predicate': 'inv1',
                    'predicateHeader': 'pred inv1 ', 'environmentBefore': 'sig Node {}\n',
                    'environmentAfter': '\n', 'starter': 'some Node',
                    'oracleSolutions': ['no Node', 'not some Node']}
        connection = sqlite3.connect(self.root / 'exercises/exercises.sqlite3')
        writer = None
        try:
            self.assertEqual(connection.execute('PRAGMA journal_mode=WAL').fetchone()[0], 'wal')
            connection.execute('BEGIN')
            connection.execute('SELECT COUNT(*) FROM exercises').fetchone()
            add_exercise(self.root, document)
            self.assertTrue((self.root / 'exercises/exercises.sqlite3-wal').is_file())
            writer = sqlite3.connect(self.root / 'exercises/exercises.sqlite3')
            writer.execute('UPDATE exercises SET description=? WHERE id=?',
                           ('UNCOMMITTED_PRIVATE_CHANGE', document['id']))
            _, entries = self.archive()
            manifest = json.loads(entries['manifest.json'])
            self.assertEqual(manifest['exerciseCount'], 2)
            self.assertEqual(manifest['correctCandidateCount'], 4)
            packaged_path = self.base / 'committed-backup.sqlite3'
            packaged_path.write_bytes(entries['backend/exercises/exercises.sqlite3'])
            packaged = load_store(self.root, database_path=packaged_path)
            self.assertEqual(packaged.exercises['private-added']['description'], 'Committed description')
            self.assertEqual(packaged.correct_pools['private-added'], ('no Node', 'not some Node'))
            self.assertFalse(any(name.endswith(('-wal', '-shm', '-journal')) for name in entries))
        finally:
            if writer is not None:
                writer.rollback()
                writer.close()
            connection.rollback()
            connection.close()

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

    def test_asset_urls_follow_content_changes_even_with_identical_zip_timestamps(self):
        source_html = (self.root / 'web/index.html').read_bytes()
        _, first = self.archive()
        first_manifest = json.loads(first['manifest.json'])
        for name in ('app.js', 'styles.css'):
            version = hashlib.sha256(first['wwwroot/' + name]).hexdigest()
            self.assertIn(('./' + name + '?v=' + version).encode(), first['wwwroot/index.html'])
            self.assertEqual(first_manifest['publicAssetVersions'][name], version)
        for name in ('app.js', 'styles.css'):
            with self.subTest(asset=name):
                original = (self.root / 'web' / name).read_bytes()
                self.write('web/' + name, original + b' changed feature')
                # Extracted releases have the same mtime; only content may
                # determine whether a browser requests a different URL.
                os.utime(self.root / 'web' / name, (315532800, 315532800))
                _, changed = self.archive()
                version = hashlib.sha256(changed['wwwroot/' + name]).hexdigest()
                self.assertIn(('./' + name + '?v=' + version).encode(), changed['wwwroot/index.html'])
                self.assertNotEqual(first['wwwroot/index.html'], changed['wwwroot/index.html'])
                self.write('web/' + name, original)
        _, restored = self.archive()
        self.assertEqual(restored, first)
        self.assertEqual((self.root / 'web/index.html').read_bytes(), source_html)

    def test_dashboard_asset_hashes_refresh_independently_of_portal_assets(self):
        _, first = self.archive()
        manifest = json.loads(first['manifest.json'])
        for name in ('app.js', 'styles.css'):
            version = hashlib.sha256(first['wwwroot/dashboard/' + name]).hexdigest()
            self.assertEqual(manifest['publicAssetVersions']['dashboard/' + name], version)
            self.assertIn(('./' + name + '?v=' + version).encode(), first['wwwroot/dashboard/index.html'])
        self.write('web/dashboard/app.js', 'updated dashboard')
        _, changed = self.archive()
        self.assertNotEqual(first['wwwroot/dashboard/index.html'], changed['wwwroot/dashboard/index.html'])
        self.assertEqual(first['wwwroot/index.html'], changed['wwwroot/index.html'])

    def test_module_change_versions_the_dependency_and_importing_application(self):
        original_app = (self.root / 'web/app.js').read_bytes()
        _, first = self.archive()
        first_manifest = json.loads(first['manifest.json'])
        name = 'instance-graph.js'
        version = hashlib.sha256(first['wwwroot/' + name]).hexdigest()
        self.assertEqual(first_manifest['publicAssetVersions'][name], version)
        self.assertEqual(first['wwwroot/app.js'], original_app.replace(
            b"'./instance-graph.js'", ("'./instance-graph.js?v=" + version + "'").encode()))
        self.write('web/instance-graph.js', 'updated graph rendering')
        _, changed = self.archive()
        self.assertNotEqual(first['wwwroot/app.js'], changed['wwwroot/app.js'])
        self.assertNotEqual(first['wwwroot/index.html'], changed['wwwroot/index.html'])
        self.assertEqual(first['wwwroot/dashboard/index.html'], changed['wwwroot/dashboard/index.html'])
        self.assertEqual(first['wwwroot/admin/index.html'], changed['wwwroot/admin/index.html'])
        self.assertEqual((self.root / 'web/app.js').read_bytes(), original_app)
        changed_manifest = json.loads(changed['manifest.json'])
        for asset in ('app.js', 'instance-graph.js'):
            self.assertEqual(changed_manifest['publicAssetVersions'][asset],
                             hashlib.sha256(changed['wwwroot/' + asset]).hexdigest())

    def test_missing_or_ambiguous_module_import_preserves_previous_archive(self):
        self.archive()
        original = self.output.read_bytes()
        import_line = (self.root / 'web/app.js').read_bytes()
        for application in (b'// no dependency', import_line + import_line,
                            import_line.replace(b'instance-graph.js', b'instance-graph.js?v=stale')):
            with self.subTest(application=application):
                self.write('web/app.js', application)
                with self.assertRaisesRegex(PackageError, 'relative instance-graph.js import'):
                    build_package(self.root, self.output)
                self.assertEqual(self.output.read_bytes(), original)

    def test_admin_asset_hashes_refresh_without_exposing_private_configuration(self):
        self.write('admin.local.json', '{"hash":"PRIVATE_ADMIN_HASH_SENTINEL"}')
        self.write('.admin-config-fixture.tmp', 'PRIVATE_ADMIN_HASH_SENTINEL')
        _, first = self.archive()
        manifest = json.loads(first['manifest.json'])
        for name in ('app.js', 'styles.css'):
            version = hashlib.sha256(first['wwwroot/admin/' + name]).hexdigest()
            self.assertEqual(manifest['publicAssetVersions']['admin/' + name], version)
            self.assertIn(('./' + name + '?v=' + version).encode(), first['wwwroot/admin/index.html'])
        self.assertFalse(any(b'PRIVATE_ADMIN_HASH_SENTINEL' in data for data in first.values()))
        self.write('web/admin/app.js', 'updated administration shell')
        _, changed = self.archive()
        self.assertNotEqual(first['wwwroot/admin/index.html'], changed['wwwroot/admin/index.html'])
        self.assertEqual(first['wwwroot/index.html'], changed['wwwroot/index.html'])
        self.assertEqual(first['wwwroot/dashboard/index.html'], changed['wwwroot/dashboard/index.html'])

    def test_iis_admin_permissions_and_startup_contract(self):
        manager = (ROOT / 'deploy/iis/Manage-AlloyStudio.ps1').read_text()
        readonly = manager.index('Set-RestrictedAcl -Path $BackendRoot -Recurse')
        writable = "Set-RestrictedAcl -Path (Join-Path $BackendRoot 'exercises') -LocalServiceAccess Modify -Recurse"
        self.assertGreater(manager.index(writable), readonly)
        self.assertNotRegex(manager, r'Set-RestrictedAcl -Path \$BackendRoot[^\n]*-LocalServiceAccess Modify')
        startup = (ROOT / 'deploy/iis/Start-AlloyStudio.ps1').read_text()
        self.assertIn("$expectedAssets = @('index.html', 'app.js', 'instance-graph.js', 'styles.css',", startup)
        self.assertNotRegex(startup, r'\$health\.exercises\s+-eq\s+181')
        self.assertIn('$health.exercises -gt 0', startup)
        self.assertIn('@($listing.exercises).Count -ne $health.exercises', startup)
        self.assertIn("Compare-Object @('index.html', 'app.js', 'styles.css') @($adminAssets.Name)", startup)

    def test_upgrade_permission_migration_changes_only_stopped_private_data_acl(self):
        manager = (ROOT / 'deploy/iis/Manage-AlloyStudio.ps1').read_text()
        block = manager.split("    'UpdateDataPermissions' {", 1)[1].split("    'Restart' {", 1)[0]
        mutation = 'Set-RestrictedAcl -Path $exerciseDirectory -LocalServiceAccess Modify -Recurse'
        self.assertEqual(block.count('Set-RestrictedAcl'), 1)
        self.assertIn(mutation, block)
        for preflight in ("$existing.State -eq 'Running'", 'Get-NetTCPConnection -State Listen',
                          'Get-LocalPath -Path $configPath', 'Get-IisPhysicalRoots',
                          'Assert-PrivatePath -Path $permissionBackend',
                          'Assert-PrivatePath -Path $exerciseDirectory',
                          'Test-Path -LiteralPath $databasePath -PathType Leaf'):
            self.assertLess(block.index(preflight), block.index(mutation))
        self.assertNotRegex(block, r'Copy-Item|Remove-Item|WriteAllText|Register-ScheduledTask|Start-BackendTask')
        guide = (ROOT / 'deploy/iis/README.md').read_text()
        upgrade = guide[guide.index('1. Stop the backend and only the dedicated Alloy website'):]
        self.assertLess(upgrade.index('-Action UpdateDataPermissions'), upgrade.index('-Action Restart'))
        for preserved in ('backend/admin.local.json', 'backend/openai.local.json', 'exercises/exercises.sqlite3'):
            self.assertIn(preserved, upgrade)

    def test_missing_or_ambiguous_asset_reference_refuses_stale_browser_urls(self):
        self.archive()
        original_archive = self.output.read_bytes()
        original_html = (self.root / 'web/index.html').read_bytes()
        for html in (b'<html>No assets</html>', original_html + b'<script src="./app.js"></script>'):
            with self.subTest(html=html):
                self.write('web/index.html', html)
                with self.assertRaisesRegex(PackageError, 'relative .* reference'):
                    build_package(self.root, self.output)
                self.assertEqual(self.output.read_bytes(), original_archive)

    def test_missing_input_refuses_package_and_preserves_previous_archive(self):
        self.archive()
        original = self.output.read_bytes()
        for name in ('web/app.js', 'web/instance-graph.js', 'deploy/iis/web.config', 'deploy/iis/Start-AlloyStudio.ps1',
                     'openai.example.json', 'exercise_store.py', 'exercise_sql.py', 'sql/compiled-queries.json',
                     'scripts/import_correct_pools.py', 'scripts/import_exercises.py',
                     'build/engine/classes/live/LiveFeedback.class'):
            with self.subTest(name=name):
                path = self.root / name
                payload = path.read_bytes()
                path.unlink()
                expected = 'Missing deployment input'
                with self.assertRaisesRegex(PackageError, expected):
                    build_package(self.root, self.output)
                self.assertEqual(self.output.read_bytes(), original)
                self.write(name, payload)

    def test_default_cli_does_not_attempt_to_replace_locked_legacy_archive_or_checksum(self):
        self.output = self.root / 'build/iis/alloy-studio-iis.zip'
        self.archive()
        checksum = self.output.with_suffix('.zip.sha256')
        originals = {path: path.read_bytes() for path in (self.output, checksum)}
        original_replace = os.replace
        output = io.StringIO()
        denied_attempts = []

        def deny_legacy_replace(source, destination):
            if Path(destination) in originals:
                denied_attempts.append(destination)
                error = PermissionError(13, 'simulated legacy archive lock')
                error.winerror = 5
                raise error
            return original_replace(source, destination)

        with patch('scripts.build_engine.compile_engine', return_value=self.root / 'build/engine/classes'), \
                patch.object(package_module.os, 'replace', side_effect=deny_legacy_replace), \
                patch.object(sys, 'argv', ['package_iis.py', '--source', str(self.root)]), \
                redirect_stdout(output):
            self.assertEqual(package_module.main(), 0)
        selected = Path(json.loads(output.getvalue())['archive'])
        self.assertEqual(selected.parent, self.output.parent)
        self.assertNotEqual(selected, self.output)
        self.assertTrue(selected.is_file())
        self.assertEqual(denied_attempts, [])
        for path, contents in originals.items():
            self.assertEqual(path.read_bytes(), contents)

    def test_failed_compilation_does_not_allocate_a_default_archive_name(self):
        from scripts.build_engine import BuildError
        with patch('scripts.build_engine.compile_engine', side_effect=BuildError('fixture compile failure')), \
                patch.object(package_module, 'default_archive_path') as choose_name, \
                patch.object(sys, 'argv', ['package_iis.py', '--source', str(self.root)]), \
                redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                package_module.main()
        self.assertEqual(raised.exception.code, 1)
        choose_name.assert_not_called()
        self.assertFalse((self.root / 'build/iis').exists())

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
                with self.assertRaisesRegex(PackageError, 'database validation'):
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
            from exercise_store import load_store
            original = load_store(ROOT)
            self.assertEqual(manifest['exerciseCount'], original.exercise_count)
            self.assertEqual(original.exercise_count, 181)
            self.assertEqual(manifest['knownCorrectPoolCount'], 181)
            self.assertEqual(manifest['correctCandidateCount'], 7731)
            self.assertEqual(json.loads(archive.read('backend/openai.example.json')), {'api_key': ''})
            self.assertNotIn('backend/openai.local.json', archive.namelist())
            self.assertNotIn('backend/exercises/catalogue.json', archive.namelist())
            self.assertNotIn('backend/exercises/correct-pools.json', archive.namelist())
            database = self.base / 'packaged.sqlite3'
            database.write_bytes(archive.read('backend/exercises/exercises.sqlite3'))
            packaged = load_store(ROOT, database_path=database)
            self.assertEqual(packaged.exercises, original.exercises)
            self.assertEqual(packaged.correct_pools, original.correct_pools)
            self.assertTrue(all(f'backend/vendor/acgn/lib/{name}' in archive.namelist() for name in JAR_FILES))
            self.assertGreater(result['files'], len(REQUIRED_CLASSES) + len(JAR_FILES))
        # Reproduce a clone with no corpus using the real package, not just a
        # synthetic two-entry ZIP. Source/witness validation runs on all pools.
        from scripts.prepare_private_data import prepare
        restored = self.base / 'fresh checkout without ACGN'
        restored.mkdir()
        restoration = prepare(restored, bundle=self.output)
        self.assertEqual(restoration['action'], 'restored-bundle')
        self.assertEqual(restoration['exercises'], 181)
        self.assertEqual(restoration['correctCandidates'], 7550)
        self.assertEqual(load_store(restored).exercises, original.exercises)
        self.assertEqual(load_store(restored).correct_pools, original.correct_pools)
        self.assertFalse((restored / 'exercises/catalogue.json').exists())
        self.assertFalse((restored / 'exercises/correct-pools.json').exists())

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
            from exercise_store import load_store
            expected_size = len(load_store(extracted / 'backend').correct_pools['graphs-inv1'])
            self.assertGreater(expected_size, 1, 'The runtime smoke exercise must include correct student references.')
            self.assertEqual(feedback['comparison'], {'strategy': 'nearest-known-correct',
                             'poolSize': expected_size, 'evaluatedCandidates': expected_size, 'complete': True})
            self.assertEqual(sum(operation['cost'] for operation in feedback['operations']), feedback['distance'])
            self.assertEqual(request('/api/feedback', payload, 'https://other.example.invalid')[0], 403)
            for path in ('/server.py', '/luna.py', '/exercises/catalogue.json', '/exercises/correct-pools.json',
                         '/exercises/exercises.sqlite3', '/exercises/exercises.sqlite3-wal',
                         '/exercises/exercises.sqlite3-shm', '/exercises/exercises.sqlite3-journal',
                         '/sql/schema.json', '/sql/compiled-queries.json', '/exercise_store.py',
                         '/scripts/manage_exercises.py', '/vendor/sqlean/provenance.json',
                         '/admin.local.json', '/admin/admin.local.json', '/admin/upload.als',
                         '/admin_auth.py', '/admin_upload.py', '/admin_luna.py', '/admin_service.py',
                         '/scripts/configure_admin.py',
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
        for path in ('api', 'api/health', 'api/exercises/graphs-inv1', 'api/feedback', 'api/explain', 'api/admin/session', 'api/admin/prepare'):
            self.assertIsNotNone(expression.fullmatch(path))
        for path in ('other-api/health', 'apiX/health', 'alloy/api/health', '/api/health', 'server.py'):
            self.assertIsNone(expression.fullmatch(path))
        action = proxy.find('action')
        self.assertEqual(action.get('type'), 'Rewrite')
        self.assertEqual(action.get('url'), 'http://127.0.0.1:8080/{R:0}')
        self.assertEqual(action.get('appendQueryString'), 'true')
        blocked = re.compile(boundary.find('match').get('url'))
        self.assertEqual(boundary.find('action').get('statusCode'), '404')
        for path in ('', 'index.html', 'app.js', 'instance-graph.js', 'styles.css', 'dashboard', 'dashboard/',
                     'dashboard/index.html', 'dashboard/app.js', 'dashboard/styles.css', 'dashboard/data.json',
                     'admin', 'admin/', 'admin/index.html', 'admin/app.js', 'admin/styles.css'):
            self.assertIsNone(blocked.fullmatch(path))
        for path in ('web.config', 'manifest.json', 'backend/server.py', 'exercises/catalogue.json',
                     'exercises/correct-pools.json', 'scripts/import_correct_pools.py',
                     'openai.local.json', 'openai.example.json',
                     '.env', 'openai.key', 'app.js.map', 'instance-graph.js.map',
                     'instance-graph.js/extra', 'INSTANCE-GRAPH.JS', '../server.py', 'INDEX.HTML',
                     'dashboard/.env', 'dashboard/closure-report.json', 'dashboard/../server.py',
                     'dashboard/backend/catalogue.json', 'dashboard/index.html/extra',
                     'admin.local.json', 'admin/admin.local.json', 'admin/upload.als', 'admin/drafts.json',
                     'admin/.admin-config-fixture.tmp', 'admin/index.html/extra', 'admin_auth.py',
                     'admin_upload.py', 'admin_luna.py', 'admin_service.py', 'scripts/configure_admin.py'):
            self.assertIsNotNone(blocked.fullmatch(path))


if __name__ == '__main__':
    unittest.main()
