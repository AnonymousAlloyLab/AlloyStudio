#!/usr/bin/env python3
"""Finite constrained-host benchmark contract for v0.0.5-alpha.

Each 2/4-logical-CPU scenario runs in a child with verified Linux CPU affinity;
this never changes the invoking process affinity and does not impose a RAM cap.
For all 181 exercises, starter and first known-correct bodies run in both metrics
(724 complete requests). Preserved AP01 and current JVM phases run sequentially;
complete canonical-JSON response hashes must agree, with every response OK.
A legacy adapter removes only the unsupported outer workMillis transport field.
Public payloads, pool ordering and context hashes are unchanged.

The constrained production profile is also exercised through actual loopback
HTTP: public revalidation, concurrent shared/cached checks, distinct edits in
both metrics, and all four behavioral categories on representative exercises.
A 20 ms cooperative limit must return WORK_LIMIT, then a normal request must
succeed in the same JVM. Neither providers nor administrator credentials are
loaded. Staging copies only explicit public DB, web, JAR and class inputs.

Reports retain case IDs, counts, timings, full-response hashes and sampled
process-tree RSS, never model/reference bodies. Inputs and binaries must remain
identical through the run. A failure preserves partial evidence and exits nonzero;
there is no resume or success claim for missing cases. This is finite testing,
not a formal refinement, RAM quota, Windows result, or universal timing bound.
"""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
import argparse
import hashlib
from http.client import HTTPConnection
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts')]
import engine_workers
from engine_workers import EnginePool
from exercise_store import load_store
from runtime_dependencies import JAR_FILES, ProcessBudget, clean_java_environment
import server


class Refused(RuntimeError):
    pass


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True,
                      allow_nan=False).encode('utf-8')


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    with Path(path).open('x', encoding='utf-8') as output:
        json.dump(value, output, indent=2, sort_keys=True)
        output.write('\n')


def inventory(directory, suffix):
    directory = Path(directory)
    found = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise Refused('Linked benchmark input')
        if path.is_file() and path.suffix == suffix:
            found[path.relative_to(directory).as_posix()] = sha(path.read_bytes())
    if not found:
        raise Refused('Empty benchmark input inventory')
    return found


def verify_baseline(report_path, root=ROOT):
    report_path = Path(report_path)
    report = json.loads(report_path.read_bytes())
    expected = {key.removeprefix('engine-classes/'): value for key, value in
                report.get('compiledClasses', {}).items() if key.startswith('engine-classes/')}
    classes = report_path.parent / 'engine-classes'
    if not expected or inventory(classes, '.class') != expected:
        raise Refused('Preserved AP01 classes differ from their recorded inventory')
    if report.get('preservation', {}).get('mismatches') != []:
        raise Refused('Baseline record lacks completed response preservation')
    for name in JAR_FILES:
        relative = 'vendor/acgn/lib/' + name
        if sha((Path(root) / relative).read_bytes()) != report['inputs'].get(relative):
            raise Refused('Runtime JAR differs from the preserved baseline')
    return classes, {'reportSha256': sha(report_path.read_bytes()), 'classes': expected,
                     'priorPreservedResponses': report['preservation']['byteIdenticalResponses']}


def inputs(root=ROOT):
    paths = [Path(root) / name for name in (
        'scripts/benchmark_constrained_runtime.py', 'engine_workers.py', 'execution_profile.py',
        'runtime_dependencies.py', 'server.py', 'portal_routes.py', 'traffic_limits.py',
        'traffic_scheduler.py', 'traffic_http.py', 'traffic_profile.py', 'traffic_identity.py',
        'exercise_store.py', 'admin_auth.py', 'admin_service.py', 'admin_upload.py', 'luna.py',
        'exercises/exercises.sqlite3', 'vendor/acgn/snapshot.json')]
    for directory, pattern in (('engine/src', '*.java'), ('vendor/acgn/src', '*.java'),
                               ('vendor/acgn/lib', '*.jar'), ('web', '*')):
        paths.extend(p for p in (Path(root) / directory).rglob(pattern) if p.is_file())
    return {p.relative_to(root).as_posix(): sha(p.read_bytes()) for p in sorted(set(paths))}


def stage_public(root, destination, classes):
    """Explicit allowlist: never copy a backend tree or deployment configuration."""
    destination = Path(destination)
    destination.mkdir()
    for relative in ('exercises/exercises.sqlite3', *('vendor/acgn/lib/' + name for name in JAR_FILES)):
        source = Path(root) / relative
        if source.is_symlink():
            raise Refused('Linked public staging input')
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    for source, target in ((Path(classes), destination / 'build/engine/classes'),
                           (Path(root) / 'web', destination / 'web')):
        for path in source.rglob('*'):
            if path.is_symlink():
                raise Refused('Linked public staging tree')
        shutil.copytree(source, target)


