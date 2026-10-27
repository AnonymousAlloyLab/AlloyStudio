"""Dashboard disclosure, revision binding, release gating, and real browser witnesses."""
import errno
import json
import os
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from scripts import ci_check, ci_dashboard, release_preflight, verify_closure

ROOT = Path(__file__).resolve().parents[1]
SHA = 'a' * 40
OTHER = 'b' * 40


class DashboardTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-dashboard-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.write('package.json', {'version': '0.0.1-alpha'})

    def write(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value))
        return path

    def snapshot(self, dirty=False):
        with patch.object(ci_dashboard, 'revision', return_value={'sha': SHA, 'dirty': dirty}):
            return ci_dashboard.build_snapshot(self.root)

    def test_missing_inputs_never_become_success(self):
        result = self.snapshot()
        self.assertEqual(result['closure'], {'status': 'NOT_AVAILABLE', 'current': False})
        self.assertTrue(all(item['status'] == 'NOT_RUN' and not item['current'] for item in result['checks']))
        self.assertEqual(result['version'], '0.0.1-alpha')

    def test_reports_are_allowlisted_and_bound_to_a_clean_revision(self):
        self.write('build/ci/python.json', {'check': 'python', 'status': 'PASS', 'count': 123,
            'revision': SHA, 'dirty': False, 'oracleBody': 'PRIVATE_SENTINEL', 'token': 'PRIVATE_SENTINEL'})
        result = self.snapshot()
        check = next(item for item in result['checks'] if item['name'] == 'python')
        self.assertTrue(check['current'])
        self.assertEqual(check['count'], 123)
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(result))
        self.assertFalse(next(item for item in self.snapshot(True)['checks'] if item['name'] == 'python')['current'])
        self.write('build/ci/python.json', {'check': 'python', 'status': 'PASS', 'count': True,
            'revision': OTHER, 'dirty': False})
        check = next(item for item in self.snapshot()['checks'] if item['name'] == 'python')
        self.assertFalse(check['current'])
        self.assertIsNone(check['count'])

    def test_malformed_and_symlinked_reports_are_unavailable(self):
        report = self.write('build/ci/build.json', {'check': 'build', 'status': 'PRIVATE_SENTINEL',
                                                  'revision': 'PRIVATE_SENTINEL', 'count': -1})
        report.with_name('runtime.json').write_text('["PRIVATE_SENTINEL"]')
        snapshot = self.snapshot()
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(snapshot))
        self.assertTrue(all(item['status'] == 'NOT_RUN' for item in snapshot['checks']))
        for value in ([], {}, 123, True):
            self.write('build/ci/build.json', {'check': 'build', 'status': value})
            self.assertEqual(self.snapshot()['checks'][0]['status'], 'NOT_RUN')
            self.write('closure/runs/bad/reports/closure-report.json', {'status': value})
            self.assertEqual(self.snapshot()['closure']['status'], 'NOT_AVAILABLE')
        report.unlink()
        private = self.write('private.json', {'check': 'build', 'status': 'PASS', 'revision': SHA, 'dirty': False})
        report.symlink_to(private)
        self.assertEqual(self.snapshot()['checks'][0]['status'], 'NOT_RUN')

    def test_historical_closure_omits_paths_logs_and_arbitrary_failure_text(self):
        identity = 'portal-20260928T120000Z-aabbccdd'
        self.write(f'closure/runs/{identity}/reports/closure-report.json', {
            'status': 'VERIFIED', 'closure_id': identity, 'input_root_hash': 'c' * 64,
            'claims': {'passed': 10, 'total': 10, 'statuses': {'PRIVATE_SENTINEL': 'PASS'}},
            'builds': {'passed': 2, 'required': 2, 'records': ['PRIVATE_SENTINEL']},
            'blocking_reasons': ['PRIVATE_SENTINEL'], 'closure_boundary': 'PRIVATE_SENTINEL'})
        closure = self.snapshot()['closure']
        self.assertEqual(closure['status'], 'VERIFIED')
        self.assertFalse(closure['current'])
        self.assertEqual(closure['claimsPassed'], 10)
        self.assertNotIn('PRIVATE_SENTINEL', json.dumps(closure))

    def test_generator_exports_only_four_dashboard_files(self):
        for name in ('index.html', 'app.js', 'styles.css'):
            path = self.root / 'web/dashboard' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        self.write('secrets/openai.local.json', {'key': 'PRIVATE_SENTINEL'})
        self.write('web/dashboard/extra.json', {'oracle': 'PRIVATE_SENTINEL'})
        output = self.root / 'output'
        ci_dashboard.write_dashboard(self.root, output)
        self.assertEqual(sorted(path.name for path in output.iterdir()), ['app.js', 'data.json', 'index.html', 'styles.css'])
        self.assertNotIn('PRIVATE_SENTINEL', (output / 'data.json').read_text())

    def test_ci_wrapper_disables_openai_and_never_prints_process_output(self):
        from contextlib import redirect_stdout
        from io import StringIO
        stream = StringIO()
        with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
             patch.object(ci_check.subprocess, 'run', return_value=subprocess.CompletedProcess([], 1, 'PRIVATE_SENTINEL', 'PRIVATE_SENTINEL')) as runner, \
             redirect_stdout(stream):
            self.assertEqual(ci_check.execute('build', self.root), 1)
        self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue())
        self.assertEqual(runner.call_args.kwargs['env']['OPENAI_DISABLED'], '1')
        self.assertEqual(json.loads((self.root / 'build/ci/build.json').read_text())['status'], 'FAIL')

    def test_build_failures_publish_only_fixed_diagnostic_codes(self):
        from contextlib import redirect_stdout
        from io import StringIO
        cases = (
            ('Package refused: Exercise database validation or consistent backup failed; '
             'PRIVATE_SENTINEL', 'BUILD_DATABASE_BACKUP_FAILED'),
            ('Package refused: PRIVATE_SENTINEL', 'BUILD_PACKAGE_REFUSED'),
            ('Private data preparation failed [INVALID_INPUT]: PRIVATE_SENTINEL', 'BUILD_PRIVATE_DATA_FAILED'),
            ('PRIVATE_SENTINEL', 'BUILD_FAILED'),
        )
        for message, expected in cases:
            for channel in ('stdout', 'stderr'):
                with self.subTest(expected=expected, channel=channel):
                    stream = StringIO()
                    result = subprocess.CompletedProcess([], 1,
                        message if channel == 'stdout' else 'PRIVATE_SENTINEL',
                        message if channel == 'stderr' else 'PRIVATE_SENTINEL')
                    with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
                         patch.object(ci_check.subprocess, 'run', return_value=result), \
                         redirect_stdout(stream):
                        self.assertEqual(ci_check.execute('build', self.root), 1)
                    self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue())
                    report = json.loads((self.root / 'build/ci/build.json').read_text())
                    self.assertNotIn('PRIVATE_SENTINEL', json.dumps(report))
                    self.assertEqual(report['status'], 'FAIL')
                    self.assertEqual(report['failure_code'], expected)

    def register_python_fixture(self):
        path = self.root / 'tests/test_registered.py'
        path.parent.mkdir(parents=True, exist_ok=True)
        # Registry discovery must parse source without executing it.
        path.write_text('raise RuntimeError("PRIVATE_SENTINEL")\n'
                        'class Registered:\n'
                        '    def test_failure(self): pass\n'
                        '    def test_success(self): pass\n'
                        '    def test_unresolved(self): pass\n')
        return 'test_registered.Registered.'

    def test_failed_python_child_reports_only_exact_source_registered_test_names(self):
        from contextlib import redirect_stdout
        from io import StringIO
        prefix = self.register_python_fixture()
        raw = {'successful': False, 'tests_run': 3, 'outcomes': {
            prefix + 'test_failure': 'BLOCK', prefix + 'test_success': 'PASS',
            prefix + 'test_unresolved': 'UNRESOLVED', 'PRIVATE_SENTINEL': 'BLOCK',
            prefix + 'test_failure (body=PRIVATE_SENTINEL)': 'BLOCK',
            '_FailedTest.PRIVATE_SENTINEL': 'BLOCK'}, 'error': 'PRIVATE_SENTINEL',
            'failureDetails': {prefix + 'test_failure': {'line': 3, 'kind': 'assertion',
                'message': 'PRIVATE_SENTINEL', 'path': 'PRIVATE_SENTINEL'},
                prefix + 'test_success': {'line': 4, 'kind': 'error'},
                'PRIVATE_SENTINEL': {'line': 1, 'kind': 'error'}}}
        child = self.root / 'scripts/verify_closure.py'
        child.parent.mkdir()
        child.write_text('import json, sys\nfrom pathlib import Path\n'
                         'assert not Path(sys.argv[2]).exists()\n'
                         'print("PRIVATE_SENTINEL")\n'
                         'print("PRIVATE_SENTINEL", file=sys.stderr)\n'
                         f'Path(sys.argv[2]).write_text(json.dumps({raw!r}))\n'
                         'raise SystemExit(1)\n')
        stale = self.write('build/ci/unittest-private.json', {'successful': True, 'tests_run': 999})
        stream = StringIO()
        with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), redirect_stdout(stream):
            self.assertEqual(ci_check.execute('python', self.root), 1)
        report = json.loads((self.root / 'build/ci/python.json').read_text())
        self.assertEqual(report['status'], 'FAIL')
        self.assertEqual(report['count'], 3)
        self.assertEqual(report['failedRegisteredTests'],
                         [prefix + 'test_failure', prefix + 'test_unresolved'])
        self.assertEqual(report['failureDetails'],
                         [{'test': prefix + 'test_failure', 'line': 3, 'kind': 'assertion'}])
        self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue() + json.dumps(report))
        self.assertFalse(stale.exists())

    def test_failure_locations_never_export_exception_text_or_subtest_values(self):
        from contextlib import redirect_stderr
        from io import StringIO
        path = self.root / 'tests/test_registered.py'
        path.parent.mkdir(parents=True)
        source = ('class Registered(unittest.TestCase):\n'
                  '    def test_assertion(self):\n'
                  '        self.fail("PRIVATE_SENTINEL")\n'
                  '    def test_timeout(self):\n'
                  '        raise subprocess.TimeoutExpired("PRIVATE_SENTINEL", 5)\n'
                  '    def test_error(self):\n'
                  '        raise ValueError("PRIVATE_SENTINEL")\n'
                  '    def test_subtest(self):\n'
                  '        with self.subTest(body="PRIVATE_SENTINEL"):\n'
                  '            self.fail("PRIVATE_SENTINEL")\n')
        path.write_text(source)
        namespace = {'__name__': 'test_registered', 'unittest': unittest, 'subprocess': subprocess}
        exec(compile(source, str(path), 'exec'), namespace)
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(namespace['Registered'])
        report = self.root / 'private-result.json'
        with patch.object(verify_closure, 'ROOT', self.root), \
             patch.object(unittest.defaultTestLoader, 'discover', return_value=suite), \
             patch.object(verify_closure.sys, 'path', list(verify_closure.sys.path)), \
             redirect_stderr(StringIO()):
            self.assertEqual(verify_closure.python_tests(report), 1)
        raw = json.loads(report.read_text())
        prefix = 'test_registered.Registered.'
        self.assertEqual(raw['failureDetails'], {
            prefix + 'test_assertion': {'line': 3, 'kind': 'assertion'},
            prefix + 'test_timeout': {'line': 5, 'kind': 'timeout'},
            prefix + 'test_error': {'line': 7, 'kind': 'error'},
            prefix + 'test_subtest': {'line': 10, 'kind': 'assertion'}})
        self.assertNotIn('PRIVATE_SENTINEL', report.read_text())
        self.assertNotIn(str(self.root), report.read_text())

    def test_python_failure_details_are_revalidated_against_registered_source(self):
        prefix = self.register_python_fixture()
        identifier = prefix + 'test_failure'
        sources = verify_closure.discover_test_sources(self.root)
        cases = [None, [], 'PRIVATE_SENTINEL', {'kind': 'PRIVATE_SENTINEL', 'line': 3},
                 {'kind': ['PRIVATE_SENTINEL'], 'line': 3},
                 {'kind': 'error', 'line': True}, {'kind': 'error', 'line': -1},
                 {'kind': 'error', 'line': 6}, {'kind': 'error', 'line': 'PRIVATE_SENTINEL'}]
        for detail in cases:
            with self.subTest(detail=detail):
                raw = {'tests_run': 1, 'outcomes': {identifier: 'BLOCK'},
                       'failureDetails': {identifier: detail}}
                summary = ci_check.python_outcome_summary(raw, frozenset(sources), sources)
                self.assertEqual(summary['failureDetails'], [])
                self.assertNotIn('PRIVATE_SENTINEL', json.dumps(summary))
        for line in (None, 1, 5):
            raw = {'tests_run': 1, 'outcomes': {identifier: 'BLOCK'},
                   'failureDetails': {identifier: {'line': line, 'kind': 'error'}}}
            self.assertEqual(ci_check.python_outcome_summary(raw, frozenset(sources), sources)
                             ['failureDetails'], [{'test': identifier, 'line': line, 'kind': 'error'}])

    def test_os_failure_categories_never_export_exception_identity_or_values(self):
        path = self.root / 'tests/test_registered.py'
        path.parent.mkdir(parents=True)
        source = ('class Registered:\n'
                  '    def test_failure(self):\n'
                  '        try:\n'
                  '            raise injected\n'
                  '        except Exception:\n'
                  '            return sys.exc_info()\n')
        path.write_text(source)
        identifier = 'test_registered.Registered.test_failure'
        sources = verify_closure.discover_test_sources(self.root)
        # Even custom exception names and formatting must remain private.
        def forbidden_string(self):
            raise AssertionError('Exception formatting must not be used.')
        private_error = type('PRIVATE_SENTINEL', (OSError,), {'__str__': forbidden_string})
        cases = (
            (FileNotFoundError('PRIVATE_SENTINEL'), 'file_not_found'),
            (PermissionError('PRIVATE_SENTINEL'), 'permission_denied'),
            (OSError(errno.ENOENT, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'file_not_found'),
            (OSError(errno.EACCES, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'permission_denied'),
            (OSError(errno.EPERM, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'permission_denied'),
            (OSError(errno.ENOTEMPTY, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'directory_not_empty'),
            (OSError(errno.EIO, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'io_error'),
            (OSError(errno.ENOSPC, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'no_space'),
            (private_error(errno.ENOTEMPTY, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), 'directory_not_empty'),
            (private_error('PRIVATE_SENTINEL'), None),
            (OSError(987654321, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), None),
            (private_error(True, 'PRIVATE_SENTINEL', '/PRIVATE_SENTINEL'), None),
            (OSError('PRIVATE_SENTINEL', 'PRIVATE_SENTINEL'), None),
            (ValueError('PRIVATE_SENTINEL'), None),
        )
        for index, (error, expected) in enumerate(cases):
            with self.subTest(case=index):
                namespace = {'sys': sys, 'injected': error}
                exec(compile(source, str(path), 'exec'), namespace)
                detail = verify_closure.failure_location(
                    identifier, namespace['Registered']().test_failure(), sources)
                wanted = {'line': 4, 'kind': 'error'}
                if expected is not None:
                    wanted['osError'] = expected
                self.assertEqual(detail, wanted)
                raw = {'tests_run': 1, 'outcomes': {identifier: 'BLOCK'},
                       'failureDetails': {identifier: detail}}
                summary = ci_check.python_outcome_summary(raw, frozenset(sources), sources)
                self.assertEqual(summary['failureDetails'], [{'test': identifier, **wanted}])
                self.assertNotIn('PRIVATE_SENTINEL', json.dumps(summary))
                self.assertNotIn('987654321', json.dumps(summary))
                self.assertNotIn(str(self.root), json.dumps(summary))

    def test_os_failure_projection_accepts_only_fixed_string_categories(self):
        prefix = self.register_python_fixture()
        identifier = prefix + 'test_failure'
        sources = verify_closure.discover_test_sources(self.root)
        codes = ('file_not_found', 'permission_denied', 'directory_not_empty',
                 'io_error', 'no_space')
        self.assertEqual(verify_closure.OS_ERROR_CODES, frozenset(codes))
        for value in (*codes, None, True, errno.ENOENT, [], {},
                      ['file_not_found'], {'code': 'file_not_found'}, 'PRIVATE_SENTINEL',
                      'file_not_found/PRIVATE_SENTINEL'):
            with self.subTest(value=value):
                raw = {'tests_run': 1, 'outcomes': {identifier: 'BLOCK'},
                       'failureDetails': {identifier: {'line': 3, 'kind': 'error',
                           'osError': value, 'exceptionType': 'PRIVATE_SENTINEL',
                           'errno': 987654321, 'path': '/PRIVATE_SENTINEL'}}}
                summary = ci_check.python_outcome_summary(raw, frozenset(sources), sources)
                expected = {'test': identifier, 'line': 3, 'kind': 'error'}
                if type(value) is str and value in codes:
                    expected['osError'] = value
                self.assertEqual(summary['failureDetails'], [expected])
                self.assertNotIn('PRIVATE_SENTINEL', json.dumps(summary))
                self.assertNotIn('987654321', json.dumps(summary))

    def test_python_exit_and_report_must_both_pass_and_runtime_values_never_escape(self):
        from contextlib import redirect_stdout
        from io import StringIO
        prefix = self.register_python_fixture()
        cases = [(0, True, 'PASS', 'PASS', []), (1, True, 'PASS', 'FAIL', []),
                 (0, False, 'BLOCK', 'FAIL', [prefix + 'test_failure']),
                 (0, True, 'PRIVATE_SENTINEL', 'FAIL', []),
                 (1, False, {'private': 'PRIVATE_SENTINEL'}, 'FAIL', [])]
        for exitcode, success, outcome, status, failures in cases:
            with self.subTest(exitcode=exitcode, success=success, status=status):
                def run(command, **kwargs):
                    self.write('build/ci/unittest-private.json', {'successful': success,
                        'tests_run': 1, 'outcomes': {prefix + 'test_failure': outcome}})
                    return subprocess.CompletedProcess(command, exitcode, 'PRIVATE_SENTINEL', 'PRIVATE_SENTINEL')
                stream = StringIO()
                with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
                     patch.object(ci_check.subprocess, 'run', side_effect=run), redirect_stdout(stream):
                    self.assertEqual(ci_check.execute('python', self.root), 0 if status == 'PASS' else 1)
                report = json.loads((self.root / 'build/ci/python.json').read_text())
                self.assertEqual(report['status'], status)
                self.assertEqual(report['count'], 1)
                self.assertEqual(report['failedRegisteredTests'], failures)
                self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue() + json.dumps(report))
                self.assertFalse((self.root / 'build/ci/unittest-private.json').exists())

    def test_python_private_report_is_deleted_on_malformed_report_and_timeout(self):
        from contextlib import redirect_stdout
        from io import StringIO
        self.register_python_fixture()
        for mode in ('malformed_json', 'malformed_outcomes', 'timeout'):
            with self.subTest(mode=mode):
                def run(command, **kwargs):
                    raw = self.write('build/ci/unittest-private.json', {'outcomes': ['PRIVATE_SENTINEL']})
                    if mode == 'malformed_json':
                        raw.write_text('PRIVATE_SENTINEL not JSON')
                    if mode == 'timeout':
                        raise subprocess.TimeoutExpired(command, 1800, 'PRIVATE_SENTINEL', 'PRIVATE_SENTINEL')
                    return subprocess.CompletedProcess(command, 1, 'PRIVATE_SENTINEL', 'PRIVATE_SENTINEL')
                stream = StringIO()
                with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
                     patch.object(ci_check.subprocess, 'run', side_effect=run), redirect_stdout(stream):
                    self.assertEqual(ci_check.execute('python', self.root), 1)
                report = json.loads((self.root / 'build/ci/python.json').read_text())
                self.assertEqual(report['status'], 'UNAVAILABLE')
                self.assertEqual(report['failedRegisteredTests'], [])
                self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue() + json.dumps(report))
                self.assertFalse((self.root / 'build/ci/unittest-private.json').exists())

    def test_ci_child_uses_owned_scratch_instead_of_ambient_temp_directory(self):
        from contextlib import redirect_stdout
        from io import StringIO
        import sys
        ambient = self.root / 'ambient temporary directory'
        ambient.mkdir()
        scratch = (self.root / 'build/ci/tmp').resolve()
        command = [sys.executable, '-c',
                   'import tempfile; from pathlib import Path; '
                   f'assert Path(tempfile.gettempdir()).resolve() == Path({str(scratch)!r}); '
                   'f=tempfile.TemporaryFile(); f.write(b"scratch probe"); f.close()']
        with patch.dict(os.environ, {key: str(ambient) for key in ('TMPDIR', 'TMP', 'TEMP')}), \
             patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
             patch.object(ci_check, 'build_command', return_value=command), \
             redirect_stdout(StringIO()):
            self.assertEqual(ci_check.execute('build', self.root), 0)
        self.assertEqual(list(ambient.iterdir()), [])

    def test_windows_build_uses_bash_beside_git_not_system_wsl(self):
        for layout in ('cmd', 'bin', 'mingw64/bin', 'mingw32/bin'):
            install = self.root / ('Git ' + layout.replace('/', '-'))
            git = install / layout / 'git.exe'
            git.parent.mkdir(parents=True)
            git.touch()
            bash = install / 'bin/bash.exe'
            bash.parent.mkdir(exist_ok=True)
            bash.touch()
            with patch.object(ci_check.shutil, 'which', return_value=str(git)) as which:
                command = ci_check.build_command({'PATH': 'synthetic-windows-path'}, platform_name='nt')
            self.assertEqual(command, [str(bash), './scripts/build.sh'])
            which.assert_called_once_with('git', path='synthetic-windows-path')
        with patch.object(ci_check.shutil, 'which', return_value=str(self.root / 'System32/git.exe')):
            with self.assertRaises(OSError):
                ci_check.build_command({'PATH': 'synthetic-windows-path'}, platform_name='nt')
        with patch.object(ci_check.shutil, 'which', return_value=None):
            with self.assertRaises(OSError):
                ci_check.build_command({}, platform_name='nt')
        self.assertEqual(ci_check.build_command({}, platform_name='posix'), ['bash', './scripts/build.sh'])

    def test_browser_wrapper_refreshes_explicit_fixture_before_launch(self):
        from contextlib import redirect_stdout
        from io import StringIO
        events = []
        def package(root, output):
            events.append(('package', root, output))
        def browser(command, **kwargs):
            events.append(('browser', command))
            return subprocess.CompletedProcess(command, 0, '{"status":"PASS","checks":46}', '')
        with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
                patch.object(ci_check, 'build_package', side_effect=package), \
                patch.object(ci_check.subprocess, 'run', side_effect=browser), redirect_stdout(StringIO()):
            self.assertEqual(ci_check.execute('browser', self.root), 0)
        self.assertEqual(events, [('package', self.root, self.root / 'build/iis/alloy-studio-iis.zip'),
                                  ('browser', ['node', 'tests/browser-suite.mjs'])])

    def test_browser_wrapper_does_not_use_old_fixture_after_packaging_failure(self):
        from contextlib import redirect_stdout
        from io import StringIO
        stream = StringIO()
        with patch.object(ci_check, 'revision', return_value={'sha': SHA, 'dirty': False}), \
                patch.object(ci_check, 'build_package', side_effect=OSError('PRIVATE_SENTINEL')), \
                patch.object(ci_check.subprocess, 'run') as runner, redirect_stdout(stream):
            self.assertEqual(ci_check.execute('browser', self.root), 1)
            runner.assert_not_called()
        self.assertNotIn('PRIVATE_SENTINEL', stream.getvalue())

    def test_release_tag_must_match_both_package_versions(self):
        self.write('package-lock.json', {'version': '0.0.1-alpha', 'packages': {'': {'version': '0.0.1-alpha'}}})
        self.assertTrue(release_preflight.validate(self.root, 'refs/tags/v0.0.1-alpha'))
        self.assertFalse(release_preflight.validate(self.root, 'refs/tags/v0.0.2-alpha'))
        self.write('package-lock.json', {'version': '0.0.1-alpha', 'packages': {'': {'version': '0.0.2-alpha'}}})
        self.assertFalse(release_preflight.validate(self.root, 'refs/tags/v0.0.1-alpha'))

    def test_maintenance_release_accepts_only_exact_documented_tag_alias(self):
        version='0.0.4-f1-alpha'
        self.write('package.json',{'version':version})
        self.write('package-lock.json',{'version':version,'packages':{'':{'version':version}}})
        for ref in ('refs/tags/v0.0.4-f1-alpha','refs/tags/v0.0.4.f1-alpha','refs/heads/master'):
            self.assertTrue(release_preflight.validate(self.root,ref),ref)
        for ref in ('refs/tags/v0.0.4.f2-alpha','refs/tags/v0.0.5.f1-alpha',
                    'refs/tags/v0.0.4.f01-alpha','refs/tags/v0.0.4.f0-alpha',
                    'refs/tags/v0.0.4.f1-beta','refs/tags/v0.0.4-alpha',
                    'refs/tags/0.0.4.f1-alpha','refs/tags/v0.0.4.f1-alpha/extra'):
            self.assertFalse(release_preflight.validate(self.root,ref),ref)

    def test_maintenance_alias_requires_exact_package_and_lock_identity(self):
        for version in ('0.0.4-alpha','0.0.4-f01-alpha','0.0.4-f0-alpha','0.0.4-f1-beta','0.0.4.f1-alpha'):
            self.write('package.json',{'version':version})
            self.write('package-lock.json',{'version':version,'packages':{'':{'version':version}}})
            self.assertFalse(release_preflight.validate(self.root,'refs/tags/v0.0.4.f1-alpha'),version)
        self.write('package.json',{'version':'0.0.4-f1-alpha'})
        self.write('package-lock.json',{'version':'0.0.4-f1-alpha','packages':{'':{'version':'0.0.4-f2-alpha'}}})
        self.assertFalse(release_preflight.validate(self.root,'refs/tags/v0.0.4.f1-alpha'))

    def test_workflows_pin_actions_and_publish_only_safe_summary_allowlists(self):
        allowed = {'build/ci-dashboard/index.html', 'build/ci-dashboard/app.js',
                   'build/ci-dashboard/styles.css', 'build/ci-dashboard/data.json'}
        for path in sorted((ROOT / '.github/workflows').glob('*.yml')):
            expected = {'build/native-iis/public-summary.json'} if path.name == 'iis.yml' else allowed
            workflow = path.read_text()
            self.assertNotRegex(workflow, r'permissions:[^\n]*write|(?:contents|actions|id-token|packages):\s*write')
            self.assertNotIn('secrets.', workflow)
            self.assertNotIn('pull_request_target:', workflow)
            self.assertIn("OPENAI_DISABLED: '1'", workflow)
            actions = re.findall(r'uses:\s+([^\s#]+)', workflow)
            self.assertTrue(actions)
            self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[0-9a-f]{40}', action) for action in actions))
            uploads = re.findall(r'          path: \|\n((?:            [^\n]+\n)+)', workflow)
            self.assertTrue(uploads)
            for block in uploads:
                self.assertEqual({line.strip() for line in block.splitlines()}, expected)

    def test_browser_dashboard_contract(self):
        result = subprocess.run(['node', 'tests/dashboard.mjs'], cwd=ROOT, capture_output=True,
                                text=True, timeout=120, env=dict(os.environ, OPENAI_DISABLED='1'))
        self.assertEqual(result.returncode, 0, 'Dashboard browser contract failed; run node tests/dashboard.mjs locally.')
        report = json.loads(result.stdout.strip().splitlines()[-1])
        self.assertEqual(report['status'], 'PASS')
        self.assertEqual(report['checks'], 7)
