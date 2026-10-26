"""ASRL/1 reference parser and positional guidance resolver.

No HTTP integration, solver, source mutation or oracle access. All statements
refer to one immutable learner snapshot. See docs/canonical-repair-language.md.
"""
from dataclasses import dataclass
import json
import re

import repair_grammar as legacy

MAX_BYTES = 1 << 20
MAX_STATEMENTS = 4096
MAX_DEPTH = 64
MAX_INDEX = 99_999_999
CATEGORIES = {
    'c': 'CONTROL', 'p': 'COMPARISON', 'r': 'RELATION', 'm': 'MULTIPLICITY',
    'a': 'ARITHMETIC', 't': 'TEMPORAL', 'b': 'BINDING', 'v': 'VARIABLE_USE',
    'n': 'REFERENCE', 'k': 'CONSTANT', 'f': 'CALL', 'o': 'OPERATOR', 's': 'STRUCTURE',
}
OPERATORS = frozenset(legacy.OPERATOR_NAMES)
_DIGEST = re.compile(r'[0-9a-f]{64}\Z')
_MATRIX = re.compile(r'normalForm\[([0-9]{1,8})\]\.matrix((?:\.child\[[0-9]{1,8}\])*)\Z')
_BINDING = re.compile(r'normalForm\[([0-9]{1,8})\]\.quantifier\[([0-9]{1,8})\]\Z')
_TEMPORAL = re.compile(r'temporal((?:\.child\[[0-9]{1,8}\])+)\Z')
_CHILD = re.compile(r'\.child\[([0-9]{1,8})\]')
_TOKEN = re.compile(r'[ \t\r\n]+|"(?:[^"\\\r\n]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*"|[A-Z_]+|[0-9]+|[{}\[\],;?]')


class LanguageError(ValueError):
    """Invalid syntax, address, evidence or snapshot identity."""


def _require(condition, reason):
    if not condition:
        raise LanguageError(reason)


def _index(value):
    return type(value) is int and 0 <= value <= MAX_INDEX


@dataclass(frozen=True)
class Address:
    # None/None means temporal skeleton; integer/None means phase matrix.
    temporal: int | None
    quantifier: int | None
    inside: tuple[int, ...] = ()

    def __post_init__(self):
        _require(self.temporal is None or _index(self.temporal), 'invalid temporal coordinate')
        _require(self.quantifier is None or _index(self.quantifier), 'invalid quantifier coordinate')
        _require(type(self.inside) is tuple and len(self.inside) <= MAX_DEPTH
                 and all(_index(i) for i in self.inside), 'invalid inside sequence')
        _require(self.temporal is not None or self.quantifier is None, 'invalid address namespace')
        _require(self.quantifier is None or not self.inside, 'binding has no inside sequence')

    @property
    def component(self):
        return 'temporal' if self.temporal is None else 'quantifier' if self.quantifier is not None else 'matrix'


@dataclass(frozen=True)
class Node:
    """Public learner structure; never carries names, literals or private targets."""
    category: str
    operator: str | None = None
    children: tuple['Node', ...] = ()

    def __post_init__(self):
        _require(isinstance(self.category, str) and self.category in CATEGORIES.values(), 'invalid category')
        _require(self.operator is None or isinstance(self.operator, str) and self.operator in OPERATORS, 'invalid operator')
        _require(type(self.children) is tuple and all(isinstance(n, Node) for n in self.children), 'invalid children')


@dataclass(frozen=True)
class Phase:
    bindings: tuple[Node, ...]
    matrix: Node

    def __post_init__(self):
        _require(type(self.bindings) is tuple and all(isinstance(n, Node) and n.category == 'BINDING'
                                                   for n in self.bindings), 'invalid binding list')
        _require(isinstance(self.matrix, Node), 'invalid matrix')


@dataclass(frozen=True)
class Snapshot:
    revision: str
    phases: tuple[Phase, ...]
    temporal_tree: Node

    def __post_init__(self):
        _require(isinstance(self.revision, str) and _DIGEST.fullmatch(self.revision), 'invalid snapshot identifier')
        _require(type(self.phases) is tuple and all(isinstance(p, Phase) for p in self.phases), 'invalid phases')
        _require(isinstance(self.temporal_tree, Node), 'invalid temporal tree')


@dataclass(frozen=True)
class Statement:
    verb: str
    address: Address
    category: str | None = None
    placement: str | None = None
    target: str | None = None  # None in UPDATE is the public '?' placeholder.

    def __post_init__(self):
        _require(self.verb in ('CREATE', 'READ', 'UPDATE', 'DELETE'), 'unknown verb')
        _require(isinstance(self.address, Address), 'invalid address')
        if self.verb == 'READ':
            _require(self.category is None and self.placement is None and self.target is None, 'invalid READ fields')
            return
        _require(isinstance(self.category, str) and self.category in CATEGORIES.values(), 'invalid category')
        _require(self.placement in ('AT', 'NEAR') if self.verb == 'CREATE' else self.placement is None,
                 'invalid placement')
        _require(self.target is None or self.verb == 'UPDATE' and isinstance(self.target, str)
                 and self.target in OPERATORS, 'invalid replacement operator')
        if self.address.component == 'quantifier':
            _require(self.category == 'BINDING', 'prefix edit must be BINDING')
        if self.address.component == 'temporal':
            _require(self.category == 'TEMPORAL', 'skeleton edit must be TEMPORAL')
        if self.verb == 'CREATE':
            expected = 'AT' if self.address.component == 'quantifier' else 'NEAR'
            _require(self.placement == expected, 'CREATE must distinguish gap from anchor')

    @property
    def cost(self):
        return 0 if self.verb == 'READ' else 1


