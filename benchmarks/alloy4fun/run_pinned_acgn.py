#!/usr/bin/env python3
"""Freeze and run only the ACGN Canonical/AST arms with bounded Linux services.

Existing five-arm evidence is read and hashed, never opened for writing. Inputs,
compiled classes and sources are copied to a dedicated run before measurement.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
GIB = 1024 ** 3
ARMS = (('ast', 'run_ast.py'), ('canonical', 'run_live.py'))
POLICY = dict(workers=4, memory_high_bytes=4 * GIB, memory_max_bytes=6 * GIB,
              cpu_quota_percent=400, memory_swap_max_bytes=0,
              startup_available_bytes=10 * GIB, runtime_available_bytes=4 * GIB,
              external_free_bytes=32 * GIB, internal_free_bytes=10 * GIB,
              timeout_seconds=60, heap='512m', initial_heap=None)


def run_policy(heap='512m', initial_heap=None):
    sizes = {'512m': 512, '1g': 1024, '2g': 2048}
    if heap not in sizes or initial_heap is not None and (
            initial_heap not in sizes or sizes[initial_heap] > sizes[heap]):
        raise ValueError('Invalid initial/maximum heap combination')
    policy = dict(POLICY, heap=heap, initial_heap=initial_heap)
    if heap != '512m':
        policy.update(memory_high_bytes=10 * GIB, memory_max_bytes=12 * GIB,
                      startup_available_bytes=16 * GIB)
    return policy


def selected_arms(names):
    if not names or len(set(names)) != len(names) or set(names) - {a for a, _ in ARMS}:
        raise ValueError('Invalid experiment arms')
    return [(arm, script) for arm, script in ARMS if arm in names]


def oom_kills():
    return int(next(line.split()[1] for line in Path('/proc/vmstat').read_text().splitlines()
                    if line.startswith('oom_kill ')))


def sha(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def now():
    return datetime.now(timezone.utc).isoformat()


def write(path, value):
    path = Path(path)
    pending = path.with_name(path.name + '.writing')
    pending.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    pending.replace(path)


def available():
    return int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                    if line.startswith('MemAvailable:'))) * 1024


def inventory(directory):
    return {str(p.relative_to(directory)): sha(p) for p in sorted(directory.rglob('*')) if p.is_file()}


def check_preserved(manifest):
    for name, expected in manifest['preserved_previous_results'].items():
        if sha(name) != expected:
            raise RuntimeError('A preserved prior result changed; refuse further work')


def check_snapshot(experiment, manifest):
    for name, expected in manifest['snapshot'].items():
        if sha(experiment / name) != expected:
            raise RuntimeError('A frozen source/input changed; refuse measurement')


def preserved_run_artifacts(prior_run, experiment):
    prior_run, experiment = Path(prior_run).resolve(), Path(experiment).resolve()
    if (not (prior_run / 'manifest.json').is_file()
            or prior_run.is_relative_to(experiment) or experiment.is_relative_to(prior_run)):
        raise ValueError('A preserved run must be an existing separate experiment')
    names = {'manifest.json', 'run.json', 'state.json', 'resource-guard.json',
             'results.jsonl', 'responses.jsonl.gz'}
    return {str(path): sha(path) for path in sorted(prior_run.rglob('*'))
            if path.is_file() and path.name in names}


def prepare(experiment, data, preserve_runs=(), *, arms=('ast', 'canonical'), heap='512m', initial_heap=None):
    selected = selected_arms(arms)
    policy = run_policy(heap, initial_heap)
    experiment = experiment.resolve()
    data = data.resolve()
    if experiment.exists():
        raise ValueError('Choose a fresh experiment directory')
    copied_roots = [ROOT / name for name in ('engine/src', 'vendor/acgn', 'benchmarks/alloy4fun')]
    if any(experiment.is_relative_to(p) or p.is_relative_to(experiment) for p in copied_roots) or experiment.is_relative_to(data) or data.is_relative_to(experiment):
        raise ValueError('Experiment must be separate from source and previous evidence')
    experiment.mkdir(parents=True, mode=0o700)
    source = experiment / 'source'
    source.mkdir()
    for name in ('engine/src', 'vendor/acgn', 'benchmarks/alloy4fun'):
        shutil.copytree(ROOT / name, source / name, ignore=shutil.ignore_patterns('__pycache__'))
    for name in ('runtime_dependencies.py', 'traffic_limits.py'):
        shutil.copy2(ROOT / name, source / name)
    (source / 'scripts').mkdir()
    shutil.copy2(ROOT / 'scripts/build_engine.py', source / 'scripts/build_engine.py')
    subprocess.run([sys.executable, str(source / 'scripts/build_engine.py'), '--root', str(source)],
                   check=True, capture_output=True, timeout=180)
    bridge = source / 'build/benchmarks/alloy4fun/classes'
    bridge.mkdir(parents=True)
    subprocess.run(['javac', '-J-Xmx256m', '-J-XX:ActiveProcessorCount=2', '--release', '17',
                    '-encoding', 'UTF-8', '-cp', str(source / 'build/engine/classes') + os.pathsep
                    + str(source / 'vendor/acgn/lib/*'), '-d', str(bridge),
                    str(source / 'benchmarks/alloy4fun/live/LiveWorker.java')],
                   check=True, capture_output=True, timeout=60)
    inputs = experiment / 'inputs'
    inputs.mkdir()
    for name in ('cohort.json', 'cases.jsonl', 'payloads.jsonl', 'inventory.jsonl', 'lineage.json'):
        shutil.copy2(data / name, inputs / name)
    cohort = json.loads((inputs / 'cohort.json').read_text())
    for name in ('cases.jsonl', 'payloads.jsonl', 'inventory.jsonl'):
        if sha(inputs / name) != cohort[name + '_sha256']:
            raise RuntimeError('Frozen cohort input does not match its recorded hash')
    cases = [json.loads(line) for line in (inputs / 'cases.jsonl').read_text().splitlines()]
    if len(cases) != 61598 or len({case['case_id'] for case in cases}) != 61598:
        raise ValueError('This experiment requires the same 61,598-case cohort')
    preserved = {}
    for arm in ('live-full', 'ast-full', 'tar', 'fm24-history', 'fm24-mutation'):
        for name in ('manifest.json', 'run.json', 'results.jsonl', 'responses.jsonl.gz'):
            path = data / arm / name
            preserved[str(path)] = sha(path)
    for prior_run in preserve_runs:
        # Hash immutable evidence from an explicitly paused/completed run. Do
        # not rewrite or repair its possibly interrupted gzip stream.
        preserved.update(preserved_run_artifacts(prior_run, experiment))
    manifest = {'schema': 'alloystudio-pinned-acgn-rerun-v1', 'prepared_at': now(),
                'acgn_commit': json.loads((source / 'vendor/acgn/snapshot.json').read_text())['commit'],
                'ast_view_version': 'acgn-semantic-ast-noop-body-erasure-v1',
                'arms': [name for name, _ in selected], 'policy': policy,
                'cases': 61598, 'data_source': str(data),
                'preserved_runs': [str(Path(p).resolve()) for p in preserve_runs],
                'preserved_previous_results': preserved,
                'snapshot': {str(p.relative_to(experiment)): sha(p)
                             for folder in (source, inputs) for p in sorted(folder.rglob('*')) if p.is_file()}}
    write(experiment / 'manifest.json', manifest)
    write(experiment / 'state.json', {'status': 'PREPARED', 'at': now()})
    (experiment / 'scratch').mkdir()
    (experiment / 'logs').mkdir()
    return manifest


def audit_arm(experiment, arm):
    directory = experiment / arm
    rows = [json.loads(line) for line in (directory / 'results.jsonl').read_text().splitlines()]
    expected = {json.loads(line)['case_id'] for line in (experiment / 'inputs/cases.jsonl').read_text().splitlines()}
    seen = set()
    count = 0
    with gzip.open(directory / 'responses.jsonl.gz', 'rt') as stream:
        for line in stream:
            response = json.loads(line)
            case = response['case_id']
            if case in seen or case not in expected:
                raise ValueError('Invalid/duplicate response identity')
            seen.add(case)
            count += 1
    if len(rows) != 61598 or {r['case_id'] for r in rows} != expected or count != 61598 or seen != expected:
        raise ValueError('Incomplete arm evidence')
    return {'status': 'COMPLETE', 'cases': len(rows), 'sha256': inventory(directory)}


def execute(experiment):
    manifest = json.loads((experiment / 'manifest.json').read_text())
    check_snapshot(experiment, manifest)
    check_preserved(manifest)
    environment = dict(os.environ, OPENAI_DISABLED='1', ALLOY_AI_DISABLED='1',
                       TMPDIR=str(experiment / 'scratch'), TMP=str(experiment / 'scratch'),
                       TEMP=str(experiment / 'scratch'), ALLOY_BENCHMARK_TMP_ROOT=str(experiment / 'scratch'))
    completed = {}
    policy = manifest['policy']
    for arm, script in selected_arms(manifest['arms']):
        check_snapshot(experiment, manifest)
        check_preserved(manifest)
        write(experiment / 'state.json', {'status': 'RUNNING', 'arm': arm, 'at': now(), 'completed': completed})
        command = [sys.executable, str(experiment / 'source/benchmarks/alloy4fun' / script),
                   '--data', str(experiment / 'inputs'), '--folds', str(experiment / 'inputs/lineage.json'),
                   '--output', str(experiment / arm), '--workers', '4', '--timeout', '60']
        if arm == 'canonical':
            command += ['--heap', policy['heap']]
            if policy['initial_heap'] is not None:
                command += ['--initial-heap', policy['initial_heap']]
        start = now()
        with (experiment / 'logs' / (arm + '.log')).open('x') as log:
            subprocess.run(command, cwd=experiment / 'source', env=environment, stdout=log,
                           stderr=subprocess.STDOUT, check=True)
        completed[arm] = dict(audit_arm(experiment, arm), started_at=start, ended_at=now(), command=command)
        write(experiment / 'completion.json', completed)
    check_snapshot(experiment, manifest)
    check_preserved(manifest)
    write(experiment / 'state.json', {'status': 'COMPLETED', 'at': now(), 'completed': completed,
                                    'preserved_previous_results_unchanged': True})


def properties(unit):
    run = subprocess.run(['systemctl', '--user', 'show', unit, '-p', 'ControlGroup', '-p', 'MainPID',
                          '-p', 'ActiveState', '-p', 'SubState', '-p', 'Result', '-p', 'ExecMainStatus'],
                         capture_output=True, text=True, check=True, timeout=10)
    return dict(line.split('=', 1) for line in run.stdout.splitlines() if '=' in line)


def live_cgroup(state, root=Path('/sys/fs/cgroup')):
    terminal = state.get('ActiveState') in ('inactive', 'failed') or state.get('SubState') == 'exited'
    group = state.get('ControlGroup')
    cgroup = root / group.lstrip('/') if group else None
    if cgroup is None or not (cgroup / 'memory.current').is_file():
        if terminal:
            return None
        raise RuntimeError('UNKNOWN live service cgroup telemetry; stopping owned workload')
    return cgroup


def supervise(experiment, prefix):
    manifest = json.loads((experiment / 'manifest.json').read_text())
    policy = manifest['policy']
    if policy != run_policy(policy['heap'], policy['initial_heap']):
        raise RuntimeError('Manifest resource policy does not match the frozen runner')
    selected_arms(manifest['arms'])
    check_preserved(manifest)
    check_snapshot(experiment, manifest)
    if available() < policy['startup_available_bytes']:
        raise RuntimeError('Insufficient startup headroom; nothing launched')
    if (shutil.disk_usage(experiment).free < policy['external_free_bytes']
            or shutil.disk_usage(Path.home()).free < policy['internal_free_bytes']):
        raise RuntimeError('Insufficient storage reserve; nothing launched')
    unit = prefix + '-workload.service'
    supervisor = prefix + '-supervisor.service'
    frozen = experiment / 'source/benchmarks/alloy4fun/run_pinned_acgn.py'
    command = ['systemd-run', '--user', '--unit=' + unit, '--property=MemoryAccounting=yes',
               '--property=MemoryHigh=' + str(policy['memory_high_bytes']),
               '--property=MemoryMax=' + str(policy['memory_max_bytes']), '--property=MemorySwapMax=0',
               '--property=CPUQuota=400%', '--property=Nice=10', '--property=OOMPolicy=kill',
               '--property=KillMode=control-group', '--property=TimeoutStopSec=5s',
               '--property=RemainAfterExit=yes', '--property=BindsTo=' + supervisor,
               '--property=After=' + supervisor,
               '--property=StandardOutput=append:' + str(experiment / 'logs/pipeline.log'),
               '--property=StandardError=inherit', sys.executable, str(frozen),
               '--experiment', str(experiment), '--execute']
    initial_oom = oom_kills()
    guard = {'status': 'STARTING', 'at': now(), 'policy': policy, 'unit': unit,
             'supervisor': supervisor, 'peak_bytes': 0, 'minimum_host_available_bytes': available(),
             'initial_device': experiment.stat().st_dev, 'initial_global_oom_kills': initial_oom,
             'memory_events': {}}
    subprocess.run(command, check=True, capture_output=True, timeout=20)
    try:
        while True:
            state = properties(unit)
            free = available()
            guard['minimum_host_available_bytes'] = min(guard['minimum_host_available_bytes'], free)
            cgroup = live_cgroup(state)
            if cgroup is not None:
                guard['peak_bytes'] = max(guard['peak_bytes'], int((cgroup / 'memory.peak').read_text()))
                guard['memory_events'] = {k: int(v) for k, v in (line.split() for line in (cgroup / 'memory.events').read_text().splitlines())}
                quota, period = (cgroup / 'cpu.max').read_text().split()
                limits = {'memory_max': int((cgroup / 'memory.max').read_text()),
                          'memory_high': int((cgroup / 'memory.high').read_text()),
                          'swap_max': int((cgroup / 'memory.swap.max').read_text()),
                          'cpu_percent': int(quota) * 100 // int(period)}
                if limits != {'memory_max': policy['memory_max_bytes'],
                              'memory_high': policy['memory_high_bytes'], 'swap_max': 0, 'cpu_percent': 400}:
                    raise RuntimeError('Effective cgroup limits do not match policy')
                guard['effective_limits'] = limits
                guard['swap_bytes'] = int((cgroup / 'memory.swap.current').read_text())
                if guard['swap_bytes'] or any(guard['memory_events'].get(k, 0)
                        for k in ('oom', 'oom_kill', 'oom_group_kill')):
                    raise RuntimeError('Service swap or OOM incident')
            if oom_kills() != initial_oom:
                raise RuntimeError('Host OOM incident')
            if state.get('ActiveState') in ('inactive', 'failed') or state.get('SubState') == 'exited':
                if 'effective_limits' not in guard:
                    raise RuntimeError('UNKNOWN effective resource limits; no verified service sample')
                guard['service_state'] = state
                guard['status'] = 'COMPLETED' if state.get('ExecMainStatus') == '0' and state.get('Result') == 'success' else 'FAILED'
                break
            if free < policy['runtime_available_bytes']:
                raise RuntimeError('Global memory reserve crossed')
            if (experiment.stat().st_dev != guard['initial_device']
                    or shutil.disk_usage(experiment).free < policy['external_free_bytes']
                    or shutil.disk_usage(Path.home()).free < policy['internal_free_bytes']):
                raise RuntimeError('Experiment storage unavailable or below reserve')
            guard['status'] = 'RUNNING'; guard['at'] = now()
            write(experiment / 'resource-guard.json', guard)
            time.sleep(2)
    except BaseException as error:
        subprocess.run(['systemctl', '--user', 'stop', unit], check=False, timeout=15)
        guard['status'] = 'STOPPED'; guard['reason'] = str(error)
        raise
    finally:
        guard['ended_at'] = now()
        write(experiment / 'resource-guard.json', guard)
    check_preserved(manifest)
    if guard['status'] != 'COMPLETED':
        raise RuntimeError('Pinned ACGN pipeline failed; inspect its private log')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--data', type=Path)
    parser.add_argument('--arm', choices=('ast', 'canonical', 'both'), default='both')
    parser.add_argument('--heap', choices=('512m', '1g', '2g'), default='512m')
    parser.add_argument('--initial-heap', choices=('512m', '1g', '2g'))
    parser.add_argument('--preserve-run', type=Path, action='append', default=[],
                        help='Hash prior paused/completed evidence without reusing observations')
    parser.add_argument('--prepare', action='store_true')
    parser.add_argument('--launch', action='store_true')
    parser.add_argument('--supervise', action='store_true')
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--unit-prefix', default='alloy-acgn-rebase-20261109')
    args = parser.parse_args()
    experiment = args.experiment.resolve()
    if sum((args.prepare, args.launch, args.supervise, args.execute)) != 1:
        parser.error('Choose exactly one action')
    if args.prepare:
        if args.data is None:
            parser.error('--prepare requires --data')
        arms = tuple(a for a, _ in ARMS) if args.arm == 'both' else (args.arm,)
        manifest = prepare(experiment, args.data, args.preserve_run,
                           arms=arms, heap=args.heap, initial_heap=args.initial_heap)
        print(json.dumps({'status': 'PREPARED', 'cases': manifest['cases'], 'experiment': str(experiment)}))
    elif args.launch:
        frozen = experiment / 'source/benchmarks/alloy4fun/run_pinned_acgn.py'
        subprocess.run(['systemd-run', '--user', '--unit=' + args.unit_prefix + '-supervisor.service',
                        '--property=MemoryMax=256M', '--property=MemorySwapMax=0',
                        '--property=OOMPolicy=kill', '--property=Nice=10',
                        '--property=StandardOutput=append:' + str(experiment / 'logs/supervisor.log'),
                        '--property=StandardError=inherit', sys.executable, str(frozen),
                        '--experiment', str(experiment), '--supervise', '--unit-prefix', args.unit_prefix], check=True)
        print(json.dumps({'status': 'LAUNCHED', 'experiment': str(experiment), 'supervisor': args.unit_prefix + '-supervisor.service'}))
    elif args.supervise:
        supervise(experiment, args.unit_prefix)
    else:
        execute(experiment)


if __name__ == '__main__':
    main()
