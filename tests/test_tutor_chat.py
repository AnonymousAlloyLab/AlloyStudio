"""Bounded tutor conversations and provider boundaries; no JVM or paid calls."""
from copy import deepcopy
from dataclasses import replace
import io
import json
import threading
import unittest
from unittest.mock import Mock, patch
from urllib.error import HTTPError

import ai_provider
import tutor_chat
from test_education import behavior
from test_guidance_providers import provider_reply
from test_structural_guidance import feedback


class TutorChatTests(unittest.TestCase):
    def setUp(self):
        self.profile = ai_provider.Profile('openai', 'gpt-6.1-sol', 'SYNTHETIC_TUTOR_TOKEN',
                                           ai_provider.ENDPOINTS['openai'], 'high')
        selection = patch.object(tutor_chat.tutor_models, 'resolve', side_effect=lambda *args: self.profile)
        selection.start()
        self.addCleanup(selection.stop)
        self.calls = []
        self.output = {'reply': 'Inspect what the multiplicity operator requires. Which example disagrees?'}
        self.client = tutor_chat.TutorChat(transport=self.transport, key_reader=lambda: 'unused')
        self.addCleanup(self.client.close)
        self.context = tutor_chat.context_projection('Require an empty set.', 'some A', 'canonical',
                                                     feedback(), behavior(), context_identity='a' * 64)

    def transport(self, request, timeout):
        self.calls.append((request, timeout))
        return io.BytesIO(json.dumps(provider_reply(self.profile.protocol, self.output)).encode())

    def ask(self, client=None, **changes):
        values = dict(context=self.context, owner=('session', 'exercise', 'environment'), channel='channel',
                      model=self.profile.model, effort=self.profile.effort, message='What should I inspect?')
        values.update(changes)
        return (client or self.client).respond(**values)

    def document(self, index=-1):
        wire = json.loads(self.calls[index][0].data)
        if self.profile.protocol == 'openai':
            return json.loads(wire['input'])
        if self.profile.protocol == 'anthropic':
            return json.loads(wire['messages'][0]['content'])
        return json.loads(wire['contents'][0]['parts'][0]['text'])

    def test_context_carries_current_question_raw_code_crud_structure_and_all_instances(self):
        self.assertEqual(self.context['question'], 'Require an empty set.')
        self.assertEqual(self.context['studentBody'], 'some A')
        self.assertEqual(self.context['metric'], 'canonical')
        self.assertEqual(self.context['schemaVersion'], 'alloy-tutor-v1')
        self.assertEqual(self.context['structuralContext']['steps'][0]['dsl'],
                         'UPDATE MULTIPLICITY [0,M,[]] TO "no";')
        self.assertEqual(sum(len(category['instances']) for category in self.context['behavior']['categories']), 12)

    def test_context_projection_discards_private_fields_at_nested_boundaries(self):
        trace, instances = feedback(), behavior()
        trace.update(oracle='PRIVATE_ORACLE', record={'correctPool': 'PRIVATE_POOL'})
        trace['operations'][0]['targetTerm'] = 'PRIVATE_TARGET'
        trace['canonicalStructure']['oracle'] = 'PRIVATE_GRAPH'
        instances['categories'][0]['instances'][0]['xml'] = 'PRIVATE_XML'
        projected = tutor_chat.context_projection('Public question.', 'some A', 'canonical', trace, instances)
        self.assertNotIn('PRIVATE_', json.dumps(projected))

    def test_without_compiled_feedback_does_not_fabricate_trace(self):
        projected = tutor_chat.context_projection('Public question.', 'some A', 'canonical')
        self.assertEqual(projected['trace'], {'status': 'unavailable'})
        self.assertEqual(projected['behavior'], {'status': 'unavailable'})

    def test_projection_rejects_oversized_context_and_invalid_bounded_evidence(self):
        with self.assertRaises(ValueError):
            tutor_chat.context_projection('q' * 140001, 'some A', 'canonical')
        invalid = behavior()
        invalid['score'] = 1
        with self.assertRaises(ValueError):
            tutor_chat.context_projection('Public question.', 'some A', 'canonical', behavior=invalid)

    def test_reply_is_server_context_grounded_and_credentials_stay_in_header(self):
        with patch('subprocess.Popen', side_effect=AssertionError('Tutor must not start engines')):
            result = self.ask()
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(result['model'], 'gpt-6.1-sol')
        self.assertFalse(result['historyTruncated'])
        self.assertEqual(self.document()['context'], self.context)
        self.assertEqual(self.document()['conversation'], [])
        self.assertEqual(self.document()['message'], 'What should I inspect?')
        request, timeout = self.calls[0]
        self.assertEqual(timeout, 40)
        self.assertNotIn(self.profile.key.encode(), request.data)
        self.assertNotIn(self.profile.key, request.full_url)
        self.assertEqual(request.get_header('Authorization'), 'Bearer ' + self.profile.key)
        self.assertNotIn(self.profile.key, json.dumps(result))

    def test_each_native_provider_is_schema_bound_and_returns_same_validated_reply(self):
        for protocol, model, effort in [('openai', 'gpt-6.1-sol', 'high'),
                                       ('anthropic', 'claude-opus-5-5', 'high'),
                                       ('gemini', 'gemini-4-argon', 'low')]:
            with self.subTest(protocol=protocol):
                endpoint = ai_provider.ENDPOINTS[protocol]
                if protocol == 'gemini':
                    endpoint += model + ':generateContent'
                self.profile = ai_provider.Profile(protocol, model, 'SYNTHETIC_TUTOR_TOKEN', endpoint, effort)
                result = self.ask()
                self.assertEqual(result['status'], 'ok')
                self.assertEqual(self.document()['context'], self.context)
                self.assertIn('alloy-tutor-v1', self.calls[-1][0].data.decode())

    def test_suggestion_request_uses_cheapest_same_account_without_adding_history(self):
        self.output = {'suggestions': ['What does this operator require?', 'Which example should I inspect?']}
        result = self.ask(suggestions=True, message=None)
        self.assertEqual(result['status'], 'ok')
        self.assertEqual(result['model'], 'gpt-6-luna')
        self.assertEqual(result['effort'], 'none')
        self.assertEqual(len(result['suggestions']), 2)
        self.assertNotIn('conversationToken', result)
        self.assertEqual(len(self.client.histories), 0)
        request = self.calls[-1][0]
        self.assertEqual(request.get_header('Authorization'), 'Bearer SYNTHETIC_TUTOR_TOKEN')
        self.assertEqual(json.loads(request.data)['max_output_tokens'], 1200)

    def test_suggestions_allow_empty_but_refuse_duplicates_excess_and_bad_types(self):
        for index, value in enumerate([[], ['What should I inspect?'], ['Q?'] * 4,
                                       ['Q?', 'q?'], [''], [' '], ['q' * 161], [None], 'Q?']):
            self.output = {'suggestions': value}
            result = self.ask(suggestions=True, message=None, context={'case': index})
            with self.subTest(value=value):
                self.assertEqual(result['status'], 'ok' if index < 2 else 'unavailable')

    def test_history_is_server_held_and_follows_opaque_tokens(self):
        first = self.ask()
        token = first['conversationToken']
        result = self.ask(conversation_token=token, message='Why this operator?')
        self.assertEqual(result['status'], 'ok')
        self.assertNotEqual(result['conversationToken'], token)
        self.assertEqual(self.document()['conversation'], [
            {'role': 'user', 'content': 'What should I inspect?', 'contextRevision': self.context.get('revision')},
            {'role': 'assistant', 'content': first['reply'], 'contextRevision': self.context.get('revision')}])
        self.assertEqual(self.document()['message'], 'Why this operator?')

    def test_history_truncates_whole_turn_pairs_to_six_exchanges(self):
        token = None
        for index in range(8):
            result = self.ask(conversation_token=token, message=f'Question {index}?')
            token = result['conversationToken']
        self.assertTrue(result['historyTruncated'])
        self.assertEqual(len(self.document()['conversation']), 12)
        self.assertEqual(self.document()['conversation'][0]['content'], 'Question 1?')
        history = self.client.histories.get(token)
        self.assertEqual(len(history['turns']), 12)
        self.assertEqual(history['turns'][0]['content'], 'Question 2?')

    def test_wrong_owner_or_unknown_conversation_does_not_call_provider(self):
        token = self.ask()['conversationToken']
        count = len(self.calls)
        for changes in ({'conversation_token': 'made-up'},
                        {'conversation_token': token, 'owner': ('different-session',)},
                        {'conversation_token': token, 'owner': ('session', 'different-exercise')}):
            with self.subTest(changes=changes):
                self.assertEqual(self.ask(**changes)['status'], 'expired')
        self.assertEqual(len(self.calls), count)

    def test_new_current_evidence_replaces_older_context_while_history_retains_only_turns(self):
        token = self.ask()['conversationToken']
        changed = deepcopy(self.context)
        changed['studentBody'] = 'none'
        self.assertEqual(self.ask(conversation_token=token, context=changed)['status'], 'ok')
        self.assertEqual(self.document()['context']['studentBody'], 'none')
        self.assertNotIn('context', self.document()['conversation'][0])

    def test_cache_is_idempotent_and_returned_mutation_cannot_modify_saved_response(self):
        first = self.ask()
        saved = deepcopy(first)
        first['reply'] = 'mutated'
        self.assertEqual(self.ask(), saved)
        self.assertEqual(len(self.calls), 1)

    def test_cache_varies_with_evidence_owner_message_effort_account_and_provider(self):
        self.ask()
        self.ask(context={'studentBody': 'none'})
        self.ask(owner=('different-session',))
        self.ask(message='A different question?')
        self.profile = replace(self.profile, effort='max')
        self.ask()
        self.profile = replace(self.profile, key='OTHER_PRIVATE_ACCOUNT')
        self.ask()
        self.assertEqual(len(self.calls), 6)

    def test_cache_without_surviving_conversation_recomputes_instead_of_returning_dead_token(self):
        first = self.ask()
        self.client.histories.clear()
        second = self.ask()
        self.assertEqual(second['status'], 'ok')
        self.assertNotEqual(first['conversationToken'], second['conversationToken'])
        self.assertEqual(len(self.calls), 2)

    def test_expiry_and_bounds_use_injected_clock(self):
        now = [0]
        client = tutor_chat.TutorChat(transport=self.transport, clock=lambda: now[0])
        self.addCleanup(client.close)
        first = self.ask(client)
        now[0] = 121
        self.ask(client)
        self.assertEqual(len(self.calls), 2)
        now[0] = 1800
        self.assertEqual(self.ask(client, conversation_token=first['conversationToken'])['status'], 'expired')
        for index in range(135):
            self.ask(client, message=f'Bounded question {index}?')
        self.assertLessEqual(len(client.histories), 128)
        self.assertLessEqual(len(client.cache), 128)
        self.assertLessEqual(client.histories.bytes, 4 * 1048576)
        self.assertLessEqual(client.cache.bytes, 2 * 1048576)

    def test_invalid_messages_or_oversized_inputs_do_not_call_provider(self):
        for message in (None, '', ' ', [], 'bad\x00message', 'q' * 2049, '💡' * 513, '\ud800'):
            with self.subTest(message=repr(message)):
                self.assertEqual(self.ask(message=message)['status'], 'unavailable')
        self.assertEqual(self.ask(context={'large': 'x' * 192001})['status'], 'unavailable')
        self.assertEqual(self.calls, [])

    def test_invalid_outputs_are_not_cached_and_release_slots(self):
        for index, output in enumerate([{}, {'reply': ''}, {'reply': 1}, {'reply': 'q' * 4001},
                {'reply': '```alloy\nno A\n```'}, {'reply': 'pred inv { no A }'},
                {'reply': 'The complete solution is: no A'}, {'reply': 'Valid', 'private': 'extra'}]):
            self.output = output
            with self.subTest(output=output):
                self.assertEqual(self.ask(context={'case': index})['status'], 'unavailable')
                self.assertEqual(self.client.active, set())
        self.assertEqual(len(self.client.cache), 0)
        self.output = {'reply': 'Inspect the operator.'}
        self.assertEqual(self.ask()['status'], 'ok')

    def test_provider_key_and_token_like_strings_are_redacted(self):
        self.output = {'reply': 'Inspect SYNTHETIC_TUTOR_TOKEN and sk-abcdefghijklmnop123456789.'}
        result = self.ask()
        self.assertEqual(result['status'], 'ok')
        self.assertNotIn(self.profile.key, result['reply'])
        self.assertNotIn('sk-abcdefghijklmnop', result['reply'])
        self.assertIn('[redacted]', result['reply'])

    def test_http_errors_are_sanitized_closed_and_not_retried(self):
        for code in (401, 403, 404, 429, 500):
            stream = io.BytesIO(b'PRIVATE_PROVIDER_DETAIL')
            transport = Mock(side_effect=HTTPError('https://provider.example', code, 'PRIVATE_DETAIL', {}, stream))
            client = tutor_chat.TutorChat(transport=transport)
            self.addCleanup(client.close)
            result = self.ask(client)
            with self.subTest(code=code):
                self.assertEqual(result['status'], 'unavailable')
                self.assertNotIn('PRIVATE_', json.dumps(result))
                transport.assert_called_once()
                self.assertTrue(stream.closed)

    def test_malformed_truncated_oversized_provider_response_fails_safely(self):
        values = [b'{bad', b' ' * 1048577, json.dumps({'status': 'incomplete', 'output': []}).encode(),
                  b'{"status":"completed","status":"completed","output":[]}']
        for value in values:
            client = tutor_chat.TutorChat(transport=lambda *a, **k: io.BytesIO(value))
            self.addCleanup(client.close)
            with self.subTest(size=len(value)):
                self.assertEqual(self.ask(client)['status'], 'unavailable')

    def test_superseded_before_and_after_transport_never_persists_history(self):
        self.assertEqual(self.ask(current=lambda: False), {'status': 'superseded'})
        self.assertEqual(self.calls, [])
        answers = iter([True, True, False])
        self.assertEqual(self.ask(current=lambda: next(answers)), {'status': 'superseded'})
        self.assertEqual(len(self.calls), 1)
        self.assertEqual(len(self.client.histories), 0)
        self.assertEqual(len(self.client.cache), 0)
        self.assertEqual(self.client.active, set())

    def test_shared_slot_limit_and_same_channel_exclusion_are_nonblocking(self):
        entered, release = threading.Event(), threading.Event()
        results = []
        def transport(*args, **kwargs):
            entered.set()
            if not release.wait(5):
                raise AssertionError('test release timed out')
            return self.transport(*args, **kwargs)
        slots = threading.BoundedSemaphore(1)
        client = tutor_chat.TutorChat(transport=transport, slots=slots)
        self.addCleanup(client.close)
        thread = threading.Thread(target=lambda: results.append(self.ask(client)), daemon=True)
        thread.start()
        self.addCleanup(lambda: (release.set(), thread.join(5)))
        self.assertTrue(entered.wait(5))
        self.assertEqual(self.ask(client)['status'], 'busy')
        self.assertEqual(self.ask(client, channel='other')['status'], 'busy')
        release.set()
        thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertEqual(results[0]['status'], 'ok')
        self.assertTrue(slots.acquire(blocking=False))
        slots.release()

    def test_close_in_flight_drops_result_and_returns_slot(self):
        def transport(*args, **kwargs):
            self.client.close()
            return self.transport(*args, **kwargs)
        self.client.transport = transport
        result = self.ask()
        self.assertEqual(result['status'], 'unavailable')
        self.assertEqual(len(self.client.histories), 0)
        self.assertEqual(len(self.client.cache), 0)
        self.assertEqual(self.client.active, set())
        self.assertEqual(self.ask()['status'], 'unavailable')

    def test_catalogue_failure_sanitized_and_disabled_shape(self):
        for value in ([], [{'id': 'safe-model'}], ValueError('PRIVATE_CONFIGURATION_DETAIL')):
            with patch.object(tutor_chat.tutor_models, 'catalogue') as catalogue:
                if isinstance(value, Exception):
                    catalogue.side_effect = value
                else:
                    catalogue.return_value = value
                result = self.client.catalogue()
                self.assertEqual(result['status'], 'unavailable' if isinstance(value, Exception)
                                 else 'ok' if value else 'disabled')
                self.assertNotIn('PRIVATE_', json.dumps(result))


