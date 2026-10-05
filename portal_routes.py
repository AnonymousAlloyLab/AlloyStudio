"""Business routes behind the validated ingress boundary (AP01-C08).

server.Handler decodes the request line, headers, deadlines and the bounded JSON
body, resolves the quota identity and applies administration admission. Only
then does it dispatch to the registered callbacks here, with a frozen
ValidatedRequest and named RouteServices. Callbacks never receive the socket, the request reader, raw
header objects or HTTP admission state, and they never write a response: they
return a Reply that the handler serializes under its byte and deadline bounds.
"""
from dataclasses import dataclass
import hashlib
import json
import re
from types import SimpleNamespace

from admin_service import AdminError
from luna import solution_length_comparison
from traffic_scheduler import CapacityError, Superseded, ChannelExpired

PUBLIC_FIELDS = ('id', 'title', 'group', 'predicate', 'description', 'environmentBefore',
                 'environmentAfter', 'predicateHeader', 'starter', 'source')
SUMMARY_FIELDS = ('id', 'title', 'group', 'predicate', 'description')
MAX_BODY_BYTES = 8192
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
PUBLIC_POST_ROUTES = frozenset(('/api/feedback', '/api/explain', '/api/behavior', '/api/channel', '/api/cancel'))
# Only these request fields cross the boundary; X-Forwarded-For is consumed by ingress.
HEADER_ALLOWLIST = frozenset(('origin', 'host', 'cookie', 'x-csrf-token', 'sec-fetch-site',
                              'content-length', 'transfer-encoding'))
JSON = 'application/json; charset=utf-8'
ENGINE_NAME = 'ACGN / CanDis Fast Rewrite IR'


@dataclass(frozen=True, slots=True)
class FrozenHeaders:
    """Immutable allowlisted header pairs, preserving multiplicity for strict checks."""

    _pairs: tuple

    def __init__(self, pairs):
        object.__setattr__(self, '_pairs', tuple((str(name), str(value)) for name, value in pairs
                            if str(name).lower() in HEADER_ALLOWLIST))

    def get_all(self, name, failobj=None):
        values = [value for key, value in self._pairs if key.lower() == name.lower()]
        return values if values else failobj

    def get(self, name, failobj=None):
        values = self.get_all(name)
        return values[0] if values else failobj

    def items(self):
        return list(self._pairs)


@dataclass(frozen=True)
class ValidatedRequest:
    method: str
    path: str
    listener: str
    peer: str
    identity: object
    headers: FrozenHeaders
    body: object = None


@dataclass(frozen=True)
class Reply:
    status: int
    data: object = None
    content_type: str = JSON
    cookies: tuple = ()
    public_key: tuple = None
    produce: object = None
    generation: object = None
    location: str = None

    @classmethod
    def json(cls, status, data, *, cookies=()):
        return cls(status, data, cookies=tuple(cookies))

    @classmethod
    def public(cls, key, produce, content_type=JSON, *, generation):
        return cls(200, content_type=content_type, public_key=key, produce=produce, generation=generation)

    @classmethod
    def redirect(cls, location):
        return cls(308, location=location)


NOT_FOUND = Reply(404, {'error': 'Not found.'})


def project(record, fields):
    return {key: record[key] for key in fields}


def behavior_token(exercise_id, body, evidence):
    """Bind educational annotations to the exact public witness snapshot."""
    encoded = json.dumps([exercise_id, body, evidence], sort_keys=True,
                         separators=(',', ':'), ensure_ascii=True).encode('utf-8')
    return hashlib.sha256(encoded).hexdigest()


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


def administration_path(path):
    """Static administration UI paths; their admission precedes any file read."""
    return path == '/admin' or path.startswith('/admin/')


def health(app):
    return Reply.json(200, {'status': 'ok', 'exercises': len(app.exercises), 'engine': ENGINE_NAME})


def control_get(app, request):
    if request.path == '/api/health':
        return health(app)
    if request.path == '/api/diagnostics':
        snapshot = app.diagnostics()
        return Reply.json(200 if snapshot is not None else 503,
                          snapshot if snapshot is not None else {'error': 'Diagnostics are unavailable.'})
    return NOT_FOUND


