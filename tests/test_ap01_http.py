"""AP01 C04/C05/C10/C11 through real HTTP listeners on loopback.

Channel quotas follow the resolved identity, administration admission precedes
any auth state, and diagnostics exist only on the private control listener.
Synthetic administrator configuration lives in a temporary root.
"""
from http.client import HTTPConnection
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

import admin_auth as auth
import server
from traffic_http import BoundedHTTPServer, TrafficProfile
from test_sqlite_store import fixture

PROFILE = TrafficProfile(public_burst=10000, peer_burst=10000, public_rate=10000, peer_rate=10000)


class Listener:
    def __init__(self, test, **options):
        self.temporary = tempfile.TemporaryDirectory()
        test.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        fixture(self.root)
        self.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', root=self.root,
                                 traffic_profile=PROFILE, **options)
        self.thread = threading.Thread(target=lambda: self.app.serve_forever(poll_interval=0.01), daemon=True)
        self.thread.start()
        test.addCleanup(self.close)

    def close(self):
        self.app.shutdown()
        self.app.server_close()
        self.thread.join(2)

    def configure_admin(self, scheme='http'):
        config = auth.configuration(scheme + '://127.0.0.1:' + str(self.app.server_port), '/', 'synthetic-ap01-password')
        path = self.root / auth.CONFIG_NAME
        path.write_text(json.dumps(config))
        path.chmod(0o600)


def request(port, method, path, *, body=None, headers=None):
    connection = HTTPConnection('127.0.0.1', port, timeout=10)
    try:
        connection.request(method, path, body=body, headers=headers or {})
        response = connection.getresponse()
        data = response.read()
        return response.status, json.loads(data) if data else None
    finally:
        connection.close()


def channel(port, forwarded=None):
    headers = {'Content-Type': 'application/json'}
    if forwarded is not None:
        headers['X-Forwarded-For'] = forwarded
    return request(port, 'POST', '/api/channel', body=b'{}', headers=headers)


class ChannelIdentityTests(unittest.TestCase):
    def test_trusted_proxy_identity_bounds_each_client_and_admits_distinct_clients(self):
        listener = Listener(self, trusted_proxy_addresses=('127.0.0.1',))
        port = listener.app.server_port
        for _ in range(32):
            self.assertEqual(channel(port, '192.0.2.1')[0], 200)
        self.assertEqual(channel(port, '192.0.2.1'), (429, {'status': 'busy', 'code': 'capacity',
                                                           'retryable': True, 'dispatched': False}))
        self.assertEqual(channel(port, '192.0.2.2')[0], 200)  # full peer quota does not block another identity
        self.assertEqual(channel(port, '203.0.113.9, 192.0.2.2')[0], 200)  # spoofed left prefix ignored

    def test_trusted_proxy_without_valid_metadata_never_falls_back(self):
        listener = Listener(self, trusted_proxy_addresses=('127.0.0.1',))
        port = listener.app.server_port
        for forwarded in (None, '', 'unknown', '192.0.2.1:8080', '::ffff:192.0.2.1'):
            with self.subTest(forwarded=forwarded):
                status, data = channel(port, forwarded)
                self.assertEqual((status, data['code']), (400, 'identity_rejected'))
        self.assertEqual(len(listener.app.scheduler.channels), 0)

    def test_untrusted_peer_headers_cannot_select_a_fresh_quota_identity(self):
        listener = Listener(self)
        port = listener.app.server_port
        for index in range(32):
            self.assertEqual(channel(port, '192.0.2.%d' % index)[0], 200)
        self.assertEqual(channel(port, '198.51.100.1')[0], 429)

    def test_global_channel_bound_refuses_even_a_fresh_identity(self):
        listener = Listener(self, trusted_proxy_addresses=('127.0.0.1',))
        port = listener.app.server_port
        for client in range(16):
            for _ in range(32):
                self.assertEqual(channel(port, '192.0.2.%d' % client)[0], 200)
        self.assertEqual(len(listener.app.scheduler.channels), 512)
        self.assertEqual(channel(port, '198.51.100.200')[0], 429)


