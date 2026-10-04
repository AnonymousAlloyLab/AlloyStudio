"""Persistent worker transport/lifecycle adversaries and real JVM reuse witnesses."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from engine_workers import EnginePool, EngineTimeout, EngineUnavailable, RESPONSE_BYTES
from runtime_dependencies import (ProcessBudget, clean_java_environment, run_engine, runtime_classpath,
                                  open_engine_admission)

ROOT = Path(__file__).resolve().parents[1]
FAKE = r'''
import json,os,struct,sys,time
inc,lane,mode=sys.argv[1:]
def send(value):
 b=json.dumps(value,separators=(',',':')).encode();sys.stdout.buffer.write(struct.pack('>I',len(b))+b);sys.stdout.buffer.flush()
ready={'protocol':1,'incarnation':inc,'ticket':0,'context':'','kind':'ready','result':{'status':'ready'}}
if mode=='startup_hang':time.sleep(30)
send(ready)
if mode=='neverread':time.sleep(30)
while True:
 p=sys.stdin.buffer.read(4)
 if not p:break
 n=struct.unpack('>I',p)[0];f=json.loads(sys.stdin.buffer.read(n));r={k:f[k] for k in ('protocol','incarnation','ticket','context','kind')}
 r.update(result={'status':'ok','value':f['request'].get('value'),'env': sorted(os.environ)},parseUnits=1)
 if mode=='hang':time.sleep(30)
 if mode=='crash':sys.exit(9)
 if mode=='stdout':sys.stdout.buffer.write(b'PRIVATE_DIAGNOSTIC');sys.stdout.buffer.flush();time.sleep(30)
 if mode=='oversize':sys.stdout.buffer.write(struct.pack('>I',4294967295));sys.stdout.buffer.flush();time.sleep(30)
 if mode=='truncated':sys.stdout.buffer.write(struct.pack('>I',120)+b'{}');sys.stdout.buffer.flush();sys.exit(0)
 if mode=='stale':r['ticket']-=1
 if mode=='incarnation':r['incarnation']='0'
 if mode=='context':r['context']='0'*64
 if mode=='kind':r['kind']='other'
 if mode=='bool_ticket':r['ticket']=True
 if mode=='extra':r['secret']='PRIVATE_DIAGNOSTIC'
 if mode=='nan':r['result']['value']=float('nan')
 if mode=='negative_parses':r['parseUnits']=-1
 if mode=='invalid_utf8':sys.stdout.buffer.write(struct.pack('>I',2)+b'\xff\xff');sys.stdout.buffer.flush();continue
 if mode=='duplicate_field':
  b=json.dumps(r).encode();b=b[:-1]+b',"protocol":1}';sys.stdout.buffer.write(struct.pack('>I',len(b))+b);sys.stdout.buffer.flush();continue
 if mode=='delay':time.sleep(.07)
 send(r)
 if mode=='duplicate':send(r)
'''


class FakePool(EnginePool):
    def __init__(self, *args, script, mode='ok', **kwargs):
        self.script, self.mode = script, mode
        super().__init__(*args, **kwargs)

    def _command(self, lane, incarnation, directory):
        return [sys.executable, '-I', str(self.script), incarnation, lane, self.mode]


class WorkerProtocolTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        base = ROOT / 'build/tests'
        base.mkdir(exist_ok=True, parents=True)
        cls.temp = tempfile.TemporaryDirectory(prefix='worker-protocol-', dir=base)
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.script = cls.root / 'fake_worker.py'
        cls.script.write_text(FAKE)

    def pool(self, **kwargs):
        budget = ProcessBudget()
        pool = FakePool(self.root, 'unused', script=self.script, budget=budget, **kwargs)
        self.addCleanup(pool.close)
        return pool

    def test_prewarm_and_100_requests_have_no_new_launches(self):
        pool = self.pool()
        initial = pool.prewarm()
        self.assertEqual(initial['launches'], 3)
        for index in range(100):
            self.assertEqual(pool.evaluate('feedback', {'value': index}, 2)['value'], index)
        self.assertEqual(pool.stats()['launches'], 3)
        self.assertEqual(pool.stats()['completed'], 100)
        self.assertEqual(pool.close()['processBudget']['reserved'], 0)

    def test_minimal_environment_excludes_credentials_options_and_unknowns(self):
        pool = self.pool()
        with patch.dict(os.environ, {'OPENAI_API_KEY': 'fake-secret', 'ADMIN_PASSWORD': 'fake-secret',
                                     'UNRELATED_SECRET': 'fake-secret', 'JAVA_TOOL_OPTIONS': '-Dbad=true'}):
            result = pool.evaluate('feedback', {}, 3)
        for key in ('OPENAI_API_KEY', 'ADMIN_PASSWORD', 'UNRELATED_SECRET', 'JAVA_TOOL_OPTIONS'):
            self.assertNotIn(key, result['env'])
        self.assertEqual(clean_java_environment({'SystemRoot': 'C:\\Windows', 'Path': 'x', 'SECRET':'x'}),
                         {'SystemRoot': 'C:\\Windows', 'Path': 'x'})

    def test_frame_faults_retire_and_reap_without_retry_or_diagnostic(self):
        modes = ('stale','incarnation','context','kind','bool_ticket','extra','nan','negative_parses',
                 'invalid_utf8','duplicate_field','oversize','truncated','stdout','crash')
        for mode in modes:
            with self.subTest(mode=mode):
                pool = self.pool(mode=mode)
                with self.assertRaises(EngineUnavailable) as error:
                    pool.evaluate('feedback', {}, 3)
                self.assertNotIn('PRIVATE', str(error.exception))
                self.assertEqual(pool.stats()['launches'], 1)
                self.assertEqual(pool.stats()['processBudget']['reserved'], 0)
                self.assertEqual(list((self.root / 'build/runtime/tmp').iterdir()), [])

    def test_deadline_covers_stalled_response_and_stalled_request_pipe(self):
        for mode, request in [('hang', {}), ('neverread', {'value':'x'*300000})]:
            with self.subTest(mode=mode):
                pool = self.pool(mode=mode)
                start = time.monotonic()
                with self.assertRaises(EngineTimeout):
                    pool.evaluate('feedback', request, .25)
                self.assertLess(time.monotonic()-start, 2)
                self.assertEqual(pool.stats()['processBudget']['reserved'], 0)

    def test_startup_timeout_reaps_failed_constructor(self):
        pool = self.pool(mode='startup_hang', startup_timeout=.1)
        with self.assertRaises(EngineTimeout):
            pool.evaluate('feedback', {}, 2)
        self.assertEqual(pool.stats()['processBudget']['reserved'], 0)

    def test_duplicate_frame_cannot_finish_next_ticket(self):
        pool = self.pool(mode='duplicate')
        try:
            pool.evaluate('feedback', {'value':1}, 2)
        except EngineUnavailable:
            pass
        # The duplicate may arrive before or after first delivery; either path
        # poisons that incarnation, rather than satisfying another request.
        time.sleep(.1)
        pool.mode='ok'
        self.assertEqual(pool.evaluate('feedback', {'value':2}, 2)['value'], 2)
        self.assertEqual(pool.stats()['launches'], 2)

    def test_recycling_reaps_before_replacement_and_global_admin_bound(self):
        pool = self.pool(max_tasks=1)
        pool.prewarm()
        owner = pool.budget.reserve('admin', .1)
        for index in range(5):
            pool.evaluate('feedback', {'value':index}, 2)
        self.assertEqual(pool.budget.stats()['highWater'], 4)
        self.assertLessEqual(pool.budget.stats()['reserved'], 4)
        pool.budget.release_reaped(owner)
        self.assertEqual(pool.close()['processBudget']['reserved'], 0)

    def test_parallel_lanes_and_waiting_deadlines(self):
        pool = self.pool(mode='delay')
        pool.prewarm()
        with ThreadPoolExecutor(max_workers=8) as executor:
            requests = [executor.submit(pool.evaluate, 'behavior' if i%3==0 else 'feedback', {'value':i}, 3)
                        for i in range(24)]
            self.assertEqual([request.result()['value'] for request in requests], list(range(24)))
        self.assertEqual(pool.stats()['launches'], 3)
        self.assertEqual(pool.stats()['processBudget']['highWater'], 3)

    def test_pool_closed_during_constructor_reaps_new_process(self):
        pool = self.pool()
        original = pool._launched
        def launched():
            original()
            # Reentrant close from this constructor cannot wait for itself.
            # The caller explicitly requests a nonblocking ownership snapshot.
            self.assertEqual(pool.close(timeout=0)['starting'], 1)
        pool._launched=launched
        with self.assertRaises(EngineUnavailable):
            pool.evaluate('feedback', {}, 2)
        self.assertEqual(pool.budget.stats()['reserved'], 0)

    def test_close_waits_for_starting_handshake_and_reaping(self):
        pool = self.pool(mode='startup_hang', startup_timeout=.3)
        with ThreadPoolExecutor(1) as executor:
            future = executor.submit(pool.evaluate, 'feedback', {}, 2)
            deadline = time.monotonic() + 1
            while pool.stats()['launches'] != 1:
                self.assertLess(time.monotonic(), deadline)
                time.sleep(.001)
            status = pool.close()
            # A PID exists before its ready frame registers an idle worker.
            # Closing must include that owner, rather than returning cleanly.
            self.assertEqual(status['starting'], 0)
            self.assertEqual(status['unreaped'], 0)
            self.assertEqual(status['processBudget']['reserved'], 0)
            with self.assertRaises(EngineUnavailable):
                future.result(1)

    def test_close_deadline_reports_still_starting_instead_of_clean_exit(self):
        pool = self.pool(mode='startup_hang', startup_timeout=.4)
        with ThreadPoolExecutor(1) as executor:
            future = executor.submit(pool.evaluate, 'feedback', {}, 2)
            deadline = time.monotonic() + 1
            while pool.stats()['launches'] != 1:
                self.assertLess(time.monotonic(), deadline)
                time.sleep(.001)
            started = time.monotonic()
            status = pool.close(timeout=.02)
            self.assertLess(time.monotonic() - started, .3)
            self.assertEqual(status['starting'], 1)
            self.assertEqual(status['processBudget']['reserved'], 1)
            with self.assertRaises(EngineUnavailable):
                future.result(1)
        status = pool.close(timeout=1)
        self.assertEqual(status['starting'], 0)
        self.assertEqual(status['unreaped'], 0)
        self.assertEqual(status['processBudget']['reserved'], 0)

    def test_closing_constructor_waiting_for_capacity_cannot_launch_after_release(self):
        pool = self.pool()
        owners = [pool.budget.reserve('feedback', 1) for _ in range(2)]
        try:
            with ThreadPoolExecutor(1) as executor:
                future = executor.submit(pool.evaluate, 'feedback', {}, 2)
                deadline = time.monotonic() + 1
                while pool.stats()['starting'] != 1:
                    self.assertLess(time.monotonic(), deadline)
                    time.sleep(.001)
                status = pool.close(timeout=.02)
                self.assertEqual(status['starting'], 1)
                for owner in owners:
                    pool.budget.release_reaped(owner)
                with self.assertRaises(EngineUnavailable):
                    future.result(1)
            status = pool.close(timeout=1)
            self.assertEqual(status['launches'], 0)
            self.assertEqual(status['starting'], 0)
            self.assertEqual(status['processBudget']['reserved'], 0)
        finally:
            for owner in owners:
                pool.budget.release_reaped(owner)

    def test_expired_deadline_cannot_launch_a_new_worker(self):
        pool = self.pool()
        with self.assertRaises(EngineTimeout):
            pool._acquire('feedback', time.monotonic() - 1)
        self.assertEqual(pool.stats()['launches'], 0)
        self.assertEqual(pool.budget.stats()['reserved'], 0)

    def test_bounds_are_checked_before_launch(self):
        pool = self.pool()
        for invalid in ({'value':'x'*1048576}, {'value':float('nan')}):
            with self.assertRaises(EngineUnavailable):
                pool.evaluate('feedback', invalid, 1)
        self.assertEqual(pool.stats()['launches'], 0)

    def test_startup_circuit_breaker_does_not_retry_forever(self):
        pool = self.pool(mode='crash')
        for _ in range(12):
            with self.assertRaises(EngineUnavailable):
                pool.evaluate('feedback', {}, 2)
        with self.assertRaises(EngineUnavailable):
            pool.evaluate('feedback', {}, 2)
        self.assertEqual(pool.stats()['launches'], 12)
        self.assertEqual(pool.stats()['processBudget']['reserved'], 0)

    def test_unreaped_owner_is_retained_until_actual_wait(self):
        pool = self.pool()
        pool.prewarm()
        worker = pool.workers['feedback'][0]
        process = worker.process
        with patch.object(process, 'wait', side_effect=subprocess.TimeoutExpired('safe', 1)):
            pool._retire(worker)
        self.assertEqual(pool.budget.stats()['reserved'], 3)
        self.assertEqual(len(pool.unreaped), 1)
        pool._retire(worker)
        self.assertEqual(pool.budget.stats()['reserved'], 2)
        self.assertEqual(len(pool.unreaped), 0)


class ActualWorkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = shutil.which('java')
        if not cls.java or not (ROOT/'build/engine/classes/live/EngineWorker.class').is_file():
            raise unittest.SkipTest('Compiled EngineWorker and Java required')
        # This class runs before WorkerProtocolTests in a clean checkout; own
        # both the evidence and compiler scratch parent instead of relying on
        # a different test class (or a prior local run) to have created it.
        cls.scratch = ROOT / 'build/tests'
        cls.scratch.mkdir(parents=True, exist_ok=True)

    def setUp(self):
        open_engine_admission(ROOT)
        self.pool = EnginePool(ROOT, self.java, budget=ProcessBudget())
        self.addCleanup(self.pool.close)

    @staticmethod
    def request(body='some A', **extra):
        value={'studentSource': 'sig A {}\npred inv { '+body+' }',
               'oracleSource': 'sig A {}\npred inv { no A }', 'predicate':'inv'}
        value.update(extra)
        return value

    def fresh(self, request, kind='feedback'):
        name='live.LiveFeedback' if kind=='feedback' else 'live.BehaviorFeedback'
        completed=run_engine([self.java,'-Dfile.encoding=UTF-8','-Xmx256m','-XX:ActiveProcessorCount=2',
                              '-cp',runtime_classpath(ROOT),name],root=ROOT,input=json.dumps(request),
                             text=True,encoding='utf-8',capture_output=True,timeout=20)
        self.assertEqual(completed.returncode,0)
        return json.loads(completed.stdout)

    def test_fresh_warm_equivalence_after_mixed_history_and_parser_errors(self):
        cases=[self.request(),self.request('no A',metric='ast'),self.request('some B'),
               self.request('all x: A | x in A'),self.request('some A',metric='ast'),
               self.request('some A',oracleSource='sig A {} pred inv { bad }'),
               self.request('some A',metric='canonical')]
        for request in cases:
            with self.subTest(request=request):
                self.assertEqual(self.pool.evaluate('feedback',request,20),self.fresh(request))
        self.assertEqual(self.pool.stats()['launches'],1)
        worker=self.pool.workers['feedback'][0]
        self.assertEqual(list(worker.directory.iterdir()),[])

    def test_100_distinct_valid_edits_after_prewarm_start_no_jvms(self):
        self.pool.prewarm()
        launches=self.pool.stats()['launches']
        started=time.monotonic()
        for index in range(100):
            request=self.request(metric='canonical' if index%2==0 else 'ast')
            request['studentSource']='sig A {}\npred inv { '+(' ' * index)+'some A }'
            response=self.pool.evaluate('feedback',request,20)
            self.assertEqual(response['status'],'ok')
            self.assertEqual(response['distance'],1)
        self.assertEqual(self.pool.stats()['launches'],launches)
        report={'status':'PASS','edits':100,'initialLaunches':launches,'additionalLaunches':0,
                'durationSeconds':round(time.monotonic()-started,3), 'stats':self.pool.stats(),
                'profile':{'maxTasks':self.pool.max_tasks,'maxParseUnits':self.pool.max_parse_units,
                           'maxAgeSeconds':self.pool.max_age_seconds}}
        (self.scratch/'worker-100-edit-report.json').write_text(json.dumps(report,indent=2)+'\n')

    def test_java_worker_rejects_non_json_and_oversized_protocol_frames(self):
        command=[self.java,'-Dfile.encoding=UTF-8','-Xmx256m','-XX:ActiveProcessorCount=2',
                 '-cp',runtime_classpath(ROOT),'live.EngineWorker','123','feedback']
        valid={'protocol':1,'incarnation':'123','ticket':1,'context':'a'*64,'kind':'feedback',
               'request':self.request()}
        encoded=json.dumps(valid).encode()
        invalid=[encoded+b' trailing',encoded.replace(b'"protocol"',b"'protocol'"),
                 encoded[:-1]+b',"protocol":1}',encoded.replace(b'"ticket": 1',b'"ticket": 01'),
                 encoded.replace(b'"ticket": 1',b'"ticket": NaN'),b'\xff\xff']
        for body in invalid:
            with self.subTest(body=body[:20]):
                result=run_engine(command,root=ROOT,input=struct.pack('>I',len(body))+body,
                                  capture_output=True,timeout=5)
                length=struct.unpack('>I',result.stdout[:4])[0]
                self.assertEqual(len(result.stdout),4+length,'Only the ready frame may be returned')
                self.assertEqual(result.stderr,b'')
        result=run_engine(command,root=ROOT,input=struct.pack('>I',4294967295),
                          capture_output=True,timeout=5)
        length=struct.unpack('>I',result.stdout[:4])[0]
        self.assertEqual(len(result.stdout),4+length)

    def test_fatal_classification_and_parser_adapter_avoid_exit_registrations(self):
        compiler=shutil.which('javac')
        if not compiler:
            self.skipTest('JDK required')
        with tempfile.TemporaryDirectory(prefix='worker-boundary-', dir=self.scratch) as directory:
            source=Path(directory)/'WorkerBoundaryProbe.java'
            source.write_text(r'''package live;
import java.nio.file.*;
import java.util.*;
import edu.mit.csail.sdg.alloy4.ErrorFatal;
public class WorkerBoundaryProbe {
 public static void main(String[] args) throws Exception {
  WorkerSafety.rethrowFatal(new OutOfMemoryError());
  Path dir=Files.createDirectory(Path.of(System.getProperty("java.io.tmpdir")).resolve("job"));
  var field=Class.forName("java.io.DeleteOnExitHook").getDeclaredField("files");field.setAccessible(true);
  int before=((Set<?>)field.get(null)).size();
  WorkerSafety.begin(dir);
  for(int i=0;i<10;i++)WorkerSafety.parse("sig A {} pred inv { some A }");
  if(((Set<?>)field.get(null)).size()!=before)throw new AssertionError("exit registrations grew");
  WorkerSafety.rethrowFatal(new IllegalArgumentException());
  Throwable[] errors={new OutOfMemoryError(),new StackOverflowError(),new LinkageError(),
                     new ErrorFatal("private"),new RuntimeException(new OutOfMemoryError())};
  for(Throwable error:errors){boolean caught=false;try{WorkerSafety.rethrowFatal(error);}catch(Error poison){caught=true;}
   if(!caught)throw new AssertionError("fatal swallowed");}
  if(WorkerSafety.end()!=10)throw new AssertionError("parse count");
  System.out.println("worker boundary passed");
 }
}''')
            compilation=subprocess.run([compiler,'--release','17','-cp',runtime_classpath(ROOT),
                                        '-d',directory,str(source)],capture_output=True,timeout=20)
            self.assertEqual(compilation.returncode,0,compilation.stderr)
            command=[self.java,'--add-opens=java.base/java.io=ALL-UNNAMED','-Dfile.encoding=UTF-8',
                     '-cp',directory+os.pathsep+runtime_classpath(ROOT),'live.WorkerBoundaryProbe']
            result=run_engine(command,root=ROOT,capture_output=True,text=True,timeout=10)
            self.assertEqual(result.returncode,0,result.stderr)
            self.assertEqual(result.stdout.strip(),'worker boundary passed')

    def test_behavior_fresh_and_warm_equivalence_including_facts(self):
        for body in ('some A','no A','some A'):
            request=self.request(body,studentBody=body)
            request['studentSource']='sig A {} fact { lone A }\npred inv { '+body+' }'
            request['oracleSource']='sig A {} fact { lone A }\npred inv { no A }'
            self.assertEqual(self.pool.evaluate('behavior',request,20),self.fresh(request,'behavior'))
        self.assertEqual(self.pool.stats()['launches'],1)
        self.assertEqual(list(self.pool.workers['behavior'][0].directory.iterdir()),[])

    def test_late_invalid_reference_still_invalidates_complete_pool(self):
        request=self.request()
        del request['oracleSource']
        request.update(referencePrefix='sig A {}\npred inv {',referenceSuffix='}',
                       referenceBodies=['some A','some MISSING'])
        for metric in ('canonical','ast'):
            request['metric']=metric
            result=self.pool.evaluate('feedback',request,20)
            self.assertEqual(result,self.fresh(request))
            self.assertEqual(result['status'],'engine_error')
            self.assertEqual(result['diagnostics'][0]['code'],'REFERENCE_POOL_UNAVAILABLE')


if __name__=='__main__':
    unittest.main()
