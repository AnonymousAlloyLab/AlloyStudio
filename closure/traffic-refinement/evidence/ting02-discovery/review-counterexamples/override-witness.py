import sys,types,socket,threading,json,hashlib
from pathlib import Path
r=Path(__file__).resolve().parents[3];sys.path[:0]=[str(r),str(r/'scripts')]
import strict_ingress_bridge as route, strict_ingress_deadline_bridge as deadlines, admission_bridge as admission
import server
from traffic_http import BoundedHTTPServer
source=(r/'server.py').read_text().replace("    server_version = 'AlloyPractice/1.0'", "    server_version = 'AlloyPractice/1.0'\n\n    def handle(self):\n        self.server.scheduler.issue_channel(self.client_address[0])")
checks={'route':route.extract(r,server_source=source)['status'], 'deadline':len(deadlines.extraction(r,server_source=source)['programs']), 'admission':len(admission.extract(r,{'server.py':source})['shapes'])}
module=types.ModuleType('mutated_ingress');module.__file__=str(r/'server.py');exec(compile(source,str(r/'server.py'),'exec'),module.__dict__)
seen=threading.Event();app=BoundedHTTPServer(('127.0.0.1',0),module.Handler);app.scheduler=types.SimpleNamespace(issue_channel=lambda peer:seen.set())
thread=threading.Thread(target=app.serve_forever,kwargs={'poll_interval':.01},daemon=True);thread.start()
try:
 connection=socket.create_connection(app.server_address,timeout=2)
 dispatched=seen.wait(2)
 connection.close()
finally:app.shutdown();app.server_close();thread.join(2)
result={'sourceSha256':hashlib.sha256((r/'server.py').read_bytes()).hexdigest(),'mutation':'new Handler.handle overrides stdlib receive/parse dispatch','preRepairBridges':checks,'sentBytes':0,'businessChannelIssued':dispatched}
Path(__file__).with_name('override-result.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result));assert dispatched
