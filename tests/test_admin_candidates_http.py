"""New library/cache endpoints through the production HTTP authorization boundary.

These focused tests reuse HTTP fixture helpers without inheriting old tests.
Certificates and provider advice are synthetic; no Java or paid calls occur.
"""
from copy import deepcopy
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import admin_auth as auth
import admin_service
import candidate_store as cache
import exercise_store as store
import server
import test_admin_http as legacy
import test_admin_candidates as cache_fixtures
from test_sqlite_store import ROOT, fixture


class AdminCandidatesHTTPTests(unittest.TestCase):
    setUpClass = classmethod(legacy.AdminHTTPTests.setUpClass.__func__)
    save = legacy.AdminHTTPTests.save
    headers = legacy.AdminHTTPTests.headers
    capture = legacy.AdminHTTPTests.capture
    request = legacy.AdminHTTPTests.request
    raw = legacy.AdminHTTPTests.raw
    sign_in = legacy.AdminHTTPTests.sign_in

    def setUp(self):
        scratch = ROOT / 'build/admin-features/tmp'
        scratch.mkdir(parents=True, exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='http-', dir=scratch)
        self.root = Path(self.temporary.name)
        fixture(self.root)
        self.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot', root=self.root,
                                 admin_networks=('127.0.0.0/8',))
        self.origin = 'http://127.0.0.1:' + str(self.app.server_port)
        self.config = deepcopy(self.configuration)
        self.config['origin'] = self.origin
        self.save()
        self.now = [1000.0]
        self.app.admin_auth = auth.AuthManager(self.root, clock=lambda: self.now[0])
        self.app.admin = admin_service.AdminService(self.app, self.app.admin_auth, clock=lambda: self.now[0])
        self.thread = threading.Thread(target=lambda: self.app.serve_forever(poll_interval=.01), daemon=True)
        self.thread.start()
        self.jar, self.csrf = {}, None
        self.record = next(iter(self.app.snapshot.exercises.values()))
        self.body = 'no n: Node | n in n.adj'

    def tearDown(self):
        self.app.shutdown()
        self.app.server_close()
        self.thread.join(2)
        self.temporary.cleanup()

    def candidate(self):
        value = cache.capture(self.root, self.record, store.exercise_version(self.record),
                              self.app.snapshot.pool_tokens[self.record['id']], self.body,
                              cache_fixtures.perfect())
        self.assertIsNotNone(value)
        return value

    def question(self):
        status, value, _ = self.request('POST', '/api/admin/questions/detail', {'exerciseId': self.record['id']})
        self.assertEqual(status, 200)
        return value

    def wait_job(self, identifier):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            status, value, _ = self.request('GET', '/api/admin/drafts/' + identifier)
            self.assertEqual(status, 200)
            if value['state'] in ('completed', 'rejected'):
                return value
            time.sleep(.01)
        self.fail('Synthetic administrator operation did not terminate.')

    def payloads(self, candidate):
        identity = dict(id=candidate['id'], version=candidate['candidateVersion'])
        return {
            '/api/admin/library': {'offset': 0},
            '/api/admin/questions/detail': {'exerciseId': self.record['id']},
            '/api/admin/questions/edit': {'exerciseId': self.record['id'],
                'version': store.exercise_version(self.record), 'title': 'Title', 'question': 'Question'},
            '/api/admin/questions/remove': {'exerciseId': self.record['id'],
                'version': store.exercise_version(self.record), 'confirmation': self.record['id']},
            '/api/admin/candidates': {'offset': 0},
            **{f'/api/admin/candidates/{action}': dict(identity)
               for action in ('detail', 'review', 'approve', 'dismiss')}}

    def test_all_new_endpoints_authorize_before_body_provider_solver_or_write(self):
        candidate = self.candidate()
        before = (self.root / store.DATABASE_RELATIVE).read_bytes()
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('Unauthorized body read')) as body, \
                patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            for path, data in self.payloads(candidate).items():
                with self.subTest(path=path):
                    status, result, headers = self.request('POST', path, data)
                    self.assertEqual(status, 401)
                    self.assertEqual(headers['Cache-Control'], 'no-store, private')
                    self.assertNotIn(self.body, json.dumps(result))
        body.assert_not_called()
        provider.assert_not_called()
        solver.assert_not_called()
        self.assertEqual(before, (self.root / store.DATABASE_RELATIVE).read_bytes())

    def test_all_new_endpoints_require_csrf_and_exact_origin_before_work(self):
        self.sign_in()
        candidate = self.candidate()
        with patch.object(server.Handler, 'admin_body', side_effect=AssertionError('Invalid-origin body read')) as body, \
                patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            for path, data in self.payloads(candidate).items():
                for change in ({'Origin': 'https://other.invalid'}, {'X-CSRF-Token': 'invalid'}):
                    headers = dict(self.headers(), **change)
                    with self.subTest(path=path, change=list(change)):
                        self.assertEqual(self.request('POST', path, data, headers=headers)[0], 403)
        body.assert_not_called()
        provider.assert_not_called()
        solver.assert_not_called()

    def test_exact_json_schemas_reject_extra_missing_duplicate_and_wrong_page_types(self):
        self.sign_in()
        candidate = self.candidate()
        before = (self.root / store.DATABASE_RELATIVE).read_bytes()
        with patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            for path, data in self.payloads(candidate).items():
                variants = [dict(data, body='client replacement', certificate={'trusted': True}),
                            {key: value for key, value in data.items() if key != next(iter(data))}]
                for value in variants:
                    with self.subTest(path=path, kind='field set'):
                        self.assertEqual(self.request('POST', path, value)[0], 400)
                key = next(iter(data))
                duplicate = json.dumps(data)[:-1] + ',' + json.dumps(key) + ':' + json.dumps(data[key]) + '}'
                with self.subTest(path=path, kind='duplicate'):
                    self.assertEqual(self.request('POST', path, body=duplicate.encode())[0], 400)
            for path in ('/api/admin/library', '/api/admin/candidates'):
                for offset in (-1, True, '0', None, 0.5):
                    with self.subTest(path=path, offset=offset):
                        self.assertEqual(self.request('POST', path, {'offset': offset})[0], 400)
        provider.assert_not_called()
        solver.assert_not_called()
        self.assertEqual(before, (self.root / store.DATABASE_RELATIVE).read_bytes())

    def test_private_library_and_cache_reads_return_versions_without_public_leak_or_paid_calls(self):
        self.sign_in()
        candidate = self.candidate()
        with patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            status, library, headers = self.request('POST', '/api/admin/library', {'offset': 0})
            self.assertEqual(status, 200)
            self.assertEqual(headers['Cache-Control'], 'no-store, private')
            self.assertIsNone(headers.get('ETag'))
            self.assertEqual(library['limit'], 50)
            item = library['items'][0]
            self.assertEqual(item['exerciseVersion'], store.exercise_version(self.record))
            self.assertEqual(item['contentVersion'], store.content_version(self.record))
            self.assertNotIn('oracleBody', json.dumps(library))
            status, candidates, headers = self.request('POST', '/api/admin/candidates', {'offset': 0})
            self.assertEqual(status, 200)
            self.assertEqual(headers['Cache-Control'], 'no-store, private')
            self.assertEqual(candidates['limit'], 25)
            self.assertNotIn('body', candidates['items'][0])
            self.assertNotIn(self.body, json.dumps(candidates))
            for _ in range(2):
                status, detail, _ = self.request('POST', '/api/admin/candidates/detail',
                    {'id': candidate['id'], 'version': candidate['candidateVersion']})
                self.assertEqual(status, 200)
                self.assertEqual(detail['body'], self.body)
                self.assertEqual(detail['candidateVersion'], candidate['candidateVersion'])
        provider.assert_not_called()
        solver.assert_not_called()
        status, public, _ = self.request('GET', '/api/exercises')
        self.assertEqual(status, 200)
        self.assertNotIn(self.body, json.dumps(public))
        for private in ('candidateHash', 'candidateVersion', 'behavioralEvidence', 'review', 'oracleBody', 'exerciseVersion'):
            for item in public['exercises']:
                self.assertNotIn(private, item)

    def test_question_edit_requires_current_version_preserves_private_code_then_removal_needs_confirmation(self):
        self.sign_in()
        original_pool = self.app.snapshot.correct_pools
        question = self.question()
        payload = dict(exerciseId=self.record['id'], version=question['exerciseVersion'],
                       title="New title'); DELETE FROM exercises; --", question='A revised question.')
        status, result, _ = self.request('POST', '/api/admin/questions/edit', payload)
        self.assertEqual(status, 200)
        self.assertEqual(result['status'], 'committed')
        current = self.app.snapshot.exercises[self.record['id']]
        self.assertEqual(current['title'], payload['title'])
        self.assertEqual(current['description'], payload['question'])
        for key in self.record:
            if key not in ('title', 'description'):
                self.assertEqual(current[key], self.record[key], key)
        self.assertEqual(self.app.snapshot.correct_pools, original_pool)
        changed = self.question()
        self.assertNotEqual(question['contentVersion'], changed['contentVersion'])
        self.assertNotEqual(question['exerciseVersion'], changed['exerciseVersion'])
        self.assertEqual(self.request('POST', '/api/admin/questions/edit', payload)[0], 409)
        remove = dict(exerciseId=self.record['id'], version=changed['exerciseVersion'], confirmation='wrong')
        self.assertEqual(self.request('POST', '/api/admin/questions/remove', remove)[0], 400)
        remove['confirmation'] = self.record['id']
        status, result, _ = self.request('POST', '/api/admin/questions/remove', remove)
        self.assertEqual(status, 200)
        self.assertEqual(result['exerciseCount'], 0)
        self.assertEqual(self.request('GET', '/api/exercises')[1]['exercises'], [])
        self.assertIn(self.record['id'], store.load_store(self.root).raw_exercises)

    def test_null_and_wrong_version_types_cannot_bypass_fresh_echo_or_start_work(self):
        self.sign_in()
        candidate = self.candidate()
        before = (self.root / store.DATABASE_RELATIVE).read_bytes()
        with patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            for path, value in self.payloads(candidate).items():
                if 'version' not in value:
                    continue
                for version in (None, True, 1, 1.0, '', 'wrong'):
                    payload = dict(value, version=version)
                    with self.subTest(path=path, version=version):
                        self.assertIn(self.request('POST', path, payload)[0], (400, 409))
        provider.assert_not_called()
        solver.assert_not_called()
        self.assertEqual(before, (self.root / store.DATABASE_RELATIVE).read_bytes())

    def test_explicit_sol_review_binds_context_and_refreshes_version_without_auto_approval(self):
        self.sign_in()
        candidate = self.candidate()
        advice = dict(exerciseId=candidate['exerciseId'], exerciseVersion=candidate['exerciseVersion'],
            candidateHash=candidate['candidateHash'], verdict='recommend',
            reason='This alternative appears suitable for the separate Alloy check.', counterexampleIdeas=[])
        with patch.object(admin_service.candidate_review, 'review', return_value={'status': 'ok', 'advice': advice}) as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            status, job, _ = self.request('POST', '/api/admin/candidates/review',
                {'id': candidate['id'], 'version': candidate['candidateVersion']})
            self.assertEqual(status, 202)
            finished = self.wait_job(job['id'])
            self.assertEqual(finished['state'], 'completed')
            saved = finished['result']
            self.assertEqual(saved['state'], 'pending')
            self.assertEqual(saved['review']['advice'], advice)
            self.assertNotEqual(saved['candidateVersion'], candidate['candidateVersion'])
            context = provider.call_args.args[1]
            self.assertEqual(context['candidateBody'], self.body)
            self.assertEqual(context['oracleBodies'], [self.record['oracleBody']])
            self.assertEqual(context['exerciseVersion'], candidate['exerciseVersion'])
            self.assertEqual(context['candidateHash'], candidate['candidateHash'])
            self.assertEqual(context['boundedCheck']['score'], 1)
            self.assertEqual(self.request('POST', '/api/admin/candidates/detail',
                {'id': candidate['id'], 'version': candidate['candidateVersion']})[0], 400)
            for _ in range(2):
                self.assertEqual(self.request('POST', '/api/admin/candidates/detail',
                    {'id': candidate['id'], 'version': saved['candidateVersion']})[0], 200)
        self.assertEqual(provider.call_count, 1)
        solver.assert_not_called()
        self.assertNotIn(self.body, self.app.snapshot.correct_pools[self.record['id']])

    def test_administrator_can_approve_after_ai_rejection_but_only_exact_server_body_passes_validator(self):
        self.sign_in()
        candidate = self.candidate()
        advice = dict(exerciseId=candidate['exerciseId'], exerciseVersion=candidate['exerciseVersion'],
            candidateHash=candidate['candidateHash'], verdict='reject', reason='Inspect a larger graph.',
            counterexampleIdeas=['Try four nodes joined in a cycle and compare acceptance.'])
        with patch.object(admin_service.candidate_review, 'review', return_value={'status': 'ok', 'advice': advice}):
            status, job, _ = self.request('POST', '/api/admin/candidates/review',
                {'id': candidate['id'], 'version': candidate['candidateVersion']})
            self.assertEqual(status, 202)
            current = self.wait_job(job['id'])['result']
            self.assertEqual(self.request('POST', '/api/admin/discard', {'id': job['id']})[0], 200)
        certificate = cache_fixtures.certificate(self.record, self.body)
        with patch.object(admin_service, 'prepare_approval', return_value=certificate) as validator, \
                patch.object(admin_service.candidate_review, 'review') as provider:
            status, job, _ = self.request('POST', '/api/admin/candidates/approve',
                {'id': candidate['id'], 'version': current['candidateVersion']})
            self.assertEqual(status, 202)
            finished = self.wait_job(job['id'])
            self.assertEqual(finished['state'], 'completed')
            self.assertEqual(finished['result']['status'], 'approved')
        self.assertEqual(validator.call_args.args[1:3], (self.record, self.body))
        provider.assert_not_called()
        self.assertIn(self.body, self.app.snapshot.correct_pools[self.record['id']])
        self.assertEqual(self.app.snapshot.exercises[self.record['id']]['oracleBody'], self.record['oracleBody'])
        latest = cache.read_candidate(self.root, self.app.snapshot, candidate['id'])
        self.assertEqual(latest['state'], 'approved')
        self.assertEqual(self.request('POST', '/api/admin/candidates/approve',
            {'id': candidate['id'], 'version': latest['candidateVersion']})[0], 409)

    def test_dismiss_is_terminal_and_question_edit_or_removal_invalidates_pending_candidates(self):
        self.sign_in()
        candidate = self.candidate()
        identity = dict(id=candidate['id'], version=candidate['candidateVersion'])
        status, result, _ = self.request('POST', '/api/admin/candidates/dismiss', identity)
        self.assertEqual(status, 200)
        self.assertEqual(result['candidate']['state'], 'dismissed')
        latest = dict(id=candidate['id'], version=result['candidate']['candidateVersion'])
        self.assertEqual(self.request('POST', '/api/admin/candidates/dismiss', latest)[0], 400)
        second_body = 'all n: Node | n not in n.adj'
        second = cache.capture(self.root, self.record, store.exercise_version(self.record),
                              self.app.snapshot.pool_tokens[self.record['id']], second_body,
                              cache_fixtures.perfect())
        self.assertIsNotNone(second)
        status, _, _ = self.request('POST', '/api/admin/questions/edit', dict(exerciseId=self.record['id'],
            version=store.exercise_version(self.record), title='Changed', question='Changed wording.'))
        self.assertEqual(status, 200)
        with patch.object(admin_service.candidate_review, 'review') as provider, \
                patch.object(admin_service, 'prepare_approval') as solver:
            for action in ('review', 'approve'):
                self.assertEqual(self.request('POST', '/api/admin/candidates/' + action,
                    dict(id=second['id'], version=second['candidateVersion']))[0], 409)
        provider.assert_not_called()
        solver.assert_not_called()


if __name__ == '__main__':
    unittest.main()
