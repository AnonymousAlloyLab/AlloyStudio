"""Independent observations and constructed counterexamples; no JVM/provider."""
from copy import deepcopy
import importlib.util
import json
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
loader = importlib.util.spec_from_file_location('traffic_observation', ROOT / 'scripts/traffic_observation.py')
observation = importlib.util.module_from_spec(loader)
loader.loader.exec_module(observation)


def unavailable(coordinate):
    return dict(status='unavailable', coordinateSystem=coordinate, offsetEncoding='utf-16',
                ranges=[], reason='No learner location.')


def feedback():
    operations = []
    for start in (0, 11):
        operations.append(dict(kind='replace', component='matrix', path=f'matrix[{start}]', cost=1,
            aggregate=False, description='Inspect this operator.', replacementOperator='no',
            sourceLocation=dict(status='located', coordinateSystem='body', offsetEncoding='utf-16',
                precision='node', reason='Selected occurrence.', ranges=[dict(start=start, end=start+6,
                    text='some A', startLine=1, startColumn=start+1, endLine=1, endColumn=start+7,
                    moduleLine=3, moduleColumn=start+1)]), canonicalLocation=unavailable('canonical')))
    return dict(status='ok', metric='acgn-fast-rewrite-canonical-distance', distance=2,
        breakdown=dict(temporal=0, quantifier=0, matrix=2), canonicalForm=['some A and some A'],
        operations=operations, operationSummary=dict(replace=2), diagnostics=[],
        trace=dict(cost=2, matchesDistance=True, hasAggregates=False, kind='redacted-framework-feedback',
            certifiedOptimalScript=False, note='Hints only.', matrixReplayVerified=True,
            matrixTraceAlgorithm='ordered-dp-unordered-assignment-v1', quantifierCostVerified=True,
            components=[dict(component=name, distance=count, hintCount=count, countMatchesDistance=True)
                        for name,count in [('temporal',0),('quantifier',0),('matrix',2)]]),
        comparison=dict(strategy='nearest-known-correct', poolSize=2, evaluatedCandidates=2, complete=True))


def behavior():
    instance=dict(traceLength=1, loopState=0, truncated=False, stringsAnonymized=False,
        states=[dict(index=0, signatures=[dict(label='A', atoms=['A$0','A$1'])],
            relations=[dict(label='A.r',arity=2,tuples=[['A$0','A$1']])])])
    return dict(status='ok', metric='acgn-reward', score=.25, scoreStatus='ok', scoreReason='OK',
        scope=dict(overall=3, bitwidth=3, maxSequence=3, poolSize=100, minTrace=1, maxTrace=10, moduleFacts=True),
        sampling=dict(positiveTested=4, positiveAccepted=2, negativeTested=4, negativeRejected=2, semanticCounterexamples=0),
        categories=[dict(id=name,oracle=flags[0],student=flags[1],status='sat',enumerationComplete=True,
            instances=[deepcopy(instance)]) for name,flags in
            [('both',(True,True)),('undercoverage',(True,False)),('overcoverage',(False,True)),('neither',(False,False))]])


