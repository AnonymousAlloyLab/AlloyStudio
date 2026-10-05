"""Production ownership and live-thread witnesses, with actual paired sockets."""
from contextlib import redirect_stderr
from dataclasses import replace
from http.client import HTTPConnection
import io
import json
import socket
import socketserver
import threading
import time
import unittest
from unittest.mock import patch

import server
from tests.route_services_fixture import install_route_fixture
from traffic_http import Admission, BoundedHTTPServer, TrafficProfile


class AdmissionOwnershipTests(unittest.TestCase):
    def test_simultaneous_reservation_linearization_and_exact_release(self):
        gate = Admission(replace(TrafficProfile(), public_handlers=2), clock=lambda: 0)
        barrier = threading.Barrier(17)
        owners = [object() for _ in range(16)]
        results = [None] * 16
        def attempt(index):
            barrier.wait()
            results[index] = gate.reserve('peer', owner=owners[index])
        threads = [threading.Thread(target=attempt, args=(i,)) for i in range(16)]
        for thread in threads: thread.start()
        barrier.wait()
        for thread in threads: thread.join(2)
        self.assertEqual(results.count(None), 2)
        self.assertEqual(results.count(503), 14)
        self.assertEqual(gate.active, len(gate.owners))
        held = list(gate.owners)
        with self.assertRaises(ValueError): gate.reserve('peer', owner=held[0])
        gate.release(held[0]); gate.release(held[0])
        self.assertEqual(gate.owners, {held[1]})
        gate.release(held[1])
        self.assertEqual(gate.active, 0)

    def test_anonymous_stack_and_nonborrowable_credits(self):
        profile = replace(TrafficProfile(), public_burst=1, control_burst=1)
        public = Admission(profile, clock=lambda: 0)
        control = Admission(profile, control=True, clock=lambda: 0)
        self.assertIsNot(public.owners, control.owners)
        self.assertIsNot(public.bucket, control.bucket)
        self.assertIsNone(public.reserve('peer'))
        public.release(); public.release()
        self.assertEqual(public.active, 0)
        self.assertEqual(public.reserve('peer'), 429)
        self.assertIsNone(control.reserve('peer'))
        self.assertEqual(public.reserve('peer'), 429)
        control.release()


