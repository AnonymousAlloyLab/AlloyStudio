"""Finite learner-only positional retrieval contracts and constructed counterexamples."""
import copy
import json
import unittest

import canonical_retrieval as r
import repair_language as dsl


def node(address, identity, category, operator=None, count=0):
    result = dict(address=address, nodeId=identity, category=category, childCount=count)
    if operator is not None:
        result['operator'] = operator
    return result


def structure():
    # Same graph node n1 at two distinct occurrences with equal printed labels.
    return {'version': 1, 'status': 'ok', 'representation': r.REPRESENTATION,
            'phases': [{'index': 0, 'bindingCount': 1}], 'nodes': [
                node(['T', '_', []], 'n8', 'TEMPORAL', count=1),
                node(['T', '_', [0]], 'n9', 'TEMPORAL', 'always'),
                node([0, 0, []], 'n7', 'BINDING', 'all'),
                node([0, 'M', []], 'n0', 'CONTROL', 'and', 2),
                node([0, 'M', [0]], 'n1', 'MULTIPLICITY', 'no', 1),
                node([0, 'M', [0, 0]], 'n2', 'REFERENCE'),
                node([0, 'M', [1]], 'n1', 'MULTIPLICITY', 'no', 1),
                node([0, 'M', [1, 0]], 'n2', 'REFERENCE')]}


def operation(**updates):
    result = dict(kind='replace', component='matrix', cost=1, aggregate=False,
                  path='normalForm[0].matrix.child[1]', sourceOperator='no',
                  sourceNodeKind='formula', replacementOperator='some')
    result.update(updates)
    if result['kind'] not in ('replace', 'modify'):
        result.pop('replacementOperator', None)
    return result


def feedback(ops=None):
    ops = [operation()] if ops is None else ops
    return dict(status='ok', metric=r.CANONICAL_METRIC, distance=sum(op['cost'] for op in ops),
                operations=ops, canonicalForm=['all v : A | no v.r and no v.r'],
                canonicalStructure=structure())


def size(value):
    return len(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode())


class StructuralProjectionTests(unittest.TestCase):
    def test_projection_is_name_free_and_detached(self):
        raw = structure()
        raw['oracle'] = 'PRIVATE_TARGET'
        raw['nodes'][0].update(name='PRIVATE_NAME', expression='PRIVATE_EXPRESSION')
        clean = r.sanitize_structure(raw)
        self.assertEqual(clean, structure())
        self.assertNotIn('PRIVATE', repr(clean))
        clean['nodes'][0]['address'][2].append(5)
        self.assertEqual(raw['nodes'][0]['address'][2], [])

    def test_shared_node_has_distinct_occurrences_and_consistent_metadata(self):
        clean = r.sanitize_structure(structure())
        self.assertEqual(clean['status'], 'ok')
        raw = structure()
        raw['nodes'][-2]['operator'] = 'some'
        self.assertEqual(r.sanitize_structure(raw)['status'], 'unavailable')

    def test_malformed_structural_evidence_never_enters_index(self):
        edits = [lambda s: s['nodes'].append(copy.deepcopy(s['nodes'][0])),
                 lambda s: s['nodes'][0].update(category='PRIVATE_NAME'),
                 lambda s: s['nodes'][0].update(operator='some PRIVATE_TARGET'),
                 lambda s: s['nodes'][0].update(nodeId='oracle-node'),
                 lambda s: s['nodes'][0].update(childCount=True),
                 lambda s: s['nodes'][0].update(address=[True, 'M', []]),
                 lambda s: s['nodes'][0].update(address=['T', '_', [0]*65]),
                 lambda s: s['nodes'][1].update(address=['T', '_', [2]]),
                 lambda s: s['nodes'][2].update(category='REFERENCE'),
                 lambda s: s['phases'][0].update(index=1),
                 lambda s: s['phases'][0].update(bindingCount=4097),
                 lambda s: s['nodes'].pop(),
                 lambda s: s.update(version=True),
                 lambda s: s.update(nodes=[s['nodes'][0]]*2049)]
        for edit in edits:
            raw = structure()
            edit(raw)
            with self.subTest(edit=edit):
                self.assertEqual(r.sanitize_structure(raw)['status'], 'unavailable')

    def test_partial_evidence_retains_exact_existing_occurrences(self):
        raw = structure()
        raw.update(status='partial', reason='time_limit')
        raw['nodes'].pop()
        clean = r.sanitize_structure(raw)
        self.assertEqual(clean['status'], 'partial')
        source = feedback(); source['canonicalStructure'] = raw
        prepared = r.prepare(source)
        found = prepared.retrieve(prepared.snapshot_id, address=[0, 'M', [1]])
        self.assertEqual(found['status'], 'partial')
        self.assertTrue(found['truncated'])
        self.assertEqual(found['children'], [])
        missing = prepared.retrieve(prepared.snapshot_id, address=[0, 'M', [1, 0]])
        self.assertEqual(missing['status'], 'unavailable')

    def test_shared_node_children_cannot_disagree(self):
        raw = structure()
        raw['nodes'][-1]['nodeId'] = 'n10'
        self.assertEqual(r.sanitize_structure(raw)['status'], 'unavailable')

    def test_partial_cannot_invent_missing_parent(self):
        raw = structure()
        raw.update(status='partial', reason='node_limit')
        raw['nodes'].pop(6)
        self.assertEqual(r.sanitize_structure(raw)['status'], 'unavailable')


