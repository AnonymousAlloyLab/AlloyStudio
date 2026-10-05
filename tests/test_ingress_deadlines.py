"""TING01: sampled absolute read/completion deadlines at the production boundary.

Fake clocks make equality and CPU-preemption cases deterministic. Socket
witnesses use Event handshakes rather than hoping a sleep hits a parser window.
These tests do not construct Portal, load private configuration, or launch JVMs.
"""
from dataclasses import replace
from http.server import BaseHTTPRequestHandler
import json
from pathlib import Path
import socket
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import server
from tests.route_services_fixture import install_route_fixture
from traffic_http import BoundedHTTPServer, DeadlineReader, HTTPInputError
from traffic_profile import TrafficProfile


class FakeClock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now


class FakeSocket:
    def __init__(self, payload=b'', *, after_recv=None):
        self.payload = payload
        self.after_recv = after_recv
        self.recv_calls = 0
        self.timeouts = []

    def settimeout(self, value):
        self.timeouts.append(value)

    def recv(self, count):
        self.recv_calls += 1
        result, self.payload = self.payload[:count], self.payload[count:]
        if self.after_recv is not None:
            self.after_recv()
        return result


class AdvancingBuffer(bytearray):
    """A deletion boundary models a scheduling pause after buffered consumption."""
    def __init__(self, value, after_consume):
        super().__init__(value)
        self.after_consume = after_consume

    def __delitem__(self, key):
        super().__delitem__(key)
        self.after_consume()


