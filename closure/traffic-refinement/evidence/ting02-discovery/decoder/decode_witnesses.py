"""Constructed current-source TRF-01 decoder witnesses; no engines/keys loaded.

Scheduling witnesses add a delay at an ordinary execution boundary; this
represents an admissible CPU preemption and does not change the underlying
parser/decoder return value. Every production method is otherwise unchanged.
"""
import hashlib
import inspect
import json
import os
from pathlib import Path
import socket
import sys
import threading
import time
from dataclasses import replace
from types import SimpleNamespace
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
os.environ['OPENAI_DISABLED'] = '1'
import server
import traffic_http
from traffic_profile import TrafficProfile

OUT = Path(__file__).parent
RESULTS = []

class App:
    def __init__(self, profile=None):
        self.calls = []
        self.app = traffic_http.BoundedHTTPServer(('127.0.0.1', 0), server.Handler,
            traffic_profile=profile or replace(TrafficProfile(), public_burst=1000, peer_burst=1000))
        self.app.exercises = {}
        self.app.public_origins = frozenset()
        self.app.scheduler = SimpleNamespace(issue_channel=self.issue_channel)
        self.thread = threading.Thread(target=self.app.serve_forever, kwargs={'poll_interval': .001}, daemon=True)
        self.thread.start()
    def issue_channel(self, peer):
        self.calls.append({'operation': 'scheduler.issue_channel', 'at': time.monotonic()})
        return 'a' * 43
    def close(self):
        self.app.shutdown()
        self.app.server_close()
        self.thread.join(2)
    def raw(self, value, eof=False):
        with socket.create_connection(('127.0.0.1', self.app.server_port), timeout=2) as conn:
            conn.sendall(value)
            if eof:
                conn.shutdown(socket.SHUT_WR)
            output = bytearray()
            while True:
                try:
                    chunk = conn.recv(65536)
                except (ConnectionResetError, ConnectionAbortedError):
                    break
                if not chunk:
                    break
                output.extend(chunk)
        return bytes(output)

def record(identifier, request, response, **extra):
    record = {'id': identifier, 'requestHex': request.hex(), 'requestBytes': len(request),
              'responseFirstLine': response.split(b'\r\n')[0].decode('latin1'),
              'responseSha256': hashlib.sha256(response).hexdigest(), **extra}
    RESULTS.append(record)
    return record

GET = b'GET /api/health HTTP/1.1\r\n'
POST = b'POST /api/channel HTTP/1.1\r\n'
app = App()
try:
    for identifier, request, eof in [
        ('missing_header_terminator', GET + b'Host: localhost\r\n', True),
        ('missing_host_http11', GET + b'\r\n', False),
        ('tab_separated_request_line', b'GET\t/api/health\tHTTP/1.1\r\nHost: localhost\r\n\r\n', False),
        ('embedded_request_line_carriage_return', b'GET\r /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n', False),
        ('absolute_target_host_mismatch', b'GET http://other.example/api/health HTTP/1.1\r\nHost: localhost\r\n\r\n', False),
        ('http09_request', b'GET /api/health\r\n\r\n', False),
    ]:
        response = app.raw(request, eof)
        record(identifier, request, response, successfulHealth=b'"status": "ok"' in response)
    request = POST + b'Host: localhost\r\nContent-Type: application/json; charset=utf-8; charset=latin-1\r\nContent-Length: 2\r\n\r\n{}'
    before = len(app.calls)
    response = app.raw(request)
    record('ambiguous_charset_parameters', request, response, businessDispatches=len(app.calls)-before)
finally:
    app.close()

# Exact deterministic unit witness for the buffered-read deadline bypass.
class Connection:
    def __init__(self):
        self.remaining = b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n'
    def settimeout(self, value):
        self.timeout = value
    def recv(self, count):
        result, self.remaining = self.remaining[:count], self.remaining[count:]
        return result
now = [0.0]
reader = traffic_http.DeadlineReader(Connection(), TrafficProfile(), clock=lambda: now[0])
first = reader.readline()
now[0] = 6.0
second = reader.readline()
RESULTS.append({'id': 'buffered_readline_after_deadline', 'deadline': reader.deadline,
                'now': now[0], 'returnedLine': second.decode(),
                'acceptedExpiredHeader': second == b'Host: localhost\r\n'})

# Real socket counterpart: delay after the request line, before parsing already
# received header bytes. This is precisely a possible OS scheduling pause.
class PausedReader(traffic_http.DeadlineReader):
    def readline(self, *args, **kwargs):
        value = super().readline(*args, **kwargs)
        if self.header_lines == 1:
            time.sleep(.08)
        return value
profile = replace(TrafficProfile(), header_seconds=.04, idle_seconds=.3)
app = App(profile)
try:
    request = GET + b'Host: localhost\r\n\r\n'
    with patch.object(server, 'DeadlineReader', PausedReader):
        before = time.monotonic()
        response = app.raw(request)
        elapsed = time.monotonic() - before
    record('header_dispatch_after_deadline', request, response,
           schedulingPauseSeconds=.08, headerBudgetSeconds=.04,
           elapsed=elapsed, successfulHealth=b'"status": "ok"' in response)
finally:
    app.close()

profile = replace(TrafficProfile(), body_seconds=.04, idle_seconds=.3)
app = App(profile)
original_decoder = server.bounded_json
boundaries = []
def paused_decode(*args, **kwargs):
    value = original_decoder(*args, **kwargs)
    boundaries.append(time.monotonic())
    time.sleep(.08)
    return value
try:
    request = POST + b'Host: localhost\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}'
    with patch.object(server, 'bounded_json', paused_decode):
        response = app.raw(request)
    record('business_dispatch_after_json_deadline', request, response,
           schedulingPauseSeconds=.08, bodyBudgetSeconds=.04,
           businessDispatches=len(app.calls),
           delayBeforeDispatch=app.calls[0]['at'] - boundaries[0] if app.calls else None)
finally:
    app.close()

import http.server, http.client
sources = ['server.py', 'traffic_http.py', 'traffic_profile.py', 'admin_auth.py',
           'closure/traffic-obligations.json', 'docs/backend-performance-spec.md']
result = {'kind': 'CONSTRUCTED_TRF01_DECODE_COUNTEREXAMPLES', 'claims': 'TESTED finite witnesses, not formal proof',
          'pythonVersion': sys.version,
          'sourceHashes': {name: hashlib.sha256((ROOT/name).read_bytes()).hexdigest() for name in sources},
          'stdlibHashes': {str(Path(inspect.getsourcefile(module))): hashlib.sha256(Path(inspect.getsourcefile(module)).read_bytes()).hexdigest()
                           for module in (http.server, http.client)},
          'results': RESULTS}
(OUT/'decode-witness-results.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
assert RESULTS[0]['successfulHealth'] is False  # Negative control: EOF is already rejected.
assert ' 400 ' in RESULTS[0]['responseFirstLine']
assert all(r.get('successfulHealth', True) for r in RESULTS[1:])
assert all(r.get('businessDispatches', 1) == 1 for r in RESULTS)
assert RESULTS[-1]['delayBeforeDispatch'] >= .08
