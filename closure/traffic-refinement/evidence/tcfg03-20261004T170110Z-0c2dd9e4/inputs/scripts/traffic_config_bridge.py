#!/usr/bin/env python3
"""Translate the admitted numeric guard AST into a small Lean scalar program.

The structural adapter is deliberately closed: exact built-in type checks,
as_integer_ratio normalization, constant diagnostics and identity returns only.
Arithmetic conditions are translated from the actual source, not replaced by a
known-good formula. Python built-in classification and exact ratio semantics are
explicit TCB; this bridge does not prove constructors or the whole traffic model.
"""
import ast
import copy
import hashlib
from pathlib import Path


class BridgeRejected(ValueError):
    pass


TEMPLATE = '''
def validated_int(value, minimum=1, maximum=67108864):
    if type(minimum) is not int or type(maximum) is not int or integer_bounds:
        raise ValueError('diagnostic')
    if type(value) is not int or integer_value:
        raise ValueError('diagnostic')
    return value

def validated_seconds(value, minimum_zero=False, maximum=86400):
    if type(minimum_zero) is not bool or type(maximum) is not int or seconds_bounds:
        raise ValueError('diagnostic')
    if type(value) is not int and type(value) is not float:
        raise ValueError('diagnostic')
    try:
        numerator, denominator = value.as_integer_ratio()
    except (ValueError, OverflowError):
        raise ValueError('diagnostic') from None
    if denominator <= 0:
        raise ValueError('diagnostic')
    if seconds_value:
        raise ValueError('diagnostic')
    return value
'''


def dump(node):
    return ast.dump(node, include_attributes=False)


def diagnostic_literals(tree):
    for node in ast.walk(tree):
        if isinstance(node, ast.Raise):
            call = node.exc
            if (not isinstance(call, ast.Call) or not isinstance(call.func, ast.Name)
                    or call.func.id != 'ValueError' or call.keywords or len(call.args) != 1
                    or not isinstance(call.args[0], ast.Constant)
                    or type(call.args[0].value) is not str):
                raise BridgeRejected('Nonconstant or unregistered diagnostic.')
            call.args[0] = ast.Constant('diagnostic')


def extract(source):
    """Return guards only after all remaining executable syntax is matched."""
    try:
        tree = ast.parse(source)
    except (SyntaxError, RecursionError):
        raise BridgeRejected('Invalid scalar validator syntax.') from None
    if tree.body and isinstance(tree.body[0], ast.Expr) and isinstance(tree.body[0].value, ast.Constant):
        if type(tree.body[0].value.value) is not str:
            raise BridgeRejected('Unexpected module expression.')
        tree.body.pop(0)
    tree = copy.deepcopy(tree)
    try:
        integer, seconds = tree.body
        if not isinstance(integer, ast.FunctionDef) or not isinstance(seconds, ast.FunctionDef):
            raise BridgeRejected('Only two registered functions are admitted.')
        bound = integer.body[0].test
        value = integer.body[1].test
        seconds_bound = seconds.body[0].test
        if (not isinstance(bound, ast.BoolOp) or not isinstance(bound.op, ast.Or)
                or len(bound.values) != 4
                or not isinstance(value, ast.BoolOp) or not isinstance(value.op, ast.Or)
                or len(value.values) != 2
                or not isinstance(seconds_bound, ast.BoolOp) or not isinstance(seconds_bound.op, ast.Or)
                or len(seconds_bound.values) != 3):
            raise BridgeRejected('Unregistered type/guard composition.')
        guards = {'integer_bounds': ast.BoolOp(ast.Or(), bound.values[2:]),
                  'integer_value': value.values[1],
                  'seconds_bounds': seconds_bound.values[2],
                  'seconds_value': seconds.body[4].test}
        bound.values[2:] = [ast.Name('integer_bounds', ast.Load())]
        value.values[1] = ast.Name('integer_value', ast.Load())
        seconds_bound.values[2] = ast.Name('seconds_bounds', ast.Load())
        seconds.body[4].test = ast.Name('seconds_value', ast.Load())
        diagnostic_literals(tree)
        if dump(tree) != dump(ast.parse(TEMPLATE)):
            raise BridgeRejected('Unregistered normalization, control flow or return.')
        return guards
    except (ValueError, AttributeError, IndexError, TypeError) as error:
        if isinstance(error, BridgeRejected):
            raise
        raise BridgeRejected('Malformed scalar validator structure.') from None


