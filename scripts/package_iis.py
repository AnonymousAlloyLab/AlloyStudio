#!/usr/bin/env python3
"""Build the private, reproducible IIS distribution from an explicit allowlist.

Run scripts/build.sh (or scripts/build.ps1 on Windows) first. The archive is an
administrator deployment artifact: only its wwwroot directory is public.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from runtime_dependencies import JAR_FILES, REQUIRED_CLASSES

WEB_FILES = ('index.html', 'app.js', 'styles.css')
DEPLOY_FILES = (
    'web.config', 'Common.ps1', 'Manage-AlloyStudio.ps1', 'Set-OpenAIKey.ps1',
    'Start-AlloyStudio.ps1', 'run_backend.py', 'Test-IisDeployment.ps1', 'README.md',
)
RUNTIME_HELPERS = ('import_correct_pools.py', 'import_exercises.py')
TOKEN_PATTERN = re.compile(rb'sk-(?:proj-)?[A-Za-z0-9_-]{40,}')
ZIP_TIME = (1980, 1, 1, 0, 0, 0)


class PackageError(ValueError):
    """An incomplete or unsafe deployment input was refused."""


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_source(root: Path, relative: str) -> bytes:
    """Read a regular source file, refusing links and path escapes."""
    parts = PurePosixPath(relative).parts
    if not parts or relative.startswith('/') or any(part in ('..', '.') for part in parts):
        raise PackageError('Invalid source path in package inventory.')
    path = root
    for part in parts:
        path = path / part
        if path.is_symlink():
            raise PackageError(f'Symlink deployment input is not allowed: {relative}')
    if not path.is_file():
        if relative in ('exercises/catalogue.json', 'exercises/correct-pools.json'):
            raise PackageError('Private exercise data are missing. Run scripts/prepare_private_data.py --source-root <original ACGN checkout containing classified-data>, or restore both ignored files from a trusted private bundle.')
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


def collect_files(root: Path) -> dict[str, bytes]:
    root = root.resolve(strict=True)
    entries = {f'wwwroot/{name}': read_source(root, f'web/{name}') for name in WEB_FILES}
    for name in DEPLOY_FILES:
        entries[f'deploy/iis/{name}'] = read_source(root, f'deploy/iis/{name}')
    entries['wwwroot/web.config'] = entries['deploy/iis/web.config']
    entries['LICENSE'] = read_source(root, 'LICENSE')
    for name in ('server.py', 'luna.py', 'runtime_dependencies.py'):
        entries[f'backend/{name}'] = read_source(root, name)
    example_bytes = read_source(root, 'openai.example.json')
    if parse_json(example_bytes, 'OpenAI configuration template') != {'api_key': ''}:
        raise PackageError('The OpenAI configuration template must contain only an empty api_key.')
    entries['backend/openai.example.json'] = example_bytes
    for name in RUNTIME_HELPERS:
        entries[f'backend/scripts/{name}'] = read_source(root, f'scripts/{name}')

    catalogue_bytes = read_source(root, 'exercises/catalogue.json')
    catalogue = parse_json(catalogue_bytes, 'catalogue')
    exercises = catalogue.get('exercises')
    if catalogue.get('schemaVersion') != 1 or not isinstance(exercises, list) or not exercises:
        raise PackageError('Catalogue must contain schemaVersion 1 and a nonempty exercise list.')
    ids = set()
    required = ('id', 'title', 'group', 'predicate', 'description',
                'environmentBefore', 'environmentAfter', 'predicateHeader', 'starter', 'oracleBody')
    for record in exercises:
        if (not isinstance(record, dict)
                or any(not isinstance(record.get(key), str) for key in required)
                or not isinstance(record.get('source'), dict)
                or not record['id'] or record['id'] in ids):
            raise PackageError('Catalogue has an incomplete exercise or duplicate exercise identifier.')
        ids.add(record['id'])
    entries['backend/exercises/catalogue.json'] = catalogue_bytes

    pools_bytes = read_source(root, 'exercises/correct-pools.json')
    pools_document = parse_json(pools_bytes, 'correct-solution pools')
    from scripts.import_correct_pools import verify_document
    try:
        verify_document(catalogue, pools_document)
    except (ValueError, KeyError, TypeError, AttributeError, RecursionError) as exc:
        raise PackageError('Correct-solution pool validation failed; regenerate the private pools.') from exc
    entries['backend/exercises/correct-pools.json'] = pools_bytes

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

    classes_root = root / 'build/engine/classes'
    for name in REQUIRED_CLASSES:
        read_source(root, f'build/engine/classes/{name}')
    for path in sorted(classes_root.rglob('*.class')):
        relative = path.relative_to(root).as_posix()
        data = read_source(root, relative)
        if len(data) < 8 or data[:4] != b'\xca\xfe\xba\xbe' or not (45 <= struct.unpack('>H', data[6:8])[0] <= 61):
            raise PackageError(f'Expected a Java 17 compatible compiled class: {relative}')
        entries[f'backend/{relative}'] = data

    manifest = {
        'schemaVersion': 1,
        'application': 'Alloy Studio',
        'distribution': 'IIS 10',
        'privateArchive': True,
        'publicDirectory': 'wwwroot',
        'requirements': {'python': '3.10+', 'java': '17+', 'iis': '10.0'},
        'acgnCommit': snapshot['commit'],
        'exerciseCount': len(exercises),
        'knownCorrectPoolCount': len(pools_document['pools']),
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


def build_package(root: Path, output: Path) -> dict:
    """Write an atomic ZIP and checksum, returning only non-secret metadata."""
    root = root.resolve(strict=True)
    output = output.absolute()
    if output.suffix.lower() != '.zip':
        raise PackageError('Deployment archive output must end in .zip.')
    for public in (root / 'web', root / 'wwwroot'):
        if output.resolve().is_relative_to(public.resolve()):
            raise PackageError('The private deployment archive must be outside the public directory.')
    entries = collect_files(root)
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
        os.replace(temporary_path, output)
        atomic_private_write(output.with_suffix('.zip.sha256'), f'{checksum}  {output.name}\n'.encode('ascii'))
    finally:
        temporary_path.unlink(missing_ok=True)
    return {'archive': str(output), 'sha256': checksum, 'files': len(entries),
            'bytes': output.stat().st_size, 'privateArchive': True}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, default=ROOT, help='Project source root (defaults to this checkout).')
    parser.add_argument('--output', type=Path, help='Private ZIP destination (default: build/iis/alloy-studio-iis.zip).')
    args = parser.parse_args()
    try:
        result = build_package(args.source, args.output or args.source / 'build/iis/alloy-studio-iis.zip')
    except (OSError, PackageError) as exc:
        parser.exit(1, f'Package refused: {exc}\n')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
