#!/usr/bin/env python3
"""Select illustrative all-five-arm examples from preserved benchmark evidence.
No engine or network calls. Never copies private oracle/repair expressions.
"""
import argparse
from collections import Counter, defaultdict
import gzip
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.quality_all import public_native_hint

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--data', type=Path, default=ROOT / 'build/benchmarks/alloy4fun-v2')
parser.add_argument('--output-dir', type=Path, required=True)
args = parser.parse_args()
BASE, OUT = args.data.resolve(), args.output_dir.resolve()
if any((OUT / name).exists() for name in ('selection.json', 'frozen-examples.json')):
    parser.error('Use a fresh output directory; existing evidence is preserved.')
OUT.mkdir(parents=True, exist_ok=True)
ARMS = {'canonical': ('live-full','live-canonical'), 'ast': ('ast-full','live-ast'),
        'tar': ('tar','tar-depth-2'), 'higena_h': ('fm24-history','fm24-history'),
        'higena_m': ('fm24-mutation','fm24-mutation')}
CLASSES = ('UNDERCONSTRAINED','OVERCONSTRAINED','BOTH')

def sha(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda:stream.read(1024*1024),b''):h.update(block)
    return h.hexdigest()

def native(arm, response):
    if arm in ('canonical','ast'):
        return any(not op.get('aggregate') and (op.get('action') or op.get('description') or '').strip()
                   for op in response.get('operations',[]))
    if arm=='tar':
        return any(isinstance(h.get('hint'),str) and h['hint'].strip()
                   for h in (response.get('native_result') or {}).get('native_trace',[]))
    return bool((response.get('hint') or response.get('native_hint') or '').strip())

def safe_response(arm, response):
    if arm in ('canonical','ast'):
        return {k:response[k] for k in ('status','metric','distance','canonicalForm','operations','trace','comparison') if k in response}
    if arm=='tar':
        return {'native_hints':[{k:h[k] for k in ('hint','operation','line','column','end_line','end_column') if k in h}
                for h in (response.get('native_result') or {}).get('native_trace',[]) if (h.get('hint') or '').strip()],
                'bounded_verified': (response.get('independent_validation') or {}).get('verified_correct'),
                'validation_status': (response.get('independent_validation') or {}).get('status'),
                'validation_error_class': (response.get('independent_validation') or {}).get('error_class')}
    hint=response.get('hint') or response.get('native_hint')
    return {'native_hint':public_native_hint(hint),'publication_redacted':public_native_hint(hint)!=hint,
            'hint_source':response.get('hint_source')}

report=json.loads((ROOT/'docs/benchmarks/alloy4fun-results.json').read_text())
audit_path=BASE/'evidence-audit.json'
assert sha(audit_path)==report['provenance']['raw_response_audit']['sha256']
audit={x['tool']:x for x in json.loads(audit_path.read_text())['tools']}
results={}; hashes={}; raw_hashes={}
for arm,(folder,tool) in ARMS.items():
    rp=BASE/folder/'results.jsonl'; hp=BASE/folder/'responses.jsonl.gz'
    hashes[arm]={'results_sha256':sha(rp),'responses_sha256':sha(hp)}
    assert hashes[arm]['results_sha256']==report['provenance']['result_snapshots'][tool]['sha256']
    assert hashes[arm]['responses_sha256']==audit[tool]['sha256']['responses.jsonl.gz']
    results[arm]={};raw_hashes[arm]={}
    with rp.open('rb') as stream:
        for raw in stream:
            r=json.loads(raw); cid=r['case_id']
            if r['cohort_status'] not in CLASSES or not r.get('hint_available') or r.get('timed_out'): continue
            if arm in ('canonical','ast') and not(r.get('distance',0)>0 and r.get('operation_count',0)>0):continue
            assert cid not in results[arm]
            results[arm][cid]=r; raw_hashes[arm][cid]=hashlib.sha256(raw).hexdigest()
