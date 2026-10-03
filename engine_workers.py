"""Private persistent JVM lanes with bounded, portable framed pipe transport.

No client token/source text is included in diagnostics. All process reservations
survive stop signals until wait() acknowledges exit. There is no one-shot retry.
"""
from collections import deque
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

PROTOCOL = 1
REQUEST_BYTES = 1_048_576
RESPONSE_BYTES = 4_194_304
_IDS = itertools.count(1)
_TICKETS = itertools.count(1)


class EngineUnavailable(RuntimeError):
    def __init__(self, message='The analysis worker is unavailable.'):
        super().__init__(message)


class EngineTimeout(EngineUnavailable):
    def __init__(self):
        super().__init__('The analysis worker exceeded its deadline.')


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
    def __init__(self, pool, lane, deadline):
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
        try:
            self.owner = pool.budget.reserve(lane, max(0, deadline - time.monotonic()))
            self.directory = Path(tempfile.mkdtemp(prefix='worker-', dir=engine_temp_root(pool.root)))
            command = pool._command(lane, self.incarnation, self.directory)
            if time.monotonic() >= deadline:
                raise EngineTimeout()
            self.process = subprocess.Popen(command, cwd=self.directory,
                                            env=clean_java_environment(), stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, bufsize=0)
            pool._launched()
            for name, target in (('read', self._read), ('write', self._write), ('stderr', self._drain)):
                thread = threading.Thread(target=target, name='alloy-' + name + '-' + self.incarnation,
                                          daemon=True)
                self.threads.append(thread)
                thread.start()
            ready = self._receive(min(deadline, self.started + pool.startup_timeout))
            if ready != {'status': 'ready'}:
                raise EngineUnavailable()
        except subprocess.TimeoutExpired:
            self.stop()
            raise EngineTimeout() from None
        except BaseException:
            if not self.stop():
                with pool.condition:
                    pool.unreaped.append(self)
            raise

    def _fail(self):
        with self.lock:
            self.failed = True
            self.expected = None
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

    def stop(self):
        with self.stop_lock:
            return self._stop_locked()

    def _stop_locked(self):
        self.closed.set()
        try:
            self.outgoing.put_nowait(None)
        except queue.Full:
            pass
        if self.process is not None:
            try:
                if self.process.poll() is None:
                    self.process.kill()
                self.process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                # Retain the permit and scratch while the OS has not reaped it.
                return False
            for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
                try:
                    stream.close()
                except OSError:
                    pass
        for thread in self.threads:
            thread.join(timeout=0.2)
        if self.directory is not None:
            shutil.rmtree(self.directory, ignore_errors=True)
        if self.owner is not None:
            self.pool.budget.release_reaped(self.owner)
            self.owner = None
        return True


