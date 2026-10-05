"""AP01-B08 successor correspondence: ingress preserved, business separated.

Each mutation is constructed from the current sources and must be rejected for
the stated reason. These are structural regressions, not a Lean proof.
"""
from pathlib import Path
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import ingress_boundary_bridge as bridge


class IngressBoundaryBridgeTests(unittest.TestCase):
    def setUp(self):
        self.server = (ROOT / 'server.py').read_text()
        self.routes = (ROOT / 'portal_routes.py').read_text()
        self.traffic = (ROOT / 'traffic_http.py').read_text()

    def rejects(self, reason, **sources):
        with self.assertRaisesRegex(bridge.BridgeRejected, reason):
            bridge.check(ROOT, **sources)

    def replaced(self, text, old, new):
        self.assertEqual(text.count(old), 1, old)
        return text.replace(old, new)

    def test_current_boundary_passes_with_trf01_ingress_methods(self):
        result = bridge.check(ROOT)
        self.assertEqual(result['status'], 'PASS')
        self.assertIn('read_json_body', result['ingressMethodsMatchingTrf01'])
        self.assertEqual(set(result['businessTypes']), {'ValidatedRequest', 'Reply'})

    def test_changed_ingress_method_is_rejected(self):
        changed = self.replaced(self.server, 'if not 0 < length <= limit:', 'if not 0 < length <= limit + 1:')
        self.rejects('TRF-01 template: Handler.read_json_body', server_source=changed)

    def test_reader_change_is_rejected(self):
        changed = self.replaced(self.traffic, "raise HTTPInputError(400, 'Request lines must use CRLF.')",
                                "pass")
        self.rejects('DeadlineReader differs', traffic_source=changed)

    def test_bypass_override_is_rejected(self):
        changed = self.replaced(self.server, "    server_version = 'AlloyPractice/1.0'\n",
                                "    server_version = 'AlloyPractice/1.0'\n\n    def handle(self):\n        pass\n")
        self.rejects('Unregistered or missing Handler method', server_source=changed)

    def test_inherited_bypass_and_decorated_or_metaclass_handler_are_rejected(self):
        original = 'class Handler(BaseHTTPRequestHandler):'
        inherited = ('class AlternateTransport(BaseHTTPRequestHandler):\n'
                     '    def handle(self):\n'
                     '        self.unvalidated_handled = True\n\n\n'
                     'class Handler(AlternateTransport):')
        for replacement in (inherited, '@rewrite_handler\n' + original,
                            'class Handler(BaseHTTPRequestHandler, metaclass=AlternateMeta):'):
            with self.subTest(replacement=replacement):
                changed = self.replaced(self.server, original, replacement)
                self.rejects('class header or inheritance', server_source=changed)

    def test_missing_or_malformed_registry_is_a_deterministic_rejection(self):
        base = ROOT / 'build/ingress-boundary-tests'
        base.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='registry-', dir=base) as directory:
            path = Path(directory) / bridge.BOUNDARY_REGISTRY
            with self.assertRaisesRegex(bridge.BridgeRejected, 'missing or unreadable'):
                bridge.check_boundary_registry(directory, self.server)
            path.parent.mkdir(parents=True)
            for content in ('{', '[]', '{"schemaVersion":1,"astSha256":[]}',
                            '{"schemaVersion":true,"astSha256":{}}'):
                path.write_text(content)
                with self.subTest(content=content), self.assertRaises(bridge.BridgeRejected):
                    bridge.check_boundary_registry(directory, self.server)

    def test_callback_before_body_validation_is_rejected(self):
        changed = self.replaced(self.server, "            data = self.public_body()\n",
                                "            portal_routes.public_post(self.server, None)\n            data = self.public_body()\n")
        self.rejects('order violated|exactly once', server_source=changed)

    def test_admin_policy_must_precede_authorization(self):
        changed = self.replaced(
            self.server,
            "            length = self.admin_headers(mutation=True)\n            if not self.admitted_administration():\n",
            "            length = self.admin_headers(mutation=True)\n            if False:\n")
        self.rejects('Handler.admin_POST', server_source=changed)

    def test_dead_validation_and_inverted_guard_are_rejected(self):
        dead = self.replaced(self.server, "            self.admin_headers()\n",
                             "            if False:\n                self.admin_headers()\n")
        self.rejects('boundary control flow', server_source=dead)
        inverted = self.server.replace('if not self.admitted_administration():',
                                       'if self.admitted_administration():', 1)
        self.assertNotEqual(inverted, self.server)
        self.rejects('boundary control flow', server_source=inverted)

    def test_raw_application_cannot_be_passed_as_route_capability(self):
        changed = self.replaced(self.server,
            'portal_routes.public_post(route_services(self.server), self.validated(path, data))',
            'portal_routes.public_post(self.server, self.validated(path, data))')
        self.rejects('boundary control flow or capabilities', server_source=changed)

    def test_dynamic_ingress_access_is_rejected(self):
        self.rejects('dynamic ingress', routes_source=self.routes +
                     "\ndef leak(app):\n    return getattr(app, 'socket')\n")

    def test_business_cannot_reach_ingress_state(self):
        for attribute in ('rfile', 'http_admission', 'raw_items', 'client_address'):
            with self.subTest(attribute=attribute):
                changed = self.routes + '\n\ndef leak(app):\n    return app.' + attribute + '\n'
                self.rejects('reaches ingress state', routes_source=changed)
        self.rejects('imports an ingress module', routes_source=self.routes + '\nimport socket\n')

    def test_channels_use_resolved_identity_not_peer(self):
        changed = self.replaced(self.routes, 'app.scheduler.issue_channel(request.identity)',
                                'app.scheduler.issue_channel(request.peer)')
        self.rejects('resolved quota identity', routes_source=changed)

    def test_mutable_request_is_rejected(self):
        changed = self.replaced(self.routes, '@dataclass(frozen=True)\nclass ValidatedRequest:',
                                '@dataclass\nclass ValidatedRequest:')
        self.rejects('immutable dataclass', routes_source=changed)


if __name__ == '__main__':
    unittest.main()
