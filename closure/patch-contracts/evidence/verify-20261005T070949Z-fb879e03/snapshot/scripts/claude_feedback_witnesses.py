#!/usr/bin/env python3
"""Bounded source/mocked-transition witnesses; never launch Java or an HTTP server.

These tests execute production Python methods with named controlled substitutes.
They do not reproduce canonical slow inputs, certify current CI, or prove liveness.
No private configuration file is opened; administration uses a synthetic provider.
"""
import argparse
import ast
from email.message import Message
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import threading
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
OUT = None
sys.dont_write_bytecode = True
sys.path[:0] = [str(ROOT), str(ROOT / 'scripts')]
import admin_auth
import engine_workers
import strict_ingress_bridge
import traffic_http
import traffic_scheduler


class IdleThread:
    def __init__(self, *args, **kwargs): pass
    def start(self): pass
    def join(self, *args, **kwargs): pass


class SyntheticWorker:
    """No process/reservation: immediate timeout or bounded synthetic success."""
    def __init__(self, pool, lane, deadline):
        self.pool, self.lane = pool, lane
        pool._launched()
    def expired(self): return False
    def evaluate(self, request, deadline):
        if request.get('timeout'):
            raise engine_workers.EngineTimeout()
        return {'status': 'ok'}
    def stop(self, timeout=5): return True


def restart_witness():
    now = [0.0]
    budget = SimpleNamespace(stats=lambda: {'synthetic': True})
    with patch.object(engine_workers, '_Worker', SyntheticWorker), \
         patch.object(engine_workers, 'time', SimpleNamespace(monotonic=lambda: now[0])):
        pool = engine_workers.EnginePool(ROOT, 'NEVER_LAUNCH', budget=budget)
        errors = []
        for _ in range(12):
            try:
                pool.evaluate('feedback', {'timeout': True}, 12)
                raise AssertionError('Timeout expected')
            except engine_workers.EngineTimeout as error:
                errors.append(type(error).__name__)
        blocked = {}
        for lane in ('feedback', 'behavior'):
            try:
                pool.evaluate(lane, {}, 12)
                raise AssertionError('Shared start bucket must reject')
            except engine_workers.EngineUnavailable as error:
                assert 'restart limit' in str(error)
                blocked[lane] = str(error)
        before = pool.stats()
        assert before['workers'] == {'feedback': 0, 'behavior': 0}
        now[0] = 60
        assert pool.evaluate('feedback', {}, 12) == {'status': 'ok'}
        pool.close(timeout=0)
    return {'syntheticTimeouts': len(errors), 'beforeExpiry': before,
            'normalRequestsBlocked': blocked, 'recoveryAtSeconds': 60,
            'scope': 'Production EnginePool accounting with immediate timeout stub and frozen clock; not real canonical-timeout reachability.'}


def channel_witness():
    now = [0.0]
    with patch.object(traffic_scheduler.threading, 'Thread', IdleThread):
        scheduler = traffic_scheduler.Scheduler(clock=lambda: now[0])
        for _ in range(32): scheduler.issue_channel('127.0.0.1')
        rejected = []
        for instant in (0, 899):
            now[0] = instant
            try:
                scheduler.issue_channel('127.0.0.1')
                raise AssertionError('33rd same-peer channel must reject')
            except traffic_scheduler.CapacityError:
                rejected.append(instant)
        other = scheduler.issue_channel('192.0.2.2')
        assert scheduler.current(None, 0)
        scheduler.observe(None, 0)
        now[0] = 900
        scheduler.issue_channel('127.0.0.1')
        count_after = len(scheduler.channels)
        scheduler.close()
    return {'samePeerAccepted': 32, 'samePeer33rdRejectedAtSeconds': rejected,
            'differentPeerAccepted': bool(other), 'channelLessCurrent': True,
            'expiredSamePeerRecoveredAtSeconds': 900, 'channelsAfterExpiry': count_after,
            'scope': 'Real Scheduler channel methods; scheduling threads replaced by inert objects.'}


