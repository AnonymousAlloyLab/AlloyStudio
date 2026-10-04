#!/usr/bin/env python3
"""Closed AST lowering for the HTTP profile and its constructor consumption.

Python/dataclass/collection allocation semantics and this restricted translator
are declared trust; the independent interval and state specification is Lean.
"""
import ast
import hashlib
import json
from pathlib import Path


class BridgeRejected(ValueError):
    pass


def dump(node):
    return ast.dump(node, include_attributes=False)


def parse(source):
    try:
        return ast.parse(source)
    except (SyntaxError, RecursionError):
        raise BridgeRejected('Malformed profile source.') from None


def literal(node):
    if isinstance(node, ast.Constant) and type(node.value) in (int, float):
        return node.value
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mult):
        return literal(node.left) * literal(node.right)
    raise BridgeRejected('Unregistered profile default.')


def field_mapping(node, receiver, names):
    if not isinstance(node, ast.Dict) or len(node.keys) != len(names):
        raise BridgeRejected('Missing or extra profile value mapping.')
    for key, value, name in zip(node.keys, node.values, names):
        if (not isinstance(key, ast.Constant) or key.value != name
                or not isinstance(value, ast.Attribute) or value.attr != name
                or not isinstance(value.value, ast.Name) or value.value.id != receiver):
            raise BridgeRejected('Non-identity or ambiguous profile value mapping.')


def extract(root, source):
    tree = parse(source)
    try:
        cls = next(n for n in tree.body if isinstance(n, ast.ClassDef))
        declarations = [n for n in cls.body if isinstance(n, ast.AnnAssign)]
        fields = [(n.target.id, literal(n.value)) for n in declarations]
        expected = json.loads((root / 'closure/traffic-refinement/http-profile-spec.json').read_text())['fields']
        if fields != [(f['name'], f['default']) for f in expected]:
            raise BridgeRejected('Profile schema/defaults differ from independent specification.')
        # Equality alone would permit an integer default to impersonate 5.0.
        if any(type(value) is not type(f['default']) for (_, value), f in zip(fields, expected)):
            raise BridgeRejected('Default scalar representation changed.')
        for field in expected:
            name = field['name']
            runtime_domain = ('positive_seconds' if name.endswith('_seconds')
                              and name != 'peer_idle_seconds' else 'positive_integer')
            if field['domain'] != runtime_domain:
                raise BridgeRejected('Independent domain disagrees with admitted runtime dispatch.')
        post = next(n for n in cls.body if isinstance(n, ast.FunctionDef))
        names = [name for name, _ in fields]
        field_mapping(post.body[0].value, 'self', names)
        normalizer = next(n for n in tree.body if isinstance(n, ast.FunctionDef)
                          and n.name == 'normalized_profile')
        field_mapping(normalizer.body[1].value, 'profile', names)
        pair = post.body[-2].test
        if not isinstance(pair, ast.BoolOp) or not isinstance(pair.op, ast.Or) or len(pair.values) != 2:
            raise BridgeRejected('Unregistered profile relation composition.')
        relations = [*pair.values, post.body[-1].test]
        post.body[-2].test = ast.Name('PROFILE_RELATIONS', ast.Load())
        post.body[-1].test = ast.Name('HANDLER_RELATION', ast.Load())
        init = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == 'initial_admission')
        assignments = {}
        for n in init.body:
            if isinstance(n, ast.Assign) and isinstance(n.targets[0], ast.Name):
                name = n.targets[0].id
                if name in ('limit', 'burst', 'rate', 'capacity'):
                    assignments[name] = n.value
                    n.value = ast.Name('INITIAL_' + name.upper(), ast.Load())
        state = init.body[-1].value.elts[1]
        outputs = {}
        for i, key in enumerate(state.keys):
            if key.value not in ('owners', 'anonymous_owners', 'peers'):
                outputs[key.value] = state.values[i]
                state.values[i] = ast.Name('STATE_' + key.value.upper(), ast.Load())
        template = parse((root / 'formal/traffic_profile/profile-template.py.txt').read_text())
        if dump(tree) != dump(template):
            raise BridgeRejected('Unregistered profile control flow, normalization, field wiring or return.')
        return fields, expected, relations, assignments, outputs
    except (ValueError, AttributeError, IndexError, KeyError, StopIteration, TypeError) as error:
        if isinstance(error, BridgeRejected):
            raise
        raise BridgeRejected('Malformed profile structure.') from None