common=set.intersection(*(set(r) for r in results.values()))
pre_native_count=len(common)
tar_validation={}
for arm,(folder,_) in ARMS.items():
    valid=set()
    with gzip.open(BASE/folder/'responses.jsonl.gz','rb') as stream:
        for raw in stream:
            wrapper=json.loads(raw);cid=wrapper['case_id']
            if cid not in common:continue
            if arm=='tar':
                assert wrapper['source_sha256']==results[arm][cid]['source_sha256']
                canon=json.dumps(results[arm][cid],sort_keys=True,separators=(',',':'),allow_nan=False).encode()
                assert wrapper['result_sha256']==hashlib.sha256(canon).hexdigest()
                validation=wrapper['response'].get('independent_validation') or {}
                assert validation.get('verified_correct') is results[arm][cid].get('verified_correct')
                tar_validation[cid]={k:validation.get(k) for k in ('status','verified_correct','error_class')}
            if native(arm,wrapper['response']): valid.add(cid)
    common &= valid
    print('verified native',arm,len(common),flush=True)

payloads={}
with (BASE/'payloads.jsonl').open() as stream:
    for raw in stream:
        p=json.loads(raw);cid=p['case_id']
        if cid not in common: continue
        assert all(results[a][cid]['source_sha256']==p['source_sha256'] for a in ARMS)
        # Nonempty predicates can pass the current public HTTP input boundary.
        if not p['body'].strip():continue
        payloads[cid]={k:p[k] for k in ('body','case_id','cohort_status','group','predicate','prefix','suffix','source_sha256','path','environment_sha256','body_token_sha256')}
common &= set(payloads)
by_invariant=defaultdict(list)
for cid in common:
    r=results['canonical'][cid];by_invariant[(r['group'],r['predicate'])].append(cid)
class_counts=Counter();selected=[]
for invariant in sorted(by_invariant,key=lambda x:(x[0],int(x[1][3:]))):
    ids=by_invariant[invariant]
    # Preserve maximum invariant coverage, preferring a checked TAR candidate
    # when one is available; unknown validation remains explicitly unknown.
    verified=[cid for cid in ids if results['tar'][cid].get('verified_correct') is True]
    ids=verified or ids
    moderate=[cid for cid in ids if max(results[a][cid]['operation_count'] for a in ('canonical','ast'))<=20]
    candidates=moderate or ids
    available={payloads[cid]['cohort_status'] for cid in candidates}
    cls=min(available,key=lambda c:(class_counts[c],CLASSES.index(c)))
    candidates=[cid for cid in candidates if payloads[cid]['cohort_status']==cls]
    # Target eight total steps, allowing enough structure to illustrate guidance;
    # then prefer a concise learner draft, with a fixed identity tie-break.
    cid=min(candidates,key=lambda k:(abs(sum(results[a][k]['operation_count'] for a in ('canonical','ast'))-8),len(payloads[k]['body']),k))
    selected.append(cid);class_counts[cls]+=1
assert 100<=len(selected)<=200
metadata={r['exercise_id']:r for r in json.loads((ROOT/'benchmarks/alloy4fun/protocol/quality-selection-181.json').read_text())['cases']}
all_invariants=set(metadata);cases=[]
for number,cid in enumerate(selected,1):
    p=payloads[cid];exid=p['group']+'-'+p['predicate'];prefix=p['prefix'];suffix=p['suffix']
    match=re.search(r'(?s)(pred\s+'+re.escape(p['predicate'])+r'\s*(?:\[[^\]]*\])?\s*)\{$',prefix)
    assert match and suffix.startswith('}')
    assert sha(Path(p['path']))==p['source_sha256']
    cases.append({'number':number,'exercise_id':exid,'case_id':cid,'source_sha256':p['source_sha256'],
        'source_classification':p['cohort_status'],'group':p['group'],'predicate':p['predicate'],
        'learner_body':p['body'],'question':metadata[exid]['question'],'description':metadata[exid]['question'],
        'environment_before':prefix[:match.start()],'predicate_header':match[1],'environment_after':suffix[1:],
        'environment_sha256':p['environment_sha256'],'body_token_sha256':p['body_token_sha256'],
        'selection_rationale':'One example per eligible invariant; prefer a bounded-verified TAR repair if available, then both traces at most 20 steps; balance corrected incorrect classes; target 8 combined steps, then shorter body and lexical case ID.'})
