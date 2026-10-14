"""The learner boundary is identical across configured providers; no live calls."""
from dataclasses import replace
import io
import json
import unittest
from unittest.mock import patch

from ai_provider import ENDPOINTS, Profile
import luna
from test_education import behavior, expected, trace


def provider_reply(protocol, value):
    content = json.dumps(value)
    if protocol == 'openai':
        return {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant',
            'content': [{'type': 'output_text', 'text': content}]}]}
    if protocol == 'anthropic':
        return {'type': 'message', 'role': 'assistant', 'stop_reason': 'end_turn',
                'content': [{'type': 'text', 'text': content}]}
    if protocol == 'gemini':
        return {'candidates': [{'finishReason': 'STOP', 'content': {'role': 'model',
                'parts': [{'text': content}]}}]}
    return {'choices': [{'finish_reason': 'stop', 'message': {'role': 'assistant', 'content': content}}]}


class GuidanceProviderTests(unittest.TestCase):
    def profile(self, protocol='openai'):
        return Profile(protocol, 'fixture-model', 'SYNTHETIC_PROVIDER_TOKEN',
                       ENDPOINTS.get(protocol, 'https://example.invalid/chat/completions'), 'high')

    def test_each_adapter_preserves_redaction_identity_and_no_solution_validation(self):
        for protocol in ('openai', 'anthropic', 'gemini', 'deepseek', 'openai-compatible'):
            profile = self.profile(protocol)
            for variant in ('valid', 'missing-id', 'solution', 'secret'):
                with self.subTest(protocol=protocol, variant=variant):
                    calls = []
                    feedback = trace()
                    feedback['oracleBody'] = 'HIDDEN_ORACLE_SENTINEL'
                    evidence = luna.prompt_education(feedback, 'some A', behavior(), question='Require some objects.')
                    output = expected(evidence)
                    if variant == 'missing-id': output['operations'].pop()
                    if variant == 'solution': output['summary'] = 'The full solution is: no A'
                    if variant == 'secret': output['summary'] = 'Inspect ' + profile.key
                    def transport(request, timeout):
                        calls.append(request)
                        self.assertNotIn(b'HIDDEN_ORACLE_SENTINEL', request.data)
                        self.assertNotIn(profile.key, request.full_url)
                        self.assertNotIn(profile.key.encode(), request.data)
                        self.assertIn(b'Require some objects.', request.data)
                        return io.BytesIO(json.dumps(provider_reply(protocol, output)).encode())
                    with patch.object(luna, 'select_profile', return_value=profile):
                        result = luna.Explainer(transport=transport).explain(feedback, student_body='some A',
                            behavior=behavior(), question='Require some objects.')
                    self.assertEqual(len(calls), 1)
                    self.assertEqual(result['status'], 'unavailable' if variant in ('missing-id', 'solution') else 'ok')
                    self.assertNotIn(profile.key, json.dumps(result))
                    if result['status'] == 'ok':
                        self.assertEqual(result['model'], 'fixture-model')
                        self.assertEqual(len(result['instances']), 12)

    def test_cache_cannot_cross_provider_model_endpoint_effort_or_credential(self):
        original = self.profile()
        profiles = [original, replace(original, model='second-model'),
                    replace(original, endpoint='https://example.invalid/v1/responses'),
                    replace(original, effort='low'), replace(original, key='SECOND_SYNTHETIC_TOKEN'),
                    self.profile('anthropic')]
        active = [original]
        calls = []
        evidence = luna.prompt_education(trace(), 'some A')
        def transport(request, timeout):
            calls.append(request)
            return io.BytesIO(json.dumps(provider_reply(active[0].protocol, expected(evidence))).encode())
        client = luna.Explainer(transport=transport)
        with patch.object(luna, 'select_profile', side_effect=lambda *args: active[0]):
            for profile in profiles:
                active[0] = profile
                self.assertEqual(client.explain(trace(), student_body='some A')['status'], 'ok')
                self.assertEqual(client.explain(trace(), student_body='some A')['status'], 'ok')
        self.assertEqual(len(calls), len(profiles))
        self.assertEqual(client.pending, {})

    def test_invalid_config_or_disabled_profile_never_calls_transport(self):
        for config in (None, ValueError('PRIVATE_CONFIGURATION_DETAIL')):
            with self.subTest(config=type(config).__name__), patch.object(luna, 'select_profile') as select:
                if isinstance(config, Exception): select.side_effect = config
                else: select.return_value = config
                with patch.object(luna, 'urlopen') as transport:
                    result = luna.Explainer().explain(trace())
                transport.assert_not_called()
                self.assertIn(result['status'], ('disabled', 'unavailable'))
                self.assertNotIn('PRIVATE_CONFIGURATION_DETAIL', json.dumps(result))


if __name__ == '__main__':
    unittest.main()
