"""Corruption regressions for raw benchmark response-to-row correspondence."""
from copy import deepcopy
import gzip
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from benchmarks.alloy4fun.audit_results import AuditError, audit_tool


class EvidenceAuditTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='alloy-evidence-test-')
        self.directory = Path(self.temporary.name)
        self.row = {
            'case_id': 'private-model-id', 'tool': 'live-canonical',
            'cohort_status': 'BOTH', 'source_sha256': 'a' * 64,
            'status': 'ok', 'hint_available': True, 'timed_out': False,
            'wall_seconds': 0.1, 'engine_seconds': 0.05, 'distance': 2,
            'operation_count': 1, 'aggregate_operation_count': 1,
            'located_operations': 1, 'canonical_located_operations': 0,
            'matrix_replay_verified': True,
        }
        self.response = {
            'status': 'ok', 'distance': 2, 'benchmark_engine_seconds': 0.05,
            'trace': {'cost': 2, 'matchesDistance': True, 'matrixReplayVerified': True},
            'operations': [
                {'aggregate': False, 'cost': 1, 'private_text': 'learner secret payload',
                 'sourceLocation': {'status': 'located', 'precision': 'node'}},
                {'aggregate': True, 'cost': 1},
            ],
        }

    def tearDown(self):
        self.temporary.cleanup()

    def write(self, rows=None, responses=None, tool=None):
        rows = [deepcopy(self.row)] if rows is None else rows
        responses = [{'case_id': self.row['case_id'], 'response': deepcopy(self.response)}] if responses is None else responses
        name = tool or self.row['tool']
        manifest = {'tool': name, 'cases': len(rows), 'timeout_seconds': 60}
        if name.startswith('tar-'):
            manifest['semantic_policy'] = {'no_overflow': False}
        (self.directory / 'manifest.json').write_text(json.dumps(manifest))
        (self.directory / 'run.json').write_text(json.dumps({**manifest, 'completed': len(rows)}))
        (self.directory / 'results.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
        with gzip.open(self.directory / 'responses.jsonl.gz', 'wt') as stream:
            for response in responses:
                stream.write(json.dumps(response) + '\n')

    def audit(self, tool=None):
        return audit_tool(self.directory, tool or self.row['tool'])

    def reject(self, code):
        with self.assertRaisesRegex(AuditError, '^' + code + '$'):
            self.audit()

    def test_valid_cost_includes_aggregate_once_and_report_is_private(self):
        self.write()
        result = self.audit()
        self.assertEqual(result['counts']['operation_cost_total'], 2)
        self.assertEqual(result['counts']['atomic_operations'], 1)
        self.assertEqual(result['counts']['native_hints_within_60s'], 1)
        encoded = json.dumps(result)
        for private in ('private-model-id', 'learner secret payload'):
            self.assertNotIn(private, encoded)
        self.assertEqual(result['counts']['timings_independently_remeasured'], 0)

    def test_corrupted_row_distance_rejected(self):
        self.row['distance'] = 3
        self.write()
        self.reject('mismatch_distance')

    def test_corrupted_row_hint_rejected(self):
        self.row['hint_available'] = False
        self.write()
        self.reject('mismatch_hint_available')

    def test_operation_cost_cannot_be_hidden_by_consistent_row(self):
        self.response['operations'][1]['cost'] = 2
        self.write()
        self.reject('operation_cost_distance_mismatch')

    def test_duplicate_results_rejected(self):
        self.write(rows=[self.row, deepcopy(self.row)])
        self.reject('duplicate_result_case')

    def test_duplicate_raw_case_rejected(self):
        record = {'case_id': self.row['case_id'], 'response': self.response}
        self.write(responses=[record, deepcopy(record)])
        self.reject('duplicate_response_case')

    def test_missing_raw_record_rejected(self):
        self.write(responses=[])
        self.reject('response_case_set_mismatch')

    def test_missing_raw_stream_rejected(self):
        self.write()
        (self.directory / 'responses.jsonl.gz').unlink()
        self.reject('required_artifact_missing')

    def test_truncated_gzip_rejected(self):
        self.write()
        path = self.directory / 'responses.jsonl.gz'
        path.write_bytes(path.read_bytes()[:-5])
        self.reject('incomplete_or_invalid_gzip')

    def test_running_or_unclosed_run_rejected(self):
        self.write()
        path = self.directory / 'run.json'
        value = json.loads(path.read_text())
        value['completed'] = 0
        path.write_text(json.dumps(value))
        self.reject('run_not_closed')

    def test_missing_ok_operations_is_not_imputed(self):
        del self.response['operations']
        self.write()
        self.reject('required_field_missing_operations')

    def test_replay_flag_correspondence_checked(self):
        self.row['matrix_replay_verified'] = False
        self.write()
        self.reject('mismatch_matrix_replay_verified')

    def test_ast_uses_ast_replay_flag(self):
        self.row['tool'] = 'live-ast'
        self.row['matrix_replay_verified'] = None
        self.row['ast_replay_verified'] = True
        del self.response['trace']['matrixReplayVerified']
        self.response['trace']['astReplayVerified'] = True
        self.write()
        self.assertEqual(self.audit()['counts']['self_reported_replay_true'], 1)

    def fm24_fixture(self):
        self.row = {'case_id': 'private-model-id', 'tool': 'fm24-history', 'cohort_status': 'BOTH',
                    'status': 'hint', 'hint_available': True, 'timed_out': False,
                    'wall_seconds': .1, 'engine_seconds': .09, 'java_engine_seconds': .08,
                    'fold': 0, 'hint_source': 'history', 'mutation_candidates': None,
                    'cold_worker': True, 'worker_recycled': True, 'native_action_count': 2}
        self.response = {'status': 'hint', 'hint_available': True, 'hint': 'private hint',
                         'source': 'history', 'engine_s': .09, 'java_engine_s': .08, 'fold': 0,
                         'cold_worker': True, 'worker_recycled': True, 'native_action_count': 2}

    def test_fm24_late_native_hint_excluded(self):
        self.fm24_fixture()
        self.row.update(status='deadline_exceeded', hint_available=False, timed_out=True,
                        wall_seconds=61, engine_seconds=60.5, java_engine_seconds=60)
        self.response.update(engine_s=60.5, java_engine_s=60)
        self.write()
        counts = self.audit()['counts']
        self.assertEqual(counts['raw_native_hints'], 1)
        self.assertEqual(counts['native_hints_within_60s'], 0)
        self.assertEqual(counts['timeout_rows'], 1)

    def test_fm24_native_lifecycle_matches_raw_aggregates(self):
        for tool in ('fm24-history', 'fm24-mutation'):
            with self.subTest(tool=tool):
                self.fm24_fixture()
                self.row['tool'] = tool
                self.write()
                counts = self.audit()['counts']
                self.assertEqual(counts['native_action_attempts'], 2)
                self.assertEqual(counts['cold_worker_queries'], 1)
                self.assertEqual(counts['recycled_worker_queries'], 1)

    def test_fm24_lifecycle_field_mismatch_is_rejected(self):
        for key, value in (('cold_worker', False), ('worker_recycled', False), ('native_action_count', 3)):
            with self.subTest(key=key):
                self.fm24_fixture()
                self.row[key] = value
                self.write()
                self.reject('mismatch_' + key)

    def test_fm24_lifecycle_fields_are_required_in_row_and_response(self):
        for location in ('row', 'response'):
            for key in ('cold_worker', 'worker_recycled', 'native_action_count'):
                with self.subTest(location=location, key=key):
                    self.fm24_fixture()
                    del getattr(self, location)[key]
                    self.write()
                    self.reject('required_field_missing_' + key)

    def test_fm24_lifecycle_booleans_reject_numeric_aliases_and_null(self):
        for location in ('row', 'response'):
            for key in ('cold_worker', 'worker_recycled'):
                for value in (1, 0, 'true', None):
                    with self.subTest(location=location, key=key, value=value):
                        self.fm24_fixture()
                        getattr(self, location)[key] = value
                        self.write()
                        self.reject('invalid_boolean_' + key)

    def test_fm24_native_action_count_requires_nonnegative_integer(self):
        for location in ('row', 'response'):
            for value in (True, 2.0, -1, '2', None):
                with self.subTest(location=location, value=value):
                    self.fm24_fixture()
                    getattr(self, location)['native_action_count'] = value
                    self.write()
                    self.reject('invalid_integer_native_action_count')

    def test_fm24_unsupported_zero_action_response_is_valid(self):
        self.fm24_fixture()
        self.row.update(status='native_edge_error', hint_available=False, hint_source=None,
                        fold=None, cold_worker=False, worker_recycled=False, native_action_count=0)
        self.response = {'status': 'native_edge_error', 'hint_available': False,
                         'engine_s': .09, 'java_engine_s': .08,
                         'cold_worker': False, 'worker_recycled': False, 'native_action_count': 0}
        self.write()
        counts = self.audit()['counts']
        self.assertEqual(counts['native_action_attempts'], 0)
        self.assertEqual(counts['cold_worker_queries'], 0)
        self.assertEqual(counts['recycled_worker_queries'], 0)

    def test_fm24_lifecycle_events_without_native_action_are_rejected(self):
        for key in ('cold_worker', 'worker_recycled'):
            with self.subTest(key=key):
                self.fm24_fixture()
                for value in (self.row, self.response):
                    value.update(cold_worker=False, worker_recycled=False, native_action_count=0)
                    value[key] = True
                self.write()
                self.reject('native_lifecycle_flags_without_action')

    def test_fm24_hint_without_native_action_is_rejected(self):
        self.fm24_fixture()
        for value in (self.row, self.response):
            value.update(cold_worker=False, worker_recycled=False, native_action_count=0)
        self.write()
        self.reject('native_hint_without_action')

    def tar_fixture(self):
        self.row = {'case_id': 'private-model-id', 'tool': 'tar-depth-2', 'cohort_status': 'BOTH',
                    'source_sha256': 'a' * 64,
                    'status': 'repaired', 'hint_available': True, 'timed_out': False,
                    'wall_seconds': 0.2, 'engine_seconds': 0.1, 'mutation_depth': 1,
                    'native_repair_available': True, 'verified_correct': True,
                    'validation_status': 'checked', 'validation_seconds': 0.3}
        self.response = {'status': 'repaired', 'hint_available': True, 'native_hint_available': True,
                         'repair_available': True, 'wall_s': 0.2, 'engine_s': 0.1,
                         'candidate_repair': {'inv1': 'private body'},
                         'native_result': {'solved': True, 'timed_out': False, 'depth': 1, 'no_overflow': False,
                                           'native_trace': [{'hint': 'Check this operator'}],
                                           'solution': {'inv1': 'private body'}, 'api_seconds': 0.1},
                         'independent_validation': {'verified_correct': True, 'status': 'checked', 'no_overflow': False,
                                                    'validation_process_wall_s': 0.3}}

    def bind_tar_checkpoint(self):
        policy = {'format': 'gzip-member-raw-first-v1'}
        for name in ('manifest.json', 'run.json'):
            path = self.directory / name
            value = json.loads(path.read_text())
            value['checkpoint_policy'] = policy
            path.write_text(json.dumps(value))
        manifest_digest = hashlib.sha256((self.directory / 'manifest.json').read_bytes()).hexdigest()
        rows = {row['case_id']: row for row in map(json.loads, (self.directory / 'results.jsonl').read_text().splitlines())}
        path = self.directory / 'responses.jsonl.gz'
        with gzip.open(path, 'rt') as stream:
            records = [json.loads(line) for line in stream]
        for record in records:
            row = rows[record['case_id']]
            record.update(source_sha256=row['source_sha256'], manifest_sha256=manifest_digest,
                          result_sha256=hashlib.sha256(json.dumps(row, sort_keys=True, separators=(',', ':'),
                                                                 allow_nan=False).encode()).hexdigest())
        self.write_checkpoint_records(records)
        return records

    def write_checkpoint_records(self, records):
        with (self.directory / 'responses.jsonl.gz').open('wb') as stream:
            for record in records:
                stream.write(gzip.compress((json.dumps(record) + '\n').encode(), mtime=0))

    def test_tar_durable_checkpoint_checks_provenance_without_requiring_it_from_legacy(self):
        self.tar_fixture()
        self.write()
        self.assertNotIn('checkpoint_provenance_checked', self.audit()['counts'])
        self.bind_tar_checkpoint()
        self.assertEqual(self.audit()['counts']['checkpoint_provenance_checked'], 1)

    def test_tar_durable_checkpoint_requires_all_raw_provenance_fields(self):
        self.tar_fixture()
        for key in ('source_sha256', 'manifest_sha256', 'result_sha256'):
            for change in ('missing', 'different'):
                with self.subTest(key=key, change=change):
                    self.write()
                    records = self.bind_tar_checkpoint()
                    if change == 'missing':
                        records[0].pop(key)
                    else:
                        records[0][key] = 'b' * 64
                    self.write_checkpoint_records(records)
                    self.reject(('required_field_missing_' if change == 'missing' else 'mismatch_') + key)

    def test_tar_durable_checkpoint_binds_the_complete_row(self):
        self.tar_fixture()
        self.write()
        self.bind_tar_checkpoint()
        row = {**self.row, 'cold_worker': True}
        (self.directory / 'results.jsonl').write_text(json.dumps(row) + '\n')
        self.reject('mismatch_result_sha256')

    def test_tar_durable_checkpoint_requires_committed_record_order(self):
        self.tar_fixture()
        other_row = {**self.row, 'case_id': 'second-private-model-id'}
        self.write(rows=[self.row, other_row], responses=[
            {'case_id': self.row['case_id'], 'response': self.response},
            {'case_id': other_row['case_id'], 'response': self.response}])
        records = self.bind_tar_checkpoint()
        self.assertEqual(self.audit()['counts']['checkpoint_provenance_checked'], 2)
        self.write_checkpoint_records(list(reversed(records)))
        self.reject('checkpoint_order_mismatch')

    def test_tar_durable_checkpoint_requires_consistent_run_policy(self):
        self.tar_fixture()
        self.write()
        self.bind_tar_checkpoint()
        path = self.directory / 'run.json'
        value = json.loads(path.read_text())
        del value['checkpoint_policy']
        path.write_text(json.dumps(value))
        self.reject('run_checkpoint_policy_mismatch')

    def test_tar_validation_matches_raw_evidence(self):
        self.tar_fixture()
        self.write()
        self.assertEqual(self.audit()['counts']['independently_verified_repairs'], 1)
        self.response['independent_validation']['verified_correct'] = False
        self.write()
        self.reject('mismatch_verified_correct')

    def test_tar_missing_verification_is_not_imputed(self):
        self.tar_fixture()
        del self.response['independent_validation']
        self.write()
        self.reject('independent_validation_missing')

    def test_tar_full_repair_without_actual_hint_is_not_a_hint(self):
        self.tar_fixture()
        self.response['native_result']['native_trace'][0]['hint'] = None
        self.write()
        self.reject('mismatch_native_hint_available')

    def test_native_overflow_true_rejected(self):
        self.tar_fixture()
        self.response['native_result']['no_overflow'] = True
        self.write()
        self.reject('native_overflow_policy_mismatch')

    def test_native_overflow_missing_rejected(self):
        self.tar_fixture()
        del self.response['native_result']['no_overflow']
        self.write()
        self.reject('required_field_missing_no_overflow')

    def test_verified_repair_with_wrong_overflow_policy_rejected(self):
        self.tar_fixture()
        self.response['independent_validation']['no_overflow'] = True
        self.write()
        self.reject('validation_overflow_policy_mismatch')

    def test_verified_repair_missing_overflow_policy_rejected(self):
        self.tar_fixture()
        del self.response['independent_validation']['no_overflow']
        self.write()
        self.reject('required_field_missing_no_overflow')

    def test_unresolved_parse_failure_does_not_invent_solver_policy(self):
        self.tar_fixture()
        self.row['verified_correct'] = None
        self.row['validation_status'] = 'validation_error'
        self.response['independent_validation'] = {
            'verified_correct': None, 'status': 'validation_error',
            'error_class': 'ErrorSyntax', 'validation_process_wall_s': 0.3}
        self.write()
        counts = self.audit()['counts']
        self.assertEqual(counts['unresolved_validation_without_overflow_evidence'], 1)
        self.assertEqual(counts['independently_verified_repairs'], 0)
        self.response['independent_validation']['no_overflow'] = True
        self.write()
        self.reject('validation_overflow_policy_mismatch')

    def test_transport_timeout_requires_no_invented_native_policy(self):
        self.tar_fixture()
        self.row.update(status='deadline_exceeded', hint_available=False, timed_out=True,
                        wall_seconds=61, engine_seconds=None, mutation_depth=None,
                        native_repair_available=False, verified_correct=None,
                        validation_seconds=None)
        self.row.pop('validation_status')
        self.response = {'status': 'timeout', 'wall_s': 61, 'engine_s': None,
                         'hint_available': False, 'native_hint_available': False,
                         'repair_available': False, 'native_result': {'status': 'timeout'}}
        self.write()
        counts = self.audit()['counts']
        self.assertEqual(counts['native_hints_within_60s'], 0)
        self.assertEqual(counts.get('native_overflow_policy_checked', 0), 0)

    def test_legacy_or_wrong_manifest_policy_rejected(self):
        self.tar_fixture()
        for policy in (None, {'no_overflow': True}, {'no_overflow': 0}):
            self.write()
            path = self.directory / 'manifest.json'
            document = json.loads(path.read_text())
            document['semantic_policy'] = policy
            path.write_text(json.dumps(document))
            self.reject('manifest_overflow_policy_mismatch')

    def test_run_policy_must_match_manifest(self):
        self.tar_fixture()
        self.write()
        path = self.directory / 'run.json'
        document = json.loads(path.read_text())
        document['semantic_policy'] = {'no_overflow': True}
        path.write_text(json.dumps(document))
        self.reject('run_overflow_policy_mismatch')

    def test_expected_corpus_mapping_checked(self):
        self.write()
        with self.assertRaisesRegex(AuditError, 'unknown_corpus_case'):
            audit_tool(self.directory, 'live-canonical', {'other-private-case': self.row})


if __name__ == '__main__':
    unittest.main()
