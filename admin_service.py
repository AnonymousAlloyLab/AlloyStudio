"""Bounded, owner-bound upload jobs and atomic administrative publication."""
from contextlib import contextmanager, nullcontext
from copy import deepcopy
from dataclasses import dataclass, field
import re
import secrets
import threading
import time

from admin_auth import AuthError
from admin_luna import suggest
from admin_upload import prepare_upload, UploadError
from exercise_store import (StoreError, commit_upload, text, library_list, library_detail,
                            edit_question, remove_question, exercise_version, prepare_approval)
import candidate_store
import candidate_review

DRAFT_SECONDS = 900
MAX_DRAFTS = 8
MAX_OWNER_DRAFTS = 2
NO_REVISION = object()


class AdminError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status, self.message = status, message


@dataclass
class Draft:
    identifier: str
    principal: object
    created: float
    state: str = 'preparing'
    revision: int = 0
    prepared: object = None
    groups: list = field(default_factory=list)
    filename: str = ''
    source_hash: str = ''
    scope: int = 5
    suggestion_status: str = 'not_requested'
    message: str = 'Checking the model and all solution variants…'
    kind: str = 'upload'
    candidate_id: str = ''
    result: object = None


class AdminService:
    def __init__(self, portal, auth, *, clock=time.monotonic):
        self.portal, self.auth, self.clock = portal, auth, clock
        self.lock = threading.RLock()
        self.slot = threading.BoundedSemaphore(1)
        self.drafts = {}
        self.closed = False
        self.idle = threading.Event()
        self.idle.set()

    def close(self, timeout=65):
        """Stop admission/publication and wait for the bounded active operation."""
        with self.lock:
            self.closed = True
        return self.idle.wait(timeout)

    def _available(self):
        if self.closed:
            raise AdminError(503, 'The administrator service is stopping.')

    def _cleanup(self):
        for identifier, draft in list(self.drafts.items()):
            expired = self.clock() - draft.created >= DRAFT_SECONDS
            try:
                with self.auth.guard(draft.principal):
                    pass
            except AuthError:
                expired = True
            if expired:
                del self.drafts[identifier]

    def _owned(self, principal, identifier, revision=NO_REVISION):
        self._available()
        self.auth.validate(principal)
        self._cleanup()
        draft = self.drafts.get(identifier) if type(identifier) is str else None
        if (draft is None or draft.principal.owner != principal.owner
                or draft.principal.generation != principal.generation):
            raise AdminError(404,'Draft not found or expired.')
        if revision is not NO_REVISION and (type(revision) is not int or revision != draft.revision):
            raise AdminError(409,'This preview changed. Refresh it before continuing.')
        return draft

    @staticmethod
    def _view(draft):
        if draft.kind != 'upload':
            return dict(id=draft.identifier, revision=draft.revision, state=draft.state,
                        kind=draft.kind, candidateId=draft.candidate_id,
                        message=draft.message, result=deepcopy(draft.result))
        return dict(id=draft.identifier,revision=draft.revision,state=draft.state,
                    filename=draft.filename,sourceSha256=draft.source_hash,
                    equivalenceScope=draft.scope,groups=deepcopy(draft.groups),
                    suggestionStatus=draft.suggestion_status,message=draft.message)

    def view(self, principal, identifier):
        with self.lock:
            return self._view(self._owned(principal,identifier))

    def discard(self, principal, identifier):
        with self.lock:
            draft = self._owned(principal,identifier)
            del self.drafts[draft.identifier]
        return {'status':'discarded'}

    def prepare(self, principal, envelope):
        if (type(envelope) is not dict or not {'source','filename','modelId'} <= set(envelope)
                or set(envelope) - {'source','filename','modelId','equivalenceScope'}):
            raise AdminError(400,'Provide source, filename and modelId.')
        text(envelope['source'],262144,empty=False)
        text(envelope['filename'],256,empty=False)
        text(envelope['modelId'],128,empty=False)
        envelope = deepcopy(envelope)
        with self.lock:
            self._available()
            self.auth.validate(principal)
            self._cleanup()
            if (len(self.drafts) >= MAX_DRAFTS
                    or sum(item.principal.owner == principal.owner for item in self.drafts.values()) >= MAX_OWNER_DRAFTS):
                raise AdminError(429,'Discard an earlier draft before uploading another model.')
            if not self.slot.acquire(blocking=False):
                raise AdminError(429,'An upload operation is running. Try again shortly.')
            draft = Draft(secrets.token_urlsafe(32),principal,self.clock())
            self.drafts[draft.identifier] = draft
            self.idle.clear()
            try:
                threading.Thread(target=self._prepare,args=(draft,envelope),daemon=True).start()
            except Exception:
                del self.drafts[draft.identifier]
                self.slot.release()
                self.idle.set()
                raise AdminError(503,'The upload worker is unavailable.') from None
            return self._view(draft)

    def _prepare(self, draft, envelope):
        try:
            with self.lock:
                if self._owned(draft.principal,draft.identifier) is not draft:
                    return
            prepared = prepare_upload(self.portal.root,envelope,java=self.portal.java,timeout=60)
            with self.lock:
                self._cleanup()
                if self.drafts.get(draft.identifier) is not draft:
                    return
                draft.prepared = prepared
                witness = prepared['witness']
                draft.filename, draft.source_hash = witness['filename'],witness['sourceSha256']
                draft.scope = prepared['documents'][0].get('equivalenceScope',5)
                draft.groups = [dict(id=doc['id'],predicate=doc['predicate'],title=doc['title'],
                                      question=doc['description'],oracleCount=len(doc['oracleSolutions']),
                                      variants=[variant['name'] for variant in group['variants']])
                                for doc, group in zip(prepared['documents'],witness['groups'])]
                draft.state, draft.message = 'ready','All solution variants agree within the displayed bounds. Review the public questions.'
                draft.revision += 1
        except Exception as error:
            with self.lock:
                self._cleanup()
                if self.drafts.get(draft.identifier) is draft:
                    draft.state, draft.revision = 'rejected',draft.revision+1
                    draft.message = (str(error)[:300] if isinstance(error,(StoreError,UploadError))
                                     else 'The upload could not be validated. Check the format guide and try again.')
        finally:
            with self.lock:
                self.slot.release()
                self.idle.set()

    def request_suggestion(self, principal, identifier, revision, seed):
        text(seed,8192)
        with self.lock:
            draft = self._owned(principal,identifier,revision)
            if draft.state != 'ready':
                raise AdminError(409,'Wait for model validation before requesting suggestions.')
            if not self.slot.acquire(blocking=False):
                raise AdminError(429,'An upload operation is running. Try again shortly.')
            draft.state, draft.message = 'suggesting','Luna is drafting question suggestions…'
            draft.revision += 1
            self.idle.clear()
            try:
                threading.Thread(target=self._suggest,args=(draft,seed),daemon=True).start()
            except Exception:
                draft.state = 'ready'
                self.slot.release()
                self.idle.set()
                raise AdminError(503,'The suggestion worker is unavailable.') from None
            return self._view(draft)

    def _suggest(self, draft, seed):
        try:
            with self.lock:
                if self._owned(draft.principal,draft.identifier) is not draft:
                    return
            result = suggest(self.portal.root,draft.prepared['witness'],seed)
            with self.lock:
                self._cleanup()
                if self.drafts.get(draft.identifier) is not draft:
                    return
                if result['status'] == 'ok':
                    # Admin Luna validates identity; protect it again at assignment.
                    from admin_luna import validate_metadata
                    metadata = validate_metadata({'exercises':result['exercises']},[g['predicate'] for g in draft.groups])
                    for group, item in zip(draft.groups,metadata):
                        group.update(title=item['title'],question=item['question'])
                draft.suggestion_status = result['status']
                draft.message = ('Review these suggestions. Only the titles and questions will become public.'
                                 if result['status'] == 'ok' else 'Suggestions are unavailable. Enter and review the questions yourself.')
                draft.state, draft.revision = 'ready',draft.revision+1
        except Exception:
            with self.lock:
                self._cleanup()
                if self.drafts.get(draft.identifier) is draft:
                    draft.suggestion_status = 'unavailable'
                    draft.state, draft.revision = 'ready',draft.revision+1
                    draft.message = 'Suggestions are unavailable. Enter and review the questions yourself.'
        finally:
            with self.lock:
                self.slot.release()
                self.idle.set()

    @contextmanager
    def _publication_guard(self, principal, draft, revision):
        # Snapshot construction/SQLite lock waits may cross the draft deadline.
        # Recheck draft identity, revision and lifetime at the commit point too.
        with self.lock:
            current = self._owned(principal,draft.identifier,revision)
            if current is not draft or current.state != 'ready' or current.prepared is None:
                raise AdminError(409,'This draft is no longer ready to publish.')
            with self.auth.guard(principal):
                yield

    def commit(self, principal, identifier, revision, metadata):
        with self.lock:
            draft = self._owned(principal,identifier,revision)
            if draft.state != 'ready' or draft.prepared is None:
                raise AdminError(409,'This draft is not ready to publish or has already been published.')
            with self._snapshot_guard():
                snapshot = commit_upload(self.portal.root,draft.prepared,metadata,
                                         guard=lambda:self._publication_guard(principal,draft,revision))
                # A single pointer publishes a fully validated, committed generation.
                self.portal.snapshot = snapshot
            identifiers = [item['id'] for item in draft.prepared['documents']]
            draft.prepared = None
            draft.state, draft.revision = 'committed',draft.revision+1
            draft.message = 'Published. The exercises are available in Alloy Studio.'
            return {'status':'committed','exerciseIds':identifiers,'exerciseCount':snapshot.exercise_count}

    def _snapshot_guard(self):
        # Test portals may omit the publication lock; production Portal owns it.
        return getattr(self.portal, 'snapshot_lock', nullcontext())

    def library(self, principal, offset):
        with self.lock, self.auth.guard(principal):
            self._available()
            return library_list(self.portal.snapshot, offset=offset)

    def question(self, principal, identifier):
        with self.lock, self.auth.guard(principal):
            self._available()
            return library_detail(self.portal.snapshot, identifier)

    def _question_mutation(self, principal, operation, *args):
        with self.lock:
            self._available()
            self.auth.validate(principal)
            self._version(args[1])
            with self._snapshot_guard():
                try:
                    snapshot = operation(self.portal.root, *args,
                                         guard=lambda:self.auth.guard(principal))
                except StoreError:
                    raise AdminError(409, 'The question changed or cannot be updated. Refresh the library.') from None
                self.portal.snapshot = snapshot
                return {'status':'committed', 'exerciseCount':snapshot.exercise_count}

    def edit_question(self, principal, identifier, version, title, question):
        return self._question_mutation(principal, edit_question, identifier, version, title, question)

    def remove_question(self, principal, identifier, version):
        return self._question_mutation(principal, remove_question, identifier, version)

    def candidates(self, principal, offset):
        with self.lock, self.auth.guard(principal):
            self._available()
            return candidate_store.list_candidates(self.portal.root, self.portal.snapshot, offset=offset)

    def candidate(self, principal, identifier, version):
        with self.lock, self.auth.guard(principal):
            self._available()
            self._candidate_identity(identifier, version)
            return candidate_store.read_candidate(self.portal.root, self.portal.snapshot, identifier, version)

    def dismiss_candidate(self, principal, identifier, version):
        with self.lock:
            self._available()
            self.auth.validate(principal)
            self._candidate_identity(identifier, version)
            with self._snapshot_guard():
                result = candidate_store.dismiss(self.portal.root, self.portal.snapshot, identifier, version,
                                                guard=lambda:self.auth.guard(principal))
            return {'status':'dismissed', 'candidate':result}

    def start_candidate(self, principal, identifier, version, action):
        if action not in ('review', 'approve'):
            raise AdminError(400, 'Unknown candidate action.')
        with self.lock:
            self._available()
            self.auth.validate(principal)
            self._candidate_identity(identifier, version)
            self._cleanup()
            if (len(self.drafts) >= MAX_DRAFTS or
                    sum(d.principal.owner == principal.owner for d in self.drafts.values()) >= MAX_OWNER_DRAFTS):
                raise AdminError(429, 'Discard an earlier operation before starting another.')
            with self._snapshot_guard():
                snapshot = self.portal.snapshot
                candidate = candidate_store.read_candidate(self.portal.root, snapshot, identifier, version)
                if candidate['state'] != 'pending' or candidate['stale']:
                    raise AdminError(409, 'This candidate is no longer pending in the current question.')
                record = deepcopy(snapshot.exercises[candidate['exerciseId']])
            if not self.slot.acquire(blocking=False):
                raise AdminError(429, 'An administrator operation is running. Try again shortly.')
            draft = Draft(secrets.token_urlsafe(32), principal, self.clock(),
                          state='reviewing' if action == 'review' else 'approving',
                          kind=action, candidate_id=identifier,
                          message=('Sol is reviewing this candidate…' if action == 'review' else
                                   'Alloy is checking agreement with the unchanged model facts…'))
            self.drafts[draft.identifier] = draft
            self.idle.clear()
            try:
                threading.Thread(target=self._candidate_operation,
                                 args=(draft, candidate, record), daemon=True).start()
            except Exception:
                del self.drafts[draft.identifier]
                self.slot.release()
                self.idle.set()
                raise AdminError(503, 'The candidate worker is unavailable.') from None
            return self._view(draft)

    @staticmethod
    def _version(value):
        if type(value) is not str or re.fullmatch(r'[0-9a-f]{64}', value) is None:
            raise AdminError(400, 'Provide the current version from the administrator preview.')

    @classmethod
    def _candidate_identity(cls, identifier, version):
        cls._version(version)
        if type(identifier) is not str or re.fullmatch(r'[A-Za-z0-9_-]{43}', identifier) is None:
            raise AdminError(400, 'Provide a candidate ID from the administrator cache.')

    @contextmanager
    def _candidate_guard(self, draft):
        # Recheck lifetime/owner after solver or provider work and SQLite waits.
        with self.lock:
            current = self._owned(draft.principal, draft.identifier, 0)
            if current is not draft or draft.state not in ('reviewing', 'approving'):
                raise AdminError(409, 'This candidate operation is no longer active.')
            with self.auth.guard(draft.principal):
                yield

    def _candidate_operation(self, draft, candidate, record):
        try:
            with self.lock:
                self._owned(draft.principal, draft.identifier, 0)
                with self._snapshot_guard():
                    current = candidate_store.read_candidate(self.portal.root, self.portal.snapshot,
                                                              candidate['id'], candidate['candidateVersion'])
                    if (current['state'] != 'pending' or current['stale']
                            or current['exerciseVersion'] != exercise_version(record)):
                        raise AdminError(409, 'This candidate changed before its operation started.')
            if draft.kind == 'review':
                context = dict(exerciseId=record['id'], exerciseVersion=exercise_version(record),
                               candidateHash=candidate['candidateHash'], predicate=record['predicate'],
                               question=record['description'], environmentBefore=record['environmentBefore'],
                               environmentAfter=record['environmentAfter'], predicateHeader=record['predicateHeader'],
                               candidateBody=candidate['body'], oracleBodies=[record['oracleBody']],
                               boundedCheck=candidate['behavioralEvidence'])
                result = candidate_review.review(self.portal.root, context)
                with self.lock, self._snapshot_guard():
                    with self._candidate_guard(draft):
                        saved = candidate_store.save_review(self.portal.root, self.portal.snapshot,
                            candidate['id'], candidate['candidateVersion'], result,
                            guard=lambda:self._candidate_guard(draft))
                    draft.result = saved
                    draft.message = ('Review the advice and decide whether to approve or dismiss.'
                                     if result['status'] == 'ok' else
                                     'AI review is unavailable. You can still approve through the Alloy check.')
            else:
                certificate = prepare_approval(self.portal.root, record, candidate['body'],
                                               java=self.portal.java, timeout=60)
                with self.lock, self._snapshot_guard():
                    snapshot = candidate_store.approve(self.portal.root, candidate['id'],
                        candidate['candidateVersion'], candidate['exerciseVersion'], certificate,
                        guard=lambda:self._candidate_guard(draft))
                    self.portal.snapshot = snapshot
                    draft.result = {'status':'approved', 'exerciseId':record['id'],
                                    'exerciseCount':snapshot.exercise_count}
                    draft.message = 'Approved after the bounded Alloy check. Future hints can match this solution.'
            with self.lock:
                draft.state, draft.revision = 'completed', 1
        except Exception:
            with self.lock:
                self._cleanup()
                if self.drafts.get(draft.identifier) is draft:
                    draft.state, draft.revision = 'rejected', draft.revision + 1
                    draft.result = None
                    draft.message = 'The candidate could not be processed. Refresh it; check agreement, session lifetime and service availability.'
        finally:
            with self.lock:
                self.slot.release()
                self.idle.set()