def stale_bridge_witness():
    relative_manifest = 'closure/traffic-refinement/evidence/ting02-20261004T201301Z-9d697815/manifest.json'
    manifest = json.loads((ROOT / relative_manifest).read_text())
    overlay = OUT / 'bridge-overlay'
    required = ['server.py', 'traffic_http.py',
                'formal/ingress_admission/dispatch-template.py.txt',
                'formal/ingress_admission/IngressRouteExtracted.lean']
    for name in required:
        target = overlay / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / name).read_bytes())
    name = 'engine_workers.py'
    current = (ROOT / name).read_bytes()
    changed = current.replace(b'self.starts = deque(maxlen=12)', b'self.starts = deque(maxlen=13)', 1)
    assert changed != current
    (overlay / name).write_bytes(changed)
    result = strict_ingress_bridge.check(overlay)
    assert result['status'] == 'PASS'
    digest = lambda data: hashlib.sha256(data).hexdigest()
    assert digest(changed) != manifest[name]
    return {'historicalManifestEntries': len(manifest), 'mutatedSource': name,
            'mutation': 'deque(maxlen=12) to deque(maxlen=13), scratch overlay only',
            'currentHashMatchesFrozen': digest(current) == manifest[name],
            'mutantHashMatchesFrozen': False, 'strictIngressBridge': result['status'],
            'scope': 'Bridge-only acceptance despite unrelated pinned-source drift; whole CI was not run. Full verify_strict_ingress.inputs does hash every registered input.'}


def extracted_health():
    tree = ast.parse((ROOT / 'server.py').read_text())
    cls = next(node for node in tree.body if isinstance(node, ast.ClassDef) and node.name == 'Handler')
    method = next(node for node in cls.body if isinstance(node, ast.FunctionDef) and node.name == 'do_GET')
    namespace = {'urlsplit': urlsplit, 'unquote': unquote}
    exec(compile(ast.Module(body=[method], type_ignores=[]), 'server.py::Handler.do_GET', 'exec'), namespace)
    return namespace['do_GET']


def health_witness():
    get = extracted_health()
    responses = []
    def health(control, workers):
        stats = {'workers': {'feedback': workers, 'behavior': 0}, 'failures': 12}
        app = SimpleNamespace(exercises=[], engine_pool=SimpleNamespace(stats=lambda: stats))
        app.control_listener = control
        app.shared_app = app
        handler = SimpleNamespace(path='/api/health', server=app,
            request_headers=lambda: None,
            reply=lambda status, body: (status, body))
        return get(handler)
    for control in (False, True):
        degraded = health(control, 0)
        healthy = health(control, 2)
        assert degraded == healthy and degraded[0] == 200 and degraded[1]['status'] == 'ok'
        responses.append({'controlListener': control, 'status': degraded[0],
                          'body': degraded[1], 'unchangedWhenWorkersVary': True})
    admission = traffic_http.Admission(traffic_http.TrafficProfile(), clock=lambda: 0)
    owner = object()
    assert admission.reserve('192.0.2.1', owner=owner) is None
    class UncertainThread:
        calls = 0
        def join(self, timeout):
            self.calls += 1
            raise RuntimeError('synthetic uncertain thread status')
        def is_alive(self): raise AssertionError('Must not read uncertain status')
    thread = UncertainThread()
    fake = SimpleNamespace(_request_threads_lock=threading.Lock(),
        _request_threads={owner: thread}, _request_threads_uncertain=set(), http_admission=admission)
    traffic_http.BoundedHTTPServer._reap_request_threads(fake)
    traffic_http.BoundedHTTPServer._reap_request_threads(fake)
    assert owner in admission.owners and owner in fake._request_threads_uncertain and thread.calls == 1
    counters = admission.statistics()
    return {'health': responses, 'uncertainOwnerRetained': True,
            'uncertainObservationCalls': thread.calls, 'availableAdmissionCounters': counters,
            'scope': 'Actual extracted do_GET health branches (header parsing bypassed) and actual reaper with synthetic thread error; no network listener.'}


def headers(values):
    result = Message()
    for name, value in values.items(): result[name] = value
    return result


