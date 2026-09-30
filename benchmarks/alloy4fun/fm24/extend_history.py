#!/usr/bin/env python3
"""Retain teacher-exact training transitions without adding evaluation cases."""
import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare import extract_one, canonical

def extend(args):
    rows = {x['case_id']: x for x in (json.loads(line) for line in args.payloads.open())}
    supplemental = 0
    for line in args.inventory.open():
        item = json.loads(line)
        if item['case_id'] not in rows:
            corpus = Path(item['path']).parents[2]
            metadata, payload = extract_one((corpus, item['case_id'], False))
            if metadata.get('extraction_status') != 'ok':
                raise ValueError('Historical extraction failed: ' + item['case_id'])
            payload['eligible'] = False
            payload['historical_only'] = True
            rows[item['case_id']] = payload
            supplemental += 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(canonical(rows[k]) + '\n' for k in sorted(rows)))
    print(json.dumps({'historical_cases': len(rows), 'historical_only_teacher_exact': supplemental,
                      'evaluation_cases': len(rows) - supplemental}))

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--inventory', type=Path, required=True)
    p.add_argument('--payloads', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    extend(p.parse_args())
