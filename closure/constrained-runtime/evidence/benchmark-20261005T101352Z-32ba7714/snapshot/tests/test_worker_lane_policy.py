"""AP01 lane policy plus pre-spawn cancellation, with real child-process traces.

The pure tests mirror the named theorems over randomized reachable traces. The
pool tests use a fake framed worker; they establish lifecycle classification
and accounting, not OS scheduling, timely reaping or JVM startup behaviour.
"""
import random
from concurrent.futures import ThreadPoolExecutor
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from engine_workers import (EnginePool, EngineTimeout, EngineUnavailable, LaneLimits,
                            LanePolicy, default_lane_limits, _Worker)
from runtime_dependencies import ProcessBudget

ROOT = Path(__file__).resolve().parents[1]
EVENTS = ('start', 'ready', 'startup_failure', 'abandon_start', 'retire', 'reaped', 'tick', 'renew')

FAKE = r'''
import json,struct,sys,time
inc,lane,mode=sys.argv[1:]
if mode=='die_at_start':sys.exit(3)
def send(value):
 b=json.dumps(value,separators=(',',':')).encode();sys.stdout.buffer.write(struct.pack('>I',len(b))+b);sys.stdout.buffer.flush()
send({'protocol':1,'incarnation':inc,'ticket':0,'context':'','kind':'ready','result':{'status':'ready'}})
while True:
 p=sys.stdin.buffer.read(4)
 if not p:break
 n=struct.unpack('>I',p)[0];f=json.loads(sys.stdin.buffer.read(n))
 if mode=='hang':time.sleep(30)
 r={k:f[k] for k in ('protocol','incarnation','ticket','context','kind')}
 r.update(result={'status':'ok'},parseUnits=1)
 send(r)
'''


def invariant(policy):
    limits = policy.limits
    return (policy.reserved() <= limits.capacity
            and policy.tokens + policy.attempts == limits.starts_per_epoch
            and policy.renewal <= policy.now + limits.period)


def step(policy, event, clock):
    if event == 'tick':
        clock[0] += random.choice((0, 1, 7, 30, 61))
        policy.tick(clock[0])
    else:
        result = getattr(policy, event)()
        return result


