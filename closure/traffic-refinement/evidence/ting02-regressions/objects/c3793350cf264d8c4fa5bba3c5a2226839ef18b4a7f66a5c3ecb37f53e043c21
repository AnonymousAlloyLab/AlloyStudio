#!/usr/bin/env python3
"""Collect public deterministic portal hints for a frozen small quality sample.

Uses a fresh local server/cache, the installed production class tree and full
correct pools. Does not invoke /api/explain or alter the completed benchmark.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file; existing evidence is preserved.')
    selected = json.loads(args.selection.read_text())
    scratch = args.output.resolve().parent / 'tmp'
    scratch.mkdir(parents=True, exist_ok=True)
    env = {**os.environ, 'OPENAI_DISABLED': '1', 'TMPDIR': str(scratch)}
    process = subprocess.Popen([sys.executable, 'server.py', '--port', '0',
                                '--workers', '1', '--timeout', '60'], cwd=ROOT,
                               env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                               text=True)
    try:
        with selectors.DefaultSelector() as poll:
            poll.register(process.stdout, selectors.EVENT_READ)
            if not poll.select(20):
                raise RuntimeError('Study server startup timed out')
            match = re.search(r'http://127\.0\.0\.1:\d+', process.stdout.readline())
            if not match:
                raise RuntimeError('Study server did not provide a loopback URL')
            base = match[0]
        records = []
        for case in selected['cases']:
            row = {'exercise_id': case['exercise_id'], 'case_id': case['case_id'], 'responses': {}}
            for metric in ('canonical', 'ast'):
                payload = {'exerciseId': case['exercise_id'], 'body': case['learner_body'],
                           'revision': case['number'], 'metric': metric}
                request = urllib.request.Request(base + '/api/feedback',
                    data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json', 'Origin': base})
                start = time.perf_counter()
                with urllib.request.urlopen(request, timeout=70) as response:
                    raw = response.read()
                data = json.loads(raw)
                assert data['exerciseId'] == case['exercise_id'] and data['revision'] == case['number']
                row['responses'][metric] = {'response': data,
                    'response_bytes_sha256': hashlib.sha256(raw).hexdigest(),
                    'wall_seconds_diagnostic_only': time.perf_counter() - start}
                print(case['exercise_id'], metric, data['status'], data.get('distance'), flush=True)
            records.append(row)
        paths = [ROOT / 'server.py', ROOT / 'web/app.js', ROOT / 'exercises/exercises.sqlite3']
        for directory, pattern in [('engine/src', '*.java'), ('vendor/acgn/src', '*.java'),
                                   ('vendor/acgn/lib', '*.jar'), ('build/engine/classes', '*.class')]:
            paths.extend(sorted((ROOT / directory).rglob(pattern)))
        output = {'schema': 1, 'collected_at_utc': datetime.now(timezone.utc).isoformat(),
                  'selection_sha256': sha(args.selection),
                  'collection': 'Fresh local production HTTP /api/feedback requests; full correct pools; one worker; 60-second engine timeout; no Luna calls.',
                  'runtime_sha256': {str(p.relative_to(ROOT)): sha(p) for p in paths}, 'cases': records}
        args.output.write_text(json.dumps(output, indent=2) + '\n')
    finally:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


if __name__ == '__main__':
    main()