def choose_cpus(available, count):
    if type(count) is not int or count not in (2, 4) or len(set(available)) < count:
        raise Refused('Requested logical-CPU affinity is unavailable')
    return sorted(set(available))[:count]


def cases(snapshot):
    result = []
    for record in snapshot.exercises.values():
        for kind, body in (('starter', record['starter']), ('correct', snapshot.correct_pools[record['id']][0])):
            for metric in ('canonical', 'ast'):
                payload = server.Portal.feedback_payload(None, record, body, metric, snapshot)
                result.append({'case': record['id'] + '/' + kind + '/' + metric,
                               'exercise': record['id'], 'kind': kind, 'metric': metric,
                               'inputSha256': sha(encoded(payload)), 'request': payload})
    return result


def parity(left, right):
    if len(left) != len(right) or not left:
        raise Refused('Missing paired comparison cases')
    identities = set()
    for before, after in zip(left, right):
        key = (before['case'], before['inputSha256'])
        if key in identities or key != (after['case'], after['inputSha256']):
            raise Refused('Comparison cases are duplicated, changed or reordered')
        identities.add(key)
        if before['status'] != 'ok' or after['status'] != 'ok':
            raise Refused('Incomplete responses cannot establish preserved behavior')
        if before['responseSha256'] != after['responseSha256']:
            raise Refused('Complete response differs for ' + before['case'])
    return {'requests': len(left), 'equalCompleteResponses': len(left),
            'comparison': 'SHA-256 of complete canonical JSON values; no response fields omitted'}


def adjust_frame(value, *, legacy=False, work_millis=None):
    keys = {'protocol', 'incarnation', 'ticket', 'context', 'kind', 'request', 'workMillis'}
    if isinstance(value, dict) and set(value) == keys and value.get('kind') == 'feedback':
        value = dict(value)
        if legacy:
            value.pop('workMillis')
        elif work_millis is not None:
            value['workMillis'] = work_millis
    return value


@contextmanager
def frame_policy(*, legacy=False, work_millis=None):
    original = engine_workers._encoded
    engine_workers._encoded = lambda value: original(adjust_frame(value, legacy=legacy, work_millis=work_millis))
    try:
        yield
    finally:
        engine_workers._encoded = original


def summary(rows):
    values = sorted(row['seconds'] for row in rows)
    return {'requests': len(values), 'medianSeconds': statistics.median(values),
            'p95Seconds': values[max(0, math.ceil(.95 * len(values)) - 1)],
            'maxSeconds': values[-1], 'totalSeconds': sum(values)}


class RssSampler:
    """Linux /proc observations of this benchmark's process tree, not host RSS."""
    def __init__(self):
        self.done = threading.Event()
        self.peak_child = self.peak_tree = self.peak_children = self.samples = 0
        self.thread = threading.Thread(target=self.collect, daemon=True)

    def descendants(self, parent):
        pending, seen = [parent], set()
        while pending:
            pid = pending.pop()
            if pid in seen:
                continue
            seen.add(pid)
            try:
                for task in Path('/proc', str(pid), 'task').iterdir():
                    pending.extend(int(value) for value in (task / 'children').read_text().split())
            except (OSError, ValueError):
                pass
        return seen

    def collect(self):
        while not self.done.is_set():
            sizes = {}
            for pid in self.descendants(os.getpid()):
                try:
                    lines = Path('/proc', str(pid), 'status').read_text().splitlines()
                    sizes[pid] = next(int(line.split()[1]) for line in lines if line.startswith('VmRSS:'))
                except (OSError, ValueError, StopIteration):
                    pass
            children = [value for pid, value in sizes.items() if pid != os.getpid()]
            self.peak_child = max(self.peak_child, sum(children))
            self.peak_tree = max(self.peak_tree, sum(sizes.values()))
            self.peak_children = max(self.peak_children, len(children))
            self.samples += 1
            self.done.wait(.05)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.done.set()
        self.thread.join(2)

    def result(self):
        return {'peakChildrenRssKiB': self.peak_child, 'peakProcessTreeRssKiB': self.peak_tree,
                'peakObservedChildren': self.peak_children, 'samples': self.samples,
                'interpretation': '50 ms /proc samples; not exact peaks or a memory quota'}


