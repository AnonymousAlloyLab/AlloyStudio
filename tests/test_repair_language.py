"""ASRL reference contracts: positional selection, disclosure, and cost accounting."""
import copy
from dataclasses import FrozenInstanceError
import random
import unittest

import repair_language as r


REVISION = '0123456789abcdef' * 4


def fixture():
    # Identical labels, distinct subtrees; one shared object at two occurrences.
    shared = r.Node('MULTIPLICITY', 'no', (r.Node('REFERENCE'),))
    matrix = r.Node('CONTROL', 'and', (
        shared, r.Node('MULTIPLICITY', 'no', (r.Node('VARIABLE_USE'),)), shared))
    return r.Snapshot(REVISION, (
        r.Phase((r.Node('BINDING', 'all'), r.Node('BINDING', 'some')), matrix),
        r.Phase((), r.Node('COMPARISON', '='))),
        r.Node('TEMPORAL', None, (r.Node('TEMPORAL', 'always'),
                                r.Node('TEMPORAL', 'eventually'))))


def program(body, revision=REVISION):
    return r.parse('ON "' + revision + '" { ' + body + ' }')


def operation(**fields):
    result = dict(kind='replace', component='matrix', cost=1, aggregate=False,
                  path='normalForm[0].matrix.child[1]', sourceOperator='no',
                  sourceNodeKind='formula', replacementOperator='some')
    result.update(fields)
    if result['kind'] not in ('replace', 'modify'):
        result.pop('replacementOperator', None)
    return result


