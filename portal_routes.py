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
from exercise_store import content_version
from practice_dependencies import public_slots, validate_plan
from practice_progress import ProgressError, token_list
from luna import solution_length_comparison
from tutor_chat import context_projection
from traffic_scheduler import CapacityError, Superseded, ChannelExpired

PUBLIC_FIELDS = ('id', 'title', 'group', 'predicate', 'description', 'environmentBefore',
                 'environmentAfter', 'predicateHeader', 'starter', 'source', 'contentVersion')
SUMMARY_FIELDS = ('id', 'title', 'group', 'predicate', 'description', 'contentVersion')
MAX_BODY_BYTES = 8192
METRICS = {'canonical': 'acgn-fast-rewrite-canonical-distance',
           'ast': 'acgn-raw-ast-zhang-shasha-distance'}
STATIC = {'/': ('index.html', 'text/html; charset=utf-8'),
          '/index.html': ('index.html', 'text/html; charset=utf-8'),
          '/app.js': ('app.js', 'text/javascript; charset=utf-8'),
          '/tutor-chat.js': ('tutor-chat.js', 'text/javascript; charset=utf-8'),
          '/instance-graph.js': ('instance-graph.js', 'text/javascript; charset=utf-8'),
          '/alloy-language.js': ('alloy-language.js', 'text/javascript; charset=utf-8'),
          '/styles.css': ('styles.css', 'text/css; charset=utf-8')}
STATIC.update({'/dashboard/' + name: ('dashboard/' + name, mime) for name, mime in (
    ('index.html', 'text/html; charset=utf-8'), ('app.js', 'text/javascript; charset=utf-8'),
    ('styles.css', 'text/css; charset=utf-8'), ('data.json', 'application/json; charset=utf-8'))})
STATIC['/dashboard/'] = STATIC['/dashboard/index.html']
STATIC.update({'/admin/' + name: ('admin/' + name, mime) for name, mime in (
    ('index.html', 'text/html; charset=utf-8'), ('app.js', 'text/javascript; charset=utf-8'),
    ('styles.css', 'text/css; charset=utf-8'))})
STATIC['/admin/'] = STATIC['/admin/index.html']
PUBLIC_POST_ROUTES = frozenset(('/api/feedback', '/api/explain', '/api/behavior', '/api/channel', '/api/cancel', '/api/progress',
                              '/api/chat', '/api/chat/suggestions'))
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
    return {key: content_version(record) if key == 'contentVersion' else record[key] for key in fields}


def dependency_requirements(record, snapshot):
    plan = validate_plan(record)
    return [dict(id=identifier, predicate=snapshot.exercises[identifier]['predicate'],
                 contentVersion=content_version(snapshot.exercises[identifier]))
            for identifier in plan['direct']] if plan else []


