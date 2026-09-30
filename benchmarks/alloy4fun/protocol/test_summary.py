"""Finite synthetic checks for report denominators, provenance and publication gates."""
from copy import deepcopy
from collections import Counter
import json
from pathlib import Path
import tempfile
import unittest

from benchmarks.alloy4fun.summarize import (
    BEGIN, END, TOOL_DIRECTORIES, digest, publish, render_markdown, sha, summarize, update_markers,
    _validate_manifest, validate_evidence_audit, resource_provenance, _percent,
    validate_label_corrections, METHOD_VERSION, EXPECTED_TOTAL, EXPECTED_INCORRECT,
    CORRECTION_REGISTRY, CORRECTION_CASE_IDS, SOURCE_CLASSIFICATION_COUNTS, CORRECTED_CLASSIFICATION_COUNTS,
    RECOVERY_LIMITS, RECOVERY_WORKERS, load_execution_profile, validate_execution_profile,
)


def json_file(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value) + '\n')


def jsonl_file(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(''.join(json.dumps(row) + '\n' for row in rows))


class SummaryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'build/benchmarks/alloy4fun'
        self.data.mkdir(parents=True)
        self.source = self.root / 'corpus'
        self.cases = []
        for number, status in enumerate(('BOTH', 'UNDERCONSTRAINED', 'CORRECT')):
            cid = f'group/{status.lower()}/m{number}_inv1.als'
            path = self.source / cid
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f'sig A {{}} pred inv1 {{some A}} // {number}')
            self.cases.append({'case_id': cid, 'model_id': f'm{number}', 'group': 'group', 'predicate': 'inv1',
                               'cohort_status': status, 'source_sha256': sha(path), 'path': str(path)})
        jsonl_file(self.data / 'cases.jsonl', self.cases)
        json_file(self.data / 'cohort.json', {'eligible': 3, 'source_root': str(self.source),
                                            'cases.jsonl_sha256': sha(self.data / 'cases.jsonl')})
        json_file(self.data / 'lineage.json', {f'm{i}': {'fold': i} for i in range(3)})
        self.rows = {}
        for tool, directory in TOOL_DIRECTORIES.items():
            rows = []
            for index, case in enumerate(self.cases):
                hint = (tool == 'live-canonical' and index in (0, 2)) or (tool == 'live-ast' and index == 1)
                row = {**case, 'tool': tool, 'status': 'ok', 'hint_available': hint,
                       'timed_out': False, 'supported': True, 'wall_seconds': index + 1.0}
                if tool.startswith(('live-', 'fm24-')):
                    row['fold'] = index
                rows.append(row)
            self.rows[tool] = rows
            folder = self.data / directory
            jsonl_file(folder / 'results.jsonl', rows)
            json_file(folder / 'manifest.json', {'tool': tool, 'cases': 3, 'timeout_seconds': 60,
                                                 'case_manifest_sha256': sha(self.data / 'cases.jsonl')})
            json_file(folder / 'run.json', {'cases': 3, 'completed': 3, 'run_wall_seconds': 2.0})

    def run_summary(self, **kwargs):
        # This tiny fixture exercises aggregation; strict runtime-pin tests below
        # exercise production requirements independently of real compiled JARs.
        kwargs.setdefault('verify_runtime', False)
        kwargs.setdefault('expected_total', 3)
        kwargs.setdefault('expected_incorrect', 2)
        return summarize(self.data, repository_root=self.root, **kwargs)

    def write_rows(self, tool):
        jsonl_file(self.data / TOOL_DIRECTORIES[tool] / 'results.jsonl', self.rows[tool])

    def test_complete_summary_keeps_correct_controls_out_of_hit_denominator(self):
        result = self.run_summary()
        self.assertEqual(result['status'], 'COMPLETE')
        canonical = result['metrics']['tools']['live-canonical']
        self.assertEqual(canonical['incorrect']['hit_rate_micro'], .5)
        self.assertEqual(canonical['incorrect']['hints'], 1)
        self.assertEqual(canonical['correct_controls']['nonempty_hint_count'], 1)
        self.assertEqual(canonical['wall_all_observed']['mean_seconds'], 2)
        self.assertEqual(result['provenance']['actual_source_audit']['source_files_checked'], 3)

    def test_correct_control_wording_distinguishes_holdout_from_original_pool_members(self):
        result = self.run_summary()
        metrics_before = deepcopy(result['metrics'])
        markdown = render_markdown(result)
        self.assertIn('held-out CORRECT submissions', markdown)
        self.assertIn('not failure to recognize members already admitted to a full correct pool', markdown)
        self.assertIn('The original Alloy4FunAugmenter', markdown)
        self.assertIn('these control counts are not original-policy results', markdown)
        self.assertNotIn('measures unnecessary guidance', markdown)
        self.assertIn('whole-branch folds', result['scope']['correct_control_policy'])
        self.assertEqual(result['metrics'], metrics_before)

    def add_label_correction(self):
        # A formerly CORRECT control joins the denominator after a source-bound
        # correction. Every tool keeps exactly the same source and case ID.
        corrected = self.cases[2]
        for case in self.cases:
            case['source_cohort_status'] = case['cohort_status']
        corrected['cohort_status'] = 'UNDERCONSTRAINED'
        registry = {'schema_version': 1, 'corrections': [{
            'case_id': corrected['case_id'], 'source_sha256': corrected['source_sha256'],
            'original_label': 'CORRECT', 'corrected_label': 'UNDERCONSTRAINED'}]}
        path = self.root / CORRECTION_REGISTRY
        json_file(path, registry)
        jsonl_file(self.data / 'cases.jsonl', self.cases)
        cohort = json.loads((self.data / 'cohort.json').read_text())
        cohort.update({'cases.jsonl_sha256': sha(self.data / 'cases.jsonl'),
                       'source_classification_counts': dict(Counter(c['source_cohort_status'] for c in self.cases)),
                       'classification_counts': dict(Counter(c['cohort_status'] for c in self.cases)),
                       'label_corrections': {'path': str(path), 'sha256': sha(path), 'applied_count': 1,
                                             'case_ids': [corrected['case_id']]}})
        json_file(self.data / 'cohort.json', cohort)
        for tool, rows in self.rows.items():
            for row, case in zip(rows, self.cases):
                row.update(cohort_status=case['cohort_status'], source_cohort_status=case['source_cohort_status'])
            self.write_rows(tool)
            manifest_path = self.data / TOOL_DIRECTORIES[tool] / 'manifest.json'
            manifest = json.loads(manifest_path.read_text())
            manifest['case_manifest_sha256'] = sha(self.data / 'cases.jsonl')
            json_file(manifest_path, manifest)
        return cohort, registry, path

    def test_corrected_control_enters_hit_denominator_with_original_label_preserved(self):
        self.add_label_correction()
        result = self.run_summary(expected_incorrect=3)
        self.assertEqual(result['method_version'], METHOD_VERSION)
        self.assertEqual(result['metrics']['cohort']['incorrect'], 3)
        self.assertEqual(result['metrics']['cohort']['correct'], 0)
        canonical = result['metrics']['tools']['live-canonical']
        self.assertEqual(canonical['incorrect']['hit_rate_micro'], 2 / 3)
        self.assertEqual(canonical['correct_controls']['cases'], 0)
        labels = result['provenance']['label_corrections']
        self.assertEqual(labels['status'], 'MATCHED')
        self.assertEqual(labels['source_classification_counts']['CORRECT'], 1)
        self.assertNotIn('CORRECT', labels['effective_classification_counts'])

    def test_label_registry_hash_mismatch_is_rejected(self):
        _, registry, path = self.add_label_correction()
        registry['unreviewed_change'] = True
        json_file(path, registry)
        with self.assertRaisesRegex(ValueError, 'label-correction registry'):
            self.run_summary(expected_incorrect=3)

    def test_correction_cannot_target_a_different_source_or_hide_extra_relabels(self):
        cohort, registry, path = self.add_label_correction()
        for mutation in ('source', 'inventory', 'unregistered'):
            current = deepcopy(registry)
            cases = {c['case_id']: deepcopy(c) for c in self.cases}
            metadata = deepcopy(cohort)
            if mutation == 'source':
                current['corrections'][0]['source_sha256'] = '0' * 64
            elif mutation == 'inventory':
                metadata['label_corrections']['case_ids'] = []
            else:
                cases[self.cases[0]['case_id']]['source_cohort_status'] = 'CORRECT'
            json_file(path, current)
            metadata['label_corrections']['sha256'] = sha(path)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                validate_label_corrections(metadata, cases, self.root, required=False)

    def test_final_production_contract_requires_v3_corrections_and_denominators(self):
        self.assertEqual((EXPECTED_TOTAL, EXPECTED_INCORRECT), (61598, 42388))
        self.assertEqual(SOURCE_CLASSIFICATION_COUNTS['CORRECT'], 19212)
        self.assertEqual(CORRECTED_CLASSIFICATION_COUNTS['CORRECT'], 19210)
        self.assertEqual(METHOD_VERSION, 'alloy4fun-comparison-v3')
        with self.assertRaisesRegex(ValueError, 'requires label_corrections'):
            validate_label_corrections({}, {}, self.root, required=True)
        cohort, _, _ = self.add_label_correction()
        with self.assertRaisesRegex(ValueError, 'two reviewed label corrections'):
            validate_label_corrections(cohort, {c['case_id']: c for c in self.cases}, self.root, required=True)

    def test_original_labels_are_bound_to_corpus_folders_not_only_supplied_counts(self):
        entries = [{'case_id': cid, 'source_sha256': '0' * 64, 'original_label': 'CORRECT',
                    'corrected_label': 'UNDERCONSTRAINED'} for cid in sorted(CORRECTION_CASE_IDS)]
        path = self.root / CORRECTION_REGISTRY
        json_file(path, {'schema_version': 1, 'corrections': entries})
        cases = {entry['case_id']: {'source_sha256': entry['source_sha256'],
                                   'source_cohort_status': 'CORRECT', 'cohort_status': 'UNDERCONSTRAINED'}
                 for entry in entries}
        # Merely changing both supplied labels could otherwise hide an
        # unregistered relabel behind apparently consistent metadata/counts.
        cases['group/both/other_inv1.als'] = {'source_sha256': '1' * 64,
                                            'source_cohort_status': 'CORRECT', 'cohort_status': 'CORRECT'}
        cohort = {'label_corrections': {'path': str(path), 'sha256': sha(path), 'applied_count': 2,
                                        'case_ids': sorted(CORRECTION_CASE_IDS)},
                  'source_classification_counts': {'CORRECT': 3},
                  'classification_counts': {'CORRECT': 1, 'UNDERCONSTRAINED': 2}}
        with self.assertRaisesRegex(ValueError, 'source folder'):
            validate_label_corrections(cohort, cases, self.root, required=True)

    def test_headline_precision_keeps_one_missing_hint_visible(self):
        rate = 42385 / 42386
        self.assertEqual(_percent(rate, digits=4), '99.9976%')
        self.assertNotEqual(_percent(rate, digits=4), _percent(1.0, digits=4))
        result = self.run_summary()
        # Rendering must use headline precision, not the two-place defaults
        # reserved for secondary statistics.
        result['metrics']['tools']['live-ast']['incorrect']['hit_rate_micro'] = rate
        result['metrics']['tools']['live-ast']['incorrect']['hit_rate_macro_exercise'] = rate
        ast_row = next(line for line in render_markdown(result).splitlines()
                       if line.startswith('| Alloy Studio raw AST |'))
        self.assertEqual(ast_row.count('99.9976%'), 2)
        self.assertNotIn('100.00%', ast_row)

    def test_paired_coverage_uses_same_wrong_inputs_only(self):
        counts = self.run_summary()['paired_hint_coverage']['live-canonical__vs__live-ast']
        self.assertEqual(counts['paired_observed'], 2)
        self.assertEqual(counts['both'], 0)
        self.assertEqual(counts['left_only'], 1)
        self.assertEqual(counts['right_only'], 1)
        self.assertEqual(counts['neither'], 0)

    def test_both_modes_have_all_sota_pairs_without_duplicate_reverse(self):
        pairs = self.run_summary()['paired_hint_coverage']
        self.assertEqual(len(pairs), 7)
        self.assertNotIn('live-ast__vs__live-canonical', pairs)
        for focal in ('live-canonical', 'live-ast'):
            for other in ('tar-depth-2', 'fm24-history', 'fm24-mutation'):
                record = pairs[focal + '__vs__' + other]
                self.assertEqual(record['left_tool'], focal)
                self.assertEqual(record['right_tool'], other)
                self.assertEqual(sum(record[key] for key in ('both', 'left_only', 'right_only', 'neither')), 2)
        self.assertEqual(pairs['live-ast__vs__tar-depth-2']['left_only'], 1)

    def test_missing_rows_are_fatal_but_partial_keeps_denominator(self):
        self.rows['live-canonical'].pop(1)
        self.write_rows('live-canonical')
        (self.data / 'live-full/run.json').unlink()
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.run_summary()
        result = self.run_summary(allow_partial=True)
        self.assertEqual(result['status'], 'RUNNING')
        self.assertEqual(result['metrics']['tools']['live-canonical']['incorrect']['cases'], 2)
        self.assertEqual(result['metrics']['tools']['live-canonical']['incorrect']['missing'], 1)

    def test_missing_completion_record_is_not_final(self):
        (self.data / 'tar/run.json').unlink()
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.run_summary()

    def test_duplicate_case_is_rejected(self):
        self.rows['live-ast'].append(deepcopy(self.rows['live-ast'][0]))
        self.write_rows('live-ast')
        with self.assertRaisesRegex(ValueError, 'duplicate'):
            self.run_summary()

    def test_row_source_hash_cannot_be_stale(self):
        self.rows['tar-depth-2'][0]['source_sha256'] = '0' * 64
        self.write_rows('tar-depth-2')
        with self.assertRaisesRegex(ValueError, 'source_sha256 mismatch'):
            self.run_summary()

    def test_actual_external_source_change_is_rejected(self):
        Path(self.cases[0]['path']).write_text('modified model after measurement')
        with self.assertRaisesRegex(ValueError, 'corpus source'):
            self.run_summary()

    def test_wrong_fold_rejected_but_unsupported_fm_without_fold_valid(self):
        self.rows['live-canonical'][0]['fold'] = 4
        self.write_rows('live-canonical')
        with self.assertRaisesRegex(ValueError, 'fold mismatch'):
            self.run_summary()
        self.rows['live-canonical'][0]['fold'] = 0
        self.write_rows('live-canonical')
        self.rows['fm24-history'][0].update(fold=None, supported=False, status='native_edge_error')
        self.write_rows('fm24-history')
        result = self.run_summary()
        self.assertEqual(result['metrics']['tools']['fm24-history']['incorrect']['unsupported'], 1)

    def test_stale_declared_artifact_hash_is_rejected(self):
        path = self.data / 'tar/manifest.json'
        manifest = json.loads(path.read_text())
        manifest['case_manifest_sha256'] = '0' * 64
        json_file(path, manifest)
        with self.assertRaisesRegex(ValueError, 'Stale'):
            self.run_summary()

    def test_native_repairs_and_validation_errors_do_not_become_hint_hits(self):
        self.rows['tar-depth-2'][0].update(native_repair_available=True, verified_correct=True,
                                         validation_status='checked', validation_seconds=.2)
        self.rows['tar-depth-2'][1].update(native_repair_available=True, verified_correct=None,
                                         validation_status='validation_error', validation_seconds=.2)
        self.write_rows('tar-depth-2')
        result = self.run_summary()['metrics']['tools']['tar-depth-2']
        self.assertEqual(result['incorrect']['hints'], 0)
        repairs = result['quality']['full_repairs']
        self.assertEqual(repairs['native_within_budget'], 2)
        self.assertEqual(repairs['independent_validation']['correct'], 1)
        self.assertEqual(repairs['independent_validation']['false'], 0)
        self.assertEqual(repairs['independent_validation']['error'], 1)

    def test_partial_publication_leaves_final_artifacts_unchanged(self):
        result = self.run_summary(allow_partial=True)
        final = self.root / 'docs/result.json'
        markdown = self.root / 'docs/report.md'
        final.parent.mkdir()
        final.write_text('existing results')
        markdown.write_text('existing report')
        progress = self.data / 'progress.json'
        publish(result, final_json=final, markdown=markdown, progress_json=progress)
        self.assertEqual(final.read_text(), 'existing results')
        self.assertEqual(markdown.read_text(), 'existing report')
        self.assertEqual(json.loads(progress.read_text())['status'], 'RUNNING')

    def test_publication_changes_only_markers_and_requires_them(self):
        result = self.run_summary()
        final = self.root / 'docs/result.json'
        markdown = self.root / 'docs/report.md'
        markdown.parent.mkdir()
        markdown.write_text('prefix\n' + BEGIN + '\nold\n' + END + '\nsuffix\n')
        publish(result, final_json=final, markdown=markdown, progress_json=self.data / 'progress.json')
        content = markdown.read_text()
        self.assertTrue(content.startswith('prefix\n' + BEGIN))
        self.assertTrue(content.endswith(END + '\nsuffix\n'))
        self.assertIn('50.0000%', content)
        self.assertIn('Explicit unsupported', content)
        self.assertEqual(json.loads(final.read_text())['schema_version'], 2)
        for bad in ('no markers', END + BEGIN, BEGIN + BEGIN + END):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                update_markers(bad, 'new')

    def test_completion_configuration_and_status_counts_must_agree(self):
        path = self.data / 'live-full/manifest.json'
        manifest = json.loads(path.read_text()); manifest['workers'] = 2
        json_file(path, manifest)
        report_path = self.data / 'live-full/run.json'
        report = json.loads(report_path.read_text()); report['workers'] = 3
        json_file(report_path, report)
        with self.assertRaisesRegex(ValueError, 'configuration differs'):
            self.run_summary()
        report['workers'] = 2; report['statuses'] = {'made_up': 3}
        json_file(report_path, report)
        with self.assertRaisesRegex(ValueError, 'status counts disagree'):
            self.run_summary()

    def test_completion_record_disagreement_and_changed_cohort_rejected(self):
        json_file(self.data / 'tar/run.json', {'cases': 3, 'completed': 2})
        with self.assertRaisesRegex(ValueError, 'completion record disagrees'):
            self.run_summary()
        json_file(self.data / 'tar/run.json', {'cases': 3, 'completed': 3})
        with self.assertRaisesRegex(ValueError, 'cohort size'):
            summarize(self.data, expected_total=4, expected_incorrect=2, repository_root=self.root)

    def test_partial_tail_is_ignored_only_in_running_snapshot(self):
        path = self.data / 'tar/results.jsonl'
        with path.open('a') as handle:
            handle.write('{"case_id":')
        with self.assertRaisesRegex(ValueError, 'Unterminated'):
            self.run_summary()
        result = self.run_summary(allow_partial=True)
        self.assertTrue(result['provenance']['result_snapshots']['tar-depth-2']['incomplete_tail_ignored'])
        self.assertEqual(result['status'], 'RUNNING')


