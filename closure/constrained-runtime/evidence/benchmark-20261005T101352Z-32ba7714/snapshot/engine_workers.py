"""Private persistent JVM lanes with bounded, portable framed pipe transport.

No client token/source text is included in diagnostics. All process reservations
survive stop signals until wait() acknowledges exit. There is no one-shot retry.
"""
from dataclasses import dataclass
import hashlib
import itertools
import json
import math
import os
from pathlib import Path
import queue
import shutil
import struct
import subprocess
import tempfile
import threading
import time

from runtime_dependencies import (PROCESS_BUDGET, clean_java_environment,
                                  engine_temp_root, runtime_classpath)
from traffic_limits import validated_int, validated_seconds

PROTOCOL = 1
REQUEST_BYTES = 1_048_576
RESPONSE_BYTES = 4_194_304
# Request completion cannot wait for the full shutdown drain. If the OS has
# not confirmed exit within this allowance, ownership remains retained.
REQUEST_REAP_SECONDS = 0.25
_IDS = itertools.count(1)
_TICKETS = itertools.count(1)


class EngineUnavailable(RuntimeError):
    def __init__(self, message='The analysis worker is unavailable.'):
        super().__init__(message)


class EngineTimeout(EngineUnavailable):
    def __init__(self):
        super().__init__('The analysis worker exceeded its deadline.')


class EngineAcquisitionTimeout(EngineTimeout):
    """The bounded capacity/startup phase expired before analysis dispatch."""


def _pairs(values):
    result = {}
    for name, value in values:
        if name in result:
            raise ValueError('Duplicate frame field')
        result[name] = value
    return result


def _decode(data):
    return json.loads(data.decode('utf-8'), object_pairs_hook=_pairs,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError('Nonfinite JSON')))


def _encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(',', ':'), sort_keys=True,
                      allow_nan=False).encode('utf-8')


