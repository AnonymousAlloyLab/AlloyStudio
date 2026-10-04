#!/usr/bin/env python3
"""Independent, fail-closed public observation contract for TRF-00.

This checks a finite response against the frozen semantic schema. It neither
invokes production projections nor proves general warm/fresh equivalence.
Dictionary order is unobservable; list order and every admitted leaf are exact.
"""
import argparse
import ast
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = 'closure/traffic-refinement/observation-spec.json'
BASELINE_PATH = 'closure/traffic-refinement/observation-baseline.json'
BASELINE_SOURCES = 'closure/traffic-refinement/observation-baseline'


def encoded(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':'),
                      allow_nan=False).encode('ascii')


def load_spec(root=ROOT):
    return json.loads((root / SPEC_PATH).read_text(encoding='utf-8'))


def _check(schema, value, path, definitions, errors):
    if '$ref' in schema:
        return _check(definitions[schema['$ref']], value, path, definitions, errors)
    def fail(code):
        errors.append({'code': code, 'path': path})
    if 'oneOf' in schema:
        matches = []
        for variant in schema['oneOf']:
            found = []
            _check(variant, value, path, definitions, found)
            if not found:
                matches.append(variant)
        if len(matches) != 1:
            fail('SCHEMA_UNION')
        return
    kind = schema['type']
    if kind == 'json':
        if type(value) is dict:
            if not all(type(key) is str for key in value):
                fail('NON_JSON_KEY')
                return
            for key, child in value.items():
                _check(schema, child, path + '.' + key, definitions, errors)
        elif type(value) is list:
            for i, child in enumerate(value):
                _check(schema, child, f'{path}[{i}]', definitions, errors)
        elif type(value) is str:
            try:
                value.encode('utf-8')
            except UnicodeError:
                fail('INVALID_UNICODE')
        elif value is not None and type(value) not in (bool, int, float):
            fail('NON_JSON_VALUE')
        elif type(value) is float and not math.isfinite(value):
            fail('NONFINITE_NUMBER')
        return
    expected = {'object': dict, 'array': list, 'string': str, 'integer': int,
                'boolean': bool, 'null': type(None)}
    if kind == 'number':
        if type(value) not in (int, float) or not math.isfinite(value):
            fail('TYPE')
            return
    elif type(value) is not expected[kind]:
        fail('TYPE')
        return
    if 'enum' in schema and not any(type(value) is type(item) and value == item for item in schema['enum']):
        fail('ENUM')
    if kind == 'object':
        fields = schema['properties']
        if not set(schema['required']) <= set(value):
            fail('MISSING_FIELD')
        if not set(value) <= set(fields):
            fail('UNKNOWN_FIELD')
        for key in sorted(set(value) & set(fields)):
            _check(fields[key], value[key], path + '.' + key, definitions, errors)
    elif kind == 'array':
        if not schema.get('minItems', 0) <= len(value) <= schema.get('maxItems', 2**63 - 1):
            fail('ARRAY_LENGTH')
        for i, child in enumerate(value):
            _check(schema['items'], child, f'{path}[{i}]', definitions, errors)
    elif kind == 'string':
        if not schema.get('minLength', 0) <= len(value) <= schema.get('maxLength', 2**63 - 1):
            fail('STRING_LENGTH')
        try:
            value.encode('utf-8')
        except UnicodeError:
            fail('INVALID_UNICODE')
        if 'pattern' in schema and re.fullmatch(schema['pattern'], value) is None:
            fail('STRING_PATTERN')
    elif kind in ('number', 'integer'):
        if not schema.get('minimum', -math.inf) <= value <= schema.get('maximum', math.inf):
            fail('NUMBER_RANGE')