class EnginePool:
    """Bounded sequential workers per lane; waiting callers retain their deadline."""
    def __init__(self, root, java, feedback_workers=2, behavior_workers=1, *,
                 max_tasks=512, max_parse_units=1_000_000, max_age_seconds=1800,
                 startup_timeout=10, budget=None):
        if type(feedback_workers) is not int or not 1 <= feedback_workers <= 2:
            raise ValueError('Feedback worker count must be between one and two')
        if type(behavior_workers) is not int or behavior_workers != 1:
            raise ValueError('One behavior worker is required')
        if max_tasks < 1 or max_parse_units < 1 or max_age_seconds <= 0:
            raise ValueError('Worker budgets must be positive')
        self.root, self.java = Path(root).resolve(), str(java)
        self.limits = {'feedback': feedback_workers, 'behavior': behavior_workers}
        self.max_tasks, self.max_parse_units = max_tasks, max_parse_units
        self.max_age_seconds, self.startup_timeout = max_age_seconds, startup_timeout
        self.budget = PROCESS_BUDGET if budget is None else budget
        self.condition = threading.Condition()
        self.workers = {'feedback': [], 'behavior': []}
        self.busy = set()
        self.starting = {'feedback': 0, 'behavior': 0}
        self.stopped = False
        self.launches = self.completed = self.failures = self.recycled = 0
        self.starts = deque(maxlen=12)
        self.unreaped = []

    def _command(self, lane, incarnation, directory):
        return [self.java, '-Djava.io.tmpdir=' + str(directory), '-Dfile.encoding=UTF-8',
                '-Xmx256m', '-XX:MaxDirectMemorySize=64m', '-XX:ActiveProcessorCount=2',
                '-cp', runtime_classpath(self.root), 'live.EngineWorker', incarnation, lane]

    def _launched(self):
        with self.condition:
            self.launches += 1

    def _retire(self, worker):
        reaped = worker.stop()
        with self.condition:
            if worker in self.workers[worker.lane]:
                self.workers[worker.lane].remove(worker)
            self.busy.discard(worker)
            if not reaped and worker not in self.unreaped:
                self.unreaped.append(worker)
            elif reaped and worker in self.unreaped:
                self.unreaped.remove(worker)
            self.condition.notify_all()

    def _acquire(self, lane, deadline):
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
                    if len(self.workers[lane]) + self.starting[lane] < self.limits[lane]:
                        now = time.monotonic()
                        while self.starts and now - self.starts[0] >= 60:
                            self.starts.popleft()
                        if len(self.starts) >= 12:
                            raise EngineUnavailable('The analysis worker restart limit was reached.')
                        self.starts.append(now)
                        self.starting[lane] += 1
                        break
                    remaining = deadline - time.monotonic()
                    if remaining <= 0:
                        raise EngineTimeout()
                    self.condition.wait(remaining)
            if retired is not None:
                self._retire(retired)
        worker = None
        try:
            worker = _Worker(self, lane, deadline)
            with self.condition:
                if self.stopped:
                    raise EngineUnavailable()
                self.workers[lane].append(worker)
                self.busy.add(worker)
            return worker
        except BaseException as error:
            if worker is not None:
                self._retire(worker)
            if isinstance(error, (OSError, ValueError, subprocess.TimeoutExpired)):
                raise EngineUnavailable() from None
            raise
        finally:
            with self.condition:
                self.starting[lane] -= 1
                self.condition.notify_all()

    def evaluate(self, kind, request, timeout):
        if kind not in self.limits or not isinstance(request, dict):
            raise ValueError('Invalid analysis kind or request')
        if not isinstance(timeout, (int, float)) or not math.isfinite(timeout) or timeout <= 0:
            raise ValueError('A positive finite deadline is required')
        deadline = time.monotonic() + timeout
        # Validate and bound serialized input before reserving a process.
        try:
            encoded = _encoded(request)
            if len(encoded) > REQUEST_BYTES - 256:
                raise ValueError()
            # Detach mutable caller containers before identity or transport use.
            request = _decode(encoded)
        except (TypeError, ValueError, UnicodeError, RecursionError):
            raise EngineUnavailable('The analysis request exceeds its encoding limits.') from None
        worker = self._acquire(kind, deadline)
        try:
            result = worker.evaluate(request, deadline)
            with self.condition:
                self.completed += 1
            return result
        except BaseException:
            with self.condition:
                self.failures += 1
            self._retire(worker)
            raise
        finally:
            with self.condition:
                self.busy.discard(worker)
                self.condition.notify_all()

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

    def stats(self):
        with self.condition:
            return {'launches': self.launches, 'completed': self.completed,
                    'failures': self.failures, 'recycled': self.recycled,
                    'workers': {lane: len(workers) for lane, workers in self.workers.items()},
                    'busy': len(self.busy), 'starting': sum(self.starting.values()),
                    'unreaped': len(self.unreaped), 'closed': self.stopped,
                    'processBudget': self.budget.stats()}

    def close(self):
        with self.condition:
            self.stopped = True
            workers = [worker for lane in self.workers.values() for worker in lane]
            workers += list(self.unreaped)
            self.condition.notify_all()
        for worker in workers:
            self._retire(worker)
        return self.stats()
