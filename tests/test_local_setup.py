"""Local launcher regressions: mocked macOS discovery and real POSIX execution.

Fresh Git clone and synthetic ZIP recovery witnesses never open real credentials
or contact OpenAI. macOS discovery is mocked explicitly; these tests do not
claim native macOS execution.
"""
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import zipfile

from scripts import local_portal, prepare_private_data


ROOT = Path(__file__).resolve().parents[1]
JAVA_HOOKS = ('CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
              'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS')
MODEL = '''sig Node { adj: set Node }
pred inv1 { STUDENT }
pred inv1c { no iden & adj }
check correct { inv1 <=> inv1c }
pred under { inv1 and !inv1c }
pred over { !inv1 and inv1c }
run over
run under
'''
RUNTIME_DIRECTORIES = ('engine/src', 'vendor/acgn', 'vendor/sqlean', 'sql', 'web')
RUNTIME_FILES = ('traffic_profile.py', 'traffic_limits.py', 'execution_profile.py', 'engine_workers.py', 'traffic_scheduler.py', 'traffic_http.py', 'traffic_decode.py', 'traffic_identity.py', 'portal_routes.py', 'server.py', 'luna.py', 'runtime_dependencies.py', 'exercise_store.py', 'exercise_sql.py',
                 'admin_auth.py', 'admin_upload.py', 'admin_luna.py', 'admin_service.py',
                 'scripts/configure_admin.py', 'docs/admin-setup.md', 'docs/admin-security-spec.md',
                 'scripts/manage_exercises.py', 'docs/private-exercises.md',
                 'docs/sqlite-security-spec.md', 'examples/private-exercise.json',
                 'scripts/local.sh', 'scripts/local_portal.py', 'scripts/setup.sh',
                 'scripts/run.sh', 'scripts/prepare_private_data.py',
                 'scripts/import_exercises.py', 'scripts/import_correct_pools.py',
                 'scripts/exercise_descriptions.json')


def copy_runtime(checkout):
    """Copy only the runtime allowlist; never traverse local credentials or .git."""
    checkout.mkdir()
    for name in RUNTIME_DIRECTORIES:
        shutil.copytree(ROOT / name, checkout / name)
    for name in RUNTIME_FILES:
        destination = checkout / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / name, destination)


def isolated_environment(home):
    environment = {name: os.environ[name] for name in
                   ('LANG', 'LC_ALL', 'TZ', 'TMP', 'TEMP', 'TMPDIR', 'SYSTEMROOT', 'WINDIR')
                   if name in os.environ}
    environment.update(HOME=str(home), USERPROFILE=str(home), OPENAI_DISABLED='1')
    return environment


def restricted_path(directory):
    """Expose shell utilities only, excluding Node and automatic JDK discovery."""
    directory.mkdir()
    for name in ('bash', 'dirname', 'uname'):
        executable = shutil.which(name)
        if executable is None:
            raise RuntimeError(f'The POSIX launcher witness requires {name}.')
        (directory / name).symlink_to(Path(executable).resolve())
    return str(directory)


def runtime_environment(base):
    home = base / 'empty home'
    home.mkdir()
    _, compiler = local_portal.resolve_jdk(environ=local_portal.clean_environment())
    environment = isolated_environment(home)
    environment.update(PATH=restricted_path(base / 'minimal PATH'),
                       ALLOY_PYTHON=sys.executable, JAVA_HOME=str(compiler.parent.parent),
                       ACGN_ROOT=str(base / 'absent original ACGN'),
                       PYTHONPATH=str(base / 'absent ambient Python modules'),
                       PYTHONHOME=str(base / 'absent ambient Python home'),
                       CLASSPATH=str(base / 'absent external Java classes'),
                       JAVA_TOOL_OPTIONS='-XX:DefinitelyNotAnAlloyOption',
                       _JAVA_OPTIONS='-XX:DefinitelyNotAnAlloyOption',
                       JDK_JAVA_OPTIONS='--definitely-not-an-alloy-option',
                       JDK_JAVAC_OPTIONS='--definitely-not-an-alloy-option')
    return environment


class JdkResolutionTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='local-jdk-')
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name) / 'JDK home with spaces'
        (self.home / 'bin').mkdir(parents=True)
        for name in ('java', 'javac'):
            executable = self.home / 'bin' / name
            executable.write_text('#!/bin/sh\nexit 0\n', encoding='utf-8')
            executable.chmod(0o755)
        self.runtime = self.home / 'bin/java'
        self.compiler = self.home / 'bin/javac'
        self.environment = {'PATH': '/isolated/path', 'PRESERVED': 'value',
                            **{name: 'POISONED_JAVA_HOOK' for name in JAVA_HOOKS}}

    def versions(self, runtime=17, compiler=17):
        return [subprocess.CompletedProcess([], 0, f'openjdk {runtime}.0.1\n', ''),
                subprocess.CompletedProcess([], 0, '', f'javac {compiler}.0.1\n')]

    def test_environment_hooks_are_removed_without_mutating_input(self):
        before = dict(self.environment)
        self.assertEqual(local_portal.clean_environment(self.environment),
                         {'PATH': '/isolated/path', 'PRESERVED': 'value'})
        self.assertEqual(self.environment, before)
        with patch.dict(os.environ, self.environment, clear=True):
            self.assertEqual(local_portal.clean_environment(),
                             {'PATH': '/isolated/path', 'PRESERVED': 'value'})

    def test_explicit_home_wins_and_both_version_probes_have_clean_environment(self):
        self.environment['JAVA_HOME'] = '/missing/ambient/jdk'
        with patch.object(local_portal.subprocess, 'run', side_effect=self.versions()) as run:
            self.assertEqual(local_portal.resolve_jdk(self.home, environ=self.environment),
                             (self.runtime, self.compiler))
        self.assertEqual([call.args[0] for call in run.call_args_list],
                         [[str(self.runtime), '--version'], [str(self.compiler), '-version']])
        for call in run.call_args_list:
            self.assertEqual(call.kwargs['timeout'], 10)
            self.assertEqual(call.kwargs['env']['PRESERVED'], 'value')
            self.assertTrue(set(JAVA_HOOKS).isdisjoint(call.kwargs['env']))

    def test_explicit_java_overrides_ambient_home_and_selects_sibling_compiler(self):
        self.environment['JAVA_HOME'] = '/missing/ambient/jdk'
        with patch.object(local_portal.shutil, 'which', return_value=str(self.runtime)), \
                patch.object(local_portal.subprocess, 'run', side_effect=self.versions()):
            self.assertEqual(local_portal.resolve_jdk(java='chosen-java', environ=self.environment),
                             (self.runtime, self.compiler))

    def test_mocked_macos_java_home_success_uses_discovered_jdk(self):
        discovery = subprocess.CompletedProcess([], 0, str(self.home) + '\n', '')
        with patch.object(local_portal.platform, 'system', return_value='Darwin'), \
                patch.object(local_portal.shutil, 'which') as which, \
                patch.object(local_portal.subprocess, 'run',
                             side_effect=[discovery, *self.versions()]) as run:
            self.assertEqual(local_portal.resolve_jdk(environ=self.environment),
                             (self.runtime, self.compiler))
        which.assert_not_called()
        self.assertEqual(run.call_args_list[0].args[0], ['/usr/libexec/java_home', '-v', '17+'])
        self.assertTrue(set(JAVA_HOOKS).isdisjoint(run.call_args_list[0].kwargs['env']))

    def test_mocked_macos_failed_or_timed_out_java_home_falls_back_to_path(self):
        failures = [subprocess.CompletedProcess([], 1, '', 'No installed JDK'),
                    OSError('not installed'),
                    subprocess.TimeoutExpired('/usr/libexec/java_home', 10)]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(local_portal.platform, 'system', return_value='Darwin'), \
                    patch.object(local_portal.shutil, 'which', return_value=str(self.compiler)), \
                    patch.object(local_portal.subprocess, 'run',
                                 side_effect=[failure, *self.versions()]):
                self.assertEqual(local_portal.resolve_jdk(environ=self.environment),
                                 (self.runtime, self.compiler))

    def test_mocked_macos_apple_path_stub_is_rejected_without_executing_it(self):
        discovery = subprocess.CompletedProcess([], 1, '', 'No installed JDK')
        with patch.object(local_portal.platform, 'system', return_value='Darwin'), \
                patch.object(local_portal.shutil, 'which', return_value='/usr/bin/javac'), \
                patch.object(Path, 'resolve', return_value=Path('/usr/bin/javac')), \
                patch.object(local_portal.subprocess, 'run', return_value=discovery) as run:
            with self.assertRaisesRegex(local_portal.SetupError, 'No JDK was found'):
                local_portal.resolve_jdk(environ=self.environment)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0][0], '/usr/libexec/java_home')

    def test_mocked_macos_path_search_continues_after_apple_stub(self):
        discovery = subprocess.CompletedProcess([], 1, '', 'No registered JDK')
        environment = dict(self.environment, PATH='/usr/bin' + os.pathsep + str(self.home / 'bin'))
        original_resolve = Path.resolve

        def resolve(path, *args, **kwargs):
            # On this Linux host /usr/bin/javac is a real JDK symlink. Model
            # only Apple's stub path; preserve real resolution for our fixture.
            return path if path == Path('/usr/bin/javac') else original_resolve(path, *args, **kwargs)

        with patch.object(local_portal.platform, 'system', return_value='Darwin'), \
                patch.object(local_portal.shutil, 'which',
                             side_effect=['/usr/bin/javac', str(self.compiler)]) as which, \
                patch.object(Path, 'resolve', autospec=True, side_effect=resolve), \
                patch.object(local_portal.subprocess, 'run',
                             side_effect=[discovery, *self.versions()]) as run:
            self.assertEqual(local_portal.resolve_jdk(environ=environment),
                             (self.runtime, self.compiler))
        self.assertEqual([call.kwargs['path'] for call in which.call_args_list],
                         ['/usr/bin', str(self.home / 'bin')])
        self.assertEqual([call.args[0][0] for call in run.call_args_list],
                         ['/usr/libexec/java_home', str(self.runtime), str(self.compiler)])

    def test_missing_jdk_and_explicit_java_produce_actionable_diagnostics(self):
        with patch.object(local_portal.platform, 'system', return_value='Linux'), \
                patch.object(local_portal.shutil, 'which', return_value=None):
            with self.assertRaisesRegex(local_portal.SetupError, 'No JDK was found.*JDK 17'):
                local_portal.resolve_jdk(environ=self.environment)
            with self.assertRaisesRegex(local_portal.SetupError, '--java executable was not found'):
                local_portal.resolve_jdk(java='absent-java', environ=self.environment)

    def test_missing_or_nonexecutable_compiler_rejects_jre_without_running_java(self):
        for mode in ('missing', 'nonexecutable'):
            with self.subTest(mode=mode):
                if mode == 'missing':
                    self.compiler.unlink()
                else:
                    self.compiler.write_text('not executable', encoding='utf-8')
                    self.compiler.chmod(0o600)
                with patch.object(local_portal.subprocess, 'run') as run:
                    with self.assertRaisesRegex(local_portal.SetupError, 'JRE alone is insufficient'):
                        local_portal.resolve_jdk(self.home, environ=self.environment)
                run.assert_not_called()

    def test_old_runtime_or_compiler_is_rejected(self):
        for runtime, compiler, rejected in ((11, 17, 'runtime'), (17, 11, 'compiler'),
                                             ('1.8', 17, 'runtime')):
            with self.subTest(runtime=runtime, compiler=compiler), \
                    patch.object(local_portal.subprocess, 'run',
                                 side_effect=self.versions(runtime, compiler)):
                with self.assertRaisesRegex(local_portal.SetupError, f'JDK 17.*{rejected}'):
                    local_portal.resolve_jdk(self.home, environ=self.environment)

    def test_mismatched_runtime_and_compiler_are_rejected(self):
        with patch.object(local_portal.subprocess, 'run', side_effect=self.versions(17, 21)):
            with self.assertRaisesRegex(local_portal.SetupError, 'different major versions'):
                local_portal.resolve_jdk(self.home, environ=self.environment)

    def test_unusable_and_timed_out_java_fail_without_echoing_private_output(self):
        failures = [subprocess.TimeoutExpired('java', 10, output='PRIVATE_PROBE_OUTPUT'),
                    OSError('PRIVATE_PROBE_OUTPUT'),
                    subprocess.CompletedProcess([], 1, 'PRIVATE_PROBE_OUTPUT', ''),
                    subprocess.CompletedProcess([], 0, 'PRIVATE_PROBE_OUTPUT', '')]
        for failure in failures:
            with self.subTest(failure=type(failure).__name__), \
                    patch.object(local_portal.subprocess, 'run', side_effect=[failure]):
                with self.assertRaises(local_portal.SetupError) as caught:
                    local_portal.resolve_jdk(self.home, environ=self.environment)
                self.assertIn('JDK', str(caught.exception))
                self.assertNotIn('PRIVATE_PROBE_OUTPUT', str(caught.exception))


