"""Closed interpreter mutation controls and regex language correspondence examples."""
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import decoder_bridge as bridge
from lean_offline import installed_toolchain, isolated_command, clean_environment


def nullable(node):
    if node[0] == 'eps':
        return True
    if node[0] in ('empty', 'atom'):
        return False
    if node[0] == 'star':
        return True
    if node[0] == 'seq':
        return nullable(node[1]) and nullable(node[2])
    return nullable(node[1]) or nullable(node[2])


def derivative(node, character):
    tag = node[0]
    if tag in ('empty', 'eps'):
        return ['empty']
    if tag == 'atom':
        return ['eps'] if any(low <= character <= high for low, high in node[1]) else ['empty']
    if tag == 'seq':
        return ['alt', ['seq', derivative(node[1], character), node[2]],
                derivative(node[2], character) if nullable(node[1]) else ['empty']]
    if tag == 'alt':
        return ['alt', derivative(node[1], character), derivative(node[2], character)]
    return ['seq', derivative(node[1], character), node]


def language_accepts(node, text):
    for character in text:
        node = derivative(node, ord(character))
    return nullable(node)


class DecoderBridgeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.decoder = (ROOT / 'traffic_decode.py').read_text()
        cls.traffic = (ROOT / 'traffic_http.py').read_text()

    def test_checked_artifacts_match_actual_source_and_independent_contract(self):
        result = bridge.check(ROOT)
        self.assertEqual(result['status'], 'PASS')
        self.assertEqual(len(result['lineRules']), 7)
        self.assertEqual(len(result['headerRules']), 13)
        self.assertIn('bounded_json', result['semanticInterpretation'])

    def test_guard_removal_changes_extracted_rules_instead_of_inserting_virtual_guard(self):
        mutant = self.decoder.replace("    _require(not target.startswith('//'), 400, 'Invalid request target.')\n", '')
        extracted = bridge.extraction(ROOT, decoder_source=mutant)
        self.assertNotIn('originOnly', extracted['lineRules'])
        self.assertNotEqual(bridge.generate(ROOT, mutant), bridge.generate(ROOT))

    def test_unknown_calls_effects_and_predicate_changes_fail_closed(self):
        mutations = [self.decoder.replace("raw[:-2].decode('ascii')", "raw[:-2].decode('latin-1')"),
                     self.decoder.replace('not target.startswith', 'target.startswith'),
                     self.decoder.replace("return method, target, version", "return method, '/api/health', version"),
                     self.decoder.replace('len(values.get(name, [])) <= 1', 'len(values.get(name, [])) <= 2'),
                     self.decoder + '\nre.fullmatch = lambda *args: True\n',
                     self.decoder.replace('except ipaddress.AddressValueError:', 'except RuntimeError:'),
                     self.decoder.replace('int(suffix[1:]) > 65535', 'int(suffix[1:]) > 65536')]
        for mutant in mutations:
            with self.subTest(mutant=mutant[-100:]), self.assertRaises(bridge.BridgeRejected):
                bridge.extraction(ROOT, decoder_source=mutant)

    def test_json_policy_changes_fail_closed(self):
        for before, after in [('if key in result:', 'if False:'), ('depth > depth_limit', 'depth >= depth_limit'),
                              ('allow_nan=False', 'allow_nan=True'), ('type(value) is not dict', 'False'),
                              ('parse_constant=invalid', 'parse_constant=float')]:
            with self.subTest(before=before), self.assertRaises(bridge.BridgeRejected):
                bridge.extraction(ROOT, traffic_source=self.traffic.replace(before, after))

    def test_regex_extensions_and_hidden_flags_are_not_in_language(self):
        for pattern in ('(?=a)a', '(a)', '(?i)a', '[^x]', r'\w+', '(?:a){1,100}'):
            with self.subTest(pattern=pattern), self.assertRaises(bridge.BridgeRejected):
                bridge.regex_ast(pattern)

    def test_derivative_language_agrees_with_runtime_on_boundary_examples(self):
        data = bridge.extraction(ROOT)
        examples = {
            'METHOD_PATTERN': ['GET', 'HEAD', 'POST', '', 'G ET', 'GET\t', 'get', 'G\r'],
            'TARGET_PATTERN': ['/', '//other', '/a?b=%E2%98%83', '/%00', '/%', '/%GG', '/#x', '/a\\b', '/é'],
            'ESCAPED_FORBIDDEN_PATTERN': ['/%00', '/%1f', '/%7f', '/%5c', '/%20', '/%ff', '/x', '/%0a?x=1'],
            'HEADER_NAME_PATTERN': ['Host', 'X-CSRF-Token', '', 'Bad Name', 'X-É', 'Host:'],
            'HEADER_VALUE_PATTERN': ['', '\t x \t', '\x00', '\x7f', '\xff', '\r', '\n', '\u0100'],
            'HOST_PATTERN': ['localhost', '[::1]:80', 'a:65535', 'a:65536', 'a:123456', 'a:b', 'a,b', 'u@a'],
            'CONTENT_TYPE_PATTERN': ['application/json', 'application/json;charset=utf8',
                'application/json; charset="utf-8"', 'application/json;', 'application/json;x=1',
                'application/json;charset=utf8;charset=utf8', 'application/json;charset=utf16'],
            'CONTENT_LENGTH_PATTERN': ['', '0', '1', '99999999', '100000000', '00', '+1', '١'],
        }
        for name, samples in examples.items():
            for sample in samples:
                with self.subTest(pattern=name, sample=sample):
                    self.assertEqual(language_accepts(data['grammar'][name], sample),
                                     re.fullmatch(data['constants'][name], sample) is not None)

    @unittest.skipUnless(sys.platform == 'linux', 'Offline Lean negative controls require Linux namespaces')
    def test_removed_guard_and_widened_regex_fail_independent_lean_contract(self):
        try:
            _, toolchain = installed_toolchain(ROOT)
        except FileNotFoundError:
            self.skipTest('Pinned Lean toolchain is not installed for this Python unit job')
        lean = str(toolchain / 'bin' / 'lean')
        mutants = {
            'missing-origin-guard': self.decoder.replace(
                "    _require(not target.startswith('//'), 400, 'Invalid request target.')\n", ''),
            'widened-method': re.sub(r'^METHOD_PATTERN =.*$', 'METHOD_PATTERN = r".*"',
                                     self.decoder, flags=re.MULTILINE),
        }
        self.assertNotEqual(mutants['widened-method'], self.decoder)
        scratch = ROOT / 'build' / 'trf-closure' / 'decoder-bridge-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        for name, mutant in mutants.items():
            with self.subTest(name=name), tempfile.TemporaryDirectory(dir=scratch) as directory:
                target = Path(directory)
                for path in ('DecoderModel.lean', 'DecoderContract.lean', 'Decoder.lean'):
                    shutil.copyfile(ROOT / 'formal/ingress_admission' / path, target / path)
                (target / 'DecoderExtracted.lean').write_text(bridge.generate(ROOT, mutant))
                env = clean_environment(toolchain, target)
                env['ELAN_HOME'] = str(toolchain.parents[1])
                for module in ('DecoderModel', 'DecoderContract', 'DecoderExtracted'):
                    result = subprocess.run(isolated_command([lean, '-DgenInjectivity=false', '-DmaxRecDepth=8192',
                        '-DmaxHeartbeats=4000000', '-o', str(target / (module + '.olean')),
                        str(target / (module + '.lean'))]), env=env, capture_output=True, text=True, timeout=30)
                    self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                result = subprocess.run(isolated_command([lean, '-DgenInjectivity=false', '-DmaxRecDepth=8192',
                    '-DmaxHeartbeats=4000000', str(target / 'Decoder.lean')]), env=env,
                    capture_output=True, text=True, timeout=30)
                self.assertNotEqual(result.returncode, 0, 'Mutated decoder was accepted by independent specification')
                self.assertIn('error:', result.stdout + result.stderr)


if __name__ == '__main__':
    unittest.main()
