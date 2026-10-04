"""Exercise the read-only IIS diagnostic against real HTTP responses."""
import importlib.util
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest


ROOT = Path(__file__).resolve().parents[1]
HELPER = ROOT / 'deploy/iis/test_api_connection.py'
spec = importlib.util.spec_from_file_location('iis_connection', HELPER)
connection = importlib.util.module_from_spec(spec)
spec.loader.exec_module(connection)
HEALTH = json.dumps({'status': 'ok', 'exercises': 181,
                     'engine': 'ACGN / CanDis Fast Rewrite IR'}).encode()
CANARY = 'PRIVATE_RESPONSE_AND_LOCATION_CANARY'


class IisConnectionTests(unittest.TestCase):
    def setUp(self):
        self.routes = {'/api/health': (200, 'application/json; charset=utf-8', HEALTH)}
        self.requests = []
        test = self

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                test.requests.append(self.path)
                status, mime, body = test.routes.get(self.path, (404, 'text/html', CANARY.encode()))
                self.send_response(status)
                self.send_header('Content-Type', mime)
                self.send_header('Content-Length', str(len(body)))
                if 300 <= status < 400:
                    self.send_header('Location', '/login?credential=' + CANARY)
                self.end_headers()
                if self.path == '/slow':
                    time.sleep(1)
                try:
                    self.wfile.write(body)
                except (BrokenPipeError, ConnectionResetError):
                    pass

            def log_message(self, *_args):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.origin = 'http://127.0.0.1:' + str(self.server.server_port)
        self.addCleanup(self.stop)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def diagnose(self):
        result = connection.diagnose(self.origin + '/alloy/',
                                     loopback_url=self.origin + '/api/health')
        self.assertNotIn(CANARY, json.dumps(result))
        self.assertNotIn(self.origin, json.dumps(result))
        return result

    def test_virtual_application_and_loopback_report_matching_health(self):
        self.routes['/alloy/api/health'] = (200, 'application/json', HEALTH)
        result = self.diagnose()
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(result['diagnosis'], 'healthy')
        self.assertTrue(result['same_catalogue_count'])
        self.assertEqual(self.requests, ['/alloy/api/health', '/api/health'])

    def test_iis_html_errors_preserve_status_without_body(self):
        for status in (404, 500, 502, 503):
            with self.subTest(status=status):
                self.routes['/alloy/api/health'] = (status, 'text/html; charset=utf-8', CANARY.encode())
                result = self.diagnose()
                self.assertEqual(result['diagnosis'], 'public_proxy_or_routing')
                self.assertEqual(result['public']['http_status'], status)
                self.assertEqual(result['public']['content_type'], 'html')
                self.assertTrue(result['loopback']['valid_health'])

    def test_authentication_status_and_redirect_are_distinct_and_not_followed(self):
        for status, expected in ((401, 'public_authentication_or_access'),
                                 (403, 'public_authentication_or_access'),
                                 (302, 'public_redirect')):
            with self.subTest(status=status):
                self.routes['/alloy/api/health'] = (status, 'text/html', CANARY.encode())
                self.assertEqual(self.diagnose()['diagnosis'], expected)
        self.assertTrue(all(path in ('/alloy/api/health', '/api/health') for path in self.requests))

    def test_html_200_invalid_json_and_wrong_health_shape_cannot_pass(self):
        for mime, body, error in (('text/html', CANARY.encode(), None),
                                  ('application/json', CANARY.encode(), 'invalid_json'),
                                  ('application/json', b'{"status":"ok"}', None),
                                  ('application/json', HEALTH.replace(b'181', b'true'), None)):
            with self.subTest(mime=mime, error=error):
                self.routes['/alloy/api/health'] = (200, mime, body)
                result = self.diagnose()
                self.assertEqual(result['status'], 'FAIL')
                self.assertFalse(result['public']['valid_health'])
                self.assertEqual(result['public']['error'], error)

    def test_mismatched_catalogue_and_local_backend_failure_are_reported(self):
        self.routes['/alloy/api/health'] = (200, 'application/json', HEALTH.replace(b'181', b'180'))
        result = self.diagnose()
        self.assertEqual(result['diagnosis'], 'catalogue_mismatch')
        self.assertFalse(result['same_catalogue_count'])
        self.routes['/api/health'] = (500, 'text/html', CANARY.encode())
        self.assertEqual(self.diagnose()['diagnosis'], 'public_available_local_backend_unavailable')
        self.routes['/alloy/api/health'] = (502, 'text/html', CANARY.encode())
        self.assertEqual(self.diagnose()['diagnosis'], 'local_backend_unhealthy')

    def test_unreachable_backend_is_distinct_from_html_proxy_failure(self):
        closed = ThreadingHTTPServer(('127.0.0.1', 0), BaseHTTPRequestHandler)
        port = closed.server_port
        closed.server_close()
        loopback = connection.probe('http://127.0.0.1:' + str(port) + '/api/health', timeout=1)
        self.assertEqual(loopback['error'], 'connection_failed')
        public = connection.classify_response(502, 'text/html', CANARY.encode())
        self.assertEqual(connection.diagnosis(public, loopback), 'local_backend_unavailable')

    def test_request_has_bounded_wall_time_and_body_size(self):
        self.routes['/slow'] = (200, 'application/json', HEALTH)
        started = time.monotonic()
        result = connection.probe(self.origin + '/slow', timeout=0.1)
        self.assertLess(time.monotonic() - started, 0.8)
        self.assertEqual(result['error'], 'timeout')
        self.routes['/large'] = (200, 'application/json', b' ' * (connection.MAX_RESPONSE_BYTES + 1))
        result = connection.probe(self.origin + '/large')
        self.assertEqual(result['error'], 'response_too_large')

    def test_credentials_queries_and_fragments_are_rejected_without_output(self):
        for url in ('https://user:' + CANARY + '@example.invalid',
                    'https://example.invalid/?credential=' + CANARY,
                    'https://example.invalid/#' + CANARY,
                    'https://example.invalid:99999/', 'file:///tmp/private'):
            with self.subTest(url_type=url.split(':', 1)[0]):
                result = subprocess.run([sys.executable, str(HELPER), '--public-url', url],
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 1)
                self.assertEqual(json.loads(result.stdout)['diagnosis'], 'invalid_public_url')
                self.assertNotIn(CANARY, result.stdout + result.stderr)
                self.assertEqual(result.stderr, '')


if __name__ == '__main__':
    unittest.main()