def public_get(app, request):
    path = request.path
    if path in ('/dashboard', '/admin'):
        return Reply.redirect(path[1:] + '/')
    if path == '/api/health':
        return health(app)
    snapshot = app.snapshot
    if path == '/api/exercises':
        return Reply.public(('catalogue',), lambda: {
            'exercises': [project(e, SUMMARY_FIELDS) for e in snapshot.exercises.values()]}, generation=snapshot)
    if path.startswith('/api/exercises/'):
        record = snapshot.exercises.get(path[len('/api/exercises/'):])
        if record:
            return Reply.public(('exercise', record['id']), lambda: project(record, PUBLIC_FIELDS), generation=snapshot)
    if path in STATIC:
        name, mime = STATIC[path]
        target = app.root / 'web' / name
        limit = app.traffic_profile.response_bytes + 1
        try:
            stat = target.stat()
            def contents():
                with target.open('rb') as stream:
                    return stream.read(limit)
            if path.startswith('/admin/'):
                return Reply(200, contents(), mime)
            return Reply.public(('static', name, stat.st_mtime_ns, stat.st_ctime_ns, stat.st_ino, stat.st_size),
                                contents, mime, generation=snapshot)
        except FileNotFoundError:
            pass
    return NOT_FOUND


def public_post(app, request):
    path, data = request.path, request.body
    if path == '/api/channel':
        if data:
            return Reply.json(400, {'error': 'No channel fields are accepted.'})
        if request.identity is None:
            # A trusted proxy sent absent or malformed forwarding metadata: no fallback.
            return Reply.json(400, {'error': 'The forwarded client identity was rejected.', 'code': 'identity_rejected'})
        try:
            channel = app.scheduler.issue_channel(request.identity)
            return Reply.json(200, {'status': 'ok', 'channel': channel})
        except CapacityError:
            return Reply.json(429, {'status': 'busy', 'code': 'capacity', 'retryable': True, 'dispatched': False})
    if path == '/api/cancel':
        if (set(data) != {'channel', 'revision'} or not isinstance(data.get('channel'), str)
                or re.fullmatch(r'[A-Za-z0-9_-]{43}', data['channel']) is None
                or type(data.get('revision')) is not int or not 0 <= data['revision'] <= 2**53 - 1):
            return Reply.json(400, {'error': 'Expected channel and revision.'})
        try:
            app.scheduler.observe(data['channel'], data['revision'])
        except ChannelExpired:
            return Reply.json(410, {'status': 'expired', 'code': 'channel_expired'})
        except Superseded:
            pass  # a delayed cancel cannot roll a channel back
        return Reply.json(200, {'status': 'ok'})
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
        return Reply.json(400, {'error': 'Expected exerciseId, body, and a nonnegative integer revision.'})
    snapshot, generation = app.capture()
    record = snapshot.exercises.get(data['exerciseId'])
    if not record:
        return Reply.json(404, {'error': 'Exercise not found.'})
    metric, channel, revision = data.get('metric', 'canonical'), data.get('channel'), data['revision']
    error = validate_body(data['body'])
    if not error:
        try:
            app.scheduler.observe(channel, revision, (generation, record['id'], data['body'], metric))
        except ChannelExpired:
            return Reply.json(410, {'status': 'expired', 'code': 'channel_expired'})
        except Superseded:
            return Reply.json(200, {'status': 'superseded', 'exerciseId': record['id'], 'revision': revision})
    selected = SimpleNamespace(correct_pools={record['id']: snapshot.correct_pools[record['id']]})
    snapshot = None  # publication cannot accumulate whole historical catalogues in waiters
    context = dict(snapshot=selected, generation=generation, channel=channel, revision=revision)
    identity = app.evidence_identity(record, data['body'], metric, generation, channel)
    behavior_identity = app.evidence_identity(record, data['body'], metric, generation, channel, 'behavior')
    if path == '/api/behavior':
        result = ({'status': 'invalid', 'message': error} if error else
                  app.evaluate_behavior(record, data['body'], **context))
        if result.get('status') == 'ok':
            token = behavior_token(record['id'], data['body'], result)
            # Each channel gets its own accounted pin; the public behavior
            # token remains derived only from displayed evidence as before.
            if channel is not None:
                app.evidence.pin(behavior_identity, result)
            result = dict(result, behaviorToken=token)
    else:
        evidence_token = data.get('evidenceToken')
        if error:
            result = {'status': 'invalid', 'diagnostics': [{'message': error}]}
        elif path == '/api/explain' and evidence_token is not None:
            result = app.evidence.get(evidence_token, identity)
            if result is None:
                result = {'status': 'expired'}
        else:
            result = app.evaluate(record, data['body'], metric, **context)
        if path == '/api/feedback' and result.get('status') == 'ok':
            result = dict(result, evidenceToken=app.evidence.pin(identity, result))
        if path == '/api/explain':
            token = data.get('behaviorToken')
            evidence = None
            if token is not None:
                if channel is not None:
                    evidence = app.evidence.find(behavior_identity,
                        lambda value: behavior_token(record['id'], data['body'], value) == token)
                if evidence is None and channel is None:
                    # Compatibility for old clients; never rerun behavioral
                    # enumeration to recreate a displayed instance.
                    key = app._key('behavior', app.behavior_payload(record, data['body']), generation)
                    with app.cache_lock:
                        evidence = app.behavior_cache.get(key)
                    if evidence is not None and behavior_token(record['id'], data['body'], evidence) != token:
                        evidence = None
            if result.get('status') != 'ok' or token is not None and evidence is None:
                result = {'status': 'unavailable', 'model': 'gpt-6-luna',
                          'message': 'These hints or examples have expired. Check your predicate again to refresh guidance.'}
            else:
                with app.scheduler.lock:
                    current = app.scheduler.current(channel, revision)
                if not current:
                    result = {'status': 'superseded'}
                else:
                    comparison = (solution_length_comparison(data['body'], selected.correct_pools[record['id']])
                                  if type(result.get('distance')) is int and result['distance'] == 0 else None)
                    result = app.explainer.explain(result, student_body=data['body'], behavior=evidence,
                                                   question=record['description'], solution_comparison=comparison)
                    if token is not None:
                        result = dict(result, behaviorToken=token)
    with app.scheduler.lock:
        if not error and not app.scheduler.current(channel, revision):
            result = {'status': 'superseded'}
    status = 503 if result.get('code') == 'capacity' and result.get('dispatched') is False else 200
    delivery = {'exerciseId': record['id'], 'revision': revision}
    if path != '/api/behavior':
        delivery['requestedMetric'] = metric
    return Reply.json(status, dict(result, **delivery))


