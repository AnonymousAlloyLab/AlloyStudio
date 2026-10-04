"""Deterministic local thread-start lifecycle regression; no HTTP requests."""
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
identified=threading.Event();resume=threading.Event();finish=threading.Event()
first_entered=threading.Event();second_entered=threading.Event();threads=[]
original=threading.Thread
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        threads.append(threading.current_thread())
        (first_entered if isinstance(threading.current_thread(),PausedIdent) else second_entered).set()
        finish.wait(4)
class StartedEvent:
    def __init__(self):self.real=threading.Event()
    def is_set(self):return self.real.is_set()
    def set(self):return self.real.set()
    def wait(self,*args):
        if not identified.wait(3):raise RuntimeError('ident checkpoint unavailable')
        raise KeyboardInterrupt('injected interrupted start wait')
class PausedIdent(original):
    def __init__(self,*args,**kwargs):
        super().__init__(*args,**kwargs);self._started=StartedEvent()
    def _set_ident(self):
        super()._set_ident();identified.set()
        if not resume.wait(3):raise RuntimeError('ident checkpoint timed out')
app=module.BoundedHTTPServer(('127.0.0.1',0),Handler,
    traffic_profile=replace(module.TrafficProfile(),public_handlers=1))
a,b=socket.socketpair();c,d=socket.socketpair()
first=None
try:
    with patch.object(module.threading,'Thread',PausedIdent):
        try:app.process_request(a,('127.0.0.1',1))
        except KeyboardInterrupt:pass
    first=app._request_threads[a]
    assert first.ident is not None and not first.is_alive()
    app._reap_request_threads()
    at_cut=app.http_admission.active
    app.process_request(c,('127.0.0.1',2))
    if at_cut==0:assert second_entered.wait(2)
    resume.set();assert first_entered.wait(2)
    result={'sourceSha256':hashlib.sha256(source.read_bytes()).hexdigest(),
      'python':sys.version.split()[0],'configuredLimit':1,'identPublished':True,
      'startedPublishedAtCut':False,'inheritedIsAliveAtCut':False,
      'reservationsAfterReap':at_cut,'liveHandlers':len(threads),
      'acceptedConnections':app.http_admission.accepted,
      'counterexample':at_cut==0 and len(threads)==2}
    print(json.dumps(result,sort_keys=True))
finally:
    resume.set();finish.set()
    if first is not None:
        first._started.real.wait(3);first.join(2)
    for t in threads:t.join(2)
    for s in (a,b,c,d):s.close()
    app._reap_request_threads();app.server_close()
