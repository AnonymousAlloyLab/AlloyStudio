"""Bounded private candidate cache and guarded, separately validated pool additions.

No caller-supplied SQL, provider call, or JVM operation occurs at admission.
The portal holds its publication lock around admission and admin publication.
"""
from contextlib import closing
from copy import deepcopy
from pathlib import Path
import re
import secrets
import threading
import time

import exercise_sql as sql
import exercise_store as store

MAX_CANDIDATES = 100
MAX_PER_EXERCISE = 10
CACHE_KIND = 'studentCandidate'
CACHE_LOCK = threading.Lock()
HASH = re.compile(r'[a-f0-9]{64}\Z')
IDENTIFIER = re.compile(r'[A-Za-z0-9_-]{43}\Z')
CHECK_SCOPE = {'overall': 3, 'bitwidth': 3, 'maxSequence': 3, 'poolSize': 100,
               'minTrace': 1, 'maxTrace': 10}
RECORD_FIELDS = frozenset(('id', 'exerciseId', 'exerciseVersion', 'candidateHash', 'tokenSha256',
                          'body', 'createdAt', 'state', 'revision', 'behavioralEvidence', 'review'))


def candidate_version(record):
    return store.sha(store.encoded({key: record[key] for key in RECORD_FIELDS}))


def _evidence(value):
    keys = {'score', 'moduleFacts', 'undercoverage', 'overcoverage', 'scope', 'sampling'}
    if (type(value) is not dict or set(value) != keys or type(value['score']) not in (int, float)
            or value['score'] != 1 or value['moduleFacts'] is not True
            or value['undercoverage'] != 'unsat' or value['overcoverage'] != 'unsat'):
        raise store.StoreError('Candidate requires perfect bounded behavioral checks.')
    scope, sample = value['scope'], value['sampling']
    sample_keys = {'positiveTested','positiveAccepted','negativeTested','negativeRejected','semanticCounterexamples'}
    if (type(scope) is not dict or set(scope) != set(CHECK_SCOPE)
            or any(type(scope[key]) is not int or scope[key] != expected for key, expected in CHECK_SCOPE.items())
            or type(sample) is not dict or set(sample) != sample_keys
            or any(type(sample[key]) is not int or not 0 <= sample[key] <= 100 for key in sample_keys)
            or not 0 < sample['positiveTested'] == sample['positiveAccepted']
            or not 0 < sample['negativeTested'] == sample['negativeRejected']
            or sample['semanticCounterexamples'] != 0):
        raise store.StoreError('Candidate requires complete agreeing samples.')
    return deepcopy(value)


def behavioral_evidence(behavior):
    """Recheck admission even though the server has already projected the result."""
    if (type(behavior) is not dict or behavior.get('status') != 'ok'
            or behavior.get('metric') != 'acgn-reward' or behavior.get('scoreStatus') != 'ok'
            or behavior.get('scoreReason') != 'OK'):
        raise store.StoreError('Candidate requires a successful behavioral check.')
    scope = behavior.get('scope')
    if type(scope) is not dict or scope.get('moduleFacts') is not True:
        raise store.StoreError('Candidate requires model facts.')
    categories = behavior.get('categories')
    if type(categories) is not list or len(categories) != 4:
        raise store.StoreError('Candidate requires every behavioral category.')
    by_id = {}
    for item in categories:
        if type(item) is not dict or item.get('id') in by_id:
            raise store.StoreError('Invalid behavioral category.')
        by_id[item.get('id')] = item
    if set(by_id) != {'both','undercoverage','overcoverage','neither'}:
        raise store.StoreError('Missing behavioral category.')
    for name, truth in (('undercoverage', (True, False)), ('overcoverage', (False, True))):
        item = by_id[name]
        if (item.get('status') != 'unsat' or item.get('enumerationComplete') is not True
                or item.get('oracle') is not truth[0] or item.get('student') is not truth[1]
                or item.get('instances') != []):
            raise store.StoreError('A behavioral disagreement prevents admission.')
    for name, truth in (('both', (True, True)), ('neither', (False, False))):
        item = by_id[name]
        if (item.get('status') != 'sat' or item.get('oracle') is not truth[0]
                or item.get('student') is not truth[1] or type(item.get('instances')) is not list
                or not item['instances']):
            raise store.StoreError('Candidate requires positive and negative witnesses.')
    return _evidence(dict(score=behavior.get('score'), moduleFacts=True, undercoverage='unsat',
                          overcoverage='unsat', scope={k: v for k, v in scope.items() if k != 'moduleFacts'},
                          sampling=behavior.get('sampling')))


