"""Local regression for an exception at CPython's thread-status lock acquisition."""
from dataclasses import replace
import hashlib
import importlib.util
import json
from pathlib import Path
import socket
import socketserver
import sys
import threading
from unittest.mock import patch
ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT))
source=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'traffic_http.py'
spec=importlib.util.spec_from_file_location('lifecycle_fixture',source)
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
finish=threading.Event();entered=[threading.Event(),threading.Event()];finished=[];threads=[]
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        index=len(threads);threads.append(threading.current_thread());entered[index].set()
        try:finish.wait(4)
        finally:finished.append(index)
class InterruptedAcquire:
    def __init__(self,lock):self.lock=lock;self.calls=0
    def acquire(self,*args,**kwargs):
        self.calls+=1
        if self.calls==1:raise RuntimeError('injected status-lock interruption')
        return self.lock.acquire(*args,**kwargs)
    def release(self):return self.lock.release()
    def locked(self):return self.lock.locked()
app=module.BoundedHTTPServer(('127.0.0.1',0),Handler,
    traffic_profile=replace(module.TrafficProfile(),public_handlers=1))
a,b=socket.socketpair();c,d=socket.socketpair()
try:
    app.process_request(a,('127.0.0.1',1));assert entered[0].wait(2)
    first=app._request_threads[a]
    assert first.is_alive() and not finished
    wrapped=InterruptedAcquire(first._tstate_lock);first._tstate_lock=wrapped
    app._reap_request_threads()
    assert not first.is_alive() and not finished
    initial=app.http_admission.active
    app._reap_request_threads();retained=app.http_admission.active
    app.process_request(c,('127.0.0.1',2))
    if retained==0:assert entered[1].wait(2)
    result={'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
      'python':sys.version.split()[0],'configuredLimit':1,
      'reportedAliveAfterInterruption':first.is_alive(),
      'firstHandlerStillRunning':0 not in finished,
      'reservationsAfterException':initial,'reservationsAfterReobservation':retained,
      'liveHandlers':len(threads)-len(finished),'acceptedConnections':app.http_admission.accepted,
      'counterexample':retained==0 and len(threads)-len(finished)==2}
    print(json.dumps(result,sort_keys=True))
finally:
    finish.set()
    for t in threads:t.join(2)
    for s in (a,b,c,d):s.close()
    app.server_close()
