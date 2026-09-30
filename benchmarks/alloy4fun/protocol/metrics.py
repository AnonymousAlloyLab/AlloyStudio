"""Aggregate finite Alloy4Fun benchmark observations without treating a hint as repair.

Run: python3 metrics.py ROWS.jsonl [--cases CASES.jsonl] [--timeout 60]
Only case metadata and numeric observations belong in these inputs. Do not include
student/oracle bodies or credentials in a public result file.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
from statistics import fmean
from typing import Iterable, Mapping

COHORT_STATUSES = frozenset({'CORRECT', 'BOTH', 'OVERCONSTRAINED', 'UNDERCONSTRAINED'})
CASE_FIELDS = ('case_id', 'cohort_status', 'group', 'predicate')
LATENCY_THRESHOLDS = (0.1, 0.25, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0)


def _number(value, name):
    if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
        raise ValueError(f'{name} must be a finite nonnegative number')
    return float(value)


def _case(record):
    values = {}
    for name in CASE_FIELDS:
        value = record.get(name)
        if not isinstance(value, str) or not value:
            raise ValueError(f'{name} must be a nonempty string')
        values[name] = value
    if values['cohort_status'] not in COHORT_STATUSES:
        raise ValueError('unrecognized cohort_status')
    return values


def _validate_row(record):
    _case(record)
    for name in ('tool', 'status'):
        if not isinstance(record.get(name), str) or not record[name]:
            raise ValueError(f'{name} must be a nonempty string')
    for name in ('hint_available', 'timed_out', 'supported'):
        if type(record.get(name)) is not bool:
            raise ValueError(f'{name} must be bool')
    _number(record.get('wall_seconds'), 'wall_seconds')
    for name in ('engine_seconds', 'distance'):
        if record.get(name) is not None:
            _number(record[name], name)
    for name in ('operation_count', 'located_operations'):
        if record.get(name) is not None and (type(record[name]) is not int or record[name] < 0):
            raise ValueError(f'{name} must be a nonnegative integer')
    if record.get('located_operations') is not None:
        if record.get('operation_count') is None or record['located_operations'] > record['operation_count']:
            raise ValueError('located_operations requires an operation_count at least as large')
    if record['hint_available'] and (record['timed_out'] or not record['supported']):
        raise ValueError('timeout/unsupported observations cannot have an available hint')


def _ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def _quantile(values, q):
    """Linear interpolation of sorted order statistics (same rule for all tools)."""
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def _latencies(rows, field='wall_seconds'):
    values = [float(row[field]) for row in rows if row.get(field) is not None]
    return {
        'observations': len(values),
        'sum_seconds': sum(values),
        'mean_seconds': fmean(values) if values else None,
        'p50_seconds': _quantile(values, 0.50),
        'p95_seconds': _quantile(values, 0.95),
        'max_seconds': max(values) if values else None,
    }


def _population(rows, expected, timeout_seconds):
    denominator = len(expected)
    hints = [row for row in rows if row['hint_available'] and row['wall_seconds'] <= timeout_seconds]
    supported = [row for row in rows if row['supported']]
    grouped = defaultdict(list)
    expected_grouped = defaultdict(list)
    for case in expected:
        expected_grouped[(case['group'], case['predicate'])].append(case)
    for row in rows:
        grouped[(row['group'], row['predicate'])].append(row)
    group_rates = []
    by_exercise = []
    for key, group_cases in sorted(expected_grouped.items()):
        observed = grouped[key]
        hits = sum(row['hint_available'] and row['wall_seconds'] <= timeout_seconds for row in observed)
        rate = hits / len(group_cases)
        group_rates.append(rate)
        by_exercise.append({'group': key[0], 'predicate': key[1], 'cases': len(group_cases),
                            'observed': len(observed), 'hints': hits, 'hit_rate': rate})
    timeout_count = sum(row['timed_out'] for row in rows)
    operation_rows = [row for row in rows if row.get('operation_count') is not None]
    located_rows = [row for row in rows if row.get('located_operations') is not None]
    return {
        'cases': denominator,
        'observed': len(rows),
        'missing': denominator - len(rows),
        'hints': len(hints),
        'hints_after_budget': sum(row['hint_available'] and row['wall_seconds'] > timeout_seconds for row in rows),
        'hit_rate_micro': _ratio(len(hints), denominator),
        'hit_rate_macro_exercise': fmean(group_rates) if group_rates else None,
        'supported': len(supported),
        'supported_fraction': _ratio(len(supported), denominator),
        'hit_rate_given_supported': _ratio(len(hints), len(supported)),
        'unsupported': sum(not row['supported'] for row in rows),
        'timeouts': timeout_count,
        'timeout_rate': _ratio(timeout_count, denominator),
        'status_counts': dict(sorted(Counter(row['status'] for row in rows).items())),
        'wall_all_observed': _latencies(rows),
        'wall_hints_only': _latencies(hints),
        'engine_observed': _latencies(rows, 'engine_seconds'),
        'hints_within_seconds': {
            str(t): {'hints': sum(row['wall_seconds'] <= t for row in hints),
                     'rate': _ratio(sum(row['wall_seconds'] <= t for row in hints), denominator)}
            for t in LATENCY_THRESHOLDS if t <= timeout_seconds
        },
        'operation_count_observations': len(operation_rows),
        'operation_count_mean': fmean(row['operation_count'] for row in operation_rows) if operation_rows else None,
        'localization_observations': len(located_rows),
        'located_operation_fraction': _ratio(sum(row['located_operations'] for row in located_rows),
                                             sum(row['operation_count'] for row in located_rows)),
        'by_exercise': by_exercise,
    }


def aggregate(rows: Iterable[Mapping], *, expected_cases=None, timeout_seconds=60.0):
    """Missing expected cases stay in denominators; latency is never imputed.

    `hint_available` means a native next-step hint, excluding aggregate-only
    distance notices. This metric does not assert that an edit is executable,
    correct, educationally useful, or an improvement. If expected_cases is absent,
    the union of observed case IDs defines the cohort and completeness is unknown.
    """
    timeout_seconds = _number(timeout_seconds, 'timeout_seconds')
    if timeout_seconds == 0:
        raise ValueError('timeout_seconds must be positive')
    rows = [dict(row) for row in rows]
    cases = {}
    if expected_cases is not None:
        for record in expected_cases:
            case = _case(record)
            if case['case_id'] in cases:
                raise ValueError('duplicate expected case_id')
            cases[case['case_id']] = case
    seen = set()
    by_tool = defaultdict(list)
    for row in rows:
        _validate_row(row)
        identity = (row['tool'], row['case_id'])
        if identity in seen:
            raise ValueError('duplicate (tool, case_id); aggregate each repeat separately')
        seen.add(identity)
        case = _case(row)
        prior = cases.get(row['case_id'])
        if prior is None:
            if expected_cases is not None:
                raise ValueError('observed case missing from expected cohort')
            cases[row['case_id']] = case
        elif prior != case:
            raise ValueError('case metadata differs across observations/cohort')
        by_tool[row['tool']].append(row)
    correct = [case for case in cases.values() if case['cohort_status'] == 'CORRECT']
    incorrect = [case for case in cases.values() if case['cohort_status'] != 'CORRECT']
    tools = {}
    for tool, observations in sorted(by_tool.items()):
        wrong = [row for row in observations if row['cohort_status'] != 'CORRECT']
        controls = [row for row in observations if row['cohort_status'] == 'CORRECT']
        nonempty = sum(row['hint_available'] for row in controls)
        tools[tool] = {
            'complete': len(observations) == len(cases),
            'incorrect': _population(wrong, incorrect, timeout_seconds),
            'correct_controls': {
                'cases': len(correct), 'observed': len(controls), 'missing': len(correct) - len(controls),
                'nonempty_hint_count': nonempty,
                'nonempty_hint_rate': _ratio(nonempty, len(correct)),
                'interpretation': 'A nonempty hint on a corpus-CORRECT input; not a proof that the hint is incorrect.',
                'wall_all_observed': _latencies(controls),
            },
            'wall_all_observed': _latencies(observations),
        }
    return {
        'schema_version': 1,
        'cohort': {'cases': len(cases), 'incorrect': len(incorrect), 'correct': len(correct),
                   'source': 'expected_manifest' if expected_cases is not None else 'observed_union',
                   'counts_by_status': dict(sorted(Counter(case['cohort_status'] for case in cases.values()).items()))},
        'timeout_seconds': timeout_seconds,
        'timing_policy': 'Observed wall latency includes failures and timeouts; missing runs are not imputed. '
                         'Percentiles use linear interpolation; tools with missing rows have incomplete timings.',
        'tools': tools,
    }


def _jsonl(path):
    with Path(path).open(encoding='utf-8') as handle:
        return [json.loads(line) for line in handle if line.strip()]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('rows', type=Path)
    parser.add_argument('--cases', type=Path)
    parser.add_argument('--timeout', type=float, default=60.0)
    args = parser.parse_args()
    result = aggregate(_jsonl(args.rows), expected_cases=_jsonl(args.cases) if args.cases else None,
                       timeout_seconds=args.timeout)
    print(json.dumps(result, indent=2, sort_keys=True, allow_nan=False))


if __name__ == '__main__':
    main()
