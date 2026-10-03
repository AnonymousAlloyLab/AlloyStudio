"""Actual HTTP framing, authorization and real Alloy upload/publication witnesses."""
from copy import deepcopy
from http.client import HTTPConnection
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import admin_auth as auth
import admin_service
import admin_upload
import server
from runtime_dependencies import runtime_classpath
from test_admin_store import SOURCE
from test_sqlite_store import ROOT, fixture

PASSWORD = 'synthetic-admin-http-password'


class AdminHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.configuration = auth.configuration('http://127.0.0.1:8080', '/', PASSWORD)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        fixture(self.root)
        self.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', root=self.root)
        self.origin = 'http://127.0.0.1:' + str(self.app.server_port)
        self.config = deepcopy(self.configuration)
        self.config['origin'] = self.origin
        self.save()
        self.now = [1000.0]
        self.app.admin_auth = auth.AuthManager(self.root, clock=lambda: self.now[0])
        self.app.admin = admin_service.AdminService(self.app, self.app.admin_auth, clock=lambda: self.now[0])
        self.thread = threading.Thread(target=lambda: self.app.serve_forever(poll_interval=0.01), daemon=True)
        self.thread.start()
        self.jar, self.csrf = {}, None

    def tearDown(self):
        self.app.shutdown()
        self.app.server_close()
        self.thread.join(2)
        self.temporary.cleanup()

    def save(self):
        path = self.root / auth.CONFIG_NAME
        path.write_text(json.dumps(self.config))
        path.chmod(0o600)

    def headers(self):
        headers = {'Origin': self.config['origin'], 'Content-Type': 'application/json',
                   'Sec-Fetch-Site': 'same-origin'}
        if self.jar:
            headers['Cookie'] = '; '.join(key + '=' + value for key, value in self.jar.items())
        if self.csrf:
            headers['X-CSRF-Token'] = self.csrf
        return headers

    def capture(self, response):
        for header in response.headers.get_all('Set-Cookie', []):
            name, value = header.split(';', 1)[0].split('=', 1)
            if value:
                self.jar[name] = value
            else:
                self.jar.pop(name, None)
        return response.status, json.loads(response.read()), response.headers

    def request(self, method, path, payload=None, *, body=None, headers=None, timeout=10):
        connection = HTTPConnection('127.0.0.1', self.app.server_port, timeout=timeout)
        try:
            if body is None and payload is not None:
                body = json.dumps(payload).encode('utf-8')
            connection.request(method, path, body=body, headers=self.headers() if headers is None else headers)
            return self.capture(connection.getresponse())
        finally:
            connection.close()

    def raw(self, method, path, headers, body=None):
        connection = HTTPConnection('127.0.0.1', self.app.server_port, timeout=2)
        try:
            connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
            for name, value in headers:
                connection.putheader(name, value)
            connection.endheaders(body)
            return self.capture(connection.getresponse())
        finally:
            connection.close()

    def sign_in(self):
        status, state, _ = self.request('GET', '/api/admin/session')
        self.assertEqual(status, 200)
        self.assertTrue(state['enabled'])
        self.csrf = state['csrfToken']
        with patch.object(auth, 'derive_password', return_value=bytes.fromhex(self.config['password']['hash'])):
            status, state, headers = self.request('POST', '/api/admin/login', {'password': PASSWORD})
        self.assertEqual(status, 200)
        self.csrf = state['csrfToken']
        return state, headers

    def test_bootstrap_login_cookie_rotation_logout_and_private_cache_headers(self):
        status, initial, headers = self.request('GET', '/api/admin/session')
        self.assertEqual(status, 200)
        old = dict(self.jar)
        self.csrf = initial['csrfToken']
        state, headers = self.sign_in()
        self.assertTrue(state['authenticated'])
        self.assertFalse(set(old) & set(self.jar))
        self.assertEqual(headers['Cache-Control'], 'no-store, private')
        for name in ('HttpOnly', 'SameSite=Strict', 'Path=/'):
            self.assertIn(name, '\n'.join(headers.get_all('Set-Cookie')))
        self.assertNotIn('Access-Control-Allow-Origin', headers)
        status, state, headers = self.request('GET', '/api/admin/session')
        self.assertTrue(state['authenticated'])
        self.assertEqual(headers['Cache-Control'], 'no-store, private')
        self.assertEqual(self.request('POST', '/api/admin/logout', {})[0], 200)
        self.assertEqual(self.jar, {})
        self.assertEqual(self.request('POST', '/api/admin/prepare', {})[0], 401)

    def test_disabled_administration_has_no_body_read_or_work(self):
        (self.root / auth.CONFIG_NAME).unlink()
        self.assertEqual(self.request('GET', '/api/admin/session')[1], {'enabled': False, 'authenticated': False})
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('unauthorized body read')) as body, \
                patch.object(admin_service, 'prepare_upload') as solver, patch.object(admin_service, 'suggest') as provider:
            headers = self.headers() | {'Content-Length': '1024'}
            status, _, _ = self.request('POST', '/api/admin/prepare', headers=headers, timeout=2)
        self.assertEqual(status, 404)
        body.assert_not_called()
        solver.assert_not_called()
        provider.assert_not_called()

    def test_unauthenticated_mutations_are_rejected_before_unread_body_or_work(self):
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('unauthorized body read')) as body, \
                patch.object(admin_service, 'prepare_upload') as solver, patch.object(admin_service, 'suggest') as provider:
            for route in ('login', 'prepare', 'suggest', 'commit', 'discard', 'logout'):
                with self.subTest(route=route):
                    status, _, headers = self.request('POST', '/api/admin/' + route,
                                                     headers=self.headers() | {'Content-Length': '1048576'}, timeout=2)
                    self.assertEqual(status, 401)
                    self.assertEqual(headers['Cache-Control'], 'no-store, private')
        body.assert_not_called()
        solver.assert_not_called()
        provider.assert_not_called()

    def test_origin_csrf_and_fetch_metadata_are_required_before_read(self):
        self.sign_in()
        variants = [self.headers() | {'Origin': 'https://evil.example'},
                    self.headers() | {'X-CSRF-Token': '0' * 64},
                    self.headers() | {'Sec-Fetch-Site': 'cross-site'}]
        variants += [{k: v for k, v in self.headers().items() if k != missing}
                     for missing in ('Origin', 'X-CSRF-Token')]
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('read before authority')) as body:
            for headers in variants:
                status, _, _ = self.request('POST', '/api/admin/prepare',
                                             headers=headers | {'Content-Length': '1024'}, timeout=2)
                self.assertEqual(status, 403)
        body.assert_not_called()

    def test_ambiguous_framing_and_duplicate_relevant_cookie_reject_before_read(self):
        self.sign_in()
        base = [('Host', self.origin.removeprefix('http://'))] + list(self.headers().items()) + [('Content-Length', '1024')]
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('ambiguous body read')) as body:
            for name, value in (('Host', 'evil.example'), ('Origin', self.origin), ('Content-Length', '1024'),
                                ('Content-Type', 'application/json'), ('X-CSRF-Token', self.csrf),
                                ('Transfer-Encoding', 'chunked'), ('Sec-Fetch-Site', 'same-origin')):
                with self.subTest(header=name):
                    self.assertEqual(self.raw('POST', '/api/admin/prepare', base + [(name, value)])[0], 400)
            duplicate = base + [('Cookie', next(iter(self.jar)) + '=' + next(iter(self.jar.values())))]
            self.assertEqual(self.raw('POST', '/api/admin/prepare', duplicate)[0], 403)
        body.assert_not_called()

    def test_byte_limits_strict_json_and_route_fields_reject_without_worker(self):
        self.sign_in()
        with patch.object(admin_service, 'prepare_upload') as solver, patch.object(admin_service, 'suggest') as provider:
            for raw in (b'{"source":"a","source":"b"}', b'[]', b'{bad', b'\xff', b'{"x":NaN}'):
                self.assertEqual(self.request('POST', '/api/admin/prepare', body=raw)[0], 400)
            self.assertEqual(self.request('POST', '/api/admin/prepare', headers=self.headers() | {'Content-Length': '2097153'}, timeout=2)[0], 413)
            self.assertEqual(self.request('POST', '/api/admin/commit', {'id': 'a', 'exercises': []})[0], 400)
            self.assertEqual(self.request('POST', '/api/admin/logout', {'surprise': True})[0], 400)
            self.assertEqual(self.request('POST', '/api/admin/no-such-route', {})[0], 404)
            self.assertEqual(self.request('POST', '/api/admin/prepare', {}, headers=self.headers() | {'Content-Type': 'text/plain'})[0], 415)
        solver.assert_not_called()
        provider.assert_not_called()
        self.jar.clear()
        self.csrf = self.request('GET', '/api/admin/session')[1]['csrfToken']
        with patch.object(auth, 'derive_password') as derive:
            self.assertEqual(self.request('POST', '/api/admin/login', headers=self.headers() | {'Content-Length': '8193'}, timeout=2)[0], 413)
        derive.assert_not_called()

    def test_denied_json_does_not_extend_idle_session(self):
        self.sign_in()
        self.now[0] += 899
        self.assertEqual(self.request('POST', '/api/admin/prepare', body=b'{bad')[0], 400)
        self.now[0] += 1
        self.assertEqual(self.request('GET', '/api/admin/drafts/' + 'A' * 43)[0], 401)

    def test_session_expiring_while_body_is_parsed_cannot_start_solver(self):
        self.sign_in()
        original = server.Handler.admin_body
        def expire(handler, length, limit):
            result = original(handler, length, limit)
            self.now[0] += auth.IDLE_TTL
            return result
        with patch.object(server.Handler, 'admin_body', expire), patch.object(admin_service, 'prepare_upload') as solver:
            status, _, _ = self.request('POST', '/api/admin/prepare',
                                         {'source': 'sig Node {} pred inv1C0 { some Node }', 'filename': 'x.als', 'modelId': 'x'})
        self.assertEqual(status, 401)
        solver.assert_not_called()
        self.assertEqual(self.app.admin.drafts, {})

    def test_body_deadline_is_total_despite_continuing_network_progress(self):
        self.sign_in()
        connection = HTTPConnection('127.0.0.1', self.app.server_port, timeout=8)
        stopped = threading.Event()
        sent = []
        connection.putrequest('POST', '/api/admin/prepare')
        for name, value in (self.headers() | {'Content-Length': '100'}).items():
            connection.putheader(name, value)
        connection.endheaders(b'{')
        def trickle():
            while not stopped.wait(0.5):
                try:
                    connection.send(b' ')
                    sent.append(1)
                except OSError:
                    return
        writer = threading.Thread(target=trickle, daemon=True)
        started = time.monotonic()
        writer.start()
        try:
            status, result, _ = self.capture(connection.getresponse())
            elapsed = time.monotonic() - started
        finally:
            stopped.set()
            writer.join(2)
            connection.close()
        self.assertEqual(status, 400)
        self.assertIn('timed-out', result['error'])
        self.assertGreaterEqual(len(sent), 5)
        self.assertGreater(elapsed, 4)
        self.assertLess(elapsed, 7)
        self.assertEqual(self.app.admin.drafts, {})

    def test_unsupported_methods_use_private_json_errors_before_unread_body_or_work(self):
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('unexpected body read')) as reader, \
                patch.object(admin_service, 'prepare_upload') as solver, patch.object(admin_service, 'suggest') as provider:
            for route in ('/api/admin/session', '/api/%61dmin/session'):
                for method in ('HEAD', 'OPTIONS', 'PUT', 'PATCH', 'DELETE'):
                    with self.subTest(route=route, method=method):
                        connection = HTTPConnection('127.0.0.1', self.app.server_port, timeout=2)
                        try:
                            connection.request(method, route, headers=self.headers() | {'Content-Length': '1024'})
                            response = connection.getresponse()
                            self.assertEqual(response.status, 405)
                            self.assertEqual(response.headers['Allow'], 'GET, POST')
                            self.assertEqual(response.headers['Cache-Control'], 'no-store, private')
                            self.assertEqual(response.headers['X-Content-Type-Options'], 'nosniff')
                            self.assertEqual(response.headers['Referrer-Policy'], 'no-referrer')
                            self.assertEqual(response.headers.get_content_type(), 'application/json')
                            self.assertNotIn('Access-Control-Allow-Origin', response.headers)
                            body = response.read()
                            if method == 'HEAD':
                                self.assertEqual(body, b'')
                            else:
                                self.assertEqual(json.loads(body), {'error': 'Method not allowed.'})
                        finally:
                            connection.close()
        reader.assert_not_called()
        solver.assert_not_called()
        provider.assert_not_called()

    def test_encoded_administrator_route_cannot_bypass_private_route_policy(self):
        self.sign_in()
        for route in ('/api/%61dmin/session', '/%61pi/admin/session', '/api/admin%2fsession'):
            status, _, headers = self.request('GET', route)
            self.assertEqual(status, 404)
            self.assertEqual(headers['Cache-Control'], 'no-store, private')

    def test_private_credentials_sources_database_and_modules_have_no_http_route(self):
        for route in ('/admin.local.json', '/.admin-config-secret.tmp', '/scripts/configure_admin.py',
                      '/admin_auth.py', '/admin_upload.py', '/exercises/exercises.sqlite3',
                      '/exercises/exercises.sqlite3-journal', '/openai.local.json',
                      '/%2e%2e/admin.local.json', '/backend/admin.local.json'):
            with self.subTest(route=route):
                self.assertEqual(self.request('GET', route)[0], 404)

    def test_explicit_https_origin_works_through_loopback_proxy_without_trusting_forwarding(self):
        self.config['origin'] = 'https://studio.example.test'
        self.save()
        state, headers = self.sign_in()
        self.assertTrue(state['authenticated'])
        self.assertIn('__Host-', '\n'.join(headers.get_all('Set-Cookie')))
        self.assertIn('; Secure', '\n'.join(headers.get_all('Set-Cookie')))
        malicious = self.headers() | {'Origin': 'https://evil.example', 'X-Forwarded-Host': 'studio.example.test',
                                     'X-Forwarded-Proto': 'https', 'X-Forwarded-For': '127.0.0.1'}
        self.assertEqual(self.request('POST', '/api/admin/logout', {}, headers=malicious)[0], 403)

    def test_real_alloy_prepare_review_commit_and_immediate_public_projection(self):
        self.sign_in()
        with patch('admin_upload.runtime_classpath', return_value=runtime_classpath(ROOT)), \
                patch('exercise_store.runtime_classpath', return_value=runtime_classpath(ROOT)), \
                patch.object(admin_service, 'suggest') as provider:
            status, draft, _ = self.request('POST', '/api/admin/prepare',
                                           {'source': SOURCE, 'filename': 'uploaded.als', 'modelId': 'uploaded'})
            self.assertEqual(status, 202)
            deadline = time.monotonic() + 30
            while draft['state'] == 'preparing' and time.monotonic() < deadline:
                threading.Event().wait(0.02)
                status, draft, _ = self.request('GET', '/api/admin/drafts/' + draft['id'])
                self.assertEqual(status, 200)
            self.assertEqual(draft['state'], 'ready', draft)
            self.assertEqual([row['predicate'] for row in draft['groups']], ['inv1', 'inv2'])
            self.assertEqual([row['oracleCount'] for row in draft['groups']], [2, 2])
            self.assertNotIn('some Node', json.dumps(draft))
            metadata = [{'predicate': row['predicate'], 'title': 'Review ' + row['predicate'],
                         'question': 'Inspect the relation and constrain this property.'} for row in draft['groups']]
            status, result, _ = self.request('POST', '/api/admin/commit',
                                              {'id': draft['id'], 'revision': draft['revision'], 'exercises': metadata})
            self.assertEqual(status, 200, result)
            self.assertEqual(result['exerciseIds'], ['uploaded-inv1', 'uploaded-inv2'])
            self.assertEqual(result['exerciseCount'], 3)
            provider.assert_not_called()
        listing = self.request('GET', '/api/exercises')[1]['exercises']
        self.assertEqual([row['id'] for row in listing][-2:], ['uploaded-inv1', 'uploaded-inv2'])
        for identifier in result['exerciseIds']:
            status, public, _ = self.request('GET', '/api/exercises/' + identifier)
            self.assertEqual(status, 200)
            self.assertEqual(set(public), set(server.PUBLIC_FIELDS))
            for private in ('pred inv1C0', 'pred inv2C1', 'some Node', 'oracleSolutions', 'originalSource'):
                self.assertNotIn(private, json.dumps(public))
        self.assertEqual(self.app.snapshot.admin_uploads[0]['originalSource'].encode('utf-8'), SOURCE.encode('utf-8'))
        self.assertEqual(self.request('POST', '/api/admin/commit',
                                     {'id': draft['id'], 'revision': draft['revision'], 'exercises': metadata})[0], 409)


if __name__ == '__main__':
    unittest.main()
