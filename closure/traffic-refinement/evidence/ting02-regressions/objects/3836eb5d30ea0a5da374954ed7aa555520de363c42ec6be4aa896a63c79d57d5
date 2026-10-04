"""Finite administrator-authentication and credential-publication witnesses."""
from contextlib import nullcontext, redirect_stderr, redirect_stdout
from copy import deepcopy
from email.message import Message
from io import StringIO
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import threading
import unittest
import warnings
from unittest import mock

import admin_auth as auth
from scripts import configure_admin


PASSWORD = 'synthetic-test-password-only'
PEER = ('127.0.0.1', 45000)


class AdminAuthTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.fixture = auth.configuration('http://127.0.0.1:8080', '/', PASSWORD)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.path = self.root / auth.CONFIG_NAME
        self.config = deepcopy(self.fixture)
        self.save()
        self.now = [1000.0]
        self.manager = auth.AuthManager(self.root, clock=lambda: self.now[0])
        self.jar = {}

    def save(self):
        self.path.write_text(json.dumps(self.config) + '\n')
        self.path.chmod(0o600)

    def headers(self, *, origin=True, csrf=None, host='127.0.0.1:8080', jar=None):
        headers = Message()
        headers['Host'] = host
        if origin:
            headers['Origin'] = self.config['origin'] if origin is True else origin
        headers['Sec-Fetch-Site'] = 'same-origin'
        cookies = self.jar if jar is None else jar
        if cookies:
            headers['Cookie'] = '; '.join(name + '=' + value for name, value in cookies.items())
        if csrf is not None:
            headers['X-CSRF-Token'] = csrf
        return headers

    def cookies(self, cookies):
        for header in cookies:
            name, value = header.split(';', 1)[0].split('=', 1)
            if value:
                self.jar[name] = value
            else:
                self.jar.pop(name, None)

    def bootstrap(self):
        state, cookies = self.manager.bootstrap(self.headers(), PEER)
        self.cookies(cookies)
        return state

    def sign_in(self, *, real=False):
        state = self.bootstrap()
        principal = self.manager.authorize(self.headers(csrf=state['csrfToken']), PEER, preauth=True)
        patch = nullcontext() if real else mock.patch.object(auth, 'derive_password', return_value=bytes.fromhex(self.config['password']['hash']))
        with patch:
            state, cookies = self.manager.login(principal, PASSWORD)
        self.cookies(cookies)
        principal = self.manager.authorize(self.headers(csrf=state['csrfToken']), PEER)
        return principal, state

    def rejected(self, status, operation):
        with self.assertRaises(auth.AuthError) as raised:
            operation()
        self.assertEqual(raised.exception.status, status)

    def test_real_scrypt_login_rotation_csrf_and_logout(self):
        old = self.bootstrap()
        anonymous_cookie = dict(self.jar)
        principal, state = self.sign_in(real=True)
        self.assertTrue(state['enabled'] and state['authenticated'])
        self.assertNotEqual(old['csrfToken'], state['csrfToken'])
        self.assertFalse(set(anonymous_cookie) & set(self.jar))
        self.assertNotIn(next(iter(self.jar.values())), repr(self.manager._sessions))
        self.rejected(401, lambda: self.manager.authorize(self.headers(jar=anonymous_cookie, csrf=old['csrfToken']), PEER, preauth=True))
        self.cookies(self.manager.logout(principal))
        self.assertEqual(self.jar, {})
        self.rejected(401, lambda: self.manager.validate(principal))

    def test_disabled_missing_malformed_nonprivate_or_linked_configuration(self):
        self.path.unlink()
        self.assertEqual(self.manager.bootstrap(self.headers(), PEER), ({'enabled': False, 'authenticated': False}, []))
        for content in ('{}', '[]', '{"origin":"a","origin":"b"}', '{bad JSON'):
            self.path.write_text(content)
            self.path.chmod(0o600)
            self.assertIsNone(self.manager.settings)
        self.save()
        self.path.chmod(0o644)
        if os.name != 'nt':
            self.assertIsNone(self.manager.settings)
        self.path.unlink()
        target = self.root / 'linked-secret.json'
        target.write_text(json.dumps(self.config))
        target.chmod(0o600)
        self.path.symlink_to(target)
        self.assertIsNone(self.manager.settings)

    def test_wrong_password_costs_or_noncanonical_configurations_disable(self):
        for changes in ({'n': 1}, {'n': True}, {'r': 1}, {'p': 2}, {'dklen': 16},
                        {'salt': '00'}, {'hash': '00'}, {'algorithm': 'plain'}):
            self.config = deepcopy(self.fixture)
            self.config['password'].update(changes)
            self.save()
            with self.subTest(changes=changes):
                self.assertIsNone(self.manager.settings)
        self.config = deepcopy(self.fixture)
        self.config['basePath'] = '/app/../'
        self.save()
        self.assertIsNone(self.manager.settings)

    def test_bootstrap_preserves_authenticated_session_and_reuses_preauth(self):
        first = self.bootstrap()
        jar = dict(self.jar)
        second = self.bootstrap()
        self.assertEqual((first, jar), (second, self.jar))
        principal, state = self.sign_in()
        old_cookie = dict(self.jar)
        refreshed, cookies = self.manager.bootstrap(self.headers(), PEER)
        self.assertEqual(refreshed, state)
        self.assertEqual(cookies, [])
        self.assertEqual(self.jar, old_cookie)
        self.manager.validate(principal)

    def test_production_proxy_host_and_cookie_flags(self):
        self.config.update(origin='https://as.example.test', basePath='/alloy/')
        self.save()
        state, cookies = self.manager.bootstrap(self.headers(host='127.0.0.1:8080'), PEER)
        self.assertTrue(state['enabled'])
        self.assertTrue(cookies[0].startswith('__Host-'))
        for token in ('; Secure', '; HttpOnly', '; SameSite=Strict', '; Path=/'):
            self.assertIn(token, cookies[0])
        self.assertNotIn('Domain=', cookies[0])
        production_name = self.manager.settings.preauth_cookie
        self.config['basePath'] = '/other/'
        self.save()
        self.assertNotEqual(self.manager.settings.preauth_cookie, production_name)

    def test_origin_loopback_peer_host_and_forwarding_are_checked(self):
        state = self.bootstrap()
        for headers, peer in ((self.headers(origin=False, csrf=state['csrfToken']), PEER),
                              (self.headers(origin='https://evil.test', csrf=state['csrfToken']), PEER),
                              (self.headers(host='evil.test', csrf=state['csrfToken']), PEER),
                              (self.headers(csrf=state['csrfToken']), ('192.0.2.1', 4000))):
            headers['X-Forwarded-Host'] = '127.0.0.1:8080'
            headers['X-Forwarded-For'] = '127.0.0.1'
            headers['X-Forwarded-Proto'] = 'https'
            self.rejected(403, lambda: self.manager.authorize(headers, peer, preauth=True))
        headers = self.headers(csrf=state['csrfToken'])
        headers.replace_header('Sec-Fetch-Site', 'cross-site')
        self.rejected(403, lambda: self.manager.authorize(headers, PEER, preauth=True))

    def test_duplicate_headers_cookies_wrong_tokens_and_transfer_encoding_reject(self):
        state = self.bootstrap()
        for name, value in (('Origin', self.config['origin']), ('Content-Length', '1'),
                            ('Cookie', next(iter(self.jar)) + '=' + next(iter(self.jar.values()))),
                            ('X-CSRF-Token', state['csrfToken'])):
            headers = self.headers(csrf=state['csrfToken'])
            if name == 'Content-Length':
                headers[name] = value
            headers[name] = value
            self.rejected(403, lambda: self.manager.authorize(headers, PEER, preauth=True))
        headers = self.headers(csrf='0' * 64)
        self.rejected(403, lambda: self.manager.authorize(headers, PEER, preauth=True))
        headers = self.headers(csrf=state['csrfToken'])
        headers['Transfer-Encoding'] = 'chunked'
        self.rejected(400, lambda: self.manager.authorize(headers, PEER, preauth=True))

    def test_idle_renewal_is_explicit_and_absolute_expiry_still_wins(self):
        principal, state = self.sign_in()
        self.now[0] += 899
        self.manager.authorize(self.headers(csrf=state['csrfToken']), PEER)
        self.now[0] += 1
        self.rejected(401, lambda: self.manager.validate(principal))
        self.jar.clear()
        principal, state = self.sign_in()
        self.now[0] += 800
        self.manager.touch(principal)
        self.now[0] += 800
        self.manager.touch(principal)
        self.now[0] += 200
        self.rejected(401, lambda: self.manager.validate(principal))

    def test_preauth_expiry_does_not_extend_on_bootstrap(self):
        first = self.bootstrap()
        self.now[0] += 299
        self.assertEqual(self.bootstrap(), first)
        self.now[0] += 1
        self.rejected(401, lambda: self.manager.authorize(self.headers(csrf=first['csrfToken']), PEER, preauth=True))

    def test_password_rotation_during_kdf_cannot_create_session(self):
        state = self.bootstrap()
        principal = self.manager.authorize(self.headers(csrf=state['csrfToken']), PEER, preauth=True)
        def rotate(*_):
            old_hash = bytes.fromhex(self.config['password']['hash'])
            self.config['password']['salt'] = '11' * 16
            self.save()
            return old_hash
        with mock.patch.object(auth, 'derive_password', side_effect=rotate):
            self.rejected(401, lambda: self.manager.login(principal, PASSWORD))
        self.assertEqual(self.manager._sessions, {})

    def test_rotation_disabling_and_logout_revoke_publication_authority(self):
        principal, _ = self.sign_in()
        self.config['password']['salt'] = '22' * 16
        self.save()
        self.rejected(401, lambda: self.manager.validate(principal))
        self.jar.clear()
        principal, _ = self.sign_in()
        self.path.unlink()
        self.rejected(404, lambda: self.manager.validate(principal))

    def test_publication_guard_serializes_concurrent_logout(self):
        principal, _ = self.sign_in()
        started, finished = threading.Event(), threading.Event()
        def logout():
            started.set()
            self.manager.logout(principal)
            finished.set()
        with self.manager.guard(principal):
            worker = threading.Thread(target=logout)
            worker.start()
            self.assertTrue(started.wait(1))
            self.assertFalse(finished.wait(0.03))
        worker.join(1)
        self.assertTrue(finished.is_set())
        self.rejected(401, lambda: self.manager.validate(principal))

    def test_global_failed_login_limit_and_single_kdf_slot(self):
        state = self.bootstrap()
        principal = self.manager.authorize(self.headers(csrf=state['csrfToken']), PEER, preauth=True)
        self.manager._kdf.acquire()
        try:
            with mock.patch.object(auth, 'derive_password') as derive:
                self.rejected(429, lambda: self.manager.login(principal, PASSWORD))
                derive.assert_not_called()
        finally:
            self.manager._kdf.release()
        with mock.patch.object(auth, 'derive_password', return_value=b'\0' * 32) as derive:
            for _ in range(5):
                self.rejected(401, lambda: self.manager.login(principal, PASSWORD))
            self.rejected(429, lambda: self.manager.login(principal, PASSWORD))
            self.assertEqual(derive.call_count, 5)
            self.now[0] += 60
            self.rejected(401, lambda: self.manager.login(principal, PASSWORD))
            self.assertEqual(derive.call_count, 6)

    def test_preauth_and_authenticated_stores_have_hard_caps(self):
        first = self.bootstrap()
        first_jar = dict(self.jar)
        for _ in range(auth.PREAUTH_CAP - 1):
            self.jar.clear()
            self.bootstrap()
        self.jar.clear()
        self.rejected(429, self.bootstrap)
        self.assertEqual(len(self.manager._preauth), auth.PREAUTH_CAP)
        state, _ = self.manager.bootstrap(self.headers(jar=first_jar), PEER)
        self.assertEqual(state, first)
        self.manager = auth.AuthManager(self.root, clock=lambda: self.now[0])
        for _ in range(auth.SESSION_CAP):
            self.jar.clear()
            self.sign_in()
        self.jar.clear()
        self.rejected(429, self.sign_in)
        self.assertEqual(len(self.manager._sessions), auth.SESSION_CAP)

    def test_origin_base_path_and_password_input_boundaries(self):
        for origin in ('http://public.example', 'https://user:secret@example.test', 'https://example.test/a',
                       'https://example.test?', 'https://example.test#', 'http://localhost', 'http://localhost.evil.test'):
            with self.subTest(origin=origin), self.assertRaises(ValueError):
                auth.normalize_origin(origin)
        self.assertEqual(auth.normalize_origin('http://[::1]:8080'), 'http://[::1]:8080')
        for path in ('relative', '/a/../', '//app/', '/a%2fb/', '/app\\evil/'):
            with self.subTest(path=path), self.assertRaises(ValueError):
                auth.normalize_base_path(path)
        for password in ('', 'a' * 11, 'a' * 1025, 'valid-prefix\0suffix', '\ud800' * 12):
            with self.assertRaises(ValueError):
                auth.password_bytes(password)
        self.assertEqual(len(auth.password_bytes('é' * 6)), 12)
        self.assertEqual(len(auth.password_bytes('a' * 1024)), 1024)


