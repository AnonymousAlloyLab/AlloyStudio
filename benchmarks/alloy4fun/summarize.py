#!/usr/bin/env python3
"""Validate and summarize the five full-corpus Alloy4Fun benchmark runs.

Final output is refused unless all expected rows and completion records exist.
--allow-partial emits only an explicitly RUNNING build artifact; it never
modifies the public Markdown or final JSON report.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.protocol.metrics import aggregate

SCHEMA_VERSION = 2
METHOD_VERSION = 'alloy4fun-comparison-v3'
EXPECTED_TOTAL = 61598
EXPECTED_INCORRECT = 42388
CORRECTION_REGISTRY = 'benchmarks/alloy4fun/protocol/label-corrections.json'
CORRECTION_CASE_IDS = frozenset({
    'socialMedia/correct/6j7rC3GMvjoGpyX7u_inv1.als',
    'socialMedia/correct/RjBNwdcpzytR8C39D_inv1.als',
})
SOURCE_CLASSIFICATION_COUNTS = {'BOTH': 21715, 'CORRECT': 19212,
                                'OVERCONSTRAINED': 8095, 'UNDERCONSTRAINED': 12576}
CORRECTED_CLASSIFICATION_COUNTS = {'BOTH': 21715, 'CORRECT': 19210,
                                   'OVERCONSTRAINED': 8095, 'UNDERCONSTRAINED': 12578}
BEGIN = '<!-- BEGIN GENERATED BENCHMARK RESULTS -->'
END = '<!-- END GENERATED BENCHMARK RESULTS -->'
TOOL_DIRECTORIES = {
    'live-canonical': 'live-full',
    'live-ast': 'ast-full',
    'tar-depth-2': 'tar',
    'fm24-history': 'fm24-history',
    'fm24-mutation': 'fm24-mutation',
}
LABELS = {'live-canonical': 'Alloy Studio canonical', 'live-ast': 'Alloy Studio raw AST',
          'tar-depth-2': 'TAR, depth 2', 'fm24-history': 'FM24 historical',
          'fm24-mutation': 'FM24 historical + mutation'}
NON_ERROR_STATUSES = {'ok', 'hint', 'no_hint', 'known_correct', 'already_correct', 'repaired', 'no_repair'}
SHA256 = re.compile(r'[0-9a-f]{64}')
RECOVERY_WORKERS = {arm: 4 if arm == 'tar' else 16 for arm in TOOL_DIRECTORIES.values()}
RECOVERY_LIMITS = {'workers': 4, 'memory_high_bytes': 4 * 1024**3,
                   'memory_max_bytes': 6 * 1024**3, 'memory_swap_max_bytes': 0,
                   'nice': 10, 'cpu_quota_percent': 400}
RECOVERY_TIMING = ('Sequential arms: Alloy Studio and FM24 use 16 workers; '
                   'TAR uses 4 workers with a 6 GiB memory cap and 4-CPU quota after host OOM. '
                   'Timings and deadline-conditioned availability are descriptive under unequal resource configurations, '
                   'not a controlled speed ranking or isolated interactive latency.')
UNIFORM_TIMING = ('Sequential method arms with 16 concurrent requests per arm; '
                  'wall times include within-arm contention and are not isolated interactive-service latency.')
CORRECT_CONTROL_POLICY = (
    'These CORRECT submissions were held out of Live and FM24 training pools by whole-branch folds. '
    'Their nonempty hints describe responses to held-out correct submissions, not failure to recognize '
    'members already admitted to a full correct pool. The original Alloy4FunAugmenter includes its '
    'oracle and all successful CORRECT submissions without this holdout, and ranks incorrect inputs only; '
    'these control counts are not original-policy results. A nonempty hint does not establish an incorrect '
    'edit, and positive syntactic distance between different predicates can coexist with bounded semantic correctness.')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def sha(path):
    hasher = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def read_json(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def read_rows(path, *, allow_partial=False):
    path = Path(path)
    data = path.read_bytes()
    incomplete_tail = bool(data and not data.endswith(b'\n'))
    if incomplete_tail and not allow_partial:
        raise ValueError(f'Unterminated result row: {path}')
    lines = data.splitlines()
    if incomplete_tail:
        lines.pop()
    rows = []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            raise ValueError(f'Empty JSONL row {number}: {path}')
        row = json.loads(line)
        if not isinstance(row, dict):
            raise ValueError(f'JSONL row is not an object: {path}:{number}')
        rows.append(row)
    return rows, {'sha256': digest(data), 'bytes': len(data), 'rows': len(rows),
                  'incomplete_tail_ignored': incomplete_tail}


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def require_hash(path, expected, label):
    if not isinstance(expected, str) or not SHA256.fullmatch(expected):
        raise ValueError(f'Missing or malformed SHA-256 for {label}')
    if not Path(path).is_file() or sha(path) != expected:
        raise ValueError(f'Stale or missing input: {label}')


def _repository_file(root, relative):
    relative = Path(relative)
    if relative.is_absolute() or '..' in relative.parts or not relative.parts:
        raise ValueError('Manifest source must be repository relative')
    if relative.as_posix() != 'runtime_dependencies.py' and relative.parts[0] not in {'benchmarks', 'build', 'vendor', 'engine', 'scripts'}:
        raise ValueError('Manifest source is outside benchmark/runtime inputs')
    return root / relative


def _data_file(data, relative):
    path = Path(relative)
    if path.is_absolute() or '..' in path.parts or not path.parts:
        raise ValueError('Execution evidence must be data-relative')
    resolved = (Path(data) / path).resolve()
    if not resolved.is_relative_to(Path(data).resolve()):
        raise ValueError('Execution evidence escapes the experiment directory')
    return resolved


def load_execution_profile(data):
    """Only the original uniform profile and the documented OOM recovery are allowed."""
    path = Path(data) / 'resource-profile.json'
    if not path.is_file():
        raise ValueError('Missing registered execution resource profile')
    profile = read_json(path)
    arms = set(TOOL_DIRECTORIES.values())
    if profile.get('arms_sequential') is not True or profile.get('timeout_seconds') != 60:
        raise ValueError('Execution profile must retain sequential arms and the shared 60-second budget')
    if profile.get('schema_version') == 1 and profile.get('workers_per_arm') == 16 and 'recovery' not in profile:
        return profile, {tool: 16 for tool in TOOL_DIRECTORIES}
    if (profile.get('schema_version') != 2 or profile.get('workers_by_arm') != RECOVERY_WORKERS
            or profile.get('workers_per_arm') is not None
            or profile.get('timing_comparison') != 'descriptive_unequal_resource_configurations'):
        raise ValueError('Unregistered mixed-worker execution profile')
    recovery = profile.get('recovery', {})
    if (recovery.get('cause') != 'global_oom' or recovery.get('interrupted_arm') != 'tar'
            or set(recovery.get('preserved_runs', {})) != arms - {'tar'}):
        raise ValueError('Recovery must preserve exactly the four completed non-TAR arms')
    fresh = recovery.get('fresh_tar', {})
    if any(type(fresh.get(key)) is not int or fresh[key] != value for key, value in RECOVERY_LIMITS.items()):
        raise ValueError('TAR recovery resource limits differ from the registered policy')
    if recovery.get('guard_source') != 'benchmarks/alloy4fun/run_guarded_tar.py':
        raise ValueError('Unregistered TAR recovery guard source')
    return profile, {tool: RECOVERY_WORKERS[arm] for tool, arm in TOOL_DIRECTORIES.items()}


def validate_execution_profile(profile, data, root, runs, snapshots, *, allow_partial=False):
    """Bind retained runs and the fresh guarded TAR batch to their execution evidence."""
    data, root = Path(data), Path(root)
    loaded, workers = load_execution_profile(data)
    if loaded != profile:
        raise ValueError('Execution profile changed during summarization')
    for tool, run in runs.items():
        if run['state'] != 'NOT_STARTED' and run.get('workers') != workers[tool]:
            raise ValueError(f'{tool}: run workers disagree with execution evidence')
    result = {'status': 'DEFERRED' if allow_partial else 'MATCHED',
              'kind': 'oom_recovery' if 'recovery' in profile else 'sequential_equal_workers',
              'workers_by_tool': workers, 'resource_profile_sha256': sha(data / 'resource-profile.json'),
              'timing_comparison': profile.get('timing_comparison', 'sequential_equal_worker_batches')}
    if allow_partial:
        return result
    schedule = profile.get('schedule')
    expected_order = ['live-full', 'ast-full', 'fm24-history', 'fm24-mutation', 'tar']
    if not isinstance(schedule, list) or [item.get('arm') for item in schedule] != expected_order:
        raise ValueError('Execution schedule must contain exactly the five compared arms')
    last_end = None
    for item in schedule:
        command = item.get('command', [])
        arm = item['arm']
        tool = next(tool for tool, directory in TOOL_DIRECTORIES.items() if directory == arm)
        try:
            command_workers = int(command[command.index('--workers') + 1])
            started = datetime.fromisoformat(item['started_at'])
            ended = datetime.fromisoformat(item['ended_at'])
        except (KeyError, IndexError, TypeError, ValueError):
            raise ValueError('Incomplete execution schedule metadata for ' + arm) from None
        if (command_workers != workers[tool] or item.get('exit_code') != 0 or ended < started
                or (last_end is not None and started < last_end)):
            raise ValueError('Execution schedule overlaps, failed, or uses different workers: ' + arm)
        last_end = ended
    if 'recovery' not in profile:
        return result
    recovery = profile['recovery']
    fresh = recovery['fresh_tar']
    source = root / recovery['guard_source']
    require_hash(source, recovery.get('guard_source_sha256'), 'TAR recovery guard source')
    for arm, preserved in recovery['preserved_runs'].items():
        hashes = preserved.get('sha256', {})
        if set(hashes) != {'manifest.json', 'run.json', 'results.jsonl', 'responses.jsonl.gz'}:
            raise ValueError('Incomplete preserved-arm hash inventory: ' + arm)
        for name, expected in hashes.items():
            require_hash(data / arm / name, expected, 'preserved run ' + arm + '/' + name)
    archive = recovery.get('archived_run', {})
    folder = _data_file(data, archive.get('path', ''))
    if folder == (data / 'tar').resolve() or folder.name in TOOL_DIRECTORIES.values():
        raise ValueError('Interrupted TAR archive must be separate from every compared arm')
    archive_hashes = archive.get('sha256', {})
    if set(archive_hashes) != {'manifest.json', 'results.jsonl', 'responses.jsonl.gz'}:
        raise ValueError('Incomplete interrupted TAR archive hash inventory')
    for name, expected in archive_hashes.items():
        require_hash(folder / name, expected, 'interrupted TAR archive/' + name)
    archived_manifest = read_json(folder / 'manifest.json')
    current_manifest = read_json(data / 'tar/manifest.json')
    if (archived_manifest.get('workers') != 16 or archived_manifest.get('tool') != 'tar-depth-2'
            or archived_manifest.get('cases') != current_manifest.get('cases')
            or archived_manifest.get('case_manifest_sha256') != current_manifest.get('case_manifest_sha256')):
        raise ValueError('Interrupted TAR archive does not describe the original same-cohort 16-worker run')
    if runs['tar-depth-2'].get('resumed_rows') != 0:
        raise ValueError('Fresh TAR recovery cannot resume rows from the interrupted run')
    guard_path = _data_file(data, fresh.get('cgroup_evidence_path', ''))
    require_hash(guard_path, fresh.get('cgroup_evidence_sha256'), 'completed TAR recovery guard evidence')
    guard = read_json(guard_path)
    validate_guard_report(guard, fresh, expected_source_sha256=recovery['guard_source_sha256'])
    result.update(guard_report_sha256=fresh['cgroup_evidence_sha256'],
                  guard_source_sha256=recovery['guard_source_sha256'],
                  effective_limits=guard['effective_limits'],
                  preserved_arms=sorted(recovery['preserved_runs']),
                  interrupted_archive=archive['path'],
                  fresh_tar_resumed_rows=0)
    return result


def validate_guard_report(guard, fresh, *, expected_source_sha256=None):
    if guard.get('status') != 'COMPLETED' or guard.get('exit_status') != 0:
        raise ValueError('TAR recovery guard did not complete successfully')
    if (type(guard.get('cgroup_oom_group')) is not int or guard['cgroup_oom_group'] != 1
            or guard.get('watchdog_stopped_workload') is not False
            or guard.get('supervisor_binding_verified') is not True
            or guard.get('service_result') != 'success'):
        raise ValueError('TAR recovery lacks verified group OOM/watchdog evidence')
    if (not SHA256.fullmatch(guard.get('source_sha256', ''))
            or (expected_source_sha256 is not None and guard['source_sha256'] != expected_source_sha256)):
        raise ValueError('TAR guard report source hash differs from the registered guard')
    limits = guard.get('effective_limits', {})
    if any(type(limits.get(key)) is not int or limits[key] != value
           for key, value in RECOVERY_LIMITS.items()):
        raise ValueError('TAR recovery effective limits do not match the declared policy')
    events = guard.get('memory_events', {})
    if any(type(events.get(key)) is not int or events[key] != 0 for key in ('oom', 'oom_kill', 'oom_group_kill')):
        raise ValueError('TAR recovery has missing or nonzero OOM events')
    if (guard.get('start_available_min_bytes') != 10 * 1024**3
            or guard.get('stop_available_below_bytes') != 4 * 1024**3
            or type(guard.get('global_available_min_bytes')) is not int
            or guard['global_available_min_bytes'] < 4 * 1024**3
            or type(guard.get('memory_peak_bytes')) is not int
            or not 0 < guard['memory_peak_bytes'] <= fresh['memory_max_bytes']):
        raise ValueError('TAR recovery lacks valid observed memory/reserve bounds')


def validate_label_corrections(cohort, cases, root, *, required):
    """Bind effective labels to the reviewed registry and untouched source hashes."""
    metadata = cohort.get('label_corrections')
    if metadata is None and not required:
        return {'status': 'not_required_for_unverified_fixture'}
    if not isinstance(metadata, dict):
        raise ValueError('Corrected v3 cohort requires label_corrections metadata')
    registry = Path(root) / CORRECTION_REGISTRY
    declared = metadata.get('path')
    if not isinstance(declared, str):
        raise ValueError('Label-correction registry path is missing')
    declared_path = Path(declared)
    if not declared_path.is_absolute():
        declared_path = Path(root) / declared_path
    if declared_path.resolve() != registry.resolve():
        raise ValueError('Label-correction registry path is not the registered public input')
    require_hash(registry, metadata.get('sha256'), 'label-correction registry')
    document = read_json(registry)
    entries = document.get('corrections')
    if document.get('schema_version') != 1 or not isinstance(entries, list) or not entries:
        raise ValueError('Invalid label-correction registry schema')
    corrections = {}
    for entry in entries:
        if not isinstance(entry, dict) or not isinstance(entry.get('case_id'), str):
            raise ValueError('Invalid label-correction registry entry')
        cid = entry['case_id']
        if cid in corrections or cid not in cases:
            raise ValueError('Duplicate or absent label-correction case')
        if not SHA256.fullmatch(entry.get('source_sha256', '')):
            raise ValueError('Label correction lacks a source hash')
        if entry.get('original_label') not in SOURCE_CLASSIFICATION_COUNTS or entry.get('corrected_label') not in SOURCE_CLASSIFICATION_COUNTS:
            raise ValueError('Label correction has an unknown classification')
        if entry['original_label'] == entry['corrected_label']:
            raise ValueError('Label correction does not change classification')
        corrections[cid] = entry
    if metadata.get('applied_count') != len(corrections) or metadata.get('case_ids') != sorted(corrections):
        raise ValueError('Cohort label-correction inventory mismatch')
    if required and (set(corrections) != CORRECTION_CASE_IDS or any(
            entry['original_label'] != 'CORRECT' or entry['corrected_label'] != 'UNDERCONSTRAINED'
            for entry in corrections.values())):
        raise ValueError('Corrected v3 cohort requires the two reviewed label corrections')
    source_counts, effective_counts = Counter(), Counter()
    for cid, case in cases.items():
        source_label, effective_label = case.get('source_cohort_status'), case.get('cohort_status')
        if source_label not in SOURCE_CLASSIFICATION_COUNTS or effective_label not in SOURCE_CLASSIFICATION_COUNTS:
            raise ValueError('Corrected cohort lacks valid source/effective labels')
        if required:
            parts = Path(cid).parts
            folder_labels = {'correct': 'CORRECT', 'both': 'BOTH',
                             'over': 'OVERCONSTRAINED', 'under': 'UNDERCONSTRAINED'}
            if len(parts) != 3 or folder_labels.get(parts[1]) != source_label:
                raise ValueError('Original classification disagrees with source folder: ' + cid)
        entry = corrections.get(cid)
        if entry:
            if (case.get('source_sha256'), source_label, effective_label) != (
                    entry['source_sha256'], entry['original_label'], entry['corrected_label']):
                raise ValueError('Label-correction source hash or classification mismatch: ' + cid)
        elif effective_label != source_label:
            raise ValueError('Unregistered classification change: ' + cid)
        source_counts[source_label] += 1
        effective_counts[effective_label] += 1
    if cohort.get('source_classification_counts') != dict(source_counts) or cohort.get('classification_counts') != dict(effective_counts):
        raise ValueError('Cohort source/effective classification counts mismatch')
    if required and (dict(source_counts) != SOURCE_CLASSIFICATION_COUNTS or dict(effective_counts) != CORRECTED_CLASSIFICATION_COUNTS):
        raise ValueError('Corrected v3 classification denominators mismatch')
    return {'status': 'MATCHED', 'registry_path': CORRECTION_REGISTRY,
            'registry_sha256': metadata['sha256'], 'applied_count': len(corrections),
            'case_ids': sorted(corrections), 'source_classification_counts': dict(source_counts),
            'effective_classification_counts': dict(effective_counts)}


def _validate_manifest(manifest, tool, data, root, expected_total, timeout, runtime_pins, *, expected_workers=16):
    if manifest.get('tool') != tool or manifest.get('cases') != expected_total:
        raise ValueError(f'{tool}: manifest tool/cohort count mismatch')
    if manifest.get('timeout_seconds') != timeout:
        raise ValueError(f'{tool}: timeout differs from shared budget')
    if manifest.get('limit', 0) != 0:
        raise ValueError(f'{tool}: limited pilot cannot be a full run')
    if runtime_pins:
        requirements = {
            'live-canonical': ('cohort_sha256', 'payloads_sha256', 'folds_sha256', 'runtime_hashes'),
            'live-ast': ('cohort_sha256', 'payloads_sha256', 'folds_sha256', 'runtime_hashes'),
            'tar-depth-2': ('case_manifest_sha256', 'build_manifest_sha256', 'source_hashes', 'semantic_policy'),
            'fm24-history': ('case_manifest_sha256', 'graph_hashes', 'normalized_cases_sha256', 'sources'),
            'fm24-mutation': ('case_manifest_sha256', 'graph_hashes', 'normalized_cases_sha256', 'sources'),
        }
        if tool not in requirements:
            raise ValueError('Unregistered production benchmark tool')
        for field in requirements[tool]:
            if field not in manifest:
                raise ValueError(f'{tool}: missing required manifest pin {field}')
            if field in {'runtime_hashes', 'source_hashes', 'sources', 'graph_hashes'}:
                if not isinstance(manifest[field], dict) or not manifest[field]:
                    raise ValueError(f'{tool}: empty or invalid required pin map {field}')
        if type(manifest.get('workers')) is not int or manifest['workers'] != expected_workers:
            raise ValueError(f'{tool}: worker count differs from the registered execution profile')
        if tool == 'tar-depth-2':
            semantic_policy = manifest.get('semantic_policy')
            if not isinstance(semantic_policy, dict) or semantic_policy.get('no_overflow') is not False:
                raise ValueError('TAR requires explicit semantic_policy.no_overflow=false to match the corpus')
        if tool.startswith('live-'):
            runner = 'run_live.py' if tool == 'live-canonical' else 'run_ast.py'
            expected_sources = {f'benchmarks/alloy4fun/{runner}', 'benchmarks/alloy4fun/live/adapter.py',
                                'benchmarks/alloy4fun/live/LiveWorker.java', 'benchmarks/alloy4fun/prepare.py',
                                'runtime_dependencies.py'}
            for directory, pattern in [('build/engine/classes', '*.class'), ('build/benchmarks/alloy4fun/classes', '*.class'),
                                       ('engine/src', '*.java'), ('vendor/acgn/src', '*.java'), ('vendor/acgn/lib', '*.jar')]:
                found = {str(path.relative_to(root)) for path in (root / directory).rglob(pattern)}
                if not found:
                    raise ValueError(f'{tool}: required runtime inventory is absent: {directory}')
                expected_sources.update(found)
            if set(manifest['runtime_hashes']) != expected_sources:
                raise ValueError(f'{tool}: incomplete or mismatched runtime pin inventory')
        elif tool == 'tar-depth-2':
            expected_sources = {'benchmarks/alloy4fun/run_tar.py', 'benchmarks/alloy4fun/live/adapter.py',
                                'runtime_dependencies.py'}
            expected_sources.update(str(path.relative_to(root)) for pattern in ('*.py', '*.java')
                                    for path in (root / 'benchmarks/alloy4fun/tar').glob(pattern))
            if set(manifest['source_hashes']) != expected_sources:
                raise ValueError(f'{tool}: incomplete or mismatched source pin inventory')
            if manifest.get('depth') != 2:
                raise ValueError('TAR primary comparison must use mutation depth 2')
        else:
            expected_sources = {'benchmarks/alloy4fun/run_fm24.py', 'runtime_dependencies.py'}
            expected_sources.update(str(path.relative_to(root)) for pattern in ('*.py', '*.java')
                                    for path in (root / 'benchmarks/alloy4fun/fm24').glob(pattern))
            expected_sources.update(str(path.relative_to(root)) for path in (root / 'build/benchmarks/fm24-classes').glob('*.class'))
            if 'build/benchmarks/fm24-classes/FM24Native.class' not in expected_sources:
                raise ValueError('FM24 compiled adapter is absent')
            if set(manifest['sources']) != expected_sources:
                raise ValueError(f'{tool}: incomplete or mismatched source pin inventory')
            if manifest.get('mutations') is not (tool == 'fm24-mutation'):
                raise ValueError(f'{tool}: mutation arm identity differs from its configuration')
    for field, filename in [('case_manifest_sha256', 'cases.jsonl'), ('cohort_sha256', 'cohort.json'),
                            ('payloads_sha256', 'payloads.jsonl'), ('folds_sha256', 'lineage.json')]:
        if field in manifest:
            require_hash(data / filename, manifest[field], f'{tool}/{field}')
    if runtime_pins:
        for field in ('runtime_hashes', 'source_hashes', 'sources'):
            for relative, expected in manifest.get(field, {}).items():
                require_hash(_repository_file(root, relative), expected, f'{tool}/{relative}')
    if 'build_manifest_sha256' in manifest:
        require_hash(root / 'build/benchmarks/tar/manifest.json', manifest['build_manifest_sha256'], 'TAR build manifest')
        if runtime_pins:
            build = read_json(root / 'build/benchmarks/tar/manifest.json')
            if build.get('semantic_policy') != manifest.get('semantic_policy'):
                raise ValueError('TAR build/run semantic policies disagree')
    if 'graph_hashes' in manifest:
        for name, expected in manifest['graph_hashes'].items():
            if not re.fullmatch(r'graphs-fold-[0-4]\.json', name):
                raise ValueError('Unexpected graph filename')
            require_hash(data / 'fm24-final' / name, expected, name)
        if set(manifest['graph_hashes']) != {f'graphs-fold-{fold}.json' for fold in range(5)}:
            raise ValueError('Incomplete graph fold inventory')
        require_hash(data / 'fm24-final/cases.jsonl', manifest['normalized_cases_sha256'], 'FM24 normalized cohort')


def validate_observations(rows, cases, tool, lineage):
    seen = set()
    for row in rows:
        cid = row.get('case_id')
        if cid not in cases or cid in seen:
            raise ValueError(f'{tool}: duplicate or unknown case {cid}')
        seen.add(cid)
        case = cases[cid]
        for field in ('cohort_status', 'group', 'predicate', 'source_sha256'):
            if row.get(field) != case.get(field):
                raise ValueError(f'{tool}: {field} mismatch for {cid}')
        if row.get('tool') != tool:
            raise ValueError(f'{tool}: result tool mismatch')
        fold = row.get('fold')
        if tool.startswith(('live-', 'fm24-')):
            expected = lineage[case['model_id']]
            expected = expected['fold'] if isinstance(expected, dict) else expected
            if fold is not None:
                if type(fold) is not int or fold != expected:
                    raise ValueError(f'{tool}: fold mismatch for {cid}')
            elif row.get('supported') is True and row.get('status') not in {'timeout', 'worker_error', 'deadline_exceeded'}:
                raise ValueError(f'{tool}: supported observation lacks fold for {cid}')
        for field in ('matrix_replay_verified', 'ast_replay_verified', 'native_repair_available', 'verified_correct'):
            if row.get(field) is not None and type(row[field]) is not bool:
                raise ValueError(f'{tool}: {field} must be boolean or null')
        for field in ('canonical_located_operations', 'aggregate_operation_count'):
            if row.get(field) is not None and (type(row[field]) is not int or row[field] < 0):
                raise ValueError(f'{tool}: invalid {field}')
        if row.get('canonical_located_operations') is not None:
            if row.get('operation_count') is None or row['canonical_located_operations'] > row['operation_count']:
                raise ValueError(f'{tool}: canonical locations exceed operation count')
        if row.get('validation_seconds') is not None:
            value = row['validation_seconds']
            if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
                raise ValueError(f'{tool}: invalid validation time')
        if row.get('verified_correct') is not None and row.get('native_repair_available') is not True:
            raise ValueError(f'{tool}: repair validation lacks native repair')
    return seen


def _quality(rows, tool, incorrect_count, timeout):
    wrong = [row for row in rows if row['cohort_status'] != 'CORRECT']
    result = {
        'incorrect_observed': len(wrong),
        'incorrect_zero_distance': sum(row.get('distance') == 0 for row in wrong),
        'distance_observations': sum(row.get('distance') is not None for row in wrong),
        'aggregate_operation_total': sum(row.get('aggregate_operation_count') or 0 for row in wrong),
    }
    for key in ('matrix_replay_verified', 'ast_replay_verified'):
        measured = [row[key] for row in wrong if row.get(key) is not None]
        result[key] = {'observations': len(measured), 'true': sum(measured),
                       'false': len(measured) - sum(measured), 'fraction': ratio(sum(measured), len(measured))}
    measured = [row for row in wrong if row.get('canonical_located_operations') is not None]
    located = sum(row['canonical_located_operations'] for row in measured)
    operations = sum(row['operation_count'] for row in measured)
    result['canonical_exact_node_localization'] = {'observations': len(measured), 'located': located,
                                                   'operations': operations, 'fraction': ratio(located, operations)}
    if tool.startswith('tar-'):
        native = [row for row in wrong if row.get('native_repair_available') is True]
        timely = [row for row in native if not row['timed_out'] and row['wall_seconds'] <= timeout]
        outcomes = Counter()
        for row in native:
            if row.get('verified_correct') is True:
                outcomes['correct'] += 1
            elif row.get('verified_correct') is False:
                outcomes['false'] += 1
            elif row.get('validation_status') == 'validation_timeout':
                outcomes['timeout'] += 1
            elif row.get('validation_status') is not None:
                outcomes['error'] += 1
            else:
                outcomes['not_validated'] += 1
        result['full_repairs'] = {
            'native_reported': len(native), 'native_within_budget': len(timely),
            'native_within_budget_rate': ratio(len(timely), incorrect_count),
            'native_after_budget': len(native) - len(timely),
            'independent_validation': {key: outcomes[key] for key in ('correct', 'false', 'error', 'timeout', 'not_validated')},
            'verified_correct_within_budget': sum(row.get('verified_correct') is True for row in timely),
            'verified_correct_within_budget_rate': ratio(sum(row.get('verified_correct') is True for row in timely), incorrect_count),
            'validation_wall_seconds': sum(row.get('validation_seconds') or 0 for row in native),
            'interpretation': 'Independent checks are bounded Alloy/SAT4J equivalence checks; native repair availability is distinct from hint availability.',
        }
    return result


def paired_hits(by_tool, cases, timeout):
    """Both production modes versus each other and each SOTA, once per pair."""
    answer, seen_pairs = {}, set()
    wrong_ids = {cid for cid, case in cases.items() if case['cohort_status'] != 'CORRECT'}
    for focal in ('live-canonical', 'live-ast'):
        if focal not in by_tool:
            continue
        for other in by_tool:
            identity = frozenset((focal, other))
            if other == focal or identity in seen_pairs:
                continue
            seen_pairs.add(identity)
            left, right = by_tool[focal], by_tool[other]
            common = wrong_ids & left.keys() & right.keys()
            counts = Counter()
            for cid in common:
                a, b = left[cid], right[cid]
                ah = a['hint_available'] and not a['timed_out'] and a['wall_seconds'] <= timeout
                bh = b['hint_available'] and not b['timed_out'] and b['wall_seconds'] <= timeout
                counts['both' if ah and bh else 'left_only' if ah else 'right_only' if bh else 'neither'] += 1
            answer[focal + '__vs__' + other] = {
                'left_tool': focal, 'right_tool': other,
                'incorrect_cases': len(wrong_ids), 'paired_observed': len(common),
                'unpaired': len(wrong_ids) - len(common),
                **{key: counts[key] for key in ('both', 'left_only', 'right_only', 'neither')},
            }
    return answer


def resource_provenance(root, data, baselines, *, verify_resources=True):
    """Hash declared benchmark resources; never scan deployment/private files."""
    paths = [root / CORRECTION_REGISTRY, root / 'benchmarks/alloy4fun/fm24/sources.json', root / 'build/benchmarks/tar/manifest.json',
             root / 'build/benchmarks/tar/verification-manifest.json',
             data / 'fm24-final/fresh-native-preprocessing.json', data / 'fm24-final/preprocessing.json',
             data / 'lineage.summary.json', data / 'lineage-all.summary.json',
             data / 'environment.json', data / 'resource-profile.json', data / 'tar-validation-audit.json', data / 'evidence-audit.json']
    profile_path = data / 'resource-profile.json'
    if profile_path.is_file():
        recovery = read_json(profile_path).get('recovery', {})
        if recovery:
            source = recovery.get('guard_source')
            evidence = recovery.get('fresh_tar', {}).get('cgroup_evidence_path')
            if source:
                paths.append(_repository_file(root, source))
            if evidence:
                paths.append(_data_file(data, evidence))
    if verify_resources:
        for required in (root / 'benchmarks/alloy4fun/fm24/sources.json', root / 'build/benchmarks/tar/manifest.json', root / 'build/benchmarks/tar/verification-manifest.json'):
            if not required.is_file():
                raise ValueError('Missing required public runtime/build provenance: ' + str(required))
    records = {}
    for path in paths:
        if path.is_file():
            try:
                name = str(path.relative_to(root))
            except ValueError:
                name = 'data/' + str(path.relative_to(data))
            records[name] = {'sha256': sha(path), 'bytes': path.stat().st_size}
    registry = root / 'benchmarks/alloy4fun/fm24/sources.json'
    if verify_resources and registry.is_file():
        document = read_json(registry)
        if not document.get('artifacts') or not document.get('maven_dependencies'):
            raise ValueError('FM24 resource registry cannot omit runtime/dependency pins')
        for item in document.get('artifacts', []) + document.get('maven_dependencies', []):
            relative = Path(item['path'])
            if relative.is_absolute() or '..' in relative.parts:
                raise ValueError('Invalid public baseline resource path')
            path = baselines / relative
            require_hash(path, item['sha256'], 'FM24 resource ' + str(relative))
            records['lp_baselines/' + str(relative)] = {'sha256': item['sha256'], 'bytes': path.stat().st_size}
    if verify_resources:
        def checked(path, expected, name):
            require_hash(path, expected, name)
            records[name] = {'sha256': expected, 'bytes': Path(path).stat().st_size}
        tar_manifest = root / 'build/benchmarks/tar/manifest.json'
        if tar_manifest.is_file():
            tar = read_json(tar_manifest)
            for field in ('class_sha256', 'source_sha256'):
                if not isinstance(tar.get(field), dict) or not tar[field]:
                    raise ValueError('TAR build requires nonempty ' + field)
            runtime = Path(tar['runtime_archive']).resolve()
            if not runtime.is_relative_to(baselines.resolve()):
                raise ValueError('TAR runtime archive is outside registered baseline resources')
            checked(runtime, tar['runtime_archive_sha256'], 'lp_baselines/' + str(runtime.relative_to(baselines.resolve())))
            checked(root / 'vendor/acgn/lib/json-java.jar', tar['json_archive_sha256'], 'vendor/acgn/lib/json-java.jar')
            for relative, expected in tar.get('class_sha256', {}).items():
                if Path(relative).is_absolute() or '..' in Path(relative).parts:
                    raise ValueError('Invalid TAR compiled class path')
                name = 'build/benchmarks/tar/classes/' + relative
                checked(root / name, expected, name)
            for relative, expected in tar.get('source_sha256', {}).items():
                if Path(relative).is_absolute() or '..' in Path(relative).parts:
                    raise ValueError('Invalid TAR upstream source path')
                checked(baselines / 'TAR' / relative, expected, 'lp_baselines/TAR/' + relative)
        verification = root / 'build/benchmarks/tar/verification-manifest.json'
        if verification.is_file():
            pins = read_json(verification)
            if pins.get('no_overflow') is not False:
                raise ValueError('Independent TAR verifier requires explicit no_overflow=false')
            for name, key in [('benchmarks/alloy4fun/tar/VerifyRepair.java', 'source_sha256'),
                              ('vendor/acgn/lib/alloy.jar', 'alloy_runtime_sha256'),
                              ('vendor/acgn/lib/json-java.jar', 'json_runtime_sha256'),
                              ('build/benchmarks/tar/verification-classes/VerifyRepair.class', 'class_sha256')]:
                checked(root / name, pins[key], name)
    return records



def validate_evidence_audit(data, root, cases, tools, incorrect_count, *, required):
    path = data / 'evidence-audit.json'
    if not path.is_file():
        if required:
            raise ValueError('Missing full raw-response evidence audit; run audit_results.py first')
        return {'status': 'not_yet_available'}
    audit = read_json(path)
    if audit.get('status') != 'PASS' or audit.get('scope') != 'raw-response-correspondence':
        raise ValueError('Evidence audit has not passed raw-response correspondence')
    require_hash(data / 'cases.jsonl', audit.get('cases_sha256'), 'evidence audit cohort')
    require_hash(root / 'benchmarks/alloy4fun/audit_results.py', audit.get('audit_source_sha256'), 'evidence audit source')
    items = audit.get('tools')
    if not isinstance(items, list) or {item.get('tool') for item in items} != set(tools) or len(items) != len(tools):
        raise ValueError('Evidence audit must cover all registered tools exactly once')
    case_set_hash = digest(json.dumps(sorted(cases), separators=(',', ':')).encode())
    for item in items:
        tool = item['tool']
        if item.get('status') != 'PASS' or item.get('case_id_set_sha256') != case_set_hash:
            raise ValueError(f'{tool}: evidence audit status/case set mismatch')
        counts = item.get('counts', {})
        for field, expected in [('cases', len(cases)), ('raw_responses', len(cases)),
                                ('incorrect_cases', incorrect_count), ('correct_controls', len(cases) - incorrect_count)]:
            if counts.get(field) != expected:
                raise ValueError(f'{tool}: evidence audit count mismatch: {field}')
        pins = item.get('sha256', {})
        required_files = {'run.json', 'manifest.json', 'results.jsonl', 'responses.jsonl.gz'}
        if set(pins) != required_files:
            raise ValueError(f'{tool}: evidence audit artifact pins missing')
        for filename, expected in pins.items():
            require_hash(data / tools[tool] / filename, expected, f'{tool}/audited {filename}')
    return {'status': 'PASS', 'scope': audit['scope'], 'sha256': sha(path), 'tools': len(items)}


def audit_sources(cases, cohort):
    started = time.perf_counter()
    source_root = Path(cohort['source_root']).resolve()
    inventory = hashlib.sha256()
    for case in cases:
        path = Path(case['path']).resolve()
        if not path.is_relative_to(source_root) or path.relative_to(source_root).as_posix() != case['case_id']:
            raise ValueError('Case path escapes or differs from its corpus-relative identity')
        require_hash(path, case['source_sha256'], 'corpus source ' + case['case_id'])
        inventory.update(json.dumps([case['case_id'], case['source_sha256']], separators=(',', ':')).encode() + b'\n')
    return {'status': 'MATCHED', 'source_files_checked': len(cases),
            'ordered_retained_inventory_sha256': inventory.hexdigest(),
            'wall_seconds': time.perf_counter() - started}


def summarize(data, *, allow_partial=False, expected_total=EXPECTED_TOTAL, expected_incorrect=EXPECTED_INCORRECT,
              tool_directories=None, repository_root=ROOT, baselines_root=None, verify_runtime=True):
    data, root = Path(data).resolve(), Path(repository_root).resolve()
    tools = TOOL_DIRECTORIES if tool_directories is None else tool_directories
    case_rows, case_snapshot = read_rows(data / 'cases.jsonl')
    cases = {case['case_id']: case for case in case_rows}
    if len(cases) != len(case_rows) or len(cases) != expected_total:
        raise ValueError('Expected-case cohort size/uniqueness mismatch')
    incorrect = sum(case['cohort_status'] != 'CORRECT' for case in case_rows)
    if incorrect != expected_incorrect:
        raise ValueError('Expected incorrect-case denominator mismatch')
    for case in case_rows:
        if not SHA256.fullmatch(case.get('source_sha256', '')):
            raise ValueError('Case manifest lacks content SHA-256')
    cohort = read_json(data / 'cohort.json')
    if cohort.get('eligible') != expected_total:
        raise ValueError('Cohort audit does not bind expected case count')
    require_hash(data / 'cases.jsonl', cohort['cases.jsonl_sha256'], 'cohort case manifest')
    for name in ('payloads.jsonl', 'inventory.jsonl'):
        if name + '_sha256' in cohort:
            require_hash(data / name, cohort[name + '_sha256'], 'cohort ' + name)
    labels = validate_label_corrections(cohort, cases, root, required=verify_runtime)
    lineage = read_json(data / 'lineage.json')
    if any(case['model_id'] not in lineage for case in case_rows):
        raise ValueError('Evaluation lineage is incomplete')
    timeout = 60.0
    execution_profile, expected_workers = load_execution_profile(data) if verify_runtime else (None, {})
    all_rows, by_tool, runs, snapshots, incomplete = [], {}, {}, {}, []
    for tool, directory in tools.items():
        folder = data / directory
        manifest_path, results_path, report_path = folder / 'manifest.json', folder / 'results.jsonl', folder / 'run.json'
        if not manifest_path.exists() or not results_path.exists():
            if not allow_partial:
                raise ValueError(f'{tool}: missing run inputs')
            incomplete.append(tool)
            runs[tool] = {'state': 'NOT_STARTED', 'observed': 0, 'expected': expected_total}
            by_tool[tool] = {}
            continue
        manifest = read_json(manifest_path)
        _validate_manifest(manifest, tool, data, root, expected_total, timeout, verify_runtime,
                           expected_workers=expected_workers.get(tool, 16))
        rows, snapshot = read_rows(results_path, allow_partial=allow_partial)
        seen = validate_observations(rows, cases, tool, lineage)
        complete = len(seen) == expected_total and report_path.exists() and not snapshot['incomplete_tail_ignored']
        run_report = read_json(report_path) if report_path.exists() else None
        if run_report is not None and (run_report.get('completed') != len(seen) or run_report.get('cases') != expected_total):
            raise ValueError(f'{tool}: completion record disagrees with results')
        if run_report is not None:
            if verify_runtime and not {'tool', 'cases', 'completed', 'workers', 'timeout_seconds', 'run_wall_seconds'}.issubset(run_report):
                raise ValueError(f'{tool}: incomplete completion metadata')
            if verify_runtime and run_report.get('tool') != tool:
                raise ValueError(f'{tool}: completion record tool mismatch')
            for field in set(manifest) & set(run_report):
                if run_report[field] != manifest[field]:
                    raise ValueError(f'{tool}: completion record configuration differs: {field}')
            if 'statuses' in run_report and run_report['statuses'] != dict(Counter(row['status'] for row in rows)):
                raise ValueError(f'{tool}: completion record status counts disagree')
        if not complete:
            incomplete.append(tool)
            if not allow_partial:
                raise ValueError(f'{tool}: incomplete run ({len(seen)}/{expected_total} cases or missing completion record)')
        all_rows.extend(rows)
        by_tool[tool] = {row['case_id']: row for row in rows}
        snapshots[tool] = {**snapshot, 'path': str(results_path.relative_to(data)),
                           'manifest_sha256': sha(manifest_path),
                           'completion_sha256': sha(report_path) if report_path.exists() else None}
        runs[tool] = {'state': 'COMPLETE' if complete else 'RUNNING', 'observed': len(seen), 'expected': expected_total,
                      'workers': manifest.get('workers'), 'timeout_seconds': timeout,
                      'run_wall_seconds': (run_report or {}).get('run_wall_seconds'),
                      'preparation_seconds': (run_report or {}).get('preparation_seconds'),
                      'setup_worker_seconds': (run_report or {}).get('setup_worker_seconds'),
                      'resumed_rows': (run_report or {}).get('resumed_rows', 0),
                      'profile': manifest.get('profile', manifest.get('timing')),
                      'manifest': str(manifest_path.relative_to(data))}
    measured = aggregate(all_rows, expected_cases=case_rows, timeout_seconds=timeout)
    for tool, observations in by_tool.items():
        if tool not in measured['tools']:
            # No observed row does not mean a zero-time tool or an empty cohort.
            continue
        rows = list(observations.values())
        wrong = [row for row in rows if row['cohort_status'] != 'CORRECT']
        measured['tools'][tool]['incorrect']['errors'] = sum(not row['timed_out'] and row['status'] not in NON_ERROR_STATUSES for row in wrong)
        measured['tools'][tool]['quality'] = _quality(rows, tool, incorrect, timeout)
    execution = validate_execution_profile(execution_profile, data, root, runs, snapshots, allow_partial=allow_partial) if verify_runtime else {'status': 'not_required_for_unverified_fixture'}
    return {
        'schema_version': SCHEMA_VERSION,
        'method_version': METHOD_VERSION if labels['status'] == 'MATCHED' else METHOD_VERSION + '-unverified-fixture',
        'status': 'RUNNING' if incomplete or allow_partial else 'COMPLETE',
        'generated_at': datetime.now(timezone.utc).isoformat(),
        'incomplete_tools': incomplete, 'runs': runs, 'metrics': measured,
        'paired_hint_coverage': paired_hits(by_tool, cases, timeout),
        'provenance': {'case_manifest': case_snapshot, 'cohort_sha256': sha(data / 'cohort.json'),
                       'label_corrections': labels,
                       'execution_profile': execution,
                       'lineage_sha256': sha(data / 'lineage.json'), 'result_snapshots': snapshots,
                       'actual_source_audit': audit_sources(case_rows, cohort) if not allow_partial else {'status': 'deferred_until_final'},
                       'raw_response_audit': validate_evidence_audit(data, root, cases, tools, incorrect, required=verify_runtime and not allow_partial),
                       'resource_files': resource_provenance(root, data, Path(baselines_root) if baselines_root else root.parent / 'lp_baselines',
                                                            verify_resources=verify_runtime),
                       'summarizer_sha256': sha(Path(__file__)),
                       'metrics_sha256': sha(ROOT / 'benchmarks/alloy4fun/protocol/metrics.py')},
        'scope': {'hint_success_is_not_repair_success': True, 'luna_included': False,
                  'human_learning_outcomes_measured': False,
                  'correct_control_policy': CORRECT_CONTROL_POLICY,
                  'timing': RECOVERY_TIMING if execution.get('kind') == 'oom_recovery' else UNIFORM_TIMING,
                  'independent_validation': 'Bounded Alloy/SAT4J checking, not universal logical equivalence.',
                  'localization': 'Exact-node locator metadata counts; no independent ground-truth localization adjudication.',
                  'partial_run': 'Missing cases remain in coverage denominators; missing latencies are not imputed.'},
    }


def _percent(value, digits=2):
    return '—' if value is None else f'{100 * value:.{digits}f}%'


def _seconds(value):
    return '—' if value is None else f'{value:.4f}'


def render_markdown(summary):
    if summary['status'] != 'COMPLETE':
        raise ValueError('Partial observations cannot render final benchmark tables')
    metrics = summary['metrics']
    cohort = metrics['cohort']
    tools = metrics['tools']
    order = [tool for tool in TOOL_DIRECTORIES if tool in tools]
    order.extend(tool for tool in tools if tool not in order)
    lines = [
        f"Measured cohort: **{cohort['cases']:,} models**, including **{cohort['incorrect']:,} incorrect inputs** "
        f"and **{cohort['correct']:,} held-out CORRECT submissions**. Each tool completed the same cohort with a "
        f"{metrics['timeout_seconds']:g}-second hint-generation budget. Luna is excluded.",
        '',
        'Native hint availability is distinct from a complete repair, semantic improvement, or educational usefulness. '
        'All incorrect cases remain in the Hit Rate denominator, including unsupported requests and timeouts.',
        '',
        '| Tool | Incorrect inputs with hints | Micro Hit Rate | Macro Hit Rate | Explicit unsupported | Timeouts | Errors |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: |',
    ]
    for tool in order:
        value = tools[tool]['incorrect']
        lines.append(f"| {LABELS.get(tool, tool)} | {value['hints']:,} | {_percent(value['hit_rate_micro'], digits=4)} | "
                     f"{_percent(value['hit_rate_macro_exercise'], digits=4)} | {value['unsupported']:,} | "
                     f"{value['timeouts']:,} | {value['errors']:,} |")
    lines.extend([
        '',
        'Macro Hit Rate gives equal weight to each invariant exercise. Errors include explicit unsupported/error '
        'statuses and exclude timeouts; unsupported and error columns are not disjoint categories. Explicit unsupported counts reflect adapter status flags, not a complete classification of compilability (for example, TAR engine errors can include native parsing failures).',
        '',
        'Latency is measured wall time in seconds. “All” includes every correct and incorrect request, failures '
        'and timeouts; “Hints” includes successful incorrect-input hints only. Offline graph construction and '
        'independent repair validation are separate from request latency.',
        '',
        summary.get('scope', {}).get('timing', UNIFORM_TIMING),
        '',
        '| Tool | Workers | All mean | All median | All p95 | Hints mean | Hints median | Hints p95 |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |',
    ])
    for tool in order:
        all_times = tools[tool]['wall_all_observed']
        hints = tools[tool]['incorrect']['wall_hints_only']
        values = [all_times['mean_seconds'], all_times['p50_seconds'], all_times['p95_seconds'],
                  hints['mean_seconds'], hints['p50_seconds'], hints['p95_seconds']]
        workers = summary['runs'][tool].get('workers')
        lines.append('| ' + LABELS.get(tool, tool) + ' | ' + (str(workers) if workers is not None else '—') + ' | ' + ' | '.join(_seconds(v) for v in values) + ' |')
    lines.extend([
        '',
        '| Tool | Nonempty hints on held-out CORRECT submissions | Rate over held-out CORRECT submissions |',
        '| --- | ---: | ---: |',
    ])
    for tool in order:
        controls = tools[tool]['correct_controls']
        lines.append(f"| {LABELS.get(tool, tool)} | {controls['nonempty_hint_count']:,} | {_percent(controls['nonempty_hint_rate'])} |")
    lines.extend([
        '',
        CORRECT_CONTROL_POLICY,
        '',
        '| Alloy Studio mode | Checked trace replay | Exact raw-node locations | Exact canonical-node locations | Incorrect distance-zero cases |',
        '| --- | ---: | ---: | ---: | ---: |',
    ])
    for tool in ('live-canonical', 'live-ast'):
        if tool not in tools:
            continue
        quality = tools[tool]['quality']
        key = 'matrix_replay_verified' if tool == 'live-canonical' else 'ast_replay_verified'
        replay = quality[key]
        replay_text = f"{replay['true']:,}/{replay['observations']:,} ({_percent(replay['fraction'])})"
        canonical_locations = _percent(quality['canonical_exact_node_localization']['fraction']) if tool == 'live-canonical' else 'not applicable'
        lines.append(f"| {LABELS[tool]} | {replay_text} | {_percent(tools[tool]['incorrect']['located_operation_fraction'])} | "
                     f"{canonical_locations} | {quality['incorrect_zero_distance']:,} |")
    lines.extend([
        '',
        'Trace replay uses the implementation’s recorded checks: matrix replay for canonical mode and AST replay '
        'for raw mode. It does not certify that redacted hints form executable learner edits. Localization percentages '
        'count exact-node metadata over measured operations on incorrect inputs, without independent ground-truth adjudication.',
        '',
        '| Left method | Right method | Both return hints | Left only | Right only | Neither |',
        '| --- | --- | ---: | ---: | ---: | ---: |',
    ])
    for counts in summary['paired_hint_coverage'].values():
        left, right = counts['left_tool'], counts['right_tool']
        lines.append(f"| {LABELS.get(left, left)} | {LABELS.get(right, right)} | {counts['both']:,} | "
                     f"{counts['left_only']:,} | {counts['right_only']:,} | {counts['neither']:,} |")
    lines.extend(['', 'These are descriptive paired counts on the shared incorrect-input cohort; no independence assumptions or significance tests are imposed.'])
    for tool in order:
        repairs = tools[tool]['quality'].get('full_repairs')
        if repairs is None:
            continue
        validation = repairs['independent_validation']
        lines.extend([
            '',
            f"{LABELS.get(tool, tool)} reported **{repairs['native_within_budget']:,} complete repairs within the hint budget** "
            f"({_percent(repairs['native_within_budget_rate'])} of incorrect cases). Independent validation outcomes "
            'are reported separately from emitted hints:',
            '',
            '| Bounded check passed | Counterexample / false repair | Validation error | Validation timeout | Not validated |',
            '| ---: | ---: | ---: | ---: | ---: |',
            '| ' + ' | '.join(f"{validation[k]:,}" for k in ('correct', 'false', 'error', 'timeout', 'not_validated')) + ' |',
            '',
            f"The rate of timely repairs that also passed independent bounded checking is "
            f"**{_percent(repairs['verified_correct_within_budget_rate'])}**. "
            'These checks preserve the model environment and facts and use the recorded Alloy scope; they do not prove unbounded equivalence.',
        ])
    lines.extend(['', f"Generated by `{METHOD_VERSION}`. Numeric results, per-exercise counts, status breakdowns, latency curves "
                  'and content hashes are in [the versioned results JSON](benchmarks/alloy4fun-results.json).'])
    return '\n'.join(lines) + '\n'


def update_markers(text, generated):
    if text.count(BEGIN) != 1 or text.count(END) != 1:
        raise ValueError('Comparison Markdown must contain exactly one generated-results marker pair')
    first, last = text.index(BEGIN), text.index(END)
    if first >= last:
        raise ValueError('Generated-results markers are reversed')
    return text[:first + len(BEGIN)] + '\n\n' + generated.rstrip() + '\n\n' + text[last:]


def atomic_write(path, text):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix='.' + path.name + '.', dir=path.parent)
    try:
        with os.fdopen(descriptor, 'w', encoding='utf-8') as handle:
            handle.write(text)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def publish(summary, *, final_json, markdown, progress_json):
    encoded = json.dumps(summary, indent=2, sort_keys=True, allow_nan=False) + '\n'
    if summary['status'] != 'COMPLETE':
        atomic_write(progress_json, encoded)
        return [Path(progress_json)]
    path = Path(markdown)
    replacement = update_markers(path.read_text(encoding='utf-8'), render_markdown(summary))
    # Validate both outputs before either final artifact is changed.
    atomic_write(final_json, encoded)
    atomic_write(path, replacement)
    return [Path(final_json), path]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'build/benchmarks/alloy4fun-v2')
    parser.add_argument('--baselines', type=Path, default=ROOT.parent / 'lp_baselines')
    parser.add_argument('--allow-partial', action='store_true')
    parser.add_argument('--json-output', type=Path, default=ROOT / 'docs/benchmarks/alloy4fun-results.json')
    parser.add_argument('--markdown', type=Path, default=ROOT / 'docs/alloy4fun-comparison.md')
    parser.add_argument('--progress-output', type=Path)
    args = parser.parse_args()
    try:
        result = summarize(args.data, allow_partial=args.allow_partial, baselines_root=args.baselines)
        progress = args.progress_output or args.data / 'progress-summary.json'
        if result['status'] != 'COMPLETE' and not progress.resolve().is_relative_to((ROOT / 'build').resolve()):
            raise ValueError('Partial results must stay under build/')
        written = publish(result, final_json=args.json_output, markdown=args.markdown, progress_json=progress)
    except (ValueError, OSError, KeyError, TypeError) as error:
        print('Benchmark summary refused: ' + str(error), file=sys.stderr)
        return 1
    print(json.dumps({'status': result['status'], 'written': [str(path) for path in written],
                      'completed': {tool: run['observed'] for tool, run in result['runs'].items()}}, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
