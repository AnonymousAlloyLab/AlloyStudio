#!/usr/bin/env python3
"""Measure the AP01-C01 work budget against the bundled catalogue.

Legitimate drafts (each exercise's starter, oracle, benchmark draft and its
longest known-correct answers, in both metrics) are measured at the production budget.
Adversarial families are then run at the production budget to record how
quickly they reach WORK_LIMIT. Full rows stay in owned build/ scratch; with
--record-evidence a small sealed summary is appended under
closure/patch-contracts/evidence/. Abstract units are not a wall-clock proof.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import statistics
import subprocess
import sys
import tempfile
import tarfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts')]
import evidence_store  # noqa: E402
from build_engine import compile_engine  # noqa: E402
import server  # noqa: E402
from runtime_dependencies import clean_java_environment  # noqa: E402

def hashes(paths, root=ROOT):
    """Bind explicit public inputs/artifacts; never read local deployment config."""
    return {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(paths)}


def source_inputs():
    paths = [ROOT / name for name in ('scripts/calibrate_work_budget.py', 'scripts/build_engine.py',
             'engine/test/WorkCalibration.java', 'vendor/acgn/snapshot.json', 'runtime_dependencies.py',
             'server.py', 'exercises/exercises.sqlite3',
             'benchmarks/alloy4fun/protocol/quality-selection-181.json')]
    for directory, pattern in (('engine/src', '*.java'), ('vendor/acgn/src', '*.java'),
                                ('vendor/acgn/lib', '*.jar')):
        paths.extend((ROOT / directory).rglob(pattern))
    return hashes(paths)


def production_constants():
    source = (ROOT / 'engine/src/live/LiveFeedback.java').read_text()
    values = {}
    for name in ('WORK_BUDGET', 'ALLOCATION_LIMIT'):
        match = re.search(r'static final long ' + name + r' = ([0-9_]+)L;', source)
        values[name] = int(match.group(1).replace('_', ''))
    return values


def catalogue():
    with sqlite3.connect(f'file:{ROOT}/exercises/exercises.sqlite3?mode=ro', uri=True) as db:
        exercises = db.execute('select id, environment_before, environment_after, predicate_header, '
                               'predicate_name, starter from exercises order by ordinal').fetchall()
        for row in exercises:
            pool = [body for (body,) in db.execute(
                'select body from solutions where exercise_id=? order by ordinal', (row[0],))]
            oracle = db.execute("select body from solutions where exercise_id=? and kind='oracle' "
                                'order by ordinal', (row[0],)).fetchone()[0]
            yield row, pool, oracle


def request(row, pool, body, metric):
    exercise, before, after, header, predicate, _ = row
    record = {'environmentBefore': before, 'environmentAfter': after, 'predicateHeader': header}
    return {'studentSource': server.model(record, body), 'referenceBodies': pool,
            'referencePrefix': before + header + '{\n', 'referenceSuffix': '\n}' + after,
            'predicate': predicate, 'metric': metric}


def rows(per_exercise):
    selection = json.loads((ROOT / 'benchmarks/alloy4fun/protocol/quality-selection-181.json').read_text())
    benchmark = {case['exercise_id']: case['learner_body'] for case in selection['cases']}
    for row, pool, oracle in catalogue():
        correct = sorted((body for body in pool if body != oracle), key=len, reverse=True)[:per_exercise]
        drafts = [('starter', row[5]), ('oracle', oracle), ('benchmark', benchmark.get(row[0]))]
        drafts += [('correct', body) for body in correct]
        for kind, body in drafts:
            if body and server.validate_body(body) is None:
                for metric in ('canonical', 'ast'):
                    yield {'id': row[0], 'kind': kind, 'metric': metric, 'bytes': len(body.encode()),
                           'pool': len(pool), 'request': request(row, pool, body, metric)}


def adversarial():
    """Valid drafts with superlinear canonical work, from the catalogue itself."""
    wanted = {'socialMedia-inv4', 'socialMedia-inv7'}
    for row, pool, _ in catalogue():
        if row[0] not in wanted:
            continue
        joined = lambda n, separator: f' {separator}\n'.join('(' + body.strip() + ')' for body in pool[:n])
        drafts = [('joined-and-19', joined(19, 'and')), ('joined-and-40', joined(40, 'and')),
                  ('joined-or-30', joined(30, 'or'))]
        if row[0] == 'socialMedia-inv4':
            drafts.append(('nested-quantifiers-30', ' and '.join(
                '(all u%d : posts.Ad | u%d.posts in Ad' % (i, i) for i in range(30)) + ')' * 30))
        for kind, body in drafts:
            if server.validate_body(body) is None:
                for metric in ('canonical', 'ast'):
                    yield {'id': row[0], 'kind': kind, 'metric': metric, 'bytes': len(body.encode()),
                           'pool': len(pool), 'request': request(row, pool, body, metric)}


def run(scratch, name, items, fuel, classpath):
    requests, results = scratch / (name + '-requests.jsonl'), scratch / (name + '-results.jsonl')
    with requests.open('w') as stream:
        count = 0
        for item in items:
            stream.write(json.dumps(item) + '\n')
            count += 1
    temporary = scratch / (name + '-java-tmp')
    temporary.mkdir()
    subprocess.run(['java', '-Xmx256m', '-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(temporary), '-cp',
                    str(scratch / 'classes') + os.pathsep + classpath, 'live.WorkCalibration',
                    str(requests), str(results), fuel], check=True, cwd=ROOT, timeout=7200,
                   stdout=subprocess.DEVNULL, env=clean_java_environment())
    rows = [json.loads(line) for line in results.read_text().splitlines()]
    if len(rows) != count:
        raise RuntimeError('Calibration result count does not match its request inventory.')
    return rows


def baseline(scratch, revision):
    """Build only public tracked Java/build inputs from an immutable git commit."""
    commit = subprocess.run(['git', 'rev-parse', '--verify', revision + '^{commit}'], cwd=ROOT,
                            text=True, capture_output=True, check=True).stdout.strip()
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise RuntimeError('Expected a complete baseline commit identifier.')
    archive = scratch / 'baseline.tar'
    with archive.open('xb') as stream:
        subprocess.run(['git', 'archive', '--format=tar', commit, 'engine/src', 'vendor/acgn',
                        'scripts/build_engine.py', 'runtime_dependencies.py', 'traffic_limits.py'],
                       cwd=ROOT, stdout=stream, check=True, timeout=60)
    directory = scratch / 'baseline'
    directory.mkdir()
    with tarfile.open(archive) as stream:
        # Only ordinary tracked files beneath the explicitly selected public
        # paths are materialized; no linked checkout or local config is copied.
        for member in stream.getmembers():
            if not (member.isfile() or member.isdir()):
                raise RuntimeError('Baseline contains a non-regular archive entry.')
            relative = Path(member.name)
            if relative.is_absolute() or any(part in ('', '.', '..') for part in relative.parts):
                raise RuntimeError('Baseline contains an unsafe archive path.')
            target = directory / relative
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with stream.extractfile(member) as source, target.open('xb') as destination:
                    shutil.copyfileobj(source, destination)
    subprocess.run([sys.executable, '-E', '-s', str(directory / 'scripts/build_engine.py'),
                    '--root', str(directory), '--output', str(directory / 'classes')],
                   cwd=directory, check=True, timeout=240, stdout=subprocess.DEVNULL)
    inputs = hashes((path for path in directory.rglob('*')
                     if path.is_file() and 'classes' not in path.relative_to(directory).parts
                     and '__pycache__' not in path.relative_to(directory).parts), directory)
    classes = hashes((directory / 'classes').rglob('*.class'), directory)
    return os.pathsep.join((str(directory / 'classes'), str(directory / 'vendor/acgn/lib/*'))), {
        'commit': commit, 'inputs': inputs, 'compiledClasses': classes}


def preservation(current, previous):
    if len(current) != len(previous):
        raise RuntimeError('Baseline and current result counts differ.')
    mismatches = []
    for index, (left, right) in enumerate(zip(current, previous)):
        if any(left[key] != right[key] for key in ('id', 'kind', 'metric', 'requestSha256')):
            raise RuntimeError('Baseline and current request inventories differ.')
        if left['responseSha256'] != right['responseSha256']:
            mismatches.append({'index': index, 'id': left['id'], 'kind': left['kind'], 'metric': left['metric']})
    return {'requests': len(current), 'byteIdenticalResponses': len(current) - len(mismatches),
            'mismatches': mismatches, 'comparison': 'SHA-256 of complete Java UTF-8 JSON responses'}


def reuse_catalogue(scratch, previous, items):
    """Retain a completed phase only after fresh class and request-byte equality.

    A later baseline/build failure does not invalidate completed measurements.
    The original directory is read-only; a new run receives independently bound
    copies, and mismatched or incomplete phases are refused.
    """
    previous = Path(previous).resolve(strict=True)
    previous.relative_to(ROOT / 'build/work-calibration')
    for name in ('engine-classes', 'classes'):
        old, fresh = previous / name, scratch / name
        if hashes(old.rglob('*.class'), old) != hashes(fresh.rglob('*.class'), fresh):
            raise RuntimeError('Completed calibration classes do not match the freshly compiled engine/driver.')
    count = 0
    with (previous / 'catalogue-requests.jsonl').open('rb') as old, \
            (scratch / 'catalogue-requests.jsonl').open('xb') as fresh:
        for item in items:
            encoded = (json.dumps(item) + '\n').encode()
            if old.readline() != encoded:
                raise RuntimeError('Completed calibration requests do not match the current cohort.')
            fresh.write(encoded)
            count += 1
        if old.read(1):
            raise RuntimeError('Completed calibration contains extra requests.')
    data = (previous / 'catalogue-results.jsonl').read_bytes()
    measured = [json.loads(line) for line in data.splitlines()]
    if len(measured) != count:
        raise RuntimeError('Completed calibration result count does not match its cohort.')
    limits = production_constants()
    with (scratch / 'catalogue-requests.jsonl').open() as requests:
        for result, line in zip(measured, requests):
            item = json.loads(line)
            if any(result[key] != item[key] for key in ('id', 'kind', 'metric', 'bytes', 'pool')):
                raise RuntimeError('Completed calibration result order does not match its cohort.')
            if any(not re.fullmatch('[0-9a-f]{64}', result[key])
                   for key in ('requestSha256', 'responseSha256')):
                raise RuntimeError('Completed calibration lacks complete request/response hashes.')
            # A reused run may have been invoked with a different fuel. Only
            # fully completed work fitting today's compiled production limits
            # can stand in for execution at those limits.
            if (result.get('status') != 'ok'
                    or type(result.get('units')) is not int or not 0 <= result['units'] <= limits['WORK_BUDGET']
                    or type(result.get('allocated')) is not int
                    or not 0 <= result['allocated'] <= limits['ALLOCATION_LIMIT']):
                raise RuntimeError('Completed calibration does not fit the current production limits.')
    with (scratch / 'catalogue-results.jsonl').open('xb') as output:
        output.write(data)
    return measured, {'source': str(previous.relative_to(ROOT)), 'requests': count,
                      'validation': 'fresh engine/driver class equality and complete regenerated request-byte equality'}


def summarize(results):
    summary = {}
    for metric in ('canonical', 'ast'):
        selected = [row for row in results if row['metric'] == metric]
        units = sorted(row['units'] for row in selected)
        summary[metric] = {
            'requests': len(selected),
            'statuses': {status: sum(row['status'] == status for row in selected)
                         for status in sorted({row['status'] for row in selected})},
            'maxUnits': units[-1], 'p99Units': units[int(len(units) * .99)],
            'medianUnits': int(statistics.median(units)),
            'maxAllocated': max(row['allocated'] for row in selected),
            'maxSeconds': round(max(row['seconds'] for row in selected), 3),
            'heaviest': [{key: row[key] for key in ('id', 'kind', 'bytes', 'pool', 'units')}
                         for row in sorted(selected, key=lambda row: -row['units'])[:5]]}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--per-exercise', type=int, default=10, help='Longest correct answers per exercise')
    parser.add_argument('--record-evidence', action='store_true', help='Append a sealed summary to the repository')
    parser.add_argument('--baseline-ref', help='Compare complete public responses to a tracked git commit')
    parser.add_argument('--reuse-catalogue', type=Path,
                        help='Reuse a completed phase only after fresh class and exact request-byte checks')
    args = parser.parse_args()
    if args.per_exercise < 0:
        parser.error('--per-exercise must be nonnegative.')
    if not shutil.which('java') or not shutil.which('javac'):
        parser.error('Provide a JDK 17 or newer.')
    base = ROOT / 'build/work-calibration'
    base.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix=time.strftime('%Y%m%dT%H%M%SZ-', time.gmtime()), dir=base))
    inputs = source_inputs()
    # Compile the measured tree from these sources; a stale global build must
    # never masquerade as evidence for the current work-budget charge sites.
    engine_classes = compile_engine(ROOT, output=scratch / 'engine-classes')
    classpath = os.pathsep.join((str(engine_classes), str(ROOT / 'vendor/acgn/lib/*')))
    (scratch / 'classes').mkdir()
    subprocess.run(['javac', '-encoding', 'UTF-8', '--release', '17', '-cp', classpath, '-d',
                    str(scratch / 'classes'), str(ROOT / 'engine/test/WorkCalibration.java')], check=True,
                   env=clean_java_environment())
    constants = production_constants()
    reuse_report = None
    if args.reuse_catalogue:
        legitimate, reuse_report = reuse_catalogue(scratch, args.reuse_catalogue, rows(args.per_exercise))
    else:
        legitimate = run(scratch, 'catalogue', rows(args.per_exercise), str(constants['WORK_BUDGET']), classpath)
    preservation_report = None
    if args.baseline_ref:
        baseline_classpath, baseline_info = baseline(scratch, args.baseline_ref)
        previous = run(scratch, 'baseline', rows(args.per_exercise), 'baseline', baseline_classpath)
        preservation_report = preservation(legitimate, previous) | {'baseline': baseline_info}
    attacks = run(scratch, 'adversarial', adversarial(), str(constants['WORK_BUDGET']), classpath)
    if source_inputs() != inputs:
        raise RuntimeError('Calibration inputs changed during execution; evidence was not sealed.')
    measured = summarize(legitimate)
    report = {'schemaVersion': 2, 'constants': constants, 'catalogue': measured,
              'inputs': inputs,
              'compiledClasses': hashes([*engine_classes.rglob('*.class'),
                                          *(scratch / 'classes').rglob('*.class')], scratch),
              'artifacts': hashes(scratch.glob('*.jsonl'), scratch),
              'javaVersion': subprocess.run(['java', '-version'], text=True, capture_output=True,
                                            check=True, timeout=10).stderr.strip(),
              'parameters': {'perExercise': args.per_exercise, 'heapMiB': 256, 'activeProcessors': 2},
              'preservation': preservation_report,
              'reusedCatalogue': reuse_report,
              'headroom': {metric: round(constants['WORK_BUDGET'] / measured[metric]['maxUnits'], 2)
                           for metric in measured},
              'allocationHeadroom': round(constants['ALLOCATION_LIMIT']
                                          / max(1, max(m['maxAllocated'] for m in measured.values())), 2),
              'adversarial': [{key: row[key] for key in ('id', 'kind', 'metric', 'bytes', 'units', 'allocated',
                                                         'status', 'code')} | {'seconds': round(row['seconds'], 3)}
                              for row in attacks],
              'scratch': str(scratch.relative_to(ROOT)),
              'interpretation': 'Abstract work units on this host; not a wall-clock, RSS or liveness guarantee.'}
    (scratch / 'summary.json').write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    if args.record_evidence:
        directory = evidence_store.new_directory(ROOT, 'patch-contracts', 'work-calibration')
        entries = {'summary.json': evidence_store.append(
            directory, 'summary.json', (json.dumps(report, indent=2, sort_keys=True) + '\n').encode())}
        report['evidence'] = str(directory.relative_to(ROOT))
        report['evidenceRoot'] = evidence_store.seal(directory, entries)
    print(json.dumps({key: report[key] for key in ('constants', 'headroom', 'allocationHeadroom', 'scratch')}
                     | ({'evidence': report['evidence']} if 'evidence' in report else {}), sort_keys=True))
    unbounded_failures = [row for row in legitimate if row['status'] != 'ok']
    return 1 if unbounded_failures or (preservation_report and preservation_report['mismatches']) else 0


if __name__ == '__main__':
    raise SystemExit(main())
