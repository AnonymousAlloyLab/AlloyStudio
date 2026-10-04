"""Finite production constructor state, in a fresh process and owned scratch."""
import json
import os
from pathlib import Path
import subprocess
import sys
import unittest

ROOT=Path(__file__).resolve().parents[1]

class ServiceInitialWitnessTests(unittest.TestCase):
    def test_actual_constructor_graph_with_no_jvm_or_provider_start(self):
        scratch=ROOT/'build/trf-closure/witness-test-scratch'
        scratch.mkdir(parents=True,exist_ok=True)
        environment=dict(os.environ,OPENAI_DISABLED='1',TMPDIR=str(scratch))
        result=subprocess.run([sys.executable,'-I','-c',
            'import runpy,sys;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
            'runpy.run_path(sys.argv[1]+"/scripts/service_initial_witness.py",run_name="__main__")',str(ROOT)],
            cwd=ROOT,env=environment,text=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=15)
        self.assertEqual(result.returncode,0,result.stderr)
        evidence=json.loads(result.stdout)
        self.assertEqual(evidence['status'],'PASS')
        self.assertEqual(evidence['jvmLaunches'],0)
        self.assertEqual(evidence['startedSchedulerThreads'],3)
        self.assertEqual(evidence['listenerCount'],1)
        self.assertFalse(evidence['secretValuesEmitted'])
        self.assertGreater(evidence['checkedCells'],300)

if __name__=='__main__':unittest.main()