def term(node, names):
    if isinstance(node, ast.Name) and node.id in names and node.id != 'minimum_zero':
        return names[node.id]
    if isinstance(node, ast.Constant) and type(node.value) is int and abs(node.value) <= 1000000000:
        return str(node.value) if node.value >= 0 else '(' + str(node.value) + ')'
    if isinstance(node, ast.BinOp) and isinstance(node.op, ast.Mult):
        return '(' + term(node.left, names) + ' * ' + term(node.right, names) + ')'
    raise BridgeRejected('Unregistered arithmetic expression.')


def condition(node, names):
    if isinstance(node, ast.BoolOp):
        join = ' ∨ ' if isinstance(node.op, ast.Or) else ' ∧ ' if isinstance(node.op, ast.And) else None
        if join is not None:
            return '(' + join.join(condition(value, names) for value in node.values) + ')'
    if isinstance(node, ast.UnaryOp) and isinstance(node.op, ast.Not):
        if isinstance(node.operand, ast.Name) and node.operand.id == 'minimum_zero':
            return '(minimumZero = false)'
        return '(¬ ' + condition(node.operand, names) + ')'
    if isinstance(node, ast.Compare):
        symbols = {ast.Lt: '<', ast.LtE: '≤', ast.Gt: '>', ast.GtE: '≥', ast.Eq: '=', ast.NotEq: '≠'}
        values = [node.left, *node.comparators]
        parts = []
        for left, op, right in zip(values, node.ops, values[1:]):
            if type(op) not in symbols:
                raise BridgeRejected('Unregistered comparison.')
            lhs, rhs = term(left, names), term(right, names)
            if type(op) is ast.Lt:
                parts.append('(less ' + lhs + ' ' + rhs + ')')
            elif type(op) is ast.LtE:
                parts.append('(lessEqual ' + lhs + ' ' + rhs + ')')
            elif type(op) is ast.Gt:
                parts.append('(less ' + rhs + ' ' + lhs + ')')
            elif type(op) is ast.GtE:
                parts.append('(lessEqual ' + rhs + ' ' + lhs + ')')
            else:
                parts.append('(' + lhs + ' ' + symbols[type(op)] + ' ' + rhs + ')')
        return '(' + ' ∧ '.join(parts) + ')'
    raise BridgeRejected('Unregistered Boolean expression.')


def generate(source):
    guards = extract(source)
    integer_names = {'value': 'number', 'minimum': 'minimum', 'maximum': 'maximum'}
    seconds_names = {'numerator': 'numerator', 'denominator': '(Int.ofNat denominator)',
                     'maximum': 'maximum', 'minimum_zero': 'minimumZero'}
    integer_bounds = condition(guards['integer_bounds'], {'minimum': 'minimum', 'maximum': 'maximum'})
    integer_value = condition(guards['integer_value'], integer_names)
    seconds_bounds = condition(guards['seconds_bounds'], {'maximum': 'maximum'})
    seconds_value = condition(guards['seconds_value'], seconds_names)
    return f'''import TrafficConfig.Scalar

/- Generated from the admitted Python numeric guard AST. Do not hand-edit. -/
namespace AlloyStudio.TrafficConfig.Extracted

def validatedInt (value : Scalar) (minimum maximum : Int) : Option Scalar :=
  if {integer_bounds} then none else
  match value with
  | .integer number =>
    if {integer_value} then none else some value
  | .floating _ _ => none
  | .invalid _ => none

def validatedSeconds (value : Scalar) (minimumZero : Bool) (maximum : Int) : Option Scalar :=
  if {seconds_bounds} then none else
  let ratio := match value with
    | .integer number => some (number, 1)
    | .floating numerator denominator =>
      if denominator = 0 then none else some (numerator, denominator)
    | .invalid _ => none
  match ratio with
  | none => none
  | some (numerator, denominator) =>
    if {seconds_value} then none else some value

end AlloyStudio.TrafficConfig.Extracted
'''


def check(root):
    root = Path(root)
    source = (root / 'traffic_limits.py').read_text(encoding='utf-8')
    generated = generate(source).encode('utf-8')
    actual = (root / 'formal/traffic_config/TrafficConfig/Extracted.lean').read_bytes()
    if actual != generated:
        raise BridgeRejected('Production guard AST and registered Lean program differ.')
    return {'status': 'PASS', 'kind': 'restricted_ast_translation',
            'sourceSha256': hashlib.sha256(source.encode()).hexdigest(),
            'generatedSha256': hashlib.sha256(generated).hexdigest(),
            'guards': {name: hashlib.sha256(dump(node).encode()).hexdigest()
                       for name, node in extract(source).items()},
            'normalizationTrust': 'Exact built-in Python type identity and int/float.as_integer_ratio; '
                                  'typed bound parameters and positive float denominators.',
            'excluded': ['arbitrary Python code', 'constructor allocation/state refinement',
                         'complete TRF-00 profile closure']}
