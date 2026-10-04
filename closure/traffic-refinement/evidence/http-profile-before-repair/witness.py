"""Observed-baseline constructor witnesses; no sockets, processes or secrets.

Run with `python3 -B build/trf-closure/next-profile-audit/witness.py`.
This is candidate-generation evidence, not a registered closure verifier.
"""
from dataclasses import asdict
import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace
from unittest.mock import patch

OWNED = Path(__file__).resolve().parent
BASELINE = OWNED / 'baseline'
manifest = json.loads((OWNED / 'baseline-hashes.json').read_text())
for entry in manifest['sources']:
    assert hashlib.sha256((BASELINE / entry['path']).read_bytes()).hexdigest() == entry['sha256']


def no_external_action(event, args):
    if event in ('socket.__new__', 'socket.connect', 'socket.bind', 'subprocess.Popen', 'os.system'):
        raise AssertionError('External action forbidden in constructor witness: ' + event)


sys.addaudithook(no_external_action)
sys.path.insert(0, str(BASELINE))
from traffic_http import Admission, BoundedHTTPServer, TrafficProfile


def summary(gate):
    return {
        'limit': gate.limit,
        'controlType': type(gate.control).__name__,
        'active': gate.active,
        'peak': gate.peak,
        'accepted': gate.accepted,
        'rejected': gate.rejected,
        'owners': len(gate.owners),
        'anonymousOwners': len(gate.anonymous_owners),
        'peers': len(gate.peers),
        'bucketCapacity': gate.bucket.capacity,
        'bucketRate': gate.bucket.rate,
        'bucketCredit': gate.bucket.credit,
        'bucketLastType': type(gate.bucket.last).__name__,
    }


def bounded_without_socket(profile, control=False):
    # Only stdlib socket-bearing initialization is replaced. The complete
    # production BoundedHTTPServer.__init__ and its Admission/PublicViews execute.
    with patch('traffic_http.ThreadingHTTPServer.__init__', return_value=None) as stdlib:
        instance = BoundedHTTPServer(('127.0.0.1', 0), object,
                                     traffic_profile=profile, control=control)
    return instance, stdlib.call_count


report = {
    'kind': 'unregistered-concrete-baseline-witnesses',
    'scope': 'TCFG02 candidate: HTTP profile ingress, exact Boolean selector, clock and initial state',
    'baselineHashes': manifest['sources'],
    'boundary': {'network': 'forbidden by audit hook', 'jvm': 'none', 'providerCalls': 'none',
                 'stdio': 'only synthetic public metadata',
                 'stdlibSocketInitialization': 'mocked in BoundedHTTPServer witnesses'},
    'witnesses': [],
}

# A duck-typed object bypasses every TrafficProfile scalar/relationship guard.
namespace = SimpleNamespace(**asdict(TrafficProfile()))
namespace.control_handlers = 0
namespace.line_bytes = namespace.header_bytes + 1
instance, calls = bounded_without_socket(namespace)
assert calls == 1
assert instance.traffic_profile is namespace
assert instance.traffic_profile.control_handlers == 0
assert instance.traffic_profile.line_bytes > instance.traffic_profile.header_bytes
report['witnesses'].append({
    'id': 'HTTP-NS-PROFILE',
    'input': {'type': 'SimpleNamespace', 'changes': {'control_handlers': 0, 'line_bytes': 32769}},
    'events': ['construct BoundedHTTPServer with stdlib socket initialization mocked'],
    'observed': {'accepted': True, 'stdlibConstructorCalls': calls,
                 'zeroControlReserve': True, 'lineExceedsHeader': True},
    'violatedPostcondition': 'A production constructor accepts only a validated 23-field HTTP profile.',
})

# dataclass(frozen=True) does not establish a trusted invariant at consumer entry.
corrupted = TrafficProfile()
object.__setattr__(corrupted, 'public_handlers', 31)
object.__setattr__(corrupted, 'control_handlers', 256)
assert corrupted.public_handlers + corrupted.control_handlers == 287
instance, calls = bounded_without_socket(corrupted)
assert calls == 1
assert instance.http_admission.limit == 31
report['witnesses'].append({
    'id': 'HTTP-CORRUPTED-PROFILE',
    'input': {'type': 'TrafficProfile', 'mutation': 'object.__setattr__',
              'changes': {'public_handlers': 31, 'control_handlers': 256}},
    'events': ['construct TrafficProfile', 'corrupt frozen fields',
               'construct BoundedHTTPServer with stdlib socket initialization mocked'],
    'observed': {'accepted': True, 'stdlibConstructorCalls': calls, 'combinedHandlers': 287},
    'violatedPostcondition': 'Accepted profile satisfies the existing combined handler envelope <= 256.',
})