def term(node, fields, variables=()):
    if isinstance(node, ast.Constant) and type(node.value) is int and 0 <= node.value <= 1000000000:
        return str(node.value)
    if isinstance(node, ast.Name) and node.id in variables:
        return node.id
    if (isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name)
            and node.value.id in ('self', 'profile') and node.attr in fields):
        return '(Model.integerValue p.' + node.attr + ')'
    if isinstance(node, ast.BinOp) and isinstance(node.op, (ast.Add, ast.Mult)):
        op = ' + ' if isinstance(node.op, ast.Add) else ' * '
        return '(' + term(node.left, fields, variables) + op + term(node.right, fields, variables) + ')'
    raise BridgeRejected('Unregistered profile arithmetic.')


def condition(node, fields):
    if not isinstance(node, ast.Compare) or len(node.ops) != 1 or len(node.comparators) != 1:
        raise BridgeRejected('Unregistered profile comparison.')
    left, right = term(node.left, fields), term(node.comparators[0], fields)
    op = node.ops[0]
    if isinstance(op, ast.Gt):
        return '(less ' + right + ' ' + left + ')'
    if isinstance(op, ast.Lt):
        return '(less ' + left + ' ' + right + ')'
    if isinstance(op, ast.GtE):
        return '(lessEqual ' + right + ' ' + left + ')'
    if isinstance(op, ast.LtE):
        return '(lessEqual ' + left + ' ' + right + ')'
    raise BridgeRejected('Unregistered profile comparison operator.')


def lane(node, fields):
    if not isinstance(node, ast.IfExp) or dump(node.test) != dump(ast.Name('control', ast.Load())):
        raise BridgeRejected('Unregistered lane selection.')
    return '(if control then ' + term(node.body, fields) + ' else ' + term(node.orelse, fields) + ')'


def scalar(value):
    if type(value) is int:
        return '.integer ' + str(value)
    n, d = value.as_integer_ratio()
    return '.floating ' + str(n) + ' ' + str(d)


def generate(root, source):
    root = Path(root)
    fields, schema, relations, assignments, outputs = extract(root, source)
    names = {name for name, _ in fields}
    clauses = []
    for field in schema:
        name = field['name']
        if field['domain'] == 'positive_integer':
            clauses.append('(TrafficConfig.Extracted.validatedInt p.' + name + ' 1 67108864).isSome = true')
        elif field['domain'] == 'positive_seconds':
            clauses.append('(TrafficConfig.Extracted.validatedSeconds p.' + name + ' false 300).isSome = true')
        else:
            raise BridgeRejected('Unknown scalar domain.')
    clauses.extend('¬ ' + condition(n, names) for n in relations)
    selected = {name: lane(assignments[name], names) for name in ('limit', 'burst', 'rate')}
    selected['capacity'] = term(assignments['capacity'], names, ('burst',))
    defaults = '\n'.join('  ' + name + ' := ' + scalar(value) for name, value in fields)
    let_lines = '\n'.join('  let ' + name + ' : Int := ' + selected[name] for name in ('limit', 'burst', 'rate', 'capacity'))
    output_lines = []
    for name, expression in outputs.items():
        # Counters are natural-valued and have no subtraction or coercion path.
        if name in ('active', 'peak', 'accepted', 'rejected') and not (
                isinstance(expression, ast.Constant) and type(expression.value) is int and expression.value >= 0):
            raise BridgeRejected('Unregistered initial counter.')
        output_lines.append('    ' + name + ' := ' + term(expression, names, ('limit', 'capacity', 'rate', 'now')))
    return '''import TrafficConfig.Extracted
import TrafficProfile.Model

/- Generated from the admitted production profile AST. Do not hand-edit. -/
namespace AlloyStudio.HttpProfile.Extracted
open AlloyStudio
open TrafficConfig

def accepted (p : Model.RawProfile) : Prop :=
  ''' + ' ∧\n  '.join(clauses) + '''

instance (p : Model.RawProfile) : Decidable (accepted p) := by
  unfold accepted
  infer_instance

def normalize (p : Model.RawProfile) : Option Model.RawProfile :=
  if accepted p then some p else none

def defaults : Model.RawProfile := {
''' + defaults + '''
}

def initial (p : Model.RawProfile) (control : Bool) (now : Int) : Model.InitialState :=
''' + let_lines + '''
  {
''' + '\n'.join(output_lines) + '''
    owners := []
    anonymous_owners := []
    peers := []
  }

end AlloyStudio.HttpProfile.Extracted
'''


