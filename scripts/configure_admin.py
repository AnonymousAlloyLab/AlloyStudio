#!/usr/bin/env python3
"""Set the private administrator password interactively; never accept it in argv."""
import argparse
import getpass
import json
import os
from pathlib import Path
import secrets
import sys
import warnings

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from admin_auth import CONFIG_NAME, _safe_path, configuration


def write_configuration(root, origin, base_path, password, *, replace=False):
    root = _safe_path(root)
    if not root.is_dir():
        raise ValueError('The backend root must exist.')
    destination = _safe_path(root / CONFIG_NAME)
    if destination.exists() and not replace:
        raise ValueError('Configuration already exists; use --replace to rotate it.')
    data = (json.dumps(configuration(origin, base_path, password), sort_keys=True) + '\n').encode('utf-8')
    temporary = root / ('.admin-config-' + secrets.token_hex(12) + '.tmp')
    try:
        descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, 'wb') as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        _safe_path(destination)
        if replace:
            os.replace(temporary, destination)
        else:
            os.link(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--origin', required=True, help='Exact HTTPS browser origin, or literal loopback HTTP origin')
    parser.add_argument('--base-path', default='/', help='IIS application path, for example /alloy/')
    parser.add_argument('--root', type=Path, default=ROOT, help='Private backend root')
    parser.add_argument('--replace', action='store_true', help='Rotate existing credentials and revoke prior sessions')
    args = parser.parse_args()
    try:
        with warnings.catch_warnings():
            warnings.simplefilter('error', getpass.GetPassWarning)
            password = getpass.getpass('Administrator password (12–1024 UTF-8 bytes): ')
            confirmation = getpass.getpass('Confirm administrator password: ')
        if password != confirmation:
            raise ValueError('Password confirmation does not match.')
        write_configuration(args.root, args.origin, args.base_path, password, replace=args.replace)
        print('Administrator configuration saved privately. Existing sessions are revoked on their next check.')
        return 0
    except (OSError, ValueError, EOFError, KeyboardInterrupt, getpass.GetPassWarning):
        print('Administrator setup failed. Check the backend path, origin and matching password; use --replace only to rotate an existing configuration.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
