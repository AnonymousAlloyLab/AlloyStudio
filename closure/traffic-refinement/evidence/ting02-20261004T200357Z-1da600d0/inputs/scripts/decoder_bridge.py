"""Closed source-to-rule interpreter for the actual strict HTTP decoder.

Regex ASTs are translated to a derivative language evaluated over raw strings in
Lean. Each require is translated from its concrete expression, not source hashes
or an assumed decoder-valid Boolean. CPython primitives and the recorded closed
statement/call interpretation are the explicit TCB.
"""
import ast
import copy
import hashlib
import json
from pathlib import Path
try:
    from re import _parser as regex_parser, _constants as rc
except ImportError:  # CPython 3.10 exposes the same closed parser separately.
    import sre_parse as regex_parser
    import sre_constants as rc


class BridgeRejected(ValueError):
    pass


def dump(node):
    return ast.dump(node, include_attributes=False)


RULE_EXPRESSIONS = {
    'crlf': "raw.endswith(b'\\r\\n')",
    'threeParts': 'len(parts) == 3',
    'methodToken': 're.fullmatch(METHOD_PATTERN, method) is not None',
    'version': 'version in VERSIONS',
    'targetGrammar': 're.fullmatch(TARGET_PATTERN, target) is not None',
    'originOnly': "not target.startswith('//')",
    'noEscapedControls': 're.fullmatch(ESCAPED_FORBIDDEN_PATTERN, target.lower()) is None',
    'headerNames': 're.fullmatch(HEADER_NAME_PATTERN, name) is not None',
    'headerValues': 're.fullmatch(HEADER_VALUE_PATTERN, value) is not None',
    'singletons': 'len(values.get(name, [])) <= 1',
    'hostRequired': "version != 'HTTP/1.1' or len(hosts) == 1",
    'hostGrammar': 'not hosts or _host_valid(hosts[0])',
    'noTransferEncoding': "'transfer-encoding' not in values",
    'identityEncoding': "values.get('content-encoding', ['identity'])[0].lower() == 'identity'",
    'noExpect': "'expect' not in values",
    'contentTypeRequired': 'not mutation or len(content_types) == 1',
    'contentTypeGrammar': 'not content_types or re.fullmatch(CONTENT_TYPE_PATTERN, content_types[0].lower()) is not None',
    'lengthGrammar': 're.fullmatch(CONTENT_LENGTH_PATTERN, length) is not None',
    'bodylessRead': 'mutation or int(length) == 0',
}
PREDICATES = {dump(ast.parse(value, mode='eval').body): key for key, value in RULE_EXPRESSIONS.items()}
PATTERNS = ('METHOD_PATTERN', 'TARGET_PATTERN', 'ESCAPED_FORBIDDEN_PATTERN', 'HEADER_NAME_PATTERN',
            'HEADER_VALUE_PATTERN', 'HOST_PATTERN', 'CONTENT_TYPE_PATTERN', 'CONTENT_LENGTH_PATTERN')
FUNCTIONS = ('_require', 'strict_request_line', '_host_valid', 'strict_request_headers')


def require_call(node):
    return (isinstance(node, ast.Expr) and isinstance(node.value, ast.Call)
            and isinstance(node.value.func, ast.Name) and node.value.func.id == '_require')


class WithoutGuards(ast.NodeTransformer):
    def visit_Expr(self, node):
        return None if require_call(node) else self.generic_visit(node)

    def visit_For(self, node):
        self.generic_visit(node)
        if not node.body:
            node.body = [ast.Pass()]
        return node


def guards(node):
    for child in ast.iter_child_nodes(node):
        if require_call(child):
            call = child.value
            if len(call.args) != 3 or call.keywords:
                raise BridgeRejected('Unknown require primitive invocation')
            key = PREDICATES.get(dump(call.args[0]))
            if key is None:
                raise BridgeRejected('Unknown strict decoder predicate: ' + ast.unparse(call.args[0]))
            yield key
        else:
            yield from guards(child)


def trees(source):
    tree = ast.parse(source)
    functions, constants, other = {}, {}, []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef):
            if node.name in functions:
                raise BridgeRejected('Duplicate decoder function')
            functions[node.name] = node
        elif isinstance(node, ast.Assign):
            if len(node.targets) != 1 or not isinstance(node.targets[0], ast.Name):
                raise BridgeRejected('Unknown decoder assignment')
            name = node.targets[0].id
            if name in constants:
                raise BridgeRejected('Duplicate decoder constant')
            try:
                constants[name] = ast.literal_eval(node.value)
            except (ValueError, TypeError):
                raise BridgeRejected('Nonliteral decoder constant') from None
        else:
            other.append(node)
    return functions, constants, [dump(item) for item in other]


