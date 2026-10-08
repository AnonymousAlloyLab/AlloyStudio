"""Finite, name-free encoding of already redacted learner repair guidance.

This adapter selects no expressions and sees no private correct predicate. Rows
refer to existing operations, so their structural highlighting remains unchanged.
"""
import re


VERSION = 1
MAX_OPERATIONS = 4096
MAX_PATH_LENGTH = 512
MAX_COST = 1048576

# IDs are append-only within a grammar version. Do not sort this tuple when adding
# an operator: published sequences rely on the assigned index.
OPERATOR_NAMES = (
    'and', 'or', 'not', 'implies', 'iff', '=', '!=', '>', '>=', 'in', '<', '<=',
    '!>', '!>=', '!in', '!<', '!<=', 'some', 'no', 'one', 'lone', 'all', '->',
    '.', '<:', ':>', '&', '++', '+', '-', '*', '/', '%', '<<', '>>', '>>>', 'set',
    'exactly', '~', '^', '#', 'int', 'Int', "'", 'before', 'historically', 'once',
    'always', 'eventually', 'after', 'until', 'releases', 'since', 'triggered',
    'if-then-else', 'disj', 'sum', 'some->some', 'some->one', 'some->lone',
    'some->', 'one->some', 'one->one', 'one->lone', 'one->', 'lone->some',
    'lone->one', 'lone->lone', 'lone->', '->some', '->one', '->lone')
_OPERATOR_IDS = {name: 'o' + str(index) for index, name in enumerate(OPERATOR_NAMES)}

_ACTIONS = {'a': 'add', 'd': 'remove', 'r': 'replace', 'g': 'grouped adjustment'}
_SUBJECTS = {
    'c': 'logical control operator', 'p': 'comparison operator',
    'r': 'relation operator', 'm': 'multiplicity or cardinality operator',
    'a': 'arithmetic operator', 't': 'temporal condition or operator',
    'b': 'variable declaration or binding', 'v': 'bound variable use',
    'n': 'relation or signature reference', 'k': 'constant value',
    'f': 'predicate or function call', 'o': 'expression operator',
    's': 'expression structure', 'g': 'grouped changes'}
_COMPONENTS = {'t': 'temporal', 'q': 'quantifier', 'm': 'matrix', 'a': 'ast'}
_COMPONENT_IDS = {value: key for key, value in _COMPONENTS.items()}
_ROLES = {'a': 'affected learner node', 'i': 'insertion anchor', 'g': 'grouped context'}
_ACTION_IDS = {'insert': 'a', 'delete': 'd', 'replace': 'r', 'modify': 'r', 'component-edit': 'g'}
_PATH = re.compile(
    r'(?:ast\[[0-9]{1,8}\]|temporal|matrix|quantifier|'
    r'normalForm\[[0-9]{1,8}\]\.(?:matrix|quantifier)(?:\[[0-9]{1,8}\])?)'
    r'(?:\.child\[[0-9]{1,8}\])*\Z')
_OPERATOR_SUBJECTS = {}
for _subject, _names in (
        ('c', ('and', 'or', 'not', 'implies', 'iff', 'if-then-else')),
        ('p', ('=', '!=', '>', '>=', 'in', '<', '<=', '!>', '!>=', '!in', '!<', '!<=', 'disj')),
        ('r', ('->', '.', '<:', ':>', '&', '++', '~', '^')),
        ('m', ('some', 'no', 'one', 'lone', 'all', 'set', 'exactly', '#')),
        ('a', ('/', '%', '<<', '>>', '>>>', 'int', 'Int', 'sum')),
        ('t', ("'", 'before', 'historically', 'once', 'always', 'eventually', 'after',
               'until', 'releases', 'since', 'triggered')),
        ('o', ('+', '-', '*'))):
    _OPERATOR_SUBJECTS.update({name: _subject for name in _names})
_OPERATOR_SUBJECTS.update({name: 'r' for name in OPERATOR_NAMES if '->' in name})
_NODE_SUBJECTS = {'binding': 'b', 'variable': 'v', 'reference': 'n', 'constant': 'k',
                  'call': 'f', 'formula': 's', 'relation': 's', 'operator': 'o', 'structure': 's'}


