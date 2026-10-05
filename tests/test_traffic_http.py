"""Real socket witnesses for bounded ingress, framing and public revalidation."""
from dataclasses import replace
from http.client import HTTPConnection, RemoteDisconnected
from contextlib import redirect_stderr
import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import server
from tests.route_services_fixture import install_route_fixture
from traffic_http import (Admission, BoundedHTTPServer, DeadlineReader, HTTPInputError,
                          PublicViews, TokenBucket, TrafficProfile, bounded_json)

ROOT = Path(__file__).resolve().parents[1]


class AdmissionTests(unittest.TestCase):
    def test_integer_credits_backward_clock_and_no_refill_from_rejection(self):
        now = [10000000000]
        gate = Admission(replace(TrafficProfile(), public_burst=2, public_rate=1), clock=lambda: now[0])
        self.assertIsNone(gate.reserve('a')); gate.release()
        self.assertIsNone(gate.reserve('b')); gate.release()
        self.assertEqual(gate.reserve('c'), 429)
        now[0] -= 10000000000
        self.assertEqual(gate.reserve('c'), 429)
        now[0] = 11000000000
        self.assertIsNone(gate.reserve('c')); gate.release()
        self.assertEqual(gate.reserve('c'), 429)
        self.assertEqual(gate.statistics()['activeHandlers'], 0)

    def test_duplicate_or_delayed_release_cannot_free_another_connection(self):
        gate = Admission(TrafficProfile(), clock=lambda: 0)
        first, second = object(), object()
        self.assertIsNone(gate.reserve('peer', owner=first))
        gate.release(first)
        self.assertIsNone(gate.reserve('peer', owner=second))
        gate.release(first)
        self.assertEqual(gate.statistics()['activeHandlers'], 1)
        gate.release(second)
        gate.release(second)
        self.assertEqual(gate.statistics()['activeHandlers'], 0)

    def test_peer_registry_finite_and_idle_full_bucket_eviction(self):
        now = [0]
        profile = replace(TrafficProfile(), peer_entries=2, peer_idle_seconds=1)
        gate = Admission(profile, clock=lambda: now[0])
        for peer in ('a', 'b'):
            self.assertIsNone(gate.reserve(peer)); gate.release()
        self.assertEqual(gate.reserve('c'), 429)
        self.assertEqual(gate.statistics()['peerEntries'], 2)
        now[0] = 1000000000
        self.assertIsNone(gate.reserve('c')); gate.release()
        self.assertLessEqual(gate.statistics()['peerEntries'], 2)

    def test_public_and_control_credits_are_nonborrowable(self):
        profile = replace(TrafficProfile(), public_burst=1, control_burst=1)
        public = Admission(profile, clock=lambda: 0)
        control = Admission(profile, control=True, clock=lambda: 0)
        self.assertIsNone(public.reserve('a')); public.release()
        self.assertEqual(public.reserve('a'), 429)
        self.assertIsNone(control.reserve('a')); control.release()
        self.assertEqual(control.reserve('a'), 429)
        self.assertEqual(public.reserve('a'), 429)

    def test_profile_rejects_unbounded_or_invalid_limits(self):
        for change in ({'header_seconds': float('inf')}, {'body_seconds': float('nan')},
                       {'peer_entries': 0}, {'header_count': 101}, {'public_handlers': True},
                       {'public_handlers': 256}, {'line_bytes': 32769}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                replace(TrafficProfile(), **change)

    def test_json_is_strict_utf8_duplicate_free_and_depth_bounded(self):
        bad = (b'{"x":1,"x":2}', b'{"x":NaN}', b'{"x":Infinity}', b'{"x":1e999}',
               b'{"x":"\\ud800"}', b'{"x":"\xff"}', '{}'.encode('utf-16'),
               b'{"x":' + b'[' * 33 + b'0' + b']' * 33 + b'}', b'[]')
        for value in bad:
            with self.subTest(value=value[:30]), self.assertRaises(HTTPInputError):
                bounded_json(value)
        self.assertEqual(bounded_json(b'{"x":"{} \\" ["}'), {'x': '{} " ['})

    def test_public_cache_generation_bytes_entries_and_projection_only_etag(self):
        profile = replace(TrafficProfile(), public_cache_bytes=8, public_cache_entries=1)
        cache = PublicViews(profile)
        generation = object()
        a = cache.get(generation, 'a', lambda: b'one')
        self.assertEqual(cache.get(generation, 'a', lambda: self.fail('cache miss')), a)
        cache.get(generation, 'b', lambda: b'two')
        self.assertEqual(list(cache.entries), ['b'])
        cache.get(generation, 'large', lambda: b'123456789')
        self.assertEqual(list(cache.entries), ['b'])
        new = cache.get(object(), 'b', lambda: b'new')
        self.assertNotEqual(a[1], new[1])
        self.assertEqual(cache.bytes, 3)


class HTTPBoundaryTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build' / 'traffic-http-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(dir=scratch)
        self.root = Path(self.temporary.name)
        (self.root / 'web').mkdir()
        (self.root / 'web' / 'index.html').write_text('<!doctype html><p>public</p>')
        self.profile = replace(TrafficProfile(), public_burst=1000, peer_burst=1000)
        self.apps = []
        self.app = self.make_app(self.profile)
        self.evaluations = []
        self.app.evaluate = lambda *args: self.evaluations.append(args) or {'status': 'ok', 'distance': 0}

    def make_app(self, profile, *, control=False, shared_app=None):
        app = BoundedHTTPServer(('127.0.0.1', 0), server.Handler,
                                traffic_profile=profile, control=control, shared_app=shared_app)
        record = {name: '' for name in server.PUBLIC_FIELDS}
        record.update(id='example-inv1', title='Example', predicate='inv1', starter='some Node')
        install_route_fixture(app, {record['id']: record}, root=self.root)
        thread = threading.Thread(target=app.serve_forever, kwargs={'poll_interval': .005}, daemon=True)
        thread.start()
        self.apps.append((app, thread))
        return app

    def tearDown(self):
        for app, thread in self.apps:
            app.shutdown(); app.server_close(); thread.join(2)
            self.assertEqual(app.unexpected_route_calls, [])
        self.temporary.cleanup()

    def request(self, method, path, body=None, headers=None, app=None, *, allow_peer_close=False):
        app = app or self.app
        conn = HTTPConnection('127.0.0.1', app.server_port, timeout=2)
        try:
            conn.request(method, path, body, headers=headers or {})
            response = conn.getresponse()
            return response.status, response.headers, response.read()
        except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
            if not allow_peer_close:
                raise
            return None, {}, b''
        finally:
            conn.close()

    def raw(self, value, app=None):
        app = app or self.app
        with socket.create_connection(('127.0.0.1', app.server_port), timeout=2) as sock:
            try:
                sock.sendall(value)
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                return b''
            result = bytearray()
            while True:
                try:
                    chunk = sock.recv(65536)
                except (ConnectionResetError, ConnectionAbortedError):
                    break
                if not chunk:
                    break
                result.extend(chunk)
            return bytes(result)

    def capacity_request(self, app, expected_status, *, headers=None):
        """Allow only peer-close transport outcomes, while checking real admission.

        Pre-thread rejection may close before the client's request arrives. Its
        decision/counters remain observable even if Windows drops the response.
        Any delivered response must retain the precise retryable JSON contract.
        """
        decisions = []
        reserve = app.http_admission.reserve
        before = app.traffic_stats()
        def observe_reserve(*args, **kwargs):
            result = reserve(*args, **kwargs)
            decisions.append(result)
            return result
        with patch.object(app.http_admission, 'reserve', observe_reserve):
            status, response_headers, body = self.request('GET', '/api/health', headers=headers,
                                                         app=app, allow_peer_close=True)
        self.assertEqual(decisions, [expected_status])
        after = app.traffic_stats()
        self.assertEqual(after['acceptedConnections'], before['acceptedConnections'])
        self.assertEqual(after['rejectedConnections'], before['rejectedConnections'] + 1)
        self.assertEqual(after['peakHandlers'], before['peakHandlers'])
        if status is not None:
            self.assertEqual(status, expected_status)
            self.assertEqual(response_headers['Retry-After'], '1')
            self.assertEqual(json.loads(body), {'status': 'busy', 'retryable': True,
                                              'dispatched': False, 'code': 'capacity'})
        else:
            self.assertEqual((response_headers, body), ({}, b''))

    def await_active(self, app, count, timeout=1):
        deadline = time.monotonic() + timeout
        while app.traffic_stats()['activeHandlers'] != count and time.monotonic() < deadline:
            time.sleep(.005)
        self.assertEqual(app.traffic_stats()['activeHandlers'], count)

    def test_unexpected_handler_exception_cannot_log_private_values(self):
        output = io.StringIO()
        with redirect_stderr(output), patch.object(server.Handler, 'do_GET',
                side_effect=RuntimeError('PRIVATE_REQUEST_KEY_PATH_CANARY')):
            with self.assertRaises(RemoteDisconnected):
                self.request('GET', '/api/health')
            self.await_active(self.app, 0)
        self.assertEqual(output.getvalue(), 'Alloy HTTP handler failed.\n')
        self.assertNotIn('PRIVATE', output.getvalue())
        self.assertNotIn('Traceback', output.getvalue())

    def test_catalogue_detail_html_revalidation_and_publication_invalidation(self):
        for path in ('/', '/api/exercises', '/api/exercises/example-inv1'):
            status, headers, body = self.request('GET', path)
            self.assertEqual(status, 200)
            self.assertIn('no-cache', headers['Cache-Control'])
            cached = self.request('GET', path, headers={'If-None-Match': 'W/' + headers['ETag']})
            self.assertEqual(cached[0], 304)
            self.assertEqual(cached[2], b'')
            self.assertEqual(cached[1]['ETag'], headers['ETag'])
        first = self.request('GET', '/api/exercises')[1]['ETag']
        changed = dict(self.app.exercises['example-inv1'], description='Published description')
        self.app.exercises = {'example-inv1': changed}
        self.app.snapshot = SimpleNamespace(exercises=self.app.exercises)
        status, headers, body = self.request('GET', '/api/exercises', headers={'If-None-Match': first})
        self.assertEqual(status, 200)
        self.assertNotEqual(headers['ETag'], first)
        self.assertIn(b'Published description', body)
        self.assertEqual(self.request('GET', '/api/health')[1]['Cache-Control'], 'no-store')

    def test_publication_between_capture_and_cache_lookup_cannot_poison_new_generation(self):
        original = server.Handler.public_reply
        changed = dict(self.app.exercises['example-inv1'], description='New generation')
        newer = SimpleNamespace(exercises={'example-inv1': changed})
        def publish_before_lookup(handler, *args, **kwargs):
            self.app.snapshot = newer
            self.app.exercises = newer.exercises
            return original(handler, *args, **kwargs)
        with patch.object(server.Handler, 'public_reply', publish_before_lookup):
            first = self.request('GET', '/api/exercises')
        # The admitted old snapshot is valid for this one response; it must not
        # be retained under the newly published generation's cache identity.
        self.assertNotIn(b'New generation', first[2])
        second = self.request('GET', '/api/exercises', headers={'If-None-Match': first[1]['ETag']})
        self.assertEqual(second[0], 200)
        self.assertIn(b'New generation', second[2])

    def test_ambiguous_framing_is_rejected_before_engine_even_on_get(self):
        for method in ('GET', 'POST'):
            path = '/api/health' if method == 'GET' else '/api/feedback'
            for headers in (b'Content-Length: 0\r\nContent-Length: 0\r\n',
                            b'Transfer-Encoding: chunked\r\n', b'Content-Length: +2\r\n',
                            b'Content-Length: 02\r\n', b'Host: second\r\n',
                            b'Content-Length : 2\r\n', b' X-Folded: value\r\n',
                            b'X-Invalid: \x00\r\n'):
                with self.subTest(method=method, headers=headers):
                    response = self.raw(method.encode() + b' ' + path.encode() + b' HTTP/1.1\r\n'
                        + b'Host: localhost\r\nContent-Type: application/json\r\n' + headers + b'\r\n')
                    self.assertIn(b' 400 ', response.split(b'\r\n')[0], response)
        self.assertEqual(self.evaluations, [])

    def test_invalid_json_encodings_and_limits_do_not_dispatch(self):
        for body in (b'{"exerciseId":"x","exerciseId":"y"}', b'{"x":NaN}',
                     '{}'.encode('utf-16'), b'{"x":"\xff"}', b'{"x":1e999}',
                     b'{"x":' + b'[' * 33 + b'0' + b']' * 33 + b'}'):
            self.assertEqual(self.request('POST', '/api/feedback', body,
                {'Content-Type': 'application/json'})[0], 400)
        self.assertEqual(self.request('POST', '/api/feedback', b'{}',
            {'Content-Type': 'application/json; charset=utf-16'})[0], 415)
        self.assertEqual(self.request('POST', '/api/feedback', b'{}',
            {'Content-Type': 'application/json', 'Content-Encoding': 'gzip'})[0], 415)
        response = self.raw(b'POST /api/feedback HTTP/1.1\r\nHost: localhost\r\n'
                            b'Content-Type: application/json\r\nContent-Length: 16385\r\n\r\n')
        self.assertIn(b' 413 ', response.split(b'\r\n')[0])
        self.assertEqual(self.evaluations, [])

    def test_header_line_and_aggregate_bounds(self):
        for headers in (b'X-Huge: ' + b'x' * 8200 + b'\r\n',
                        b''.join(b'X-' + str(i).encode() + b': ' + b'x' * 1000 + b'\r\n' for i in range(33)),
                        b''.join(b'X-' + str(i).encode() + b': v\r\n' for i in range(65))):
            response = self.raw(b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n' + headers + b'\r\n')
            self.assertIn(b' 431 ', response.split(b'\r\n')[0], response)

    def test_slow_trickle_header_has_absolute_expiry(self):
        app = self.make_app(replace(self.profile, header_seconds=.18, idle_seconds=.15))
        with socket.create_connection(('127.0.0.1', app.server_port), timeout=1) as sock:
            started = time.monotonic()
            sock.sendall(b'GET /api/health HTTP/1.1\r\nHost: ')
            for _ in range(12):
                time.sleep(.035)
                try:
                    sock.sendall(b'a')
                except OSError:
                    break
            self.await_active(app, 0)
            self.assertLess(time.monotonic() - started, .65)

    def test_slow_trickle_body_has_absolute_expiry_and_json_error(self):
        app = self.make_app(replace(self.profile, body_seconds=.18, idle_seconds=.15))
        rejections, dispatches = [], []
        original_reply = server.Handler.reply
        started = time.monotonic()
        def observe_reply(handler, status, data, *args, **kwargs):
            if handler.server is app:
                rejections.append((status, data, time.monotonic() - started))
            return original_reply(handler, status, data, *args, **kwargs)
        app.evaluate = lambda *args, **kwargs: dispatches.append(args) or {'status': 'ok'}
        with patch.object(server.Handler, 'reply', observe_reply), \
                socket.create_connection(('127.0.0.1', app.server_port), timeout=1) as sock:
            sock.sendall(b'POST /api/feedback HTTP/1.1\r\nHost: localhost\r\n'
                         b'Content-Type: application/json\r\nContent-Length: 100\r\n\r\n{')
            for _ in range(7):
                time.sleep(.035)
                try:
                    sock.sendall(b' ')
                except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                    break
            # Windows may abort/reset after post-deadline writes rather than
            # deliver the already-attempted rejection. A timeout or unrelated
            # socket error is not an accepted outcome. Independently verify the
            # server's rejection and its deadline below, even when TCP drops it.
            try:
                result = sock.recv(8192)
            except (ConnectionResetError, ConnectionAbortedError):
                result = b''
            if result:
                self.assertIn(b' 400 ', result)
            self.await_active(app, 0)
        self.assertEqual(len(rejections), 1)
        status, body, rejected_after = rejections[0]
        self.assertEqual(status, 400)
        self.assertIn('timed-out', body['error'])
        self.assertGreaterEqual(rejected_after, .15)
        self.assertLess(rejected_after, .35)  # idle-only expiry would exceed .39s
        self.assertLess(time.monotonic() - started, .65)
        self.assertEqual(dispatches, [])

    def test_gate_precedes_thread_and_control_survives_public_saturation(self):
        profile = replace(self.profile, public_handlers=1, header_seconds=.5)
        public = self.make_app(profile)
        control = self.make_app(profile, control=True, shared_app=public)
        with socket.create_connection(('127.0.0.1', public.server_port), timeout=1):
            self.await_active(public, 1)
            self.capacity_request(public, 503)
            self.assertEqual(public.traffic_stats()['peakHandlers'], 1)
            self.assertEqual(self.request('GET', '/api/health', app=control)[0], 200)
            self.assertEqual(self.request('GET', '/api/exercises', app=control)[0], 404)
            self.assertEqual(self.request('POST', '/api/admin/commit', b'{}', {'Content-Type': 'application/json'}, app=control)[0], 405)
        self.await_active(public, 0)

    def test_forwarded_addresses_do_not_reset_source_or_total_credits(self):
        profile = replace(self.profile, public_burst=100, peer_burst=1, peer_rate=1)
        app = self.make_app(profile)
        self.assertEqual(self.request('GET', '/api/health', headers={'X-Forwarded-For': '192.0.2.1'}, app=app)[0], 200)
        for source in ('192.0.2.2', '192.0.2.3'):
            self.capacity_request(app, 429, headers={'X-Forwarded-For': source})
        self.assertEqual(app.traffic_stats()['peerEntries'], 1)

    def test_control_listener_rejects_non_loopback_bind(self):
        with self.assertRaises(ValueError):
            BoundedHTTPServer(('0.0.0.0', 0), server.Handler, control=True)

    def test_slow_response_reader_cannot_retain_handler_past_write_budget(self):
        app = self.make_app(replace(self.profile, write_seconds=.18, idle_seconds=.15))
        (self.root / 'web' / 'index.html').write_bytes(b'x' * (8 * 1024 * 1024))
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
            sock.connect(('127.0.0.1', app.server_port))
            sock.sendall(b'GET / HTTP/1.1\r\nHost: localhost\r\n\r\n')
            self.await_active(app, 1)
            self.await_active(app, 0, timeout=1)


if __name__ == '__main__':
    unittest.main()