class SocketAdmissionTests(unittest.TestCase):
    def setUp(self):
        self.apps = []
        self.sockets = []
        self.release_tail = threading.Event()

    def make_app(self, profile=None, handler=server.Handler, **kwargs):
        app = BoundedHTTPServer(('127.0.0.1', 0), handler,
                                traffic_profile=profile or TrafficProfile(), **kwargs)
        install_route_fixture(app, {})
        thread = threading.Thread(target=app.serve_forever, kwargs={'poll_interval': .005}, daemon=True)
        thread.start()
        self.apps.append((app, thread))
        return app

    def tearDown(self):
        self.release_tail.set()
        for sock in self.sockets: sock.close()
        for app, thread in self.apps:
            app.shutdown(); app.server_close(); thread.join(2)
            self.wait_for(lambda: not any(t.is_alive() for t in app._request_threads.values()))
            app._reap_request_threads()
            self.assertEqual(app.http_admission.active, getattr(app, '_expected_retained', 0))
            self.assertEqual(len(app._request_threads), getattr(app, '_expected_retained', 0))
            self.assertEqual(app.unexpected_route_calls, [])

    def wait_for(self, predicate):
        deadline = time.monotonic() + 3
        while not predicate():
            if time.monotonic() >= deadline: self.fail('Production state did not reach the required cut')
            time.sleep(.002)

    def connect(self, app):
        sock = socket.create_connection(app.server_address, timeout=2)
        self.sockets.append(sock)
        return sock

    def get(self, app, path='/api/health'):
        conn = HTTPConnection(*app.server_address, timeout=2)
        try:
            conn.request('GET', path)
            result = conn.getresponse()
            return result.status, result.read()
        finally:
            conn.close()

    def capacity_request(self, app, expected_status):
        """Check the bounded refusal decision even when TCP drops its response."""
        decisions = []
        reserve = app.http_admission.reserve
        before = app.traffic_stats()
        def observe_reserve(*args, **kwargs):
            result = reserve(*args, **kwargs)
            decisions.append(result)
            return result
        conn = HTTPConnection(*app.server_address, timeout=2)
        with patch.object(app.http_admission, 'reserve', observe_reserve):
            try:
                conn.request('GET', '/api/health')
                response = conn.getresponse()
                self.assertEqual(response.status, expected_status)
                self.assertEqual(response.getheader('Retry-After'), '1')
                body = response.read()
            except (ConnectionResetError, ConnectionAbortedError, BrokenPipeError):
                # Pre-thread refusal closes without reading the request. The
                # client may observe peer close before the refusal arrives.
                pass
            else:
                self.assertEqual(json.loads(body), {'status': 'busy', 'retryable': True,
                                                   'dispatched': False, 'code': 'capacity'})
            finally:
                conn.close()
        self.assertEqual(decisions, [expected_status])
        after = app.traffic_stats()
        self.assertEqual(after['acceptedConnections'], before['acceptedConnections'])
        self.assertEqual(after['rejectedConnections'], before['rejectedConnections'] + 1)
        self.assertEqual(after['peakHandlers'], before['peakHandlers'])

    def test_enabled_pair_actual_saturation_control_health_and_limits(self):
        profile = replace(TrafficProfile(), public_handlers=2, control_handlers=1)
        public = self.make_app(profile)
        control = self.make_app(profile, control=True, shared_app=public)
        self.assertNotEqual(public.server_port, control.server_port)
        self.assertEqual(public.traffic_profile, control.traffic_profile)
        self.assertIsNot(public.http_admission, control.http_admission)
        held = [self.connect(public) for _ in range(2)]
        self.wait_for(lambda: public.traffic_stats()['activeHandlers'] == 2)
        accepted = public.traffic_stats()['acceptedConnections']
        self.capacity_request(public, 503)
        self.assertEqual(public.traffic_stats()['acceptedConnections'], accepted)
        self.assertEqual(self.get(control)[0], 200)
        self.wait_for(lambda: control.traffic_stats()['activeHandlers'] == 0)
        self.assertEqual(self.get(control, '/api/exercises')[0], 404)
        self.wait_for(lambda: control.traffic_stats()['activeHandlers'] == 0)
        private = self.connect(control)
        self.wait_for(lambda: control.traffic_stats()['activeHandlers'] == 1)
        self.assertEqual(public.http_admission.active + control.http_admission.active, 3)
        self.capacity_request(control, 503)
        private.close()
        for sock in held: sock.close()

    def test_live_thread_tail_retains_reservation_and_blocks_next_allocator(self):
        tail_entered = threading.Event()
        original_thread = threading.Thread
        app = self.make_app(replace(TrafficProfile(), public_handlers=1))
        release_tail = self.release_tail
        class PausedTailThread(original_thread):
            def run(self):
                super().run()
                tail_entered.set()
                if not release_tail.wait(3): raise RuntimeError('Tail witness synchronization failed')
        with patch('traffic_http.threading.Thread', PausedTailThread):
            self.assertEqual(self.get(app)[0], 200)
            self.assertTrue(tail_entered.wait(2))
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.assertEqual(sum(t.is_alive() for t in app._request_threads.values()), 1)
            self.capacity_request(app, 503)
            self.assertEqual(app.traffic_stats()['acceptedConnections'], 1)
        release_tail.set()
        self.wait_for(lambda: app.traffic_stats()['activeHandlers'] == 0)
        self.assertEqual(self.get(app)[0], 200)

    def test_constructor_start_failure_and_started_then_raise_paths(self):
        app = self.make_app(replace(TrafficProfile(), public_handlers=1))
        original_thread = threading.Thread
        def constructor_failure(*args, **kwargs):
            self.assertEqual(app.http_admission.active, 1)
            raise RuntimeError('injected constructor failure')
        class BeforeAttempt(original_thread):
            @property
            def daemon(self): return False
            @daemon.setter
            def daemon(self, value): raise RuntimeError('injected before attempted start')
        for factory in (constructor_failure, BeforeAttempt):
            left, right = socket.socketpair()
            try:
                with patch('traffic_http.threading.Thread', factory), self.assertRaises(RuntimeError):
                    app.process_request(left, ('127.0.0.1', 1))
                self.assertEqual(app.http_admission.active, 0)
                self.assertEqual(app._request_threads, {})
            finally:
                left.close(); right.close()
        started = threading.Event()
        release = self.release_tail
        class StartedThenRaise(original_thread):
            def run(self):
                started.set()
                release.wait(3)
                super().run()
            def start(self):
                super().start()
                raise RuntimeError('injected after successful start')
        left, right = socket.socketpair()
        try:
            with patch('traffic_http.threading.Thread', StartedThenRaise), self.assertRaises(RuntimeError):
                app.process_request(left, ('127.0.0.1', 1))
            self.assertTrue(started.wait(2))
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.assertIn(left, app.http_admission.owners)
            right.close(); release.set()
            self.wait_for(lambda: app.traffic_stats()['activeHandlers'] == 0)
        finally:
            left.close(); right.close(); release.set()

    def test_failed_attempt_without_bootstrap_retains_bounded_capacity(self):
        app = self.make_app(replace(TrafficProfile(), public_handlers=1))
        original_thread = threading.Thread
        class NeverStarted(original_thread):
            def start(self): raise RuntimeError('injected failed native launch')
        left, right = socket.socketpair()
        try:
            with patch('traffic_http.threading.Thread', NeverStarted), self.assertRaises(RuntimeError):
                app.process_request(left, ('127.0.0.1', 1))
            app._expected_retained = 1
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.assertIn(left, app.http_admission.owners)
            self.capacity_request(app, 503)
        finally:
            left.close(); right.close()

    def test_interrupted_start_before_ident_publication_retains_future_thread(self):
        app = self.make_app(replace(TrafficProfile(), public_handlers=1))
        original_thread = threading.Thread
        bootstrap = threading.Event()
        entered = threading.Event()
        finished = threading.Event()
        class StartedEvent:
            def __init__(self): self.real = threading.Event()
            def is_set(self): return self.real.is_set()
            def set(self): return self.real.set()
            def wait(self, *args): raise KeyboardInterrupt('injected before bootstrap publication')
        class DelayedBootstrap(original_thread):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self._started = StartedEvent()
            def _bootstrap(self):
                bootstrap.wait(3)
                super()._bootstrap()
                finished.set()
            def run(self):
                entered.set()
                super().run()
        left, right = socket.socketpair()
        try:
            with patch('traffic_http.threading.Thread', DelayedBootstrap), self.assertRaises(KeyboardInterrupt):
                app.process_request(left, ('127.0.0.1', 1))
            thread = app._request_threads[left]
            self.assertIsNone(thread.ident)
            self.assertFalse(thread.is_alive())
            app._expected_retained = 1
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.capacity_request(app, 503)
            bootstrap.set()
            self.assertTrue(entered.wait(2))
            self.assertIsNotNone(thread.ident)
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            right.close()
            self.assertTrue(finished.wait(2))
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.assertIn(left, app._request_threads_uncertain)
        finally:
            bootstrap.set(); left.close(); right.close()

    def test_handler_failure_retained_until_thread_exit_then_reaped(self):
        class Failing(socketserver.BaseRequestHandler):
            def handle(self): raise RuntimeError('private diagnostic must be redacted')
        app = self.make_app(handler=Failing)
        errors = io.StringIO()
        with redirect_stderr(errors):
            self.connect(app)
            self.wait_for(lambda: app.traffic_stats()['acceptedConnections'] == 1)
            self.wait_for(lambda: app.traffic_stats()['activeHandlers'] == 0)
        self.assertEqual(errors.getvalue(), 'Alloy HTTP handler failed.\n')

    def test_ident_published_before_started_is_not_a_terminated_thread(self):
        app = self.make_app(replace(TrafficProfile(), public_handlers=1))
        original_thread = threading.Thread
        identified, resume, entered = (threading.Event() for _ in range(3))
        class StartedEvent:
            def __init__(self): self.real = threading.Event()
            def is_set(self): return self.real.is_set()
            def set(self): return self.real.set()
            def wait(self, *args):
                if not identified.wait(3):
                    raise RuntimeError('Identification checkpoint unavailable')
                raise KeyboardInterrupt('injected interrupted start wait')
        class PausedIdentification(original_thread):
            def __init__(self, *args, **kwargs):
                super().__init__(*args, **kwargs)
                self._started = StartedEvent()
            def _set_ident(self):
                super()._set_ident()
                identified.set()
                if not resume.wait(3):
                    raise RuntimeError('Identification checkpoint timed out')
            def run(self):
                entered.set()
                super().run()
        left, right = socket.socketpair()
        thread = None
        try:
            with patch('traffic_http.threading.Thread', PausedIdentification), self.assertRaises(KeyboardInterrupt):
                app.process_request(left, ('127.0.0.1', 1))
            thread = app._request_threads[left]
            self.assertIsNotNone(thread.ident)
            self.assertFalse(thread.is_alive())
            self.assertFalse(thread._started.is_set())
            app._expected_retained = 1
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.capacity_request(app, 503)
            self.assertEqual(app.traffic_stats()['acceptedConnections'], 1)
            resume.set()
            self.assertTrue(entered.wait(2))
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            right.close()
            thread.join(2)
            self.assertEqual(app.traffic_stats()['activeHandlers'], 1)
            self.assertIn(left, app._request_threads_uncertain)
        finally:
            resume.set(); left.close(); right.close()
            if thread is not None:
                thread._started.real.wait(3)
                thread.join(2)

    def test_nonloopback_control_refused_before_listening(self):
        with self.assertRaises(ValueError):
            BoundedHTTPServer(('0.0.0.0', 0), server.Handler, control=True)


