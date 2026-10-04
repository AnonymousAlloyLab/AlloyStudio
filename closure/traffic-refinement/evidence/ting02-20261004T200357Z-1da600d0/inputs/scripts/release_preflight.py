#!/usr/bin/env python3
"""Check source-release version consistency without publishing deployment inputs."""
import json
import os
from pathlib import Path
import re
import sys


def validate(root, ref):
    package = json.loads((Path(root) / 'package.json').read_text())
    lock = json.loads((Path(root) / 'package-lock.json').read_text())
    version = package.get('version')
    if not isinstance(version, str) or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?', version):
        return False
    if lock.get('version') != version or lock.get('packages', {}).get('', {}).get('version') != version:
        return False
    tags = {'refs/tags/v' + version}
    # npm keeps a SemVer-compatible prerelease. The documented maintenance-tag
    # spelling is accepted only for this exact, positive fN-alpha form.
    fix = re.fullmatch(r'(\d+\.\d+\.\d+)-f([1-9]\d*)-alpha', version)
    if fix:
        tags.add('refs/tags/v' + fix.group(1) + '.f' + fix.group(2) + '-alpha')
    return not ref.startswith('refs/tags/') or ref in tags


if __name__ == '__main__':
    try:
        passed = validate(Path(__file__).resolve().parents[1], os.environ.get('GITHUB_REF', ''))
    except (OSError, ValueError, TypeError, AttributeError):
        passed = False
    print('Source release version check: ' + ('PASS' if passed else 'FAIL'))
    sys.exit(0 if passed else 1)