class PositionalLanguageTests(unittest.TestCase):
    def test_crud_resolves_original_snapshot_without_shifting_after_delete(self):
        snapshot = fixture()
        original = copy.deepcopy(snapshot)
        batch = program('DELETE MULTIPLICITY [0,M,[0]]; READ [0,M,[1,0]]; '
                        'CREATE STRUCTURE NEAR [0,M,[0]]; '
                        'UPDATE MULTIPLICITY [0,M,[1]] TO "some";')
        selected = r.inspect_program(batch, snapshot)
        self.assertEqual([item['role'] for item in selected], ['node', 'node', 'anchor', 'node'])
        self.assertEqual(selected[1]['category'], 'VARIABLE_USE')
        self.assertEqual(batch.cost, 3)
        self.assertEqual(snapshot, original)
        with self.assertRaises(FrozenInstanceError):
            snapshot.revision = 'a' * 64

    def test_repeated_labels_select_one_structural_occurrence(self):
        snapshot = fixture()
        batch = program('READ [0,M,[0,0]]; READ [0,M,[1,0]]; READ [0,M,[2,0]];')
        selected = r.inspect_program(batch, snapshot)
        self.assertEqual([item['category'] for item in selected],
                         ['REFERENCE', 'VARIABLE_USE', 'REFERENCE'])
        self.assertEqual(len(set(item['address'] for item in selected)), 3)
        self.assertIs(snapshot.phases[0].matrix.children[0], snapshot.phases[0].matrix.children[2])
        self.assertEqual(batch.cost, 0)

    def test_phase_and_temporal_skeleton_are_distinct_namespaces(self):
        selected = r.inspect_program(program('READ [1,M,[]]; READ [T,_,[1]];'), fixture())
        self.assertEqual([item['operator'] for item in selected], ['=', 'eventually'])
        self.assertNotEqual(selected[0]['address'], selected[1]['address'])

    def test_binding_gaps_include_end_and_empty_prefix_but_are_not_nodes(self):
        for address in ('[0,0,[]]', '[0,1,[]]', '[0,2,[]]', '[1,0,[]]'):
            with self.subTest(address=address):
                selected = r.inspect_program(program('CREATE BINDING AT '+address+';'), fixture())
                self.assertEqual(selected[0]['role'], 'gap')
        for body in ('READ [0,2,[]];', 'DELETE BINDING [0,2,[]];',
                     'UPDATE BINDING [1,0,[]] TO ?;', 'CREATE BINDING AT [0,3,[]];'):
            with self.subTest(body=body), self.assertRaises(r.LanguageError):
                r.inspect_program(program(body), fixture())

    def test_wrong_category_bounds_and_revision_reject_whole_batch(self):
        for body in ('UPDATE CONTROL [0,M,[1]] TO "or";', 'DELETE CALL [0,M,[1]];',
                     'READ [0,M,[3]];', 'READ [0,M,[0,1]];', 'READ [2,M,[]];',
                     'READ [T,_,[2]];', 'CREATE STRUCTURE NEAR [0,M,[4]];'):
            with self.subTest(body=body), self.assertRaises(r.LanguageError):
                r.inspect_program(program('READ [0,M,[]]; '+body), fixture())
        with self.assertRaises(r.LanguageError):
            r.inspect_program(program('READ [0,M,[]];', 'a'*64), fixture())

    def test_create_anchor_metadata_is_not_hidden_new_node_metadata(self):
        batch = program('CREATE STRUCTURE NEAR [0,M,[1]];')
        selected = r.inspect_program(batch, fixture())[0]
        self.assertEqual(batch.statements[0].category, 'STRUCTURE')
        self.assertEqual((selected['category'], selected['operator']), ('MULTIPLICITY', 'no'))
        self.assertIsNone(batch.statements[0].target)

    def test_round_trip_all_operators_categories_and_verbs(self):
        self.assertEqual(len(r.OPERATORS), 72)
        statements = [r.Statement('READ', r.Address(0, None))]
        for category in r.CATEGORIES.values():
            for verb in ('CREATE', 'UPDATE', 'DELETE'):
                statements.append(r.Statement(verb, r.Address(0, None, (1,)), category,
                                              'NEAR' if verb == 'CREATE' else None))
        statements.extend(r.Statement('UPDATE', r.Address(0, None), 'OPERATOR', target=op)
                          for op in sorted(r.OPERATORS))
        statements.extend((r.Statement('CREATE', r.Address(1, 0), 'BINDING', 'AT'),
                           r.Statement('UPDATE', r.Address(None, None, (1,)), 'TEMPORAL', target='always')))
        batch = r.Program(REVISION, tuple(statements))
        self.assertEqual(r.parse(r.format_program(batch)), batch)

    def test_invalid_syntax_hidden_payloads_and_namespace_shapes(self):
        bodies = ('READ [0,0,[1]];', 'READ [T,0,[]];', 'READ [0,_,[]];',
                  'READ [0,M,[-1]];', 'READ [0,M,[1.5]];', 'READ [0,M,[true]];',
                  'READ [0,M,[*]];', 'READ [0,M,[１]];', 'READ [0,M,[]]',
                  'read [0,M,[]];', 'MOVE CONTROL [0,M,[]];', 'READ [0,M,[]] WHERE x;',
                  'UPDATE CONTROL [0,M,[]] TO "some HiddenRelation";',
                  'UPDATE REFERENCE [0,M,[0,0]] TO "HiddenName";',
                  'DELETE GROUP [0,M,[]];', 'CREATE STRUCTURE AT [0,M,[]];',
                  'CREATE BINDING NEAR [0,0,[]];', 'CREATE STRUCTURE NEAR [0,M,[]] TO "and";',
                  'UPDATE CONTROL [0,0,[]] TO "all";', 'DELETE CONTROL [T,_,[]];',
                  'READ [0,M,[]]; // comment', 'READ [0,M,[]]; /* comment */')
        for body in bodies:
            with self.subTest(body=body), self.assertRaises(r.LanguageError):
                program(body)
        for source in ('', 'ON "'+REVISION+'" {', r.format_program(program(''))+'READ [0,M,[]];',
                       'ON "'+'A'*64+'" {}', 'ON "'+REVISION[:-1]+'" {}', '\ud800'):
            with self.subTest(source=repr(source)), self.assertRaises(r.LanguageError):
                r.parse(source)

    def test_resource_boundaries(self):
        self.assertEqual(len(program('READ [0,M,[]];'*r.MAX_STATEMENTS).statements), r.MAX_STATEMENTS)
        inside = ','.join('0' for _ in range(r.MAX_DEPTH))
        self.assertEqual(len(program('READ [99999999,M,['+inside+']];').statements[0].address.inside), 64)
        for body in ('READ [0,M,[]];'*(r.MAX_STATEMENTS+1),
                     'READ [0,M,['+inside+',0]];', 'READ [100000000,M,[]];'):
            with self.assertRaises(r.LanguageError):
                program(body)
        minimal = r.format_program(program(''))
        self.assertEqual(r.parse(minimal+' '*(r.MAX_BYTES-len(minimal))), program(''))
        with self.assertRaises(r.LanguageError):
            r.parse(minimal+' '*(r.MAX_BYTES-len(minimal)+1))

    def test_direct_construction_cannot_bypass_core_validation(self):
        constructors = (lambda: r.Address(True, None), lambda: r.Address(0, False),
                        lambda: r.Address(0, None, (True,)), lambda: r.Address(None, 1),
                        lambda: r.Address(0, 0, (0,)), lambda: r.Address(0, None, []),
                        lambda: r.Node('GROUP'), lambda: r.Node('CALL', 'HiddenPredicate'),
                        lambda: r.Phase((r.Node('CONTROL'),), r.Node('CONTROL')),
                        lambda: r.Statement('CREATE', r.Address(0, 0), 'CONTROL', 'AT'),
                        lambda: r.Statement('DELETE', r.Address(0, None), 'CONTROL', target='and'),
                        lambda: r.Statement('READ', r.Address(0, None), 'CONTROL'),
                        lambda: r.Program(REVISION, []))
        for constructor in constructors:
            with self.assertRaises(r.LanguageError):
                constructor()

    def test_bounded_random_text_never_escapes_language_error(self):
        rng = random.Random(20261023)
        for _ in range(500):
            body = ''.join(rng.choice('[]{}012,;?"_ abcdefMTRU\\\n') for _ in range(rng.randrange(90)))
            try:
                parsed = program(body)
            except r.LanguageError:
                continue
            self.assertEqual(r.parse(r.format_program(parsed)), parsed)


