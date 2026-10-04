#!/usr/bin/env python3
"""Closed, value-bearing source selectors for the finite TRF-00 profile.

AST paths select exact nodes within named Python declarations. Only numeric
literals and closed arithmetic execute; no eval, imports or production calls.
Non-Python selectors bind unique literal fragments, not substring coincidences.
"""
import ast
import hashlib
import json
import math
import operator
from pathlib import Path
import re


class BindingError(ValueError):
    pass


def _inside(root, relative):
    path = Path(relative)
    if path.is_absolute() or '..' in path.parts:
        raise BindingError('Source must be a repository relative path')
    return Path(root) / path


def _at(node, path):
    for component in path:
        if type(component) is int:
            if type(node) is not list or not 0 <= component < len(node):
                raise BindingError('AST list selector missing')
            node = node[component]
        elif type(component) is str and isinstance(node, ast.AST) and component in node._fields:
            node = getattr(node, component)
        else:
            raise BindingError('AST selector missing or malformed')
    if not isinstance(node, ast.AST):
        raise BindingError('Selector does not resolve to an AST node')
    return node


def _scope(tree, name):
    if name == '<module>':
        return tree
    owner = tree
    for component in name.split('.'):
        matches = [node for node in owner.body if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
                   and node.name == component]
        if len(matches) != 1:
            raise BindingError('Named declaration missing or ambiguous')
        owner = matches[0]
    return owner


def _expression(text):
    try:
        return ast.parse(text, mode='eval').body
    except SyntaxError:
        nodes = ast.parse(text).body
        if len(nodes) != 1:
            raise BindingError('Selector expression must be one syntactic object')
        return nodes[0]


def _number(node):
    if isinstance(node, ast.Constant) and type(node.value) in (int, float):
        value = node.value
    elif isinstance(node, ast.UnaryOp) and type(node.op) in (ast.USub, ast.UAdd):
        child = _number(node.operand)
        value = -child if isinstance(node.op, ast.USub) else child
    elif isinstance(node, ast.BinOp) and type(node.op) in (ast.Add, ast.Sub, ast.Mult, ast.Div, ast.FloorDiv, ast.Pow):
        left, right = _number(node.left), _number(node.right)
        if isinstance(node.op, ast.Pow) and (type(right) is not int or not 0 <= right <= 64):
            raise BindingError('Exponent outside closed arithmetic domain')
        operation = {ast.Add:operator.add, ast.Sub:operator.sub, ast.Mult:operator.mul,
                     ast.Div:operator.truediv, ast.FloorDiv:operator.floordiv, ast.Pow:operator.pow}[type(node.op)]
        try:
            value = operation(left, right)
        except (ArithmeticError, ValueError):
            raise BindingError('Invalid closed arithmetic') from None
    else:
        raise BindingError('Nonliteral or executable numeric expression')
    if (type(value) not in (int, float) or abs(value) > 2**64
            or type(value) is float and not math.isfinite(value)):
        raise BindingError('Nonfinite or excessive numeric expression')
    return value


def _python(source, binding):
    required = {'kind','scope','expression','expressionPath','valuePath','sourcePath'}
    if set(binding) != required:
        raise BindingError('Incomplete or unknown Python binding field')
    tree = ast.parse(source)
    owner = _scope(tree, binding['scope'])
    expression = _at(owner, binding['expressionPath'])
    if ast.dump(expression, include_attributes=False) != ast.dump(_expression(binding['expression']), include_attributes=False):
        raise BindingError('Selected source expression changed')
    if binding['sourcePath'] != binding['expressionPath'] + binding['valuePath']:
        raise BindingError('Numeric path is detached from the expression')
    return _number(_at(owner, binding['sourcePath']))


def _text(source, binding):
    required = {'kind','text','numeric','scale','subtract','conversion'}
    if set(binding) != required or type(binding['scale']) is not int or binding['scale'] < 1 or type(binding['subtract']) is not int:
        raise BindingError('Invalid text numeric selector')
    text, numeric = binding['text'], binding['numeric']
    if type(text) is not str or not text or source.count(text) != 1 or text.count(numeric) != 1:
        raise BindingError('Text numeric source is absent or ambiguous')
    if re.fullmatch(r'[0-9_]+(?:\.[0-9]+)?[Ll]?', numeric) is None:
        raise BindingError('Only literal numeric text is admitted')
    value = _number(ast.parse(numeric.rstrip('Ll'), mode='eval').body)
    value = value * binding['scale'] - binding['subtract']
    if binding['conversion'] == 'integer-exact':
        if value != int(value):
            raise BindingError('Lossy numeric text conversion')
        return int(value)
    if binding['conversion'] != 'preserve':
        raise BindingError('Unknown numeric text conversion')
    return value


