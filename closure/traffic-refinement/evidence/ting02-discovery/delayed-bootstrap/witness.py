"""A start interrupted after native launch but before Thread bootstrap."""
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import socket
import socketserver
import sys
import threading
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[3]))
from traffic_http import BoundedHTTPServer, TrafficProfile
source=Path('traffic_http.py').read_bytes()
bootstrap=threading.Event(); finish=threading.Event(); entered=[threading.Event(),threading.Event()]
threads=[]
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        index=len(threads);threads.append(threading.current_thread());entered[index].set();finish.wait(3)
original=threading.Thread
class StartedEvent:
    def __init__(self):self.real=threading.Event()
    def is_set(self):return self.real.is_set()
    def set(self):return self.real.set()
    def wait(self,*args):raise KeyboardInterrupt('injected before bootstrap publication')
class DelayedBootstrap(original):
    def __init__(self,*args,**kwargs):super().__init__(*args,**kwargs);self._started=StartedEvent()
    def _bootstrap(self):bootstrap.wait(3);super()._bootstrap()
app=BoundedHTTPServer(('127.0.0.1',0),Handler,traffic_profile=replace(TrafficProfile(),public_handlers=1))
left,right=socket.socketpair();second,other=socket.socketpair()
try:
    with patch('traffic_http.threading.Thread',DelayedBootstrap):
        try:app.process_request(left,('127.0.0.1',1))
        except KeyboardInterrupt:pass
    released_before_bootstrap=app.http_admission.active==0
    app.process_request(second,('127.0.0.1',2))
    if not entered[0].wait(2):raise RuntimeError('Second request did not start')
    bootstrap.set()
    if not entered[1].wait(2):raise RuntimeError('Delayed request did not start')
    result={'sourceSha256':hashlib.sha256(source).hexdigest(),'configuredLimit':1,
            'releasedBeforeBootstrap':released_before_bootstrap,
            'activeReservations':app.http_admission.active,'liveRequestThreads':sum(t.is_alive() for t in threads)}
    result['counterexample']=released_before_bootstrap and result['liveRequestThreads']==2
    if not result['counterexample']:raise AssertionError(result)
    print(json.dumps(result,sort_keys=True))
finally:
    bootstrap.set();finish.set()
    for thread in threads:thread.join(2)
    for sock in (left,right,second,other):sock.close()
    app.server_close()
