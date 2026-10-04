"""Fail-closed inventory/policy controls for the full successor ingress gate."""
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'scripts'))
import verify_strict_ingress as gate

class StrictIngressGateTests(unittest.TestCase):
    def setUp(self):
        scratch=ROOT/'build/trf-closure/strict-ingress-gate-tests';scratch.mkdir(parents=True,exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(dir=scratch);self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name)
        names=gate.BASE_INPUTS|{'server.py','traffic_http.py','traffic_decode.py'}
        for name in names:
            path=self.root/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text('fixture\n')
        for name in (gate.ORIGINAL,gate.DEPENDENCY,gate.SPEC):shutil.copyfile(ROOT/name,self.root/name)
        prior={'files':{'server.py':gate.digest(self.root/'server.py')}}
        (self.root/gate.PRIOR_MANIFEST).write_text(json.dumps(prior))
        sources={'files':{name:gate.digest(self.root/name) for name in ('server.py','traffic_http.py','traffic_decode.py')}}
        (self.root/gate.SOURCE_MANIFEST).write_text(json.dumps(sources))
        self.block=dict(gate.policy(),inputs={name:gate.digest(self.root/name) for name in gate.required_inputs(self.root)})
        (self.root/gate.BLOCK).write_text(json.dumps(self.block))

    def test_exact_fixture_requires_full_original_parent(self):
        self.assertIn(gate.BLOCK,gate.inputs(self.root,self.block))
        self.assertEqual(self.block['umbrellaObligationsClosed'],['TRF-01'])
        self.assertEqual(len(self.block['claims']),6)

    def test_source_mutation_and_missing_inventory_block(self):
        (self.root/'server.py').write_text('changed')
        with self.assertRaisesRegex(gate.Rejected,'Frozen input'):gate.inputs(self.root,self.block)
        for name in gate.BASE_INPUTS:
            block=copy.deepcopy(self.block);block['inputs'].pop(name)
            with self.subTest(name=name),self.assertRaisesRegex(gate.Rejected,'inventory'):gate.inputs(self.root,block)

    def test_claim_trust_build_review_or_axiom_policy_cannot_be_relaxed(self):
        for key,value in [('claims',[]),('trust',[]),('requiredCleanBuilds',1),('requiredReviews',0),
                          ('allowlistedAxioms',['propext']),('umbrellaObligationsClosed',['TRF-00','TRF-01']),
                          ('modules',[]),('flags',[]),('requiredTheorems',[])]:
            block=copy.deepcopy(self.block);block[key]=value
            with self.subTest(key=key),self.assertRaisesRegex(gate.Rejected,'policy'):gate.inputs(self.root,block)

    def test_contract_cannot_rewrite_original_obligation(self):
        data=json.loads((self.root/gate.SPEC).read_text());data['original']['statement']='weaker'
        (self.root/gate.SPEC).write_text(json.dumps(data));self.block['inputs'][gate.SPEC]=gate.digest(self.root/gate.SPEC)
        with self.assertRaisesRegex(gate.Rejected,'Original TRF-01'):gate.inputs(self.root,self.block)

    def test_unregistered_proof_is_rejected(self):
        (self.root/'formal/ingress_admission/Extra.lean').write_text('axiom forge : False')
        with self.assertRaisesRegex(gate.Rejected,'Unregistered'):gate.inputs(self.root,self.block)

    def test_no_historical_dependency_can_be_dropped(self):
        path=self.root/gate.SOURCE_MANIFEST;data=json.loads(path.read_text());del data['files']['server.py'];path.write_text(json.dumps(data))
        with self.assertRaisesRegex(gate.Rejected,'Dropped'):gate.required_inputs(self.root)

    def test_changed_source_manifest_cannot_self_attest(self):
        path=self.root/gate.SOURCE_MANIFEST;data=json.loads(path.read_text());data['files']['server.py']='0'*64;path.write_text(json.dumps(data))
        self.block['inputs'][gate.SOURCE_MANIFEST]=gate.digest(path)
        with self.assertRaisesRegex(gate.Rejected,'manifest mismatch'):gate.inputs(self.root,self.block)

    def test_proof_language_rejects_placeholders_axioms_and_options(self):
        for source in ('axiom rogue : False','theorem hole : True := by sorry','set_option debug.skipKernelTC true'):
            with self.subTest(source=source),self.assertRaises(gate.Rejected):gate.check_proof_source(source,set(gate.MODULES))

    def test_required_history_theorems_cannot_be_dropped_from_inventory(self):
        path=self.root/'formal/ingress_admission/theorems.json'
        rows=[{'name':name,'module':'AdmissionSpec','levelParameters':[],
               'typeSha256':'0'*64,'axioms':[]} for name in gate.REQUIRED_THEOREMS]
        path.write_text(json.dumps({'theorems':rows}))
        self.assertEqual(set(gate.inventory(self.root)),set(gate.REQUIRED_THEOREMS))
        for omitted in gate.REQUIRED_THEOREMS:
            path.write_text(json.dumps({'theorems':[row for row in rows if row['name']!=omitted]}))
            with self.subTest(omitted=omitted),self.assertRaisesRegex(gate.Rejected,'Missing required'):
                gate.inventory(self.root)

if __name__=='__main__':unittest.main()
