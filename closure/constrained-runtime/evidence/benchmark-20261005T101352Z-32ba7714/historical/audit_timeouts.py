"""Bounded synthetic witnesses; Python protocol children only, no JVM/provider."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
from unittest.mock import patch
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from engine_workers import EnginePool, EngineUnavailable
from runtime_dependencies import ProcessBudget
from traffic_scheduler import Scheduler, CapacityError
OUT = Path(__file__).resolve().parent
WORKER = OUT / 'delayed_worker.py'
WORKER.write_text('''import json,struct,sys,time
inc,lane=sys.argv[1:]
def send(x):
 b=json.dumps(x).encode();sys.stdout.buffer.write(struct.pack('>I',len(b))+b);sys.stdout.buffer.flush()
time.sleep(.18)
send({'protocol':1,'incarnation':inc,'ticket':0,'context':'','kind':'ready','result':{'status':'ready'}})
while True:
 p=sys.stdin.buffer.read(4)
 if not p:break
 f=json.loads(sys.stdin.buffer.read(struct.unpack('>I',p)[0]));time.sleep(.12)
 r={k:f[k] for k in ('protocol','incarnation','ticket','context','kind')}
 r.update(result={'status':'ok'},parseUnits=1);send(r)
''')
class Pool(EnginePool):
 def _command(self,lane,incarnation,directory):
  return [sys.executable,'-I',str(WORKER),incarnation,lane]
def answer(pool):
 before=time.monotonic()
 try: result=pool.evaluate('feedback',{},.25)['status']
 except EngineUnavailable as error: result=type(error).__name__
 return {'result':result,'seconds':round(time.monotonic()-before,4)}
report={}
pool=Pool(OUT/'cold','unused',feedback_workers=1,startup_timeout=1,budget=ProcessBudget())
try: report['cold_request']=answer(pool)
finally: pool.close()
pool=Pool(OUT/'warm','unused',feedback_workers=1,startup_timeout=1,budget=ProcessBudget())
try:
 pool.prewarm();report['same_warm_request']=answer(pool)
finally: pool.close()
budget=ProcessBudget();owners=[budget.reserve('feedback',1) for _ in range(2)]
pool=Pool(OUT/'capacity','unused',feedback_workers=1,budget=budget)
try:
 with patch('engine_workers.subprocess.Popen',side_effect=AssertionError('No child may launch')):
  for _ in range(3):
   try:pool.evaluate('feedback',{},.02)
   except EngineUnavailable:pass
  for owner in owners:budget.release_reaped(owner)
  try:pool.evaluate('feedback',{},1)
  except EngineUnavailable as error:failure=str(error)
  report['capacity_wait_poison']={'launches':pool.stats()['launches'],'failedStarts':pool.policies['feedback'].failed_starts,'resultAfterCapacityReleased':failure}
finally:
 for owner in owners:budget.release_reaped(owner)
 pool.close()
scheduler=Scheduler({'feedback':1,'behavior':1},queue_seconds=.08)
entered,release=threading.Event(),threading.Event()
def blocking():entered.set();release.wait(2);return {'status':'ok'}
try:
 with ThreadPoolExecutor(2) as executor:
  first=executor.submit(scheduler.run,'feedback',('blocking',),blocking)
  assert entered.wait(1)
  started=time.monotonic();second=executor.submit(scheduler.run,'feedback',('queued',),lambda:{'status':'ok'})
  time.sleep(.14)
  report['queue_expiry']={'queueSeconds':.08,'observedAfterSeconds':round(time.monotonic()-started,4),'completedAtObservation':second.done()}
  release.set();first.result(1)
  try:second.result(1)
  except CapacityError:report['queue_expiry']['eventualResult']='CapacityError'
finally:release.set();scheduler.close()
print(json.dumps(report,indent=2))
(OUT/'timeout-audit-before.json').write_text(json.dumps(report,indent=2)+'\n')
