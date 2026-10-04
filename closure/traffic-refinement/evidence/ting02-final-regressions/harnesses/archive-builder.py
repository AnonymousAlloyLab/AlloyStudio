import hashlib,json,re
from pathlib import Path
root=Path.cwd();archive=root/'closure/traffic-refinement/evidence/ting02-final-regressions'
if (archive/'index.json').exists():raise RuntimeError('Archive already exists')
sha=lambda data:hashlib.sha256(data).hexdigest()
artifacts={};objects={};origins={}
def artifact(source,name):
 data=source.read_bytes()
 if re.search(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}',data):raise RuntimeError('Credential-shaped artifact; inspect privately before preservation')
 target=archive/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
 artifacts[name]={'sha256':sha(data),'bytes':len(data),'origin':source.relative_to(root).as_posix()}
base=root/'build/trf-closure/ting02-python-20261004T182729Z'
report=json.loads((base/'report.json').read_bytes());manifest=json.loads((base/'inputs.json').read_bytes())
assert report['status']=='PASS' and report['tests']==1047 and report['changedInputPaths']==[]
assert sha((base/'inputs.json').read_bytes())==report['inputManifestSha256']
assert sha((base/'unittest.txt').read_bytes())==report['logSha256']
for name in ['report.json','inputs.json','unittest.txt']:artifact(base/name,'artifacts/python-final/'+name)
for name,digest in manifest.items():
 path=Path(name)
 if path.is_absolute() or '..' in path.parts or any(s in ('.git','admin.local.json','openai.local.json','secrets') or s.startswith('.env') for s in path.parts):raise RuntimeError('Forbidden input path')
 source=base/'source'/path
 if source.is_symlink():raise RuntimeError('Linked input')
 data=source.read_bytes()
 if sha(data)!=digest:raise RuntimeError('Changed staged input '+name)
 origins.setdefault(digest,source)
 if name.startswith('closure/traffic-refinement/evidence/'):
  historical=root/name
  if historical.is_symlink() or sha(historical.read_bytes())!=digest:raise RuntimeError('Historical evidence mismatch')
  objects.setdefault(digest,{'kind':'historical-evidence','path':name,'bytes':len(data)})
for digest,source in origins.items():
 if digest in objects:continue
 data=source.read_bytes();target=archive/'objects'/digest;target.parent.mkdir(exist_ok=True);target.write_bytes(data)
 objects[digest]={'kind':'local-object','path':'objects/'+digest,'bytes':len(data)}
browser=root/'build/trf-closure/trf01-browser/snapshot-20261004T182311Z'
for name in ['report.json','browser.stdout','browser.stderr','dashboard.stdout','dashboard.stderr','package.stdout','package.stderr']:artifact(browser/name,'artifacts/browser/'+name)
functional=root/'build/trf-closure/trf01-regressions/functional-invariance-4daa8c4786d8.json';artifact(functional,'artifacts/functional/report.json')
artifact(root/'build/trf-closure/trf01-composition/runtime-check.json','artifacts/runtime/report.json')
for source,name in [(root/'build/trf-closure/trf01-composition/full_regression.py','python-runner.py'),(root/'build/trf-closure/trf01-browser/run-isolated.py','browser-runner.py'),(Path(__file__).resolve(),'archive-builder.py')]:artifact(source,'harnesses/'+name)
b=json.loads((browser/'report.json').read_bytes());f=json.loads(functional.read_bytes());runtime=json.loads((archive/'artifacts/runtime/report.json').read_bytes())
assert b['status']=='PASS' and f['status']=='PASS' and runtime['status']=='PASS'
for subset in (b['sourceFiles'],f['sources']):
 assert all(manifest.get(name)==digest for name,digest in subset.items())
for item in runtime['classes']+runtime['dependencies']:assert manifest[item['path']]==item['sha256']
for name in ['replay.py','README.md']:
 data=(archive/name).read_bytes();artifacts[name]={'sha256':sha(data),'bytes':len(data),'origin':'authored preservation tooling/documentation'}
runs={
 'python-final':{'manifestArtifact':'artifacts/python-final/inputs.json','reportArtifact':'artifacts/python-final/report.json','status':'PASS','tests':1047},
 'browser':{'manifestArtifact':'artifacts/browser/report.json','manifestFields':['sourceFiles'],'reportArtifact':'artifacts/browser/report.json','status':'PASS','browserChecks':77,'dashboardChecks':7,'privatePackageArchived':False},
 'functional':{'replayInputSet':'python-final','recordedManifestArtifact':'artifacts/functional/report.json','recordedManifestFields':['sources'],'reportArtifact':'artifacts/functional/report.json','status':'PASS','casesPerArm':23,'arms':3},
 'runtime':{'replayInputSet':'python-final','reportArtifact':'artifacts/runtime/report.json','status':'PASS','engineChecks':378},
}
index={'schemaVersion':1,'kind':'finite-regression-preservation','artifacts':artifacts,'runs':runs,'objects':objects,'closureReviewStateAtPreservation':'Unresolved required GPT-6.1 Sol reviews; automatic screening stopped the attempts. Passing regressions do not discharge that review requirement.','publicationAtPreservation':'not performed','previousArchive':'closure/traffic-refinement/evidence/ting02-regressions/index.json','limitations':['Finite Linux executions, not universal equivalence or native IIS/macOS verification.','Toolchains and Playwright installation are external; no network is required for archive verification.','Historical evidence references must remain available with identical bytes.','Private configs, .env files and private IIS ZIPs are excluded.'],'statistics':{'uniquePayloads':len(objects),'newPayloads':sum(v['kind']=='local-object' for v in objects.values()),'newPayloadBytes':sum(v['bytes'] for v in objects.values() if v['kind']=='local-object'),'referencedHistoricalPayloads':sum(v['kind']=='historical-evidence' for v in objects.values())}}
raw=(json.dumps(index,sort_keys=True,indent=2)+'\n').encode();(archive/'index.json').write_bytes(raw);(archive/'index.sha256').write_text(sha(raw)+'  index.json\n')
print(json.dumps({'archive':archive.relative_to(root).as_posix(),'indexSha256':sha(raw),**index['statistics']}))
