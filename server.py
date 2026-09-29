#!/usr/bin/env python3
"""Local Alloy practice portal; private exercise records never become HTTP files."""
import argparse
from collections import OrderedDict
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import unquote, urlsplit
from luna import Explainer
from runtime_dependencies import check_runtime, runtime_classpath
from exercise_store import load_store, StoreError, parse_json
from admin_auth import AuthManager, AuthError
from admin_service import AdminService, AdminError

ROOT = Path(__file__).resolve().parent
PUBLIC_FIELDS = ('id', 'title', 'group', 'predicate', 'description', 'environmentBefore',
                 'environmentAfter', 'predicateHeader', 'starter', 'source')
SUMMARY_FIELDS = ('id', 'title', 'group', 'predicate', 'description')
MAX_BODY_BYTES = 8192
MAX_REQUEST_BYTES = 16384
METRICS = {'canonical': 'acgn-fast-rewrite-canonical-distance',
           'ast': 'acgn-raw-ast-zhang-shasha-distance'}
STATIC = {'/': ('index.html', 'text/html; charset=utf-8'),
          '/index.html': ('index.html', 'text/html; charset=utf-8'),
          '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
          '/styles.css': ('styles.css', 'text/css; charset=utf-8')}
STATIC.update({'/dashboard/' + name: ('dashboard/' + name, mime) for name, mime in (
    ('index.html', 'text/html; charset=utf-8'), ('app.js', 'text/javascript; charset=utf-8'),
    ('styles.css', 'text/css; charset=utf-8'), ('data.json', 'application/json; charset=utf-8'))})
STATIC['/dashboard/'] = STATIC['/dashboard/index.html']
STATIC.update({'/admin/' + name: ('admin/' + name, mime) for name, mime in (
    ('index.html', 'text/html; charset=utf-8'), ('app.js', 'text/javascript; charset=utf-8'),
    ('styles.css', 'text/css; charset=utf-8'))})
STATIC['/admin/'] = STATIC['/admin/index.html']

def project(record, fields):
    return {key: record[key] for key in fields}


def model(record, body):
    return (record['environmentBefore'] + record['predicateHeader'] + '{\n' + body
            + '\n}' + record['environmentAfter'])


def behavior_token(exercise_id, body, evidence):
    """Bind educational annotations to the exact public witness snapshot."""
    encoded = json.dumps([exercise_id, body, evidence], sort_keys=True,
                         separators=(',', ':'), ensure_ascii=True).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


