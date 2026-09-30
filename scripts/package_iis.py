#!/usr/bin/env python3
"""Build the private, reproducible IIS distribution from an explicit allowlist.

The command line rebuilds the Java engine before packaging. The archive is an
administrator deployment artifact: only its wwwroot directory is public.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import sqlite3
import stat
import struct
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from runtime_dependencies import JAR_FILES, REQUIRED_CLASSES

WEB_FILES = ('index.html', 'app.js', 'instance-graph.js', 'styles.css', 'dashboard/index.html',
             'dashboard/app.js', 'dashboard/styles.css', 'dashboard/data.json',
             'admin/index.html', 'admin/app.js', 'admin/styles.css')
DEPLOY_FILES = (
    'web.config', 'Common.ps1', 'Manage-AlloyStudio.ps1', 'Set-OpenAIKey.ps1',
    'Start-AlloyStudio.ps1', 'run_backend.py', 'Test-IisDeployment.ps1',
    'Test-ApiConnection.ps1', 'test_api_connection.py', 'README.md',
)
RUNTIME_HELPERS = ('import_correct_pools.py', 'import_exercises.py', 'exercise_descriptions.json',
                   'prepare_private_data.py', 'manage_exercises.py', 'configure_admin.py')
ADMIN_MODULES = ('admin_auth.py', 'admin_upload.py', 'admin_luna.py', 'admin_service.py')
STORE_FILES = ('exercise_store.py', 'exercise_sql.py', 'sql/schema.json',
               'sql/queries.json', 'sql/compiled-queries.json', 'vendor/sqlean/provenance.json')
ADMIN_FILES = ('docs/private-exercises.md', 'docs/sqlite-security-spec.md',
               'docs/admin-security-spec.md', 'docs/admin-setup.md', 'examples/private-exercise.json')
TOKEN_PATTERN = re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}')
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class PackageError(ValueError):
    """An incomplete or unsafe deployment input was refused."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def checked_deployment_path(path: Path) -> Path:
    from scripts.prepare_private_data import checked_path
    try:
        return checked_path(path)
    except ValueError as error:
        raise PackageError('Linked deployment input or output paths are not allowed.') from error


def read_source(root: Path, relative: str) -> bytes:
    """Read a regular source file, refusing links and path escapes."""
    parts = PurePosixPath(relative).parts
    if not parts or relative.startswith('/') or any(part in ('..', '.') for part in parts):
        raise PackageError('Invalid source path in package inventory.')
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink() or (path.exists() and getattr(path.lstat(), 'st_file_attributes', 0) & 0x400):
            raise PackageError(f'Symlink deployment input is not allowed: {relative}')
    if not path.is_file():
        if relative == 'exercises/exercises.sqlite3':
            raise PackageError('Bundled exercise database is missing. Restore exercises/exercises.sqlite3 from Git, or explicitly prepare a legacy corpus with scripts/prepare_private_data.py.')
        raise PackageError(f'Missing deployment input: {relative}; build the engine and import the catalogue first.')
    data = path.read_bytes()
    if TOKEN_PATTERN.search(data):
        raise PackageError(f'Credential-shaped value in deployment input: {relative}')
    return data


def parse_json(data: bytes, label: str) -> dict:
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field.')
            result[key] = value
        return result

    try:
        result = json.loads(data.decode('utf-8'), object_pairs_hook=unique_fields)
    except (UnicodeError, ValueError) as exc:
        raise PackageError(f'Invalid UTF-8 JSON in {label}.') from exc
    if not isinstance(result, dict):
        raise PackageError(f'{label} must be a JSON object.')
    return result


def version_portal_module(entries: dict[str, bytes]) -> dict[str, str]:
    """Version the graph import before hashing its importing application.

    The application hash must change when this dependency changes, even if
    app.js itself has not changed. Relative imports keep IIS subpaths working.
    """
    name = 'instance-graph.js'
    version = digest(entries['wwwroot/' + name])
    application = entries['wwwroot/app.js']
    pattern = re.compile(rb'(\bfrom\s+)([\'\"])(\./instance-graph\.js)\2')
    if len(pattern.findall(application)) != 1:
        raise PackageError('Expected exactly one relative instance-graph.js import in web/app.js.')
    entries['wwwroot/app.js'] = pattern.sub(
        lambda match: match[1] + match[2] + match[3] + b'?v='
        + version.encode('ascii') + match[2], application)
    return {name: version}


def version_public_assets(entries: dict[str, bytes], directory: str = '') -> dict[str, str]:
    """Bind the packaged HTML's asset URLs to the exact JS/CSS payload bytes.

    ZIP timestamps are deliberately fixed for reproducibility. They cannot
    identify a new release to a browser, IIS, or a CDN; content hashes can.
    Keep relative URLs so virtual IIS applications continue to work.
    """
    html = entries['wwwroot/' + directory + 'index.html']
    versions = {}
    for attribute, name in (('src', 'app.js'), ('href', 'styles.css')):
        version = digest(entries['wwwroot/' + directory + name])
        pattern = re.compile(rb'(\b' + attribute.encode() + rb'\s*=\s*)([\'\"])(\./'
                             + re.escape(name.encode()) + rb')\2')
        if len(pattern.findall(html)) != 1:
            raise PackageError(f'Expected exactly one relative {name} reference in web/{directory}index.html.')
        html = pattern.sub(lambda match: match[1] + match[2] + match[3]
                           + b'?v=' + version.encode('ascii') + match[2], html)
        versions[directory + name] = version
    entries['wwwroot/' + directory + 'index.html'] = html
    return versions


