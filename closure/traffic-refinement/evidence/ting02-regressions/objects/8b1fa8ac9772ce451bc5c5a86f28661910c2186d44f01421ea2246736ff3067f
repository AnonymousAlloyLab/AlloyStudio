#!/usr/bin/env python3
"""Cross-check closed benchmark rows against their private raw response streams.

The report contains counts and hashes only. Recorded wall-clock measurements are
trusted inputs: this audit neither reruns engines nor independently times them.
Reported replay flags are checked for correspondence, not proved again here.
"""
from __future__ import annotations
import argparse
from collections import Counter
import gzip
import hashlib
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
TOOLS = {'live-full': 'live-canonical', 'ast-full': 'live-ast', 'tar': 'tar-depth-2',
         'fm24-history': 'fm24-history', 'fm24-mutation': 'fm24-mutation'}
MAX_LINE_BYTES = 64 * 1024 * 1024
MAX_CASES = 200000
TAR_CHECKPOINT_FORMAT = 'gzip-member-raw-first-v1'

class AuditError(ValueError):
    """A public failure code, deliberately excluding private case data."""

def require(condition, code):
    if not condition:
        raise AuditError(code)

def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()

def strict_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate_json_key')
        result[key] = value
    return result

def decode(data):
    try:
        return json.loads(data, object_pairs_hook=strict_object,
                          parse_constant=lambda _: (_ for _ in ()).throw(AuditError('nonfinite_json')))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise AuditError('invalid_json') from error

def document(path):
    require(Path(path).is_file(), 'required_artifact_missing')
    result = decode(Path(path).read_bytes())
    require(isinstance(result, dict), 'expected_json_object')
    return result

def records(stream, digest=None):
    while True:
        line = stream.readline(MAX_LINE_BYTES + 1)
        if not line:
            return
        require(len(line) <= MAX_LINE_BYTES, 'record_too_large')
        require(line.endswith(b'\n'), 'incomplete_record')
        require(bool(line.strip()), 'empty_record')
        if digest is not None:
            digest.update(line)
        result = decode(line)
        require(isinstance(result, dict), 'expected_json_object')
        yield result

def identifier(row):
    require(isinstance(row.get('case_id'), str) and bool(row['case_id']), 'case_id_missing')
    return row['case_id']

def field(row, key):
    require(key in row, 'required_field_missing_' + key)
    return row[key]

def boolean(row, key):
    value = field(row, key)
    require(type(value) is bool, 'invalid_boolean_' + key)
    return value

def number(row, key):
    value = field(row, key)
    require(type(value) in (int, float) and math.isfinite(value) and value >= 0, 'invalid_number_' + key)
    return value

def equal(row, key, expected):
    require(field(row, key) == expected, 'mismatch_' + key)