def project_behavior(raw):
    """Validate bounded behavior evidence and discard all worker metadata.

    No source, command, XML, skolems or unrestricted diagnostic text crosses
    this boundary. Only signatures/relations serialized by the engine survive.
    """
    def require(condition):
        if not condition:
            raise ValueError('Invalid behavioral evidence')

    def integer(value, low, high):
        require(type(value) is int and low <= value <= high)
        return value

    def boolean(value):
        require(type(value) is bool)
        return value

    def sequence(value, maximum):
        require(isinstance(value, list) and len(value) <= maximum)
        return value

    def text(value):
        require(isinstance(value, str) and 0 < len(value) <= 256 and '\x00' not in value)
        value.encode('utf-8')  # Reject lone surrogates before HTTP serialization.
        return value

    def object_(value):
        require(isinstance(value, dict))
        return value

    object_(raw)
    require(raw.get('status') == 'ok' and raw.get('metric') == 'acgn-reward')
    expected_scope = {'overall': 3, 'bitwidth': 3, 'maxSequence': 3, 'poolSize': 100,
                      'minTrace': 1, 'maxTrace': 10, 'moduleFacts': True}
    scope = object_(raw.get('scope'))
    for key, value in expected_scope.items():
        require(type(scope.get(key)) is type(value) and scope[key] == value)
    sample = object_(raw.get('sampling'))
    sampling = {key: integer(sample.get(key), 0, 100) for key in
                ('positiveTested', 'positiveAccepted', 'negativeTested', 'negativeRejected')}
    sampling['semanticCounterexamples'] = integer(sample.get('semanticCounterexamples'), 0, 2)
    require(sampling['positiveAccepted'] <= sampling['positiveTested']
            and sampling['negativeRejected'] <= sampling['negativeTested'])
    category_types = {'both': (True, True), 'undercoverage': (True, False),
                      'overcoverage': (False, True), 'neither': (False, False)}
    categories, seen = [], set()
    for item in sequence(raw.get('categories'), 4):
        object_(item)
        name = item.get('id')
        require(isinstance(name, str) and name in category_types and name not in seen)
        seen.add(name)
        truth = (boolean(item.get('oracle')), boolean(item.get('student')))
        require(truth == category_types[name] and item.get('status') in ('sat', 'unsat'))
        complete = boolean(item.get('enumerationComplete'))
        instances = []
        for instance in sequence(item.get('instances'), 3):
            object_(instance)
            length = integer(instance.get('traceLength'), 1, 10)
            loop = integer(instance.get('loopState'), -1, length - 1)
            truncated = boolean(instance.get('truncated', False))
            strings_anonymized = boolean(instance.get('stringsAnonymized', False))
            states = []
            for state in sequence(instance.get('states'), 10):
                object_(state)
                require(state.get('index') == len(states) and type(state.get('index')) is int)
                signatures, relations = [], []
                for signature in sequence(state.get('signatures'), 128):
                    object_(signature)
                    signatures.append({'label': text(signature.get('label')),
                                       'atoms': [text(atom) for atom in sequence(signature.get('atoms'), 128)]})
                for relation in sequence(state.get('relations'), 128):
                    object_(relation)
                    arity = integer(relation.get('arity'), 1, 8)
                    tuples = []
                    for row in sequence(relation.get('tuples'), 512):
                        require(isinstance(row, list) and len(row) == arity)
                        tuples.append([text(atom) for atom in row])
                    relations.append({'label': text(relation.get('label')), 'arity': arity, 'tuples': tuples})
                states.append({'index': len(states), 'signatures': signatures, 'relations': relations})
            require(0 < len(states) <= length and (len(states) == length or truncated))
            instances.append({'traceLength': length, 'loopState': loop, 'truncated': truncated,
                              'stringsAnonymized': strings_anonymized, 'states': states})
        require((item['status'] == 'sat') == bool(instances))
        require(item['status'] != 'unsat' or complete)
        # With fewer than the three requested witnesses, enumeration must have
        # reached UNSAT, not merely stopped early without accounting for it.
        require(complete or len(instances) == 3)
        categories.append({'id': name, 'oracle': truth[0], 'student': truth[1],
                           'status': item['status'], 'enumerationComplete': complete, 'instances': instances})
    require(seen == set(category_types))
    by_name = {item['id']: item for item in categories}
    positive = sampling['positiveTested']
    negative = sampling['negativeTested']
    accepted = sampling['positiveAccepted']
    rejected = sampling['negativeRejected']
    correction = sampling['semanticCounterexamples']
    require(bool(positive) == any(by_name[name]['status'] == 'sat' for name in ('both', 'undercoverage')))
    require(bool(negative) == any(by_name[name]['status'] == 'sat' for name in ('overcoverage', 'neither')))
    require(not accepted or by_name['both']['status'] == 'sat')
    require(accepted == positive or by_name['undercoverage']['status'] == 'sat')
    require(not rejected or by_name['neither']['status'] == 'sat')
    require(rejected == negative or by_name['overcoverage']['status'] == 'sat')
    if positive and negative:
        require(raw.get('scoreStatus') == 'ok' and raw.get('scoreReason') == 'OK')
        score = raw.get('score')
        require(type(score) in (int, float) and math.isfinite(score) and 0 <= score <= 1)
        perfect_sample = accepted == positive and rejected == negative
        expected_correction = sum(by_name[name]['status'] == 'sat' for name in ('undercoverage', 'overcoverage')) if perfect_sample else 0
        require(correction == expected_correction)
        expected = accepted * rejected / (positive * negative + correction)
        require(abs(score - math.floor(expected * 1000 + 0.5) / 1000) < 1e-9)
    else:
        require(raw.get('scoreStatus') == 'unavailable' and raw.get('score') is None and correction == 0)
        require(raw.get('scoreReason') == ('ORACLE_POSITIVE_UNSAT' if not positive else 'ORACLE_NEGATIVE_UNSAT'))
        score = None
    return {'status': 'ok', 'metric': 'acgn-reward', 'score': score,
            'scoreStatus': raw['scoreStatus'], 'scoreReason': raw['scoreReason'],
            'scope': expected_scope, 'sampling': sampling,
            'categories': [by_name[name] for name in category_types]}


def compact_canonical_text(text):
    """Collapse display whitespace outside literals and map UTF-16 boundaries."""
    result, offsets = [], {0: 0}
    index = old_offset = new_offset = 0
    quoted = escaped = False
    while index < len(text):
        character = text[index]
        if not quoted and character.isspace():
            end = index + 1
            while end < len(text) and text[end].isspace():
                end += 1
            if result and end < len(text):
                result.append(' ')
                new_offset += 1
            for white in text[index:end]:
                old_offset += 2 if ord(white) > 0xffff else 1
                offsets[old_offset] = new_offset
            index = end
            continue
        result.append(character)
        width = 2 if ord(character) > 0xffff else 1
        old_offset += width
        new_offset += width
        offsets[old_offset] = new_offset
        if quoted:
            if escaped:
                escaped = False
            elif character == '\\':
                escaped = True
            elif character == '"':
                quoted = False
        elif character == '"':
            quoted = True
        index += 1
    return ''.join(result), offsets


