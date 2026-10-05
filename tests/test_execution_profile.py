"""LP05 small-machine settings preserve both lanes and bound every phase."""
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch

from execution_profile import resolve_profile
import runtime_dependencies as runtime
import server


class ExecutionProfileTests(unittest.TestCase):
    def test_default_and_standard_retain_both_analysis_lanes(self):
        small, standard = resolve_profile(), resolve_profile('standard')
        self.assertEqual((small.workers, small.java_processors, small.startup_timeout), (1, 1, 20))
        self.assertEqual((standard.workers, standard.java_processors, standard.startup_timeout), (2, 2, 10))

    def test_explicit_limits_are_bounded_without_implicit_coercion(self):
        self.assertEqual(resolve_profile(workers=32, startup_timeout=30).workers, 2)
        for options in ({'name': 'unknown'}, {'name': None}, {'workers': True}, {'workers': 0},
                        {'workers': 33}, {'workers': '1'}, {'startup_timeout': float('nan')},
                        {'startup_timeout': float('inf')}, {'startup_timeout': 0},
                        {'startup_timeout': True}, {'startup_timeout': 30.1}):
            with self.subTest(options=options), self.assertRaises(ValueError):
                resolve_profile(**options)

    def test_resolved_profile_is_immutable(self):
        with self.assertRaises(AttributeError):
            resolve_profile().workers = 100


class PortalProfileTests(unittest.TestCase):
    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / 'build/v005-refinement/tests'
        scratch.mkdir(parents=True, exist_ok=True)
        temp = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        snapshot = SimpleNamespace(exercises={}, correct_pools={})
        for name, value in (('load_store', Mock(return_value=snapshot)), ('AuthManager', Mock()),
                            ('Explainer', Mock()), ('AdminService', Mock())):
            context = patch.object(server, name, value)
            context.start(); self.addCleanup(context.stop)
        server.AdminService.return_value.close.return_value = True

    def app(self, **options):
        app = server.Portal(('127.0.0.1', 0), root=self.root, **options)
        self.addCleanup(app.server_close)
        return app

    def test_profile_reaches_scheduler_worker_and_admin_runtime(self):
        app = self.app()
        self.assertEqual(app.engine_pool.limits, {'feedback': 1, 'behavior': 1})
        self.assertEqual(app.engine_pool.java_processors, 1)
        self.assertEqual(app.engine_pool.startup_timeout, 20)
        self.assertEqual(len(app.scheduler.threads), 2)
        with patch.object(runtime.subprocess, 'run', return_value=SimpleNamespace(returncode=0)) as execute:
            runtime.run_engine(['java', '-XX:ActiveProcessorCount=2', '-cp', 'fixture', 'main'], root=self.root)
        command = execute.call_args.args[0]
        self.assertIn('-XX:ActiveProcessorCount=1', command)
        self.assertNotIn('-XX:ActiveProcessorCount=2', command)

    def test_scheduler_allowance_covers_cold_worker_and_cleanup(self):
        app = self.app(timeout=12)
        with patch.object(app.scheduler, 'run', return_value={}) as run:
            app._schedule('feedback', ('fixture',), lambda: {}, None, 0)
        self.assertEqual(run.call_args.kwargs['timeout'], 32.25)
        with patch.object(app.scheduler, 'run', return_value={}) as run:
            app._schedule('behavior', ('fixture',), lambda: {}, None, 0)
        self.assertEqual(run.call_args.kwargs['timeout'], 50.25)

    def test_oneshot_profile_has_its_own_soft_limit_and_no_extra_wait(self):
        app = self.app(engine_mode='oneshot')
        with patch.object(server, 'run_engine', return_value=SimpleNamespace(returncode=0, stdout='{}')) as run:
            self.assertEqual(app._engine('feedback', {}), {})
        command = run.call_args.args[0]
        self.assertIn('-XX:ActiveProcessorCount=1', command)
        self.assertIn('-Dalloy.feedback.workMillis=8000', command)
        self.assertEqual(run.call_args.kwargs['timeout'], 12)
        with patch.object(app.scheduler, 'run', return_value={}) as run:
            app._schedule('feedback', ('fixture',), lambda: {}, None, 0)
        self.assertEqual(run.call_args.kwargs['timeout'], 12)

    def test_standard_profile_retains_explicit_extra_capacity(self):
        app = self.app(resource_profile='standard', workers=2, startup_timeout=15)
        self.assertEqual(app.engine_pool.limits, {'feedback': 2, 'behavior': 1})
        self.assertEqual(app.engine_pool.java_processors, 2)
        self.assertEqual(app.engine_pool.startup_timeout, 15)

    def test_acquisition_timeout_is_retryable_without_blame_or_cached_result(self):
        app = self.app()
        with patch.object(app.engine_pool, 'evaluate', side_effect=server.EngineAcquisitionTimeout()) as evaluate:
            for function, arguments in ((app._feedback, ({}, '', 'canonical', {})),
                                        (app._behavior, ({},))):
                result = function(*arguments)
                self.assertEqual((result['status'], result['code'], result['dispatched']), ('busy', 'capacity', False))
                self.assertNotIn('Simplify', result['message'])
        self.assertEqual(evaluate.call_count, 2)

    def test_invalid_profile_allocates_no_service_or_listener(self):
        with patch.object(server, 'Scheduler') as scheduler, self.assertRaises(ValueError):
            self.app(startup_timeout=float('nan'))
        server.load_store.assert_not_called()
        scheduler.assert_not_called()

    def test_active_oneshot_profile_cannot_change(self):
        runtime.open_engine_admission(self.root, java_processors=1)
        state = runtime._ONESHOT_ROOTS[runtime._root_key(self.root)]
        with runtime._ONESHOT_CONDITION:
            state['active'] += 1
        try:
            with self.assertRaises(OSError):
                runtime.open_engine_admission(self.root, java_processors=2)
            self.assertEqual(state['java_processors'], 1)
        finally:
            with runtime._ONESHOT_CONDITION:
                state['active'] -= 1


if __name__ == '__main__':
    unittest.main()
