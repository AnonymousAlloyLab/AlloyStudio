"""Production callers retain private JVM scratch and sanitized failure handling."""
from collections import OrderedDict
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import admin_upload
import exercise_store
import runtime_dependencies
import server

ROOT = Path(__file__).resolve().parents[1]


class EngineTempIntegrationTests(unittest.TestCase):
    def setUp(self):
        base = ROOT / 'build/tests/tmp'
        base.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix='engine-integration-', dir=base)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.environment = patch.dict(os.environ, {'ALLOY_ENGINE_TMP_ROOT': ''})
        self.environment.start()
        self.addCleanup(self.environment.stop)
        self.scratch_paths = []

    def fake_run(self, command, **options):
        option = next(value for value in command if value.startswith('-Djava.io.tmpdir='))
        scratch = Path(option.split('=', 1)[1])
        self.assertEqual(scratch.parent, self.root / 'build/runtime/tmp')
        self.assertTrue(scratch.is_dir())
        (scratch / 'alloy_heredoc_private.als').write_text('private fixture')
        self.scratch_paths.append(scratch)
        self.assertGreater(options['timeout'], 0)
        return subprocess.CompletedProcess(command, 0, '{"status":"invalid","code":"validation_failed"}', '')

    def assert_cleaned(self, expected):
        self.assertEqual(len(self.scratch_paths), expected)
        self.assertTrue(all(not path.exists() for path in self.scratch_paths))

    def test_feedback_and_behavior_use_the_configured_backend_root(self):
        portal = SimpleNamespace(root=self.root, java='java', timeout=5,
            slots=threading.BoundedSemaphore(1), cache_lock=threading.Lock(), cache=OrderedDict(),
            correct_pools={'fixture': ['some A']}, behavior_slots=threading.BoundedSemaphore(1),
            behavior_cache=OrderedDict())
        record = dict(id='fixture', environmentBefore='sig A {}\n', predicateHeader='pred inv1 ',
                      environmentAfter='', predicate='inv1', oracleBody='some A')
        with patch.object(runtime_dependencies.subprocess, 'run', side_effect=self.fake_run):
            answer = server.Portal.evaluate(portal, record, 'no A')
            self.assertEqual(answer['status'], 'invalid')
            behavior = server.Portal.evaluate_behavior(portal, record, 'no A')
            self.assertEqual(behavior['status'], 'invalid')
        self.assert_cleaned(2)

    def test_upload_worker_preserves_environment_filter_and_private_error(self):
        def run(command, **options):
            self.assertNotIn('JAVA_TOOL_OPTIONS', options['env'])
            self.assertEqual(json.loads(options['input']), {'source': 'sig A {}'})
            self.fake_run(command, **options)
            raise subprocess.TimeoutExpired('PRIVATE_PATH', options['timeout'])
        with patch.dict(os.environ, {'JAVA_TOOL_OPTIONS': '-Dprivate=canary'}), \
                patch.object(runtime_dependencies.subprocess, 'run', side_effect=run):
            with self.assertRaises(admin_upload.UploadError) as error:
                admin_upload._worker('private-classpath', 'live.UploadInspector', {'source': 'sig A {}'},
                                     'java', time.monotonic() + 5, root=self.root)
        self.assertNotIn('PRIVATE_PATH', str(error.exception))
        self.assert_cleaned(1)

    def test_exercise_validator_uses_owned_scratch_without_changing_request(self):
        record = dict(predicate='inv1', environmentBefore='sig A {}\n', predicateHeader='pred inv1 ',
                      environmentAfter='', starter='')
        def run(command, **options):
            self.assertNotIn('JAVA_TOOL_OPTIONS', options['env'])
            request = json.loads(options['input'])
            self.assertEqual(request['oracleBodies'], ['some A'])
            self.assertEqual(request['scope'], 5)
            return self.fake_run(command, **options)
        with patch.object(exercise_store, 'normalize_import', return_value=(record, ['some A'], [], 5)), \
                patch.object(exercise_store, '_engine_identity', return_value={'engine': 'fixed'}), \
                patch.dict(os.environ, {'JAVA_TOOL_OPTIONS': '-Dprivate=canary'}), \
                patch.object(runtime_dependencies.subprocess, 'run', side_effect=run):
            with self.assertRaisesRegex(exercise_store.StoreError, 'validation_failed'):
                exercise_store.validate_import(self.root, {}, timeout=5)
        self.assert_cleaned(1)


if __name__ == '__main__':
    unittest.main()
