#!/usr/bin/env python3
"""Run a named CI check, publishing only status/counts, never captured model data."""
from __future__ import annotations
import argparse
import json
import os
import shutil
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.ci_dashboard import CHECKS, count, revision
from scripts.package_iis import build_package


def build_command(environment, *, platform_name=None):
    """Use Git for Windows' Bash, never the unrelated System32 WSL launcher."""
    if (os.name if platform_name is None else platform_name) != 'nt':
        return ['bash', './scripts/build.sh']
    git = shutil.which('git', path=environment.get('PATH'))
    if git is None:
        raise OSError('Git for Windows is required for the Bash build entrypoint.')
    directory = Path(git).resolve().parent
    # Standard installers expose cmd/git.exe or bin/git.exe; portable/MSYS
    # layouts can expose mingw64/bin/git.exe or mingw32/bin/git.exe instead.
    roots = [directory.parent]
    if directory.name.lower() == 'bin' and directory.parent.name.lower() in {'mingw64', 'mingw32', 'usr'}:
        roots.insert(0, directory.parent.parent)
    for install in roots:
        for relative in ('bin/bash.exe', 'usr/bin/bash.exe'):
            candidate = install / relative
            if candidate.is_file():
                return [str(candidate), './scripts/build.sh']
    raise OSError('The selected Git installation does not include Git Bash.')


def execute(name, root=ROOT):
    root = Path(root)
    output = root / 'build/ci'
    output.mkdir(parents=True, exist_ok=True)
    before = revision(root)
    report = {'check': name, 'status': 'FAIL', 'count': None,
              'revision': before['sha'], 'dirty': before['dirty']}
    environment = dict(os.environ, OPENAI_DISABLED='1')
    commands = {
        'build': None,
        'runtime': [sys.executable, 'runtime_dependencies.py', '--java', 'java'],
        'python': [sys.executable, 'scripts/verify_closure.py', '--unittest-report', 'build/ci/unittest-private.json'],
        'browser': ['node', 'tests/browser-suite.mjs'],
        'dashboard': ['node', 'tests/dashboard.mjs'],
    }
    try:
        if name == 'build':
            commands[name] = build_command(environment)
        elif name == 'browser':
            # The frozen browser fixture uses a stable filename. Deployment
            # builds use timestamped names; assemble this private test input
            # explicitly from the classes prepared by the preceding build.
            build_package(root, root / 'build/iis/alloy-studio-iis.zip')
        result = subprocess.run(commands[name], cwd=root, env=environment, capture_output=True,
                                text=True, encoding='utf-8', errors='replace', timeout=1800, check=False)
        if result.returncode == 0:
            report['status'] = 'PASS'
            if name == 'python':
                raw = json.loads((output / 'unittest-private.json').read_text())
                if raw.get('successful') is not True:
                    report['status'] = 'FAIL'
                report['count'] = count(raw.get('tests_run'))
            elif name in {'runtime', 'browser', 'dashboard'}:
                raw = json.loads(result.stdout.strip().splitlines()[-1])
                if raw.get('status') != 'PASS':
                    report['status'] = 'FAIL'
                report['count'] = count(raw.get('engine', {}).get('checks') if name == 'runtime' else raw.get('checks'))
        # Captured stdout/stderr are intentionally not logged or uploaded: assertion
        # failures can contain private models. Reproduce the failed check locally.
    except (OSError, ValueError, KeyError, AttributeError, TypeError, subprocess.SubprocessError):
        report['status'] = 'UNAVAILABLE'
    if revision(root) != before:
        report['dirty'] = True
    (output / (name + '.json')).write_text(json.dumps(report, sort_keys=True) + '\n', encoding='utf-8')
    (output / 'unittest-private.json').unlink(missing_ok=True)
    print(json.dumps(report, sort_keys=True))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('check', choices=CHECKS)
    raise SystemExit(execute(parser.parse_args().check))
