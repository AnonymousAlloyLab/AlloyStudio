#!/usr/bin/env python3
"""Five-fold path-held-out MIN-TED graphs with original FM24 normalizer/APTED.

Storage and multi-source Dijkstra replace MongoDB/Quarkus only. This is a
database-free adaptation, not an execution of the authors' REST deployment.
All expression normalization, edge distances, mutation candidates, and hints
are obtained from original published Java artifacts.
"""
from __future__ import annotations
import argparse
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
import hashlib
import heapq
import json
from pathlib import Path
import threading
import time
from native import Native, build_classpath

LOCAL = threading.local()
ENGINES = []

def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'))

def engine(cp):
    if not hasattr(LOCAL, 'engine'):
        LOCAL.engine = Native(cp)
        ENGINES.append(LOCAL.engine)
    return LOCAL.engine

def key(row):
    # Match the production comparison's environment partition. Historical
    # execution roots for the same exercise/environment can share evidence.
    return row['group'] + '/' + row['predicate'] + '/' + row['environment_sha256'] + '/' + row['oracle_token_sha256']

def policy(incoming, valid):
    """Upstream MIN-TED scaling; surface undefined constant-range policies."""
    weights = [d for sources in incoming.values() for d in sources.values()]
    minimum = min(weights) if weights else 0.0
    maximum = max(weights) if weights else 0.0
    zero_range = bool(weights) and maximum == minimum
    scores = {f: 0.0 for f in valid}
    hops = {f: 0 for f in valid}
    next_state = {}
    if not zero_range:
        queue = [(0.0, f) for f in sorted(valid)]
        heapq.heapify(queue)
        while queue:
            score, target = heapq.heappop(queue)
            if score != scores[target]:
                continue
            for source, weight in sorted(incoming.get(target, {}).items()):
                scaled = (weight - minimum) / (maximum - minimum)
                proposal = score + scaled
                if proposal < scores.get(source, float('inf')):
                    scores[source] = proposal
                    hops[source] = hops[target] + 1
                    next_state[source] = target
                    heapq.heappush(queue, (proposal, source))
    return {'scores': scores, 'hops': hops, 'next': next_state, 'ted_min': minimum, 'ted_max': maximum,
            'zero_range_policy': zero_range,
            'policy_status': 'upstream_equal_weight_graph' if zero_range else 'ok'}