def project_canonical_locations(result):
    """Compact learner canonical forms and rebind every highlight to their text."""
    raw_forms = result.get('canonicalForm')
    if isinstance(raw_forms, str):
        raw_forms = [raw_forms]
    forms, mappings, boundaries = [], [], []
    if isinstance(raw_forms, list) and all(isinstance(form, str) for form in raw_forms):
        for form in raw_forms:
            compact, mapping = compact_canonical_text(form)
            forms.append(compact)
            mappings.append(mapping)
            points, offset = {0: 0}, 0
            for index, character in enumerate(compact):
                offset += 2 if ord(character) > 0xffff else 1
                points[offset] = index + 1
            boundaries.append(points)
        result['canonicalForm'] = forms
    operations = result.get('operations')
    if not isinstance(operations, list):
        return
    for operation in operations:
        if not isinstance(operation, dict):
            continue
        raw = operation.get('canonicalLocation')
        operation['canonicalLocation'] = {
            'status': 'unavailable', 'coordinateSystem': 'canonical', 'offsetEncoding': 'utf-16',
            'ranges': [], 'reason': 'There is no matching part of your simplified predicate to highlight.'}
        if (not isinstance(raw, dict) or raw.get('coordinateSystem') != 'canonical'
                or raw.get('offsetEncoding') != 'utf-16'
                or raw.get('status') not in ('located', 'ambiguous')
                or raw.get('precision') not in ('node', 'related', 'form')
                or raw.get('precision') == 'node' and raw.get('status') != 'located'
                or not isinstance(raw.get('ranges'), list) or not 1 <= len(raw['ranges']) <= 16
                or (raw['status'] == 'located') != (len(raw['ranges']) == 1)):
            continue
        projected, seen = [], set()
        for span in raw['ranges']:
            if (not isinstance(span, dict) or any(type(span.get(key)) is not int
                                                for key in ('formIndex', 'start', 'end'))):
                break
            form_index, start, end = span['formIndex'], span['start'], span['end']
            if (not 0 <= form_index < len(forms) or start >= end
                    or start not in mappings[form_index] or end not in mappings[form_index]):
                break
            start, end = mappings[form_index][start], mappings[form_index][end]
            if start >= end or (form_index, start, end) in seen:
                break
            seen.add((form_index, start, end))
            a, b = boundaries[form_index][start], boundaries[form_index][end]
            projected.append({'formIndex': form_index, 'start': start, 'end': end,
                              'text': forms[form_index][a:b]})
        else:
            reason = ('This is the expression selected by this edit in your simplified predicate.'
                      if raw['precision'] == 'node' else
                      'The whole simplified predicate is shown because a smaller matching part could not be found.'
                      if raw['precision'] == 'form' else
                      'Several parts of your simplified predicate match this hint. Inspect each highlighted possibility.'
                      if len(projected) > 1 else 'This part of your simplified predicate relates to the hint.')
            operation['canonicalLocation'] = {
                'status': raw['status'], 'coordinateSystem': 'canonical', 'offsetEncoding': 'utf-16',
                'precision': raw['precision'], 'reason': reason,
                'ranges': sorted(projected, key=lambda item: (item['formIndex'], item['start'], item['end']))}