def audit_tar_checkpoint(record, row, manifest_sha256, counters):
    """Verify the durable writer's raw-to-row and run/source provenance bonds."""
    source_sha256 = field(row, 'source_sha256')
    require(isinstance(source_sha256, str) and len(source_sha256) == 64
            and all(char in '0123456789abcdef' for char in source_sha256), 'invalid_checkpoint_source_sha256')
    equal(record, 'source_sha256', source_sha256)
    equal(record, 'manifest_sha256', manifest_sha256)
    encoded = json.dumps(row, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')
    equal(record, 'result_sha256', hashlib.sha256(encoded).hexdigest())
    counters['checkpoint_provenance_checked'] += 1

def audit_live(row, response, counters, tool):
    status = field(response, 'status')
    equal(row, 'status', status)
    equal(row, 'timed_out', status == 'timeout')
    equal(row, 'engine_seconds', response.get('benchmark_engine_seconds'))
    equal(row, 'distance', response.get('distance'))
    if status == 'ok':
        operations = field(response, 'operations')
        require(isinstance(operations, list), 'invalid_operations')
        distance = number(response, 'distance')
        trace = field(response, 'trace')
        require(isinstance(trace, dict), 'invalid_trace')
        cost = 0
        for operation in operations:
            require(isinstance(operation, dict), 'invalid_operation')
            boolean(operation, 'aggregate')
            cost += number(operation, 'cost')
        require(cost == distance, 'operation_cost_distance_mismatch')
        require(number(trace, 'cost') == distance, 'trace_cost_distance_mismatch')
        require(boolean(trace, 'matchesDistance'), 'trace_distance_flag_false')
        replay_key = 'astReplayVerified' if tool == 'live-ast' else 'matrixReplayVerified'
        replay = boolean(trace, replay_key)
        counters['self_reported_replay_true'] += int(replay)
        counters['self_reported_replay_false'] += int(not replay)
        counters['operation_cost_checked_cases'] += 1
        counters['operation_cost_total'] += cost
        counters['distance_total'] += distance
    else:
        # Error responses need not contain edits. Never manufacture a successful
        # result from missing status, operations, distance, or trace evidence.
        operations = response.get('operations', [])
        require(isinstance(operations, list), 'invalid_operations')
        trace = response.get('trace', {})
    atomic = [operation for operation in operations if operation.get('aggregate') is False]
    equal(row, 'operation_count', len(atomic))
    equal(row, 'aggregate_operation_count', len(operations) - len(atomic))
    for field_name, location in (('located_operations', 'sourceLocation'), ('canonical_located_operations', 'canonicalLocation')):
        expected = sum(operation.get(location, {}).get('status') == 'located'
                       and operation.get(location, {}).get('precision') == 'node' for operation in atomic)
        equal(row, field_name, expected)
        counters[field_name] += expected
    equal(row, 'matrix_replay_verified', trace.get('matrixReplayVerified'))
    if tool == 'live-ast':
        equal(row, 'ast_replay_verified', trace.get('astReplayVerified'))
    counters['atomic_operations'] += len(atomic)
    counters['aggregate_operations'] += len(operations) - len(atomic)
    return status == 'ok' and bool(atomic)

def audit_tar(row, response, counters, timeout):
    status = field(response, 'status')
    native = response.get('native_result')
    raw_hint = False
    if native is not None:
        require(isinstance(native, dict), 'invalid_native_result')
        native_status = native.get('status')
        if 'no_overflow' in native:
            require(boolean(native, 'no_overflow') is False, 'native_overflow_policy_mismatch')
        if native_status not in ('timeout', 'worker_error'):
            require(boolean(native, 'no_overflow') is False, 'native_overflow_policy_mismatch')
            counters['native_overflow_policy_checked'] += 1
            solved = boolean(native, 'solved')
            if solved:
                depth = number(native, 'depth')
                trace = field(native, 'native_trace')
                require(isinstance(trace, list) and all(isinstance(step, dict) for step in trace), 'invalid_native_trace')
                require(depth == len(trace), 'mutation_depth_trace_mismatch')
                raw_hint = depth > 0 and any(isinstance(step.get('hint'), str) and step['hint'].strip() for step in trace)
                expected_status = 'already_correct' if depth == 0 else 'repaired'
                equal(response, 'candidate_repair', field(native, 'solution'))
            else:
                depth = None
                expected_status = 'timeout' if native.get('timed_out') else 'engine_error' if native.get('error') else 'no_repair'
            equal(response, 'status', expected_status)
            equal(row, 'mutation_depth', native.get('depth'))
        else:
            equal(response, 'status', native_status)
        equal(row, 'engine_seconds', native.get('api_seconds'))
        equal(response, 'engine_s', native.get('api_seconds'))
        equal(response, 'native_hint_available', raw_hint)
        equal(response, 'hint_available', raw_hint)
        expected_repair = native.get('solved') is True and native.get('depth', 0) > 0
        equal(response, 'repair_available', expected_repair)
        equal(row, 'native_repair_available', expected_repair)
    else:
        require(status in ('adapter_error', 'timeout'), 'missing_native_result')
        equal(row, 'native_repair_available', False)
    equal(row, 'wall_seconds', field(response, 'wall_s'))
    late = row['wall_seconds'] > timeout
    expected_timeout = late or status in ('timeout', 'outer_timeout')
    equal(row, 'timed_out', expected_timeout)
    equal(row, 'status', 'deadline_exceeded' if late else status)
    validated = raw_hint and not expected_timeout
    validation = response.get('independent_validation')
    if validated:
        require(isinstance(validation, dict), 'independent_validation_missing')
        equal(row, 'verified_correct', field(validation, 'verified_correct'))
        equal(row, 'validation_status', field(validation, 'status'))
        equal(row, 'validation_seconds', number(validation, 'validation_process_wall_s'))
        verified = validation['verified_correct']
        # Parse failures and external validation timeouts never reach the SAT
        # result emitter, so their absent flag supplies no semantic evidence.
        # Every checked/non-null verdict must explicitly record the aligned flag.
        if 'no_overflow' in validation or validation['status'] == 'checked' or verified is not None:
            require(boolean(validation, 'no_overflow') is False, 'validation_overflow_policy_mismatch')
            counters['validation_overflow_policy_checked'] += 1
        else:
            counters['unresolved_validation_without_overflow_evidence'] += 1
        require(verified is None or type(verified) is bool, 'invalid_verification_value')
        counters['independent_validation_cases'] += 1
        counters['independently_verified_repairs'] += int(verified is True)
        counters['independently_rejected_repairs'] += int(verified is False)
        counters['independent_validation_unresolved'] += int(verified is None)
    else:
        require(validation is None, 'unexpected_independent_validation')
        equal(row, 'verified_correct', None)
        equal(row, 'validation_seconds', None)
    return raw_hint

def audit_fm24(row, response, counters, timeout):
    status = field(response, 'status')
    raw_hint = boolean(response, 'hint_available')
    # These are query aggregates over native normalize/hint/mutations actions,
    # not an inference from the first query assigned to a Python worker.
    for key in ('cold_worker', 'worker_recycled'):
        actual = boolean(response, key)
        require(boolean(row, key) == actual, 'mismatch_' + key)
    actions = field(response, 'native_action_count')
    row_actions = field(row, 'native_action_count')
    require(type(actions) is int and actions >= 0, 'invalid_integer_native_action_count')
    require(type(row_actions) is int and row_actions >= 0, 'invalid_integer_native_action_count')
    require(row_actions == actions, 'mismatch_native_action_count')
    require(actions > 0 or not (response['cold_worker'] or response['worker_recycled']),
            'native_lifecycle_flags_without_action')
    require(not raw_hint or actions > 0, 'native_hint_without_action')
    counters['native_action_attempts'] += actions
    counters['cold_worker_queries'] += int(response['cold_worker'])
    counters['recycled_worker_queries'] += int(response['worker_recycled'])
    source = response.get('source')
    if raw_hint:
        require(isinstance(response.get('hint'), str) and bool(response['hint'].strip()), 'native_hint_text_missing')
        require(source in ('history', 'mutation'), 'native_hint_source_invalid')
        require(status == 'hint', 'hint_status_mismatch')
        counters['raw_' + source + '_hints'] += 1
    equal(row, 'hint_source', source)
    equal(row, 'engine_seconds', response.get('engine_s'))
    equal(row, 'java_engine_seconds', response.get('java_engine_s'))
    equal(row, 'fold', response.get('fold'))
    equal(row, 'mutation_candidates', response.get('mutation_candidates'))
    late = row['wall_seconds'] > timeout
    expected_status = 'deadline_exceeded' if late else status
    equal(row, 'status', expected_status)
    equal(row, 'timed_out', late or expected_status == 'timeout')
    return raw_hint

def audit_tool(directory: Path, expected_tool: str, expected_cases=None):
    directory = Path(directory)
    run = document(directory / 'run.json')
    manifest = document(directory / 'manifest.json')
    checkpoint_policy = manifest.get('checkpoint_policy')
    durable_checkpoint = expected_tool.startswith('tar-') and isinstance(checkpoint_policy, dict) \
        and checkpoint_policy.get('format') == TAR_CHECKPOINT_FORMAT
    if durable_checkpoint:
        require(run.get('checkpoint_policy') == checkpoint_policy, 'run_checkpoint_policy_mismatch')
    require(run.get('tool') == expected_tool == manifest.get('tool'), 'tool_identity_mismatch')
    if expected_tool.startswith('tar-'):
        policy = manifest.get('semantic_policy')
        require(isinstance(policy, dict) and policy.get('no_overflow') is False, 'manifest_overflow_policy_mismatch')
        require(run.get('semantic_policy') == policy, 'run_overflow_policy_mismatch')
    count = field(run, 'cases')
    require(type(count) is int and 0 < count <= MAX_CASES, 'invalid_case_count')
    require(run.get('completed') == count == manifest.get('cases'), 'run_not_closed')
    timeout = number(manifest, 'timeout_seconds')
    require(timeout > 0 and run.get('timeout_seconds') == timeout, 'timeout_configuration_mismatch')
    paths = {name: directory / name for name in ('run.json', 'manifest.json', 'results.jsonl', 'responses.jsonl.gz')}
    require(all(path.is_file() for path in paths.values()), 'required_artifact_missing')
    hashes = {name: sha(path) for name, path in paths.items()}
    rows = {}
    with paths['results.jsonl'].open('rb') as stream:
        for row in records(stream):
            key = identifier(row)
            require(key not in rows, 'duplicate_result_case')
            require(row.get('tool') == expected_tool, 'row_tool_mismatch')
            number(row, 'wall_seconds')
            boolean(row, 'hint_available')
            boolean(row, 'timed_out')
            require(row.get('cohort_status') in ('CORRECT', 'OVERCONSTRAINED', 'UNDERCONSTRAINED', 'BOTH'), 'invalid_cohort_status')
            if expected_cases is not None:
                require(key in expected_cases, 'unknown_corpus_case')
                for name in ('source_sha256', 'cohort_status'):
                    equal(row, name, field(expected_cases[key], name))
            rows[key] = row
            require(len(rows) <= count, 'excess_result_count')
    require(len(rows) == count, 'result_count_mismatch')
    if expected_cases is not None:
        require(set(rows) == set(expected_cases), 'corpus_case_set_mismatch')
    counters = Counter({'cases': count, 'timings_independently_remeasured': 0, 'raw_responses': 0})
    statuses = Counter()
    digest = hashlib.sha256()
    seen = set()
    ordered_cases = iter(rows)
    try:
        with gzip.open(paths['responses.jsonl.gz'], 'rb') as stream:
            for record in records(stream, digest):
                key = identifier(record)
                require(key not in seen, 'duplicate_response_case')
                require(key in rows, 'unknown_response_case')
                seen.add(key)
                row = rows[key]
                if durable_checkpoint:
                    require(key == next(ordered_cases, None), 'checkpoint_order_mismatch')
                    audit_tar_checkpoint(record, row, hashes['manifest.json'], counters)
                response = field(record, 'response')
                require(isinstance(response, dict), 'invalid_raw_response')
                if 'case_id' in response:
                    require(response['case_id'] == key, 'nested_case_id_mismatch')
                if expected_tool.startswith('live-'):
                    raw_hint = audit_live(row, response, counters, expected_tool)
                    # Live's worker enforces its own deadline. The metric row
                    # stores raw availability; the <=60 headline is derived below.
                    equal(row, 'hint_available', raw_hint)
                elif expected_tool.startswith('tar-'):
                    raw_hint = audit_tar(row, response, counters, timeout)
                    equal(row, 'hint_available', raw_hint and not row['timed_out'])
                else:
                    raw_hint = audit_fm24(row, response, counters, timeout)
                    equal(row, 'hint_available', raw_hint and not row['timed_out'])
                hit = raw_hint and not row['timed_out'] and row['wall_seconds'] <= 60
                control = row['cohort_status'] == 'CORRECT'
                counters['raw_responses'] += 1
                counters['raw_native_hints'] += int(raw_hint)
                counters['native_hints_within_60s'] += int(hit)
                counters['incorrect_cases'] += int(not control)
                counters['correct_controls'] += int(control)
                counters['incorrect_native_hints_within_60s'] += int(hit and not control)
                counters['correct_control_hints_within_60s'] += int(hit and control)
                counters['timeout_rows'] += int(row['timed_out'])
                statuses[row['status']] += 1
    except (OSError, EOFError) as error:
        raise AuditError('incomplete_or_invalid_gzip') from error
    require(seen == set(rows), 'response_case_set_mismatch')
    if 'statuses' in run:
        require(run['statuses'] == dict(statuses), 'run_status_counts_mismatch')
    # Closed artifacts must remain unchanged throughout the audit.
    require(all(sha(path) == hashes[name] for name, path in paths.items()), 'artifact_changed_during_audit')
    return {'tool': expected_tool, 'status': 'PASS', 'counts': dict(sorted(counters.items())),
            'status_counts': dict(sorted(statuses.items())), 'sha256': hashes,
            'responses_decompressed_sha256': digest.hexdigest(),
            'case_id_set_sha256': hashlib.sha256(json.dumps(sorted(rows), separators=(',', ':')).encode()).hexdigest()}

def load_cases(path):
    result = {}
    with Path(path).open('rb') as stream:
        for row in records(stream):
            key = identifier(row)
            require(key not in result, 'duplicate_corpus_case')
            result[key] = row
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'build/benchmarks/alloy4fun')
    parser.add_argument('--tool', action='append', choices=TOOLS, help='Audit selected closed tools; default requires all five')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    selected = args.tool or list(TOOLS)
    try:
        cases_path = args.data / 'cases.jsonl'
        cases = load_cases(cases_path)
        reports = [audit_tool(args.data / name, TOOLS[name], cases) for name in selected]
        report = {'schema_version': 1, 'status': 'PASS', 'scope': 'raw-response-correspondence', 'cases_sha256': sha(cases_path),
                  'audit_source_sha256': sha(__file__), 'tools': reports}
        encoded = json.dumps(report, indent=2, sort_keys=True) + '\n'
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(encoded)
        sys.stdout.write(encoded)
        return 0
    except (AuditError, OSError) as error:
        code = str(error) if isinstance(error, AuditError) else 'artifact_io_error'
        print(json.dumps({'status': 'FAILED', 'error_code': code}, sort_keys=True))
        return 1

if __name__ == '__main__':
    raise SystemExit(main())
