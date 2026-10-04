#!/usr/bin/env python3
"""Bounded fresh/warm behavior qualification over every catalogue starter.

The fresh diagnostic CLI uses the reserved admin process lane, allowing exactly
one warm behavior JVM plus one diagnostic JVM. It does not alter production
behavior scheduling. Stop at the first mismatch and retain its public outputs.
"""
import argparse
from collections import Counter
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
sys.path.insert(0, str(ROOT / 'scripts'))
import check_worker_equivalence as common
import engine_workers
import runtime_dependencies
import server
from exercise_store import load_store


def identity_sources():
    identity = common.source_identity(ROOT)
    identity['sources']['scripts/check_behavior_worker_equivalence.py'] = common.sha(Path(__file__).read_bytes())
    for name in ('Portal._behavior', 'Portal.behavior_payload', 'project_behavior'):
        identity['sources'][name] = common.sha(common.qualified_source(ROOT / 'server.py', name).encode('utf-8'))
    return identity


def summarize(value):
    return {'status': value.get('status'), 'score': value.get('score'), 'scoreStatus': value.get('scoreStatus'),
            'sampling': value.get('sampling'), 'categories': [
                {'id': item['id'], 'status': item['status'], 'instances': len(item['instances']),
                 'enumerationComplete': item['enumerationComplete']}
                for item in value.get('categories', [])]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default='java')
    parser.add_argument('--timeout', type=float, default=30)
    parser.add_argument('--limit', type=int)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/traffic-implementation/behavior-equivalence.json')
    args = parser.parse_args()
    if not 0 < args.timeout <= 60 or (args.limit is not None and args.limit <= 0):
        parser.error('Use a positive timeout at most 60 seconds and a positive fixture limit.')
    snapshot = load_store(ROOT)
    records = list(snapshot.exercises.values())
    if args.limit is not None:
        records = records[:args.limit]
    cases = [(record['id'] + '/starter/behavior', record, record['starter'], 'behavior',
              server.Portal.behavior_payload(None, record, record['starter'])) for record in records]
    before = identity_sources()
    server_file_sha = common.sha((ROOT / 'server.py').read_bytes())
    java = subprocess.run([args.java, '-version'], capture_output=True, text=True, timeout=10,
                          env=runtime_dependencies.clean_java_environment())
    if java.returncode:
        parser.error('Java version probe failed.')
    identity = {'schema': 1, 'sourcesAndBinaries': before, 'timeoutSeconds': args.timeout,
                'python': platform.python_version(), 'platform': platform.platform(),
                'java': java.stderr.strip() or java.stdout.strip(),
                'freshDiagnosticLane': 'admin', 'persistentLane': 'behavior',
                'cases': [{'id': case[0], 'inputSha256': common.sha(common.encoded(case[4]))} for case in cases]}
    directory = ROOT / 'build/traffic-implementation/behavior-equivalence' / common.sha(common.encoded(identity))[:24]
    directory.mkdir(parents=True, exist_ok=True)
    checkpoint = directory / 'checkpoint.jsonl'
    rows = common.load_checkpoint(checkpoint, identity, cases)
    if not checkpoint.exists():
        with checkpoint.open('xb') as stream:
            stream.write(common.encoded({'type': 'manifest', 'identity': identity}) + b'\n')
    print(json.dumps({'status': 'running', 'cases': len(cases), 'resumed': len(rows),
                      'checkpoint': str(checkpoint.relative_to(ROOT))}), flush=True)
    command = [args.java, '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
               '-cp', runtime_dependencies.runtime_classpath(ROOT), 'live.BehaviorFeedback']
    pool = engine_workers.EnginePool(ROOT, args.java, feedback_workers=1)
    def fresh(kind, payload):
        completed = runtime_dependencies.run_engine(command, root=ROOT, lane='admin',
            input=common.encoded(payload).decode('utf-8'), text=True, encoding='utf-8',
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=ROOT, timeout=args.timeout, check=False)
        if completed.returncode or len(completed.stdout.encode('utf-8')) > 4 * 1048576:
            raise OSError('Fresh behavior failed.')
        return json.loads(completed.stdout)
    def warm(kind, payload):
        try:
            return pool.evaluate(kind, payload, args.timeout)
        except engine_workers.EngineTimeout:
            raise subprocess.TimeoutExpired('behavior', args.timeout) from None
        except engine_workers.EngineUnavailable:
            raise OSError('Warm behavior failed.') from None
    def observe(call, payload):
        started = time.monotonic()
        value = server.Portal._behavior(SimpleNamespace(_engine=call), payload)
        return value, round(time.monotonic() - started, 6)
    try:
        if not any(row['outcome'] == 'MISMATCH' for row in rows):
            for case_id, record, body, metric, payload in cases[len(rows):]:
                cold, cold_time = observe(fresh, payload)
                hot, hot_time = observe(warm, payload)
                equal = cold == hot
                outcome = ('MISMATCH' if not equal else 'UNRESOLVED' if cold.get('status') in
                           {'error', 'timeout', 'busy'} else 'MATCH')
                row = {'case': case_id, 'exerciseId': record['id'], 'metric': metric,
                       'serverSourceFileSha256': server_file_sha,
                       'inputSha256': common.sha(common.encoded(payload)),
                       'freshSha256': common.sha(common.encoded(cold)), 'warmSha256': common.sha(common.encoded(hot)),
                       'freshStatus': cold.get('status'), 'warmStatus': hot.get('status'),
                       'freshSeconds': cold_time, 'warmSeconds': hot_time, 'outcome': outcome,
                       'differentPaths': common.diff_paths(cold, hot),
                       'freshSummary': summarize(cold), 'warmSummary': summarize(hot),
                       'warmLaunches': pool.stats()['launches']}
                with checkpoint.open('ab') as stream:
                    stream.write(common.encoded(row) + b'\n'); stream.flush(); os.fsync(stream.fileno())
                rows.append(row)
                if len(rows) % 5 == 0 or outcome != 'MATCH':
                    print(json.dumps({'completed': len(rows), 'total': len(cases), 'case': case_id,
                                      'outcomes': dict(Counter(item['outcome'] for item in rows)),
                                      'warmLaunches': pool.stats()['launches']}), flush=True)
                if outcome == 'MISMATCH':
                    (directory / 'first-mismatch-public.json').write_bytes(common.encoded(
                        {'case': case_id, 'fresh': cold, 'warm': hot}) + b'\n')
                    break
    finally:
        final_pool = pool.close()
    stable = identity_sources() == before
    counts = Counter(row['outcome'] for row in rows)
    status = 'PASS' if len(rows) == len(cases) and counts.get('MATCH', 0) == len(cases) and stable else 'FAIL'
    timings = {}
    for mode in ('fresh', 'warm'):
        durations = sorted(row[mode + 'Seconds'] for row in rows)
        timings[mode] = {'sum': round(sum(durations), 6),
                         'median': round(statistics.median(durations), 6) if durations else None,
                         'p95': durations[min(len(durations) - 1, int(len(durations) * .95))] if durations else None}
    report = {'schema': 1, 'status': status, 'scope': 'fresh-vs-warm-public-behavior-starters',
              'identitySha256': common.sha(common.encoded(identity)), 'identity': identity,
              'sourceAndBinaryIdentityStable': stable, 'expectedCases': len(cases), 'completedCases': len(rows),
              'outcomes': dict(counts), 'freshStatuses': dict(Counter(row['freshStatus'] for row in rows)),
              'warmStatuses': dict(Counter(row['warmStatus'] for row in rows)), 'runtimeSeconds': timings,
              'validatedBehaviorCases': sum(row['outcome'] == 'MATCH' and row['freshStatus'] == 'ok' for row in rows),
              'successfulInstanceCases': sum(row['outcome'] == 'MATCH' and any(
                  item['instances'] for item in row['freshSummary']['categories']) for row in rows),
              'categoryInstanceCounts': {name: sum(item['instances'] for row in rows
                  for item in row['freshSummary']['categories'] if item['id'] == name)
                  for name in ('both', 'undercoverage', 'overcoverage', 'neither')},
              'historicalServerSourceFileSha256': sorted({row['serverSourceFileSha256'] for row in rows}),
              'warmWorkerFinal': final_pool, 'failures': [row for row in rows if row['outcome'] != 'MATCH'],
              'limitations': ['Finite starters do not prove arbitrary solver or JVM state independence.',
                             'Matching unsupported/invalid cases do not count as successful instance generation.',
                             'Fresh diagnostic uses the admin lane; production behavior uses its own single lane.',
                             'Exact projected instances, score and sampling are compared; differences are not normalized away.',
                             'A resumed checkpoint is not a continuous warm-worker history.']}
    for target in (directory / 'report.json', args.output):
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + '.new')
        temporary.write_bytes(json.dumps(report, sort_keys=True, indent=2).encode('utf-8') + b'\n')
        temporary.replace(target)
    print(json.dumps({'status': status, 'completed': len(rows), 'total': len(cases),
                      'outcomes': dict(counts), 'report': str(args.output)}), flush=True)
    return 0 if status == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