def project_source_locations(operations, record, body):
    """Bind learner-only UTF-16 spans to this exact editable body.

    The engine selects a structural occurrence when available, or relates the
    hint to parser expressions. Neither is a complete source repair. Never
    trust its snippets, coordinates, or free-form reasons;
    derive the public text/coordinates here and reject an incomplete mapping.
    """
    prefix = record['environmentBefore'] + record['predicateHeader'] + '{\n'
    origin = len(prefix.encode('utf-16-le')) // 2
    first_line = prefix.count('\n') + 1
    boundaries = {0: (0, 1, 1)}
    offset, line, column = 0, 1, 1
    for index, character in enumerate(body):
        width = 2 if ord(character) > 0xffff else 1
        offset += width
        if character == '\n':
            line, column = line + 1, 1
        else:
            column += width
        boundaries[offset] = (index + 1, line, column)

    def unavailable():
        return {'status': 'unavailable', 'coordinateSystem': 'body',
                'offsetEncoding': 'utf-16', 'ranges': [],
                'reason': 'This hint could not be matched reliably to a location in your code.'}

    if not isinstance(operations, list):
        return
    for operation in operations:
        if not isinstance(operation, dict):
            continue
        raw = operation.get('sourceLocation')
        operation.pop('sourceSpan', None)
        operation['sourceLocation'] = unavailable()
        if (not isinstance(raw, dict) or raw.get('coordinateSystem') != 'module'
                or raw.get('offsetEncoding') != 'utf-16'
                or raw.get('status') not in ('located', 'ambiguous')
                or raw.get('precision') not in ('node', 'exact', 'related', 'predicate')
                or raw.get('precision') == 'node' and raw.get('status') != 'located'
                or not isinstance(raw.get('ranges'), list) or not 1 <= len(raw['ranges']) <= 16
                or (raw['status'] == 'located') != (len(raw['ranges']) == 1)):
            continue
        projected, seen = [], set()
        for span in raw['ranges']:
            if (not isinstance(span, dict) or type(span.get('start')) is not int
                    or type(span.get('end')) is not int):
                break
            start, end = span['start'] - origin, span['end'] - origin
            if start not in boundaries or end not in boundaries or start >= end or (start, end) in seen:
                break
            a, start_line, start_column = boundaries[start]
            b, end_line, end_column = boundaries[end]
            seen.add((start, end))
            projected.append({'start': start, 'end': end, 'text': body[a:b],
                              'startLine': start_line, 'startColumn': start_column,
                              'endLine': end_line, 'endColumn': end_column,
                              'moduleLine': first_line + start_line - 1,
                              'moduleColumn': start_column})
        else:
            # A selected structural occurrence is distinct from a complete
            # repair. Legacy exact claims still only establish related context.
            precision = raw['precision'] if raw['precision'] in ('node', 'predicate') else 'related'
            reason = ('This is the expression selected by this edit. Review it before changing your code.'
                      if precision == 'node' else
                      'Review the whole predicate; a smaller matching part could not be found.'
                      if precision == 'predicate' else
                      'Several parts of your code match this hint. Choose a highlight to inspect.'
                      if len(projected) > 1 else
                      'This highlight shows a related part of your code to inspect. The change you need may look different from the hint.')
            operation['sourceLocation'] = {'status': raw['status'], 'precision': precision,
                                           'coordinateSystem': 'body', 'offsetEncoding': 'utf-16',
                                           'ranges': sorted(projected, key=lambda item: (item['start'], item['end'])),
                                           'reason': reason}


def normalize_origin(value):
    """Normalize an explicit browser origin without trusting proxy headers."""
    try:
        if not isinstance(value, str) or re.search(r'[\s\\]', value):
            raise ValueError
        parts = urlsplit(value)
        host, port = parts.hostname, parts.port
        if (parts.scheme not in ('http', 'https') or not host
                or parts.username is not None or parts.password is not None
                or parts.path not in ('', '/') or parts.query or parts.fragment
                or '?' in value or '#' in value
                or parts.netloc.endswith(':')
                or not re.fullmatch(r'[A-Za-z0-9.:\[\]-]+', parts.netloc)):
            raise ValueError
        authority = '[' + host + ']' if ':' in host else host
        if port is not None and port != (443 if parts.scheme == 'https' else 80):
            authority += ':' + str(port)
        return parts.scheme + '://' + authority
    except (TypeError, ValueError):
        raise ValueError('Use an http(s) origin with no credentials, path, query, or fragment.') from None


def validate_body(body):
    """Permit nested expressions, but never let a body escape its predicate."""
    if not isinstance(body, str):
        return 'Enter a predicate body of at most 8 KiB.'
    try:
        if len(body.encode('utf-8')) > MAX_BODY_BYTES:
            return 'Enter a predicate body of at most 8 KiB.'
    except UnicodeError:
        return 'The predicate contains an invalid Unicode character.'
    if not body.strip():
        return 'Enter a predicate body to receive feedback.'
    if '\x00' in body:
        return 'The predicate contains an invalid character.'
    depth, i, mode = 0, 0, 'code'
    while i < len(body):
        c, pair = body[i], body[i:i+2]
        if mode == 'line':
            if c in '\r\n': mode = 'code'
        elif mode == 'block':
            if pair == '*/': mode = 'code'; i += 1
        elif mode == 'string':
            if c == '\\': i += 1
            elif c == '"': mode = 'code'
        elif pair in ('//', '--'):
            mode = 'line'; i += 1
        elif pair == '/*':
            mode = 'block'; i += 1
        elif c == '"': mode = 'string'
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth < 0: return 'Edit only the predicate body; its outer braces are fixed.'
        i += 1
    if depth or mode in ('block', 'string'):
        return 'Close the braces, comment, or string in your predicate body.'
    return None