def admin_witness():
    now = [0.0]
    synthetic = {'origin': 'https://admin.example.invalid', 'basePath': '/',
                 'password': {'salt': (b'S' * 16).hex(), 'hash': (b'A' * 32).hex()}}
    def kdf(password, salt):
        return b'A' * 32 if password == 'synthetic-correct-password' else b'B' * 32
    with patch.object(admin_auth, '_load_configuration', return_value=(synthetic, 'synthetic-generation')), \
         patch.object(admin_auth, 'derive_password', side_effect=kdf):
        manager = admin_auth.AuthManager(OUT / 'never-read-config', clock=lambda: now[0])
        principals = []
        for peer in ('192.0.2.10', '192.0.2.20'):
            state, cookies = manager.bootstrap(headers({}), peer)
            supplied = {'Origin': synthetic['origin'], 'Cookie': cookies[0].split(';', 1)[0],
                        'X-CSRF-Token': state['csrfToken']}
            principals.append(manager.authorize(headers(supplied), peer, preauth=True))
        failures = []
        for _ in range(5):
            try:
                manager.login(principals[0], 'synthetic-wrong-password')
                raise AssertionError('Wrong password should fail')
            except admin_auth.AuthError as error:
                failures.append(error.status)
        assert failures == [401] * 5
        try:
            manager.login(principals[1], 'synthetic-correct-password')
            raise AssertionError('Other principal must be globally throttled')
        except admin_auth.AuthError as error:
            blocked_status = error.status
            assert blocked_status == 429
        now[0] = 60
        state, _ = manager.login(principals[1], 'synthetic-correct-password')
        assert state['authenticated']
    return {'attackerWrongPasswordStatuses': failures, 'differentPeerCorrectPasswordStatus': blocked_status,
            'correctPasswordAcceptedAtSeconds': 60,
            'scope': 'Production bootstrap/CSRF/authorize/login logic with synthetic HTTPS config, cheap deterministic KDF and clock. Actual deployment configuration/reachability unknown.'}


def proposed_fix_counterexamples():
    # Model-only negative control: successful starts plus timeout retirement do
    # not charge a limiter that counts only failed starts. Concurrency stays one.
    failed_starts = launches = live = high_water = 0
    for _ in range(13):
        assert failed_starts < 12
        launches += 1
        live += 1
        high_water = max(high_water, live)
        live -= 1  # immediate synthetic timeout and confirmed reap
    assert launches > 12 and failed_starts == 0 and high_water == 1
    # Caller-controlled headers split a single peer into arbitrary quota keys.
    untrusted_peer = '192.0.2.50'
    forged_keys = {f'198.51.100.{i}' for i in range(1, 34)}
    assert len(forged_keys) == 33
    return {'scope': 'Pure bounded countermodels for proposed policy, not production executions.',
        'startupFailureOnlyLimiter': {'launchesInOneSyntheticWindow': launches,
            'countedFailedStarts': failed_starts, 'concurrentHighWater': high_water,
            'violation': '13 launches pass a claimed 12/window launch envelope despite concurrency=1.'},
        'blindForwardedIdentity': {'tcpPeers': 1, 'distinctAttackerChosenKeys': len(forged_keys),
            'violation': 'Trusting arbitrary XFF lets one source bypass a per-peer channel quota.'}}


def main():
    global OUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-root', type=Path, required=True)
    args = parser.parse_args()
    OUT = args.output_root.resolve()
    OUT.mkdir(parents=True, exist_ok=False)
    with patch.object(subprocess, 'Popen', side_effect=AssertionError('Process launch prohibited')), \
         patch.object(socket, 'socket', side_effect=AssertionError('Socket creation prohibited')):
        results = {'method': 'bounded mocked/source witnesses',
                   'productionProcessesLaunched': 0, 'networkSocketsOpened': 0,
                   'restart': restart_witness(), 'channels': channel_witness(),
                   'staleBridge': stale_bridge_witness(), 'health': health_witness(),
                   'admin': admin_witness(), 'proposedFixCounterexamples': proposed_fix_counterexamples()}
    path = OUT / 'results.json'
    path.write_text(json.dumps(results, indent=2, sort_keys=True) + '\n')
    print(json.dumps({'status': 'PASS', 'witnessGroups': 5, 'results': str(path)}))


if __name__ == '__main__': main()