class AdministrationAdmissionTests(unittest.TestCase):
    ROUTES = (('GET', '/api/admin/session', None), ('POST', '/api/admin/login', b'{"password":"wrong-password-123"}'),
              ('GET', '/admin/', None), ('GET', '/admin', None), ('GET', '/admin/app.js', None))

    def admin_headers(self, listener, forwarded=None, scheme='http'):
        headers = {'Origin': scheme + '://127.0.0.1:' + str(listener.app.server_port), 'Sec-Fetch-Site': 'same-origin',
                   'Content-Type': 'application/json'}
        if forwarded:
            headers['X-Forwarded-For'] = forwarded
        return headers

    def test_default_policy_denies_every_route_before_auth_state(self):
        listener = Listener(self)
        listener.configure_admin()
        manager = listener.app.admin_auth
        for method, path, body in self.ROUTES * 3:
            with self.subTest(method=method, path=path):
                status, _ = request(listener.app.server_port, method, path, body=body,
                                    headers=self.admin_headers(listener))
                self.assertEqual(status, 404)
        self.assertEqual((manager._preauth, manager._failures, manager._sessions), ({}, [], {}))

    def test_allowed_network_still_uses_the_existing_auth_gates(self):
        listener = Listener(self, admin_networks=('127.0.0.0/8',))
        listener.configure_admin()
        status, state = request(listener.app.server_port, 'GET', '/api/admin/session',
                                headers=self.admin_headers(listener))
        self.assertEqual((status, state['enabled'], state['authenticated']), (200, True, False))
        self.assertEqual(len(listener.app.admin_auth._preauth), 1)

    def test_forwarding_from_an_untrusted_peer_cannot_forge_admission(self):
        listener = Listener(self, admin_networks=('192.0.2.0/24',))
        listener.configure_admin()
        status, _ = request(listener.app.server_port, 'GET', '/api/admin/session',
                            headers=self.admin_headers(listener, forwarded='192.0.2.10'))
        self.assertEqual(status, 404)
        self.assertEqual(listener.app.admin_auth._preauth, {})

    def test_trusted_proxy_identity_is_the_admission_identity(self):
        listener = Listener(self, admin_networks=('192.0.2.0/24',), trusted_proxy_addresses=('127.0.0.1',))
        listener.configure_admin(scheme='https')
        port = listener.app.server_port
        allowed = request(port, 'GET', '/api/admin/session', headers=self.admin_headers(listener, '192.0.2.10', scheme='https'))
        denied = request(port, 'GET', '/api/admin/session', headers=self.admin_headers(listener, '198.51.100.10', scheme='https'))
        self.assertEqual((allowed[0], denied[0]), (200, 404))

    def test_proxy_loopback_cannot_bypass_http_authentication_policy(self):
        listener = Listener(self, admin_networks=('192.0.2.0/24',), trusted_proxy_addresses=('127.0.0.1',))
        listener.configure_admin()
        status, _ = request(listener.app.server_port, 'GET', '/api/admin/session',
                            headers=self.admin_headers(listener, '192.0.2.10'))
        self.assertEqual(status, 403)
        self.assertEqual(listener.app.admin_auth._preauth, {})

    def test_business_callback_receives_only_named_service_capabilities(self):
        listener = Listener(self)
        original = server.portal_routes.public_get
        with patch.object(server.portal_routes, 'public_get', wraps=original) as callback:
            status, _ = request(listener.app.server_port, 'GET', '/api/health')
        self.assertEqual(status, 200)
        services, validated = callback.call_args.args
        self.assertIsInstance(services, server.RouteServices)
        self.assertIsNot(services, listener.app)
        for name in ('socket', 'http_admission', '_request_threads', 'server_close', 'rfile'):
            self.assertFalse(hasattr(services, name), name)
        with self.assertRaises(AttributeError):
            services.root = None
        self.assertEqual(validated.path, '/api/health')


