"""Fail-closed source projection and permissive-mutant proof rejection."""
from itertools import product
import ast
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
# TRF-01 is VERIFIED at this recorded source root. AP01 changed the current
# checkout (scripts/source_freshness.py classifies it STALE); this historical
# correspondence is replayed against the frozen evidence inputs, read-only.
HISTORICAL = ROOT / 'closure/traffic-refinement/evidence/ting02-20261004T201301Z-9d697815/inputs'
import admission_bridge as bridge
from traffic_http import Admission, TrafficProfile
from dataclasses import replace


class AdmissionBridgeTests(unittest.TestCase):
    def setUp(self):
        self.http = (HISTORICAL / 'traffic_http.py').read_text()
        self.profile = (HISTORICAL / 'traffic_profile.py').read_text()
        self.server = (HISTORICAL / 'server.py').read_text()

    def test_actual_source_projection_is_exact_and_closed(self):
        self.assertEqual(bridge.check(HISTORICAL)['status'], 'PASS')
        data = bridge.extract(HISTORICAL)
        self.assertEqual(data['paths']['allocation'], ['reserve','allocate','register','attempt','identify','start'])
        self.assertEqual(data['paths']['reaping'], ['markObservation','joinReturned','terminate','clearObservation','unregister','release'])
        self.assertEqual(data['paths']['retainedOnUnstartedJoin'],
                         ['reserve','allocate','register','attempt','identify','markObservation','joinRefused'])
        self.assertEqual(data['reapCheck'], 'joined')
        self.assertIs(data['stickyObservationFailure'], True)
        self.assertEqual(data['controlPortDefault'], 0)

    def test_guard_lock_owner_and_rejection_mutations_rejected(self):
        mutations = [
            ('if self.active >= self.limit:', 'if False:'),
            ('with self.lock:', 'if True:'),
            ('self.owners.add(owner)', 'self.owners.add(object())'),
            ('self.owners.remove(owner)', 'self.owners.clear()'),
            ('self.bucket.charge()', 'self.active = 0'),
            ('return 503', 'return None'),
            ('owner = object()', 'owner = 0'),
            ('self.anonymous_owners.pop()', 'self.anonymous_owners[0]'),
            ('self._request_threads = {}', 'self._request_threads = shared_app._request_threads'),
            ('thread.join(timeout=0)', 'thread.join(timeout=1)'),
            ('except RuntimeError:\n                    continue', 'except RuntimeError:\n                    pass'),
            ('if not alive:', 'if thread.ident is not None:'),
            ('self._request_threads_uncertain = set()', 'self._request_threads_uncertain = shared_app._request_threads_uncertain'),
            ('self._request_threads_uncertain.add(request)', 'pass'),
            ('request in self._request_threads_uncertain or thread is threading.current_thread()',
             'thread is threading.current_thread()'),
            ('request in self._request_threads_uncertain or thread is threading.current_thread()',
             'request in self._request_threads_uncertain'),
            ('if not start_attempted:', 'if thread.ident is None:'),
        ]
        for old,new in mutations:
            with self.subTest(old=old):
                self.assertIn(old,self.http)
                with self.assertRaises(bridge.BridgeRejected):
                    bridge.generate(HISTORICAL, {'traffic_http.py':self.http.replace(old,new,1)})

    def test_allocation_reorder_release_loss_and_namespace_escape_rejected(self):
        originals = [
            self.http.replace('self._request_threads[request] = thread\n                start_attempted = True\n                thread.start()',
                              'start_attempted = True\n                thread.start()\n                self._request_threads[request] = thread'),
            self.http.replace('                    self.http_admission.release(request)', '                    pass',1),
            self.http + '\nAdmission.reserve = lambda *a, **k: None\n',
            self.http + '\ndef object():\n    return 0\n',
            self.http.replace('self._reap_request_threads()\n        rejection =', 'rejection ='),
        ]
        for source in originals:
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(HISTORICAL, {'traffic_http.py':source})

    def reject_with_registered_bounded_shape(self, source):
        """The semantic adapter must reject, even after its class digest changes."""
        self.assertNotEqual(source, self.http)
        parent=ROOT/'build/trf-closure/admission-bridge-tests'
        parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='observation-guard-',dir=parent) as temporary:
            scratch=Path(temporary)
            for name,value in [('traffic_http.py',source),('traffic_profile.py',self.profile),
                               ('server.py',self.server)]:
                (scratch/name).write_text(value)
            path=scratch/bridge.CONTRACT;path.parent.mkdir(parents=True)
            contract=json.loads((HISTORICAL/bridge.CONTRACT).read_text())
            cls=bridge.unique(bridge.parse(source),'BoundedHTTPServer',ast.ClassDef)
            contract['shapes']['BoundedHTTPServer']=bridge.sha(bridge.dump(cls))
            path.write_text(json.dumps(contract))
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(scratch,{'traffic_http.py':source,'traffic_profile.py':self.profile,
                                         'server.py':self.server})

    def test_identity_only_reaper_is_rejected_even_with_registered_class_hash(self):
        tree=ast.parse(self.http)
        cls=bridge.unique(tree,'BoundedHTTPServer',ast.ClassDef)
        method=bridge.method(cls,'_reap_request_threads')
        lines=self.http.splitlines(keepends=True)
        replacement=("    def _reap_request_threads(self):\n"
                     "        with self._request_threads_lock:\n"
                     "            for request, thread in list(self._request_threads.items()):\n"
                     "                if thread.ident is not None and not thread.is_alive():\n"
                     "                    del self._request_threads[request]\n"
                     "                    self.http_admission.release(request)\n")
        old=''.join(lines[:method.lineno-1])+replacement+''.join(lines[method.end_lineno:])
        self.reject_with_registered_bounded_shape(old)

    def test_observation_exception_mutants_rejected_with_registered_class_hash(self):
        cases=[
            ('self._request_threads_uncertain.add(request)', 'pass'),
            ('except RuntimeError:\n                    continue',
             'except RuntimeError:\n                    self._request_threads_uncertain.remove(request)\n                    continue'),
            ('                    alive = thread.is_alive()\n                except RuntimeError:\n                    continue',
             '                except RuntimeError:\n                    continue\n                alive = thread.is_alive()'),
            ('request in self._request_threads_uncertain or thread is threading.current_thread()',
             'thread is threading.current_thread()'),
            ('request in self._request_threads_uncertain or thread is threading.current_thread()',
             'request in self._request_threads_uncertain'),
        ]
        for old,new in cases:
            with self.subTest(old=old):
                self.assertIn(old,self.http)
                self.reject_with_registered_bounded_shape(self.http.replace(old,new,1))

    def test_topology_and_initial_state_mutations_rejected(self):
        for path,source,old,new in [
            ('server.py',self.server,'if args.control_port:', 'if True:'),
            ('server.py',self.server,"('127.0.0.1', args.control_port)","('0.0.0.0', args.control_port)"),
            ('server.py',self.server,'control=True, shared_app=server','control=False, shared_app=server'),
            ('traffic_profile.py',self.profile,"'active': 0", "'active': 1"),
            ('traffic_profile.py',self.profile,"'owners': set()", "'owners': {1}"),
        ]:
            with self.subTest(old=old), self.assertRaises(bridge.BridgeRejected):
                bridge.generate(HISTORICAL,{path:source.replace(old,new,1)})

    def test_profile_postinit_mutation_and_portal_override_are_closed(self):
        malicious_profile = self.profile.replace('    def __post_init__(self):\n',
            "    def __post_init__(self):\n        object.__setattr__(self, 'public_handlers', 1000)\n        return\n", 1)
        malicious_portal = self.server.replace('class Portal(BoundedHTTPServer):\n',
            'class Portal(BoundedHTTPServer):\n    def process_request(self, request, client_address):\n        return None\n', 1)
        for path, source in [('traffic_profile.py', malicious_profile), ('server.py', malicious_portal)]:
            with self.assertRaises(bridge.BridgeRejected):
                bridge.generate(HISTORICAL, {path: source})

    def test_actual_numeric_operators_are_translated_not_approved_by_shape(self):
        cases = [
            ('traffic_http.py',self.http,'self.active >= self.limit','self.active > self.limit','.gt 1'),
            ('traffic_http.py',self.http,'self.active += 1','self.active += 2','.ge 2'),
            ('traffic_http.py',self.http,'self.active -= 1','self.active -= 0','release 0'),
            ('traffic_profile.py',self.profile,'profile.control_handlers if control else profile.public_handlers',
             'profile.public_handlers if control else profile.control_handlers','if control then publicHandlers else controlHandlers'),
        ]
        for path,source,old,new,expected in cases:
            with self.subTest(old=old):
                generated=bridge.generate(HISTORICAL,{path:source.replace(old,new,1)})
                self.assertIn(expected,generated)
                self.assertNotEqual(generated,bridge.generate(HISTORICAL))

    def test_translated_mutants_fail_independent_lean_obligations_offline(self):
        cases = [
            ('traffic_http.py',self.http,'self.active >= self.limit','self.active > self.limit'),
            ('traffic_http.py',self.http,'self.active += 1','self.active += 2'),
            ('traffic_http.py',self.http,'self.active -= 1','self.active -= 0'),
            ('traffic_profile.py',self.profile,'profile.control_handlers if control else profile.public_handlers',
             'profile.public_handlers if control else profile.control_handlers'),
        ]
        from lean_offline import installed_toolchain
        try:
            installed_toolchain(ROOT)
        except (FileNotFoundError, ValueError):
            self.skipTest('Pinned Lean unavailable outside the registered closure gate')
        if sys.platform != 'linux':
            self.skipTest('Network-isolated Lean requires Linux outside the registered closure gate')
        scratch_parent=ROOT/'build/trf-closure/admission-bridge-tests'
        scratch_parent.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='admission-mutants-',dir=scratch_parent) as temporary:
            scratch=Path(temporary)
            for name in ('AdmissionModel.lean','AdmissionSpec.lean'):
                (scratch/name).write_bytes((HISTORICAL/'formal/ingress_admission'/name).read_bytes())
            def compile_(name):
                return subprocess.run([sys.executable,str(ROOT/'scripts/lean_offline.py'),
                    '--cwd',str(scratch),'--lean-path',str(scratch),'--','-DgenInjectivity=false',
                    '-o',name+'.olean',name+'.lean'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
            model=compile_('AdmissionModel')
            self.assertEqual(model.returncode,0,model.stdout)
            for path,source,old,new in cases:
                with self.subTest(old=old):
                    (scratch/'AdmissionExtracted.lean').write_text(bridge.generate(HISTORICAL,{path:source.replace(old,new,1)}))
                    extracted=compile_('AdmissionExtracted')
                    self.assertEqual(extracted.returncode,0,extracted.stdout)
                    spec=compile_('AdmissionSpec')
                    self.assertEqual(spec.returncode,1,spec.stdout)
                    self.assertIn('error:',spec.stdout)
            # This mutant parses and compiles, but independently specified
            # join-refusal/identified-phase obligations reject its semantics.
            source=bridge.generate(HISTORICAL).replace('reclaimable .joined phase',
                                                 'reclaimable .identityOnly phase')
            self.assertNotEqual(source,bridge.generate(HISTORICAL))
            (scratch/'AdmissionExtracted.lean').write_text(source)
            extracted=compile_('AdmissionExtracted')
            self.assertEqual(extracted.returncode,0,extracted.stdout)
            spec=compile_('AdmissionSpec')
            self.assertEqual(spec.returncode,1,spec.stdout)
            self.assertIn('error:',spec.stdout)
            # Clearing uncertainty after an exceptional observation recreates
            # a ready entry whose runtime liveness metadata is poisoned. The
            # reachable-history and explicit counterexample obligations fail.
            source=bridge.generate(HISTORICAL).replace('stickyObservationFailure : Bool := true',
                                                 'stickyObservationFailure : Bool := false')
            self.assertNotEqual(source,bridge.generate(HISTORICAL))
            (scratch/'AdmissionExtracted.lean').write_text(source)
            extracted=compile_('AdmissionExtracted')
            self.assertEqual(extracted.returncode,0,extracted.stdout)
            spec=compile_('AdmissionSpec')
            self.assertEqual(spec.returncode,1,spec.stdout)
            self.assertIn('error:',spec.stdout)

    def test_finite_production_transition_projection(self):
        # Fixed-clock credits are deliberately ample. All 256 four-event traces
        # over two exact owners exercise reserve duplicate/refusal/release edges.
        for events in product(range(4),repeat=4):
            gate=Admission(replace(TrafficProfile(),public_handlers=1,public_burst=100,peer_burst=100),clock=lambda:0)
            owners=[object(),object()]
            modeled=set()
            for event in events:
                owner=owners[event//2]
                if event%2:
                    gate.release(owner); modeled.discard(owner)
                elif owner in modeled:
                    with self.assertRaises(ValueError): gate.reserve('peer',owner=owner)
                elif len(modeled)>=1:
                    self.assertEqual(gate.reserve('peer',owner=owner),503)
                else:
                    self.assertIsNone(gate.reserve('peer',owner=owner));modeled.add(owner)
                self.assertEqual(gate.owners,modeled)
                self.assertEqual(gate.active,len(modeled))
                self.assertLessEqual(gate.active,gate.limit)


if __name__=='__main__':
    unittest.main()
