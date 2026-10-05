#!/usr/bin/env python3
"""Run an installed, exactly pinned Lean toolchain without a network interface.

This entrypoint never invokes elan and never installs a dependency. The namespace
boundary is established before Lean, Lake, or any compiler process is started.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def installed_toolchain(root=ROOT):
    root = Path(root)
    pin = (root / 'formal/lean-toolchain').read_text(encoding='utf-8').strip()
    if not re.fullmatch(r'leanprover/lean4:v\d+\.\d+\.\d+', pin):
        raise ValueError('An exact stable Lean version is required')
    if (root / 'lean-toolchain').read_text(encoding='utf-8').strip() != pin:
        raise ValueError('The root and formal Lean pins disagree')
    elan_root = Path(os.environ.get('ELAN_HOME', Path.home() / '.elan'))
    directory = elan_root / 'toolchains' / pin.replace('/', '--').replace(':', '---')
    if not all((directory / 'bin' / name).is_file() for name in ('lean', 'lake', 'leanc')):
        raise FileNotFoundError('Install the pinned toolchain before entering the offline proof process')
    return pin, directory.resolve()


def isolated_command(command):
    unshare = shutil.which('unshare')
    if sys.platform != 'linux' or not unshare:
        raise RuntimeError('Offline proof execution requires Linux network namespaces')
    return [unshare, '--user', '--map-root-user', '--net', '--', *command]


def clean_environment(toolchain, lean_path=None):
    # Proof subprocesses need neither deployment credentials nor shell hooks.
    environment = {name: os.environ[name] for name in ('LANG', 'LC_ALL', 'TZ', 'TMPDIR')
                   if name in os.environ}
    environment['PATH'] = str(toolchain / 'bin') + os.pathsep + os.defpath
    environment['OPENAI_DISABLED'] = '1'
    if lean_path is not None:
        environment['LEAN_PATH'] = str(Path(lean_path).resolve())
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tool', choices=('lean', 'lake', 'leanc'), default='lean')
    parser.add_argument('--cwd', type=Path, default=ROOT / 'formal')
    parser.add_argument('--lean-path', type=Path)
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    try:
        _, toolchain = installed_toolchain()
        arguments = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
        command = isolated_command([str(toolchain / 'bin' / args.tool), *arguments])
        result = subprocess.run(command, cwd=args.cwd,
                                env=clean_environment(toolchain, args.lean_path), check=False)
        return result.returncode
    except (OSError, ValueError, RuntimeError) as error:
        print(json.dumps({'status': 'INFRASTRUCTURE_FAILURE', 'reason': str(error)}), file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