def _review(value, record):
    if value is None:
        return None
    if type(value) is not dict:
        raise store.StoreError('Invalid candidate review.')
    if value.get('status') in ('disabled', 'unavailable'):
        if set(value) != {'status', 'message'}:
            raise store.StoreError('Invalid unavailable candidate review.')
        store.text(value['message'], 1200, empty=False)
        return deepcopy(value)
    if set(value) != {'status', 'advice'} or value.get('status') != 'ok':
        raise store.StoreError('Invalid candidate review.')
    from candidate_review import validate_advice
    context = {key: record[key] for key in ('exerciseId','exerciseVersion','candidateHash')}
    return dict(status='ok', advice=validate_advice(value['advice'], context))


def validate_rows(rows):
    if type(rows) is not list or len(rows) > MAX_CANDIDATES:
        raise store.StoreError('Candidate cache limit exceeded.')
    records, seen, contexts, counts = [], set(), set(), {}
    for row in rows:
        value = store.parse_json(row['payload'])
        if type(value) is not dict or set(value) != RECORD_FIELDS - {'body'}:
            raise store.StoreError('Invalid candidate cache record.')
        value['body'] = row['original_source']
        if (type(value['id']) is not str or not IDENTIFIER.fullmatch(value['id'])
                or value['id'] in seen or type(value['exerciseId']) is not str
                or not store.ID.fullmatch(value['exerciseId'])
                or any(type(value[key]) is not str or not HASH.fullmatch(value[key])
                       for key in ('exerciseVersion','candidateHash','tokenSha256'))
                or type(value['createdAt']) is not int or not 0 <= value['createdAt'] < 2 ** 63
                or type(value['revision']) is not int or not 0 <= value['revision'] < 2 ** 63
                or value['state'] not in ('pending','approved','dismissed')
                or type(row['ordinal']) is not int or not 0 <= row['ordinal'] < 2 ** 63):
            raise store.StoreError('Invalid candidate identity or state.')
        store.body(value['body'])
        if store.sha(value['body']) != value['candidateHash'] or store.body_token_sha256(value['body']) != value['tokenSha256']:
            raise store.StoreError('Candidate body identity changed.')
        _evidence(value['behavioralEvidence'])
        _review(value['review'], value)
        context = (value['exerciseId'], value['exerciseVersion'], value['tokenSha256'])
        if context in contexts:
            raise store.StoreError('Duplicate candidate context.')
        contexts.add(context)
        seen.add(value['id'])
        counts[value['exerciseId']] = counts.get(value['exerciseId'], 0) + 1
        value['_ordinal'] = row['ordinal']
        records.append(value)
    if any(count > MAX_PER_EXERCISE for count in counts.values()):
        raise store.StoreError('Per-question candidate limit exceeded.')
    return records


def _records(connection):
    return validate_rows(store._auxiliary_kind(connection, CACHE_KIND))


def _dto(record, snapshot, *, detail=False):
    keys = ('id','exerciseId','exerciseVersion','candidateHash','state','revision','createdAt')
    value = {key: record[key] for key in keys}
    current = snapshot.exercises.get(record['exerciseId'])
    displayed = current or snapshot.raw_exercises.get(record['exerciseId'])
    value.update(title=displayed['title'] if displayed else record['exerciseId'],
                 predicate=displayed['predicate'] if displayed else '')
    value.update(candidateVersion=candidate_version(record), stale=current is None or
                 store.exercise_version(current) != record['exerciseVersion'])
    if detail:
        value.update({key: deepcopy(record[key]) for key in ('body','tokenSha256','behavioralEvidence','review')})
    return value


