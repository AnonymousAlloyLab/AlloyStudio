"""Plot validation and synthetic-only export checks; no public figures are made."""
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

from benchmarks.alloy4fun.plot_results import TOOLS, THRESHOLDS, generate_figures, validate_summary, hit_rate_label
from benchmarks.alloy4fun.summarize import (
    ROOT, METHOD_VERSION, EXPECTED_TOTAL, EXPECTED_INCORRECT, CORRECTION_REGISTRY,
    CORRECTION_CASE_IDS, SOURCE_CLASSIFICATION_COUNTS, CORRECTED_CLASSIFICATION_COUNTS,
    RECOVERY_LIMITS, RECOVERY_WORKERS, TOOL_DIRECTORIES,
)


def synthetic_summary():
    tools, runs = {}, {}
    for index, tool in enumerate(TOOLS):
        counts = [0, 0, 0, 1, 1, 1, 2, 2] if index % 2 == 0 else [0, 0, 1, 1, 1, 1, 1, 1]
        tools[tool] = {'complete': True, 'incorrect': {'cases': 2, 'observed': 2, 'missing': 0,
                       'hints': counts[-1], 'hit_rate_micro': counts[-1] / 2,
                       'hints_within_seconds': {str(t): {'hints': n, 'rate': n / 2} for t, n in zip(THRESHOLDS, counts)}}}
        runs[tool] = {'state': 'COMPLETE', 'observed': 3, 'expected': 3}
    return {'schema_version': 2, 'method_version': METHOD_VERSION, 'status': 'COMPLETE',
            'incomplete_tools': [], 'generated_at': '2000-01-01T00:00:00+00:00', 'runs': runs,
            'metrics': {'cohort': {'cases': 3, 'incorrect': 2, 'correct': 1, 'source': 'expected_manifest'},
                        'timeout_seconds': 60, 'tools': tools},
            'provenance': {'actual_source_audit': {'status': 'MATCHED', 'source_files_checked': 3},
                           'raw_response_audit': {'status': 'PASS', 'scope': 'raw-response-correspondence', 'tools': 5},
                           'label_corrections': {'status': 'MATCHED', 'registry_path': CORRECTION_REGISTRY}}}


def synthetic_production_shape():
    """Counts-only fixture: validate the real contract without inventing a run."""
    summary = synthetic_summary()
    summary['metrics']['cohort'].update(cases=EXPECTED_TOTAL, incorrect=EXPECTED_INCORRECT,
                                       correct=EXPECTED_TOTAL - EXPECTED_INCORRECT)
    summary['provenance']['actual_source_audit']['source_files_checked'] = EXPECTED_TOTAL
    summary['provenance']['execution_profile'] = {
        'status': 'MATCHED', 'kind': 'sequential_equal_workers', 'resource_profile_sha256': 'f' * 64,
        'workers_by_tool': {tool: 16 for tool in TOOLS}}
    summary['provenance']['label_corrections'].update(
        applied_count=2, case_ids=sorted(CORRECTION_CASE_IDS),
        source_classification_counts=SOURCE_CLASSIFICATION_COUNTS.copy(),
        effective_classification_counts=CORRECTED_CLASSIFICATION_COUNTS.copy(),
        registry_sha256=hashlib.sha256((ROOT / CORRECTION_REGISTRY).read_bytes()).hexdigest())
    for tool in TOOLS:
        summary['runs'][tool].update(observed=EXPECTED_TOTAL, expected=EXPECTED_TOTAL, workers=16)
        wrong = summary['metrics']['tools'][tool]['incorrect']
        wrong.update(cases=EXPECTED_INCORRECT, observed=EXPECTED_INCORRECT,
                     hit_rate_micro=wrong['hints'] / EXPECTED_INCORRECT)
        for point in wrong['hints_within_seconds'].values():
            point['rate'] = point['hints'] / EXPECTED_INCORRECT
    return summary


def with_recovery(value):
    workers = {tool: RECOVERY_WORKERS[arm] for tool, arm in TOOL_DIRECTORIES.items()}
    for tool, count in workers.items():
        value['runs'][tool].update(workers=count, resumed_rows=0)
    value['provenance']['execution_profile'] = {
        'status': 'MATCHED', 'kind': 'oom_recovery', 'resource_profile_sha256': 'f' * 64,
        'workers_by_tool': workers, 'timing_comparison': 'descriptive_unequal_resource_configurations',
        'effective_limits': RECOVERY_LIMITS.copy(), 'fresh_tar_resumed_rows': 0,
        'preserved_arms': sorted(set(TOOL_DIRECTORIES.values()) - {'tar'}),
        'guard_report_sha256': 'a' * 64, 'guard_source_sha256': 'b' * 64}
    return value


