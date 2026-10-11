#!/usr/bin/env python3
"""Measure bounded synthetic upload batches without keys or corpus mutations."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys
import time
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
BASE = 'sig Node {}\n'
SCENARIOS = ('100-standalone', '100-parameterized', '100-equivalent-pairs', '100-token-duplicate-pairs')


def source_for(scenario):
    if scenario == '100-standalone':
        return BASE + ''.join(f'pred practice{i} {{some Node}}\n' for i in range(100))
    if scenario == '100-parameterized':
        return BASE + ''.join(f'pred practice{i}[n: Node] {{some n}}\n' for i in range(100))
    body = 'not no Node' if scenario == '100-equivalent-pairs' else 'some  Node'
    return BASE + ''.join(f'pred inv{i}C0 {{some Node}}\npred inv{i}C1 {{{body}}}\n'
                          for i in range(1, 101))


def affinity():
    if not hasattr(os, 'sched_getaffinity') or not hasattr(os, 'sched_setaffinity'):
        return dict(status='unavailable', processors=None)
    try:
        processors = sorted(os.sched_getaffinity(0))[:2]
        os.sched_setaffinity(0, processors)
        return dict(status='pinned', processors=sorted(os.sched_getaffinity(0)))
    except OSError:
        return dict(status='unavailable', processors=None)


def maximum_rss():
    try:
        import resource
    except ImportError:
        return dict(status='unavailable')
    # macOS reports bytes; Linux and other Unix implementations report KiB.
    factor = 1 if sys.platform == 'darwin' else 1024
    return dict(status='available', pythonBytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * factor,
                largestReapedChildBytes=resource.getrusage(resource.RUSAGE_CHILDREN).ru_maxrss * factor,
                meaning='Largest individual child RSS, not simultaneous or combined process RSS.')


def worker(scenario, output):
    import admin_upload as upload
    import exercise_store as store
    from runtime_dependencies import PROCESS_BUDGET

    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    scratch = output.parent / (scenario + '-engine-tmp')
    os.environ['ALLOY_ENGINE_TMP_ROOT'] = str(scratch)
    cpu = affinity()
    source = source_for(scenario)
    envelope = dict(source=source, filename='capacity.als', modelId='capacity')
    launches, hashes = [], []
    engine, identify = upload.run_engine, store._engine_identity

    def run(arguments, **kwargs):
        started = time.monotonic()
        result = engine(arguments, **kwargs)
        launches.append(dict(main=arguments[-2] if arguments[-1] == '--batch' else arguments[-1],
                             batch=arguments[-1] == '--batch',
                             frames=len(kwargs['input'].splitlines()) if arguments[-1] == '--batch' else 1,
                             seconds=round(time.monotonic() - started, 6),
                             heapMiB=256 if '-Xmx256m' in arguments else None,
                             activeProcessorCount=2 if '-XX:ActiveProcessorCount=2' in arguments else None))
        return result

    def identity(classpath):
        started = time.monotonic()
        result = identify(classpath)
        hashes.append(round(time.monotonic() - started, 6))
        return result

    started = time.monotonic()
    with patch.object(upload, 'run_engine', side_effect=run), patch.object(store, '_engine_identity', side_effect=identity):
        prepared = upload.prepare_upload(ROOT, envelope)
    elapsed = time.monotonic() - started
    frames = 200 if scenario == '100-token-duplicate-pairs' else 100
    if len(launches) != 2 or launches[1]['frames'] != frames or len(hashes) != 2:
        raise RuntimeError('Preparation violated its constant JVM or identity-check contract.')
    if len(prepared['documents']) != 100 or elapsed > 60:
        raise RuntimeError('The synthetic batch did not satisfy the acceptance budget.')
    process = PROCESS_BUDGET.stats()
    if process['reserved'] or process['highWater'] > 1:
        raise RuntimeError('Sequential administrator JVMs were not reaped or overlapped.')
    remaining_scratch = sum(1 for _ in scratch.iterdir())
    if remaining_scratch:
        raise RuntimeError('An upload JVM left files in its owned scratch directory.')
    result = dict(scenario=scenario, status='PASS', seconds=round(elapsed, 6),
                  groups=100, sourceBytes=len(source.encode('utf-8')),
                  sourceVariants=sum(len(group['variants']) for group in prepared['witness']['groups']),
                  storedOracles=sum(len(doc['oracleSolutions']) for doc in prepared['documents']),
                  preparedSerializedBytes=upload.prepared_size(prepared), launches=launches,
                  dependencyIdentityChecks=len(hashes), dependencyIdentitySeconds=hashes,
                  cpuAffinity=cpu, maximumRss=maximum_rss(), processBudget=process,
                  remainingScratchEntries=remaining_scratch,
                  engineSha256=prepared['certificates'][0]['engineSha256'],
                  sourceSha256=hashlib.sha256(source.encode('utf-8')).hexdigest(),
                  artifactSha256={name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest()
                      for name in ('admin_upload.py', 'admin_service.py', 'exercise_store.py',
                                   'runtime_dependencies.py', 'engine/src/live/ExerciseValidator.java',
                                   'engine/src/live/UploadInspector.java',
                                   'engine/src/live/ParameterContext.java',
                                   'engine/src/live/BehaviorFeedback.java',
                                   'scripts/test_upload_capacity.py')},
                  python=platform.python_version(), platform=platform.platform())
    output.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(dict(scenario=scenario, status='PASS', seconds=result['seconds'],
                          jvmLaunches=len(launches), validationFrames=frames)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker', choices=SCENARIOS)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    if args.worker:
        if args.output is None:
            parser.error('--worker requires --output')
        worker(args.worker, args.output)
        return
    stamp = time.strftime('%Y%m%d-%H%M%S', time.gmtime())
    directory = (args.directory or ROOT / 'build/upload-capacity-validation' / ('measurement-' + stamp)).resolve()
    directory.mkdir(parents=True, exist_ok=False)
    reports = []
    for scenario in SCENARIOS:
        output = directory / (scenario + '.json')
        completed = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--worker', scenario,
                                    '--output', str(output)], cwd=ROOT, capture_output=True,
                                   text=True, encoding='utf-8', timeout=80, check=False)
        if completed.returncode:
            raise SystemExit(f'Capacity validation failed in {scenario}; no publication was performed.')
        print(completed.stdout.strip(), flush=True)
        reports.append(json.loads(output.read_text(encoding='utf-8')))
    summary = dict(status='PASS', scenarios=reports,
                   exclusions=['Synthetic bounded models do not establish arbitrary Alloy solve times.',
                               'The RSS samples do not prove a combined service-memory bound.',
                               'No real provider calls, uploaded private models, or production SQLite writes.'])
    (directory / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    print('Evidence: ' + str(directory / 'summary.json'))


if __name__ == '__main__':
    main()
