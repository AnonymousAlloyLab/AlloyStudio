"""Configured providers preserve private administrator response boundaries."""
from copy import deepcopy
from io import BytesIO
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import admin_luna as upload
import candidate_review as review
from ai_provider import Profile
from exercise_store import StoreError
from test_admin_luna import GOOD, SOURCE, WITNESS
from test_candidate_review import ADVICE, CONTEXT

ROOT = Path(__file__).resolve().parents[1]
KEY = 'synthetic-provider-token-for-unit-tests'


def profile(protocol='openai'):
    return Profile(protocol=protocol, model='configured-unit-test-model', key=KEY,
                   endpoint='https://unit-test.example/provider', effort=None)


def canonical_response(value):
    return {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant',
        'content': [{'type': 'output_text', 'text': json.dumps(value)}]}]}


def provider_response(protocol, value):
    encoded = json.dumps(value)
    if protocol == 'openai':
        return canonical_response(value)
    if protocol == 'anthropic':
        return {'type': 'message', 'role': 'assistant', 'stop_reason': 'end_turn',
                'content': [{'type': 'text', 'text': encoded}]}
    if protocol == 'gemini':
        return {'candidates': [{'finishReason': 'STOP', 'content': {
            'role': 'model', 'parts': [{'text': encoded}]}}]}
    return {'choices': [{'finish_reason': 'stop', 'message': {
        'role': 'assistant', 'content': encoded}}]}