class PlotTests(unittest.TestCase):
    def validate(self, value):
        return validate_summary(value, expected_total=3, expected_incorrect=2)

    def test_all_five_arms_and_correct_denominator_are_retained(self):
        curves, counts = self.validate(synthetic_summary())
        self.assertEqual(len(curves), 5)
        self.assertEqual(counts, [2, 1, 2, 1, 2])
        self.assertEqual(curves[1][-1], 50)

    def test_bar_annotation_keeps_full_cohort_single_miss_visible(self):
        self.assertEqual(hit_rate_label(42387, 42388), '99.9976%\n42,387/42,388')
        self.assertEqual(hit_rate_label(42388, 42388), '100.0000%\n42,388/42,388')
        self.assertNotIn('100.00%', hit_rate_label(42387, 42388))

    def test_corrected_v3_denominator_and_registry_are_required_by_default(self):
        value = synthetic_production_shape()
        curves, counts = validate_summary(value)
        self.assertEqual(curves[0][-1], 100 * counts[0] / 42388)
        for mutation in ('old_method', 'old_denominator', 'registry_hash', 'registry_missing', 'wrong_workers'):
            changed = deepcopy(value)
            if mutation == 'old_method':
                changed['method_version'] = 'alloy4fun-comparison-v2'
            elif mutation == 'old_denominator':
                changed['metrics']['cohort'].update(incorrect=42386, correct=19212)
            elif mutation == 'registry_hash':
                changed['provenance']['label_corrections']['registry_sha256'] = '0' * 64
            elif mutation == 'registry_missing':
                del changed['provenance']['label_corrections']
            else:
                changed['runs']['live-ast']['workers'] = 1
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_summary(changed)

    def test_running_or_incomplete_report_is_rejected(self):
        for update in ({'status': 'RUNNING'}, {'incomplete_tools': ['live-ast']}):
            value = synthetic_summary(); value.update(update)
            with self.subTest(update=update), self.assertRaisesRegex(ValueError, 'COMPLETE'):
                self.validate(value)

    def test_guarded_recovery_requires_bound_profile_and_fresh_tar(self):
        value = with_recovery(synthetic_production_shape())
        self.assertEqual(len(validate_summary(value)[0]), 5)
        for mutation in ('missing_profile', 'missing_guard_hash', 'changed_workers', 'resumed', 'old_limit'):
            changed = deepcopy(value)
            if mutation == 'missing_profile':
                del changed['provenance']['execution_profile']
            elif mutation == 'missing_guard_hash':
                del changed['provenance']['execution_profile']['guard_report_sha256']
            elif mutation == 'changed_workers':
                changed['runs']['tar-depth-2']['workers'] = 16
            elif mutation == 'resumed':
                changed['runs']['tar-depth-2']['resumed_rows'] = 200
            else:
                changed['provenance']['execution_profile']['effective_limits']['memory_max_bytes'] = 0
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_summary(changed)

    def test_missing_ast_arm_or_raw_audit_is_rejected(self):
        value = synthetic_summary()
        del value['metrics']['tools']['live-ast']
        with self.assertRaisesRegex(ValueError, 'raw AST'):
            self.validate(value)
        value = synthetic_summary(); value['provenance']['raw_response_audit']['status'] = 'FAILED'
        with self.assertRaisesRegex(ValueError, 'raw-response'):
            self.validate(value)

    def test_inconsistent_counts_or_nonmonotonic_curve_is_rejected(self):
        for field, newvalue in [('hit_rate_micro', .123), ('cases', 3)]:
            value = synthetic_summary(); value['metrics']['tools']['live-ast']['incorrect'][field] = newvalue
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.validate(value)
        value = synthetic_summary()
        value['metrics']['tools']['live-canonical']['incorrect']['hints_within_seconds']['10.0'] = {'hints': 0, 'rate': 0}
        with self.assertRaisesRegex(ValueError, 'decreasing'):
            self.validate(value)

    @unittest.skipUnless(importlib.util.find_spec('matplotlib'), 'matplotlib optional plotting dependency')
    def test_synthetic_exports_are_standalone_and_hash_bound(self):
        value = synthetic_summary()
        digest = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
        with tempfile.TemporaryDirectory(prefix='alloy4fun-synthetic-plots-') as temp:
            files = generate_figures(value, temp, input_sha256=digest, expected_total=3, expected_incorrect=2)
            self.assertEqual(len(files), 5)
            manifest = json.loads((Path(temp) / 'alloy4fun-figures.json').read_text())
            self.assertEqual(manifest['input_sha256'], digest)
            for name, expected in manifest['files'].items():
                self.assertEqual(hashlib.sha256((Path(temp) / name).read_bytes()).hexdigest(), expected)
            svg = (Path(temp) / 'alloy4fun-timely-hints.svg').read_text()
            self.assertIn('Alloy Studio raw AST', svg)
            self.assertIn('2 incorrect inputs', svg)
            self.assertIn('Sequential method arms with 16 concurrent requests per arm', svg)
            self.assertTrue((Path(temp) / 'alloy4fun-hit-rates.png').read_bytes().startswith(b'\x89PNG'))

    def test_invalid_input_creates_no_output_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'must-not-exist'
            value = synthetic_summary(); value['status'] = 'RUNNING'
            with self.assertRaises(ValueError):
                generate_figures(value, output, input_sha256='0'*64, expected_total=3, expected_incorrect=2)
            self.assertFalse(output.exists())

    @unittest.skipUnless(importlib.util.find_spec('matplotlib'), 'matplotlib optional plotting dependency')
    def test_recovery_figure_discloses_unequal_workers_and_no_speed_ranking(self):
        value = with_recovery(synthetic_summary())
        with tempfile.TemporaryDirectory(prefix='alloy4fun-synthetic-recovery-plots-') as temp:
            generate_figures(value, temp, input_sha256='f' * 64, expected_total=3, expected_incorrect=2)
            svg = (Path(temp) / 'alloy4fun-timely-hints.svg').read_text()
            self.assertIn('TAR (depth 2) (4 workers)', svg)
            self.assertIn('Alloy Studio raw AST (16 workers)', svg)
            self.assertIn('not a speed ranking', svg)
            manifest = json.loads((Path(temp) / 'alloy4fun-figures.json').read_text())
            self.assertEqual(manifest['execution_profile']['workers_by_tool']['tar-depth-2'], 4)


if __name__ == '__main__':
    unittest.main()
