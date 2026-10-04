#!/usr/bin/env python3
"""JSONL benchmark adapter for FM24 MIN-TED, with optional one-step mutations.

Accepts {case_id,path,predicate,timeout_seconds}; emits deterministic engine
results only. Published Java normalizer, TAR mutants and HiGenA hint generator
run unchanged. The local Python graph index implements cross-fit persistence.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import sys
import time
from native import Native, build_classpath

class Worker:
    def __init__(self, data: Path, baselines: Path, mutations: bool):
        self.mutations = mutations
        self.rows = {r['case_id']: r for r in (json.loads(line) for line in (data / 'cases.jsonl').open())}
        self.graphs = [json.loads((data / f'graphs-fold-{fold}.json').read_text()) for fold in range(5)]
        self.native = Native(build_classpath(baselines, compile_adapter=False))

    def evaluate(self, request):
        start = time.perf_counter()
        limit = float(request.get('timeout_seconds', 60))
        result = {'case_id': request['case_id'], 'hint_available': False, 'java_engine_s': 0.0,
                  'cold_worker': False, 'worker_recycled': False, 'native_action_count': 0,
                  'baseline': 'fm24-mutation' if self.mutations else 'fm24-pure'}
        def ask(payload):
            remaining = limit - (time.perf_counter() - start)
            if remaining <= 0:
                raise TimeoutError('request deadline')
            # One learner query can perform normalization, hint construction,
            # and mutation enumeration. A JVM may recycle between those native
            # actions; preserve any cold/recycle event across the entire query.
            result['native_action_count'] += 1
            result['cold_worker'] |= (self.native.process is None
                                      or self.native.completed_requests == 0)
            answer = self.native.ask(payload, timeout=remaining)
            result['cold_worker'] |= bool(answer.get('native_cold_worker', False))
            result['worker_recycled'] |= bool(answer.get('native_worker_recycled', False))
            result['java_engine_s'] += answer.get('engine_s', 0)
            if answer.get('status') != 'ok':
                raise ValueError(answer.get('error_type', 'native_error'))
            return answer
        try:
            row = self.rows.get(request['case_id'])
            if row is None:
                elapsed = time.perf_counter() - start
                return {**result, 'status': 'normalization_error', 'wall_s': elapsed, 'engine_s': elapsed}
            graph = self.graphs[row['fold']][row['graph']]
            result['zero_range_policy'] = graph.get('zero_range_policy', False)
            if graph.get('policy_status', 'ok') != 'ok':
                elapsed = time.perf_counter() - start
                return {**result, 'status': graph['policy_status'], 'wall_s': elapsed, 'engine_s': elapsed}
            normalized = ask({'action': 'normalize', 'path': request['path'], 'predicate': request['predicate']})
            formula = normalized['formula']
            result['fold'] = row['fold']
            if formula in graph['valid']:
                result['status'] = 'known_correct'
            else:
                target = graph['next'].get(formula)
                if target is not None:
                    answer = ask({'action': 'hint', 'path': request['path'], 'source': formula, 'target': target})
                    result.update({'status': 'hint' if answer['hint_available'] else 'no_hint',
                                   'hint_available': answer['hint_available'], 'hint': answer['hint'],
                                   'target': target, 'source': 'history'})
                else:
                    result['status'] = 'no_hint'
                if not result['hint_available'] and self.mutations:
                    answer = ask({'action': 'mutations', 'path': request['path'], 'predicate': request['predicate']})
                    candidates = answer['mutations']
                    reachable = [(graph['hops'][c['formula']], graph['scores'][c['formula']], c['formula'], i)
                                 for i, c in enumerate(candidates) if c['formula'] in graph['scores']]
                    result['mutation_candidates'] = len(candidates)
                    if reachable:
                        _, score, target, index = min(reachable)
                        # Native implementation selects the first candidate
                        # producing the database's best reachable formula.
                        candidate = next(c for c in candidates if c['formula'] == target)
                        hint = candidate['hint']
                        result.update({'status': 'hint' if hint else 'no_hint', 'hint_available': bool(hint),
                                       'hint': hint, 'target': target, 'source': 'mutation'})
        except TimeoutError:
            result['status'] = 'timeout'
        except Exception as e:
            result.update({'status': 'error', 'error_type': type(e).__name__, 'error': str(e)})
        result['wall_s'] = time.perf_counter() - start
        result['engine_s'] = result['wall_s']
        return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--data', type=Path, required=True)
    parser.add_argument('--baselines', type=Path, required=True)
    parser.add_argument('--mutations', action='store_true')
    args = parser.parse_args()
    worker = Worker(args.data, args.baselines, args.mutations)
    try:
        for line in sys.stdin:
            request = json.loads(line)
            print(json.dumps(worker.evaluate(request), separators=(',', ':')), flush=True)
    finally:
        worker.native.close()

if __name__ == '__main__':
    main()
