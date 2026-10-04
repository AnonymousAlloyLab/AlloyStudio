#!/usr/bin/env python3
"""Bind the requested ACGN cohort and extract unchanged learner environments.

Generated source bodies belong under ignored build/, never public web assets.
The exclusion CSV is retained as an explicit, hashed cohort-selection input.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ProcessPoolExecutor
import csv
import hashlib
import json
from pathlib import Path
import re
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from scripts.import_exercises import extract_model
from scripts.import_correct_pools import environment_sha256, body_token_sha256

STATUS = {'correct': 'CORRECT', 'both': 'BOTH', 'over': 'OVERCONSTRAINED',
          'under': 'UNDERCONSTRAINED'}
LABEL_CORRECTIONS = Path(__file__).resolve().parent / 'protocol/label-corrections.json'


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True)


def load_label_corrections(path=LABEL_CORRECTIONS):
    """Read the reviewed registry without treating an unknown source as corrected."""
    data = Path(path).read_bytes()
    registry = json.loads(data)
    if registry.get('schema_version') != 1 or not isinstance(registry.get('corrections'), list):
        raise ValueError('Invalid label-correction registry schema')
    entries = {}
    for entry in registry['corrections']:
        if not isinstance(entry, dict):
            raise ValueError('Invalid label-correction entry')
        case_id = entry.get('case_id')
        if not isinstance(case_id, str) or not re.fullmatch(r'[^/]+/(correct|both|over|under)/[^/]+_inv\d+\.als', case_id):
            raise ValueError('Invalid label-correction case ID')
        if case_id in entries:
            raise ValueError('Duplicate label-correction case ID: ' + case_id)
        if not isinstance(entry.get('source_sha256'), str) or not re.fullmatch(r'[0-9a-f]{64}', entry['source_sha256']):
            raise ValueError('Invalid label-correction source hash: ' + case_id)
        source_status = STATUS[Path(case_id).parts[1]]
        if entry.get('original_label') != source_status:
            raise ValueError('Label-correction original label disagrees with source path: ' + case_id)
        if entry.get('corrected_label') not in STATUS.values() or entry['corrected_label'] == source_status:
            raise ValueError('Invalid corrected label: ' + case_id)
        entries[case_id] = entry
    if not entries:
        raise ValueError('Label-correction registry is empty')
    return entries, digest(data)


def apply_label_correction(row, correction):
    """Apply only an exact case-ID, source-hash, original-label match."""
    if correction is None:
        return
    for row_key, correction_key in (('case_id', 'case_id'), ('source_sha256', 'source_sha256'),
                                    ('source_cohort_status', 'original_label')):
        if row[row_key] != correction[correction_key]:
            raise ValueError('Label-correction binding mismatch (' + row_key + '): ' + row['case_id'])
    row['cohort_status'] = correction['corrected_label']


def validate_label_corrections(corpus, paths, excluded, corrections):
    """Fail before writing outputs when reviewed records are absent or changed."""
    available = set(paths)
    for case_id, correction in corrections.items():
        if case_id not in available:
            raise ValueError('Expected label-correction case is missing: ' + case_id)
        if case_id in excluded:
            raise ValueError('Expected label-correction case is excluded: ' + case_id)
        source_status = STATUS[Path(case_id).parts[1]]
        apply_label_correction({'case_id': case_id,
                                'source_sha256': digest((Path(corpus) / case_id).read_bytes()),
                                'source_cohort_status': source_status,
                                'cohort_status': source_status}, correction)


def extract_one(item):
    corpus, relative, excluded, *optional_correction = item
    if len(optional_correction) > 1:
        raise ValueError('Unexpected extraction arguments')
    path = Path(corpus) / relative
    group, status, filename = Path(relative).parts
    match = re.fullmatch(r'(.+)_(inv\d+)\.als', filename)
    if not match or status not in STATUS:
        raise ValueError('Unexpected corpus path: ' + relative)
    data = path.read_bytes()
    row = {'case_id': relative, 'path': str(path), 'group': group,
           'predicate': match[2], 'model_id': match[1],
           'cohort_status': STATUS[status], 'source_cohort_status': STATUS[status],
           'source_sha256': digest(data),
           'eligible': not excluded}
    apply_label_correction(row, optional_correction[0] if optional_correction else None)
    if excluded:
        return row, None
    try:
        model = extract_model(data, match[2])
        row['extraction_status'] = 'ok'
        context = environment_sha256(model)
        payload = {**row, 'environment_sha256': context,
                   'prefix': model['environmentBefore'] + model['predicateHeader'] + '{',
                   'suffix': '}' + model['environmentAfter'],
                   'body': model['starter'], 'oracle_body': model['oracleBody'],
                   'body_token_sha256': body_token_sha256(model['starter']),
                   'oracle_token_sha256': body_token_sha256(model['oracleBody'])}
        return row, payload
    except (ValueError, UnicodeError) as error:
        row['extraction_status'] = type(error).__name__
        return row, {**row, 'extraction_error': str(error)}


def prepare(corpus, exclusions, output, workers=8):
    corpus, exclusions, output = Path(corpus).resolve(), Path(exclusions).resolve(), Path(output)
    with exclusions.open(encoding='utf-8', newline='') as stream:
        excluded_rows = list(csv.DictReader(stream))
    excluded = {row['relativePath'] for row in excluded_rows}
    paths = sorted(p.relative_to(corpus).as_posix() for p in corpus.rglob('*.als'))
    if len(excluded_rows) != len(excluded) or not excluded.issubset(paths):
        raise ValueError('Exclusion inventory is duplicated or refers to absent files')
    if (len(paths), len(excluded), len(paths) - len(excluded)) != (66080, 4482, 61598):
        raise ValueError('Dataset is not the requested 66,080 − 4,482 = 61,598 cohort')
    corrections, corrections_sha256 = load_label_corrections()
    validate_label_corrections(corpus, paths, excluded, corrections)
    output.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    counts, source_counts, extraction = Counter(), Counter(), Counter()
    applied = set()
    inventory_hash = hashlib.sha256()
    with (output / 'inventory.jsonl').open('w', encoding='utf-8') as inventory, \
         (output / 'cases.jsonl').open('w', encoding='utf-8') as cases, \
         (output / 'payloads.jsonl').open('w', encoding='utf-8') as payloads, \
         ProcessPoolExecutor(max_workers=workers) as pool:
        items = ((str(corpus), relative, relative in excluded, corrections.get(relative)) for relative in paths)
        for index, (row, payload) in enumerate(pool.map(extract_one, items, chunksize=64), 1):
            line = canonical(row) + '\n'
            inventory.write(line)
            inventory_hash.update(canonical([row['case_id'], row['source_sha256'], row['eligible']]).encode())
            if row['eligible']:
                cases.write(line)
                payloads.write(canonical(payload) + '\n')
                counts[row['cohort_status']] += 1
                source_counts[row['source_cohort_status']] += 1
                extraction[row['extraction_status']] += 1
                if row['case_id'] in corrections:
                    applied.add(row['case_id'])
            if index % 5000 == 0:
                print(json.dumps({'audited': index, 'total': len(paths)}), flush=True)
    if applied != set(corrections):
        raise ValueError('Expected label corrections were not all applied')
    if source_counts['CORRECT'] != 19212 or sum(source_counts.values()) - source_counts['CORRECT'] != 42386:
        raise ValueError('Unexpected original correctness-label counts')
    if counts['CORRECT'] != 19210 or counts['UNDERCONSTRAINED'] != 12578 or sum(counts.values()) - counts['CORRECT'] != 42388:
        raise ValueError('Unexpected corrected correctness-label counts')
    report = {'schema_version': 1, 'source_root': str(corpus), 'source_files': len(paths),
              'excluded_ast_identical': len(excluded), 'eligible': sum(counts.values()),
              'classification_counts': dict(counts), 'source_classification_counts': dict(source_counts),
              'extraction_counts': dict(extraction),
              'label_corrections': {'path': str(LABEL_CORRECTIONS), 'sha256': corrections_sha256,
                                    'applied_count': len(applied), 'case_ids': sorted(applied)},
              'corpus_inventory_sha256': inventory_hash.hexdigest(),
              'exclusion_list': str(exclusions), 'exclusion_list_sha256': digest(exclusions.read_bytes()),
              'selection_note': 'Exclusion membership is inherited from the hashed ACGN raw-AST identity audit; no incorrect case is filtered by tool outcome.',
              'elapsed_seconds': time.perf_counter() - started}
    for name in ('inventory.jsonl', 'cases.jsonl', 'payloads.jsonl'):
        report[name + '_sha256'] = digest((output / name).read_bytes())
    (output / 'cohort.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report), flush=True)
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, default=ROOT.parent / 'ACGN/classified-data')
    parser.add_argument('--exclusions', type=Path, default=ROOT.parent / 'ACGN/alloy4fun-augmented/ast_identical_predicate_pairs.csv')
    parser.add_argument('--output', type=Path, default=ROOT / 'build/benchmarks/alloy4fun')
    parser.add_argument('--workers', type=int, default=8)
    args = parser.parse_args()
    prepare(args.corpus, args.exclusions, args.output, args.workers)