def _location(location, texts, canonical, errors, path):
    ranges = location['ranges']
    status = location['status']
    def fail(code):
        errors.append({'code': code, 'path': path})
    if status == 'unavailable':
        if ranges:
            fail('UNAVAILABLE_HAS_RANGES')
        return
    if not ranges or (status == 'located') != (len(ranges) == 1):
        fail('LOCATION_CARDINALITY')
    if 'precision' not in location:
        fail('MISSING_LOCATION_PRECISION')
    if location.get('precision') == 'node' and status != 'located':
        fail('NODE_NOT_UNIQUE')
    tuples = []
    for span in ranges:
        index = span.get('formIndex', 0) if canonical else 0
        tuples.append((index, span['start'], span['end']))
        if span['start'] >= span['end']:
            fail('EMPTY_SPAN')
        if texts is None:
            continue
        if not 0 <= index < len(texts):
            fail('FORM_INDEX')
            continue
        raw = texts[index].encode('utf-16-le')
        try:
            if span['end'] * 2 > len(raw):
                raise ValueError
            selected = raw[2 * span['start']:2 * span['end']].decode('utf-16-le')
            if selected != span['text']:
                raise ValueError
            if not canonical:
                before = raw[:2 * span['start']].decode('utf-16-le')
                through = raw[:2 * span['end']].decode('utf-16-le')
                expected = (before.count('\n') + 1, len(before.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2 + 1,
                            through.count('\n') + 1, len(through.rsplit('\n', 1)[-1].encode('utf-16-le')) // 2 + 1)
                actual = tuple(span[key] for key in ('startLine', 'startColumn', 'endLine', 'endColumn'))
                if actual != expected:
                    fail('SPAN_COORDINATES')
        except (UnicodeError, ValueError):
            fail('SPAN_TEXT_OR_BOUNDARY')
    if tuples != sorted(set(tuples)):
        fail('SPAN_ORDER_OR_DUPLICATE')


def validate_observation(kind, value, context=None, *, spec=None):
    """Return code/path errors; context supplies exact input body/pool/IDs.

The checker has no oracle source input and prints no response values. A missing
context does not certify input binding; it only checks the standalone schema.
"""
    spec = load_spec() if spec is None else spec
    errors = []
    if kind not in spec['schemas']:
        return [{'code': 'UNKNOWN_KIND', 'path': '$'}]
    _check(spec['schemas'][kind], value, '$', spec['definitions'], errors)
    if errors:
        return errors
    context = {} if context is None else context
    def require(condition, code, path='$'):
        if not condition:
            errors.append({'code': code, 'path': path})
    if kind in ('feedback', 'behavior', 'explanation'):
        for field in ('exerciseId', 'revision', 'requestedMetric'):
            if field in value and field in context:
                require(type(value[field]) is type(context[field]) and value[field] == context[field],
                        'DELIVERY_MISMATCH', '$.' + field)
    if kind == 'feedback' and value.get('status') == 'ok':
        metric = 'ast' if value['metric'] == spec['metrics']['ast'] else 'canonical'
        require(value['metric'] == spec['metrics'][context.get('metric', metric)], 'METRIC_MISMATCH')
        breakdown = value['breakdown']
        require(set(breakdown) == ({'ast'} if metric == 'ast' else {'temporal', 'quantifier', 'matrix'}), 'BREAKDOWN_COMPONENTS')
        require(value['distance'] == sum(breakdown.values()) == value['trace']['cost'], 'DISTANCE_COST')
        comparison = value['comparison']
        require(comparison['poolSize'] == comparison['evaluatedCandidates'], 'PARTIAL_POOL')
        if 'poolSize' in context:
            require(comparison['poolSize'] == context['poolSize'], 'POOL_IDENTITY')
        counts = {}
        for i, operation in enumerate(value['operations']):
            counts[operation['kind']] = counts.get(operation['kind'], 0) + 1
            require(operation['component'] in breakdown, 'OPERATION_COMPONENT', f'$.operations[{i}]')
            require(operation['aggregate'] or operation['cost'] == 1, 'NONATOMIC_COST', f'$.operations[{i}]')
            if 'replacementOperator' in operation:
                require(operation['kind'] in ('replace', 'modify'), 'INSERTED_TARGET_OPERATOR', f'$.operations[{i}]')
            _location(operation['sourceLocation'], [context['body']] if 'body' in context else None,
                      False, errors, f'$.operations[{i}].sourceLocation')
            _location(operation['canonicalLocation'], value['canonicalForm'], True,
                      errors, f'$.operations[{i}].canonicalLocation')
        require(value['operationSummary'] == counts, 'OPERATION_SUMMARY')
        require(sum(op['cost'] for op in value['operations']) == value['distance'], 'OPERATION_COST')
        require([c['component'] for c in value['trace']['components']] ==
                (['ast'] if metric == 'ast' else ['temporal', 'quantifier', 'matrix']), 'COMPONENT_ORDER')
        for component in value['trace']['components']:
            require(component['distance'] == breakdown.get(component['component']), 'COMPONENT_DISTANCE')
        if metric == 'ast':
            require('astSize' in value and value['canonicalForm'] == [], 'AST_SHAPE')
        common_trace = {'cost', 'matchesDistance', 'hasAggregates', 'kind', 'certifiedOptimalScript', 'components', 'note'}
        metric_trace = {'astReplayVerified', 'astTraceAlgorithm'} if metric == 'ast' else {
            'matrixReplayVerified', 'matrixTraceAlgorithm', 'quantifierCostVerified'}
        require(set(value['trace']) == common_trace | metric_trace, 'TRACE_METRIC_SHAPE')
    elif kind == 'behavior' and value.get('status') == 'ok':
        categories = value['categories']
        require([row['id'] for row in categories] == list(spec['categoryTruth']), 'CATEGORY_ORDER')
        for category in categories:
            require([category['oracle'], category['student']] == spec['categoryTruth'][category['id']], 'CATEGORY_TRUTH')
            instances = category['instances']
            require((category['status'] == 'sat') == bool(instances), 'CATEGORY_SAT')
            require(category['enumerationComplete'] or len(instances) == 3, 'INCOMPLETE_ENUMERATION')
            for instance in instances:
                states = instance['states']
                require(-1 <= instance['loopState'] < instance['traceLength'], 'LOOP_BOUND')
                require(len(states) <= instance['traceLength'] and
                        (len(states) == instance['traceLength'] or instance['truncated']), 'TRACE_LENGTH')
                require([state['index'] for state in states] == list(range(len(states))), 'STATE_ORDER')
                for state in states:
                    for relation in state['relations']:
                        require(all(len(row) == relation['arity'] for row in relation['tuples']), 'RELATION_ARITY')
        sample = value['sampling']
        p, a, n, r, c = (sample[k] for k in ('positiveTested', 'positiveAccepted', 'negativeTested',
                                           'negativeRejected', 'semanticCounterexamples'))
        require(a <= p and r <= n, 'SAMPLE_COUNTS')
        by_id = {row['id']: row for row in categories}
        if len(by_id) == 4:
            sat = lambda name: by_id[name]['status'] == 'sat'
            require(bool(p) == (sat('both') or sat('undercoverage')) and
                    bool(n) == (sat('neither') or sat('overcoverage')), 'SAMPLE_EXISTENCE')
            require((not a or sat('both')) and (a == p or sat('undercoverage')) and
                    (not r or sat('neither')) and (r == n or sat('overcoverage')), 'SAMPLE_CATEGORY')
            correction = int(sat('undercoverage')) + int(sat('overcoverage')) if a == p and r == n else 0
            if p and n:
                # Rational half-up rounding: no dependency on binary rounding.
                denominator = p * n + c
                expected = ((2 * a * r * 1000 + denominator) // (2 * denominator)) / 1000
                require(value['scoreStatus'] == 'ok' and value['scoreReason'] == 'OK' and
                        c == correction and value['score'] == expected, 'SCORE_DEFINITION')
            else:
                require(value['scoreStatus'] == 'unavailable' and value['score'] is None and c == 0 and
                        value['scoreReason'] == ('ORACLE_POSITIVE_UNSAT' if not p else 'ORACLE_NEGATIVE_UNSAT'),
                        'UNAVAILABLE_SCORE')
    elif kind == 'explanation' and value.get('status') == 'ok':
        for field in ('operations', 'instances'):
            ids = [row['id'] for row in value[field]]
            require(len(set(ids)) == len(ids), 'DUPLICATE_EXPLANATION_ID')
            if field + 'Ids' in context:
                require(ids == context[field + 'Ids'], 'EXPLANATION_IDENTITY')
    return errors


def semantic_observation(kind, value, context=None, *, spec=None):
    """Canonical bytes retaining every semantic field, after contract checks."""
    spec = load_spec() if spec is None else spec
    errors = validate_observation(kind, value, context, spec=spec)
    if errors:
        raise ValueError(errors)
    excluded = spec['deliveryMetadata'] if kind in ('feedback', 'behavior', 'explanation') else []
    return encoded({key: item for key, item in value.items() if key not in excluded}
                   if type(value) is dict else value)


def compare_observations(kind, baseline, candidate, context=None):
    """Only exact retained semantic equality is MATCH; failures are not hits."""
    errors = {name: validate_observation(kind, value, context) for name, value in
              (('baseline', baseline), ('candidate', candidate))}
    if any(errors.values()):
        return {'status': 'BLOCKED', 'code': 'INVALID_OBSERVATION', 'errors': errors}
    same = semantic_observation(kind, baseline, context) == semantic_observation(kind, candidate, context)
    return {'status': 'PASS' if same else 'BLOCKED', 'code': 'MATCH' if same else 'SEMANTIC_MISMATCH',
            'successfulAnalysis': same and type(baseline) is dict and baseline.get('status') == 'ok'}


def baseline_inventory(root=ROOT):
    """Extract baseline fields from archived pinned bytes, never current code.

    The enclosing closure manifest includes these exact source snapshots. Git
    provenance can be checked separately when the repository is available;
    clean offline verification does not depend on a mutable Git database.
    """
    manifest = json.loads((root / BASELINE_PATH).read_text(encoding='utf-8'))
    sources = {}
    for name in manifest['sources']:
        sources[name] = (root / BASELINE_SOURCES / name).read_bytes()
    module = ast.parse(sources['server.py'].decode('utf-8'))
    fields = {}
    for node in module.body:
        if isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and target.id in ('PUBLIC_FIELDS', 'SUMMARY_FIELDS', 'METRICS'):
                    fields[target.id] = ast.literal_eval(node.value)
    method = next(n for n in module.body if isinstance(n, ast.ClassDef) and n.name == 'Portal')
    evaluate = next(n for n in method.body if isinstance(n, ast.FunctionDef) and n.name == 'evaluate')
    matches = [n for n in ast.walk(evaluate) if isinstance(n, ast.Assign) and
               any(isinstance(t, ast.Name) and t.id == 'allowed' for t in n.targets)]
    if len(matches) != 1:
        raise ValueError('Baseline projection missing or ambiguous')
    fields['feedbackAllowed'] = ast.literal_eval(matches[0].value)
    luna = ast.parse(sources['luna.py'].decode('utf-8'))
    operators = [n for n in luna.body if isinstance(n, ast.Assign) and
                 any(isinstance(t, ast.Name) and t.id == 'REPLACEMENT_OPERATORS' for t in n.targets)]
    if len(operators) != 1 or not isinstance(operators[0].value, ast.Call):
        raise ValueError('Baseline operator inventory missing or ambiguous')
    fields['REPLACEMENT_OPERATORS'] = ast.literal_eval(operators[0].value.args[0])
    return {'schemaVersion': 1, 'commit': manifest['commit'],
            'sources': {name: hashlib.sha256(value).hexdigest() for name, value in sources.items()},
            'inventory': json.loads(encoded(fields))}


def check_baseline(root=ROOT):
    expected = json.loads((root / BASELINE_PATH).read_text(encoding='utf-8'))
    actual = baseline_inventory(root)
    if actual != expected:
        raise ValueError('Pinned baseline source binding changed')
    spec = load_spec(root)
    inv = actual['inventory']
    if inv['METRICS'] != spec['metrics'] or inv['feedbackAllowed'] != spec['baselineFeedbackFields']:
        raise ValueError('Observation contract omitted baseline projection fields')
    if inv['REPLACEMENT_OPERATORS'] != spec['definitions']['operation']['properties']['replacementOperator']['enum']:
        raise ValueError('Observation contract changed approved operator vocabulary')
    for constant, shape in (('PUBLIC_FIELDS', 'exercise'), ('SUMMARY_FIELDS', 'exerciseSummary')):
        if set(inv[constant]) != set(spec['definitions'][shape]['properties']):
            raise ValueError('Observation contract omitted catalogue fields')
    return actual


def check_baseline_git(root=ROOT):
    """Optional local provenance check; never fetches or accesses the network."""
    manifest = check_baseline(root)
    for name, expected in manifest['sources'].items():
        result = subprocess.run(['git', 'show', manifest['commit'] + ':' + name], cwd=root,
                                check=True, capture_output=True, timeout=20)
        if hashlib.sha256(result.stdout).hexdigest() != expected:
            raise ValueError('Archived source differs from pinned Git object')
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-baseline', action='store_true')
    args = parser.parse_args()
    if not args.check_baseline:
        parser.error('Use --check-baseline; programmatic validators never read private inputs.')
    inventory = check_baseline()
    print(json.dumps({'status': 'PASS', 'baseline': inventory['commit'],
                      'sourceCount': len(inventory['sources']),
                      'inventorySha256': hashlib.sha256(encoded(inventory)).hexdigest()}))


if __name__ == '__main__':
    main()