def extraction(root, decoder_source=None, traffic_source=None):
    root = Path(root)
    actual = (root / 'traffic_decode.py').read_text() if decoder_source is None else decoder_source
    functions, constants, other = trees(actual)
    reference, ref_constants, ref_other = trees((root / 'formal/ingress_admission/decoder-template.py.txt').read_text())
    if set(functions) != set(FUNCTIONS) or set(constants) != set(PATTERNS) | {'VERSIONS', 'SINGLETON_HEADERS'}:
        raise BridgeRejected('Unregistered decoder function or constant')
    if other != ref_other:
        raise BridgeRejected('Unregistered top-level effects/imports')
    for name in FUNCTIONS:
        current = WithoutGuards().visit(copy.deepcopy(functions[name]))
        expected = WithoutGuards().visit(copy.deepcopy(reference[name]))
        if dump(current) != dump(expected):
            raise BridgeRejected('Unregistered statement, primitive or call order: ' + name)
    # Required lexical primitives have explicit closed regular language syntax.
    regexes = {name: regex_ast(constants[name]) for name in PATTERNS}
    if any(type(value) is not tuple or any(type(item) is not str for item in value)
           for value in (constants['VERSIONS'], constants['SINGLETON_HEADERS'])):
        raise BridgeRejected('Invalid version/singleton tuple')
    line = list(guards(functions['strict_request_line']))
    headers = [('headerVersion' if rule == 'version' else rule)
               for rule in guards(functions['strict_request_headers'])]
    traffic = (root / 'traffic_http.py').read_text() if traffic_source is None else traffic_source
    json_nodes = [node for node in ast.parse(traffic).body if isinstance(node, ast.FunctionDef) and node.name == 'bounded_json']
    json_reference = ast.parse((root / 'formal/ingress_admission/json-template.py.txt').read_text()).body[0]
    if len(json_nodes) != 1 or dump(json_nodes[0]) != dump(json_reference):
        raise BridgeRejected('Unregistered JSON scan, duplicate hook, root or finite-Unicode serialization policy')
    return {'grammar': regexes, 'constants': constants, 'lineRules': line, 'headerRules': headers,
            'functions': {name: hashlib.sha256(dump(value).encode()).hexdigest() for name, value in functions.items()},
            'jsonAstSha256': hashlib.sha256(dump(json_nodes[0]).encode()).hexdigest()}


def seq(parts):
    if not parts:
        return ['eps']
    result = parts[0]
    for part in parts[1:]:
        result = ['seq', result, part]
    return result


def alt(parts):
    if not parts:
        return ['empty']
    result = parts[0]
    for part in parts[1:]:
        result = ['alt', result, part]
    return result


def regex_ast(pattern):
    if type(pattern) is not str:
        raise BridgeRejected('Regex must be a literal string')
    try:
        parsed = regex_parser.parse(pattern, 0)
    except Exception as error:
        raise BridgeRejected('Invalid regex: ' + type(error).__name__) from None
    if parsed.state.flags != 32:  # implicit Python Unicode mode, no inline modifiers
        raise BridgeRejected('Regex flags are not registered')
    def convert(nodes):
        parts = []
        for op, value in nodes:
            if op == rc.LITERAL:
                parts.append(['atom', [[value, value]]])
            elif op == rc.ANY:
                parts.append(['atom', [[0, 9], [11, 0x10ffff]]])
            elif op == rc.IN:
                ranges = []
                for child_op, child_value in value:
                    if child_op == rc.LITERAL:
                        ranges.append([child_value, child_value])
                    elif child_op == rc.RANGE:
                        ranges.append(list(child_value))
                    else:
                        raise BridgeRejected('Unknown character-set operator')
                parts.append(['atom', ranges])
            elif op == rc.BRANCH:
                parts.append(alt([convert(branch) for branch in value[1]]))
            elif op == rc.SUBPATTERN:
                group, add, delete, child = value
                if group is not None or add or delete:
                    raise BridgeRejected('Only plain noncapturing groups are registered')
                parts.append(convert(child))
            elif op == rc.MAX_REPEAT:
                low, high, child = value
                if low > 16 or high != rc.MAXREPEAT and high > 16:
                    raise BridgeRejected('Unsupported bounded regex repetition')
                element = convert(child)
                repeated = [element] * low
                if high == rc.MAXREPEAT:
                    repeated.append(['star', element])
                else:
                    repeated += [['alt', ['eps'], element]] * (high - low)
                parts.append(seq(repeated))
            else:
                raise BridgeRejected('Unregistered regex operator ' + str(op))
        return seq(parts)
    return convert(parsed)


