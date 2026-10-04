#!/usr/bin/env python3
"""Verify the frozen SQL separation block twice, offline, with source bindings.

The theorem is universal over modeled values; production correspondence uses a
restricted trusted extractor. This does not discharge the whole-portal ledger.
"""
from __future__ import annotations
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import secrets
import shutil
import sys

from lean_offline import ROOT, clean_environment, installed_toolchain
from sql_separation_bridge import extract, canonical, sha, require_frozen_sources
from verify_lean import (FLAGS, Rejected, check_inventory, check_proof_source,
                         check_reviews, digest, network_witness, run,
                         toolchain_inventory)

BLOCK = 'formal/sql/block.json'
BRIDGE_THEOREMS = {'SqlProduction.adapter_lowering','SqlProduction.accepted_statement',
                  'SqlProduction.syntax_noninterference','SqlProduction.arbitrary_request',
                  'SqlProduction.trace_closed','SqlProduction.frontend_composition'}


def byte_list(value):
    return '[' + ','.join(str(byte) for byte in value.encode('utf-8')) + ']'


def render_bridge(extraction):
    entries = []
    for entry in extraction['registry']:
        parameters = []
        for parameter in entry['parameters']:
            if parameter['type'] == 'text' and type(parameter['maxBytes']) is int and parameter['maxBytes'] >= 0:
                parameters.append('.text ' + str(parameter['maxBytes']))
            elif parameter['type'] == 'int':
                parameters.append('.integer')
            else:
                raise Rejected('Unsupported concrete parameter type')
        entries.append('(' + json.dumps(entry['id']) + ', { sql := ' + byte_list(entry['sql']) +
                       ', parameters := [' + ','.join(parameters) + '] })')
    return '''import SqlSeparation
open SqlSeparation
namespace SqlProduction
def registry : Registry := [
''' + ',\n'.join(entries) + '''\n]
def controls : List Bytes := [
''' + ',\n'.join(byte_list(value) for value in extraction['controls']) + '''\n]
-- Lowering of the exactly checked production adapter body. The rejecting or
-- identity validator is overapproximated by an arbitrary gate.
def adapter (identifier : String) (values : List Value)
    (gate : Entry → List Value → Bool) : Option Bound :=
  match lookup registry identifier with
  | none => none
  | some entry => if gate entry values then some ⟨entry.sql, values⟩ else none
theorem adapter_lowering (identifier : String) (values : List Value)
    (gate : Entry → List Value → Bool) :
    adapter identifier values gate = executeWith registry identifier values gate := rfl
theorem accepted_statement (identifier : String) (values : List Value)
    (gate : Entry → List Value → Bool) (call : Bound)
    (accepted : adapter identifier values gate = some call) :
    ∃ entry, lookup registry identifier = some entry ∧
      call.sql = entry.sql ∧ call.values = values :=
  executeWith_separates registry identifier values gate call accepted
theorem syntax_noninterference (identifier : String) (a b : List Value)
    (ga gb : Entry → List Value → Bool) (left right : Bound)
    (ha : adapter identifier a ga = some left) (hb : adapter identifier b gb = some right) :
    left.sql = right.sql :=
  executeWith_same_id_same_syntax registry identifier a b ga gb left right ha hb
theorem arbitrary_request (identifier : String) (wire : List Wire)
    (gate : Entry → List Value → Bool) (call : Bound)
    (accepted : backendWith registry identifier wire gate = some call) :
    ∃ values entry, decodeValues wire = some values ∧
      lookup registry identifier = some entry ∧ call.sql = entry.sql ∧ call.values = values :=
  arbitrary_wireWith_separates registry identifier wire gate call accepted
theorem trace_closed (gate : Entry → List Value → Bool) (actions : List Action) :
    TraceClosed registry controls (runTraceWith registry controls gate actions) :=
  runTraceWith_closed registry controls gate actions
theorem frontend_composition (identifier : String) (values : List Value)
    (gate : Entry → List Value → Bool) :
    backendWith registry identifier (encodeValues values) gate = adapter identifier values gate :=
  frontend_backendWith_composition registry identifier values gate
end SqlProduction
'''


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def manifest(root, block):
    entries = dict(block['inputs'])
    for name, expected in entries.items():
        path = root / name
        if path.is_symlink() or not path.is_file() or digest(path) != expected:
            raise Rejected('Frozen SQL verification input changed: ' + name)
    entries[BLOCK] = digest(root / BLOCK)
    return entries


