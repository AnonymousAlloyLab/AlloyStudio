#!/usr/bin/env python3
"""Check TCFG01 numeric configuration guards twice, offline, with source binding.

This block discharges a numeric subclaim only. The unchanged umbrella TRF-00
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
from verify_lean import (FLAGS, Rejected, check_inventory, check_proof_source,
                         digest, inside, json_bytes, network_witness,
                         run, toolchain_inventory)
from verify_traffic_proofs import snapshot_inputs, frozen_bytes
from review_ladder import check_reviews

BLOCK = 'formal/traffic_config/block.json'
MODULES = ('TrafficConfig.Scalar', 'TrafficConfig.Extracted', 'TrafficConfig.Spec')
SOURCES = tuple('formal/traffic_config/' + m.replace('.', '/') + '.lean' for m in MODULES)
CLAIM = {
    'id': 'TRF00-NUMERIC',
    'statement': 'The admitted production scalar guard program accepts exactly its independently specified integer and exact-rational intervals and preserves accepted input values.',
    'passPredicate': 'exact_restricted_AST_lowering_and_all_registered_kernel_theorems_empty_axioms_in_two_identical_offline_builds',
    'publicClaims': ['formal/traffic_config/README.md'],
    'parentObligation': 'TRF-00',
    'closesParent': False,
}
TRUST = [
    {'id': 'TCB-LEAN', 'classification': 'TRUSTED', 'components': ['Pinned Lean kernel, compiler and installed distribution; transitive axiom audit is empty.']},
    {'id': 'TCB-TRANSLATION', 'classification': 'TRUSTED', 'components': ['Frozen restricted Python AST translator and Lean audit/verifier scripts.']},
    {'id': 'TCB-PYTHON', 'classification': 'TRUSTED', 'components': ['Exact built-in Python type identity, comparison/integer arithmetic and int/float.as_integer_ratio semantics; no monkey-patched built-ins or hostile trusted code.']},
    {'id': 'TCB-HOST', 'classification': 'TRUSTED', 'components': ['Python standard library, SHA-256, Linux network namespaces, filesystem/process isolation and hardware.']},
]
EXCLUDED = ['Complete TRF-00 profile, source-wide configuration inventory and unique initialization.',
            'Other TRF obligations, constructor allocation/locking/state refinement and arbitrary Python code.',
            'Whole-process memory containment, solver history independence, platform deployment and future source revisions.']
REQUIRED_INPUTS = frozenset((*SOURCES, 'formal/traffic_config/Audit.lean',
    'formal/traffic_config/theorems.json', 'formal/traffic_config/README.md',
    'traffic_limits.py', 'scripts/traffic_config_bridge.py',
    'scripts/verify_traffic_config.py', 'scripts/lean_offline.py', 'scripts/verify_lean.py',
    'scripts/verify_traffic_proofs.py', 'scripts/bridge_policies.py',
    'scripts/review_ladder.py', 'tests/test_review_ladder.py',
    'tests/test_traffic_config_bridge.py', 'tests/test_traffic_config_gate.py',
    'formal/lean-toolchain', 'lean-toolchain'))


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def inputs(root, block):
    required_policy = {'schemaVersion': 1, 'id': 'TCFG01', 'scope': 'normalized-numeric-configuration',
        'flags': FLAGS, 'allowlistedAxioms': [], 'requiredCleanBuilds': 2,
        'requiredReviews': 6, 'umbrellaObligationsClosed': [], 'modules': list(MODULES),
        'claims': [CLAIM], 'trust': TRUST, 'excluded': EXCLUDED}
    if any(block.get(key) != value for key, value in required_policy.items()):
        raise Rejected('Invalid numeric block policy.')
    if not isinstance(block.get('inputs'), dict) or set(block['inputs']) != REQUIRED_INPUTS:
        raise Rejected('Incomplete or unexpected numeric input inventory.')
    for relative, expected in block['inputs'].items():
        if digest(inside(root, relative)) != expected:
            raise Rejected('Frozen numeric input changed: ' + relative)
    actual = {p.relative_to(root).as_posix() for p in (root / 'formal/traffic_config').rglob('*.lean')}
    if actual != set(SOURCES) | {'formal/traffic_config/Audit.lean'}:
        raise Rejected('Unregistered or missing numeric proof source.')
    return dict(block['inputs'], **{BLOCK: digest(inside(root, BLOCK))})


def inventory(root):
    rows = json.loads((root / 'formal/traffic_config/theorems.json').read_text())['theorems']
    expected = {}
    for row in rows:
        if (set(row) != {'name', 'module', 'levelParameters', 'typeSha256', 'axioms'}
                or row['axioms'] != [] or row['module'] not in MODULES or row['name'] in expected):
            raise Rejected('Invalid numeric theorem inventory.')
        expected[row['name']] = {key: value for key, value in row.items() if key != 'axioms'}
    if not expected:
        raise Rejected('Empty numeric theorem inventory.')
    return expected


def reviews(root):
    return check_reviews(root, BLOCK)


def build(root, work, toolchain, expected, manifest):
    work.mkdir()
    objects = work / 'objects'
    (objects / 'TrafficConfig').mkdir(parents=True)
    (work / 'TrafficConfig').mkdir()
    environment = clean_environment(toolchain, objects)
    (work / 'scratch').mkdir()
    environment['TMPDIR'] = str(work / 'scratch')
    network = network_witness(work, environment)
    bridge_code = ('import sys,json; from pathlib import Path; '
        'sys.path.insert(0,sys.argv[1]); import traffic_config_bridge as b; '
        'print(json.dumps(b.check(Path(sys.argv[1]).parent),sort_keys=True))')
    result = run([sys.executable, '-I', '-c', bridge_code, str(root / 'scripts')],
                 root, environment, work / 'bridge.json')
    bridge = json.loads(result)
    if bridge.get('status') != 'PASS':
        raise Rejected('Numeric production AST translation did not pass.')
    for module, relative in zip(MODULES, SOURCES):
        source = frozen_bytes(root, relative, manifest)
        check_proof_source(source.decode(), set(MODULES))
        target = work / (module.replace('.', '/') + '.lean')
        target.write_bytes(source)
        target.chmod(0o400)
        run([str(toolchain / 'bin/lean'), *FLAGS, '-o',
             str(objects / (module.replace('.', '/') + '.olean')),
             module.replace('.', '/') + '.lean'], work, environment, work / (module + '.log'))
    (work / 'Audit.lean').write_bytes(frozen_bytes(root, 'formal/traffic_config/Audit.lean', manifest))
    raw = run([str(toolchain / 'bin/lean'), *FLAGS, 'Audit.lean'], work, environment, work / 'audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    checked = check_inventory(rows, expected)
    return {'status': 'PASS', 'theorems': len(checked), 'axioms': [], 'network': network,
            'bridge': bridge, 'inventorySha256': sha(checked),
            'artifacts': {m: digest(objects / (m.replace('.', '/') + '.olean')) for m in MODULES}}


def negative_controls(root, work, toolchain):
    work.mkdir()
    objects = work / 'objects'
    (objects / 'TrafficConfig').mkdir(parents=True)
    (work / 'TrafficConfig').mkdir()
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain / 'bin/lean')
    # A legal arithmetic change is lowered rather than hidden by a golden formula.
    code = ('import sys; from pathlib import Path; sys.path.insert(0,sys.argv[1]); '
        'import traffic_config_bridge as b; p=Path(sys.argv[1]).parent; '
        's=(p/"traffic_limits.py").read_text(); assert "numerator == 0" in s; '
        'print(b.generate(s.replace("numerator == 0", "numerator < 0")),end="")')
    mutated = run([sys.executable, '-I', '-c', code, str(root / 'scripts')], root,
                  environment, work / 'mutated-program.lean')
    for module, relative in zip(MODULES, SOURCES):
        target = work / (module.replace('.', '/') + '.lean')
        target.write_bytes(mutated if module == 'TrafficConfig.Extracted' else (root / relative).read_bytes())
        command = [lean, *FLAGS, '-o', str(objects / (module.replace('.', '/') + '.olean')),
                   module.replace('.', '/') + '.lean']
        try:
            run(command, work, environment, work / (module + '.log'))
        except Rejected:
            if module != 'TrafficConfig.Spec':
                raise
            break
        else:
            if module == 'TrafficConfig.Spec':
                raise Rejected('Independent specification accepted the zero-admission defect.')
    # Same real auditor must expose introduced axioms and their dependent claims.
    (work / 'TrafficConfig/Spec.lean').write_text(
        'import TrafficConfig.Extracted\nnamespace AlloyStudio.TrafficConfig\n'
        'axiom fabricated : False\ntheorem falseClaim : False := fabricated\n'
        'end AlloyStudio.TrafficConfig\n')
    run([lean, *FLAGS, '-o', str(objects / 'TrafficConfig/Spec.olean'), 'TrafficConfig/Spec.lean'],
        work, environment, work / 'axiom-build.log')
    shutil.copyfile(root / 'formal/traffic_config/Audit.lean', work / 'Audit.lean')
    rows = [json.loads(line) for line in run([lean, *FLAGS, 'Audit.lean'], work, environment,
            work / 'axiom-audit.jsonl').decode().splitlines()]
    if (not any(r.get('kind') == 'forbidden-project-axiom' for r in rows)
            or not any(r.get('kind') == 'theorem' and r.get('axioms') for r in rows)):
        raise Rejected('Auditor did not expose introduced axiom and dependent theorem.')
    (work / 'Placeholder.lean').write_text('import Std\ntheorem bad : False := by sorry\n')
    try:
        run([lean, *FLAGS, 'Placeholder.lean'], work, environment, work / 'placeholder.log')
    except Rejected:
        if 'sorry' not in (work / 'placeholder.log').read_text():
            raise Rejected('Placeholder rejection had an unrelated cause.')
    else:
        raise Rejected('Proof placeholder was accepted.')
    return {'zeroAdmissionMutation': 'REJECTED_BY_INDEPENDENT_SPEC',
            'introducedAxiom': 'REJECTED_BY_AUDITOR', 'placeholder': 'REJECTED_BY_COMPILER'}


def verify(root=ROOT, candidate=False):
    root = Path(root).resolve()
    identifier = datetime.now(timezone.utc).strftime('tcfg01-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    work = root / 'build/trf-closure' / identifier
    work.mkdir(parents=True)
    report = {'schemaVersion': 1, 'id': identifier, 'blockId': 'TCFG01', 'status': 'BLOCKED',
              'scope': 'normalized-numeric-configuration', 'umbrellaObligationsClosed': [],
              'builds': [], 'blockingReasons': [], 'infrastructureErrors': []}
    try:
        block = json.loads(inside(root, BLOCK).read_text())
        manifest = inputs(root, block)
        if candidate:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        else:
            manifest.update(reviews(root))
        report['inputRootHash'] = sha(manifest)
        report['verifier'] = {'id': 'V-TCFG01', 'sha256': manifest['scripts/verify_traffic_config.py']}
        write(work / 'manifest.json', manifest)
        frozen = snapshot_inputs(root, work / 'inputs', manifest)
        if not candidate:
            reviews(frozen)
        pin, toolchain = installed_toolchain(frozen)
        if pin != block['leanToolchain']:
            raise Rejected('Numeric proof toolchain pin mismatch.')
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
            raise Rejected('Independent numeric proof builds disagree.')
        report['determinism'] = 'PASS'
        report['negativeControls'] = negative_controls(frozen, work / 'negative-controls', toolchain)
        environment = clean_environment(toolchain)
        environment['TMPDIR'] = str(work)
        for test in ('test_traffic_config_bridge.py', 'test_traffic_config_gate.py', 'test_review_ladder.py'):
            run([sys.executable, '-I', str(frozen / 'tests' / test)], frozen, environment, work / (test + '.log'))
        report['verifierTests'] = 'PASS'
        if toolchain_inventory(toolchain) != tools_before:
            raise Rejected('Toolchain changed during numeric proof verification.')
        for relative, expected_hash in manifest.items():
            if digest(inside(root, relative)) != expected_hash or digest(inside(frozen, relative)) != expected_hash:
                raise Rejected('Numeric input mutated during verification: ' + relative)
        report['claims'] = [{'id': 'TRF00-NUMERIC', 'status': 'PASS',
            'classification': 'PROVED_RESTRICTED_SCALAR_PROGRAM', 'inputRootHash': report['inputRootHash'],
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
