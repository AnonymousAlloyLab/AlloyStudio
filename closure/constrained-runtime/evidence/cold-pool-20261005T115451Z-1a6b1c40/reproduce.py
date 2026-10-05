"""Finite cold-JVM contention witness; run from a built checkout on Linux."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path.cwd()
sys.path.insert(0, str(ROOT))
from server import model
from runtime_dependencies import clean_java_environment


def sha(data):
    return hashlib.sha256(data).hexdigest()


def inputs():
    paths = [ROOT / name for name in (
        'exercises/catalogue.json', 'exercises/correct-pools.json',
        'engine/src/live/LiveFeedback.java', 'engine/src/live/EngineWorker.java',
        'vendor/acgn/src/is/fivefivefive/CanDis/WorkBudget.java')]
    paths += sorted((ROOT / 'build/engine/classes').rglob('*.class'))
    paths += sorted((ROOT / 'vendor/acgn/lib').glob('*.jar'))
    return {p.relative_to(ROOT).as_posix(): sha(p.read_bytes()) for p in paths}


if __name__ == '__main__':
    before = inputs()
    cpu = min(os.sched_getaffinity(0))
    os.sched_setaffinity(0, {cpu})
    record = next(r for r in json.loads((ROOT / 'exercises/catalogue.json').read_text())['exercises']
                  if r['id'] == 'socialMedia-inv4')
    pool = next(p for p in json.loads((ROOT / 'exercises/correct-pools.json').read_text())['pools']
                if p['exerciseId'] == record['id'])
    payload = json.dumps({'studentSource': model(record, record['starter']),
        'predicate': record['predicate'], 'referenceBodies': [c['body'] for c in pool['candidates']],
        'referencePrefix': record['environmentBefore'] + record['predicateHeader'] + '{\n',
        'referenceSuffix': '\n}' + record['environmentAfter']})
    java = Path(shutil.which('java')).resolve()
    command = [str(java), '-Xmx256m', '-XX:ActiveProcessorCount=2', '-cp',
               str(ROOT / 'build/engine/classes') + ':' + str(ROOT / 'vendor/acgn/lib/*'),
               'live.LiveFeedback']

    def request(_):
        started = time.monotonic()
        result = subprocess.run(command, input=payload, text=True, capture_output=True,
                                timeout=20, env=clean_java_environment())
        response = json.loads(result.stdout)
        return {'seconds': time.monotonic() - started, 'status': response.get('status'),
                'workLimit': any(d.get('code') == 'WORK_LIMIT' for d in response.get('diagnostics', [])),
                'distance': response.get('distance'),
                'complete': response.get('comparison', {}).get('complete'),
                'evaluatedCandidates': response.get('comparison', {}).get('evaluatedCandidates'),
                'exitCode': result.returncode, 'stderrPresent': bool(result.stderr),
                'responseSha256': sha(result.stdout.encode())}

    serial_before = [request(0), request(1)]
    with ThreadPoolExecutor(max_workers=4) as executor:
        parallel = list(executor.map(request, range(4)))
    serial_after = request(0)
    assert inputs() == before, 'Runtime inputs changed during witness'
    serial = [*serial_before, serial_after]
    observed = (all(r['status'] == 'ok' and r['complete'] is True for r in serial)
                and len({r['responseSha256'] for r in serial}) == 1
                and all(r['status'] == 'unsupported' and r['workLimit']
                        and r['distance'] is None and r['complete'] is None for r in parallel))
    report = {'scope': 'TESTED single-CPU cold-process contention; not identification of historical CI response codes',
        'status': 'OBSERVED' if observed else 'NOT_OBSERVED', 'affinity': [cpu],
        'exercise': record['id'], 'poolSize': len(pool['candidates']),
        'inputSha256': sha(payload.encode()), 'inputs': before,
        'javaExecutableSha256': sha(java.read_bytes()), 'pythonVersion': sys.version,
        'defaultSoftMilliseconds': 8000, 'outerTimeoutSeconds': 20,
        'serialBefore': serial_before, 'parallelFour': parallel, 'serialAfter': serial_after}
    Path(sys.argv[1]).write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'status': report['status'], 'poolSize': report['poolSize'],
                     'serialSeconds': [r['seconds'] for r in serial],
                     'parallelSeconds': [r['seconds'] for r in parallel]}))
    raise SystemExit(0 if observed else 1)
