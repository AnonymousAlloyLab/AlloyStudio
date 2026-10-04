#!/usr/bin/env python3
"""TING02: finite TRF-01 closure, two clean offline builds, exact source mapping.

No report can inherit VERIFIED from a different source root. The six reviews are
advisory workflow records; the kernel, closed bridges and witnesses decide PASS.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import re
import secrets
import shutil
import sys

from lean_offline import ROOT, clean_environment, installed_toolchain
from verify_lean import (FLAGS as BASE_FLAGS, Rejected, check_inventory, check_proof_source,
                        digest, inside, json_bytes, network_witness, run, toolchain_inventory)
from verify_traffic_proofs import snapshot_inputs, frozen_bytes
from review_ladder import check_reviews
from verify_ingress_deadlines import TEST_RUNNER

FLAGS=[*BASE_FLAGS,'-DmaxRecDepth=8192','-DmaxHeartbeats=4000000']
BLOCK='formal/ingress_admission/block.json'
SPEC='closure/traffic-refinement/strict-ingress-spec.json'
SOURCE_MANIFEST='closure/traffic-refinement/strict-ingress-sources.json'
PRIOR_MANIFEST='closure/traffic-refinement/ingress-deadline-sources.json'
ORIGINAL='closure/traffic-obligations.json'
ORIGINAL_SHA='c87b9576349f1e2a071379b7edb77cb090815717dbf1341a0db23b8b768dcfbc'
DEPENDENCY='closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/report.json'
DEPENDENCY_SHA='24642ba8bd6c7d171e2fab165af33eddc56613d0460582e2da34e7065dfc64e7'
MODULES=('IngressDeadlines.Model','IngressDeadlines.Extracted','IngressDeadlines.Spec',
         'AdmissionModel','AdmissionExtracted','AdmissionSpec','DecoderModel','DecoderContract',
         'DecoderExtracted','Decoder','IngressWire','IngressRouteModel','IngressRouteExtracted','IngressComposition')
SOURCES={module: ('formal/ingress_deadlines/'+module.replace('.','/')+'.lean' if module.startswith('IngressDeadlines.')
                 else 'formal/ingress_admission/'+module+'.lean') for module in MODULES}
BRIDGES=('strict_ingress_deadline_bridge','admission_bridge','decoder_bridge','strict_ingress_bridge','wire_ingress_bridge')
CORRESPONDENCE_COUNTS={'strict_ingress_deadline_bridge':15,'admission_bridge':9,'decoder_bridge':5,'strict_ingress_bridge':17,'wire_ingress_bridge':6}
TESTS=('test_ingress_deadlines.py','test_traffic_http.py','test_strict_http_decode.py',
       'test_ingress_admission.py','test_admission_bridge.py','test_decoder_bridge.py',
       'test_strict_ingress_bridge.py','test_ingress_wire.py','test_strict_ingress_gate.py','test_review_ladder.py')
TRUST=[
 {'id':'TCB-LEAN','classification':'TRUSTED','description':'Pinned Lean 4.34.1 distribution/kernel, registered source-language restriction and all-project-definition/theorem transitive axiom auditor.'},
 {'id':'TCB-TRANSLATION','classification':'TRUSTED','description':'Closed source AST interpreters and per-operation correspondences; not a proof of an arbitrary Python virtual machine. No entire admission/decoder-validity predicate is trusted.'},
 {'id':'TCB-PRIMITIVES','classification':'TRUSTED','description':'CPython ASCII/UTF8/string/regex/JSON/IPv6 primitives, HTTP header raw-items and method dispatch, exceptions and sequential ordinary execution, socket recv bound, threading.start/is_alive/lock mutual exclusion, one serialized accept callback per listener, monotonic finite clock sample order.'},
 {'id':'TCB-HOST','classification':'TRUSTED','description':'Linux user/network namespaces, process/filesystem/socket semantics, SHA-256 implementation and collision resistance, hardware.'},
]
EXCLUDED=['Future source revisions and unlisted properties; the other 21 traffic obligations.',
          'IIS/Cloudflare normalization and native Windows/macOS execution; external/multiple processes.',
          'Whole-process RSS and kernel allocation sizes, OS fairness and unsampled scheduling latency after a deadline observation.']
CLAIMS=[{'id':cid,'parentObligation':'TRF-01','classification':kind,'passPredicate':predicate}
 for cid,kind,predicate in (
 ('TRF01-DECODE','PROVED_UNDER_DECLARED_TCB','Extracted raw-input decoder equals independent grammar; strict bounded JSON policy correspondence; constructed invalid-byte witnesses reject.'),
 ('TRF01-DEADLINES','PROVED_UNDER_DECLARED_TCB','All six current-source read/parse/decode cut programs retain final strict deadline guards.'),
 ('TRF01-ALLOCATION','PROVED_UNDER_DECLARED_TCB','Every allocated request thread has a reserved owner until it terminates; reserve/release traces retain exact ownership and handler limits; accepted-unreserved sockets at most one per listener.'),
 ('TRF01-LANES','PROVED_UNDER_DECLARED_TCB','Enabled public/control listener topology has distinct nonborrowable gates, separately enforced capacities and combined sums; disabled branch claims no private availability.'),
 ('TRF01-DISPATCH','CHECKED_AND_PROVED_UNDER_DECLARED_TCB','Closed source call graph links strict decoder, byte-budget and sampled deadline validation before successful public/admin business dispatch.'),
 ('TRF01-REGRESSION','TESTED','Registered actual socket/clock/allocation and source/contract mutation witnesses pass without engine dispatch on rejected input.'),
 )]
BASE_INPUTS={SPEC,SOURCE_MANIFEST,PRIOR_MANIFEST,ORIGINAL,DEPENDENCY,
 'formal/ingress_admission/Audit.lean','formal/ingress_admission/theorems.json',
 'formal/ingress_deadlines/Audit.lean',
 'formal/ingress_admission/deadline-template.py.txt','formal/ingress_admission/dispatch-template.py.txt',
 'closure/traffic-refinement/admission-source-contract.json','formal/ingress_admission/decoder-template.py.txt',
 'formal/ingress_admission/json-template.py.txt',
 'closure/traffic-refinement/strict-decoder-spec.json','closure/traffic-refinement/admission-spec.json',
 'docs/strict-ingress-closure.md','docs/strict-decoder-contract.md','docs/admission-contract.md',
 'scripts/verify_strict_ingress.py','scripts/lean_offline.py','scripts/verify_lean.py',
 'scripts/verify_traffic_proofs.py','scripts/verify_ingress_deadlines.py','scripts/bridge_policies.py',
 'scripts/review_ladder.py','formal/lean-toolchain','lean-toolchain',
 *SOURCES.values(),*('scripts/'+b+'.py' for b in BRIDGES),*('tests/'+t for t in TESTS)}

def write(path,value):path.write_text(json.dumps(value,sort_keys=True,indent=2)+'\n')
def sha(value):return hashlib.sha256(json_bytes(value)).hexdigest()

def policy():
 return {'schemaVersion':1,'id':'TING02','scope':'TRF-01-complete-finite-ingress-refinement',
         'leanToolchain':'leanprover/lean4:v4.34.1','flags':FLAGS,'allowlistedAxioms':[],
         'requiredCleanBuilds':2,'requiredReviews':6,'umbrellaObligationsClosed':['TRF-01'],
         'modules':list(MODULES),'claims':CLAIMS,'trust':TRUST,'excluded':EXCLUDED,
         'registeredMappingCounts':CORRESPONDENCE_COUNTS}

def required_inputs(root):
 files=json.loads(inside(root,SOURCE_MANIFEST).read_text())['files']
 prior=json.loads(inside(root,PRIOR_MANIFEST).read_text())['files']
 if not isinstance(files,dict) or not set(prior)<=set(files):raise Rejected('Dropped application/dependency input')
 for name,value in files.items():
  if not isinstance(value,str) or re.fullmatch('[0-9a-f]{64}',value) is None:raise Rejected('Invalid source hash')
  inside(root,name)
 return BASE_INPUTS|set(files)

def inputs(root,block):
 if any(block.get(k)!=v for k,v in policy().items()):raise Rejected('Invalid strict ingress closure policy')
 if not isinstance(block.get('inputs'),dict) or set(block['inputs'])!=required_inputs(root):raise Rejected('Incomplete or unexpected strict ingress inventory')
 for name,expected in block['inputs'].items():
  if digest(inside(root,name))!=expected:raise Rejected('Frozen input changed: '+name)
 if digest(root/ORIGINAL)!=ORIGINAL_SHA:raise Rejected('Original obligation registry changed')
 original=next(x for x in json.loads((root/ORIGINAL).read_text())['obligations'] if x['id']=='TRF-01')
 spec=json.loads((root/SPEC).read_text())
 if spec.get('original')!={k:original[k] for k in ('id','statement','plannedPassCondition','dependsOn')} or spec.get('claimIds')!=[x['id'] for x in CLAIMS] or spec.get('closesParent') is not True:raise Rejected('Original TRF-01 contract identity changed')
 if digest(root/DEPENDENCY)!=DEPENDENCY_SHA or json.loads((root/DEPENDENCY).read_text()).get('status')!='VERIFIED':raise Rejected('Historical TRF-00 dependency not verified')
 for name,value in json.loads((root/SOURCE_MANIFEST).read_text())['files'].items():
  if block['inputs'].get(name)!=value:raise Rejected('Source manifest mismatch')
 actual={p.relative_to(root).as_posix() for p in (root/'formal/ingress_admission').rglob('*.lean')}
 expected={p for p in SOURCES.values() if p.startswith('formal/ingress_admission/')}|{'formal/ingress_admission/Audit.lean'}
 if actual!=expected:raise Rejected('Unregistered or missing ingress proof source')
 return dict(block['inputs'],**{BLOCK:digest(inside(root,BLOCK))})

def inventory(root):
 rows=json.loads((root/'formal/ingress_admission/theorems.json').read_text())['theorems']
 expected={}
 for row in rows:
  if set(row)!={'name','module','levelParameters','typeSha256','axioms'} or row['axioms'] or row['module'] not in MODULES or row['name'] in expected:raise Rejected('Invalid theorem inventory')
  expected[row['name']]={k:v for k,v in row.items() if k!='axioms'}
 if not expected or 'AlloyStudio.Traffic.admitted_is_validated' not in expected:raise Rejected('Missing composed parent theorem')
 return expected

def build(root,work,toolchain,expected,manifest):
 work.mkdir();objects=work/'objects';(objects/'IngressDeadlines').mkdir(parents=True);(work/'IngressDeadlines').mkdir();(work/'scratch').mkdir()
 environment=clean_environment(toolchain,objects);environment['TMPDIR']=str(work/'scratch')
 network=network_witness(work,environment)
 mappings={}
 for name in BRIDGES:
  code=('import sys,json,importlib;from pathlib import Path;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
        'm=importlib.import_module(sys.argv[2]);print(json.dumps(m.check(Path(sys.argv[1])),sort_keys=True))')
  mappings[name]=json.loads(run([sys.executable,'-I','-c',code,str(root),name],root,environment,work/(name+'.json')))
  if mappings[name].get('status')!='PASS':raise Rejected('Correspondence failed: '+name)
 for module,relative in SOURCES.items():
  source=frozen_bytes(root,relative,manifest);check_proof_source(source.decode(),set(MODULES))
  target=work/(module.replace('.','/')+'.lean');target.write_bytes(source);target.chmod(0o400)
  run([str(toolchain/'bin/lean'),*FLAGS,'-o',str(objects/(module.replace('.','/')+'.olean')),str(target.relative_to(work))],work,environment,work/(module+'.log'))
 (work/'Audit.lean').write_bytes(frozen_bytes(root,'formal/ingress_admission/Audit.lean',manifest))
 raw=run([str(toolchain/'bin/lean'),*FLAGS,'Audit.lean'],work,environment,work/'audit.jsonl')
 checked=check_inventory([json.loads(line) for line in raw.decode().splitlines()],expected)
 return {'status':'PASS','theorems':len(checked),'axioms':[],'network':network,'inventorySha256':sha(checked),
         'bridges':mappings,'artifacts':{m:digest(objects/(m.replace('.','/')+'.olean')) for m in MODULES}}

def deadline_negative_controls(root, work, toolchain, build_a):
    work.mkdir()
    objects = work / 'objects'
    shutil.copytree(build_a / 'objects', objects)
    shutil.copytree(build_a / 'IngressDeadlines', work / 'IngressDeadlines')
    for path in (work / 'IngressDeadlines').glob('*.lean'):
        path.chmod(0o600)
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain / 'bin/lean')
    original = (root / SOURCES['IngressDeadlines.Extracted']).read_bytes()
    results = {}
    mutations = (
        ('equality_guard', 'traffic_http.py', 'now >= self.deadline', 'now > self.deadline'),
        ('missing_json_postguard', 'server.py',
         '            self.rfile.check_deadline()\n            return data', '            return data'),
    )
    code = ('import sys;from pathlib import Path;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
            'import strict_ingress_deadline_bridge as b;r=Path(sys.argv[1]);s=(r/sys.argv[2]).read_text();'
            'assert s.count(sys.argv[3])==1;v=s.replace(sys.argv[3],sys.argv[4],1);'
            'print(b.generate(r,**({"traffic_source":v} if sys.argv[2]=="traffic_http.py" else {"server_source":v})),end="")')
    for name, relative, before, after in mutations:
        mutated = run([sys.executable, '-I', '-c', code, str(root), relative, before, after],
                      root, environment, work / (name + '-program.txt'))
        if mutated == original:
            raise Rejected('Production mutation did not change extracted program: ' + name)
        (work / 'IngressDeadlines/Extracted.lean').write_bytes(mutated)
        run([lean, *FLAGS, '-o', str(objects / 'IngressDeadlines/Extracted.olean'),
             'IngressDeadlines/Extracted.lean'], work, environment, work / (name + '-extract.log'))
        try:
            run([lean, *FLAGS, 'IngressDeadlines/Spec.lean'], work, environment, work / (name + '-spec.log'))
        except Rejected:
            output = (work / (name + '-spec.log')).read_text()
            if ('error:' not in output or any(term in output for term in
                    ('unknown module', 'unknown identifier', 'file not found', 'unexpected token', 'failed to synthesize'))):
                raise Rejected('Unrelated ingress mutation failure: ' + name)
        else:
            raise Rejected('Independent deadline specification accepted source mutation: ' + name)
        results[name] = 'REJECTED_BY_INDEPENDENT_SPEC'
    for name, source in (('rogue_axiom_language', 'axiom forged : False'),
                         ('placeholder_language', 'theorem unfinished : True := by sorry')):
        try:
            check_proof_source(source, set(MODULES))
        except Rejected:
            results[name] = 'REJECTED_BY_REGISTERED_LANGUAGE_GATE'
        else:
            raise Rejected('Proof language control was accepted: ' + name)
    (work / 'IngressDeadlines/Extracted.lean').write_bytes(original)
    run([lean, *FLAGS, '-o', str(objects / 'IngressDeadlines/Extracted.olean'),
         'IngressDeadlines/Extracted.lean'], work, environment, work / 'restore-extracted.log')
    spec = (root / SOURCES['IngressDeadlines.Spec']).read_text()
    (work / 'IngressDeadlines/Spec.lean').write_text(spec + '\nnamespace AlloyStudio.IngressDeadlines.Spec\naxiom forged : False\ntheorem forged_use : False := forged\nend AlloyStudio.IngressDeadlines.Spec\n')
    run([lean, *FLAGS, '-o', str(objects / 'IngressDeadlines/Spec.olean'),
         'IngressDeadlines/Spec.lean'], work, environment, work / 'rogue-axiom-build.log')
    (work / 'Audit.lean').write_bytes((root / 'formal/ingress_deadlines/Audit.lean').read_bytes())
    raw = run([lean, *FLAGS, 'Audit.lean'], work, environment, work / 'rogue-axiom-audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    if not any(row.get('kind') == 'forbidden-project-axiom' for row in rows) or not any(row.get('axioms') for row in rows):
        raise Rejected('Introduced axiom or its transitive dependency escaped the actual auditor.')
    results['rogue_axiom_audit'] = 'REJECTED_BY_TRANSITIVE_AUDIT'
    (work / 'Placeholder.lean').write_text('theorem unfinished : True := by sorry\n')
    try:
        run([lean, *FLAGS, 'Placeholder.lean'], work, environment, work / 'placeholder.log')
    except Rejected:
        if 'sorry' not in (work / 'placeholder.log').read_text():
            raise Rejected('Unrelated placeholder compiler failure.')
    else:
        raise Rejected('Placeholder compiled without warning-as-error rejection.')
    results['placeholder_compiler'] = 'REJECTED_BY_WARNING_AS_ERROR'
    return results


def verify(root=ROOT,candidate=False):
 root=Path(root).resolve();identifier=datetime.now(timezone.utc).strftime('ting02-%Y%m%dT%H%M%SZ-')+secrets.token_hex(4)
 work=root/'build/trf-closure'/identifier;work.mkdir(parents=True)
 report={'schemaVersion':1,'id':identifier,'blockId':'TING02','parentObligation':'TRF-01','closesParent':True,
         'umbrellaObligationsClosed':[],'status':'BLOCKED','builds':[],'blockingReasons':[],'infrastructureErrors':[]}
 try:
  block=json.loads(inside(root,BLOCK).read_text());manifest=inputs(root,block)
  if candidate:report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
  else:manifest.update(check_reviews(root,BLOCK))
  report['inputRootHash']=sha(manifest);write(work/'manifest.json',manifest)
  frozen=snapshot_inputs(root,work/'inputs',manifest)
  if not candidate:check_reviews(frozen,BLOCK)
  pin,toolchain=installed_toolchain(frozen)
  if pin!=block['leanToolchain']:raise Rejected('Toolchain pin changed')
  environment=clean_environment(toolchain);environment['TMPDIR']=str(work);environment['ELAN_HOME']=str(toolchain.parents[1])
  version=run([str(toolchain/'bin/lean'),'--version'],work,environment,work/'lean-version.log').decode().strip()
  if 'version 4.34.1' not in version:raise Rejected('Installed toolchain version changed')
  before=toolchain_inventory(toolchain);write(work/'toolchain.json',before)
  report['toolchain']={'pin':pin,'version':version,'rootHash':sha(before)}
  report['executionEnvironment']={'platform':platform.platform(),'python':sys.version,'pythonExecutableSha256':digest(Path(sys.executable).resolve()),'flags':FLAGS,'network':'fresh user+network namespaces; isolated loopback only for socket tests'}
  report['trust'],report['excluded']=TRUST,EXCLUDED
  report['dependency']={'id':'TCFG03','report':DEPENDENCY,'sha256':DEPENDENCY_SHA,'interpretation':'Historical selected initial profile, with successor admission initialization separately checked; does not certify future transitions.'}
  report['verifier']={'id':'V-TING02','sha256':manifest['scripts/verify_strict_ingress.py']}
  expected=inventory(frozen)
  for name in ('build-a','build-b'):report['builds'].append(build(frozen,work/name,toolchain,expected,manifest))
  if report['builds'][0]!=report['builds'][1]:raise Rejected('Independent clean builds disagree')
  report['determinism']='PASS';report['provedTheorems']=len(expected)
  bridges=report['builds'][0]['bridges']
  actual_counts={'strict_ingress_deadline_bridge':len(bridges['strict_ingress_deadline_bridge']['consumerAstSha256'])+1,
                 'admission_bridge':bridges['admission_bridge']['mappedObjects'],
                 'decoder_bridge':len(bridges['decoder_bridge']['mappings'])+1,
                 'strict_ingress_bridge':len(bridges['strict_ingress_bridge']['mappings']),
                 'wire_ingress_bridge':len(bridges['wire_ingress_bridge']['bindings'])}
  if actual_counts!=CORRESPONDENCE_COUNTS:raise Rejected('Missing or ambiguous registered correspondence')
  report['correspondence']={'requiredMappings':sum(CORRESPONDENCE_COUNTS.values()),'mapped':sum(actual_counts.values()),
                           'unmapped':0,'ambiguous':0,'byBridge':actual_counts,
                           'interpretation':'Distinct registered mapping roles; a method may have separate deadline, data-flow and dispatch mappings.'} 
  report['negativeControls']=deadline_negative_controls(frozen,work/'negative-controls',toolchain,work/'build-a')
  report['tests']=[]
  for test in TESTS:
   raw=run([sys.executable,'-I','-c',TEST_RUNNER,str(frozen),str(frozen/'tests'/test)],frozen,environment,work/(test+'.log'))
   count=re.search(rb'Ran ([0-9]+) tests? in ',raw)
   if count is None or int(count[1])<=0 or re.search(rb'OK \(.*skipped=', raw):raise Rejected('Registered test missing or skipped required cases: '+test)
   report['tests'].append({'module':test,'status':'PASS','tests':int(count[1]),'evidence':test+'.log','sha256':hashlib.sha256(raw).hexdigest()})
  report['testCount']=sum(r['tests'] for r in report['tests'])
  report['witnesses']={'requiredModules':len(TESTS),'validModules':len(report['tests']),'invalidModules':0,
                       'executedChecks':report['testCount'],'modules':list(TESTS)}
  if toolchain_inventory(toolchain)!=before:raise Rejected('Toolchain mutated during verification')
  for name,value in manifest.items():
   if digest(inside(root,name))!=value or digest(inside(frozen,name))!=value:raise Rejected('Input mutated during verification: '+name)
  report['claims']=[dict(claim,status='PASS',inputRootHash=report['inputRootHash'],verifier=report['verifier'],exitCode=0,
                         evidence=['build-a/audit.jsonl','build-b/audit.jsonl',*('build-a/'+b+'.json' for b in BRIDGES),*(x['evidence'] for x in report['tests'])],
                         executionEnvironment=report['executionEnvironment']) for claim in CLAIMS]
  public_claims=json.loads((frozen/SPEC).read_text())['publicClaims']
  if not public_claims or any(name not in manifest for name in public_claims):raise Rejected('Orphan public claim target')
  report['provenance']={'publicClaimCount':len(public_claims),'fullyBound':len(public_claims),'orphanClaims':0,'publicClaims':public_claims,'claims':[c['id'] for c in CLAIMS],
                        'originalRegistrySha256':ORIGINAL_SHA,'specification':SPEC,'blockManifestSha256':manifest[BLOCK]}
  if not report['blockingReasons']:report['status']='VERIFIED';report['umbrellaObligationsClosed']=['TRF-01']
 except (Rejected,ValueError,KeyError,TypeError,StopIteration) as error:report['blockingReasons'].append(type(error).__name__+': '+str(error))
 except (OSError,RuntimeError) as error:report['status']='INFRASTRUCTURE_FAILURE';report['infrastructureErrors'].append(type(error).__name__+': '+str(error))
 report['claimSummary']={'total':len(CLAIMS),'passed':len(report.get('claims',[])),
                         'blocked':sum(c.get('status')=='BLOCKED' for c in report.get('claims',[])),
                         'unresolved':len(CLAIMS)-len(report.get('claims',[]))}
 report['closureInvariantFailures']=len(report['blockingReasons'])
 report['buildSummary']={'required':2,'passed':sum(b.get('status')=='PASS' for b in report['builds'])}
 report['closureBoundary']={'verifiedSurface':[c['id'] for c in CLAIMS] if report['status']=='VERIFIED' else [],
     'trustedSurface':TRUST,'excludedSurface':EXCLUDED,
     'interpretation':'VERIFIED is finite closure for these hashed inputs and registered interpreters under the explicit TCB, not universal or assumption-free Python/OS correctness.'}
 write(work/'report.json',report);print(json.dumps({'status':report['status'],'report':str(work/'report.json')}))
 return 0 if report['status']=='VERIFIED' else 2 if report['status']=='INFRASTRUCTURE_FAILURE' else 1

if __name__=='__main__':
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--candidate',action='store_true');args=parser.parse_args()
 raise SystemExit(verify(candidate=args.candidate))
