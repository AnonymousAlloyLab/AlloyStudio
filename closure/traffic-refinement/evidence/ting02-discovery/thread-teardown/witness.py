"""Deterministic live-thread counterexample against the pre-repair source."""
import hashlib
import json
from pathlib import Path
import socket
import sys
import threading
from dataclasses import replace
sys.path.insert(0, str(Path(__file__).resolve().parents[3]))
from traffic_http import BoundedHTTPServer, TrafficProfile
import socketserver
source=Path('traffic_http.py').read_bytes()
exits=[threading.Event(), threading.Event()]
entered=[threading.Event(), threading.Event()]
threads=[]
class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        threads.append(threading.current_thread())
app=BoundedHTTPServer(('127.0.0.1',0),Handler,traffic_profile=replace(TrafficProfile(),public_handlers=1))
release=app.http_admission.release
count=[0]
def paused_release(owner=None):
    release(owner)
    index=count[0];count[0]+=1
    entered[index].set()
    if not exits[index].wait(3):raise RuntimeError('Witness synchronization failed')
app.http_admission.release=paused_release
serving=threading.Thread(target=app.serve_forever,kwargs={'poll_interval':.005},daemon=True)
serving.start()
try:
    for i in range(2):
        with socket.create_connection(app.server_address,timeout=2):
            if not entered[i].wait(2):raise RuntimeError('Handler did not reach post-release cut')
    result={'sourceSha256':hashlib.sha256(source).hexdigest(),'configuredHandlerLimit':1,
            'activeReservations':app.http_admission.active,
            'liveRequestThreads':sum(t.is_alive() for t in threads),
            'counterexample':len(threads)==2 and all(t.is_alive() for t in threads)}
    if not result['counterexample']:raise AssertionError(result)
    print(json.dumps(result,sort_keys=True))
finally:
    for event in exits:event.set()
    app.shutdown();app.server_close();serving.join(2)
