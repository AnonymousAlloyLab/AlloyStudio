#!/usr/bin/env python3
"""Opt-in real guidance smoke check; never prints configuration or private pools."""
import argparse
import json
from pathlib import Path
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import luna
from ai_provider import select_profile
from server import Portal, model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--live', action='store_true', help='Authorize five configured Luna requests')
    parser.add_argument('--output', type=Path, default=Path('build/structural-guidance-live'))
    args = parser.parse_args()
    if not args.live:
        parser.error('--live is required; this check makes paid provider requests')
    profile = select_profile('guidance', luna.read_key, luna.MODEL, 'low')
    if profile is None or profile.protocol != 'openai' or profile.model != luna.MODEL:
        print(json.dumps({'status': 'unavailable', 'reason': 'Configure OpenAI gpt-6-luna guidance for this check.'}))
        return 1
    args.output.mkdir(parents=True, exist_ok=True)
    app = Portal(('127.0.0.1', 0))
    results = []
    try:
        fixtures = [
            ('multiplicity', 'sig A { r: set A }\n', 'no A', 'some A',
             'There must be at least one object.', True),
            ('binding', 'sig A { r: set A }\n', 'all a: A | some a.r', 'some a: A | some a.r',
             'At least one object must have an outgoing connection.', False),
            ('temporal', 'var sig A {}\n', 'always some A', 'eventually some A',
             'At some point in the trace, at least one object must exist.', False),
            ('matching', 'sig A {}\n', 'some A and some A', 'some A',
             'There must be at least one object.', False),
        ]
        for name, environment, body, reference, question, examples in fixtures:
            record = dict(id=name, predicate='target', predicateHeader='pred target ',
                          environmentBefore=environment, environmentAfter='', description=question,
                          oracleBody=reference)
            payload = dict(metric='canonical', predicate='target', studentSource=model(record, body),
                           referencePrefix=environment+'pred target {\n', referenceSuffix='\n}',
                           referenceBodies=[reference])
            feedback = app._feedback(record, body, 'canonical', payload)
            behavior = app._behavior(app.behavior_payload(record, body)) if examples else None
            results.append(check(app, name, record, body, feedback, behavior, [reference], args.output))
            print(json.dumps(results[-1]), flush=True)
        record = app.exercises['graphs-inv7']
        body = record['starter']
        results.append(check(app, 'graphs-inv7', record, body, app.evaluate(record, body),
                             None, app.correct_pools[record['id']], args.output))
        print(json.dumps(results[-1]), flush=True)
    finally:
        app.server_close()
    report = {'status': 'PASS' if all(row['status'] == 'ok' for row in results) else 'FAIL',
              'model': profile.model, 'cases': results}
    (args.output/'summary.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    return 0 if report['status'] == 'PASS' else 1


def check(app, name, record, body, feedback, behavior, references, output):
    if feedback.get('status') != 'ok':
        return {'case': name, 'status': 'engine_unavailable'}
    comparison = luna.solution_length_comparison(body, references) if feedback['distance'] == 0 else None
    options = dict(student_body=body, behavior=behavior if behavior and behavior.get('status') == 'ok' else None,
                   question=record['description'], solution_comparison=comparison)
    evidence = luna.prompt_education(feedback, **options)
    started = time.monotonic()
    answer = app.explainer.explain(feedback, **options)
    elapsed = time.monotonic()-started
    # These are the already projected learner/provider payloads, never the
    # request's private reference model or credential/provider configuration.
    (output/(name+'.json')).write_text(json.dumps({'evidence': evidence, 'answer': answer},
                                                 ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    context = evidence.get('structuralContext', {})
    expected_operations, expected_instances = luna._expected_ids(evidence)
    valid = (answer.get('status') == 'ok'
             and [item['id'] for item in answer['operations']] == expected_operations
             and [item['id'] for item in answer['instances']] == expected_instances
             and (bool(context.get('overview')) if feedback['distance'] == 0 else
                  any(step.get('dsl') for step in context.get('steps', []))))
    return {'case': name, 'status': 'ok' if valid else 'guidance_unavailable',
            'distance': feedback['distance'], 'operations': len(expected_operations),
            'instances': len(expected_instances), 'structure': feedback.get('canonicalStructure', {}).get('status'),
            'retrieval': context.get('status'), 'positionedCost': context.get('positionedCost'),
            'residualCost': context.get('residualCost'), 'seconds': round(elapsed, 3)}


if __name__ == '__main__':
    raise SystemExit(main())
