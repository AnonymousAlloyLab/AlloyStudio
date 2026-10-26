"""Production prompt/provider integration for learner-only structural retrieval."""
from copy import deepcopy
import json
import unittest
from unittest.mock import patch

import luna
from test_education import expected, response, trace


def structure(operator='some'):
    return {'version': 1, 'status': 'ok', 'representation': 'learner-canonical-occurrences',
            'phases': [{'index': 0, 'bindingCount': 0}], 'nodes': [
                {'address': ['T', '_', []], 'nodeId': 'n0', 'category': 'TEMPORAL', 'childCount': 0},
                {'address': [0, 'M', []], 'nodeId': 'n1', 'category': 'MULTIPLICITY',
                 'operator': operator, 'childCount': 1},
                {'address': [0, 'M', [0]], 'nodeId': 'n2', 'category': 'REFERENCE', 'childCount': 0}]}


def feedback():
    value = trace(1)
    value['canonicalStructure'] = structure()
    value['operations'][0].update(path='normalForm[0].matrix', sourceOperator='some',
                                  sourceNodeKind='formula', sourceRole='affected')
    return value


class StructuralGuidanceTests(unittest.TestCase):
    def test_prompt_contains_checked_crud_exact_neighborhood_and_question(self):
        evidence = luna.prompt_education(feedback(), 'some A', question='Require an empty set.')
        context = evidence['structuralContext']
        self.assertEqual(context['stageMode'], 'immutable-learner-snapshot')
        self.assertEqual(context['status'], 'ok')
        step = context['steps'][0]
        self.assertEqual(step['id'], evidence['trace']['operations'][0]['id'])
        self.assertEqual(step['dsl'], 'UPDATE MULTIPLICITY [0,M,[]] TO "no";')
        self.assertEqual(step['focus']['operator'], 'some')
        self.assertEqual(step['children'][0]['address'], [0, 'M', [0]])
        self.assertEqual(evidence['question'], 'Require an empty set.')
        self.assertIn('ASRL/1 CRUD', luna.INSTRUCTIONS)
        self.assertIn('SAME original learner snapshot', luna.INSTRUCTIONS)
        self.assertIn('TO ? means the target is withheld', luna.INSTRUCTIONS)
        self.assertIn('FULL learner condition', luna.INSTRUCTIONS)
        self.assertIn('excluding the current node', luna.INSTRUCTIONS)
        self.assertIn('need not be a separate', luna.INSTRUCTIONS)

    def test_unknown_targets_and_supplied_dsl_are_not_prompt_fields(self):
        raw = feedback()
        raw['canonicalStructure']['oracle'] = 'PRIVATE_GRAPH'
        raw['canonicalStructure']['phases'][0]['bindingNames'] = ['PRIVATE_BINDING']
        for node in raw['canonicalStructure']['nodes']:
            node.update(target='PRIVATE_TARGET', sourceName='PRIVATE_NODE', dsl='PRIVATE_COMMAND')
        raw['operations'][0].update(dsl='PRIVATE_OPERATION', targetTerm='PRIVATE_EXPRESSION')
        evidence = luna.prompt_education(raw, 'some A')
        self.assertNotIn('PRIVATE_', json.dumps(evidence))
        self.assertEqual(evidence['structuralContext']['steps'][0]['status'], 'ok')

    def test_missing_or_invalid_optional_graph_does_not_disable_existing_explanations(self):
        for graph in (None, {}, {'version': 999}, dict(structure(), nodes=[])):
            raw = feedback()
            raw['canonicalStructure'] = graph
            evidence = luna.prompt_education(raw, 'some A')
            self.assertEqual(evidence['structuralContext']['status'], 'unavailable')
            self.assertEqual(evidence['trace']['operations'][0]['id'], 'operation-1')
            self.assertEqual(evidence['trace']['distance'], 1)

    def test_context_is_bound_to_real_revision_and_dependency_context(self):
        first = luna.prompt_education(feedback(), 'some A', context_identity='a'*64)
        same = luna.prompt_education(feedback(), 'some A', context_identity='a'*64)
        changed = luna.prompt_education(feedback(), 'some A // new revision', context_identity='a'*64)
        dependency = luna.prompt_education(feedback(), 'some A', context_identity='b'*64)
        self.assertEqual(first['structuralContext']['snapshotId'], same['structuralContext']['snapshotId'])
        self.assertNotEqual(first['structuralContext']['snapshotId'], changed['structuralContext']['snapshotId'])
        self.assertNotEqual(first['structuralContext']['snapshotId'], dependency['structuralContext']['snapshotId'])

    def test_ast_does_not_gain_canonical_locations(self):
        raw = dict(feedback(), metric='acgn-raw-ast-zhang-shasha-distance', breakdown={'ast': 1}, canonicalForm=[])
        raw['operations'][0].update(component='ast', path='ast[0]')
        evidence = luna.prompt_education(raw, 'some A')
        self.assertEqual(evidence['structuralContext']['status'], 'not-applicable')
        self.assertNotIn('steps', evidence['structuralContext'])

    def test_one_provider_call_carries_crud_and_cache_binds_structure(self):
        calls = []
        def transport(request, **kwargs):
            evidence = json.loads(json.loads(request.data)['input'])
            calls.append(evidence)
            return response(expected(evidence))
        client = luna.Explainer(transport=transport, key_reader=lambda: 'SYNTHETIC_GUIDANCE_KEY')
        raw = feedback()
        with patch('subprocess.Popen', side_effect=AssertionError('Retrieval cannot launch a JVM')):
            first = client.explain(raw, student_body='some A')
            self.assertEqual(first['status'], 'ok')
            self.assertEqual(client.explain(raw, student_body='some A'), first)
            changed = deepcopy(raw)
            changed['canonicalStructure']['nodes'][2]['category'] = 'VARIABLE_USE'
            self.assertEqual(client.explain(changed, student_body='some A')['status'], 'ok')
        self.assertEqual(len(calls), 2)
        self.assertNotEqual(calls[0]['structuralContext']['snapshotId'], calls[1]['structuralContext']['snapshotId'])
        self.assertEqual(calls[0]['structuralContext']['steps'][0]['focus']['operator'], 'some')

    def test_optional_retrieval_never_overflows_existing_prompt_cap(self):
        raw = feedback()
        raw['canonicalForm'] = ['x'*luna.MAX_CANONICAL_BYTES]
        body, question = 'a'*8192, 'q'*8192
        evidence = luna.prompt_education(raw, body, question=question)
        self.assertLessEqual(len(json.dumps(evidence, ensure_ascii=False, separators=(',', ':')).encode()),
                             luna.MAX_EDUCATION_INPUT_BYTES)
        self.assertLessEqual(len(json.dumps(evidence['structuralContext'], ensure_ascii=False,
                                           separators=(',', ':')).encode()), 32768)
        self.assertEqual(evidence['canonicalForm'], raw['canonicalForm'])


if __name__ == '__main__':
    unittest.main()
