"""Constructed stale-job, authority and candidate-capture witnesses."""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import admin_service as service
import server
from exercise_store import exercise_version, StoreError
import test_admin_service as fixtures
QueuedThread = fixtures.QueuedThread


class CandidateServiceTests(unittest.TestCase):
    setUpClass = classmethod(fixtures.AdminServiceTests.setUpClass.__func__)
    save = fixtures.AdminServiceTests.save
    sign_in = fixtures.AdminServiceTests.sign_in
    denied = fixtures.AdminServiceTests.denied

    def setUp(self):
        scratch = Path(__file__).resolve().parents[1] / 'build/admin-features/tmp'
        scratch.mkdir(parents=True, exist_ok=True)
        original_temporary = tempfile.TemporaryDirectory
        with patch.object(fixtures.tempfile, 'TemporaryDirectory',
                          side_effect=lambda:original_temporary(dir=scratch)):
            fixtures.AdminServiceTests.setUp(self)
        self.record = dict(id='example-inv1', title='An exercise', group='example', predicate='inv1',
            description='Require at least one node.', environmentBefore='sig Node {}\n',
            environmentAfter='', predicateHeader='pred inv1 ', starter='no Node',
            oracleBody='some Node', source={})
        self.portal.snapshot_lock = threading.RLock()
        self.portal.snapshot = SimpleNamespace(exercises={self.record['id']:self.record}, exercise_count=1)
        self.candidate = dict(id='c'*43, exerciseId=self.record['id'],
            exerciseVersion=exercise_version(self.record), candidateHash='a'*64,
            candidateVersion='b'*64, state='pending', stale=False, body='not no Node',
            behavioralEvidence={'score':1.0}, review=None)
        self.read_patch = patch.object(service.candidate_store, 'read_candidate',
                                      side_effect=lambda *a, **k:deepcopy(self.candidate))
        self.reader = self.read_patch.start()
        self.addCleanup(self.read_patch.stop)

    def start(self, action='review'):
        return self.service.start_candidate(self.principal, self.candidate['id'],
                                            self.candidate['candidateVersion'], action)

    def test_viewing_candidate_never_calls_provider_or_solver(self):
        with patch.object(service.candidate_review, 'review') as provider, \
                patch.object(service, 'prepare_approval') as solver:
            detail = self.service.candidate(self.principal, self.candidate['id'], self.candidate['candidateVersion'])
        self.assertEqual(detail['body'], 'not no Node')
        provider.assert_not_called()
        solver.assert_not_called()

    def test_candidate_endpoints_require_a_real_current_version(self):
        for version in (None, True, 1, {}, [], '', 'b'*63, 'B'*64):
            for action in ('review', 'approve'):
                self.denied(400, lambda:self.service.start_candidate(self.principal,
                    self.candidate['id'], version, action))
            self.denied(400, lambda:self.service.candidate(self.principal, self.candidate['id'], version))
            self.denied(400, lambda:self.service.dismiss_candidate(self.principal, self.candidate['id'], version))
        self.reader.assert_not_called()
        self.assertEqual(QueuedThread.pending, [])

    def test_explicit_review_has_owner_bound_job_and_immutable_context(self):
        job = self.start()
        self.assertEqual(job['state'], 'reviewing')
        self.assertNotIn('not no Node', json.dumps(job))
        outsider = self.sign_in()
        self.denied(404, lambda:self.service.view(outsider, job['id']))
        self.denied(429, lambda:self.service.start_candidate(outsider, self.candidate['id'], 'b'*64, 'approve'))
        with patch.object(service.candidate_review, 'review', return_value={'status':'unavailable'}) as provider, \
                patch.object(service.candidate_store, 'save_review', return_value=self.candidate) as save:
            QueuedThread.run()
        context = provider.call_args.args[1]
        self.assertEqual(context['candidateBody'], self.candidate['body'])
        self.assertEqual(context['oracleBodies'], ['some Node'])
        self.assertEqual(context['question'], self.record['description'])
        self.assertEqual(context['exerciseVersion'], self.candidate['exerciseVersion'])
        self.assertEqual(context['environmentBefore'], self.record['environmentBefore'])
        save.assert_called_once()
        self.assertEqual(self.service.view(self.principal, job['id'])['state'], 'completed')

    def test_revoked_or_discarded_queued_jobs_start_no_expensive_work(self):
        for action in ('review', 'approve'):
            job = self.start(action)
            self.service.discard(self.principal, job['id'])
            with patch.object(service.candidate_review, 'review') as provider, \
                    patch.object(service, 'prepare_approval') as solver:
                QueuedThread.run()
            provider.assert_not_called()
            solver.assert_not_called()
        job = self.start()
        self.auth.logout(self.principal)
        with patch.object(service.candidate_review, 'review') as provider:
            QueuedThread.run()
        provider.assert_not_called()
        self.assertNotIn(job['id'], self.service.drafts)

    def test_revocation_during_provider_prevents_advice_persistence(self):
        self.start()
        def revoked(*args):
            self.auth.logout(self.principal)
            return {'status':'unavailable'}
        with patch.object(service.candidate_review, 'review', side_effect=revoked), \
                patch.object(service.candidate_store, 'save_review') as save:
            QueuedThread.run()
        save.assert_not_called()
        self.assertEqual(self.service.drafts, {})

    def test_deadline_crossed_during_solver_prevents_publication(self):
        original = self.portal.snapshot
        self.start('approve')
        def expired(*args, **kwargs):
            self.now[0] += 800
            self.auth.touch(self.principal)
            self.now[0] += 100
            return {'privateCertificate':'synthetic'}
        def guarded(*args, **kwargs):
            with kwargs['guard']():
                self.fail('Expired job reached its commit point')
        with patch.object(service, 'prepare_approval', side_effect=expired), \
                patch.object(service.candidate_store, 'approve', side_effect=guarded):
            QueuedThread.run()
        self.assertIs(self.portal.snapshot, original)
        self.assertEqual(self.service.drafts, {})

    def test_approval_always_checks_exact_candidate_then_publishes_once(self):
        job = self.start('approve')
        published = SimpleNamespace(exercise_count=1)
        certificate = {'privateCertificate':'synthetic'}
        def guarded(*args, **kwargs):
            self.assertEqual(args[1:5], (self.candidate['id'], 'b'*64,
                                         self.candidate['exerciseVersion'], certificate))
            with kwargs['guard']():
                return published
        with patch.object(service, 'prepare_approval', return_value=certificate) as validator, \
                patch.object(service.candidate_store, 'approve', side_effect=guarded), \
                patch.object(service.candidate_review, 'review') as provider:
            QueuedThread.run()
        self.assertEqual(validator.call_args.args[1:3], (self.record, self.candidate['body']))
        self.assertEqual(validator.call_args.kwargs, {'java':'java', 'timeout':60})
        provider.assert_not_called()
        self.assertIs(self.portal.snapshot, published)
        self.assertEqual(self.service.view(self.principal, job['id'])['result']['status'], 'approved')

    def test_failed_or_stale_candidate_job_is_sanitized_and_slot_reusable(self):
        job = self.start()
        with patch.object(service.candidate_review, 'review', side_effect=StoreError('PRIVATE_BODY_OR_TOKEN')):
            QueuedThread.run()
        result = self.service.view(self.principal, job['id'])
        self.assertEqual(result['state'], 'rejected')
        self.assertNotIn('PRIVATE_BODY_OR_TOKEN', json.dumps(result))
        self.start('approve')

    def test_terminal_and_stale_candidates_allocate_no_worker(self):
        for changes in ({'state':'approved'}, {'state':'dismissed'}, {'stale':True}):
            original = deepcopy(self.candidate)
            self.candidate.update(changes)
            self.denied(409, self.start)
            self.assertEqual(QueuedThread.pending, [])
            self.candidate = original

    def test_question_changed_before_queued_review_starts_no_paid_call(self):
        job = self.start()
        self.candidate['stale'] = True
        with patch.object(service.candidate_review, 'review') as provider:
            QueuedThread.run()
        provider.assert_not_called()
        self.assertEqual(self.service.view(self.principal, job['id'])['state'], 'rejected')

    def test_metadata_edit_guard_revocation_and_snapshot_swap(self):
        original = self.portal.snapshot
        def guarded(*args, **kwargs):
            self.auth.logout(self.principal)
            with kwargs['guard']():
                self.fail('Revoked edit committed')
        with patch.object(service, 'edit_question', side_effect=guarded):
            self.denied(401, lambda:self.service.edit_question(self.principal,
                self.record['id'], exercise_version(self.record), 'Title', 'Question'))
        self.assertIs(self.portal.snapshot, original)