def build(root, directory, toolchain, extraction, expected):
    directory.mkdir()
    objects = directory / 'objects'
    objects.mkdir()
    environment = clean_environment(toolchain, objects)
    network = network_witness(directory, environment)
    for name in ('SqlSeparation.lean','Audit.lean'):
        shutil.copyfile(root / 'formal/sql' / name, directory / name)
    generated = render_bridge(extraction)
    check_proof_source(generated, {'SqlSeparation'})
    (directory / 'SqlProduction.lean').write_text(generated, encoding='utf-8')
    audit = (directory / 'Audit.lean').read_text()
    audit = audit.replace('import SqlSeparation\n', 'import SqlProduction\n')
    audit = audit.replace('if owner != `SqlSeparation then continue',
                          'if owner != `SqlSeparation && owner != `SqlProduction then continue')
    if audit == (directory / 'Audit.lean').read_text():
        raise Rejected('Concrete bridge audit registration missing')
    (directory / 'AuditProduction.lean').write_text(audit)
    lean = str(toolchain / 'bin/lean')
    for name in ('SqlSeparation','SqlProduction'):
        run([lean,*FLAGS,'-o',str(objects / (name+'.olean')),name+'.lean'], directory,
            environment, directory / (name+'.log'))
    raw = run([lean,*FLAGS,'AuditProduction.lean'],directory,environment,directory/'audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    model = [row for row in rows if row.get('module') == 'SqlSeparation']
    check_inventory(model, expected)
    concrete = [row for row in rows if row.get('module') == 'SqlProduction']
    if ({row['name'] for row in concrete} != BRIDGE_THEOREMS or
            any(row.get('kind') != 'theorem' or row.get('axioms') != [] for row in concrete) or
            len(rows) != len(model)+len(concrete)):
        raise Rejected('Concrete registry theorem inventory mismatch')
    return {'status':'PASS','network':network,'theorems':rows,
            'generatedBridgeSha256':sha(generated.encode()),
            'artifacts':{name:digest(objects/name) for name in ('SqlSeparation.olean','SqlProduction.olean')}}


def verify(root=ROOT, *, candidate=False):
    root = Path(root)
    identifier = datetime.now(timezone.utc).strftime('sql-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    destination = root / 'build/sql-separation' / identifier
    destination.mkdir(parents=True)
    report = {'schemaVersion':1,'id':identifier,'status':'BLOCKED','blockStatus':'BLOCKED',
              'inputRootHash':None,'builds':[], 'blockingReasons':[], 'infrastructureErrors':[],
              'interpretation':'Universal modeled SQL/value separation with checked source correspondence under the declared TCB, not universal application immunity.'}
    try:
        block = json.loads((root/BLOCK).read_text())
        if (block['id'] != 'SQL01' or block['flags'] != FLAGS or block['allowlistedAxioms'] != []
                or block['requiredCleanBuilds'] != 2 or block['requiredReviews'] != 6):
            raise Rejected('Invalid SQL verification policy')
        before = manifest(root, block)
        if not candidate:
            reviews = check_reviews(root,BLOCK)
            before.update(reviews)
            for name in reviews:
                notes = name.removesuffix('.json')+'.md'
                before[notes] = digest(root/notes)
        else:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        report['inputRootHash'] = sha(canonical(before))
        write(destination/'manifest.json',before)
        pin, toolchain = installed_toolchain(root)
        if pin != block['leanToolchain']:
            raise Rejected('SQL proof toolchain pin changed')
        tool_files = toolchain_inventory(toolchain)
        tool_hash = sha(canonical(tool_files))
        write(destination/'toolchain.json',tool_files)
        report['toolchain'] = {'pin':pin,'rootHash':tool_hash}
        report['trust'] = block['trust']
        extraction = extract(root)
        require_frozen_sources(extraction,before)
        write(destination/'correspondence.json',extraction)
        report['correspondence'] = {'classification':extraction['classification'],
                                    'mappedObjects':len(extraction['mappings']),
                                    'queries':len(extraction['registry']),
                                    'hash':sha(canonical(extraction))}
        source = (root/'formal/sql/SqlSeparation.lean').read_text()
        check_proof_source(source,set())
        inventory = json.loads((root/'formal/sql/theorems.json').read_text())['theorems']
        expected = {row['name']:{k:v for k,v in row.items() if k!='axioms'} for row in inventory}
        if (not expected or len(expected) != len(inventory) or
                any(row.get('axioms') != [] for row in inventory)):
            raise Rejected('Invalid frozen SQL theorem inventory')
        for name in ('build-a','build-b'):
            report['builds'].append(build(root,destination/name,toolchain,extraction,expected))
        if report['builds'][0] != report['builds'][1]:
            raise Rejected('Nondeterministic SQL proof/correspondence artifacts')
        report['determinism'] = 'PASS'
        for path, value in before.items():
            if digest(root/path) != value:
                raise Rejected('SQL inputs changed during verification')
        if extract(root) != extraction:
            raise Rejected('Production source inventory changed during verification')
        if sha(canonical(toolchain_inventory(toolchain))) != tool_hash:
            raise Rejected('Lean toolchain changed during verification')
        report['blockStatus'] = 'VERIFIED'
        report['provedTheorems'] = len(report['builds'][0]['theorems'])
        report['axioms'] = []
        report['provenance'] = [{'claim':name,'inputRootHash':report['inputRootHash'],
                                 'verifier':'scripts/verify_sql_separation.py',
                                 'evidence':'correspondence.json' if name=='SQL-BRIDGE' else 'build-a/audit.jsonl'}
                                for name in ('SQL-LEAN','SQL-BRIDGE')]
        if not report['blockingReasons']:
            report['status'] = 'VERIFIED'
    except (Rejected,ValueError,KeyError,TypeError) as error:
        report['blockingReasons'].append(type(error).__name__ + ': ' + str(error))
    except (OSError,RuntimeError) as error:
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append(type(error).__name__ + ': ' + str(error))
    write(destination/'report.json', report)
    print(json.dumps({'status':report['status'],'blockStatus':report['blockStatus'],
                      'report':str(destination/'report.json'),'theorems':report.get('provedTheorems')}))
    return 0 if report['status']=='VERIFIED' else 2 if report['status']=='INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate',action='store_true',help='Development only; skips review records and always remains BLOCKED')
    args = parser.parse_args()
    raise SystemExit(verify(candidate=args.candidate))