def list_candidates(root, snapshot, offset=0, limit=25):
    store._page(offset, limit, 25)
    with closing(store.connect(Path(root) / store.DATABASE_RELATIVE)) as connection:
        records = sorted(_records(connection), key=lambda r: (r['createdAt'], r['id']), reverse=True)
    return dict(items=[_dto(record, snapshot) for record in records[offset:offset + limit]],
                total=len(records), offset=offset, limit=limit)


def _selected(records, snapshot, identifier, expected_version=None, *, pending=False):
    if pending and (type(expected_version) is not str or not HASH.fullmatch(expected_version)):
        raise store.StoreError('Provide the current candidate version.')
    record = next((r for r in records if r['id'] == identifier), None)
    if record is None:
        raise store.StoreError('Candidate not found.')
    if expected_version is not None and expected_version != candidate_version(record):
        raise store.StoreError('This candidate changed. Refresh it before continuing.')
    if pending and (record['state'] != 'pending' or _dto(record, snapshot)['stale']):
        raise store.StoreError('This candidate is no longer pending for the current question.')
    return record


def read_candidate(root, snapshot, identifier, expected_candidate_version=None):
    with closing(store.connect(Path(root) / store.DATABASE_RELATIVE)) as connection:
        record = _selected(_records(connection), snapshot, identifier, expected_candidate_version)
    return _dto(record, snapshot, detail=True)


def _write(connection, record):
    if type(record['revision']) is not int or not 0 <= record['revision'] < 2 ** 63:
        raise store.StoreError('Candidate revision limit reached.')
    payload = store.encoded({key: record[key] for key in RECORD_FIELDS if key != 'body'})
    sql.execute(connection, 'update_auxiliary_item', (payload, record['body'], CACHE_KIND, record['_ordinal']))


def capture(root, record, version, known_tokens, candidate_body, behavior, *, now=None):
    """Best effort; caller must nonblockingly hold the portal publication gate."""
    if not CACHE_LOCK.acquire(blocking=False):
        return None
    connection = None
    try:
        if version != store.exercise_version(record):
            return None
        store.body(candidate_body)
        evidence = behavioral_evidence(behavior)
        token = store.body_token_sha256(candidate_body)
        if token in known_tokens:
            return None
        connection = store.connect(Path(root) / store.DATABASE_RELATIVE, writable=True)
        connection.execute('PRAGMA busy_timeout = 100')
        connection.execute('BEGIN IMMEDIATE')
        current = sql.execute(connection, 'select_question_prose', (record['id'],)).fetchone()
        if (current is None or current['title'] != record['title']
                or current['description'] != record['description']
                or sql.execute(connection, 'select_auxiliary_item', ('adminRemoved', current['ordinal'])).fetchone() is not None):
            return None
        # Current approvals are checked in the same write transaction. Each
        # exercise's family has a strict100-record cap; no full snapshot scan.
        approvals = store._auxiliary_kind(connection, 'adminApproval:' + record['id'])
        if len(approvals) > store.MAX_APPROVALS_PER_EXERCISE:
            return None
        if any(store.parse_json(row['payload']).get('tokenSha256') == token for row in approvals):
            return None
        records = _records(connection)
        if any(r['exerciseId'] == record['id'] and r['exerciseVersion'] == version
               and r['tokenSha256'] == token for r in records):
            return None
        same = [r for r in records if r['exerciseId'] == record['id']]
        def evict(options):
            victim = min(options, key=lambda r: (r['state'] == 'pending', r['createdAt'], r['id']))
            sql.execute(connection, 'delete_auxiliary_item', (CACHE_KIND, victim['_ordinal']))
            records.remove(victim)
        if len(same) >= MAX_PER_EXERCISE:
            evict(same)
        if len(records) >= MAX_CANDIDATES:
            evict(records)
        timestamp = int(time.time() * 1000) if now is None else now
        if type(timestamp) is not int or not 0 <= timestamp < 2 ** 63:
            return None
        value = dict(id=secrets.token_urlsafe(32), exerciseId=record['id'], exerciseVersion=version,
                     candidateHash=store.sha(candidate_body), tokenSha256=token, body=candidate_body,
                     createdAt=timestamp, state='pending', revision=0, behavioralEvidence=evidence, review=None)
        ordinal = max((r['_ordinal'] for r in records), default=-1) + 1
        store._insert(connection, 'auxiliary', dict(kind=CACHE_KIND, ordinal=ordinal,
            payload=store.encoded({k: v for k, v in value.items() if k != 'body'}), original_source=candidate_body))
        store._size_guard(connection)
        connection.commit()
        return dict(value, candidateVersion=candidate_version(value), stale=False)
    except Exception:
        return None
    finally:
        if connection is not None:
            connection.close()
        CACHE_LOCK.release()


