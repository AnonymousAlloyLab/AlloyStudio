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
        self.assertIn('[string]$ACGNRoot', source)
        self.assertIn('scripts\\prepare_private_data.py', source)
        self.assertIn('classified-data', source)
        self.assertIn("$env:ACGN_ROOT", source)
        self.assertNotRegex(source, r"lib[\\/]\*")
        self.assertRegex(source, r"'-cp'\s*,\s*\$[A-Za-z][A-Za-z0-9_]*")


class WindowsBashDispatchTests(unittest.TestCase):
    """Execute the real Bash entrypoints with controlled Windows-shell adapters."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-bash-dispatch-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.source = self.base / 'relocated source with spaces'
        shutil.copytree(ROOT / 'scripts', self.source / 'scripts',
                        ignore=shutil.ignore_patterns('__pycache__'))
        (self.source / 'engine').mkdir()
        shutil.copy2(ROOT / 'engine/build.sh', self.source / 'engine/build.sh')
        self.unrelated = self.base / 'unrelated working directory'
        self.unrelated.mkdir()
        self.bin = self.base / 'mock Windows tools'
        self.bin.mkdir()
        self.capture = self.base / 'launcher-arguments.json'
        self.conversions = self.base / 'path-conversions.jsonl'
        self.javac_marker = self.base / 'bash-javac-was-invoked'
        self.bash = shutil.which('bash')
        if self.bash is None:
            raise RuntimeError('Bash is required for the Windows-shell dispatcher witness.')
        self.tool('uname', 'import os\nprint(os.environ["MOCK_UNAME"])\n')
        self.tool('cygpath', '''import json, os, sys
from pathlib import Path
arguments = sys.argv[1:]
if len(arguments) != 2 or arguments[0] != '-aw':
    raise SystemExit('The fixture expects cygpath -aw PATH')
path = Path(arguments[1]).resolve()
result = 'C:\\\\mock' + str(path).replace('/', '\\\\')
with open(os.environ['MOCK_CONVERSIONS'], 'a') as log:
    log.write(json.dumps({'input': arguments[1], 'result': result}) + '\\n')
print(result)
''')
        launcher = '''import json, os, sys
from pathlib import Path
Path(os.environ['MOCK_CAPTURE']).write_text(json.dumps({
    'argv': sys.argv[1:], 'cwd': os.getcwd(), 'launcher': Path(sys.argv[0]).name,
    'MSYS2_ARG_CONV_EXCL': os.environ.get('MSYS2_ARG_CONV_EXCL'),
    'MSYS_NO_PATHCONV': os.environ.get('MSYS_NO_PATHCONV'),
}))
raise SystemExit(int(os.environ.get('MOCK_POWERSHELL_EXIT', '0')))
'''
        for name in ('powershell.exe', 'pwsh.exe'):
            self.tool(name, launcher)
        self.tool('javac', '''import os
from pathlib import Path
Path(os.environ['MOCK_JAVAC_MARKER']).write_text('invoked')
raise SystemExit(97)
''')

    def tool(self, name, body):
        path = self.bin / name
        path.write_text('#!' + sys.executable + '\n' + body, encoding='utf-8')
        path.chmod(0o700)

    @staticmethod
    def windows_path(path):
        return 'C:\\mock' + str(path.resolve()).replace('/', '\\')

    def invoke(self, entrypoint, arguments=(), *, system='MINGW64_NT-10.0', exit_code=0):
        if self.capture.exists():
            self.capture.unlink()
        if self.conversions.exists():
            self.conversions.unlink()
        allowed = ('PATH', 'SYSTEMROOT', 'WINDIR', 'TMP', 'TEMP', 'TMPDIR', 'HOME',
                   'USERPROFILE', 'LANG', 'LC_ALL', 'TZ')
        environment = {name: os.environ[name] for name in allowed if name in os.environ}
        environment.update({
            'PATH': str(self.bin) + os.pathsep + environment.get('PATH', ''),
            'OPENAI_DISABLED': '1', 'MOCK_UNAME': system,
            'MOCK_CAPTURE': str(self.capture), 'MOCK_CONVERSIONS': str(self.conversions),
            'MOCK_JAVAC_MARKER': str(self.javac_marker),
            'MOCK_POWERSHELL_EXIT': str(exit_code),
        })
        result = subprocess.run([self.bash, str(self.source / entrypoint), *arguments],
                                cwd=self.unrelated, env=environment, capture_output=True,
                                encoding='utf-8', timeout=20, check=False)
        self.assertFalse(self.javac_marker.exists(), 'Windows Bash must not call javac directly.')
        return result

    def assert_dispatch(self, expected_output, *, engine_only=False):
        self.assertTrue(self.capture.is_file(), 'The native PowerShell launcher was not called.')
        capture = json.loads(self.capture.read_text())
        arguments = capture['argv']
        self.assertEqual(capture['MSYS2_ARG_CONV_EXCL'], '*')
        self.assertEqual(capture['MSYS_NO_PATHCONV'], '1')
        self.assertIn('-NoProfile', arguments)
        self.assertEqual(arguments[arguments.index('-File') + 1],
                         self.windows_path(self.source / 'scripts/build.ps1'))
        self.assertEqual(arguments[arguments.index('-OutputDirectory') + 1],
                         self.windows_path(expected_output))
        self.assertEqual('-EngineOnly' in arguments, engine_only)
        self.assertEqual('-RequireNode' in arguments, not engine_only)
        conversions = [json.loads(line) for line in self.conversions.read_text().splitlines()]
        self.assertEqual({item['result'] for item in conversions},
                         {self.windows_path(self.source / 'scripts/build.ps1'),
                          self.windows_path(expected_output)})

    def test_root_build_routes_all_windows_bash_variants_through_native_powershell(self):
        for system in ('MINGW64_NT-10.0', 'MSYS_NT-10.0', 'CYGWIN_NT-10.0'):
            with self.subTest(system=system):
                result = self.invoke('scripts/build.sh', system=system)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_dispatch(self.source / 'build/engine/classes')

    def test_root_custom_output_remains_relative_to_project_root(self):
        result = self.invoke('scripts/build.sh', ('custom output with spaces',))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_dispatch(self.source / 'custom output with spaces')

    def test_direct_engine_default_and_caller_relative_output_are_preserved(self):
        cases = [((), self.source / 'engine/build/classes'),
                 (('caller output with spaces',), self.unrelated / 'caller output with spaces')]
        for arguments, expected in cases:
            with self.subTest(arguments=arguments):
                result = self.invoke('engine/build.sh', arguments)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_dispatch(expected, engine_only=True)

    def test_windows_dispatch_propagates_powershell_failure(self):
        for entrypoint in ('scripts/build.sh', 'engine/build.sh'):
            with self.subTest(entrypoint=entrypoint):
                result = self.invoke(entrypoint, exit_code=23)
                self.assertEqual(result.returncode, 23, result.stderr)

    def test_windows_dispatch_supports_pwsh_fallback(self):
        (self.bin / 'powershell.exe').unlink()
        result = self.invoke('scripts/build.sh')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_dispatch(self.source / 'build/engine/classes')
        self.assertEqual(json.loads(self.capture.read_text())['launcher'], 'pwsh.exe')

    def test_failed_path_translation_stops_before_compiler_or_powershell(self):
        self.tool('cygpath', 'raise SystemExit(19)\n')
        for entrypoint in ('scripts/build.sh', 'engine/build.sh'):
            with self.subTest(entrypoint=entrypoint):
                result = self.invoke(entrypoint)
                self.assertEqual(result.returncode, 19, result.stderr)
                self.assertFalse(self.capture.exists())


if __name__ == '__main__':
    unittest.main()