def layout(tree):
    result = []
    for node in tree.body:
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            result.append(dump(node))
        elif isinstance(node, ast.ClassDef):
            result.append({'class': node.name, 'bases': [dump(x) for x in node.bases],
                'decorators': [dump(x) for x in node.decorator_list],
                'members': [dump(x) if not isinstance(x, ast.FunctionDef) else {
                    'method': x.name, 'arguments': dump(x.args),
                    'decorators': [dump(d) for d in x.decorator_list]} for x in node.body]})
        elif isinstance(node, ast.FunctionDef):
            result.append({'function': node.name, 'arguments': dump(node.args),
                'decorators': [dump(x) for x in node.decorator_list]})
        elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and type(node.value.value) is str:
            continue
        else:
            raise BridgeRejected('Unregistered runtime module execution or rebinding.')
    return result


def linkage(root, source):
    tree = parse(source)
    expected = json.loads((root / 'formal/traffic_profile/runtime-linkage.json').read_text())
    if layout(tree) != expected['moduleLayout']:
        raise BridgeRejected('Runtime class/import/member layout changed.')
    actual = {}
    for cls in [n for n in tree.body if isinstance(n, ast.ClassDef)]:
        for method in [n for n in cls.body if isinstance(n, ast.FunctionDef)]:
            key = cls.name + '.' + method.name
            if key in expected['methods']:
                if key in actual or dump(method) != dump(parse(expected['methods'][key]).body[0]):
                    raise BridgeRejected('Constructor-to-initializer correspondence changed: ' + key)
                actual[key] = hashlib.sha256(dump(method).encode()).hexdigest()
    if set(actual) != set(expected['methods']):
        raise BridgeRejected('Missing constructor correspondence.')
    return actual


def check(root):
    root = Path(root)
    source = (root / 'traffic_profile.py').read_text()
    generated = generate(root, source).encode()
    if generated != (root / 'formal/traffic_profile/TrafficProfile/Extracted.lean').read_bytes():
        raise BridgeRejected('Profile AST differs from its generated Lean program.')
    mappings = linkage(root, (root / 'traffic_http.py').read_text())
    return {'status': 'PASS', 'kind': 'restricted_profile_AST_and_constructor_correspondence',
        'profileSourceSha256': hashlib.sha256(source.encode()).hexdigest(),
        'generatedSha256': hashlib.sha256(generated).hexdigest(), 'constructors': mappings,
        'fields': 23, 'relations': 3, 'normalization': 'exact type, defensive dataclass copy, strict Boolean, one exact-integer clock sample',
        'trust': 'Frozen translator; exact Python, dataclass and initial-container/lock semantics; no hostile trusted-code mutation.',
        'excluded': ['all future admission histories', 'whole Portal initialization', 'complete TRF-00 closure']}