class LanePolicyTheoremTests(unittest.TestCase):
    """Executable counterparts of the Work.lean lane theorems."""

    def policy(self, capacity=2, starts=12, failures=3, period=60):
        return LanePolicy(LaneLimits(capacity, starts, failures, period), 0.0)

    def test_reachable_capacity_and_spawn_bound(self):
        rng = random.Random(4096)
        for trace in range(300):
            random.seed(rng.random())
            clock = [0.0]
            policy = self.policy(capacity=rng.choice((1, 2)), starts=rng.randint(1, 12),
                                 failures=rng.randint(1, 4), period=rng.choice((1, 30, 60)))
            self.assertTrue(invariant(policy))
            for _ in range(200):
                step(policy, rng.choice(EVENTS), clock)
                self.assertTrue(invariant(policy), (trace, vars(policy)))
                self.assertLessEqual(policy.attempts, policy.limits.starts_per_epoch)

    def test_every_start_charged_and_no_tokens_no_spawn(self):
        policy = self.policy(starts=2)
        self.assertTrue(policy.start())
        self.assertEqual((policy.attempts, policy.tokens), (1, 1))
        policy.ready()
        self.assertTrue(policy.start())
        policy.ready()
        policy.retire(); policy.reaped(); policy.retire(); policy.reaped()
        before = vars(policy).copy()
        self.assertFalse(policy.start())
        self.assertEqual(vars(policy), before)

    def test_timeout_and_planned_retirement_do_not_poison_circuit(self):
        policy = self.policy()
        policy.start(); policy.ready()
        policy.retire()
        self.assertEqual(policy.failed_starts, 0)
        self.assertEqual(policy.reserved(), 1)  # retirement retains the reservation
        policy.reaped()
        self.assertEqual(policy.reserved(), 0)

    def test_only_startup_failure_increases_circuit(self):
        policy = self.policy(failures=2)
        policy.start(); policy.startup_failure()
        self.assertEqual(policy.failed_starts, 1)
        self.assertEqual(policy.unreaped, 1)
        policy.reaped(); policy.start(); policy.startup_failure(); policy.reaped()
        self.assertTrue(policy.circuit_open())
        self.assertFalse(policy.start())
        self.assertGreater(policy.tokens, 0)  # refusal is the circuit, not tokens

    def test_abandoned_start_keeps_attempt_charge_and_capacity_until_cleanup(self):
        policy = self.policy(capacity=1)
        policy.start()
        policy.abandon_start()
        self.assertEqual((policy.failed_starts, policy.tokens, policy.attempts), (0, 11, 1))
        self.assertEqual((policy.starting, policy.unreaped, policy.reserved()), (0, 1, 1))
        self.assertFalse(policy.start())
        policy.reaped()
        self.assertTrue(policy.start())

    def test_renewal_requires_time_and_cannot_repeat(self):
        policy = self.policy(starts=1, period=60)
        policy.start(); policy.ready(); policy.retire(); policy.reaped()
        policy.renew()
        self.assertEqual(policy.tokens, 0)  # renewal is due only at the boundary
        policy.tick(60.0); policy.renew()
        self.assertEqual((policy.tokens, policy.attempts, policy.failed_starts), (1, 0, 0))
        granted = vars(policy).copy()
        policy.renew()
        self.assertEqual(vars(policy), granted)

    def test_bounded_modeled_recovery(self):
        policy = self.policy(capacity=1, starts=1, failures=1, period=60)
        policy.start(); policy.startup_failure(); policy.reaped()
        self.assertFalse(policy.start())
        policy.tick(policy.now + 60); policy.renew()
        self.assertTrue(policy.start())

    def test_unlimited_churn_counterexample_is_bounded_here(self):
        policy = self.policy(capacity=1, starts=12, failures=3, period=60)
        cycles = 0
        while policy.start():
            policy.ready(); policy.retire(); policy.reaped()
            cycles += 1
            self.assertLess(cycles, 13)
        self.assertEqual(cycles, 12)
        self.assertEqual(policy.failed_starts, 0)

    def test_retry_after_is_read_only_and_bounded(self):
        policy = self.policy(starts=1, period=60)
        self.assertEqual(policy.retry_after(0.0), 0)
        policy.start()
        before = vars(policy).copy()
        self.assertEqual(policy.retry_after(10.5), 50)
        self.assertEqual(policy.retry_after(500.0), 0)
        self.assertEqual(vars(policy), before)

    def test_limits_reject_values_outside_the_diagnostic_envelope(self):
        for args in ((0, 12, 3, 60), (3, 12, 3, 60), (2, 13, 3, 60), (2, 12, 3, 61), (2, 12, 0, 60)):
            with self.subTest(args=args), self.assertRaises(ValueError):
                LaneLimits(*args)
        self.assertEqual(default_lane_limits(2, 1)['behavior'], LaneLimits(1, 6, 3, 60))

    def test_invalid_lane_profile_precedes_path_and_runtime_state(self):
        class UnreadablePath:
            def __fspath__(self):
                raise AssertionError('Invalid lane configuration reached filesystem setup')

        valid = default_lane_limits(2, 1)
        invalid = ({}, [], True, {'feedback': valid['feedback']},
                   {**valid, 'feedback': LaneLimits(1, 12, 3, 60)},
                   {**valid, 'behavior': LaneLimits(2, 6, 3, 60)},
                   {**valid, 'feedback': object()}, {**valid, 'other': valid['feedback']})
        for value in invalid:
            pool = EnginePool.__new__(EnginePool)
            with self.subTest(value=value), self.assertRaises(ValueError):
                pool.__init__(UnreadablePath(), 'unused', lane_limits=value)
            self.assertEqual(vars(pool), {})

    def test_java_processor_limits_are_validated_before_runtime_allocation(self):
        for value in (0, 3, True, 1.5, float('nan')):
            pool = EnginePool.__new__(EnginePool)
            with self.subTest(value=value), self.assertRaises(ValueError):
                pool.__init__('unused', 'unused', java_processors=value)
            self.assertEqual(vars(pool), {})


class FakePool(EnginePool):
    def __init__(self, *args, script, mode='ok', **kwargs):
        self.script, self.mode = script, mode
        super().__init__(*args, **kwargs)

    def _command(self, lane, incarnation, directory):
        return [sys.executable, '-I', str(self.script), incarnation, lane, self.mode]


class LanePoolTraceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base = ROOT / 'build/tests'
        base.mkdir(parents=True, exist_ok=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='lane-policy-', dir=base)
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.script = cls.root / 'fake_worker.py'
        cls.script.write_text(FAKE)

    def pool(self, mode='ok', clock=None, **kwargs):
        options = dict(script=self.script, mode=mode, budget=ProcessBudget())
        if clock is not None:
            options['clock'] = clock
        pool = FakePool(self.root, 'unused', **options, **kwargs)
        self.addCleanup(pool.close)
        return pool

    def test_failed_startup_opens_circuit_without_touching_behavior(self):
        pool = self.pool(mode='die_at_start')
        for _ in range(3):
            with self.assertRaises(EngineUnavailable):
                pool.evaluate('feedback', {}, 3)
        with self.assertRaisesRegex(EngineUnavailable, 'failed to start repeatedly'):
            pool.evaluate('feedback', {}, 3)
        lanes = pool.stats()['lanes']
        self.assertTrue(lanes['feedback']['startupCircuitOpen'])
        self.assertEqual(lanes['feedback']['launchCredits'], 9)
        self.assertFalse(lanes['behavior']['startupCircuitOpen'])
        self.assertEqual(lanes['behavior']['launchCredits'], 6)
        self.assertEqual(pool.stats()['processBudget']['reserved'], 0)

    def test_initialization_failure_releases_starting_reservation(self):
        pool = self.pool(feedback_workers=1)
        with patch('engine_workers._Worker', side_effect=OSError('allocation failed')):
            with self.assertRaises(EngineUnavailable):
                pool.evaluate('feedback', {}, 3)
        state = pool.stats()
        lane = state['lanes']['feedback']
        self.assertEqual((state['starting'], lane['starting'], lane['unreaped']), (0, 0, 0))
        self.assertEqual((lane['launchCredits'], pool.policies['feedback'].failed_starts), (11, 1))
        self.assertEqual(state['processBudget']['reserved'], 0)
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')

    def test_failed_transport_thread_start_still_reaps_child(self):
        pool = self.pool(feedback_workers=1)
        with patch('engine_workers.threading.Thread.start', side_effect=RuntimeError('no threads')):
            with self.assertRaises(RuntimeError):
                pool.evaluate('feedback', {}, 3)
        state = pool.stats()
        lane = state['lanes']['feedback']
        self.assertEqual((state['starting'], lane['unreaped'], state['processBudget']['reserved']), (0, 0, 0))
        self.assertEqual(pool.policies['feedback'].failed_starts, 1)
        self.assertEqual(list((self.root / 'build/runtime/tmp').iterdir()), [])
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')

    def test_request_timeout_retires_without_opening_circuit(self):
        pool = self.pool(mode='hang')
        for _ in range(3):
            with self.assertRaises(EngineTimeout):
                pool.evaluate('feedback', {}, 0.5)
        lanes = pool.stats()['lanes']['feedback']
        self.assertFalse(lanes['startupCircuitOpen'])
        self.assertEqual(lanes['launchCredits'], 9)
        self.assertEqual(lanes['unreaped'], 0)

    def test_capacity_wait_expiry_does_not_poison_startup_circuit(self):
        pool = self.pool(feedback_workers=1, startup_timeout=.03)
        owners = [pool.budget.reserve('feedback', 1) for _ in range(2)]
        try:
            for _ in range(3):
                with self.assertRaises(EngineTimeout):
                    pool.evaluate('feedback', {}, 1)
            self.assertEqual(pool.stats()['launches'], 0)
            self.assertEqual(pool.policies['feedback'].failed_starts, 0)
            self.assertEqual(pool.policies['feedback'].tokens, 9)
            self.assertEqual(pool.policies['feedback'].reserved(), 0)
        finally:
            for owner in owners:
                pool.budget.release_reaped(owner)
        pool.startup_timeout = 1
        self.assertEqual(pool.evaluate('feedback', {}, 1)['status'], 'ok')

    def test_planned_retirement_tokens_and_epoch_renewal(self):
        now = [1000.0]
        pool = self.pool(clock=lambda: now[0], max_tasks=1)
        for _ in range(12):
            self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')
        # The thirteenth checkout retires the expired worker and needs a 13th start.
        with self.assertRaisesRegex(EngineUnavailable, 'restart limit'):
            pool.evaluate('feedback', {}, 3)
        lane = pool.stats()['lanes']['feedback']
        self.assertEqual(lane['launchCredits'], 0)
        self.assertEqual(lane['retryAfterSeconds'], 60)
        self.assertEqual(pool.evaluate('behavior', {}, 3)['status'], 'ok')  # lane isolation
        now[0] += 60
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')
        self.assertEqual(pool.stats()['lanes']['feedback']['launchCredits'], 11)

    def test_prewarm_burst_charges_every_launch(self):
        pool = self.pool()
        pool.prewarm()
        lanes = pool.stats()['lanes']
        self.assertEqual((lanes['feedback']['launchCredits'], lanes['behavior']['launchCredits']), (10, 5))
        self.assertEqual((lanes['feedback']['ready'], lanes['behavior']['ready']), (2, 1))

    def test_unreaped_child_retains_capacity_until_confirmed_reap(self):
        pool = self.pool(feedback_workers=1, startup_timeout=.4, lane_limits={
            'feedback': LaneLimits(1, 12, 3, 60), 'behavior': LaneLimits(1, 6, 3, 60)})
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')
        worker = pool.workers['feedback'][0]
        with patch.object(worker.process, 'wait', side_effect=subprocess.TimeoutExpired('safe', 1)):
            pool._retire(worker)
            lane = pool.stats()['lanes']['feedback']
            self.assertEqual((lane['unreaped'], lane['ready']), (1, 0))
            # Capacity is reserved by the unconfirmed child: no replacement may start.
            started = time.monotonic()
            with self.assertRaises(EngineTimeout):
                pool.evaluate('feedback', {}, 0.4)
            self.assertLess(time.monotonic() - started, 3)
            self.assertEqual(pool.stats()['launches'], 1)
        # A later confirmed reap frees the reservation for a replacement.
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')
        self.assertEqual(pool.stats()['lanes']['feedback']['unreaped'], 0)
        self.assertEqual(pool.stats()['launches'], 2)

    def test_stale_failed_stop_cannot_resurrect_a_confirmed_reap(self):
        pool = self.pool(feedback_workers=1)
        pool.evaluate('feedback', {}, 3)
        worker = pool.workers['feedback'][0]
        original_stop = worker.stop
        failing_stop_entered = threading.Event()
        allow_failed_return = threading.Event()

        def stop(timeout=5):
            if threading.current_thread().name.startswith('stale-reap'):
                failing_stop_entered.set()
                if not allow_failed_return.wait(3):
                    raise AssertionError('successful reap did not finish')
                return False
            return original_stop(timeout=timeout)

        with patch.object(worker, 'stop', side_effect=stop):
            with ThreadPoolExecutor(1, thread_name_prefix='stale-reap') as executor:
                older = executor.submit(pool._retire, worker)
                try:
                    self.assertTrue(failing_stop_entered.wait(3))
                    # Ownership must already be retained while stop is pending.
                    self.assertEqual(pool.stats()['unreaped'], 1)
                    pool._retire(worker)
                    self.assertEqual(worker.reservation, 'reaped')
                finally:
                    allow_failed_return.set()
                older.result(3)
        state = pool.stats()
        self.assertEqual((state['unreaped'], state['lanes']['feedback']['unreaped'],
                          state['processBudget']['reserved']), (0, 0, 0))
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')

    def test_idle_transport_failure_is_retained_in_diagnostics_without_reaping(self):
        pool = self.pool(feedback_workers=1)
        pool.evaluate('feedback', {}, 3)
        worker = pool.workers['feedback'][0]
        worker._fail()  # Same event the transport reader emits on unexpected EOF.
        before = pool.stats()
        with patch.object(worker, 'stop', side_effect=AssertionError('diagnostics must not reap')):
            lane = pool.diagnostics()['lanes']['feedback']
        self.assertEqual((lane['ready'], lane['busy'], lane['unreaped']), (0, 0, 1))
        self.assertFalse(lane['startupCircuitOpen'])
        self.assertEqual(pool.stats(), before)
        self.assertEqual(pool.budget.stats()['reserved'], 1)
        self.assertIsNone(worker.process.poll())  # Ownership, not diagnosis, controls retirement.
        self.assertEqual(pool.evaluate('feedback', {}, 3)['status'], 'ok')
        self.assertEqual(pool.stats()['unreaped'], 0)

    def test_transport_failure_between_handshake_and_publication_is_not_ready(self):
        pool = self.pool(feedback_workers=1)
        original_launch = _Worker.launch

        def launch_then_fail(worker, deadline):
            original_launch(worker, deadline)
            worker._fail()

        with patch.object(_Worker, 'launch', launch_then_fail):
            with self.assertRaises(EngineUnavailable):
                pool.prewarm()
        state = pool.stats()
        lane = state['lanes']['feedback']
        self.assertEqual((lane['ready'], lane['starting'], lane['unreaped']), (0, 0, 0))
        self.assertEqual(state['processBudget']['reserved'], 0)
        self.assertEqual(pool.policies['feedback'].failed_starts, 0)
        self.assertEqual(lane['launchCredits'], 11)

    def test_diagnostics_sample_is_read_only(self):
        now = [0.0]
        pool = self.pool(clock=lambda: now[0], max_tasks=1)
        pool.evaluate('feedback', {}, 3)
        before = (pool.stats()['launches'], {lane: vars(policy).copy() for lane, policy in pool.policies.items()})
        now[0] += 3600
        for _ in range(3):
            sample = pool.diagnostics()
        self.assertEqual(sample['lanes']['feedback']['launchCredits'], 11)  # no implicit renewal
        after = (pool.stats()['launches'], {lane: vars(policy).copy() for lane, policy in pool.policies.items()})
        self.assertEqual(before, after)


if __name__ == '__main__':
    unittest.main()
