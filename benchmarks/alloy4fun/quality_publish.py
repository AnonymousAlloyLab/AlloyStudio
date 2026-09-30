#!/usr/bin/env python3
"""Publish the ten-case pilot's public evidence, with target operands redacted."""
import argparse
from collections import Counter
from datetime import date
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return json.loads(path.read_text())


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def public_hint(hint):
    if hint is None:
        return None
    # This pilot has one native FM24 hint naming a replacement signature.
    # Preserve its requested action but do not publish the target name.
    return re.sub(r'(try using signature of type )(.+?)( to help)',
                  r'\1[target signature hidden]\3', hint)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, default=ROOT / 'build/benchmarks/hint-quality-pilot')
    parser.add_argument('--output', type=Path, default=ROOT / 'docs/benchmarks/alloy4fun-hint-quality.json')
    parser.add_argument('--collection-date', type=date.fromisoformat, required=True,
                        help='UTC collection date (YYYY-MM-DD), not the publication date')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file; existing evidence is preserved.')
    files = {name: args.study / (name + '.json') for name in
             ('selection', 'current', 'baselines-verified', 'literal-edit-probes', 'reviewer-a', 'reviewer-b')}
    source = {name: read(path) for name, path in files.items()}
    selected = source['selection']['cases']
    assert len(selected) == 10 and len({c['exercise_id'] for c in selected}) == 10
    selection_hash = sha(files['selection'])
    for name in ('current', 'baselines-verified'):
        assert source[name]['selection_sha256'] == selection_hash
    current = {c['case_id']: c for c in source['current']['cases']}
    baseline = {c['case_id']: c for c in source['baselines-verified']['cases']}
    assert set(current) == set(baseline) == {c['case_id'] for c in selected}
    output = {'schema': 1, 'collected_date_utc': args.collection_date.isoformat(),
              'selection_policy': source['selection']['selection_policy'],
              'collection': source['current']['collection'],
              'limitations': ['Purposive ten-family catalogue-starter sample; all ten are UNDERCONSTRAINED. Not a representative population sample.',
                  'Current portal full correct pools differ from five-fold baseline history/reference pools. Saved Live controls are reported separately.',
                  'Native deterministic hints only. No paid Luna explanation generation, learner study, or comparable runtime measurement.',
                  'Luna reviewer scores are unvalidated model judgments; absent hints have null quality scores. No composite method ranking.',
                  'Candidate/oracle/target expressions are excluded. One FM24 replacement-signature name is redacted; replacement operator names remain visible.'],
              'input_sha256': {name: sha(path) for name, path in files.items()},
              'archive_sha256': source['baselines-verified']['archive_sha256'],
              'runtime_sha256': source['current']['runtime_sha256'], 'cases': []}
    for case in selected:
        saved = baseline[case['case_id']]
        assert saved['source_sha256'] == case['source_sha256']
        row = {**case, 'current': {}, 'baselines': {}, 'heldout_live_controls': {}}
        for metric, wrapper in current[case['case_id']]['responses'].items():
            r = wrapper['response']
            assert r['exerciseId'] == case['exercise_id'] and r['status'] == 'ok'
            assert r['distance'] == sum(op['cost'] for op in r['operations'])
            for op in r['operations']:
                location = op.get('sourceLocation', {})
                assert location.get('coordinateSystem', 'body') == 'body'
                for span in location.get('ranges', []):
                    raw = case['learner_body'].encode('utf-16-le')
                    assert raw[span['start'] * 2:span['end'] * 2].decode('utf-16-le') == span['text']
            row['current'][metric] = r
        for method in ('tar', 'fm24-history', 'fm24-mutation'):
            entry = saved['engines'][method]
            if method == 'tar':
                row['baselines'][method] = {k: entry[k] for k in
                    ('status', 'hint_available', 'native_hints', 'bounded_verification',
                     'result_record_sha256', 'response_record_sha256')}
            else:
                original = entry['native_hint']
                row['baselines'][method] = {k: entry[k] for k in
                    ('status', 'hint_available', 'hint_source', 'fold', 'result_record_sha256', 'response_record_sha256')}
                row['baselines'][method].update(hint=public_hint(original),
                    publication_redacted=public_hint(original) != original)
        for method in ('live-full', 'ast-full'):
            row['heldout_live_controls'][method] = saved['engines'][method]
        output['cases'].append(row)
    stats = {}
    for metric in ('canonical', 'ast'):
        responses = [c['current'][metric] for c in output['cases']]
        ops = [op for r in responses for op in r['operations'] if not op.get('aggregate', False)]
        stats[metric] = {'hints_available': sum(bool(r['operations']) for r in responses),
            'operations': len(ops), 'raw_node_locations': sum(op.get('sourceLocation', {}).get('precision') == 'node' for op in ops),
            'raw_related_locations': sum(op.get('sourceLocation', {}).get('precision') == 'related' for op in ops),
            'canonical_node_locations': sum(op.get('canonicalLocation', {}).get('precision') == 'node' for op in ops),
            'operation_kinds': dict(Counter(op['kind'] for op in ops))}
    for method in ('tar', 'fm24-history', 'fm24-mutation'):
        entries = [c['baselines'][method] for c in output['cases']]
        stats[method] = {'hints_available': sum(e['hint_available'] for e in entries),
                         'statuses': dict(Counter(e['status'] for e in entries))}
    output['observed_counts'] = stats
    output['literal_edit_probes'] = []
    for probe in source['literal-edit-probes']:
        # Do not publish the edited candidate or its resulting canonical form.
        output['literal_edit_probes'].append({**{k: probe[k] for k in
            ('exercise_id', 'metric', 'operation_index', 'interpretation', 'source_operator', 'replacement_operator', 'before_fragment')},
            'after_status': probe['response']['status'], 'after_distance': probe['response'].get('distance'),
            'diagnostics': probe['response'].get('diagnostics', []),
            'bounded_behavior': probe.get('bounded_behavior')})
    reviews = []
    expected = {(c['exercise_id'], m) for c in selected for m in ('canonical', 'ast', 'tar', 'fm24-history', 'fm24-mutation')}
    for name in ('reviewer-a', 'reviewer-b'):
        review = source[name]
        entries = review.get('cases', review.get('entries'))
        assert len(entries) == 50 and {(e['exercise_id'], e['method']) for e in entries} == expected
        for entry in entries:
            values = list(entry['scores'].values())
            assert len(values) == 4 and all(value in (None, 0, 1, 2) for value in values)
        reviews.append({'model': 'gpt-6-luna', 'blinded': False, **review})
    output['reviews'] = reviews
    args.output.write_text(json.dumps(output, indent=2, ensure_ascii=False) + '\n')
    print(json.dumps({'status': 'PASS', 'cases': len(selected), 'observed_counts': stats,
                      'public_evidence_sha256': sha(args.output)}, indent=2))


if __name__ == '__main__':
    main()
