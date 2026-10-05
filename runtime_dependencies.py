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
import tempfile
import threading
import time
import itertools
import shutil
import sys

if __name__ == '__main__':
    # The relocated checker runs with -I, which excludes the script directory.
    # Resolve only this bundled sibling dependency, never ambient PYTHONPATH.
    sys.path.insert(0, str(Path(__file__).resolve().parent))

from traffic_limits import validated_int, validated_seconds


ROOT = Path(__file__).resolve().parent
JAVA_ENVIRONMENT_OPTIONS = frozenset((
    'CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS',
))
JAR_FILES = (
    'AlloyASG-Release.jar', 'AlloyASG.jar', 'AlloyParser.jar', 'alloy.jar',
    'commons-cli-1.4.jar', 'json-java.jar', 'slf4j-simple-1.7.36.jar',
)
REQUIRED_CLASSES = (
    'live/EngineWorker.class', 'live/WorkerJson.class', 'live/WorkerSafety.class', 'live/WorkerSafety$PoisonedWorker.class',
    'live/UploadInspector.class', 'live/UploadInspector$Declaration.class',
    'live/ExerciseValidator.class', 'live/ExerciseValidator$Rejected.class',
    'live/BridgePolicies.class', 'live/VerifiedPoolSelection.class', 'live/VerifiedPoolSelection$Result.class',
    'live/LiveFeedback$Candidate.class', 'live/AstFeedback$Candidate.class',
    'live/LiveFeedback.class', 'live/EngineSelfTest.class', 'live/BehaviorFeedback.class',
    'live/SourceLocator.class', 'live/SourceLocator$Range.class', 'live/SourceLocator$1.class',
    'live/CanonicalLocator.class', 'live/CanonicalLocator$Token.class', 'live/CanonicalLocator$Group.class',
    'live/CanonicalLocator$Span.class', 'live/CanonicalLocator$FormIndex.class', 'live/CanonicalLocator$1.class',
    'is/fivefivefive/CanDis/LiveTrace.class',
    'is/fivefivefive/CanDis/WorkBudget.class', 'is/fivefivefive/CanDis/WorkBudget$Exhausted.class',
    'is/fivefivefive/CanDis/WorkBudget$State.class',
    'is/fivefivefive/CanDis/core/EGraphNode$SourceOrigin.class',
    'is/fivefivefive/ACGN/visitor/MASGVisitor$1.class',
    'is/fivefivefive/ACGN/visitor/MASGVisitor$2.class',
    'live/AstFeedback.class', 'live/AstFeedback$Locations.class',
    'is/fivefivefive/CanDis/RawAstTrace.class', 'is/fivefivefive/CanDis/RawAstTrace$1.class',
    'is/fivefivefive/CanDis/RawAstTrace$Edit.class', 'is/fivefivefive/CanDis/RawAstTrace$Index.class',
    'is/fivefivefive/CanDis/RawAstTrace$Mutable.class', 'is/fivefivefive/CanDis/RawAstTrace$Prepared.class',
    'is/fivefivefive/CanDis/RawAstTrace$PrivateEdit.class', 'is/fivefivefive/CanDis/RawAstTrace$Result.class',
    'is/fivefivefive/CanDis/RawAstTrace$Solver.class', 'is/fivefivefive/CanDis/RawAstTrace$Tree.class',
    'is/fivefivefive/CanDis/DatasetConventions.class',
    'is/fivefivefive/CanDis/core/OrderedTreeEditDistance.class',
    'is/fivefivefive/CanDis/core/OrderedTreeEditDistance$Adapter.class',
    'is/fivefivefive/CanDis/core/OrderedTreeEditDistance$IndexedTree.class',
)
ENGINE_CHECKS = 378


def runtime_classpath(root):
    """Fix dependency order across operating systems; ignore ambient CLASSPATH."""
    root = Path(root).resolve()
    return os.pathsep.join(str(path) for path in (
        root / 'build/engine/classes', *(root / 'vendor/acgn/lib' / name for name in JAR_FILES)))


JAVA_ENVIRONMENT_ALLOWLIST = frozenset((
    'PATH', 'SYSTEMROOT', 'WINDIR', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ',
))


def clean_java_environment(env=None):
    """Construct the runtime environment; credentials never enter JVM env."""
    source = os.environ if env is None else env
    return {name: value for name, value in source.items()
            if name.upper() in JAVA_ENVIRONMENT_ALLOWLIST}


class ProcessBudget:
    """Count starting/live/stopping JVMs until their owner has reaped them."""
    def __init__(self, feedback=2, behavior=1, admin=1):
        feedback = validated_int(feedback, maximum=2)
        behavior = validated_int(behavior, maximum=1)
        admin = validated_int(admin, maximum=1)
        self.limits = {'feedback': feedback, 'behavior': behavior, 'admin': admin}
        self.total_limit = sum(self.limits.values())
        self.condition = threading.Condition()
        self.owners = {}
        self.serial = itertools.count(1)
        self.high_water = 0

    def reserve(self, lane, timeout):
        timeout = validated_seconds(timeout, minimum_zero=True)
        if lane not in self.limits:
            raise ValueError('Unknown engine capacity lane.')
        deadline = time.monotonic() + max(0, timeout)
        with self.condition:
            while (len(self.owners) >= self.total_limit
                   or sum(value == lane for value in self.owners.values()) >= self.limits[lane]):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired('engine capacity', timeout)
                self.condition.wait(remaining)
            owner = next(self.serial)
            self.owners[owner] = lane
            self.high_water = max(self.high_water, len(self.owners))
            return owner

    def release_reaped(self, owner):
        with self.condition:
            self.owners.pop(owner, None)
            self.condition.notify_all()

    def stats(self):
        with self.condition:
            return {'reserved': len(self.owners), 'limit': self.total_limit,
                    'highWater': self.high_water,
                    'lanes': {lane: sum(value == lane for value in self.owners.values())
                              for lane in self.limits}}