class ControlDiagnosticsTests(unittest.TestCase):
    def setUp(self):
        self.listener = Listener(self)
        self.control = BoundedHTTPServer(('127.0.0.1', 0), server.Handler, control=True, shared_app=self.listener.app)
        thread = threading.Thread(target=lambda: self.control.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        self.addCleanup(lambda: (self.control.shutdown(), self.control.server_close(), thread.join(2)))

    def test_public_listener_has_no_diagnostics(self):
        status, _ = request(self.listener.app.server_port, 'GET', '/api/diagnostics')
        self.assertEqual(status, 404)
        status, health = request(self.listener.app.server_port, 'GET', '/api/health')
        self.assertEqual((status, health['status']), (200, 'ok'))  # public liveness unchanged

    def test_control_snapshot_is_bounded_numeric_status_only(self):
        before = self.listener.app.engine_pool.stats()
        status, snapshot = request(self.control.server_port, 'GET', '/api/diagnostics')
        self.assertEqual(status, 200)
        self.assertEqual(set(snapshot), {'serviceStatus', 'generation', 'stopping', 'lanes', 'handlers', 'sampling'})
        self.assertEqual(snapshot['serviceStatus'], 'starting')  # zero workers with launch credit: lazy start
        self.assertEqual(snapshot['generation'], 0)
        for name, lane in snapshot['lanes'].items():
            self.assertEqual(set(lane), {'ready', 'busy', 'starting', 'unreaped', 'launchCredits',
                                         'startupCircuitOpen', 'retryAfterSeconds', 'status'})
            self.assertEqual(lane['status'], 'starting')
            self.assertTrue(all(type(lane[key]) is int and 0 <= lane[key] < 1048576
                                for key in ('ready', 'busy', 'starting', 'unreaped')))
        self.assertEqual(snapshot['handlers']['retained'], 0)
        self.assertEqual(self.listener.app.engine_pool.stats(), before)  # reading spawned or renewed nothing

    def test_retained_uncertain_handler_is_visible_and_never_released(self):
        app = self.listener.app
        marker = object()
        with app._request_threads_lock:
            app._request_threads_uncertain.add(marker)
        try:
            _, snapshot = request(self.control.server_port, 'GET', '/api/diagnostics')
            self.assertEqual((snapshot['serviceStatus'], snapshot['handlers']['retained']), ('degraded', 1))
            self.assertIn(marker, app._request_threads_uncertain)
        finally:
            with app._request_threads_lock:
                app._request_threads_uncertain.discard(marker)

    def test_internal_lane_content_is_not_part_of_the_diagnostic_dto(self):
        app = self.listener.app
        internal = app.engine_pool.diagnostics()
        internal['lanes']['feedback']['internalPayload'] = 'synthetic-private-canary'
        with patch.object(app.engine_pool, 'diagnostics', return_value=internal):
            status, result = request(self.control.server_port, 'GET', '/api/diagnostics')
        self.assertEqual(status, 200)
        self.assertNotIn('internalPayload', result['lanes']['feedback'])
        self.assertNotIn('synthetic-private-canary', json.dumps(result))

    def test_invalid_lane_scalar_is_refused(self):
        app = self.listener.app
        for field, value in (('launchCredits', True), ('retryAfterSeconds', False),
                             ('startupCircuitOpen', 'private-canary')):
            with self.subTest(field=field):
                internal = app.engine_pool.diagnostics()
                internal['lanes']['feedback'][field] = value
                with patch.object(app.engine_pool, 'diagnostics', return_value=internal):
                    status, _ = request(self.control.server_port, 'GET', '/api/diagnostics')
                self.assertEqual(status, 503)

    def test_stopping_dominates(self):
        self.listener.app.stopping = True
        try:
            _, snapshot = request(self.control.server_port, 'GET', '/api/diagnostics')
            self.assertEqual(snapshot['serviceStatus'], 'stopping')
        finally:
            self.listener.app.stopping = False


if __name__ == '__main__':
    unittest.main()
