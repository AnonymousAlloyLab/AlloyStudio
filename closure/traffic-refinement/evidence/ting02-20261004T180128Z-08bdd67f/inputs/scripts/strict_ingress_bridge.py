"""Closed correspondence for the concrete ingress call graph and byte budgets.

This is a registered, deliberately restricted Python interpretation. Unknown
work is rejected, never silently assigned a validation effect. Standard-library
HTTP dispatch, exception termination, exact recv(count) bounds, primitive string
operations and ordinary sequential Python execution are explicit TCB.
"""
import ast
import copy
import hashlib
from pathlib import Path

class BridgeRejected(ValueError):
    pass

METHODS = ('setup', 'parse_request', 'handle_one_request', 'request_headers',
           'read_json_body', 'public_body', 'admin_headers', 'admin_body',
           'admin_GET', 'admin_POST', 'do_GET', 'do_POST')

def dump(node):
    return ast.dump(node, include_attributes=False)

def methods(source, cls, names):
    tree = ast.parse(source)
    found = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == cls]
    if len(found) != 1:
        raise BridgeRejected('Missing or ambiguous ingress class')
    result = {}
    for name in names:
        nodes = [node for node in found[0].body if isinstance(node, ast.FunctionDef) and node.name == name]
        if len(nodes) != 1:
            raise BridgeRejected('Missing or ambiguous ingress method: ' + name)
        result[name] = nodes[0]
    return tree, result

def extract(root, server_source=None, traffic_source=None):
    root = Path(root)
    source = (root/'server.py').read_text() if server_source is None else server_source
    traffic = (root/'traffic_http.py').read_text() if traffic_source is None else traffic_source
    tree, actual = methods(source, 'Handler', METHODS)
    _, template = methods((root/'formal/ingress_admission/dispatch-template.py.txt').read_text(), 'Handler', METHODS)
    # Each registered successful branch has exactly the frozen work/call-return
    # interpretation. This includes the exception exits and rejected routes.
    for name in METHODS:
        if dump(actual[name]) != dump(template[name]):
            raise BridgeRejected('Unregistered ingress branch or call: Handler.' + name)
    imports = [(n.module, [(a.name, a.asname) for a in n.names]) for n in tree.body if isinstance(n, ast.ImportFrom)]
    if imports.count(('traffic_decode', [('strict_request_line', None), ('strict_request_headers', None)])) != 1:
        raise BridgeRejected('Decoder import identity changed')
    _, readers = methods(traffic, 'DeadlineReader', ('readline', '_recv', 'read1', 'read', 'begin_headers'))
    _, expected = methods((root/'formal/ingress_admission/dispatch-template.py.txt').read_text(), 'DeadlineReader', tuple(readers))
    for name in readers:
        if dump(readers[name]) != dump(expected[name]):
            raise BridgeRejected('Unregistered byte-reader transition: ' + name)
    required_calls = {
        'parse_request': ('strict_request_line', 'super().parse_request', 'self.request_headers', 'self.rfile.check_deadline'),
        'request_headers': ('strict_request_headers',),
        'read_json_body': ('self.rfile.begin_body', 'self.rfile.read1', 'self.rfile.check_deadline', 'bounded_json'),
        'public_body': ('self.read_json_body', 'self.request_headers'),
        'admin_body': ('self.read_json_body',),
        'do_GET': ('self.request_headers',),
        'do_POST': ('self.public_body', 'self.admin_POST'),
        'admin_POST': ('self.admin_headers', 'self.admin_body'),
    }
    mappings = {}
    for method, calls in required_calls.items():
        names = [ast.unparse(n.func) for n in ast.walk(actual[method]) if isinstance(n, ast.Call)]
        for call in calls:
            count = names.count(call)
            expected_count = 2 if method == 'read_json_body' and call == 'self.rfile.check_deadline' else 1
            if count != expected_count:
                raise BridgeRejected('Missing or ambiguous call mapping: ' + method + ':' + call)
            mappings[method + ':' + call] = count
    # Guards are checked against independent literal AST expectations, not merely
    # against the broad consumer template. These are the arithmetic proof inputs.
    body_guard = actual['read_json_body'].body[0]
    if not isinstance(body_guard, ast.If) or dump(body_guard.test) != dump(ast.parse('not 0 < length <= limit', mode='eval').body):
        raise BridgeRejected('Body length guard changed')
    loop = next(n for n in ast.walk(actual['read_json_body']) if isinstance(n, ast.While))
    if dump(loop.test) != dump(ast.Name('remaining', ast.Load())):
        raise BridgeRejected('Incomplete-body loop changed')
    return {'status': 'PASS', 'methodAstSha256': {name: hashlib.sha256(dump(node).encode()).hexdigest() for name,node in actual.items()},
            'readerAstSha256': {name: hashlib.sha256(dump(node).encode()).hexdigest() for name,node in readers.items()},
            'mappings': mappings, 'unmapped': 0, 'ambiguous': 0,
            'bodyLengthGuard': '0 < length <= limit',
            'sourceSemantics': 'Frozen interprocedural ordinary-execution/exception interpretation, one request per reserved connection; successful GET dispatch follows strict line+headers+header cut; successful public/admin POST business dispatch additionally follows exact-length bounded JSON and body completion cut. Rejected routes do not constitute business dispatch.'}

def check(root):
    result = extract(root)
    generated = generate(root).encode()
    if generated != (Path(root)/'formal/ingress_admission/IngressRouteExtracted.lean').read_bytes():
        raise BridgeRejected('Concrete route program differs from generated interpretation')
    result['routeProgramSha256'] = hashlib.sha256(generated).hexdigest()
    return result


def generate(root, server_source=None, traffic_source=None):
    extract(root, server_source, traffic_source)
    return 'import IngressRouteModel\n\nnamespace AlloyStudio.Traffic\nopen AlloyStudio.IngressDecoder\n\n/-- Rejection returns false before business dispatch. This evaluator executes\nthe registered validators on their raw representation, not a validity premise. -/\ndef dispatch (ipv6 : Octets → Bool) (input : Inbound) : Bool :=\n  accepted Extracted.grammar ipv6 (Extracted.lineRules ++ Extracted.headerRules) input.request &&\n  headersWithin input &&\n  AlloyStudio.IngressDeadlines.Extracted.checkDeadline input.headerNow input.headerDeadline &&\n  (if input.request.mutation then completedBody input else true)\n\n\nend AlloyStudio.Traffic\n'
