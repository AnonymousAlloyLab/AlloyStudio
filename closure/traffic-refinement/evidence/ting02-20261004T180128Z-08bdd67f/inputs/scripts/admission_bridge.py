#!/usr/bin/env python3
"""Closed production admission ownership/lifecycle projection into Lean.

The admitted syntax outside the numeric holes is a frozen structural contract.
This is more than a source anchor: each complete function/class is covered,
namespace writes are closed, credit work has an explicit erased-state effect
boundary, and executable guard/delta/profile/lifecycle terms come from AST.
The Python-to-Lean interpretation of built-ins remains declared bridge TCB.
"""
import ast
import copy
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CONTRACT = 'closure/traffic-refinement/admission-source-contract.json'
GENERATED = 'formal/ingress_admission/AdmissionExtracted.lean'


class BridgeRejected(ValueError):
    pass


def dump(node):
    return ast.dump(node, include_attributes=False)


def sha(value):
    return hashlib.sha256(value.encode()).hexdigest()


def parse(source):
    try:
        return ast.parse(source)
    except (SyntaxError, RecursionError, ValueError):
        raise BridgeRejected('Invalid source syntax') from None


def unique(tree, name, kind):
    matches = [node for node in tree.body if isinstance(node, kind) and node.name == name]
    if len(matches) != 1:
        raise BridgeRejected('Missing or ambiguous source object: ' + name)
    return matches[0]


def method(cls, name):
    return unique(cls, name, ast.FunctionDef)


def member(node, spelling):
    node = copy.deepcopy(node)
    for item in ast.walk(node):
        if hasattr(item, 'ctx'):
            item.ctx = ast.Load()
    return dump(node) == dump(ast.parse(spelling, mode='eval').body)


def only(node, kind, predicate, description):
    found = [n for n in ast.walk(node) if isinstance(n, kind) and predicate(n)]
    if len(found) != 1:
        raise BridgeRejected('Missing or ambiguous ' + description)
    return found[0]


def number(node):
    if not isinstance(node, ast.Constant) or type(node.value) is not int or not 0 <= node.value <= 1000000000:
        raise BridgeRejected('Unsupported natural integer scalar')
    return node.value


def namespace(tree, protected):
    """Reject shadowing by module/class assignments and unexpected definitions.

    Function bodies may use locals. No top-level executable expression other
    than a docstring and the conventional __main__ call may mutate bindings.
    """
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            continue
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
            if node.name in protected:
                continue
            if any(isinstance(n, ast.Global) and set(n.names) & protected for n in ast.walk(node)):
                raise BridgeRejected('Global rebinding of a protected primitive')
            continue
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and type(node.value.value) is str:
            continue
        if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call) and member(node.value.func, 'STATIC.update'):
            continue
        if isinstance(node, ast.If) and member(node.test, "__name__ == '__main__'"):
            continue
        if isinstance(node, (ast.Assign, ast.AnnAssign)):
            targets = node.targets if isinstance(node, ast.Assign) else [node.target]
            if any(isinstance(n, ast.Name) and n.id in protected for target in targets for n in ast.walk(target)):
                raise BridgeRejected('Protected module binding overwritten')
            if any(isinstance(n, ast.Attribute) and isinstance(n.ctx, ast.Store) for target in targets for n in ast.walk(target)):
                raise BridgeRejected('Module-level attribute mutation')
            continue
        raise BridgeRejected('Unregistered module execution')


def credit_term(node):
    for spelling, term in [('self.capacity', 'capacity'), ('self.credit', 'credit'),
                           ('self.rate', 'rate'), ('self.UNIT', 'tokenUnit'),
                           ('now - self.last', 'elapsed')]:
        if member(node, spelling):
            return term
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Mult)):
        return '(' + credit_term(node.left) + (' + ' if isinstance(node.op, ast.Add) else ' * ') + credit_term(node.right) + ')'
    if isinstance(node, ast.Call) and member(node.func, 'min') and len(node.args) == 2 and not node.keywords:
        return '(clip ' + credit_term(node.args[0]) + ' ' + credit_term(node.args[1]) + ')'
    raise BridgeRejected('Unsupported credit arithmetic')