def prepare(args):
    start = time.perf_counter()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(line) for line in args.payloads.open()]
    evaluation = {json.loads(line)['case_id'] for line in args.evaluation_cases.open()}
    lineage = json.loads(args.lineage.read_text())
    metadata = json.loads((args.baselines / 'alloy4fun-zenodo-8123547/metadata-index.json').read_text())
    cp = build_classpath(args.baselines)
    normal_file = args.output / 'normalized.jsonl'
    existing = {}
    if normal_file.exists():
        for line in normal_file.open():
            row = json.loads(line)
            existing[row['case_id']] = row
    normalizations_reused = len(existing)
    def normalize(row):
        if row['case_id'] in existing:
            return existing[row['case_id']]
        try:
            result = engine(cp).ask({'path': row['path'], 'predicate': row['predicate'], 'oracle': row['predicate'] + 'c'}, timeout=60)
        except Exception as e:
            result = {'status': 'error', 'error_type': type(e).__name__}
        return {'case_id': row['case_id'], **result}
    with ThreadPoolExecutor(max_workers=args.workers) as pool, normal_file.open('a') as out:
        for index, norm in enumerate(pool.map(normalize, rows)):
            if norm['case_id'] not in existing:
                out.write(canonical(norm) + '\n')
                out.flush()
                existing[norm['case_id']] = norm
            if index % 5000 == 0:
                print(canonical({'stage': 'normalize', 'done': index, 'seconds': time.perf_counter() - start}), flush=True)
    normalized_at = time.perf_counter()
    byid = {row['model_id']: row for row in rows}
    records = []
    witnesses = {}
    oracles = defaultdict(set)
    for row in rows:
        norm = existing[row['case_id']]
        if norm['status'] != 'ok':
            continue
        graph = key(row)
        formula = norm['formula']
        oracle = norm['oracle_formula']
        # Find the previous surviving submission to the same challenge. Skipped
        # executions, other questions and excluded AST-identical states preserve
        # ancestry rather than inventing adjacency by file or timestamp order.
        parent = lineage[row['model_id']]['derivationOf']
        original = lineage[row['model_id']]['original']
        seen = set()
        # ExprStringify.stringify(empty predicate) is the empty string; the
        # upstream Alloy parser accepts it as true (literal "true" is invalid).
        source = ''
        while parent and parent != original and parent in metadata:
            if parent in seen:
                raise ValueError('Cyclic predecessor')
            seen.add(parent)
            candidate = byid.get(parent)
            if candidate and key(candidate) == graph:
                prev = existing[candidate['case_id']]
                if prev['status'] == 'ok':
                    source = prev['formula']
                    if lineage[parent]['fold'] != lineage[row['model_id']]['fold']:
                        raise ValueError('Student edge crosses held-out folds')
                    break
            parent = metadata[parent]['derivationOf']
        record = {'case_id': row['case_id'], 'graph': graph, 'formula': formula, 'source': source,
                  'fold': lineage[row['model_id']]['fold'], 'valid': row['cohort_status'] == 'CORRECT', 'path': row['path']}
        records.append(record)
        witnesses.setdefault(graph, row['path'])
        oracles[graph].add(oracle)
    unique_edges = {(r['graph'], r['source'], r['formula']) for r in records if r['source'] != r['formula']}
    edge_file = args.output / 'edge_distances.jsonl'
    distances = {}
    if edge_file.exists():
        for line in edge_file.open():
            item = json.loads(line)
            if tuple(item['edge']) in unique_edges:
                distances[tuple(item['edge'])] = item
    distances_reused = len(distances)
    def distance(edge):
        if edge in distances:
            return distances[edge]
        g, source, target = edge
        try:
            result = engine(cp).ask({'action': 'distance', 'path': witnesses[g], 'source': source, 'target': target}, timeout=60)
        except Exception as e:
            result = {'status': 'error', 'error_type': type(e).__name__}
        return {'edge': list(edge), **result}
    with ThreadPoolExecutor(max_workers=args.workers) as pool, edge_file.open('a') as out:
        for index, item in enumerate(pool.map(distance, sorted(unique_edges))):
            edge = tuple(item['edge'])
            if edge not in distances:
                out.write(canonical(item) + '\n')
                out.flush()
                distances[edge] = item
            if index % 5000 == 0:
                print(canonical({'stage': 'distances', 'done': index, 'total': len(unique_edges), 'seconds': time.perf_counter() - start}), flush=True)
    policy_at = time.perf_counter()
    summary_folds = []
    for fold in range(5):
        groups = defaultdict(list)
        for r in records:
            if r['fold'] != fold:
                groups[r['graph']].append(r)
        graphs = {}
        for g in sorted(oracles):
            training = groups[g]
            labels = defaultdict(set)
            for r in training:
                labels[r['formula']].add(r['valid'])
            valid = set(oracles[g]) | {f for f, outcomes in labels.items() if outcomes == {True}}
            # Conflicting corpus labels are quarantined; never silently upgrade
            # a historical normalized node to a trusted correct terminal.
            conflicts = {f for f, outcomes in labels.items() if len(outcomes) > 1}
            valid -= conflicts
            incoming = defaultdict(dict)
            failed_edges = set()
            for r in training:
                source, target = r['source'], r['formula']
                if source == target or source in valid or source in conflicts or target in conflicts:
                    continue
                d = distances[(g, source, target)]
                if d['status'] == 'ok':
                    incoming[target][source] = d['distance']
                else:
                    failed_edges.add((source, target))
            # Var.TED.normalizeByGraph scales BEFORE summing. Subtracting the
            # minimum changes path choice; raw TED sums are not equivalent.
            # The native implementation produces NaN on a constant range. We
            # report those graphs unsupported rather than crediting hints to an
            # unreported robustness change. Lexical ties replace DB ordering.
            graphs[g] = {'valid': sorted(valid), **policy(incoming, valid),
                         'conflicts': sorted(conflicts), 'training_cases': len(training),
                         'failed_edges': len(failed_edges),
                         }
            if failed_edges:
                # Never silently turn a partial native graph into an apparent
                # baseline success. The production artifact cannot round-trip
                # every normalized expression; this graph is unsupported.
                graphs[g]['policy_status'] = 'native_edge_error'
        path = args.output / f'graphs-fold-{fold}.json'
        path.write_text(canonical(graphs) + '\n')
        summary_folds.append({'fold': fold, 'graph_count': len(graphs), 'training_cases': sum(g['training_cases'] for g in graphs.values()),
                              'reachable_states': sum(len(g['scores']) for g in graphs.values()),
                              'conflicting_states': sum(len(g['conflicts']) for g in graphs.values()),
                              'zero_range_policy_graphs': sum(g['zero_range_policy'] for g in graphs.values()),
                              'native_edge_error_graphs': sum(g['policy_status'] == 'native_edge_error' for g in graphs.values()),
                              'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    (args.output / 'cases.jsonl').write_text(''.join(canonical(r) + '\n' for r in records if r['case_id'] in evaluation))
    (args.output / 'training-inventory.jsonl').write_text(''.join(canonical(r) + '\n' for r in records))
    for e in ENGINES:
        e.close()
    summary = {'status': 'completed', 'cases': len(rows), 'normalized': len(records),
               'normalizations_reused': normalizations_reused, 'distances_reused': distances_reused,
               'evaluation_cases': len(evaluation), 'historical_only_cases': len(rows) - len(evaluation),
               'evaluated_normalized_cases': sum(r['case_id'] in evaluation for r in records),
               'normalization_statuses': dict(Counter(n['status'] for n in existing.values())),
               'unique_edges': len(unique_edges), 'edge_statuses': dict(Counter(d['status'] for d in distances.values())),
               'normalization_s': normalized_at - start, 'edge_distance_s': policy_at - normalized_at,
               'policy_s': time.perf_counter() - policy_at, 'total_s': time.perf_counter() - start,
               'folds': summary_folds}
    (args.output / 'preprocessing.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(json.dumps(summary, indent=2))

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--payloads', type=Path, required=True)
    p.add_argument('--evaluation-cases', type=Path, required=True)
    p.add_argument('--lineage', type=Path, required=True)
    p.add_argument('--baselines', type=Path, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--workers', type=int, default=4)
    prepare(p.parse_args())
