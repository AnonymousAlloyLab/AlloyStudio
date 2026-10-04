"""Finite admission and lane witness; no corpus, credentials, engine or network."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import ast
import hashlib
import json
from pathlib import Path
from threading import Barrier
from unittest.mock import patch
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from traffic_http import Admission, BoundedHTTPServer, TrafficProfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    profile = replace(TrafficProfile(), public_handlers=2, control_handlers=1,
                      public_burst=40, control_burst=40, peer_burst=40)
    public = Admission(profile, clock=lambda: 0)
    control = Admission(profile, control=True, clock=lambda: 0)
    barrier = Barrier(17)
    owners = [object() for _ in range(16)]
    def attempt(owner):
        barrier.wait()
        return public.reserve('same-peer', owner=owner)
    with ThreadPoolExecutor(max_workers=16) as pool:
        futures = [pool.submit(attempt, owner) for owner in owners]
        barrier.wait()
        outcomes = [future.result() for future in futures]
    assert outcomes.count(None) == 2 and outcomes.count(503) == 14
    assert public.active == len(public.owners) == 2
    private_owner = object()
    assert control.reserve('loopback', owner=private_owner) is None
    assert control.reserve('loopback', owner=object()) == 503
    assert public.active + control.active == profile.public_handlers + profile.control_handlers
    for owner in owners:
        public.release(owner)
        public.release(owner)
    control.release(private_owner)
    assert public.active == control.active == 0

    small = replace(profile, public_burst=1, control_burst=1)
    pg = Admission(small, clock=lambda: 0)
    cg = Admission(small, control=True, clock=lambda: 0)
    assert pg.reserve('peer') is None
    pg.release()
    assert pg.reserve('peer') == 429
    assert cg.reserve('loopback') is None
    cg.release()
    assert cg.reserve('loopback') == 429
    assert pg.reserve('peer') == 429

    # Construct without binding: process_request's actual code does not depend
    # on a listening socket. Replace only the stdlib allocator and shutdown.
    app = object.__new__(BoundedHTTPServer)
    app.http_admission = Admission(profile, clock=lambda: 0)
    shutdowns = []
    app.shutdown_request = lambda request: shutdowns.append(request)
    events = []
    class Socket:
        def setblocking(self, value):
            assert value is False
        def send(self, packet):
            events.append(('reject_send', app.http_admission.active))
            return len(packet)
    def allocate(receiver, request, address):
        assert receiver is app
        events.append(('allocate', app.http_admission.active))
        assert request in app.http_admission.owners
        assert app.http_admission.active <= profile.public_handlers
    first, second, rejected = Socket(), Socket(), Socket()
    with patch('socketserver.ThreadingMixIn.process_request', allocate):
        app.process_request(first, ('127.0.0.1', 1))
        app.process_request(second, ('127.0.0.1', 2))
        app.process_request(rejected, ('127.0.0.1', 3))
    assert events == [('allocate', 1), ('allocate', 2), ('reject_send', 2)]
    assert shutdowns == [rejected]
    app.http_admission.release(first)
    app.http_admission.release(second)
    failed = Socket()
    def fail_allocate(receiver, request, address):
        assert request in receiver.http_admission.owners
        raise RuntimeError('constructed allocator failure')
    with patch('socketserver.ThreadingMixIn.process_request', fail_allocate):
        try:
            app.process_request(failed, ('127.0.0.1', 4))
        except RuntimeError:
            pass
        else:
            raise AssertionError('expected allocation failure')
    assert app.http_admission.active == 0
    thread_owner = Socket()
    assert app.http_admission.reserve('peer', owner=thread_owner) is None
    def fail_handler(receiver, request, address):
        raise RuntimeError('constructed handler failure')
    with patch('socketserver.ThreadingMixIn.process_request_thread', fail_handler):
        try:
            app.process_request_thread(thread_owner, ('127.0.0.1', 5))
        except RuntimeError:
            pass
        else:
            raise AssertionError('expected handler failure')
    assert app.http_admission.active == 0

    source = ast.parse((ROOT / 'server.py').read_text())
    main_ast = next(node for node in source.body if isinstance(node, ast.FunctionDef) and node.name == 'main')
    default = []
    for node in ast.walk(main_ast):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute) and node.func.attr == 'add_argument':
            if node.args and isinstance(node.args[0], ast.Constant) and node.args[0].value == '--control-port':
                default.extend(kw.value.value for kw in node.keywords if kw.arg == 'default' and isinstance(kw.value, ast.Constant))
    assert default == [0]
    profile_json = json.loads((ROOT / 'closure/traffic-refinement/service-profile.json').read_text())
    selected = profile_json['deploymentModes']['selected']
    assert profile_json['deploymentModes'][selected]['controlPort'] == 0
    result = {
        'schemaVersion': 1, 'classification': 'FINITE_DISCOVERY_NOT_CLOSURE',
        'sourceInputs': {str(p): digest(ROOT / p) for p in [Path('traffic_http.py'),Path('traffic_profile.py'),Path('server.py'),Path('closure/traffic-refinement/service-profile.json')]},
        'finiteTests': {
            'simultaneousPublicAttempts':16,'publicAccepted':2,'publicCapacityRejected':14,
            'combinedActive':3,'combinedCapacity':3,'releaseIdempotence':True,
            'separateCreditsNoBorrowing':True,'reservationBeforeStdlibAllocator':True,
            'capacityRejectionNoStdlibAllocator':True,'allocatorFailureRelease':True,
            'handlerFailureRelease':True,
        },
        'controlBoundary': {'productionCliDefault':0,'trf00FrozenControlPort':0,
            'controlCurrentlyStartedByDefault':False,
            'requiresActiveTopologyWitnessToCloseActualControlIngress':True},
        'trust': ['CPython locks serialize critical sections', 'socketserver callback contract',
                  'one public plus at most one control listener share one specified profile',
                  'finite test executions are not proofs for all schedules'],
    }
    print(json.dumps(result,indent=2,sort_keys=True))

if __name__ == '__main__':
    main()
