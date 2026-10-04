"""Real Java policy, ordered accumulation and both metric integration checks."""
import os
import json
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]
CLASSPATH = os.pathsep.join((str(ROOT / 'build/engine/classes'), str(ROOT / 'vendor/acgn/lib/*')))


class PoolBridgeTests(unittest.TestCase):
    def test_finite_pool_oracle_and_live_metrics(self):
        with tempfile.TemporaryDirectory(prefix='pool-bridge-') as directory:
            compiled = subprocess.run(
                ['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', CLASSPATH,
                 '-d', directory, str(ROOT / 'engine/test/PoolBridgeSelfTest.java')],
                cwd=ROOT, capture_output=True, timeout=30, check=False)
            self.assertEqual(compiled.returncode, 0, 'Pool bridge self-test did not compile')
            checked = subprocess.run(
                ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
                 directory + os.pathsep + CLASSPATH, 'live.PoolBridgeSelfTest'],
                cwd=ROOT, text=True, capture_output=True, timeout=30, check=False)
        self.assertEqual(checked.returncode, 0, 'Pool bridge self-test failed')
        self.assertEqual(checked.stderr, '')
        self.assertRegex(checked.stdout.strip(), r'^PoolBridgeSelfTest passed \(5462 exhaustive pools, [0-9]+ checks\)$')

    def test_generated_policies_control_both_live_metric_paths(self):
        # Shadow only the policy class in an isolated JVM classpath. These
        # deliberately wrong policies establish that each runtime adapter
        # actually consumes both decisions rather than duplicating them inline.
        for policy in ('choose', 'finish'):
            with self.subTest(policy=policy), tempfile.TemporaryDirectory(prefix='pool-policy-mutation-') as directory:
                source = Path(directory) / 'BridgePolicies.java'
                source.write_text(
                    'package live;\npublic final class BridgePolicies {\n'
                    ' public static boolean choose(boolean hasBest, boolean improves) { return '
                    + ('true' if policy == 'choose' else '!hasBest || improves') + '; }\n'
                    ' public static boolean finish(boolean nonempty, boolean complete) { return '
                    + ('false' if policy == 'finish' else 'nonempty && complete') + '; }\n}\n',
                    encoding='utf-8')
                compiled = subprocess.run(
                    ['javac', '-encoding', 'UTF-8', '--release', '17', '-d', directory, str(source)],
                    cwd=ROOT, capture_output=True, timeout=30, check=False)
                self.assertEqual(compiled.returncode, 0, 'Policy mutation fixture did not compile')
                for metric in ('canonical', 'ast'):
                    with self.subTest(metric=metric):
                        request = {
                            'metric': metric, 'studentSource': 'sig A {}\npred target { some A }',
                            'predicate': 'target', 'referencePrefix': 'sig A {}\npred target {\n',
                            'referenceSuffix': '\n}', 'referenceBodies': ['some A', 'no A'],
                        }
                        checked = subprocess.run(
                            ['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
                             directory + os.pathsep + CLASSPATH, 'live.LiveFeedback'],
                            input=json.dumps(request), cwd=ROOT, text=True, capture_output=True,
                            timeout=20, check=False)
                        self.assertEqual(checked.returncode, 0)
                        self.assertEqual(checked.stderr, '')
                        result = json.loads(checked.stdout)
                        if policy == 'choose':
                            self.assertEqual(result.get('status'), 'ok')
                            self.assertEqual(result.get('distance'), 1, 'Choice mutation must change the winner')
                            self.assertEqual(result['operations'][0]['replacementOperator'], 'no')
                        else:
                            self.assertEqual(result.get('status'), 'engine_error')
                            self.assertEqual(result['diagnostics'][0]['code'], 'REFERENCE_POOL_UNAVAILABLE')
                            self.assertNotIn('distance', result, 'Completion mutation must prevent publication')
                            self.assertNotIn('comparison', result)


if __name__ == '__main__':
    unittest.main(verbosity=2)