selection={'schema':1,'selection_policy':'Purposive, deterministic maximum-invariant-coverage illustration conditioned on nonempty native hints in all five arms; not a population-representative random sample. One case per eligible invariant. Within each invariant, prefer a bounded-verified TAR repair if available, then both traces at most 20 steps, choose the least-used eligible incorrect class (fixed UNDER/OVER/BOTH tie-break), minimize distance of the combined trace count from 8, then learner body length and case ID. Unknown TAR validation is retained and labeled explicitly. AI outputs were not examined for selection.',
    'population':{'corpus_cases':61598,'incorrect_all_five_result_hits':pre_native_count,'eligible_native_hint_cases':len(common),'eligible_invariants':len(by_invariant),'all_invariants':181,
       'eligible_class_counts':{c:sum(payloads[k]['cohort_status']==c for k in common) for c in CLASSES},
       'eligible_verified_tar_cases':sum(results['tar'][k].get('verified_correct') is True for k in common),
       'eligible_verified_tar_invariants':len({(results['tar'][k]['group'],results['tar'][k]['predicate']) for k in common if results['tar'][k].get('verified_correct') is True}),
       'selected_tar_validation_counts':dict(Counter(tar_validation[k]['status'] for k in selected)),
       'selected_unverified_tar_cases':[{'case_id':k,**tar_validation[k]} for k in selected if results['tar'][k].get('verified_correct') is not True],
       'selected_class_counts':dict(class_counts),'selected_families':len({x['group'] for x in cases}),
       'omitted_invariants':sorted(all_invariants-{x['exercise_id'] for x in cases}),
       'higena_m_sources_in_eligible_intersection':dict(sorted(Counter(results['higena_m'][k].get('hint_source') for k in common).items()))},
    'archive_sha256':hashes,'cases':cases}
sp=OUT/'selection.json'
if sp.exists():raise SystemExit('Refusing overwrite existing selection')
sp.write_text(json.dumps(selection,indent=2,ensure_ascii=False)+'\n')
# Public-safe frozen outputs for the selected cases, retaining exact record hashes.
wanted=set(selected);evidence={cid:{} for cid in wanted}
for arm,(folder,_) in ARMS.items():
    with gzip.open(BASE/folder/'responses.jsonl.gz','rb') as stream:
        for raw in stream:
            w=json.loads(raw);cid=w['case_id']
            if cid not in wanted:continue
            record={'result_record_sha256':raw_hashes[arm][cid], 'response_record_sha256':hashlib.sha256(raw).hexdigest(),
                 'result':{k:results[arm][cid].get(k) for k in ('status','cohort_status','source_sha256','hint_available','hint_source','fold','distance','operation_count','timed_out','verified_correct')},
                 'response':safe_response(arm,w['response'])}
            if arm.startswith('higena_'):
                record['response']['hint_source']=results[arm][cid].get('hint_source')
            assert arm not in evidence[cid];evidence[cid][arm]=record
assert all(set(e)==set(ARMS) for e in evidence.values())
(OUT/'frozen-examples.json').write_text(json.dumps({'schema':1,'selection_sha256':sha(sp),'archive_sha256':hashes,
    'cases':[{'case_id':cid,'exercise_id':payloads[cid]['group']+'-'+payloads[cid]['predicate'],'arms':evidence[cid]} for cid in selected]},indent=2,ensure_ascii=False)+'\n')
print(json.dumps(selection['population'],indent=2))
print('selection',len(cases),sha(sp),flush=True)