class CanonicalRetrievalTests(unittest.TestCase):
    def test_update_dsl_and_exact_repeated_occurrence(self):
        prepared = r.prepare(feedback(), 'no x.r and no x.r', question='Each node has a successor')
        result = prepared.retrieve(prepared.snapshot_id, operation_index=0)
        self.assertEqual(result['dsl'], 'UPDATE MULTIPLICITY [0,M,[1]] TO "some";')
        self.assertEqual(result['focus']['address'], [0, 'M', [1]])
        self.assertEqual(result['siblings'][0]['address'], [0, 'M', [0]])
        self.assertEqual(result['focus']['nodeId'], result['siblings'][0]['nodeId'])
        self.assertEqual(result['children'][0]['address'], [0, 'M', [1, 0]])
        self.assertEqual(result['bindings'][0]['category'], 'BINDING')
        parsed = dsl.parse('ON "'+prepared.snapshot_id+'" { '+result['dsl']+' }')
        self.assertEqual(parsed.statements[0].address, dsl.Address(0, None, (1,)))

    def test_read_does_not_shift_after_delete_or_mutate_source(self):
        source = feedback([operation(kind='delete'), operation()])
        original = copy.deepcopy(source)
        prepared = r.prepare(source)
        first = prepared.retrieve(prepared.snapshot_id, operation_index=0)
        second = prepared.retrieve(prepared.snapshot_id, operation_index=1)
        read = prepared.retrieve(prepared.snapshot_id, address=[0, 'M', [1]])
        self.assertEqual(first['focus'], second['focus'])
        self.assertEqual(read['cost'], 0)
        self.assertEqual(read['dsl'], 'READ [0,M,[1]];')
        self.assertEqual(source, original)
        first['focus']['operator'] = 'or'
        source['canonicalStructure']['nodes'][-2]['operator'] = 'one'
        self.assertEqual(prepared.retrieve(prepared.snapshot_id, operation_index=0)['focus']['operator'], 'no')

    def test_snapshot_binds_every_relevant_pass_input(self):
        source = feedback()
        base = r.prepare(source, 'body', question='question', context_identity='environment').snapshot_id
        variants = [r.prepare(source, 'changed', question='question', context_identity='environment'),
                    r.prepare(source, 'body', question='changed', context_identity='environment'),
                    r.prepare(source, 'body', question='question', context_identity='changed')]
        for field, value in [('canonicalForm', ['different']), ('operations', [operation(replacementOperator='one')])]:
            changed = copy.deepcopy(source); changed[field] = value
            variants.append(r.prepare(changed, 'body', question='question', context_identity='environment'))
        self.assertTrue(all(v.snapshot_id != base for v in variants))
        prepared = r.prepare(source, 'body', question='question', context_identity='environment')
        self.assertEqual(prepared.retrieve('0'*64, operation_index=0)['reason'], 'snapshot_mismatch')

    def test_ignored_target_fields_cannot_change_payload_or_snapshot(self):
        source = feedback(); expected = r.build_context(source)
        source['canonicalStructure']['oracle'] = 'PRIVATE_TARGET'
        source['canonicalStructure']['nodes'][0]['name'] = 'PRIVATE_NAME'
        source['operations'][0].update(replacementExpression='PRIVATE_TARGET',
                                       dsl='DELETE EVERYTHING', sourceTerm='PRIVATE_LEARNER_NAME')
        self.assertEqual(r.build_context(source), expected)
        self.assertNotIn('PRIVATE', repr(r.build_context(source)))

    def test_binding_create_is_gap_and_matrix_create_anchor(self):
        source = feedback([operation(kind='insert', component='quantifier', path='normalForm[0].quantifier[1]'),
                           operation(kind='insert')])
        prepared = r.prepare(source)
        gap = prepared.retrieve(prepared.snapshot_id, operation_index=0)
        anchor = prepared.retrieve(prepared.snapshot_id, operation_index=1)
        self.assertEqual(gap['dsl'], 'CREATE BINDING AT [0,1,[]];')
        self.assertEqual(gap['role'], 'gap')
        self.assertNotIn('nodeId', gap['focus'])
        self.assertEqual(anchor['dsl'], 'CREATE STRUCTURE NEAR [0,M,[1]];')
        self.assertEqual(anchor['role'], 'anchor')
        self.assertEqual(anchor['focus']['category'], 'MULTIPLICITY')
        self.assertEqual(prepared.retrieve(prepared.snapshot_id, address=[0, 1, []])['status'], 'unavailable')

    def test_invalid_path_role_operator_or_category_never_text_fallback(self):
        for row in (operation(path='normalForm[0].matrix.child[9]'),
                    operation(path='matrix'), operation(sourceRole='insertion-anchor'),
                    operation(sourceOperator='some'), operation(sourceNodeKind='call')):
            source = feedback([row]); result = r.build_context(source)
            self.assertEqual(result['positionedCost'], 0)
            self.assertEqual(result['residualCost'], 1)
            self.assertNotIn('dsl', result['steps'][0])

    def test_temporal_namespace_and_insertion_target_are_distinct(self):
        source = feedback([operation(component='temporal', path='temporal.child[0]', sourceOperator='always',
                                     replacementOperator='eventually'),
                           operation(kind='insert', component='temporal', path='temporal.child[0]', sourceOperator='always')])
        prepared = r.prepare(source)
        update = prepared.retrieve(prepared.snapshot_id, operation_index=0)
        create = prepared.retrieve(prepared.snapshot_id, operation_index=1)
        self.assertEqual(update['dsl'], 'UPDATE TEMPORAL [T,_,[0]] TO "eventually";')
        self.assertEqual(create['reason'], 'temporal_insertion_unlocated')
        self.assertNotIn('dsl', create)

    def test_residual_cost_includes_aggregates_missing_addresses(self):
        source = feedback([operation(), operation(kind='component-edit', aggregate=True, cost=5),
                           operation(path='matrix')])
        result = r.build_context(source)
        self.assertEqual((result['distance'], result['positionedCost'], result['residualCost']), (7, 1, 6))
        self.assertEqual(result['steps'][1]['reason'], 'aggregate_not_atomic')

    def test_partial_source_does_not_claim_complete_graph(self):
        source = feedback()
        source['canonicalStructure'].update(status='partial', reason='node_limit')
        self.assertEqual(r.build_context(source)['status'], 'partial')

    def test_missing_structure_preserves_unavailable_guidance_context(self):
        source = feedback(); source.pop('canonicalStructure')
        result = r.build_context(source)
        self.assertEqual(result['status'], 'unavailable')
        self.assertEqual(result['steps'][0]['reason'], 'structure_unavailable')

    def test_ast_is_not_canonical_and_no_dsl_is_claimed(self):
        source = feedback([operation(component='ast', path='ast[1]')])
        source['metric'] = r.AST_METRIC
        result = r.build_context(source)
        self.assertEqual(result['status'], 'not-applicable')
        self.assertNotIn('steps', result)

    def test_context_and_single_lookup_hard_budgets(self):
        source = feedback([operation() for _ in range(128)])
        prepared = r.prepare(source)
        for budget in (2, 30, 128, 511, 512, 1024, 4096, 32768):
            with self.subTest(budget=budget):
                context = prepared.context(budget)
                self.assertLessEqual(size(context), budget)
                selected = prepared.retrieve(prepared.snapshot_id, operation_index=0, max_bytes=budget)
                self.assertLessEqual(size(selected), budget)
                for index, step in enumerate(context.get('steps', [])):
                    self.assertEqual(step['id'], 'operation-'+str(index+1))
        self.assertGreater(prepared.context(1024)['omittedSteps'], 0)

    def test_repeated_stateless_pass_retrieves_identical_result(self):
        source = feedback(); prepared = r.prepare(source, 'no x.r')
        self.assertEqual(r.retrieve(source, prepared.snapshot_id, 'no x.r', operation_index=0),
                         prepared.retrieve(prepared.snapshot_id, operation_index=0))

    def test_zero_distance_retrieves_read_only_overview_without_edit_steps(self):
        source = feedback([])
        result = r.build_context(source)
        self.assertEqual(result['steps'], [])
        self.assertEqual(result['positionedCost'], 0)
        self.assertEqual(result['residualCost'], 0)
        self.assertEqual(result['omittedOverview'], 0)
        self.assertEqual([item['address'] for item in result['overview']], [[0, 'M', []], ['T', '_', []]])
        self.assertTrue(all(item['dsl'].startswith('READ ') and item['cost'] == 0 for item in result['overview']))
        self.assertEqual(result['overview'][0]['bindings'][0]['address'], [0, 0, []])
        for budget in (2, 128, 512, 1024, 4096):
            self.assertLessEqual(size(r.build_context(source, max_bytes=budget)), budget)

    def test_zero_distance_overview_phase_limit_is_explicit(self):
        source = feedback([])
        for phase in range(1, 6):
            source['canonicalStructure']['phases'].append({'index': phase, 'bindingCount': 0})
            source['canonicalStructure']['nodes'].append(node([phase, 'M', []], 'n'+str(20+phase), 'REFERENCE'))
        result = r.build_context(source)
        self.assertEqual(result['status'], 'partial')
        self.assertEqual(len(result['overview']), 4)
        self.assertEqual(result['omittedOverview'], 3)
        self.assertEqual([entry['address'] for entry in result['overview']],
                         [[0, 'M', []], [1, 'M', []], [2, 'M', []], ['T', '_', []]])

    def test_invalid_optional_evidence_never_crashes_normal_guidance(self):
        for source in (None, {}, dict(feedback(), operations=[None]),
                       dict(feedback(), canonicalForm=['\ud800']),
                       dict(feedback(), operations=[operation()]*129)):
            self.assertEqual(r.build_context(source)['status'], 'unavailable')
        prepared = r.prepare(feedback())
        for address in ([False, 'M', []], [0, 'M', [9]], [0, 'M', ['0']], ['T', 0, []]):
            self.assertEqual(prepared.retrieve(prepared.snapshot_id, address=address)['status'], 'unavailable')
        self.assertEqual(prepared.retrieve(prepared.snapshot_id, operation_index=True)['status'], 'unavailable')
        self.assertEqual(prepared.retrieve(prepared.snapshot_id)['status'], 'unavailable')


if __name__ == '__main__':
    unittest.main()
