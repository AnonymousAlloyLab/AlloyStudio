#!/usr/bin/env python3
"""Private host administration for the exercise SQLite database (no HTTP route)."""
import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from exercise_store import StoreError, add_exercise, ensure_store, load_store, read_import, validate_import


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--java', default='java')
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('info', help='Validate and count the private database')
    commands.add_parser('migrate', help='Create SQLite from validated legacy files if absent')
    for name in ('validate','add'):
        command = commands.add_parser(name, help='Validate an input with Alloy' if name == 'validate' else 'Validate and atomically add one exercise')
        command.add_argument('file', type=Path)
    args = parser.parse_args()
    try:
        if args.command in ('info','migrate'):
            snapshot = (ensure_store if args.command == 'migrate' else load_store)(args.root)
            result = {'exercises':snapshot.exercise_count,'candidates':snapshot.candidate_count}
        else:
            document = read_import(args.file)
            result = (validate_import if args.command == 'validate' else add_exercise)(args.root,document,java=args.java)
            if args.command == 'validate':
                result = result['result']
        print(json.dumps(dict(status='ok', **result), indent=2))
    except (StoreError, OSError, ValueError):
        # The file and solver diagnostics can include private source. Only our
        # explicitly sanitized StoreError text is suitable for the operator.
        error = sys.exc_info()[1]
        print('Import refused: ' + (str(error) if isinstance(error, StoreError) else 'Private exercise preparation failed.'), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