@dataclass(frozen=True)
class Program:
    revision: str
    statements: tuple[Statement, ...]

    def __post_init__(self):
        _require(isinstance(self.revision, str) and _DIGEST.fullmatch(self.revision), 'invalid snapshot identifier')
        _require(type(self.statements) is tuple and len(self.statements) <= MAX_STATEMENTS
                 and all(isinstance(s, Statement) for s in self.statements), 'invalid statement sequence')

    @property
    def cost(self):
        return sum(s.cost for s in self.statements)


def resolve(snapshot, address):
    """Resolve one existing occurrence; a gap is intentionally not a node."""
    _require(isinstance(snapshot, Snapshot) and isinstance(address, Address), 'invalid lookup input')
    if address.temporal is None:
        node = snapshot.temporal_tree
    else:
        _require(address.temporal < len(snapshot.phases), 'phase outside snapshot')
        phase = snapshot.phases[address.temporal]
        if address.quantifier is not None:
            _require(address.quantifier < len(phase.bindings), 'binding outside snapshot')
            return phase.bindings[address.quantifier]
        node = phase.matrix
    for index in address.inside:
        _require(index < len(node.children), 'child outside occurrence')
        node = node.children[index]
    return node


def _inspect(statement, snapshot):
    address = statement.address
    if statement.verb == 'CREATE' and address.component == 'quantifier':
        _require(address.temporal < len(snapshot.phases), 'phase outside snapshot')
        _require(address.quantifier <= len(snapshot.phases[address.temporal].bindings), 'binding gap outside snapshot')
        return {'address': address, 'role': 'gap', 'category': 'BINDING', 'operator': None}
    node = resolve(snapshot, address)
    if statement.verb in ('UPDATE', 'DELETE'):
        _require(statement.category == node.category, 'category does not match occurrence')
    if address.component == 'temporal':
        _require(node.category == 'TEMPORAL', 'invalid temporal-skeleton category')
    return {'address': address, 'role': 'anchor' if statement.verb == 'CREATE' else 'node',
            'category': node.category, 'operator': node.operator}


def inspect_program(program, snapshot):
    """Validate the whole batch, then return descriptors. Never applies C/U/D."""
    _require(isinstance(program, Program) and isinstance(snapshot, Snapshot), 'invalid program input')
    _require(program.revision == snapshot.revision, 'snapshot identifier mismatch')
    return tuple(_inspect(statement, snapshot) for statement in program.statements)


class _Parser:
    def __init__(self, source):
        _require(isinstance(source, str), 'program must be text')
        try:
            size = len(source.encode('utf-8'))
        except UnicodeError:
            raise LanguageError('invalid program encoding') from None
        _require(size <= MAX_BYTES, 'program too large')
        self.tokens = []
        offset = 0
        while offset < len(source):
            match = _TOKEN.match(source, offset)
            _require(match is not None, 'invalid lexical token')
            value = match.group()
            if value[0] not in ' \t\r\n':
                self.tokens.append(value)
            offset = match.end()
        self.index = 0

    def pop(self, expected=None):
        _require(self.index < len(self.tokens), 'unexpected end of program')
        value = self.tokens[self.index]
        _require(expected is None or value == expected, 'unexpected token')
        self.index += 1
        return value

    def peek(self):
        return self.tokens[self.index] if self.index < len(self.tokens) else None

    def number(self):
        value = self.pop()
        _require(re.fullmatch(r'[0-9]{1,8}', value), 'invalid index')
        return int(value)

    def string(self):
        value = self.pop()
        _require(value.startswith('"'), 'expected quoted value')
        try:
            result = json.loads(value)
        except (ValueError, UnicodeError):
            raise LanguageError('invalid quoted value') from None
        _require(isinstance(result, str), 'expected string')
        return result

    def address(self):
        self.pop('[')
        if self.peek() == 'T':
            self.pop('T'); temporal = None
            self.pop(','); self.pop('_'); quantifier = None
        else:
            temporal = self.number(); self.pop(',')
            if self.peek() == 'M':
                self.pop('M'); quantifier = None
            else:
                quantifier = self.number()
        self.pop(','); self.pop('[')
        inside = []
        if self.peek() != ']':
            inside.append(self.number())
            while self.peek() == ',':
                self.pop(','); inside.append(self.number())
                _require(len(inside) <= MAX_DEPTH, 'inside sequence too deep')
        self.pop(']'); self.pop(']')
        return Address(temporal, quantifier, tuple(inside))

    def statement(self):
        verb = self.pop()
        _require(verb in ('CREATE', 'READ', 'UPDATE', 'DELETE'), 'unknown verb')
        category = None if verb == 'READ' else self.pop()
        placement = self.pop() if verb == 'CREATE' else None
        address = self.address()
        target = None
        if verb == 'UPDATE':
            self.pop('TO')
            if self.peek() == '?':
                self.pop('?')
            else:
                target = self.string()
        self.pop(';')
        return Statement(verb, address, category, placement, target)


