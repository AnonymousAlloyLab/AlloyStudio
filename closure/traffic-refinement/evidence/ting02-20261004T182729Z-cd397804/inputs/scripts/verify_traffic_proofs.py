#!/usr/bin/env python3
"""Check the isolated TB01 traffic *model* block twice without networking.

This verifier does not certify the production server. The immutable claim and
theorem inventories deliberately separate model proofs from OPEN implementation
obligations. Reviews are source-bound workflow records, never proof evidence.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import platform
import re
from pathlib import Path
import secrets
import shutil
import sys

from lean_offline import ROOT, clean_environment, installed_toolchain
from verify_lean import (FLAGS, Rejected, check_inventory, check_proof_source,
                         check_reviews, digest, inside, json_bytes,
                         network_witness, run, toolchain_inventory)

BLOCK = 'formal/traffic/block.json'
MODULES = ('Traffic.Resources', 'Traffic.Reuse', 'Traffic.Ingress')
SOURCE_PATHS = tuple('formal/traffic/' + m.replace('.', '/') + '.lean' for m in MODULES)
REQUIRED_INPUTS = frozenset((*SOURCE_PATHS,
    'formal/traffic/Audit.lean', 'formal/traffic/theorems.json',
    'formal/traffic/claims.json', 'formal/traffic/witnesses.json',
    'formal/traffic/README.md', 'docs/backend-performance-spec.md',
    'docs/traffic-proof-handoff.md', 'closure/traffic-obligations.json',
    'lean-toolchain', 'formal/lean-toolchain', 'scripts/lean_offline.py',
    'scripts/verify_lean.py', 'scripts/bridge_policies.py',
    'scripts/verify_traffic_proofs.py', 'tests/test_traffic_proofs.py'))


def write(path, value):
    path.write_text(json.dumps(value, sort_keys=True, indent=2) + '\n', encoding='utf-8')


def sha(value):
    return hashlib.sha256(json_bytes(value)).hexdigest()


def frozen_bytes(root, relative, manifest):
    data = inside(root, relative).read_bytes()
    if hashlib.sha256(data).hexdigest() != manifest[relative]:
        raise Rejected('Input changed before snapshot/consumption: ' + relative)
    return data


def snapshot_inputs(root, destination, manifest):
    destination.mkdir()
    for relative in sorted(manifest):
        data = frozen_bytes(root, relative, manifest)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        target.chmod(0o400)
    return destination


def inputs(root, block):
    if (block.get('schemaVersion') != 1 or block.get('id') != 'TB01'
            or block.get('scope') != 'traffic-model-only'
            or block.get('flags') != FLAGS or block.get('allowlistedAxioms') != []
            or block.get('requiredCleanBuilds') != 2 or block.get('requiredReviews') != 6
            or block.get('productionRefinementEstablished') is not False
            or block.get('modules') != list(MODULES)):
        raise Rejected('Invalid traffic block policy')
    entries = block.get('inputs')
    if not isinstance(entries, dict) or set(entries) != REQUIRED_INPUTS:
        raise Rejected('Incomplete or unexpected traffic input inventory')
    for relative, expected in entries.items():
        if digest(inside(root, relative)) != expected:
            raise Rejected('Frozen traffic input changed: ' + relative)
    # Unexpected modules must not become an unaudited extension of the block.
    actual = {p.relative_to(root).as_posix()
              for p in (root / 'formal/traffic').rglob('*.lean')}
    if actual != set(SOURCE_PATHS) | {'formal/traffic/Audit.lean'}:
        raise Rejected('Unexpected or missing traffic proof source')
    return dict(entries, **{BLOCK: digest(inside(root, BLOCK))})


def inventory(root):
    document = json.loads(inside(root, 'formal/traffic/theorems.json').read_text())
    rows = document.get('theorems')
    if not isinstance(rows, list) or not rows:
        raise Rejected('Empty traffic theorem inventory')
    expected = {}
    for row in rows:
        if (not isinstance(row, dict)
                or set(row) != {'name', 'module', 'levelParameters', 'typeSha256', 'axioms'}
                or row['axioms'] != [] or row['module'] not in MODULES
                or not isinstance(row['name'], str) or row['name'] in expected):
            raise Rejected('Invalid traffic theorem inventory')
        expected[row['name']] = {k: v for k, v in row.items() if k != 'axioms'}
    return expected


def mappings(root, expected):
    claims = json.loads(inside(root, 'formal/traffic/claims.json').read_text())['claims']
    witnesses = json.loads(inside(root, 'formal/traffic/witnesses.json').read_text())['witnesses']
    if not claims or len({c['id'] for c in claims}) != len(claims):
        raise Rejected('Empty or duplicate model claims')
    used = set()
    for claim in claims:
        if (claim.get('class') != 'model_theorem'
                or claim.get('productionRefinement') != 'OPEN'
                or not claim.get('theorems')
                or not set(claim['theorems']) <= set(expected)
                or claim.get('verifier') != 'V-TRF-MODEL'
                or claim.get('passPredicate') != 'all_registered_theorems_kernel_checked_with_empty_axioms'):
            raise Rejected('Unbound or overstated traffic model claim')
        if used.intersection(claim['theorems']):
            raise Rejected('Ambiguous claim-to-theorem ownership')
        used.update(claim['theorems'])
    if used != set(expected):
        raise Rejected('Unmapped traffic theorem declaration')
    if not witnesses or len({w['id'] for w in witnesses}) != len(witnesses):
        raise Rejected('Empty or duplicate traffic witnesses')
    for witness in witnesses:
        if (witness.get('classification') != 'kernel_checked_model_witness'
                or not witness.get('theorems')
                or not set(witness['theorems']) <= set(expected)
                or not witness.get('scope')):
            raise Rejected('Unbound traffic counterexample witness')
    return claims, witnesses


def traffic_reviews(root):
    # A later tier binds prior JSON hashes. Binding each JSON to its own notes
    # makes that linkage cover the actual coverage text as well as the verdict.
    records = check_reviews(root, BLOCK)
    for relative in records:
        record = json.loads(inside(root, relative).read_text())
        notes = relative.removesuffix('.json') + '.md'
        if record.get('notesSha256') != digest(inside(root, notes)):
            raise Rejected('Traffic review coverage notes changed: ' + notes)
    return records


def build(root, directory, toolchain, expected, manifest):
    directory.mkdir()
    objects = directory / 'objects'
    (objects / 'Traffic').mkdir(parents=True)
    (directory / 'Traffic').mkdir()
    environment = clean_environment(toolchain, objects)
    scratch = directory / 'scratch'
    scratch.mkdir()
    environment['TMPDIR'] = str(scratch)
    network = network_witness(directory, environment)
    lean = str(toolchain / 'bin/lean')
    for module, relative in zip(MODULES, SOURCE_PATHS):
        source = frozen_bytes(root, relative, manifest)
        check_proof_source(source.decode('utf-8'), set(MODULES))
        destination = directory / (module.replace('.', '/') + '.lean')
        destination.write_bytes(source)
        destination.chmod(0o400)
        run([lean, *FLAGS, '-o', str(objects / (module.replace('.', '/') + '.olean')),
             module.replace('.', '/') + '.lean'], directory, environment,
            directory / (module + '.log'))
    (directory / 'Audit.lean').write_bytes(frozen_bytes(root, 'formal/traffic/Audit.lean', manifest))
    (directory / 'Audit.lean').chmod(0o400)
    raw = run([lean, *FLAGS, 'Audit.lean'], directory, environment, directory / 'audit.jsonl')
    try:
        rows = [json.loads(line) for line in raw.decode().splitlines()]
    except (ValueError, UnicodeError) as error:
        raise Rejected('Invalid traffic audit stream') from error
    checked = check_inventory(rows, expected)
    return {'status': 'PASS', 'exitCode': 0, 'theorems': len(checked), 'axioms': [], 'network': network,
            'inventorySha256': sha(checked),
            'artifacts': {m: digest(objects / (m.replace('.', '/') + '.olean')) for m in MODULES}}


def negative_controls(root, work, toolchain):
    """Use the actual traffic auditor against invalid, separately owned fixtures."""
    work.mkdir()
    (work / 'Traffic').mkdir()
    objects = work / 'objects'
    (objects / 'Traffic').mkdir(parents=True)
    environment = clean_environment(toolchain, objects)
    environment['TMPDIR'] = str(work)
    lean = str(toolchain / 'bin/lean')
    (work / 'Placeholder.lean').write_text('import Std\ntheorem unacceptable : False := by sorry\n')
    try:
        run([lean, *FLAGS, 'Placeholder.lean'], work, environment, work / 'placeholder.log')
    except Rejected:
        if 'sorry' not in (work / 'placeholder.log').read_text():
            raise Rejected('Placeholder failed for an unrelated reason')
    else:
        raise Rejected('Placeholder accepted')
    # Empty companion modules let exactly the registered auditor run unchanged.
    for module in MODULES:
        content = 'import Std\n'
        if module == 'Traffic.Resources':
            content += ('namespace Traffic.Bad\naxiom fabricated : False\n'
                        'theorem falseClaim : False := fabricated\nend Traffic.Bad\n')
        path = module.replace('.', '/')
        (work / (path + '.lean')).write_text(content)
        run([lean, *FLAGS, '-o', str(objects / (path + '.olean')), path + '.lean'],
            work, environment, work / (module + '.log'))
    shutil.copyfile(inside(root, 'formal/traffic/Audit.lean'), work / 'Audit.lean')
    raw = run([lean, *FLAGS, 'Audit.lean'], work, environment, work / 'audit.jsonl')
    rows = [json.loads(line) for line in raw.decode().splitlines()]
    if (not any(row.get('kind') == 'forbidden-project-axiom' for row in rows)
            or not any(row.get('kind') == 'theorem' and row.get('axioms') for row in rows)):
        raise Rejected('Traffic audit failed to expose custom axiom/dependent theorem')
    try:
        check_inventory(rows, {})
    except Rejected:
        pass
    else:
        raise Rejected('Traffic gate accepted custom axiom')
    return {'placeholder': 'REJECTED', 'customAxiomAndDependentTheorem': 'REJECTED'}


def verify(root=ROOT, *, candidate=False):
    root = Path(root).resolve()
    identifier = datetime.now(timezone.utc).strftime('traffic-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    destination = root / 'build/traffic-proofs' / identifier
    destination.mkdir(parents=True)
    report = {'schemaVersion': 1, 'id': identifier, 'blockId': 'TB01',
              'scope': 'traffic-model-only', 'status': 'BLOCKED', 'modelStatus': 'BLOCKED',
              'productionRefinementStatus': 'NOT_ESTABLISHED', 'inputRootHash': None,
              'builds': [], 'claims': [], 'blockingReasons': [], 'infrastructureErrors': []}
    try:
        block_bytes = inside(root, BLOCK).read_bytes()
        block = json.loads(block_bytes)
        before = inputs(root, block)
        if before[BLOCK] != hashlib.sha256(block_bytes).hexdigest():
            raise Rejected('Traffic block changed while loading its policy')
        if candidate:
            report['blockingReasons'].append('CANDIDATE_ONLY_REVIEWS_NOT_CHECKED')
        else:
            reviews = traffic_reviews(root)
            before.update(reviews)
            for relative in reviews:
                notes = relative.removesuffix('.json') + '.md'
                before[notes] = digest(inside(root, notes))
        report['inputRootHash'] = sha(before)
        report['verifier'] = {'id': 'V-TRF-MODEL', 'sha256': before['scripts/verify_traffic_proofs.py']}
        write(destination / 'manifest.json', before)
        frozen = snapshot_inputs(root, destination / 'inputs', before)
        # All remaining model/metadata/test reads use the checked private copy,
        # never the mutable worktree. Recheck review objects on those same bytes.
        if not candidate:
            traffic_reviews(frozen)
        pin, toolchain = installed_toolchain(frozen)
        if pin != block['leanToolchain']:
            raise Rejected('Traffic toolchain pin mismatch')
        tool_files = toolchain_inventory(toolchain)
        write(destination / 'toolchain.json', tool_files)
        report['toolchain'] = {'pin': pin, 'rootHash': sha(tool_files)}
        version_environment = clean_environment(toolchain)
        version_environment['TMPDIR'] = str(destination)
        actual_version = run([str(toolchain / 'bin/lean'), '--version'], destination,
            version_environment, destination / 'lean-version.log').decode().strip()
        if not re.search(r'\bversion ' + re.escape(pin.split(':v')[1]) + r'(?:[,\s])', actual_version):
            raise Rejected('Installed Lean version does not match the frozen pin')
        report['executionEnvironment'] = {'platform': platform.platform(),
            'python': sys.version, 'leanToolchain': pin, 'leanVersion': actual_version, 'flags': FLAGS,
            'network': 'unshare user+network namespace for every proof subprocess'}
        report['trust'] = block['trust']
        expected = inventory(frozen)
        claims, witnesses = mappings(frozen, expected)
        for name in ('build-a', 'build-b'):
            report['builds'].append(build(frozen, destination / name, toolchain, expected, before))
        if report['builds'][0] != report['builds'][1]:
            raise Rejected('Traffic clean-build inventories/artifacts differ')
        report['determinism'] = 'PASS'
        report['negativeControls'] = negative_controls(frozen, destination / 'negative-controls', toolchain)
        test_environment = clean_environment(toolchain)
        test_environment['TMPDIR'] = str(destination)
        run([sys.executable, '-I', str(frozen / 'tests/test_traffic_proofs.py')],
            frozen, test_environment, destination / 'verifier-tests.log')
        report['verifierTests'] = {'status': 'PASS', 'evidence': 'verifier-tests.log'}
        if toolchain_inventory(toolchain) != tool_files:
            raise Rejected('Traffic toolchain changed during verification')
        inputs(root, block)
        for relative, expected_hash in before.items():
            if digest(inside(root, relative)) != expected_hash:
                raise Rejected('Traffic input mutated during verification: ' + relative)
            if digest(inside(frozen, relative)) != expected_hash:
                raise Rejected('Frozen traffic input mutated during verification: ' + relative)
        for claim in claims:
            report['claims'].append({'id': claim['id'], 'status': 'PASS',
                'classification': 'PROVED_MODEL', 'inputRootHash': report['inputRootHash'],
                'verifier': report['verifier'], 'theorems': claim['theorems'],
                'exitCode': 0, 'executionEnvironment': report['executionEnvironment'],
                'evidence': ['build-a/audit.jsonl', 'build-b/audit.jsonl'],
                'productionRefinement': 'OPEN'})
        report['witnesses'] = witnesses
        report['modelStatus'] = 'VERIFIED'
        report['provedTheorems'] = len(expected)
        report['axioms'] = []
        report['openObligations'] = [o['id'] for o in json.loads(
            (frozen / 'closure/traffic-obligations.json').read_text())['obligations']
            if o['status'] == 'OPEN']
        if not report['blockingReasons']:
            report['status'] = 'VERIFIED'
    except (Rejected, ValueError, KeyError, TypeError) as error:
        report['blockingReasons'].append(type(error).__name__ + ': ' + str(error))
    except (OSError, RuntimeError) as error:
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append(type(error).__name__ + ': ' + str(error))
    write(destination / 'report.json', report)
    print(json.dumps({'scope': report['scope'], 'status': report['status'],
        'modelStatus': report['modelStatus'], 'productionRefinementStatus': report['productionRefinementStatus'],
        'theorems': report.get('provedTheorems'), 'report': str(destination / 'report.json')}))
    return 0 if report['status'] == 'VERIFIED' else 2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', action='store_true',
                        help='Development: check model builds but omit ladder; overall result stays BLOCKED')
    args = parser.parse_args()
    raise SystemExit(verify(candidate=args.candidate))