class ReclamationObservationTests(unittest.TestCase):
    def app(self):
        app = BoundedHTTPServer(('127.0.0.1', 0), server.Handler,
                               traffic_profile=replace(TrafficProfile(), public_handlers=1))
        self.addCleanup(app.server_close)
        return app

    def register(self, app, thread):
        owner = object()
        self.assertIsNone(app.http_admission.reserve('peer', owner=owner))
        app._request_threads[owner] = thread
        return owner

    def test_current_thread_is_skipped_without_creating_uncertainty(self):
        app = self.app()
        owner = self.register(app, threading.current_thread())
        app._reap_request_threads()
        self.assertEqual(app._request_threads_uncertain, set())
        self.assertIn(owner, app.http_admission.owners)

    def test_normal_live_then_dead_observations_reclaim_without_retaining_markers(self):
        class Probe:
            alive = True
            def join(self, *, timeout):
                assert timeout == 0
            def is_alive(self): return self.alive
        app, probe = self.app(), Probe()
        owner = self.register(app, probe)
        for _ in range(3):
            app._reap_request_threads()
            self.assertEqual(app._request_threads_uncertain, set())
            self.assertEqual(app.http_admission.active, 1)
        probe.alive = False
        app._reap_request_threads()
        self.assertEqual(app._request_threads_uncertain, set())
        self.assertNotIn(owner, app._request_threads)
        self.assertEqual(app.http_admission.active, 0)

    def test_every_observation_exception_prevents_all_later_probes(self):
        for where in ('join', 'is_alive'):
            for error in (RuntimeError, ValueError, KeyboardInterrupt):
                with self.subTest(where=where, error=error.__name__):
                    class Probe:
                        def __init__(self): self.calls = []; self.fail = True
                        def observe(self, phase):
                            self.calls.append(phase)
                            if phase == where and self.fail:
                                raise error('injected observation exception')
                        def join(self, *, timeout): self.observe('join')
                        def is_alive(self): self.observe('is_alive'); return False
                    app, probe = self.app(), Probe()
                    owner = self.register(app, probe)
                    if error is RuntimeError:
                        app._reap_request_threads()
                    else:
                        with self.assertRaises(error): app._reap_request_threads()
                    calls = list(probe.calls)
                    probe.fail = False
                    for _ in range(3): app._reap_request_threads()
                    self.assertEqual(probe.calls, calls)
                    self.assertEqual(app._request_threads_uncertain, {owner})
                    self.assertEqual(app.http_admission.active, 1)
                    self.assertEqual(app.http_admission.reserve('another', owner=object()), 503)

    @unittest.skipUnless(hasattr(threading.Thread, '_wait_for_tstate_lock'),
                         'Legacy CPython status-lock implementation is not present')
    def test_installed_thread_status_interruption_cannot_reclaim_a_running_handler(self):
        for interrupt_at in (1, 2):
            with self.subTest(interrupt_at=interrupt_at):
                entered, finish, finished = (threading.Event() for _ in range(3))
                class Handler(socketserver.BaseRequestHandler):
                    def handle(self):
                        entered.set()
                        try: finish.wait(3)
                        finally: finished.set()
                class InterruptedAcquire:
                    def __init__(self, lock): self.lock = lock; self.calls = 0
                    def acquire(self, *args, **kwargs):
                        self.calls += 1
                        if self.calls == interrupt_at:
                            raise RuntimeError('injected status-lock interruption')
                        return self.lock.acquire(*args, **kwargs)
                    def release(self): return self.lock.release()
                    def locked(self): return self.lock.locked()
                app = BoundedHTTPServer(('127.0.0.1', 0), Handler,
                    traffic_profile=replace(TrafficProfile(), public_handlers=1))
                left, right = socket.socketpair()
                try:
                    app.process_request(left, ('127.0.0.1', 1))
                    self.assertTrue(entered.wait(2))
                    thread = app._request_threads[left]
                    thread._tstate_lock = InterruptedAcquire(thread._tstate_lock)
                    app._reap_request_threads()
                    self.assertFalse(thread.is_alive())
                    self.assertFalse(finished.is_set())
                    for _ in range(3): app._reap_request_threads()
                    self.assertEqual(app.http_admission.active, 1)
                    self.assertIn(left, app._request_threads_uncertain)
                    self.assertEqual(app.http_admission.reserve('other', owner=object()), 503)
                finally:
                    finish.set()
                    self.assertTrue(finished.wait(2))
                    left.close(); right.close(); app.server_close()


if __name__ == '__main__':
    unittest.main()
