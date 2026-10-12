"""Production routes and completion pins under explicitly fake engine evidence."""
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server
from practice_dependencies import GENERATED_STARTER
from test_practice_progress import practice, METRICS
from test_sqlite_store import fixture, ROOT
from test_admin_candidates import perfect
from test_candidate_admission_gate import instance


class PracticeProgressHTTPTests(unittest.TestCase):
    def setUp(self):
        scratch = ROOT/'build/practice-progress/http'; scratch.mkdir(parents=True, exist_ok=True)
        self.folder = tempfile.TemporaryDirectory(dir=scratch)
        self.addCleanup(self.folder.cleanup)
        self.root = Path(self.folder.name); fixture(self.root)
        self.app = server.Portal(('127.0.0.1',0), root=self.root, engine_mode='oneshot',
            traffic_profile=server.TrafficProfile(public_burst=10000, peer_burst=10000))
        self.records = {'A':practice('A'), 'B':practice('B',['A'],['A']),
                        'C':practice('C',['B'],['A','B'])}
        self.records['B']['oracleBody'] = 'A[]'; self.records['C']['oracleBody'] = 'B[]'
        self.app.snapshot = SimpleNamespace(exercises=self.records,
            correct_pools={name:(record['oracleBody'],) for name,record in self.records.items()})
        self.thread = threading.Thread(target=self.app.serve_forever, daemon=True); self.thread.start()
        self.addCleanup(self.stop)
        self.url = 'http://127.0.0.1:'+str(self.app.server_port)
        self.channel = self.request('/api/channel',{})[1]['channel']
        self.revision = 0

    def stop(self):
        self.app.shutdown(); self.app.server_close(); self.thread.join()

    def request(self, path, data=None):
        request = Request(self.url+path, data=None if data is None else json.dumps(data).encode(),
                          headers={'Content-Type':'application/json'})
        try: response = urlopen(request, timeout=8)
        except HTTPError as error: response = error
        with response: return response.status, json.loads(response.read()), response.headers

    def engine(self, kind, payload):
        if kind == 'feedback':
            return dict(status='ok', metric=METRICS[payload['metric']], distance=0,
                canonicalForm=[], operations=[], comparison=dict(strategy='nearest-known-correct',
                    poolSize=len(payload['referenceBodies']), evaluatedCandidates=len(payload['referenceBodies']), complete=True))
        evidence = perfect()
        for category in evidence['categories']:
            if category['instances']:
                category['instances'] = [instance(['Node$0'], [] if category['id']=='both'
                                                   else [['Node$0','Node$0']])]
        return evidence

    def payload(self, name, body=None, tokens=(), metric='canonical'):
        self.revision += 1
        return dict(exerciseId=name, body=body or self.records[name]['oracleBody'],
                    revision=self.revision, channel=self.channel, metric=metric,
                    prerequisiteTokens=list(tokens))

    def complete(self, name, tokens=(), body=None, metric='canonical'):
        payload = self.payload(name,body,tokens,metric)
        with patch.object(self.app,'_engine',side_effect=self.engine) as calls:
            status, structural, _ = self.request('/api/feedback',payload)
            self.assertEqual(status,200); self.assertEqual(structural['status'],'ok')
            status, behavior, _ = self.request('/api/behavior',dict(payload,evidenceToken=structural['evidenceToken']))
            self.assertEqual(status,200); self.assertEqual(behavior['status'],'ok')
            self.assertRegex(behavior['completionToken'],r'^[A-Za-z0-9_-]{43}$')
            self.assertEqual(calls.call_count,2)
        return behavior, payload

    def test_locked_requests_and_progress_checks_launch_no_engine_or_provider(self):
        with patch.object(self.app,'_engine',side_effect=AssertionError('No solve')), \
             patch.object(self.app.explainer,'explain',side_effect=AssertionError('No provider')):
            for path in ('/api/feedback','/api/behavior','/api/explain'):
                status,result,_ = self.request(path,self.payload('B',tokens=['x'*43]))
                self.assertEqual(status,423); self.assertEqual(result['status'],'locked')
            status,result,headers = self.request('/api/progress',{'completionTokens':['x'*43]})
            self.assertEqual(status,200); self.assertEqual(result['expiredTokens'],['x'*43])
            self.assertEqual(headers['Cache-Control'],'no-store')

    def test_public_dependencies_and_slots_never_project_private_plans(self):
        _,summary,_ = self.request('/api/exercises')
        item = next(x for x in summary['exercises'] if x['id']=='B')
        self.assertEqual(item['dependencies'][0]['id'],'A')
        _,detail,_ = self.request('/api/exercises/B')
        self.assertEqual(detail['dependencySlots'][0]['part'],'environmentBefore')
        self.assertNotIn('practicePlan',detail)
        self.assertNotIn('validationEnvironmentBefore',json.dumps(detail))
        self.assertIn(GENERATED_STARTER,detail['environmentBefore'])

    def test_completion_reuses_existing_pin_and_unlocks_transitive_chain(self):
        a,_ = self.complete('A'); b,_ = self.complete('B',[a['completionToken']])
        c,_ = self.complete('C',[b['completionToken']])
        status,result,_ = self.request('/api/progress',dict(completionTokens=[a['completionToken'],b['completionToken'],c['completionToken']]))
        self.assertEqual(status,200); self.assertEqual(len(result['completions']),3)
        self.assertEqual(result['completions'][-1]['prerequisiteTokens'],[b['completionToken']])

    def test_both_engine_models_use_verified_learner_code_not_blank_oracle_slots(self):
        a,_ = self.complete('A',body='no n: Node | n in n.adj')
        payload = self.payload('B',tokens=[a['completionToken']])
        with patch.object(self.app,'_engine',side_effect=self.engine) as calls:
            _,feedback,_ = self.request('/api/feedback',payload)
            self.request('/api/behavior',dict(payload,evidenceToken=feedback['evidenceToken']))
        for call in calls.call_args_list:
            wire = call.args[1]
            self.assertIn('no n: Node | n in n.adj',wire['studentSource'])
            self.assertNotIn(GENERATED_STARTER,wire['studentSource'])
            if call.args[0]=='behavior':
                self.assertIn('no n: Node | n in n.adj',wire['oracleSource'])
            else:
                self.assertIn('no n: Node | n in n.adj',wire['referencePrefix'])
            self.assertIn('blockedPredicates',wire)

    def test_mismatched_body_metric_channel_or_generation_pin_never_mints(self):
        for change in ('body','metric','channel','generation'):
            payload = self.payload('A')
            with patch.object(self.app,'_engine',side_effect=self.engine):
                _,feedback,_ = self.request('/api/feedback',payload)
                changed = dict(payload,evidenceToken=feedback['evidenceToken'])
                if change=='body': changed['body']='no n: Node | n in n.adj'
                elif change=='metric': changed['metric']='ast'
                elif change=='channel': changed['channel']=self.request('/api/channel',{})[1]['channel']
                else: self.app.snapshot=self.app.snapshot
                _,result,_ = self.request('/api/behavior',changed)
            self.assertNotIn('completionToken',result,change)

    def test_context_change_cannot_mix_feedback_or_behavior_or_luna_pins(self):
        old,_ = self.complete('A'); new,_ = self.complete('A',body='no n: Node | n in n.adj')
        payload = self.payload('B',tokens=[old['completionToken']])
        with patch.object(self.app,'_engine',side_effect=self.engine):
            _,feedback,_ = self.request('/api/feedback',payload)
            _,behavior,_ = self.request('/api/behavior',dict(payload,evidenceToken=feedback['evidenceToken']))
        changed = dict(payload,prerequisiteTokens=[new['completionToken']],evidenceToken=feedback['evidenceToken'],
                       behaviorToken=behavior['behaviorToken'])
        with patch.object(self.app,'_engine',side_effect=self.engine), \
             patch.object(self.app.explainer,'explain',side_effect=AssertionError('Mixed context')):
            _,result,_ = self.request('/api/explain',changed)
            self.assertEqual(result['status'],'superseded')
            changed['revision'] += 1
            _,result,_ = self.request('/api/explain',changed)
        self.assertEqual(result['status'],'unavailable')

    def test_wrong_or_nonzero_evidence_cannot_issue_a_receipt(self):
        payload = self.payload('A')
        def nonzero(kind,wire):
            result = self.engine(kind,wire)
            if kind=='feedback': result['distance']=1
            return result
        with patch.object(self.app,'_engine',side_effect=nonzero):
            _,feedback,_ = self.request('/api/feedback',payload)
            _,behavior,_ = self.request('/api/behavior',dict(payload,evidenceToken=feedback['evidenceToken']))
        self.assertNotIn('completionToken',behavior)
        with patch.object(self.app,'_engine',side_effect=self.engine):
            _,behavior,_ = self.request('/api/behavior',dict(self.payload('A'),evidenceToken='0'*64))
        self.assertNotIn('completionToken',behavior)

    def test_malformed_token_queries_and_extra_prerequisites_are_bounded(self):
        with patch.object(self.app,'_engine',side_effect=AssertionError('No solve')):
            for values in ([None], ['x'], ['x'*43]*201, ['x'*43]*2):
                self.assertEqual(self.request('/api/progress',{'completionTokens':values})[0],400)
            self.assertEqual(self.request('/api/progress',{'completionTokens':[],'solved':True})[0],400)
            self.assertEqual(self.request('/api/feedback',self.payload('A',tokens=['x'*43]))[0],423)
            self.assertEqual(self.request('/api/feedback',self.payload('B',tokens=['x']))[0],400)


if __name__ == '__main__':
    unittest.main()
