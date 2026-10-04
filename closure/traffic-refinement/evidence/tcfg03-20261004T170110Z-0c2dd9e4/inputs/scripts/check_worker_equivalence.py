#!/usr/bin/env python3
"""Compare fresh and sequential warm JVM public feedback on the local catalogue.

All scratch/checkpoints are owned by build/. No provider calls, remote inputs or
source bodies appear in the aggregate evidence. At most one warm and one fresh
JVM exist concurrently; requests are evaluated serially with fixed deadlines.
"""
import argparse
import ast
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import sys
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
import engine_workers
import runtime_dependencies
import server
from exercise_store import load_store


def encoded(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def sha(value):
    return hashlib.sha256(value).hexdigest()


def diff_paths(left, right, prefix='$', limit=24):
    """Report locations, never differing learner/reference values."""
    if type(left) is not type(right):
        return [prefix + ':type']
    if isinstance(left, dict):
        result = [prefix + ':keys'] if set(left) != set(right) else []
        for key in sorted(set(left) & set(right)):
            result.extend(diff_paths(left[key], right[key], prefix + '.' + key, limit))
            if len(result) >= limit:
                break
        return result[:limit]
    if isinstance(left, list):
        result = [prefix + ':length'] if len(left) != len(right) else []
        for index, (a, b) in enumerate(zip(left, right)):
            result.extend(diff_paths(a, b, prefix + '[' + str(index) + ']', limit))
            if len(result) >= limit:
                break
        return result[:limit]
    return [] if left == right else [prefix]


def qualified_source(path, qualname):
    """Resolve declarations by syntax identity, independent of shifted lines."""
    text = Path(path).read_text(encoding='utf-8')
    nodes = ast.parse(text).body
    target = None
    for component in qualname.split('.'):
        matches = [node for node in nodes if isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef))
                   and node.name == component]
        if len(matches) != 1:
            raise ValueError('Source declaration is missing or ambiguous: ' + qualname)
        target = matches[0]
        nodes = target.body
    first = min([target.lineno] + [item.lineno for item in target.decorator_list])
    return ''.join(text.splitlines(keepends=True)[first - 1:target.end_lineno])


def source_identity(root):
    sources = {}
    for name in ('engine_workers.py', 'runtime_dependencies.py', 'traffic_profile.py', 'traffic_limits.py',
                 'scripts/check_worker_equivalence.py'):
        sources[name] = sha((root / name).read_bytes())
    for name in ('Portal._feedback', 'Portal.feedback_payload', 'model', 'compact_canonical_text',
                 'project_canonical_locations', 'project_source_locations'):
        sources[name] = sha(qualified_source(root / 'server.py', name).encode('utf-8'))
    sources['server.METRICS'] = sha(encoded(server.METRICS))
    binaries = {}
    for item in runtime_dependencies.runtime_classpath(root).split(os.pathsep):
        entry = Path(item)
        if entry.is_dir():
            for path in sorted(entry.rglob('*.class')):
                binaries[str(path.relative_to(root))] = sha(path.read_bytes())
        else:
            binaries[str(entry.relative_to(root))] = sha(entry.read_bytes())
    return {'sources': sources, 'binaries': binaries}


def make_cases(snapshot, include_invalid=False, limit=None):
    records = list(snapshot.exercises.values())
    if limit is not None:
        records = records[:limit]
    cases = []
    for record in records:
        variants = [('starter', record['starter']), ('correct', snapshot.correct_pools[record['id']][0])]
        if include_invalid:
            variants.append(('invalid', 'some __ALLOY_STUDIO_UNDECLARED__'))
        for variant, body in variants:
            for metric in ('canonical', 'ast'):
                payload = server.Portal.feedback_payload(None, record, body, metric, snapshot)
                case_id = record['id'] + '/' + variant + '/' + metric
                cases.append((case_id, record, body, metric, payload))
    return cases


