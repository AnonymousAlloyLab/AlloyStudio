import math
import unittest
from benchmarks.alloy4fun.protocol.metrics import aggregate


def case(cid, status='BOTH', group='a', predicate='inv1'):
    return dict(case_id=cid, cohort_status=status, group=group, predicate=predicate)


def row(cid, *, tool='live', hint=True, wall=1.0, supported=True, timeout=False, **kwargs):
    return dict(case(cid, **kwargs), tool=tool, status='timeout' if timeout else 'ok',
                hint_available=hint, wall_seconds=wall, supported=supported, timed_out=timeout)


class MetricTests(unittest.TestCase):
    def test_missing_observations_remain_failures_in_denominator(self):
        result = aggregate([row('1')], expected_cases=[case('1'), case('2')])
        tool = result['tools']['live']
        self.assertFalse(tool['complete'])
        self.assertEqual(tool['incorrect']['hit_rate_micro'], .5)
        self.assertEqual(tool['incorrect']['missing'], 1)
        self.assertEqual(tool['incorrect']['wall_all_observed']['observations'], 1)

    def test_all_failures_and_timeouts_contribute_runtime(self):
        result = aggregate([row('1', wall=1), row('2', hint=False, wall=60, timeout=True),
                            row('3', hint=False, wall=2, supported=False)])
        m = result['tools']['live']['incorrect']
        self.assertEqual(m['wall_all_observed']['mean_seconds'], 21)
        self.assertEqual(m['wall_hints_only']['mean_seconds'], 1)
        self.assertEqual(m['timeout_rate'], 1/3)
        self.assertEqual(m['hit_rate_given_supported'], .5)
        self.assertEqual(m['unsupported'], 1)

    def test_macro_gives_equal_weight_to_exercises(self):
        observations = [row('1'), row('2'), row('3', hint=False, group='b')]
        m = aggregate(observations)['tools']['live']['incorrect']
        self.assertEqual(m['hit_rate_micro'], 2/3)
        self.assertEqual(m['hit_rate_macro_exercise'], .5)

    def test_correct_inputs_are_controls_not_wrong_case_hits(self):
        observations = [row('1'), row('2', status='CORRECT'), row('3', status='CORRECT', hint=False)]
        result = aggregate(observations)
        self.assertEqual(result['cohort']['incorrect'], 1)
        tool = result['tools']['live']
        self.assertEqual(tool['incorrect']['hit_rate_micro'], 1)
        self.assertEqual(tool['correct_controls']['nonempty_hint_count'], 1)
        self.assertEqual(tool['correct_controls']['nonempty_hint_rate'], .5)

    def test_no_incorrect_inputs_gives_null_rate(self):
        result = aggregate([row('1', status='CORRECT', hint=False)])
        self.assertIsNone(result['tools']['live']['incorrect']['hit_rate_micro'])

    def test_duplicate_rows_and_expected_ids_are_rejected(self):
        with self.assertRaises(ValueError): aggregate([row('1'), row('1')])
        with self.assertRaises(ValueError): aggregate([], expected_cases=[case('1'), case('1')])

    def test_case_metadata_must_match_between_tools(self):
        with self.assertRaises(ValueError):
            aggregate([row('1'), row('1', tool='tar', group='changed')])

    def test_unknown_case_is_rejected_against_manifest(self):
        with self.assertRaises(ValueError): aggregate([row('new')], expected_cases=[case('old')])

    def test_nonfinite_and_negative_or_boolean_timings_are_rejected(self):
        for bad in (math.nan, math.inf, -1, True, '1'):
            with self.subTest(value=bad), self.assertRaises(ValueError): aggregate([row('1', wall=bad)])

    def test_booleans_must_be_boolean_and_contradictory_hits_rejected(self):
        for updates in ({'hint_available': 1}, {'timed_out': True}, {'supported': False}):
            bad = row('1'); bad.update(updates)
            with self.subTest(updates=updates), self.assertRaises(ValueError): aggregate([bad])

    def test_localization_is_count_weighted_and_absent_is_not_zero(self):
        a, b, c = row('a'), row('b'), row('c')
        a.update(operation_count=10, located_operations=1)
        b.update(operation_count=1, located_operations=1)
        m = aggregate([a,b,c])['tools']['live']['incorrect']
        self.assertEqual(m['located_operation_fraction'], 2/11)
        self.assertEqual(m['localization_observations'], 2)
        a['located_operations'] = 11
        with self.assertRaises(ValueError): aggregate([a])

    def test_latency_quantile_and_thresholds(self):
        m = aggregate([row('a', wall=1), row('b', wall=3)])['tools']['live']['incorrect']
        self.assertEqual(m['wall_all_observed']['p50_seconds'], 2)
        self.assertAlmostEqual(m['wall_all_observed']['p95_seconds'], 2.9)
        self.assertEqual(m['hints_within_seconds']['2.0']['rate'], .5)

    def test_late_hint_does_not_count_toward_budgeted_hit_rate(self):
        m = aggregate([row('late', wall=61)])['tools']['live']['incorrect']
        self.assertEqual(m['hints'], 0)
        self.assertEqual(m['hints_after_budget'], 1)
        self.assertEqual(m['hit_rate_micro'], 0)
        self.assertEqual(m['hit_rate_macro_exercise'], 0)
        self.assertEqual(m['wall_all_observed']['mean_seconds'], 61)

    def test_shared_manifest_not_tool_specific_denominators(self):
        result = aggregate([row('1'), row('2', tool='tar', hint=False)])
        self.assertEqual(result['tools']['live']['incorrect']['cases'], 2)
        self.assertEqual(result['tools']['tar']['incorrect']['cases'], 2)
        self.assertEqual(result['cohort']['source'], 'observed_union')


if __name__ == '__main__':
    unittest.main()
