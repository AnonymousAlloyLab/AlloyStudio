#!/usr/bin/env python3
"""Current-source freshness for historical VERIFIED closures (AP01-C07, bridge B07).

Governance.currentVerified, executed over the repository. For each closure in
the independently frozen registry, first prove the historical record is
intact and bound (report hash, VERIFIED status, input root, verifier, ledger).
A broken record is INVALID and fails the gate. Then compare the complete
approved inventory with the current checkout: exact equality (no missing,
changed, extra or duplicate entry) is CURRENT; anything else is STALE.

STALE never rewrites history: the historical report is read-only input and is
echoed unchanged. SHA-256, filesystem reads and this checker are trusted.
"""
import argparse
import hashlib
import json
from pathlib import Path
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = 'closure/freshness-registry.json'
BLOCK_BEGIN = '<!-- BEGIN CURRENT-SOURCE FRESHNESS (scripts/source_freshness.py --readme-block) -->'
BLOCK_END = '<!-- END CURRENT-SOURCE FRESHNESS -->'


class InvalidRecord(ValueError):
    pass


def unique_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise InvalidRecord('Duplicate key in historical record: ' + str(key))
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=pairs)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def json_root(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def inside(root, relative):
    # Inventories use one portable spelling per path. Path() normalizes aliases
    # such as './a' and 'a//b', and POSIX treats Windows drive/backslash paths as
    # ordinary names; inspect the original components before constructing it.
    if (type(relative) is not str or not relative or '\\' in relative or ':' in relative
            or '\x00' in relative or any(part in ('', '.', '..') for part in relative.split('/'))):
        raise InvalidRecord('Registered paths must be repository-relative: ' + repr(relative))
    return Path(root) / relative


def linked(metadata):
    return stat.S_ISLNK(metadata.st_mode) or bool(getattr(metadata, 'st_file_attributes', 0) & 0x400)


def observed_path(root, relative, *, directory=False):
    """Check every component without following a symlink or Windows junction.

    Filesystem snapshots are still a trust boundary: this does not claim an
    atomic view against concurrent changes between metadata checks and reads.
    """
    path = inside(root, relative)
    current = Path(root)
    components = relative.split('/')
    for component in ('', *components):
        if component:
            current = current / component
        metadata = current.lstat()
        if linked(metadata):
            raise OSError('Linked observation path')
        if current != path or directory:
            if not stat.S_ISDIR(metadata.st_mode):
                raise OSError('Observation parent is not a directory')
        elif not stat.S_ISREG(metadata.st_mode):
            raise OSError('Observation input is not a regular file')
    return path


def approved_inventory(root, entry, report):
    """The approved surface: a canonical, nonempty list of unique (path, digest) entries."""
    inventory = entry['inventory']
    if inventory.get('kind') == 'manifest':
        path = inside(root, inventory['path'])
        if digest(path) != inventory['sha256']:
            raise InvalidRecord('Approved manifest changed: ' + inventory['path'])
        entries = unique_json(path.read_bytes())
    elif inventory.get('kind') == 'report-inputs':
        entries = report.get('inputs')
    else:
        raise InvalidRecord('Unknown inventory kind')
    if not isinstance(entries, dict) or not entries:
        raise InvalidRecord('Empty or malformed approved inventory')
    for relative, value in entries.items():
        inside(root, relative)
        if not isinstance(value, str) or len(value) != 64 or any(c not in '0123456789abcdef' for c in value):
            raise InvalidRecord('Malformed approved digest: ' + relative)
    return entries


def bound_record(root, entry):
    """Integrity of the historical record; raises InvalidRecord instead of guessing."""
    report_path = inside(root, entry['report'])
    if digest(report_path) != entry['reportSha256']:
        raise InvalidRecord('Historical report changed: ' + entry['report'])
    report = unique_json(report_path.read_bytes())
    if report.get('status') != 'VERIFIED':
        raise InvalidRecord('Registered record did not pass')
    inventory = approved_inventory(root, entry, report)
    root_field, pinned_root = entry['root']['field'], entry['root']['sha256']
    if report.get(root_field) != pinned_root or json_root(inventory) != pinned_root:
        raise InvalidRecord('Input root is not bound to the approved inventory')
    verifier = entry['verifier']
    if inventory.get(verifier['path']) != verifier['sha256']:
        raise InvalidRecord('Verifier is not bound to the approved inventory')
    claimed = {claim.get('verifierSha256') or (claim.get('verifier') or {}).get('sha256')
               for claim in report.get('claims', [])}
    if not claimed or claimed != {verifier['sha256']}:
        raise InvalidRecord('Report claims a different verifier')
    ledger = entry.get('ledger')
    if ledger is not None:
        rows = [row for row in unique_json(inside(root, ledger['path']).read_bytes()).get('obligations', [])
                if row.get('id') == ledger['obligation']]
        if (len(rows) != 1 or rows[0].get('status') != 'VERIFIED'
                or rows[0].get('reportSha256') != entry['reportSha256']
                or rows[0].get('inputRootHash') != pinned_root):
            raise InvalidRecord('Ledger does not bind this historical record')
    return report, inventory


def observe(root, inventory, proof_directories):
    """Observed entries for the approved paths plus unregistered proof sources
    in each directory the registry declares complete."""
    observed, unavailable = {}, set()
    for relative in inventory:
        try:
            observed[relative] = digest(observed_path(root, relative))
        except OSError:
            observed[relative] = None
    for directory in sorted(proof_directories):
        try:
            pending = [observed_path(root, directory, directory=True)]
        except OSError:
            unavailable.add(directory)
            continue
        while pending:
            parent = pending.pop()
            try:
                children = sorted(parent.iterdir())
            except OSError:
                unavailable.add(parent.relative_to(root).as_posix())
                continue
            for path in children:
                relative = path.relative_to(root).as_posix()
                try:
                    metadata = path.lstat()
                    if linked(metadata):
                        # It might hide additional proof sources. Do not follow
                        # it, silently skip it, or classify an incomplete scan CURRENT.
                        unavailable.add(relative)
                    elif stat.S_ISDIR(metadata.st_mode):
                        pending.append(path)
                    elif path.suffix == '.lean' and relative not in observed:
                        observed[relative] = None
                        if stat.S_ISREG(metadata.st_mode):
                            observed[relative] = digest(path)
                except OSError:
                    unavailable.add(relative)
    return observed, sorted(unavailable)


def classify(root, entry):
    try:
        report, inventory = bound_record(root, entry)
    except (InvalidRecord, OSError, KeyError, TypeError, ValueError) as error:
        return {'id': entry.get('id'), 'record': 'INVALID', 'reason': str(error)}
    directories = entry.get('completeProofDirectories', [])
    try:
        if not isinstance(directories, list):
            raise InvalidRecord('Malformed complete proof directories')
        for directory in directories:
            inside(root, directory)
            if not any(path.startswith(directory + '/') and path.endswith('.lean') for path in inventory):
                raise InvalidRecord('Complete proof directories must contain registered proof sources')
    except InvalidRecord as error:
        return {'id': entry.get('id'), 'record': 'INVALID',
                'reason': str(error)}
    observed, unavailable = observe(root, inventory, directories)
    missing = sorted(path for path in inventory if observed.get(path) is None)
    changed = sorted(path for path in inventory if observed.get(path) not in (None, inventory[path]))
    extra = sorted(path for path in observed if path not in inventory)
    current = not (missing or changed or extra or unavailable)
    return {'id': entry['id'], 'record': 'VALID',
            'historical': {'status': report['status'], 'root': entry['root']['sha256'],
                           'report': entry['report'], 'reportSha256': entry['reportSha256'],
                           'verifier': entry['verifier']['path']},
            'current': 'CURRENT' if current else 'STALE',
            'approvedEntries': len(inventory), 'missing': missing, 'changed': changed, 'extra': extra,
            'unavailable': unavailable}


def run(root=ROOT):
    registry = unique_json((Path(root) / REGISTRY).read_bytes())
    if registry.get('schemaVersion') != 1 or not registry.get('closures'):
        raise InvalidRecord('Empty or malformed freshness registry')
    ids = [entry.get('id') for entry in registry['closures']]
    if len(set(ids)) != len(ids):
        raise InvalidRecord('Duplicate registered closure')
    results = [classify(Path(root), entry) for entry in registry['closures']]
    return {'schemaVersion': 1, 'gate': 'FAIL' if any(r['record'] != 'VALID' for r in results) else 'PASS',
            'closures': results,
            'interpretation': 'CURRENT requires exact equality with the approved inventory. STALE means '
                              'the historical VERIFIED result applies to its recorded root, not this checkout.'}


def readme_block(report):
    lines = [BLOCK_BEGIN, '| Closure | Historical record | Current source |', '| --- | --- | --- |']
    for result in report['closures']:
        historical = (result['historical']['status'] + ' at its recorded root'
                      if result['record'] == 'VALID' else 'INVALID record')
        current = result.get('current', 'not classified')
        lines.append(f"| {result['id']} | {historical} | {current} |")
    lines.append(BLOCK_END)
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--output', type=Path, help='Write the full JSON report here')
    parser.add_argument('--readme-block', action='store_true', help='Print the README status table')
    args = parser.parse_args()
    try:
        report = run()
    except (InvalidRecord, OSError, ValueError) as error:
        print(json.dumps({'gate': 'FAIL', 'reason': str(error)}))
        return 1
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    if args.readme_block:
        print(readme_block(report))
    else:
        print(json.dumps({'gate': report['gate'], 'closures': {
            r['id']: r.get('current', r['record']) for r in report['closures']}}, sort_keys=True))
    return 0 if report['gate'] == 'PASS' else 1


if __name__ == '__main__':
    sys.exit(main())