class Portal(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, *, root=ROOT, timeout=12, workers=4, java='java', public_origins=()):
        self.root = Path(root)
        snapshot = load_store(self.root)
        self.snapshot = snapshot
        self.admin_auth = AuthManager(self.root)
        self.admin = AdminService(self, self.admin_auth)
        self.timeout = timeout
        self.java = str(java)
        self.public_origins = frozenset(normalize_origin(origin) for origin in public_origins)
        self.explainer = Explainer()
        self.slots = threading.BoundedSemaphore(workers)
        self.cache, self.cache_lock = OrderedDict(), threading.Lock()
        # SAT enumeration has its own small lane, so a slow behavior request
        # cannot occupy the canonical feedback workers.
        self.behavior_slots = threading.BoundedSemaphore(1)
        self.behavior_cache = OrderedDict()
        super().__init__(address, Handler)

    @property
    def exercises(self):
        return self.snapshot.exercises

    @property
    def correct_pools(self):
        return self.snapshot.correct_pools

    def evaluate(self, record, body, metric='canonical'):
        if not isinstance(metric, str) or metric not in METRICS:
            return {'status': 'invalid', 'diagnostics': [{'message': 'Choose Canonical form or Raw syntax tree.'}]}
        key = (record['id'], hashlib.sha256(body.encode()).hexdigest(), metric)
        with self.cache_lock:
            if key in self.cache:
                self.cache.move_to_end(key)
                return self.cache[key]
        if not self.slots.acquire(blocking=False):
            return {'status': 'busy', 'diagnostics': [{'message': 'All analysis workers are busy. Try again shortly.'}]}
        try:
            payload = {'studentSource': model(record, body),
                       'referenceBodies': self.correct_pools[record['id']],
                       'referencePrefix': record['environmentBefore'] + record['predicateHeader'] + '{\n',
                       'referenceSuffix': '\n}' + record['environmentAfter'],
                       'predicate': record['predicate'], 'metric': metric}
            command = [self.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
                       runtime_classpath(self.root),
                       'live.LiveFeedback']
            try:
                completed = subprocess.run(command, input=json.dumps(payload), text=True, encoding='utf-8',
                                           stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                           cwd=self.root, timeout=self.timeout, check=False)
                if completed.returncode != 0:
                    return {'status': 'error', 'diagnostics': [{'message': 'Analysis could not complete. Try a smaller predicate.'}]}
                raw = json.loads(completed.stdout)
                if not isinstance(raw, dict):
                    return {'status': 'error', 'diagnostics': [{'message': 'The analysis service returned an invalid response.'}]}
                expected_comparison = {'strategy': 'nearest-known-correct',
                                       'poolSize': len(self.correct_pools[record['id']]),
                                       'evaluatedCandidates': len(self.correct_pools[record['id']]),
                                       'complete': True}
                if raw.get('status') == 'ok' and raw.get('comparison') != expected_comparison:
                    return {'status': 'error', 'diagnostics': [{'message': 'The complete correct-predicate pool could not be compared.'}]}
                if raw.get('status') == 'ok' and raw.get('metric', METRICS['canonical']) != METRICS[metric]:
                    return {'status': 'error', 'diagnostics': [{'message': 'The analysis returned a different distance metric. Please retry.'}]}
                # The adapter emits only public data. Project again at the HTTP boundary.
                allowed = ('status', 'metric', 'distance', 'breakdown', 'canonicalForm',
                           'operations', 'operationSummary', 'trace', 'diagnostics', 'comparison', 'astSize')
                result = {k: raw[k] for k in allowed if k in raw}
                project_canonical_locations(result)
                project_source_locations(result.get('operations'), record, body)
                # Parser coordinates use the complete model; expose body coordinates.
                for diagnostic in result.get('diagnostics', []):
                    if 'line' in diagnostic:
                        first = (record['environmentBefore'] + record['predicateHeader'] + '{\n').count('\n') + 1
                        module_line = diagnostic['line']
                        diagnostic['moduleLine'] = module_line
                        if first <= module_line <= first + body.count('\n'):
                            diagnostic['line'] = module_line - first + 1
                        else:
                            diagnostic.pop('line', None)
                            diagnostic.pop('column', None)
                if result.get('status') == 'ok':
                    with self.cache_lock:
                        self.cache[key] = result
                        if len(self.cache) > 128: self.cache.popitem(last=False)
                return result
            except subprocess.TimeoutExpired:
                return {'status': 'timeout', 'diagnostics': [{'message': 'Analysis exceeded the time limit. Simplify the predicate and retry.'}]}
            except (OSError, ValueError):
                return {'status': 'error', 'diagnostics': [{'message': 'The analysis service is unavailable.'}]}
        finally:
            self.slots.release()

    def evaluate_behavior(self, record, body):
        key = (record['id'], hashlib.sha256(body.encode()).hexdigest())
        with self.cache_lock:
            if key in self.behavior_cache:
                self.behavior_cache.move_to_end(key)
                return self.behavior_cache[key]
        if not self.behavior_slots.acquire(blocking=False):
            return {'status': 'busy', 'message': 'Behavioral analysis is busy. Try again shortly.'}
        try:
            payload = {'studentSource': model(record, body), 'studentBody': body,
                       'oracleSource': model(record, record['oracleBody']), 'predicate': record['predicate']}
            command = [self.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
                       runtime_classpath(self.root), 'live.BehaviorFeedback']
            completed = subprocess.run(command, input=json.dumps(payload), text=True, encoding='utf-8',
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=self.root,
                                       timeout=max(30, self.timeout), check=False)
            if completed.returncode or len(completed.stdout.encode('utf-8')) > 4 * 1024 * 1024:
                return {'status': 'error', 'message': 'Behavioral analysis could not complete.'}
            raw = json.loads(completed.stdout)
            if isinstance(raw, dict) and raw.get('status') in ('invalid', 'unsupported', 'error', 'invalid_request'):
                status = 'invalid' if raw['status'] in ('invalid', 'invalid_request') else raw['status']
                message = 'The solver could not evaluate this predicate in the fixed model. Canonical feedback remains available.'
                diagnostics = raw.get('diagnostics')
                code = diagnostics[0].get('code') if (isinstance(diagnostics, list) and diagnostics
                        and isinstance(diagnostics[0], dict)) else None
                if status == 'unsupported' and code == 'STUDENT_STRING_UNIVERSE':
                    message = 'This draft introduces a string literal outside the oracle sampling universe. Behavioral analysis is unavailable for this form.'
                elif status == 'unsupported' and code == 'RECURSIVE_OR_CONTEXT_DEPENDENCY':
                    message = 'Behavioral analysis does not support recursion or model facts and shared helpers that depend on the edited predicate.'
                return {'status': status, 'message': message}
            result = project_behavior(raw)
            with self.cache_lock:
                self.behavior_cache[key] = result
                if len(self.behavior_cache) > 32:
                    self.behavior_cache.popitem(last=False)
            return result
        except subprocess.TimeoutExpired:
            return {'status': 'timeout', 'message': 'Behavioral analysis exceeded its time limit. Canonical feedback remains available.'}
        except (OSError, ValueError, TypeError, KeyError, OverflowError, RecursionError):
            return {'status': 'error', 'message': 'Behavioral analysis returned no usable evidence. Try again.'}
        finally:
            self.behavior_slots.release()


