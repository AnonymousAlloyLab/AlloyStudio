#!/usr/bin/env python3
"""Rebuild frozen constructive proof blocks twice, offline, and audit all theorems.

Block verification is deliberately separate from portal implementation closure.
Reviews are a required workflow record, never a substitute for kernel checking.
No network fetch, elan invocation, third-party tactic package, or native proof
evaluation is used. The installed Lean distribution and this verifier are TCB.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time

from lean_offline import ROOT, clean_environment, installed_toolchain, isolated_command
import bridge_policies

FLAGS = ['--trust=0', '-DwarningAsError=true', '-DgenInjectivity=false', '-j1']
TIERS = (('luna', 'gpt-6-luna'), ('sol', 'gpt-6-sol'), ('astra', 'gpt-6-astra'))


class Rejected(ValueError):
    """Frozen evidence is missing, changed, or invalid."""


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def bridge_runtime_path(name):
    # Match bridge_policies.verify_build's host PATH resolution. The child
    # receives a clean environment but executes this absolute selected binary.
    executable = shutil.which(name)
    if executable is None:
        raise RuntimeError(f'Missing bridge runtime: {name}')
    return Path(executable).resolve()


def check_bridge_runtimes(inventory):
    for name, runtime in inventory.items():
        actual = bridge_runtime_path(name)
        if str(actual) != runtime['executable'] or digest(actual) != runtime['sha256']:
            raise Rejected('A bridge runtime resolution or executable changed during verification')


def inside(root, relative):
    path = Path(relative)
    if path.is_absolute() or '..' in path.parts or not (root / path).resolve().is_relative_to(root.resolve()):
        raise Rejected(f'Input is outside the source root: {relative}')
    target = root / path
    if target.is_symlink() or not target.is_file():
        raise Rejected(f'Missing or linked input: {relative}')
    return target


def proof_code(text):
    """Remove nested Lean comments and strings before conservative token checks."""
    output, i, depth = [], 0, 0
    while i < len(text):
        if depth:
            if text.startswith('/-', i):
                depth += 1; i += 2
            elif text.startswith('-/', i):
                depth -= 1; i += 2
            else:
                i += 1
        elif text.startswith('/-', i):
            depth = 1; i += 2; output.append(' ')
        elif text.startswith('--', i):
            end = text.find('\n', i)
            i = len(text) if end < 0 else end
        elif text[i] == '"':
            output.append(' '); i += 1
            while i < len(text) and text[i] != '"':
                i += 2 if text[i] == '\\' else 1
            i += 1
        else:
            output.append(text[i]); i += 1
    if depth:
        raise Rejected('Unterminated proof comment')
    return ''.join(output)


def check_proof_source(text, modules):
    code = proof_code(text)
    # This is a deliberately narrow registered source language, not a security
    # sandbox for arbitrary Lean metaprograms. Audit.lean is separately trusted.
    forbidden = r'\b(sorry|admit|axiom|unsafe|native_decide|implemented_by|extern|run_elab|elab|macro|syntax|initialize|builtin_initialize|set_option|partial|opaque|prelude)\b|#(eval|compile|guard_msgs)'
    if re.search(forbidden, code):
        raise Rejected('Forbidden proof-language escape or unchecked declaration')
    imports = []
    for line in code.splitlines():
        if re.search(r'\bimport\b', line):
            # Lean also permits multiline imports. This registered subset uses
            # a single line, so a split keyword/name must fail, not evade audit.
            match = re.fullmatch(r'\s*import +([A-Za-z][A-Za-z0-9_.]*(?: +[A-Za-z][A-Za-z0-9_.]*)*)\s*', line)
            if match is None:
                raise Rejected('Unsupported proof import syntax')
            names = match[1].split()
            if any(name not in modules | {'Std'} for name in names):
                raise Rejected('Unregistered proof import')
            imports.extend(names)
    return imports


def expected_inventory(blocks):
    expected = {}
    for block in blocks:
        for item in block['theorems']:
            name = item['name']
            if name in expected:
                raise Rejected(f'Duplicate registered theorem: {name}')
            expected[name] = item
    if not expected:
        raise Rejected('Empty theorem inventory')
    return expected


def check_barrel(root):
    """The checked entrypoint may re-export modules, never hide declarations."""
    modules = {'AlloyStudio.' + path.stem for path in (root / 'formal/AlloyStudio').glob('*.lean')}
    source = (root / 'formal/AlloyStudio.lean').read_text()
    imports = check_proof_source(source, modules)
    if set(imports) != modules or len(imports) != len(modules):
        raise Rejected('The library entrypoint must import each proof module exactly once')
    if any(not re.fullmatch(r'\s*import +[A-Za-z0-9_. ]+\s*', line)
           for line in proof_code(source).splitlines() if line.strip()):
        raise Rejected('The library entrypoint contains an unaudited declaration')


def check_inventory(rows, expected):
    actual = {}
    if not isinstance(rows, list):
        raise Rejected('Malformed theorem audit inventory')
    for row in rows:
        if not isinstance(row, dict):
            raise Rejected('Malformed theorem audit row')
        if row.get('kind') != 'theorem' or row.get('axioms') != []:
            raise Rejected('A project axiom or a transitive axiom dependency was found')
        if (set(row) != {'kind', 'name', 'module', 'levelParameters', 'type', 'axioms'}
                or any(not isinstance(row[key], str) for key in ('name', 'module', 'type'))
                or not isinstance(row['levelParameters'], list)
                or any(not isinstance(level, str) for level in row['levelParameters'])):
            raise Rejected('Malformed theorem audit fields')
        name = row['name']
        if name in actual:
            raise Rejected(f'Duplicate audited theorem: {name}')
        actual[name] = {'name': name, 'module': row['module'],
                        'levelParameters': row['levelParameters'],
                        'typeSha256': hashlib.sha256(row['type'].encode()).hexdigest()}
    if actual != expected:
        missing = sorted(set(expected) - set(actual))
        extra = sorted(set(actual) - set(expected))
        changed = sorted(n for n in set(actual) & set(expected) if actual[n] != expected[n])
        raise Rejected(f'Theorem inventory/type mismatch: missing={missing}, extra={extra}, changed={changed}')
    return actual


def check_reviews(root, block_path):
    """Require two independent reports at each sequential tier, with hash links."""
    block_hash = digest(root / block_path)
    block_id = json.loads((root / block_path).read_text())['id']
    prior, records = {}, {}
    for tier, (prefix, model) in enumerate(TIERS, 1):
        current = {}
        for suffix in ('a', 'b'):
            relative = f'formal/reviews/{block_id}/{prefix}-{suffix}.json'
            path = inside(root, relative)
            report = json.loads(path.read_text())
            if (not isinstance(report, dict) or report.get('reviewerModel') != model or report.get('tier') != tier
                    or report.get('blockManifestSha256') != block_hash
                    or report.get('priorReviews') != prior):
                raise Rejected(f'Review provenance/ordering mismatch: {relative}')
            if report.get('verdict') != 'no_constructed_breach' or report.get('findings') != []:
                raise Rejected(f'Unresolved review finding: {relative}; a constructed breach needs replay and a revised block')
            current[relative] = digest(path)
            # Coverage notes are evidence inputs too; they are not proof terms.
            notes = relative.removesuffix('.json') + '.md'
            inside(root, notes)
        records.update(current)
        prior.update(current)
    return records


def load_blocks(root, names, pin):
    blocks, inputs = [], {}
    for name in names:
        if not re.fullmatch(r'B\d\d', name):
            raise Rejected('Invalid block identifier')
        relative = f'formal/blocks/{name}.json'
        path = inside(root, relative)
        block = json.loads(path.read_text())
        if (not isinstance(block, dict) or block.get('schemaVersion') != 1 or block.get('id') != name
                or block.get('leanToolchain') != pin or block.get('flags') != FLAGS
                or block.get('allowlistedAxioms') != []
                or block.get('doesNotCloseOriginalObligations') is not True):
            raise Rejected(f'Invalid block policy: {name}')
        inputs[relative] = digest(path)
        for source, frozen in block['sources'].items():
            if not re.fullmatch(r'formal/AlloyStudio/[A-Za-z]+\.lean', source):
                raise Rejected('Invalid proof module path')
            if source in inputs or digest(inside(root, source)) != frozen:
                raise Rejected(f'Duplicate or changed frozen source: {source}')
            inputs[source] = frozen
        for source, frozen in block.get('implementationInputs', {}).items():
            if digest(inside(root, source)) != frozen:
                raise Rejected(f'Changed implementation regression input: {source}')
            inputs[source] = frozen
        blocks.append(block)
    return blocks, inputs


def run(command, cwd, environment, log):
    try:
        result = subprocess.run(isolated_command(command), cwd=cwd, env=environment,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise RuntimeError(f'Offline verifier process failed: {type(error).__name__}') from error
    log.parent.mkdir(parents=True, exist_ok=True)
    log.write_bytes(result.stdout)
    if result.returncode:
        if result.returncode < 0:
            raise RuntimeError(f'Offline verifier process killed by signal {-result.returncode}; inspect {log}')
        if b'unshare:' in result.stdout:
            raise RuntimeError('Network namespace creation failed')
        raise Rejected(f'Proof command failed ({result.returncode}); inspect {log}')
    return result.stdout


def toolchain_inventory(directory):
    paths = [p for folder in ('bin', 'lib') for p in (directory / folder).rglob('*') if p.is_file()]
    entries = {p.relative_to(directory).as_posix(): digest(p) for p in sorted(paths)}
    if not entries:
        raise RuntimeError('Installed toolchain inventory is empty')
    return entries


def network_witness(work, environment):
    code = ('import json,socket; from pathlib import Path; '
            'interfaces=sorted(name for _,name in socket.if_nameindex()); '
            'routes=Path("/proc/net/route").read_text().splitlines(); '
            'assert interfaces == ["lo"], interfaces; '
            'assert not routes or (len(routes)==1 and routes[0].startswith("Iface")), routes; '
            's=socket.socket(); s.settimeout(1); r=s.connect_ex(("192.0.2.1",443)); '
            'assert r != 0; print(json.dumps({"interfaces":interfaces,"ipv4Routes":0,"outboundConnectError":r}))')
    try:
        return json.loads(run([sys.executable, '-I', '-c', code], work, environment, work / 'network.json'))
    except Rejected as error:
        raise RuntimeError('Network namespace witness failed') from error


def build_once(root, work, toolchain, sources, expected, bridges=False):
    formal = work / 'formal'
    formal.mkdir(parents=True)
    for source in sources:
        destination = formal / Path(source).relative_to('formal')
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(root / source, destination)
    modules = {Path(p).relative_to('formal').with_suffix('').as_posix().replace('/', '.') for p in sources}
    dependencies = {}
    for source in sources:
        module = Path(source).relative_to('formal').with_suffix('').as_posix().replace('/', '.')
        dependencies[module] = check_proof_source((root / source).read_text(), modules)
    remaining = set(modules)
    order = []
    while remaining:
        ready = sorted(m for m in remaining if all(dep not in remaining for dep in dependencies[m]))
        if not ready:
            raise Rejected('Proof import cycle')
        order.extend(ready); remaining.difference_update(ready)
    imports = ''.join(f'import {module}\n' for module in order)
    (formal / 'AlloyStudio.lean').write_text(imports)
    shutil.copyfile(root / 'formal/Audit.lean', formal / 'Audit.lean')
    output = work / 'objects'
    output.mkdir()
    environment = clean_environment(toolchain, output)
    lean = str(toolchain / 'bin/lean')
    network = network_witness(work, environment)
    for module in [*order, 'AlloyStudio']:
        relative = module.replace('.', '/')
        target = output / (relative + '.olean')
        target.parent.mkdir(parents=True, exist_ok=True)
        run([lean, *FLAGS, '-o', str(target), relative + '.lean'], formal, environment,
            work / 'logs' / (module + '.log'))
    data = run([lean, *FLAGS, 'Audit.lean'], formal, environment, work / 'inventory.jsonl')
    try:
        rows = [json.loads(line) for line in data.decode().splitlines()]
    except (ValueError, UnicodeError) as error:
        raise Rejected('Malformed theorem audit output') from error
    checked = check_inventory(rows, expected)
    bridge_result = bridge_policies.verify_build(root, work / 'bridges', output, toolchain, run) if bridges else None
    artifacts = {p.relative_to(output).as_posix(): digest(p) for p in sorted(output.rglob('*.olean'))}
    if bridges:
        artifacts.update({'bridges/' + p.relative_to(work / 'bridges').as_posix(): digest(p)
                          for p in sorted((work / 'bridges/java-classes').rglob('*.class'))})
    return {'inventorySha256': hashlib.sha256(json_bytes(checked)).hexdigest(),
            'theorems': len(checked), 'artifacts': artifacts, 'network': network, 'bridges': bridge_result}


def negative_controls(root, work, toolchain):
    """Try deliberately invalid scratch inputs, outside the accepted library."""
    work.mkdir()
    (work / 'AlloyStudio').mkdir()
    objects = work / 'objects'
    (objects / 'AlloyStudio').mkdir(parents=True)
    env = clean_environment(toolchain, objects)
    lean = str(toolchain / 'bin/lean')
    outcomes = {}
    # Verify that the compiler itself refuses placeholders, not only the scanner.
    (work / 'Placeholder.lean').write_text('import Std\ntheorem unacceptable : False := by sorry\n')
    try:
        run([lean, *FLAGS, 'Placeholder.lean'], work, env, work / 'placeholder.log')
    except Rejected:
        if 'sorry' not in (work / 'placeholder.log').read_text():
            raise Rejected('Placeholder negative control failed for an unrelated reason')
        outcomes['placeholder'] = 'REJECTED'
    else:
        raise Rejected('Placeholder negative control was accepted')
    # A custom axiom is legal Lean syntax. The full declaration audit must still
    # refuse it, including when its dependent theorem is never registered.
    (work / 'AlloyStudio/Adversarial.lean').write_text(
        'import Std\nnamespace AlloyStudio.Adversarial\n'
        'axiom fabricated : False\ntheorem falseClaim : False := fabricated\n'
        'end AlloyStudio.Adversarial\n')
    run([lean, *FLAGS, '-o', str(objects / 'AlloyStudio/Adversarial.olean'),
         'AlloyStudio/Adversarial.lean'], work, env, work / 'custom-axiom-build.log')
    audit = (root / 'formal/Audit.lean').read_text().replace('import AlloyStudio\n', 'import AlloyStudio.Adversarial\n')
    (work / 'Audit.lean').write_text(audit)
    data = run([lean, *FLAGS, 'Audit.lean'], work, env, work / 'custom-axiom-inventory.jsonl')
    rows = [json.loads(line) for line in data.decode().splitlines()]
    if not any(row.get('kind') == 'forbidden-project-axiom' for row in rows):
        raise Rejected('The audit did not enumerate the constructed custom axiom')
    try:
        check_inventory(rows, {})
    except Rejected:
        outcomes['customAxiomAndDependentTheorem'] = 'REJECTED'
    else:
        raise Rejected('Custom axiom negative control was accepted')
    return outcomes


def verify(root, names, output):
    pin, toolchain = installed_toolchain(root)
    blocks, inputs = load_blocks(root, names, pin)
    check_barrel(root)
    expected = expected_inventory(blocks)
    bridges = 'B03' in names
    bridge_registry = None
    if bridges:
        bridge_registry = json.loads((root / 'formal/bridges/registry.json').read_text())
        objects = bridge_registry['objects']
        if (len(objects) != len(bridge_policies.SCHEMA)
                or len({obj['id'] for obj in objects}) != len(objects)
                or len({obj['implementation'] for obj in objects}) != len(objects)
                or {obj['policy'] for obj in objects} != set(bridge_policies.SCHEMA)):
            raise Rejected('Missing, duplicate or ambiguous policy correspondence')
        for obj in objects:
            if obj['arity'] != bridge_policies.SCHEMA[obj['policy']][0] or not obj['theorems']:
                raise Rejected('Malformed policy correspondence')
            if any(name not in expected for name in obj['theorems']):
                raise Rejected('A bridge maps to a missing or unregistered theorem')
            for source in obj['implementationFiles']:
                inputs[source] = digest(inside(root, source))
        for source in ('formal/bridges/registry.json', 'formal/bridges/policies.json',
                       'formal/ExportBridges.lean', 'scripts/bridge_policies.py',
                       'scripts/obligation_overview.py', 'tests/test_obligation_overview.py',
                       'docs/implementation-bridges.md'):
            inputs[source] = digest(inside(root, source))
    proof_sources = [p for block in blocks for p in block['sources']]
    for path in ('lean-toolchain', 'formal/lean-toolchain', 'formal/Audit.lean',
                 'formal/lakefile.toml', 'formal/AlloyStudio.lean', 'scripts/lean_offline.py',
                 'scripts/verify_lean.py', 'tests/test_lean_verifier.py', 'closure/lean-obligations.json'):
        inputs[path] = digest(inside(root, path))
    review_errors = []
    for name in names:
        try:
            reviews = check_reviews(root, f'formal/blocks/{name}.json')
            inputs.update(reviews)
            for relative in reviews:
                notes = relative.removesuffix('.json') + '.md'
                inputs[notes] = digest(inside(root, notes))
        except (Rejected, ValueError) as error:
            review_errors.append(str(error))
    tool_inventory = toolchain_inventory(toolchain)
    (output / 'toolchain-files.json').write_text(json.dumps(tool_inventory, indent=2) + '\n')
    environment = clean_environment(toolchain)
    version = run([str(toolchain / 'bin/lean'), '--version'], output, environment, output / 'lean-version.txt').decode().strip()
    runtime_inventory = {}
    if bridges:
        for name, option in (('node', '--version'), ('java', '-version'), ('javac', '-version')):
            executable = str(bridge_runtime_path(name))
            runtime_inventory[name] = {
                'executable': executable, 'sha256': digest(Path(executable)),
                'version': run([executable, option], output, environment,
                               output / f'{name}-version.txt').decode().strip(),
            }
    run([sys.executable, '-I', '-m', 'unittest', 'discover', '-s', str(root / 'tests'),
         '-p', 'test_lean_verifier.py'], root, environment, output / 'gate-regressions.log')
    builds = [build_once(root, output / f'build-{number}', toolchain, proof_sources, expected, bridges) for number in (1, 2)]
    if (builds[0]['artifacts'] != builds[1]['artifacts'] or builds[0]['inventorySha256'] != builds[1]['inventorySha256']
            or builds[0]['bridges'] != builds[1]['bridges']):
        raise Rejected('The two independent proof builds differ')
    controls = negative_controls(root, output / 'negative-controls', toolchain)
    for relative, frozen in inputs.items():
        if digest(inside(root, relative)) != frozen:
            raise Rejected(f'Input changed during verification: {relative}')
    if toolchain_inventory(toolchain) != tool_inventory:
        raise Rejected('Toolchain changed during verification')
    check_bridge_runtimes(runtime_inventory)
    ledger = json.loads((root / 'closure/lean-obligations.json').read_text())
    unresolved = [o['id'] for o in ledger['obligations'] if o['status'] != 'PROVED']
    # The finite policy kernels now have checked runtime correspondence. This
    # does not close wire/host-loop/algorithm obligations by relabeling the ledger.
    return {'schemaVersion': 1, 'kind': 'offline-formal-block-verification',
            'status': 'BLOCKED', 'blockStatus': 'BLOCKED' if review_errors else 'VERIFIED',
            'scope': 'The frozen mathematical definitions and theorem types only',
            'blocks': names, 'leanVersion': version, 'flags': FLAGS, 'allowlistedAxioms': [],
            'bridgeStatus': ('BLOCKED' if review_errors else 'VERIFIED') if bridges else 'NOT_RUN',
            'bridgeClaims': [obj['id'] for obj in bridge_registry['objects']] if bridges else [],
            'correspondence': {'required_objects': 4 if bridges else 0, 'mapped_objects': 4 if bridges else 0,
                               'unmapped_objects': 0, 'ambiguous_objects': 0,
                               'scope': 'Complete finite Boolean policy kernels only'},
            'inputs': inputs, 'inputRootSha256': hashlib.sha256(json_bytes(inputs)).hexdigest(),
            'toolchainRootSha256': hashlib.sha256(json_bytes(tool_inventory)).hexdigest(),
            'bridgeRuntimes': runtime_inventory,
            'builds': builds, 'negativeControls': controls, 'reviewErrors': review_errors, 'openObligations': unresolved,
            'formalClosureBlockers': ['Full wire, host-loop and algorithm semantic correspondence is not established',
                                     *[f'{identity} is not discharged' for identity in unresolved]],
            'trust': ['Installed Lean 4.34.1 kernel, elaborator, and bundled library',
                      'Registered Audit.lean, Python verifier, hashing and subprocess implementation',
                      'Model selection and review provenance recorded by the agent orchestrator',
                      *([] if not bridges else bridge_registry['trust']),
                      'Linux namespace enforcement, filesystem, OS and hardware'],
            'notEstablished': ['Raw AST distance optimality or Java refinement', 'Canonical normalization/metric correctness',
                               'Complete portal disclosure, behavior, coordinates or delivery refinement']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--blocks', nargs='+', default=['B01', 'B03'])
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.output:
        output = args.output.resolve()
        if output.exists():
            parser.error('Use a new output directory for each frozen run')
        output.mkdir(parents=True)
    else:
        (ROOT / 'build/lean-verification').mkdir(parents=True, exist_ok=True)
        output = Path(tempfile.mkdtemp(prefix=time.strftime('%Y%m%dT%H%M%S-'), dir=ROOT / 'build/lean-verification'))
    try:
        report = verify(ROOT, args.blocks, output)
    except (Rejected, ValueError, KeyError, TypeError) as error:
        report = {'status': 'BLOCKED', 'blockStatus': 'BLOCKED', 'reason': str(error)}
    except (OSError, RuntimeError) as error:
        report = {'status': 'INFRASTRUCTURE_FAILURE', 'blockStatus': 'INFRASTRUCTURE_FAILURE', 'reason': str(error)}
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'blockStatus': report['blockStatus'],
                      'report': str(output / 'report.json'), 'reason': report.get('reason')}))
    return 0 if report['status'] == 'VERIFIED' else 2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    raise SystemExit(main())