def collect_files(root: Path, *, classes_root: Path | None = None) -> dict[str, bytes]:
    root = checked_deployment_path(root).resolve(strict=True)
    entries = {f'wwwroot/{name}': read_source(root, f'web/{name}') for name in WEB_FILES}
    public_asset_versions = version_portal_module(entries)
    public_asset_versions.update(version_public_assets(entries))
    public_asset_versions.update(version_public_assets(entries, 'dashboard/'))
    public_asset_versions.update(version_public_assets(entries, 'admin/'))
    for name in DEPLOY_FILES:
        entries[f'deploy/iis/{name}'] = read_source(root, f'deploy/iis/{name}')
    entries['wwwroot/web.config'] = entries['deploy/iis/web.config']
    entries['LICENSE'] = read_source(root, 'LICENSE')
    for name in ('server.py', 'luna.py', 'runtime_dependencies.py'):
        entries[f'backend/{name}'] = read_source(root, name)
    for name in (*STORE_FILES, *ADMIN_FILES, *ADMIN_MODULES):
        entries[f'backend/{name}'] = read_source(root, name)
    from exercise_sql import ARTIFACT_HASHES
    for name, expected in ARTIFACT_HASHES.items():
        if digest(entries['backend/' + name]) != expected:
            raise PackageError('Exercise query artifacts differ from the registered parser output.')
    example_bytes = read_source(root, 'openai.example.json')
    if parse_json(example_bytes, 'OpenAI configuration template') != {'api_key': ''}:
        raise PackageError('The OpenAI configuration template must contain only an empty api_key.')
    entries['backend/openai.example.json'] = example_bytes
    for name in RUNTIME_HELPERS:
        entries[f'backend/scripts/{name}'] = read_source(root, f'scripts/{name}')

    from exercise_store import backup_store, ensure_store
    try:
        ensure_store(root)
        # macOS exposes its system temp directory through /var -> /private/var.
        # Resolve that OS-selected parent before allocating our own snapshot;
        # caller-supplied database and deployment paths still reject all links.
        temporary_parent = Path(tempfile.gettempdir()).resolve(strict=True)
        with tempfile.TemporaryDirectory(prefix='alloy-package-snapshot-', dir=temporary_parent) as directory:
            snapshot_path = Path(directory) / 'exercises.sqlite3'
            store = backup_store(root, snapshot_path)
            database_bytes = snapshot_path.read_bytes()
        if TOKEN_PATTERN.search(database_bytes):
            raise PackageError('Credential-shaped value in deployment database.')
    except (OSError, ValueError, sqlite3.Error, KeyError, TypeError, AttributeError, RecursionError) as exc:
        raise PackageError('Exercise database validation or consistent backup failed; existing data and previous archives were preserved.') from exc
    entries['backend/exercises/exercises.sqlite3'] = database_bytes

    snapshot_bytes = read_source(root, 'vendor/acgn/snapshot.json')
    snapshot = parse_json(snapshot_bytes, 'ACGN snapshot')
    if not isinstance(snapshot.get('commit'), str) or not re.fullmatch(r'[0-9a-f]{40}', snapshot['commit']):
        raise PackageError('ACGN snapshot must identify a source commit.')
    provenance = snapshot.get('files')
    if not isinstance(provenance, list):
        raise PackageError('ACGN snapshot must inventory dependency files.')
    dependencies = {}
    allowed_dependencies = {'LICENSE', *(f'lib/{name}' for name in JAR_FILES)}
    for entry in provenance:
        if not isinstance(entry, dict) or not isinstance(entry.get('path'), str):
            raise PackageError('Invalid ACGN snapshot file entry.')
        name = entry['path']
        if name in allowed_dependencies:
            if name in dependencies or not re.fullmatch(r'[0-9a-f]{64}', str(entry.get('sha256', ''))):
                raise PackageError('Invalid or duplicate ACGN dependency hash.')
            dependencies[name] = entry['sha256']
    if set(dependencies) != allowed_dependencies:
        raise PackageError('ACGN snapshot is missing licensed runtime dependencies.')
    for name, expected in sorted(dependencies.items()):
        data = read_source(root, f'vendor/acgn/{name}')
        if digest(data) != expected:
            raise PackageError(f'ACGN dependency differs from its snapshot: {name}')
        entries[f'backend/vendor/acgn/{name}'] = data
    entries['backend/vendor/acgn/snapshot.json'] = snapshot_bytes

    if classes_root is None:
        classes_root = root / 'build/engine/classes'
        class_input_root, class_prefix = root, 'build/engine/classes/'
    else:
        classes_root = Path(classes_root).absolute()
        if classes_root.is_symlink():
            raise PackageError('Symlink class directory is not allowed.')
        class_input_root, class_prefix = classes_root, ''
    for name in REQUIRED_CLASSES:
        read_source(class_input_root, class_prefix + name)
    for path in sorted(classes_root.rglob('*.class')):
        relative = path.relative_to(classes_root).as_posix()
        data = read_source(class_input_root, class_prefix + relative)
        if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe' or not (45 <= struct.unpack('>H', data[6:8])[0] <= 61):
            raise PackageError(f'Expected a Java 17 compatible compiled class: {relative}')
        entries[f'backend/build/engine/classes/{relative}'] = data

    manifest = {
        'schemaVersion': 1,
        'application': 'Alloy Studio',
        'distribution': 'IIS 10',
        'privateArchive': True,
        'publicDirectory': 'wwwroot',
        'publicAssetVersions': public_asset_versions,
        'requirements': {'python': '3.10+', 'java': '17+', 'iis': '10.0'},
        'acgnCommit': snapshot['commit'],
        'exerciseCount': store.exercise_count,
        'knownCorrectPoolCount': len(store.correct_pools),
        'correctCandidateCount': store.candidate_count,
        'exerciseStorage': 'sqlite',
        'files': [{'path': name, 'bytes': len(data), 'sha256': digest(data)}
                  for name, data in sorted(entries.items())],
    }
    entries['manifest.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode('utf-8')
    return entries


def atomic_private_write(path: Path, data: bytes) -> None:
    descriptor, temporary = tempfile.mkstemp(prefix=f'.{path.name}.', dir=path.parent)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
        os.replace(temporary_path, path)
    finally:
        temporary_path.unlink(missing_ok=True)


def default_archive_path(root: Path) -> Path:
    """Give each successful build a UTC-stamped name, retaining older packages."""
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-%fZ')
    directory = Path(root) / 'build/iis'
    stem = f'alloy-studio-iis-{stamp}'
    candidate = directory / (stem + '.zip')
    serial = 1
    while any(path.exists() or path.is_symlink()
              for path in (candidate, candidate.with_suffix('.zip.sha256'))):
        serial += 1
        candidate = directory / f'{stem}-{serial}.zip'
    return candidate


def build_package(root: Path, output: Path, *, classes_root: Path | None = None) -> dict:
    """Package a prepared class tree atomically; the CLI compiles it first."""
    root = checked_deployment_path(root).resolve(strict=True)
    output = checked_deployment_path(output)
    checked_deployment_path(output.with_suffix('.zip.sha256'))
    if output.suffix.lower() != '.zip':
        raise PackageError('Deployment archive output must end in .zip.')
    for public in (root / 'web', root / 'wwwroot'):
        if output.resolve().is_relative_to(public.resolve()):
            raise PackageError('The private deployment archive must be outside the public directory.')
    entries = collect_files(root, classes_root=classes_root)
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, temporary = tempfile.mkstemp(prefix=f'.{output.name}.', dir=output.parent)
    temporary_path = Path(temporary)
    try:
        with os.fdopen(descriptor, 'w+b') as stream:
            # STORED avoids platform/zlib-dependent compression bytes; the JARs
            # are already compressed and the archive stays modest in size.
            with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED) as archive:
                for name, data in sorted(entries.items()):
                    info = zipfile.ZipInfo(name, ZIP_TIME)
                    info.create_system = 3
                    info.external_attr = (stat.S_IFREG | 0o600) << 16
                    archive.writestr(info, data)
        checksum = digest(temporary_path.read_bytes())
        checksum_bytes = f'{checksum}  {output.name}\n'.encode('utf-8')
        os.replace(temporary_path, output)
        atomic_private_write(output.with_suffix('.zip.sha256'), checksum_bytes)
    finally:
        temporary_path.unlink(missing_ok=True)
    return {'archive': str(output), 'sha256': checksum, 'files': len(entries),
            'bytes': output.stat().st_size, 'privateArchive': True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT, help='Project source root (defaults to this checkout).')
    parser.add_argument('--output', type=Path,
                        help='Explicit private ZIP destination (default: a new UTC-timestamped ZIP in build/iis).')
    parser.add_argument('--javac', default='javac', help='JDK 17+ compiler executable used for the fresh engine build.')
    parser.add_argument('--classes-output', type=Path,
                        help='Compiled class directory; relative paths start at --source (default: build/engine/classes).')
    args = parser.parse_args()
    from scripts.build_engine import BuildError, compile_engine
    try:
        classes = compile_engine(args.source, output=args.classes_output, compiler=args.javac)
        result = build_package(args.source, args.output or default_archive_path(args.source),
                               classes_root=classes)
    except (OSError, PackageError, BuildError) as exc:
        parser.exit(1, f'Package refused: {exc} The previous archive, if any, has not been refreshed.\n')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
