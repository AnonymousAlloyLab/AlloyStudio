#!/usr/bin/env python3
"""Run TAR and final reporting in bounded user services, preserving closed arms.

Linux/systemd recovery launcher. The small supervisor is outside the workload's
memory cgroup. Limits cover Java heaps, native solvers, verification, and Python.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
GIB = 1024 ** 3
POLICY = {'workers': 4, 'memory_high_bytes': 4 * GIB, 'memory_max_bytes': 6 * GIB,
          'memory_swap_max_bytes': 0, 'cpu_quota_percent': 400, 'nice': 10}
START_RESERVE, STOP_RESERVE = 10 * GIB, 4 * GIB


def now():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.writing')
    with temporary.open('w') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)


def available_memory():
    for line in Path('/proc/meminfo').read_text().splitlines():
        if line.startswith('MemAvailable:'):
            return int(line.split()[1]) * 1024
    raise RuntimeError('Available memory cannot be measured')


def properties(unit):
    result = subprocess.run(['systemctl', '--user', 'show', unit,
        '-p', 'ControlGroup', '-p', 'MainPID', '-p', 'ActiveState', '-p', 'SubState', '-p', 'Result',
        '-p', 'ExecMainStatus', '-p', 'BindsTo', '-p', 'After'], check=True, capture_output=True, text=True, timeout=10)
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def verify_preserved(data, profile):
    for arm, files in profile['recovery']['preserved_runs'].items():
        for name, expected in files['sha256'].items():
            if sha(data / arm / name) != expected:
                raise RuntimeError('A preserved completed-arm artifact changed')
    archive = profile['recovery']['archived_run']
    for name, expected in archive['sha256'].items():
        if sha(data / archive['path'] / name) != expected:
            raise RuntimeError('Interrupted evidence archive changed')


def effective_limits(cgroup, pid):
    quota, period = (cgroup / 'cpu.max').read_text().split()
    return {'workers': 4,
            'memory_high_bytes': int((cgroup / 'memory.high').read_text()),
            'memory_max_bytes': int((cgroup / 'memory.max').read_text()),
            'memory_swap_max_bytes': int((cgroup / 'memory.swap.max').read_text()),
            'cpu_quota_percent': int(quota) * 100 // int(period),
            'nice': os.getpriority(os.PRIO_PROCESS, pid)}


def run_bounded(data, unit, command, log):
    if available_memory() < START_RESERVE:
        raise RuntimeError('Less than 10 GiB available; workload was not started')
    scratch = data / 'scratch' / unit
    scratch.mkdir(parents=True, mode=0o700, exist_ok=False)
    supervisor = unit.rsplit('-', 1)[0] + '-supervisor.service'
    command_line = ['systemd-run', '--user', '--unit=' + unit,
        '--working-directory=' + str(ROOT), '--property=MemoryAccounting=yes',
        '--property=MemoryHigh=' + str(POLICY['memory_high_bytes']),
        '--property=MemoryMax=' + str(POLICY['memory_max_bytes']),
        '--property=MemorySwapMax=0', '--property=OOMPolicy=kill',
        '--property=CPUQuota=400%', '--property=Nice=10',
        '--property=KillMode=control-group', '--property=TimeoutStopSec=5s',
        '--property=RemainAfterExit=yes',
        '--property=BindsTo=' + supervisor, '--property=After=' + supervisor,
        '--property=StandardOutput=append:' + str(log), '--property=StandardError=inherit',
        '--setenv=ALLOY_BENCHMARK_TMP_ROOT=' + str(scratch), *command]
    report = {'status': 'RUNNING', 'unit': unit + '.service', 'started_at': now(),
              'command': command, 'source_sha256': sha(__file__),
              'exit_status': None, 'effective_limits': None, 'cgroup_oom_group': None,
              'memory_events': {}, 'memory_peak_bytes': 0,
              'global_available_min_bytes': available_memory(),
              'start_available_min_bytes': START_RESERVE,
              'stop_available_below_bytes': STOP_RESERVE,
              'watchdog_stopped_workload': False, 'scratch_root': str(scratch),
              'supervisor_unit': supervisor, 'supervisor_binding_verified': False}
    evidence = data / 'recovery' / ('tar-guard.json' if unit.endswith('-tar') else 'finalization-guard.json')
    try:
        subprocess.run(command_line, check=True, capture_output=True, text=True, timeout=15)
        write_json(evidence, report)
        while True:
            state = properties(unit)
            if supervisor not in state.get('BindsTo', '').split() or supervisor not in state.get('After', '').split():
                raise RuntimeError('Workload is not bound to the watchdog supervisor')
            report['supervisor_binding_verified'] = True
            group = state.get('ControlGroup')
            if group:
                cgroup = Path('/sys/fs/cgroup') / group.lstrip('/')
                if (cgroup / 'memory.current').exists():
                    events = dict(line.split() for line in (cgroup / 'memory.events').read_text().splitlines())
                    report['memory_events'] = {k: int(v) for k, v in events.items()}
                    report['memory_peak_bytes'] = max(report['memory_peak_bytes'],
                                                      int((cgroup / 'memory.peak').read_text()))
                    report['cgroup_oom_group'] = int((cgroup / 'memory.oom.group').read_text())
                    if report['effective_limits'] is None and int(state.get('MainPID', 0)):
                        report['effective_limits'] = effective_limits(cgroup, int(state['MainPID']))
                        report['control_group'] = group
                        if report['effective_limits'] != POLICY or report['cgroup_oom_group'] != 1:
                            raise RuntimeError('Enforced cgroup limits do not match the registered policy')
            free = available_memory()
            report['global_available_min_bytes'] = min(report['global_available_min_bytes'], free)
            if state.get('ActiveState') in ('inactive', 'failed') or state.get('SubState') == 'exited':
                report['exit_status'] = int(state.get('ExecMainStatus', 1))
                report['service_result'] = state.get('Result')
                break
            if free < STOP_RESERVE:
                report['watchdog_stopped_workload'] = True
                raise RuntimeError('Global available memory fell below the 4 GiB reserve')
            write_json(evidence, report)
            time.sleep(1)
    except BaseException:
        subprocess.run(['systemctl', '--user', 'stop', unit], timeout=10, check=False)
        report.update(status='STOPPED_FOR_SAFETY', ended_at=now())
        write_json(evidence, report)
        raise
    report.update(ended_at=now(), scratch_entries_after_run=len(list(scratch.iterdir())))
    ok = (report['exit_status'] == 0 and report.get('service_result') == 'success'
          and report['effective_limits'] == POLICY and report['cgroup_oom_group'] == 1
          and not any(report['memory_events'].get(k, 0) for k in ('oom', 'oom_kill', 'oom_group_kill')))
    report['status'] = 'COMPLETED' if ok else 'FAILED'
    write_json(evidence, report)
    subprocess.run(['systemctl', '--user', 'stop', unit], timeout=10, check=False)
    if not ok:
        raise RuntimeError('Bounded workload did not finish cleanly; evidence preserved')
    return report


def supervise(data, prefix):
    profile_path = data / 'resource-profile.json'
    profile = json.loads(profile_path.read_text())
    verify_preserved(data, profile)
    if (data / 'tar').exists():
        raise RuntimeError('Fresh TAR output already exists; preserved data will not be overwritten')
    report = run_bounded(data, prefix + '-tar', [sys.executable,
        str(ROOT / 'benchmarks/alloy4fun/run_tar.py'), '--cases', str(data / 'cases.jsonl'),
        '--output', str(data / 'tar'), '--workers', '4', '--timeout', '60', '--depth', '2'],
        data / 'tar-recovery-progress.log')
    verify_preserved(data, profile)
    profile['schedule'].append({'arm': 'tar', 'started_at': report['started_at'],
        'ended_at': report['ended_at'], 'exit_code': report['exit_status'],
        'command': report['command'], 'scratch_entries_after_arm': report['scratch_entries_after_run']})
    profile['recovery']['fresh_tar']['cgroup_evidence_sha256'] = sha(data / 'recovery/tar-guard.json')
    write_json(profile_path, profile)
    run_bounded(data, prefix + '-final', [sys.executable, str(Path(__file__).resolve()),
        '--data', str(data), '--finalize'], data / 'recovery/finalization.log')
    verify_preserved(data, profile)
    write_json(data / 'recovery/pipeline-completion.json', {'status': 'COMPLETED', 'at': now(),
        'preserved_arms_unchanged': True, 'final_results_sha256': sha(ROOT / 'docs/benchmarks/alloy4fun-results.json')})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--supervise', action='store_true')
    parser.add_argument('--finalize', action='store_true')
    parser.add_argument('--unit-prefix')
    args = parser.parse_args()
    data = args.data.resolve()
    if not data.is_relative_to(ROOT / 'build'):
        raise ValueError('Private benchmark data must stay under build/')
    if args.finalize:
        commands = [
            ['audit_results.py', '--data', str(data), '--output', str(data / 'evidence-audit.json')],
            ['summarize.py', '--data', str(data), '--baselines', str(ROOT.parent / 'lp_baselines')],
            ['plot_results.py']]
        for command in commands:
            subprocess.run([sys.executable, str(ROOT / 'benchmarks/alloy4fun' / command[0]), *command[1:]], check=True)
        return
    prefix = args.unit_prefix or 'alloy-recovery-' + datetime.now().strftime('%Y%m%d%H%M%S')
    if args.supervise:
        supervise(data, prefix)
        return
    if available_memory() < START_RESERVE:
        raise RuntimeError('Less than 10 GiB available; no service started')
    profile_path = data / 'resource-profile.json'
    profile = json.loads(profile_path.read_text())
    verify_preserved(data, profile)
    profile['recovery']['guard_source_sha256'] = sha(__file__)
    write_json(profile_path, profile)
    subprocess.run(['systemd-run', '--user', '--unit=' + prefix + '-supervisor',
        '--working-directory=' + str(ROOT), '--property=MemoryMax=128M',
        '--property=MemorySwapMax=0', '--property=OOMPolicy=kill', '--property=Nice=10',
        '--property=StandardOutput=append:' + str(data / 'recovery/supervisor.log'),
        '--property=StandardError=inherit', sys.executable, str(Path(__file__).resolve()),
        '--data', str(data), '--supervise', '--unit-prefix', prefix], check=True)
    print(json.dumps({'status': 'SUPERVISOR_STARTED', 'unit_prefix': prefix, 'data': str(data)}))


if __name__ == '__main__':
    main()
