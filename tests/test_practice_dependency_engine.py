"""Actual Alloy call-identity witnesses for selected unsolved practice guards."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from runtime_dependencies import clean_java_environment, runtime_classpath


ROOT = Path(__file__).resolve().parents[1]
SCRATCH = ROOT / 'build/practice-dependency-engine-tests'


class PracticeDependencyEngineTests(unittest.TestCase):
    def test_single_jvm_constructed_counterexamples_and_all_engine_paths(self):
        SCRATCH.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='guard-', dir=SCRATCH) as temporary:
            path = Path(temporary)
            classes = path / 'classes'
            classes.mkdir()
            classpath = runtime_classpath(ROOT)
            compiled = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', classpath,
                 '-d', str(classes), str(ROOT / 'engine/test/PracticeDependencySelfTest.java')],
                cwd=ROOT, capture_output=True, timeout=30, env=clean_java_environment())
            self.assertEqual(compiled.returncode, 0, 'Dependency guard witness did not compile')
            result = subprocess.run(
                ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(path),
                 '-cp', str(classes) + os.pathsep + classpath, 'live.PracticeDependencySelfTest'],
                cwd=ROOT, capture_output=True, timeout=60, env=clean_java_environment())
        self.assertEqual(result.returncode, 0, 'Dependency guard constructed counterexample failed')
        self.assertEqual(result.stderr, b'', 'Dependency witness leaked raw diagnostics')
        try:
            report = json.loads(result.stdout)
        except (ValueError, UnicodeError):
            self.fail('Dependency witness returned an unreadable response')
        self.assertEqual(report['status'], 'PASS', report.get('failedCheck', 'Dependency witness failed'))
        self.assertEqual(set(report), {'status', 'checks'})
        self.assertGreaterEqual(report['checks'], 150)


if __name__ == '__main__':
    unittest.main()
