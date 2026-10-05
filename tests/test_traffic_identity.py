"""AP01-C04/C11 identity: executable counterparts of the Identity.lean parser and resolver.

The Lean model takes canonical IP atoms from "a separately required strict
parser"; these tests pin that string parser and the resolver built on it.
"""
import ipaddress
import random
import unittest
from dataclasses import FrozenInstanceError
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import admin_auth
import portal_routes as routes
from portal_routes import FrozenHeaders

from traffic_identity import (ABSENT, REJECTED, AdminNetworkPolicy, canonical_address, nearest_untrusted,
                              parse_forwarding, resolve_identity, socket_identity, trusted_proxies)

CLIENT, ATTACKER, PROXY, EDGE = '10.0.0.10', '10.0.0.20', '10.0.0.30', '198.51.100.7'


def forwarded(value):
    return [('Host', 'example'), ('X-Forwarded-For', value)]


class CanonicalAddressTests(unittest.TestCase):
    def test_only_canonical_atoms_are_accepted(self):
        for text in ('127.0.0.1', '10.0.0.10', '::1', '2001:db8::1', 'fe80::1'):
            self.assertEqual(canonical_address(text), text)
        for text in ('', ' 10.0.0.1', '10.0.0.1 ', '10.0.0.1:80', '[::1]', '[::1]:443', 'fe80::1%eth0',
                     '::ffff:10.0.0.1', '::FFFF:10.0.0.1', '2001:DB8::1', '2001:0db8::1', '010.0.0.1',
                     '10.0.0', '10.0.0.256', 'localhost', '10.0.0.1/32', '１０.0.0.1', None, 3):
            with self.subTest(text=text):
                self.assertIsNone(canonical_address(text))

    def test_socket_peer_mapped_aliases_collapse_consistently(self):
        self.assertEqual(socket_identity('::ffff:127.0.0.1'), '127.0.0.1')
        self.assertEqual(socket_identity('127.0.0.1'), '127.0.0.1')
        self.assertIsNone(socket_identity('not-an-address'))
        self.assertIsNone(socket_identity('fe80::1%eth0'))

    def test_trusted_proxies_require_exact_canonical_addresses(self):
        self.assertEqual(trusted_proxies([]), frozenset())
        self.assertEqual(trusted_proxies(['127.0.0.1', '127.0.0.1']), frozenset({'127.0.0.1'}))
        for bad in ('127.0.0.0/8', '127.0.0.1:8080', '::ffff:127.0.0.1', 'proxy.local'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                trusted_proxies([bad])


class HeaderBoundaryTests(unittest.TestCase):
    def test_header_projection_is_immutable_and_detached_from_input(self):
        pairs = [('Origin', 'https://alloy.example'), ('X-Forwarded-For', CLIENT)]
        headers = FrozenHeaders(pairs)
        pairs.append(('Origin', 'https://changed.example'))
        self.assertEqual(headers.get_all('Origin'), ['https://alloy.example'])
        self.assertIsNone(headers.get('X-Forwarded-For'))
        with self.assertRaises(FrozenInstanceError):
            headers._pairs = ()
        exposed = headers.items()
        exposed.clear()
        self.assertEqual(headers.get('Origin'), 'https://alloy.example')


class AdministratorIdentityBoundaryTests(unittest.TestCase):
    def setup_manager(self, origin):
        configuration = {'origin': origin, 'basePath': '/',
                         'password': {'salt': '00' * 16, 'hash': '00' * 32}}
        replacement = patch.object(admin_auth, '_load_configuration', return_value=(configuration, 'synthetic'))
        replacement.start()
        self.addCleanup(replacement.stop)
        # The loader is replaced; no configuration or credential file is opened.
        return admin_auth.AuthManager(Path('build/ap01-resume-20261005/portal/auth-boundary/never-read'))

    def request(self, origin, identity, *, method='GET', path='/api/admin/session', extra=()):
        headers = FrozenHeaders([('Host', '127.0.0.1:8080'), ('Origin', origin), *extra])
        return routes.ValidatedRequest(method, path, 'public', '127.0.0.1', identity, headers)

    def test_proxy_cannot_convert_remote_http_administration_into_loopback(self):
        origin = 'http://127.0.0.1:8080'
        manager = self.setup_manager(origin)
        with self.assertRaises(admin_auth.AuthError) as raised:
            routes.admin_get(SimpleNamespace(admin_auth=manager), self.request(origin, '192.0.2.10'))
        self.assertEqual(raised.exception.status, 403)
        self.assertEqual((manager._preauth, manager._sessions, manager._failures), ({}, {}, []))

    def test_http_loopback_session_cannot_be_used_by_remote_forwarded_identity(self):
        origin = 'http://127.0.0.1:8080'
        manager = self.setup_manager(origin)
        app = SimpleNamespace(admin_auth=manager)
        response = routes.admin_get(app, self.request(origin, '127.0.0.1'))
        self.assertEqual(response.status, 200)
        extra = [('Cookie', response.cookies[0].split(';', 1)[0]), ('X-CSRF-Token', response.data['csrfToken'])]
        with self.assertRaises(admin_auth.AuthError) as raised:
            routes.admin_authorize(app, self.request(origin, '192.0.2.10', method='POST', path='/api/admin/login', extra=extra))
        self.assertEqual(raised.exception.status, 403)
        with self.assertRaises(admin_auth.AuthError) as raised:
            routes.admin_get(app, self.request(origin, '192.0.2.10', path='/api/admin/drafts/' + 'a' * 43, extra=extra))
        self.assertEqual(raised.exception.status, 403)
        self.assertEqual((len(manager._preauth), manager._sessions, manager._failures), (1, {}, []))

    def test_https_forwarded_client_preserves_origin_cookie_and_csrf_checks(self):
        origin = 'https://alloy.example'
        manager = self.setup_manager(origin)
        app = SimpleNamespace(admin_auth=manager)
        response = routes.admin_get(app, self.request(origin, '192.0.2.10'))
        self.assertEqual(response.status, 200)
        self.assertIn('; Secure', response.cookies[0])
        extra = [('Cookie', response.cookies[0].split(';', 1)[0]), ('X-CSRF-Token', response.data['csrfToken'])]
        principal = routes.admin_authorize(app, self.request(origin, '192.0.2.10', method='POST', path='/api/admin/login', extra=extra))
        self.assertFalse(principal.authenticated)
        for bad_origin, bad_extra in [(origin, extra[:1]), ('https://other.example', extra)]:
            with self.assertRaises(admin_auth.AuthError) as raised:
                routes.admin_authorize(app, self.request(bad_origin, '192.0.2.10', method='POST', path='/api/admin/login', extra=bad_extra))
            self.assertEqual(raised.exception.status, 403)


class ForwardingParserTests(unittest.TestCase):
    def test_bounded_single_field_contract(self):
        self.assertIs(parse_forwarding([('Host', 'x')]), ABSENT)
        self.assertEqual(parse_forwarding(forwarded(CLIENT + ', ' + PROXY)), [CLIENT, PROXY])
        self.assertIs(parse_forwarding(forwarded(CLIENT) + forwarded(PROXY)), REJECTED)  # duplicate field
        self.assertIs(parse_forwarding(forwarded('')), REJECTED)  # empty_forwarding_rejected
        self.assertIs(parse_forwarding(forwarded(CLIENT + ',,' + PROXY)), REJECTED)
        self.assertIs(parse_forwarding(forwarded(CLIENT + ', unknown')), REJECTED)  # invalid_token_rejected

    def test_hop_and_byte_boundaries(self):
        hops = ['10.0.0.%d' % index for index in range(1, 34)]
        self.assertEqual(len(parse_forwarding(forwarded(','.join(hops[:32])))), 32)
        self.assertIs(parse_forwarding(forwarded(','.join(hops[:33]))), REJECTED)
        padded = '10.0.0.1' + ' ' * (4096 - len('10.0.0.1'))
        self.assertEqual(parse_forwarding(forwarded(padded)), ['10.0.0.1'])
        self.assertIs(parse_forwarding(forwarded(padded + ' ')), REJECTED)  # oversized_forwarding_rejected

    def test_accepted_chain_is_bounded(self):
        rng = random.Random(32)
        for _ in range(500):
            tokens = [rng.choice(['10.0.0.%d' % rng.randint(0, 255), '::1', '', 'bad', '10.0.0.1:1'])
                      for _ in range(rng.randint(1, 40))]
            result = parse_forwarding(forwarded(', '.join(tokens)))
            if result not in (ABSENT, REJECTED):
                self.assertTrue(0 < len(result) <= 32)
                self.assertTrue(all(canonical_address(hop) == hop for hop in result))


class ResolverTests(unittest.TestCase):
    def test_default_trust_is_empty_and_headers_cannot_influence(self):
        rng = random.Random(7)
        for _ in range(300):
            header = ', '.join(rng.choice([CLIENT, ATTACKER, 'garbage', '', '::1']) for _ in range(rng.randint(0, 40)))
            items = forwarded(header) if rng.random() < .8 else []
            self.assertEqual(resolve_identity(frozenset(), PROXY, items), PROXY)
            self.assertEqual(resolve_identity(frozenset({EDGE}), PROXY, items), PROXY)

    def test_trusted_peer_never_falls_back_to_itself(self):
        trusted = frozenset({PROXY})
        for items in ([], forwarded(''), forwarded('garbage'), forwarded(CLIENT) + forwarded(CLIENT)):
            with self.subTest(items=items):
                self.assertIsNone(resolve_identity(trusted, PROXY, items))
        self.assertIsNone(resolve_identity(frozenset({PROXY, CLIENT}), PROXY, forwarded(CLIENT)))

    def test_right_to_left_nearest_untrusted_hop_wins(self):
        trusted = frozenset({PROXY, EDGE})
        self.assertEqual(resolve_identity(trusted, PROXY, forwarded(f'{CLIENT}, {EDGE}')), CLIENT)
        self.assertEqual(resolve_identity(trusted, PROXY, forwarded(f'{ATTACKER}, {CLIENT}, {EDGE}')), CLIENT)
        selected = nearest_untrusted(trusted, [ATTACKER, CLIENT, EDGE])
        self.assertNotIn(selected, trusted)  # selected_hop_is_untrusted

    def test_spoofed_left_prefix_has_no_influence(self):
        trusted = frozenset({PROXY, EDGE})
        rng = random.Random(99)
        for _ in range(300):
            prefix = ['10.%d.%d.%d' % (rng.randint(0, 255), rng.randint(0, 255), rng.randint(0, 255))
                      for _ in range(rng.randint(0, 20))]
            chain = prefix + [CLIENT, EDGE]
            self.assertEqual(resolve_identity(trusted, PROXY, forwarded(', '.join(chain))), CLIENT)

    def test_leftmost_selection_counterexample(self):
        chain = [ATTACKER, CLIENT]
        leftmost = chain[0]
        self.assertEqual(resolve_identity(frozenset({PROXY}), PROXY, forwarded(', '.join(chain))), CLIENT)
        self.assertNotEqual(leftmost, CLIENT)


class AdminNetworkPolicyTests(unittest.TestCase):
    def test_default_deny_and_exact_networks(self):
        self.assertFalse(AdminNetworkPolicy().admits('127.0.0.1'))
        policy = AdminNetworkPolicy(['127.0.0.0/8', '2001:db8::/32'])
        self.assertTrue(policy.admits('127.0.0.1'))
        self.assertTrue(policy.admits('2001:db8::5'))
        self.assertFalse(policy.admits('10.0.0.1'))
        self.assertFalse(policy.admits(None))
        self.assertFalse(policy.admits('garbage'))
        for alias in ('2001:DB8::5', '::ffff:127.0.0.1', '127.1', 2130706433, ['127.0.0.1']):
            with self.subTest(alias=alias):
                self.assertFalse(policy.admits(alias))
        for bad in ('127.0.0.1/8', ' 127.0.0.0/8', '127.0.0.0/33', '2001:DB8::/32', 'everyone'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                AdminNetworkPolicy([bad])

    def test_network_containment_matches_ipaddress(self):
        policy = AdminNetworkPolicy(['192.0.2.0/24'])
        for index in range(256):
            address = '192.0.2.%d' % index
            self.assertTrue(policy.admits(address))
        self.assertFalse(policy.admits(str(ipaddress.ip_address('192.0.3.0'))))


if __name__ == '__main__':
    unittest.main()
