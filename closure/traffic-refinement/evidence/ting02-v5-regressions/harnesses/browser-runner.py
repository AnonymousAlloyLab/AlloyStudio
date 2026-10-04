import hashlib,json,os,shutil,subprocess,sys,time
from pathlib import Path
workspace=Path.cwd();owned=workspace/'build/trf-closure/trf01-browser'
stage=owned/('snapshot-'+time.strftime('%Y%m%dT%H%M%SZ',time.gmtime()));stage.mkdir()
files=set(json.loads((workspace/'closure/traffic-refinement/ingress-deadline-sources.json').read_text())['files'])
files.update(['traffic_decode.py','LICENSE','vendor/acgn/LICENSE','deploy/iis/README.md','vendor/sqlean/provenance.json','docs/private-exercises.md','docs/sqlite-security-spec.md','docs/admin-security-spec.md','docs/admin-setup.md'])
for glob in ('tests/*.mjs','tests/fixtures/*.json','build/engine/classes/**/*.class'):
 files.update(p.relative_to(workspace).as_posix() for p in workspace.glob(glob) if p.is_file())
def checked(name):
 p=Path(name)
 if p.is_absolute() or '..' in p.parts or any(part.startswith('.env') or part in ('admin.local.json','openai.local.json','secrets') for part in p.parts):raise RuntimeError('Forbidden source input')
 source=workspace
 for part in p.parts:
  source=source/part
  if source.is_symlink():raise RuntimeError('Linked source input')
 if not source.is_file():raise RuntimeError('Missing explicit source input: '+name)
 return source
hashes={}
for name in sorted(files):
 source=checked(name);content=source.read_bytes();hashes[name]=hashlib.sha256(content).hexdigest();target=stage/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(content)
for name in ('admin.local.json','openai.local.json','.env','.env.example'):
 assert not (stage/name).exists()
scratch=stage/'build/ci/tmp';scratch.mkdir(parents=True)
env={**os.environ,'OPENAI_DISABLED':'1','TMPDIR':str(scratch),'TMP':str(scratch),'TEMP':str(scratch)}
for name in ('OPENAI_API_KEY','OPENAI_CONFIG_FILE','ALLOY_ENGINE_MODE'):
 env.pop(name,None)
report={'kind':'isolated-existing-binaries-browser-regression','sourceFiles':hashes,'sourceIdentitySha256':hashlib.sha256(json.dumps(hashes,sort_keys=True,separators=(',',':')).encode()).hexdigest(),'stage':str(stage.relative_to(workspace)),'commands':[],'providerDisabled':True,'engineRecompiled':False,'privateConfigsCopied':False}
program="import json,sys;from pathlib import Path;sys.path.insert(0,str(Path.cwd()));from scripts.package_iis import build_package;print(json.dumps(build_package(Path.cwd(),Path.cwd()/'build/iis/alloy-studio-iis.zip')))"
commands=[('package',[sys.executable,'-I','-B','-c',program],120),('browser',['node','tests/browser-suite.mjs'],1000),('dashboard',['node','tests/dashboard.mjs'],240)]
for label,cmd,limit in commands:
 started=time.monotonic()
 try:
  p=subprocess.run(cmd,cwd=stage,env=env,capture_output=True,text=True,timeout=limit)
  (stage/f'{label}.stdout').write_text(p.stdout);(stage/f'{label}.stderr').write_text(p.stderr)
  result=json.loads(p.stdout.strip().splitlines()[-1]) if p.returncode==0 else {'status':'FAIL'}
  row={'command':label,'exitCode':p.returncode,'elapsedSeconds':round(time.monotonic()-started,3),'result':result}
 except subprocess.TimeoutExpired:
  row={'command':label,'exitCode':None,'elapsedSeconds':round(time.monotonic()-started,3),'result':{'status':'TIMEOUT'}}
 report['commands'].append(row)
 (owned/'report.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
 print(json.dumps({'command':label,'exitCode':row['exitCode'],'status':row['result'].get('status','PACKAGED'),'checks':row['result'].get('checks'),'stage':report['stage']}),flush=True)
 if row['exitCode']!=0:break
report['sourceSnapshotStable']=all(hashlib.sha256((stage/name).read_bytes()).hexdigest()==digest for name,digest in hashes.items())
report['workspaceChangesSinceCopy']=[name for name,digest in hashes.items() if hashlib.sha256((workspace/name).read_bytes()).hexdigest()!=digest]
report['status']='PASS' if len(report['commands'])==3 and all(row['exitCode']==0 for row in report['commands']) and report['sourceSnapshotStable'] else 'FAIL'
(owned/'report.json').write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
print(json.dumps({'status':report['status'],'report':str((owned/'report.json').relative_to(workspace))}),flush=True)
raise SystemExit(0 if report['status']=='PASS' else 1)
