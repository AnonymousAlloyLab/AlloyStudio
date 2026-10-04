#!/usr/bin/env python3
"""Measure the author-component FM24 adaptation on the complete shared cohort."""
from __future__ import annotations
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
from pathlib import Path
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'benchmarks/alloy4fun/fm24'))
from worker import Worker


def read_jsonl(path):
    with Path(path).open() as stream:
        return [json.loads(line) for line in stream if line.strip()]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cases', type=Path, default=ROOT / 'build/benchmarks/alloy4fun/cases.jsonl')
    p.add_argument('--data', type=Path, default=ROOT / 'build/benchmarks/alloy4fun/fm24-final')
    p.add_argument('--baselines', type=Path, default=ROOT.parent / 'lp_baselines')
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--workers', type=int, default=4)
    p.add_argument('--timeout', type=int, default=60)
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--mutations', action='store_true')
    p.add_argument('--resume', action='store_true')
    args = p.parse_args()
    name = 'fm24-mutation' if args.mutations else 'fm24-history'
    cases = sorted(read_jsonl(args.cases), key=lambda c: hashlib.sha256(c['case_id'].encode()).digest())
    if args.limit:
        cases = cases[:args.limit]
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'tool': name, 'cases': len(cases), 'case_manifest_sha256': sha(args.cases),
                'workers': args.workers, 'timeout_seconds': args.timeout,
                'mutations': args.mutations, 'limit': args.limit,
                'graph_hashes': {p.name: sha(p) for p in sorted(args.data.glob('graphs-fold-*.json'))},
                'normalized_cases_sha256': sha(args.data / 'cases.jsonl'),
                'sources': {str(p.relative_to(ROOT)): sha(p) for p in
                            [Path(__file__), ROOT / 'runtime_dependencies.py', *sorted((ROOT / 'benchmarks/alloy4fun/fm24').glob('*.py')),
                             *sorted((ROOT / 'benchmarks/alloy4fun/fm24').glob('*.java')),
                             *sorted((ROOT / 'build/benchmarks/fm24-classes').glob('*.class'))]},
                'scratch_policy': 'private per-JVM directories under ALLOY_BENCHMARK_TMP_ROOT or build/benchmarks/tmp; parent cleanup after exit/kill',
                'recycle_after_native_actions': 256,
                'recycling_policy': 'Each completed normalize/hint/mutations action counts separately; cleanup is timed in the completing action and replacement startup in the next action; query cold/recycled flags aggregate all its native actions',
                'profile': 'Native FM24 normalizer/APTED/HiGenA/TAR components; in-memory five-fold historical graph adapter; warm sequential JVM workers'}
    manifest_path = args.output / 'manifest.json'
    if manifest_path.exists():
        if not args.resume or json.loads(manifest_path.read_text()) != manifest:
            raise ValueError('Run inputs/configuration changed or --resume was not supplied')
    else:
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    results_path = args.output / 'results.jsonl'
    prior = read_jsonl(results_path) if results_path.exists() else []
    finished = {row['case_id'] for row in prior}
    expected = {case['case_id']: case for case in cases}
    if len(finished) != len(prior) or not finished.issubset(expected):
        raise ValueError('Duplicate or unknown checkpoint case')
    for row in prior:
        if row['tool'] != name or row['source_sha256'] != expected[row['case_id']]['source_sha256']:
            raise ValueError('Checkpoint input or tool mismatch')
    pending = [case for case in cases if case['case_id'] not in finished]
    local, lock, workers = threading.local(), threading.Lock(), []
    setup = []

    def evaluate(case):
        if not hasattr(local, 'worker'):
            started = time.perf_counter()
            local.worker = Worker(args.data, args.baselines, args.mutations)
            with lock:
                workers.append(local.worker)
                setup.append(time.perf_counter() - started)
        metadata = {k: case[k] for k in ('case_id', 'group', 'predicate', 'cohort_status', 'source_sha256')}
        started = time.perf_counter()
        response = local.worker.evaluate({**case, 'timeout_seconds': args.timeout})
        elapsed = time.perf_counter() - started
        late = elapsed > args.timeout
        status = 'deadline_exceeded' if late else response['status']
        timeout = late or status == 'timeout'
        row = {**metadata, 'tool': name, 'status': status,
               'hint_available': bool(response.get('hint_available')) and not timeout,
               'supported': status not in ('normalization_error', 'upstream_equal_weight_graph', 'native_edge_error', 'error'),
               'timed_out': timeout, 'wall_seconds': elapsed,
               'engine_seconds': response.get('engine_s'),
               'java_engine_seconds': response.get('java_engine_s'),
               'fold': response.get('fold'), 'hint_source': response.get('source'),
               'cold_worker': bool(response.get('cold_worker', False)),
               'worker_recycled': bool(response.get('worker_recycled', False)),
               'native_action_count': response.get('native_action_count', 0),
               'mutation_candidates': response.get('mutation_candidates')}
        return row, response

    started = time.perf_counter()
    statuses = Counter(row['status'] for row in prior)
    completed = len(prior)
    try:
        with results_path.open('a') as output, gzip.open(args.output / 'responses.jsonl.gz', 'at') as raw, \
             ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {executor.submit(evaluate, case): case['case_id'] for case in pending}
            for future in as_completed(futures):
                row, response = future.result()
                output.write(json.dumps(row, sort_keys=True) + '\n')
                output.flush()
                raw.write(json.dumps({'case_id': row['case_id'], 'response': response}) + '\n')
                completed += 1
                statuses[row['status']] += 1
                if completed % 500 == 0 or completed == len(cases):
                    print(json.dumps({'tool': name, 'completed': completed, 'total': len(cases),
                                      'wall_seconds': time.perf_counter() - started,
                                      'statuses': dict(statuses)}), flush=True)
    finally:
        for worker in workers:
            worker.native.close()
    report = {**manifest, 'completed': completed, 'run_wall_seconds': time.perf_counter() - started,
              'setup_worker_seconds': setup, 'statuses': dict(statuses), 'resumed_rows': len(prior)}
    (args.output / 'run.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ('sources', 'graph_hashes')}), flush=True)


if __name__ == '__main__':
    main()
