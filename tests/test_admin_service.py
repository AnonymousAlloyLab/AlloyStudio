"""Constructed owner, capacity, revocation and publication-race witnesses."""
from copy import deepcopy
from email.message import Message
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import admin_auth as auth
import admin_service as service

PASSWORD = 'synthetic-admin-service-password'
PEER = ('127.0.0.1', 40000)
ENVELOPE = {'source': 'sig Node {} pred inv1C0 { some Node }',
            'filename': 'model.als', 'modelId': 'model'}
PREPARED = {'documents': [{'id': 'model-inv1', 'predicate': 'inv1', 'title': 'Exercise',
                           'description': 'Review the property.', 'oracleSolutions': ['some Node']}],
            'witness': {'filename': 'model.als', 'sourceSha256': 'a' * 64,
                        'originalSource': ENVELOPE['source'],
                        'groups': [{'variants': [{'name': 'inv1C0'}]}]}}


class QueuedThread:
    pending = []

    def __init__(self, *, target, args, daemon):
        self.target, self.args = target, args

    def start(self):
        self.pending.append(self)

    @classmethod
    def run(cls):
        item = cls.pending.pop(0)
        item.target(*item.args)


class AdminServiceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.configuration = auth.configuration('http://127.0.0.1:8080', '/', PASSWORD)

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.config = deepcopy(self.configuration)
        self.save()
        self.now = [1000.0]
        self.auth = auth.AuthManager(self.root, clock=lambda: self.now[0])
        self.portal = SimpleNamespace(root=self.root, java='java', snapshot='original snapshot')
        self.service = service.AdminService(self.portal, self.auth, clock=lambda: self.now[0])
        QueuedThread.pending = []
        self.thread_patch = patch.object(service.threading, 'Thread', QueuedThread)
        self.thread_patch.start()
        self.addCleanup(self.thread_patch.stop)
        self.principal = self.sign_in()

    def save(self):
        path = self.root / auth.CONFIG_NAME
        path.write_text(json.dumps(self.config))
        path.chmod(0o600)

    def sign_in(self):
        headers = Message()
        headers['Host'] = '127.0.0.1:8080'
        headers['Origin'] = self.config['origin']
        state, cookies = self.auth.bootstrap(headers, PEER)
        headers['Cookie'] = cookies[0].split(';', 1)[0]
        headers['X-CSRF-Token'] = state['csrfToken']
        principal = self.auth.authorize(headers, PEER, preauth=True)
        with patch.object(auth, 'derive_password', return_value=bytes.fromhex(self.config['password']['hash'])):
            state, cookies = self.auth.login(principal, PASSWORD)
        del headers['Cookie']
        headers['Cookie'] = next(value.split(';', 1)[0] for value in cookies if 'Max-Age=1800' in value)
        headers.replace_header('X-CSRF-Token', state['csrfToken'])
        return self.auth.authorize(headers, PEER)

    def ready(self, principal=None):
        principal = principal or self.principal
        pending = self.service.prepare(principal, ENVELOPE)
        with patch.object(service, 'prepare_upload', return_value=deepcopy(PREPARED)):
            QueuedThread.run()
        return self.service.view(principal, pending['id'])

    def denied(self, status, operation):
        with self.assertRaises((service.AdminError, auth.AuthError)) as raised:
            operation()
        self.assertEqual(raised.exception.status, status)

    def test_owner_revision_and_private_projection(self):
        ready = self.ready()
        outsider = self.sign_in()
        self.denied(404, lambda: self.service.view(outsider, ready['id']))
        self.denied(404, lambda: self.service.discard(outsider, ready['id']))
        self.denied(404, lambda: self.service.commit(outsider, ready['id'], ready['revision'], []))
        self.denied(409, lambda: self.service.commit(self.principal, ready['id'], 0, []))
        for invalid in (None, True, 1.0, '1'):
            self.denied(409, lambda: self.service.request_suggestion(self.principal, ready['id'], invalid, ''))
            self.denied(409, lambda: self.service.commit(self.principal, ready['id'], invalid, []))
        self.assertNotIn('some Node', json.dumps(ready))
        for private in ('originalSource', 'prepared', 'documents', 'oracleSolutions'):
            self.assertNotIn(private, json.dumps(ready))
        ready['groups'][0]['title'] = 'Changed returned copy'
        self.assertEqual(self.service.view(self.principal, ready['id'])['groups'][0]['title'], 'Exercise')

    def test_failed_and_completed_jobs_count_toward_owner_and_global_caps(self):
        first = self.service.prepare(self.principal, ENVELOPE)
        with patch.object(service, 'prepare_upload', side_effect=RuntimeError('PRIVATE_SOURCE')):
            QueuedThread.run()
        rejected = self.service.view(self.principal, first['id'])
        self.assertEqual(rejected['state'], 'rejected')
        self.assertNotIn('PRIVATE_SOURCE', json.dumps(rejected))
        second = self.ready()
        with patch.object(service, 'commit_upload', return_value=SimpleNamespace(exercise_count=2)):
            self.service.commit(self.principal, second['id'], second['revision'], [])
        self.denied(429, lambda: self.service.prepare(self.principal, ENVELOPE))
        for _ in range(3):
            owner = self.sign_in()
            self.ready(owner)
            self.ready(owner)
        self.assertEqual(len(self.service.drafts), 8)
        fresh_owner = self.sign_in()
        self.denied(429, lambda: self.service.prepare(fresh_owner, ENVELOPE))
        self.service.discard(self.principal, first['id'])
        self.ready(fresh_owner)
        self.assertEqual(len(self.service.drafts), 8)

    def test_one_worker_covers_preparation_and_provider_and_releases_after_failure(self):
        waiting = self.service.prepare(self.principal, ENVELOPE)
        other = self.sign_in()
        self.denied(429, lambda: self.service.prepare(other, ENVELOPE))
        with patch.object(service, 'prepare_upload', side_effect=RuntimeError('fail')):
            QueuedThread.run()
        ready = self.ready(other)
        self.service.request_suggestion(other, ready['id'], ready['revision'], '')
        self.denied(429, lambda: self.service.prepare(self.principal, ENVELOPE))
        with patch.object(service, 'suggest', side_effect=RuntimeError('PRIVATE_PROVIDER')):
            QueuedThread.run()
        view = self.service.view(other, ready['id'])
        self.assertEqual(view['suggestionStatus'], 'unavailable')
        self.assertEqual(view['state'], 'ready')
        self.assertNotIn('PRIVATE_PROVIDER', json.dumps(view))
        self.service.discard(self.principal, waiting['id'])
        self.ready()

    def test_expired_and_revoked_drafts_are_discarded(self):
        ready = self.ready()
        self.now[0] += 800
        self.auth.touch(self.principal)
        self.now[0] += 100
        self.denied(404, lambda: self.service.view(self.principal, ready['id']))
        self.assertEqual(self.service.drafts, {})
        ready = self.ready()
        self.auth.logout(self.principal)
        self.denied(401, lambda: self.service.view(self.principal, ready['id']))
        replacement = self.sign_in()
        self.denied(404, lambda: self.service.view(replacement, ready['id']))
        self.assertEqual(self.service.drafts, {})

    def test_revoked_principal_cannot_create_or_start_preparation(self):
        self.auth.logout(self.principal)
        with patch.object(service, 'prepare_upload') as prepare:
            self.denied(401, lambda: self.service.prepare(self.principal, ENVELOPE))
            self.assertEqual(self.service.drafts, {})
            self.assertEqual(QueuedThread.pending, [])
            prepare.assert_not_called()

    def test_logout_before_queued_preparation_prevents_solver_start(self):
        self.service.prepare(self.principal, ENVELOPE)
        self.auth.logout(self.principal)
        with patch.object(service, 'prepare_upload') as prepare:
            QueuedThread.run()
        prepare.assert_not_called()
        self.assertEqual(self.service.drafts, {})
        self.assertTrue(self.service.slot.acquire(blocking=False))
        self.service.slot.release()

    def test_rotation_before_queued_suggestion_prevents_provider_start(self):
        ready = self.ready()
        self.service.request_suggestion(self.principal, ready['id'], ready['revision'], '')
        self.config['password']['salt'] = '22' * 16
        self.save()
        with patch.object(service, 'suggest') as suggest:
            QueuedThread.run()
        suggest.assert_not_called()
        self.assertEqual(self.service.drafts, {})

    def test_logout_during_preparation_discards_successful_late_result(self):
        self.service.prepare(self.principal, ENVELOPE)
        def prepare(*args, **kwargs):
            self.auth.logout(self.principal)
            return deepcopy(PREPARED)
        with patch.object(service, 'prepare_upload', side_effect=prepare):
            QueuedThread.run()
        self.assertEqual(self.service.drafts, {})
        self.assertEqual(self.portal.snapshot, 'original snapshot')

    def test_logout_during_suggestion_discards_late_metadata(self):
        ready = self.ready()
        self.service.request_suggestion(self.principal, ready['id'], ready['revision'], '')
        def suggest(*args, **kwargs):
            self.auth.logout(self.principal)
            return {'status': 'ok', 'exercises': [{'predicate': 'inv1', 'title': 'New', 'question': 'New question'}]}
        with patch.object(service, 'suggest', side_effect=suggest):
            QueuedThread.run()
        self.assertEqual(self.service.drafts, {})
        self.assertEqual(self.portal.snapshot, 'original snapshot')

    def test_final_guard_revocation_leaves_draft_and_snapshot_unpublished(self):
        ready = self.ready()
        def interrupted_commit(root, prepared, metadata, *, guard):
            self.config['password']['salt'] = '33' * 16
            self.save()
            with guard():
                self.fail('Revocation must stop publication')
        with patch.object(service, 'commit_upload', side_effect=interrupted_commit):
            self.denied(401, lambda: self.service.commit(self.principal, ready['id'], ready['revision'], []))
        self.assertEqual(self.portal.snapshot, 'original snapshot')
        draft = self.service.drafts[ready['id']]
        self.assertEqual(draft.state, 'ready')
        self.assertIsNotNone(draft.prepared)

    def test_draft_expiring_during_transaction_work_fails_final_publication_guard(self):
        ready = self.ready()
        self.now[0] += 800
        self.auth.touch(self.principal)
        def delayed_commit(root, prepared, metadata, *, guard):
            self.now[0] += 100
            with guard():
                self.fail('An expired draft must not publish even with a live session')
        with patch.object(service, 'commit_upload', side_effect=delayed_commit):
            self.denied(404, lambda: self.service.commit(self.principal, ready['id'], ready['revision'], []))
        self.auth.validate(self.principal)
        self.assertEqual(self.portal.snapshot, 'original snapshot')
        self.assertEqual(self.service.drafts, {})

    def test_successful_guarded_commit_publishes_one_snapshot_and_is_single_use(self):
        ready = self.ready()
        replacement = SimpleNamespace(exercise_count=2)
        def commit(root, prepared, metadata, *, guard):
            with guard():
                self.assertEqual(self.portal.snapshot, 'original snapshot')
            return replacement
        with patch.object(service, 'commit_upload', side_effect=commit) as writer:
            result = self.service.commit(self.principal, ready['id'], ready['revision'], [])
            self.assertEqual(result, {'status': 'committed', 'exerciseIds': ['model-inv1'], 'exerciseCount': 2})
            self.assertIs(self.portal.snapshot, replacement)
            committed = self.service.view(self.principal, ready['id'])
            self.assertEqual(committed['state'], 'committed')
            self.denied(409, lambda: self.service.commit(self.principal, ready['id'], committed['revision'], []))
            writer.assert_called_once()
        self.assertIsNone(self.service.drafts[ready['id']].prepared)

    def test_only_metadata_can_be_updated_by_provider(self):
        ready = self.ready()
        original = deepcopy(self.service.drafts[ready['id']].prepared)
        self.service.request_suggestion(self.principal, ready['id'], ready['revision'], '')
        with patch.object(service, 'suggest', return_value={'status': 'ok', 'exercises': [
                {'predicate': 'inv1', 'title': 'New title', 'question': 'New question'}]}):
            QueuedThread.run()
        after = self.service.view(self.principal, ready['id'])
        self.assertEqual(after['groups'][0]['title'], 'New title')
        self.assertEqual(self.service.drafts[ready['id']].prepared, original)
        self.service.request_suggestion(self.principal, ready['id'], after['revision'], '')
        with patch.object(service, 'suggest', return_value={'status': 'ok', 'exercises': [
                {'predicate': 'renamed', 'title': 'Wrong title', 'question': 'Wrong question'}]}):
            QueuedThread.run()
        after = self.service.view(self.principal, ready['id'])
        self.assertEqual(after['groups'][0]['title'], 'New title')
        self.assertEqual(after['suggestionStatus'], 'unavailable')
        self.assertEqual(self.service.drafts[ready['id']].prepared, original)

    def test_shutdown_rejects_admission_and_waits_for_pending_work(self):
        pending = self.service.prepare(self.principal, ENVELOPE)
        self.assertFalse(self.service.close(timeout=0))
        self.denied(503, lambda: self.service.prepare(self.principal, ENVELOPE))
        self.denied(503, lambda: self.service.view(self.principal, pending['id']))
        with patch.object(service, 'prepare_upload') as prepare:
            QueuedThread.run()
        prepare.assert_not_called()
        self.assertTrue(self.service.close(timeout=0))

    def test_shutdown_rejects_prepared_publication(self):
        ready = self.ready()
        self.assertTrue(self.service.close(timeout=0))
        with patch.object(service, 'commit_upload') as commit:
            self.denied(503, lambda: self.service.commit(self.principal, ready['id'], ready['revision'], []))
        commit.assert_not_called()


if __name__ == '__main__':
    unittest.main()
