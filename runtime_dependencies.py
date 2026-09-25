#!/usr/bin/env python3
"""Check a relocated backend's complete bundled Java runtime, without downloading."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess


ROOT = Path(__file__).resolve().parent
JAR_FILES = (
    'AlloyASG-Release.jar', 'AlloyASG.jar', 'AlloyParser.jar', 'alloy.jar',
    'commons-cli-1.4.jar', 'json-java.jar', 'slf4j-simple-1.7.36.jar',
)
REQUIRED_CLASSES = (
    'live/LiveFeedback.class', 'live/EngineSelfTest.class',
    'is/fivefivefive/CanDis/LiveTrace.class',
)
ENGINE_CHECKS = 372


def runtime_classpath(root):
    """Fix dependency order across operating systems; ignore ambient CLASSPATH."""
    root = Path(root).resolve()
    return os.pathsep.join(str(path) for path in (
        root / 'build/engine/classes', *(root / 'vendor/acgn/lib' / name for name in JAR_FILES)))


def _read_regular(root, relative):
    path = root
    for part in Path(relative).parts:
        path /= part
        if path.is_symlink():
            raise OSError('Symlink runtime input')
    if not path.is_file():
        raise OSError('Missing regular runtime input')
    return path.read_bytes()


def check_runtime(root, java=None, *, require_classes=True):
    """Return a credential-free report. Any absent or changed dependency fails."""
    root = Path(root).resolve()
    report = {'status': 'PASS', 'dependencies': [], 'classes': [], 'errors': [],
              'engine': {'status': 'NOT_RUN', 'checks': 0}}

    def fail(code, path):
        report['status'] = 'FAIL'
        report['errors'].append({'code': code, 'path': path})

    expected = {}
    snapshot_path = 'vendor/acgn/snapshot.json'
    try:
        snapshot = json.loads(_read_regular(root, snapshot_path))
        for entry in snapshot['files']:
            name = entry['path']
            if name in {'lib/' + jar for jar in JAR_FILES}:
                if name in expected or not re.fullmatch(r'[0-9a-f]{64}', entry['sha256']):
                    raise ValueError('Invalid dependency inventory')
                expected[name] = entry['sha256']
        if set(expected) != {'lib/' + jar for jar in JAR_FILES}:
            raise ValueError('Incomplete dependency inventory')
    except (OSError, ValueError, TypeError, KeyError, RecursionError):
        fail('INVALID_DEPENDENCY_INVENTORY', snapshot_path)

    try:
        # Some thin/source JARs are redundant at class-loading time. A successful
        # request is not evidence that the complete declared distribution exists.
        extras = {path.name for path in (root / 'vendor/acgn/lib').iterdir()
                  if path.suffix.lower() == '.jar'} - set(JAR_FILES)
        if extras:
            fail('UNEXPECTED_DEPENDENCY', 'vendor/acgn/lib')
    except OSError:
        fail('MISSING_DEPENDENCY_DIRECTORY', 'vendor/acgn/lib')
    for name in JAR_FILES:
        relative = 'vendor/acgn/lib/' + name
        item = {'name': name, 'path': relative, 'status': 'FAIL', 'sha256': None,
                'expectedSha256': expected.get('lib/' + name)}
        try:
            data = _read_regular(root, relative)
            item['sha256'] = hashlib.sha256(data).hexdigest()
            if item['sha256'] != item['expectedSha256']:
                fail('DEPENDENCY_HASH_MISMATCH', relative)
            else:
                item['status'] = 'PASS'
        except OSError:
            fail('MISSING_OR_UNSAFE_DEPENDENCY', relative)
        report['dependencies'].append(item)
    for name in REQUIRED_CLASSES if require_classes else ():
        relative = 'build/engine/classes/' + name
        item = {'path': relative, 'status': 'FAIL'}
        try:
            data = _read_regular(root, relative)
            if (len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe'
                    or not 45 <= struct.unpack('>H', data[6:8])[0] <= 61):
                fail('INVALID_COMPILED_CLASS', relative)
            else:
                item.update(status='PASS', sha256=hashlib.sha256(data).hexdigest())
        except OSError:
            fail('MISSING_OR_UNSAFE_COMPILED_CLASS', relative)
        report['classes'].append(item)

    if java is not None and report['status'] == 'PASS':
        environment = {name: value for name, value in os.environ.items()
                       if name not in {'CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS'}}
        command = [str(java), '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                   '-cp', runtime_classpath(root), 'live.EngineSelfTest']
        try:
            completed = subprocess.run(command, cwd=root, env=environment, capture_output=True,
                                       text=True, encoding='utf-8', timeout=30, check=False)
            if (completed.returncode != 0
                    or completed.stdout.strip() != f'EngineSelfTest passed ({ENGINE_CHECKS} checks)'):
                fail('ENGINE_SELF_TEST_FAILED', 'live.EngineSelfTest')
                report['engine'] = {'status': 'FAIL', 'checks': 0}
            else:
                report['engine'] = {'status': 'PASS', 'checks': ENGINE_CHECKS}
        except (OSError, UnicodeError, subprocess.TimeoutExpired):
            fail('ENGINE_SELF_TEST_UNAVAILABLE', 'live.EngineSelfTest')
            report['engine'] = {'status': 'FAIL', 'checks': 0}
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='Private backend directory')
    parser.add_argument('--java', help='Java 17+ executable; also run the compiled engine self-test')
    parser.add_argument('--dependencies-only', action='store_true',
                        help='Check JARs before building; compiled classes are not required')
    args = parser.parse_args()
    if args.java and args.dependencies_only:
        parser.error('--java cannot be combined with --dependencies-only')
    report = check_runtime(args.root, args.java, require_classes=not args.dependencies_only)
    print(json.dumps(report, sort_keys=True))
    return 0 if report['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
