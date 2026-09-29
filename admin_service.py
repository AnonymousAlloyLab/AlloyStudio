"""Bounded, owner-bound upload jobs and atomic administrative publication."""
from copy import deepcopy
from dataclasses import dataclass, field
import secrets
import threading
import time

from admin_auth import AuthError
from admin_luna import suggest
from admin_upload import prepare_upload
from exercise_store import StoreError, commit_upload, text

DRAFT_SECONDS = 900
MAX_DRAFTS = 8
MAX_OWNER_DRAFTS = 2


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


class AdminService:
    def __init__(self, portal, auth, *, clock=time.monotonic):
        self.portal, self.auth, self.clock = portal, auth, clock
        self.lock = threading.RLock()
        self.slot = threading.BoundedSemaphore(1)
        self.drafts = {}

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

    def _owned(self, principal, identifier, revision=None):
        self._cleanup()
        draft = self.drafts.get(identifier) if type(identifier) is str else None
        if (draft is None or draft.principal.owner != principal.owner
                or draft.principal.generation != principal.generation):
            raise AdminError(404,'Draft not found or expired.')
        if revision is not None and (type(revision) is not int or revision != draft.revision):
            raise AdminError(409,'This preview changed. Refresh it before continuing.')
        return draft

    @staticmethod
    def _view(draft):
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
            self._cleanup()
            if (len(self.drafts) >= MAX_DRAFTS
                    or sum(item.principal.owner == principal.owner for item in self.drafts.values()) >= MAX_OWNER_DRAFTS):
                raise AdminError(429,'Discard an earlier draft before uploading another model.')
            if not self.slot.acquire(blocking=False):
                raise AdminError(429,'An upload operation is running. Try again shortly.')
            draft = Draft(secrets.token_urlsafe(32),principal,self.clock())
            self.drafts[draft.identifier] = draft
            try:
                threading.Thread(target=self._prepare,args=(draft,envelope),daemon=True).start()
            except Exception:
                del self.drafts[draft.identifier]
                self.slot.release()
                raise AdminError(503,'The upload worker is unavailable.') from None
            return self._view(draft)

    def _prepare(self, draft, envelope):
        try:
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
                if self.drafts.get(draft.identifier) is draft:
                    draft.state, draft.revision = 'rejected',draft.revision+1
                    draft.message = (str(error)[:300] if isinstance(error,StoreError)
                                     else 'The upload could not be validated. Check the format guide and try again.')
        finally:
            self.slot.release()

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
            try:
                threading.Thread(target=self._suggest,args=(draft,seed),daemon=True).start()
            except Exception:
                draft.state = 'ready'
                self.slot.release()
                raise AdminError(503,'The suggestion worker is unavailable.') from None
            return self._view(draft)

    def _suggest(self, draft, seed):
        try:
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
                if self.drafts.get(draft.identifier) is draft:
                    draft.suggestion_status = 'unavailable'
                    draft.state, draft.revision = 'ready',draft.revision+1
                    draft.message = 'Suggestions are unavailable. Enter and review the questions yourself.'
        finally:
            self.slot.release()

    def commit(self, principal, identifier, revision, metadata):
        with self.lock:
            draft = self._owned(principal,identifier,revision)
            if draft.state != 'ready' or draft.prepared is None:
                raise AdminError(409,'This draft is not ready to publish or has already been published.')
            snapshot = commit_upload(self.portal.root,draft.prepared,metadata,
                                     guard=lambda:self.auth.guard(principal))
            # A single pointer publishes a fully validated, committed generation.
            self.portal.snapshot = snapshot
            identifiers = [item['id'] for item in draft.prepared['documents']]
            draft.prepared = None
            draft.state, draft.revision = 'committed',draft.revision+1
            draft.message = 'Published. The exercises are available in Alloy Studio.'
            return {'status':'committed','exerciseIds':identifiers,'exerciseCount':snapshot.exercise_count}