def save_review(root, snapshot, identifier, expected_candidate_version, review, *, guard):
    return _mutate(root, snapshot, identifier, expected_candidate_version, review=review, guard=guard)


def dismiss(root, snapshot, identifier, expected_candidate_version, *, guard):
    return _mutate(root, snapshot, identifier, expected_candidate_version, dismiss=True, guard=guard)


def _mutate(root, snapshot, identifier, expected_candidate_version, *, review=None, dismiss=False, guard):
    with CACHE_LOCK:
        connection = store.connect(Path(root) / store.DATABASE_RELATIVE, writable=True)
        try:
            connection.execute('BEGIN IMMEDIATE')
            # Administrative mutations are infrequent; re-read all authority to
            # reject stale decisions even if another process edited the question.
            current = store._snapshot(connection)
            record = _selected(_records(connection), current, identifier, expected_candidate_version, pending=True)
            if dismiss:
                record['state'] = 'dismissed'
            else:
                record['review'] = _review(review, record)
            record['revision'] += 1
            _write(connection, record)
            store._size_guard(connection)
            with guard():
                connection.commit()
            return _dto(record, current, detail=True)
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()


def approve(root, identifier, expected_candidate_version, expected_exercise_version, certificate, *, guard):
    """Internal certificate capability; HTTP passes only the random identifier/CAS."""
    with CACHE_LOCK:
        connection = store.connect(Path(root) / store.DATABASE_RELATIVE, writable=True)
        try:
            connection.execute('BEGIN IMMEDIATE')
            snapshot = store._snapshot(connection)
            candidate = _selected(_records(connection), snapshot, identifier, expected_candidate_version, pending=True)
            record = snapshot.exercises[candidate['exerciseId']]
            if expected_exercise_version != store.exercise_version(record):
                raise store.StoreError('This question changed. Refresh it before continuing.')
            store.validate_approval(record, candidate['body'], certificate,
                                    minimum_scope=snapshot.equivalence_scopes.get(record['id'], 5))
            if candidate['tokenSha256'] in snapshot.pool_tokens[record['id']]:
                raise store.StoreError('This predicate is already in the correct pool.')
            kind = 'adminApproval:' + record['id']
            approvals = store._auxiliary_kind(connection, kind)
            if len(approvals) >= store.MAX_APPROVALS_PER_EXERCISE:
                raise store.StoreError('This question reached its approved predicate limit.')
            total = sum(row['kind'].startswith('adminApproval:') for row in store._rows(connection, 'auxiliary'))
            if total >= store.MAX_APPROVALS:
                raise store.StoreError('The approved predicate limit was reached.')
            witness = dict(exerciseId=record['id'], exerciseVersion=expected_exercise_version,
                           candidateId=candidate['id'], candidateHash=candidate['candidateHash'],
                           tokenSha256=candidate['tokenSha256'], certificate=certificate,
                           approvedAt=int(time.time() * 1000))
            store._insert(connection, 'auxiliary', dict(kind=kind, ordinal=len(approvals),
                          payload=store.encoded(witness), original_source=candidate['body']))
            candidate['state'], candidate['revision'] = 'approved', candidate['revision'] + 1
            _write(connection, candidate)
            result = store._snapshot(connection)
            store._size_guard(connection)
            with guard():
                connection.commit()
            return result
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()