class AdminProviderBoundaryTests(unittest.TestCase):
    def test_chat_schema_translation_must_fit_the_review_wire_byte_budget(self):
        # JSON-object protocols move the schema into an escaped message string;
        # an accepted Responses-shaped prompt can exceed the final wire budget.
        baseline = len(json.dumps(review.request_payload(CONTEXT), ensure_ascii=False).encode())
        selected = profile('deepseek')
        with patch.object(review, 'select_profile', return_value=selected), \
                patch.object(review, 'MAX_REQUEST', baseline + 1), \
                patch.object(review, 'urlopen') as transport:
            with self.assertRaisesRegex(StoreError, 'byte limit'):
                review._provider(CONTEXT)
            transport.assert_not_called()

    def test_each_provider_preserves_review_identity_and_upload_names_without_key_leak(self):
        protocols = ('openai', 'anthropic', 'gemini', 'deepseek', 'openai-compatible')
        pairs = ((upload, lambda: upload._provider(SOURCE, ['inv1'], ''), GOOD,
                  dict(status='ok', **GOOD)),
                 (review, lambda: review._provider(CONTEXT), ADVICE,
                  dict(status='ok', advice=ADVICE)))
        for protocol in protocols:
            for module, invoke, value, expected in pairs:
                encoded = json.dumps(provider_response(protocol, value)).encode()
                with self.subTest(protocol=protocol, module=module.__name__), \
                        patch.object(module, 'select_profile', return_value=profile(protocol)), \
                        patch.object(module, 'read_key') as legacy, \
                        patch.object(module, 'urlopen', return_value=BytesIO(encoded)) as transport:
                    self.assertEqual(invoke(), expected)
                    request = transport.call_args.args[0]
                    self.assertNotIn(KEY, request.full_url + request.data.decode())
                    self.assertIn(KEY, str(request.headers))
                    self.assertEqual(transport.call_args.kwargs['timeout'], 40)
                    self.assertNotIn(KEY, json.dumps(expected))
                    legacy.assert_not_called()

    def test_each_provider_rejects_changed_identity_and_hidden_upload_code(self):
        metadata = deepcopy(GOOD)
        metadata['exercises'][0]['question'] = 'Use pred inv1 { some Node }'
        for protocol in ('openai', 'anthropic', 'gemini', 'deepseek', 'openai-compatible'):
            for module, invoke, value in (
                    (upload, lambda: upload._provider(SOURCE, ['inv1'], ''), metadata),
                    (review, lambda: review._provider(CONTEXT), dict(ADVICE, candidateHash='b' * 64))):
                encoded = json.dumps(provider_response(protocol, value)).encode()
                with self.subTest(protocol=protocol, module=module.__name__), \
                        patch.object(module, 'select_profile', return_value=profile(protocol)), \
                        patch.object(module, 'urlopen', return_value=BytesIO(encoded)), \
                        self.assertRaises(StoreError):
                    invoke()

    def test_invalid_private_config_does_not_fall_back_or_allocate_worker(self):
        for module, invoke in ((upload, lambda: upload.suggest(ROOT, WITNESS, '')),
                               (review, lambda: review.review(ROOT, CONTEXT))):
            with self.subTest(module=module.__name__), \
                    patch.object(module, 'select_profile', side_effect=ValueError('PRIVATE_CONFIG_SENTINEL')), \
                    patch.object(module, 'read_key') as legacy, \
                    patch.object(module.subprocess, 'run') as child, \
                    patch.object(module, 'urlopen') as transport:
                result = invoke()
                self.assertEqual(result['status'], 'unavailable')
                self.assertNotIn('PRIVATE_CONFIG_SENTINEL', str(result))
                legacy.assert_not_called()
                child.assert_not_called()
                transport.assert_not_called()

    def test_selected_roles_use_separate_model_defaults_and_profile_keys(self):
        pairs = ((upload, 'upload', 'gpt-6-luna', 'low',
                  lambda: upload.suggest(ROOT, WITNESS, ''), dict(status='ok', **GOOD)),
                 (review, 'review', 'gpt-6.1-sol', 'high',
                  lambda: review.review(ROOT, CONTEXT), dict(status='ok', advice=ADVICE)))
        for module, role, default_model, effort, invoke, response in pairs:
            with self.subTest(role=role), \
                    patch.object(module, 'select_profile', return_value=profile()) as select, \
                    patch.object(module, 'read_key') as legacy, \
                    patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess(
                        [], 0, json.dumps(response), '')) as child:
                self.assertEqual(invoke(), response)
                select.assert_called_once_with(role, legacy, default_model, effort)
                legacy.assert_not_called()
                self.assertNotIn(KEY, str(child.call_args.args) + child.call_args.kwargs['input'])

    def test_parent_worker_outputs_redact_non_openai_token_from_all_prose_fields(self):
        metadata = deepcopy(GOOD)
        metadata['exercises'][0].update(title='Ignore ' + KEY, question='Do not show ' + KEY)
        advice = dict(ADVICE, reason='Ignore ' + KEY, counterexampleIdeas=['Do not show ' + KEY])
        for module, invoke, response in (
                (upload, lambda: upload.suggest(ROOT, WITNESS, ''), dict(status='ok', **metadata)),
                (review, lambda: review.review(ROOT, CONTEXT), dict(status='ok', advice=advice))):
            with self.subTest(module=module.__name__), \
                    patch.object(module, 'select_profile', return_value=profile()), \
                    patch.object(module.subprocess, 'run', return_value=subprocess.CompletedProcess(
                        [], 0, json.dumps(response), '')):
                result = invoke()
                self.assertEqual(result['status'], 'ok')
                self.assertNotIn(KEY, json.dumps(result))
                self.assertIn('[redacted]', json.dumps(result))

    def test_upload_rejects_source_code_hidden_in_metadata_prose(self):
        for text in ('```alloy\nsome Node\n```', 'Try pred inv1 { some Node }',
                     '~~~alloy\nsome Node\n~~~', 'fun answer[n: Node]: Node { n }'):
            for field in ('title', 'question'):
                value = deepcopy(GOOD)
                value['exercises'][0][field] = text
                with self.subTest(field=field, text=text), self.assertRaises(StoreError):
                    upload.validate_metadata(value, ['inv1'])

    def test_provider_translation_does_not_bypass_upload_or_advice_validator(self):
        changed_metadata = deepcopy(GOOD)
        changed_metadata['exercises'][0]['predicate'] = 'different'
        changed_advice = dict(ADVICE, candidateHash='b' * 64)
        for module, invoke, output in (
                (upload, lambda: upload._provider(SOURCE, ['inv1'], ''), changed_metadata),
                (review, lambda: review._provider(CONTEXT), changed_advice)):
            with self.subTest(module=module.__name__), \
                    patch.object(module, 'select_profile', return_value=profile()), \
                    patch.object(module, 'urlopen', return_value=BytesIO(b'{}')), \
                    patch.object(module, 'normalize_response', return_value=canonical_response(output)), \
                    self.assertRaises(StoreError):
                invoke()


if __name__ == '__main__':
    unittest.main()
