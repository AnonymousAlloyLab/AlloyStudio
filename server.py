#!/usr/bin/env python3
"""Local Alloy practice portal; private exercise records never become HTTP files."""
import argparse
from collections import OrderedDict
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
from urllib.parse import unquote, urlsplit
from luna import Explainer
from runtime_dependencies import check_runtime, runtime_classpath
from scripts.import_correct_pools import verify_document

ROOT = Path(__file__).resolve().parent
PUBLIC_FIELDS = ('id', 'title', 'group', 'predicate', 'description', 'environmentBefore',
                 'environmentAfter', 'predicateHeader', 'starter', 'source')
SUMMARY_FIELDS = ('id', 'title', 'group', 'predicate', 'description')
MAX_BODY_BYTES = 8192
MAX_REQUEST_BYTES = 16384
STATIC = {'/': ('index.html', 'text/html; charset=utf-8'),
          '/index.html': ('index.html', 'text/html; charset=utf-8'),
          '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
          '/styles.css': ('styles.css', 'text/css; charset=utf-8')}
POOL_VALIDATION_CACHE = OrderedDict()
POOL_VALIDATION_LOCK = threading.Lock()


def load_correct_pools(root, catalogue_bytes, catalogue):
    """Validate private witnesses once per content pair, never by path or mtime."""
    encoded = (root / 'exercises/correct-pools.json').read_bytes()
    key = (hashlib.sha256(catalogue_bytes).digest(), hashlib.sha256(encoded).digest())
    with POOL_VALIDATION_LOCK:
        if key not in POOL_VALIDATION_CACHE:
            document = json.loads(encoded)
            verify_document(catalogue, document)
            POOL_VALIDATION_CACHE[key] = tuple((pool['exerciseId'], tuple(candidate['body']
                for candidate in pool['candidates'])) for pool in document['pools'])
            if len(POOL_VALIDATION_CACHE) > 4:
                POOL_VALIDATION_CACHE.popitem(last=False)
        POOL_VALIDATION_CACHE.move_to_end(key)
        # Do not share a mutable dictionary between server instances.
        return dict(POOL_VALIDATION_CACHE[key])


def project(record, fields):
    return {key: record[key] for key in fields}


def model(record, body):
    return (record['environmentBefore'] + record['predicateHeader'] + '{\n' + body
            + '\n}' + record['environmentAfter'])


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
            'ranges': [], 'reason': 'No corresponding learner canonical fragment is available.'}
        if (not isinstance(raw, dict) or raw.get('coordinateSystem') != 'canonical'
                or raw.get('offsetEncoding') != 'utf-16'
                or raw.get('status') not in ('located', 'ambiguous')
                or raw.get('precision') not in ('related', 'form')
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
            reason = ('Canonical form context; a smaller fragment could not be identified.'
                      if raw['precision'] == 'form' else
                      'Matching canonical fragments; the normalized occurrence may be ambiguous.'
                      if len(projected) > 1 else 'Learner canonical fragment related to this edit step.')
            operation['canonicalLocation'] = {
                'status': raw['status'], 'coordinateSystem': 'canonical', 'offsetEncoding': 'utf-16',
                'precision': raw['precision'], 'reason': reason,
                'ranges': sorted(projected, key=lambda item: (item['formIndex'], item['start'], item['end']))}


def project_source_locations(operations, record, body):
    """Bind learner-only UTF-16 spans to this exact editable body.

    The engine relates canonical hints to parser expressions, not certified
    source repairs. Never trust its snippets, coordinates, or free-form reasons;
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
                'reason': 'No reliable location in this predicate body is available for this normalized edit.'}

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
                or raw.get('precision') not in ('exact', 'related', 'predicate')
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
            # Downgrade any engine precision claim: AST expression matching
            # establishes a related region, not an exact defect provenance.
            precision = 'predicate' if raw['precision'] == 'predicate' else 'related'
            reason = ('The whole predicate is context for this edit; a smaller location is unavailable.'
                      if precision == 'predicate' else
                      'These source expressions may correspond to the normalized edit; choose a location to inspect.'
                      if len(projected) > 1 else
                      'This source expression corresponds to the learner fragment; normalization can change the required repair.')
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
        catalogue_bytes = (self.root / 'exercises/catalogue.json').read_bytes()
        data = json.loads(catalogue_bytes)
        self.exercises = {e['id']: e for e in data['exercises']}
        self.correct_pools = load_correct_pools(self.root, catalogue_bytes, data)
        self.timeout = timeout
        self.java = str(java)
        self.public_origins = frozenset(normalize_origin(origin) for origin in public_origins)
        self.explainer = Explainer()
        self.slots = threading.BoundedSemaphore(workers)
        self.cache, self.cache_lock = OrderedDict(), threading.Lock()
        super().__init__(address, Handler)

    def evaluate(self, record, body):
        key = (record['id'], hashlib.sha256(body.encode()).hexdigest())
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
                       'predicate': record['predicate']}
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
                # The adapter emits only public data. Project again at the HTTP boundary.
                allowed = ('status', 'metric', 'distance', 'breakdown', 'canonicalForm',
                           'operations', 'operationSummary', 'trace', 'diagnostics', 'comparison')
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


class Handler(BaseHTTPRequestHandler):
    server_version = 'AlloyPractice/1.0'

    def log_message(self, fmt, *args):
        # No learner input, oracle, model paths, or query strings in access logs.
        pass

    def reply(self, status, data, content_type='application/json; charset=utf-8'):
        if not isinstance(data, bytes): data = json.dumps(data, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(data)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Content-Type-Options', 'nosniff')
        self.send_header('Referrer-Policy', 'no-referrer')
        self.send_header('Content-Security-Policy', "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'")
        self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError): pass

    def do_GET(self):
        path = unquote(urlsplit(self.path).path)
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
        if path not in ('/api/feedback', '/api/explain'):
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
        if (not isinstance(data, dict) or set(data) != {'exerciseId', 'body', 'revision'}
                or not isinstance(data['exerciseId'], str)
                or type(data['revision']) is not int or not 0 <= data['revision'] <= 2**53 - 1):
            return self.reply(400, {'error': 'Expected exerciseId, body, and a nonnegative integer revision.'})
        record = self.server.exercises.get(data['exerciseId'])
        if not record: return self.reply(404, {'error': 'Exercise not found.'})
        error = validate_body(data['body'])
        result = ({'status': 'invalid', 'diagnostics': [{'message': error}]} if error
                  else self.server.evaluate(record, data['body']))
        if path == '/api/explain':
            result = (self.server.explainer.explain(result) if result.get('status') == 'ok'
                      else {'status': 'unavailable', 'model': 'gpt-6-luna', 'message': 'Check a valid predicate before requesting an explanation.'})
        self.reply(200, dict(result, exerciseId=record['id'], revision=data['revision']))


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
    if not (ROOT / 'exercises/correct-pools.json').is_file():
        parser.error('Import the private correct pools first: python scripts/import_correct_pools.py')
    server = Portal((args.host, args.port), timeout=args.timeout, workers=args.workers,
                    java=args.java, public_origins=args.public_origin)
    print(f'Alloy practice: http://{args.host}:{server.server_port}', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()


if __name__ == '__main__': main()
