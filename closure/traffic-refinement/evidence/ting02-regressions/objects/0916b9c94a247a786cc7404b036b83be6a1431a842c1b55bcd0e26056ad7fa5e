#!/usr/bin/env python3
"""Recover entire student-branch groups from the original public derivation tree."""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

SALT = 'alloy4fun-path-v1:'

def build(metadata_dir: Path, cases: Path, output: Path) -> dict:
    records = {}
    for path in sorted(metadata_dir.glob('*.json')):
        if path.name in {'record.json', 'metadata-index.json'}:
            continue
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                item = json.loads(line)
                records[item['_id']] = {key: item.get(key) for key in ('original', 'derivationOf', 'sat', 'cmd_n')}
    (metadata_dir / 'metadata-index.json').write_text(json.dumps(records, sort_keys=True) + '\n')
    cache = {}
    def branch(model_id):
        if model_id in cache:
            return cache[model_id]
        original = records[model_id]['original']
        trail = []
        current = model_id
        seen = set()
        while True:
            if current in seen:
                raise ValueError('Cyclic historical derivation: ' + model_id)
            seen.add(current)
            trail.append(current)
            parent = records[current]['derivationOf']
            if current == original or parent == original or parent not in records:
                root = current
                break
            if records[parent]['original'] != original:
                raise ValueError('Unexpected cross-original student parent: ' + model_id)
            if parent in cache:
                root = cache[parent]
                break
            current = parent
        for identifier in trail:
            cache[identifier] = root
        return root
    result = {}
    with cases.open(encoding='utf-8') as stream:
        for line in stream:
            item = json.loads(line)
            identifier = item['model_id']
            record = records[identifier]
            root = branch(identifier)
            key = record['original'] + ':' + root
            fold = int.from_bytes(hashlib.sha256((SALT + key).encode()).digest(), 'big') % 5
            result[identifier] = {**record, 'root_id': root, 'fold': fold}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, sort_keys=True, separators=(',', ':')) + '\n', encoding='utf-8')
    report = {'schema_version': 1, 'models': len(result), 'branches': len({(x['original'], x['root_id']) for x in result.values()}),
              'fold_counts': dict(sorted(Counter(x['fold'] for x in result.values()).items())),
              'rule': 'big-endian integer SHA256(alloy4fun-path-v1: + original + : + branch_root) modulo 5',
              'branch_definition': 'First descendant of original instructor model; all descendant questions stay together.',
              'sha256': hashlib.sha256(output.read_bytes()).hexdigest()}
    output.with_suffix('.summary.json').write_text(json.dumps(report, indent=2) + '\n')
    return report

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--metadata-dir', type=Path, required=True)
    p.add_argument('--cases', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    print(json.dumps(build(a.metadata_dir, a.cases, a.output), indent=2))
