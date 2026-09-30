"""Manage the baseline-only JVM; preserve authoritative upstream algorithms."""
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

def build_classpath(baselines: Path, *, compile_adapter=True) -> str:
    api = baselines / 'Alloy4Fun-FM24/api/lib'
    jars = [api / 'org/alloytools/Alloy/6.1.0/Alloy-6.1.0.jar',
            api / 'pt/haslab/specassistant/1.0.0/specassistant-1.0.0.jar',
            baselines / 'SpecAssistant/lib/pt/haslab/TAR/1.1.1/TAR-1.1.1.jar',
            api / 'org/higena/higena/1.0.0/higena-1.0.0.jar',
            api / 'Apted/apted/0.1.1/apted-0.1.1.jar',
            ROOT / 'vendor/acgn/lib/json-java.jar',
            *sorted((baselines / 'fm24-dependencies').glob('*.jar'))]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError('Missing FM24 dependency: ' + str(jar))
    output = ROOT / 'build/benchmarks/fm24-classes'
    output.mkdir(parents=True, exist_ok=True)
    cp = os.pathsep.join(map(str, jars))
    source = Path(__file__).with_name('FM24Native.java')
    compiled = output / 'FM24Native.class'
    if not compiled.is_file() or compiled.stat().st_mtime_ns < source.stat().st_mtime_ns:
        if not compile_adapter:
            raise RuntimeError('FM24 adapter must be compiled during preparation before measurement')
        subprocess.run(['javac', '-cp', cp, '-d', str(output), str(source)],
                       check=True, env=clean_java_environment())
    return str(output) + os.pathsep + cp

class Native:
    def __init__(self, classpath: str, heap='512m', *, scratch_root=None, recycle_after=256):
        if type(recycle_after) is not int or recycle_after < 1:
            raise ValueError('recycle_after must be a positive integer')
        self.classpath, self.heap = classpath, heap
        self.scratch_root = Path(scratch_root or os.environ.get('ALLOY_BENCHMARK_TMP_ROOT')
                                 or ROOT / 'build/benchmarks/tmp').resolve()
        self.recycle_after = recycle_after
        self.scratch = None
        self.process = None
        self.completed_requests = 0
        self.start()

    def _command(self):
        return ['java', '-Djava.io.tmpdir=' + self.scratch.name,
                '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC', '-Xmx' + self.heap,
                '-cp', self.classpath, 'FM24Native']

    def start(self):
        if self.process is not None:
            raise RuntimeError('Native baseline is already running')
        self.scratch_root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.scratch = tempfile.TemporaryDirectory(prefix='fm24-', dir=self.scratch_root)
        try:
            self.process = subprocess.Popen(self._command(), stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                            start_new_session=True, env=clean_java_environment())
        except BaseException:
            self.scratch.cleanup()
            self.scratch = None
            raise
        self.completed_requests = 0

    def close(self):
        if self.process is not None:
            if self.process.poll() is None:
                try:
                    os.killpg(self.process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            self.process.wait()
            process = self.process
            self.process = None
            try:
                process.stdin.close()
                process.stdout.close()
            finally:
                self._cleanup_scratch()
        else:
            self._cleanup_scratch()

    def _cleanup_scratch(self):
        # Alloy's parser uses deleteOnExit, which a killed JVM cannot run.
        # The parent removes only this worker's directory, after process.wait().
        if self.scratch is not None:
            self.scratch.cleanup()
            self.scratch = None
        self.completed_requests = 0

    def ask(self, request: dict, timeout=60.0):
        start = time.perf_counter()
        try:
            if self.process is None:
                self.start()
            cold = self.completed_requests == 0
            outgoing = (json.dumps(request, ensure_ascii=True) + '\n').encode()
            sent = 0
            incoming = bytearray()
            input_fd, output_fd = self.process.stdin.fileno(), self.process.stdout.fileno()
            os.set_blocking(input_fd, False)
            os.set_blocking(output_fd, False)
            with selectors.DefaultSelector() as selector:
                selector.register(input_fd, selectors.EVENT_WRITE)
                selector.register(output_fd, selectors.EVENT_READ)
                while b'\n' not in incoming:
                    remaining = timeout - (time.perf_counter() - start)
                    if remaining <= 0:
                        raise TimeoutError('Native baseline deadline')
                    events = selector.select(remaining)
                    if not events:
                        raise TimeoutError('Native baseline deadline')
                    for key, _ in events:
                        try:
                            if key.fd == input_fd:
                                sent += os.write(input_fd, outgoing[sent:sent + 65536])
                                if sent == len(outgoing):
                                    selector.unregister(input_fd)
                            else:
                                chunk = os.read(output_fd, 65536)
                                if not chunk:
                                    raise RuntimeError('Native baseline exited')
                                incoming.extend(chunk)
                                if len(incoming) > 32 * 1024 * 1024:
                                    raise RuntimeError('Native response exceeds 32 MiB')
                        except BlockingIOError:
                            continue
            if sent != len(outgoing) or incoming.count(b'\n') != 1 or not incoming.endswith(b'\n'):
                raise RuntimeError('Unexpected native response framing')
            response = json.loads(incoming)
            if not isinstance(response, dict):
                raise RuntimeError('Native response must be a JSON object')
            self.completed_requests += 1
            response['native_cold_worker'] = cold
            response['native_request_number'] = self.completed_requests
            response['native_worker_recycled'] = self.completed_requests >= self.recycle_after
            if response['native_worker_recycled']:
                # Keep temporary-file counts bounded. The next request starts
                # its replacement JVM inside the measured deadline.
                self.close()
            if time.perf_counter() - start > timeout:
                raise TimeoutError('Native baseline deadline')
        except (TimeoutError, OSError, RuntimeError, json.JSONDecodeError):
            self.close()
            raise
        response['native_wall_s'] = time.perf_counter() - start
        return response
