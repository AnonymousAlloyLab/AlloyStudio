"""Constructed raw-wire correspondence witnesses for the repaired composition."""
from http.client import parse_headers
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts')]
# TRF-01 is VERIFIED at this recorded source root. AP01 changed the current
# checkout (scripts/source_freshness.py classifies it STALE); this historical
# correspondence is replayed against the frozen evidence inputs, read-only.
HISTORICAL = ROOT / 'closure/traffic-refinement/evidence/ting02-20261004T201301Z-9d697815/inputs'
import wire_ingress_bridge as bridge
import strict_ingress_bridge
from lean_offline import installed_toolchain, isolated_command, clean_environment
from traffic_decode import strict_request_line, strict_request_headers
from traffic_http import DeadlineReader, HTTPInputError, TrafficProfile


class BytesConnection:
    def __init__(self, raw):
        self.raw = raw
    def settimeout(self, _):
        pass
    def recv(self, count):
        result, self.raw = self.raw[:count], self.raw[count:]
        return result


class WireCorrespondenceTests(unittest.TestCase):
    def test_source_wire_identity_bridge(self):
        result = bridge.check(HISTORICAL)
        self.assertEqual((result['status'], result['unmapped'], result['ambiguous']), ('PASS', 0, 0))
        self.assertEqual(len(result['bindings']), 6)

    def test_luna_free_mutation_witness_cannot_describe_actual_post(self):
        method, _, version = strict_request_line(b'POST /api/feedback HTTP/1.1\r\n')
        fields = [('Host', 'localhost')]
        # This demonstrates precisely why the prior free input was unsound.
        self.assertEqual(strict_request_headers(fields, version, mutation=False), 0)
        self.assertEqual(method, 'POST')
        with self.assertRaises(HTTPInputError) as error:
            strict_request_headers(fields, version, mutation=method == 'POST')
        self.assertEqual(error.exception.status, 415)

    def test_raw_header_pair_interpretation_preserves_occurrences_and_ows(self):
        raw_line = b'POST / HTTP/1.1\r\n'
        raw_headers = [b'Host:\t localhost \t\r\n', b'X-Example: first:colon\r\n',
                       b'X-Example:\tsecond\xff \r\n', b'\r\n']
        reader = DeadlineReader(BytesConnection(raw_line + b''.join(raw_headers)), TrafficProfile())
        self.assertEqual(reader.readline(), raw_line)
        fields = list(parse_headers(reader).raw_items())
        normalized = [(name, value.strip(' \t')) for name, value in fields]
        self.assertEqual(normalized, [('Host', 'localhost'), ('X-Example', 'first:colon'),
                                      ('X-Example', 'second\xff')])
        self.assertEqual(reader.profile.header_bytes - reader.header_remaining,
                         len(raw_line) + sum(map(len, raw_headers)))
        self.assertEqual(reader.header_lines, 1 + len(raw_headers))

    def test_missing_terminal_blank_and_folded_fields_fail_actual_reader(self):
        for headers in (b'Host: localhost\r\n', b' Host: localhost\r\n\r\n',
                        b'Host: local\nhost\r\n\r\n', b'Host : localhost\r\n\r\n'):
            with self.subTest(headers=headers):
                reader = DeadlineReader(BytesConnection(b'GET / HTTP/1.1\r\n' + headers), TrafficProfile())
                reader.readline()
                with self.assertRaises(HTTPInputError):
                    parse_headers(reader)

    def test_source_mutation_and_body_byte_aliases_are_rejected(self):
        source = (HISTORICAL / 'server.py').read_text()
        traffic = (HISTORICAL / 'traffic_http.py').read_text()
        for before, after in [("self.request_headers(mutation=self.command == 'POST')", 'self.request_headers(mutation=False)'),
                              ('strict_request_line(self.raw_requestline)', "strict_request_line(b'GET / HTTP/1.1\\r\\n')"),
                              ("bounded_json(b''.join(chunks),", "bounded_json(b'{}',"),
                              ('self.headers.raw_items()', 'self.headers.items()')]:
            with self.subTest(before=before), self.assertRaises((bridge.BridgeRejected, strict_ingress_bridge.BridgeRejected)):
                bridge.check(HISTORICAL, server_source=source.replace(before, after))
        with self.assertRaises((bridge.BridgeRejected, strict_ingress_bridge.BridgeRejected)):
            bridge.check(HISTORICAL, traffic_source=traffic.replace('self.header_remaining -= len(result)',
                                                              'self.header_remaining -= 0'))

    @unittest.skipUnless(sys.platform == 'linux', 'Offline Lean controls require Linux namespaces')
    def test_raw_wire_theorems_compile_with_empty_transitive_axioms(self):
        try:
            _, toolchain = installed_toolchain(ROOT)
        except FileNotFoundError:
            self.skipTest('Pinned Lean toolchain is not installed for this Python unit job')
        scratch = ROOT / 'build' / 'trf-closure' / 'wire-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=scratch) as directory:
            target = Path(directory)
            env = clean_environment(toolchain, target)
            env['ELAN_HOME'] = str(toolchain.parents[1])
            lean = str(toolchain / 'bin' / 'lean')
            flags = ['-DgenInjectivity=false', '-DmaxRecDepth=8192', '-DmaxHeartbeats=4000000']
            for name in ('DecoderModel', 'DecoderExtracted', 'DecoderContract', 'Decoder', 'IngressWire'):
                shutil.copyfile(HISTORICAL / 'formal/ingress_admission' / (name + '.lean'), target / (name + '.lean'))
                result = subprocess.run(isolated_command([lean, *flags, '-o', str(target / (name + '.olean')),
                    str(target / (name + '.lean'))]), env=env, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            audit = target / 'WireAudit.lean'
            audit.write_text('''import Lean
import IngressWire
open Lean Elab Command
run_elab do
  let env ← getEnv
  for (name, info) in env.constants.toList do
    if !(name.toString.startsWith "AlloyStudio.IngressWire") then continue
    match info with
    | .thmInfo _ | .defnInfo _ =>
      let axioms ← collectAxioms name
      if !axioms.isEmpty then throwError "Wire axiom dependencies: {name}: {axioms}"
    | .axiomInfo _ => throwError "Project axiom {name}"
    | _ => pure ()
''')
            result = subprocess.run(isolated_command([lean, *flags, str(audit)]), env=env,
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
