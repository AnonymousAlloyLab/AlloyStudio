#!/usr/bin/env python3
"""Audit a completed two-arm rebase run and emit public counts, never model text.

Existing TAR/HiGenA and previous ACGN artifacts are read-only. The source and
input snapshot, all prior artifact hashes, case identities and raw-response
correspondence must pass before a new result summary can be written.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
import gzip
import json
import math
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.audit_results import audit_tool, load_cases, sha
from benchmarks.alloy4fun.run_pinned_acgn import check_preserved, check_snapshot


def rows(path):
    with Path(path).open() as stream:
        return [json.loads(line) for line in stream]


def fraction(numerator, denominator):
    return numerator / denominator if denominator else None


def distribution(values):
    """Nearest-rank quantiles over all finite observations, errors included."""
    ordered = sorted(value for value in values
                     if type(value) in (int, float) and math.isfinite(value))
    if not ordered:
        return {'observations': 0}
    return {'observations': len(ordered), 'mean': sum(ordered) / len(ordered),
            'p50': ordered[math.ceil(.50 * len(ordered)) - 1],
            'p95': ordered[math.ceil(.95 * len(ordered)) - 1],
            'p99': ordered[math.ceil(.99 * len(ordered)) - 1], 'max': ordered[-1]}


def subset_summary(selected):
    successes = [row for row in selected if row['status'] == 'ok']
    hinted = [row for row in selected if row['hint_available'] is True
              and row['timed_out'] is False and row['wall_seconds'] <= 60]
    ops = sum(row.get('operation_count', 0) for row in hinted)
    located = sum(row.get('located_operations', 0) for row in hinted)
    return {'cases': len(selected), 'successful_distance_results': len(successes),
            'native_hints_within_60s': len(hinted),
            'native_hint_rate': fraction(len(hinted), len(selected)),
            'zero_distance': sum(row.get('distance') == 0 for row in successes),
            'statuses': dict(sorted(Counter(row['status'] for row in selected).items())),
            'timeouts': sum(row['timed_out'] for row in selected),
            'wall_seconds': distribution(row['wall_seconds'] for row in selected),
            'engine_seconds': distribution(row.get('engine_seconds') for row in selected),
            'distance_successful': distribution(row['distance'] for row in successes),
            'atomic_operations_on_hinted_inputs': ops,
            'source_located_operations_on_hinted_inputs': located,
            'source_locator_rate_per_operation': fraction(located, ops),
            'canonical_located_operations_on_hinted_inputs': sum(
                row.get('canonical_located_operations', 0) for row in hinted),
            'replay_verified_successes': sum(row.get('ast_replay_verified') is True
                                           or row.get('matrix_replay_verified') is True
                                           for row in successes)}


def arm_summary(selected):
    by_invariant = defaultdict(list)
    for row in selected:
        by_invariant[(row['group'], row['predicate'])].append(row)
    return {'all': subset_summary(selected),
            'incorrect': subset_summary([row for row in selected if row['cohort_status'] != 'CORRECT']),
            'correct_controls': subset_summary([row for row in selected if row['cohort_status'] == 'CORRECT']),
            'invariants': [{'model': group, 'predicate': predicate,
                            'incorrect': subset_summary([r for r in group_rows if r['cohort_status'] != 'CORRECT']),
                            'correct_controls': subset_summary([r for r in group_rows if r['cohort_status'] == 'CORRECT'])}
                           for (group, predicate), group_rows in sorted(by_invariant.items())]}


def paired_distances(current, previous):
    old = {row['case_id']: row for row in previous}
    if set(old) != {row['case_id'] for row in current} or len(old) != len(previous):
        raise ValueError('Paired case identities differ')
    counts = Counter()
    for row in current:
        prior = old[row['case_id']]
        if row['source_sha256'] != prior['source_sha256'] or row['cohort_status'] != prior['cohort_status']:
            raise ValueError('Paired source/classification differs')
        if row['status'] == 'ok' and prior['status'] == 'ok':
            delta = row['distance'] - prior['distance']
            counts['paired_successes'] += 1
            counts['distance_lower' if delta < 0 else 'distance_higher' if delta > 0 else 'distance_equal'] += 1
            counts['zero_became_nonzero'] += int(prior['distance'] == 0 and row['distance'] > 0)
            counts['nonzero_became_zero'] += int(prior['distance'] > 0 and row['distance'] == 0)
        else:
            counts['not_both_successful'] += 1
    return dict(sorted(counts.items()))


def diagnostic_counts(directory):
    counts = Counter()
    with gzip.open(directory / 'responses.jsonl.gz', 'rt') as stream:
        for line in stream:
            response = json.loads(line)['response']
            for item in response.get('diagnostics', []):
                code = item.get('code')
                if not isinstance(code, str) or re.fullmatch('[A-Z][A-Z0-9_]{0,63}', code) is None:
                    raise ValueError('Unregistered diagnostic shape')
                counts[code] += 1
    return dict(sorted(counts.items()))


def summarize(experiment):
    experiment = Path(experiment)
    manifest = json.loads((experiment / 'manifest.json').read_text())
    state = json.loads((experiment / 'state.json').read_text())
    guard = json.loads((experiment / 'resource-guard.json').read_text())
    if state['status'] != 'COMPLETED' or guard['status'] != 'COMPLETED':
        raise ValueError('Both arms and the supervisor must complete before publication')
    check_snapshot(experiment, manifest)
    check_preserved(manifest)
    cases = load_cases(experiment / 'inputs/cases.jsonl')
    if len(cases) != 61598:
        raise ValueError('Incomplete cohort')
    report = {'schema': 'alloystudio-acgn-rebase-summary-v1', 'status': 'PASS',
              'acgn_commit': manifest['acgn_commit'], 'ast_view_version': manifest['ast_view_version'],
              'experiment_manifest_sha256': sha(experiment / 'manifest.json'),
              'summary_source_sha256': sha(__file__),
              'cohort_sha256': sha(experiment / 'inputs/cohort.json'),
              'cases_sha256': sha(experiment / 'inputs/cases.jsonl'),
              'folds_sha256': sha(experiment / 'inputs/lineage.json'),
              'policy': manifest['policy'], 'resource_guard': guard,
              'arms': {}, 'unchanged_baselines': {}, 'all_previous_artifacts_unchanged': True,
              'source_snapshot_unchanged': True,
              'timings_independently_remeasured': False, 'ai_calls': 0}
    # Public reports carry no machine-local paths.
    report['resource_guard'] = {k: v for k, v in guard.items() if k not in ('initial_device', 'service_state')}
    data = Path(manifest['data_source'])
    for arm, previous, tool in [('ast', 'ast-full', 'live-ast'), ('canonical', 'live-full', 'live-canonical')]:
        directory = experiment / arm
        audit = audit_tool(directory, tool, cases)
        selected = rows(directory / 'results.jsonl')
        entry = arm_summary(selected)
        entry['audit'] = audit
        entry['diagnostics'] = diagnostic_counts(directory)
        entry['run'] = json.loads((directory / 'run.json').read_text())
        entry['paired_previous_distances'] = paired_distances(selected, rows(data / previous / 'results.jsonl'))
        report['arms'][arm] = entry
    for name, label in [('tar', 'TAR'), ('fm24-history', 'HiGenA-H'), ('fm24-mutation', 'HiGenA-M')]:
        directory = data / name
        selected = rows(directory / 'results.jsonl')
        entry = arm_summary(selected)
        entry['sha256'] = {name: sha(directory / name) for name in
                           ('manifest.json', 'run.json', 'results.jsonl', 'responses.jsonl.gz')}
        entry['measurement'] = 'preserved original measurement; not rerun'
        report['unchanged_baselines'][label] = entry
    check_snapshot(experiment, manifest)
    check_preserved(manifest)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        report = summarize(args.experiment)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
        print(json.dumps({'status': 'PASS', 'arms': ['canonical', 'ast'], 'cases_each': 61598}))
    except (ValueError, OSError, KeyError):
        print(json.dumps({'status': 'FAIL', 'code': 'INCOMPLETE_OR_INCONSISTENT_REBASE_EVIDENCE'}))
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
