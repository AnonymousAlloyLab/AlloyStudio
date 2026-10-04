"""Bounded supervisor regressions using fake services/cgroups; no JVM launches."""
from contextlib import ExitStack
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from benchmarks.alloy4fun import run_guarded_tar as guard


class GuardTests(unittest.TestCase):
    def setUp(self):
        base = guard.ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix='guard-test-', dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'data'
        (self.data / 'recovery').mkdir(parents=True)
        self.group = '/user.slice/user-1000.slice/user@1000.service/app.slice/test-tar.service'
        self.cgroup = self.root / 'cgroup'
        self.cgroup.mkdir()
        for name, value in {
            'memory.current': '1024', 'memory.peak': '4096',
            'memory.events': 'low 0\nhigh 0\nmax 0\noom 0\noom_kill 0\noom_group_kill 0\n',
            'memory.oom.group': '1', 'memory.high': str(4 * guard.GIB),
            'memory.max': str(6 * guard.GIB), 'memory.swap.max': '0',
            'cpu.max': '400000 100000', 'cgroup.procs': '999999\n',
        }.items():
            (self.cgroup / name).write_text(value)
        self.calls = []
        self.states = [self.state('active', 'running', '999999'),
                       self.state('active', 'exited', '0')]

    def state(self, active, sub, pid, *, result='success', exit_status='0'):
        return {'ControlGroup': self.group, 'MainPID': pid, 'ActiveState': active,
                'SubState': sub, 'Result': result, 'ExecMainStatus': exit_status,
                'BindsTo': 'test-supervisor.service', 'After': 'test-supervisor.service'}

    def fake_path(self, *parts):
        path = Path(*parts)
        if str(path) == '/sys/fs/cgroup':
            return self.root / 'fake-sys-cgroup'
        if str(path) == '/proc/999999/cgroup':
            probe = self.root / 'main-cgroup'
            probe.write_text('0::' + self.group + '\n')
            return probe
        return path

    def fake_run(self, command, **options):
        self.calls.append(command)
        return subprocess.CompletedProcess(command, 0, '', '')

    def run_guard(self, *, memory=None, write_error=False):
        # Path('/sys/fs/cgroup') / group must resolve into the fake tree.
        location = self.root / 'fake-sys-cgroup' / self.group.lstrip('/')
        location.parent.mkdir(parents=True, exist_ok=True)
        if not location.exists():
            location.symlink_to(self.cgroup, target_is_directory=True)
        with ExitStack() as stack:
            stack.enter_context(patch.object(guard, 'Path', side_effect=self.fake_path))
            stack.enter_context(patch.object(guard, 'properties', side_effect=self.states))
            stack.enter_context(patch.object(guard.subprocess, 'run', side_effect=self.fake_run))
            stack.enter_context(patch.object(guard.os, 'getpriority', return_value=10))
            stack.enter_context(patch.object(guard, 'available_memory',
                                            side_effect=memory or [16 * guard.GIB] * 8))
            stack.enter_context(patch.object(guard.time, 'sleep'))
            if write_error:
                stack.enter_context(patch.object(guard, 'write_json', side_effect=OSError('report disk unavailable')))
            return guard.run_bounded(self.data, 'test-tar', ['python', 'benchmark-fixture'],
                                     self.data / 'log.txt')

    def stopped(self):
        return any(c[:4] == ['systemctl', '--user', 'stop', 'test-tar'] for c in self.calls)

    def test_successful_remain_after_exit_service_finishes_and_is_released(self):
        report = self.run_guard()
        self.assertEqual(report['status'], 'COMPLETED')
        self.assertEqual(report['effective_limits'], guard.POLICY)
        self.assertTrue(self.stopped())

    def test_no_launch_below_startup_memory_reserve(self):
        with self.assertRaisesRegex(RuntimeError, '10 GiB'):
            self.run_guard(memory=[9 * guard.GIB])
        self.assertEqual(self.calls, [])

    def test_global_reserve_failure_stops_only_owned_unit(self):
        with self.assertRaisesRegex(RuntimeError, '4 GiB'):
            self.run_guard(memory=[16 * guard.GIB, 16 * guard.GIB, 3 * guard.GIB])
        self.assertTrue(self.stopped())
        report = json.loads((self.data / 'recovery/tar-guard.json').read_text())
        self.assertTrue(report['watchdog_stopped_workload'])
        self.assertEqual(report['status'], 'STOPPED_FOR_SAFETY')

    def test_effective_limits_mismatch_stops_owned_unit(self):
        (self.cgroup / 'memory.max').write_text(str(7 * guard.GIB))
        with self.assertRaisesRegex(RuntimeError, 'limits'):
            self.run_guard()
        self.assertTrue(self.stopped())

    def test_oom_event_cannot_be_a_completed_measurement(self):
        (self.cgroup / 'memory.events').write_text('oom 1\noom_kill 1\noom_group_kill 1\n')
        with self.assertRaisesRegex(RuntimeError, 'finish cleanly'):
            self.run_guard()
        self.assertTrue(self.stopped())
        report = json.loads((self.data / 'recovery/tar-guard.json').read_text())
        self.assertEqual(report['status'], 'FAILED')

    def test_postlaunch_evidence_failure_stops_workload(self):
        with self.assertRaisesRegex(OSError, 'report disk unavailable'):
            self.run_guard(write_error=True)
        self.assertTrue(self.stopped(), 'Report failure left an unmonitored workload running')

    def test_workload_is_bound_to_supervisor_lifetime(self):
        self.run_guard()
        launched = self.calls[0]
        self.assertIn('--property=BindsTo=test-supervisor.service', launched)
        self.assertIn('--property=After=test-supervisor.service', launched)

    def test_missing_enforced_supervisor_dependency_stops_unit(self):
        self.states[0]['BindsTo'] = ''
        with self.assertRaisesRegex(RuntimeError, 'watchdog supervisor'):
            self.run_guard()
        self.assertTrue(self.stopped())

    def test_missing_group_oom_protection_stops_unit(self):
        (self.cgroup / 'memory.oom.group').write_text('0')
        with self.assertRaisesRegex(RuntimeError, 'limits'):
            self.run_guard()
        self.assertTrue(self.stopped())


if __name__ == '__main__':
    unittest.main()