class SetupFailureTests(unittest.TestCase):
    def test_compilation_failure_and_timeout_are_sanitized_and_bounded(self):
        failures = [subprocess.CompletedProcess([], 1, b'', b'PRIVATE_COMPILER_OUTPUT'),
                    subprocess.TimeoutExpired('javac', 180, stderr=b'PRIVATE_COMPILER_OUTPUT'),
                    OSError('PRIVATE_COMPILER_OUTPUT')]
        with tempfile.TemporaryDirectory() as directory:
            for failure in failures:
                with self.subTest(failure=type(failure).__name__), \
                        patch.object(local_portal, 'resolve_jdk',
                                     return_value=(Path('/jdk/bin/java'), Path('/jdk/bin/javac'))), \
                        patch.object(local_portal, 'check_runtime', return_value={'status': 'PASS'}), \
                        patch.object(local_portal, 'prepare',
                                     return_value={'exercises': 1, 'action': 'validated-existing'}), \
                        patch.object(local_portal.subprocess, 'run', side_effect=[failure]) as run, \
                        patch.dict(os.environ, {name: 'POISON' for name in JAVA_HOOKS}), \
                        contextlib.redirect_stdout(io.StringIO()):
                    with self.assertRaises(local_portal.SetupError) as caught:
                        local_portal.setup(directory)
                    self.assertNotIn('PRIVATE_COMPILER_OUTPUT', str(caught.exception))
                    self.assertIn('Java compilation', str(caught.exception))
                    self.assertTrue(set(JAVA_HOOKS).isdisjoint(run.call_args.kwargs['env']))
                    self.assertEqual(run.call_args.kwargs['timeout'], 180)
                    self.assertIn(str(Path(directory).resolve() / 'engine/src/live/EngineWorker.java'),
                                  run.call_args.args[0])


