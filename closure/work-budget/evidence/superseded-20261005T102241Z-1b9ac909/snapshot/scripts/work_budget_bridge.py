#!/usr/bin/env python3
"""Extract charged-work Java statements into executable Lean definitions.

This restricted parser translates the actual expressions, branches, assignments,
returns and calls of charge/sampleClock/checkpoint. Unknown syntax is refused;
method bodies are not substituted with a known-good formula or a source hash.
The lexer/parser, normal javac/Java execution, ThreadLocal identity and nanoTime
primitive are explicit trust boundaries. Only these three bodies are mapped;
charge-site completeness and the rest of WorkBudget are separate obligations.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'vendor/acgn/src/is/fivefivefive/CanDis/WorkBudget.java'
GENERATED = 'formal/work_budget/Generated.lean'
SHELL = 'formal/work_budget/work-budget-shell.java.txt'
PUBLICATION = 'formal/work_budget/publication-template.java.txt'
FEEDBACK = 'engine/src/live/LiveFeedback.java'


class BridgeRejected(ValueError):
    pass


TOKEN = re.compile(r'\s+|/\*.*?\*/|//[^\n]*|"(?:\\.|[^"\\])*"|[A-Za-z_$][A-Za-z0-9_$]*|[0-9][0-9_]*L?|\|\||&&|>=|<=|!=|==|-=|\+=|--|::|[{}();.,<>!=+*/?:@\[\]&|%-]', re.S)


def tokens(source):
    # Java expands Unicode escapes before comment/token processing. Admitting
    # them into a different lexer would not preserve the compiler's program.
    if '\\u' in source:
        raise BridgeRejected('Java Unicode escapes are outside the admitted subset.')
    result, offset = [], 0
    while offset < len(source):
        match = TOKEN.match(source, offset)
        if match is None:
            raise BridgeRejected('Unregistered Java token.')
        value = match.group()
        offset = match.end()
        if not value.isspace() and not value.startswith(('/*', '//')):
            result.append(value)
    return result


class Parser:
    def __init__(self, values, cadence):
        self.values, self.i, self.cadence = values, 0, cadence

    def peek(self):
        return self.values[self.i] if self.i < len(self.values) else None

    def take(self, expected=None):
        value = self.peek()
        if value is None or expected is not None and value != expected:
            raise BridgeRejected('Unregistered charged-work statement or expression.')
        self.i += 1
        return value

    def sequence(self):
        self.take('{')
        result = []
        while self.peek() != '}':
            result.append(self.statement())
        self.take('}')
        return result

    def statement(self):
        token = self.peek()
        if token == 'if':
            self.take(); self.take('(')
            condition = self.expression(); self.take(')')
            yes = self.sequence() if self.peek() == '{' else [self.statement()]
            no = []
            if self.peek() == 'else':
                self.take()
                no = self.sequence() if self.peek() == '{' else [self.statement()]
            return ('if', condition, yes, no)
        if token == 'return':
            self.take(); self.take(';')
            return ('return',)
        if token == 'throw':
            self.take()
            if self.peek() == 'EXHAUSTED':
                self.take(); kind = 'exhausted'
            else:
                self.take('new'); self.take('IllegalArgumentException'); self.take('(')
                diagnostic = self.take()
                if not diagnostic.startswith('"'):
                    raise BridgeRejected('Only constant invalid-charge diagnostics are admitted.')
                self.take(')'); kind = 'invalid'
            self.take(';')
            return ('throw', kind)
        if token == 'State':
            for value in ('State', 'state', '=', 'STATE', '.', 'get', '(', ')', ';'):
                self.take(value)
            return ('bindState',)
        if token == 'long':
            self.take(); self.take('elapsed'); self.take('=')
            expression = self.expression(); self.take(';')
            return ('elapsed', expression)
        if token in ('sampleClock', 'checkpoint'):
            name = self.take(); self.take('(')
            if name == 'sampleClock':
                self.take('state')
            self.take(')'); self.take(';')
            return ('call', name)
        self.take('state'); self.take('.')
        field = self.take()
        if field not in ('remaining', 'exhausted', 'clockChecks'):
            raise BridgeRejected('Unregistered charged-work assignment target.')
        operation = self.take()
        if operation == '--':
            if field != 'clockChecks':
                raise BridgeRejected('Unregistered Java int decrement.')
            expression = ('javaInt', ('-', ('field', field), ('number', 1)))
        elif operation in ('=', '-=', '+='):
            expression = self.expression()
            if operation != '=':
                expression = ('javaLong', (operation[0], ('field', field), expression))
        else:
            raise BridgeRejected('Unregistered Java assignment.')
        self.take(';')
        return ('assign', field, expression)

    PRECEDENCE = {'||': 1, '&&': 2, '==': 3, '!=': 3, '<': 4, '>': 4, '<=': 4, '>=': 4, '+': 5, '-': 5}

    def expression(self, minimum=0):
        token = self.take()
        if token == '(':
            value = self.expression(); self.take(')')
        elif token == '!':
            value = ('not', self.expression(6))
        elif re.fullmatch('[0-9][0-9_]*L?', token):
            value = ('number', int(token.rstrip('L').replace('_', '')))
        elif token in ('true', 'false', 'null', 'units', 'elapsed'):
            value = (token,)
        elif token == 'CLOCK_CHECK_CALLS':
            value = ('number', self.cadence)
        elif token == 'state':
            if self.peek() == '.':
                self.take(); field = self.take()
                if field == 'clock' and self.peek() == '.':
                    for part in ('.', 'getAsLong', '(', ')'):
                        self.take(part)
                    value = ('now',)
                elif field in ('remaining', 'exhausted', 'clock', 'clockChecks', 'startedNanos', 'limitNanos'):
                    value = ('field', field)
                else:
                    raise BridgeRejected('Unregistered Java state read.')
            else:
                value = ('state',)
        else:
            raise BridgeRejected('Unregistered Java expression atom.')
        while self.peek() in self.PRECEDENCE and self.PRECEDENCE[self.peek()] >= minimum:
            operation = self.take()
            right = self.expression(self.PRECEDENCE[operation] + 1)
            value = (operation, value, right)
        return value


FIELDS = {'remaining': 'remaining', 'exhausted': 'exhausted', 'clock': 'timed',
          'clockChecks': 'checks', 'startedNanos': 'started', 'limitNanos': 'limit'}


def term(expression, *, raw_arithmetic=False):
    tag = expression[0]
    if tag == 'number':
        return str(expression[1])
    if tag in ('units', 'elapsed', 'now', 'true', 'false'):
        return tag
    if tag == 'field':
        return 's.' + FIELDS[expression[1]]
    if tag in ('javaLong', 'javaInt'):
        return tag + ' ' + term(expression[1], raw_arithmetic=True)
    if tag in ('+', '-'):
        raw = '(' + term(expression[1]) + ' ' + tag + ' ' + term(expression[2]) + ')'
        return raw if raw_arithmetic else 'javaLong ' + raw
    raise BridgeRejected('A nonnumeric/nonboolean Java value was used as a term.')


def condition(expression):
    tag = expression[0]
    if tag in ('||', '&&'):
        return '(' + condition(expression[1]) + (' ∨ ' if tag == '||' else ' ∧ ') + condition(expression[2]) + ')'
    if tag == 'not':
        return '(¬ ' + condition(expression[1]) + ')'
    if tag == 'field' and expression[1] == 'exhausted':
        return '(s.exhausted = true)'
    if tag in ('==', '!=') and expression[2] == ('null',):
        name = 'active' if expression[1] == ('state',) else 'timed' if expression[1] == ('field', 'clock') else None
        if name is None:
            raise BridgeRejected('Unregistered null comparison.')
        return '(s.' + name + (' = false)' if tag == '==' else ' = true)')
    operators = {'==': '=', '!=': '≠', '<': '<', '>': '>', '<=': '≤', '>=': '≥'}
    if tag in operators:
        return '(' + term(expression[1]) + ' ' + operators[tag] + ' ' + term(expression[2]) + ')'
    raise BridgeRejected('Unregistered Java condition.')


def emit(statements, indent=2):
    pad = ' ' * indent
    if not statements:
        return pad + '.ok s'
    first, rest = statements[0], statements[1:]
    tag = first[0]
    if tag == 'bindState':
        return emit(rest, indent)
    if tag == 'return':
        return pad + '.ok s'
    if tag == 'throw':
        return pad + '.' + first[1] + ' s'
    if tag == 'elapsed':
        return pad + 'let elapsed := ' + term(first[1]) + '\n' + emit(rest, indent)
    if tag == 'assign':
        return pad + 'let s : State := { s with ' + FIELDS[first[1]] + ' := ' + term(first[2]) + ' }\n' + emit(rest, indent)
    if tag == 'if':
        return (pad + 'if ' + condition(first[1]) + ' then\n' + emit(first[2] + rest, indent + 2)
                + '\n' + pad + 'else\n' + emit(first[3] + rest, indent + 2))
    if tag == 'call':
        name = first[1]
        return (pad + 'match ' + name + ' s units now with\n' + pad + '| .ok s =>\n' + emit(rest, indent + 2)
                + '\n' + pad + '| .invalid s => .invalid s\n' + pad + '| .exhausted s => .exhausted s')
    raise BridgeRejected('Unregistered Java statement.')


HEADERS = {'charge': ['public', 'static', 'void', 'charge', '(', 'long', 'units', ')'],
           'sampleClock': ['private', 'static', 'void', 'sampleClock', '(', 'State', 'state', ')'],
           'checkpoint': ['public', 'static', 'void', 'checkpoint', '(', ')']}


def extract(source, *, shell_source=None, bootstrap_shell=False):
    values = tokens(source)
    cadence_headers = [i for i in range(len(values)) if values[i:i + 5] == ['private', 'static', 'final', 'int', 'CLOCK_CHECK_CALLS']]
    if len(cadence_headers) != 1:
        raise BridgeRejected('Missing or ambiguous clock cadence declaration.')
    index = cadence_headers[0] + 5
    if values[index] != '=' or values[index + 2] != ';' or not re.fullmatch('[0-9][0-9_]*', values[index + 1]):
        raise BridgeRejected('Clock cadence must be an integer literal.')
    cadence = int(values[index + 1].replace('_', ''))
    spans = [(index + 1, index + 2, ['SEMANTIC_CADENCE'])]
    initializers = [i for i in range(len(values)) if values[i:i + 4] == ['private', 'int', 'clockChecks', '=']]
    if len(initializers) != 1:
        raise BridgeRejected('Missing or ambiguous initial clock countdown.')
    initial = Parser(values[initializers[0] + 4:], cadence)
    initial_checks = initial.expression()
    initial.take(';')
    start = initializers[0] + 4
    spans.append((start, start + initial.i - 1, ['SEMANTIC_INITIAL_CHECKS']))
    result = {'initialChecks': initial_checks}
    for name, header in HEADERS.items():
        offsets = [i for i in range(len(values)) if values[i:i + len(header)] == header]
        if len(offsets) != 1:
            raise BridgeRejected('Missing or ambiguous charged-work method: ' + name)
        parser = Parser(values[offsets[0] + len(header):], cadence)
        statements = parser.sequence()
        start = offsets[0] + len(header)
        spans.append((start, start + parser.i, ['{', 'SEMANTIC_' + name, '}']))
        if name in ('charge', 'checkpoint') and (not statements or statements[0] != ('bindState',)):
            raise BridgeRejected('The active ThreadLocal budget must be loaded first.')
        result[name] = statements
    shell = list(values)
    for start, end, replacement in sorted(spans, reverse=True):
        shell[start:end] = replacement
    if bootstrap_shell:
        return shell
    expected = tokens((ROOT / SHELL).read_text() if shell_source is None else shell_source)
    if shell != expected:
        raise BridgeRejected('Unregistered WorkBudget declarations, methods, bindings or initialization.')
    return result


def generate(source, *, shell_source=None):
    methods = extract(source, shell_source=shell_source)
    lines = ['import Semantics', '', '-- Generated from parsed Java branches and assignments; never edit by hand.',
             'namespace AlloyStudio.WorkBudget.Generated', 'open AlloyStudio.WorkBudget', '']
    lines += ['def initialChecks : Int := ' + term(methods['initialChecks']), '']
    for name in ('checkpoint', 'sampleClock', 'charge'):
        units = '_units' if name == 'checkpoint' else 'units'
        lines.append('def ' + name + ' (s : State) (' + units + ' now : Int) : Result :=\n' + emit(methods[name]))
        lines.append('')
    lines.append('end AlloyStudio.WorkBudget.Generated')
    return '\n'.join(lines) + '\n'



def publication_body(source):
    values = tokens(source)
    header = ['private', 'static', 'JSONObject', 'evaluateBudgeted', '(', 'JSONObject', 'request', ',',
              'long', 'fuel', ',', 'long', 'workMillis', ')']
    matches = [i for i in range(len(values)) if values[i:i + len(header)] == header]
    if len(matches) != 1:
        raise BridgeRejected('Missing or ambiguous publication wrapper.')
    start = matches[0] + len(header)
    if values[start] != '{':
        raise BridgeRejected('Malformed publication wrapper.')
    depth = 0
    for end in range(start, len(values)):
        depth += values[end] == '{'
        depth -= values[end] == '}'
        if depth == 0:
            return values[matches[0]:end + 1]
    raise BridgeRejected('Unclosed publication wrapper.')


def check_publication(source, template):
    # This is a separate structural call-site check, not a Lean proof of JSON,
    # exception unwinding, public entry points or the rest of LiveFeedback.
    if publication_body(source) != tokens(template):
        raise BridgeRejected('Publication checkpoint or wrapper control flow changed.')


def check(root=ROOT):
    root = Path(root)
    source = (root / SOURCE).read_text()
    generated = generate(source, shell_source=(root / SHELL).read_text())
    check_publication((root / FEEDBACK).read_text(), (root / PUBLICATION).read_text())
    if (root / GENERATED).read_text() != generated:
        raise BridgeRejected('Generated charged-work semantics differ from the admitted Java source.')
    return {'status': 'PASS', 'sourceSha256': hashlib.sha256(source.encode()).hexdigest(),
            'generatedSha256': hashlib.sha256(generated.encode()).hexdigest(),
            'methods': list(HEADERS),
            'scope': 'restricted Java charged-work/checkpoint bodies; explicit compiler/parser/runtime/clock trust'}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--generate', action='store_true')
    args = parser.parse_args()
    try:
        if args.generate:
            (ROOT / GENERATED).write_text(generate((ROOT / SOURCE).read_text()))
        print(json.dumps(check(), sort_keys=True))
        return 0
    except (BridgeRejected, OSError) as error:
        print(json.dumps({'status': 'REJECTED', 'reason': str(error)}))
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