def feedback_phase(root, cohort, destination, java, *, legacy=False):
    pool = EnginePool(root, java, feedback_workers=1, startup_timeout=20,
                      java_processors=1, budget=ProcessBudget())
    rows = []
    try:
        with frame_policy(legacy=legacy):
            cold_warm = []
            for _ in range(2):
                started = time.monotonic()
                value = pool.evaluate('feedback', cohort[0]['request'], 12)
                cold_warm.append({'seconds': time.monotonic() - started, 'responseSha256': sha(encoded(value))})
            if cold_warm[0]['responseSha256'] != cold_warm[1]['responseSha256']:
                raise Refused('Cold and warm complete responses differ')
            with Path(destination).open('x') as output:
                for item in cohort:
                    started = time.monotonic()
                    response = pool.evaluate('feedback', item['request'], 12)
                    row = {key: item[key] for key in ('case', 'exercise', 'kind', 'metric', 'inputSha256')}
                    row.update(status=response.get('status'), responseSha256=sha(encoded(response)),
                               seconds=time.monotonic() - started)
                    output.write(json.dumps(row, sort_keys=True) + '\n')
                    output.flush()
                    rows.append(row)
                    if response.get('status') != 'ok':
                        raise Refused('Incomplete feedback for ' + item['case'])
            counts = pool.stats()
    finally:
        closed = pool.close()
        if closed['unreaped'] or closed['starting'] or closed['processBudget']['reserved']:
            raise Refused('Feedback phase did not drain')
    return rows, {'coldSeconds': cold_warm[0]['seconds'], 'warmSeconds': cold_warm[1]['seconds'],
                  'metrics': {metric: summary([r for r in rows if r['metric'] == metric])
                              for metric in ('canonical', 'ast')},
                  'launches': counts['launches'], 'plannedRecycles': counts['recycled'],
                  'processHighWater': counts['processBudget']['highWater']}


def work_limit_phase(root, snapshot, java):
    record = snapshot.exercises['socialMedia-inv4']
    pool = EnginePool(root, java, feedback_workers=1, startup_timeout=20,
                      java_processors=1, budget=ProcessBudget())
    normal = server.Portal.feedback_payload(None, record, record['starter'], 'canonical', snapshot)
    body = ' or '.join('(' + value.strip() + ')' for value in snapshot.correct_pools[record['id']][:30])
    if server.validate_body(body) is not None:
        raise Refused('Costly draft is outside the portal input boundary')
    costly = server.Portal.feedback_payload(None, record, body, 'canonical', snapshot)
    try:
        before = pool.evaluate('feedback', normal, 12)
        worker = pool.workers['feedback'][0]
        pid, launches = worker.process.pid, pool.stats()['launches']
        started = time.monotonic()
        with frame_policy(work_millis=20):
            limited = pool.evaluate('feedback', costly, 12)
        elapsed = time.monotonic() - started
        if (limited.get('status') != 'unsupported'
                or (limited.get('diagnostics') or [{}])[0].get('code') != 'WORK_LIMIT'
                or any(key in limited for key in ('distance', 'comparison', 'canonicalForm', 'operations'))):
            raise Refused('Tiny cooperative budget did not return atomic WORK_LIMIT')
        recovered = pool.evaluate('feedback', normal, 12)
        if (sha(encoded(before)) != sha(encoded(recovered)) or recovered.get('status') != 'ok'
                or pool.workers['feedback'][0].process.pid != pid or pool.stats()['launches'] != launches):
            raise Refused('WORK_LIMIT did not preserve same-worker recovery')
        return {'status': 'PASS', 'cooperativeMillis': 20, 'seconds': elapsed,
                'samePid': True, 'additionalLaunches': 0, 'normalResponsePreserved': True}
    finally:
        drained = pool.close()
        if drained['unreaped'] or drained['starting'] or drained['processBudget']['reserved']:
            raise Refused('WORK_LIMIT phase did not drain')


