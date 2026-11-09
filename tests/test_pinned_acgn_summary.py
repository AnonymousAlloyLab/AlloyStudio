"""Negative controls for publication of the new, independently versioned run."""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from benchmarks.alloy4fun import summarize_pinned_acgn as summary


def row(case='one', **changes):
    value = dict(case_id=case, group='fixture', predicate='inv', source_sha256='a' * 64,
                 cohort_status='BOTH', status='ok', hint_available=True, timed_out=False,
                 wall_seconds=.5, engine_seconds=.4, distance=1, operation_count=1,
                 located_operations=1, canonical_located_operations=1, matrix_replay_verified=True)
    value.update(changes)
    return value


class PinnedSummaryTests(unittest.TestCase):
    def test_refusals_and_timeouts_stay_in_hint_denominator(self):
        values = [row(), row('refused', status='unsupported', hint_available=False,
                             distance=None, operation_count=0, located_operations=0),
                  row('late', wall_seconds=60.001),
                  row('timeout', status='timeout', timed_out=True, hint_available=False,
                      distance=None, operation_count=0, located_operations=0)]
        result = summary.subset_summary(values)
        self.assertEqual(result['cases'], 4)
        self.assertEqual(result['native_hint_rate'], .25)
        self.assertEqual(result['native_hints_within_60s'], 1)
        self.assertEqual(result['timeouts'], 1)
        self.assertEqual(result['atomic_operations_on_hinted_inputs'], 1)

    def test_controls_do_not_enter_incorrect_denominator(self):
        result = summary.arm_summary([row(), row('control', cohort_status='CORRECT',
                                                distance=0, operation_count=0, hint_available=False)])
        self.assertEqual(result['incorrect']['cases'], 1)
        self.assertEqual(result['incorrect']['native_hint_rate'], 1)
        self.assertEqual(result['correct_controls']['zero_distance'], 1)

    def test_quantiles_include_failed_requests_and_mark_missing_engine_times(self):
        result = summary.subset_summary([row(), row('failed', status='timeout',
                     hint_available=False, timed_out=True, distance=None, wall_seconds=60, engine_seconds=None)])
        self.assertEqual(result['wall_seconds']['observations'], 2)
        self.assertEqual(result['wall_seconds']['p95'], 60)
        self.assertEqual(result['engine_seconds']['observations'], 1)

    def test_pairing_refuses_changed_source_or_classification(self):
        for changes in (dict(source_sha256='b' * 64), dict(cohort_status='CORRECT')):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                summary.paired_distances([row(**changes)], [row()])

    def test_pairing_refuses_missing_or_duplicate_case(self):
        for previous in ([], [row(), row()]):
            with self.subTest(count=len(previous)), self.assertRaises(ValueError):
                summary.paired_distances([row()], previous)

    def test_distance_delta_excludes_non_successful_pairs(self):
        result = summary.paired_distances([row(distance=0), row('failed', status='timeout', distance=None)],
                                         [row(), row('failed')])
        self.assertEqual(result['paired_successes'], 1)
        self.assertEqual(result['distance_lower'], 1)
        self.assertEqual(result['nonzero_became_zero'], 1)
        self.assertEqual(result['not_both_successful'], 1)

    def test_publication_refuses_incomplete_arm_or_failed_supervisor(self):
        parent = Path(__file__).resolve().parents[1] / 'build/tests'
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=parent) as directory:
            root = Path(directory)
            (root / 'manifest.json').write_text('{}')
            for state, guard in [('RUNNING', 'RUNNING'), ('COMPLETED', 'STOPPED')]:
                (root / 'state.json').write_text(json.dumps(dict(status=state)))
                (root / 'resource-guard.json').write_text(json.dumps(dict(status=guard)))
                with self.subTest(state=state, guard=guard), patch.object(summary, 'check_snapshot') as check:
                    with self.assertRaises(ValueError):
                        summary.summarize(root)
                    check.assert_not_called()


if __name__ == '__main__':
    unittest.main()
