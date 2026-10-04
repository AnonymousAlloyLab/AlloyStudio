#!/usr/bin/env python3
"""Verify TING01 sampled header/body deadline cuts in two clean offline builds.

Only TRF01-DEADLINE-CUTS is discharged. Original TRF-01 remains open for the
complete strict decoder, pre-handler allocation and composed admission proof.
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

FLAGS = [*BASE_FLAGS, '-DmaxRecDepth=4096', '-DmaxHeartbeats=2000000']
BLOCK = 'formal/ingress_deadlines/block.json'
SCOPE = 'TRF-01-sampled-phase-deadline-cuts'
LEAN_TOOLCHAIN = 'leanprover/lean4:v4.34.1'
MODULES = ('IngressDeadlines.Model', 'IngressDeadlines.Extracted', 'IngressDeadlines.Spec')
SOURCES = tuple('formal/ingress_deadlines/' + module.replace('.', '/') + '.lean' for module in MODULES)
SPEC = 'closure/traffic-refinement/ingress-deadline-spec.json'
SOURCE_MANIFEST = 'closure/traffic-refinement/ingress-deadline-sources.json'
PRIOR_MANIFEST = 'closure/traffic-refinement/source-manifest.json'
DEPENDENCY_BLOCK = 'formal/service_profile/block.json'
DEPENDENCY_BLOCK_SHA = 'e1fc05d411a453ebba4ccb3be7f65d2d6ad731e565741db0c2b62653fa5519ca'
DEPENDENCY_REPORT = 'closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/report.json'
DEPENDENCY_REPORT_SHA = '24642ba8bd6c7d171e2fab165af33eddc56613d0460582e2da34e7065dfc64e7'
BASELINE_REGISTRY_SHA = 'c87b9576349f1e2a071379b7edb77cb090815717dbf1341a0db23b8b768dcfbc'
CLAIM = {
    'id': 'TRF01-DEADLINE-CUTS',
    'statement': 'Successful registered header/body completion cuts sample a clock strictly before the fixed phase deadline; buffered reads cannot bypass the final guard and a body reset uses exactly the validated header sample.',
    'passPredicate': 'independent_deadline_semantics_and_closed_AST_mapping_and_all_registered_empty_axiom_theorems_in_two_identical_offline_builds_and_constructed_mutation_controls',
    'publicClaims': ['docs/ingress-deadline-obligation.md', 'formal/ingress_deadlines/README.md'],
    'parentObligation': 'TRF-01', 'closesParent': False,
}
TRUST = [
    {'id': 'TCB-LEAN', 'classification': 'TRUSTED', 'components': ['Pinned Lean 4.34.1 kernel/compiler/distribution and separately registered audit machinery; empty transitive axiom audit.']},
    {'id': 'TCB-TRANSLATION', 'classification': 'TRUSTED', 'components': ['Closed AST bridge, admitted work/call-return interpretation, verifier and source mapping; not a verified arbitrary Python VM.']},
    {'id': 'TCB-CLOCK', 'classification': 'TRUSTED', 'components': ['Finite monotonic CPython float samples embedded by order in signed logical integers; the successful clock sample is the observation cut. No claim about scheduling delay after that cut.']},
    {'id': 'TCB-HOST', 'classification': 'TRUSTED', 'components': ['CPython, socket/JSON/stdlib semantics, successful ordinary execution, Linux process/filesystem/network namespace isolation, SHA-256 and hardware.']},
]
EXCLUDED = [
    'Complete original TRF-01 strict byte/request-line/header/JSON decoder, pre-handler allocation and composed public/control admission proof.',
    'Unsampled elapsed wall time, CPU scheduling guarantees, total handler lifetime and asynchronous work after the accepted cut.',
    'JSON semantic parser correctness, native Windows/macOS/IIS/Cloudflare deployment and future source revisions.',
]
TESTS = ('test_ingress_deadlines.py', 'test_ingress_deadline_bridge.py',
         'test_ingress_deadline_gate.py', 'test_review_ladder.py', 'test_traffic_http.py')
REQUIRED_INPUTS = frozenset((*SOURCES, SPEC, SOURCE_MANIFEST, PRIOR_MANIFEST,
    DEPENDENCY_BLOCK, DEPENDENCY_REPORT, 'closure/traffic-obligations.json',
    'formal/ingress_deadlines/Audit.lean', 'formal/ingress_deadlines/theorems.json',
    'formal/ingress_deadlines/consumer-template.py.txt',
    'formal/ingress_deadlines/README.md', 'docs/ingress-deadline-obligation.md',
    'scripts/ingress_deadline_bridge.py', 'scripts/verify_ingress_deadlines.py',
    'scripts/lean_offline.py', 'scripts/verify_lean.py', 'scripts/verify_traffic_proofs.py',
    'scripts/bridge_policies.py', 'scripts/review_ladder.py',
    'formal/lean-toolchain', 'lean-toolchain', 'server.py', 'traffic_http.py',
    'traffic_profile.py', 'traffic_limits.py', 'admin_auth.py', *('tests/' + test for test in TESTS)))
REQUIRED_THEOREMS = frozenset('AlloyStudio.IngressDeadlines.Spec.' + name for name in (
    'check_deadline_sound', 'check_deadline_complete', 'initial_safe', 'run_safe',
    'initial_run_safe', 'unguarded_program_rejected', 'unguarded_late_delivery_executes',
    'unguarded_late_delivery_unsafe', 'recv_program_guarded', 'readline_program_guarded',
    'read1_program_guarded', 'parse_program_guarded', 'body_program_guarded',
    'begin_body_program_guarded', 'reset_uses_current_sample',
    'work_after_guard_cannot_deliver', 'work_after_guard_cannot_reset'))


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def required_inputs(root):
    source = json.loads(inside(root, SOURCE_MANIFEST).read_text())
    files = source.get('files')
    if not isinstance(files, dict) or not files:
        raise Rejected('Empty or malformed ingress source/dependency inventory.')
    for name, value in files.items():
        if not isinstance(name, str) or not isinstance(value, str) or re.fullmatch(r'[0-9a-f]{64}', value) is None:
            raise Rejected('Invalid source/dependency identity.')
        inside(root, name)
    return REQUIRED_INPUTS | set(files)


def inputs(root, block):
    policy = {'schemaVersion': 1, 'id': 'TING01', 'scope': SCOPE, 'flags': FLAGS,
              'leanToolchain': LEAN_TOOLCHAIN,
              'allowlistedAxioms': [], 'requiredCleanBuilds': 2, 'requiredReviews': 6,
              'umbrellaObligationsClosed': [], 'modules': list(MODULES),
              'claims': [CLAIM], 'trust': TRUST, 'excluded': EXCLUDED}
    if any(block.get(key) != value for key, value in policy.items()):
        raise Rejected('Invalid sampled ingress deadline block policy.')
    if not isinstance(block.get('inputs'), dict) or set(block['inputs']) != required_inputs(root):
        raise Rejected('Incomplete or unexpected ingress deadline input inventory.')
    for relative, expected in block['inputs'].items():
        if digest(inside(root, relative)) != expected:
            raise Rejected('Frozen ingress deadline input changed: ' + relative)
    if digest(root / 'closure/traffic-obligations.json') != BASELINE_REGISTRY_SHA:
        raise Rejected('Original traffic obligation registry changed.')
    original = next(row for row in json.loads((root / 'closure/traffic-obligations.json').read_text())['obligations']
                    if row['id'] == 'TRF-01')
    specification = json.loads((root / SPEC).read_text())
    if (specification.get('id') != 'TING01' or specification.get('parent') != 'TRF-01'
            or specification.get('closesParent') is not False
            or specification.get('original') != {key: original[key] for key in ('id', 'statement', 'plannedPassCondition', 'dependsOn')}):
        raise Rejected('Original TRF-01 identity or child-only specification changed.')
    if digest(root / DEPENDENCY_BLOCK) != DEPENDENCY_BLOCK_SHA or digest(root / DEPENDENCY_REPORT) != DEPENDENCY_REPORT_SHA:
        raise Rejected('Verified TCFG03 dependency identity changed.')
    dependency = json.loads((root / DEPENDENCY_BLOCK).read_text())
    report = json.loads((root / DEPENDENCY_REPORT).read_text())
    if report.get('status') != 'VERIFIED' or report.get('umbrellaObligationsClosed') != ['TRF-00']:
        raise Rejected('Missing verified TRF-00 dependency.')
    if dependency['inputs'].get(PRIOR_MANIFEST) != digest(root / PRIOR_MANIFEST):
        raise Rejected('Previous application/dependency inventory changed.')
    prior_files = json.loads((root / PRIOR_MANIFEST).read_text())['files']
    files = json.loads((root / SOURCE_MANIFEST).read_text())['files']
    if not set(prior_files) <= set(files):
        raise Rejected('Application/dependency input was dropped from successor inventory.')
    if any(block['inputs'].get(name) != value for name, value in files.items()):
        raise Rejected('Source/dependency manifest hash mismatch.')
    actual = {path.relative_to(root).as_posix() for path in (root / 'formal/ingress_deadlines').rglob('*.lean')}
    if actual != set(SOURCES) | {'formal/ingress_deadlines/Audit.lean'}:
        raise Rejected('Unregistered or missing ingress deadline proof source.')
    return dict(block['inputs'], **{BLOCK: digest(inside(root, BLOCK))})


def inventory(root):
    rows = json.loads((root / 'formal/ingress_deadlines/theorems.json').read_text())['theorems']
    if not isinstance(rows, list) or not rows:
        raise Rejected('Empty or malformed ingress deadline theorem inventory.')
    expected = {}
    for row in rows:
        if (not isinstance(row, dict) or set(row) != {'name', 'module', 'levelParameters', 'typeSha256', 'axioms'}
                or row['axioms'] != [] or row['module'] not in MODULES
                or not isinstance(row['name'], str) or row['name'] in expected
                or not isinstance(row['typeSha256'], str) or re.fullmatch(r'[0-9a-f]{64}', row['typeSha256']) is None
                or not isinstance(row['levelParameters'], list)
                or any(not isinstance(value, str) for value in row['levelParameters'])):
            raise Rejected('Invalid ingress deadline theorem inventory.')
        expected[row['name']] = {key: value for key, value in row.items() if key != 'axioms'}
    if not REQUIRED_THEOREMS <= set(expected):
        raise Rejected('Missing required ingress deadline claim theorem or counterexample.')
    return expected


def reviews(root):
    return check_reviews(root, BLOCK)


def build(root, work, toolchain, expected, manifest):
    work.mkdir()
    objects = work / 'objects'
    (objects / 'IngressDeadlines').mkdir(parents=True)
    (work / 'IngressDeadlines').mkdir()
    (work / 'scratch').mkdir()
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work / 'scratch')
    network = network_witness(work, environment)
    code = ('import sys,json;from pathlib import Path;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
            'import ingress_deadline_bridge as b;print(json.dumps(b.check(Path(sys.argv[1])),sort_keys=True))')
    bridge = json.loads(run([sys.executable, '-I', '-c', code, str(root)], root, environment, work / 'bridge.json'))
    if bridge.get('status') != 'PASS':
        raise Rejected('Ingress deadline production AST bridge failed.')
    if len(bridge.get('consumerAstSha256', {})) != 14 or len(bridge.get('programs', {})) != 6:
        raise Rejected('Incomplete registered ingress correspondence inventory.')
    for module, relative in zip(MODULES, SOURCES):
        source = frozen_bytes(root, relative, manifest)
        check_proof_source(source.decode(), set(MODULES))
        target = work / (module.replace('.', '/') + '.lean')
        target.write_bytes(source)
        target.chmod(0o400)
        run([str(toolchain / 'bin/lean'), *FLAGS, '-o', str(objects / (module.replace('.', '/') + '.olean')),
             module.replace('.', '/') + '.lean'], work, environment, work / (module + '.log'))
    # Audit.lean is separately registered trusted metaprogramming, not a proof module.
    (work / 'Audit.lean').write_bytes(frozen_bytes(root, 'formal/ingress_deadlines/Audit.lean', manifest))
    raw = run([str(toolchain / 'bin/lean'), *FLAGS, 'Audit.lean'], work, environment, work / 'audit.jsonl')
    checked = check_inventory([json.loads(line) for line in raw.decode().splitlines()], expected)
    return {'status': 'PASS', 'theorems': len(checked), 'axioms': [], 'network': network,
            'proofSourceLanguage': 'PASS',
            'bridge': bridge, 'inventorySha256': sha(checked),
            'artifacts': {module: digest(objects / (module.replace('.', '/') + '.olean')) for module in MODULES}}


def negative_controls(root, work, toolchain, build_a):
    work.mkdir()
    objects = work / 'objects'
    shutil.copytree(build_a / 'objects', objects)
    shutil.copytree(build_a / 'IngressDeadlines', work / 'IngressDeadlines')
    for path in (work / 'IngressDeadlines').glob('*.lean'):
        path.chmod(0o600)
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain / 'bin/lean')
    original = (root / SOURCES[1]).read_bytes()
    results = {}
    mutations = (
        ('equality_guard', 'traffic_http.py', 'now >= self.deadline', 'now > self.deadline'),
        ('missing_json_postguard', 'server.py',
         '            self.rfile.check_deadline()\n            return data', '            return data'),
    )
    code = ('import sys;from pathlib import Path;sys.path[:0]=[sys.argv[1],sys.argv[1]+"/scripts"];'
            'import ingress_deadline_bridge as b;r=Path(sys.argv[1]);s=(r/sys.argv[2]).read_text();'
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
    spec = (root / SOURCES[2]).read_text()
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


# Every test process receives a fresh isolated network namespace. Enable only
# its local loopback interface for the real socket witnesses; no host mutation.
TEST_RUNNER = ('import sys,runpy,socket,fcntl,struct;'
    'assert [name for _,name in socket.if_nameindex()]==["lo"];'
    's=socket.socket();q=struct.pack("16sH",b"lo",0);'
    'v=fcntl.ioctl(s.fileno(),0x8913,q);flags=struct.unpack("16sH",v[:18])[1];'
    'fcntl.ioctl(s.fileno(),0x8914,struct.pack("16sH",b"lo",flags|1));s.close();'
    'root,test=sys.argv[1:];sys.path.insert(0,root);sys.argv=[test];'
    'runpy.run_path(test,run_name="__main__")')


def verify(root=ROOT, candidate=False):
    root = Path(root).resolve()
    identifier = datetime.now(timezone.utc).strftime('ting01-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    work = root / 'build/trf-closure' / identifier
    work.mkdir(parents=True)
    report = {'schemaVersion': 1, 'id': identifier, 'blockId': 'TING01', 'status': 'BLOCKED',
              'scope': SCOPE, 'parentObligation': 'TRF-01', 'closesParent': False,
              'umbrellaObligationsClosed': [], 'builds': [], 'blockingReasons': [], 'infrastructureErrors': []}
    try:
        block = json.loads(inside(root, BLOCK).read_text())
        manifest = inputs(root, block)
        if candidate:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        else:
            manifest.update(reviews(root))
        report['inputRootHash'] = sha(manifest)
        report['verifier'] = {'id': 'V-TING01', 'sha256': manifest['scripts/verify_ingress_deadlines.py']}
        write(work / 'manifest.json', manifest)
        frozen = snapshot_inputs(root, work / 'inputs', manifest)
        if not candidate:
            reviews(frozen)
        pin, toolchain = installed_toolchain(frozen)
        if pin != block['leanToolchain']:
            raise Rejected('Ingress deadline toolchain pin mismatch.')
        environment = clean_environment(toolchain)
        environment['TMPDIR'] = str(work)
        version = run([str(toolchain / 'bin/lean'), '--version'], work, environment, work / 'lean-version.log').decode().strip()
        if not re.search(r'\bversion ' + re.escape(pin.split(':v')[1]) + r'(?:[,\s])', version):
            raise Rejected('Installed Lean version differs from frozen pin.')
        tools_before = toolchain_inventory(toolchain)
        write(work / 'toolchain.json', tools_before)
        report['toolchain'] = {'pin': pin, 'version': version, 'rootHash': sha(tools_before)}
        report['executionEnvironment'] = {'platform': platform.platform(), 'python': sys.version,
            'pythonExecutableSha256': digest(Path(sys.executable).resolve()), 'flags': FLAGS,
            'network': 'fresh isolated user+network namespace per subprocess; only local loopback enabled for registered socket regressions'}
        report['trust'], report['excluded'] = block['trust'], block['excluded']
        report['dependency'] = {'id': 'TCFG03', 'blockSha256': DEPENDENCY_BLOCK_SHA,
            'report': DEPENDENCY_REPORT, 'reportSha256': DEPENDENCY_REPORT_SHA,
            'interpretation': 'Historical frozen TRF-00 closure only; current successor application sources are separately frozen and no current whole-profile closure is inferred.'}
        report['provenance'] = {'claimId': CLAIM['id'], 'publicClaims': CLAIM['publicClaims'],
            'originalRegistrySha256': BASELINE_REGISTRY_SHA, 'specification': SPEC,
            'sourceManifest': SOURCE_MANIFEST, 'blockManifestSha256': manifest[BLOCK]}
        expected = inventory(frozen)
        for name in ('build-a', 'build-b'):
            report['builds'].append(build(frozen, work / name, toolchain, expected, manifest))
        if report['builds'][0] != report['builds'][1]:
            raise Rejected('Independent ingress deadline builds disagree.')
        report['determinism'] = 'PASS'
        bridge = report['builds'][0]['bridge']
        report['correspondence'] = {
            'classification': 'CHECKED_RESTRICTED_SUCCESSFUL_PATH_ABSTRACTION_UNDER_DECLARED_TCB',
            'registeredConsumerMethodAsts': len(bridge['consumerAstSha256']),
            'registeredPrimitiveComparisons': 1, 'generatedPrograms': len(bridge['programs']),
            'mandatoryMappings': 15, 'mapped': 15, 'unmapped': 0, 'ambiguous': 0,
            'coverageBoundary': 'Only the registered guard primitive and closed consumer-method inventory; not arbitrary Python or complete ingress semantics.',
            'evidence': ['build-a/bridge.json', 'build-b/bridge.json']}
        report['negativeControls'] = negative_controls(frozen, work / 'negative-controls', toolchain, work / 'build-a')
        report['verifierTestEvidence'] = []
        for test in TESTS:
            raw = run([sys.executable, '-I', '-c', TEST_RUNNER, str(frozen), str(frozen / 'tests' / test)],
                frozen, environment, work / (test + '.log'))
            count = re.search(rb'Ran ([0-9]+) tests? in ', raw)
            if count is None or int(count[1]) <= 0:
                raise Rejected('Registered regression module ran no tests: ' + test)
            report['verifierTestEvidence'].append({'module': test, 'status': 'PASS',
                'tests': int(count[1]), 'evidence': test + '.log', 'sha256': hashlib.sha256(raw).hexdigest()})
        report['verifierTests'] = 'PASS'
        report['verifierTestCount'] = sum(row['tests'] for row in report['verifierTestEvidence'])
        report['negativeControlEvidence'] = [str(path.relative_to(work))
            for path in sorted((work / 'negative-controls').iterdir()) if path.is_file()]
        if toolchain_inventory(toolchain) != tools_before:
            raise Rejected('Toolchain changed during ingress deadline verification.')
        for relative, expected_hash in manifest.items():
            if digest(inside(root, relative)) != expected_hash or digest(inside(frozen, relative)) != expected_hash:
                raise Rejected('Ingress deadline input mutated during verification: ' + relative)
        report['claims'] = [{'id': CLAIM['id'], 'status': 'PASS', 'parentObligation': 'TRF-01', 'closesParent': False,
            'classification': 'PROVED_SAMPLED_DEADLINE_CUTS_UNDER_DECLARED_TCB',
            'inputRootHash': report['inputRootHash'], 'verifier': report['verifier'],
            'theorems': sorted(expected), 'exitCode': 0,
            'evidence': ['build-a/audit.jsonl', 'build-b/audit.jsonl', 'build-a/bridge.json'],
            'executionEnvironment': report['executionEnvironment']}]
        report['provedTheorems'] = len(expected)
        if not report['blockingReasons']:
            report['status'] = 'VERIFIED'
    except (Rejected, ValueError, KeyError, TypeError, StopIteration) as error:
        report['blockingReasons'].append(type(error).__name__ + ': ' + str(error))
    except (OSError, RuntimeError) as error:
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append(type(error).__name__ + ': ' + str(error))
    write(work / 'report.json', report)
    print(json.dumps({'status': report['status'], 'scope': report['scope'], 'report': str(work / 'report.json')}))
    return 0 if report['status'] == 'VERIFIED' else 2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', action='store_true')
    arguments = parser.parse_args()
    raise SystemExit(verify(candidate=arguments.candidate))