class LegacyProjectionTests(unittest.TestCase):
    def test_exact_paths_map_with_no_text_search(self):
        cases = [('normalForm[2].matrix.child[1].child[0]', 'matrix', r.Address(2, None, (1, 0))),
                 ('normalForm[2].quantifier[3]', 'quantifier', r.Address(2, 3)),
                 ('temporal.child[0].child[1]', 'temporal', r.Address(None, None, (0, 1)))]
        for path, component, expected in cases:
            self.assertEqual(r.address_from_legacy_path(path, component), expected)
        for path, component in (('temporal', 'temporal'), ('matrix', 'matrix'), ('ast[1]', 'ast'),
                                ('normalForm[0].matrix[1]', 'matrix'),
                                ('normalForm[0].matrix', 'quantifier')):
            with self.subTest(path=path), self.assertRaises(r.LanguageError):
                r.address_from_legacy_path(path, component)

    def test_complete_projection_preserves_cost_order_target_masking_and_input(self):
        rows = [operation(), operation(kind='insert'), operation(kind='delete'),
                operation(kind='insert', component='quantifier', path='normalForm[0].quantifier[2]'),
                operation(kind='modify', component='quantifier', sourceOperator='all',
                          path='normalForm[0].quantifier[0]', replacementOperator='some')]
        original = copy.deepcopy(rows)
        result = r.project_legacy(fixture(), rows, 5)
        self.assertTrue(result.complete)
        self.assertEqual(result.operation_indexes, tuple(range(5)))
        self.assertEqual(result.program.cost, 5)
        self.assertEqual([s.verb for s in result.program.statements], ['UPDATE', 'CREATE', 'DELETE', 'CREATE', 'UPDATE'])
        self.assertEqual(rows, original)
        for row in rows:
            row.update(sourceTerm='HIDDEN_SOURCE', replacementExpression='HIDDEN_TARGET', targetName='HIDDEN_NAME')
        self.assertEqual(r.project_legacy(fixture(), rows, 5), result)
        self.assertNotIn('HIDDEN', repr(result))

    def test_residual_ledger_preserves_aggregates_missing_locations_and_costs(self):
        rows = [operation(), operation(kind='component-edit', aggregate=True, cost=7),
                operation(component='temporal', path='temporal', sourceOperator='always'),
                operation(path=None), operation(path='normalForm[0].matrix.child[9]'),
                operation(component='ast', path='ast[1]'), operation(kind='delete')]
        result = r.project_legacy(fixture(), rows, 13)
        self.assertFalse(result.complete)
        self.assertEqual(result.operation_indexes, (0, 6))
        self.assertEqual([x.operation_index for x in result.residuals], [1, 2, 3, 4, 5])
        self.assertEqual([x.cost for x in result.residuals], [7, 1, 1, 1, 1])
        self.assertEqual(result.program.cost+sum(x.cost for x in result.residuals), result.recorded_distance)

    def test_incompatible_role_or_operator_cannot_gain_precision(self):
        for row in (operation(sourceRole='insertion-anchor'), operation(sourceOperator='some'),
                    operation(sourceNodeKind='call'),
                    operation(kind='insert', sourceOperator='some')):
            with self.subTest(row=row):
                result = r.project_legacy(fixture(), [row], 1)
                self.assertFalse(result.complete)
                self.assertEqual(result.program.cost, 0)
                self.assertEqual(result.residuals[0].cost, 1)

    def test_detailed_temporal_path_works_only_with_matching_public_snapshot(self):
        row = operation(component='temporal', path='temporal.child[0]', sourceOperator='always',
                        replacementOperator='eventually')
        result = r.project_legacy(fixture(), [row], 1)
        self.assertTrue(result.complete)
        self.assertEqual(result.program.statements[0].address, r.Address(None, None, (0,)))
        # A syntactically valid path is not evidence that the selected node exists.
        row['path'] = 'temporal.child[0].child[0]'
        self.assertFalse(r.project_legacy(fixture(), [row], 1).complete)

    def test_malformed_legacy_trace_rejects_instead_of_returning_partial_program(self):
        for rows, distance in (([operation()], 0), ([operation(cost=2)], 2),
                               ([operation(), operation(kind='unknown')], 2),
                               ([operation(replacementOperator='some HiddenTarget')], 1)):
            with self.assertRaises(r.LanguageError):
                r.project_legacy(fixture(), rows, distance)
        empty = r.project_legacy(fixture(), [], 0)
        self.assertTrue(empty.complete)
        self.assertEqual(empty.program.cost, 0)

    def test_temporal_destination_is_not_upgraded_to_a_learner_anchor(self):
        row = operation(kind='insert', component='temporal', path='temporal.child[1]',
                        sourceOperator='eventually')
        self.assertFalse(r.project_legacy(fixture(), [row], 1).complete)
        row['sourceRole'] = 'insertion-anchor'
        self.assertTrue(r.project_legacy(fixture(), [row], 1).complete)


if __name__ == '__main__':
    unittest.main()
