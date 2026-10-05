#!/usr/bin/env python3
"""Bounded HTTP/projector regression against the archived one-shot baseline.

Only a synthetic corpus and copied runtime/public assets are staged. No private
deployment configuration is read, no provider is contacted, and no source file
is rewritten. The archived Python baseline and both current modes use the same
current compiled Java artifacts: this is not a historical engine benchmark.
Current arms explicitly admit the synthetic loopback administrator network, so
this compares permitted-network business behavior. AP01's intentional default
network denial is covered separately by test_ap01_http.py, not normalized away.
"""
from contextlib import contextmanager
import hashlib
from http.client import HTTPConnection
import importlib.util
import json
import os
from pathlib import Path
import platform
import shutil
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
sys.path.insert(0, str(ROOT / 'scripts'))
import runtime_dependencies
import server
import traffic_observation as observer
from exercise_store import load_store, migrate_legacy
from scripts.import_correct_pools import build_document
from scripts.import_exercises import build_catalogue

BASELINE = 'closure/traffic-refinement/observation-baseline/server.py'
MODEL = '''sig Node { adj: set Node }
pred inv1 { some Node }
pred inv1c { no iden & adj }
check correct { inv1 <=> inv1c }
pred under { inv1 and !inv1c }
pred over { !inv1 and inv1c }
run over
run under
'''
SOURCES = ('server.py', 'traffic_http.py', 'traffic_profile.py', 'traffic_limits.py',
           'traffic_decode.py', 'traffic_identity.py', 'portal_routes.py', 'engine_workers.py',
           'traffic_scheduler.py', 'runtime_dependencies.py', 'execution_profile.py',
           'luna.py', 'admin_auth.py', 'admin_service.py', 'admin_upload.py', 'admin_luna.py',
           'exercise_store.py', 'exercise_sql.py', 'scripts/import_exercises.py',
           'scripts/import_correct_pools.py', 'scripts/traffic_observation.py',
           'closure/traffic-refinement/observation-spec.json',
           'closure/traffic-refinement/observation-baseline.json',
           'scripts/check_ingress_functional_invariance.py',
           'tests/test_ingress_functional_invariance.py')


def encoded(value):
    return json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_identity(root):
    files = {name: sha((root / name).read_bytes()) for name in SOURCES if (root / name).is_file()}
    files[BASELINE] = sha((root / BASELINE).read_bytes())
    for path in sorted((root / 'web').rglob('*')):
        if path.is_file() and path.suffix in ('.js', '.css', '.html', '.json'):
            files[path.relative_to(root).as_posix()] = sha(path.read_bytes())
    for name in runtime_dependencies.JAR_FILES:
        path = root / 'vendor/acgn/lib' / name
        files[path.relative_to(root).as_posix()] = sha(path.read_bytes())
    for path in sorted((root / 'build/engine/classes').rglob('*.class')):
        files[path.relative_to(root).as_posix()] = sha(path.read_bytes())
    return files


def staged_root(root, destination):
    """Synthetic exercise with two equivalent correct expressions plus oracle."""
    corpus = destination / 'source/classified-data/graphs'
    (corpus / 'under').mkdir(parents=True)
    (corpus / 'under/fixture_inv1.als').write_text(MODEL)
    (corpus / 'correct').mkdir()
    for number, correct in enumerate(('no iden & adj', 'all n: Node | n not in n.adj')):
        (corpus / f'correct/fixture{number}_inv1.als').write_text(
            MODEL.replace('pred inv1 { some Node }', 'pred inv1 { ' + correct + ' }'))
    catalogue = build_catalogue(destination / 'source')
    pools = build_document(catalogue, destination / 'source')
    (destination / 'exercises').mkdir()
    (destination / 'exercises/catalogue.json').write_bytes(encoded(catalogue))
    (destination / 'exercises/correct-pools.json').write_bytes(encoded(pools))
    migrate_legacy(destination)
    shutil.copytree(root / 'web', destination / 'web')
    shutil.copytree(root / 'build/engine/classes', destination / 'build/engine/classes')
    libraries = destination / 'vendor/acgn/lib'
    libraries.mkdir(parents=True)
    for name in runtime_dependencies.JAR_FILES:
        shutil.copy2(root / 'vendor/acgn/lib' / name, libraries / name)
    # These names are deliberately absent; only this new owned folder is tested.
    if any((destination / name).exists() for name in ('admin.local.json', 'openai.local.json', 'secrets')):
        raise ValueError('Synthetic deployment unexpectedly contains private configuration')
    return load_store(destination)


