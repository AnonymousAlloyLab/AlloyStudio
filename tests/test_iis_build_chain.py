"""Local build-to-IIS dependency chain; no installed IIS server is changed."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

from scripts.package_iis import build_package


ROOT = Path(__file__).resolve().parents[1]
MARKER = 'FRESH_LOCAL_BUILD_NOVICE_HINT_2026'


def class_hashes(directory):
    return {path.relative_to(directory).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in directory.rglob('*.class')}


class IisPackageBuildChainTests(unittest.TestCase):
    """Use the real CLI, real JDK, existing classes, and an existing archive."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-iis-build-chain-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'relocated source with spaces'
        self.root.mkdir()
        for directory in ('web', 'deploy/iis', 'engine/src', 'vendor/acgn', 'vendor/sqlean', 'sql', 'scripts', 'exercises', 'docs', 'examples'):
            shutil.copytree(ROOT / directory, self.root / directory,
                            ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        for name in ('server.py', 'luna.py', 'runtime_dependencies.py', 'exercise_store.py', 'exercise_sql.py', 'LICENSE', 'openai.example.json'):
            shutil.copy2(ROOT / name, self.root / name)
        self.classes = self.root / 'build/engine/classes'
        shutil.copytree(ROOT / 'build/engine/classes', self.classes)
        self.output = self.root / 'build/iis/alloy-studio-iis.zip'
        self.unrelated = self.base / 'unrelated working directory'
        self.unrelated.mkdir()
        self.javac = shutil.which('javac')
        if not self.javac:
            raise RuntimeError('The local IIS build-chain witness requires a Java 17+ JDK.')

    def invoke(self, *arguments):
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR', 'HOME',
                   'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {name: os.environ[name] for name in allowed if name in os.environ}
        environment.update(OPENAI_DISABLED='1', CLASSPATH=str(self.unrelated / 'absent ambient classes'))
        return subprocess.run(
            [sys.executable, '-I', str(self.root / 'scripts/package_iis.py'),
             '--source', str(self.root), '--javac', self.javac, *map(str, arguments)],
            cwd=self.unrelated, env=environment, capture_output=True, encoding='utf-8',
            timeout=120, check=False)

    def modify_source(self):
        path = self.root / 'engine/src/live/LiveFeedback.java'
        content = path.read_text(encoding='utf-8')
        declaration = 'public final class LiveFeedback {'
        self.assertIn(declaration, content)
        path.write_text(content.replace(declaration, declaration + '\n    public static final String BUILD_CHAIN_HINT = "' + MARKER + '";', 1), encoding='utf-8')
        self.assertNotIn(MARKER.encode(), (self.classes / 'live/LiveFeedback.class').read_bytes())

    def test_cli_recompiles_changed_source_and_replaces_old_zip_without_obsolete_classes(self):
        obsolete = self.classes / 'live/ObsoleteBuildChainClass.class'
        obsolete.write_bytes((self.classes / 'live/LiveFeedback.class').read_bytes())
        build_package(self.root, self.output)
        old_archive = self.output.read_bytes()
        self.modify_source()
        result = self.invoke('--output', self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        metadata = json.loads(result.stdout)
        self.assertEqual(Path(metadata['archive']), self.output)
        self.assertNotEqual(old_archive, self.output.read_bytes())
        self.assertFalse(obsolete.exists())
        self.assertIn(MARKER.encode(), (self.classes / 'live/LiveFeedback.class').read_bytes())
        with zipfile.ZipFile(self.output) as archive:
            self.assertIn(MARKER.encode(), archive.read('backend/build/engine/classes/live/LiveFeedback.class'))
            self.assertNotIn('backend/build/engine/classes/live/ObsoleteBuildChainClass.class', archive.namelist())
            self.assertEqual(archive.read('wwwroot/app.js'), (self.root / 'web/app.js').read_bytes())
        self.assertEqual(metadata['sha256'], hashlib.sha256(self.output.read_bytes()).hexdigest())
        self.assertEqual(self.output.with_suffix('.zip.sha256').read_text().split()[0], metadata['sha256'])

    def test_custom_compiler_output_is_the_tree_packaged_and_old_default_is_ignored(self):
        build_package(self.root, self.output)
        old_default = class_hashes(self.classes)
        self.modify_source()
        alternate = self.root / 'build/custom compiled classes'
        alternate.mkdir(parents=True)
        (alternate / 'obsolete.class').write_bytes((self.classes / 'live/LiveFeedback.class').read_bytes())
        custom_archive = self.root / 'build/iis/custom local 更新.zip'
        result = self.invoke('--classes-output', alternate, '--output', custom_archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(class_hashes(self.classes), old_default)
        self.assertFalse((alternate / 'obsolete.class').exists())
        with zipfile.ZipFile(custom_archive) as archive:
            compiled = archive.read('backend/build/engine/classes/live/LiveFeedback.class')
            self.assertEqual(compiled, (alternate / 'live/LiveFeedback.class').read_bytes())
            self.assertIn(MARKER.encode(), compiled)
            self.assertFalse(any('custom compiled classes' in name for name in archive.namelist()))
        self.assertEqual(Path(json.loads(result.stdout)['archive']), custom_archive)
        self.assertEqual(custom_archive.with_suffix('.zip.sha256').read_text(encoding='utf-8'),
                         hashlib.sha256(custom_archive.read_bytes()).hexdigest()
                         + '  ' + custom_archive.name + '\n')

    def test_failed_compilation_preserves_old_archive_checksum_and_classes_without_success(self):
        build_package(self.root, self.output)
        old_archive = self.output.read_bytes()
        old_checksum = self.output.with_suffix('.zip.sha256').read_bytes()
        old_classes = class_hashes(self.classes)
        self.modify_source()
        path = self.root / 'engine/src/live/LiveFeedback.java'
        with path.open('a', encoding='utf-8') as source:
            source.write('\nTHIS_IS_AN_INTENTIONAL_JAVA_SYNTAX_FAILURE\n')
        result = self.invoke('--output', self.output)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn('"archive"', result.stdout)
        self.assertNotIn('"sha256"', result.stdout)
        self.assertEqual(self.output.read_bytes(), old_archive)
        self.assertEqual(self.output.with_suffix('.zip.sha256').read_bytes(), old_checksum)
        self.assertEqual(class_hashes(self.classes), old_classes)

    def test_default_cli_builds_distinct_timestamped_archives_and_preserves_readonly_legacy_pair(self):
        build_package(self.root, self.output)
        legacy_checksum = self.output.with_suffix('.zip.sha256')
        legacy_contents = {path: path.read_bytes() for path in (self.output, legacy_checksum)}
        legacy_modes = {}
        for path in legacy_contents:
            original_mode = path.stat().st_mode
            self.addCleanup(lambda item=path, mode=original_mode: item.chmod(mode) if item.exists() else None)
            path.chmod(0o400)
            legacy_modes[path] = path.stat().st_mode
        self.modify_source()
        archives = []
        for _ in range(2):
            result = self.invoke()
            self.assertEqual(result.returncode, 0, result.stderr)
            metadata = json.loads(result.stdout)
            archive_path = Path(metadata['archive'])
            self.assertEqual(archive_path.parent, self.output.parent)
            self.assertRegex(archive_path.name, r'^alloy-studio-iis-\d{8}-\d{6}-\d{6}Z(?:-\d+)?\.zip$')
            self.assertNotIn(archive_path, archives)
            self.assertNotEqual(archive_path, self.output)
            self.assertEqual(metadata['sha256'], hashlib.sha256(archive_path.read_bytes()).hexdigest())
            self.assertEqual(archive_path.with_suffix('.zip.sha256').read_text(encoding='utf-8'),
                             metadata['sha256'] + '  ' + archive_path.name + '\n')
            with zipfile.ZipFile(archive_path) as archive:
                self.assertIn(MARKER.encode(), archive.read('backend/build/engine/classes/live/LiveFeedback.class'))
                self.assertEqual(archive.read('wwwroot/app.js'), (self.root / 'web/app.js').read_bytes())
            archives.append(archive_path)
        self.assertEqual(archives[0].read_bytes(), archives[1].read_bytes())
        for path, contents in legacy_contents.items():
            self.assertEqual(path.read_bytes(), contents)
            self.assertEqual(path.stat().st_mode, legacy_modes[path])


class PortalBuildPackagingDispatchTests(unittest.TestCase):
    """Execute the Bash wrapper; compilation/archive content are tested above."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-portal-build-dispatch-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.root = self.base / 'checkout with spaces'
        shutil.copytree(ROOT / 'scripts', self.root / 'scripts',
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
        (self.root / 'engine').mkdir()
        (self.root / 'engine/build.sh').write_text('#!/usr/bin/env bash\nexit 97\n')
        (self.root / 'engine/build.sh').chmod(0o700)
        (self.root / 'exercises').mkdir()
        for name in ('catalogue.json', 'correct-pools.json'):
            (self.root / 'exercises' / name).write_text('{}')
        self.output = self.root / 'build/iis/alloy-studio-iis.zip'
        self.output.parent.mkdir(parents=True)
        self.output.write_bytes(b'old archive retained until successful release')
        self.bin = self.base / 'controlled tools'
        self.bin.mkdir()
        self.log = self.base / 'invocations.jsonl'
        self.unrelated = self.base / 'unrelated directory'
        self.unrelated.mkdir()
        self.bash = shutil.which('bash')
        if not self.bash:
            raise RuntimeError('The local wrapper regression requires Bash.')
        self.tool('uname', 'print("Linux")\n')
        self.tool('python3', '''import json, os, sys
from pathlib import Path
with open(os.environ['BUILD_LOG'], 'a') as output:
    output.write(json.dumps({'tool': 'python', 'args': sys.argv[1:], 'cwd': os.getcwd()}) + '\\n')
if any(Path(item).name == 'package_iis.py' for item in sys.argv[1:]):
    raise SystemExit(int(os.environ.get('PACKAGE_EXIT', '0')))
raise SystemExit(int(os.environ.get('PYTHON_CHECK_EXIT', '0')))
''')
        self.tool('node', '''import json, os, sys
with open(os.environ['BUILD_LOG'], 'a') as output:
    output.write(json.dumps({'tool': 'node', 'args': sys.argv[1:]}) + '\\n')
raise SystemExit(int(os.environ.get('NODE_CHECK_EXIT', '0')))
''')
        self.tool('javac', 'raise SystemExit(98)\n')

    def tool(self, name, source):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + source)
        path.chmod(0o700)

    def invoke(self, *arguments, **variables):
        environment = dict(os.environ)
        for name in list(environment):
            if name.startswith('OPENAI_') or name in ('ACGN_ROOT', 'CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'):
                environment.pop(name, None)
        environment.update(OPENAI_DISABLED='1', BUILD_LOG=str(self.log),
                           PATH=str(self.bin) + os.pathsep + environment.get('PATH', ''))
        environment.update({name: str(value) for name, value in variables.items()})
        self.log.unlink(missing_ok=True)
        return subprocess.run([self.bash, str(self.root / 'scripts/build.sh'), *arguments],
                              cwd=self.unrelated, env=environment, capture_output=True,
                              encoding='utf-8', timeout=20, check=False)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def test_portal_build_packages_once_after_checks_without_a_second_compilation(self):
        result = self.invoke('build/custom classes')
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        packages = [call for call in calls if any(Path(arg).name == 'package_iis.py' for arg in call['args'])]
        self.assertEqual(len(packages), 1)
        self.assertEqual(calls[-1], packages[0])
        self.assertTrue(any(call['tool'] == 'node' and '--check' in call['args'] for call in calls[:-1]))
        self.assertFalse(any(any(Path(arg).name == 'build_engine.py' for arg in call['args']) for call in calls))
        argv = packages[0]['args']
        selected = Path(argv[argv.index('--classes-output') + 1])
        self.assertEqual(selected if selected.is_absolute() else self.root / selected,
                         self.root / 'build/custom classes')
        self.assertEqual(Path(packages[0]['cwd']), self.root)

    def test_validation_failure_prevents_packaging_and_retains_previous_artifact(self):
        original = self.output.read_bytes()
        for failure in ({'PYTHON_CHECK_EXIT': 31}, {'NODE_CHECK_EXIT': 32}):
            with self.subTest(failure=failure):
                result = self.invoke(**failure)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(any(Path(arg).name == 'package_iis.py' for arg in call['args'])
                                     for call in self.calls()))
                self.assertEqual(self.output.read_bytes(), original)

    def test_packaging_failure_is_propagated_as_build_failure(self):
        result = self.invoke(PACKAGE_EXIT=23)
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(len([call for call in self.calls()
                              if any(Path(arg).name == 'package_iis.py' for arg in call['args'])]), 1)
        self.assertNotIn('"archive"', result.stdout)


if __name__ == '__main__':
    unittest.main()