def exercise_projection(record, fields, snapshot):
    result = project(record, fields)
    if 'practicePlan' in record:
        result['dependencies'] = dependency_requirements(record, snapshot)
        if 'environmentBefore' in fields:
            result['dependencySlots'] = public_slots(record)
    return result


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
    if path == '/api/chat/models':
        return Reply.json(200, app.chat.catalogue())
    if path in ('/dashboard', '/admin'):
        return Reply.redirect(path[1:] + '/')
    if path == '/api/health':
        return health(app)
    snapshot = app.snapshot
    if path == '/api/exercises':
        return Reply.public(('catalogue',), lambda: {
            'exercises': [exercise_projection(e, SUMMARY_FIELDS, snapshot)
                          for e in snapshot.exercises.values()]}, generation=snapshot)
    if path.startswith('/api/exercises/'):
        record = snapshot.exercises.get(path[len('/api/exercises/'):])
        if record:
            return Reply.public(('exercise', record['id']),
                lambda: exercise_projection(record, PUBLIC_FIELDS, snapshot), generation=snapshot)
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
    if path in ('/api/chat', '/api/chat/suggestions'):
        return chat_post(app, request)
    if path == '/api/progress':
        try:
            if type(data) is not dict or set(data) != {'completionTokens'}:
                raise ProgressError('Invalid completion query.')
            tokens = token_list(data['completionTokens'], 200)
            snapshot, _ = app.capture()
            return Reply.json(200, app.progress_status(tokens, snapshot))
        except (ProgressError, ValueError, TypeError, KeyError, UnicodeError):
            return Reply.json(400, {'error': 'Supply at most 200 distinct completion tokens.'})
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
    optional = {'metric', 'channel', 'prerequisiteTokens'} | (
        {'behaviorToken', 'evidenceToken'} if path == '/api/explain' else
        {'evidenceToken'} if path == '/api/behavior' else set())
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
    try:
        tokens = token_list(data.get('prerequisiteTokens', []))
    except ProgressError:
        return Reply.json(400, {'error': 'Supply at most 99 distinct prerequisite tokens.'})
    snapshot, generation = app.capture()
    base = snapshot.exercises.get(data['exerciseId'])
    if not base:
        return Reply.json(404, {'error': 'Exercise not found.'})
    base_version = content_version(base)
    try:
        record, dependency_context = app.resolve_practice(base, tokens, snapshot)
    except (ProgressError, ValueError, TypeError, KeyError, UnicodeError):
        return Reply.json(423, dict(status='locked', code='prerequisites_required',
            message='Complete or restore the required prerequisite checks before starting this practice.',
            required=dependency_requirements(base, snapshot), exerciseId=base['id'],
            revision=data['revision'], contentVersion=base_version))
    metric, channel, revision = data.get('metric', 'canonical'), data.get('channel'), data['revision']
    error = validate_body(data['body'])
    if not error:
        try:
            app.scheduler.observe(channel, revision, (generation, record['id'], data['body'], metric,
                                                       dependency_context.version))
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
            completion = app.mint_completion(record, data['body'], metric, data.get('evidenceToken'),
                result, dependency_context, generation, channel, revision)
            token = behavior_token(record['id'], data['body'], result)
            # Each channel gets its own accounted pin; the public behavior
            # token remains derived only from displayed evidence as before.
            if channel is not None:
                app.evidence.pin(behavior_identity, result)
            result = dict(result, behaviorToken=token)
            if completion is not None:
                result.update(completionToken=completion,
                              completionBodySha256=hashlib.sha256(data['body'].encode('utf-8')).hexdigest(),
                              completionPrerequisiteTokens=list(dependency_context.direct_tokens))
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
                result = {'status': 'unavailable',
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
                                                   question=record['description'], solution_comparison=comparison,
                                                   dependency_context=dependency_context.version)
                    if token is not None:
                        result = dict(result, behaviorToken=token)
    with app.scheduler.lock:
        if not error and not app.scheduler.current(channel, revision):
            result = {'status': 'superseded'}
    status = 503 if result.get('code') == 'capacity' and result.get('dispatched') is False else 200
    delivery = {'exerciseId': record['id'], 'revision': revision, 'contentVersion': base_version,
                'dependencyContextVersion': dependency_context.version}
    if path != '/api/behavior':
        delivery['requestedMetric'] = metric
    return Reply.json(status, dict(result, **delivery))


