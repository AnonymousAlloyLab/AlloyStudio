"""Finite source-build portability checks; native Windows execution is separate.

The offline suite uses the real JDK with the dependency-only preflight and the
same explicit compiler inputs as build.ps1. PowerShell's wiring is checked here;
an actual PowerShell invocation is also run separately when that tool is present.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from runtime_dependencies import ENGINE_CHECKS, JAR_FILES


ROOT = Path(__file__).resolve().parents[1]


class WindowsSourceBuildTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix='alloy-source-build-')
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.base = Path(cls.temporary.name)
        cls.source = cls.base / 'relocated complete source with spaces'
        cls.source.mkdir()
        for directory in ('engine/src', 'vendor/acgn'):
            shutil.copytree(ROOT / directory, cls.source / directory)
        shutil.copy2(ROOT / 'runtime_dependencies.py', cls.source / 'runtime_dependencies.py')
        cls.unrelated = cls.base / 'unrelated working directory'
        cls.unrelated.mkdir()
        cls.javac = shutil.which('javac')
        cls.java = shutil.which('java')
        if cls.javac is None or cls.java is None:
            raise RuntimeError('A Java 17+ JDK is required for the source-build witness.')

    def environment(self):
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR', 'HOME',
                   'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {name: os.environ[name] for name in allowed if name in os.environ}
        environment['OPENAI_DISABLED'] = '1'
        environment['CLASSPATH'] = str(self.unrelated / 'nonexistent ambient classes')
        return environment

    def preflight(self):
        completed = subprocess.run(
            [sys.executable, '-I', str(self.source / 'runtime_dependencies.py'),
             '--root', str(self.source), '--dependencies-only'],
            cwd=self.unrelated, env=self.environment(), capture_output=True,
            encoding='utf-8', timeout=30, check=False,
        )
        self.assertEqual(completed.stderr, '')
        return completed, json.loads(completed.stdout)

    def test_fresh_relocated_source_build_uses_explicit_seven_jar_classpath(self):
        output = self.source / 'fresh class output with spaces'
        self.assertFalse(output.exists(), 'This witness must not reuse compiled classes.')
        completed, report = self.preflight()
        self.assertEqual(completed.returncode, 0, report)
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['classes'], [], 'Source builds cannot require existing class files.')
        self.assertEqual([item['name'] for item in report['dependencies']], list(JAR_FILES))
        dependency_paths = [self.source / item['path'] for item in report['dependencies']]
        classpath = os.pathsep.join(map(str, dependency_paths))
        self.assertNotIn('*', classpath)
        self.assertTrue(all(path.is_file() for path in dependency_paths))
        output.mkdir()
        sourcepath = os.pathsep.join(str(self.source / directory)
                                    for directory in ('engine/src', 'vendor/acgn/src'))
        compiled = subprocess.run(
            [self.javac, '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source',
             '-cp', classpath, '-sourcepath', sourcepath, '-d', str(output),
             str(self.source / 'engine/src/live/LiveFeedback.java'),
             str(self.source / 'engine/src/live/EngineSelfTest.java')],
            cwd=self.unrelated, env=self.environment(), capture_output=True,
            encoding='utf-8', timeout=90, check=False,
        )
        self.assertEqual(compiled.returncode, 0, compiled.stderr)
        self.assertTrue((output / 'live/LiveFeedback.class').is_file())
        tested = subprocess.run(
            [self.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
             '-cp', os.pathsep.join((str(output), classpath)), 'live.EngineSelfTest'],
            cwd=self.unrelated, env=self.environment(), capture_output=True,
            encoding='utf-8', timeout=40, check=False,
        )
        self.assertEqual(tested.returncode, 0, tested.stderr)
        self.assertEqual(tested.stdout.strip(), f'EngineSelfTest passed ({ENGINE_CHECKS} checks)')

    def test_each_missing_jar_fails_source_build_preflight(self):
        for name in JAR_FILES:
            with self.subTest(jar=name):
                path = self.source / 'vendor/acgn/lib' / name
                displaced = path.with_suffix('.temporarily-absent')
                path.rename(displaced)
                try:
                    completed, report = self.preflight()
                    self.assertNotEqual(completed.returncode, 0)
                    self.assertEqual(report['status'], 'FAIL')
                    self.assertIn({'code': 'MISSING_OR_UNSAFE_DEPENDENCY',
                                   'path': 'vendor/acgn/lib/' + name}, report['errors'])
                finally:
                    displaced.rename(path)

    def test_powershell_builder_wires_fail_closed_preflight_before_explicit_classpath(self):
        source = (ROOT / 'scripts/build.ps1').read_text(encoding='utf-8')
        preflight = source.index('--dependencies-only')
        compilation = source.index('& $JavaCompiler @compilerArguments')
        self.assertLess(preflight, compilation)
        between = source[preflight:compilation]
        self.assertIn('$dependencyExit = $LASTEXITCODE', between)
        self.assertRegex(between, r'\$dependencyExit\s+-ne\s+0')
        self.assertIn('throw', between)
        self.assertIn('ConvertFrom-Json', source[:compilation])
        self.assertRegex(source[:compilation], r'\.dependencies\b')
        self.assertIn('[IO.Path]::PathSeparator', between)
        self.assertNotRegex(source, r"lib[\\/]\*")
        self.assertRegex(source, r"'-cp'\s*,\s*\$[A-Za-z][A-Za-z0-9_]*")


if __name__ == '__main__':
    unittest.main()
