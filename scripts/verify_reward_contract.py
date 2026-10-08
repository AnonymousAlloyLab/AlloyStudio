#!/usr/bin/env python3
"""Verify the isolated reward/LFU contract twice with no network interface.

This is a model-only closure. Runtime probes are separate tested evidence;
this verifier never promotes them to a universal implementation refinement.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import platform
import re
import secrets
import sys

from lean_offline import ROOT, clean_environment, installed_toolchain
from verify_lean import (FLAGS, Rejected, check_inventory, check_proof_source,
                         digest, inside, json_bytes, network_witness, run,
                         toolchain_inventory)

BLOCK = 'formal/reward/block.json'
MODULES = ('Reward', 'LFU')
SOURCES = tuple(f'formal/reward/{module}.lean' for module in MODULES)
TIERS = (('luna', 'gpt-6-luna'), ('sol', 'gpt-6.1-sol'), ('astra', 'gpt-6-astra'))
REQUIRED_INPUTS = frozenset((*SOURCES, 'formal/reward/Audit.lean',
    'formal/reward/theorems.json', 'formal/reward/claims.json',
    'formal/reward/witnesses.json', 'formal/reward/README.md',
    'docs/reward-lfu-contract.md', 'lean-toolchain', 'formal/lean-toolchain',
    'scripts/lean_offline.py', 'scripts/verify_lean.py',
    'scripts/bridge_policies.py', 'scripts/verify_reward_contract.py',
    'engine/src/live/BehaviorReward.java', 'engine/src/live/BehaviorPool.java',
    'engine/src/live/BehaviorFeedback.java', 'server.py'))


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def frozen(root, relative, manifest):
    data = inside(root, relative).read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest[relative]:
        raise Rejected('INPUT_MUTATION: ' + relative)
    return data


def inputs(root, block):
    if (block.get('schemaVersion') != 1 or block.get('id') != 'RWD01'
            or block.get('scope') != 'reward-lfu-model-only'
            or block.get('flags') != FLAGS or block.get('allowlistedAxioms') != []
            or block.get('requiredCleanBuilds') != 2 or block.get('requiredReviews') != 6
            or block.get('productionRefinementEstablished') is not False
            or block.get('modules') != list(MODULES)):
        raise Rejected('Invalid reward model policy')
    entries = block.get('inputs')
    if not isinstance(entries, dict) or set(entries) != REQUIRED_INPUTS:
        raise Rejected('Incomplete reward input inventory')
    for relative in entries:
        frozen(root, relative, entries)
    actual = {path.relative_to(root).as_posix() for path in (root/'formal/reward').glob('*.lean')}
    if actual != {*SOURCES, 'formal/reward/Audit.lean'}:
        raise Rejected('Unexpected reward proof source')
    return dict(entries, **{BLOCK: digest(inside(root, BLOCK))})


def inventory(root):
    rows = json.loads(inside(root, 'formal/reward/theorems.json').read_text())['theorems']
    expected = {}
    for row in rows:
        if (set(row) != {'name','module','levelParameters','typeSha256','axioms'}
                or row['axioms'] != [] or row['module'] not in MODULES
                or row['name'] in expected):
            raise Rejected('Malformed theorem inventory')
        expected[row['name']] = {key:value for key,value in row.items() if key != 'axioms'}
    if not expected:
        raise Rejected('Empty theorem inventory')
    return expected


def claims(root, expected):
    rows = json.loads(inside(root, 'formal/reward/claims.json').read_text())['claims']
    seen, owned = set(), set()
    for row in rows:
        if (row.get('id') in seen or row.get('class') != 'model_theorem'
                or row.get('productionRefinement') != 'NOT_ESTABLISHED'
                or row.get('verifier') != 'V-RWD-LEAN'
                or row.get('passPredicate') != 'all_registered_theorems_kernel_checked_with_empty_axioms'
                or not row.get('theorems') or not set(row['theorems']) <= set(expected)
                or owned.intersection(row['theorems'])):
            raise Rejected('UNMAPPED_OR_AMBIGUOUS_MODEL_CLAIM')
        seen.add(row['id'])
        owned.update(row['theorems'])
    if owned != set(expected):
        raise Rejected('Unowned theorem declarations')
    witnesses = json.loads(inside(root, 'formal/reward/witnesses.json').read_text())['witnesses']
    if not witnesses or len({row['id'] for row in witnesses}) != len(witnesses):
        raise Rejected('Missing or duplicate witnesses')
    for witness in witnesses:
        if (witness.get('classification') != 'kernel_checked_model_witness'
                or not witness.get('theorems') or not set(witness['theorems']) <= set(expected)):
            raise Rejected('Unregistered witness')
    return rows, witnesses


def reviews(root):
    block_hash = digest(inside(root, BLOCK))
    prior, recorded = {}, {}
    for tier,(prefix,model) in enumerate(TIERS, 1):
        current = {}
        for suffix in ('a','b'):
            relative = f'formal/reviews/RWD01/{prefix}-{suffix}.json'
            record = json.loads(inside(root, relative).read_text())
            notes = relative.removesuffix('.json') + '.md'
            if (record.get('reviewerModel') != model or record.get('tier') != tier
                    or record.get('blockManifestSha256') != block_hash
                    or record.get('priorReviews') != prior
                    or record.get('notesSha256') != digest(inside(root,notes))
                    or record.get('verdict') != 'no_constructed_breach'
                    or record.get('findings') != []):
                raise Rejected('Unresolved or unbound review record: ' + relative)
            current[relative] = digest(inside(root,relative))
            recorded[relative] = current[relative]
            recorded[notes] = digest(inside(root,notes))
        prior.update(current)
    return recorded


def snapshot(root, destination, manifest):
    destination.mkdir()
    for relative in sorted(manifest):
        target = destination/relative
        target.parent.mkdir(parents=True,exist_ok=True)
        target.write_bytes(frozen(root,relative,manifest))
        target.chmod(0o400)


def build(root, directory, toolchain, expected, manifest):
    directory.mkdir()
    objects = directory/'objects'
    objects.mkdir()
    scratch = directory/'scratch'
    scratch.mkdir()
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(scratch)
    network = network_witness(directory, environment)
    lean = str(toolchain/'bin/lean')
    for module,relative in zip(MODULES,SOURCES):
        source = frozen(root,relative,manifest)
        check_proof_source(source.decode(), set(MODULES))
        target = directory/(module+'.lean')
        target.write_bytes(source)
        target.chmod(0o400)
        run([lean,*FLAGS,'-o',str(objects/(module+'.olean')),module+'.lean'],
            directory,environment,directory/(module+'.log'))
    (directory/'Audit.lean').write_bytes(frozen(root,'formal/reward/Audit.lean',manifest))
    (directory/'Audit.lean').chmod(0o400)
    raw = run([lean,*FLAGS,'Audit.lean'],directory,environment,directory/'audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    checked = check_inventory(rows,expected)
    return {'status':'PASS','exitCode':0,'theorems':len(checked),'axioms':[],
            'network':network,'inventorySha256':sha(checked),
            'artifacts':{module:digest(objects/(module+'.olean')) for module in MODULES}}


def negative_controls(root, work, toolchain, manifest):
    work.mkdir()
    objects = work/'objects'
    objects.mkdir()
    environment = clean_environment(toolchain,objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain/'bin/lean')
    controls = (
        ('false_full_score','Reward',
         'some (if fullAgreement e then 1000 else min 999 (rounded e))',
         'some (if fullAgreement e then 1000 else min 1000 (rounded e))',
         'repaired_rounding_counterexample'),
        ('missing_witness_update','LFU',
         'survivors capacity xs ++ [⟨identity, 1⟩]', 'xs',
         'oldest_tie_witness'),
    )
    results = {}
    for name,module,before,after,witness in controls:
        source = frozen(root,f'formal/reward/{module}.lean',manifest).decode()
        if source.count(before) != 1:
            raise Rejected('Mutation control anchor is ambiguous: '+name)
        fixture = work/(name+'.lean')
        fixture.write_text(source.replace(before,after,1))
        try:
            run([lean,*FLAGS,fixture.name],work,environment,work/(name+'.log'))
        except Rejected:
            output = (work/(name+'.log')).read_text()
            lines = fixture.read_text().splitlines()
            start = next(index for index,line in enumerate(lines,1)
                         if line.startswith('theorem '+witness+' '))
            end = next((index for index,line in enumerate(lines,1)
                        if index > start and line.startswith(('theorem ','def ','end '))),len(lines)+1)
            errors = [int(match[1]) for match in re.finditer(
                re.escape(fixture.name)+r':(\d+):\d+: error',output)]
            if not any(start <= line < end for line in errors):
                raise Rejected('Negative mutant failed without constructed witness: '+name)
        else:
            raise Rejected('Invalid reward mutant accepted: '+name)
        results[name] = {'status':'REJECTED','constructedWitness':witness}
    # Placeholder/custom-axiom controls are generated only in rejected scratch
    # fixtures, never in the admitted proof source or positive build.
    (work/'Placeholder.lean').write_text('import Std\ntheorem invalid : False := by sorry\n')
    try:
        run([lean,*FLAGS,'Placeholder.lean'],work,environment,work/'placeholder.log')
    except Rejected:
        if 'sorry' not in (work/'placeholder.log').read_text():
            raise Rejected('Placeholder rejection was unrelated')
    else:
        raise Rejected('Placeholder accepted')
    for module in MODULES:
        text = 'import Std\n'
        if module == 'Reward':
            text += 'namespace Reward\naxiom rogue : False\ntheorem bad : False := rogue\nend Reward\n'
        (work/(module+'.lean')).write_text(text)
        run([lean,*FLAGS,'-o',str(objects/(module+'.olean')),module+'.lean'],
            work,environment,work/(module+'.log'))
    (work/'Audit.lean').write_bytes(frozen(root,'formal/reward/Audit.lean',manifest))
    raw = run([lean,*FLAGS,'Audit.lean'],work,environment,work/'axiom-audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    if (not any(row.get('kind') == 'forbidden-project-axiom' for row in rows)
            or not any(row.get('kind') == 'theorem' and row.get('axioms') for row in rows)):
        raise Rejected('Rogue axiom was not exposed by the actual auditor')
    try:
        check_inventory(rows,{})
    except Rejected:
        pass
    else:
        raise Rejected('Rogue axiom passed the gate')
    results['placeholder'] = {'status':'REJECTED'}
    results['rogue_axiom'] = {'status':'REJECTED'}
    return results


def verify(root=ROOT, *, candidate=False):
    root = Path(root).resolve()
    identifier = datetime.now(timezone.utc).strftime('reward-%Y%m%dT%H%M%SZ-')+secrets.token_hex(4)
    destination = root/'build/reward-contract'/identifier
    destination.mkdir(parents=True)
    report = {'schemaVersion':1,'id':identifier,'blockId':'RWD01',
              'scope':'reward-lfu-model-only','status':'BLOCKED','modelStatus':'BLOCKED',
              'productionRefinementStatus':'NOT_ESTABLISHED','inputRootHash':None,
              'builds':[],'claims':[],'blockingReasons':[],'infrastructureErrors':[],
              'environment':{'os':platform.platform(),'python':sys.version.split()[0]},
              'interpretation':'Finite mathematical model closure under the declared TCB; runtime correspondence is separately tested and excluded.'}
    try:
        block = json.loads(inside(root,BLOCK).read_text())
        manifest = inputs(root,block)
        if candidate:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        else:
            manifest.update(reviews(root))
        report['inputRootHash'] = sha(manifest)
        report['verifierHash'] = manifest['scripts/verify_reward_contract.py']
        write(destination/'manifest.json',manifest)
        snapshot(root,destination/'inputs',manifest)
        pin,toolchain = installed_toolchain(root)
        if pin != block['leanToolchain']:
            raise Rejected('Pinned Lean toolchain changed')
        tools = toolchain_inventory(toolchain)
        write(destination/'toolchain.json',tools)
        report['toolchain'] = {'pin':pin,'rootHash':sha(tools)}
        report['trust'] = block['trust']
        expected = inventory(root)
        declared,witnesses = claims(root,expected)
        for name in ('build-a','build-b'):
            report['builds'].append(build(root,destination/name,toolchain,expected,manifest))
        if report['builds'][0] != report['builds'][1]:
            raise Rejected('NONDETERMINISM: proof inventory/artifacts differ')
        report['determinism'] = 'PASS'
        report['negativeControls'] = negative_controls(root,destination/'negative-controls',toolchain,manifest)
        for relative in manifest:
            frozen(root,relative,manifest)
        if toolchain_inventory(toolchain) != tools:
            raise Rejected('Pinned toolchain changed during proof verification')
        report['claims'] = [dict(row,status='PASS',inputRootHash=report['inputRootHash'],
                                 verifierHash=report['verifierHash'],evidence='build-a/audit.jsonl')
                            for row in declared]
        report['provedTheorems'] = len(expected)
        report['axioms'] = []
        report['witnesses'] = {'required':len(witnesses),'valid':len(witnesses),'invalid':0}
        report['correspondence'] = {'classification':'MODEL_DEFINITIONS_ONLY',
                                   'requiredObjects':len(expected),'mappedObjects':len(expected),
                                   'unmappedObjects':0,'ambiguousObjects':0,
                                   'productionSemanticRefinement':'OUT_OF_SCOPE'}
        report['provenance'] = {'publicClaims':len(declared),'fullyBound':len(declared),
                                'orphanClaims':0}
        report['modelStatus'] = 'VERIFIED'
        report['closureBoundary'] = {'verifiedSurface':['frozen Reward and LFU mathematical definitions and registered theorem inventory'],
                                     'trustedSurface':block['trust'],
                                     'excludedSurface':block['excluded']}
        if not report['blockingReasons']:
            report['status'] = 'VERIFIED'
    except (Rejected,ValueError,KeyError,TypeError) as error:
        report['blockingReasons'].append(type(error).__name__+': '+str(error))
    except (OSError,RuntimeError) as error:
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append(type(error).__name__+': '+str(error))
    write(destination/'report.json',report)
    print(json.dumps({'status':report['status'],'modelStatus':report['modelStatus'],
                      'theorems':report.get('provedTheorems'),'report':str(destination/'report.json')}))
    return 0 if report['status']=='VERIFIED' else 2 if report['status']=='INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',action='store_true',help='Kernel checks only; six review records remain unresolved')
    args = parser.parse_args()
    raise SystemExit(verify(candidate=args.candidate))
