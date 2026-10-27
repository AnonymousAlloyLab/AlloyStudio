"""HTTP chat context is server-owned, pinned and never starts another solve."""
import io
import json
import threading
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import ai_provider
import server
from tutor_chat import TutorChat
from test_education_http import behavior_evidence
from test_structural_guidance import feedback


class TutorHTTPTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = server.Portal(('127.0.0.1', 0), engine_mode='oneshot')
        cls.thread = threading.Thread(target=lambda: cls.app.serve_forever(poll_interval=.01), daemon=True)
        cls.thread.start()
        cls.url = f'http://127.0.0.1:{cls.app.server_port}'

    @classmethod
    def tearDownClass(cls):
        cls.app.shutdown()
        cls.app.server_close()
        cls.thread.join(2)

    def setUp(self):
        self.calls = []
        self.answer = {'reply': 'Compare the chosen multiplicity with what the question requires.'}
        self.hook = lambda: None
        def transport(request, timeout):
            self.calls.append(json.loads(request.data))
            self.hook()
            return io.BytesIO(json.dumps({'status': 'completed', 'output': [
                {'type': 'message', 'role': 'assistant', 'content': [
                    {'type': 'output_text', 'text': json.dumps(self.answer)}]}]}).encode())
        self.app.chat.close()
        self.app.chat = TutorChat(slots=self.app.explainer.slots, transport=transport)
        profile = ai_provider.Profile('openai', 'gpt-6-luna', 'PRIVATE_TEST_KEY', ai_provider.ENDPOINTS['openai'], 'low')
        config = patch('ai_provider.chat_profiles', return_value={'openai': profile})
        config.start(); self.addCleanup(config.stop)
        for name in ('evaluate', 'evaluate_behavior'):
            guard = patch.object(self.app, name, side_effect=AssertionError('Chat must not solve'))
            guard.start(); self.addCleanup(guard.stop)
        self.channel = self.post('/api/channel', {})[2]['channel']
        self.base = {'exerciseId': 'graphs-inv5', 'body': 'some A', 'revision': 1,
                     'metric': 'canonical', 'channel': self.channel, 'model': 'gpt-6-luna',
                     'effort': 'low', 'message': 'Help me understand this condition.'}

    def request(self, path, data=None, headers=None):
        request = Request(self.url + path,
                          data=json.dumps(data).encode() if data is not None else None,
                          headers={'Content-Type': 'application/json', **(headers or {})})
        try:
            response = urlopen(request, timeout=5)
        except HTTPError as error:
            response = error
        with response:
            content = response.read()
            return response.status, response.headers, json.loads(content)

    def post(self, path='/api/chat', data=None, headers=None):
        return self.request(path, self.base if data is None else data, headers)

    def pins(self, data=None):
        data = data or self.base
        snapshot, generation = self.app.capture()
        record, _ = self.app.resolve_practice(snapshot.exercises[data['exerciseId']], [], snapshot)
        identity = self.app.evidence_identity(record, data['body'], data['metric'], generation, data['channel'])
        raw = feedback()
        raw.update(oracleBody='PRIVATE_ORACLE_CANARY', referenceBodies=['PRIVATE_POOL_CANARY'])
        raw['operations'][0]['targetTerm'] = 'PRIVATE_TARGET_CANARY'
        evidence = self.app.evidence.pin(identity, raw)
        behavior = behavior_evidence()
        behavior['oracleSource'] = 'PRIVATE_INSTANCE_CANARY'
        self.app.evidence.pin(self.app.evidence_identity(record, data['body'], data['metric'],
                                                       generation, data['channel'], 'behavior'), behavior)
        return dict(data, evidenceToken=evidence,
                    behaviorToken=server.behavior_token(record['id'], data['body'], behavior))

    def test_catalogue_is_safe_and_no_store(self):
        code, headers, value = self.request('/api/chat/models')
        self.assertEqual(code, 200)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(len(value['models']), 3)
        self.assertNotIn('PRIVATE_', json.dumps(value))
        self.assertEqual(self.calls, [])

    def test_exact_pins_carry_crud_and_all_displayed_examples_without_oracles(self):
        code, headers, value = self.post(data=self.pins())
        self.assertEqual((code, value['status']), (200, 'ok'))
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual((value['exerciseId'], value['revision'], value['metric']), ('graphs-inv5', 1, 'canonical'))
        context = json.loads(self.calls[0]['input'])['context']
        self.assertEqual(context['question'], self.app.exercises['graphs-inv5']['description'])
        self.assertEqual(context['studentBody'], self.base['body'])
        self.assertIn('UPDATE MULTIPLICITY', context['structuralContext']['steps'][0]['dsl'])
        self.assertEqual(len(context['behavior']['categories']), 4)
        self.assertNotIn('PRIVATE_', self.calls[0]['input'])
        self.assertNotIn('oracleBody', self.calls[0]['input'])
        self.assertFalse(self.calls[0]['store'])

    def test_incomplete_empty_body_chat_without_false_compilation_claim(self):
        for revision, body in enumerate(('', 'all n: Node | {'), 1):
            code, _, value = self.post(data=dict(self.base, revision=revision, body=body))
            self.assertEqual((code, value['status']), (200, 'ok'))
            context = json.loads(self.calls[-1]['input'])['context']
            self.assertEqual(context['trace'], {'status': 'unavailable'})
            self.assertEqual(context['behavior'], {'status': 'unavailable'})

    def test_forged_cross_channel_and_wrong_body_pins_do_not_call_provider(self):
        pinned = self.pins()
        channel = self.post('/api/channel', {})[2]['channel']
        cases = [dict(self.base, evidenceToken='0' * 64), dict(pinned, channel=channel),
                 dict(pinned, body='no A', revision=2)]
        for data in cases:
            self.assertEqual(self.post(data=data)[2]['status'], 'expired')
        self.assertEqual(self.calls, [])

    def test_unknown_fields_keys_history_and_bad_model_are_rejected(self):
        for field in ('apiKey', 'history', 'context', 'endpoint'):
            self.assertEqual(self.post(data=dict(self.base, **{field: 'untrusted'}))[0], 400)
        self.assertEqual(self.post(data=dict(self.base, model='arbitrary-model'))[2]['status'], 'unavailable')
        self.assertEqual(self.post(data=dict(self.base, effort='invented'))[2]['status'], 'unavailable')
        self.assertEqual(self.calls, [])

    def test_message_bound_and_channel_required(self):
        for change in ({'message': 'a' * 2049}, {'revision': True}, {'body': 'a' * 8193}, {'channel': ''}):
            self.assertEqual(self.post(data=dict(self.base, **change))[0], 400)
        data = dict(self.base); del data['channel']
        self.assertEqual(self.post(data=data)[0], 400)
        self.assertEqual(self.calls, [])

    def test_server_owned_history_survives_edits_with_fresh_context_and_stays_exercise_bound(self):
        result = self.post()[2]
        data = dict(self.base, message='What does multiplicity mean?', conversationToken=result['conversationToken'])
        self.assertEqual(self.post(data=data)[2]['status'], 'ok')
        turns = json.loads(self.calls[-1]['input'])['conversation']
        self.assertEqual([row['role'] for row in turns], ['user', 'assistant'])
        self.assertEqual(turns[1]['content'], result['reply'])
        changed = dict(data, revision=2, body='no A', message='Does my revised approach address that?')
        self.assertEqual(self.post(data=changed)[2]['status'], 'ok')
        document = json.loads(self.calls[-1]['input'])
        self.assertEqual(document['context']['studentBody'], 'no A')
        self.assertEqual(document['context']['revision'], 2)
        self.assertEqual(document['context']['trace'], {'status': 'unavailable'})
        self.assertEqual(document['conversation'][0]['contextRevision'], 1)
        self.assertEqual(self.post(data=dict(changed, exerciseId='graphs-inv6', revision=3))[2]['status'], 'expired')
        self.assertEqual(len(self.calls), 3)

    def test_same_conversation_retrieves_new_checked_evidence_after_an_edit(self):
        first = self.post(data=self.pins())[2]
        changed = dict(self.base, revision=2, body='some A // revised', conversationToken=first['conversationToken'])
        self.assertEqual(self.post(data=self.pins(changed))[2]['status'], 'ok')
        document = json.loads(self.calls[-1]['input'])
        self.assertEqual(document['context']['studentBody'], changed['body'])
        self.assertEqual(document['context']['revision'], 2)
        self.assertEqual(document['context']['structuralContext']['steps'][0]['status'], 'ok')
        self.assertEqual(len(document['context']['behavior']['categories']), 4)
        self.assertEqual(len(document['conversation']), 2)

    def test_suggestions_are_cheap_at_most_three_and_cached(self):
        self.answer = {'suggestions': ['What does this operator mean?', 'How do I read the example?']}
        data = dict(self.base, model='gpt-6.1-sol', effort='high'); del data['message']
        first = self.post('/api/chat/suggestions', data)[2]
        self.assertEqual(first['status'], 'ok')
        self.assertEqual(first['model'], 'gpt-6-luna')
        self.assertEqual(self.calls[0]['reasoning']['effort'], 'none')
        self.assertEqual(self.post('/api/chat/suggestions', data)[2], first)
        self.assertEqual(len(self.calls), 1)

    def test_stale_provider_reply_is_discarded_without_history(self):
        self.hook = lambda: self.app.scheduler.observe(self.channel, 2)
        self.assertEqual(self.post()[2]['status'], 'superseded')
        self.assertEqual(len(self.app.chat.histories), 0)
        self.assertEqual(len(self.app.chat.cache), 0)

    def test_foreign_origin_rejected_before_provider(self):
        self.assertEqual(self.post(headers={'Origin': 'https://evil.example'})[0], 403)
        self.assertEqual(self.calls, [])

    def test_provider_slots_shared_with_automatic_guidance(self):
        self.assertIs(self.app.chat.slots, self.app.explainer.slots)
        self.app.explainer.slots.acquire(); self.app.explainer.slots.acquire()
        try:
            self.assertEqual(self.post()[2]['status'], 'busy')
            self.assertEqual(self.calls, [])
        finally:
            self.app.explainer.slots.release(); self.app.explainer.slots.release()


if __name__ == '__main__':
    unittest.main()