class ReaderDeadlineTests(unittest.TestCase):
    def make_reader(self, payload=b'', *, after_recv=None):
        clock = FakeClock()
        connection = FakeSocket(payload, after_recv=after_recv)
        reader = DeadlineReader(connection, TrafficProfile(), clock=clock)
        return reader, clock, connection

    def test_deadline_equality_and_lateness_reject(self):
        for instant in (5.0, 6.0):
            with self.subTest(instant=instant):
                reader, clock, _ = self.make_reader()
                clock.now = instant
                with self.assertRaises(TimeoutError):
                    reader.check_deadline()

    def test_deadline_before_boundary_accepts(self):
        reader, clock, _ = self.make_reader()
        clock.now = 4.999
        self.assertEqual(reader.check_deadline(), 4.999)
        self.assertEqual(reader.deadline, 5.0)

    def test_expired_buffered_header_rejected_without_recv(self):
        for instant in (5.0, 6.0):
            with self.subTest(instant=instant):
                reader, clock, connection = self.make_reader()
                reader.buffer = bytearray(b'Host: localhost\r\n')
                reader.header_lines = 1
                clock.now = instant
                with self.assertRaises(TimeoutError):
                    reader.readline()
                self.assertEqual(connection.recv_calls, 0)

    def test_buffered_header_return_rechecks_clock(self):
        reader, clock, connection = self.make_reader()
        reader.header_lines = 1
        reader.buffer = AdvancingBuffer(b'Host: localhost\r\n', lambda: setattr(clock, 'now', 5.0))
        with self.assertRaises(TimeoutError):
            reader.readline()
        self.assertEqual(connection.recv_calls, 0)

    def test_recv_equality_rejects_received_bytes(self):
        for instant in (5.0, 6.0):
            with self.subTest(instant=instant):
                reader, clock, connection = self.make_reader(b'GET /api/health HTTP/1.1\r\n')
                connection.after_recv = lambda: setattr(clock, 'now', instant)
                with self.assertRaises(TimeoutError):
                    reader.readline()
                self.assertEqual(connection.recv_calls, 1)

    def test_expired_recv_is_not_started(self):
        reader, clock, connection = self.make_reader(b'X')
        clock.now = 5.0
        with self.assertRaises(TimeoutError):
            reader._recv(1)
        self.assertEqual(connection.recv_calls, 0)

    def test_body_transition_cannot_renew_expired_header_deadline(self):
        for instant in (5.0, 6.0):
            with self.subTest(instant=instant):
                reader, clock, _ = self.make_reader()
                clock.now = instant
                with self.assertRaises(TimeoutError):
                    reader.begin_body()
                self.assertEqual(reader.deadline, 5.0)
                self.assertTrue(reader.headers_mode)

    def test_body_transition_uses_exactly_the_validated_clock_sample(self):
        reader, _, _ = self.make_reader()
        samples = iter((4.0, 6.0))
        calls = []

        def jumping_clock():
            value = next(samples)
            calls.append(value)
            return value

        reader.clock = jumping_clock
        reader.begin_body()
        self.assertEqual(calls, [4.0])
        self.assertEqual(reader.deadline, 9.0)
        self.assertFalse(reader.headers_mode)

    def test_body_has_one_new_absolute_deadline(self):
        reader, clock, _ = self.make_reader()
        clock.now = 4.0
        reader.begin_body()
        self.assertEqual(reader.deadline, 9.0)
        self.assertFalse(reader.headers_mode)
        reader.buffer = bytearray(b'ab')
        clock.now = 8.0
        self.assertEqual(reader.read1(1), b'a')
        self.assertEqual(reader.deadline, 9.0)
        clock.now = 9.0
        with self.assertRaises(TimeoutError):
            reader.read1(1)

    def test_expired_buffered_body_rejected_without_recv(self):
        reader, clock, connection = self.make_reader()
        reader.begin_body()
        reader.buffer = bytearray(b'{}')
        clock.now = 5.0
        with self.assertRaises(TimeoutError):
            reader.read1(2)
        self.assertEqual(connection.recv_calls, 0)

    def test_buffered_body_return_rechecks_clock(self):
        reader, clock, connection = self.make_reader()
        reader.begin_body()
        reader.buffer = AdvancingBuffer(b'{}', lambda: setattr(clock, 'now', 5.0))
        with self.assertRaises(TimeoutError):
            reader.read1(2)
        self.assertEqual(connection.recv_calls, 0)

    def test_zero_count_read_does_not_bypass_expiration(self):
        reader, clock, connection = self.make_reader()
        clock.now = 5.0
        with self.assertRaises(TimeoutError):
            reader.read1(0)
        self.assertEqual(connection.recv_calls, 0)

    def test_header_eof_is_rejected_existing_negative_control(self):
        reader, _, _ = self.make_reader(b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n')
        self.assertEqual(reader.readline(), b'GET /api/health HTTP/1.1\r\n')
        self.assertEqual(reader.readline(), b'Host: localhost\r\n')
        with self.assertRaises(HTTPInputError):
            reader.readline()


class JSONCompletionTests(unittest.TestCase):
    def make_handler(self):
        clock = FakeClock()
        handler = server.Handler.__new__(server.Handler)
        handler.server = SimpleNamespace(traffic_profile=TrafficProfile())
        handler.rfile = DeadlineReader(FakeSocket(b'{}'), TrafficProfile(), clock=clock)
        return handler, clock

    def test_public_and_admin_completion_share_the_final_gate(self):
        for admin in (False, True):
            with self.subTest(admin=admin):
                handler, clock = self.make_handler()
                original = server.bounded_json

                def expire_after_decode(*args, **kwargs):
                    value = original(*args, **kwargs)
                    clock.now = handler.rfile.deadline
                    return value

                expected = server.AdminError if admin else HTTPInputError
                with patch.object(server, 'bounded_json', expire_after_decode):
                    with self.assertRaises(expected) as raised:
                        if admin:
                            handler.admin_body(2, 8192)
                        else:
                            handler.read_json_body(2, 16384)
                self.assertEqual(raised.exception.status, 400)

    def test_expired_completed_read_does_not_start_json_decoder(self):
        handler, clock = self.make_handler()
        original = handler.rfile.read1

        def expire_after_read(*args, **kwargs):
            value = original(*args, **kwargs)
            clock.now = handler.rfile.deadline
            return value

        with patch.object(handler.rfile, 'read1', expire_after_read), \
                patch.object(server, 'bounded_json') as decoder:
            with self.assertRaises(HTTPInputError) as raised:
                handler.read_json_body(2, 16384)
        self.assertEqual(raised.exception.status, 400)
        decoder.assert_not_called()


class HandlerDeadlineTests(unittest.TestCase):
    def setUp(self):
        self.clock = FakeClock()
        self.clock.now = time.monotonic()
        self.entered = threading.Event()
        self.resume = threading.Event()
        self.dispatches = []
        self.readers = []
        owner = self

        class TrackedExercises(dict):
            def __len__(self):
                owner.dispatches.append('health')
                return super().__len__()

        self.app = BoundedHTTPServer(('127.0.0.1', 0), server.Handler,
            traffic_profile=replace(TrafficProfile(), public_burst=1000, peer_burst=1000))
        install_route_fixture(self.app, TrackedExercises(), issue_channel=self.issue_channel)
        self.worker = threading.Thread(target=self.app.serve_forever,
            kwargs={'poll_interval': .001}, daemon=True)
        self.worker.start()

    def tearDown(self):
        self.resume.set()
        self.app.shutdown()
        self.app.server_close()
        self.worker.join(2)
        self.assertFalse(self.worker.is_alive())
        self.assertEqual(self.app.unexpected_route_calls, [])

    def issue_channel(self, peer):
        self.dispatches.append('channel')
        return 'a' * 43

    def reader_class(self, *, pause_after_first_line=False):
        owner = self

        class ControlledReader(DeadlineReader):
            def __init__(self, connection, profile):
                super().__init__(connection, profile, clock=owner.clock)
                owner.readers.append(self)

            def readline(self, *args, **kwargs):
                value = super().readline(*args, **kwargs)
                if pause_after_first_line and self.header_lines == 1:
                    owner.entered.set()
                    if not owner.resume.wait(2):
                        raise AssertionError('Test did not release the parser boundary.')
                return value

        return ControlledReader

    def exchange(self, raw, *, expire=False, eof=False):
        with socket.create_connection(('127.0.0.1', self.app.server_port), timeout=2) as connection:
            connection.sendall(raw)
            if eof:
                connection.shutdown(socket.SHUT_WR)
            if expire:
                self.assertTrue(self.entered.wait(2), 'Parser did not reach the intended boundary.')
                self.assertEqual(len(self.readers), 1)
                self.clock.now = self.readers[0].deadline
                self.resume.set()
            result = bytearray()
            while True:
                try:
                    chunk = connection.recv(65536)
                except (ConnectionResetError, ConnectionAbortedError):
                    break
                if not chunk:
                    break
                result.extend(chunk)
        return bytes(result)

    def test_valid_request_retains_channel_dispatch(self):
        request = (b'POST /api/channel HTTP/1.1\r\nHost: localhost\r\n'
                   b'Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}')
        with patch.object(server, 'DeadlineReader', self.reader_class()):
            response = self.exchange(request)
        self.assertIn(b' 200 ', response.split(b'\r\n')[0])
        self.assertEqual(self.dispatches, ['channel'])
        self.assertEqual(json.loads(response.partition(b'\r\n\r\n')[2])['status'], 'ok')

    def test_expired_buffered_headers_cannot_dispatch_health(self):
        request = b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n'
        with patch.object(server, 'DeadlineReader', self.reader_class(pause_after_first_line=True)):
            response = self.exchange(request, expire=True)
        self.assertNotIn(b' 200 ', response.split(b'\r\n')[0])
        self.assertEqual(self.dispatches, [])

    def test_header_parse_completion_rechecks_deadline(self):
        original = BaseHTTPRequestHandler.parse_request
        owner = self

        def paused_parse(handler):
            accepted = original(handler)
            owner.entered.set()
            if not owner.resume.wait(2):
                raise AssertionError('Test did not release the parser boundary.')
            return accepted

        request = b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n'
        with patch.object(server, 'DeadlineReader', self.reader_class()), \
                patch.object(BaseHTTPRequestHandler, 'parse_request', paused_parse):
            response = self.exchange(request, expire=True)
        self.assertNotIn(b' 200 ', response.split(b'\r\n')[0])
        self.assertEqual(self.dispatches, [])

    def test_json_completion_rechecks_deadline_before_business_dispatch(self):
        original = server.bounded_json
        owner = self

        def paused_decode(*args, **kwargs):
            value = original(*args, **kwargs)
            owner.entered.set()
            if not owner.resume.wait(2):
                raise AssertionError('Test did not release the JSON boundary.')
            return value

        request = (b'POST /api/channel HTTP/1.1\r\nHost: localhost\r\n'
                   b'Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}')
        with patch.object(server, 'DeadlineReader', self.reader_class()), \
                patch.object(server, 'bounded_json', paused_decode):
            response = self.exchange(request, expire=True)
        self.assertIn(b' 400 ', response.split(b'\r\n')[0])
        self.assertEqual(self.dispatches, [])


if __name__ == '__main__':
    unittest.main()
