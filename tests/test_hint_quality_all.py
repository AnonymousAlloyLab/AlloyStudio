"""Boundary checks for the public 181-invariant research evidence projection."""
import unittest

from benchmarks.alloy4fun.quality_all import measure, public_native_hint, safe_selected


class HintQualityProjectionTests(unittest.TestCase):
    def test_fm24_operators_survive_but_operand_identities_do_not(self):
        native = ('Instead of using signature of type Student, try using signature of type Teacher. '
                  'Consider introducing a new variable of type "SecretExpr" using universal quantifier (\'all\'). '
                  'Move field "secretField".')
        public = public_native_hint(native)
        for hidden in ('Student', 'Teacher', 'SecretExpr', 'secretField'):
            self.assertNotIn(hidden, public)
        self.assertIn("quantifier ('all')", public)
        self.assertIn('[operand hidden]', public)

    def test_no_hint_has_no_guidance_even_if_unaccepted_native_payload_exists(self):
        c, kinds, length = measure('fm24-history', {'hint': "operator ('and') to combine sets"}, False)
        self.assertEqual(c['hint_cases'], 0)
        self.assertEqual(c['native_hint_units'], 0)
        self.assertIsNone(length)
        self.assertFalse(kinds)

    def test_related_anchor_is_not_exact_and_aggregate_is_not_atomic(self):
        response = {'operations': [
            {'kind': 'insert', 'action': 'Check here', 'sourceLocation': {'status': 'located', 'precision': 'related'}},
            {'kind': 'replace', 'action': 'Check here', 'replacementOperator': 'and',
             'sourceLocation': {'status': 'located', 'precision': 'node'}},
            {'kind': 'insert', 'aggregate': True, 'cost': 8}],
            'trace': {'matchesDistance': True, 'matrixReplayVerified': True}}
        c, kinds, length = measure('canonical', response, True)
        self.assertEqual((c['aggregate_units'], c['atomic_units'], length), (1, 2, 2))
        self.assertEqual((c['raw_exact_units'], c['raw_related_units'], c['all_raw_exact_cases']), (1, 1, 0))
        self.assertEqual(c['repeated_action_units'], 1)
        self.assertEqual(c['replacement_operator_units'], 1)
        self.assertEqual(kinds, {'insert': 1, 'replace': 1})

    def test_tar_unknown_validation_is_neither_verified_nor_rejected(self):
        response = {'native_result': {'native_trace': [{'hint': 'Insert an operator.', 'line': 1,
            'column': 1, 'end_line': 1, 'end_column': 5}]},
            'independent_validation': {'status': 'validation_error', 'verified_correct': None}}
        c, _, length = measure('tar', response, True)
        self.assertEqual((c['bounded_checked_cases'], c['bounded_rejected_cases']), (0, 0))
        self.assertEqual(c['validation_error_cases'], 1)
        self.assertEqual((c['all_raw_range_cases'], length), (1, 1))

    def test_tar_public_projection_excludes_candidate_and_mutator_target(self):
        response = {'candidate_repair': {'inv1': 'private candidate'},
            'native_result': {'solution': {'inv1': 'private solution'}, 'native_trace': [
                {'hint': 'A different relation is required.', 'name': 'private replacement',
                 'operation': 'ReplaceRelation', 'line': 2, 'column': 4}]}}
        public = safe_selected('tar', response, {'hint_available': True}, 'r', 's')
        self.assertNotIn('private', str(public))
        self.assertEqual(public['native_hints'][0]['hint'], 'A different relation is required.')

    def test_fm24_public_projection_excludes_raw_target_expression(self):
        response = {'hint': 'Consider adding a field "secret" to help satisfy the required property.',
                    'target': 'complete hidden answer'}
        public = safe_selected('fm24-history', response, {'hint_available': True}, 'r', 's')
        self.assertTrue(public['publication_redacted'])
        self.assertNotIn('secret', str(public))
        self.assertNotIn('complete hidden answer', str(public))


if __name__ == '__main__':
    unittest.main()
