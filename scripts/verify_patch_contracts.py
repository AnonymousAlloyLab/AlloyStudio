#!/usr/bin/env python3
"""Freeze and verify the finite proposed patch contracts, never production closure.

Preparation registers theorem types and inputs for subsequent independent review.
Verification never rewrites that registration. Both Lean builds run offline in
fresh directories; evidence remains in this repository, including failed runs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import shutil
import sys
import time
import uuid

from lean_offline import ROOT, clean_environment, installed_toolchain
from review_ladder import check_reviews
from verify_lean import (Rejected, check_proof_source, digest, inside, json_bytes,
                         network_witness, proof_code, run, toolchain_inventory)

FLAGS = ['--trust=0', '-DwarningAsError=true', '-DgenInjectivity=false',
         '-Dbackward.match.sparseCases=false', '-j1']
MODULES = ('Work', 'Identity', 'Governance', 'Observability')
SPEC = 'closure/patch-contracts/spec.json'
BLOCK = 'formal/patch_contracts/block.json'
INVENTORY = 'formal/patch_contracts/theorems.json'
AUDIT = 'formal/patch_contracts/Audit.lean'
WITNESS = 'scripts/claude_feedback_witnesses.py'
REGISTERED = {
    SPEC, AUDIT, 'lean-toolchain', 'formal/lean-toolchain',
    'scripts/verify_patch_contracts.py', 'scripts/verify_lean.py',
    'scripts/lean_offline.py', 'scripts/review_ladder.py',
    'scripts/bridge_policies.py', 'tests/test_patch_contract_verifier.py',
    'tests/test_review_ladder.py', WITNESS,
    *(f'formal/patch_contracts/{module}.lean' for module in MODULES),
}
TRUST = {'TCB-LEAN', 'TCB-VERIFIERS', 'TCB-SHA256', 'TCB-PYTHON',
         'TCB-OS', 'TCB-HARDWARE', 'TCB-REVIEW-PROVENANCE'}


def read_json(path):
    def unique(pairs):
        obj = {}
        for key, value in pairs:
            if key in obj:
                raise Rejected('Duplicate JSON key: ' + key)
            obj[key] = value
        return obj
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique)


def safe_input(root, relative):
    if not isinstance(relative, str) or Path(relative).as_posix() != relative:
        raise Rejected('Input paths must be canonical relative POSIX paths')
    path = inside(root, relative)
    if any(part.name.startswith('.env') or part.name in
           {'admin.local.json', 'openai.local.json'} for part in Path(relative).parents) or \
            Path(relative).name.startswith('.env') or Path(relative).name in \
            {'admin.local.json', 'openai.local.json'}:
        raise Rejected('Private configuration is outside the proof surface')
    for part in (path, *path.parents):
        if part == root:
            break
        if part.is_symlink():
            raise Rejected('Linked input paths are not accepted')
    return path


def load_spec(root):
    spec = read_json(safe_input(root, SPEC))
    if (spec.get('schemaVersion') != 1 or spec.get('id') != 'AP01'
            or spec.get('scope') != 'proposed-contracts'
            or spec.get('closesProductionObligations') is not False):
        raise Rejected('SCOPE_LEAK: only proposed AP01 contracts may be certified')
    claims, bridges, trusted = spec.get('claims'), spec.get('productionBridges'), spec.get('trusted')
    if not all(isinstance(x, list) and x for x in (claims, bridges, trusted)):
        raise Rejected('Claims, explicit open production bridges and TCB are required')
    ids = set()
    for claim in claims:
        if (not isinstance(claim, dict) or not isinstance(claim.get('id'), str)
                or not claim['id'] or claim['id'] in ids
                or not isinstance(claim.get('statement'), str) or not claim['statement']
                or not isinstance(claim.get('publicClaim'), str) or not claim['publicClaim']
                or not isinstance(claim.get('requiredTheorems'), list)
                or not claim['requiredTheorems']
                or len(set(claim['requiredTheorems'])) != len(claim['requiredTheorems'])
                or any(not isinstance(n, str) or not n.startswith('AlloyStudio.PatchContracts.')
                       for n in claim['requiredTheorems'])):
            raise Rejected('Malformed, duplicated or unbound finite claim')
        ids.add(claim['id'])
        target = claim['publicClaim'].split('#', 1)[0]
        if target not in spec.get('inputs', []):
            raise Rejected('ORPHAN_CLAIM: public document is not a registered input')
        if claim['id'] not in safe_input(root, target).read_text(encoding='utf-8'):
            raise Rejected('ORPHAN_CLAIM: public document does not identify the mapped claim')
    if any(not isinstance(b, dict) or b.get('status') != 'OPEN' or not b.get('id')
           or not b.get('statement') for b in bridges):
        raise Rejected('SCOPE_LEAK: production implementation bridges must remain OPEN')
    if len({b['id'] for b in bridges}) != len(bridges):
        raise Rejected('Duplicate production bridge')
    if ({t.get('id') for t in trusted if isinstance(t, dict)} != TRUST
            or len(trusted) != len(TRUST)
            or any(not t.get('description') for t in trusted)):
        raise Rejected('UNDECLARED_DEPENDENCY: the exact finite TCB is required')
    extra = spec.get('inputs')
    if not isinstance(extra, list) or any(not isinstance(p, str) for p in extra) or len(set(extra)) != len(extra):
        raise Rejected('Invalid input registration')
    if BLOCK in extra or INVENTORY in extra:
        raise Rejected('Generated registration files cannot be spec inputs')
    return spec


def required_inputs(spec):
    return REGISTERED | set(spec['inputs']) | {INVENTORY}


def check_input_manifest(root, spec, inputs):
    if not isinstance(inputs, dict) or set(inputs) != required_inputs(spec):
        raise Rejected('INPUT_MUTATION: frozen input inventory is incomplete or expanded')
    for relative, frozen in inputs.items():
        if not isinstance(frozen, str) or not re.fullmatch('[0-9a-f]{64}', frozen):
            raise Rejected('Invalid frozen input hash')
        if digest(safe_input(root, relative)) != frozen:
            raise Rejected('INPUT_MUTATION: ' + relative)


def audit_inventory(rows):
    result = {}
    for row in rows:
        if (not isinstance(row, dict) or set(row) !=
                {'kind', 'name', 'module', 'type', 'levelParameters', 'axioms'}
                or row.get('kind') not in {'theorem', 'definition', 'structural'}
                or row.get('axioms') != []
                or row.get('module') not in MODULES
                or not all(isinstance(row.get(k), str) for k in ('name', 'type'))
                or not isinstance(row.get('levelParameters'), list)
                or any(not isinstance(level, str) for level in row['levelParameters'])
                or row['name'] in result):
            raise Rejected('Unclosed, duplicated or axiom-dependent audit declaration')
        result[row['name']] = {k: row[k] for k in ('kind', 'name', 'module', 'levelParameters')}
        result[row['name']]['typeSha256'] = hashlib.sha256(row['type'].encode()).hexdigest()
    if not result or not any(v['kind'] == 'theorem' for v in result.values()):
        raise Rejected('Empty theorem inventory')
    return result


def check_claim_theorems(spec, inventory):
    for claim in spec['claims']:
        for name in claim['requiredTheorems']:
            if name not in inventory or inventory[name]['kind'] != 'theorem':
                raise Rejected('UNMAPPED_IMPLEMENTATION_OBJECT: missing required theorem ' + name)


def clean_build(root, output, toolchain, expected=None):
    sources, objects = output / 'sources', output / 'objects'
    sources.mkdir(parents=True)
    objects.mkdir()
    deps = {}
    for module in MODULES:
        source = safe_input(root, f'formal/patch_contracts/{module}.lean')
        deps[module] = check_proof_source(source.read_text(), set(MODULES))
        shutil.copyfile(source, sources / f'{module}.lean')
    shutil.copyfile(safe_input(root, AUDIT), sources / 'Audit.lean')
    remaining, order = set(MODULES), []
    while remaining:
        ready = sorted(m for m in remaining if not remaining.intersection(deps[m]))
        if not ready:
            raise Rejected('Proof module import cycle')
        order.extend(ready)
        remaining.difference_update(ready)
    env = clean_environment(toolchain, objects)
    env['TMPDIR'] = str(output.resolve())
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    network = network_witness(output, env)
    lean = str(toolchain / 'bin/lean')
    for module in order:
        run([lean, *FLAGS, '-o', str((objects / f'{module}.olean').resolve()), f'{module}.lean'],
            sources, env, output / 'logs' / f'{module}.log')
    data = run([lean, *FLAGS, 'Audit.lean'], sources, env, output / 'inventory.jsonl')
    inventory = audit_inventory([json.loads(line) for line in data.decode().splitlines()])
    if expected is not None and inventory != expected:
        raise Rejected('CLAIM_MUTATION: full declaration/type inventory changed')
    result = {'inventorySha256': hashlib.sha256(json_bytes(inventory)).hexdigest(),
              'declarations': len(inventory),
              'theorems': sum(v['kind'] == 'theorem' for v in inventory.values()),
              'artifacts': {p.name: digest(p) for p in sorted(objects.glob('*.olean'))},
              'network': network}
    return inventory, result


def new_output(root, phase):
    directory = root / 'closure/patch-contracts/evidence' / (
        phase + '-' + time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex[:8])
    directory.mkdir(parents=True)
    return directory


def proof_negative_controls(root, output, toolchain):
    """Construct actual invalid proof programs; require the registered gate to reject them."""
    output.mkdir()
    env = clean_environment(toolchain, output)
    env['TMPDIR'] = str(output.resolve())
    lean = str(toolchain / 'bin/lean')
    cases = {
        'placeholder': ('import Std\ntheorem invalid : False := by sorry\n', 'sorry'),
        'falseWithoutPlaceholder': ('import Std\ntheorem invalid : False := by exact True.intro\n', 'mismatch'),
    }
    outcomes = {}
    for name, (source, diagnostic) in cases.items():
        (output / f'{name}.lean').write_text(source)
        log = output / f'{name}.log'
        try:
            run([lean, *FLAGS, f'{name}.lean'], output, env, log)
        except Rejected:
            if diagnostic not in log.read_text().lower():
                raise Rejected('Negative proof rejected for an unrelated reason: ' + name)
            outcomes[name] = 'REJECTED'
        else:
            raise Rejected('Invalid proof accepted: ' + name)
    for module in MODULES:
        code = 'import Std\n'
        if module == 'Work':
            code += 'axiom constructedCounterfeit : False\ntheorem invalid : False := constructedCounterfeit\n'
        (output / f'{module}.lean').write_text(code)
        run([lean, *FLAGS, '-o', str(output / f'{module}.olean'), f'{module}.lean'],
            output, env, output / f'{module}.log')
    shutil.copyfile(root / AUDIT, output / 'Audit.lean')
    try:
        run([lean, *FLAGS, 'Audit.lean'], output, env, output / 'axiom.log')
    except Rejected:
        if 'axiom dependencies' not in (output / 'axiom.log').read_text():
            raise Rejected('Audit axiom negative control failed for an unrelated reason')
        outcomes['customAxiomAndDependentTheorem'] = 'REJECTED'
    else:
        raise Rejected('Registered declaration audit accepted a constructed axiom')
    return outcomes


def prepare(root, output):
    spec = load_spec(root)
    pin, toolchain = installed_toolchain(root)
    inventory, build = clean_build(root, output / 'candidate-build', toolchain)
    check_claim_theorems(spec, inventory)
    (root / INVENTORY).write_text(json.dumps(inventory, indent=2, sort_keys=True) + '\n')
    inputs = {p: digest(safe_input(root, p)) for p in sorted(required_inputs(spec))}
    block = {'schemaVersion': 1, 'id': spec['id'],
             'closureId': 'patch-contracts-' + output.name,
             'scope': 'proposed-contracts', 'closesProductionObligations': False,
             'leanToolchain': pin, 'flags': FLAGS, 'allowlistedAxioms': [],
             'requiredCleanBuilds': 2, 'inputs': inputs,
             'requiredTheorems': {c['id']: c['requiredTheorems'] for c in spec['claims']},
             'inputRootSha256': hashlib.sha256(json_bytes(inputs)).hexdigest(),
             'toolchainRootSha256': hashlib.sha256(json_bytes(toolchain_inventory(toolchain))).hexdigest(),
             'python': {'sha256': digest(Path(sys.executable).resolve()), 'version': sys.version},
             'interpretation': 'Candidate preparation only. No production closure. Six fresh reviews required.'}
    (root / BLOCK).write_text(json.dumps(block, indent=2, sort_keys=True) + '\n')
    shutil.copyfile(root / BLOCK, output / 'prepared-block.json')
    return {'phase': 'PREPARED', 'closureId': block['closureId'], 'blockSha256': digest(root / BLOCK),
            'build': build, 'productionBridges': spec['productionBridges']}


def load_block(root, spec, pin):
    block = read_json(safe_input(root, BLOCK))
    if (block.get('schemaVersion') != 1 or block.get('id') != spec['id']
            or block.get('scope') != 'proposed-contracts'
            or block.get('closesProductionObligations') is not False
            or block.get('leanToolchain') != pin or block.get('flags') != FLAGS
            or block.get('allowlistedAxioms') != [] or block.get('requiredCleanBuilds') != 2
            or not isinstance(block.get('closureId'), str) or not block['closureId']):
        raise Rejected('CLAIM_MUTATION: frozen block policy mismatch')
    check_input_manifest(root, spec, block.get('inputs'))
    if block.get('inputRootSha256') != hashlib.sha256(json_bytes(block['inputs'])).hexdigest():
        raise Rejected('INPUT_MUTATION: root hash mismatch')
    if block.get('requiredTheorems') != {c['id']: c['requiredTheorems'] for c in spec['claims']}:
        raise Rejected('CLAIM_MUTATION: required theorem mappings changed')
    if block.get('python') != {'sha256': digest(Path(sys.executable).resolve()), 'version': sys.version}:
        raise Rejected('INPUT_MUTATION: registered Python runtime changed')
    return block


def verify(root, output):
    spec = load_spec(root)
    pin, toolchain = installed_toolchain(root)
    block = load_block(root, spec, pin)
    tools = toolchain_inventory(toolchain)
    tool_root = hashlib.sha256(json_bytes(tools)).hexdigest()
    if block.get('toolchainRootSha256') != tool_root:
        raise Rejected('INPUT_MUTATION: installed Lean toolchain changed')
    reviews = check_reviews(root, BLOCK)
    inputs = {**block['inputs'], BLOCK: digest(root / BLOCK), **reviews}
    snapshot = output / 'snapshot'
    for name in sorted(inputs):
        source = safe_input(root, name)
        target = snapshot / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        if digest(target) != inputs[name]:
            raise Rejected('INPUT_MUTATION: input changed during snapshot')
    (output / 'toolchain-files.json').write_text(json.dumps(tools, sort_keys=True, indent=2) + '\n')
    expected = read_json(snapshot / INVENTORY)
    check_claim_theorems(spec, expected)
    env = clean_environment(toolchain)
    env['TMPDIR'] = str(output.resolve())
    env['PYTHONDONTWRITEBYTECODE'] = '1'
    for suite in ('test_patch_contract_verifier.py', 'test_review_ladder.py'):
        run([sys.executable, '-I', '-m', 'unittest', 'discover', '-s', str(snapshot / 'tests'),
             '-p', suite], snapshot, env, output / (suite + '.log'))
    builds = [clean_build(snapshot, output / f'build-{n}', toolchain, expected)[1] for n in (1, 2)]
    if any(builds[0][k] != builds[1][k] for k in ('inventorySha256', 'artifacts', 'declarations', 'theorems')):
        raise Rejected('NONDETERMINISM: independent proof builds differ')
    witnesses = []
    for n in (1, 2):
        target = output / f'witness-{n}'
        run([sys.executable, '-I', str(snapshot / WITNESS), '--output-root', str(target)],
            snapshot, env, output / f'witness-{n}.log')
        result = read_json(target / 'results.json')
        if result.get('productionProcessesLaunched') != 0 or result.get('networkSocketsOpened') != 0:
            raise Rejected('WITNESS_INVALID: bounded witness escaped its process/socket scope')
        witnesses.append({'resultSha256': digest(target / 'results.json'), 'result': result})
    if witnesses[0] != witnesses[1]:
        raise Rejected('NONDETERMINISM: bounded witness replays differ')
    negative_controls = proof_negative_controls(snapshot, output / 'negative-controls', toolchain)
    check_input_manifest(root, spec, block['inputs'])
    for relative, frozen in inputs.items():
        if digest(safe_input(snapshot, relative)) != frozen:
            raise Rejected('INPUT_MUTATION: snapshot changed during execution: ' + relative)
    if check_reviews(root, BLOCK) != reviews or digest(root / BLOCK) != inputs[BLOCK]:
        raise Rejected('INPUT_MUTATION: review or block changed during verification')
    if toolchain_inventory(toolchain) != tools:
        raise Rejected('INPUT_MUTATION: Lean toolchain changed during verification')
    if digest(Path(sys.executable).resolve()) != block['python']['sha256']:
        raise Rejected('INPUT_MUTATION: Python runtime changed during verification')
    claims = []
    verifier_hash = inputs['scripts/verify_patch_contracts.py']
    execution_environment = {'platform': platform.platform(), 'python': block['python'],
        'leanToolchain': block['leanToolchain'], 'toolchainRootSha256': tool_root,
        'networkDuringProofAndWitnessProcesses': 'isolated Linux namespace; loopback only',
        'flags': FLAGS}
    for claim in spec['claims']:
        claims.append({'claimId': claim['id'], 'closureId': block['closureId'],
            'status': 'PASS', 'verifierId': 'V-PATCH-CONTRACTS',
            'verifierSha256': verifier_hash, 'inputRootSha256': block['inputRootSha256'],
            'requiredTheorems': claim['requiredTheorems'], 'publicClaim': claim['publicClaim'],
            'executionEnvironment': execution_environment,
            'rawResult': {'declarationsHaveNoAxiomDependencies': True, 'cleanBuilds': 2}, 'exitCode': 0})
    for build in builds:
        build['inputRootSha256'] = block['inputRootSha256']
    return {'schemaVersion': 1, 'closureId': block['closureId'], 'status': 'VERIFIED',
        'scope': 'proposed-contracts', 'closesProductionObligations': False,
        'inputRootSha256': block['inputRootSha256'], 'blockSha256': inputs[BLOCK],
        'evidenceInputRootSha256': hashlib.sha256(json_bytes(inputs)).hexdigest(), 'inputs': inputs,
        'claims': claims, 'builds': builds, 'determinism': 'PASS',
        'executionEnvironment': execution_environment,
        'claimCounts': {'total': len(claims), 'passed': len(claims), 'blocked': 0,
            'mappedDistinctTheorems': len({n for c in spec['claims'] for n in c['requiredTheorems']}),
            'sourceWrittenTheoremDeclarations': sum(len(re.findall(
                r'(?m)^\s*(?:private\s+|protected\s+)?theorem\s+',
                proof_code((snapshot / f'formal/patch_contracts/{module}.lean').read_text())))
                for module in MODULES),
            'note': 'The full audit also counts compiler-generated theorems; theorem count is not semantic coverage.'},
        'axiomPolicy': {'allowed': [], 'allProjectDeclarationsAudited': True},
        'boundedWitnesses': witnesses, 'negativeControls': negative_controls,
        'reviews': {'role': 'advisory only', 'records': reviews},
        'productionBridges': spec['productionBridges'],
        'correspondence': {'requiredObjects': 0, 'scope': 'No production semantic correspondence claimed'},
        'provenance': {'publicClaims': len(claims), 'fullyBound': len(claims), 'orphanClaims': 0},
        'dependencies': {'trusted': spec['trusted'], 'undeclared': [],
                         'toolchainRootSha256': tool_root, 'python': block['python']},
        'blockingReasons': [], 'infrastructureErrors': [],
        'closureBoundary': {'proved': 'Frozen constructive Lean models and mapped theorem types',
            'tested': 'Bounded source/mocked-transition witnesses; not real JVM workload reachability',
            'checked': 'Input hashes, complete declaration inventory, empty axioms and deterministic builds',
            'excluded': ['Production patches and refinement', 'Wall-clock liveness', 'Learner outcomes'],
            'interpretation': 'VERIFIED applies only to this finite proposed-contract surface under the explicit TCB.'}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--prepare', action='store_true', help='Freeze a candidate for review; not closure')
    mode.add_argument('--verify', action='store_true', help='Verify the frozen candidate (default)')
    args = parser.parse_args()
    output = new_output(ROOT, 'prepare' if args.prepare else 'verify')
    try:
        report = prepare(ROOT, output) if args.prepare else verify(ROOT, output)
    except (Rejected, ValueError, KeyError, TypeError) as error:
        report = {'status': 'BLOCKED', 'scope': 'proposed-contracts',
                  'closesProductionObligations': False, 'blockingReasons': [str(error)]}
    except (OSError, RuntimeError) as error:
        report = {'status': 'INFRASTRUCTURE_FAILURE', 'scope': 'proposed-contracts',
                  'closesProductionObligations': False, 'infrastructureErrors': [str(error)]}
    (output / 'report.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
    print(json.dumps({'status': report.get('status', report.get('phase')), 'report': str(output / 'report.json')}))
    return 0 if report.get('status') == 'VERIFIED' or report.get('phase') == 'PREPARED' else \
        2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    raise SystemExit(main())
