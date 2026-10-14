#!/usr/bin/env python3
"""Resume a finite public-hint collection through the production portal services.

Feedback is single-worker. AI explanations use the private deployment profile,
the unchanged production prompt/validators and at most two concurrent requests.
No credentials, oracle bodies or raw provider responses are written to evidence.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from ai_provider import select_profile
from luna import Explainer, INSTRUCTIONS, MODEL, prompt_education, read_key
from server import Portal


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def encoded(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    with temporary.open('w', encoding='utf-8') as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    temporary.replace(path)


def now():
    return datetime.now(timezone.utc).isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--phase', choices=('feedback', 'ai'), required=True)
    parser.add_argument('--retry-unavailable', action='store_true',
                        help='Retry failed AI once; retain the first result and never replace successful output')
    args = parser.parse_args()
    study = args.study.resolve()
    selection_path = study / 'selection.json'
    selection = json.loads(selection_path.read_text())
    cases = selection['cases']
    if not 100 <= len(cases) <= 200:
        raise ValueError('The collection requires 100–200 frozen cases.')
    scratch = study / 'tmp'
    scratch.mkdir(parents=True, exist_ok=True)
    for name in ('TMPDIR', 'TMP', 'TEMP'):
        os.environ[name] = str(scratch)
    paths = [ROOT / name for name in ('server.py', 'luna.py', 'ai_provider.py',
                                     'repair_grammar.py', 'exercises/exercises.sqlite3')]
    for directory, pattern in (('engine/src', '*.java'), ('vendor/acgn/src', '*.java'),
                               ('vendor/acgn/lib', '*.jar'), ('build/engine/classes', '*.class')):
        paths.extend(sorted((ROOT / directory).rglob(pattern)))
    identity = {'selection_sha256': digest(selection_path.read_bytes()),
                'runtime_sha256': {str(p.relative_to(ROOT)): digest(p.read_bytes()) for p in paths},
                'instructions_sha256': digest(INSTRUCTIONS.encode())}
    manifest_path = study / 'collection-manifest.json'
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
        if manifest['identity'] != identity:
            raise ValueError('Selection or runtime changed; use a new collection directory.')
    else:
        manifest = {'schema': 1, 'started_at_utc': now(), 'identity': identity,
                    'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    'collection': 'Production Portal.evaluate and Explainer.explain; current full correct pools including oracle; archived baselines unchanged.',
                    'limits': {'feedback_workers': 1, 'engine_timeout_seconds': 60,
                               'ai_concurrency': 2, 'ai_timeout_seconds': 40, 'ai_attempts_maximum': 2},
                    'behavior': 'Not collected: edit guidance only; AI receives no instances or behavioral score.'}
        write(manifest_path, manifest)

    def record_path(case, metric):
        return study / 'records' / (f"{case['number']:03d}-{metric}.json")

    def snapshot():
        rows = []
        for case in cases:
            responses = {}
            for metric in ('canonical', 'ast'):
                path = record_path(case, metric)
                if path.exists():
                    responses[metric] = json.loads(path.read_text())
            rows.append({'case_id': case['case_id'], 'exercise_id': case['exercise_id'], 'responses': responses})
        write(study / 'current.json', {**manifest, **identity, 'updated_at_utc': now(), 'cases': rows})

    if args.phase == 'feedback':
        app = Portal(('127.0.0.1', 0), workers=1, timeout=60)
        try:
            for case in cases:
                record = app.exercises[case['exercise_id']]
                if case['description'] != record['description']:
                    raise ValueError('Frozen question differs from the current portal question.')
                for metric in ('canonical', 'ast'):
                    path = record_path(case, metric)
                    if path.exists():
                        continue
                    started = time.perf_counter()
                    response = app.evaluate(record, case['learner_body'], metric)
                    value = {'response': response, 'response_sha256': digest(encoded(response)),
                             'collected_at_utc': now(), 'wall_seconds_diagnostic_only': time.perf_counter() - started}
                    if response.get('status') == 'ok':
                        try:
                            evidence = prompt_education(response, case['learner_body'], question=case['description'])
                            value['education_evidence_sha256'] = digest(encoded(evidence))
                        except (ValueError, KeyError, TypeError):
                            value['education_evidence_status'] = 'unsupported'
                    write(path, value)
                    print(json.dumps({'number': case['number'], 'exercise': case['exercise_id'], 'metric': metric,
                                      'status': response.get('status'), 'distance': response.get('distance')}), flush=True)
                snapshot()
        finally:
            app.server_close()
            snapshot()
        return

    profile = select_profile('guidance', read_key, MODEL, 'low')
    if profile is None:
        raise ValueError('Configure guidance privately before collecting AI explanations.')
    client = Explainer()

    def explain(case, metric):
        path = record_path(case, metric)
        value = json.loads(path.read_text())
        response = value['response']
        if response.get('status') != 'ok' or not response.get('operations') or response.get('distance', 0) <= 0:
            raise ValueError('Every selected case must have current nonempty hints before AI collection.')
        if 'education_evidence_sha256' not in value:
            raise ValueError('The full production education evidence is unsupported.')
        attempts = value.get('ai_attempts', [])
        if value.get('ai', {}).get('status') == 'ok' or attempts and (not args.retry_unavailable or len(attempts) >= 2):
            return
        started = time.perf_counter()
        result = client.explain(response, student_body=case['learner_body'], question=case['description'])
        attempts.append({'result': result, 'collected_at_utc': now(),
                         'wall_seconds_diagnostic_only': time.perf_counter() - started})
        value.update(ai=result, ai_attempts=attempts, ai_sha256=digest(encoded(result)),
                     ai_provider=profile.protocol, ai_effort=profile.effort)
        write(path, value)
        return {'number': case['number'], 'exercise': case['exercise_id'], 'metric': metric,
                'ai_status': result.get('status'), 'attempts': len(attempts)}

    # A bounded executor shares the production client's two outbound slots;
    # this phase starts no JVMs. Each response has its own append-only attempt list.
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = [pool.submit(explain, case, metric) for case in cases for metric in ('canonical', 'ast')]
            for task in as_completed(pending):
                result = task.result()
                if result:
                    print(json.dumps(result), flush=True)
                    snapshot()
    finally:
        snapshot()


if __name__ == '__main__':
    main()
