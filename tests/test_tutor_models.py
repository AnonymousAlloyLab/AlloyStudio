"""Private learner-chat authorization and model choices; no live requests."""
import copy
from dataclasses import replace
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import ai_provider as ai
import tutor_models as models

ROOT = Path(__file__).resolve().parents[1]


class TutorModels(unittest.TestCase):
    def setUp(self):
        scratch = ROOT / 'build' / 'tutor-model-tests'
        scratch.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        environment = patch.dict(os.environ, {}, clear=True)
        environment.start()
        self.addCleanup(environment.stop)
        backend = patch.object(ai, 'BACKEND_ROOT', self.root)
        backend.start()
        self.addCleanup(backend.stop)
        self.reader = Mock(return_value='legacy-private-example')

    def config(self):
        return {'version': 1, 'profiles': {
            'learner-secret-profile': {'provider': 'openai', 'model': 'gpt-6-luna',
                                       'api_key': 'guidance-private-example'},
            'upload-secret-profile': {'provider': 'gemini', 'model': 'gemini-4-argon',
                                      'api_key': 'upload-private-example'},
            'review-secret-profile': {'provider': 'anthropic', 'model': 'claude-opus-5-5',
                                      'api_key': 'review-private-example'},
        }, 'roles': {'guidance': 'learner-secret-profile', 'upload': 'upload-secret-profile',
                     'review': 'review-secret-profile'}}

    def write(self, content, name='ai.local.json', mode=0o600):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content if isinstance(content, str) else json.dumps(content), encoding='utf-8')
        path.chmod(mode)
        return path

    def catalogue(self):
        return models.catalogue(self.reader)

    def test_legacy_enables_only_requested_openai_stack(self):
        options = self.catalogue()
        self.assertEqual([item['id'] for item in options],
                         ['gpt-6-luna', 'gpt-6.1-terra', 'gpt-6.1-sol'])
        self.assertEqual(options[0]['efforts'], ['none', 'low', 'medium', 'high', 'xhigh', 'max'])
        self.assertEqual(options[1]['efforts'], options[0]['efforts'])
        self.assertEqual(options[2]['efforts'], options[0]['efforts'][1:])
        self.reader.assert_called_once_with()

    def test_public_catalogue_is_exact_allowlist_without_secrets_or_profile_names(self):
        config = self.config()
        config['chat'] = {'openai': 'learner-secret-profile', 'gemini': 'upload-secret-profile',
                          'anthropic': 'review-secret-profile'}
        self.write(config)
        options = self.catalogue()
        self.assertEqual(len(options), 8)
        for item in options:
            self.assertEqual(set(item), {'id', 'label', 'provider', 'efforts', 'defaultEffort'})
            self.assertIn(item['defaultEffort'], item['efforts'])
        encoded = json.dumps(options)
        for name, profile in config['profiles'].items():
            self.assertNotIn(name, encoded)
            self.assertNotIn(profile['api_key'], encoded)
        self.assertNotIn('https://', encoded)
        self.reader.assert_not_called()

    def test_catalogue_mutations_do_not_change_next_request(self):
        value = self.catalogue()
        value[0]['efforts'].append('invented')
        value[0]['id'] = 'other'
        self.assertNotIn('invented', self.catalogue()[0]['efforts'])
        self.assertEqual(self.catalogue()[0]['id'], 'gpt-6-luna')

    def test_absent_chat_uses_only_guidance_key_not_admin_keys(self):
        self.write(self.config())
        self.assertEqual({item['provider'] for item in self.catalogue()}, {'openai'})
        for model_id in ('claude-opus-5-5', 'gemini-4-argon'):
            with self.subTest(model_id=model_id), self.assertRaises(ValueError):
                models.resolve(model_id, None, self.reader)
        result = models.resolve('gpt-6.1-sol', 'high', self.reader)
        self.assertEqual(result.key, 'guidance-private-example')
        self.reader.assert_not_called()

    def test_disabled_guidance_does_not_borrow_other_roles(self):
        for value in (None, 'missing-key'):
            config = self.config()
            if value:
                config['profiles'][value] = {'provider': 'openai', 'model': 'gpt-6-luna', 'api_key': ''}
            config['roles']['guidance'] = value
            self.write(config)
            with self.subTest(value=value):
                self.assertEqual(self.catalogue(), [])
        config = self.config()
        config['roles'].pop('guidance')
        self.write(config)
        self.assertEqual(self.catalogue(), [])
        self.reader.assert_not_called()

    def test_unsupported_guidance_provider_does_not_enable_similar_protocol(self):
        config = self.config()
        config['profiles']['learner-secret-profile'] = {
            'provider': 'openai-compatible', 'model': 'gpt-6-luna', 'api_key': 'private',
            'endpoint': 'https://custom.example/chat'}
        self.write(config)
        self.assertEqual(self.catalogue(), [])
        config['profiles']['learner-secret-profile'] = {
            'provider': 'deepseek', 'model': 'deepseek-flash', 'api_key': 'private'}
        self.write(config)
        self.assertEqual(self.catalogue(), [])

    def test_explicit_chat_does_not_inherit_unlisted_guidance_provider(self):
        config = self.config()
        config['chat'] = {'anthropic': 'review-secret-profile'}
        self.write(config)
        self.assertEqual({item['provider'] for item in self.catalogue()}, {'anthropic'})
        self.assertEqual(models.resolve('claude-sonnet-5-5', None, self.reader).key,
                         'review-private-example')
        with self.assertRaises(ValueError):
            models.resolve('gpt-6-luna', 'low', self.reader)

    def test_chat_can_select_separate_account_from_same_provider(self):
        config = self.config()
        config['profiles']['chat-account'] = {'provider': 'openai', 'model': 'some-other-model',
                                             'api_key': 'chat-account-private'}
        config['chat'] = {'openai': 'chat-account'}
        self.write(config)
        selected = models.resolve('gpt-6.1-sol', 'high', self.reader)
        self.assertEqual(selected.key, 'chat-account-private')
        self.assertEqual(models.suggestions_profile(selected).key, 'chat-account-private')
        guidance = ai.select_profile('guidance', self.reader, 'gpt-6-luna', 'low')
        self.assertEqual(guidance.key, 'guidance-private-example')

    def test_null_empty_and_per_provider_null_disable_chat(self):
        for value in (None, {}, {'openai': None, 'anthropic': None, 'gemini': None}):
            config = self.config()
            config['chat'] = value
            self.write(config)
            with self.subTest(value=value):
                self.assertEqual(self.catalogue(), [])
        self.reader.assert_not_called()

    def test_selected_empty_key_disables_without_fallback(self):
        config = self.config()
        config['profiles']['learner-secret-profile']['api_key'] = ''
        config['chat'] = {'openai': 'learner-secret-profile'}
        self.write(config)
        self.assertEqual(self.catalogue(), [])
        self.reader.assert_not_called()

    def test_resolves_all_advertised_choices_and_default_efforts(self):
        config = self.config()
        config['chat'] = {'openai': 'learner-secret-profile', 'anthropic': 'review-secret-profile',
                          'gemini': 'upload-secret-profile'}
        self.write(config)
        for option in self.catalogue():
            for effort in option['efforts'] + [None]:
                with self.subTest(model_id=option['id'], effort=effort):
                    result = models.resolve(option['id'], effort, self.reader)
                    self.assertEqual(result.protocol, option['provider'])
                    self.assertEqual(result.model, option['id'])
                    self.assertEqual(result.effort, option['defaultEffort'] if effort is None else effort)
                    self.assertNotIn(result.key, repr(result))
                    if result.protocol == 'gemini':
                        self.assertEqual(result.endpoint, ai.ENDPOINTS['gemini'] + result.model + ':generateContent')
                    else:
                        self.assertEqual(result.endpoint, ai.ENDPOINTS[result.protocol])

    def test_rejects_unavailable_models_and_invalid_efforts_without_fallback(self):
        for model_id, effort in [('unknown', 'low'), ('gpt-6-astra', 'high'),
                ('gpt-6.1-sol', 'none'), ('gpt-6-luna', 'minimal'), ('gpt-6.1-terra', 'ultra'),
                ('gpt-6-luna', ''), ('gpt-6-luna', True), ('gpt-6-luna', []),
                ('gpt-6-luna', {'endpoint': 'https://other.test'}), (None, None), ([], 'low')]:
            with self.subTest(model_id=model_id, effort=effort), self.assertRaises(ValueError):
                models.resolve(model_id, effort, self.reader)
        self.reader.assert_not_called()

    def test_suggestions_remain_on_same_account_and_provider_without_config_read(self):
        config = self.config()
        config['chat'] = {'openai': 'learner-secret-profile', 'anthropic': 'review-secret-profile',
                          'gemini': 'upload-secret-profile'}
        self.write(config)
        expected = {'openai': ('gpt-6-luna', 'none'), 'anthropic': ('claude-haiku-5-5', 'low'),
                    'gemini': ('gemini-3.8-flash', 'minimal')}
        selections = [models.resolve(item['id'], None, self.reader) for item in self.catalogue()]
        with patch.object(ai, 'chat_profiles', side_effect=AssertionError('No config reread')):
            for selected in selections:
                with self.subTest(selected=selected.model):
                    result = models.suggestions_profile(selected)
                    self.assertEqual((result.model, result.effort), expected[selected.protocol])
                    self.assertEqual(result.protocol, selected.protocol)
                    self.assertEqual(result.key, selected.key)

    def test_suggestions_refuse_unrecognized_profile_authority(self):
        original = models.resolve('gpt-6-luna', 'low', self.reader)
        for changes in ({'protocol': 'deepseek'}, {'model': 'gpt-6-astra'}, {'effort': 'ultra'},
                        {'endpoint': 'https://other.example/responses'}, {'key': ''},
                        {'key': 'bad\nkey'}, {'key': None}, {'key': '☃'},
                        {'key': 'x' * (ai.MAX_KEY_BYTES + 1)}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                models.suggestions_profile(replace(original, **changes))
        for value in (None, {}, 'gpt-6-luna'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                models.suggestions_profile(value)

    def test_globals_disable_without_reading_config_or_credentials(self):
        self.write('{malformed')
        for name in ('ALLOY_AI_DISABLED', 'OPENAI_DISABLED'):
            with self.subTest(name=name), patch.dict(os.environ, {name: '1'}):
                self.assertEqual(self.catalogue(), [])
        self.reader.assert_not_called()

    def test_legacy_missing_key_disables(self):
        self.reader.return_value = ''
        self.assertEqual(self.catalogue(), [])

    def test_malformed_chat_configuration_fails_closed_for_all_roles(self):
        for value in (False, True, [], '', 'learner-secret-profile',
                      {'unknown': 'learner-secret-profile'}, {'openai': 'missing'},
                      {'openai': 'review-secret-profile'}, {'openai': False}, {'openai': []}):
            config = self.config()
            config['chat'] = value
            self.write(config)
            with self.subTest(value=value):
                with self.assertRaises(ValueError):
                    self.catalogue()
                with self.assertRaises(ValueError):
                    ai.select_profile('guidance', self.reader, 'gpt-6-luna', 'low')
        self.reader.assert_not_called()

    def test_existing_role_selection_is_unchanged_by_valid_chat_configuration(self):
        config = self.config()
        self.write(config)
        before = {role: ai.select_profile(role, self.reader, 'gpt-6-luna', 'low')
                  for role in config['roles']}
        for chat in (None, {}, {'anthropic': 'review-secret-profile'}):
            config['chat'] = chat
            self.write(config)
            for role in config['roles']:
                with self.subTest(chat=chat, role=role):
                    self.assertEqual(ai.select_profile(role, self.reader, 'gpt-6-luna', 'low'), before[role])

    def test_shared_validator_checks_chat_in_templates(self):
        config = self.config()
        for profile in config['profiles'].values():
            profile['api_key'] = ''
        config['chat'] = {'anthropic': 'review-secret-profile'}
        self.assertIsNone(ai.validate_template(config))
        config['chat']['openai'] = 'review-secret-profile'
        with self.assertRaises(ValueError):
            ai.validate_template(config)

    def test_relative_key_file_and_explicit_config_path(self):
        config = self.config()
        config['chat'] = {'openai': 'learner-secret-profile'}
        spec = config['profiles']['learner-secret-profile']
        del spec['api_key']
        spec['api_key_file'] = 'secrets/chat.key'
        self.write('relative-private-key', 'private/secrets/chat.key')
        path = self.write(config, 'private/settings.json')
        os.environ['ALLOY_AI_CONFIG_FILE'] = str(path)
        self.assertEqual(models.resolve('gpt-6-luna', 'none', self.reader).key, 'relative-private-key')
        self.reader.assert_not_called()

    def test_explicit_missing_and_invalid_file_never_fall_back(self):
        os.environ['ALLOY_AI_CONFIG_FILE'] = 'missing.json'
        with self.assertRaises(ValueError):
            self.catalogue()
        self.reader.assert_not_called()
        os.environ.pop('ALLOY_AI_CONFIG_FILE')
        for content in ('{bad', '{"version":1,"profiles":{},"roles":{},"chat":null,"chat":{}}',
                        '{"version":NaN,"profiles":{},"roles":{}}'):
            self.write(content)
            with self.subTest(content=content), self.assertRaises(ValueError):
                self.catalogue()
        self.reader.assert_not_called()

    @unittest.skipIf(sys.platform == 'win32', 'POSIX mode enforcement')
    def test_world_readable_config_refused(self):
        self.write(self.config(), mode=0o644)
        with self.assertRaises(ValueError):
            self.catalogue()
        self.reader.assert_not_called()

    def test_profile_limit_still_applies_to_chat_configuration(self):
        config = self.config()
        original = config['profiles']['learner-secret-profile']
        config['profiles'] = {f'profile-{n}': copy.deepcopy(original) for n in range(33)}
        config['roles'] = {}
        config['chat'] = {'openai': 'profile-0'}
        self.write(config)
        with self.assertRaises(ValueError):
            self.catalogue()


if __name__ == '__main__':
    unittest.main()