def chat_post(app, request):
    data = request.body
    suggestions = request.path == '/api/chat/suggestions'
    required = {'exerciseId', 'body', 'revision', 'metric', 'channel', 'model', 'effort'}
    if not suggestions:
        required.add('message')
    optional = {'prerequisiteTokens', 'evidenceToken', 'behaviorToken', 'conversationToken'}
    def text(value, maximum, *, empty=False):
        return (isinstance(value, str) and (empty or bool(value.strip())) and '\x00' not in value
                and len(value.encode('utf-8')) <= maximum)
    try:
        valid = (type(data) is dict and required <= set(data) <= required | optional
            and text(data['exerciseId'], 256) and text(data['body'], MAX_BODY_BYTES, empty=True)
            and type(data['revision']) is int and 0 <= data['revision'] <= 2**53 - 1
            and isinstance(data['metric'], str) and data['metric'] in METRICS
            and text(data['channel'], 43) and re.fullmatch(r'[A-Za-z0-9_-]{43}', data['channel'])
            and text(data['model'], 128) and text(data['effort'], 16)
            and (suggestions or text(data['message'], 2048))
            and all(text(data[name], 64) and re.fullmatch(r'[0-9a-f]{64}', data[name])
                    for name in ('evidenceToken', 'behaviorToken') if name in data)
            and ('conversationToken' not in data or (text(data['conversationToken'], 43)
                 and re.fullmatch(r'[A-Za-z0-9_-]{43}', data['conversationToken']))))
        if not valid:
            raise ValueError('Invalid chat fields')
        tokens = token_list(data.get('prerequisiteTokens', []))
    except (ValueError, TypeError, KeyError, UnicodeError, ProgressError):
        return Reply.json(400, {'error': 'Supply the current draft, channel, model and a message of at most 2 KiB.'})
    snapshot, generation = app.capture()
    base = snapshot.exercises.get(data['exerciseId'])
    if base is None:
        return Reply.json(404, {'error': 'Exercise not found.'})
    delivery = dict(exerciseId=base['id'], revision=data['revision'], metric=data['metric'],
                    contentVersion=content_version(base))
    def reply(result):
        return Reply.json(200, dict(result, **delivery))
    try:
        record, dependency = app.resolve_practice(base, tokens, snapshot)
    except (ProgressError, ValueError, TypeError, KeyError, UnicodeError):
        return Reply.json(423, dict(status='locked', message='Complete the prerequisites before chatting about this practice.',
                                   **delivery))
    body, metric, channel, revision = (data[name] for name in ('body', 'metric', 'channel', 'revision'))
    try:
        app.scheduler.observe(channel, revision, (generation, record['id'], body, metric, dependency.version))
    except ChannelExpired:
        return Reply.json(410, dict(status='expired', code='channel_expired', **delivery))
    except Superseded:
        return reply({'status': 'superseded'})
    identity = app.evidence_identity(record, body, metric, generation, channel)
    feedback = app.evidence.get(data['evidenceToken'], identity) if 'evidenceToken' in data else None
    behavior = None
    if 'behaviorToken' in data:
        behavior_identity = app.evidence_identity(record, body, metric, generation, channel, 'behavior')
        behavior = app.evidence.find(behavior_identity,
            lambda value: behavior_token(record['id'], body, value) == data['behaviorToken'])
    if ('evidenceToken' in data and feedback is None) or ('behaviorToken' in data and behavior is None):
        return reply({'status': 'expired', 'message': 'These hints or examples expired. Check this draft again before using them in chat.'})
    try:
        context = context_projection(record['description'], body, metric, feedback, behavior,
                                     context_identity=dependency.version)
        context['predicateHeader'] = record['predicateHeader']
        context['revision'] = revision
    except (ValueError, TypeError, KeyError, UnicodeError, RecursionError, OverflowError):
        return reply({'status': 'unavailable', 'message': 'The displayed evidence cannot be used for chat. Refresh the predicate check.'})
    # A conversation belongs to this exercise, not one frozen draft. Each call
    # still resolves exact current evidence above and checks current revision.
    owner = (identity[0], generation, delivery['exerciseId'], channel,
             delivery['contentVersion'], dependency.version)
    # No pool selection, engine dispatch, or environment copy enters this path.
    snapshot = base = record = feedback = behavior = None
    def current():
        current_generation = app.capture()[1]
        with app.scheduler.lock:
            return app.scheduler.current(channel, revision) and current_generation == generation
    result = app.chat.respond(context=context, owner=owner, channel=channel,
        model=data['model'], effort=data['effort'], message=data.get('message'),
        conversation_token=data.get('conversationToken'), suggestions=suggestions, current=current)
    return reply(result if current() else {'status': 'superseded'})


def admin_body_limit(path):
    if path == '/api/admin/login':
        return 8192
    return 2 * 1048576 if path in ('/api/admin/prepare', '/api/admin/commit') else 131072


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
    elif path == '/api/admin/library':
        if set(data) != {'offset'}:
            raise AdminError(400, 'Provide a page offset.')
        result, status = app.admin.library(principal, data['offset']), 200
    elif path == '/api/admin/questions/detail':
        if set(data) != {'exerciseId'}:
            raise AdminError(400, 'Provide an exercise ID.')
        result, status = app.admin.question(principal, data['exerciseId']), 200
    elif path == '/api/admin/questions/edit':
        if set(data) != {'exerciseId', 'version', 'title', 'question'}:
            raise AdminError(400, 'Provide the exercise, current version, title and question.')
        result = app.admin.edit_question(principal, data['exerciseId'], data['version'], data['title'], data['question'])
        return Reply.json(200, result)
    elif path == '/api/admin/questions/remove':
        if set(data) != {'exerciseId', 'version', 'confirmation'} or data['confirmation'] != data['exerciseId']:
            raise AdminError(400, 'Confirm the selected exercise ID.')
        result = app.admin.remove_question(principal, data['exerciseId'], data['version'])
        return Reply.json(200, result)
    elif path == '/api/admin/candidates':
        if set(data) != {'offset'}:
            raise AdminError(400, 'Provide a page offset.')
        result, status = app.admin.candidates(principal, data['offset']), 200
    elif path == '/api/admin/candidates/detail':
        if set(data) != {'id', 'version'}:
            raise AdminError(400, 'Provide the candidate ID and current version.')
        result, status = app.admin.candidate(principal, data['id'], data['version']), 200
    elif path in ('/api/admin/candidates/review', '/api/admin/candidates/approve', '/api/admin/candidates/dismiss'):
        if set(data) != {'id', 'version'}:
            raise AdminError(400, 'Provide the candidate ID and current version.')
        action = path.rsplit('/', 1)[-1]
        if action == 'dismiss':
            result = app.admin.dismiss_candidate(principal, data['id'], data['version'])
            return Reply.json(200, result)
        result, status = app.admin.start_candidate(principal, data['id'], data['version'], action), 202
    else:
        raise AdminError(404, 'Not found.')
    with auth.guard(principal):
        auth.touch(principal)
    return Reply.json(status, result)