def lifecycle_action(node):
    """Translate the concrete side-effect AST, never manufacture a safety guard."""
    if isinstance(node, ast.Assign) and len(node.targets) == 1:
        if isinstance(node.value, ast.Call) and member(node.value.func, 'self.http_admission.reserve'):
            return 'reserve'
        if isinstance(node.value, ast.Call) and member(node.value.func, 'threading.Thread'):
            return 'allocate'
        if member(node.targets[0], 'self._request_threads[request]'):
            return 'register'
        if member(node.targets[0], 'start_attempted') and isinstance(node.value, ast.Constant) and node.value.value is True:
            return 'attempt'
        if member(node.targets[0], 'thread.daemon'):
            return None
    if isinstance(node, ast.Delete) and len(node.targets) == 1 and member(node.targets[0], 'self._request_threads[request]'):
        return 'unregister'
    if isinstance(node, ast.Expr) and isinstance(node.value, ast.Call):
        for spelling, action in [('thread.start','start'), ('self._request_threads.pop','unregister'),
                                 ('self.http_admission.release','release'), ('self.shutdown_request','close')]:
            if member(node.value.func, spelling):
                return action
    raise BridgeRejected('Unsupported lifecycle effect')


def lifecycle_paths(bounded):
    process = method(bounded, 'process_request')
    allocator = process.body[-1].body[2]
    allocation = [lifecycle_action(process.body[1])]
    allocation += [action for node in allocator.body if (action := lifecycle_action(node)) is not None]
    failure = [lifecycle_action(node) for node in allocator.handlers[0].body[0].body]
    refusal_branch = process.body[2]
    refusal_try = next(node for node in refusal_branch.body if isinstance(node, ast.Try))
    close = [lifecycle_action(node) for node in refusal_try.finalbody]
    reaper = method(bounded, '_reap_request_threads').body[0].body[0].body[0]
    if not member(reaper.test, 'thread.ident is not None and not thread.is_alive()'):
        raise BridgeRejected('Reaping must observe actual termination')
    reaping = ['terminate'] + [lifecycle_action(node) for node in reaper.body]
    return {'allocation': allocation, 'refusal': ['refuse'] + close,
            'preAttemptFailure': allocation[:2] + failure,
            'constructorFailure': allocation[:1] + failure,
            'reaping': reaping, 'retainedOnAmbiguousStart': allocation}


def envelope(tree):
    tree = copy.deepcopy(tree)
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.ClassDef)):
            node.body = [ast.Pass()]
    return sha(dump(tree))