class CaptureTests(unittest.TestCase):
    def setUp(self):
        self.record = dict(id='e', title='E', group='g', predicate='inv1', description='Q',
            environmentBefore='', environmentAfter='', predicateHeader='pred inv1 ',
            starter='no none', oracleBody='some none', source={})
        self.portal = SimpleNamespace(stopping=False, generation='current',
            admin_auth=SimpleNamespace(lock=threading.RLock(), settings=object()),
            snapshot_lock=threading.RLock(), root='synthetic-root',
            _snapshot=SimpleNamespace(exercises={'e':self.record}, pool_tokens={'e':{'known'}}))

    def capture(self, generation='current'):
        return server.Portal._capture_candidate(self.portal, self.record, 'some none', {'status':'ok'}, generation)

    def test_capture_skips_stale_generations_and_removed_questions(self):
        with patch.object(server.candidate_store, 'capture') as capture:
            self.capture('old')
            self.portal._snapshot.exercises = {}
            self.capture()
        capture.assert_not_called()

    def test_unconfigured_administrator_does_not_retain_drafts(self):
        self.portal.admin_auth.settings = None
        with patch.object(server.candidate_store, 'capture') as capture:
            self.capture()
        capture.assert_not_called()

    def test_capture_uses_current_pool_and_failure_never_replaces_feedback(self):
        with patch.object(server.candidate_store, 'capture', side_effect=StoreError('private')) as capture:
            self.assertIsNone(self.capture())
        self.assertEqual(capture.call_args.args[3], {'known'})
        self.assertTrue(self.portal.snapshot_lock.acquire(blocking=False))
        self.portal.snapshot_lock.release()

    def test_capture_does_not_wait_behind_publication(self):
        acquired, release = threading.Event(), threading.Event()
        def publication():
            with self.portal.snapshot_lock:
                acquired.set()
                release.wait(2)
        worker = threading.Thread(target=publication)
        worker.start()
        self.assertTrue(acquired.wait(1))
        try:
            with patch.object(server.candidate_store, 'capture') as capture:
                self.capture()
            capture.assert_not_called()
        finally:
            release.set()
            worker.join(2)


if __name__ == '__main__':
    unittest.main()