def lean_regex(node):
    tag, *args = node
    if tag in ('empty', 'eps'):
        return '.' + tag
    if tag == 'atom':
        return '(.atom [' + ', '.join(f'({low}, {high})' for low, high in args[0]) + '])'
    return '(.' + tag + ' ' + ' '.join(lean_regex(child) for child in args) + ')'


def lean_strings(values):
    return '[' + ', '.join('[' + ', '.join(str(ord(c)) for c in value) + ']' for value in values) + ']'


def render(namespace, grammar, versions, singletons, line, headers):
    names = ('method', 'target', 'escaped', 'headerName', 'headerValue', 'host', 'contentType', 'length')
    out = ['import DecoderModel', '', 'namespace AlloyStudio.IngressDecoder.' + namespace, '',
           'def grammar : Grammar := {']
    out += ['  ' + name + ' := ' + lean_regex(grammar[pattern]) for name, pattern in zip(names, PATTERNS)]
    out += ['  versions := ' + lean_strings(versions), '  singletons := ' + lean_strings(singletons), '}', '']
    for name, rules in (('lineRules', line), ('headerRules', headers)):
        out.append('def ' + name + ' : List Rule := [' + ', '.join('.' + rule for rule in rules) + ']')
    out += ['', 'end AlloyStudio.IngressDecoder.' + namespace, '']
    return '\n'.join(out)


def generate(root, decoder_source=None, traffic_source=None):
    data = extraction(root, decoder_source, traffic_source)
    return render('Extracted', data['grammar'], data['constants']['VERSIONS'], data['constants']['SINGLETON_HEADERS'],
                  data['lineRules'], data['headerRules'])


def generate_contract(root):
    spec = json.loads((Path(root) / 'closure/traffic-refinement/strict-decoder-spec.json').read_text())
    return render('Contract', {name: regex_ast(spec['grammar'][name]) for name in PATTERNS},
                  spec['versions'], spec['singletonHeaders'], spec['lineRules'], spec['headerRules'])


def check(root):
    root = Path(root)
    data = extraction(root)
    generated = generate(root)
    contract = generate_contract(root)
    for path, expected in (('DecoderExtracted.lean', generated), ('DecoderContract.lean', contract)):
        if (root / 'formal/ingress_admission' / path).read_text() != expected:
            raise BridgeRejected('Stale generated decoder grammar/rules: ' + path)
    return {'status': 'PASS', 'kind': 'CHECKED_CLOSED_RAW_INPUT_REGEX_AND_RULE_INTERPRETATION',
            'mappings': data['functions'], 'lineRules': data['lineRules'], 'headerRules': data['headerRules'],
            'jsonAstSha256': data['jsonAstSha256'],
            'primitiveTCB': ['CPython ASCII/UTF8 decode and raw_items multiplicity',
                'CPython re.fullmatch equals extracted derivative regular language',
                'IPv6Address literal recognition',
                'CPython JSON syntax, parse_constant rejection and finite Unicode serialization',
                'ordinary Python statement/exception and recorded closed-loop interpretation'],
            'semanticInterpretation': {
                'strict_request_line': 'ASCII decode, exact split, guarded fields, unchanged tuple return',
                'strict_request_headers': 'lexical guards under all raw pairs, lower-case grouping and OWS stripping, guard conjunction before canonical integer return',
                '_host_valid': 'extracted host regex then IPv6 literal primitive and decimal port <=65535',
                'bounded_json': 'decoded text scanner, ordered object-pairs duplicate rejection at every object, rejected named constants, serialization finiteness/Unicode, exact dict root'},
            'generatedSha256': hashlib.sha256(generated.encode()).hexdigest(),
            'contractSha256': hashlib.sha256(contract.encode()).hexdigest()}


if __name__ == '__main__':
    import sys
    root = Path(__file__).resolve().parents[1]
    if '--generate' in sys.argv:
        (root / 'formal/ingress_admission/DecoderExtracted.lean').write_text(generate(root))
        (root / 'formal/ingress_admission/DecoderContract.lean').write_text(generate_contract(root))
    print(json.dumps(check(root), indent=2, sort_keys=True))
