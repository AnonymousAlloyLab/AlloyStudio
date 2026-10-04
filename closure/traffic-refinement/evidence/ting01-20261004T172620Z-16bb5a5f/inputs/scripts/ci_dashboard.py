#!/usr/bin/env python3
"""Generate a public, allowlisted CI dashboard without publishing private evidence."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'AnonymousAlloyLab/AlloyStudio'
CHECKS = ('build', 'runtime', 'python', 'browser', 'dashboard')
STATES = {'PASS', 'FAIL', 'NOT_RUN', 'UNAVAILABLE'}
HEX = re.compile(r'[0-9a-f]{40,64}\Z')


def read_json(path):
    try:
        path = Path(path)
        if path.is_symlink() or path.stat().st_size > 5_000_000:
            return {}
        value = json.loads(path.read_text(encoding='utf-8'))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError, RecursionError):
        return {}


def revision(root):
    """Only return a commit hash and dirty flag, never Git output/pathnames."""
    try:
        result = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True,
                                text=True, timeout=5, check=False)
        sha = result.stdout.strip()
        dirty = subprocess.run(['git', 'status', '--porcelain', '--untracked-files=normal'],
                               cwd=root, capture_output=True, text=True, timeout=5, check=False)
        if result.returncode == 0 and dirty.returncode == 0 and HEX.fullmatch(sha):
            return {'sha': sha, 'dirty': bool(dirty.stdout)}
    except (OSError, subprocess.SubprocessError):
        pass
    return {'sha': None, 'dirty': None}


def count(value):
    return value if type(value) is int and 0 <= value <= 10_000_000 else None


def safe_closure(root):
    """Historical finite result only. No claim that these inputs match this build."""
    candidates = sorted((Path(root) / 'closure/runs').glob('*/reports/closure-report.json'), reverse=True)
    for path in candidates:
        report = read_json(path)
        status = report.get('status')
        identity = report.get('closure_id')
        digest = report.get('input_root_hash')
        if (not isinstance(status, str) or status not in {'VERIFIED', 'BLOCKED', 'INFRASTRUCTURE_FAILURE'}
                or not isinstance(identity, str)
                or not re.fullmatch(r'portal-\d{8}T\d{6}Z-[a-f0-9]{8}', identity)
                or not isinstance(digest, str) or not re.fullmatch(r'[a-f0-9]{64}', digest)):
            continue
        claims, builds = report.get('claims', {}), report.get('builds', {})
        if not isinstance(claims, dict) or not isinstance(builds, dict):
            continue
        return {'status': status, 'id': identity, 'inputRootHash': digest,
                'claimsPassed': count(claims.get('passed')), 'claimsTotal': count(claims.get('total')),
                'buildsPassed': count(builds.get('passed')), 'buildsRequired': count(builds.get('required')),
                'current': False}
    return {'status': 'NOT_AVAILABLE', 'current': False}


def build_snapshot(root=ROOT):
    root = Path(root)
    current = revision(root)
    package = read_json(root / 'package.json')
    version = package.get('version')
    if not isinstance(version, str) or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version):
        version = 'unknown'
    checks = []
    for name in CHECKS:
        value = read_json(root / 'build/ci' / (name + '.json'))
        state = value.get('status') if value.get('check') == name else None
        if not isinstance(state, str):
            state = None
        sha = value.get('revision')
        valid_sha = isinstance(sha, str) and HEX.fullmatch(sha)
        checks.append({'name': name, 'status': state if state in STATES else 'NOT_RUN',
                       'count': count(value.get('count')) if state in STATES else None,
                       'revision': sha if valid_sha else None,
                       'current': bool(valid_sha and sha == current['sha'] and current['dirty'] is False
                                       and value.get('dirty') is False)})
    obligations = read_json(root / 'closure/lean-obligations.json').get('obligations', [])
    lean = {'total': len(obligations), 'open': sum(item.get('status') == 'OPEN' for item in obligations)} if isinstance(obligations, list) and all(isinstance(item, dict) for item in obligations) else None
    return {'schemaVersion': 1, 'repository': REPOSITORY, 'version': version,
            'revision': current, 'checks': checks, 'closure': safe_closure(root), 'lean': lean}


def write_dashboard(root, output):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    for filename in ('index.html', 'app.js', 'styles.css'):
        source, target = root / 'web/dashboard' / filename, output / filename
        if source.resolve() != target.resolve():
            shutil.copyfile(source, target)
    (output / 'data.json').write_text(json.dumps(build_snapshot(root), sort_keys=True, indent=2) + '\n', encoding='utf-8')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/ci-dashboard')
    args = parser.parse_args()
    write_dashboard(args.root, args.output)
    print('Dashboard generated: static assets and safe summary only.')


if __name__ == '__main__':
    main()
