"""Dashboard disclosure, revision binding, release gating, and real browser witnesses."""
import json
import os
import re
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from scripts import ci_check, ci_dashboard, release_preflight

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
