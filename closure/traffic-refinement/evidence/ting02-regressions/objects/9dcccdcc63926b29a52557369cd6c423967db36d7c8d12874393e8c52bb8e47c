"""Historical TCFG03 constructor witness in a copied, immutable-input fixture."""
import json
import os
from pathlib import Path
import subprocess
import sys
import shutil
import tempfile
import unittest

WORKSPACE=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(WORKSPACE/'tests'))
from test_service_initial_bridge import verified_historical_root
ROOT=WORKSPACE/'closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/inputs'

class ServiceInitialWitnessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        assert verified_historical_root('tcfg03-20261004T170110Z-0c2dd9e4',
            'scripts/verify_service_profile.py',('scripts/service_initial_witness.py',
                                               'scripts/service_initial_bridge.py'))==ROOT

    def test_actual_constructor_graph_with_no_jvm_or_provider_start(self):
        scratch=WORKSPACE/'build/trf-closure/witness-test-scratch'
        scratch.mkdir(parents=True,exist_ok=True)
        environment=dict(os.environ,OPENAI_DISABLED='1',TMPDIR=str(scratch),PYTHONDONTWRITEBYTECODE='1')
        with tempfile.TemporaryDirectory(prefix='archived-tcfg03-',dir=scratch) as temporary:
            copied=Path(temporary)/'inputs'
            shutil.copytree(ROOT,copied)
            result=subprocess.run([sys.executable,'-I','-B','-c',
                'import runpy,sys;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
                'runpy.run_path(sys.argv[1]+"/scripts/service_initial_witness.py",run_name="__main__")',str(copied)],
                cwd=copied,env=environment,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)
        evidence=json.loads(result.stdout)
        self.assertEqual(evidence['status'],'PASS')
        self.assertEqual(evidence['jvmLaunches'],0)
        self.assertEqual(evidence['startedSchedulerThreads'],3)
        self.assertEqual(evidence['listenerCount'],1)
        self.assertFalse(evidence['secretValuesEmitted'])
        self.assertGreater(evidence['checkedCells'],300)

if __name__=='__main__':unittest.main()
