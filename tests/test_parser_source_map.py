"""Actual Alloy parser correspondence and failure controls for file-free parsing."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class ParserSourceMapTests(unittest.TestCase):
    def test_current_source_parser_contract(self):
        dependencies = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
        with tempfile.TemporaryDirectory(prefix='parser-source-map-') as directory:
            subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-cp', dependencies,
                            '-d', directory, str(ROOT / 'engine/src/live/WorkerSafety.java'),
                            str(ROOT / 'engine/test/ParserSourceMapSelfTest.java')],
                           cwd=ROOT, check=True, capture_output=True, text=True, timeout=60)
            result = subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                                     '-Djava.io.tmpdir=' + directory, '-cp', directory + os.pathsep + dependencies,
                                     'live.ParserSourceMapSelfTest'], cwd=ROOT, check=True,
                                    capture_output=True, text=True, timeout=60)
            self.assertRegex(result.stdout.strip(), r'^ParserSourceMapSelfTest passed \([0-9]+ checks\)$')


if __name__ == '__main__':
    unittest.main()