PROCESS_BUDGET = ProcessBudget()


def engine_temp_root(root=ROOT):
    """Private application-owned scratch; never inherit the system temp root."""
    root = Path(root).resolve()
    configured = os.environ.get('ALLOY_ENGINE_TMP_ROOT')
    directory = Path(configured) if configured else Path(root) / 'build/runtime/tmp'
    if not directory.is_absolute():
        directory = Path(root).resolve() / directory
    # This path is operator configuration, never a model/request parameter.
    for part in (directory, *directory.parents):
        if part.is_symlink() or (part.exists() and getattr(part.lstat(), 'st_file_attributes', 0) & 0x400):
            raise OSError('Linked engine scratch directory is not allowed')
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    return directory


_ONESHOT_CONDITION = threading.Condition()
_ONESHOT_ROOTS = {}


def _root_key(root):
    return str(Path(root).resolve())


def open_engine_admission(root=ROOT, *, java_processors=None):
    """A newly initialized backend may admit one-shot calls after prior drain."""
    if java_processors is not None:
        java_processors = validated_int(java_processors, maximum=2)
    key = _root_key(root)
    with _ONESHOT_CONDITION:
        state = _ONESHOT_ROOTS.setdefault(key, {'closed': False, 'active': 0})
        if state['closed'] and (state['active'] or state.get('unreaped', 0)):
            raise OSError('Previous engine calls have not drained')
        if java_processors is not None:
            if (state['active'] or state.get('unreaped', 0)) and state.get('java_processors') != java_processors:
                raise OSError('Active engine configuration cannot change')
            state['java_processors'] = java_processors
        state['closed'] = False


def close_engine_admission(root=ROOT):
    """Close before stopping pool lanes, including callers waiting for a slot."""
    key = _root_key(root)
    with _ONESHOT_CONDITION:
        _ONESHOT_ROOTS.setdefault(key, {'closed': False, 'active': 0})['closed'] = True
        _ONESHOT_CONDITION.notify_all()


def wait_for_oneshots(root=ROOT, timeout=65):
    """Wait a fixed shutdown budget; never claim an active child is reaped."""
    timeout = validated_seconds(timeout, minimum_zero=True)
    key = _root_key(root)
    deadline = time.monotonic() + max(0, timeout)
    with _ONESHOT_CONDITION:
        while (_ONESHOT_ROOTS.get(key, {}).get('active', 0)
               or _ONESHOT_ROOTS.get(key, {}).get('unreaped', 0)):
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            _ONESHOT_CONDITION.wait(remaining)
        return True


def run_engine(command, *, root=ROOT, lane="admin", **options):
    """One-shot evaluation shares its explicit lane and participates in drain."""
    options['timeout'] = validated_seconds(60 if options.get('timeout') is None else options['timeout'])
    key = _root_key(root)
    with _ONESHOT_CONDITION:
        state = _ONESHOT_ROOTS.setdefault(key, {'closed': False, 'active': 0})
        if state['closed']:
            raise OSError('Engine admission is closed')
        state['active'] += 1
    try:
        return _run_engine_registered(command, root=root, lane=lane, state=state, **options)
    finally:
        with _ONESHOT_CONDITION:
            state['active'] -= 1
            _ONESHOT_CONDITION.notify_all()


def _run_engine_registered(command, *, root, lane, state, **options):
    started = time.monotonic()
    timeout = options.get('timeout', 60)
    if timeout is None:
        timeout = 60
    owner = PROCESS_BUDGET.reserve(lane, timeout)
    directory = None
    acknowledged = False
    try:
        with _ONESHOT_CONDITION:
            if state['closed']:
                raise OSError('Engine admission is closed')
        directory = tempfile.mkdtemp(prefix='engine-', dir=engine_temp_root(root))
        arguments = list(command[1:])
        processors = state.get('java_processors')
        if processors is not None:
            arguments = [arg for arg in arguments if not arg.startswith('-XX:ActiveProcessorCount=')]
            arguments.insert(0, '-XX:ActiveProcessorCount=' + str(processors))
        isolated = [command[0], '-Djava.io.tmpdir=' + directory, *arguments]
        options['env'] = clean_java_environment(options.get('env'))
        options['timeout'] = max(0.001, timeout - (time.monotonic() - started))
        try:
            return subprocess.run(isolated, **options)
        finally:
            # subprocess.run's context waits after ordinary exception/timeout.
            # KeyboardInterrupt alone permits Python's short wait to expire;
            # retain its reservation rather than claiming the child is reaped.
            import sys
            acknowledged = not isinstance(sys.exc_info()[1], KeyboardInterrupt)
    finally:
        if directory is None or acknowledged:
            if directory is not None:
                shutil.rmtree(directory, ignore_errors=True)
            PROCESS_BUDGET.release_reaped(owner)
        else:
            with _ONESHOT_CONDITION:
                state['unreaped'] = state.get('unreaped', 0) + 1


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
        environment = clean_java_environment()
        command = [str(java), '-Dfile.encoding=UTF-8', '-Xmx256m', '-XX:ActiveProcessorCount=2',
                   '-cp', runtime_classpath(root), 'live.EngineSelfTest']
        try:
            completed = run_engine(command, root=root, cwd=root, env=environment, capture_output=True,
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
