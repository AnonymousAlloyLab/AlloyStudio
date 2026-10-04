"""Constructed call-graph mutations cannot inherit the ingress proof mapping."""
from pathlib import Path
import sys
import unittest
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'scripts'))
import strict_ingress_bridge as bridge
import strict_ingress_deadline_bridge as deadlines

class StrictIngressBridgeTests(unittest.TestCase):
    def test_current_interprocedural_bindings(self):
        result=bridge.check(ROOT)
        self.assertEqual(result['status'],'PASS')
        self.assertEqual(len(result['methodAstSha256']),12)
        self.assertEqual(len(result['readerAstSha256']),5)
        self.assertEqual(len(result['mappings']),17)
        self.assertEqual(deadlines.check(ROOT)['programs']['parseProgram'],['work','guard','deliver'])

    def test_missing_bypassed_or_reordered_validation_is_rejected(self):
        source=(ROOT/'server.py').read_text()
        mutations=(
            ('expected = strict_request_line(self.raw_requestline)', 'expected = (self.command, self.path, self.request_version)'),
            ('return strict_request_headers(self.headers.raw_items(), self.request_version,', 'return permissive_headers(self.headers.raw_items(), self.request_version,'),
            ('self.request_headers(mutation=self.command == \'POST\')', 'pass'),
            ('if not 0 < length <= limit:', 'if length < 0:'),
            ('data = bounded_json(', 'data = json.loads('),
            ('data = self.public_body()', 'data = {}'),
            ('data = self.admin_body(length,limit)', 'data = {}'),
            ('while remaining:', 'while remaining > 1:'),
            ('remaining -= len(chunk)', 'remaining -= len(chunk) + 1'),
            ('self.close_connection = True\n\n    def reply', 'self.close_connection = False\n\n    def reply'),
            ('self.server.evaluate(record, data[\'body\'], metric, **context)', 'self.server.unsafe_evaluate(record, data[\'body\'], metric, **context)'),
        )
        for before,after in mutations:
            with self.subTest(before=before):
                self.assertIn(before,source)
                with self.assertRaises(bridge.BridgeRejected):
                    bridge.extract(ROOT,server_source=source.replace(before,after,1))

    def test_request_reader_bounds_and_crlf_cannot_be_removed(self):
        source=(ROOT/'traffic_http.py').read_text()
        for before,after in [('len(result) > self.profile.line_bytes','len(result) > 999999999'),
                             ('self.header_remaining < 0','False'),
                             ("not result.endswith(b'\\r\\n')",'False'),
                             ('min(count, 4096)','count'),
                             ('self.header_lines > self.profile.header_count + 2','False')]:
            with self.subTest(before=before):
                self.assertIn(before,source)
                with self.assertRaises(bridge.BridgeRejected):
                    bridge.extract(ROOT,traffic_source=source.replace(before,after,1))

    def test_deadline_successor_exposes_missing_and_weakened_guards(self):
        source=(ROOT/'traffic_http.py').read_text()
        changed=source.replace('now >= self.deadline','now > self.deadline')
        self.assertNotEqual(deadlines.generate(ROOT), deadlines.generate(ROOT,traffic_source=changed))
        source=(ROOT/'server.py').read_text()
        changed=source.replace('            self.rfile.check_deadline()\n            return data', '            return data')
        self.assertEqual(deadlines.extraction(ROOT,server_source=changed)['programs']['bodyProgram'][-2:],['work','deliver'])

    def test_unmapped_handler_or_reader_override_is_rejected(self):
        source=(ROOT/'server.py').read_text()
        changed=source.replace("    server_version = 'AlloyPractice/1.0'",
            "    server_version = 'AlloyPractice/1.0'\n    def handle(self):\n        self.server.scheduler.issue_channel(self.client_address[0])")
        with self.assertRaisesRegex(bridge.BridgeRejected, 'complete ingress class'):
            bridge.extract(ROOT,server_source=changed)
        source=(ROOT/'traffic_http.py').read_text()
        changed=source.replace('class DeadlineReader:',
            'class DeadlineReader:\n    def __getattribute__(self, name):\n        return lambda *args: None')
        with self.assertRaisesRegex(bridge.BridgeRejected, 'complete ingress class'):
            bridge.extract(ROOT,traffic_source=changed)

if __name__=='__main__':unittest.main()