def load_checkpoint(path, identity, cases):
    if not path.exists():
        return []
    lines = path.read_bytes().splitlines()
    if not lines:
        raise ValueError('Empty checkpoint; use a new owned run directory.')
    header = json.loads(lines[0])
    if header != {'type': 'manifest', 'identity': identity}:
        raise ValueError('Checkpoint identity changed; refusing to reuse earlier evidence.')
    rows = []
    for index, line in enumerate(lines[1:]):
        row = json.loads(line)
        if (index >= len(cases) or row.get('case') != cases[index][0]
                or row.get('inputSha256') != sha(encoded(cases[index][4]))):
            raise ValueError('Checkpoint order/input mismatch; refusing to reuse earlier evidence.')
        rows.append(row)
    return rows


def observation(call, record, body, metric, payload):
    started = time.monotonic()
    value = server.Portal._feedback(SimpleNamespace(_engine=call), record, body, metric, payload)
    return value, round(time.monotonic() - started, 6)


def aggregate(identity, rows, expected, pool, stable):
    counts = Counter(row['outcome'] for row in rows)
    metrics = {}
    for metric in ('canonical', 'ast'):
        group = [row for row in rows if row['metric'] == metric]
        metrics[metric] = {'cases': len(group), 'outcomes': dict(Counter(row['outcome'] for row in group)),
                          'freshStatuses': dict(Counter(row['freshStatus'] for row in group)),
                          'warmStatuses': dict(Counter(row['warmStatus'] for row in group))}
        for mode in ('fresh', 'warm'):
            durations = sorted(row[mode + 'Seconds'] for row in group)
            metrics[metric][mode + 'RuntimeSeconds'] = ({'sum': round(sum(durations), 6),
                'median': round(statistics.median(durations), 6),
                'p95': durations[min(len(durations) - 1, int(len(durations) * .95))]} if durations else {})
    status = 'PASS' if len(rows) == expected and counts.get('MATCH', 0) == expected and stable else 'FAIL'
    return {'schema': 1, 'status': status, 'scope': 'fresh-vs-warm-public-feedback-fixtures',
            'identitySha256': sha(encoded(identity)), 'identity': identity,
            'sourceAndBinaryIdentityStable': stable, 'expectedCases': expected,
            'completedCases': len(rows), 'outcomes': dict(counts), 'metrics': metrics,
            'warmWorkerFinal': pool,
            'historicalServerSourceFileSha256': sorted({row['serverSourceFileSha256'] for row in rows}),
            'failures': [row for row in rows if row['outcome'] != 'MATCH'],
            'limitations': ['Finite catalogue fixtures do not prove arbitrary JVM state independence.',
                           'No Luna/provider execution or nondeterministic behavior equivalence claim.',
                           'Timing includes per-call process/serialization work; this is a sequential local measurement.',
                           'A resumed checkpoint retains prior valid observations, not a continuous warm-worker history.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=float, default=12)
    parser.add_argument('--include-invalid', action='store_true')
    parser.add_argument('--limit', type=int)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/traffic-implementation/worker-equivalence.json')
    args = parser.parse_args()
    if not 0 < args.timeout <= 60 or (args.limit is not None and args.limit <= 0):
        parser.error('Use a positive timeout at most 60 seconds and a positive fixture limit.')
    snapshot = load_store(ROOT)
    cases = make_cases(snapshot, args.include_invalid, args.limit)
    before = source_identity(ROOT)
    server_file_sha = sha((ROOT / 'server.py').read_bytes())
    java = subprocess.run([args.java, '-version'], capture_output=True, text=True, timeout=10,
                          env=runtime_dependencies.clean_java_environment())
    if java.returncode:
        parser.error('Java version probe failed.')
    identity = {'schema': 1, 'sourcesAndBinaries': before, 'timeoutSeconds': args.timeout,
                'python': platform.python_version(), 'platform': platform.platform(),
                'java': java.stderr.strip() or java.stdout.strip(),
                'cases': [{'id': case[0], 'inputSha256': sha(encoded(case[4]))} for case in cases]}
    directory = ROOT / 'build/traffic-implementation/equivalence' / sha(encoded(identity))[:24]
    directory.mkdir(parents=True, exist_ok=True)
    checkpoint = directory / 'checkpoint.jsonl'
    rows = load_checkpoint(checkpoint, identity, cases)
    if not checkpoint.exists():
        with checkpoint.open('xb') as stream:
            stream.write(encoded({'type': 'manifest', 'identity': identity}) + b'\n')
    print(json.dumps({'status': 'running', 'cases': len(cases), 'resumed': len(rows),
                      'checkpoint': str(checkpoint.relative_to(ROOT))}), flush=True)
    command = [args.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
               '-cp', runtime_dependencies.runtime_classpath(ROOT), 'live.LiveFeedback']
    pool = engine_workers.EnginePool(ROOT, args.java, feedback_workers=1)
    def fresh(kind, payload):
        completed = runtime_dependencies.run_engine(command, root=ROOT, lane='feedback', input=encoded(payload).decode('utf-8'),
            text=True, encoding='utf-8', stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            cwd=ROOT, timeout=args.timeout, check=False)
        if completed.returncode or len(completed.stdout.encode('utf-8')) > 4 * 1048576:
            raise OSError('Fresh feedback failed.')
        return json.loads(completed.stdout)
    def warm(kind, payload):
        try:
            return pool.evaluate(kind, payload, args.timeout)
        except engine_workers.EngineTimeout:
            raise subprocess.TimeoutExpired('feedback', args.timeout) from None
        except engine_workers.EngineUnavailable:
            raise OSError('Warm feedback failed.') from None
    try:
        for case_id, record, body, metric, payload in cases[len(rows):]:
            cold, cold_time = observation(fresh, record, body, metric, payload)
            hot, hot_time = observation(warm, record, body, metric, payload)
            equal = cold == hot
            failure_statuses = {'error', 'timeout', 'busy'}
            outcome = 'MISMATCH' if not equal else ('UNRESOLVED' if cold.get('status') in failure_statuses else 'MATCH')
            row = {'case': case_id, 'exerciseId': record['id'], 'metric': metric,
                   'serverSourceFileSha256': server_file_sha,
                   'inputSha256': sha(encoded(payload)), 'freshSha256': sha(encoded(cold)),
                   'warmSha256': sha(encoded(hot)), 'freshStatus': cold.get('status'),
                   'warmStatus': hot.get('status'), 'freshSeconds': cold_time, 'warmSeconds': hot_time,
                   'freshDistance': cold.get('distance'), 'warmDistance': hot.get('distance'),
                   'outcome': outcome, 'differentPaths': diff_paths(cold, hot),
                   'warmLaunches': pool.stats()['launches']}
            with checkpoint.open('ab') as stream:
                stream.write(encoded(row) + b'\n')
                stream.flush()
                os.fsync(stream.fileno())
            rows.append(row)
            if len(rows) % 20 == 0 or outcome != 'MATCH':
                print(json.dumps({'completed': len(rows), 'total': len(cases), 'case': case_id,
                                  'outcomes': dict(Counter(item['outcome'] for item in rows)),
                                  'warmLaunches': pool.stats()['launches']}), flush=True)
    finally:
        final_pool = pool.close()
    stable = source_identity(ROOT) == before
    report = aggregate(identity, rows, len(cases), final_pool, stable)
    for target in (directory / 'report.json', args.output):
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.new')
        temporary.write_bytes(json.dumps(report, sort_keys=True, indent=2).encode('utf-8') + b'\n')
        temporary.replace(target)
    print(json.dumps({'status': report['status'], 'completed': len(rows), 'total': len(cases),
                      'outcomes': report['outcomes'], 'report': str(args.output)}), flush=True)
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