instance, calls = bounded_without_socket(TrafficProfile(), control='false')
assert calls == 1
assert instance.control_listener == 'false'
assert instance.http_admission.limit == 2
assert instance.http_admission.bucket.rate == 2
report['witnesses'].append({
    'id': 'HTTP-TRUTHY-CONTROL',
    'input': {'control': 'false', 'controlType': 'str'},
    'events': ['construct BoundedHTTPServer with stdlib socket initialization mocked'],
    'observed': {'accepted': True, 'stdlibConstructorCalls': calls,
                 'selectedLane': 'control', 'handlerLimit': 2, 'bucketRate': 2},
    'violatedPostcondition': 'The public/control initial-state selector is an exact built-in Boolean.',
})

import traffic_http
original_lock = traffic_http.threading.Lock
lock_events = []


def counted_lock():
    lock_events.append('lock')
    return original_lock()


def malformed_clock():
    lock_events.append('clock')
    return 'not-an-integer-tick'


with patch('traffic_http.threading.Lock', side_effect=counted_lock):
    gate = Admission(TrafficProfile(), clock=malformed_clock)
assert gate.bucket.last == 'not-an-integer-tick'
assert lock_events == ['lock', 'clock']
partial_state = summary(gate)
try:
    gate.reserve('synthetic-peer')
except TypeError as error:
    first_event_error = type(error).__name__
else:
    raise AssertionError('The baseline malformed-clock witness changed.')
report['witnesses'].append({
    'id': 'HTTP-MALFORMED-CLOCK',
    'input': {'clockResult': 'not-an-integer-tick', 'clockResultType': 'str'},
    'events': ['construct Admission', 'attempt first reserve'],
    'observed': {'accepted': True, 'constructorEventOrder': lock_events,
                 'initialState': partial_state, 'firstReserveError': first_event_error},
    'violatedPostcondition': 'Malformed initial clock is rejected before lock/state allocation.',
})

lock_events.clear()


def raising_clock():
    lock_events.append('clock')
    raise ValueError('synthetic-invalid-clock')


partial = Admission.__new__(Admission)
with patch('traffic_http.threading.Lock', side_effect=counted_lock):
    try:
        Admission.__init__(partial, TrafficProfile(), clock=raising_clock)
    except ValueError:
        pass
    else:
        raise AssertionError('Expected clock exception')
assert lock_events == ['lock', 'clock']
assert 'lock' in vars(partial)
report['witnesses'].append({
    'id': 'HTTP-RAISING-CLOCK-PARTIAL-STATE',
    'input': {'clock': 'raises ValueError'},
    'events': ['initialize fresh Admission object'],
    'observed': {'accepted': False, 'constructorEventOrder': lock_events,
                 'allocatedStateFields': sorted(vars(partial))},
    'violatedPostcondition': 'Initial clock failure leaves no allocated Admission state or lock.',
})

# Positive initial-state witnesses make the intended projection explicit.
report['validInitialStates'] = {}
for control in (False, True):
    gate = Admission(TrafficProfile(), control=control, clock=lambda: 123456789)
    state = summary(gate)
    state['bucketLast'] = gate.bucket.last
    assert state['active'] == state['peak'] == state['accepted'] == state['rejected'] == 0
    assert state['owners'] == state['anonymousOwners'] == state['peers'] == 0
    assert state['bucketCredit'] == state['bucketCapacity']
    report['validInitialStates']['control' if control else 'public'] = state

output = OWNED / 'witness-results.json'
output.write_text(json.dumps(report, indent=2, sort_keys=True, allow_nan=False) + '\n')
print(json.dumps({'witnessesObserved': len(report['witnesses']),
                  'initialStatesChecked': len(report['validInitialStates']),
                  'report': str(output),
                  'status': 'BASELINE_COUNTEREXAMPLES_REPRODUCED'}))
