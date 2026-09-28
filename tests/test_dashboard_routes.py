"""Real HTTP checks for the public dashboard's finite static-file boundary."""
from http.client import HTTPConnection
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import server


class DashboardRouteTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0))
        cls.thread = threading.Thread(target=cls.app.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join()

    def request(self, path):
        # Preserve the literal request target and observe redirects directly.
        connection = HTTPConnection('127.0.0.1', self.app.server_port, timeout=10)
        try:
            connection.request('GET', path)
            response = connection.getresponse()
            return response.status, response.headers, response.read()
        finally:
            connection.close()

    @staticmethod
    def csp(headers):
        return {parts[0]: parts[1:] for directive in headers['Content-Security-Policy'].split(';')
                if (parts := directive.split())}

    def test_dashboard_redirect_uses_relative_trailing_slash_location(self):
        status, headers, body = self.request('/dashboard')
        self.assertEqual(status, 308)
        self.assertEqual(headers['Location'], 'dashboard/')
        self.assertEqual(headers['Content-Length'], '0')
        self.assertEqual(body, b'')

    def test_dashboard_directory_serves_its_own_index(self):
        status, headers, body = self.request('/dashboard/')
        self.assertEqual(status, 200)
        self.assertEqual(headers['Content-Type'], 'text/html; charset=utf-8')
        self.assertEqual(body, (ROOT / 'web/dashboard/index.html').read_bytes())
        self.assertEqual(body, self.request('/dashboard/index.html')[2])
        self.assertNotEqual(body, (ROOT / 'web/index.html').read_bytes())

    def test_four_allowlisted_dashboard_assets_keep_current_bytes_and_types(self):
        types = {'index.html': 'text/html', 'app.js': 'text/javascript',
                 'styles.css': 'text/css', 'data.json': 'application/json'}
        for name, content_type in types.items():
            for query in ('', '?v=fixture-content-hash'):
                with self.subTest(asset=name, query=query):
                    status, headers, body = self.request('/dashboard/' + name + query)
                    self.assertEqual(status, 200)
                    self.assertEqual(headers['Content-Type'], content_type + '; charset=utf-8')
                    self.assertEqual(body, (ROOT / 'web/dashboard' / name).read_bytes())
                    self.assertEqual(headers['Content-Length'], str(len(body)))
                    self.assertEqual(headers['Cache-Control'], 'no-store')
                    self.assertEqual(headers['X-Content-Type-Options'], 'nosniff')

    def test_github_connection_permission_is_confined_to_dashboard_responses(self):
        for path in ('/dashboard/', '/dashboard/index.html', '/dashboard/app.js',
                     '/dashboard/styles.css', '/dashboard/data.json'):
            with self.subTest(path=path):
                status, headers, _ = self.request(path)
                self.assertEqual(status, 200)
                policy = self.csp(headers)
                self.assertEqual(policy['connect-src'], ["'self'", 'https://api.github.com'])
                for directive in ('default-src', 'script-src', 'style-src'):
                    self.assertEqual(policy[directive], ["'self'"])
                self.assertEqual(policy['object-src'], ["'none'"])
                self.assertEqual(policy['frame-ancestors'], ["'none'"])
        for path in ('/', '/index.html', '/app.js', '/styles.css', '/api/health', '/dashboard-other/'):
            with self.subTest(path=path):
                status, headers, _ = self.request(path)
                self.assertEqual(status, 404 if path == '/dashboard-other/' else 200)
                self.assertEqual(self.csp(headers)['connect-src'], ["'self'"])
                self.assertNotIn('api.github.com', headers['Content-Security-Policy'])

    def test_private_and_unknown_dashboard_files_are_not_served_even_when_present(self):
        paths = ('private.txt', 'openai.local.json', '.env', 'secrets/openai.key',
                 'exercises/correct-pools.json', 'server.py', 'web.config',
                 'app.js.map', 'data.json.bak', 'README.md')
        with tempfile.TemporaryDirectory(prefix='dashboard-private-files-') as directory:
            root = Path(directory)
            for name in paths:
                candidate = root / 'web/dashboard' / name
                candidate.parent.mkdir(parents=True, exist_ok=True)
                candidate.write_text('PRIVATE_DASHBOARD_FIXTURE', encoding='utf-8')
            with patch.object(self.app, 'root', root):
                for name in paths:
                    with self.subTest(path=name):
                        status, _, body = self.request('/dashboard/' + name)
                        self.assertEqual(status, 404)
                        self.assertEqual(json.loads(body), {'error': 'Not found.'})
                        self.assertNotIn(b'PRIVATE_DASHBOARD_FIXTURE', body)

    def test_dashboard_traversal_and_allowlist_suffixes_are_rejected(self):
        paths = ('/dashboard/../index.html', '/dashboard/../../server.py',
                 '/dashboard/%2e%2e/%2e%2e/server.py', '/dashboard/..%2f..%2fserver.py',
                 '/dashboard/%2e%2e%5cserver.py', '/dashboard/%252e%252e/server.py',
                 '/dashboard/../exercises/correct-pools.json', '/dashboard/data.json/extra',
                 '/dashboard/app.js.map', '/dashboard/data.json%00', '/dashboard/APP.JS')
        for path in paths:
            with self.subTest(path=path):
                status, _, body = self.request(path)
                self.assertEqual(status, 404)
                self.assertEqual(json.loads(body), {'error': 'Not found.'})


if __name__ == '__main__':
    unittest.main()
