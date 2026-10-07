"""Sol candidate advice is identity-bound, bounded, private and advisory only."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, HTTPServer
from io import BytesIO
import hashlib
import json
import os
from pathlib import Path
import subprocess
from threading import Thread
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

import candidate_review as review
from exercise_store import StoreError

ROOT = Path(__file__).resolve().parents[1]
BODY = 'some Node'
CONTEXT = {
    'exerciseId': 'test-inv1', 'exerciseVersion': 'a' * 64,
    'candidateHash': hashlib.sha256(BODY.encode('utf-8')).hexdigest(),
    'predicate': 'inv1', 'question': 'Require at least one node.',
    'environmentBefore': 'sig Node {}\n', 'predicateHeader': 'pred inv1 ',
    'environmentAfter': '\nrun inv1 for 3\n', 'candidateBody': BODY,
    'oracleBodies': ['not no Node'],
    'boundedCheck': {
        'score': 1.0, 'moduleFacts': True, 'undercoverage': 'unsat',
        'overcoverage': 'unsat', 'scope': dict(review.CHECK_SCOPE),
        'sampling': {'positiveTested': 100, 'positiveAccepted': 100,
                     'negativeTested': 100, 'negativeRejected': 100,
                     'semanticCounterexamples': 0}}}
ADVICE = dict(exerciseId=CONTEXT['exerciseId'], exerciseVersion=CONTEXT['exerciseVersion'],
              candidateHash=CONTEXT['candidateHash'], verdict='recommend',
              reason='The candidate appears to express the stated existence requirement.',
              counterexampleIdeas=[])


def provider_response(advice=ADVICE):
    return {'status': 'completed', 'output': [{'type': 'reasoning'},
        {'type': 'message', 'content': [{'type': 'output_text', 'text': json.dumps(advice)}]}]}


class CandidateReviewTests(unittest.TestCase):
    def test_model_high_effort_strict_schema_store_false_and_no_tools(self):
        before = deepcopy(CONTEXT)
        payload = review.request_payload(CONTEXT)
        self.assertEqual(payload['model'], 'gpt-6.1-sol')
        self.assertIs(payload['store'], False)
        self.assertEqual(payload['reasoning'], {'effort': 'high'})
        self.assertEqual(payload['max_output_tokens'], 8000)
        self.assertNotIn('tools', payload)
        self.assertEqual(json.loads(payload['input']), CONTEXT)
        self.assertEqual(before, CONTEXT)
        schema = payload['text']['format']['schema']
        self.assertTrue(payload['text']['format']['strict'])
        self.assertFalse(schema['additionalProperties'])
        self.assertEqual(set(schema['required']), review.ADVICE_FIELDS)
        self.assertEqual(set(schema['properties']), review.ADVICE_FIELDS)
        for key in ('exerciseId', 'exerciseVersion', 'candidateHash'):
            self.assertEqual(schema['properties'][key]['enum'], [CONTEXT[key]])

    def test_prompt_calls_bounded_score_not_proof_and_preserves_final_authority(self):
        instruction = review.request_payload(CONTEXT)['instructions']
        self.assertIn('untrusted DATA', instruction)
        self.assertIn('not a universal equivalence proof', instruction)
        self.assertIn('administrator has final authority', instruction)
        self.assertIn('fresh bounded', instruction)
        self.assertIn('No tool use or source modification', instruction)

    def test_context_snapshot_does_not_alias_body_lists_or_bounded_evidence(self):
        snapshot = review.validate_context(CONTEXT)
        snapshot['oracleBodies'][0] = 'no Node'
        snapshot['boundedCheck']['sampling']['positiveAccepted'] = 1
        self.assertEqual(CONTEXT['oracleBodies'], ['not no Node'])
        self.assertEqual(CONTEXT['boundedCheck']['sampling']['positiveAccepted'], 100)

    def test_exact_utf8_body_identity_is_required_without_normalizing_its_text(self):
        context = deepcopy(CONTEXT)
        context['candidateBody'] = 'some Node // café\n'
        context['candidateHash'] = hashlib.sha256(context['candidateBody'].encode('utf-8')).hexdigest()
        self.assertEqual(json.loads(review.request_payload(context)['input'])['candidateBody'],
                         context['candidateBody'])
        context['candidateBody'] = 'some Node // café\n '
        with self.assertRaises(StoreError):
            review.request_payload(context)

    def test_malformed_extra_or_missing_identity_and_protected_fields_fail(self):
        bad = []
        for key, value in (('exerciseId', '../other'), ('exerciseVersion', 'A' * 64),
                           ('candidateHash', 'b' * 64), ('predicate', 'inv1;drop'),
                           ('question', ''), ('predicateHeader', ''),
                           ('environmentBefore', 'x\0y'), ('question', '\ud800'),
                           ('candidateBody', 'some Node } pred injected { no Node'),
                           ('oracleBodies', []), ('oracleBodies', ['no Node }'])):
            context = deepcopy(CONTEXT)
            context[key] = value
            bad.append(context)
        extra = deepcopy(CONTEXT)
        extra['api_key'] = 'synthetic-secret'
        bad.append(extra)
        missing = deepcopy(CONTEXT)
        del missing['candidateHash']
        bad.append(missing)
        for context in bad:
            with self.subTest(keys=list(context)), self.assertRaises(StoreError):
                review.request_payload(context)

    def test_perfect_evidence_rejects_rounding_boolean_incomplete_and_disagreement(self):
        changes = [('score', .9996), ('score', True), ('score', float('nan')),
                   ('moduleFacts', False), ('moduleFacts', 1),
                   ('overcoverage', 'sat'), ('undercoverage', 'unknown')]
        for key, value in changes:
            context = deepcopy(CONTEXT)
            context['boundedCheck'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(StoreError):
                review.request_payload(context)
        for key, value in (('positiveAccepted', 99), ('negativeRejected', 99),
                           ('positiveTested', 0), ('negativeTested', 0),
                           ('semanticCounterexamples', 1), ('semanticCounterexamples', False),
                           ('positiveAccepted', 100.0), ('positiveTested', 101)):
            context = deepcopy(CONTEXT)
            context['boundedCheck']['sampling'][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(StoreError):
                review.request_payload(context)
        for branch, key in (('boundedCheck', 'trusted'), ('scope', 'extra'), ('sampling', 'extra')):
            context = deepcopy(CONTEXT)
            target = context['boundedCheck'] if branch == 'boundedCheck' else context['boundedCheck'][branch]
            target[key] = True
            with self.subTest(branch=branch), self.assertRaises(StoreError):
                review.request_payload(context)
        context = deepcopy(CONTEXT)
        context['boundedCheck']['scope']['overall'] = True
        with self.assertRaises(StoreError):
            review.request_payload(context)

    def test_every_scope_field_and_complete_sampling_schema_is_enforced(self):
        for key in review.CHECK_SCOPE:
            context = deepcopy(CONTEXT)
            context['boundedCheck']['scope'][key] += 1
            with self.subTest(scope=key), self.assertRaises(StoreError):
                review.request_payload(context)
        for branch, key in (('scope', 'overall'), ('sampling', 'positiveAccepted')):
            context = deepcopy(CONTEXT)
            del context['boundedCheck'][branch][key]
            with self.subTest(branch=branch), self.assertRaises(StoreError):
                review.request_payload(context)

    def test_oracle_count_each_body_and_aggregate_request_are_bounded_without_truncation(self):
        context = deepcopy(CONTEXT)
        context['oracleBodies'] = ['not no Node'] * 64
        self.assertEqual(len(json.loads(review.request_payload(context)['input'])['oracleBodies']), 64)
        context['oracleBodies'].append('not no Node')
        with self.assertRaises(StoreError):
            review.request_payload(context)
        context = deepcopy(CONTEXT)
        context['oracleBodies'] = ['some Node //' + 'a' * 8192]
        with self.assertRaises(StoreError):
            review.request_payload(context)
        context = deepcopy(CONTEXT)
        # Control characters increase the actual JSON wire size; fields remain
        # individually valid, but the complete request exceeds its envelope.
        context['environmentBefore'] = '\x01' * 262144
        with self.assertRaises(StoreError):
            review.request_payload(context)
        context = deepcopy(CONTEXT)
        context['question'] = 'é' * 4097
        with self.assertRaises(StoreError):
            review.request_payload(context)

    def test_advice_preserves_identity_and_rejects_code_or_extra_fields(self):
        for field in ('exerciseId', 'exerciseVersion', 'candidateHash'):
            value = deepcopy(ADVICE)
            value[field] = 'b' * 64
            with self.subTest(field=field), self.assertRaises(StoreError):
                review.validate_advice(value, CONTEXT)
        variants = [dict(ADVICE, source='pred inv1 { no Node }'),
                    dict(ADVICE, oracleBodies=['no Node']), dict(ADVICE, verdict='approved'),
                    dict(ADVICE, reason=''), dict(ADVICE, reason='x\0y'),
                    dict(ADVICE, reason='é' * 601), dict(ADVICE, counterexampleIdeas=['é' * 201]),
                    dict(ADVICE, reason='```alloy\nsome Node\n```'),
                    dict(ADVICE, reason='Use pred inv1 { some Node }'),
                    dict(ADVICE, counterexampleIdeas=['a'] * 4),
                    dict(ADVICE, counterexampleIdeas=['']),
                    dict(ADVICE, counterexampleIdeas='not a list')]
        for value in variants:
            with self.subTest(keys=list(value)), self.assertRaises(StoreError):
                review.validate_advice(value, CONTEXT)

    def test_rejection_requires_a_counterexample_idea_but_never_a_verified_certificate(self):
        value = dict(ADVICE, verdict='reject', reason='Check a larger disconnected component.')
        with self.assertRaises(StoreError):
            review.validate_advice(value, CONTEXT)
        value['counterexampleIdeas'] = ['Four disconnected nodes, with no relation edges; compare the two acceptance conditions.']
        self.assertEqual(review.validate_advice(value, CONTEXT), value)
        uncertain = dict(ADVICE, verdict='uncertain', reason='The bounded agreement needs further inspection.')
        self.assertEqual(review.validate_advice(uncertain, CONTEXT), uncertain)

    def test_advice_copy_cannot_mutate_its_input(self):
        value = dict(ADVICE, counterexampleIdeas=['Try four isolated nodes.'])
        result = review.validate_advice(value, CONTEXT)
        result['counterexampleIdeas'].append('changed')
        self.assertEqual(value['counterexampleIdeas'], ['Try four isolated nodes.'])

    def test_no_key_skips_transport_and_worker_and_keeps_manual_decisions_available(self):
        with patch.object(review, 'read_key', return_value=''), \
                patch.object(review.subprocess, 'run') as process, \
                patch.object(review, 'urlopen') as transport:
            self.assertEqual(review.review(ROOT, CONTEXT), review.DISABLED)
            self.assertEqual(review._provider(CONTEXT), review.DISABLED)
        process.assert_not_called()
        transport.assert_not_called()

    def test_malformed_context_fails_before_credential_read_or_allocation(self):
        context = dict(CONTEXT, candidateHash='b' * 64)
        with patch.object(review, 'read_key') as credential, \
                patch.object(review.subprocess, 'run') as process, self.assertRaises(StoreError):
            review.review(ROOT, context)
        credential.assert_not_called()
        process.assert_not_called()

    def test_worker_has_hard_timeout_exact_context_and_no_secret_arguments_or_echo(self):
        before = deepcopy(CONTEXT)
        result = {'status': 'ok', 'advice': ADVICE}
        with patch.object(review, 'read_key', return_value='synthetic-key'), \
                patch.object(review.subprocess, 'run', return_value=
                    subprocess.CompletedProcess([], 0, json.dumps(result), '')) as process, \
                patch.dict(os.environ, {'PYTHONINSPECT': '1', 'PYTHONSTARTUP': 'private.py'}):
            self.assertEqual(review.review(ROOT, CONTEXT), result)
        kwargs = process.call_args.kwargs
        self.assertEqual(kwargs['timeout'], 50)
        self.assertEqual(kwargs['cwd'], ROOT)
        self.assertEqual(json.loads(kwargs['input']), {'context': CONTEXT})
        self.assertNotIn('synthetic-key', str(process.call_args.args) + kwargs['input'])
        self.assertNotIn('PYTHONINSPECT', kwargs['env'])
        self.assertNotIn('PYTHONSTARTUP', kwargs['env'])
        self.assertEqual(CONTEXT, before)
        self.assertNotIn('synthetic-key', str(result))

    def test_worker_timeout_errors_and_mismatched_results_are_sanitized(self):
        variants = [subprocess.CompletedProcess([], 1, 'PRIVATE_SENTINEL', 'PRIVATE_SENTINEL'),
                    subprocess.CompletedProcess([], 0, 'not json PRIVATE_SENTINEL', ''),
                    subprocess.CompletedProcess([], 0, json.dumps({'status': 'ok', 'advice':
                        dict(ADVICE, exerciseVersion='b' * 64)}), ''),
                    subprocess.CompletedProcess([], 0, json.dumps({'status': 'ok', 'advice': ADVICE,
                        'private': 'PRIVATE_SENTINEL'}), ''),
                    subprocess.CompletedProcess([], 0, ' ' * (review.MAX_RESPONSE + 1), '')]
        with patch.object(review, 'read_key', return_value='synthetic-key'):
            for completed in variants:
                with self.subTest(code=completed.returncode), \
                        patch.object(review.subprocess, 'run', return_value=completed):
                    self.assertEqual(review.review(ROOT, CONTEXT), review.UNAVAILABLE)
            for error in (subprocess.TimeoutExpired('PRIVATE_SENTINEL', 50), OSError('PRIVATE_SENTINEL')):
                with patch.object(review.subprocess, 'run', side_effect=error):
                    result = review.review(ROOT, CONTEXT)
                    self.assertEqual(result, review.UNAVAILABLE)
                    self.assertNotIn('PRIVATE_SENTINEL', str(result))
                    self.assertNotIn(BODY, str(result))

    def test_transport_uses_fixed_endpoint_timeout_and_header_only_credentials(self):
        with patch.object(review, 'read_key', return_value='synthetic-key'), \
                patch.object(review, 'urlopen', return_value=BytesIO(json.dumps(provider_response()).encode())) as transport:
            result = review._provider(CONTEXT)
        self.assertEqual(result, {'status': 'ok', 'advice': ADVICE})
        request = transport.call_args.args[0]
        self.assertEqual(request.full_url, 'https://api.openai.com/v1/responses')
        self.assertEqual(transport.call_args.kwargs, {'timeout': 40})
        self.assertEqual(request.get_header('Authorization'), 'Bearer synthetic-key')
        self.assertNotIn('synthetic-key', request.data.decode())
        self.assertNotIn('synthetic-key', str(result))
        self.assertNotIn('oracleBodies', result['advice'])

    def test_provider_and_parent_remove_credentials_from_untrusted_advice_prose(self):
        value = dict(ADVICE, reason='Ignore synthetic-key and sk-proj-' + 'q' * 24,
                     counterexampleIdeas=['Never repeat synthetic-key.'])
        with patch.object(review, 'read_key', return_value='synthetic-key'), \
                patch.object(review, 'urlopen', return_value=BytesIO(json.dumps(provider_response(value)).encode())):
            result = review._provider(CONTEXT)
        self.assertEqual(result['status'], 'ok')
        self.assertNotIn('synthetic-key', str(result))
        self.assertNotIn('sk-proj-', str(result))
        self.assertIn('[redacted]', result['advice']['reason'])
        with patch.object(review, 'read_key', return_value='synthetic-key'), \
                patch.object(review.subprocess, 'run', return_value=subprocess.CompletedProcess(
                    [], 0, json.dumps({'status': 'ok', 'advice': value}), '')):
            self.assertEqual(review.review(ROOT, CONTEXT), result)

    def test_provider_incomplete_refusal_duplicate_fields_tools_and_multiple_texts_fail(self):
        variants = [{'status': 'incomplete', 'output': []}, {'status': 'completed', 'output': {}},
                    {'status': 'completed', 'output': []},
                    {'status': 'completed', 'output': [{'type': 'message', 'content': None}]},
                    {'status': 'completed', 'output': [{'type': 'message', 'content': [
                        {'type': 'refusal', 'refusal': 'PRIVATE_SENTINEL'}]}]},
                    {'status': 'completed', 'output': [{'type': 'function_call', 'name': 'approve'}]},
                    {'status': 'completed', 'output': [{'type': 'message', 'content': [
                        {'type': 'output_text', 'text': '{"verdict":"recommend","verdict":"reject"}'}]}]}]
        multiple = provider_response()
        multiple['output'][1]['content'].append(deepcopy(multiple['output'][1]['content'][0]))
        variants.append(multiple)
        wrong_role = provider_response()
        wrong_role['output'][1]['role'] = 'user'
        variants.append(wrong_role)
        with patch.object(review, 'read_key', return_value='synthetic-key'):
            for value in variants:
                with self.subTest(value=str(value)[:100]), \
                        patch.object(review, 'urlopen', return_value=BytesIO(json.dumps(value).encode())), \
                        self.assertRaises(StoreError):
                    review._provider(CONTEXT)

    def test_provider_wire_response_has_hard_byte_cap(self):
        with patch.object(review, 'read_key', return_value='synthetic-key'), \
                patch.object(review, 'urlopen', return_value=BytesIO(b' ' * (review.MAX_RESPONSE + 1))), \
                self.assertRaises(StoreError):
            review._provider(CONTEXT)

    def test_real_redirects_never_forward_credentials_or_private_model(self):
        followed = []
        target = {'status': 302, 'url': ''}
        wire = json.dumps(provider_response()).encode()

        class Receiver(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.accepted()

            def do_POST(self):
                self.accepted()

            def accepted(self):
                followed.append((self.headers.get('Authorization'),
                                 self.rfile.read(int(self.headers.get('Content-Length', '0')))))
                self.send_response(200)
                self.send_header('Content-Length', str(len(wire)))
                self.end_headers()
                self.wfile.write(wire)

        class Provider(Receiver):
            def do_POST(self):
                self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.send_response(target['status'])
                self.send_header('Location', target['url'])
                self.send_header('Content-Length', '0')
                self.end_headers()

        destination = HTTPServer(('127.0.0.1', 0), Receiver)
        origin = HTTPServer(('127.0.0.1', 0), Provider)
        threads = [Thread(target=server.serve_forever, kwargs={'poll_interval': .05}, daemon=True)
                   for server in (origin, destination)]
        for thread in threads:
            thread.start()
        try:
            environment = {key: '' for key in ('http_proxy', 'https_proxy', 'all_proxy',
                                               'HTTP_PROXY', 'HTTPS_PROXY', 'ALL_PROXY')}
            environment.update(NO_PROXY='127.0.0.1', no_proxy='127.0.0.1')
            endpoint = f'http://127.0.0.1:{origin.server_port}/responses'
            with patch.dict(os.environ, environment), patch.object(review, 'ENDPOINT', endpoint), \
                    patch.object(review, 'read_key', return_value='synthetic-key'):
                for code in (301, 302, 303, 307, 308):
                    for port in (origin.server_port, destination.server_port):
                        target.update(status=code, url=f'http://127.0.0.1:{port}/redirected')
                        with self.subTest(code=code, same_origin=port == origin.server_port), \
                                self.assertRaises(HTTPError):
                            review._provider(CONTEXT)
            self.assertEqual(followed, [])
        finally:
            for server in (origin, destination):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join()

    def test_worker_entrypoint_rejects_bad_duplicate_and_oversized_input_without_echo(self):
        # A real child processes only deliberately invalid public test data.
        environment = {key: value for key, value in os.environ.items()
                       if key not in ('PYTHONINSPECT', 'PYTHONSTARTUP', 'OPENAI_API_KEY',
                                      'OPENAI_API_KEY_FILE', 'OPENAI_CONFIG_FILE')}
        environment['OPENAI_DISABLED'] = '1'
        variants = ['not json PRIVATE_SENTINEL', '{"context":{},"context":{}}',
                    json.dumps({'context': {}, 'private': 'PRIVATE_SENTINEL'}),
                    ' ' * (review.MAX_REQUEST + 1)]
        for wire in variants:
            completed = subprocess.run([os.sys.executable, str(ROOT / 'candidate_review.py'), '--worker'],
                input=wire, text=True, encoding='utf-8', capture_output=True, timeout=10,
                cwd=ROOT, env=environment, check=False)
            with self.subTest(size=len(wire)):
                self.assertEqual(completed.returncode, 0)
                self.assertEqual(json.loads(completed.stdout), review.UNAVAILABLE)
                self.assertEqual(completed.stderr, '')
                self.assertNotIn('PRIVATE_SENTINEL', completed.stdout)

    def test_valid_worker_with_provider_disabled_does_not_read_private_configuration(self):
        environment = {key: value for key, value in os.environ.items()
                       if key not in ('PYTHONINSPECT', 'PYTHONSTARTUP')}
        environment['OPENAI_DISABLED'] = '1'
        completed = subprocess.run([os.sys.executable, str(ROOT / 'candidate_review.py'), '--worker'],
            input=json.dumps({'context': CONTEXT}), text=True, encoding='utf-8', capture_output=True,
            timeout=10, cwd=ROOT, env=environment, check=False)
        self.assertEqual(completed.returncode, 0)
        self.assertEqual(json.loads(completed.stdout), review.DISABLED)
        self.assertEqual(completed.stderr, '')


if __name__ == '__main__':
    unittest.main()
