#!/usr/bin/env python3
"""Create the ignored private catalogue and correct pools from ACGN/classified-data."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.import_correct_pools import PoolError, build_document, canonical, verify_document
from scripts.import_exercises import ExtractionError, build_catalogue


def prepare(root: Path, source_root: Path) -> dict:
    root = Path(root).resolve(strict=True)
    source_root = Path(source_root).resolve(strict=True)
    corpus = source_root / 'classified-data'
    if not corpus.is_dir():
        raise ValueError('The source root must contain classified-data/.')
    target = root / 'exercises'
    if target.is_symlink():
        raise ValueError('The private exercises directory cannot be a symlink.')
    target.mkdir(parents=True, exist_ok=True)
    catalogue_path, pools_path = target / 'catalogue.json', target / 'correct-pools.json'
    exists = (catalogue_path.is_file(), pools_path.is_file())
    if any(exists):
        if not all(exists):
            raise ValueError('Only one private data file exists. Restore both files or remove both before regenerating.')
        catalogue = json.loads(catalogue_path.read_text(encoding='utf-8'))
        pools = json.loads(pools_path.read_text(encoding='utf-8'))
        verify_document(catalogue, pools)
        return {'action': 'validated-existing', 'exercises': len(catalogue['exercises']),
                'correctCandidates': pools['provenance']['correctStudentCandidates'],
                'sha256': {'catalogue': hashlib.sha256(catalogue_path.read_bytes()).hexdigest(),
                           'correctPools': hashlib.sha256(pools_path.read_bytes()).hexdigest()}}

    catalogue = build_catalogue(source_root)
    if catalogue['droppedGroups']:
        raise ValueError('The corpus import did not produce every discovered exercise group.')
    pools = build_document(catalogue, source_root)
    verify_document(catalogue, pools)
    catalogue_bytes = (json.dumps(catalogue, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    pool_bytes = canonical(pools)

    # Stage both oracle-bearing files in a mode-0700 directory, and publish only
    # after both deterministic imports and their source witnesses have passed.
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
    return {'action': 'imported', 'exercises': len(catalogue['exercises']),
            'correctCandidates': pools['provenance']['correctStudentCandidates'],
            'sha256': {'catalogue': hashlib.sha256(catalogue_bytes).hexdigest(),
                       'correctPools': hashlib.sha256(pool_bytes).hexdigest()}}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT,
                        help='Checkout that receives ignored private exercises/ files')
    parser.add_argument('--source-root', type=Path,
                        default=Path(os.environ.get('ACGN_ROOT', ROOT.parent / 'ACGN')),
                        help='Original ACGN checkout with classified-data/ (default: sibling ACGN or ACGN_ROOT)')
    args = parser.parse_args()
    try:
        result = prepare(args.root, args.source_root)
    except (OSError, ValueError, ExtractionError, PoolError, UnicodeError, RecursionError):
        parser.exit(1, 'Private corpus preparation failed. Check the ACGN classified-data source and both private exercise files.\n')
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
