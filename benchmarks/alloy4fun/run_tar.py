#!/usr/bin/env python3
"""Checkpoint actual TAR queries; validate returned repairs with a second Alloy API."""
from __future__ import annotations
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import ExitStack
import gzip
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import time
import zlib

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.tar.adapter import prepare_model
from benchmarks.alloy4fun.tar.verify import verify_candidate
from benchmarks.alloy4fun.live.adapter import Worker, scratch_root

RECYCLE_AFTER = 256
CHECKPOINT_FORMAT = 'gzip-member-raw-first-v1'


def read_jsonl(path):
    with Path(path).open() as stream:
        return [json.loads(line) for line in stream if line.strip()]


def checkpoint_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode('utf-8')


def sync_file(stream):
    stream.flush()
    os.fsync(stream.fileno())


def append_checkpoint(raw, out, row, response, manifest_sha256):
    """Commit a closed raw gzip member before making its numeric row durable."""
    encoded = checkpoint_bytes(row)
    record = {'case_id': row['case_id'], 'source_sha256': row['source_sha256'],
              'manifest_sha256': manifest_sha256,
              'result_sha256': hashlib.sha256(encoded).hexdigest(), 'response': response}
    raw.write(gzip.compress(checkpoint_bytes(record) + b'\n', mtime=0))
    sync_file(raw)
    out.write(encoded + b'\n')
    sync_file(out)