def extract(root=ROOT, sources=None):
    root = Path(root)
    sources = sources or {}
    http = parse(sources.get('traffic_http.py', (root / 'traffic_http.py').read_text()))
    profile = parse(sources.get('traffic_profile.py', (root / 'traffic_profile.py').read_text()))
    server = parse(sources.get('server.py', (root / 'server.py').read_text()))
    protected = {'Admission', 'TokenBucket', 'BoundedHTTPServer', 'TrafficProfile', 'initial_admission',
                 'normalized_profile', 'threading', 'ThreadingHTTPServer', 'set', 'object', 'list', 'max', 'len'}
    namespace(http, protected)
    namespace(profile, protected)
    namespace(server, protected)
    admission = copy.deepcopy(unique(http, 'Admission', ast.ClassDef))
    bounded = copy.deepcopy(unique(http, 'BoundedHTTPServer', ast.ClassDef))
    token = copy.deepcopy(unique(http, 'TokenBucket', ast.ClassDef))
    initialization = copy.deepcopy(unique(profile, 'initial_admission', ast.FunctionDef))
    defaults = unique(profile, 'TrafficProfile', ast.ClassDef)
    startup = copy.deepcopy(unique(server, 'main', ast.FunctionDef))
    portal = copy.deepcopy(unique(server, 'Portal', ast.ClassDef))
    reserve = method(admission, 'reserve')
    release = method(admission, 'release')
    guard = only(reserve, ast.Compare, lambda n: member(n.left, 'self.active'), 'capacity guard')
    if len(guard.ops) != 1 or len(guard.comparators) != 1 or not member(guard.comparators[0], 'self.limit'):
        raise BridgeRejected('Unsupported capacity operands')
    operator = {ast.GtE: 'ge', ast.Gt: 'gt', ast.LtE: 'le', ast.Lt: 'lt', ast.Eq: 'eq', ast.NotEq: 'ne'}.get(type(guard.ops[0]))
    if operator is None:
        raise BridgeRejected('Unsupported capacity comparison')
    guard.ops = [ast.GtE()]
    increment = only(reserve, ast.AugAssign, lambda n: member(n.target, 'self.active'), 'reserve count update')
    decrement = only(release, ast.AugAssign, lambda n: member(n.target, 'self.active'), 'release count update')
    if not isinstance(increment.op, ast.Add) or not isinstance(decrement.op, ast.Sub):
        raise BridgeRejected('Unsupported ownership arithmetic')
    add, sub = number(increment.value), number(decrement.value)
    increment.value = ast.Constant(1)
    decrement.value = ast.Constant(1)
    lane = only(initialization, ast.Assign, lambda n: len(n.targets) == 1 and member(n.targets[0], 'limit'), 'lane selector')
    if not isinstance(lane.value, ast.IfExp) or not member(lane.value.test, 'control'):
        raise BridgeRejected('Unsupported lane condition')
    if member(lane.value.body, 'profile.control_handlers') and member(lane.value.orelse, 'profile.public_handlers'):
        reversed_lane = False
    elif member(lane.value.body, 'profile.public_handlers') and member(lane.value.orelse, 'profile.control_handlers'):
        reversed_lane = True
    else:
        raise BridgeRejected('Unsupported lane branches')
    lane.value = ast.parse('profile.control_handlers if control else profile.public_handlers', mode='eval').body
    profile_values = {}
    for name in ('public_handlers', 'control_handlers', 'public_burst', 'control_burst', 'public_rate', 'control_rate'):
        field = only(defaults, ast.AnnAssign, lambda n: member(n.target, name), 'profile field ' + name)
        profile_values[name] = number(field.value)
    controlarg = only(startup, ast.Call, lambda n: member(n.func, 'parser.add_argument') and n.args
                      and isinstance(n.args[0], ast.Constant) and n.args[0].value == '--control-port', 'control argument')
    control_default = [k for k in controlarg.keywords if k.arg == 'default']
    if len(control_default) != 1:
        raise BridgeRejected('Missing control default')
    control_port = number(control_default[0].value)
    control_default[0].value = ast.Constant(0)
    token_unit = only(token, ast.Assign, lambda n: len(n.targets) == 1 and member(n.targets[0], 'UNIT'), 'token unit')
    token_refill = only(method(token, 'refill'), ast.Assign,
                        lambda n: len(n.targets) == 1 and member(n.targets[0], 'self.credit'), 'credit refill')
    token_charge = only(method(token, 'charge'), ast.AugAssign, lambda n: member(n.target, 'self.credit'), 'credit charge')
    if not isinstance(token_charge.op, ast.Sub):
        raise BridgeRejected('Unsupported credit charge operator')
    credit = {'unit':number(token_unit.value), 'refill':credit_term(token_refill.value),
              'charge':credit_term(token_charge.value)}
    # Full shape matching establishes the precise source primitives surrounding
    # these holes, including peer loops, both locks, register-before-start,
    # failed-start classification, dead-only reaping, and startup call topology.
    shapes = {
        'Admission': sha(dump(admission)),
        'TrafficProfile': sha(dump(defaults)),
        'Portal': sha(dump(portal)),
        'traffic_http.envelope': envelope(http),
        'traffic_profile.envelope': envelope(profile),
        'server.envelope': envelope(server),
        'TokenBucket': sha(dump(token)),
        'BoundedHTTPServer': sha(dump(bounded)),
        'initial_admission': sha(dump(initialization)),
        'normalized_profile': sha(dump(unique(profile, 'normalized_profile', ast.FunctionDef))),
        'main': sha(dump(startup)),
        'Portal.__init__': sha(dump(method(portal, '__init__'))),
        'traffic_http.imports': sha('\n'.join(dump(n) for n in http.body if isinstance(n, (ast.Import, ast.ImportFrom)))),
        'traffic_profile.imports': sha('\n'.join(dump(n) for n in profile.body if isinstance(n, (ast.Import, ast.ImportFrom))))}
    contract = json.loads((root / CONTRACT).read_text())
    if shapes != contract['shapes']:
        mismatches = sorted(key for key in set(shapes) | set(contract['shapes']) if shapes.get(key) != contract['shapes'].get(key))
        raise BridgeRejected('Unregistered source transition(s): ' + ', '.join(mismatches))
    methods = {n.name for n in admission.body if isinstance(n, ast.FunctionDef)}
    if methods != {'__init__', 'reserve', 'release', 'statistics'}:
        raise BridgeRejected('Unmapped Admission method')
    # Complete concrete operations and the erasure boundary are registered, not
    # inferred from a passing theorem. The profile validator is frozen as source
    # hash by the root closure; successful constructors use its checked defaults.
    return {'capacityOperator': operator, 'increment': add, 'decrement': sub, 'credit':credit,
            'laneReversed': reversed_lane, 'profile': profile_values, 'controlPortDefault': control_port,
            'shapes': shapes, 'paths': lifecycle_paths(bounded), 'correspondence': contract['correspondence'],
            'trust': contract['trust']}