class _Worker:
    def __init__(self, pool, lane):
        self.pool, self.lane = pool, lane
        self.incarnation = str(next(_IDS))
        self.process = None
        self.directory = None
        self.owner = None
        self.lock = threading.Lock()
        self.stop_lock = threading.Lock()
        self.failed = False
        self.expected = ('ready', 0, '')
        self.incoming = queue.Queue(maxsize=1)
        self.outgoing = queue.Queue(maxsize=1)
        self.closed = threading.Event()
        self.threads = []
        self.started = time.monotonic()
        self.tasks = self.parses = 0
        # Lane reservation: starting -> live -> unreaped -> reaped (AP01-C03).
        self.reservation = 'starting'
        self.startup_reaped = None
        self.startup_abandoned = False

    def launch(self, deadline):
        """Start and handshake. On failure the caller owns the startup-failure event;
        startup_reaped records whether stop() confirmed the child was reaped."""
        pool, lane = self.pool, self.lane
        try:
            self.owner = pool.budget.reserve(lane, max(0, deadline - time.monotonic()))
            with pool.condition:
                if pool.stopped:
                    self.startup_abandoned = True
                    raise EngineUnavailable()
            self.directory = Path(tempfile.mkdtemp(prefix='worker-', dir=engine_temp_root(pool.root)))
            command = pool._command(lane, self.incarnation, self.directory)
            if time.monotonic() >= deadline:
                self.startup_abandoned = True
                raise EngineTimeout()
            self.process = subprocess.Popen(command, cwd=self.directory,
                                            env=clean_java_environment(), stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
            pool._launched()
            for name, target in (('read', self._read), ('write', self._write), ('stderr', self._drain)):
                thread = threading.Thread(target=target, name='alloy-' + name + '-' + self.incarnation,
                                          daemon=True)
                thread.start()
                # A failed Thread.start() has no joinable thread. Retaining it
                # would make stop() raise before releasing a reaped child.
                self.threads.append(thread)
            ready = self._receive(min(deadline, self.started + pool.startup_timeout))
            if ready != {'status': 'ready'}:
                raise EngineUnavailable()
        except subprocess.TimeoutExpired:
            # Waiting for another process owner is not evidence of a broken
            # JVM/runtime. The attempted lane start remains token-charged.
            self.startup_abandoned = self.process is None and self.owner is None
            self.startup_reaped = self.stop(timeout=REQUEST_REAP_SECONDS)
            raise EngineTimeout() from None
        except BaseException:
            self.startup_reaped = self.stop(timeout=REQUEST_REAP_SECONDS)
            raise

    def _fail(self):
        with self.lock:
            self.failed = True
            self.expected = None
        # Record loss of a published worker immediately. Otherwise an idle
        # child that exits keeps appearing ready until the next learner edit.
        # This only retains ownership; it does not kill, reap, or release it.
        self.pool._worker_failed(self)
        try:
            self.incoming.put_nowait(EngineUnavailable())
        except queue.Full:
            pass

    def _read_exact(self, size):
        parts = bytearray()
        while len(parts) < size and not self.closed.is_set():
            data = self.process.stdout.read(min(65536, size - len(parts)))
            if not data:
                raise EOFError()
            parts.extend(data)
        if len(parts) != size:
            raise EOFError()
        return parts

    def _read(self):
        try:
            while not self.closed.is_set():
                size = struct.unpack('>I', self._read_exact(4))[0]
                if size < 2 or size > RESPONSE_BYTES:
                    raise ValueError()
                frame = _decode(self._read_exact(size))
                with self.lock:
                    expected = self.expected
                    if not isinstance(frame, dict) or expected is None:
                        raise ValueError()
                    kind, ticket, context = expected
                    fields = {'protocol', 'incarnation', 'ticket', 'context', 'kind', 'result'}
                    if kind != 'ready':
                        fields.add('parseUnits')
                    if (set(frame) != fields or type(frame['protocol']) is not int
                            or frame['protocol'] != PROTOCOL or frame['incarnation'] != self.incarnation
                            or type(frame['ticket']) is not int or frame['ticket'] != ticket
                            or frame['context'] != context or frame['kind'] != kind
                            or not isinstance(frame['result'], dict)):
                        raise ValueError()
                    if kind != 'ready':
                        count = frame['parseUnits']
                        if type(count) is not int or not 0 <= count <= 4098:
                            raise ValueError()
                        self.parses += count
                    self.expected = None
                self.incoming.put_nowait(frame['result'])
        except BaseException:
            # No exception text from private output reaches a log or caller.
            if not self.closed.is_set():
                self._fail()

    def _write(self):
        try:
            while not self.closed.is_set():
                data = self.outgoing.get()
                if data is None:
                    return
                view = memoryview(data)
                while view:
                    written = self.process.stdin.write(view[:65536])
                    if not written:
                        raise BrokenPipeError()
                    view = view[written:]
                self.process.stdin.flush()
        except BaseException:
            if not self.closed.is_set():
                self._fail()

    def _drain(self):
        # Fixed chunks, zero retained diagnostics: private expressions cannot
        # accumulate in memory or be copied into a response/log.
        try:
            while not self.closed.is_set() and self.process.stderr.read(4096):
                pass
        except OSError:
            pass

    def _receive(self, deadline):
        try:
            result = self.incoming.get(timeout=max(0, deadline - time.monotonic()))
        except queue.Empty:
            raise EngineTimeout() from None
        if isinstance(result, Exception) or self.failed:
            raise EngineUnavailable()
        return result

    def evaluate(self, request, deadline):
        ticket = next(_TICKETS)
        context = hashlib.sha256(_encoded(request)).hexdigest()
        frame = {'protocol': PROTOCOL, 'incarnation': self.incarnation, 'ticket': ticket,
                 'context': context, 'kind': self.lane, 'request': request}
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise EngineTimeout()
        if self.lane == 'feedback':
            # Private transport policy, not learner input or part of the exact
            # public request/cache identity. Leave one third of the hard
            # deadline for publication/transport and unmetered parser work.
            work_millis = min(60_000, int(remaining * 1000 * 2 / 3))
            if work_millis < 1:
                raise EngineTimeout()
            frame['workMillis'] = work_millis
        data = _encoded(frame)
        if len(data) > REQUEST_BYTES:
            raise EngineUnavailable('The analysis request exceeds its size limit.')
        if time.monotonic() >= deadline:
            raise EngineTimeout()
        with self.lock:
            if self.failed or self.expected is not None or self.process.poll() is not None:
                raise EngineUnavailable()
            self.expected = (self.lane, ticket, context)
        try:
            self.outgoing.put_nowait(struct.pack('>I', len(data)) + data)
        except queue.Full:
            raise EngineUnavailable() from None
        result = self._receive(deadline)
        self.tasks += 1
        return result

    def expired(self):
        return (self.failed or self.process.poll() is not None or self.tasks >= self.pool.max_tasks
                or self.parses >= self.pool.max_parse_units
                or time.monotonic() - self.started >= self.pool.max_age_seconds)

    def stop(self, timeout=5):
        deadline = time.monotonic() + max(0, timeout)
        if not self.stop_lock.acquire(timeout=max(0, deadline - time.monotonic())):
            return False
        try:
            return self._stop_locked(deadline)
        finally:
            self.stop_lock.release()

    def _stop_locked(self, deadline):
        self.closed.set()
        try:
            self.outgoing.put_nowait(None)
        except queue.Full:
            pass
        if self.process is not None:
            try:
                if self.process.poll() is None:
                    self.process.kill()
                self.process.wait(timeout=max(0, deadline - time.monotonic()))
            except (OSError, subprocess.TimeoutExpired):
                # Retain the permit and scratch while the OS has not reaped it.
                return False
            for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                try:
                    stream.close()
                except OSError:
                    pass
        for thread in self.threads:
            thread.join(timeout=min(0.2, max(0, deadline - time.monotonic())))
        if self.directory is not None:
            shutil.rmtree(self.directory, ignore_errors=True)
        if self.owner is not None:
            self.pool.budget.release_reaped(self.owner)
            self.owner = None
        return True


@dataclass(frozen=True)
class LaneLimits:
    """Lean Work.Limits for one lane: capacity, launch tokens per epoch,
    startup-failure circuit threshold and renewal period in seconds."""
    capacity: int
    starts_per_epoch: int
    failed_start_limit: int
    period: int

    def __post_init__(self):
        validated_int(self.capacity, maximum=2)
        validated_int(self.starts_per_epoch, maximum=12)
        validated_int(self.failed_start_limit, maximum=12)
        validated_int(self.period, maximum=60)


class LanePolicy:
    """AP01 lane accounting plus pre-spawn abandonment; callers hold the condition.

    Every launch attempt consumes an epoch token. Only a failed startup advances
    the failure circuit; request timeouts and planned retirements move a live
    worker to unreaped without touching it. Starting, live and unreaped workers
    all reserve capacity until reap is confirmed. Renewal happens only when
    monotonic time reaches the epoch boundary.
    """

    def __init__(self, limits, now):
        self.limits = limits
        self.live = self.starting = self.unreaped = 0
        self.tokens = limits.starts_per_epoch
        self.attempts = self.failed_starts = 0
        self.origin = now
        self.now = 0.0
        self.renewal = float(limits.period)

    def reserved(self):
        return self.live + self.starting + self.unreaped

    def circuit_open(self):
        return self.failed_starts >= self.limits.failed_start_limit

    def start(self):
        if self.tokens > 0 and not self.circuit_open() and self.reserved() < self.limits.capacity:
            self.starting += 1
            self.tokens -= 1
            self.attempts += 1
            return True
        return False

    def ready(self):
        if self.starting > 0:
            self.starting -= 1
            self.live += 1

    def startup_failure(self):
        if self.starting > 0:
            self.starting -= 1
            self.unreaped += 1
            self.failed_starts += 1

    def abandon_start(self):
        """Capacity wait/shutdown before spawn does not diagnose startup failure.

        Keep the attempt charged and its reservation until cleanup confirms it.
        This cancellation transition is an implementation refinement obligation;
        the recorded AP01 Lean model is not silently changed.
        """
        if self.starting > 0:
            self.starting -= 1
            self.unreaped += 1

    def retire(self):
        """Both plannedRetirement and requestTimeout: the reservation is retained."""
        if self.live > 0:
            self.live -= 1
            self.unreaped += 1

    def reaped(self):
        if self.unreaped > 0:
            self.unreaped -= 1

    def tick(self, monotonic_now):
        self.now = max(self.now, monotonic_now - self.origin)

    def renew(self):
        if self.renewal <= self.now:
            self.tokens = self.limits.starts_per_epoch
            self.attempts = self.failed_starts = 0
            self.renewal = self.now + self.limits.period

    def retry_after(self, monotonic_now):
        """Whole seconds until renewal while launches are refused; read-only."""
        if self.tokens > 0 and not self.circuit_open():
            return 0
        remaining = self.renewal - max(self.now, monotonic_now - self.origin)
        return max(0, min(self.limits.period, math.ceil(remaining)))


def default_lane_limits(feedback_workers, behavior_workers):
    return {'feedback': LaneLimits(feedback_workers, 12, 3, 60),
            'behavior': LaneLimits(behavior_workers, 6, 3, 60)}


class EnginePool:
    """Bounded worker lanes with separate acquisition and execution deadlines."""
    def __init__(self, root, java, feedback_workers=2, behavior_workers=1, *,
                 max_tasks=512, max_parse_units=1_000_000, max_age_seconds=1800,
                 startup_timeout=10, java_processors=2, budget=None, lane_limits=None, clock=time.monotonic):
        feedback_workers = validated_int(feedback_workers, maximum=2)
        behavior_workers = validated_int(behavior_workers, maximum=1)
        max_tasks = validated_int(max_tasks)
        max_parse_units = validated_int(max_parse_units)
        max_age_seconds = validated_seconds(max_age_seconds)
        startup_timeout = validated_seconds(startup_timeout)
        java_processors = validated_int(java_processors, maximum=2)
        limits = {'feedback': feedback_workers, 'behavior': behavior_workers}
        lane_limits = default_lane_limits(feedback_workers, behavior_workers) if lane_limits is None else lane_limits
        if (type(lane_limits) is not dict or set(lane_limits) != set(limits)
                or any(type(lane_limits[lane]) is not LaneLimits
                       or lane_limits[lane].capacity != limits[lane] for lane in limits)):
            raise ValueError('Lane limits must cover both lanes with matching capacity')
        self.root, self.java = Path(root).resolve(), str(java)
        self.limits = limits
        self.max_tasks, self.max_parse_units = max_tasks, max_parse_units
        self.max_age_seconds, self.startup_timeout = max_age_seconds, startup_timeout
        self.java_processors = java_processors
        self.budget = PROCESS_BUDGET if budget is None else budget
        self.clock = clock
        self.condition = threading.Condition()
        self.workers = {'feedback': [], 'behavior': []}
        self.busy = set()
        self.starting = {'feedback': 0, 'behavior': 0}
        self.stopped = False
        self.launches = self.completed = self.failures = self.recycled = 0
        now = clock()
        self.policies = {lane: LanePolicy(lane_limits[lane], now) for lane in self.limits}
        self.unreaped = []

    def _command(self, lane, incarnation, directory):
        return [self.java, '-Djava.io.tmpdir=' + str(directory), '-Dfile.encoding=UTF-8',
                '-Xmx256m', '-XX:MaxDirectMemorySize=64m', '-XX:ActiveProcessorCount=' + str(self.java_processors),
                '-cp', runtime_classpath(self.root), 'live.EngineWorker', incarnation, lane]

    def _launched(self):
        with self.condition:
            self.launches += 1

    def _retain_locked(self, worker):
        """Move a published worker to retained ownership under the condition."""
        if worker in self.workers[worker.lane]:
            self.workers[worker.lane].remove(worker)
        if worker.reservation == 'live':
            self.policies[worker.lane].retire()
            worker.reservation = 'unreaped'
        if worker.reservation == 'unreaped' and worker not in self.unreaped:
            self.unreaped.append(worker)
        self.busy.discard(worker)

    def _worker_failed(self, worker):
        with self.condition:
            # A constructor classifies and retains its own failed startup.
            if worker.reservation == 'live':
                self._retain_locked(worker)
            self.condition.notify_all()

    def _retire(self, worker, timeout=5):
        """Live -> unreaped, then free the reservation only after a confirmed reap."""
        with self.condition:
            self._retain_locked(worker)
        reaped = worker.stop(timeout=timeout)
        with self.condition:
            if reaped:
                if worker in self.unreaped:
                    self.unreaped.remove(worker)
                if worker.reservation == 'unreaped':
                    self.policies[worker.lane].reaped()
                    worker.reservation = 'reaped'
            # Retention was recorded before stop(). A concurrent successful
            # reap may already have removed it; a stale failed stop must not
            # resurrect that reservation or add a reaped worker to the list.
            self.condition.notify_all()

    def _reap_retained(self, lane):
        """Retry confirmation for retained children without waiting; never assume success."""
        with self.condition:
            retained = [worker for worker in self.unreaped if worker.lane == lane]
        for worker in retained:
            self._retire(worker, timeout=0)

    def _acquire(self, lane, deadline):
        retried_reap = False
        while True:
            retired = None
            with self.condition:
                if time.monotonic() >= deadline:
                    raise EngineTimeout()
                if self.stopped:
                    raise EngineUnavailable()
                for worker in self.workers[lane]:
                    if worker not in self.busy:
                        self.busy.add(worker)
                        if worker.expired():
                            retired = worker
                            self.recycled += 1
                            break
                        return worker
                if retired is None:
                    policy = self.policies[lane]
                    policy.tick(self.clock())
                    policy.renew()
                    if policy.start():
                        self.starting[lane] += 1
                        break
                    blocked_by_capacity = policy.reserved() >= policy.limits.capacity
                    retained = policy.unreaped > 0
                    if not blocked_by_capacity:
                        if policy.circuit_open():
                            raise EngineUnavailable('The analysis worker failed to start repeatedly.')
                        raise EngineUnavailable('The analysis worker restart limit was reached.')
                    if not (retained and not retried_reap):
                        remaining = deadline - time.monotonic()
                        if remaining <= 0:
                            raise EngineTimeout()
                        # A nonblocking wait may run just before the killed
                        # child exits. Closed transport threads will not notify
                        # us then, so retry reap confirmation at a bounded rate
                        # while this caller still has a request deadline.
                        self.condition.wait(min(remaining, .05) if retained else remaining)
                        retried_reap = False
                        continue
            if retired is not None:
                self._retire(retired, timeout=min(REQUEST_REAP_SECONDS, max(0, deadline - time.monotonic())))
            else:
                retried_reap = True
                self._reap_retained(lane)
        worker = None
        try:
            # Initialization itself can fail (for example, thread/queue
            # allocation). It still owns the charged starting reservation.
            worker = _Worker(self, lane)
            worker.launch(deadline)
        except BaseException as error:
            with self.condition:
                if worker is not None and worker.startup_abandoned:
                    self.policies[lane].abandon_start()
                else:
                    self.policies[lane].startup_failure()
                if worker is None:
                    # No Worker was returned, so neither a child nor a process
                    # permit could have been allocated by launch().
                    self.policies[lane].reaped()
                else:
                    worker.reservation = 'unreaped'
                    if worker.startup_reaped:
                        self.policies[lane].reaped()
                        worker.reservation = 'reaped'
                    elif worker not in self.unreaped:
                        self.unreaped.append(worker)
            if isinstance(error, (OSError, ValueError, subprocess.TimeoutExpired)):
                raise EngineUnavailable() from None
            raise
        else:
            with self.condition:
                self.policies[lane].ready()
                worker.reservation = 'live'
                published = not self.stopped
                if published:
                    self.workers[lane].append(worker)
                    self.busy.add(worker)
                    # The reader can fail after the ready frame was delivered
                    # but before publication. Keep that known failure out of
                    # ready/busy snapshots too; it is a post-handshake failure.
                    if worker.failed:
                        self._retain_locked(worker)
                        published = False
            if not published:
                # Stopped pools and known-failed workers cannot publish; the
                # constructor still owns confirmation of the child's reap.
                self._retire(worker, timeout=REQUEST_REAP_SECONDS)
                raise EngineUnavailable()
            return worker
        finally:
            with self.condition:
                self.starting[lane] -= 1
                self.condition.notify_all()

    def evaluate(self, kind, request, timeout):
        if kind not in self.limits or not isinstance(request, dict):
            raise ValueError('Invalid analysis kind or request')
        timeout = validated_seconds(timeout)
        # Validate and bound serialized input before reserving a process.
        try:
            encoded = _encoded(request)
            if len(encoded) > REQUEST_BYTES - 256:
                raise ValueError()
            # Detach mutable caller containers before identity or transport use.
            request = _decode(encoded)
        except (TypeError, ValueError, UnicodeError, RecursionError):
            raise EngineUnavailable('The analysis request exceeds its encoding limits.') from None
        # Queue waiting is bounded by Scheduler. Pool acquisition/cold startup
        # has its own fixed allowance, so it does not consume the analysis time
        # of an otherwise identical warm request.
        try:
            worker = self._acquire(kind, time.monotonic() + self.startup_timeout)
        except EngineTimeout:
            raise EngineAcquisitionTimeout() from None
        deadline = time.monotonic() + timeout
        try:
            result = worker.evaluate(request, deadline)
            with self.condition:
                self.completed += 1
            return result
        except BaseException:
            with self.condition:
                self.failures += 1
            self._retire(worker, timeout=REQUEST_REAP_SECONDS)
            raise
        finally:
            with self.condition:
                self.busy.discard(worker)
                self.condition.notify_all()

    def evaluation_allowance(self, timeout):
        """Scheduler wait allowance, excluding its own queue/transport margins."""
        return self.startup_timeout + validated_seconds(timeout) + REQUEST_REAP_SECONDS

    def prewarm(self):
        held = []
        try:
            for lane, count in self.limits.items():
                for _ in range(count):
                    held.append(self._acquire(lane, time.monotonic() + self.startup_timeout))
        finally:
            with self.condition:
                for worker in held:
                    self.busy.discard(worker)
                self.condition.notify_all()
        return self.stats()

    def _lane_view(self, lane, now):
        """Caller holds the condition. A read-only projection of one lane."""
        policy = self.policies[lane]
        busy = sum(worker in self.busy for worker in self.workers[lane])
        return {'ready': len(self.workers[lane]) - busy, 'busy': busy,
                'starting': policy.starting, 'unreaped': policy.unreaped,
                'launchCredits': policy.tokens, 'startupCircuitOpen': policy.circuit_open(),
                'retryAfterSeconds': policy.retry_after(now)}

    def stats(self):
        with self.condition:
            now = self.clock()
            return {'launches': self.launches, 'completed': self.completed,
                    'failures': self.failures, 'recycled': self.recycled,
                    'workers': {lane: len(workers) for lane, workers in self.workers.items()},
                    'busy': len(self.busy), 'starting': sum(self.starting.values()),
                    'unreaped': len(self.unreaped), 'closed': self.stopped,
                    'lanes': {lane: self._lane_view(lane, now) for lane in self.limits},
                    'processBudget': self.budget.stats()}

    def diagnostics(self):
        """One atomic sample under the pool condition. Never spawns, reaps or renews."""
        with self.condition:
            now = self.clock()
            return {'stopping': self.stopped,
                    'lanes': {lane: self._lane_view(lane, now) for lane in self.limits}}

    def close(self, timeout=15):
        """Drain constructors as well as published workers within one budget.

        A constructor owns its starting count through handshake failure and
        reaping. It cannot publish after stopped becomes true. An expired drain
        leaves starting/unreaped visible; callers must not acknowledge clean exit.
        """
        timeout = validated_seconds(timeout, minimum_zero=True)
        deadline = time.monotonic() + timeout
        with self.condition:
            self.stopped = True
            workers = [worker for lane in self.workers.values() for worker in lane]
            workers += list(self.unreaped)
            self.condition.notify_all()
        for worker in workers:
            self._retire(worker, timeout=min(5, max(0, deadline - time.monotonic())))
        with self.condition:
            while any(self.starting.values()):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.condition.wait(remaining)
            # Constructors may have added a failed reap while the drain waited.
            unreaped = list(self.unreaped)
        for worker in unreaped:
            self._retire(worker, timeout=min(5, max(0, deadline - time.monotonic())))
        return self.stats()