class RecoveryProfileTests(unittest.TestCase):
    """A smaller TAR run is publishable only with the declared recovery evidence."""
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.data = self.root / 'data'
        self.data.mkdir()
        self.runs = {}
        preserved, schedule = {}, []
        for index, arm in enumerate(['live-full', 'ast-full', 'fm24-history', 'fm24-mutation', 'tar']):
            tool = next(tool for tool, name in TOOL_DIRECTORIES.items() if name == arm)
            folder = self.data / arm
            json_file(folder / 'manifest.json', {'tool': tool, 'workers': RECOVERY_WORKERS[arm],
                                                'cases': 3, 'case_manifest_sha256': 'a' * 64})
            json_file(folder / 'run.json', {'completed': 3})
            jsonl_file(folder / 'results.jsonl', [{'synthetic': arm}])
            (folder / 'responses.jsonl.gz').write_bytes(b'synthetic-preservation-evidence')
            if arm != 'tar':
                preserved[arm] = {'sha256': {name: sha(folder / name) for name in (
                    'manifest.json', 'run.json', 'results.jsonl', 'responses.jsonl.gz')}}
            self.runs[tool] = {'state': 'COMPLETE', 'workers': RECOVERY_WORKERS[arm], 'resumed_rows': 0}
            schedule.append({'arm': arm, 'command': ['runner', '--workers', str(RECOVERY_WORKERS[arm])],
                             'started_at': f'2000-01-01T00:{index:02}:00+00:00',
                             'ended_at': f'2000-01-01T00:{index:02}:30+00:00', 'exit_code': 0})
        archive = self.data / 'tar-interrupted'
        json_file(archive / 'manifest.json', {'tool': 'tar-depth-2', 'workers': 16,
                                             'cases': 3, 'case_manifest_sha256': 'a' * 64})
        jsonl_file(archive / 'results.jsonl', [{'synthetic': 'interrupted'}])
        (archive / 'responses.jsonl.gz').write_bytes(b'synthetic-interrupted-response')
        source = self.root / 'benchmarks/alloy4fun/run_guarded_tar.py'
        source.parent.mkdir(parents=True)
        source.write_text('# synthetic guard source\n')
        self.guard = {'status': 'COMPLETED', 'exit_status': 0, 'cgroup_oom_group': 1,
                      'watchdog_stopped_workload': False, 'effective_limits': RECOVERY_LIMITS.copy(),
                      'supervisor_binding_verified': True, 'service_result': 'success',
                      'source_sha256': sha(source), 'memory_peak_bytes': 1024**3,
                      'start_available_min_bytes': 10 * 1024**3, 'stop_available_below_bytes': 4 * 1024**3,
                      'global_available_min_bytes': 10 * 1024**3,
                      'memory_events': {'oom': 0, 'oom_kill': 0, 'oom_group_kill': 0}}
        json_file(self.data / 'recovery/tar-guard.json', self.guard)
        self.profile = {'schema_version': 2, 'arms_sequential': True, 'timeout_seconds': 60,
                        'workers_by_arm': RECOVERY_WORKERS.copy(), 'schedule': schedule,
                        'timing_comparison': 'descriptive_unequal_resource_configurations',
                        'recovery': {'cause': 'global_oom', 'interrupted_arm': 'tar',
                            'preserved_runs': preserved,
                            'archived_run': {'path': archive.name, 'sha256': {name: sha(archive / name) for name in (
                                'manifest.json', 'results.jsonl', 'responses.jsonl.gz')}},
                            'fresh_tar': {**RECOVERY_LIMITS, 'cgroup_evidence_path': 'recovery/tar-guard.json',
                                          'cgroup_evidence_sha256': sha(self.data / 'recovery/tar-guard.json')},
                            'guard_source': str(source.relative_to(self.root)), 'guard_source_sha256': sha(source)}}
        self.write_profile()

    def write_profile(self):
        json_file(self.data / 'resource-profile.json', self.profile)

    def check(self, **kwargs):
        return validate_execution_profile(self.profile, self.data, self.root, self.runs, {}, **kwargs)

    def test_exact_mixed_worker_recovery_is_hash_bound(self):
        evidence = self.check()
        self.assertEqual(evidence['status'], 'MATCHED')
        self.assertEqual(evidence['workers_by_tool']['tar-depth-2'], 4)
        self.assertEqual(evidence['workers_by_tool']['live-ast'], 16)
        self.assertEqual(evidence['fresh_tar_resumed_rows'], 0)
        self.assertEqual(evidence['guard_report_sha256'], sha(self.data / 'recovery/tar-guard.json'))

    def test_arbitrary_worker_or_limit_change_is_rejected(self):
        for target, key, value in [(self.profile['workers_by_arm'], 'tar', 8),
                                   (self.profile['recovery']['fresh_tar'], 'memory_max_bytes', 8 * 1024**3)]:
            previous = target[key]; target[key] = value; self.write_profile()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check()
            target[key] = previous
        self.write_profile()
        self.runs['live-ast']['workers'] = 4
        with self.assertRaisesRegex(ValueError, 'workers disagree'):
            self.check()

    def test_changed_retained_output_or_guard_source_is_rejected(self):
        result = self.data / 'live-full/results.jsonl'
        previous = result.read_bytes(); result.write_bytes(previous + b'changed\n')
        with self.assertRaisesRegex(ValueError, 'preserved run'):
            self.check()
        result.write_bytes(previous)
        (self.root / self.profile['recovery']['guard_source']).write_text('# changed guard\n')
        with self.assertRaisesRegex(ValueError, 'guard source'):
            self.check()

    def test_running_failed_oom_or_watchdog_guard_cannot_publish(self):
        original = deepcopy(self.guard)
        changes = [('status', 'RUNNING'), ('exit_status', 1), ('watchdog_stopped_workload', True),
                   ('supervisor_binding_verified', False), ('source_sha256', 'a' * 64),
                   ('memory_peak_bytes', 7 * 1024**3), ('global_available_min_bytes', 3 * 1024**3),
                   ('memory_events', {'oom': 0, 'oom_kill': 1, 'oom_group_kill': 0}),
                   ('effective_limits', {**RECOVERY_LIMITS, 'memory_swap_max_bytes': 1024})]
        for key, value in changes:
            self.guard = {**original, key: value}
            json_file(self.data / 'recovery/tar-guard.json', self.guard)
            self.profile['recovery']['fresh_tar']['cgroup_evidence_sha256'] = sha(self.data / 'recovery/tar-guard.json')
            self.write_profile()
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.check()

    def test_resumed_or_mixed_old_tar_rows_are_rejected(self):
        self.runs['tar-depth-2']['resumed_rows'] = 1
        with self.assertRaisesRegex(ValueError, 'cannot resume'):
            self.check()
        self.runs['tar-depth-2']['resumed_rows'] = 0
        self.profile['recovery']['archived_run']['path'] = 'tar'
        self.write_profile()
        with self.assertRaisesRegex(ValueError, 'separate'):
            self.check()

    def test_schedule_cannot_hide_interrupted_or_overlapping_arm(self):
        self.profile['schedule'].append(deepcopy(self.profile['schedule'][-1]))
        self.write_profile()
        with self.assertRaisesRegex(ValueError, 'exactly the five'):
            self.check()
        self.profile['schedule'].pop()
        self.profile['schedule'][-1]['started_at'] = '2000-01-01T00:00:00+00:00'
        self.write_profile()
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            self.check()

    def test_partial_profile_defers_final_guard_hash_without_claiming_matched(self):
        self.profile['recovery']['fresh_tar']['cgroup_evidence_sha256'] = None
        self.profile['schedule'].pop()
        self.write_profile()
        result = self.check(allow_partial=True)
        self.assertEqual(result['status'], 'DEFERRED')
        with self.assertRaises(ValueError):
            self.check()


