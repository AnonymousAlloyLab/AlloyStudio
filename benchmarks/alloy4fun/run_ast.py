#!/usr/bin/env python3
"""Run real raw AST Zhang–Shasha hint generation on every manifest case, with path crossfit."""
from __future__ import annotations
import argparse
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor, as_completed
import gzip
import hashlib
import json
from pathlib import Path
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.live.adapter import Worker, metrics


def read_jsonl(path):
    with Path(path).open() as stream:
        return [json.loads(line) for line in stream if line.strip()]


def pool_key(case):
    return (case['group'], case['predicate'], case['environment_sha256'], case['oracle_token_sha256'])


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def validate_inputs(data):
    manifest = json.loads((data / 'cohort.json').read_text())
    for name in ('cases.jsonl', 'payloads.jsonl', 'inventory.jsonl'):
        if sha(data / name) != manifest[name + '_sha256']:
            raise ValueError('Cohort input changed: ' + name)
    return manifest


def runtime_hashes():
    paths = [ROOT / 'runtime_dependencies.py', ROOT / 'benchmarks/alloy4fun/run_ast.py', ROOT / 'benchmarks/alloy4fun/live/adapter.py',
             ROOT / 'benchmarks/alloy4fun/live/LiveWorker.java', ROOT / 'benchmarks/alloy4fun/prepare.py']
    for base, pattern in [('build/engine/classes', '*.class'), ('build/benchmarks/alloy4fun/classes', '*.class'),
                          ('engine/src', '*.java'), ('vendor/acgn/src', '*.java'), ('vendor/acgn/lib', '*.jar')]:
        paths.extend(sorted((ROOT / base).rglob(pattern)))
    return {str(path.relative_to(ROOT)): sha(path) for path in paths}


