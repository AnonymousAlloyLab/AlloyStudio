#!/usr/bin/env python3
"""Compile a complete Java 17 engine into a fresh, safely promoted class tree."""
import argparse
import os
from pathlib import Path
import shutil
import stat
import struct
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from runtime_dependencies import JAR_FILES, REQUIRED_CLASSES, check_runtime

ENTRY_POINTS = ('LiveFeedback.java', 'EngineSelfTest.java', 'BehaviorFeedback.java')
JAVA_ENVIRONMENT = frozenset(('CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                              'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS'))
PROTECTED_DIRECTORIES = frozenset(('vendor', 'web', 'scripts', 'tests', 'deploy', 'docs',
                                  'closure', 'exercises', 'secrets', '.git'))


class BuildError(RuntimeError):
    """A sanitized build failure; compiler streams and private paths are excluded."""


def _linked(path):
    """Reject symbolic links and Windows junction/reparse-point output entries."""
    metadata = path.lstat()
    return (stat.S_ISLNK(metadata.st_mode)
            or bool(getattr(metadata, 'st_file_attributes', 0)
                    & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0x400)))


def _output_path(root, output):
    candidate = Path(output) if output is not None else root / 'build/engine/classes'
    if not candidate.is_absolute():
        candidate = root / candidate
    # Resolve harmless system aliases in parents (for example macOS /var), but
    # never accept a linked output directory itself. Apply all source protection
    # to the resolved target so an alias cannot bypass it.
    try:
        linked = _linked(candidate)
    except FileNotFoundError:
        linked = False
    if linked:
        raise BuildError('The class output must not be a symbolic link or junction.')
    candidate = candidate.resolve()
    if candidate == root or candidate in root.parents or candidate.parent == candidate:
        raise BuildError('The class output must not replace the project or an ancestor directory.')
    try:
        relative = candidate.relative_to(root)
    except ValueError:
        return candidate
    parts = relative.parts
    protected = (parts[0] in PROTECTED_DIRECTORIES or parts[0].startswith('.')
                 or parts == ('build',)
                 or parts[0] == 'engine' and not (len(parts) >= 3 and parts[1] == 'build'))
    if protected:
        raise BuildError('The class output must not replace source, configuration, data or project directories.')
    return candidate


def _class_tree(path, *, compiled=False):
    """Preexisting output is disposable only if it consists solely of class files."""
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        if compiled:
            raise BuildError('The compiler did not produce a class directory.')
        return
    if _linked(path) or not stat.S_ISDIR(metadata.st_mode):
        raise BuildError('The class output must be a regular directory, not a file or link.')
    def fail_walk(error):
        raise error
    for directory, directories, files in os.walk(path, followlinks=False, onerror=fail_walk):
        directory = Path(directory)
        for name in directories:
            child = directory / name
            if _linked(child) or not child.is_dir():
                raise BuildError('The class output contains a link or unsupported directory entry.')
        for name in files:
            child = directory / name
            metadata = child.lstat()
            if _linked(child) or not stat.S_ISREG(metadata.st_mode) or child.suffix != '.class':
                raise BuildError('The class output contains unrelated files or links; choose a dedicated class directory.')
            if compiled:
                with child.open('rb') as stream:
                    header = stream.read(8)
                if (len(header) != 8 or header[:4] != b'\xca\xfe\xba\xbe'
                        or struct.unpack('>HH', header[4:8]) != (0, 61)):
                    raise BuildError('Compilation produced invalid or non-Java-17 class headers.')
    if compiled and any(not (path / name).is_file() for name in REQUIRED_CLASSES):
        raise BuildError('Compilation did not produce every required engine class.')


def _promote(staging, output):
    """Preserve the old tree until the complete staged tree has been validated."""
    _class_tree(output)
    backup = None
    if output.exists():
        backup = Path(tempfile.mkdtemp(prefix='.' + output.name + '.backup-', dir=output.parent))
        backup.rmdir()
        output.replace(backup)
    try:
        staging.replace(output)
    except OSError as error:
        if backup is not None:
            try:
                backup.replace(output)
            except OSError:
                raise BuildError('Class publication failed. Previous classes remain in a sibling backup directory; restore them before retrying.') from None
        raise BuildError('Class publication failed; the previous class tree was preserved.') from None
    if backup is not None:
        try:
            # A concurrently introduced unrelated file is never swept up during
            # backup removal. Retaining a backup is safer than deleting user data.
            _class_tree(backup)
            shutil.rmtree(backup)
        except (BuildError, OSError):
            pass


def compile_engine(root, *, output=None, compiler='javac'):
    """Compile fresh classes, replacing old output only after complete validation.

    Relative outputs are resolved against root. Failures do not update an
    existing class tree, and this helper never opens or modifies a package ZIP.
    """
    staging = None
    try:
        root = Path(root).resolve(strict=True)
        if not root.is_dir():
            raise BuildError('The source project directory is missing.')
        output = _output_path(root, output)
        _class_tree(output)
        report = check_runtime(root, require_classes=False)
        if report.get('status') != 'PASS':
            raise BuildError('Bundled Java dependencies are missing, changed or unsafe. Restore vendor/acgn/lib and vendor/acgn/snapshot.json from the complete distribution.')
        entries = [root / 'engine/src/live' / name for name in ENTRY_POINTS]
        if any(not path.is_file() or _linked(path) for path in entries):
            raise BuildError('Required Java engine sources are missing or unsafe. Restore the complete source checkout.')
        output.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix='.' + output.name + '.compile-', dir=output.parent))
        classpath = os.pathsep.join(str(root / 'vendor/acgn/lib' / name) for name in JAR_FILES)
        sourcepath = os.pathsep.join(str(root / name) for name in ('engine/src', 'vendor/acgn/src'))
        command = [str(compiler), '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source',
                   '-cp', classpath, '-sourcepath', sourcepath, '-d', str(staging),
                   *(str(path) for path in entries)]
        environment = {name: value for name, value in os.environ.items()
                       if name.upper() not in JAVA_ENVIRONMENT}
        try:
            completed = subprocess.run(command, cwd=root, env=environment, capture_output=True,
                                       timeout=180, check=False)
        except subprocess.TimeoutExpired:
            raise BuildError('Java compilation exceeded 180 seconds. Previous classes were preserved.') from None
        except OSError:
            raise BuildError('The Java compiler could not be started. Install or select a JDK 17 or newer.') from None
        if completed.returncode != 0:
            raise BuildError('Java compilation failed (exit code ' + str(completed.returncode)
                             + '). Check the Java sources and selected JDK 17+; previous classes were preserved.')
        _class_tree(staging, compiled=True)
        _promote(staging, output)
        return output
    except BuildError:
        raise
    except (OSError, ValueError, TypeError, RuntimeError):
        raise BuildError('The engine build could not safely prepare or publish its output. Check source files and directory access; previous classes were preserved.') from None
    finally:
        if staging is not None and staging.exists():
            try:
                shutil.rmtree(staging)
            except OSError:
                pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='Complete source project directory')
    parser.add_argument('--output', type=Path, help='Dedicated class directory; relative paths use --root')
    parser.add_argument('--javac', default='javac', help='JDK 17+ Java compiler executable')
    args = parser.parse_args(argv)
    try:
        output = compile_engine(args.root, output=args.output, compiler=args.javac)
    except BuildError as error:
        print('Engine build failed: ' + str(error), file=sys.stderr)
        return 1
    print('Built fresh Java 17 engine classes in ' + str(output))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
