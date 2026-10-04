"""Bind formal wire-derived inputs to the registered Python parser operations.

This adds data-flow identities to the existing closed route/decoder interpreters;
it never accepts free method flags, body lengths, JSON trees or header lengths as
caller observations. The raw-header email parser correspondence is the narrow
standard-library primitive on already checked nonfolded CRLF field lines.
"""
import ast
import hashlib
from pathlib import Path
import decoder_bridge
import strict_ingress_bridge


class BridgeRejected(ValueError):
    pass


def dump(node):
    return ast.dump(node, include_attributes=False)


def expect_expression(node, source, label):
    if dump(node) != dump(ast.parse(source, mode='eval').body):
        raise BridgeRejected('Wire data-flow identity changed: ' + label)


def one_call(node, target):
    calls = [item for item in ast.walk(node) if isinstance(item, ast.Call) and ast.unparse(item.func) == target]
    if len(calls) != 1:
        raise BridgeRejected('Missing or ambiguous wire primitive: ' + target)
    return calls[0]


def check(root, server_source=None, traffic_source=None):
    root = Path(root)
    decoder = decoder_bridge.extraction(root)
    route = strict_ingress_bridge.extract(root, server_source, traffic_source)
    server = (root / 'server.py').read_text() if server_source is None else server_source
    traffic = (root / 'traffic_http.py').read_text() if traffic_source is None else traffic_source
    _, methods = strict_ingress_bridge.methods(server, 'Handler', ('parse_request', 'request_headers', 'read_json_body'))
    _, readers = strict_ingress_bridge.methods(traffic, 'DeadlineReader', ('readline',))
    line = one_call(methods['parse_request'], 'strict_request_line')
    expect_expression(line, 'strict_request_line(self.raw_requestline)', 'raw request-line identity')
    header = one_call(methods['parse_request'], 'self.request_headers')
    expect_expression(header, "self.request_headers(mutation=self.command == 'POST')", 'mutation derives from method')
    parsed = [n for n in ast.walk(methods['parse_request']) if isinstance(n, ast.Compare)
              and ast.unparse(n.left) == '(self.command, self.path, self.request_version)']
    if len(parsed) != 1:
        raise BridgeRejected('Missing strict-to-stdlib request tuple identity')
    expect_expression(parsed[0], '(self.command, self.path, self.request_version) != expected',
                      'stdlib command equals strict raw-line command')
    fields = one_call(methods['request_headers'], 'strict_request_headers')
    expect_expression(fields, 'strict_request_headers(self.headers.raw_items(), self.request_version, mutation=mutation, admin=admin)',
                      'ordered raw header occurrence identity')
    body = one_call(methods['read_json_body'], 'bounded_json')
    expect_expression(body, "bounded_json(b''.join(chunks), self.server.traffic_profile.json_depth)",
                      'JSON bytes are exactly accumulated body chunks')
    partition = one_call(readers['readline'], 'result[:-2].partition')
    expect_expression(partition, "result[:-2].partition(b':')", 'first-colon header partition')
    raw_lengths = [n for n in ast.walk(readers['readline']) if isinstance(n, ast.AugAssign)
                   and ast.unparse(n.target) == 'self.header_remaining']
    if len(raw_lengths) != 1 or not isinstance(raw_lengths[0].op, ast.Sub):
        raise BridgeRejected('Missing received-header-byte accounting')
    expect_expression(raw_lengths[0].value, 'len(result)', 'wire lengths are actual returned line lengths')
    return {
        'status': 'PASS', 'unmapped': 0, 'ambiguous': 0,
        'bindings': {
            'IngressWire.actualRequest': 'strict_request_line(raw_requestline) tuple equals stdlib command/path/version; raw_items is the standard first-colon nonfolded field parse; mutation is command == POST',
            'IngressWire.headerPairs': 'DeadlineReader.readline CRLF, first-colon, token, control and no-fold checks; exact terminal blank line must be received',
            'IngressWire.headerLengths': 'request line plus every actually returned field/terminator length; header_remaining -= len(result)',
            'IngressWire.declaredLength': 'strict_request_headers canonical Content-Length integer from the same ordered header occurrences',
            'IngressWire.receivedLength': 'remaining starts at declared length, decreases only by len(chunk), and successful exact-read loop returns its joined chunks',
            'IngressWire.decodedBody': 'bounded_json joins those exact chunks, UTF8-decodes them, then passes that same text to json.loads; returned tree is the standard decoded ordered-pair tree',
        },
        'theorems': ['AlloyStudio.IngressWire.actual_request_binding',
                     'AlloyStudio.IngressWire.post_false_mutation_impossible',
                     'AlloyStudio.IngressWire.declared_length_is_parsed_header',
                     'AlloyStudio.IngressWire.decoded_body_binding',
                     'AlloyStudio.IngressWire.accepted_body_has_bound_parse'],
        'routeMethodAstSha256': route['methodAstSha256'],
        'jsonAstSha256': decoder['jsonAstSha256'],
        'modelSha256': hashlib.sha256((root / 'formal/ingress_admission/IngressWire.lean').read_bytes()).hexdigest(),
        'primitiveTCB': ['CPython ordinary sequential/exceptions and bounded recv semantics',
                         'email raw_items first-colon/OWS interpretation on lexically valid nonfolded CRLF lines',
                         'Strict UTF8 decode and standard ordered-pair JSON parse of the resulting codepoints; finite/Unicode serialization at scalar leaves'],
    }