def reference_pools(cases, folds):
    grouped = defaultdict(list)
    for case in cases:
        if case['extraction_status'] == 'ok' and case['cohort_status'] == 'CORRECT':
            grouped[pool_key(case)].append(case)
    result = {}
    for case in cases:
        if case['extraction_status'] != 'ok':
            continue
        fold = folds[case['model_id']]
        key = (*pool_key(case), fold)
        if key not in result:
            deduplicated = {}
            for reference in sorted(grouped[pool_key(case)], key=lambda c: c['case_id']):
                if folds[reference['model_id']] != fold:
                    deduplicated.setdefault(reference['body_token_sha256'], reference['body'])
            # Explicit teacher oracle is always retained, including if a training
            # solution has the same body. Remove that duplicate before appending.
            deduplicated.pop(case['oracle_token_sha256'], None)
            result[key] = [*deduplicated.values(), case['oracle_body']]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, default=ROOT / 'build/benchmarks/alloy4fun')
    parser.add_argument('--folds', type=Path, required=True,
                        help='JSON mapping model_id to integer fold (whole historical paths share a fold)')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/benchmarks/alloy4fun/live-ast')
    parser.add_argument('--workers', type=int, default=16)
    parser.add_argument('--timeout', type=float, default=60)
    parser.add_argument('--limit', type=int, default=0)
    parser.add_argument('--resume', action='store_true')
    args = parser.parse_args()
    started = time.perf_counter()
    validate_inputs(args.data)
    cases = read_jsonl(args.data / 'payloads.jsonl')
    lineage = json.loads(args.folds.read_text())
    folds = {key: value['fold'] if isinstance(value, dict) else value for key, value in lineage.items()}
    if any(case['model_id'] not in folds or type(folds[case['model_id']]) is not int for case in cases):
        raise ValueError('Every case requires an explicit integer fold')
    pools = reference_pools(cases, folds)
    prepared_seconds = time.perf_counter() - started
    if args.limit:
        # Deterministic uniform hash sample, never prefix-only challenge sampling.
        cases = sorted(cases, key=lambda c: hashlib.sha256(c['case_id'].encode()).digest())[:args.limit]
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'tool': 'live-ast', 'cases': len(cases), 'workers': args.workers,
                'timeout_seconds': args.timeout, 'limit': args.limit, 'heap': '512m',
                'folds_sha256': sha(args.folds), 'cohort_sha256': sha(args.data / 'cohort.json'),
                'payloads_sha256': sha(args.data / 'payloads.jsonl'),
                'runtime_hashes': runtime_hashes(),
                'scratch_policy': 'private per-JVM directories under ALLOY_BENCHMARK_TMP_ROOT or build/benchmarks/tmp; parent cleanup after exit/kill',
                'recycle_after_requests': 256,
                'profile': 'five_fold_student_branch_crossfit_warm_jvm_no_result_cache'}
    manifest_path = args.output / 'manifest.json'
    if manifest_path.exists():
        if not args.resume or json.loads(manifest_path.read_text()) != manifest:
            raise ValueError('Run inputs/configuration changed or --resume was not supplied')
    else:
        manifest_path.write_text(json.dumps(manifest, indent=2) + '\n')
    results_path = args.output / 'results.jsonl'
    if results_path.exists() and not args.resume:
        raise ValueError('Existing results require --resume or a new output directory')
    prior = read_jsonl(results_path) if results_path.exists() else []
    finished = {row['case_id'] for row in prior}
    expected = {case['case_id']: case for case in cases}
    if len(finished) != len(prior) or not finished.issubset(expected):
        raise ValueError('Duplicate or unknown checkpoint cases')
    for row in prior:
        case = expected[row['case_id']]
        if row['tool'] != 'live-ast' or row['source_sha256'] != case['source_sha256'] or row.get('fold') != folds[case['model_id']]:
            raise ValueError('Checkpoint row does not match the frozen input')
    pending = [case for case in cases if case['case_id'] not in finished]
    local = threading.local()
    workers = []
    lock = threading.Lock()

    def evaluate(case):
        metadata = {name: case[name] for name in ('case_id', 'group', 'predicate', 'cohort_status', 'source_sha256')}
        if case['extraction_status'] != 'ok':
            return {**metadata, **metrics({'status': 'unsupported'}, 0), 'tool': 'live-ast'}, {}
        if not hasattr(local, 'worker'):
            local.worker = Worker(args.timeout)
            with lock:
                workers.append(local.worker)
        references = pools[(*pool_key(case), folds[case['model_id']])]
        request = {'studentSource': case['prefix'] + case['body'] + case['suffix'],
                   'predicate': case['predicate'], 'referencePrefix': case['prefix'],
                   'referenceSuffix': case['suffix'], 'referenceBodies': references,
                   'metric': 'ast'}
        response, elapsed, cold = local.worker.request(request)
        return {**metadata, **metrics(response, elapsed, cold=cold), 'tool': 'live-ast',
                'ast_replay_verified': response.get('trace', {}).get('astReplayVerified'),
                'reference_count': len(references), 'fold': folds[case['model_id']]}, response

    run_start = time.perf_counter()
    completed = len(finished)
    try:
        with results_path.open('a') as output, \
             gzip.open(args.output / 'responses.jsonl.gz', 'at') as responses, \
             ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {executor.submit(evaluate, case): case['case_id'] for case in pending}
            for future in as_completed(futures):
                row, response = future.result()
                output.write(json.dumps(row, sort_keys=True) + '\n')
                responses.write(json.dumps({'case_id': row['case_id'], 'response': response}) + '\n')
                output.flush()
                completed += 1
                if completed % 250 == 0 or completed == len(cases):
                    print(json.dumps({'tool': 'live-ast', 'completed': completed,
                                      'total': len(cases), 'wall_seconds': time.perf_counter() - run_start}), flush=True)
    finally:
        for worker in workers:
            worker.close()
    report = {'tool': 'live-ast', 'cases': len(cases), 'completed': completed,
              'preparation_seconds': prepared_seconds, 'run_wall_seconds': time.perf_counter() - run_start,
              'workers': args.workers, 'timeout_seconds': args.timeout,
              'transport': 'Sequential JSONL JVM workers invoking unmodified LiveFeedback.evaluate; no result or parsed-reference cache',
              'folds_sha256': hashlib.sha256(args.folds.read_bytes()).hexdigest(),
              'cohort_sha256': hashlib.sha256((args.data / 'cohort.json').read_bytes()).hexdigest()}
    (args.output / 'run.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