def render(data):
    p = data['profile']
    rows = ['import AdmissionModel', 'namespace AlloyStudio.IngressAdmission.Extracted',
            '-- Generated by scripts/admission_bridge.py from admitted production AST.',
            'def reserve (limit owner : Nat) (creditAllowed : Bool) (state : State) : Result :=',
            f"  IngressAdmission.reserve .{data['capacityOperator']} {data['increment']} limit owner creditAllowed state", '',
            'def release (owner : Nat) (state : State) : State :=',
            f"  IngressAdmission.release {data['decrement']} owner state", '']
    for lean, python in [('publicHandlers','public_handlers'),('controlHandlers','control_handlers'),
                         ('publicBurst','public_burst'),('controlBurst','control_burst'),
                         ('publicRate','public_rate'),('controlRate','control_rate')]:
        rows.append(f'def {lean} : Nat := {p[python]}')
    rows.append(f"def controlPortDefault : Nat := {data['controlPortDefault']}")
    branches = ('publicHandlers','controlHandlers') if data['laneReversed'] else ('controlHandlers','publicHandlers')
    rows.append(f'def laneLimit (control : Bool) : Nat := if control then {branches[0]} else {branches[1]}')
    for name, actions in data['paths'].items():
        rows.append(f"def {name} : List LifeAction := [" + ', '.join('.' + action for action in actions) + ']')
    rows.append(f"def tokenUnit : Nat := {data['credit']['unit']}")
    rows.append('def refillCredit (capacity credit elapsed rate : Nat) : Nat := ' + data['credit']['refill'])
    rows.append('def chargeCredit (credit : Nat) : Nat := credit - ' + data['credit']['charge'])
    rows.append('def availableCredit (credit : Nat) : Bool := compare .ge credit tokenUnit')
    rows.append('end AlloyStudio.IngressAdmission.Extracted')
    return '\n'.join(rows) + '\n'


def generate(root=ROOT, sources=None):
    return render(extract(root, sources))


def check(root=ROOT):
    root = Path(root)
    data = extract(root)
    source = render(data)
    if source != (root / GENERATED).read_text():
        raise BridgeRejected('Generated admission Lean differs from actual source projection')
    return {'status': 'PASS', 'generatedSha256': sha(source), 'mappedObjects': len(data['correspondence']),
            'unmappedObjects': 0, 'ambiguousObjects': 0, 'projection': data}


if __name__ == '__main__':
    print(json.dumps(check(), sort_keys=True, indent=2))