class RuntimeOptionTests(unittest.TestCase):
    def invoke(self, arguments):
        with patch.object(local_portal, 'setup', return_value=Path('/jdk/bin/java')) as setup, \
                patch.object(local_portal.os, 'execve') as execute:
            local_portal.main(['run', *arguments])
            setup.assert_called_once()
            return execute.call_args.args[1]

    def test_persistent_default_and_disabled_control_listener_reach_server(self):
        argv = self.invoke([])
        self.assertEqual(argv[argv.index('--engine-mode') + 1], 'persistent')
        self.assertEqual(argv[argv.index('--control-port') + 1], '0')
        self.assertEqual(argv[argv.index('--resource-profile') + 1], 'constrained')
        self.assertNotIn('--workers', argv)

    def test_rollback_and_separate_control_listener_are_forwarded(self):
        argv = self.invoke(['--engine-mode', 'oneshot', '--control-port', '9091', '--port', '9090', '--workers', '4'])
        self.assertEqual(argv[argv.index('--engine-mode') + 1], 'oneshot')
        self.assertEqual(argv[argv.index('--control-port') + 1], '9091')
        self.assertEqual(argv[argv.index('--port') + 1], '9090')
        self.assertEqual(argv[argv.index('--workers') + 1], '4')

    def test_invalid_or_colliding_control_ports_are_rejected_before_setup(self):
        for arguments in (['--control-port', '-1'], ['--control-port', '65536'],
                          ['--control-port', '8080'], ['--engine-mode', 'unknown'],
                          ['--resource-profile', 'unknown'], ['--startup-timeout', 'nan'],
                          ['--startup-timeout', '31'], ['--timeout', 'nan']):
            with self.subTest(arguments=arguments), patch.object(local_portal, 'setup') as setup, \
                    contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as caught:
                    local_portal.main(['run', *arguments])
                self.assertEqual(caught.exception.code, 2)
                setup.assert_not_called()


class ShellEntrypointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='local-shell-')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.environment = isolated_environment(self.base)
        self.environment['PATH'] = restricted_path(self.base / 'shell utilities')

    def invoke(self, entrypoint, *arguments):
        return subprocess.run([str(ROOT / 'scripts' / entrypoint), *arguments],
                              cwd=self.base, env=self.environment, capture_output=True,
                              text=True, encoding='utf-8', timeout=10, check=False)

    def test_help_needs_neither_jdk_nor_private_data(self):
        self.environment.update(ALLOY_PYTHON=sys.executable, JAVA_HOME='/missing/jdk',
                                ACGN_ROOT='/missing/private/corpus')
        for entrypoint in ('local.sh', 'setup.sh', 'run.sh'):
            with self.subTest(entrypoint=entrypoint):
                result = self.invoke(entrypoint, '--help')
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('--from-bundle', result.stdout)
                self.assertNotIn('Checking bundled', result.stdout)
                self.assertEqual(result.stderr, '')

    def test_missing_or_old_explicit_python_reports_requirement_without_fallback(self):
        old_python = self.base / 'old Python with spaces'
        old_python.write_text('#!/bin/sh\nexit 1\n', encoding='utf-8')
        old_python.chmod(0o755)
        # A usable Python is available, but an explicit invalid selection must
        # not silently launch a different interpreter.
        (Path(self.environment['PATH']) / 'python3').symlink_to(sys.executable)
        for selection in (self.base / 'missing Python', old_python):
            with self.subTest(selection=selection.name):
                self.environment['ALLOY_PYTHON'] = str(selection)
                result = self.invoke('setup.sh', '--help')
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('Python 3.10+ is required', result.stderr)
                self.assertIn('ALLOY_PYTHON', result.stderr)
                self.assertIn('docs/local-setup.md', result.stderr)
                self.assertEqual(result.stdout, '')

    def test_missing_automatic_python_reports_requirement(self):
        result = self.invoke('setup.sh', '--help')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Python 3.10+ is required', result.stderr)


