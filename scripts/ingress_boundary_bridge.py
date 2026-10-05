#!/usr/bin/env python3
"""Successor correspondence for the AP01-C08 ingress/business boundary (bridge B08).

The TRF-01 closure froze server.Handler as a whole. AP01 moves business logic
into portal_routes behind a ValidatedRequest, so the historical template no
longer equals the current class; that record stays intact as history. This
bridge establishes, without regenerating any historical artifact:

1. every ingress method and DeadlineReader is AST-identical to the frozen
   TRF-01 template, without promoting the historical proof to current production closure;
2. the Handler method set is closed (no new override can bypass ingress);
3. each dispatcher validates before its registered action callback;
4. callbacks receive named RouteServices, and business source rejects known ingress/introspection access;
5. channels are issued against the resolved quota identity, never the TCP peer.

This is a registered structural check under ordinary Python semantics, not a
Lean refinement of the new dispatch code.
"""
import argparse
import ast
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = 'formal/ingress_admission/dispatch-template.py.txt'
BOUNDARY_REGISTRY = 'closure/patch-contracts/ingress-boundary.json'
INGRESS_METHODS = ('log_message', 'send_error', 'setup', 'parse_request', 'handle_one_request', 'reply',
                   'public_reply', 'request_headers', 'read_json_body', 'public_body', 'admin_headers',
                   'admin_body', 'admin_failure')
BOUNDARY_METHODS = ('validated', 'admitted_administration', 'deliver',
                    'admin_GET', 'admin_POST', 'do_GET', 'do_POST')
# Ordered call obligations: each name must occur, in this order, before the next.
DISPATCH_ORDER = {
    'do_GET': ('self.request_headers', 'portal_routes.control_get|portal_routes.public_get|self.admin_GET'),
    'do_POST': ('self.request_headers', 'self.public_body', 'portal_routes.public_post'),
    'admin_GET': ('self.admin_headers', 'self.admitted_administration', 'portal_routes.admin_get'),
    'admin_POST': ('self.admin_headers', 'self.admitted_administration', 'portal_routes.admin_authorize',
                   'self.admin_body', 'portal_routes.admin_post'),
}
SINGLE_CALLBACK = {'do_POST': 'portal_routes.public_post', 'admin_GET': 'portal_routes.admin_get',
                   'admin_POST': 'portal_routes.admin_post'}
FORBIDDEN_ATTRIBUTES = frozenset((
    'rfile', 'wfile', 'connection', 'raw_items', 'http_admission', '_request_threads',
    '_request_threads_uncertain', '_reap_request_threads', 'send_response', 'send_header', 'end_headers',
    'makefile', 'recv', 'sendall', 'settimeout', 'process_request', 'shutdown_request',
    'http_public_views', 'traffic_stats', 'client_address', 'socket', '_request_threads_lock',
    '__dict__', '__class__', '__globals__', '__self__', '__closure__', '__getattribute__',
    '__subclasses__', 'server_close', 'shutdown'))
FORBIDDEN_CALLS = frozenset(('getattr', 'setattr', 'delattr', 'vars', 'globals', 'locals',
                              'eval', 'exec', 'compile', '__import__'))
FORBIDDEN_MODULES = frozenset(('socket', 'socketserver', 'http', 'traffic_http', 'traffic_decode', 'server'))


class BridgeRejected(ValueError):
    pass


def dump(node):
    return ast.dump(node, include_attributes=False)


def digest(text):
    return hashlib.sha256(text.encode()).hexdigest()


def unique_class(tree, name):
    found = [node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == name]
    if len(found) != 1:
        raise BridgeRejected('Missing or ambiguous class: ' + name)
    return found[0]


def functions(cls):
    result = {}
    for node in cls.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            if node.name in result:
                raise BridgeRejected('Duplicate Handler method: ' + node.name)
            result[node.name] = node
    return result


def ordered_calls(function):
    calls = [node for node in ast.walk(function) if isinstance(node, ast.Call)]
    calls.sort(key=lambda node: (node.lineno, node.col_offset))
    return [ast.unparse(node.func) for node in calls]


def check_order(name, function):
    calls = ordered_calls(function)
    position = -1
    for step in DISPATCH_ORDER[name]:
        options = step.split('|')
        hits = [index for index, call in enumerate(calls) if call in options and index > position]
        if not hits:
            raise BridgeRejected('Validation/callback order violated in Handler.' + name + ': ' + step)
        position = hits[0]
    callback = SINGLE_CALLBACK.get(name)
    if callback is not None and calls.count(callback) != 1:
        raise BridgeRejected('Business callback must be invoked exactly once in Handler.' + name)


def check_boundary_registry(root, server_source):
    """Pin small reviewed dispatcher/capability ASTs, not mutable business logic.

    Lexical call ordering alone admits dead validation and inverted guards.
    The registry is an independent review input, never regenerated by check().
    This is drift detection under declared Python semantics, not a proof.
    """
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise BridgeRejected('Duplicate boundary registry entry')
            result[key] = value
        return result
    try:
        expected = json.loads((Path(root) / BOUNDARY_REGISTRY).read_text(), object_pairs_hook=unique)
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        raise BridgeRejected('Boundary registry is missing or unreadable.') from error
    if (not isinstance(expected, dict) or type(expected.get('schemaVersion')) is not int
            or expected['schemaVersion'] != 1 or not isinstance(expected.get('astSha256'), dict)):
        raise BridgeRejected('Boundary registry has an invalid schema.')
    tree = ast.parse(server_source)
    handler = functions(unique_class(tree, 'Handler'))
    actual = {'Handler.' + name: digest(dump(handler[name])) for name in BOUNDARY_METHODS}
    actual['RouteServices'] = digest(dump(unique_class(tree, 'RouteServices')))
    builders = [node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'route_services']
    if len(builders) != 1:
        raise BridgeRejected('Missing or ambiguous route_services builder')
    actual['route_services'] = digest(dump(builders[0]))
    if expected.get('schemaVersion') != 1 or expected.get('astSha256') != actual:
        changed = sorted(key for key in set(actual) | set(expected.get('astSha256', {}))
                         if actual.get(key) != expected.get('astSha256', {}).get(key))
        raise BridgeRejected('Unregistered boundary control flow or capabilities: ' + ', '.join(changed))
    return actual