class TutorModelSwitchTests(unittest.TestCase):
    """Exercise real model selection while keeping credentials and transport synthetic."""

    def setUp(self):
        self.profiles = {
            protocol: ai_provider.Profile(protocol, model, f'SYNTHETIC_{protocol.upper()}_CHAT_KEY',
                                          ai_provider.ENDPOINTS[protocol], effort)
            for protocol, model, effort in [('openai', 'gpt-6-luna', 'low'),
                                            ('anthropic', 'claude-opus-5-5', 'high'),
                                            ('gemini', 'gemini-4-argon', 'low')]
        }
        profiles = patch.object(ai_provider, 'chat_profiles', return_value=self.profiles)
        profiles.start()
        self.addCleanup(profiles.stop)
        self.calls = []
        self.client = tutor_chat.TutorChat(transport=self.transport,
                                         key_reader=Mock(side_effect=AssertionError('No real key reads')))
        self.addCleanup(self.client.close)
        self.context = tutor_chat.context_projection('Require an empty set.', 'some A', 'canonical',
                                                     feedback(), behavior(), context_identity='a' * 64)
        self.context['revision'] = 'draft-1'

    def transport(self, request, timeout):
        wire = json.loads(request.data)
        if request.full_url == ai_provider.ENDPOINTS['openai']:
            protocol, model, effort = 'openai', wire['model'], wire['reasoning']['effort']
            document = json.loads(wire['input'])
            header, prefix = 'Authorization', 'Bearer '
        elif request.full_url == ai_provider.ENDPOINTS['anthropic']:
            protocol, model, effort = 'anthropic', wire['model'], wire['output_config']['effort']
            document = json.loads(wire['messages'][0]['content'])
            header, prefix = 'X-api-key', ''
        else:
            self.assertTrue(request.full_url.startswith(ai_provider.ENDPOINTS['gemini']))
            protocol = 'gemini'
            model = request.full_url.removeprefix(ai_provider.ENDPOINTS['gemini']).removesuffix(':generateContent')
            effort = wire['generationConfig']['thinkingConfig']['thinkingLevel'].lower()
            document = json.loads(wire['contents'][0]['parts'][0]['text'])
            header, prefix = 'X-goog-api-key', ''
        self.assertEqual(request.get_header(header), prefix + self.profiles[protocol].key)
        for profile in self.profiles.values():
            self.assertNotIn(profile.key.encode(), request.data)
            self.assertNotIn(profile.key, request.full_url)
            if profile.protocol != protocol:
                self.assertNotIn(profile.key, json.dumps(dict(request.header_items())))
        self.calls.append((protocol, model, effort, document))
        output = {'reply': f'Consider the current operator. What do you notice in example {len(self.calls)}?'}
        return io.BytesIO(json.dumps(provider_reply(protocol, output)).encode())

    def ask(self, model='gpt-6-luna', effort='low', **changes):
        values = dict(context=self.context, owner=('session', 'exercise', 'environment'), channel='channel',
                      model=model, effort=effort, message='What should I inspect?')
        values.update(changes)
        return self.client.respond(**values)

    def test_model_effort_and_provider_switches_continue_history_with_latest_public_context(self):
        result = self.ask()
        self.assertEqual(result['status'], 'ok')
        turns = [
            {'role': 'user', 'content': 'What should I inspect?', 'contextRevision': 'draft-1'},
            {'role': 'assistant', 'content': result['reply'], 'contextRevision': 'draft-1'},
        ]
        latest = tutor_chat.context_projection('Require an empty set.', 'no A', 'canonical')
        latest['revision'] = 'draft-2'
        for protocol, model, effort in [('openai', 'gpt-6.1-sol', 'max'),
                                       ('anthropic', 'claude-opus-5-5', 'high'),
                                       ('gemini', 'gemini-4-argon', 'low')]:
            with self.subTest(model=model, effort=effort):
                previous_token = result['conversationToken']
                message = 'How does that apply to my current draft?'
                result = self.ask(model, effort, conversation_token=previous_token,
                                  context=latest, message=message)
                self.assertEqual(result['status'], 'ok')
                self.assertEqual((result['model'], result['effort']), (model, effort))
                self.assertNotEqual(result['conversationToken'], previous_token)
                self.assertFalse(result['historyTruncated'])
                self.assertEqual(self.calls[-1][:3], (protocol, model, effort))
                self.assertEqual(self.calls[-1][3], {'context': latest, 'conversation': turns,
                                                   'message': message})
                turns += [{'role': 'user', 'content': message, 'contextRevision': 'draft-2'},
                          {'role': 'assistant', 'content': result['reply'], 'contextRevision': 'draft-2'}]
        self.assertEqual(self.client.histories.get(result['conversationToken'])['turns'], turns)
        self.assertEqual(len(self.calls), 4)

    def test_same_conversation_request_cache_separates_model_effort_provider_and_account(self):
        first = self.ask()
        token = first['conversationToken']
        expected_turns = self.client.histories.get(token)['turns']
        selections = [('openai', 'gpt-6-luna', 'low'), ('openai', 'gpt-6-luna', 'high'),
                      ('openai', 'gpt-6.1-sol', 'high'), ('anthropic', 'claude-opus-5-5', 'high'),
                      ('gemini', 'gemini-4-argon', 'low')]
        for index, (protocol, model, effort) in enumerate(selections, start=2):
            with self.subTest(model=model, effort=effort):
                result = self.ask(model, effort, conversation_token=token)
                self.assertEqual(result['status'], 'ok')
                self.assertEqual((result['model'], result['effort']), (model, effort))
                self.assertEqual(len(self.calls), index)
                self.assertEqual(self.calls[-1][:3], (protocol, model, effort))
                self.assertEqual(self.calls[-1][3]['conversation'], expected_turns)
                self.assertEqual(self.ask(model, effort, conversation_token=token), result)
                self.assertEqual(len(self.calls), index)
        self.profiles['openai'] = replace(self.profiles['openai'], key='SYNTHETIC_ROTATED_CHAT_KEY')
        rotated = self.ask('gpt-6-luna', 'high', conversation_token=token)
        self.assertEqual(rotated['status'], 'ok')
        self.assertEqual(len(self.calls), 7)
        self.assertEqual(self.calls[-1][3]['conversation'], expected_turns)
        self.assertEqual(self.ask('gpt-6-luna', 'high', conversation_token=token), rotated)
        self.assertEqual(len(self.calls), 7)


if __name__ == '__main__':
    unittest.main()
