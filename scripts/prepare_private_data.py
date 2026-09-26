#!/usr/bin/env python3
"""Validate private exercise data, import the original corpus, or restore a trusted IIS ZIP."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.import_correct_pools import build_document, canonical, verify_document
from scripts.import_exercises import PUBLIC_FIELDS, build_catalogue, verify_record


PRIVATE_NAMES = ('catalogue.json', 'correct-pools.json')
MAX_JSON_BYTES = 128 * 1024 * 1024


class PreparationError(ValueError):
    """An actionable diagnostic containing no private source text."""
    def __init__(self, code: str, message: str):
        self.code = code
        super().__init__(message)


def parse_json(data: bytes):
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field.')
            result[key] = value
        return result
    return json.loads(data.decode('utf-8-sig'), object_pairs_hook=unique_fields)


def validate_pair(catalogue_bytes: bytes, pools_bytes: bytes):
    catalogue, pools = parse_json(catalogue_bytes), parse_json(pools_bytes)
    if (not isinstance(catalogue, dict) or catalogue.get('schemaVersion') != 1
            or not isinstance(catalogue.get('exercises'), list) or not catalogue['exercises']):
        raise ValueError('Invalid catalogue schema or empty catalogue.')
    seen = set()
    for record in catalogue['exercises']:
        if (not isinstance(record, dict)
                or any(not isinstance(record.get(field), str) for field in PUBLIC_FIELDS if field != 'source')
                or not isinstance(record.get('source'), dict)
                or not record['id'] or record['id'] in seen):
            raise ValueError('Invalid or duplicate catalogue record.')
        seen.add(record['id'])
        verify_record(record)
    verify_document(catalogue, pools)
    return catalogue, pools


def read_bounded(path: Path) -> bytes:
    with path.open('rb') as stream:
        data = stream.read(MAX_JSON_BYTES + 1)
    if len(data) > MAX_JSON_BYTES:
        raise ValueError('Private data exceeds the supported size.')
    return data


def read_bundle(bundle: Path):
    """Restore only two data members; never extract paths or copy credentials."""
    bundle = Path(bundle).expanduser().absolute()
    if not bundle.is_file():
        raise PreparationError('BUNDLE_MISSING', f'Trusted IIS ZIP not found: {bundle}. Pass the ZIP file itself with --from-bundle.')
    try:
        with zipfile.ZipFile(bundle) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            if len(names) > 10000 or len(names) != len(set(names)):
                raise ValueError('Duplicate or excessive archive members.')

            def member(name, limit):
                info = archive.getinfo(name)
                mode = stat.S_IFMT(info.external_attr >> 16)
                if info.is_dir() or mode not in (0, stat.S_IFREG) or info.file_size > limit:
                    raise ValueError('Unsafe or oversized bundle member.')
                with archive.open(info) as stream:
                    data = stream.read(limit + 1)
                if len(data) != info.file_size or len(data) > limit:
                    raise ValueError('Invalid bundle member length.')
                return data

            manifest = parse_json(member('manifest.json', 1024 * 1024))
            if (manifest['schemaVersion'] != 1 or manifest['application'] != 'Alloy Studio'
                    or manifest['distribution'] != 'IIS 10' or manifest['privateArchive'] is not True):
                raise ValueError('Not an Alloy Studio private IIS manifest.')
            inventory = {}
            for entry in manifest['files']:
                if (not isinstance(entry, dict) or set(entry) != {'path', 'bytes', 'sha256'}
                        or not isinstance(entry['path'], str) or entry['path'] in inventory
                        or type(entry['bytes']) is not int or entry['bytes'] < 0
                        or not isinstance(entry['sha256'], str)
                        or not re.fullmatch(r'[0-9a-f]{64}', entry['sha256'])):
                    raise ValueError('Invalid bundle inventory.')
                inventory[entry['path']] = entry
            data = []
            for name in PRIVATE_NAMES:
                path = 'backend/exercises/' + name
                raw = member(path, MAX_JSON_BYTES)
                entry = inventory[path]
                if len(raw) != entry['bytes'] or hashlib.sha256(raw).hexdigest() != entry['sha256']:
                    raise ValueError('Bundle data does not match its manifest.')
                data.append(raw)
            catalogue, pools = validate_pair(*data)
            if (type(manifest['exerciseCount']) is not int
                    or type(manifest['knownCorrectPoolCount']) is not int
                    or manifest['exerciseCount'] != len(catalogue['exercises'])
                    or manifest['knownCorrectPoolCount'] != len(pools['pools'])):
                raise ValueError('Bundle counts do not match its data.')
            return catalogue, pools, data[0], data[1]
    except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError,
            RuntimeError, zipfile.BadZipFile, NotImplementedError) as error:
        raise PreparationError('BUNDLE_INVALID',
            'The IIS ZIP is incomplete, changed, or has invalid catalogue/pool witnesses. '
            'Obtain the complete trusted alloy-studio-iis.zip and verify its .sha256 sidecar; no data were restored.') from error


def result_metadata(action, catalogue, pools, catalogue_bytes, pools_bytes):
    return {'action': action, 'exercises': len(catalogue['exercises']),
            'correctCandidates': pools['provenance']['correctStudentCandidates'],
            'sha256': {'catalogue': hashlib.sha256(catalogue_bytes).hexdigest(),
                       'correctPools': hashlib.sha256(pools_bytes).hexdigest()}}


def prepare(root: Path, source_root: Path | None = None, *, bundle: Path | None = None) -> dict:
    root = Path(root).expanduser().resolve(strict=True)
    target = root / 'exercises'
    if target.is_symlink():
        raise PreparationError('UNSAFE_PRIVATE_PATH', 'The private exercises directory cannot be a symlink.')
    catalogue_path, pools_path = (target / name for name in PRIVATE_NAMES)
    paths = (catalogue_path, pools_path)
    if any(path.is_symlink() for path in paths):
        raise PreparationError('UNSAFE_PRIVATE_PATH', 'Private exercise files must be regular files, not symbolic links.')
    exists = tuple(path.exists() for path in paths)
    if any(exists):
        if not all(exists):
            missing = PRIVATE_NAMES[0 if not exists[0] else 1]
            raise PreparationError('PARTIAL_PRIVATE_DATA',
                f'Only one private data file exists; exercises/{missing} is missing. '
                'Restore the matching file from your trusted bundle, or back up and move the incomplete pair before retrying --from-bundle. Existing files were preserved.')
        try:
            catalogue_bytes, pool_bytes = (read_bounded(path) for path in paths)
            catalogue, pools = validate_pair(catalogue_bytes, pool_bytes)
        except (OSError, ValueError, KeyError, TypeError, AttributeError, RecursionError) as error:
            raise PreparationError('PRIVATE_DATA_INVALID',
                'The existing catalogue.json/correct-pools.json pair is unreadable or failed source/witness validation. '
                'Restore both matching files from a trusted bundle. Existing files were preserved.') from error
        return result_metadata('validated-existing', catalogue, pools, catalogue_bytes, pool_bytes)

    if bundle is not None:
        catalogue, pools, catalogue_bytes, pool_bytes = read_bundle(bundle)
        action = 'restored-bundle'
    else:
        source_root = Path(source_root or os.environ.get('ACGN_ROOT') or ROOT.parent / 'ACGN').expanduser().absolute()
        corpus = source_root / 'classified-data'
        if not corpus.is_dir():
            raise PreparationError('SOURCE_CORPUS_MISSING',
                f'Neither private exercise file is installed, and the original corpus was not found at {corpus}. '
                'A Git clone includes the engine dependencies but not these oracle-bearing data. '
                'Use --source-root with the original ACGN checkout containing classified-data/, '
                'or --from-bundle /path/to/alloy-studio-iis.zip. vendor/acgn contains engine code, not the exercise corpus.')
        try:
            catalogue = build_catalogue(source_root)
            if not catalogue['exercises']:
                raise PreparationError('EMPTY_CORPUS',
                    'classified-data contains no usable exercise groups. Expected <group>/<classification>/*_invN.als. '
                    'Use the complete original corpus or restore with --from-bundle.')
            if catalogue['droppedGroups']:
                raise PreparationError('INCOMPLETE_CORPUS',
                    'Some discovered exercise groups have no safely importable source. '
                    'Use the complete original corpus or restore with --from-bundle.')
            pools = build_document(catalogue, source_root)
            catalogue_bytes = (json.dumps(catalogue, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
            pool_bytes = canonical(pools)
            validate_pair(catalogue_bytes, pool_bytes)
        except PreparationError:
            raise
        except FileNotFoundError as error:
            raise PreparationError('IMPORT_INPUT_MISSING',
                'An import input is missing. Restore the complete source checkout, including '
                'scripts/exercise_descriptions.json, and the original ACGN classified-data sources.') from error
        except (ValueError, KeyError, TypeError, AttributeError, RecursionError) as error:
            raise PreparationError('CORPUS_INVALID',
                'The original corpus or description table failed extraction/schema/witness validation. '
                'Restore the complete matching source files or use --from-bundle; no private data were published.') from error
        action = 'imported'

    # Stage both oracle-bearing files in a mode-0700 directory, and publish only
    # after both deterministic imports and their source witnesses have passed.
    target.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix='.private-import-', dir=target))
    try:
        if os.name != 'nt':
            stage.chmod(0o700)
        staged_catalogue, staged_pools = stage / 'catalogue.json', stage / 'correct-pools.json'
        staged_catalogue.write_bytes(catalogue_bytes)
        staged_pools.write_bytes(pool_bytes)
        if os.name != 'nt':
            staged_catalogue.chmod(0o600)
            staged_pools.chmod(0o600)
        if catalogue_path.exists() or pools_path.exists():
            raise ValueError('Private data appeared during import; no existing file was overwritten.')
        os.replace(staged_catalogue, catalogue_path)
        os.replace(staged_pools, pools_path)
    finally:
        shutil.rmtree(stage, ignore_errors=True)
    return result_metadata(action, catalogue, pools, catalogue_bytes, pool_bytes)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT,
                        help='Checkout that receives ignored private exercises/ files')
    source = parser.add_mutually_exclusive_group()
    source.add_argument('--source-root', type=Path,
                        default=Path(os.environ.get('ACGN_ROOT', ROOT.parent / 'ACGN')),
                        help='Original ACGN checkout with classified-data/ (default: sibling ACGN or ACGN_ROOT)')
    source.add_argument('--from-bundle', type=Path,
                        help='Restore missing private data from a trusted alloy-studio-iis.zip without an ACGN checkout')
    args = parser.parse_args()
    try:
        result = prepare(args.root, args.source_root, bundle=args.from_bundle)
    except PreparationError as error:
        parser.exit(1, f'Private data preparation failed [{error.code}]: {error}\n')
    except OSError:
        parser.exit(1, 'Private data preparation failed [FILESYSTEM_ERROR]: Check that --root exists and that the current account can read the inputs and create exercises/ and its private data files.\n')
    except (ValueError, KeyError, TypeError, AttributeError, RecursionError):
        parser.exit(1, 'Private data preparation failed [INVALID_INPUT]: Check the source layout and use a complete trusted bundle or matching original corpus. No private source contents are printed.\n')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
