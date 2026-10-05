#!/usr/bin/env python3
"""Replay four public tutoring fixtures without credentials or provider calls."""
import hashlib
import json
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT))
import luna


def fixture_inputs():
    inputs = {}
    for name, body, canonical, question, distance, tokens in (
        ('A', 'some Node', 'SOME Node', 'At least one Node must exist.', 0, 2),
        ('B', 'some Node and some Node', 'SOME Node', 'At least one Node must exist.', 0, 5),
        ('C', 'no Node', 'NO Node', 'At least one Node must exist.', 1, 2),
        ('D', 'no Node', 'NO Node', '', 1, 2),
    ):
        operations = []
        if distance:
            operations.append({'kind': 'replace', 'component': 'matrix', 'cost': 1,
                'sourceTerm': body, 'sourceOperator': 'no', 'replacementOperator': 'some',
                'sourceRole': 'affected', 'sourceLocation': {
                    'status': 'located', 'precision': 'node', 'coordinateSystem': 'body',
                    'offsetEncoding': 'utf-16',
                    'ranges': [{'start': 0, 'end': len(body), 'text': body}]}})
        feedback = {'status': 'ok', 'distance': distance, 'canonicalForm': [canonical],
                    'breakdown': {'temporal': 0, 'quantifier': 0, 'matrix': distance},
                    'operations': operations}
        comparison = None if distance else {'status': 'ok', 'measure': 'lexical-tokens',
            'studentTokens': tokens, 'mostConciseKnownTokens': 2,
            'longerThanMostConciseKnown': tokens > 2}
        inputs[name] = luna.prompt_education(feedback, student_body=body, question=question,
                                           solution_comparison=comparison)
    return inputs


def main():
    manifest = json.loads((HERE / 'manifest.json').read_text(encoding='utf-8'))
    for relative, expected in manifest['files'].items():
        actual = hashlib.sha256((ROOT / relative).read_bytes()).hexdigest()
        if actual != expected:
            raise ValueError('Evidence input changed: ' + relative)
    if hashlib.sha256(luna.INSTRUCTIONS.encode('utf-8')).hexdigest() != manifest['instructionsSha256']:
        raise ValueError('Tutor instructions changed')
    inputs = json.loads((HERE / 'fixtures.json').read_text(encoding='utf-8'))
    if inputs != fixture_inputs():
        raise ValueError('Production prompt projection changed')
    outputs = json.loads((HERE / 'tutor-outputs.json').read_text(encoding='utf-8'))
    if set(inputs) != set(outputs):
        raise ValueError('Fixture IDs changed')
    for name, evidence in inputs.items():
        provider = {'status': 'completed', 'output': [{'type': 'message', 'role': 'assistant',
            'content': [{'type': 'output_text', 'text': json.dumps(outputs[name])}]}]}
        validated = luna._education_response(provider, evidence, 'SYNTHETIC_GUIDANCE_REPLAY')
        if validated != outputs[name]:
            raise ValueError('Output projection changed: ' + name)
    print(json.dumps({'status': 'PASS', 'fixtures': len(inputs), 'networkCalls': 0,
                      'credentialReads': 0, 'scope': 'finite-prompt-and-response-boundary'}, sort_keys=True))


if __name__ == '__main__':
    main()
