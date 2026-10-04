"""Independent examples at raw syntax and live-dispatch boundaries."""
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from traffic_decode import strict_request_line, strict_request_headers
from traffic_http import HTTPInputError, bounded_json


class StrictDecoderTests(unittest.TestCase):
    def reject_line(self, raw):
        with self.assertRaises(HTTPInputError) as error:
            strict_request_line(raw)
        self.assertEqual(error.exception.status, 400)

    def test_request_line_preserves_browser_targets_and_supported_versions(self):
        for method in ('GET', 'HEAD', 'POST', 'OPTIONS'):
            for version in ('HTTP/1.0', 'HTTP/1.1'):
                for target in ('/', '/api/health', '/path/%E2%98%83?q=a%20b&x[]=1', '/?x=%23%3F%2F'):
                    with self.subTest(method=method, version=version, target=target):
                        self.assertEqual(strict_request_line(f'{method} {target} {version}\r\n'.encode()),
                                         (method, target, version))

    def test_request_line_rejects_every_ambiguous_separator_and_legacy_version(self):
        for raw in (b'GET /api/health\r HTTP/1.1\r\n', b'GET\t/api/health HTTP/1.1\r\n',
                    b'GET / HTTP/1.1\n', b'GET / HTTP/1.1\r\n\r\n', b'GET /\r\n',
                    b'GET  / HTTP/1.1\r\n', b'GET /  HTTP/1.1\r\n', b' GET / HTTP/1.1\r\n',
                    b'GET / HTTP/1.1 \r\n', b'GET / HTTP/2.0\r\n', b'GET / HTTP/01.1\r\n',
                    b'GET / HTTP/1.01\r\n', b'GET / HTTP/1.2\r\n', b'GET / HTTP/0.9\r\n'):
            with self.subTest(raw=raw):
                self.reject_line(raw)

    def test_request_targets_reject_controls_fragments_absolute_and_bad_escapes(self):
        targets = [b'http://elsewhere/api/health', b'//elsewhere/api/health', b'*', b'elsewhere:443',
                   b'/#fragment', b'/\\admin', b'/truncated%', b'/short%1', b'/%GG', b'/snowman\xe2\x98\x83']
        targets += [b'/bad' + bytes([byte]) for byte in (*range(33), 127)]
        targets += [b'/bad%' + f'{byte:02X}'.encode() for byte in (*range(32), 127, 92)]
        for target in targets:
            with self.subTest(target=target):
                self.reject_line(b'GET ' + target + b' HTTP/1.1\r\n')

    def test_host_is_version_sensitive_and_authority_is_unambiguous(self):
        self.assertEqual(strict_request_headers([], 'HTTP/1.0'), 0)
        for host in ('localhost', 'EXAMPLE.test:443', '127.0.0.1:0', '[::1]:65535', '[2001:db8::1]',
                     '[::ffff:192.0.2.1]:80'):
            self.assertEqual(strict_request_headers([('Host', host)], 'HTTP/1.1'), 0)
        for pairs in ([], [('Host', '')], [('Host', 'a'), ('host', 'b')]):
            with self.subTest(pairs=pairs), self.assertRaises(HTTPInputError):
                strict_request_headers(pairs, 'HTTP/1.1')
        for host in ('localhost:65536', 'localhost:123456', 'localhost:', 'localhost:+80', 'user@localhost',
                     'localhost/path', 'localhost,other', 'local host', '[:::]', '[1.2.3.4]', '[fe80::1%lo]'):
            with self.subTest(host=host), self.assertRaises(HTTPInputError):
                strict_request_headers([('Host', host)], 'HTTP/1.1')

    def test_media_type_grammar_uses_raw_occurrences_and_unique_parameter(self):
        for value in ('application/json', 'Application/JSON; Charset=UTF-8', 'application/json;charset=utf8',
                      'application/json ; charset = "utf-8"', 'application/json; charset="UTF8"'):
            self.assertEqual(strict_request_headers([('Host', 'localhost'), ('Content-Type', value),
                                                   ('Content-Length', '2')], 'HTTP/1.1', mutation=True), 2)
        for value in ('application/json; charset=utf-8; charset=utf-16',
                      'application/json; charset=utf-8; charset=utf-8',
                      'application/json; boundary=x', 'application/json;', 'application/json; charset=',
                      'application/json; charset=utf-16', 'application/json; charset="utf-8',
                      'text/plain', 'application/json,application/json'):
            with self.subTest(value=value), self.assertRaises(HTTPInputError) as error:
                strict_request_headers([('Host', 'localhost'), ('Content-Type', value)], 'HTTP/1.1', mutation=True)
            self.assertEqual(error.exception.status, 415)

    def test_framing_rejects_duplicates_unknown_encodings_and_noncanonical_lengths(self):
        for name in ('Host', 'Content-Length', 'Content-Type', 'Origin', 'Cookie', 'Authorization',
                     'X-CSRF-Token', 'Sec-Fetch-Site', 'Expect', 'If-None-Match', 'Content-Encoding'):
            with self.subTest(name=name), self.assertRaises(HTTPInputError):
                strict_request_headers([('Host', 'localhost'), (name, 'x'), (name.lower(), 'x')], 'HTTP/1.1')
        for pair in (('Transfer-Encoding', ''), ('Transfer-Encoding', 'chunked'), ('Expect', ''),
                     ('Expect', '100-continue'), ('Content-Encoding', 'gzip'), ('Bad Name', 'x'),
                     ('X-Bad', '\x00'), ('X-Bad', 'x\r\ny'), ('X-Bad', '\u0100')):
            with self.subTest(pair=pair), self.assertRaises(HTTPInputError):
                strict_request_headers([('Host', 'localhost'), pair], 'HTTP/1.1')
        for length in ('+2', '-2', '02', '1, 1', '1.0', '١', '100000000'):
            with self.subTest(length=length), self.assertRaises(HTTPInputError):
                strict_request_headers([('Host', 'localhost'), ('Content-Length', length)], 'HTTP/1.1')
        with self.assertRaises(HTTPInputError):
            strict_request_headers([('Host', 'localhost'), ('Content-Length', '1')], 'HTTP/1.1')

    def test_json_nested_duplicate_unicode_finite_depth_and_root_rules(self):
        for raw in (b'{"x":{"a":1,"\\u0061":2}}', b'{"x":"\\ud800"}', b'{"x":"\\udfff"}',
                    b'{"x":1e999}', b'{"x":-1e999}', b'{"\\ud800":1}', b'{"x":NaN}', b'{"x":Infinity}',
                    b'[]', b'null', b'\xef\xbb\xbf{}', b'{"x":"\xc0\x80"}', b'{"x":"\xff"}'):
            with self.subTest(raw=raw), self.assertRaises(HTTPInputError):
                bounded_json(raw)
        self.assertEqual(bounded_json(b'{"x":[1]}', 2), {'x': [1]})
        with self.assertRaises(HTTPInputError):
            bounded_json(b'{"x":[1]}', 1)
        self.assertEqual(bounded_json(b'{"x":"{ [ \\""}', 1), {'x': '{ [ "'})
        self.assertEqual(bounded_json(b'{"x":"\\ud83d\\ude00"}'), {'x': '\U0001f600'})


