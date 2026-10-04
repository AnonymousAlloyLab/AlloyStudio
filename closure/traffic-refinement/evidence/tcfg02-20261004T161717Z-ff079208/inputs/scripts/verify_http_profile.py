#!/usr/bin/env python3
"""Check TCFG02 HTTP profile configuration guards twice, offline, with source binding.

This block discharges a HTTP profile subclaim only. The unchanged umbrella TRF-00
obligation and the remaining traffic obligations are not certified by this gate.
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
                         digest, inside, json_bytes, network_witness,
                         run, toolchain_inventory)
from verify_traffic_proofs import snapshot_inputs, frozen_bytes
from review_ladder import check_reviews

FLAGS = [*BASE_FLAGS, '-DmaxRecDepth=4096']
BLOCK = 'formal/traffic_profile/block.json'
MODULES = ('TrafficConfig.Scalar', 'TrafficConfig.Extracted', 'TrafficConfig.Spec',
           'TrafficProfile.Model', 'TrafficProfile.Extracted', 'TrafficProfile.Spec')
SOURCES = tuple(('formal/traffic_config/' if m.startswith('TrafficConfig.') else 'formal/traffic_profile/')
                + m.replace('.', '/') + '.lean' for m in MODULES)
CLAIM = {
    'id': 'TRF00-HTTP-PROFILE-INIT',
    'statement': 'The admitted 23-field HTTP profile accepts exactly its independent scalar domains and three relations; accepted scalars are preserved and the consumed admission initial state is uniquely valid for the explicit lane and clock sample.',
    'passPredicate': 'independent_profile_spec_and_constructor_AST_linkage_and_all_registered_empty_axiom_theorems_in_two_identical_offline_builds_and_registered_runtime_mutation_controls',
    'publicClaims': ['docs/http-profile-obligation.md', 'formal/traffic_profile/README.md'],
    'parentObligation': 'TRF-00', 'closesParent': False,
}
TRUST = [
    {'id':'TCB-LEAN','classification':'TRUSTED','components':['Pinned Lean kernel/compiler/distribution; empty transitive axiom audit.']},
    {'id':'TCB-TRANSLATION','classification':'TRUSTED','components':['Frozen restricted AST translator, constructor correspondence templates, audit and verifier scripts.']},
    {'id':'TCB-PYTHON','classification':'TRUSTED','components':['Exact built-in type/arithmetic/ratio semantics; dataclass copy, ordinary attributes and empty collection/lock allocation; one successful callback supplies explicit signed clock sample.']},
    {'id':'TCB-HOST','classification':'TRUSTED','components':['Python standard library, hashing, namespace/process/filesystem isolation and hardware; no monkey-patched dependencies, hostile trusted code or concurrent normalization mutation.']},
]
EXCLUDED = ['Complete TRF-00 service/deployment profile, independent observation contract and whole Portal initialization.',
            'Subsequent admission histories, clocks, ownership/resource/liveness invariants, RSS containment and actual platform deployment.',
            'Arbitrary Python execution, allocation success, concrete lock/container identities and future source revisions.']
REQUIRED_INPUTS = frozenset((*SOURCES, 'formal/traffic_profile/Audit.lean',
    'formal/traffic_profile/theorems.json', 'formal/traffic_profile/README.md',
    'formal/traffic_profile/profile-template.py.txt', 'formal/traffic_profile/runtime-linkage.json',
    'closure/traffic-refinement/http-profile-spec.json', 'docs/http-profile-obligation.md',
    'formal/traffic_config/block.json', 'traffic_limits.py', 'traffic_profile.py', 'traffic_http.py',
    'scripts/http_profile_bridge.py', 'scripts/verify_http_profile.py',
    'scripts/lean_offline.py', 'scripts/verify_lean.py', 'scripts/verify_traffic_proofs.py',
    'scripts/bridge_policies.py', 'scripts/review_ladder.py', 'tests/test_review_ladder.py',
    'tests/test_http_profile_bridge.py', 'tests/test_http_profile_gate.py',
    'tests/test_http_profile_initialization.py', 'formal/lean-toolchain', 'lean-toolchain'))
REQUIRED_THEOREMS = frozenset('AlloyStudio.HttpProfile.Spec.' + name for name in (
    'accepted_iff', 'normalize_iff', 'normalize_preserves', 'defaults_exact',
    'defaults_accepted', 'default_handler_total', 'default_burst_total', 'default_rate_total',
    'accepted_initial_valid', 'initial_exists_unique', 'handler_bounds', 'capacity_bounds',
    'default_public_witness', 'default_control_negative_clock_witness',
    'handler_boundary_witness', 'handler_excess_witness', 'header_boundary_witness',
    'header_excess_witness', 'line_excess_witness', 'zero_handlers_witness',
    'boolean_handlers_witness', 'count_boundary_witness', 'count_excess_witness',
    'duration_boundary_witness', 'duration_excess_witness'))


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def inputs(root, block):
    required_policy = {'schemaVersion': 1, 'id': 'TCFG02', 'scope': 'http-profile-and-initial-state',
        'flags': FLAGS, 'allowlistedAxioms': [], 'requiredCleanBuilds': 2,
        'requiredReviews': 6, 'umbrellaObligationsClosed': [], 'modules': list(MODULES),
        'claims': [CLAIM], 'trust': TRUST, 'excluded': EXCLUDED}
    if any(block.get(key) != value for key, value in required_policy.items()):
        raise Rejected('Invalid HTTP profile block policy.')
    if not isinstance(block.get('inputs'), dict) or set(block['inputs']) != REQUIRED_INPUTS:
        raise Rejected('Incomplete or unexpected HTTP profile input inventory.')
    for relative, expected in block['inputs'].items():
        if digest(inside(root, relative)) != expected:
            raise Rejected('Frozen HTTP profile input changed: ' + relative)
    dependency = json.loads((root / 'formal/traffic_config/block.json').read_text())
    reused = ('traffic_limits.py', *SOURCES[:3])
    if dependency.get('id') != 'TCFG01' or any(
            dependency.get('inputs', {}).get(p) != block['inputs'][p] for p in reused):
        raise Rejected('Reused scalar dependency differs from frozen TCFG01.')
    actual = {p.relative_to(root).as_posix() for p in (root / 'formal/traffic_profile').rglob('*.lean')}
    if actual != {p for p in SOURCES if p.startswith('formal/traffic_profile/')} | {'formal/traffic_profile/Audit.lean'}:
        raise Rejected('Unregistered or missing HTTP profile proof source.')
    return dict(block['inputs'], **{BLOCK: digest(inside(root, BLOCK))})


def inventory(root):
    rows = json.loads((root / 'formal/traffic_profile/theorems.json').read_text())['theorems']
    expected = {}
    for row in rows:
        if (set(row) != {'name', 'module', 'levelParameters', 'typeSha256', 'axioms'}
                or row['axioms'] != [] or row['module'] not in MODULES or row['name'] in expected):
            raise Rejected('Invalid HTTP profile theorem inventory.')
        expected[row['name']] = {key: value for key, value in row.items() if key != 'axioms'}
    if not expected:
        raise Rejected('Empty HTTP profile theorem inventory.')
    if not REQUIRED_THEOREMS <= set(expected):
        raise Rejected('Missing required HTTP profile claim theorem or witness.')
    return expected


def reviews(root):
    return check_reviews(root, BLOCK)


def build(root, work, toolchain, expected, manifest):
    work.mkdir()
    objects = work / 'objects'
    for package in ('TrafficConfig', 'TrafficProfile'):
        (objects / package).mkdir(parents=True)
        (work / package).mkdir()
    environment = clean_environment(toolchain, objects)
    (work / 'scratch').mkdir()
    environment['TMPDIR'] = str(work / 'scratch')
    network = network_witness(work, environment)
    bridge_code = ('import sys,json; from pathlib import Path; '
        'sys.path.insert(0,sys.argv[1]); import http_profile_bridge as b; '
        'print(json.dumps(b.check(Path(sys.argv[1]).parent),sort_keys=True))')
    result = run([sys.executable, '-I', '-c', bridge_code, str(root / 'scripts')],
                 root, environment, work / 'bridge.json')
    bridge = json.loads(result)
    if bridge.get('status') != 'PASS':
        raise Rejected('HTTP profile production AST translation did not pass.')
    for module, relative in zip(MODULES, SOURCES):
        source = frozen_bytes(root, relative, manifest)
        check_proof_source(source.decode(), set(MODULES))
        target = work / (module.replace('.', '/') + '.lean')
        target.write_bytes(source)
        target.chmod(0o400)
        run([str(toolchain / 'bin/lean'), *FLAGS, '-o',
             str(objects / (module.replace('.', '/') + '.olean')),
             module.replace('.', '/') + '.lean'], work, environment, work / (module + '.log'))
    (work / 'Audit.lean').write_bytes(frozen_bytes(root, 'formal/traffic_profile/Audit.lean', manifest))
    raw = run([str(toolchain / 'bin/lean'), *FLAGS, 'Audit.lean'], work, environment, work / 'audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    checked = check_inventory(rows, expected)
    return {'status': 'PASS', 'theorems': len(checked), 'axioms': [], 'network': network,
            'bridge': bridge, 'inventorySha256': sha(checked),
            'artifacts': {m: digest(objects / (m.replace('.', '/') + '.olean')) for m in MODULES}}


def negative_controls(root, work, toolchain):
    work.mkdir()
    objects = work / 'objects'
    for package in ('TrafficConfig','TrafficProfile'):
        (objects / package).mkdir(parents=True)
        (work / package).mkdir()
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain / 'bin/lean')
    # Legal source mutations must change the generated program and then fail the
    # independently frozen specification, never merely a golden output check.
    mutations = (
        ('line_relation', 'self.line_bytes > self.header_bytes', 'self.line_bytes >= self.header_bytes'),
        ('header_count', 'self.header_count > 100', 'self.header_count > 101'),
        ('handler_total', 'self.public_handlers + self.control_handlers > 256', 'self.public_handlers + self.control_handlers > 257'),
        ('lane_selection', 'profile.control_handlers if control else profile.public_handlers', 'profile.public_handlers if control else profile.control_handlers'),
        ('token_unit', 'burst * 1000000000', 'burst * 999999999'),
        ('initial_occupancy', "'active': 0", "'active': 1"),
    )
    for module, relative in zip(MODULES,SOURCES):
        if module in ('TrafficProfile.Extracted','TrafficProfile.Spec'): continue
        target = work / (module.replace('.','/')+'.lean')
        target.write_bytes((root/relative).read_bytes())
        run([lean,*FLAGS,'-o',str(objects/(module.replace('.','/')+'.olean')),str(target.relative_to(work))],work,environment,work/(module+'.log'))
    results = {}
    for name, before, after in mutations:
        code = ('import sys;from pathlib import Path;sys.path.insert(0,sys.argv[1]);'
            'import http_profile_bridge as b;p=Path(sys.argv[1]).parent;'
            's=(p/"traffic_profile.py").read_text();assert sys.argv[2] in s;'
            'print(b.generate(p,s.replace(sys.argv[2],sys.argv[3])),end="")')
        mutated = run([sys.executable,'-I','-c',code,str(root/'scripts'),before,after],root,environment,work/(name+'-program.txt'))
        (work/'TrafficProfile/Extracted.lean').write_bytes(mutated)
        (work/'TrafficProfile/Spec.lean').write_bytes((root/'formal/traffic_profile/TrafficProfile/Spec.lean').read_bytes())
        run([lean,*FLAGS,'-o',str(objects/'TrafficProfile/Extracted.olean'),'TrafficProfile/Extracted.lean'],work,environment,work/(name+'-extract.log'))
        try:
            run([lean,*FLAGS,'TrafficProfile/Spec.lean'],work,environment,work/(name+'-spec.log'))
        except Rejected:
            output=(work/(name+'-spec.log')).read_text()
            if 'error:' not in output or 'unknown module' in output or 'unknown identifier' in output:
                raise Rejected('Unrelated arithmetic/state mutation failure: '+name)
        else:
            raise Rejected('Independent profile specification accepted mutation: '+name)
        results[name]='REJECTED_BY_INDEPENDENT_SPEC'
    # Restore the valid extraction/spec before the two audit controls.
    for module in ('TrafficProfile.Extracted','TrafficProfile.Spec'):
        target=work/(module.replace('.','/')+'.lean')
        target.write_bytes((root/'formal/traffic_profile'/(module.replace('.','/')+'.lean')).read_bytes())
        run([lean,*FLAGS,'-o',str(objects/(module.replace('.','/')+'.olean')),str(target.relative_to(work))],work,environment,work/(module+'-restored.log'))
    probe=work/'TrafficProfile/Rogue.lean'
    probe.write_text('namespace AlloyStudio.HttpProfile\naxiom injected : False\ntheorem injected_use : False := injected\nend AlloyStudio.HttpProfile\n')
    run([lean,*FLAGS,'-o',str(objects/'TrafficProfile/Rogue.olean'),'TrafficProfile/Rogue.lean'],work,environment,work/'rogue.log')
    audit=(root/'formal/traffic_profile/Audit.lean').read_text()
    (work/'Audit.lean').write_text('import TrafficProfile.Rogue\n'+audit)
    raw=run([lean,*FLAGS,'Audit.lean'],work,environment,work/'axiom-audit.jsonl')
    rows=[json.loads(x) for x in raw.decode().splitlines()]
    if not any(r.get('kind')=='forbidden-project-axiom' for r in rows) or not any(r.get('axioms') for r in rows):
        raise Rejected('Introduced axiom was not exposed by audit.')
    results['introducedAxiom']='REJECTED_BY_AUDITOR'
    (work/'Placeholder.lean').write_text('theorem invalid_placeholder : False := by sorry\n')
    try:
        run([lean,*FLAGS,'Placeholder.lean'],work,environment,work/'placeholder.log')
    except Rejected:
        if 'sorry' not in (work/'placeholder.log').read_text():
            raise Rejected('Unrelated placeholder rejection.')
    else:
        raise Rejected('Placeholder accepted.')
    results['placeholder']='REJECTED_BY_COMPILER'
    return results


def verify(root=ROOT, candidate=False):
    root = Path(root).resolve()
    identifier = datetime.now(timezone.utc).strftime('tcfg02-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    work = root / 'build/trf-closure' / identifier
    work.mkdir(parents=True)
    report = {'schemaVersion': 1, 'id': identifier, 'blockId': 'TCFG02', 'status': 'BLOCKED',
              'scope': 'http-profile-and-initial-state', 'umbrellaObligationsClosed': [],
              'builds': [], 'blockingReasons': [], 'infrastructureErrors': []}
    try:
        block = json.loads(inside(root, BLOCK).read_text())
        manifest = inputs(root, block)
        if candidate:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        else:
            manifest.update(reviews(root))
        report['inputRootHash'] = sha(manifest)
        report['verifier'] = {'id': 'V-TCFG02', 'sha256': manifest['scripts/verify_http_profile.py']}
        write(work / 'manifest.json', manifest)
        frozen = snapshot_inputs(root, work / 'inputs', manifest)
        if not candidate:
            reviews(frozen)
        pin, toolchain = installed_toolchain(frozen)
        if pin != block['leanToolchain']:
            raise Rejected('HTTP profile proof toolchain pin mismatch.')
        version_environment = clean_environment(toolchain)
        version_environment['TMPDIR'] = str(work)
        actual_version = run([str(toolchain / 'bin/lean'), '--version'], work,
            version_environment, work / 'lean-version.log').decode().strip()
        if not re.search(r'\bversion ' + re.escape(pin.split(':v')[1]) + r'(?:[,\s])', actual_version):
            raise Rejected('Installed Lean version differs from its frozen pin.')
        tools_before = toolchain_inventory(toolchain)
        write(work / 'toolchain.json', tools_before)
        report['toolchain'] = {'pin': pin, 'version': actual_version, 'rootHash': sha(tools_before)}
        report['executionEnvironment'] = {'platform': platform.platform(), 'python': sys.version,
            'pythonExecutableSha256': digest(Path(sys.executable).resolve()),
            'flags': FLAGS, 'network': 'isolated user+network namespace for every proof subprocess'}
        report['trust'] = block['trust']
        report['excluded'] = block['excluded']
        expected = inventory(frozen)
        for name in ('build-a', 'build-b'):
            report['builds'].append(build(frozen, work / name, toolchain, expected, manifest))
        if report['builds'][0] != report['builds'][1]:
            raise Rejected('Independent HTTP profile proof builds disagree.')
        report['determinism'] = 'PASS'
        report['negativeControls'] = negative_controls(frozen, work / 'negative-controls', toolchain)
        environment = clean_environment(toolchain)
        environment['TMPDIR'] = str(work)
        for test in ('test_http_profile_bridge.py', 'test_http_profile_gate.py', 'test_http_profile_initialization.py', 'test_review_ladder.py'):
            run([sys.executable, '-I', '-c', 'import sys,runpy;root,test=sys.argv[1:];sys.path.insert(0,root);sys.argv=[test];runpy.run_path(test,run_name="__main__")', str(frozen), str(frozen / 'tests' / test)], frozen, environment, work / (test + '.log'))
        report['verifierTests'] = 'PASS'
        if toolchain_inventory(toolchain) != tools_before:
            raise Rejected('Toolchain changed during HTTP profile proof verification.')
        for relative, expected_hash in manifest.items():
            if digest(inside(root, relative)) != expected_hash or digest(inside(frozen, relative)) != expected_hash:
                raise Rejected('HTTP profile input mutated during verification: ' + relative)
        report['claims'] = [{'id': 'TRF00-HTTP-PROFILE-INIT', 'status': 'PASS',
            'classification': 'PROVED_RESTRICTED_HTTP_PROFILE_AND_INITIAL_STATE', 'inputRootHash': report['inputRootHash'],
            'verifier': report['verifier'], 'theorems': sorted(expected), 'exitCode': 0,
            'evidence': ['build-a/audit.jsonl', 'build-b/audit.jsonl', 'build-a/bridge.json'],
            'executionEnvironment': report['executionEnvironment']}]
        report['provedTheorems'] = len(expected)
        if not report['blockingReasons']:
            report['status'] = 'VERIFIED'
    except (Rejected, ValueError, KeyError, TypeError) as error:
        report['blockingReasons'].append(type(error).__name__ + ': ' + str(error))
    except (OSError, RuntimeError) as error:
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append(type(error).__name__ + ': ' + str(error))
    write(work / 'report.json', report)
    print(json.dumps({'status': report['status'], 'scope': report['scope'],
                     'report': str(work / 'report.json')}))
    return 0 if report['status'] == 'VERIFIED' else 2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', action='store_true')
    args = parser.parse_args()
    raise SystemExit(verify(candidate=args.candidate))
