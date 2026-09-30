"""Benchmark transport around the unmodified production LiveFeedback.evaluate.

Each worker is sequential. The JVM remains warm between requests; there is no
result/reference cache inside it. Corpus/train-pool construction is offline.
"""
from __future__ import annotations
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[3]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from runtime_dependencies import clean_java_environment


def scratch_root():
    root = Path(os.environ.get('ALLOY_BENCHMARK_TMP_ROOT', ROOT / 'build/benchmarks/tmp')).resolve()
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    return root


class Worker:
    def __init__(self, timeout=60.0, heap='512m', command=None, recycle_after=256):
        if type(recycle_after) is not int or recycle_after < 1:
            raise ValueError('recycle_after must be a positive integer')
        self.timeout = timeout
        self.heap = heap
        self.command = command
        self.process = None
        self.scratch = None
        self.requests = 0
        self.recycle_after = recycle_after

    def close(self, *, graceful=True):
        try:
            if self.process is not None:
                if graceful and self.process.poll() is None:
                    try:
                        self.process.stdin.close()
                        self.process.wait(timeout=1)
                    except (OSError, subprocess.TimeoutExpired):
                        pass
                if self.process.poll() is None:
                    try:
                        os.killpg(self.process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                self.process.wait()
                self.process.stdin.close()
                self.process.stdout.close()
        finally:
            self.process = None
            self.requests = 0
            if self.scratch is not None:
                self.scratch.cleanup()
                self.scratch = None

    def request(self, payload):
        start = time.perf_counter()
        if self.process is not None and self.requests >= self.recycle_after:
            self.close()
        cold = self.process is None
        if cold:
            classpath = os.pathsep.join(str(ROOT / p) for p in
                ('build/benchmarks/alloy4fun/classes', 'build/engine/classes', 'vendor/acgn/lib/*'))
            command = self.command or ['java', '-Xmx' + self.heap,
                '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-cp', classpath,
                'benchmark.LiveWorker']
            self.scratch = tempfile.TemporaryDirectory(prefix='worker-', dir=scratch_root())
            command = [command[0], '-Djava.io.tmpdir=' + self.scratch.name, *command[1:]]
            try:
                self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL, start_new_session=True, env=clean_java_environment())
            except BaseException:
                self.close(graceful=False)
                raise
        try:
            outgoing = (json.dumps(payload, ensure_ascii=True) + '\n').encode()
            sent = 0
            incoming = bytearray()
            input_fd, output_fd = self.process.stdin.fileno(), self.process.stdout.fileno()
            os.set_blocking(input_fd, False)
            os.set_blocking(output_fd, False)
            with selectors.DefaultSelector() as selector:
                selector.register(input_fd, selectors.EVENT_WRITE)
                selector.register(output_fd, selectors.EVENT_READ)
                while b'\n' not in incoming:
                    remaining = self.timeout - (time.perf_counter() - start)
                    if remaining <= 0:
                        self.close(graceful=False)
                        return {'status': 'timeout'}, time.perf_counter() - start, cold
                    events = selector.select(remaining)
                    if not events:
                        self.close(graceful=False)
                        return {'status': 'timeout'}, time.perf_counter() - start, cold
                    for key, _ in events:
                        try:
                            if key.fd == input_fd:
                                sent += os.write(input_fd, outgoing[sent:sent + 65536])
                                if sent == len(outgoing):
                                    selector.unregister(input_fd)
                            else:
                                chunk = os.read(output_fd, 65536)
                                if not chunk:
                                    raise ValueError('Worker ended before a complete response')
                                incoming.extend(chunk)
                                if len(incoming) > 32 * 1024 * 1024:
                                    raise ValueError('Worker response exceeds the benchmark safety limit')
                        except BlockingIOError:
                            continue
            if sent != len(outgoing) or incoming.count(b'\n') != 1 or not incoming.endswith(b'\n'):
                raise ValueError('Unexpected response framing')
            result = json.loads(incoming)
            elapsed = time.perf_counter() - start
            if elapsed > self.timeout:
                self.close(graceful=False)
                return {'status': 'timeout'}, elapsed, cold
            self.requests += 1
            return result, elapsed, cold
        except (BrokenPipeError, OSError, ValueError):
            self.close(graceful=False)
            return {'status': 'worker_error'}, time.perf_counter() - start, cold


def metrics(response, elapsed, *, cold=False):
    operations = response.get('operations', [])
    atomic = [op for op in operations if not op.get('aggregate', False)]
    status = response.get('status', 'invalid_response')
    return {
        'tool': 'live-canonical', 'status': status, 'hint_available': status == 'ok' and bool(atomic),
        'supported': status not in ('invalid', 'invalid_request', 'unsupported'),
        'timed_out': status == 'timeout', 'wall_seconds': elapsed,
        'engine_seconds': response.get('benchmark_engine_seconds'),
        'distance': response.get('distance'), 'operation_count': len(atomic),
        'located_operations': sum(op.get('sourceLocation', {}).get('status') == 'located'
                                  and op.get('sourceLocation', {}).get('precision') == 'node' for op in atomic),
        'canonical_located_operations': sum(op.get('canonicalLocation', {}).get('status') == 'located'
                                            and op.get('canonicalLocation', {}).get('precision') == 'node' for op in atomic),
        'aggregate_operation_count': len(operations) - len(atomic),
        'matrix_replay_verified': response.get('trace', {}).get('matrixReplayVerified'),
        'cold_worker': cold,
    }
