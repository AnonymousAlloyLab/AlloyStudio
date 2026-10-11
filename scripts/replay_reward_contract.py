#!/usr/bin/env python3
"""Replay the immutable historical reward/LFU model; never renew production closure.

The original verifier and every dependency execute from their pinned historical
snapshot. Changed Java/Python production code remains outside this replay.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HISTORY = 'closure/reward-contract/evidence/reward-20261008T100725Z-db006f47'
MANIFEST_SHA256 = '7ee4316348aa92de3677e2b8ec823309dec38bb54e94b11b7622d6a11a2b3310'
REPORT_SHA256 = '1ddfaaddc746897e9d8d1e888851389ccdab36948f1f245d57bfa376728ce38a'
INPUT_ROOT = '1d50d5ac13cd7c01cd97d75120044f9650ff344c7cce0747389e5026ee0be3ad'
VERIFIER_SHA256 = '5153725ed07b1843cabbb8763aea5b3356d7bd9040127112620e2f7f839f49c5'
CURRENT_MODEL = frozenset((
    'formal/reward/Reward.lean', 'formal/reward/LFU.lean', 'formal/reward/Audit.lean',
    'formal/reward/theorems.json', 'formal/reward/claims.json', 'formal/reward/witnesses.json',
    'formal/reward/block.json', 'formal/reward/README.md', 'docs/reward-lfu-contract.md',
    'lean-toolchain', 'formal/lean-toolchain',
))
# -I ignores ambient Python modules, user startup hooks and PYTHONPATH. Only the
# validated copied scripts directory is admitted before importing the verifier.
BOOTSTRAP = ('import sys; sys.path.insert(0, sys.argv[1]); '
             'import verify_reward_contract; raise SystemExit(verify_reward_contract.verify())')


class Blocked(ValueError):
    """Fixed diagnostic codes only: no source, stream or credential disclosure."""


def digest(value):
    return hashlib.sha256(value).hexdigest()


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':')).encode()


def regular(root, relative):
    if not isinstance(relative, str):
        raise Blocked('INVALID_INPUT_PATH')
    path = PurePosixPath(relative)
    if (not relative or path.is_absolute()
            or '..' in path.parts or path.as_posix() != relative):
        raise Blocked('INVALID_INPUT_PATH')
    target = root.joinpath(*path.parts)
    if root.is_symlink() or any(parent.is_symlink() for parent in root.parents):
        raise Blocked('LINKED_INPUT')
    for entry in (target, *target.parents):
        if entry == root:
            break
        if entry.is_symlink():
            raise Blocked('LINKED_INPUT')
    if not target.is_file():
        raise Blocked('MISSING_INPUT')
    return target


def read_checked(root, relative, expected, code):
    data = regular(root, relative).read_bytes()
    if digest(data) != expected:
        raise Blocked(code)
    return data


def historical_inputs(root):
    """Independent byte pins prevent a rewritten manifest/report blessing itself."""
    history = root / HISTORY
    manifest_bytes = read_checked(history, 'manifest.json', MANIFEST_SHA256, 'HISTORICAL_MANIFEST_MUTATION')
    historical_report = json.loads(read_checked(history, 'report.json', REPORT_SHA256, 'HISTORICAL_REPORT_MUTATION'))
    manifest = json.loads(manifest_bytes)
    if (type(manifest) is not dict or digest(encoded(manifest)) != INPUT_ROOT
            or manifest.get('scripts/verify_reward_contract.py') != VERIFIER_SHA256
            or not CURRENT_MODEL <= set(manifest)):
        raise Blocked('HISTORICAL_MANIFEST_INVALID')
    data = {relative: read_checked(history / 'inputs', relative, sha, 'HISTORICAL_INPUT_MUTATION')
            for relative, sha in manifest.items()}
    check_current_model(root, manifest)
    check_result(historical_report, historical_report)
    return manifest, data, historical_report


def check_current_model(root, manifest):
    for relative in CURRENT_MODEL:
        read_checked(root, relative, manifest[relative], 'CURRENT_MODEL_MUTATION')
    model = root / 'formal/reward'
    if {p.relative_to(model).as_posix() for p in model.rglob('*.lean')} != {'Reward.lean', 'LFU.lean', 'Audit.lean'}:
        raise Blocked('CURRENT_MODEL_INVENTORY_MUTATION')


def check_result(report, historical):
    """Reject a process success or abbreviated PASS that lacks frozen evidence."""
    required = dict(schemaVersion=1, blockId='RWD01', scope='reward-lfu-model-only',
                    status='VERIFIED', modelStatus='VERIFIED',
                    productionRefinementStatus='NOT_ESTABLISHED', inputRootHash=INPUT_ROOT,
                    verifierHash=VERIFIER_SHA256, provedTheorems=92, axioms=[],
                    determinism='PASS', blockingReasons=[], infrastructureErrors=[])
    if type(report) is not dict or any(type(report.get(key)) is not type(value)
            or report.get(key) != value for key, value in required.items()):
        raise Blocked('REPLAY_REPORT_INCOMPLETE')
    for key in ('builds', 'claims', 'negativeControls', 'witnesses', 'correspondence',
                'provenance', 'toolchain', 'trust', 'closureBoundary'):
        if encoded(report.get(key)) != encoded(historical.get(key)):
            raise Blocked('REPLAY_EVIDENCE_MISMATCH')
    builds = report['builds']
    if (len(builds) != 2 or builds[0] != builds[1]
            or any(build.get('status') != 'PASS' or build.get('exitCode') != 0
                   or build.get('theorems') != 92 or build.get('axioms') != [] for build in builds)
            or len(report['claims']) != 8 or any(claim.get('status') != 'PASS' for claim in report['claims'])
            or report['witnesses'] != dict(required=4, valid=4, invalid=0)
            or set(report['negativeControls']) != {'false_full_score', 'missing_witness_update', 'placeholder', 'rogue_axiom'}
            or any(control.get('status') != 'REJECTED' for control in report['negativeControls'].values())):
        raise Blocked('REPLAY_EVIDENCE_INCOMPLETE')


def make_directory(root):
    parent = root / 'build/reward-replay'
    for entry in (parent, *parent.parents):
        if entry == root:
            break
        if entry.is_symlink():
            raise Blocked('LINKED_OUTPUT')
    parent.mkdir(parents=True, exist_ok=True)
    identifier = datetime.now(timezone.utc).strftime('replay-%Y%m%dT%H%M%SZ-') + secrets.token_hex(4)
    directory = parent / identifier
    directory.mkdir(mode=0o700)
    return directory


def replay(root=ROOT, *, runner=None):
    root = Path(root).resolve()
    report = dict(schemaVersion=1, status='BLOCKED', scope='frozen-historical-reward-lfu-model-replay',
                  productionRefinementStatus='NOT_ESTABLISHED', productionBindingRenewed=False,
                  historicalInputRootHash=INPUT_ROOT, historicalVerifierSha256=VERIFIER_SHA256,
                  blockingReasons=[], infrastructureErrors=[],
                  interpretation='Replay of the frozen historical model only; current production binding remains stale and is not renewed.')
    directory = None
    try:
        directory = make_directory(root)
        manifest, files, historical = historical_inputs(root)
        snapshot = directory / 'snapshot'
        snapshot.mkdir()
        for relative, data in files.items():
            path = snapshot / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
            path.chmod(0o400)
        report['currentModelMatchesHistoricalInputs'] = True
        environment = {name: os.environ[name] for name in ('LANG', 'LC_ALL', 'TZ', 'ELAN_HOME') if name in os.environ}
        environment['PATH'] = os.defpath
        environment['OPENAI_DISABLED'] = '1'
        execute = subprocess.run if runner is None else runner
        with (directory / 'verifier.log').open('wb') as log:
            result = execute([sys.executable, '-I', '-c', BOOTSTRAP, str(snapshot / 'scripts')],
                             cwd=snapshot, env=environment, stdout=log, stderr=subprocess.STDOUT,
                             timeout=600, check=False)
        inner = list((snapshot / 'build/reward-contract').glob('*/report.json'))
        if len(inner) != 1:
            raise Blocked('REPLAY_REPORT_MISSING_OR_AMBIGUOUS')
        inner_report = json.loads(inner[0].read_bytes())
        report['replayedReport'] = inner[0].relative_to(directory).as_posix()
        report['replayedReportSha256'] = digest(inner[0].read_bytes())
        if type(inner_report) is not dict:
            raise Blocked('REPLAY_REPORT_INCOMPLETE')
        if inner_report.get('status') == 'INFRASTRUCTURE_FAILURE':
            raise RuntimeError('REPLAY_VERIFIER_INFRASTRUCTURE_FAILURE')
        if result.returncode != 0:
            raise Blocked('REPLAY_VERIFIER_REJECTED')
        check_result(inner_report, historical)
        # Bind the completed replay to unchanged input bytes, not just preflight.
        after, _, _ = historical_inputs(root)
        if after != manifest:
            raise Blocked('HISTORICAL_INPUT_MUTATION')
        report.update(status='REPLAY_PASS', cleanBuilds=2, provedTheorems=92, axioms=[],
                      negativeControls='PASS', modelClaims=8, modelWitnesses=4,
                      historicalManifestSha256=MANIFEST_SHA256, historicalReportSha256=REPORT_SHA256)
    except (Blocked, ValueError, KeyError, TypeError) as error:
        report['blockingReasons'].append(str(error) if isinstance(error, Blocked) else 'INVALID_REPLAY_DATA')
    except (OSError, RuntimeError, subprocess.TimeoutExpired):
        report['status'] = 'INFRASTRUCTURE_FAILURE'
        report['infrastructureErrors'].append('REPLAY_EXECUTION_UNAVAILABLE')
    if directory is not None:
        (directory / 'report.json').write_text(json.dumps(report, sort_keys=True, indent=2) + '\n')
    return report, directory


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    args = parser.parse_args(argv)
    report, directory = replay(args.root)
    print(json.dumps(dict(status=report['status'], scope=report['scope'],
                         productionBindingRenewed=False,
                         report=str(directory / 'report.json') if directory else None)))
    return 0 if report['status'] == 'REPLAY_PASS' else 2 if report['status'] == 'INFRASTRUCTURE_FAILURE' else 1


if __name__ == '__main__':
    raise SystemExit(main())
