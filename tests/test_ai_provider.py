"""Private profile and provider protocol regressions; no paid network requests."""
import copy
from dataclasses import FrozenInstanceError, replace
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_provider as ai

ROOT = Path(__file__).resolve().parents[1]


class Profiles(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build' / 'ai-provider-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.env = patch.dict(os.environ, {}, clear=True)
        self.env.start()
        self.addCleanup(self.env.stop)
        self.backend = patch.object(ai, 'BACKEND_ROOT', self.root)
        self.backend.start()
        self.addCleanup(self.backend.stop)
        self.legacy = Mock(return_value='legacy-private-token')

    def write(self, content, name='ai.local.json', mode=0o600):
        path = self.root / name
        path.write_text(content if isinstance(content, str) else json.dumps(content), encoding='utf-8')
        path.chmod(mode)
        return path

    def config(self, provider='anthropic', **changes):
        profile = {'provider': provider, 'model': 'model-example', 'api_key': 'private-example-token'}
        profile.update(changes)
        return {'version': 1, 'profiles': {'main': profile}, 'roles': {'guidance': 'main'}}

    def select(self, role='guidance'):
        return ai.select_profile(role, self.legacy, 'gpt-6-luna', 'low')

    def reject(self, config):
        self.write(config)
        with self.assertRaisesRegex(ValueError, '^Invalid private AI configuration or provider response$'):
            self.select()
        self.legacy.assert_not_called()

    def test_absent_config_retains_exact_legacy_profile(self):
        result = self.select()
        self.assertEqual(result, ai.Profile('openai', 'gpt-6-luna', 'legacy-private-token', ai.ENDPOINTS['openai'], 'low'))
        self.legacy.assert_called_once_with()
        self.assertNotIn(result.key, repr(result))
        with self.assertRaises(FrozenInstanceError):
            result.model = 'changed'

    def test_legacy_disabled_or_no_key(self):
        self.legacy.return_value = ''
        self.assertIsNone(self.select())
        os.environ['ALLOY_AI_DISABLED'] = '1'
        self.legacy.reset_mock()
        self.assertIsNone(self.select())
        self.legacy.assert_not_called()

    def test_injected_legacy_reader_owns_openai_disabled(self):
        os.environ['OPENAI_DISABLED'] = '1'
        self.assertIsNotNone(self.select())

    def test_legacy_allows_unspecified_effort(self):
        self.assertIsNone(ai.select_profile('review', self.legacy, 'model', None).effort)

    def test_private_profiles_override_legacy_and_roles(self):
        self.write(self.config())
        result = self.select()
        self.assertEqual(result.protocol, 'anthropic')
        self.assertEqual(result.model, 'model-example')
        self.assertEqual(result.key, 'private-example-token')
        self.assertEqual(self.select('upload'), result)
        self.assertIsNone(self.select('review'))
        self.legacy.assert_not_called()

    def test_explicit_disabled_upload_does_not_inherit(self):
        config = self.config()
        config['roles']['upload'] = None
        self.write(config)
        self.assertIsNone(self.select('upload'))

    def test_private_config_global_disables(self):
        self.write(self.config())
        for name in ('OPENAI_DISABLED', 'ALLOY_AI_DISABLED'):
            with self.subTest(name=name), patch.dict(os.environ, {name: '1'}):
                self.assertIsNone(self.select())
        self.legacy.assert_not_called()

    def test_empty_selected_key_disables_without_fallback(self):
        self.write(self.config(api_key=''))
        self.assertIsNone(self.select())
        self.legacy.assert_not_called()

    def test_key_file_relative_to_configuration(self):
        nested = self.root / 'private'
        nested.mkdir()
        keyfile = self.write('external-test-token\n', 'private/provider.key')
        config = self.config(api_key_file='provider.key')
        del config['profiles']['main']['api_key']
        path = self.write(config, 'private/settings.json')
        os.environ['ALLOY_AI_CONFIG_FILE'] = 'private/settings.json'
        self.assertEqual(self.select().key, keyfile.read_text().strip())
        os.environ['ALLOY_AI_CONFIG_FILE'] = str(path)
        self.assertEqual(self.select().key, 'external-test-token')

    def test_explicit_missing_file_never_falls_back(self):
        os.environ['ALLOY_AI_CONFIG_FILE'] = 'missing.json'
        with self.assertRaises(ValueError):
            self.select()
        self.legacy.assert_not_called()

    def test_empty_explicit_path_never_falls_back(self):
        os.environ['ALLOY_AI_CONFIG_FILE'] = ''
        with self.assertRaises(ValueError):
            self.select()
        self.legacy.assert_not_called()

    def test_rejects_malformed_duplicate_unknown_and_nonfinite_json(self):
        for content in ('{', '[]', '{"version":1,"version":1,"profiles":{},"roles":{}}',
                        '{"version":NaN,"profiles":{},"roles":{}}'):
            with self.subTest(content=content):
                self.reject(content)
        mutations = []
        for name, value in [('version', True), ('version', 2), ('extra', 'forbidden'),
                            ('roles', {'other': 'main'}), ('roles', {'guidance': 'missing'}),
                            ('roles', {'guidance': []}), ('profiles', {}), ('profiles', [])]:
            config = self.config()
            config[name] = value
            mutations.append(config)
        for config in mutations:
            with self.subTest(config=config):
                self.reject(config)

    def test_rejects_ambiguous_bad_credentials_and_provider_overrides(self):
        for changes in ({'api_key_file': 'secret'}, {'api_key': 'line\r\nbreak'},
                        {'api_key': 'not ascii ☃'}, {'api_key': ' spaced '},
                        {'api_key': None}, {'api_key': 'x' * (ai.MAX_KEY_BYTES + 1)},
                        {'endpoint': 'https://evil.test/v1/messages'}, {'provider': 'unknown'},
                        {'model': 'new\nline'}, {'model': ''}, {'model': None},
                        {'unexpected': True}, {'reasoning_effort': 'none'}):
            with self.subTest(changes=changes):
                self.reject(self.config(**changes))

    def test_rejects_duplicate_nested_key(self):
        self.reject('{"version":1,"profiles":{"main":{"provider":"openai","model":"x",'
                    '"api_key":"a","api_key":"b"}},"roles":{"guidance":"main"}}')

    def test_rejects_role_and_profile_shape_errors(self):
        config = self.config()
        config['profiles']['main'].pop('api_key')
        self.reject(config)
        config = self.config()
        config['profiles']['bad profile name'] = config['profiles'].pop('main')
        self.reject(config)
        config = self.config()
        config['profiles'] = {str(i): config['profiles']['main'] for i in range(33)}
        self.reject(config)
        with self.assertRaises(ValueError):
            self.select('wrong-role')

    @unittest.skipIf(sys.platform == 'win32', 'POSIX permission check')
    def test_rejects_shared_config_and_shared_key_file(self):
        self.write(self.config(), mode=0o644)
        with self.assertRaises(ValueError):
            self.select()
        self.write('sample', 'shared.key', mode=0o640)
        config = self.config(api_key_file='shared.key')
        del config['profiles']['main']['api_key']
        self.reject(config)

    @unittest.skipIf(sys.platform == 'win32', 'FIFO only on POSIX')
    def test_non_regular_file_rejected_without_waiting_for_fifo_writer(self):
        os.mkfifo(self.root / 'ai.local.json', 0o600)
        with self.assertRaises(ValueError):
            self.select()

    def test_rejects_oversized_configuration(self):
        self.reject(' ' * (ai.MAX_CONFIG_BYTES + 1))

    def test_custom_endpoint_requires_explicit_https_without_credentials(self):
        for endpoint in ('http://localhost/v1/chat/completions', 'https://user:pass@example.com/x',
                         'https://example.com/x?key=secret', 'https://example.com/x#secret',
                         'https://example.com:abc/x', 'https://example.com', '//example.com/x',
                         'https://example.com/\\x'):
            with self.subTest(endpoint=endpoint):
                self.reject(self.config(provider='openai-compatible', endpoint=endpoint))
        self.write(self.config(provider='openai-compatible', endpoint='https://custom.example/v1/chat/completions'))
        self.assertEqual(self.select().endpoint, 'https://custom.example/v1/chat/completions')

    def test_gemini_model_name_cannot_add_endpoint_path(self):
        self.write(self.config(provider='gemini', model='models/a?key=secret'))
        profile = self.select()
        self.assertEqual(profile.endpoint, ai.ENDPOINTS['gemini'] + 'models%2Fa%3Fkey%3Dsecret:generateContent')

    def test_provider_specific_effort_validation(self):
        for protocol, effort in [('anthropic', 'xhigh'), ('gemini', 'high'), ('deepseek', 'max')]:
            with self.subTest(protocol=protocol):
                self.write(self.config(provider=protocol, reasoning_effort=effort))
                self.assertEqual(self.select().effort, effort)
        self.reject(self.config(provider='deepseek', reasoning_effort='medium'))

    def test_binary_private_read_preserves_bytes_and_exact_limit(self):
        path = self.write('')
        raw = b'\xef\xbb\xbftoken\r\n\x1aend'
        path.write_bytes(raw)
        self.assertEqual(ai._private_text(path, len(raw)), 'token\r\n\x1aend')
        with self.assertRaises(ValueError):
            ai._private_text(path, len(raw) - 1)

    def test_public_example_is_valid_and_all_roles_disable_without_credentials(self):
        example = json.loads((ROOT / 'ai.example.json').read_text(encoding='utf-8'))
        self.assertIsNone(ai.validate_template(example))
        self.write(example)
        for role in ('guidance', 'upload', 'review'):
            with self.subTest(role=role):
                self.assertIsNone(self.select(role))
        self.legacy.assert_not_called()

    def test_template_never_opens_key_files_and_rejects_nonempty_or_extra_credentials(self):
        cases = []
        for changes in ({'api_key': 'SYNTHETIC_GOOGLE_STYLE_SECRET'},
                        {'api_key_file': 'sensitive.key'}, {'api_token': 'SYNTHETIC_EXTRA_SECRET'},
                        {'private_key': 'SYNTHETIC_EXTRA_SECRET'}, {'endpoint': 'https://x.test/'}):
            cases.append(self.config(api_key='', **changes) if 'api_key' not in changes
                         else self.config(**changes))
        file_only = self.config(api_key_file='sensitive.key')
        del file_only['profiles']['main']['api_key']
        cases.append(file_only)
        for endpoint in ('https://user:secret@example.test/chat', 'https://example.test/chat?key=secret'):
            cases.append(self.config(provider='openai-compatible', api_key='', endpoint=endpoint))
        with patch.object(ai, '_private_text', side_effect=AssertionError('No credential file access')) as reader:
            for config in cases:
                with self.subTest(config=config), self.assertRaisesRegex(ValueError, '^Invalid private AI'):
                    ai.validate_template(config)
            reader.assert_not_called()

    def test_template_uses_same_version_role_and_profile_contract(self):
        for key, value in (('version', True), ('version', 2), ('profiles', {}),
                           ('roles', {'guidance': 'absent'}), ('extra', 'forbidden')):
            config = self.config(api_key='')
            config[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                ai.validate_template(config)
        for value in (None, [], {}, {'profiles': []}):
            with self.subTest(value=value), self.assertRaises(ValueError):
                ai.validate_template(value)
        self.assertIsNone(ai.validate_template(self.config(api_key='')))
        self.assertIsNone(ai.validate_template(self.config(provider='openai-compatible', api_key='',
            endpoint='https://provider.example/v1/chat/completions')))

    def test_cache_identity_changes_for_every_provider_authority_input(self):
        value = ai.Profile('openai', 'model', 'private-example', 'https://example.org/v1/responses', 'high')
        identity = value.cache_identity
        self.assertEqual(len(identity), 64)
        self.assertNotIn(value.key, identity)
        for changed in ({'protocol': 'deepseek'}, {'model': 'other'}, {'key': 'other'},
                        {'endpoint': 'https://other.example/v1/responses'}, {'effort': 'low'}):
            with self.subTest(changed=changed):
                self.assertNotEqual(identity, replace(value, **changed).cache_identity)


class Adapters(unittest.TestCase):
    def setUp(self):
        self.schema = {'type': 'object', 'properties': {'description': {'type': 'string', 'maxLength': 360},
                      'items': {'type': 'array', 'minItems': 3, 'maxItems': 3, 'items': {'type': 'string'}},
                      'maxLength': {'type': 'integer', 'minimum': 0}},
                       'required': ['description', 'items', 'maxLength'], 'additionalProperties': False}
        self.payload = {'model': 'old-model', 'instructions': 'Be helpful; return JSON.', 'input': '{"learner":"source"}',
                        'reasoning': {'effort': 'low'}, 'max_output_tokens': 8000,
                        'text': {'format': {'type': 'json_schema', 'name': 'test', 'strict': True, 'schema': self.schema}}}

    def profile(self, protocol, effort='high'):
        return ai.Profile(protocol, 'selected-model', 'private-test-key',
                          'https://provider.example/api', effort)

    def build(self, protocol, effort='high'):
        request = ai.build_request(self.profile(protocol, effort), self.payload)
        self.assertEqual(request.method, 'POST')
        self.assertNotIn('private-test-key', request.full_url)
        self.assertNotIn('private-test-key', request.data.decode())
        return request, json.loads(request.data)

    def test_openai_changes_model_and_effort_and_disables_storage(self):
        request, body = self.build('openai')
        self.assertEqual(body['model'], 'selected-model')
        self.assertEqual(body['reasoning'], {'effort': 'high'})
        self.assertFalse(body['store'])
        self.assertEqual(body['text'], self.payload['text'])
        self.assertEqual(request.get_header('Authorization'), 'Bearer private-test-key')
        self.assertNotIn('reasoning', self.build('openai', None)[1])

    def test_anthropic_native_schema_preserves_prompt_and_local_constraints(self):
        original = copy.deepcopy(self.payload)
        request, body = self.build('anthropic')
        self.assertEqual(request.get_header('X-api-key'), 'private-test-key')
        self.assertEqual(request.get_header('Anthropic-version'), '2023-06-01')
        self.assertIsNone(request.get_header('Authorization'))
        self.assertEqual(body['system'], self.payload['instructions'])
        self.assertEqual(body['messages'][0]['content'], self.payload['input'])
        schema = body['output_config']['format']['schema']
        self.assertNotIn('maxLength', schema['properties']['description'])
        self.assertNotIn('minItems', schema['properties']['items'])
        self.assertNotIn('maxItems', schema['properties']['items'])
        self.assertIn('maxLength', schema['properties'])
        self.assertEqual(body['output_config']['effort'], 'high')
        self.assertEqual(body['thinking'], {'type': 'adaptive'})
        self.assertEqual(body['max_tokens'], 8000)
        self.assertEqual(self.payload, original)

    def test_gemini_native_schema_and_header_key(self):
        request, body = self.build('gemini')
        self.assertEqual(request.get_header('X-goog-api-key'), 'private-test-key')
        self.assertIsNone(request.get_header('Authorization'))
        self.assertEqual(body['contents'][0]['role'], 'user')
        self.assertEqual(body['systemInstruction']['parts'][0]['text'], self.payload['instructions'])
        generation = body['generationConfig']
        self.assertEqual(generation['responseFormat']['text'], {'mimeType': 'application/json', 'schema': self.schema})
        self.assertEqual(generation['thinkingConfig'], {'thinkingLevel': 'HIGH'})
        self.assertEqual(generation['maxOutputTokens'], 8000)
        self.assertNotIn('thinkingConfig', self.build('gemini', None)[1]['generationConfig'])

    def test_chat_json_mode_carries_schema_and_preserves_budget(self):
        for protocol in ('deepseek', 'openai-compatible'):
            with self.subTest(protocol=protocol):
                request, body = self.build(protocol)
                self.assertEqual(request.get_header('Authorization'), 'Bearer private-test-key')
                self.assertEqual(body['response_format'], {'type': 'json_object'})
                self.assertEqual(body['model'], 'selected-model')
                self.assertEqual(body['max_tokens'], 8000)
                self.assertEqual(body['reasoning_effort'], 'high')
                self.assertEqual(body['messages'][1]['content'], self.payload['input'])
                self.assertIn('Return JSON matching this schema:', body['messages'][0]['content'])
                self.assertFalse(body['stream'])

    def test_missing_effort_never_adds_unspecified_reasoning(self):
        for protocol in ('anthropic', 'deepseek', 'openai-compatible'):
            with self.subTest(protocol=protocol):
                body = self.build(protocol, None)[1]
                self.assertNotIn('reasoning_effort', body)
                self.assertNotIn('thinking', body)
                self.assertNotIn('effort', body.get('output_config', {}))

    def test_maximum_budget_respects_provider_caps(self):
        self.payload['max_output_tokens'] = 131072
        self.assertEqual(self.build('gemini')[1]['generationConfig']['maxOutputTokens'], 65536)
        self.assertEqual(self.build('anthropic')[1]['max_tokens'], 128000)
        for budget in (0, -1, True, 131073, '8000'):
            self.payload['max_output_tokens'] = budget
            with self.subTest(budget=budget), self.assertRaises(ValueError):
                self.build('openai')

    def responses(self):
        text = '{"description":"An example."}'
        return {
            'openai': {'status': 'completed', 'output': [{'type': 'reasoning'},
                        {'type': 'message', 'role': 'assistant', 'content': [{'type': 'output_text', 'text': text}]}]},
            'anthropic': {'type': 'message', 'role': 'assistant', 'stop_reason': 'end_turn',
                          'content': [{'type': 'thinking', 'thinking': 'private reasoning'}, {'type': 'text', 'text': text}]},
            'gemini': {'candidates': [{'finishReason': 'STOP', 'content': {'role': 'model',
                       'parts': [{'text': 'private reasoning', 'thought': True}, {'text': text, 'thoughtSignature': 'signature'}]}}]},
            'deepseek': {'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': text,
                         'reasoning_content': 'private reasoning'}}]},
        }

    def test_completed_responses_are_normalized_without_reasoning(self):
        for protocol, data in self.responses().items():
            with self.subTest(protocol=protocol):
                result = ai.normalize_response(self.profile(protocol), data)
                self.assertEqual(result['status'], 'completed')
                self.assertNotIn('private reasoning', json.dumps(result))
                self.assertEqual(len(result['output']), 1)
                self.assertEqual(json.loads(result['output'][0]['content'][0]['text']), {'description': 'An example.'})
        result = ai.normalize_response(self.profile('openai-compatible'), self.responses()['deepseek'])
        self.assertEqual(result['status'], 'completed')

    def test_truncated_responses_rejected(self):
        values = self.responses()
        values['openai']['status'] = 'incomplete'
        values['anthropic']['stop_reason'] = 'max_tokens'
        values['gemini']['candidates'][0]['finishReason'] = 'MAX_TOKENS'
        values['deepseek']['choices'][0]['finish_reason'] = 'length'
        for protocol, data in values.items():
            with self.subTest(protocol=protocol), self.assertRaises(ValueError):
                ai.normalize_response(self.profile(protocol), data)

    def test_tools_refusals_and_blocked_responses_rejected(self):
        values = self.responses()
        values['openai']['output'].append({'type': 'function_call'})
        values['anthropic']['content'].append({'type': 'tool_use'})
        values['gemini']['candidates'][0]['content']['parts'].append({'functionCall': {'name': 'tool'}})
        values['deepseek']['choices'][0]['message']['tool_calls'] = [{'type': 'function'}]
        for protocol, data in values.items():
            with self.subTest(protocol=protocol), self.assertRaises(ValueError):
                ai.normalize_response(self.profile(protocol), data)
        for protocol, data in self.responses().items():
            data['error'] = {'message': 'private provider error'}
            with self.subTest(protocol=protocol), self.assertRaisesRegex(ValueError, '^Invalid private AI'):
                ai.normalize_response(self.profile(protocol), data)
        blocked = self.responses()['gemini']
        blocked['promptFeedback'] = {'blockReason': 'SAFETY'}
        with self.assertRaises(ValueError):
            ai.normalize_response(self.profile('gemini'), blocked)

    def test_multiple_completions_or_text_blocks_rejected(self):
        values = self.responses()
        values['openai']['output'].append(values['openai']['output'][-1])
        values['anthropic']['content'].append(values['anthropic']['content'][-1])
        values['gemini']['candidates'].append(values['gemini']['candidates'][0])
        values['deepseek']['choices'].append(values['deepseek']['choices'][0])
        for protocol, data in values.items():
            with self.subTest(protocol=protocol), self.assertRaises(ValueError):
                ai.normalize_response(self.profile(protocol), data)

    def test_invalid_shapes_and_text_are_sanitized(self):
        for protocol in ('openai', 'anthropic', 'gemini', 'deepseek', 'openai-compatible'):
            for data in (None, [], {}, {'choices': 'bad'}, {'output': [None]}, {'candidates': [None]}):
                with self.subTest(protocol=protocol, data=data), self.assertRaisesRegex(ValueError, '^Invalid private AI'):
                    ai.normalize_response(self.profile(protocol), data)
        for text in ('', ' ', None, 1, 'x' * (ai.MAX_TEXT_BYTES + 1)):
            response = {'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': text}}]}
            with self.subTest(text_type=type(text)), self.assertRaises(ValueError):
                ai.normalize_response(self.profile('deepseek'), response)


if __name__ == '__main__':
    unittest.main()
