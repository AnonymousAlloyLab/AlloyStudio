"""Bounded refusal evidence; injected transport faults are not native Windows repros."""
from dataclasses import replace
import argparse
import hashlib
from http.client import HTTPConnection as NativeHTTPConnection
import importlib.util
import json
from pathlib import Path
import socket
import sys
import threading
import unittest
from unittest.mock import patch

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--root', type=Path, required=True, help='Repository root containing unchanged production modules')
parser.add_argument('--before', type=Path, required=True, help='Preserved original test source')
parser.add_argument('--candidate', type=Path, required=True, help='Corrected test source')
parser.add_argument('--output', type=Path, required=True, help='Owned writable scratch directory for replay evidence')
args = parser.parse_args()
ROOT = args.root.resolve()
OUT = args.output.resolve()
OUT.mkdir(parents=True, exist_ok=True)
sys.path[:0] = [str(ROOT), str(ROOT / 'tests')]
from traffic_http import BoundedHTTPServer, TrafficProfile


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def bounded_send_failure():
    trace = []
    class UnwritableSocket:
        def setblocking(self, value): trace.append(['setblocking', value])
        def send(self, packet):
            trace.append(['send_attempt', len(packet)])
            raise BlockingIOError('injected no socket write capacity')
        def shutdown(self, how): trace.append(['shutdown', how])
        def close(self): trace.append(['close'])
    def forbidden_handler(*args):
        raise AssertionError('Refusal allocated a request handler')
    app = BoundedHTTPServer(('127.0.0.1', 0), forbidden_handler,
                           traffic_profile=replace(TrafficProfile(), public_handlers=1))
    owner = object()
    try:
        assert app.http_admission.reserve('peer', owner=owner) is None
        before = app.traffic_stats()
        threads = threading.active_count()
        app.process_request(UnwritableSocket(), ('peer', 1))
        after = app.traffic_stats()
        assert [entry[0] for entry in trace] == ['setblocking', 'send_attempt', 'shutdown', 'close']
        assert trace[0] == ['setblocking', False]
        assert trace[2] == ['shutdown', socket.SHUT_WR]
        assert after['acceptedConnections'] == before['acceptedConnections']
        assert after['rejectedConnections'] == before['rejectedConnections'] + 1
        assert after['activeHandlers'] == after['peakHandlers'] == 1
        assert app._request_threads == {}
        assert threading.active_count() == threads
        return {'status': 'PASS', 'kind': 'deterministic send-failure injection into production process_request',
                'trace': trace, 'before': before, 'after': after, 'new_request_threads': 0}
    finally:
        app.http_admission.release(owner)
        app.server_close()


def run_pair_with_close(module):
    events = []
    class DroppedRefusal(NativeHTTPConnection):
        def getresponse(self):
            response = super().getresponse()
            events.append(response.status)
            if response.status == 503:
                response.read()
                raise ConnectionAbortedError(10053, 'injected peer-close delivery outcome')
            return response
    case = module.SocketAdmissionTests('test_enabled_pair_actual_saturation_control_health_and_limits')
    result = unittest.TestResult()
    with patch.object(module, 'HTTPConnection', DroppedRefusal):
        case.run(result)
    return {'successful': result.wasSuccessful(), 'errors': len(result.errors),
            'failures': len(result.failures), 'delivered_server_statuses': events,
            'error_contains_expected_close': any('ConnectionAbortedError' in error for _, error in result.errors)}


def helper_control(module, name, *, error=None, response_status=503, retry_after='1',
                   response_body=None, alter_decision=False):
    body = (b'{"status":"busy","retryable":true,"dispatched":false,"code":"capacity"}'
            if response_body is None else response_body)
    class ControlledResponse:
        status = response_status
        def getheader(self, name): return retry_after
        def read(self): return body
    class ControlledConnection(NativeHTTPConnection):
        def getresponse(self):
            response = super().getresponse()
            response.read()
            if error is not None: raise error
            return ControlledResponse()
    case = module.SocketAdmissionTests('runTest')
    case.setUp()
    caught = None
    try:
        app = case.make_app(replace(TrafficProfile(), public_handlers=1))
        case.connect(app)
        case.wait_for(lambda: app.traffic_stats()['activeHandlers'] == 1)
        reserve = app.http_admission.reserve
        def wrong_decision(*args, **kwargs):
            decision = reserve(*args, **kwargs)
            assert decision == 503
            return 429
        with patch.object(module, 'HTTPConnection', ControlledConnection):
            try:
                if alter_decision:
                    with patch.object(app.http_admission, 'reserve', wrong_decision):
                        case.capacity_request(app, 503)
                else:
                    case.capacity_request(app, 503)
            except Exception as exc:
                caught = type(exc).__name__
    finally:
        case.tearDown()
    return {'name': name, 'caught': caught}


before_path = args.before.resolve()
after_path = args.candidate.resolve()
before = load('ingress_before_refusal_correction', before_path)
after = load('ingress_after_refusal_correction', after_path)
old_pair = run_pair_with_close(before)
new_pair = run_pair_with_close(after)
assert not old_pair['successful'] and old_pair['error_contains_expected_close']
assert new_pair['successful'] and new_pair['delivered_server_statuses'] == [503, 200, 404, 503]
witness = {'host': sys.platform, 'native_windows_reproduction': False,
           'transport_fault': 'The real server completes each refusal; the client response operation then raises an explicitly injected ConnectionAbortedError(10053).',
           'old_paired_test': old_pair, 'candidate_paired_test': new_pair,
           'production_send_failure': bounded_send_failure()}
(OUT / 'schedule-witness.json').write_text(json.dumps(witness, indent=2) + '\n')

controls = []
for name, options, expected in [
    ('valid_response', {}, None),
    ('connection_reset', {'error': ConnectionResetError(104, 'injected peer reset')}, None),
    ('connection_aborted', {'error': ConnectionAbortedError(10053, 'injected peer abort')}, None),
    ('broken_pipe', {'error': BrokenPipeError(32, 'injected peer close')}, None),
    ('wrong_decision_despite_valid_response', {'alter_decision': True}, 'AssertionError'),
    ('wrong_delivered_status', {'response_status': 429}, 'AssertionError'),
    ('wrong_retry_after', {'retry_after': '2'}, 'AssertionError'),
    ('wrong_retryable_json', {'response_body': b'{"status":"ok"}'}, 'AssertionError'),
    ('unrelated_timeout', {'error': TimeoutError('injected unrelated timeout')}, 'TimeoutError'),
    ('unrelated_oserror', {'error': OSError(22, 'injected unrelated error')}, 'OSError'),
]:
    record = helper_control(after, name, **options)
    record['expected'] = expected
    record['status'] = 'PASS' if record['caught'] == expected else 'FAIL'
    controls.append(record)
assert all(record['status'] == 'PASS' for record in controls)
(OUT / 'negative-controls.json').write_text(json.dumps({'status': 'PASS', 'controls': controls}, indent=2) + '\n')
(OUT / 'after').mkdir(exist_ok=True)
(OUT / 'after/test_ingress_admission.py').write_bytes(after_path.read_bytes())
summary = {'status': 'PASS', 'before_sha256': hashlib.sha256(before_path.read_bytes()).hexdigest(),
           'after_sha256': hashlib.sha256(after_path.read_bytes()).hexdigest(),
           'witness': 'schedule-witness.json', 'negative_controls': len(controls)}
(OUT / 'result.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary))
