"""Pinned upstream cost/tie digest and request-local optimization controls."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class AcgnPerformanceTests(unittest.TestCase):
    def test_upstream_optimization_preserves_costs_and_selected_alignments(self):
        dependencies = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))
        with tempfile.TemporaryDirectory(prefix='acgn-performance-') as directory:
            source = ROOT / 'vendor/acgn/src'
            subprocess.run(['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=2',
                            '--release', '17', '-encoding', 'UTF-8', '-cp', dependencies,
                            '-sourcepath', str(source), '-d', directory,
                            str(source / 'is/fivefivefive/CanDis/theory/EqualityCertificates.java'),
                            str(source / 'is/fivefivefive/CanDis/core/PreparedOrbitCacheTest.java'),
                            str(source / 'is/fivefivefive/CanDis/core/FastPrefixSourceTest.java'),
                            str(source / 'is/fivefivefive/CanDis/PrefixBinderPlanTest.java'),
                            str(source / 'is/fivefivefive/CanDis/UnusedBindingDescopeTest.java'),
                            str(source / 'is/fivefivefive/CanDis/UnusedBindingD8Test.java'),
                            str(source / 'is/fivefivefive/CanDis/core/UnusedBindingEvidenceTest.java'),
                            str(source / 'is/fivefivefive/CanDis/PerformanceRegressionTest.java')],
                           cwd=ROOT, check=True, capture_output=True, timeout=90)
            result = subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                                     '-Djava.io.tmpdir=' + directory,
                                     '-cp', directory + os.pathsep + dependencies,
                                     'is.fivefivefive.CanDis.PerformanceRegressionTest'],
                                    cwd=ROOT, check=True, capture_output=True, text=True, timeout=60)
            self.assertIn('resultDigest=0af040ac2f4a4686b17fed81bfeb514357e641871074e28c7ccabaf0c4dd35f2', result.stdout)
            self.assertIn('PerformanceRegressionTest: 19760 checks passed', result.stdout)
            orbit = subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                                    '-Djava.io.tmpdir=' + directory,
                                    '-cp', directory + os.pathsep + dependencies,
                                    'is.fivefivefive.CanDis.core.PreparedOrbitCacheTest'],
                                   cwd=ROOT, check=True, capture_output=True, text=True, timeout=60)
            self.assertIn('PreparedOrbitCacheTest passed', orbit.stdout)
            for name in ('PrefixBinderPlanTest', 'core.FastPrefixSourceTest',
                         'UnusedBindingDescopeTest', 'core.UnusedBindingEvidenceTest'):
                result = subprocess.run(['java', '-ea', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                                         '-Djava.io.tmpdir=' + directory,
                                         '-cp', directory + os.pathsep + dependencies,
                                         'is.fivefivefive.CanDis.' + name],
                                        cwd=ROOT, check=True, capture_output=True, text=True, timeout=60)
                self.assertIn('checks passed', result.stdout)
            result = subprocess.run(['java', '-ea', '-Xmx256m', '-XX:ActiveProcessorCount=1',
                                     '-Djava.io.tmpdir=' + directory,
                                     '-cp', directory + os.pathsep + dependencies,
                                     'is.fivefivefive.CanDis.UnusedBindingD8Test'],
                                    cwd=ROOT, check=True, capture_output=True, text=True, timeout=60)
            self.assertEqual(result.stdout.count('Fast=17 certified=17 CHECKED_NATIVE_ACTIONS_EQUAL'), 4)