def admin_body_limit(path):
    if path == '/api/admin/login':
        return 8192
    return 2 * 1048576 if path == '/api/admin/prepare' else 131072


def admin_authorize(app, request):
    """Authorization gate evaluated before the administrator body is read."""
    # Preserve AuthManager's HTTP-only-loopback restriction for the resolved
    # client. A local reverse proxy must not turn a remote client into localhost.
    return app.admin_auth.authorize(request.headers, request.identity,
                                    preauth=request.path == '/api/admin/login')


def admin_get(app, request):
    auth, path = app.admin_auth, request.path
    if path == '/api/admin/session':
        state, cookies = auth.bootstrap(request.headers, request.identity)
        return Reply.json(200, state, cookies=cookies)
    principal = auth.authorize(request.headers, request.identity, mutation=False)
    match = re.fullmatch(r'/api/admin/drafts/([A-Za-z0-9_-]{43})', path)
    if not match:
        raise AdminError(404, 'Not found.')
    result = app.admin.view(principal, match.group(1))
    with auth.guard(principal):
        auth.touch(principal)
    return Reply.json(200, result)


def admin_post(app, request, principal):
    auth, path, data = app.admin_auth, request.path, request.body
    if path == '/api/admin/login':
        if set(data) != {'password'}:
            raise AdminError(400, 'Provide only the password.')
        result, cookies = auth.login(principal, data['password'])
        return Reply.json(200, result, cookies=cookies)
    if path == '/api/admin/logout':
        if data:
            raise AdminError(400, 'Unexpected sign-out fields.')
        cookies = auth.logout(principal)
        return Reply.json(200, {'status': 'signed_out'}, cookies=cookies)
    if path == '/api/admin/prepare':
        result = app.admin.prepare(principal, data)
        status = 202
    elif path == '/api/admin/suggest':
        if set(data) != {'id', 'revision', 'questionSeed'}:
            raise AdminError(400, 'Provide draft ID, revision and questionSeed.')
        result = app.admin.request_suggestion(principal, data['id'], data['revision'], data['questionSeed'])
        status = 202
    elif path == '/api/admin/discard':
        if set(data) != {'id'}:
            raise AdminError(400, 'Provide a draft ID.')
        result = app.admin.discard(principal, data['id'])
        status = 200
    elif path == '/api/admin/commit':
        if set(data) != {'id', 'revision', 'exercises'}:
            raise AdminError(400, 'Provide draft ID, revision and reviewed exercises.')
        result = app.admin.commit(principal, data['id'], data['revision'], data['exercises'])
        # The final transaction guard is the commit authorization point.
        # A later logout must not turn a successful commit into a failure.
        return Reply.json(200, result)
    else:
        raise AdminError(404, 'Not found.')
    with auth.guard(principal):
        auth.touch(principal)
    return Reply.json(status, result)
