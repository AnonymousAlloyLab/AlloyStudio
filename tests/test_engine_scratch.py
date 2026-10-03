"""Actual JVM lifecycle checks: cleanup includes forced deadline termination."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from unittest.mock import patch

import runtime_dependencies as runtime
from runtime_dependencies import clean_java_environment, engine_temp_root, run_engine

ROOT = Path(__file__).resolve().parents[1]


class EngineScratchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base = ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='engine-scratch-', dir=base)
        cls.addClassCleanup(cls.temp.cleanup)
        cls.base = Path(cls.temp.name)
        cls.java = shutil.which('java')
        compiler = shutil.which('javac')
        if not cls.java or not compiler:
            raise unittest.SkipTest('JDK required for actual JVM lifecycle checks')
        source = cls.base / 'ScratchProbe.java'
        source.write_text('''import java.nio.file.*;
public class ScratchProbe {
  public static void main(String[] args) throws Exception {
    Path p = Files.createTempFile("alloy-test-", ".als");
    System.out.println(p.toString()); System.out.flush();
    if (args.length > 0) Thread.sleep(30000);
  }
}''')
        subprocess.run([compiler, str(source)], check=True, capture_output=True)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(dir=self.base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {'ALLOY_ENGINE_TMP_ROOT': str(self.root / 'scratch')})
        self.env.start()
        self.addCleanup(self.env.stop)
        self.command = [self.java, '-cp', str(self.base), 'ScratchProbe']

    def test_success_removes_only_own_directory(self):
        scratch = engine_temp_root(self.root)
        neighbor = scratch / 'another-live-worker'
        neighbor.mkdir()
        (neighbor / 'retain').write_text('owned by another request')
        result = run_engine(self.command, root=self.root, capture_output=True, text=True, timeout=10)
        path = Path(result.stdout.strip())
        self.assertEqual(result.returncode, 0)
        self.assertEqual(path.parent.parent, scratch)
        self.assertFalse(path.parent.exists())
        self.assertEqual(list(scratch.iterdir()), [neighbor])

    def test_timeout_waits_for_child_then_removes_created_files(self):
        with self.assertRaises(subprocess.TimeoutExpired) as error:
            run_engine([*self.command, 'sleep'], root=self.root,
                       capture_output=True, text=True, timeout=2)
        self.assertTrue(error.exception.stdout, 'JVM must create a file before timeout')
        self.assertEqual(list(engine_temp_root(self.root).iterdir()), [])

    def test_spawn_failure_cleans_directory(self):
        with self.assertRaises(OSError):
            run_engine([str(self.root / 'missing-java')], root=self.root)
        self.assertEqual(list(engine_temp_root(self.root).iterdir()), [])

    def test_relative_configuration_is_backend_relative(self):
        with patch.dict(os.environ, {'ALLOY_ENGINE_TMP_ROOT': 'private/tmp'}):
            self.assertEqual(engine_temp_root(self.root), self.root / 'private/tmp')

    def test_default_does_not_inherit_system_temporary_directory(self):
        with patch.dict(os.environ, {}, clear=True):
            self.assertEqual(engine_temp_root(self.root), self.root / 'build/runtime/tmp')

    def test_java_environment_is_minimal_and_excludes_unknown_values(self):
        original = {'PATH': '/example', 'LANG': 'en_US.UTF-8', 'KEEP': 'unchanged',
                    'CLASSPATH': 'wrong', 'JAVA_TOOL_OPTIONS': '-Dbad=true',
                    '_JAVA_OPTIONS': '-Dbad=true', 'JDK_JAVA_OPTIONS': '-Dbad=true',
                    'JDK_JAVAC_OPTIONS': '-Dbad=true'}
        self.assertEqual(clean_java_environment(original),
                         {'PATH': '/example', 'LANG': 'en_US.UTF-8'})
        self.assertEqual(original['_JAVA_OPTIONS'], '-Dbad=true')

    def test_ambient_options_cannot_redirect_actual_jvm_files_outside_owned_scratch(self):
        escape = self.root / 'escaped'
        escape.mkdir()
        poison = {'_JAVA_OPTIONS': '-Djava.io.tmpdir=' + str(escape),
                  'JAVA_TOOL_OPTIONS': '-XX:NotARealJVMOption',
                  'JDK_JAVA_OPTIONS': '--not-a-real-java-option',
                  'JDK_JAVAC_OPTIONS': '--not-a-real-javac-option',
                  'CLASSPATH': 'nonexistent-runtime'}
        for explicit in (False, True):
            with self.subTest(explicit_environment=explicit), patch.dict(os.environ, poison):
                options = {'env': dict(os.environ)} if explicit else {}
                result = run_engine(self.command, root=self.root, capture_output=True,
                                    text=True, timeout=10, **options)
                self.assertEqual(result.returncode, 0, result.stderr)
                path = Path(result.stdout.strip())
                self.assertEqual(path.parent.parent, engine_temp_root(self.root))
                self.assertFalse(path.parent.exists())
                self.assertEqual(list(escape.iterdir()), [])

    def test_shutdown_drains_registered_admin_before_acknowledging_exit(self):
        started, release = threading.Event(), threading.Event()
        def fake_run(*args, **kwargs):
            started.set()
            release.wait(2)
            return subprocess.CompletedProcess(args[0], 0, '', '')
        with patch.object(runtime.subprocess, 'run', side_effect=fake_run), ThreadPoolExecutor(1) as executor:
            future = executor.submit(run_engine, self.command, root=self.root, timeout=2)
            self.assertTrue(started.wait(1))
            runtime.close_engine_admission(self.root)
            self.assertFalse(runtime.wait_for_oneshots(self.root, timeout=.02))
            with self.assertRaises(OSError):
                run_engine(self.command, root=self.root, timeout=.01)
            release.set()
            future.result(1)
            self.assertTrue(runtime.wait_for_oneshots(self.root, timeout=1))
        runtime.open_engine_admission(self.root)
        self.assertEqual(runtime.PROCESS_BUDGET.stats()['reserved'], 0)

    def test_shutdown_rejects_a_registered_call_still_waiting_for_process_slot(self):
        owner = runtime.PROCESS_BUDGET.reserve('admin', 1)
        try:
            with patch.object(runtime.subprocess, 'run') as process, ThreadPoolExecutor(1) as executor:
                future = executor.submit(run_engine, self.command, root=self.root, timeout=1)
                deadline = time.monotonic() + 1
                key = str(self.root.resolve())
                while not runtime._ONESHOT_ROOTS.get(key, {}).get('active'):
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(.001)
                runtime.close_engine_admission(self.root)
                runtime.PROCESS_BUDGET.release_reaped(owner)
                with self.assertRaises(OSError):
                    future.result(1)
                process.assert_not_called()
                self.assertTrue(runtime.wait_for_oneshots(self.root, timeout=1))
        finally:
            runtime.PROCESS_BUDGET.release_reaped(owner)
            runtime.open_engine_admission(self.root)

    def test_explicit_one_shot_lane_consumes_its_own_reserved_capacity(self):
        def fake_run(*args, **kwargs):
            self.assertNotIn('lane', kwargs)
            self.assertEqual(runtime.PROCESS_BUDGET.stats()['lanes']['feedback'], 1)
            self.assertEqual(runtime.PROCESS_BUDGET.stats()['lanes']['admin'], 0)
            return subprocess.CompletedProcess(args[0], 0, '', '')
        with patch.object(runtime.subprocess, 'run', side_effect=fake_run):
            run_engine(self.command, root=self.root, lane='feedback', timeout=1)
        self.assertEqual(runtime.PROCESS_BUDGET.stats()['reserved'], 0)

    @unittest.skipIf(os.name == 'nt', 'Creating symlinks needs additional Windows privileges')
    def test_linked_configuration_is_rejected(self):
        destination = self.root / 'destination'
        destination.mkdir()
        (self.root / 'linked').symlink_to(destination, target_is_directory=True)
        with patch.dict(os.environ, {'ALLOY_ENGINE_TMP_ROOT': str(self.root / 'linked/tmp')}):
            with self.assertRaises(OSError):
                engine_temp_root(self.root)
        self.assertEqual(list(destination.iterdir()), [])


if __name__ == '__main__':
    unittest.main()
