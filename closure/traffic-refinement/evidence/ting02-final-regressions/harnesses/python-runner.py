import os,sys,json,hashlib,shutil,subprocess,time
from pathlib import Path
from datetime import datetime,timezone
r=Path.cwd();base=r/'build/trf-closure'/datetime.now(timezone.utc).strftime('ting02-python-%Y%m%dT%H%M%SZ');base.mkdir();stage=base/'source';stage.mkdir()
files=set(subprocess.check_output(['git','ls-files','-z']).decode().split('\0'))|set(subprocess.check_output(['git','ls-files','--others','--exclude-standard','-z']).decode().split('\0'))
files.discard('');files.discard('.env.example')
# Paths only are selected before any file is opened; no local credentials enter the stage.
files={n for n in files if not any(x in ('openai.local.json','admin.local.json','.env','secrets','node_modules','.git','build') or x.startswith('.env.') for x in Path(n).parts)}
manifest={}
for name in sorted(files):
 p=r/name
 if not p.is_file() or p.is_symlink():raise RuntimeError('Invalid stage source path')
 value=p.read_bytes();manifest[name]=hashlib.sha256(value).hexdigest();q=stage/name;q.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,q)
shutil.copytree(r/'build/engine/classes',stage/'build/engine/classes')
for p in (stage/'build/engine/classes').rglob('*'):
 if p.is_file():manifest[p.relative_to(stage).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
(base/'inputs.json').write_text(json.dumps(manifest,sort_keys=True,indent=2)+'\n')
scratch=stage/'build/trf-closure/tmp';scratch.mkdir(parents=True,exist_ok=True)
env=dict(os.environ,OPENAI_DISABLED='1',TMPDIR=str(scratch),TMP=str(scratch),TEMP=str(scratch),PYTHONDONTWRITEBYTECODE='1')
started=time.monotonic()
with (base/'unittest.txt').open('wb') as log: result=subprocess.run([sys.executable,'-B','-m','unittest','discover','-s','tests','-v'],cwd=stage,env=env,stdout=log,stderr=subprocess.STDOUT)
import re
raw=(base/'unittest.txt').read_bytes();count=re.search(rb'Ran ([0-9]+) tests? in ',raw)
changed=[name for name,h in manifest.items() if not (stage/name).is_file() or hashlib.sha256((stage/name).read_bytes()).hexdigest()!=h]
report=dict(status='PASS' if result.returncode==0 and count and not changed else 'FAIL',exitCode=result.returncode,tests=int(count[1]) if count else 0,elapsedSeconds=time.monotonic()-started,inputManifestSha256=hashlib.sha256((base/'inputs.json').read_bytes()).hexdigest(),logSha256=hashlib.sha256(raw).hexdigest(),changedInputPaths=changed,stage=str(stage.relative_to(r)),excluded=['private deployment configs','.env files','.env.example','native Windows/macOS execution'],providersDisabled=True)
(base/'report.json').write_text(json.dumps(report,sort_keys=True,indent=2)+'\n');print(json.dumps(report));print(base)