def dictionary(status='complete'):
    """Return a fresh JSON-serializable dictionary with no request data."""
    return {'version': VERSION, 'status': status, 'actions': dict(_ACTIONS),
            'subjects': dict(_SUBJECTS), 'operators': {value: key for key, value in _OPERATOR_IDS.items()},
            'components': dict(_COMPONENTS), 'roles': dict(_ROLES)}


def _subject(operation, kind, component, operator):
    if kind == 'component-edit':
        return 'g'
    if component == 'quantifier':
        return 'b'
    if component == 'temporal':
        return 't'
    # Insert source metadata describes an anchor, not the hidden inserted node.
    if kind == 'insert':
        return 's'
    node = operation.get('sourceNodeKind')
    if not isinstance(node, str):
        node = None
    if node in ('binding', 'variable', 'reference', 'constant', 'call'):
        return _NODE_SUBJECTS[node]
    return _OPERATOR_SUBJECTS.get(operator, _NODE_SUBJECTS.get(node, 's'))


def encode_operations(operations, distance):
    """Encode one complete trace, or reject it without exposing partial rows.

    Source snippets, names, reasons, ranges and any unknown fields are deliberately
    ignored. Only finite operator tokens and whitelisted structural paths survive.
    """
    if (type(distance) is not int or not 0 <= distance <= MAX_COST
            or not isinstance(operations, list) or len(operations) > MAX_OPERATIONS):
        raise ValueError('Invalid repair trace')
    sequence, slots = [], {}
    for index, operation in enumerate(operations):
        if not isinstance(operation, dict):
            raise ValueError('Invalid repair operation')
        kind, component = operation.get('kind'), operation.get('component')
        if (not isinstance(kind, str) or not isinstance(component, str)
                or kind not in _ACTION_IDS or component not in _COMPONENT_IDS):
            raise ValueError('Unknown repair category')
        cost, aggregate = operation.get('cost'), operation.get('aggregate', False)
        if (type(cost) is not int or not 1 <= cost <= MAX_COST or type(aggregate) is not bool
                or aggregate != (kind == 'component-edit') or not aggregate and cost != 1):
            raise ValueError('Invalid repair cost')
        role = operation.get('sourceRole', 'insertion-anchor' if kind == 'insert' else 'affected')
        if role not in ('affected', 'insertion-anchor'):
            raise ValueError('Invalid learner role')
        if kind == 'insert' and 'sourceRole' in operation and role != 'insertion-anchor':
            # Canonical quantifier insertions have no sourceRole, so this does not
            # change their legacy handling or infer a target from a learner node.
            raise ValueError('Invalid insertion anchor')
        source = operation.get('sourceOperator')
        source_id = _OPERATOR_IDS.get(source) if isinstance(source, str) else None
        replacement = operation.get('replacementOperator')
        if 'replacementOperator' in operation and (kind not in ('replace', 'modify')
                or not isinstance(replacement, str) or replacement not in _OPERATOR_IDS):
            raise ValueError('Disallowed replacement hint')
        subject = _subject(operation, kind, component, source if source_id else None)
        row = {'action': _ACTION_IDS[kind], 'subject': subject, 'component': _COMPONENT_IDS[component],
               'role': 'g' if aggregate else 'i' if role == 'insertion-anchor' else 'a',
               'cost': cost, 'operationIndex': index}
        path = operation.get('path')
        if path is not None:
            if not isinstance(path, str) or len(path) > MAX_PATH_LENGTH or not _PATH.fullmatch(path):
                raise ValueError('Invalid structural path')
            row['path'] = path
        if source_id:
            row['sourceOperator'] = source_id
        if replacement is not None:
            row['replacementOperator'] = _OPERATOR_IDS[replacement]
        if subject in ('b', 'v'):
            key = (component, path if path is not None else index)
            if key not in slots:
                slots[key] = 'b' + str(len(slots))
            row['bindingSlot'] = slots[key]
        sequence.append(row)
    if sum(row['cost'] for row in sequence) != distance:
        raise ValueError('Repair costs do not match distance')
    return sequence


def attach(result):
    """Add the compact representation to public successful feedback in place."""
    if result.get('status') != 'ok':
        return
    try:
        sequence = encode_operations(result.get('operations'), result.get('distance'))
    except (TypeError, ValueError):
        result['repairGrammar'] = dictionary('unavailable')
        result['repairSequence'] = []
    else:
        result['repairGrammar'] = dictionary()
        result['repairSequence'] = sequence