class RequiredPinTests(unittest.TestCase):
    REQUIRED = {
        'live-canonical': ('cohort_sha256', 'payloads_sha256', 'folds_sha256', 'runtime_hashes'),
        'live-ast': ('cohort_sha256', 'payloads_sha256', 'folds_sha256', 'runtime_hashes'),
        'tar-depth-2': ('case_manifest_sha256', 'build_manifest_sha256', 'source_hashes', 'semantic_policy'),
        'fm24-history': ('case_manifest_sha256', 'graph_hashes', 'normalized_cases_sha256', 'sources'),
        'fm24-mutation': ('case_manifest_sha256', 'graph_hashes', 'normalized_cases_sha256', 'sources'),
    }
    MAPS = {'runtime_hashes', 'source_hashes', 'sources', 'graph_hashes'}

    def check(self, manifest):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            _validate_manifest(manifest, manifest['tool'], root / 'nonexistent-data', root, 61598, 60, True)

    def complete_fields(self, tool):
        return {'tool': tool, 'cases': 61598, 'timeout_seconds': 60, 'workers': 16,
                **{field: {'no_overflow': False} if field == 'semantic_policy' else {'entry': '0' * 64} if field in self.MAPS else '0' * 64 for field in self.REQUIRED[tool]}}

    def test_reported_minimal_manifest_counterexample_is_rejected(self):
        for tool in self.REQUIRED:
            with self.subTest(tool=tool), self.assertRaisesRegex(ValueError, 'missing required manifest pin'):
                self.check({'tool': tool, 'cases': 61598, 'timeout_seconds': 60})

    def test_every_production_pin_is_mandatory(self):
        for tool, fields in self.REQUIRED.items():
            for field in fields:
                manifest = self.complete_fields(tool)
                del manifest[field]
                with self.subTest(tool=tool, field=field), self.assertRaisesRegex(ValueError, 'missing required manifest pin ' + field):
                    self.check(manifest)

    def test_empty_maps_do_not_meet_runtime_requirements(self):
        for tool, fields in self.REQUIRED.items():
            for field in set(fields) & self.MAPS:
                manifest = self.complete_fields(tool)
                manifest[field] = {}
                with self.subTest(tool=tool, field=field), self.assertRaisesRegex(ValueError, 'empty or invalid required pin map ' + field):
                    self.check(manifest)

    def test_overflow_default_or_ambiguous_policy_is_rejected(self):
        for value in (True, None, 0, 'false'):
            manifest = self.complete_fields('tar-depth-2')
            manifest['semantic_policy'] = {'no_overflow': value}
            with self.subTest(value=value), self.assertRaisesRegex(ValueError, 'no_overflow=false'):
                self.check(manifest)

    def test_missing_runtime_registry_is_not_optional_in_production(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            with self.assertRaisesRegex(ValueError, 'Missing required public runtime/build provenance'):
                resource_provenance(root, root / 'data', root / 'baselines', verify_resources=True)
            self.assertEqual(resource_provenance(root, root / 'data', root / 'baselines', verify_resources=False), {})

    def test_full_response_audit_is_required_for_production_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp)
            with self.assertRaisesRegex(ValueError, 'Missing full raw-response evidence audit'):
                validate_evidence_audit(path, path, {}, TOOL_DIRECTORIES, 0, required=True)
            self.assertEqual(validate_evidence_audit(path, path, {}, TOOL_DIRECTORIES, 0, required=False)['status'], 'not_yet_available')


if __name__ == '__main__':
    unittest.main()
