"""Finite regression checks for adaptation and owned scratch lifecycles."""
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from benchmarks.alloy4fun import run_tar
from benchmarks.alloy4fun.tar import adapter, verify
from benchmarks.alloy4fun.tar.adapter import prepare_model
from benchmarks.alloy4fun.tar.verify import replace_candidate

class AdaptationTests(unittest.TestCase):
    def setUp(self):
        self.source = ('sig A {}\n// source remains intact\nfact { some A }\n'
                       'pred inv1 { no A }\npred inv1c { some A }\n'
                       'check correct { inv1 <=> inv1c } for 5 but 6 Int\n')

    def test_marker_preserves_environment_predicates_and_scope(self):
        adapted = prepare_model(self.source, 'inv1', 'inv1c')
        self.assertEqual(adapted, self.source.replace('check correct', 'check __repair') + '\npred __repair { inv1 }\n')

    def test_wrong_oracle_rejected(self):
        with self.assertRaises(ValueError):
            prepare_model(self.source, 'inv1', 'inv2c')

    def test_reserved_name_rejected(self):
        with self.assertRaises(ValueError):
            prepare_model(self.source + '\npred __repair {}', 'inv1', 'inv1c')

    def test_only_selected_body_can_change(self):
        repaired = replace_candidate(self.source.encode(), 'inv1', {'inv1': 'some A'}).decode()
        self.assertEqual(repaired, self.source.replace('pred inv1 { no A }', 'pred inv1 {\nsome A\n}'))

    def test_environment_injection_rejected(self):
        for body in ('} fact Evil { no univ } pred Extra {', 'some A } //'):
            with self.assertRaises(ValueError):
                replace_candidate(self.source.encode(), 'inv1', {'inv1': body})

    def test_target_switch_rejected(self):
        for candidate in ({'inv1c': 'no A'}, {'inv1': 'no A', 'inv1c': 'no A'}, {}):
            with self.assertRaises(ValueError):
                replace_candidate(self.source.encode(), 'inv1', candidate)


