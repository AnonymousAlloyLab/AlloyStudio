"""Bounded, process-local authority for verified learner implementations.

Saved browser code is portable; these opaque bearer receipts are not assertions
from a browser. Issuance consumes already checked evidence and never runs Java.
"""
from collections import OrderedDict
from dataclasses import dataclass
import json
import re
import secrets
import threading
import time
from types import MappingProxyType

from candidate_store import behavioral_evidence
from exercise_store import body as checked_body, content_version, exercise_version, sha, StoreError
from practice_dependencies import validate_plan
from traffic_limits import validated_int, validated_seconds

TOKEN = re.compile(r'[A-Za-z0-9_-]{43}\Z')
MAX_CHAIN = 100
MAX_QUERY = 200
METRICS = {'canonical': 'acgn-fast-rewrite-canonical-distance',
           'ast': 'acgn-raw-ast-zhang-shasha-distance'}


class ProgressError(ValueError):
    """No code, token value or private diagnostic is included in errors."""


@dataclass(frozen=True, slots=True)
class VerifiedContext:
    direct_tokens: tuple
    implementations: object
    identities: tuple
    version: str


@dataclass(frozen=True, slots=True)
class Completion:
    exercise_id: str
    version: str
    authority_version: str
    body: str
    body_sha256: str
    metric: str
    prerequisites: tuple
    context_version: str
    created: float


def token_list(value, maximum=99):
    if (type(value) is not list or len(value) > maximum
            or any(type(token) is not str or not TOKEN.fullmatch(token) for token in value)
            or len(set(value)) != len(value)):
        raise ProgressError('Invalid completion token list.')
    return value


def context_version(record, entries):
    # Public content and the learner's own implementations only; no oracle hash.
    identities = tuple(sorted((identifier, entry.version, entry.body_sha256, entry.context_version)
                              for identifier, entry in entries.items()))
    version = sha(json.dumps([record['id'], content_version(record), identities],
                             separators=(',', ':'), ensure_ascii=True))
    return identities, version


