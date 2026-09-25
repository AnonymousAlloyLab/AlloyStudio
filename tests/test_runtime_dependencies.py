"""Relocated runtime checks and one-at-a-time dependency fault injection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from runtime_dependencies import JAR_FILES, REQUIRED_CLASSES, runtime_classpath
from scripts.package_iis import build_package


ROOT = Path(__file__).resolve().parents[1]


class RuntimeDependencyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='alloy-runtime-portability-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.base = Path(cls.temporary.name)
        cls.archive = cls.base / 'private-package.zip'
        build_package(ROOT, cls.archive)
        cls.package = cls.base / 'relocated package with spaces'
        with zipfile.ZipFile(cls.archive) as archive:
            archive.extractall(cls.package)
        cls.backend = cls.package / 'backend'
        cls.unrelated = cls.base / 'unrelated working directory'
        cls.unrelated.mkdir()
        cls.java = shutil.which('java')
        if cls.java is None:
            raise RuntimeError('Java 17+ is required for the portability witness.')

    def environment(self, poisoned=False):
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR', 'HOME',
                   'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {key: os.environ[key] for key in allowed if key in os.environ}
        environment['OPENAI_DISABLED'] = '1'
        if poisoned:
            environment.update({
                'CLASSPATH': str(self.unrelated / 'missing-external-dependencies.jar'),
                'PYTHONPATH': str(self.unrelated / 'missing-python-modules'),
                'JAVA_TOOL_OPTIONS': '-XX:DefinitelyNotAnAlloyOption',
                '_JAVA_OPTIONS': '-XX:DefinitelyNotAnAlloyOption',
                'JDK_JAVA_OPTIONS': '--definitely-not-an-alloy-option',
            })
        return environment

    def checker(self, *, java=None, poisoned=False, dependencies_only=False):
        command = [sys.executable, '-I', str(self.backend / 'runtime_dependencies.py'),
                   '--root', str(self.backend)]
        if java is not None:
            command += ['--java', java]
        if dependencies_only:
            command.append('--dependencies-only')
        completed = subprocess.run(command, cwd=self.unrelated,
                                   env=self.environment(poisoned), capture_output=True,
                                   encoding='utf-8', timeout=60, check=False)
        self.assertEqual(completed.stderr, '', 'Checker diagnostics belong in its sanitized JSON report.')
        return completed, json.loads(completed.stdout)

    def test_relocated_package_checks_all_dependencies_and_runs_java_with_clean_environment(self):
        completed, report = self.checker(java=self.java, poisoned=True)
        self.assertEqual(completed.returncode, 0, report)
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['engine'], {'status': 'PASS', 'checks': 372})
        dependencies = {item['name']: item for item in report['dependencies']}
        self.assertEqual(set(dependencies), set(JAR_FILES))
        for item in dependencies.values():
            self.assertEqual(item['status'], 'PASS')
            self.assertEqual(item['sha256'], item['expectedSha256'])
            self.assertRegex(item['sha256'], r'^[0-9a-f]{64}$')
        expected = [str(self.backend / 'build/engine/classes')]
        expected += [str(self.backend / 'vendor/acgn/lib' / name) for name in JAR_FILES]
        self.assertEqual(runtime_classpath(self.backend).split(os.pathsep), expected)
        self.assertNotIn('*', runtime_classpath(self.backend))

    def test_each_missing_jar_fails_standalone_portability_check(self):
        for name in JAR_FILES:
            with self.subTest(jar=name):
                path = self.backend / 'vendor/acgn/lib' / name
                displaced = path.with_suffix('.temporarily-absent')
                path.rename(displaced)
                try:
                    completed, report = self.checker()
                    self.assertNotEqual(completed.returncode, 0)
                    self.assertEqual(report['status'], 'FAIL')
                    item = next(item for item in report['dependencies'] if item['name'] == name)
                    self.assertEqual(item['status'], 'FAIL')
                    self.assertIsNone(item['sha256'])
                    self.assertIn('vendor/acgn/lib/' + name, [error['path'] for error in report['errors']])
                finally:
                    displaced.rename(path)

    def test_each_corrupt_jar_fails_standalone_portability_check(self):
        for name in JAR_FILES:
            with self.subTest(jar=name):
                path = self.backend / 'vendor/acgn/lib' / name
                original = path.read_bytes()
                # A ZIP/JAR can tolerate trailing bytes. The inventory hash must
                # still reject this change even when its classes remain usable.
                path.write_bytes(original + b'\nPORTABILITY_CORRUPTION_WITNESS\n')
                try:
                    completed, report = self.checker()
                    self.assertNotEqual(completed.returncode, 0)
                    self.assertEqual(report['status'], 'FAIL')
                    item = next(item for item in report['dependencies'] if item['name'] == name)
                    self.assertEqual(item['status'], 'FAIL')
                    self.assertNotEqual(item['sha256'], item['expectedSha256'])
                    self.assertIn('vendor/acgn/lib/' + name, [error['path'] for error in report['errors']])
                finally:
                    path.write_bytes(original)

    def test_missing_alloyasg_blocks_server_before_health_endpoint_is_started(self):
        path = self.backend / 'vendor/acgn/lib/AlloyASG.jar'
        displaced = path.with_suffix('.temporarily-absent')
        path.rename(displaced)
        try:
            completed = subprocess.run(
                [sys.executable, '-E', '-s', str(self.backend / 'server.py'),
                 '--host', '127.0.0.1', '--port', '0', '--java', self.java],
                cwd=self.unrelated, env=self.environment(), capture_output=True,
                encoding='utf-8', timeout=20, check=False,
            )
            self.assertNotEqual(completed.returncode, 0)
            self.assertNotIn('Alloy practice: http://', completed.stdout)
            self.assertIn('AlloyASG.jar', completed.stdout + completed.stderr)
        finally:
            displaced.rename(path)

    def test_missing_required_compiled_classes_fail_portability_check(self):
        for name in REQUIRED_CLASSES:
            with self.subTest(class_file=name):
                path = self.backend / 'build/engine/classes' / name
                displaced = path.with_suffix('.temporarily-absent')
                path.rename(displaced)
                try:
                    completed, report = self.checker()
                    self.assertNotEqual(completed.returncode, 0)
                    self.assertEqual(report['status'], 'FAIL')
                    self.assertIn(Path(name).name, json.dumps(report))
                finally:
                    displaced.rename(path)

    def test_unavailable_java_cannot_pass_runtime_execution_check(self):
        completed, report = self.checker(java=str(self.unrelated / 'missing-java-executable'))
        self.assertNotEqual(completed.returncode, 0)
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['engine']['status'], 'FAIL')

    def test_source_dependency_preflight_works_before_compilation(self):
        classes = self.backend / 'build/engine/classes'
        displaced = classes.with_name('classes.precompile-backup')
        classes.rename(displaced)
        try:
            completed, report = self.checker(dependencies_only=True, poisoned=True)
            self.assertEqual(completed.returncode, 0, report)
            self.assertEqual(report['status'], 'PASS')
            self.assertEqual(report['classes'], [])
            self.assertEqual(report['engine'], {'status': 'NOT_RUN', 'checks': 0})
            self.assertEqual({item['name'] for item in report['dependencies']}, set(JAR_FILES))
        finally:
            displaced.rename(classes)


if __name__ == '__main__':
    unittest.main()