def http_phase(root, snapshot, java):
    app = server.Portal(('127.0.0.1', 0), root=root, java=java, resource_profile='constrained')
    thread = threading.Thread(target=app.serve_forever, kwargs={'poll_interval': .01}, daemon=True)
    thread.start()
    def request(method, path, body=None, headers=None):
        connection = HTTPConnection('127.0.0.1', app.server_port, timeout=75)
        try:
            data = encoded(body) if body is not None else None
            chosen = dict(headers or {})
            if data is not None:
                chosen['Content-Type'] = 'application/json'
            connection.request(method, path, data, chosen)
            response = connection.getresponse()
            raw = response.read()
            value = json.loads(raw) if raw and 'application/json' in response.getheader('Content-Type', '') else None
            return response.status, response.headers, value
        finally:
            connection.close()
    record = snapshot.exercises['productionLineNew-inv3']
    try:
        first = request('GET', '/api/exercises')
        if first[0] != 200 or request('GET', '/api/exercises', headers={'If-None-Match': first[1]['ETag']})[0] != 304:
            raise Refused('Public revalidation failed')
        with ThreadPoolExecutor(12) as clients:
            visits = list(clients.map(lambda index: request('GET', ('/', '/api/exercises',
                                  '/api/exercises/' + record['id'])[index % 3])[0], range(12)))
            if visits != [200] * 12 or app.engine_pool.stats()['launches'] != 0:
                raise Refused('Page visits launched a JVM or failed')
            payload = {'exerciseId': record['id'], 'body': record['starter'], 'metric': 'canonical', 'revision': 1}
            before = app.scheduler.stats()
            answers = list(clients.map(lambda _: request('POST', '/api/feedback', payload), range(12)))
            shared = app.scheduler.stats()
            if any(status != 200 or value.get('status') != 'ok' for status, _, value in answers):
                raise Refused('Concurrent feedback request failed')
            if shared['computations'] - before['computations'] != 1:
                raise Refused('Identical concurrent checks were not shared/cached')
            repeated = list(clients.map(lambda _: request('POST', '/api/feedback', payload), range(12)))
            if any(status != 200 or value.get('status') != 'ok' for status, _, value in repeated):
                raise Refused('Repeated cached feedback request failed')
            cached = app.scheduler.stats()
            if cached['computations'] != shared['computations']:
                raise Refused('Repeated cached checks recomputed')
        before_edits = app.engine_pool.stats()['launches']
        for metric in ('canonical', 'ast'):
            for spaces in range(1, 5):
                status, _, result = request('POST', '/api/feedback', dict(payload, metric=metric,
                                                   body=record['starter'] + ' ' * spaces, revision=spaces + 1))
                if status != 200 or result.get('status') != 'ok':
                    raise Refused('Distinct edit failed for ' + metric)
        if app.engine_pool.stats()['launches'] != before_edits:
            raise Refused('Ordinary edits unexpectedly launched replacement JVMs')
        behavior = []
        for name in dict.fromkeys((next(iter(snapshot.exercises)), 'socialMedia-inv4', 'productionLineNew-inv3')):
            item = snapshot.exercises[name]
            started = time.monotonic()
            status, _, value = request('POST', '/api/behavior', {'exerciseId': name,
                                      'body': item['starter'], 'revision': 1})
            if status != 200 or value.get('status') != 'ok':
                raise Refused('Behavioral examples unavailable for ' + name)
            categories = value.get('categories', [])
            if {x['id'] for x in categories} != {'both', 'undercoverage', 'overcoverage', 'neither'} or any(
                    len(x['instances']) > 3 for x in categories):
                raise Refused('Behavioral category/instance bounds changed')
            behavior.append({'exercise': name, 'seconds': time.monotonic() - started,
                             'instances': {x['id']: len(x['instances']) for x in categories},
                             'responseSha256': sha(encoded(value))})
        if request('GET', '/admin/')[0] != 404 or app.admin is None or app.execution_profile.workers != 1:
            raise Refused('Constrained profile or default administration admission changed')
        return {'publicVisits': 14, 'visitJvmLaunches': 0, 'sharedRequests': 12,
                'sharedComputations': shared['computations'] - before['computations'],
                'joined': shared['joined'] - before['joined'],
                'cachedChecks': 12, 'cachedComputations': cached['computations'] - shared['computations'],
                'distinctEdits': 8, 'editAdditionalLaunches': 0, 'behavior': behavior,
                'adminServicePreserved': True, 'defaultAdminStatus': 404,
                'workerHighWater': app.engine_pool.stats()['processBudget']['highWater']}
    finally:
        app.shutdown()
        app.server_close()
        thread.join(3)


