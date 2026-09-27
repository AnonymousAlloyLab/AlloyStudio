#!/usr/bin/env python3
"""Prepare and run a cloned portal on Linux/macOS using only Python and a JDK."""
from __future__ import annotations

import argparse
import os
from pathlib import Path
import platform
import re
import shlex
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from runtime_dependencies import ENGINE_CHECKS, JAR_FILES, check_runtime
from scripts.prepare_private_data import PreparationError, prepare

JAVA_ENV = frozenset(('CLASSPATH', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                      'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS'))


class SetupError(ValueError):
    pass


def clean_environment(environ=None):
    return {key: value for key, value in (os.environ if environ is None else environ).items()
            if key not in JAVA_ENV}


def absolute_path(value):
    # Resolve relative options in the caller's directory, before any child chdir.
    return Path(value).expanduser().absolute()


def java_version(executable, compiler, environment):
    try:
        result = subprocess.run([str(executable), '-version' if compiler else '--version'],
                                capture_output=True, text=True, encoding='utf-8',
                                env=environment, timeout=10, check=False)
    except (OSError, UnicodeError, subprocess.TimeoutExpired) as error:
        raise SetupError('The selected JDK cannot run. Install a JDK 17+ for this OS/CPU and set JAVA_HOME.') from error
    match = re.search(r'\b(?:openjdk|java|javac)\s+(?:version\s+)?"?(\d+)(?:\.(\d+))?',
                      result.stdout + '\n' + result.stderr)
    if result.returncode or not match:
        raise SetupError('The selected JDK did not report a usable version. Install JDK 17+ and set JAVA_HOME.')
    major = int(match[2] or 0) if match[1] == '1' else int(match[1])
    if major < 17:
        raise SetupError(f'JDK 17+ is required; the selected {"compiler" if compiler else "runtime"} is version {major}. Set JAVA_HOME to a newer JDK.')
    return major


def resolve_jdk(java_home=None, java=None, *, environ=None):
    environment = clean_environment(environ)
    selected_home = java_home or (environment.get('JAVA_HOME') if not java else None)
    if selected_home:
        directory = absolute_path(selected_home) / 'bin'
    elif java:
        executable = shutil.which(str(java), path=environment.get('PATH', os.defpath))
        if not executable:
            raise SetupError('The --java executable was not found. Supply a JDK 17+ executable or --java-home.')
        directory = Path(executable).resolve().parent
    else:
        directory = None
        if platform.system() == 'Darwin':
            try:
                result = subprocess.run(['/usr/libexec/java_home', '-v', '17+'],
                                        capture_output=True, text=True, encoding='utf-8',
                                        env=environment, timeout=10, check=False)
                if result.returncode == 0 and result.stdout.strip():
                    directory = absolute_path(result.stdout.strip()) / 'bin'
            except (OSError, UnicodeError, subprocess.TimeoutExpired):
                pass
        if directory is None:
            search_path = environment.get('PATH', os.defpath)
            search_entries = (search_path.split(os.pathsep) if platform.system() == 'Darwin'
                              else [search_path])
            for entry in search_entries:
                compiler = shutil.which('javac', path=entry or os.curdir)
                if compiler:
                    candidate = Path(compiler).resolve().parent
                    # Apple's stubs may prompt instead of running Java. Continue
                    # past them to find an unregistered JDK later on PATH.
                    if platform.system() != 'Darwin' or candidate != Path('/usr/bin'):
                        directory = candidate
                        break
        if directory is None:
            raise SetupError('No JDK was found. Install JDK 17+ (including javac), then set JAVA_HOME or use --java-home. See docs/local-setup.md.')
    suffix = '.exe' if os.name == 'nt' else ''
    runtime, compiler = directory / ('java' + suffix), directory / ('javac' + suffix)
    if not all(path.is_file() and os.access(path, os.X_OK) for path in (runtime, compiler)):
        raise SetupError('The selected JDK must contain executable bin/java and bin/javac. A JRE alone is insufficient. Set JAVA_HOME to a JDK 17+ directory.')
    runtime_major = java_version(runtime, False, environment)
    compiler_major = java_version(compiler, True, environment)
    if runtime_major != compiler_major:
        raise SetupError('java and javac report different major versions. Select one complete JDK with --java-home.')
    return runtime, compiler


