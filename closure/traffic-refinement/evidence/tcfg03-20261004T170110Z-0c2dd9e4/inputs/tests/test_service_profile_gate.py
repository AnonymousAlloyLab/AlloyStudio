"""Fail-closed mutation checks for the frozen TRF-00 gate contract."""
from copy import deepcopy
import json
from pathlib import Path
import sys
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import verify_service_profile as gate


class ServiceProfileGateTests(unittest.TestCase):
    def setUp(self):
        scratch=ROOT/'build/trf-closure/gate-test-scratch';scratch.mkdir(parents=True,exist_ok=True)
        self.directory=tempfile.TemporaryDirectory(prefix='profile-',dir=scratch)
        self.addCleanup(self.directory.cleanup);self.root=Path(self.directory.name)
        for p in ('closure/traffic-refinement/service-profile.json','formal/service_profile/required-profile.json'):
            target=self.root/p;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes((ROOT/p).read_bytes())
        self.path=self.root/'closure/traffic-refinement/service-profile.json'
        self.profile=json.loads(self.path.read_text())

    def reject(self,profile):
        self.path.write_text(json.dumps(profile))
        with self.assertRaises(gate.Rejected):gate.check_profile(self.root)

    def test_complete_selected_profile_is_admitted(self):
        self.assertEqual(len(gate.check_profile(self.root)['limits']),213)

    def test_missing_or_duplicate_limit_blocks(self):
        changed=deepcopy(self.profile);changed['limits'].pop();self.reject(changed)
        changed=deepcopy(self.profile);changed['limits'].append(changed['limits'][0]);self.reject(changed)

    def test_boolean_is_not_a_quantity(self):
        changed=deepcopy(self.profile);changed['limits'][0]['value']=True;self.reject(changed)

    def test_nonfinite_duration_blocks(self):
        for value in (float('nan'),float('inf'),-float('inf')):
            changed=deepcopy(self.profile)
            next(r for r in changed['limits'] if r['encoding']=='exact-built-in-finite-float-seconds')['value']=value
            self.reject(changed)

    def test_unclassified_numeric_encoding_blocks(self):
        changed=deepcopy(self.profile);changed['limits'][0]['encoding']='number';self.reject(changed)

    def test_positive_quantity_cannot_be_zero(self):
        changed=deepcopy(self.profile);changed['limits'][0]['value']=0;self.reject(changed)

    def test_disabled_zero_capacity_is_explicit(self):
        self.assertTrue(any(r['id']=='prepared.bytes' and r['value']==0 and 'nonnegative' in r['encoding'] for r in gate.check_profile(self.root)['limits']))

    def test_nonselected_deployment_or_engine_mode_blocks(self):
        for field,value in (('deployment','iis'),('engineMode','oneshot')):
            changed=deepcopy(self.profile);changed['selected'][field]=value;self.reject(changed)

    def test_deleted_independent_ownership_requirement_blocks(self):
        changed=deepcopy(self.profile);changed['initialExpectations']['zeroIntegers'].pop();self.reject(changed)

    def test_unknown_semantic_field_blocks(self):
        changed=deepcopy(self.profile);changed['unknown']='unspecified';self.reject(changed)

    def test_missing_required_theorem_blocks(self):
        target=self.root/'formal/service_profile/theorems.json'
        target.write_text(json.dumps({'theorems':[]}))
        with self.assertRaisesRegex(gate.Rejected,'required startup theorem'):gate.inventory(self.root)


if __name__=='__main__':unittest.main()
