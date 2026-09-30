#!/usr/bin/env python3
"""Reproduce two literal-edit usability probes; keep edited candidates local."""
import argparse
import json
from pathlib import Path
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--study', type=Path, required=True)
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file; existing evidence is preserved.')
    selected = {c['exercise_id']: c for c in json.loads((args.study / 'selection.json').read_text())['cases']}
    current = {c['exercise_id']: c for c in json.loads((args.study / 'current.json').read_text())['cases']}

    def request(path, payload):
        outgoing = urllib.request.Request(args.url.rstrip('/') + path,
            data=json.dumps(payload).encode(), headers={'Content-Type': 'application/json', 'Origin': args.url.rstrip('/')})
        with urllib.request.urlopen(outgoing, timeout=70) as response:
            return json.load(response)

    results = []
    for exercise, metric in [('classroom_fol-inv13', 'canonical'), ('trainStationNew-inv5', 'ast')]:
        case = selected[exercise]
        operation = current[exercise]['responses'][metric]['response']['operations'][0]
        span = operation['sourceLocation']['ranges'][0]
        raw = case['learner_body'].encode('utf-16-le')
        assert raw[span['start'] * 2:span['end'] * 2].decode('utf-16-le') == span['text']
        old, new = operation['sourceOperator'], operation['replacementOperator']
        assert operation['kind'] == 'replace' and span['text'].count(old) == 1
        fragment = span['text'].replace(old, new)
        edited = (raw[:span['start'] * 2] + fragment.encode('utf-16-le') + raw[span['end'] * 2:]).decode('utf-16-le')
        payload = {'exerciseId': exercise, 'body': edited, 'metric': metric, 'revision': 100 + case['number']}
        answer = request('/api/feedback', payload)
        probe = {'exercise_id': exercise, 'metric': metric, 'operation_index': 0,
            'interpretation': 'Replace the single sourceOperator token inside the exact highlighted raw fragment with replacementOperator; no other changes. This is a literal novice interpretation, not execution of the complete structural trace.',
            'source_operator': old, 'replacement_operator': new, 'before_fragment': span['text'],
            'after_fragment': fragment, 'response': answer}
        if answer['status'] == 'ok':
            behavior = request('/api/behavior', {k: v for k, v in payload.items() if k != 'metric'})
            probe['bounded_behavior'] = {k: behavior[k] for k in ('status', 'score', 'scoreStatus', 'scope', 'sampling') if k in behavior}
            probe['bounded_behavior']['categories'] = [{'id': c['id'], 'status': c['status'], 'instances': len(c['instances'])}
                                                      for c in behavior.get('categories', [])]
        results.append(probe)
        print(exercise, answer['status'], answer.get('distance'))
    args.output.write_text(json.dumps(results, indent=2) + '\n')


if __name__ == '__main__':
    main()
