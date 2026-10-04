#!/usr/bin/env python3
"""Local Alloy practice portal; private exercise records never become HTTP files."""
import argparse
from collections import OrderedDict
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import math
import os
import secrets
import signal
from types import SimpleNamespace
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import unquote, urlsplit
from luna import Explainer
from runtime_dependencies import (check_runtime, runtime_classpath, run_engine,
                                  open_engine_admission, close_engine_admission, wait_for_oneshots)
from engine_workers import EnginePool, EngineUnavailable, EngineTimeout
from traffic_scheduler import Scheduler, EvidenceStore, CapacityError, Superseded, ChannelExpired, encode
from traffic_http import BoundedHTTPServer, TrafficProfile, DeadlineReader, HTTPInputError, bounded_json
from exercise_store import load_store, StoreError, parse_json
from admin_auth import AuthManager, AuthError
from admin_service import AdminService, AdminError
from traffic_limits import validated_int, validated_seconds
from traffic_profile import normalized_profile

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
          '/instance-graph.js': ('instance-graph.js', 'text/javascript; charset=utf-8'),
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


class Portal(BoundedHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, *, root=ROOT, timeout=12, workers=2, java='java', public_origins=(),
                 engine_mode=None, traffic_profile=None):
        timeout = validated_seconds(timeout, maximum=120)
        workers = validated_int(workers, maximum=32)
        traffic_profile = normalized_profile(TrafficProfile() if traffic_profile is None else traffic_profile)
        self.root = Path(root)
        self.snapshot_lock = threading.RLock()
        self._snapshot = load_store(self.root)
        self.generation = secrets.token_hex(16)
        self.timeout = timeout
        self.java = str(java)
        self.engine_mode = engine_mode or os.environ.get('ALLOY_ENGINE_MODE', 'persistent')
        if self.engine_mode not in ('persistent', 'oneshot'):
            raise ValueError('Engine mode must be persistent or oneshot')
        self.public_origins = frozenset(normalize_origin(origin) for origin in public_origins)
        self.admin_auth = AuthManager(self.root)
        open_engine_admission(self.root)
        self.explainer = Explainer()
        self.scheduler = Scheduler({'feedback': min(2, max(1, workers)), 'behavior': 1})
        self.cache_lock = self.scheduler.lock
        self.cache = self.scheduler.caches['feedback']
        self.behavior_cache = self.scheduler.caches['behavior']
        self.evidence = EvidenceStore()
        self.engine_pool = EnginePool(self.root, self.java, feedback_workers=min(2, max(1, workers)), behavior_workers=1)
        # Engine/dependency changes require a backend restart; exact payload bytes
        # and a service-local generation isolate all result/evidence identities.
        self.service_identity = secrets.token_hex(16)
        self.admin = AdminService(self, self.admin_auth)
        try:
            super().__init__(address, Handler, traffic_profile=traffic_profile)
        except Exception:
            self.scheduler.close()
            self.engine_pool.close()
            raise

    @property
    def snapshot(self):
        return self._snapshot

    @snapshot.setter
    def snapshot(self, value):
        with self.snapshot_lock:
            self._snapshot = value
            self.generation = secrets.token_hex(16)

    def capture(self):
        with self.snapshot_lock:
            return self._snapshot, self.generation

    @property
    def exercises(self):
        return self.snapshot.exercises

    @property
    def correct_pools(self):
        return self.snapshot.correct_pools

    def _key(self, kind, payload, generation):
        return (self.service_identity, generation, kind, encode(payload).decode('ascii'))

    def _engine(self, kind, payload):
        budget = self.timeout if kind == 'feedback' else max(30, self.timeout)
        if self.engine_mode == 'persistent':
            try:
                return self.engine_pool.evaluate(kind, payload, budget)
            except EngineTimeout:
                raise subprocess.TimeoutExpired('analysis', budget) from None
            except EngineUnavailable:
                raise OSError('Analysis unavailable') from None
        command = [self.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                   '-cp', runtime_classpath(self.root),
                   'live.LiveFeedback' if kind == 'feedback' else 'live.BehaviorFeedback']
        completed = run_engine(command, root=self.root, lane=kind, input=json.dumps(payload), text=True,
                               encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               cwd=self.root, timeout=budget, check=False)
        if completed.returncode or len(completed.stdout.encode('utf-8')) > 4 * 1048576:
            raise OSError('Analysis unavailable')
        return json.loads(completed.stdout)

    def _schedule(self, lane, key, compute, channel, revision):
        try:
            return self.scheduler.run(lane, key, compute, channel=channel, revision=revision,
                                      timeout=self.timeout if lane == 'feedback' else max(30, self.timeout))
        except CapacityError:
            return {'status': 'busy', 'code': 'capacity', 'retryable': True, 'dispatched': False,
                    'message': 'Analysis capacity is full. Try again shortly.'}
        except Superseded:
            return {'status': 'superseded', 'message': 'A newer edit replaced this request.'}
        except (RuntimeError, TimeoutError):
            return {'status': 'error', 'message': 'Analysis could not complete. Try again.'}

    def feedback_payload(self, record, body, metric, snapshot):
        return {'studentSource': model(record, body),
                'referenceBodies': snapshot.correct_pools[record['id']],
                'referencePrefix': record['environmentBefore'] + record['predicateHeader'] + '{\n',
                'referenceSuffix': '\n}' + record['environmentAfter'],
                'predicate': record['predicate'], 'metric': metric}

    def behavior_payload(self, record, body):
        return {'studentSource': model(record, body), 'studentBody': body,
                'oracleSource': model(record, record['oracleBody']), 'predicate': record['predicate']}

    def evidence_identity(self, record, body, metric, generation, channel, kind='feedback'):
        return (self.service_identity, generation, kind, record['id'], body,
                metric if kind == 'feedback' else None, channel)

    def server_close(self):
        deadline = time.monotonic() + 65
        close_engine_admission(self.root)
        self.scheduler.close()
        pool_status = self.engine_pool.close(timeout=max(0, deadline - time.monotonic()))
        admin_drained = self.admin.close(timeout=max(0, deadline - time.monotonic()))
        oneshots_drained = wait_for_oneshots(self.root, timeout=max(0, deadline - time.monotonic()))
        super().server_close()
        if not admin_drained or not oneshots_drained or pool_status['unreaped'] or pool_status['starting']:
            raise RuntimeError('Backend shutdown could not drain active analysis.')

    def evaluate(self, record, body, metric='canonical', *, snapshot=None, generation=None,
                 channel=None, revision=0):
        if not isinstance(metric, str) or metric not in METRICS:
            return {'status': 'invalid', 'diagnostics': [{'message': 'Choose Canonical form or Raw syntax tree.'}]}
        if snapshot is None:
            snapshot, generation = self.capture()
        payload = self.feedback_payload(record, body, metric, snapshot)
        snapshot = None  # retain only this bounded request, not an old whole catalogue
        key = self._key('feedback', payload, generation)
        return self._schedule('feedback', key, lambda: self._feedback(record, body, metric, payload), channel, revision)

    def _feedback(self, record, body, metric, payload):
        try:
            raw = self._engine('feedback', payload)
            if not isinstance(raw, dict):
                raise ValueError('Invalid response')
            expected_comparison = {'strategy': 'nearest-known-correct',
                                   'poolSize': len(payload['referenceBodies']),
                                   'evaluatedCandidates': len(payload['referenceBodies']),
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
            return result
        except subprocess.TimeoutExpired:
            return {'status': 'timeout', 'diagnostics': [{'message': 'Analysis exceeded the time limit. Simplify the predicate and retry.'}]}
        except (OSError, ValueError, TypeError, KeyError, RecursionError):
            return {'status': 'error', 'diagnostics': [{'message': 'The analysis service is unavailable.'}]}

    def evaluate_behavior(self, record, body, *, snapshot=None, generation=None, channel=None, revision=0):
        if snapshot is None:
            snapshot, generation = self.capture()
        payload = self.behavior_payload(record, body)
        snapshot = None
        key = self._key('behavior', payload, generation)
        return self._schedule('behavior', key, lambda: self._behavior(payload), channel, revision)

    def _behavior(self, payload):
        try:
            raw = self._engine('behavior', payload)
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
            return result
        except subprocess.TimeoutExpired:
            return {'status': 'timeout', 'message': 'Behavioral analysis exceeded its time limit. Canonical feedback remains available.'}
        except (OSError, ValueError, TypeError, KeyError, OverflowError, RecursionError):
            return {'status': 'error', 'message': 'Behavioral analysis returned no usable evidence. Try again.'}


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

    def setup(self):
        super().setup()
        self.rfile.close()
        self.rfile = DeadlineReader(self.connection, self.server.traffic_profile)

    def parse_request(self):
        parsed = super().parse_request()
        if parsed:
            self.rfile.check_deadline()
        return parsed

    def handle_one_request(self):
        try:
            super().handle_one_request()
        except HTTPInputError as error:
            # Parsing can fail before BaseHTTPRequestHandler sets these fields.
            if not hasattr(self, 'request_version'):
                self.request_version = 'HTTP/1.0'
            if not hasattr(self, 'requestline'):
                self.requestline = ''
            if not hasattr(self, 'command'):
                self.command = None
            if not hasattr(self, 'path'):
                self.path = ''
            self.close_connection = True
            self.reply(error.status, {'error': error.message})
        except (OSError, ValueError):
            self.close_connection = True
        finally:
            # One request owns one admission credit and one handler reservation.
            self.close_connection = True

    def reply(self, status, data, content_type='application/json; charset=utf-8', *, cookies=(), etag=None):
        if not isinstance(data, bytes):
            data = json.dumps(data, ensure_ascii=False).encode('utf-8')
        profile = self.server.traffic_profile
        if len(data) > profile.response_bytes:
            status, data, etag = 503, b'{"error":"Response exceeds its byte limit."}', None
        self.close_connection = True
        deadline = time.monotonic() + profile.write_seconds
        self.connection.settimeout(min(profile.idle_seconds, profile.write_seconds))
        try:
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            if status != 304:
                self.send_header('Content-Length', str(len(data)))
            self.send_header('Connection', 'close')
            if status == 405:
                self.send_header('Allow', 'GET, POST')
            path = unquote(urlsplit(getattr(self, 'path', '')).path)
            admin = path.startswith('/api/admin/') or path.startswith('/admin/')
            self.send_header('Cache-Control', ('no-cache, max-age=0, must-revalidate' if etag and not admin
                                               else 'no-store, private' if admin else 'no-store'))
            if etag and not admin:
                self.send_header('ETag', etag)
            if status in (429, 503) and isinstance(data, bytes) and b'"retryable": true' in data:
                self.send_header('Retry-After', '1')
            for cookie in cookies:
                self.send_header('Set-Cookie', cookie)
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Referrer-Policy', 'no-referrer')
            connect = "'self' https://api.github.com" if path.startswith('/dashboard/') else "'self'"
            self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src " + connect + "; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
            self.end_headers()
            if self.command != 'HEAD' and status != 304:
                for offset in range(0, len(data), 65536):
                    budget = deadline - time.monotonic()
                    if budget <= 0:
                        raise TimeoutError()
                    self.connection.settimeout(min(profile.idle_seconds, budget))
                    self.wfile.write(data[offset:offset + 65536])
        except (OSError, TimeoutError):
            pass

    def public_reply(self, key, produce, content_type='application/json; charset=utf-8', *, generation=None):
        app = self.server
        generation = app.snapshot if generation is None else generation
        data, etag = app.http_public_views.get(generation, key, produce)
        tags = self.headers.get('If-None-Match', '').split(',')
        matched = any(tag.strip().removeprefix('W/') in ('*', etag) for tag in tags)
        return self.reply(304 if matched else 200, b'' if matched else data, content_type, etag=etag)

    def request_headers(self, *, mutation=False, admin=False):
        # Framing is validated for every route, even a GET or a rejected request.
        for name in ('Origin', 'Host', 'Content-Length', 'Content-Type', 'Content-Encoding',
                     'X-CSRF-Token', 'Sec-Fetch-Site', 'If-None-Match', 'Expect'):
            if len(self.headers.get_all(name, [])) > 1:
                raise HTTPInputError(400, 'Duplicate administrator request header.' if admin
                                     else 'Duplicate request header.')
        if self.headers.get_all('Transfer-Encoding', []):
            raise HTTPInputError(400, 'Transfer encoding is not supported.')
        if self.headers.get('Content-Encoding', 'identity').lower() != 'identity':
            raise HTTPInputError(415, 'Content encoding is not supported.')
        if self.headers.get('Expect'):
            raise HTTPInputError(417, 'Expect is not supported.')
        if mutation and self.headers.get_content_type() != 'application/json':
            raise HTTPInputError(415, 'Send application/json.')
        charset = self.headers.get_content_charset()
        if charset is not None and charset.lower() not in ('utf-8', 'utf8'):
            raise HTTPInputError(415, 'Send UTF-8 JSON.')
        length = self.headers.get('Content-Length', '0')
        if re.fullmatch(r'0|[1-9][0-9]{0,7}', length) is None:
            raise HTTPInputError(400, 'Invalid content length.')
        if not mutation and int(length):
            raise HTTPInputError(400, 'This request must not have a body.')
        return int(length)

    def read_json_body(self, length, limit, *, admin=False):
        if not 0 < length <= limit:
            raise HTTPInputError(413, 'Administrator request exceeds its byte limit.' if admin
                                 else 'Request must be at most 16 KiB.')
        self.rfile.begin_body()
        remaining, chunks = length, []
        try:
            while remaining:
                chunk = self.rfile.read1(min(65536, remaining))
                if not chunk:
                    raise ValueError()
                chunks.append(chunk)
                remaining -= len(chunk)
            self.rfile.check_deadline()
            data = bounded_json(b''.join(chunks), self.server.traffic_profile.json_depth)
            self.rfile.check_deadline()
            return data
        except (OSError, ValueError, RecursionError):
            raise HTTPInputError(400, 'Invalid, incomplete or timed-out JSON request.') from None

    def public_body(self):
        return self.read_json_body(self.request_headers(mutation=True), MAX_REQUEST_BYTES)

    def admin_headers(self, *, mutation=False):
        try:
            return self.request_headers(mutation=mutation, admin=True)
        except HTTPInputError as error:
            raise AdminError(error.status, error.message) from None

    def admin_body(self, length, limit):
        try:
            return self.read_json_body(length, limit, admin=True)
        except HTTPInputError as error:
            raise AdminError(error.status, error.message) from None

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
        try:
            self.request_headers()
            raw_path = urlsplit(self.path).path
            path = unquote(raw_path)
            if self.server.control_listener:
                if path != '/api/health':
                    return self.reply(404, {'error': 'Not found.'})
                app = self.server.shared_app
                return self.reply(200, {'status': 'ok', 'exercises': len(app.exercises),
                                        'engine': 'ACGN / CanDis Fast Rewrite IR'})
            if path.startswith('/api/admin/') and path != raw_path:
                return self.reply(404, {'error': 'Not found.'})
            if path.startswith('/api/admin/'):
                return self.admin_GET(path)
            if path in ('/dashboard', '/admin'):
                self.connection.settimeout(self.server.traffic_profile.write_seconds)
                self.send_response(308)
                self.send_header('Location', path[1:] + '/')
                self.send_header('Content-Length', '0')
                self.send_header('Cache-Control', 'no-store')
                self.send_header('Connection', 'close')
                self.end_headers()
                return
            if path == '/api/health':
                return self.reply(200, {'status': 'ok', 'exercises': len(self.server.exercises),
                                        'engine': 'ACGN / CanDis Fast Rewrite IR'})
            snapshot = self.server.snapshot
            if path == '/api/exercises':
                return self.public_reply(('catalogue',), lambda: {
                    'exercises': [project(e, SUMMARY_FIELDS) for e in snapshot.exercises.values()]}, generation=snapshot)
            if path.startswith('/api/exercises/'):
                record = snapshot.exercises.get(path[len('/api/exercises/'):])
                if record:
                    return self.public_reply(('exercise', record['id']), lambda: project(record, PUBLIC_FIELDS), generation=snapshot)
            if path in STATIC:
                name, mime = STATIC[path]
                target = self.server.root / 'web' / name
                try:
                    stat = target.stat()
                    def contents():
                        with target.open('rb') as stream:
                            return stream.read(self.server.traffic_profile.response_bytes + 1)
                    if path.startswith('/admin/'):
                        return self.reply(200, contents(), mime)
                    return self.public_reply(('static', name, stat.st_mtime_ns, stat.st_ctime_ns, stat.st_ino, stat.st_size),
                                             contents, mime, generation=snapshot)
                except FileNotFoundError:
                    pass
            self.reply(404, {'error': 'Not found.'})
        except HTTPInputError as error:
            self.reply(error.status, {'error': error.message})

    def do_POST(self):
        path = urlsplit(self.path).path
        if self.server.control_listener:
            return self.reply(405, {'error': 'Method not allowed.'})
        if path.startswith('/api/admin/'):
            return self.admin_POST(path)
        try:
            self.request_headers(mutation=True)
            if path not in ('/api/feedback', '/api/explain', '/api/behavior', '/api/channel', '/api/cancel'):
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
            data = self.public_body()
        except HTTPInputError as error:
            return self.reply(error.status, {'error': error.message})
        if path == '/api/channel':
            if data:
                return self.reply(400, {'error': 'No channel fields are accepted.'})
            try:
                channel = self.server.scheduler.issue_channel(self.client_address[0])
                return self.reply(200, {'status': 'ok', 'channel': channel})
            except CapacityError:
                return self.reply(429, {'status': 'busy', 'code': 'capacity', 'retryable': True, 'dispatched': False})
        if path == '/api/cancel':
            if (set(data) != {'channel', 'revision'} or not isinstance(data.get('channel'), str)
                    or re.fullmatch(r'[A-Za-z0-9_-]{43}', data['channel']) is None
                    or type(data.get('revision')) is not int or not 0 <= data['revision'] <= 2**53 - 1):
                return self.reply(400, {'error': 'Expected channel and revision.'})
            try:
                self.server.scheduler.observe(data['channel'], data['revision'])
            except ChannelExpired:
                return self.reply(410, {'status': 'expired', 'code': 'channel_expired'})
            except Superseded:
                pass  # a delayed cancel cannot roll a channel back
            return self.reply(200, {'status': 'ok'})
        expected = {'exerciseId', 'body', 'revision'}
        optional = {'metric', 'channel'} | ({'behaviorToken', 'evidenceToken'} if path == '/api/explain' else set())
        fields = set(data)
        if (not expected <= fields or not fields <= expected | optional
                or not isinstance(data['exerciseId'], str)
                or type(data['revision']) is not int or not 0 <= data['revision'] <= 2**53 - 1
                or ('metric' in data and (not isinstance(data['metric'], str) or data['metric'] not in METRICS))
                or ('channel' in data and (not isinstance(data['channel'], str)
                    or re.fullmatch(r'[A-Za-z0-9_-]{43}', data['channel']) is None))
                or any(name in data and (not isinstance(data[name], str)
                    or re.fullmatch(r'[0-9a-f]{64}', data[name]) is None)
                       for name in ('behaviorToken', 'evidenceToken'))):
            return self.reply(400, {'error': 'Expected exerciseId, body, and a nonnegative integer revision.'})
        snapshot, generation = self.server.capture()
        record = snapshot.exercises.get(data['exerciseId'])
        if not record:
            return self.reply(404, {'error': 'Exercise not found.'})
        metric, channel, revision = data.get('metric', 'canonical'), data.get('channel'), data['revision']
        error = validate_body(data['body'])
        if not error:
            try:
                self.server.scheduler.observe(channel, revision, (generation, record['id'], data['body'], metric))
            except ChannelExpired:
                return self.reply(410, {'status': 'expired', 'code': 'channel_expired'})
            except Superseded:
                return self.reply(200, {'status': 'superseded', 'exerciseId': record['id'], 'revision': revision})
        selected = SimpleNamespace(correct_pools={record['id']: snapshot.correct_pools[record['id']]})
        snapshot = None  # publication cannot accumulate whole historical catalogues in waiters
        context = dict(snapshot=selected, generation=generation, channel=channel, revision=revision)
        identity = self.server.evidence_identity(record, data['body'], metric, generation, channel)
        behavior_identity = self.server.evidence_identity(record, data['body'], metric, generation, channel, 'behavior')
        if path == '/api/behavior':
            result = ({'status': 'invalid', 'message': error} if error else
                      self.server.evaluate_behavior(record, data['body'], **context))
            if result.get('status') == 'ok':
                token = behavior_token(record['id'], data['body'], result)
                # Each channel gets its own accounted pin; the public behavior
                # token remains derived only from displayed evidence as before.
                if channel is not None:
                    self.server.evidence.pin(behavior_identity, result)
                result = dict(result, behaviorToken=token)
        else:
            evidence_token = data.get('evidenceToken')
            if error:
                result = {'status': 'invalid', 'diagnostics': [{'message': error}]}
            elif path == '/api/explain' and evidence_token is not None:
                result = self.server.evidence.get(evidence_token, identity)
                if result is None:
                    result = {'status': 'expired'}
            else:
                result = self.server.evaluate(record, data['body'], metric, **context)
            if path == '/api/feedback' and result.get('status') == 'ok':
                result = dict(result, evidenceToken=self.server.evidence.pin(identity, result))
            if path == '/api/explain':
                token = data.get('behaviorToken')
                evidence = None
                if token is not None:
                    if channel is not None:
                        evidence = self.server.evidence.find(behavior_identity,
                            lambda value: behavior_token(record['id'], data['body'], value) == token)
                    if evidence is None and channel is None:
                        # Compatibility for old clients; never rerun behavioral
                        # enumeration to recreate a displayed instance.
                        key = self.server._key('behavior', self.server.behavior_payload(record, data['body']), generation)
                        with self.server.cache_lock:
                            evidence = self.server.behavior_cache.get(key)
                        if evidence is not None and behavior_token(record['id'], data['body'], evidence) != token:
                            evidence = None
                if result.get('status') != 'ok' or token is not None and evidence is None:
                    result = {'status': 'unavailable', 'model': 'gpt-6-luna',
                              'message': 'These hints or examples have expired. Check your predicate again to refresh guidance.'}
                else:
                    with self.server.scheduler.lock:
                        current = self.server.scheduler.current(channel, revision)
                    if not current:
                        result = {'status': 'superseded'}
                    else:
                        result = self.server.explainer.explain(result, student_body=data['body'], behavior=evidence)
                        if token is not None:
                            result = dict(result, behaviorToken=token)
        with self.server.scheduler.lock:
            if not error and not self.server.scheduler.current(channel, revision):
                result = {'status': 'superseded'}
        status = 503 if result.get('code') == 'capacity' and result.get('dispatched') is False else 200
        delivery = {'exerciseId': record['id'], 'revision': revision}
        if path != '/api/behavior':
            delivery['requestedMetric'] = metric
        self.reply(status, dict(result, **delivery))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--timeout', type=float, default=12)
    parser.add_argument('--workers', type=int, default=2,
                        help='Feedback workers (capped at 2; behavior and admin each reserve another lane)')
    parser.add_argument('--engine-mode', choices=('persistent', 'oneshot'),
                        default=os.environ.get('ALLOY_ENGINE_MODE', 'persistent'))
    parser.add_argument('--control-port', type=int, default=0,
                        help='Optional separate loopback health listener; 0 disables it')
    parser.add_argument('--java', default='java', help='Java 17+ executable (absolute path recommended on Windows)')
    parser.add_argument('--public-origin', action='append', default=[], type=normalize_origin,
                        help='Trusted browser origin behind IIS, e.g. https://alloy.example.org; repeat for aliases')
    args = parser.parse_args()
    if not math.isfinite(args.timeout) or not 0 < args.timeout <= 120 or not 1 <= args.workers <= 32:
        parser.error('timeout must be in (0, 120] and workers in [1, 32] (feedback capped at 2)')
    if not 0 <= args.port <= 65535 or not 0 <= args.control_port <= 65535 or args.control_port and args.control_port == args.port:
        parser.error('Use distinct valid public and control ports.')
    if not (ROOT / 'build/engine/classes/live/LiveFeedback.class').is_file():
        parser.error('Build the engine first: ./scripts/build.sh')
    runtime = check_runtime(ROOT)
    if runtime['status'] != 'PASS':
        paths = sorted({error['path'] for error in runtime['errors']})
        parser.error('Bundled Java runtime is incomplete or changed: ' + ', '.join(paths)
                     + '. Restore the complete deployment archive and run runtime_dependencies.py.')
    try:
        server = Portal((args.host, args.port), timeout=args.timeout, workers=args.workers,
                        java=args.java, public_origins=args.public_origin, engine_mode=args.engine_mode)
    except StoreError:
        parser.error('Private exercise database validation failed. Run python scripts/manage_exercises.py info.')
    control = None
    if args.control_port:
        try:
            control = BoundedHTTPServer(('127.0.0.1', args.control_port), Handler,
                                        control=True, shared_app=server)
        except OSError:
            server.server_close()
            parser.error('The loopback control port could not be bound.')
        threading.Thread(target=control.serve_forever, daemon=True).start()
    def terminate(signum, frame):
        raise KeyboardInterrupt()
    signal.signal(signal.SIGTERM, terminate)
    try:
        if args.engine_mode == 'persistent':
            try:
                server.engine_pool.prewarm()
            except EngineUnavailable:
                # No unbudgeted one-shot fallback.
                print('Analysis prewarm unavailable; check the Java runtime.', flush=True)
        print(f'Alloy practice: http://{args.host}:{server.server_port}', flush=True)
        server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        if control is not None:
            control.shutdown()
            control.server_close()
        server.server_close()


if __name__ == '__main__': main()