class StrictDecoderSocketTests(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location('strict_http_fixture', ROOT / 'tests/test_traffic_http.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.fixture = module.HTTPBoundaryTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)

    def test_discovery_witnesses_cannot_reach_route_handler(self):
        import server
        requests = [b'GET /api/health\r HTTP/1.1\r\nHost: localhost\r\n\r\n',
                    b'GET\t/api/health HTTP/1.1\r\nHost: localhost\r\n\r\n',
                    b'GET /api/health\r\n\r\n', b'GET /api/health HTTP/1.1\r\n\r\n',
                    b'GET http://elsewhere/api/health HTTP/1.1\r\nHost: localhost\r\n\r\n',
                    b'GET /api/health HTTP/1.1\r\nHost: localhost\r\n'
                    b'Content-Type: application/json; charset=utf-8; charset=utf-16\r\n\r\n']
        dispatches = []
        original = server.Handler.do_GET
        def observe(handler):
            dispatches.append(handler.path)
            return original(handler)
        with patch.object(server.Handler, 'do_GET', observe):
            for request in requests:
                with self.subTest(request=request):
                    response = self.fixture.raw(request)
                    self.assertIn(response.split(b'\r\n')[0].split()[1], (b'400', b'415'))
        self.assertEqual(dispatches, [])

    def test_valid_browser_and_http10_compatibility(self):
        for raw in (b'GET /api/health HTTP/1.0\r\n\r\n',
                    b'GET /api/health?x=%E2%98%83 HTTP/1.1\r\nHost: localhost\r\n\r\n',
                    b'HEAD /api/health HTTP/1.1\r\nHost: localhost\r\n\r\n'):
            with self.subTest(raw=raw):
                result = self.fixture.raw(raw)
                self.assertIn(b' 501 ' if raw.startswith(b'HEAD') else b' 200 ', result.split(b'\r\n')[0])
                if raw.startswith(b'HEAD'):
                    self.assertEqual(result.partition(b'\r\n\r\n')[2], b'')


if __name__ == '__main__':
    unittest.main()