def archived_module(root):
    observer.check_baseline(root)
    name = 'alloy_ingress_archived_baseline'
    spec = importlib.util.spec_from_file_location(name, root / BASELINE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@contextmanager
def app_for(module, root, mode):
    kwargs = dict(root=root, timeout=12, workers=2)
    if mode != 'archived-oneshot':
        kwargs['engine_mode'] = mode
        # Keep the archived API untouched. Match its permitted-network business
        # precondition without removing the new production default-deny policy.
        kwargs['admin_networks'] = ('127.0.0.1/32',)
    app = module.Portal(('127.0.0.1', 0), **kwargs)
    thread = threading.Thread(target=lambda: app.serve_forever(poll_interval=.01), daemon=True)
    thread.start()
    try:
        yield app
    finally:
        app.shutdown()
        app.server_close()
        thread.join(2)
        if thread.is_alive():
            raise RuntimeError('HTTP server thread did not stop')


def request(app, method, path, data=None, *, headers=None):
    body = None if data is None else encoded(data)
    supplied = {} if headers is None else dict(headers)
    if body is not None:
        supplied.setdefault('Content-Type', 'application/json')
    connection = HTTPConnection('127.0.0.1', app.server_port, timeout=40)
    try:
        connection.request(method, path, body=body, headers=supplied)
        response = connection.getresponse()
        raw = response.read(8 * 1048576 + 1)
        if len(raw) > 8 * 1048576:
            raise ValueError('HTTP response exceeded the validation bound')
        content_type = response.getheader('Content-Type', '')
        data = json.loads(raw) if raw and content_type.startswith('application/json') else None
        return dict(httpStatus=response.status, contentType=content_type, body=data,
                    bodySha256=sha(raw), bodyBytes=len(raw),
                    cacheControl=response.getheader('Cache-Control', ''),
                    etag=response.getheader('ETag'), raw=raw)
    finally:
        connection.close()


def cases(record, pool):
    variants = [('incorrect', record['starter']), ('correct', pool[0]),
                ('unicode', '// 😀 repeated occurrence\r\n' + record['starter']),
                ('invalid', 'some __MISSING_DECLARATION__'), ('empty', '')]
    result = []
    revision = 1
    for label, body in variants:
        for metric in ('canonical', 'ast'):
            payload = dict(exerciseId=record['id'], body=body, revision=revision, metric=metric)
            result.append((label + '/' + metric, '/api/feedback', 'feedback', payload,
                           dict(body=body, poolSize=len(pool), metric=metric, requestedMetric=metric,
                                exerciseId=record['id'], revision=revision)))
            revision += 1
    for label, body in variants[:2]:
        payload = dict(exerciseId=record['id'], body=body, revision=revision)
        result.append((label + '/behavior', '/api/behavior', 'behavior', payload,
                       dict(body=body, exerciseId=record['id'], revision=revision)))
        revision += 1
    payload = dict(exerciseId=record['id'], body=record['starter'], revision=revision, metric='canonical')
    result.append(('disabled-provider', '/api/explain', 'explanation', payload,
                   dict(body=record['starter'], exerciseId=record['id'], revision=revision,
                        requestedMetric='canonical')))
    return result


def sample_arm(module, runtime_root, mode, snapshot):
    record = next(iter(snapshot.exercises.values()))
    pool = snapshot.correct_pools[record['id']]
    rows, observations = [], {}
    with app_for(module, runtime_root, mode) as app:
        for name, path, kind in [('health', '/api/health', 'health'),
                                 ('catalogue', '/api/exercises', 'catalogue'),
                                 ('detail', '/api/exercises/' + record['id'], 'exercise'),
                                 ('admin-disabled', '/api/admin/session', 'admin')]:
            response = request(app, 'GET', path)
            errors = observer.validate_observation(kind, response['body'])
            semantic = observer.semantic_observation(kind, response['body']) if not errors else b''
            rows.append(dict(case=name, kind=kind, httpStatus=response['httpStatus'],
                             semanticSha256=sha(semantic), errors=errors))
            observations[name] = response['body']
            if mode != 'archived-oneshot' and name in ('catalogue', 'detail'):
                cached = request(app, 'GET', path, headers={'If-None-Match': response['etag']})
                if cached['httpStatus'] != 304 or cached['bodyBytes'] != 0:
                    raise ValueError('Public entity revalidation changed')
        for path in ('/', '/app.js', '/instance-graph.js', '/admin/', '/dashboard/'):
            response = request(app, 'GET', path)
            rows.append(dict(case='asset' + path, kind='asset', httpStatus=response['httpStatus'],
                             semanticSha256=sha(encoded([response['contentType'], response['bodySha256'], response['bodyBytes']])), errors=[]))
        forbidden = request(app, 'POST', '/api/admin/prepare', {})
        if forbidden['httpStatus'] != 404 or 'no-store' not in forbidden['cacheControl']:
            raise ValueError('Disabled administration was not privately rejected')
        rows.append(dict(case='admin-rejected', kind='admin', httpStatus=forbidden['httpStatus'],
                         semanticSha256=sha(encoded(forbidden['body'])), errors=[]))
        for name, path, kind, payload, context in cases(record, pool):
            response = request(app, 'POST', path, payload)
            errors = observer.validate_observation(kind, response['body'], context)
            semantic = observer.semantic_observation(kind, response['body'], context) if not errors else b''
            if 'no-store' not in response['cacheControl']:
                errors.append({'code': 'PRIVATE_CACHE_HEADER', 'path': '$'})
            expected = 'invalid' if name.startswith(('invalid/', 'empty/')) else 'disabled' if name == 'disabled-provider' else 'ok'
            if response['body'].get('status') != expected:
                errors.append({'code': 'UNEXPECTED_STATUS', 'path': '$.status'})
            if name.startswith('correct/') and kind == 'feedback' and response['body'].get('distance') != 0:
                errors.append({'code': 'CORRECT_POOL_DISTANCE', 'path': '$.distance'})
            rows.append(dict(case=name, kind=kind, httpStatus=response['httpStatus'],
                             status=response['body'].get('status'), expectedStatus=expected,
                             semanticSha256=sha(semantic), errors=errors))
            observations[name] = response['body']
        reuse = {}
        if mode != 'archived-oneshot':
            first = cases(record, pool)[0]
            before = app.engine_pool.stats()
            scheduled_before = app.scheduler.stats()
            repeated = request(app, 'POST', first[1], first[3])
            after = app.engine_pool.stats()
            scheduled_after = app.scheduler.stats()
            if repeated['body'].get('status') != 'ok' or before['launches'] != after['launches']:
                raise ValueError('Identical successful feedback launched an extra worker')
            if scheduled_before['computations'] != scheduled_after['computations']:
                raise ValueError('Identical successful feedback repeated the engine computation')
            if mode == 'persistent' and not (after['launches'] > 0 and after['completed'] > 0):
                raise ValueError('Persistent validation did not execute real worker requests')
            reuse = dict(launches=after['launches'], completed=after['completed'], repeatedAddedLaunches=0,
                         repeatedAddedComputations=0, cacheHits=scheduled_after['cacheHits'])
            issued = request(app, 'POST', '/api/channel', {})
            channel = issued['body'].get('channel')
            if issued['httpStatus'] != 200 or type(channel) is not str or len(channel) != 43:
                raise ValueError('Editing channel issuance failed')
            cancelled = request(app, 'POST', '/api/cancel', {'channel': channel, 'revision': 1})
            if cancelled['httpStatus'] != 200 or cancelled['body'] != {'status':'ok'}:
                raise ValueError('Editing channel cancellation failed')
    return dict(arm=mode, rows=rows, reuse=reuse), observations


def compare_arms(arms):
    """A matching failure remains a failure; all expected rows must be present."""
    if [arm['arm'] for arm in arms] != ['archived-oneshot', 'oneshot', 'persistent']:
        return {'status':'FAIL','reason':'ARM_INVENTORY'}
    expected = [row['case'] for row in arms[0]['rows']]
    failures = []
    for arm in arms:
        names = [row['case'] for row in arm['rows']]
        if names != expected or len(set(names)) != len(names):
            failures.append(dict(arm=arm['arm'], code='CASE_INVENTORY'))
        for row in arm['rows']:
            if row['errors']:
                failures.append(dict(arm=arm['arm'], case=row['case'], code='INVALID_OBSERVATION'))
            if row.get('expectedStatus') is not None and row.get('status') != row['expectedStatus']:
                failures.append(dict(arm=arm['arm'], case=row['case'], code='UNEXPECTED_STATUS'))
        for baseline, row in zip(arms[0]['rows'], arm['rows']):
            if (row['httpStatus'],row['semanticSha256']) != (baseline['httpStatus'],baseline['semanticSha256']):
                failures.append(dict(arm=arm['arm'], case=row['case'], code='OBSERVATION_MISMATCH'))
    return dict(status='PASS' if not failures else 'FAIL', casesPerArm=len(expected), failures=failures)


def run(root=ROOT, output=None):
    if os.environ.get('OPENAI_DISABLED') != '1':
        raise ValueError('Run with OPENAI_DISABLED=1; providers are outside this check')
    root = Path(root).resolve()
    owned = root / 'build/trf-closure/trf01-regressions'
    owned.mkdir(parents=True, exist_ok=True)
    before = source_identity(root)
    baseline = archived_module(root)
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='functional-', dir=owned) as directory:
        runtime_root = Path(directory)
        snapshot = staged_root(root, runtime_root)
        arms = []
        for name, module in [('archived-oneshot',baseline),('oneshot',server),('persistent',server)]:
            arm, _ = sample_arm(module,runtime_root,name,snapshot)
            arms.append(arm)
            print(json.dumps({'arm':name,'cases':len(arm['rows']),'errors':sum(bool(r['errors']) for r in arm['rows'])}),flush=True)
    comparison = compare_arms(arms)
    stable = before == source_identity(root)
    if not stable:
        comparison['status'] = 'FAIL'
        comparison.setdefault('failures',[]).append({'code':'SOURCE_OR_BINARY_MUTATION'})
    report = dict(schemaVersion=1,kind='bounded-http-projector-and-worker-regression',
        status=comparison['status'],baselineCommit='e75b20419f8f92c2adc59ce083008ec897434cb1',
        sources=before,sourceIdentitySha256=sha(encoded(before)),sourceIdentityStable=stable,
        execution=dict(python=platform.python_version(),platform=platform.platform(),
                       elapsedSeconds=round(time.monotonic()-started,6),providersDisabled=True),
        preconditions=dict(currentAdministratorNetworks=['127.0.0.1/32'],
                           archivedAdministratorNetworkPolicy='unchanged',
                           administratorConfiguration='absent in every synthetic arm',
                           comparisonScope='permitted-network business behavior',
                           defaultDenyRegression='tests/test_ap01_http.py'),
        arms=arms,comparison=comparison,
        limitations=['Synthetic single exercise; all-181 checks and enabled administrator tests are separate.',
                     'Archived Python baseline and current modes use identical current compiled Java artifacts.',
                     'Finite successful requests and deterministic invalid drafts; not arbitrary-history equivalence.',
                     'Current arms explicitly admit only the synthetic loopback administrator network; intentional default-deny differences are tested separately, not ignored by comparison.',
                     'Linux execution only; not native Windows/macOS or deployed proxy validation.',
                     'No private deployment configuration or provider credentials are copied/read.'])
    target = owned / 'functional-invariance.json' if output is None else Path(output)
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(json.dumps(report,sort_keys=True,indent=2)+'\n')
    return report


if __name__ == '__main__':
    try:
        report = run()
        print(json.dumps({'status':report['status'],'casesPerArm':report['comparison']['casesPerArm'],
                          'report':'build/trf-closure/trf01-regressions/functional-invariance.json'}))
        raise SystemExit(0 if report['status']=='PASS' else 1)
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({'status':'FAIL','errorType':type(error).__name__}))
        raise SystemExit(1)