class Handler(BaseHTTPRequestHandler):
    server_version = 'AlloyPractice/1.0'

    def log_message(self, fmt, *args):
        # No learner input, oracle, model paths, or query strings in access logs.
        pass

    def send_error(self, code, message=None, explain=None):
        try:
            admin = unquote(urlsplit(getattr(self,'path','')).path).startswith('/api/admin/')
        except ValueError:
            admin = False
        if admin:
            self.close_connection = True
            status = 405 if code == 501 else code
            return self.reply(status,{'error': 'Method not allowed.' if status == 405 else 'Invalid administrator request.'})
        return super().send_error(code,message,explain)

    def reply(self, status, data, content_type='application/json; charset=utf-8', *, cookies=()):
        if not isinstance(data, bytes): data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        if status == 405:
            self.send_header('Allow', 'GET, POST')
        admin = unquote(urlsplit(self.path).path).startswith('/api/admin/')
        self.send_header('Cache-Control', 'no-store, private' if admin else 'no-store')
        for cookie in cookies:
            self.send_header('Set-Cookie', cookie)
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        connect = "'self' https://api.github.com" if urlsplit(self.path).path.startswith('/dashboard/') else "'self'"
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src " + connect + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()
        try:
            if self.command != 'HEAD':
                self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError, TimeoutError):
            pass

    def admin_headers(self, *, mutation=False):
        self.connection.settimeout(5)
        # Reject ambiguous framing before authentication or a body read.
        for name in ('Origin','Host','Content-Length','Content-Type','X-CSRF-Token','Sec-Fetch-Site'):
            if len(self.headers.get_all(name, [])) > 1:
                raise AdminError(400, 'Duplicate administrator request header.')
        if self.headers.get_all('Transfer-Encoding', []):
            raise AdminError(400, 'Transfer encoding is not supported.')
        if mutation and self.headers.get_content_type() != 'application/json':
            raise AdminError(415, 'Send application/json.')
        length = self.headers.get('Content-Length', '0')
        if re.fullmatch(r'0|[1-9][0-9]{0,7}', length) is None:
            raise AdminError(400, 'Invalid content length.')
        if not mutation and int(length):
            raise AdminError(400, 'This request must not have a body.')
        return int(length)

    def admin_body(self, length, limit):
        if not 0 < length <= limit:
            raise AdminError(413, 'Administrator request exceeds its byte limit.')
        deadline = time.monotonic() + 5
        remaining, chunks = length, []
        try:
            while remaining:
                budget = deadline - time.monotonic()
                if budget <= 0:
                    raise TimeoutError()
                self.connection.settimeout(budget)
                chunk = self.rfile.read1(min(65536, remaining))
                if not chunk:
                    raise ValueError()
                chunks.append(chunk)
                remaining -= len(chunk)
            if time.monotonic() >= deadline:
                raise TimeoutError()
            data = parse_json(b''.join(chunks))
            if type(data) is not dict:
                raise ValueError()
            return data
        except (OSError, ValueError, RecursionError):
            raise AdminError(400, 'Invalid, incomplete or timed-out JSON request.') from None

    def admin_failure(self, error):
        self.close_connection = True  # a rejected, unread body is never reused
        if isinstance(error, (AuthError, AdminError)):
            return self.reply(error.status, {'error': error.message})
        if isinstance(error, StoreError):
            return self.reply(400, {'error': str(error)[:300]})
        return self.reply(503, {'error': 'Administration could not complete this request.'})

    def admin_GET(self, path):
        try:
            self.admin_headers()
            auth = self.server.admin_auth
            if path == '/api/admin/session':
                state, cookies = auth.bootstrap(self.headers,self.client_address)
                return self.reply(200,state,cookies=cookies)
            principal = auth.authorize(self.headers,self.client_address,mutation=False)
            match = re.fullmatch(r'/api/admin/drafts/([A-Za-z0-9_-]{43})',path)
            if not match:
                raise AdminError(404,'Not found.')
            result = self.server.admin.view(principal,match.group(1))
            with auth.guard(principal):
                auth.touch(principal)
            return self.reply(200,result)
        except Exception as error:
            return self.admin_failure(error)

    def admin_POST(self, path):
        try:
            length = self.admin_headers(mutation=True)
            auth = self.server.admin_auth
            login = path == '/api/admin/login'
            principal = auth.authorize(self.headers,self.client_address,preauth=login)
            limit = 2 * 1048576 if path == '/api/admin/prepare' else 131072
            if login:
                limit = 8192
            data = self.admin_body(length,limit)
            if login:
                if set(data) != {'password'}:
                    raise AdminError(400,'Provide only the password.')
                result, cookies = auth.login(principal,data['password'])
                return self.reply(200,result,cookies=cookies)
            if path == '/api/admin/logout':
                if data:
                    raise AdminError(400,'Unexpected sign-out fields.')
                cookies = auth.logout(principal)
                return self.reply(200,{'status':'signed_out'},cookies=cookies)
            if path == '/api/admin/prepare':
                result = self.server.admin.prepare(principal,data)
                status = 202
            elif path == '/api/admin/suggest':
                if set(data) != {'id','revision','questionSeed'}:
                    raise AdminError(400,'Provide draft ID, revision and questionSeed.')
                result = self.server.admin.request_suggestion(principal,data['id'],data['revision'],data['questionSeed'])
                status = 202
            elif path == '/api/admin/discard':
                if set(data) != {'id'}:
                    raise AdminError(400,'Provide a draft ID.')
                result = self.server.admin.discard(principal,data['id'])
                status = 200
            elif path == '/api/admin/commit':
                if set(data) != {'id','revision','exercises'}:
                    raise AdminError(400,'Provide draft ID, revision and reviewed exercises.')
                result = self.server.admin.commit(principal,data['id'],data['revision'],data['exercises'])
                # The final transaction guard is the commit authorization point.
                # A later logout must not turn a successful commit into a failure.
                return self.reply(200,result)
            else:
                raise AdminError(404,'Not found.')
            with auth.guard(principal):
                auth.touch(principal)
            return self.reply(status,result)
        except Exception as error:
            return self.admin_failure(error)

    def do_GET(self):
        raw_path = urlsplit(self.path).path
        path = unquote(raw_path)
        if path.startswith('/api/admin/') and path != raw_path:
            return self.reply(404, {'error': 'Not found.'})
        if path.startswith('/api/admin/'):
            return self.admin_GET(path)
        if path in ('/dashboard', '/admin'):
            self.send_response(308)
            self.send_header('Location', path[1:] + '/')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        if path == '/api/health':
            return self.reply(200, {'status': 'ok', 'exercises': len(self.server.exercises),
                                    'engine': 'ACGN / CanDis Fast Rewrite IR'})
        if path == '/api/exercises':
            return self.reply(200, {'exercises': [project(e, SUMMARY_FIELDS) for e in self.server.exercises.values()]})
        if path.startswith('/api/exercises/'):
            record = self.server.exercises.get(path[len('/api/exercises/'):])
            if record: return self.reply(200, project(record, PUBLIC_FIELDS))
        if path in STATIC:
            name, mime = STATIC[path]
            try: return self.reply(200, (self.server.root / 'web' / name).read_bytes(), mime)
            except FileNotFoundError: pass
        self.reply(404, {'error': 'Not found.'})

    def do_POST(self):
        path = urlsplit(self.path).path
        if path.startswith('/api/admin/'):
            return self.admin_POST(path)
        if path not in ('/api/feedback', '/api/explain', '/api/behavior'):
            return self.reply(404, {'error': 'Not found.'})
        origin = self.headers.get('Origin')
        if origin:
            try:
                allowed = self.server.public_origins or frozenset(
                    normalize_origin(scheme + self.headers.get('Host', '')) for scheme in ('http://', 'https://'))
                accepted = normalize_origin(origin) in allowed
            except ValueError:
                accepted = False
            if not accepted:
                return self.reply(403, {'error': 'Cross-origin requests are not allowed.'})
        if self.headers.get_content_type() != 'application/json':
            return self.reply(415, {'error': 'Send application/json.'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= MAX_REQUEST_BYTES: return self.reply(413, {'error': 'Request must be at most 16 KiB.'})
            self.connection.settimeout(5)
            data = json.loads(self.rfile.read(length))
        except (ValueError, OSError, RecursionError):
            return self.reply(400, {'error': 'Invalid JSON request.'})
        expected = {'exerciseId', 'body', 'revision'}
        optional = ({'metric', 'behaviorToken'} if path == '/api/explain'
                    else {'metric'} if path == '/api/feedback' else set())
        fields = set(data) if isinstance(data, dict) else set()
        if (not isinstance(data, dict)
                or not expected <= fields or not fields <= expected | optional
                or not isinstance(data['exerciseId'], str)
                or type(data['revision']) is not int or not 0 <= data['revision'] <= 2**53 - 1
                or ('metric' in data and (not isinstance(data['metric'], str) or data['metric'] not in METRICS))
                or ('behaviorToken' in data and (not isinstance(data['behaviorToken'], str)
                    or re.fullmatch(r'[0-9a-f]{64}', data['behaviorToken']) is None))):
            return self.reply(400, {'error': 'Expected exerciseId, body, and a nonnegative integer revision.'})
        record = self.server.exercises.get(data['exerciseId'])
        if not record: return self.reply(404, {'error': 'Exercise not found.'})
        error = validate_body(data['body'])
        if path == '/api/behavior':
            result = ({'status': 'invalid', 'message': error} if error
                      else self.server.evaluate_behavior(record, data['body']))
            if result.get('status') == 'ok':
                result = dict(result, behaviorToken=behavior_token(record['id'], data['body'], result))
            return self.reply(200, dict(result, exerciseId=record['id'], revision=data['revision']))
        metric = data.get('metric', 'canonical')
        result = ({'status': 'invalid', 'diagnostics': [{'message': error}]} if error
                  else self.server.evaluate(record, data['body'], metric))
        if path == '/api/explain':
            if result.get('status') != 'ok':
                result = {'status': 'unavailable', 'model': 'gpt-6-luna',
                          'message': 'Check a valid predicate before requesting an explanation.'}
            else:
                evidence = None
                token = data.get('behaviorToken')
                if token is not None:
                    cache_key = (record['id'], hashlib.sha256(data['body'].encode()).hexdigest())
                    with self.server.cache_lock:
                        evidence = self.server.behavior_cache.get(cache_key)
                    if evidence is None or behavior_token(record['id'], data['body'], evidence) != token:
                        return self.reply(200, {'status': 'unavailable', 'model': 'gpt-6-luna',
                            'message': 'These examples have expired. Check your predicate again to refresh their guidance.',
                            'exerciseId': record['id'], 'revision': data['revision'], 'requestedMetric': metric})
                result = self.server.explainer.explain(result, student_body=data['body'], behavior=evidence)
                if token is not None:
                    result = dict(result, behaviorToken=token)
        self.reply(200, dict(result, exerciseId=record['id'], revision=data['revision'], requestedMetric=metric))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--timeout', type=float, default=12)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--java', default='java', help='Java 17+ executable (absolute path recommended on Windows)')
    parser.add_argument('--public-origin', action='append', default=[], type=normalize_origin,
                        help='Trusted browser origin behind IIS, e.g. https://alloy.example.org; repeat for aliases')
    args = parser.parse_args()
    if args.timeout <= 0 or args.workers < 1: parser.error('timeout and workers must be positive')
    if not (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file():
        parser.error('Build the engine first: ./scripts/build.sh')
    runtime = check_runtime(ROOT)
    if runtime['status'] != 'PASS':
        paths = sorted({error['path'] for error in runtime['errors']})
        parser.error('Bundled Java runtime is incomplete or changed: ' + ', '.join(paths)
                     + '. Restore the complete deployment archive and run runtime_dependencies.py.')
    try:
        server = Portal((args.host, args.port), timeout=args.timeout, workers=args.workers,
                        java=args.java, public_origins=args.public_origin)
    except StoreError:
        parser.error('Private exercise database validation failed. Run python scripts/manage_exercises.py info.')
    print(f'Alloy practice: http://{args.host}:{server.server_port}', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == '__main__': main()