class ScratchTests(unittest.TestCase):
    SOURCE = 'sig A {}\npred inv1 {no A}\npred inv1c {some A}\ncheck correct {inv1 <=> inv1c} for 3\n'

    def setUp(self):
        base = adapter.ROOT / 'build/tests/tmp'
        base.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix='tar-scratch-tests-', dir=base)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.scratch = self.root / 'scratch'
        self.scratch.mkdir()
        self.sentinel = self.scratch / 'unrelated.txt'
        self.sentinel.write_text('keep')
        environment = patch.dict(os.environ, {'ALLOY_BENCHMARK_TMP_ROOT': str(self.scratch)})
        environment.start()
        self.addCleanup(environment.stop)
        self.source = self.root / 'fixture.als'
        self.source.write_text(self.SOURCE)
        self.manifest = self.root / 'manifest.json'
        self.manifest.write_text('{"classpath":"fixture-classpath"}')
        self.paths = []

    def request(self):
        return dict(path=str(self.source), predicate='inv1', build_manifest=str(self.manifest), verify=False)

    def capture_scratch(self, command):
        directory = Path(next(v.split('=', 1)[1] for v in command if v.startswith('-Djava.io.tmpdir=')))
        self.assertEqual(directory.parent, self.scratch)
        self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
        self.assertTrue((directory / 'input.als').exists())
        (directory / 'alloy_heredoc_private.als').write_text('private diagnostic fixture')
        if 'TarRunner' in command:
            preference = Path(next(v.split('=', 1)[1] for v in command if v.startswith('-Djava.util.prefs.userRoot=')))
            self.assertEqual(preference.parent, directory)
            self.assertTrue(preference.is_dir())
        self.paths.append(directory)
        return directory

    def assert_clean(self):
        self.assertTrue(self.paths)
        self.assertTrue(all(not path.exists() for path in self.paths))
        self.assertEqual(list(self.scratch.iterdir()), [self.sentinel])
        self.assertEqual(self.sentinel.read_text(), 'keep')

    def test_fresh_adapter_cleans_model_preferences_and_parser_files(self):
        def run(command, **options):
            self.capture_scratch(command)
            return subprocess.CompletedProcess(command, 0, '{"solved":false}', '')
        with patch.object(adapter.subprocess, 'run', side_effect=run):
            self.assertEqual(adapter.run_case(self.request())['status'], 'no_repair')
        self.assert_clean()

    def test_fresh_adapter_cleans_after_actual_child_timeout(self):
        actual_run = subprocess.run
        def run(command, **options):
            directory = self.capture_scratch(command)
            return actual_run([sys.executable, '-c', 'import time;time.sleep(10)', str(directory)],
                              capture_output=True, text=True, timeout=.05)
        with patch.object(adapter.subprocess, 'run', side_effect=run):
            self.assertEqual(adapter.run_case(self.request())['status'], 'outer_timeout')
        self.assert_clean()

    def test_fresh_adapter_spawn_error_still_cleans(self):
        def run(command, **options):
            self.capture_scratch(command)
            raise OSError('fixture startup failure')
        with patch.object(adapter.subprocess, 'run', side_effect=run):
            with self.assertRaises(OSError):
                adapter.run_case(self.request())
        self.assert_clean()

    def test_verifier_cleans_after_success_timeout_and_invalid_output(self):
        for outcome in ('success', 'timeout', 'invalid'):
            with self.subTest(outcome=outcome):
                def run(command, **options):
                    self.capture_scratch(command)
                    if outcome == 'timeout':
                        raise subprocess.TimeoutExpired(command, .01)
                    text = '{"status":"checked","verified_correct":true}' if outcome == 'success' else 'invalid-json'
                    return subprocess.CompletedProcess(command, 0, text, '')
                with patch.object(verify.subprocess, 'run', side_effect=run):
                    result = verify.verify_candidate(self.source, 'inv1', {'inv1': 'some A'})
                self.assertEqual(result['status'], {'success': 'checked', 'timeout': 'validation_timeout', 'invalid': 'validation_error'}[outcome])
                self.assert_clean()

    def run_mocked_batch(self, *, fail_verification=False):
        case = dict(case_id='fixture/over/case_inv1.als', path=str(self.source), predicate='inv1',
                    group='fixture', cohort_status='OVERCONSTRAINED',
                    source_sha256=hashlib.sha256(self.source.read_bytes()).hexdigest())
        cases = self.root / 'cases.jsonl'
        cases.write_text(json.dumps(case) + '\n')
        output = self.root / 'output'
        built_manifest = run_tar.ROOT / 'build/benchmarks/tar/manifest.json'
        built_bytes = json.dumps({'classpath': 'fixture-classpath', 'semantic_policy': {'no_overflow': False}}).encode()
        original_read_bytes, original_read_text = Path.read_bytes, Path.read_text
        def read_bytes(path):
            return built_bytes if path == built_manifest else original_read_bytes(path)
        def read_text(path, *args, **kwargs):
            return built_bytes.decode() if path == built_manifest else original_read_text(path, *args, **kwargs)
        workers = []
        test = self
        class FakeWorker:
            def __init__(self, timeout, *, command, recycle_after):
                self.timeout, self.closed = timeout, False
                self.preference = Path(next(v.split('=', 1)[1] for v in command if v.startswith('-Djava.util.prefs.userRoot=')))
                test.assertEqual(self.preference.parent, test.scratch)
                test.assertEqual(recycle_after, run_tar.RECYCLE_AFTER)
                workers.append(self)
            def request(self, payload):
                path = Path(payload['file'])
                test.assertEqual(path.parent.parent, test.scratch)
                test.assertTrue(path.exists())
                test.paths.append(path.parent)
                native = {'solved': False}
                if fail_verification:
                    native = {'solved': True, 'depth': 1, 'solution': {'inv1': 'some A'},
                              'native_trace': [{'hint': 'Check this operator.'}]}
                return native, .001, True
            def close(self):
                test.assertTrue(self.preference.exists(), 'preferences must survive until worker exit')
                self.closed = True
        arguments = ['run_tar.py', '--cases', str(cases), '--output', str(output), '--workers', '1']
        with patch.object(sys, 'argv', arguments), patch.object(Path, 'read_bytes', read_bytes), \
                patch.object(Path, 'read_text', read_text), patch.object(run_tar, 'Worker', FakeWorker), \
                patch.object(run_tar, 'verify_candidate', side_effect=RuntimeError('verification failed')), \
                redirect_stdout(io.StringIO()):
            if fail_verification:
                with self.assertRaisesRegex(RuntimeError, 'verification failed'):
                    run_tar.main()
            else:
                run_tar.main()
        self.assertTrue(workers)
        self.assertTrue(all(worker.closed and not worker.preference.exists() for worker in workers))
        self.assert_clean()
        return json.loads((output / 'manifest.json').read_text())

    def test_warm_runner_owns_preferences_models_and_records_recycle_policy(self):
        manifest = self.run_mocked_batch()
        self.assertEqual(manifest['recycle_after_queries'], 256)
        self.assertEqual(manifest['scratch_policy']['root'], str(self.scratch))
        self.assertIn('java.io.tmpdir', manifest['scratch_policy']['jvm'])

    def test_warm_runner_cleans_workers_and_preferences_after_failure(self):
        self.run_mocked_batch(fail_verification=True)

if __name__ == '__main__':
    unittest.main()