def extracted_values(root, profile):
    rows = profile['limits']
    if type(rows) is not list or len({row['id'] for row in rows}) != len(rows):
        raise BindingError('Limit identities missing or duplicated')
    values, active = {}, set()
    by_id = {row['id']:row for row in rows}
    def extract(identifier):
        if identifier in values:
            return values[identifier]
        if identifier in active or identifier not in by_id:
            raise BindingError('Cyclic or unknown numeric dependency')
        active.add(identifier)
        row = by_id[identifier]
        binding = row.get('sourceBinding')
        if type(binding) is not dict:
            raise BindingError('Missing value-bearing source binding: ' + identifier)
        kind = binding.get('kind')
        source = _inside(root,row['source']).read_text(encoding='utf-8')
        if kind == 'python-ast':
            value = _python(source,binding)
        elif kind == 'text-numeric':
            value = _text(source,binding)
        elif kind == 'all-of':
            if set(binding) != {'kind','bindings'} or not 2 <= len(binding['bindings']) <= 16:
                raise BindingError('Invalid bounded multi-site binding')
            sites = []
            for site in binding['bindings']:
                if site.get('kind') == 'python-ast':
                    sites.append(_python(source,site))
                elif site.get('kind') == 'text-numeric':
                    sites.append(_text(source,site))
                else:
                    raise BindingError('Unsupported multi-site selector')
            if any(type(v) is not type(sites[0]) or v != sites[0] for v in sites):
                raise BindingError('Numeric sites disagree')
            value = sites[0]
        elif kind == 'text-reference':
            if (set(binding) != {'kind','texts','reference'} or type(binding['texts']) is not list
                    or not 1 <= len(binding['texts']) <= 16
                    or any(type(t) is not str or not t or source.count(t) != 1 for t in binding['texts'])):
                raise BindingError('Missing or ambiguous reference expression')
            value = extract(binding['reference'])
        elif kind == 'python-derived':
            if set(binding) != {'kind','scope','expression','expressionPath','operation','references'}:
                raise BindingError('Invalid derived numeric binding')
            expression = _at(_scope(ast.parse(source),binding['scope']),binding['expressionPath'])
            if ast.dump(expression,include_attributes=False) != ast.dump(_expression(binding['expression']),include_attributes=False):
                raise BindingError('Derived source expression changed')
            if binding['operation'] != 'sum' or len(binding['references']) < 1:
                raise BindingError('Unsupported derived operation')
            value = sum(extract(name) for name in binding['references'])
        elif kind == 'declaration':
            if (set(binding) != {'kind','justification'} or row['classification'] != 'SPECIFIED_BUT_TRUSTED'
                    or row['source'] != 'closure/traffic-refinement/service-profile.json'):
                raise BindingError('Only declared external deployment limits are self-binding')
            value = row['value']
        elif kind == 'disabled-feature':
            if (set(binding) != {'kind','sourceSha256','selectedField','reason'}
                    or binding['selectedField'] != 'preparedPoolCaching'
                    or profile['selected'].get(binding['selectedField']) is not False
                    or row['id'] not in ('prepared.entries','prepared.bytes')):
                raise BindingError('Invalid explicit disabled-feature declaration')
            if hashlib.sha256(source.encode('utf-8')).hexdigest() != binding['sourceSha256']:
                raise BindingError('Disabled feature source changed')
            value = 0
        elif kind == 'python-discard-loop':
            if set(binding) != {'kind','scope','expressionPath','expression'}:
                raise BindingError('Invalid discard-loop selector')
            expression = _at(_scope(ast.parse(source),binding['scope']),binding['expressionPath'])
            expected = _expression('while not self.closed.is_set() and self.process.stderr.read(4096):\n    pass')
            if (binding['scope'] != '_Worker._drain' or row['id'] != 'worker.stderr_retained'
                    or ast.dump(expression,include_attributes=False) != ast.dump(expected,include_attributes=False)
                    or ast.dump(_expression(binding['expression']),include_attributes=False) != ast.dump(expected,include_attributes=False)):
                raise BindingError('Stderr loop does not discard every read chunk')
            value = 0
        else:
            raise BindingError('Unknown numeric source binding kind')
        active.remove(identifier)
        if (type(value) not in (int,float) or value < 0 or abs(value) > 2**64
                or type(value) is float and not math.isfinite(value)):
            raise BindingError('Numeric source value has invalid domain')
        if type(value) is not type(row['value']) or value != row['value']:
            raise BindingError('Source value disagrees with frozen profile: ' + identifier)
        values[identifier] = value
        return value
    for row in rows:
        extract(row['id'])
    return values


def check(root, profile):
    values = extracted_values(root,profile)
    return {'status':'PASS','limits':len(values),'sourceDerived':sum(r['sourceBinding']['kind'] not in
        ('declaration','disabled-feature') for r in profile['limits']),
        'declarationOnly':sum(r['sourceBinding']['kind'] == 'declaration' for r in profile['limits']),
        'explicitlyDisabled':sum(r['sourceBinding']['kind'] == 'disabled-feature' for r in profile['limits']),
        'valuesSha256':hashlib.sha256(json.dumps(values,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest()}
