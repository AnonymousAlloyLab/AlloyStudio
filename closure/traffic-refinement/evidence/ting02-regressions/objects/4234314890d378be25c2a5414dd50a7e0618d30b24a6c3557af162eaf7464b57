#!/usr/bin/env python3
"""Check the repaired quantifier-body display using the original public witnesses.

Requires the production engine and benchmark LiveWorker bridge to be built.
No corpus, reference solutions, credentials, or network calls are used.
"""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from benchmarks.alloy4fun.live.adapter import Worker


def main():
    destination = ROOT / 'build/benchmarks/canonical-display-witness'
    destination.mkdir(parents=True, exist_ok=True)
    classpath = os.pathsep.join(str(ROOT / path) for path in (
        'build/benchmarks/alloy4fun/classes', 'build/engine/classes', 'vendor/acgn/lib/*'))
    records = []
    # The parent removes only this owned directory after the JVM has exited,
    # including when the benchmark transport kills the worker.
    with tempfile.TemporaryDirectory(prefix='java-', dir=destination) as scratch:
        worker = Worker(command=['java', '-Xmx512m', '-XX:ActiveProcessorCount=1',
            '-XX:+UseSerialGC', '-Djava.io.tmpdir=' + scratch, '-cp', classpath,
            'benchmark.LiveWorker'])
        try:
            for quantifier in ('lone', 'one'):
                sources = ['sig A { r: set A }\npred inv1 { some A and (' + quantifier
                           + ' a: A | ' + condition + ' a.r) }' for condition in ('some', 'no')]
                requests = [{'studentSource': sources[i], 'oracleSource': sources[1 - i],
                             'predicate': 'inv1', 'metric': 'canonical'} for i in (0, 1)]
                responses = [worker.request(request)[0] for request in requests]
                assert all(result['status'] == 'ok' and result['distance'] == 1 for result in responses)
                assert responses[0]['canonicalForm'] != responses[1]['canonicalForm']
                for i, result in enumerate(responses):
                    operation = result['operations'][0]
                    assert operation['canonicalLocation']['status'] == 'located'
                    assert operation['canonicalLocation']['precision'] == 'node'
                    location = operation['sourceLocation']
                    assert location['status'] == 'located' and location['precision'] == 'node'
                    span = location['ranges'][0]
                    assert sources[i][span['start']:span['end']] == ('some a.r' if i == 0 else 'no a.r')
                records.append({'quantifier': quantifier, 'requests': requests, 'responses': responses})
        finally:
            worker.close()
    report = {
        'status': 'VERIFIED_RENDERER_FIX', 'witnesses': records,
        'owned_temporary_directory_removed': not Path(scratch).exists(),
        'source_sha256': {path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest() for path in (
            'vendor/acgn/src/is/fivefivefive/CanDis/core/CanonicalDistance.java',
            'engine/src/live/CanonicalLocator.java', 'engine/src/live/LiveFeedback.java')},
    }
    output = destination / 'report.json'
    output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'witnesses': len(records), 'distance_each': 1,
                      'canonical_strings_identical': False, 'raw_locations_correct': True,
                      'canonical_locations_unavailable': False,
                      'owned_temporary_directory_removed': report['owned_temporary_directory_removed'],
                      'output': str(output)}))


if __name__ == '__main__':
    main()