def require_runtime(report):
    if report['status'] != 'PASS':
        details = ', '.join(f"{item['code']}: {item['path']}" for item in report['errors'])
        raise SetupError('Bundled engine validation failed: ' + details
                         + '. Restore the complete checkout, including vendor/acgn/lib; see docs/local-setup.md.')


def setup(root, *, source_root=None, bundle=None, java_home=None, java=None):
    root = Path(root).resolve()
    runtime, compiler = resolve_jdk(java_home, java)
    print('Checking bundled Java dependencies...', flush=True)
    require_runtime(check_runtime(root, require_classes=False))
    print('Preparing private exercise data...', flush=True)
    metadata = prepare(root, source_root, bundle=bundle)
    print(f"Private exercises: {metadata['exercises']} ({metadata['action']}).", flush=True)
    output = root / 'build/engine/classes'
    output.mkdir(parents=True, exist_ok=True)
    command = [str(compiler), '-encoding', 'UTF-8', '--release', '17', '-Xprefer:source',
               '-cp', os.pathsep.join(str(root / 'vendor/acgn/lib' / name) for name in JAR_FILES),
               '-sourcepath', os.pathsep.join(str(root / name) for name in ('engine/src', 'vendor/acgn/src')),
               '-d', str(output), str(root / 'engine/src/live/LiveFeedback.java'),
               str(root / 'engine/src/live/EngineSelfTest.java'),
               str(root / 'engine/src/live/BehaviorFeedback.java')]
    print('Building the Java engine...', flush=True)
    try:
        completed = subprocess.run(command, cwd=root, env=clean_environment(),
                                   capture_output=True, timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        raise SetupError('Java compilation could not finish. Check your JDK and write access to build/engine/classes.') from error
    if completed.returncode:
        raise SetupError('Java compilation failed. Restore the complete engine/src and vendor/acgn snapshot and use JDK 17+. The full developer build is scripts/build.sh.')
    print(f'Running {ENGINE_CHECKS} engine checks...', flush=True)
    require_runtime(check_runtime(root, java=runtime))
    print(f'Engine ready: {ENGINE_CHECKS} checks passed.', flush=True)
    return runtime


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('setup', 'run'))
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--from-bundle', type=absolute_path, help='Trusted private IIS ZIP; only exercise data are restored')
    source.add_argument('--source-root', type=absolute_path, help='Original ACGN checkout containing classified-data/')
    jdk = parser.add_mutually_exclusive_group()
    jdk.add_argument('--java-home', type=absolute_path, help='JDK 17+ directory (otherwise JAVA_HOME or automatic discovery)')
    jdk.add_argument('--java', help='Java executable from a complete JDK (compatible with the server option)')
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8080)
    parser.add_argument('--timeout', type=float, default=12)
    parser.add_argument('--workers', type=int, default=4)
    parser.add_argument('--public-origin', action='append', default=[])
    args = parser.parse_args(argv)
    if not 0 <= args.port <= 65535 or args.timeout <= 0 or args.workers < 1:
        parser.error('port must be 0..65535; timeout and workers must be positive')
    try:
        runtime = setup(ROOT, source_root=args.source_root, bundle=args.from_bundle,
                        java_home=args.java_home, java=args.java)
        if args.action == 'setup':
            start = ['./scripts/run.sh']
            if args.java_home or args.java:
                start.extend(('--java-home', str(runtime.parent.parent)))
            print(f'Setup complete. From the checkout, start with {shlex.join(start)} and open http://127.0.0.1:8080.')
            return 0
        command = [sys.executable, '-E', '-s', str(ROOT / 'server.py'),
                   '--java', str(runtime), '--host', args.host, '--port', str(args.port),
                   '--timeout', str(args.timeout), '--workers', str(args.workers)]
        for origin in args.public_origin:
            command.extend(('--public-origin', origin))
        # Replace this process: terminal signals reach the server directly.
        os.execve(sys.executable, command, clean_environment())
    except PreparationError as error:
        parser.exit(1, f'Private data preparation failed [{error.code}]: {error}\n')
    except SetupError as error:
        parser.exit(1, f'Local setup failed: {error}\n')
    except OSError:
        parser.exit(1, 'Local setup failed: Check permissions and available disk space in the checkout and input files.\n')
    except (ValueError, KeyError, TypeError, AttributeError, RecursionError):
        parser.exit(1, 'Local setup failed: Invalid private exercise data. Restore both private files from a trusted bundle or complete source corpus.\n')
    except KeyboardInterrupt:
        return 130


if __name__ == '__main__':
    raise SystemExit(main())
