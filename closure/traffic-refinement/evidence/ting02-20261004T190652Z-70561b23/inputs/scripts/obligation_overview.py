#!/usr/bin/env python3
"""Render the OPEN obligation ledger with current, narrowly scoped evidence.

This is a report consumer, not a proof checker. It trusts the registered formal
verifier's report only after checking every reported input against the current
registered source files. Missing, stale or unregistered inputs cannot turn green.
It never executes Lean, downloads tools, reads credentials or closes an obligation.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
LEDGER = 'closure/lean-obligations.json'
REGISTRY = 'formal/bridges/registry.json'
EXPECTED_IDS = {f'L{number:02d}' for number in range(24)}
BRIDGE_SCHEMA = {'BR-FEEDBACK': ('feedbackSuccess', 10),
                 'BR-GUIDANCE': ('guidanceSuccess', 13),
                 'BR-POOL-CHOOSE': ('poolChoose', 2),
                 'BR-POOL-FINISH': ('poolFinish', 2)}
BASE_INPUTS = {
    LEDGER, 'lean-toolchain', 'formal/lean-toolchain', 'formal/Audit.lean',
    'formal/lakefile.toml', 'formal/AlloyStudio.lean', 'scripts/lean_offline.py',
    'scripts/verify_lean.py', 'tests/test_lean_verifier.py',
}
BRIDGE_INPUTS = {
    REGISTRY, 'formal/ExportBridges.lean', 'formal/bridges/policies.json',
    'scripts/bridge_policies.py', 'scripts/obligation_overview.py',
    'tests/test_obligation_overview.py', 'docs/implementation-bridges.md',
}

# These are descriptions of existing supporting definitions, never ledger
# discharge records. The full implementation requirements stay in the ledger.
SUPPORT = {
    'L00': ('Closure-decision model; pinned offline proof builds and axiom inventory.',
            'Freeze the complete implementation claim set and trust boundary, and discharge every required correspondence.'),
    'L01': ('Ordered forest counts and postorder occurrence laws in RawAst.',
            'Prove the Java parser/body adapter preserves all admitted constructors, labels, children and occurrence identities.'),
    'L04': ('Unit promotion/adoption/relabel edits, contextual scripts, inverse and cost composition.',
            'Connect the edit model to actual Java edits, including root/sentinel handling and any required insertion freshness.'),
    'L11': ('Complete finite-pool first-minimum model; policy scan refinement and positional certificate soundness.',
            'Prove the runtime pool iterator, evaluation completeness, numeric conversion, input decoding and cost correctness; bind oracle and pool membership.'),
    'L18': ('Guidance identity acceptance is linked to the finite Boolean policy kernel.',
            'Prove complete evidence/operation IDs, prompt and response shape, bounded learner data, decoding and redaction; prose remains untrusted.'),
    'L20': ('Strict feedback/guidance identity models, legacy counterexamples and Boolean guard equivalence.',
            'Prove host-value comparisons, response decoding, asynchronous capture/render timing, serialized caches and the remaining browser transitions.'),
    'L22': ('Registered finite Boolean policy correspondence for browser guards and Java pool decisions.',
            'Supply semantic bridges for Java AST extraction, DP, trace replay, arbitrary runtime iteration and certificate/wire decoding.'),
    'L23': ('Closure-decision model and fail-closed frozen-block verifier.',
            'Prove the full implementation gate collects complete evidence and closes all required end-to-end obligations.'),
}


class InvalidEvidence(ValueError):
    """The overview cannot safely use the supplied evidence."""


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def safe_input(root, relative):
    """Accept only ordinary files inside the repository, without symlink hops."""
    if not isinstance(relative, str):
        raise InvalidEvidence('An input path is not a string')
    path = Path(relative)
    if path.is_absolute() or '..' in path.parts or path.as_posix() != relative:
        raise InvalidEvidence(f'Invalid registered input path: {relative}')
    target = root
    for component in path.parts:
        target = target / component
        if target.is_symlink():
            raise InvalidEvidence(f'Linked input is not allowed: {relative}')
    if not target.resolve().is_relative_to(root.resolve()) or not target.is_file():
        raise InvalidEvidence(f'Missing or out-of-root input: {relative}')
    return target


def digest(path):
    checksum = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            checksum.update(chunk)
    return checksum.hexdigest()


def read_json(root, relative):
    return json.loads(safe_input(root, relative).read_text(encoding='utf-8'))


def registration(root, ledger):
    registered = set(BASE_INPUTS)
    blocks = {}
    registered_blocks = ledger.get('supportingProofBlocks', {})
    active = ledger.get('activeProofBlocks', list(registered_blocks))
    if len(active) != len(set(active)) or not set(active).issubset(registered_blocks):
        raise InvalidEvidence('Invalid active proof block registration')
    for identity in active:
        relative = registered_blocks[identity]
        if not re.fullmatch(r'B\d\d', identity) or relative != f'formal/blocks/{identity}.json':
            raise InvalidEvidence('Invalid supporting proof block registration')
        block = read_json(root, relative)
        if block.get('id') != identity or block.get('doesNotCloseOriginalObligations') is not True:
            raise InvalidEvidence(f'Invalid supporting block: {identity}')
        blocks[identity] = block
        registered.add(relative)
        registered.update(block.get('sources', {}))
        registered.update(block.get('implementationInputs', {}))
        for tier in ('luna', 'sol', 'astra'):
            for suffix in ('a', 'b'):
                registered.update(f'formal/reviews/{identity}/{tier}-{suffix}.{ext}' for ext in ('json', 'md'))
    registry = None
    if (root / REGISTRY).exists():
        registry = read_json(root, REGISTRY)
        if registry.get('schemaVersion') != 1 or registry.get('kind') != 'finite-policy-correspondence':
            raise InvalidEvidence('Invalid bridge registry')
        objects = registry.get('objects')
        if not isinstance(objects, list) or not objects:
            raise InvalidEvidence('Missing bridge objects')
        ids = [item['id'] for item in objects]
        if len(ids) != len(set(ids)) or set(ids) != set(BRIDGE_SCHEMA):
            raise InvalidEvidence('Missing, duplicate or unregistered bridge object')
        registered.update(BRIDGE_INPUTS)
        registered.update(registry.get('inputFiles', []))
        for item in objects:
            if type(item.get('arity')) is not int or not 0 < item['arity'] <= 16:
                raise InvalidEvidence('Invalid bounded policy arity')
            if (item.get('policy'), item['arity']) != BRIDGE_SCHEMA[item['id']]:
                raise InvalidEvidence('Bridge policy differs from the registered finite domain')
            if not set(item.get('supports', [])).issubset(EXPECTED_IDS):
                raise InvalidEvidence('Unknown obligation in bridge registry')
            registered.update(item.get('implementationFiles', []))
    return registered, blocks, registry


def report_binding(root, report, registered):
    """Validate the complete path set before opening any reported source file."""
    errors = []
    if (not isinstance(report, dict) or report.get('schemaVersion') != 1
            or report.get('kind') != 'offline-formal-block-verification'):
        return ['INVALID_FORMAL_REPORT']
    inputs = report.get('inputs')
    if not isinstance(inputs, dict) or not inputs:
        return ['MISSING_INPUT_BINDING']
    if set(inputs) - registered:
        return ['UNREGISTERED_INPUT: ' + ', '.join(sorted(set(inputs) - registered))]
    missing = registered - set(inputs)
    if missing:
        errors.append('MISSING_INPUT_BINDING: ' + ', '.join(sorted(missing)))
    if report.get('inputRootSha256') != hashlib.sha256(canonical(inputs)).hexdigest():
        errors.append('INPUT_ROOT_MISMATCH')
    # Validate paths and hashes as a whole before reading any report-named file.
    checked = []
    for relative, frozen in inputs.items():
        if not isinstance(frozen, str) or not re.fullmatch('[0-9a-f]{64}', frozen):
            errors.append(f'INVALID_INPUT_HASH: {relative}')
            continue
        try:
            checked.append((relative, safe_input(root, relative), frozen))
        except InvalidEvidence as error:
            errors.append(str(error))
    if errors:
        return errors
    for relative, path, frozen in checked:
        if digest(path) != frozen:
            errors.append(f'STALE_INPUT: {relative}')
    return errors


def bridge_evidence(report, registry, block_status):
    if registry is None:
        return 'BLOCKED', ['MISSING_BRIDGE_REGISTRY']
    objects = registry['objects']
    ids = {item['id'] for item in objects}
    errors = []
    if block_status != 'VERIFIED' or 'B03' not in report.get('blocks', []):
        errors.append('BRIDGE_PROOFS_NOT_VERIFIED')
    if report.get('bridgeStatus') != 'VERIFIED':
        errors.append('BRIDGE_VERIFIER_NOT_VERIFIED')
    if set(report.get('bridgeClaims', [])) != ids or len(report.get('bridgeClaims', [])) != len(ids):
        errors.append('MISSING_OR_DUPLICATE_BRIDGE_CLAIMS')
    correspondence = report.get('correspondence', {})
    expected_counts = {'required_objects': len(ids), 'mapped_objects': len(ids),
                       'unmapped_objects': 0, 'ambiguous_objects': 0}
    if any(type(correspondence.get(key)) is not int or correspondence[key] != value
           for key, value in expected_counts.items()):
        errors.append('UNRESOLVED_BRIDGE_CORRESPONDENCE')
    builds = report.get('builds', [])
    if not isinstance(builds, list) or len(builds) != 2:
        errors.append('MISSING_TWO_BRIDGE_BUILDS')
    else:
        rows = sum(2 ** item['arity'] for item in objects)
        for number, build in enumerate(builds, 1):
            evidence = build.get('bridges', {})
            if (evidence.get('status') != 'VERIFIED'
                    or evidence.get('kernelCheckedRows') != rows
                    or evidence.get('javascriptValuations') != rows
                    or evidence.get('javaValuations') != 8):
                errors.append(f'INCOMPLETE_BRIDGE_BUILD: {number}')
    return ('BLOCKED' if errors else 'VERIFIED'), errors


def build_overview(root=ROOT, report=None, report_path=None):
    root = Path(root).resolve()
    ledger = read_json(root, LEDGER)
    obligations = ledger.get('obligations', [])
    ids = [item['id'] for item in obligations]
    if set(ids) != EXPECTED_IDS or len(ids) != len(EXPECTED_IDS):
        raise InvalidEvidence('The overview requires exactly the registered L00–L23 obligations')
    registered, blocks, registry = registration(root, ledger)
    errors = []
    current = False
    block_status = bridge_status = 'NOT_RUN'
    if report is not None:
        errors = report_binding(root, report, registered)
        current = not errors
        if current:
            block_status = report.get('blockStatus', 'BLOCKED')
            if set(report.get('blocks', [])) != set(blocks):
                errors.append('ACTIVE_BLOCK_SET_MISMATCH')
                block_status = 'BLOCKED'
            bridge_status, bridge_errors = bridge_evidence(report, registry, block_status)
            errors.extend(bridge_errors)
        else:
            block_status = bridge_status = 'BLOCKED'
    bridge_objects = [] if registry is None else registry['objects']
    records = []
    for item in obligations:
        identity = item['id']
        supporting_blocks = sorted(name for name, block in blocks.items() if identity in block.get('supports', []))
        mapped_bridges = sorted(obj['id'] for obj in bridge_objects if identity in obj.get('supports', []))
        piece, remaining = SUPPORT.get(identity, ('No supporting Lean theorem is registered for this obligation.', item['implementationDetails']))
        records.append({
            'id': identity, 'title': item['title'], 'status': 'OPEN', 'ledgerStatus': item['status'],
            'endToEndStatus': 'OPEN', 'dependsOn': item['dependsOn'],
            'implementation': item['implementation'], 'supportingPieces': piece,
            'supportingProofBlocks': supporting_blocks,
            'proofEvidenceStatus': block_status if supporting_blocks else 'NOT_REGISTERED',
            'implementationBridges': mapped_bridges,
            'bridgeEvidenceStatus': bridge_status if mapped_bridges else 'NOT_REGISTERED',
            'remaining': remaining, 'requiredEvidence': item['requiredEvidence'],
            'ledgerEvidence': item['evidence'],
        })
    # No full-obligation checker exists. A ledger edit or a green finite bridge
    # cannot promote the end-to-end obligations to PROVED.
    proved_ledger = [item['id'] for item in obligations if item['status'] != 'OPEN']
    if proved_ledger:
        errors.append('UNSUPPORTED_LEDGER_DISCHARGE: ' + ', '.join(proved_ledger))
    return {
        'schemaVersion': 1, 'kind': 'formal-obligation-overview',
        'status': 'BLOCKED', 'formalClosureStatus': 'NOT_ESTABLISHED',
        'ledger': LEDGER, 'ledgerSha256': digest(root / LEDGER),
        'report': str(report_path) if report_path is not None else None,
        'reportCurrent': current, 'blockStatus': block_status, 'bridgeStatus': bridge_status,
        'inputRootSha256': report.get('inputRootSha256') if current else None,
        'counts': {'total': len(records), 'provedEndToEnd': 0, 'openEndToEnd': len(records),
                   'requiredBridges': len(BRIDGE_SCHEMA),
                   'registeredBridges': len(bridge_objects),
                   'verifiedBridges': len(bridge_objects) if bridge_status == 'VERIFIED' else 0,
                   'unresolvedBridges': len(BRIDGE_SCHEMA) if bridge_status != 'VERIFIED' else 0},
        'evidenceProblems': errors,
        'obligations': records,
        'trust': list(report.get('trust', [])) if current else [],
        'bridgeTrust': [] if registry is None else registry.get('trust', []),
        'excluded': [] if registry is None else registry.get('excluded', []),
        'interpretation': 'VERIFIED applies only to current frozen supporting proofs and finite Boolean policy correspondence under declared toolchain/runtime trust. All 24 end-to-end obligations remain OPEN. This renderer does not recheck proof terms.',
    }


def markdown(overview):
    def cell(value):
        return str(value).replace('|', '\\|').replace('\n', ' ')
    counts = overview['counts']
    lines = ['# Obligation fulfillment overview', '',
             f"Full implementation closure: **{overview['status']}** ({overview['formalClosureStatus']}).",
             f"End-to-end obligations: **{counts['provedEndToEnd']}/{counts['total']} proved**, {counts['openEndToEnd']} OPEN.",
             f"Supporting proofs: **{overview['blockStatus']}**. Finite bridges: **{overview['bridgeStatus']}** ({counts['verifiedBridges']}/{counts['requiredBridges']}).", '',
             overview['interpretation'], '',
             '| ID | Obligation | Supporting pieces | Evidence | Remaining work |',
             '| --- | --- | --- | --- | --- |']
    for item in overview['obligations']:
        evidence = ', '.join(item['supportingProofBlocks']) or 'No proof block'
        evidence += f" ({item['proofEvidenceStatus']})"
        if item['implementationBridges']:
            evidence += '; ' + ', '.join(item['implementationBridges']) + f" ({item['bridgeEvidenceStatus']})"
        lines.append('| ' + ' | '.join(cell(value) for value in (item['id'], item['title'], item['supportingPieces'], evidence, item['remaining'])) + ' |')
    if overview['evidenceProblems']:
        lines.extend(['', 'Evidence could not be used completely:', ''])
        lines.extend('- ' + cell(error) for error in overview['evidenceProblems'])
    for label, field in (('Execution trust', 'trust'), ('Bridge trust', 'bridgeTrust'), ('Excluded surfaces', 'excluded')):
        if overview[field]:
            lines.extend(['', label + ':', ''])
            lines.extend('- ' + cell(item) for item in overview[field])
    lines.extend(['', f"Ledger SHA-256: `{overview['ledgerSha256']}`.",
                  f"Current report input root: `{overview['inputRootSha256'] or 'NOT_RUN / UNBOUND'}`.", ''])
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', type=Path, help='Current scripts/verify_lean.py report.json')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/obligation-overview', help='Directory for overview.json and overview.md')
    args = parser.parse_args()
    try:
        report = json.loads(args.report.read_text(encoding='utf-8')) if args.report else None
        overview = build_overview(ROOT, report, args.report)
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(1, f'Unable to render obligation overview: {error}\n')
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / 'overview.json').write_text(json.dumps(overview, indent=2) + '\n', encoding='utf-8')
    (args.output / 'overview.md').write_text(markdown(overview), encoding='utf-8')
    print(json.dumps({'status': overview['status'], 'bridgeStatus': overview['bridgeStatus'],
                      'output': str(args.output)}))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
