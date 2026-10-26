"""Real-JVM learner-only CRUD structure, occurrence and optional-bound witnesses."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from runtime_dependencies import clean_java_environment


ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))


@unittest.skipUnless(shutil.which('java') and shutil.which('javac')
                     and (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file(),
                     'Compiled engine and JDK required')
class LearnerStructureTests(unittest.TestCase):
    def test_learner_only_structure_and_optional_bounds(self):
        scratch = ROOT / 'build/guidance-structure-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='structure-', dir=scratch) as directory:
            compiled = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', CLASSPATH,
                 '-d', directory, str(ROOT / 'engine/test/LearnerStructureSelfTest.java')],
                cwd=ROOT, capture_output=True, timeout=60, check=False, env=clean_java_environment())
            self.assertEqual(compiled.returncode, 0, 'Learner structure self-test did not compile: '
                             + compiled.stderr.decode(errors='replace'))
            checked = subprocess.run(
                ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + directory,
                 '-cp', directory + os.pathsep + CLASSPATH,
                 'is.fivefivefive.CanDis.LearnerStructureSelfTest'],
                cwd=ROOT, text=True, capture_output=True, timeout=60, check=False,
                env=clean_java_environment())
        self.assertEqual(checked.returncode, 0, 'Learner structure self-test failed: ' + checked.stderr)
        self.assertEqual(checked.stderr, '')
        self.assertRegex(checked.stdout.strip(), r'^LearnerStructureSelfTest passed \([0-9]+ checks\)$')


if __name__ == '__main__':
    unittest.main()