class RelocatedLocalSetupTests(unittest.TestCase):
    def assert_served_portal(self, root, working_directory, environment, budget, log_path,
                             *, exercises, exercise_id, body, pool_size):
        with log_path.open('w+', encoding='utf-8') as log:
            process = subprocess.Popen(
                [str(root / 'scripts/run.sh'), '--port', '0'],
                cwd=working_directory, env=environment, stdout=log, stderr=subprocess.STDOUT,
                start_new_session=True)
            try:
                startup_deadline = time.monotonic() + budget(35)
                address = None
                while time.monotonic() < startup_deadline:
                    log.seek(0)
                    output = log.read()
                    match = re.search(r'Alloy practice: (http://127\.0\.0\.1:\d+)', output)
                    if match:
                        address = match.group(1)
                        break
                    if process.poll() is not None:
                        self.fail('Local server exited before startup: ' + output)
                    time.sleep(0.05)
                self.assertIsNotNone(address, 'Local server did not start: ' + output)
                self.assertIn(f'Private exercises: {exercises} (validated-existing)', output)

                def request(path, body=None):
                    data = None if body is None else json.dumps(body).encode()
                    request = Request(address + path, data=data,
                                      headers={'Content-Type': 'application/json'})
                    try:
                        response = urlopen(request, timeout=budget(15))
                    except HTTPError as error:
                        response = error
                    with response:
                        return response.status, response.read()

                status, raw = request('/api/health')
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(raw)['exercises'], exercises)
                self.assertEqual(request('/')[0], 200)
                status, raw = request('/api/exercises')
                self.assertEqual(status, 200)
                summaries = json.loads(raw)['exercises']
                self.assertEqual(len(summaries), exercises)
                self.assertEqual(len({item['id'] for item in summaries}), exercises)
                for item in summaries:
                    self.assertNotIn('oracleBody', item)
                    self.assertNotIn('originalSource', item)
                status, raw = request('/api/exercises/' + exercise_id)
                self.assertEqual(status, 200)
                public = json.loads(raw)
                self.assertEqual(public['id'], exercise_id)
                self.assertNotIn('oracleBody', public)
                self.assertNotIn('originalSource', public)
                payload = {'exerciseId': exercise_id, 'body': body, 'revision': 7}
                status, raw = request('/api/feedback', payload)
                self.assertEqual(status, 200)
                feedback = json.loads(raw)
                self.assertEqual(feedback['status'], 'ok', feedback)
                self.assertEqual(feedback['revision'], 7)
                self.assertEqual(sum(item['cost'] for item in feedback['operations']),
                                 feedback['distance'])
                self.assertTrue(feedback['comparison']['complete'])
                self.assertEqual(feedback['comparison']['poolSize'], pool_size)
                self.assertNotIn('oracleBody', feedback)
                status, raw = request('/api/explain', payload)
                self.assertEqual(status, 200)
                self.assertEqual(json.loads(raw)['status'], 'disabled')
                for path in ('/exercises/catalogue.json', '/exercises/correct-pools.json',
                             '/exercises/exercises.sqlite3', '/exercises/exercises.sqlite3-wal',
                             '/exercises/exercises.sqlite3-shm', '/sql/compiled-queries.json',
                             '/exercise_sql.py', '/scripts/manage_exercises.py',
                             '/admin.local.json', '/admin/admin.local.json', '/admin/upload.als',
                             '/admin_auth.py', '/admin_upload.py', '/admin_luna.py', '/admin_service.py',
                             '/scripts/configure_admin.py',
                             '/secrets/openai.key', '/openai.local.json', '/.env',
                             '/vendor/acgn/lib/alloy.jar', '/server.py',
                             '/%2e%2e/exercises/catalogue.json'):
                    with self.subTest(private_path=path):
                        self.assertEqual(request(path)[0], 404)
                process.send_signal(signal.SIGINT)
                self.assertEqual(process.wait(timeout=budget(10)), 0)
                with self.assertRaises(ProcessLookupError,
                                       msg='A child process survived launcher SIGINT.'):
                    os.killpg(process.pid, 0)
            finally:
                # Also remove child processes when an assertion or startup
                # fails, so a failed witness cannot leave a server running.
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                process.wait(timeout=5)

    def test_real_git_clone_sets_up_bundled_181_exercises_without_data_flags(self):
        deadline = time.monotonic() + 85

        def budget(maximum):
            remaining = deadline - time.monotonic()
            self.assertGreater(remaining, 0, 'Fresh Git clone exceeded its 85-second budget.')
            return min(maximum, remaining)

        with tempfile.TemporaryDirectory(prefix='alloy-bundled-clone-') as directory:
            base = Path(directory)
            source = base / 'temporary source repository'
            copy_runtime(source)
            for name in ('.gitignore', 'exercises/exercises.sqlite3'):
                target = source / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(ROOT / name, target)
            # These are synthetic traps, never copied from the user's checkout.
            credentials = ('.env', 'openai.local.json', 'admin.local.json', '.admin-config-fixture.tmp', 'secrets/openai.key')
            for name in credentials:
                target = source / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('SYNTHETIC_CREDENTIAL_MUST_NOT_CLONE\n', encoding='utf-8')
            environment = runtime_environment(base)
            # Exercise the normal missing-key path, without forcing Luna off.
            environment.pop('OPENAI_DISABLED')
            git_environment = isolated_environment(Path(environment['HOME']))
            git_environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull)
            git_executable = shutil.which('git')
            self.assertIsNotNone(git_executable, 'The source-distribution witness requires Git.')

            def git(*arguments, cwd=source):
                result = subprocess.run([git_executable, *arguments], cwd=cwd, env=git_environment,
                                        capture_output=True, text=True, encoding='utf-8',
                                        timeout=budget(10), check=False)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                return result.stdout

            git('init', '--quiet', '--template=')
            # A normal add must admit the bundled data and reject local keys.
            git('add', '--', '.')
            git('-c', 'user.name=Source distribution witness',
                '-c', 'user.email=source-witness@example.invalid',
                '-c', 'commit.gpgSign=false', 'commit', '--quiet', '-m', 'Runtime source fixture')
            checkout = base / 'fresh Git clone with spaces'
            git('clone', '--quiet', '--no-hardlinks', str(source), str(checkout), cwd=base)
            tracked = set(git('ls-files', cwd=checkout).splitlines())
            self.assertIn('exercises/exercises.sqlite3', tracked)
            self.assertNotIn('exercises/catalogue.json', tracked)
            self.assertNotIn('exercises/correct-pools.json', tracked)
            self.assertTrue(set(credentials).isdisjoint(tracked))
            for name in credentials:
                self.assertFalse((checkout / name).exists(), 'A local credential entered the clone.')
            shutil.rmtree(source)
            self.assertFalse((checkout / 'build').exists())
            self.assertFalse((base / 'ACGN').exists())
            self.assertFalse(Path(environment['ACGN_ROOT']).exists())
            self.assertFalse(list(base.rglob('*.zip')))
            self.assertIsNone(shutil.which('node', path=environment['PATH']))
            original_database = (checkout / 'exercises/exercises.sqlite3').read_bytes()
            from exercise_store import load_store
            snapshot = load_store(checkout)
            catalogue, pools = snapshot.catalogue, snapshot.pools_document
            self.assertEqual((snapshot.exercise_count, len(snapshot.correct_pools),
                              snapshot.candidate_count), (181, 181, 7731))
            unrelated = base / 'unrelated working directory'
            unrelated.mkdir()
            completed = subprocess.run([str(checkout / 'scripts/setup.sh')],
                                       cwd=unrelated, env=environment, capture_output=True,
                                       text=True, encoding='utf-8', timeout=budget(45), check=False)
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertIn('Private exercises: 181 (validated-existing)', completed.stdout)
            self.assertIn('Engine ready: 378 checks passed', completed.stdout)
            pool = next(pool for pool in pools['pools'] if pool['exerciseId'] == 'cv_v1-inv4')
            self.assert_served_portal(
                checkout, unrelated, environment, budget, base / 'server output.log',
                exercises=181, exercise_id=pool['exerciseId'],
                body=pool['candidates'][0]['body'], pool_size=len(pool['candidates']))
            self.assertEqual(original_database, (checkout / 'exercises/exercises.sqlite3').read_bytes())
            self.assertFalse((checkout / 'exercises/catalogue.json').exists())
            self.assertFalse((checkout / 'exercises/correct-pools.json').exists())

    def write_private_bundle(self, base):
        # Adapted from test_private_data_import: preserve and validate actual
        # importer witnesses instead of inventing private JSON records.
        source = base / 'temporary synthetic corpus'
        for classification, body in (('under', 'some Node'),
                                      ('correct', 'all n: Node | n not in n.adj')):
            folder = source / 'classified-data/graphs' / classification
            folder.mkdir(parents=True)
            (folder / 'fixture_inv1.als').write_text(MODEL.replace('STUDENT', body), encoding='utf-8')
        catalogue = prepare_private_data.build_catalogue(source)
        pools = prepare_private_data.build_document(catalogue, source)
        entries = {
            'backend/exercises/catalogue.json': (json.dumps(catalogue) + '\n').encode(),
            'backend/exercises/correct-pools.json': prepare_private_data.canonical(pools),
            'backend/secrets/openai.key': b'SYNTHETIC_BUNDLE_CREDENTIAL_MUST_NOT_COPY',
            'backend/openai.local.json': b'{"api_key":"SYNTHETIC_BUNDLE_CREDENTIAL_MUST_NOT_COPY"}',
            'backend/server.py': b'ARCHIVE_PROGRAM_MUST_NOT_COPY',
        }
        prepare_private_data.validate_pair(entries['backend/exercises/catalogue.json'],
                                           entries['backend/exercises/correct-pools.json'])
        manifest = {'schemaVersion': 1, 'application': 'Alloy Studio',
                    'distribution': 'IIS 10', 'privateArchive': True,
                    'exerciseCount': 1, 'knownCorrectPoolCount': 1,
                    'files': [{'path': name, 'bytes': len(data),
                               'sha256': hashlib.sha256(data).hexdigest()}
                              for name, data in entries.items()]}
        bundle = base / 'private input bundle.zip'
        with zipfile.ZipFile(bundle, 'w') as archive:
            archive.writestr('manifest.json', json.dumps(manifest))
            for name, data in entries.items():
                archive.writestr(name, data)
        shutil.rmtree(source)
        return bundle

    def test_real_relocated_setup_run_feedback_privacy_and_sigint_without_node_or_corpus(self):
        deadline = time.monotonic() + 85

        def budget(maximum):
            remaining = deadline - time.monotonic()
            self.assertGreater(remaining, 0, 'Local setup witness exceeded its 85-second budget.')
            return min(maximum, remaining)

        with tempfile.TemporaryDirectory(prefix='alloy-local-portability-') as directory:
            base = Path(directory)
            checkout = base / 'fresh source directory with spaces'
            copy_runtime(checkout)
            bundle = self.write_private_bundle(base)
            unrelated = base / 'unrelated working directory'
            unrelated.mkdir()
            environment = runtime_environment(base)
            self.assertIsNone(shutil.which('node', path=environment['PATH']))
            self.assertIsNone(shutil.which('javac', path=environment['PATH']))
            self.assertFalse((checkout / 'build').exists())
            self.assertFalse((checkout / 'exercises').exists())
            self.assertFalse((base / 'ACGN').exists())
            self.assertFalse((base / 'temporary synthetic corpus').exists())
            relative_bundle = os.path.relpath(bundle, unrelated)
            completed = subprocess.run(
                [str(checkout / 'scripts/setup.sh'), '--from-bundle', relative_bundle],
                cwd=unrelated, env=environment, capture_output=True,
                text=True, encoding='utf-8', timeout=budget(45), check=False)
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertIn('Private exercises: 1 (restored-bundle)', completed.stdout)
            self.assertIn('Engine ready: 378 checks passed', completed.stdout)
            self.assertTrue((checkout / 'build/engine/classes/live/LiveFeedback.class').is_file())
            original_pair = {name: (checkout / 'exercises' / name).read_bytes()
                             for name in prepare_private_data.PRIVATE_NAMES}
            prepare_private_data.validate_pair(original_pair['catalogue.json'],
                                               original_pair['correct-pools.json'])
            for name in original_pair:
                self.assertEqual((checkout / 'exercises' / name).stat().st_mode & 0o777, 0o600)
            # Run again after physically moving the prepared checkout. Neither
            # the archive nor the source corpus is available to the run path.
            relocated = base / 'moved installation with spaces'
            checkout.rename(relocated)
            bundle.unlink()
            self.assert_served_portal(
                relocated, unrelated, environment, budget, base / 'server output.log',
                exercises=1, exercise_id='graphs-inv1', body='some Node', pool_size=2)
            self.assertEqual(original_pair,
                             {name: (relocated / 'exercises' / name).read_bytes()
                              for name in original_pair})
            self.assertFalse(list(relocated.rglob('*.key')))
            self.assertFalse(list(relocated.rglob('openai.local.json')))
            self.assertFalse((relocated / 'secrets').exists())
            self.assertFalse((relocated / '.env').exists())
            self.assertEqual((relocated / 'server.py').read_bytes(), (ROOT / 'server.py').read_bytes())
            self.assertNotIn('SYNTHETIC_BUNDLE_CREDENTIAL_MUST_NOT_COPY',
                             (base / 'server output.log').read_text(encoding='utf-8'))


if __name__ == '__main__':
    unittest.main()