def validate_checkpoint(directory, cases, manifest_sha256, tool):
    """Read-only, fail-closed resume validation; never discard an incomplete tail."""
    directory = Path(directory)
    results = directory / 'results.jsonl'
    responses = directory / 'responses.jsonl.gz'
    known = {case['case_id']: case for case in cases}
    if len(known) != len(cases):
        raise ValueError('Duplicate input case IDs')
    if not results.exists() and not responses.exists():
        return []
    try:
        if not results.is_file() or not responses.is_file():
            raise ValueError('Missing checkpoint partner')
        rows = []
        seen = set()
        with results.open('rb') as stream:
            for line in stream:
                if not line.endswith(b'\n'):
                    raise ValueError('Incomplete numeric checkpoint row')
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError('Invalid numeric checkpoint row')
                key = row['case_id']
                if key not in known or key in seen:
                    raise ValueError('Unknown or duplicate checkpoint case')
                for field in ('source_sha256', 'group', 'predicate', 'cohort_status'):
                    if row.get(field) != known[key][field]:
                        raise ValueError('Checkpoint source metadata differs')
                if row.get('tool') != tool:
                    raise ValueError('Checkpoint tool differs')
                checkpoint_bytes(row)  # Reject non-finite numbers as well.
                rows.append(row)
                seen.add(key)
        count = 0
        with gzip.open(responses, 'rb') as stream:
            for line in stream:
                if not line.endswith(b'\n') or count >= len(rows):
                    raise ValueError('Incomplete or unmatched raw checkpoint')
                record = json.loads(line)
                if not isinstance(record, dict):
                    raise ValueError('Invalid raw checkpoint record')
                checkpoint_bytes(record)
                row = rows[count]
                expected = {'case_id': row['case_id'], 'source_sha256': row['source_sha256'],
                            'manifest_sha256': manifest_sha256,
                            'result_sha256': hashlib.sha256(checkpoint_bytes(row)).hexdigest()}
                if any(record.get(key) != value for key, value in expected.items()):
                    raise ValueError('Raw checkpoint provenance or row digest differs')
                if not isinstance(record.get('response'), dict):
                    raise ValueError('Invalid raw checkpoint response')
                count += 1
        if count != len(rows):
            raise ValueError('Numeric/raw checkpoint count differs')
        return rows
    except (OSError, EOFError, ValueError, KeyError, TypeError, zlib.error) as error:
        raise ValueError('Checkpoint refused: incomplete or inconsistent evidence; '
                         'all existing files were preserved. Use a fresh output directory '
                         'or explicitly recover the preserved checkpoint.') from error


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cases', type=Path, default=ROOT / 'build/benchmarks/alloy4fun/cases.jsonl')
    p.add_argument('--output', type=Path, default=ROOT / 'build/benchmarks/alloy4fun/tar')
    p.add_argument('--workers', type=int, default=16)
    p.add_argument('--timeout', type=int, default=60)
    p.add_argument('--depth', type=int, default=2)
    p.add_argument('--limit', type=int, default=0)
    p.add_argument('--resume', action='store_true')
    args = p.parse_args()
    build_path = ROOT / 'build/benchmarks/tar/manifest.json'
    build = json.loads(build_path.read_text())
    cases = read_jsonl(args.cases)
    # Interleave exercises by stable digest; early checkpoints do not consist
    # solely of the alphabetically first challenge.
    cases.sort(key=lambda c: hashlib.sha256(c['case_id'].encode()).digest())
    if args.limit:
        cases = cases[:args.limit]
    if len({case['case_id'] for case in cases}) != len(cases):
        raise ValueError('Duplicate input case IDs')
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {'tool': 'tar-depth-' + str(args.depth), 'cases': len(cases),
                'case_manifest_sha256': hashlib.sha256(args.cases.read_bytes()).hexdigest(),
                'build_manifest_sha256': hashlib.sha256(build_path.read_bytes()).hexdigest(),
                'workers': args.workers, 'timeout_seconds': args.timeout, 'depth': args.depth,
                'selected_case_ids_sha256': hashlib.sha256(checkpoint_bytes(sorted(case['case_id'] for case in cases))).hexdigest(),
                'checkpoint_policy': {
                    'format': CHECKPOINT_FORMAT,
                    'commit': 'One complete gzip member per response; raw flush/fsync precedes numeric row flush/fsync',
                    'provenance': 'Each raw record binds the manifest SHA-256, source SHA-256 and complete numeric row SHA-256',
                    'resume': 'Requires matching manifest and ordered 1:1 complete pairs with known case IDs/source metadata; any incomplete tail is refused without mutation',
                },
                'validation_timeout_seconds': 15, 'jvm_heap': '512m',
                'recycle_after_queries': RECYCLE_AFTER,
                'scratch_policy': {
                    'root': str(scratch_root()),
                    'configuration': 'ALLOY_BENCHMARK_TMP_ROOT; default build/benchmarks/tmp',
                    'jvm': 'Private directory per warm JVM, explicit java.io.tmpdir, parent cleanup after exit or kill',
                    'recycling': f'Shared Worker recycles before the request following {RECYCLE_AFTER} completed requests; cleanup and startup included in that request wall time',
                    'preferences': 'Private directory per benchmark thread, retained across its recycled JVMs; removed after all worker processes exit',
                    'models': 'Private directory per adapted request; removed after response, timeout or error',
                    'verification': 'Independent JVM with private per-validation model/tmp directory, removed after exit, timeout or error',
                },
                'semantic_policy': build['semantic_policy'],
                'timing': 'Sequential warm original TAR API JVM workers. Wall includes model adaptation, transport, native parse, mutation generation, search, hints and output; first/recycled request includes JVM startup. Independent verification is separately timed.',
                'source_hashes': {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                    for path in [Path(__file__), ROOT / 'runtime_dependencies.py', ROOT / 'benchmarks/alloy4fun/live/adapter.py',
                                 *sorted((ROOT / 'benchmarks/alloy4fun/tar').glob('*.py')),
                                 *sorted((ROOT / 'benchmarks/alloy4fun/tar').glob('*.java'))]}}
    manifest_path = args.output / 'manifest.json'
    if manifest_path.exists():
        if not args.resume or json.loads(manifest_path.read_text()) != manifest:
            raise ValueError('Existing run configuration differs or --resume was not supplied')
        if (args.output / 'run.json').exists():
            raise ValueError('Completed run is preserved; use a fresh output directory')
    else:
        if any((args.output / name).exists() for name in ('results.jsonl', 'responses.jsonl.gz', 'run.json')):
            raise ValueError('Checkpoint files exist without their manifest; existing files were preserved')
        with manifest_path.open('x', encoding='utf-8') as stream:
            stream.write(json.dumps(manifest, indent=2) + '\n')
            sync_file(stream)
    manifest_sha256 = hashlib.sha256(manifest_path.read_bytes()).hexdigest()
    results_path = args.output / 'results.jsonl'
    prior = validate_checkpoint(args.output, cases, manifest_sha256, manifest['tool'])
    finished = {row['case_id'] for row in prior}
    pending = [case for case in cases if case['case_id'] not in finished]
    local = threading.local()
    workers = []
    preference_roots = []
    lock = threading.Lock()

    def generate(case):
        started = time.perf_counter()
        if not hasattr(local, 'worker'):
            local.preference_root = tempfile.TemporaryDirectory(prefix='alloy-tar-preferences-', dir=scratch_root())
            command = ['java', '-Djava.util.prefs.userRoot=' + local.preference_root.name, '-Xmx512m', '-XX:ActiveProcessorCount=1', '-XX:+UseSerialGC',
                       '-cp', build['classpath'], 'TarRunner', '--jsonl']
            local.worker = Worker(args.timeout, command=command, recycle_after=RECYCLE_AFTER)
            with lock:
                workers.append(local.worker)
                preference_roots.append(local.preference_root)
        source = Path(case['path']).read_text(encoding='utf-8')
        with tempfile.TemporaryDirectory(prefix='alloy-tar-benchmark-', dir=scratch_root()) as directory:
            path = Path(directory) / 'case.als'
            path.write_text(prepare_model(source, case['predicate'], case['predicate'] + 'c'), encoding='utf-8')
            remaining = args.timeout - (time.perf_counter() - started)
            if remaining <= 0:
                return {'status': 'timeout', 'hint_available': False, 'wall_s': time.perf_counter() - started}
            local.worker.timeout = remaining
            native, elapsed, cold = local.worker.request({'file': str(path), 'depth': args.depth, 'timeout': args.timeout})
        solved = native.get('solved') is True
        depth = native.get('depth', 0)
        hint = solved and depth > 0 and any(isinstance(s.get('hint'), str) and s['hint'].strip()
                                          for s in native.get('native_trace', []))
        status = native.get('status')
        if status not in ('timeout', 'worker_error'):
            status = ('already_correct' if depth == 0 else 'repaired') if solved else (
                'timeout' if native.get('timed_out') else 'engine_error' if native.get('error') else 'no_repair')
        return {'status': status, 'hint_available': hint, 'native_hint_available': hint,
                'repair_available': solved and depth > 0, 'candidate_repair': native.get('solution'),
                'engine_s': native.get('api_seconds'), 'wall_s': time.perf_counter() - started,
                'cold_worker': cold, 'native_result': native}

    def evaluate(case):
        metadata = {k: case[k] for k in ('case_id', 'group', 'predicate', 'cohort_status', 'source_sha256')}
        start = time.perf_counter()
        try:
            response = generate(case)
        except Exception as error:
            response = {'status': 'adapter_error', 'error_class': type(error).__name__,
                        'wall_s': time.perf_counter() - start, 'hint_available': False}
        elapsed = response['wall_s']
        late = elapsed > args.timeout
        timed_out = late or response['status'] in ('timeout', 'outer_timeout')
        hint = response.get('hint_available', False) and not timed_out
        row = {**metadata, 'tool': manifest['tool'], 'status': 'deadline_exceeded' if late else response['status'],
               'hint_available': hint, 'timed_out': timed_out,
               'supported': response['status'] != 'parse_error',
               'wall_seconds': elapsed, 'engine_seconds': response.get('engine_s'),
               'native_repair_available': response.get('repair_available', False),
               'cold_worker': response.get('cold_worker', False),
               'verified_correct': None, 'validation_seconds': None,
               'mutation_depth': response.get('native_result', {}).get('depth')}
        if hint:
            verification = verify_candidate(case['path'], case['predicate'], response['candidate_repair'])
            row['verified_correct'] = verification.get('verified_correct')
            row['validation_seconds'] = verification['validation_process_wall_s']
            row['validation_status'] = verification['status']
            response['independent_validation'] = verification
        return row, response

    started = time.perf_counter()
    statuses = Counter(row['status'] for row in prior)
    completed = len(finished)
    try:
        with results_path.open('ab') as out, (args.output / 'responses.jsonl.gz').open('ab') as raw, \
             ThreadPoolExecutor(max_workers=args.workers) as executor:
            futures = {executor.submit(evaluate, case): case['case_id'] for case in pending}
            for future in as_completed(futures):
                row, response = future.result()
                append_checkpoint(raw, out, row, response, manifest_sha256)
                # Completed Future objects retain private responses; release
                # them as soon as both checkpoint records are durable.
                futures.pop(future)
                completed += 1
                statuses[row['status']] += 1
                if completed % 100 == 0 or completed == len(cases):
                    print(json.dumps({'tool': manifest['tool'], 'completed': completed, 'total': len(cases),
                                      'elapsed_seconds': time.perf_counter() - started, 'statuses': dict(statuses)}), flush=True)
    finally:
        # Attempt every cleanup even when another cleanup raises; worker
        # callbacks run first, so preferences remain until their JVMs exit.
        with ExitStack() as cleanup:
            for preference_root in preference_roots:
                cleanup.callback(preference_root.cleanup)
            for worker in workers:
                cleanup.callback(worker.close)
    report = {**manifest, 'completed': completed, 'run_wall_seconds': time.perf_counter() - started,
              'statuses': dict(statuses), 'resumed_rows': len(prior)}
    (args.output / 'run.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