def parse(source):
    parser = _Parser(source)
    parser.pop('ON'); revision = parser.string(); parser.pop('{')
    statements = []
    while parser.peek() != '}':
        _require(len(statements) < MAX_STATEMENTS, 'too many statements')
        statements.append(parser.statement())
    parser.pop('}')
    _require(parser.peek() is None, 'trailing input')
    return Program(revision, tuple(statements))


def format_address(address):
    left = 'T' if address.temporal is None else str(address.temporal)
    middle = '_' if address.temporal is None else 'M' if address.quantifier is None else str(address.quantifier)
    return '['+left+','+middle+',['+','.join(map(str, address.inside))+']]'


def format_program(program):
    lines = ['ON '+json.dumps(program.revision)+' {']
    for statement in program.statements:
        parts = [statement.verb]
        if statement.category is not None:
            parts.append(statement.category)
        if statement.placement is not None:
            parts.append(statement.placement)
        parts.append(format_address(statement.address))
        if statement.verb == 'UPDATE':
            parts.extend(['TO', '?' if statement.target is None else json.dumps(statement.target)])
        lines.append('    '+' '.join(parts)+';')
    return '\n'.join(lines+['}'])+'\n'


def address_from_legacy_path(path, component):
    """Syntax conversion only; existence and path authority are separate checks."""
    _require(isinstance(path, str) and len(path) <= legacy.MAX_PATH_LENGTH, 'missing or oversized path')
    if component == 'matrix' and (match := _MATRIX.fullmatch(path)):
        return Address(int(match[1]), None, tuple(map(int, _CHILD.findall(match[2]))))
    if component == 'quantifier' and (match := _BINDING.fullmatch(path)):
        return Address(int(match[1]), int(match[2]))
    if component == 'temporal' and (match := _TEMPORAL.fullmatch(path)):
        return Address(None, None, tuple(map(int, _CHILD.findall(match[1]))))
    raise LanguageError('no exact canonical address')


@dataclass(frozen=True)
class Residual:
    operation_index: int
    cost: int
    reason: str


@dataclass(frozen=True)
class Projection:
    program: Program
    operation_indexes: tuple[int, ...]
    residuals: tuple[Residual, ...]
    recorded_distance: int

    @property
    def complete(self):
        return not self.residuals


def project_legacy(snapshot, operations, distance):
    """Preserve every original cost, including explicitly unpositioned evidence.

    Snapshot construction is the caller's responsibility. Passing a trace or
    snapshot here does not authenticate it as a production engine response.
    """
    _require(isinstance(snapshot, Snapshot), 'invalid snapshot')
    try:
        rows = legacy.encode_operations(operations, distance)
    except (ValueError, TypeError):
        raise LanguageError('invalid legacy trace') from None
    commands, indexes, residuals = [], [], []
    operator_names = legacy.dictionary()['operators']
    for row in rows:
        try:
            _require(row['action'] != 'g', 'aggregate has no atomic address')
            original = operations[row['operationIndex']]
            address = address_from_legacy_path(row.get('path'), original['component'])
            verb = {'a': 'CREATE', 'r': 'UPDATE', 'd': 'DELETE'}[row['action']]
            if verb == 'CREATE' and address.component == 'temporal':
                # Legacy temporal DP records target insertion positions. Their
                # accidental existence in the learner is not an anchor.
                _require(original.get('sourceRole') == 'insertion-anchor',
                         'temporal insertion has no learner anchor evidence')
            expected_role = 'i' if verb == 'CREATE' else 'a'
            _require(row['role'] == expected_role, 'trace role does not justify an affected node')
            placement = ('AT' if address.component == 'quantifier' else 'NEAR') if verb == 'CREATE' else None
            target = operator_names[row['replacementOperator']] if 'replacementOperator' in row else None
            command = Statement(verb, address, CATEGORIES[row['subject']], placement, target)
            selected = _inspect(command, snapshot)
            if selected['role'] != 'gap' and 'sourceOperator' in row:
                _require(selected['operator'] == operator_names[row['sourceOperator']],
                         'source operator does not match occurrence')
            commands.append(command); indexes.append(row['operationIndex'])
        except LanguageError as error:
            residuals.append(Residual(row['operationIndex'], row['cost'], str(error)))
    program = Program(snapshot.revision, tuple(commands))
    _require(program.cost+sum(r.cost for r in residuals) == distance, 'projection cost mismatch')
    return Projection(program, tuple(indexes), tuple(residuals), distance)
