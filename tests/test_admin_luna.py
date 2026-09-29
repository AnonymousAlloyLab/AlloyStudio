"""The provider can suggest prose, never change protected upload code or names."""
from copy import deepcopy
from http.server import BaseHTTPRequestHandler, HTTPServer
from io import BytesIO
import json
import os
from pathlib import Path
import subprocess
from threading import Thread
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

import admin_luna as luna
from exercise_store import StoreError

ROOT = Path(__file__).resolve().parents[1]
SOURCE = 'sig Node {}\npred inv1C0 { some Node }\npred inv1C1 { not no Node }'
WITNESS = {'originalSource':SOURCE,'groups':[{'predicate':'inv1'}]}
GOOD = {'exercises':[{'predicate':'inv1','title':'Nonempty graph','question':'Require at least one node.'}]}


class AdminLunaTests(unittest.TestCase):
    def test_real_redirects_never_forward_credentials_or_source_to_another_recipient(self):
        # Two independent local origins construct the urllib bearer-forwarding
        # counterexample without using real credentials or external networking.
        followed = []
        redirect = {'status': 302, 'target': ''}
        payload = json.dumps({'status':'completed','output':[{'type':'message',
            'content':[{'type':'output_text','text':json.dumps(GOOD)}]}]}).encode()

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
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

        class Provider(Receiver):
            def do_POST(self):
                if self.path != '/responses':
                    return self.accepted()
                self.rfile.read(int(self.headers.get('Content-Length', '0')))
                self.send_response(redirect['status'])
                self.send_header('Location', redirect['target'])
                self.send_header('Content-Length', '0')
                self.end_headers()

        destination = HTTPServer(('127.0.0.1', 0), Receiver)
        origin = HTTPServer(('127.0.0.1', 0), Provider)
        threads = [Thread(target=host.serve_forever, kwargs={'poll_interval':0.05}, daemon=True)
                   for host in (origin, destination)]
        for thread in threads:
            thread.start()
        try:
            endpoint = f'http://127.0.0.1:{origin.server_port}/responses'
            proxy_environment = {key:'' for key in ('http_proxy','https_proxy','all_proxy',
                                                     'HTTP_PROXY','HTTPS_PROXY','ALL_PROXY')}
            proxy_environment.update(NO_PROXY='127.0.0.1', no_proxy='127.0.0.1')
            with patch.dict(os.environ, proxy_environment), patch.object(luna,'ENDPOINT',endpoint), \
                    patch.object(luna,'read_key',return_value='synthetic-redirect-placeholder'):
                for status in (301,302,303,307,308):
                    for port in (origin.server_port,destination.server_port):
                        redirect.update(status=status,target=f'http://127.0.0.1:{port}/redirected')
                        with self.subTest(status=status,same_origin=port == origin.server_port), \
                                self.assertRaises(HTTPError):
                            luna._provider(SOURCE,['inv1'],'')
            self.assertEqual(followed, [], 'No redirected recipient may receive a request.')
        finally:
            for host in (origin,destination):
                host.shutdown()
                host.server_close()
            for thread in threads:
                thread.join()

    def test_strict_metadata_payload_uses_required_model_and_no_storage(self):
        payload = luna.request_payload(SOURCE,['inv1'],'Teach existence.')
        self.assertEqual(payload['model'],'gpt-6-luna')
        self.assertIs(payload['store'],False)
        self.assertEqual(json.loads(payload['input']),dict(source=SOURCE,predicates=['inv1'],questionSeed='Teach existence.'))
        schema = payload['text']['format']['schema']
        self.assertIs(schema['additionalProperties'],False)
        item = schema['properties']['exercises']['items']
        self.assertEqual(set(item['properties']),{'predicate','title','question'})
        self.assertEqual(item['properties']['predicate']['enum'],['inv1'])
        self.assertIn('do not give Alloy code',payload['instructions'])

    def test_single_predicate_retains_its_original_nonconvention_name(self):
        item = {'predicate':'acyclic','title':'Cycles','question':'Rule out directed cycles.'}
        self.assertEqual(luna.validate_metadata({'exercises':[item]},['acyclic']),[item])
        self.assertEqual(luna.request_payload('pred acyclic { no none }',['acyclic'],'')['model'],'gpt-6-luna')

    def test_code_and_identity_changes_or_incomplete_suggestions_are_rejected(self):
        bad = [dict(GOOD,source='changed'),{'exercises':[]},
               {'exercises':[dict(GOOD['exercises'][0],predicate='inv1C0')]},
               {'exercises':[dict(GOOD['exercises'][0],body='no Node')]},
               {'exercises':[dict(GOOD['exercises'][0],environmentBefore='fact { no Node }')]},
               {'exercises':[GOOD['exercises'][0],GOOD['exercises'][0]]},
               {'exercises':[dict(GOOD['exercises'][0],title='')]},
               {'exercises':[dict(GOOD['exercises'][0],question='\ud800')]},
               {'exercises':[dict(GOOD['exercises'][0],question='x\0y')]},
               {'exercises':[dict(GOOD['exercises'][0],question='é'*5000)]}]
        for value in bad:
            with self.subTest(value=repr(value)[:80]), self.assertRaises(StoreError):
                luna.validate_metadata(value,['inv1'])

    def test_every_backend_name_present_once_and_restored_to_backend_order(self):
        second = dict(predicate='inv2',title='Other',question='Another property.')
        self.assertEqual(luna.validate_metadata({'exercises':[second,GOOD['exercises'][0]]},['inv1','inv2']),[GOOD['exercises'][0],second])
        with self.assertRaises(StoreError):
            luna.validate_metadata({'exercises':[second,second]},['inv1','inv2'])

    def test_no_key_keeps_manual_workflow_without_spawning_or_mutating_upload(self):
        before = deepcopy(WITNESS)
        with patch.object(luna,'read_key',return_value=''),patch.object(luna.subprocess,'run') as process:
            self.assertEqual(luna.suggest(ROOT,WITNESS,'')['status'],'disabled')
            process.assert_not_called()
        self.assertEqual(before,WITNESS)

    def test_suggestion_worker_has_hard_timeout_and_no_secret_in_prompt_or_arguments(self):
        response = json.dumps(dict(status='ok',**GOOD))
        with patch.object(luna,'read_key',return_value='unit-only-placeholder'), \
                patch.object(luna.subprocess,'run',return_value=subprocess.CompletedProcess([],0,response,'')) as process:
            result = luna.suggest(ROOT,WITNESS,'Existence')
        self.assertEqual(result,dict(status='ok',**GOOD))
        args = process.call_args
        self.assertEqual(args.kwargs['timeout'],50)
        self.assertNotIn('unit-only-placeholder',str(args.args)+args.kwargs['input'])
        self.assertEqual(json.loads(args.kwargs['input'])['source'],SOURCE)
        self.assertEqual(WITNESS['originalSource'],SOURCE)

    def test_worker_failures_and_bad_metadata_fall_back_without_disclosing_diagnostics(self):
        results = [subprocess.CompletedProcess([],1,'PRIVATE_SENTINEL','PRIVATE_SENTINEL'),
                   subprocess.CompletedProcess([],0,'not json',''),
                   subprocess.CompletedProcess([],0,json.dumps({'status':'ok','exercises':[dict(GOOD['exercises'][0],source='changed')]}),'')]
        with patch.object(luna,'read_key',return_value='unit-only-placeholder'):
            for completed in results:
                with self.subTest(code=completed.returncode),patch.object(luna.subprocess,'run',return_value=completed):
                    result = luna.suggest(ROOT,WITNESS,'')
                    self.assertEqual(result['status'],'unavailable')
                    self.assertNotIn('PRIVATE_SENTINEL',str(result))
            with patch.object(luna.subprocess,'run',side_effect=subprocess.TimeoutExpired('PRIVATE_SENTINEL',50)):
                self.assertEqual(luna.suggest(ROOT,WITNESS,'')['status'],'unavailable')

    def test_real_transport_shape_validates_structured_response_and_hides_key(self):
        response = {'status':'completed','output':[{'type':'message','content':[{'type':'output_text','text':json.dumps(GOOD)}]}]}
        with patch.object(luna,'read_key',return_value='unit-only-placeholder'), \
                patch.object(luna,'urlopen',return_value=BytesIO(json.dumps(response).encode())) as transport:
            result = luna._provider(SOURCE,['inv1'],'')
        self.assertEqual(result,dict(status='ok',**GOOD))
        request = transport.call_args.args[0]
        self.assertEqual(request.full_url,'https://api.openai.com/v1/responses')
        self.assertEqual(request.get_header('Authorization'),'Bearer unit-only-placeholder')
        self.assertNotIn('unit-only-placeholder',request.data.decode())
        self.assertNotIn('unit-only-placeholder',str(result))

    def test_duplicate_json_provider_fields_and_incomplete_output_fail(self):
        variants = [{'status':'incomplete','output':[]},
                    {'status':'completed','output':[{'type':'message','content':[{'type':'output_text','text':'{"exercises":[],"exercises":[]}'}]}]},
                    {'status':'completed','output':[{'type':'message','content':[{'type':'refusal','refusal':'PRIVATE_SENTINEL'}]}]}]
        with patch.object(luna,'read_key',return_value='unit-only-placeholder'):
            for response in variants:
                with self.subTest(response=response),patch.object(luna,'urlopen',return_value=BytesIO(json.dumps(response).encode())),self.assertRaises(StoreError):
                    luna._provider(SOURCE,['inv1'],'')

    def test_provider_response_and_question_seed_byte_caps(self):
        with patch.object(luna,'read_key',return_value='unit-only-placeholder'), \
                patch.object(luna,'urlopen',return_value=BytesIO(b' '*(luna.MAX_RESPONSE+1))), self.assertRaises(StoreError):
            luna._provider(SOURCE,['inv1'],'')
        with self.assertRaises(StoreError):
            luna.request_payload(SOURCE,['inv1'],'é'*4097)