class CompletionAuthority:
    def __init__(self, *, maximum=1024, budget=8 * 1048576, ttl=86400,
                 clock=time.monotonic, token_factory=lambda: secrets.token_urlsafe(32)):
        self.maximum = validated_int(maximum, maximum=1024)
        self.budget = validated_int(budget, maximum=8 * 1048576)
        self.ttl = validated_seconds(ttl, maximum=86400)
        self.clock, self.token_factory = clock, token_factory
        self.lock = threading.RLock()
        self.entries = OrderedDict()
        self.bytes = 0

    def _cleanup(self):
        now = self.clock()
        for token, (entry, size) in list(self.entries.items()):
            if now - entry.created >= self.ttl:
                del self.entries[token]
                self.bytes -= size

    @staticmethod
    def _merge(target, entries):
        for identifier, entry in entries.items():
            previous = target.get(identifier)
            if previous is not None and (previous.version, previous.authority_version,
                    previous.body_sha256, previous.context_version) != (
                    entry.version, entry.authority_version, entry.body_sha256, entry.context_version):
                raise ProgressError('Prerequisite implementations belong to different contexts.')
            target[identifier] = entry
        if len(target) >= MAX_CHAIN:
            raise ProgressError('Too many prerequisite implementations.')

    def _walk(self, token, snapshot, memo, visiting):
        if token in memo:
            return memo[token]
        if token in visiting or len(visiting) >= MAX_CHAIN:
            raise ProgressError('Invalid prerequisite chain.')
        stored = self.entries.get(token)
        if stored is None:
            raise ProgressError('A saved prerequisite check has expired. Restore its checks.')
        entry = stored[0]
        record = snapshot.exercises.get(entry.exercise_id)
        if (record is None or entry.version != content_version(record)
                or entry.authority_version != exercise_version(record)
                or entry.body_sha256 != sha(entry.body)):
            raise ProgressError('A saved prerequisite changed. Check its implementation again.')
        visiting.add(token)
        try:
            ancestors = self._resolve_entries(record, list(entry.prerequisites), snapshot, memo, visiting)
            if context_version(record, ancestors)[1] != entry.context_version:
                raise ProgressError('A saved prerequisite context changed.')
            result = dict(ancestors)
            if entry.exercise_id in result:
                raise ProgressError('Invalid prerequisite cycle.')
            result[entry.exercise_id] = entry
            if len(result) > MAX_CHAIN:
                raise ProgressError('Too many prerequisite implementations.')
            memo[token] = result
            self.entries.move_to_end(token)
            return result
        finally:
            visiting.remove(token)

    def _resolve_entries(self, record, tokens, snapshot, memo, visiting):
        plan = validate_plan(record)
        expected = plan['direct'] if plan else []
        by_id, all_entries = {}, {}
        for token in tokens:
            values = self._walk(token, snapshot, memo, visiting)
            entry = self.entries[token][0]
            if entry.exercise_id in by_id:
                raise ProgressError('A prerequisite was supplied more than once.')
            by_id[entry.exercise_id] = token
            self._merge(all_entries, values)
        if set(by_id) != set(expected):
            raise ProgressError('Complete the required prerequisites before starting this practice.')
        if set(all_entries) != set(plan['dependencies'] if plan else []):
            raise ProgressError('The saved prerequisite chain does not match this practice.')
        return all_entries

    def resolve(self, record, tokens, snapshot):
        token_list(tokens)
        with self.lock:
            self._cleanup()
            entries = self._resolve_entries(record, tokens, snapshot, {}, set())
            plan = validate_plan(record)
            ids = plan['direct'] if plan else []
            by_id = {self.entries[token][0].exercise_id: token for token in tokens}
            identities, version = context_version(record, entries)
            return VerifiedContext(tuple(by_id[identifier] for identifier in ids),
                MappingProxyType({identifier: entry.body for identifier, entry in entries.items()}),
                identities, version)

    def mint(self, record, body, metric, structural, behavior, context, snapshot):
        """Only called after the portal's channel/generation evidence checks."""
        if (metric not in METRICS or type(structural) is not dict
                or structural.get('status') != 'ok'
                or type(structural.get('distance')) not in (int, float)
                or structural['distance'] != 0
                or structural.get('metric') != METRICS[metric]
                or type(structural.get('comparison')) is not dict
                or structural['comparison'].get('complete') is not True):
            return None
        try:
            behavioral_evidence(behavior)
            checked_body(body)
        except (StoreError, ValueError, TypeError, UnicodeError):
            return None
        with self.lock:
            self._cleanup()
            fresh = self.resolve(record, list(context.direct_tokens), snapshot)
            if fresh.version != context.version or fresh.identities != context.identities:
                raise ProgressError('The prerequisite context changed during the check.')
            entry = Completion(record['id'], content_version(record), exercise_version(record),
                body, sha(body), metric, fresh.direct_tokens, fresh.version, self.clock())
            size = len(json.dumps([entry.exercise_id, entry.version, entry.authority_version,
                entry.body, entry.body_sha256, entry.metric, entry.prerequisites,
                entry.context_version, entry.created], ensure_ascii=False,
                separators=(',', ':')).encode('utf-8')) + 43
            if size > self.budget:
                return None
            for token, (previous, _) in list(self.entries.items()):
                if (previous.exercise_id, previous.version, previous.authority_version,
                        previous.body, previous.prerequisites, previous.context_version) == (
                        entry.exercise_id, entry.version, entry.authority_version, entry.body,
                        entry.prerequisites, entry.context_version):
                    self.entries.move_to_end(token)
                    return token  # absolute expiry is deliberately not renewed
            # Never mint a token whose own prerequisites are evicted by admission.
            protected = set()
            pending = list(entry.prerequisites)
            while pending:
                token = pending.pop()
                if token not in protected:
                    protected.add(token)
                    pending.extend(self.entries[token][0].prerequisites)
            while len(self.entries) >= self.maximum or self.bytes + size > self.budget:
                removable = next((token for token in self.entries if token not in protected), None)
                if removable is None:
                    return None
                _, removed_size = self.entries.pop(removable)
                self.bytes -= removed_size
            for _ in range(4):
                token = self.token_factory()
                if type(token) is not str or not TOKEN.fullmatch(token):
                    raise ProgressError('Completion authority is unavailable.')
                if token not in self.entries:
                    self.entries[token] = (entry, size)
                    self.bytes += size
                    return token
            return None

    def inspect(self, tokens, snapshot):
        token_list(tokens, MAX_QUERY)
        completed, expired = [], []
        with self.lock:
            self._cleanup()
            memo = {}
            for token in tokens:
                try:
                    self._walk(token, snapshot, memo, set())
                    entry = self.entries[token][0]
                    completed.append(dict(exerciseId=entry.exercise_id, contentVersion=entry.version,
                        completionToken=token, dependencyContextVersion=entry.context_version,
                        bodySha256=entry.body_sha256, prerequisiteTokens=list(entry.prerequisites)))
                except (ProgressError, ValueError, TypeError, KeyError, UnicodeError):
                    expired.append(token)
        return dict(status='ok', completions=completed, expiredTokens=expired)

    def stats(self):
        with self.lock:
            self._cleanup()
            return dict(records=len(self.entries), bytes=self.bytes,
                        maximum=self.maximum, budget=self.budget)