class AdminConfigurationTests(unittest.TestCase):
    def test_private_atomic_configuration_requires_explicit_replacement(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = {'synthetic': 'no-live-secret'}
            with mock.patch.object(configure_admin, 'configuration', return_value=config):
                path = configure_admin.write_configuration(root, 'https://example.test', '/', PASSWORD)
                before = path.read_bytes()
                if os.name != 'nt':
                    self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
                with self.assertRaises(ValueError):
                    configure_admin.write_configuration(root, 'https://example.test', '/', PASSWORD)
                self.assertEqual(path.read_bytes(), before)
                with mock.patch.object(configure_admin.os, 'replace', side_effect=OSError('constructed failure')):
                    with self.assertRaises(OSError):
                        configure_admin.write_configuration(root, 'https://example.test', '/', PASSWORD, replace=True)
                self.assertEqual(path.read_bytes(), before)
                self.assertEqual(list(root.glob('.admin-config-*.tmp')), [])
                path.unlink()
                target = root / 'other.json'
                target.write_text('preserve')
                path.symlink_to(target)
                with self.assertRaises(ValueError):
                    configure_admin.write_configuration(root, 'https://example.test', '/', PASSWORD, replace=True)
                self.assertEqual(target.read_text(), 'preserve')

    def test_cli_uses_hidden_prompts_and_never_prints_password_or_failure_detail(self):
        secret = 'PRIVATE_SENTINEL-password'
        for passwords in ((secret, 'mismatch'), (secret, secret)):
            output, error = StringIO(), StringIO()
            with mock.patch.object(sys, 'argv', ['configure_admin.py', '--origin', 'https://example.test']), \
                    mock.patch.object(configure_admin.getpass, 'getpass', side_effect=passwords), \
                    mock.patch.object(configure_admin, 'write_configuration', side_effect=OSError(secret)) as writer, \
                    redirect_stdout(output), redirect_stderr(error):
                self.assertEqual(configure_admin.main(), 1)
            self.assertNotIn(secret, output.getvalue() + error.getvalue())
            if passwords[0] != passwords[1]:
                writer.assert_not_called()

    def test_cli_refuses_getpass_echo_fallback(self):
        def insecure_prompt(*_):
            warnings.warn('Cannot control echo on the terminal.', configure_admin.getpass.GetPassWarning)
            return PASSWORD
        with mock.patch.object(sys, 'argv', ['configure_admin.py', '--origin', 'https://example.test']), \
                mock.patch.object(configure_admin.getpass, 'getpass', side_effect=insecure_prompt), \
                mock.patch.object(configure_admin, 'write_configuration') as writer, redirect_stderr(StringIO()):
            self.assertEqual(configure_admin.main(), 1)
        writer.assert_not_called()


if __name__ == '__main__':
    unittest.main()