class IndependentObservationTests(unittest.TestCase):
    def test_pinned_baseline_sources_and_public_projection_inventory(self):
        bound=observation.check_baseline()
        self.assertEqual(bound['commit'],'e75b20419f8f92c2adc59ce083008ec897434cb1')
        self.assertEqual(len(bound['sources']),10)

    def test_valid_full_feedback_and_behavior(self):
        self.assertEqual(observation.validate_observation('feedback',feedback(),dict(body='some A and some A',poolSize=2)),[])
        self.assertEqual(observation.validate_observation('behavior',behavior()),[])

    def test_dictionary_order_is_not_semantic_but_operation_order_is(self):
        original=feedback();candidate={key:original[key] for key in reversed(original)}
        candidate['breakdown']=dict(matrix=2,quantifier=0,temporal=0)
        self.assertEqual(observation.compare_observations('feedback',original,candidate)['status'],'PASS')
        candidate=deepcopy(original);candidate['operations'].reverse()
        self.assertEqual(observation.compare_observations('feedback',original,candidate)['code'],'SEMANTIC_MISMATCH')

    def test_two_identical_terms_keep_distinct_structural_occurrences(self):
        original=feedback();candidate=deepcopy(original)
        candidate['operations'][0]['sourceLocation']=deepcopy(candidate['operations'][1]['sourceLocation'])
        self.assertEqual(observation.compare_observations('feedback',original,candidate,
            {'body':'some A and some A'})['code'],'SEMANTIC_MISMATCH')

    def test_nested_target_disclosure_fails_closed(self):
        value=feedback();value['operations'][0]['targetTerm']='PRIVATE_CANARY'
        errors=observation.validate_observation('feedback',value)
        self.assertTrue(errors)
        self.assertNotIn('PRIVATE_CANARY',json.dumps(errors))
        value=behavior();value['categories'][0]['instances'][0]['states'][0]['oracleSource']='PRIVATE_CANARY'
        self.assertTrue(observation.validate_observation('behavior',value))

    def test_distance_and_partial_pool_mutations_are_rejected(self):
        for mutate in (lambda x:x.update(distance=0),lambda x:x['comparison'].update(evaluatedCandidates=1),
                       lambda x:x['comparison'].update(complete=False),lambda x:x['operations'][0].update(cost=2)):
            value=feedback();mutate(value)
            self.assertTrue(observation.validate_observation('feedback',value))

    def test_context_pool_and_metric_are_exact(self):
        self.assertTrue(observation.validate_observation('feedback',feedback(),{'poolSize':3}))
        self.assertTrue(observation.validate_observation('feedback',feedback(),{'metric':'ast'}))

    def test_unknown_fields_and_boolean_numeric_values_are_rejected(self):
        for field,value in [('distance',True),('distance',float('inf')),('distance',-1),('private',42)]:
            data=feedback();data[field]=value
            self.assertTrue(observation.validate_observation('feedback',data))

    def test_malformed_utf16_coordinates_and_stale_span_text_reject(self):
        value=feedback();value['operations'][0]['sourceLocation']['ranges'][0]['text']='no A'
        self.assertTrue(observation.validate_observation('feedback',value,{'body':'some A and some A'}))
        value=feedback();value['operations'][0]['sourceLocation']['ranges'][0].update(start=1,end=2,text='x')
        self.assertTrue(observation.validate_observation('feedback',value,{'body':'😀some A and some A'}))
        value=feedback();value['operations'][0]['sourceLocation']['ranges'][0]['startColumn']=2
        self.assertTrue(observation.validate_observation('feedback',value,{'body':'some A and some A'}))

    def test_operator_vocabulary_is_not_a_target_expression(self):
        value=feedback();value['operations'][0]['replacementOperator']='all a: A | no a.r'
        self.assertTrue(observation.validate_observation('feedback',value))
        value=feedback();value['operations'][0]['kind']='insert';value['operationSummary']={'insert':1,'replace':1}
        self.assertTrue(observation.validate_observation('feedback',value))

    def test_catalogue_preserves_structured_source_provenance(self):
        summary={key:key for key in ('id','title','group','predicate','description')}
        detail=dict(summary,environmentBefore='sig A {}',environmentAfter='',predicateHeader='pred inv1 ',
                    starter='some A',source={'path':'models/example.als','sha256':'f'*64})
        self.assertEqual(observation.validate_observation('catalogue',{'exercises':[summary]}),[])
        self.assertEqual(observation.validate_observation('exercise',detail),[])
        detail['source']['oracleBody']='PRIVATE'
        self.assertTrue(observation.validate_observation('exercise',detail))

    def test_duplicate_or_ambiguous_node_location_is_rejected(self):
        for variant in ('duplicate','ambiguous'):
            value=feedback();location=value['operations'][0]['sourceLocation']
            location['status']='ambiguous';location['ranges']*=2
            if variant=='duplicate':location['precision']='related'
            self.assertTrue(observation.validate_observation('feedback',value))

    def test_category_truth_order_and_score_are_not_nondeterminism(self):
        for mutate in (lambda x:x['categories'].reverse(), lambda x:x['categories'][0].update(student=False),
                       lambda x:x.update(score=.251),lambda x:x['scope'].update(moduleFacts=False)):
            value=behavior();mutate(value)
            self.assertTrue(observation.validate_observation('behavior',value))

    def test_witness_atom_or_tuple_changes_are_semantic(self):
        original=behavior();candidate=deepcopy(original)
        candidate['categories'][0]['instances'][0]['states'][0]['relations'][0]['tuples'][0].reverse()
        self.assertEqual(observation.compare_observations('behavior',original,candidate)['code'],'SEMANTIC_MISMATCH')

    def test_no_more_than_three_witnesses_and_incomplete_enumeration_reject(self):
        value=behavior();value['categories'][0]['instances']*=4
        self.assertTrue(observation.validate_observation('behavior',value))
        value=behavior();value['categories'][0]['enumerationComplete']=False
        self.assertTrue(observation.validate_observation('behavior',value))

    def test_failure_equality_is_not_a_successful_analysis(self):
        for kind in ('feedback','behavior','explanation'):
            value={'status':'timeout','message':'Try again.'}
            result=observation.compare_observations(kind,value,value)
            self.assertEqual(result['status'],'PASS');self.assertFalse(result['successfulAnalysis'])
            self.assertEqual(observation.compare_observations(kind,value,{'status':'error','message':'Try again.'})['status'],'BLOCKED')

    def test_http_error_object_is_supported_without_coercing_to_success(self):
        for kind in ('feedback','behavior','explanation'):
            result=observation.compare_observations(kind,{'error':'Invalid JSON.'},{'error':'Invalid JSON.'})
            self.assertEqual(result['status'],'PASS');self.assertFalse(result['successfulAnalysis'])

    def test_delivery_changes_are_checked_then_separated(self):
        left=feedback();left.update(exerciseId='x',revision=2,requestedMetric='canonical',evidenceToken='a'*64)
        right=deepcopy(left);right['evidenceToken']='b'*64
        context=dict(exerciseId='x',revision=2,requestedMetric='canonical')
        self.assertEqual(observation.compare_observations('feedback',left,right,context)['status'],'PASS')
        right['revision']=3
        self.assertEqual(observation.compare_observations('feedback',left,right,context)['status'],'BLOCKED')

    def test_educational_order_ids_and_wording_are_exact(self):
        left=dict(status='ok',model='gpt-6-luna',operations=[{'id':'operation-1','description':'Inspect the operator.'}],instances=[],summary='Try one change.')
        self.assertEqual(observation.validate_observation('explanation',left,{'operationsIds':['operation-1'],'instancesIds':[]}),[])
        right=deepcopy(left);right['operations'][0]['id']='operation-2'
        self.assertTrue(observation.validate_observation('explanation',right,{'operationsIds':['operation-1']}))
        right=deepcopy(left);right['summary']='Try another change.'
        self.assertEqual(observation.compare_observations('explanation',left,right)['code'],'SEMANTIC_MISMATCH')

    def test_all_admin_dashboard_control_leaves_are_preserved(self):
        for kind in ('admin','dashboard','control'):
            left={'nested':{'items':[None,True,1,1.5,'value']}}
            right=deepcopy(left);right['nested']['items'][0]='new'
            self.assertEqual(observation.compare_observations(kind,left,right)['code'],'SEMANTIC_MISMATCH')
            self.assertEqual(observation.semantic_observation(kind,[1,2]),b'[1,2]')
            self.assertTrue(observation.validate_observation(kind,{'value':math.nan}))

    def test_unknown_kind_missing_fields_and_lone_surrogates_fail_closed(self):
        self.assertTrue(observation.validate_observation('new-kind',{}))
        self.assertTrue(observation.validate_observation('feedback',{'status':'ok'}))
        self.assertTrue(observation.validate_observation('transportError',{'error':'\ud800'}))


if __name__=='__main__':
    unittest.main()
