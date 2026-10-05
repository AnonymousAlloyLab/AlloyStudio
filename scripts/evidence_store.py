#!/usr/bin/env python3
"""Append-only in-repository evidence (AP01-C09, bridge B09).

Create unique directories, append new files, and seal an exact inventory. A seal
ends writes through this API. External filesystem writers, crash durability and
concurrent independent processes are outside this helper's guarantee.
"""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import secrets
import stat
import threading
import time

MANIFEST = 'manifest.json'
_LOCK = threading.RLock()


class EvidenceCollision(FileExistsError):
    """An identifier/path is occupied or its inventory has already been sealed."""


class EvidenceMismatch(ValueError):
    """Stored bytes or the complete inventory no longer match their binding."""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def manifest_root(entries):
    return digest(json.dumps(entries, sort_keys=True, separators=(',', ':')).encode())


def _relative(relative):
    if not isinstance(relative, str) or not relative or '\\' in relative or ':' in relative:
        raise ValueError('Evidence paths must be canonical relative POSIX paths.')
    path = PurePosixPath(relative)
    if (not path.parts or path.is_absolute() or relative != path.as_posix()
            or any(part in ('', '.', '..') or part.endswith(('.', ' '))
                   or any(ord(c) < 32 for c in part)
                   or re.fullmatch(r'(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?', part)
                   for part in path.parts)):
        raise ValueError('Evidence paths must be canonical portable relative paths.')
    return path


def _unlinked(directory, path):
    for part in (path, *path.parents):
        try:
            metadata = part.lstat()
        except FileNotFoundError:
            metadata = None
        if (part.is_symlink() or metadata is not None
                and getattr(metadata, 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0)):
            raise ValueError('Linked evidence paths are not accepted.')
        if part == directory:
            return
    raise ValueError('Evidence path is outside its directory.')


def _inventory(directory):
    _unlinked(directory, directory)
    entries = {}
    for path in directory.rglob('*'):
        _unlinked(directory, path)
        if path.is_dir():
            continue
        if not path.is_file():
            raise EvidenceMismatch('Evidence must contain regular files only.')
        relative = path.relative_to(directory).as_posix()
        _relative(relative)
        if relative != MANIFEST:
            entries[relative] = digest(path.read_bytes())
    return entries


def _entries(entries):
    if not isinstance(entries, dict):
        raise EvidenceMismatch('Evidence manifest must contain a path-to-digest mapping.')
    for relative, value in entries.items():
        _relative(relative)
        if relative == MANIFEST or not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None:
            raise EvidenceMismatch('Malformed evidence manifest entry.')
    return entries


def new_directory(root, family, label, *, now=None, token=None):
    """Create closure/<family>/evidence/<label>-<UTC>-<token>/ exclusively."""
    for value in (family, label):
        if not isinstance(value, str) or re.fullmatch('[A-Za-z0-9_-]+', value) is None:
            raise ValueError('Evidence family and label must be simple names.')
    token = secrets.token_hex(4) if token is None else token
    if not isinstance(token, str) or re.fullmatch('[A-Za-z0-9_-]+', token) is None:
        raise ValueError('Evidence tokens must be simple names.')
    root = Path(root)
    parent = root / 'closure' / family / 'evidence'
    _unlinked(root, parent)
    parent.mkdir(parents=True, exist_ok=True)
    _unlinked(root, parent)
    stamp = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime(now))
    directory = parent / f'{label}-{stamp}-{token}'
    try:
        directory.mkdir()
    except FileExistsError:
        raise EvidenceCollision(directory.name) from None
    return directory


def _write_new(directory, relative, data):
    path = directory / _relative(relative)
    _unlinked(directory, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    _unlinked(directory, path)
    try:
        with path.open('xb') as stream:
            stream.write(data)
    except FileExistsError:
        raise EvidenceCollision(relative) from None
    return digest(data)


def append(directory, relative, data):
    """Refuse existing files and sealed inventories; preserve earlier bytes."""
    directory = Path(directory)
    with _LOCK:
        _unlinked(directory, directory / MANIFEST)
        if (directory / MANIFEST).exists() or relative == MANIFEST:
            raise EvidenceCollision('Evidence inventory is sealed or the path is reserved.')
        return _write_new(directory, relative, data)


def seal(directory, entries):
    """Publish a manifest only after its complete inventory has been checked."""
    directory = Path(directory)
    with _LOCK:
        _unlinked(directory, directory / MANIFEST)
        if (directory / MANIFEST).exists():
            raise EvidenceCollision('Evidence inventory is already sealed.')
        _entries(entries)
        if _inventory(directory) != entries:
            raise EvidenceMismatch('Cannot seal missing, changed or unregistered evidence.')
        _write_new(directory, MANIFEST, (json.dumps(entries, indent=2, sort_keys=True) + '\n').encode())
        return manifest_root(entries)


def lookup(directory, relative, expected):
    directory = Path(directory)
    path = directory / _relative(relative)
    _unlinked(directory, path)
    data = path.read_bytes()
    if digest(data) != expected:
        raise EvidenceMismatch('Evidence changed: ' + relative)
    return data


def verify(directory):
    """Check every digest and reject extra files, duplicate paths and links."""
    directory = Path(directory)
    with _LOCK:
        _unlinked(directory, directory / MANIFEST)
        def unique(pairs):
            result = {}
            for key, value in pairs:
                if key in result:
                    raise EvidenceMismatch('Duplicate evidence path.')
                result[key] = value
            return result
        entries = _entries(json.loads((directory / MANIFEST).read_text(), object_pairs_hook=unique))
        observed = _inventory(directory)
        if observed.keys() - entries.keys():
            raise EvidenceMismatch('Unregistered evidence added.')
        if observed != entries:
            raise EvidenceMismatch('Evidence changed or is missing.')
        return manifest_root(entries)
