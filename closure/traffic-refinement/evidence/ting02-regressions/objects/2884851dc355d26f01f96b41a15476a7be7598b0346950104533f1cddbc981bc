"""Every measured Java subprocess receives the same clean option environment."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from benchmarks.alloy4fun.live.adapter import Worker
from benchmarks.alloy4fun.fm24.native import Native
from benchmarks.alloy4fun.tar import adapter, verify
from runtime_dependencies import JAVA_ENVIRONMENT_OPTIONS


class EnvironmentBoundaryTests(unittest.TestCase):
    def setUp(self):
        base = adapter.ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='environment-boundaries-', dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        poison = {name: 'poison' for name in JAVA_ENVIRONMENT_OPTIONS}
        poison.update(ALLOY_BENCHMARK_TMP_ROOT=str(self.root / 'scratch'), KEEP_BOUNDARY='preserved')
        environment = patch.dict(os.environ, poison)
        environment.start()
        self.addCleanup(environment.stop)

    def check_environment(self, options):
        self.assertTrue(JAVA_ENVIRONMENT_OPTIONS.isdisjoint(options['env']))
        self.assertEqual(options['env']['KEEP_BOUNDARY'], 'preserved')

    def test_live_and_native_spawn_boundaries_strip_ambient_options(self):
        def failed_spawn(*args, **options):
            self.check_environment(options)
            raise FileNotFoundError('bounded spawn witness')
        with patch('subprocess.Popen', side_effect=failed_spawn) as spawn:
            live = Worker()
            try:
                with self.assertRaises(FileNotFoundError):
                    live.request({})
            finally:
                live.close()
            with self.assertRaises(FileNotFoundError):
                Native('unused')
            self.assertEqual(spawn.call_count, 2)
        self.assertEqual(list((self.root / 'scratch').iterdir()), [])

    def test_tar_generation_and_validation_strip_ambient_options(self):
        source = self.root / 'input.als'
        source.write_text('sig A {}\npred inv1 {no A}\npred inv1c {some A}\n'
                          'check correct {inv1 <=> inv1c}\n')
        manifest = self.root / 'manifest.json'
        manifest.write_text('{"classpath":"unused"}')
        def completed(command, **options):
            self.check_environment(options)
            payload = {'solved': False} if 'TarRunner' in command else {
                'status': 'checked', 'verified_correct': True, 'no_overflow': False}
            return subprocess.CompletedProcess(command, 0, json.dumps(payload), '')
        with patch('subprocess.run', side_effect=completed) as run:
            result = adapter.run_case({'path': str(source), 'predicate': 'inv1',
                                       'build_manifest': str(manifest), 'verify': False})
            self.assertEqual(result['status'], 'no_repair')
            result = verify.verify_candidate(source, 'inv1', {'inv1': 'some A'})
            self.assertTrue(result['verified_correct'])
            self.assertEqual(run.call_count, 2)
        self.assertEqual(list((self.root / 'scratch').iterdir()), [])


if __name__ == '__main__':
    unittest.main()