def check_handler(server_source, template_source):
    current = unique_class(ast.parse(server_source), 'Handler')
    frozen = unique_class(ast.parse(template_source), 'Handler')
    # An inherited handle(), class decorator or metaclass can bypass every
    # pinned ingress method without changing a method AST in Handler itself.
    # Freeze the class header as well as the direct method set.
    if ([dump(base) for base in current.bases] != [dump(ast.Name(id='BaseHTTPRequestHandler', ctx=ast.Load()))]
            or current.decorator_list or current.keywords or getattr(current, 'type_params', ())):
        raise BridgeRejected('Unregistered Handler class header or inheritance')
    methods, historical = functions(current), functions(frozen)
    expected = set(INGRESS_METHODS) | set(BOUNDARY_METHODS)
    if set(methods) != expected:
        raise BridgeRejected('Unregistered or missing Handler method(s): '
                             + ', '.join(sorted(set(methods) ^ expected)))
    others = [node for node in current.body if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))]
    if len(others) != 1 or dump(others[0]) != dump(ast.parse("server_version = 'AlloyPractice/1.0'").body[0]):
        raise BridgeRejected('Unregistered Handler class attribute')
    for name in INGRESS_METHODS:
        if dump(methods[name]) != dump(historical[name]):
            raise BridgeRejected('Ingress method differs from the TRF-01 template: Handler.' + name)
    for name in DISPATCH_ORDER:
        check_order(name, methods[name])
    validated = ordered_calls(methods['validated'])
    if validated.count('resolve_identity') != 1 or validated.count('FrozenHeaders') != 1:
        raise BridgeRejected('Validated requests must carry the resolved identity and frozen headers')
    return {name: digest(dump(methods[name])) for name in sorted(methods)}


def check_reader(traffic_source, template_source):
    current = unique_class(ast.parse(traffic_source), 'DeadlineReader')
    frozen = unique_class(ast.parse(template_source), 'DeadlineReader')
    if dump(current) != dump(frozen):
        raise BridgeRejected('DeadlineReader differs from the TRF-01 template')
    return digest(dump(current))


def check_business(routes_source):
    tree = ast.parse(routes_source)
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and ast.unparse(node.func) in FORBIDDEN_CALLS:
            raise BridgeRejected('Business code uses dynamic ingress/introspection access')
        if isinstance(node, ast.Attribute) and node.attr in FORBIDDEN_ATTRIBUTES:
            raise BridgeRejected('Business code reaches ingress state: ' + node.attr)
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.name.split('.')[0] in FORBIDDEN_MODULES:
                    raise BridgeRejected('Business code imports an ingress module: ' + alias.name)
        if isinstance(node, ast.ImportFrom) and (node.module or '').split('.')[0] in FORBIDDEN_MODULES:
            raise BridgeRejected('Business code imports an ingress module: ' + node.module)
    frozen = {}
    for name in ('ValidatedRequest', 'Reply'):
        cls = unique_class(tree, name)
        decorators = [ast.unparse(item) for item in cls.decorator_list]
        if decorators != ['dataclass(frozen=True)']:
            raise BridgeRejected(name + ' must be an immutable dataclass')
        frozen[name] = digest(dump(cls))
    issues = [node for node in ast.walk(tree) if isinstance(node, ast.Call)
              and ast.unparse(node.func) == 'app.scheduler.issue_channel']
    if len(issues) != 1 or [ast.unparse(arg) for arg in issues[0].args] != ['request.identity']:
        raise BridgeRejected('Channels must be issued against the resolved quota identity')
    return frozen


def check(root=ROOT, *, server_source=None, traffic_source=None, routes_source=None):
    root = Path(root)
    template = (root / TEMPLATE).read_text()
    server_source = (root / 'server.py').read_text() if server_source is None else server_source
    traffic_source = (root / 'traffic_http.py').read_text() if traffic_source is None else traffic_source
    routes_source = (root / 'portal_routes.py').read_text() if routes_source is None else routes_source
    return {'status': 'PASS', 'bridge': 'AP01-B08-ingress-boundary',
            'templateSha256': hashlib.sha256((root / TEMPLATE).read_bytes()).hexdigest(),
            'handlerMethods': check_handler(server_source, template),
            'boundaryRegistry': check_boundary_registry(root, server_source),
            'deadlineReader': check_reader(traffic_source, template),
            'businessTypes': check_business(routes_source),
            'ingressMethodsMatchingTrf01': list(INGRESS_METHODS),
            'interpretation': 'Ingress methods are the frozen TRF-01 surface; dispatch and business '
                              'separation are checked structurally, not proved in Lean.'}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.parse_args()
    try:
        result = check()
    except BridgeRejected as error:
        result = {'status': 'REJECTED', 'reason': str(error)}
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