def child(spec_path):
    spec = json.loads(Path(spec_path).read_bytes())
    selected = choose_cpus(os.sched_getaffinity(0), spec['cpuCount'])
    os.sched_setaffinity(0, selected)
    if sorted(os.sched_getaffinity(0)) != selected:
        raise Refused('CPU affinity was not applied')
    destination = Path(spec['output'])
    destination.mkdir()
    snapshot = load_store(Path(spec['currentRoot']))
    cohort = cases(snapshot)
    if snapshot.exercise_count != 181 or len(cohort) != 724:
        raise Refused('Expected complete 181-exercise, 724-request cohort')
    with RssSampler() as resources:
        baseline, before = feedback_phase(Path(spec['baselineRoot']), cohort, destination / 'baseline.jsonl', spec['java'], legacy=True)
        current, after = feedback_phase(Path(spec['currentRoot']), cohort, destination / 'current.jsonl', spec['java'])
        equality = parity(baseline, current)
        limited = work_limit_phase(Path(spec['currentRoot']), snapshot, spec['java'])
        web = http_phase(Path(spec['currentRoot']), snapshot, spec['java'])
    result = {'status': 'PASS', 'logicalCpuAffinity': selected, 'cpuCount': spec['cpuCount'],
              'profile': {'feedbackWorkers': 1, 'behaviorWorkers': 1, 'adminLanePreserved': True,
                          'javaProcessors': 1, 'heapMiBPerJvm': 256, 'startupSeconds': 20,
                          'feedbackSeconds': 12, 'behaviorSeconds': 30},
              'exerciseIds': list(snapshot.exercises), 'baseline': before, 'current': after,
              'preservation': equality, 'workLimit': limited, 'http': web, 'resources': resources.result()}
    write_json(destination / 'summary.json', result)
    print(json.dumps({'cpuCount': spec['cpuCount'], 'status': 'PASS', 'requests': len(cohort)}), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--baseline-report', type=Path)
    parser.add_argument('--java', default=shutil.which('java'))
    parser.add_argument('--child', type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.child:
        child(args.child)
        return 0
    if not hasattr(os, 'sched_setaffinity') or not Path('/proc/self/status').is_file():
        raise Refused('This benchmark requires Linux CPU affinity and /proc')
    if not args.java or args.baseline_report is None:
        parser.error('--baseline-report and a Java runtime are required')
    choose_cpus(os.sched_getaffinity(0), 4)
    baseline_classes, baseline_record = verify_baseline(args.baseline_report)
    frozen = inputs()
    current_classes = ROOT / 'build/engine/classes'
    current_binaries = inventory(current_classes, '.class')
    base = ROOT / 'build/v005-refinement/bench'
    base.mkdir(parents=True, exist_ok=True)
    import tempfile
    run = Path(tempfile.mkdtemp(prefix=time.strftime('%Y%m%dT%H%M%SZ-', time.gmtime()), dir=base))
    write_json(run / 'manifest.json', {'contract': __doc__, 'inputs': frozen,
                'currentClasses': current_binaries, 'baseline': baseline_record,
                'legacyAdapter': 'Only outer workMillis stripped; request/context unchanged'})
    stage_public(ROOT, run / 'current-runtime', current_classes)
    stage_public(ROOT, run / 'baseline-runtime', baseline_classes)
    results = []
    environment = clean_java_environment()
    environment['OPENAI_DISABLED'] = '1'
    for count in (2, 4):
        spec = run / ('cpu-' + str(count) + '-input.json')
        write_json(spec, {'cpuCount': count, 'currentRoot': str(run / 'current-runtime'),
                         'baselineRoot': str(run / 'baseline-runtime'), 'java': args.java,
                         'output': str(run / ('cpu-' + str(count)))})
        with (run / ('cpu-' + str(count) + '.log')).open('x') as log:
            completed = subprocess.run([sys.executable, '-I', str(Path(__file__).resolve()), '--child', str(spec)],
                                       cwd=ROOT, env=environment, stdout=log, stderr=subprocess.STDOUT, timeout=1200)
        if completed.returncode:
            raise Refused('Scenario failed; partial evidence retained in ' + str(run.relative_to(ROOT)))
        results.append(json.loads((run / ('cpu-' + str(count)) / 'summary.json').read_bytes()))
    if (inputs() != frozen or inventory(current_classes, '.class') != current_binaries
            or verify_baseline(args.baseline_report)[1] != baseline_record):
        raise Refused('Benchmark inputs changed; partial evidence retained in ' + str(run.relative_to(ROOT)))
    report = {'schemaVersion': 1, 'status': 'PASS', 'manifestSha256': sha((run / 'manifest.json').read_bytes()),
              'scenarios': results, 'scope': 'Finite Linux logical-CPU affinity experiments; no RAM cap or Windows execution'}
    write_json(run / 'summary.json', report)
    print(json.dumps({'status': 'PASS', 'evidence': str(run.relative_to(ROOT)),
                      'comparedResponses': sum(x['preservation']['requests'] for x in results)}))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (Refused, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print(json.dumps({'status': 'FAIL', 'reason': str(error)}), file=sys.stderr)
        raise SystemExit(1)
