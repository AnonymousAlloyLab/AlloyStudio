#!/usr/bin/env python3
"""Extract the frozen hint-quality pilot baselines from saved benchmark JSONL.

This reads archives sequentially and omits private candidate/target fields.
Native hint strings can still disclose target operands. Output is local research
evidence; the separate publisher redacts those strings for the public report.
"""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build/benchmarks/alloy4fun-v2"
STUDY = ROOT / "build/benchmarks/hint-quality-pilot"
SOURCES = ("tar", "fm24-history", "fm24-mutation", "live-full", "ast-full")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_matches(path: Path, wanted: set[tuple[str, str]], *, compressed=False):
    op = gzip.open if compressed else open
    matches = {}
    with op(path, "rb") as f:
        for raw in f:
            obj = json.loads(raw)
            key = (obj.get("case_id"), obj.get("source_sha256"))
            if key in wanted:
                if key in matches:
                    raise ValueError("Duplicate selected result record")
                matches[key] = (obj, hashlib.sha256(raw).hexdigest())
    return matches


def match_response(path: Path, wanted: set[tuple[str, str]], result_hashes: dict,
                   *, tar=False):
    matches = {}
    with gzip.open(path, "rb") as f:
        for raw in f:
            obj = json.loads(raw)
            key = (obj.get("case_id"), obj.get("source_sha256")) if tar else None
            if not tar:
                case = obj.get("case_id")
                key = next((k for k in wanted if k[0] == case), None)
            if key not in wanted:
                continue
            if key in matches:
                raise ValueError("Duplicate selected response record")
            if tar and obj.get("result_sha256") != result_hashes[key]:
                raise ValueError("TAR response/result binding does not match")
            matches[key] = (obj, hashlib.sha256(raw).hexdigest())
    return matches


def strings(value):
    if isinstance(value, str):
        return [value]
    if isinstance(value, dict):
        return sum((strings(v) for v in value.values()), [])
    if isinstance(value, list):
        return sum((strings(v) for v in value), [])
    return []


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selection', type=Path, default=STUDY / 'selection.json')
    parser.add_argument('--data', type=Path, default=BUILD)
    parser.add_argument('--output', type=Path, default=STUDY / 'baselines.json')
    args = parser.parse_args()
    if args.output.exists():
        parser.error('Use a new output file; existing evidence is preserved.')
    selection_path = args.selection
    provenance = json.loads((ROOT / 'docs/benchmarks/alloy4fun-results.json').read_text())['provenance']
    registered = provenance['result_snapshots']
    audit_path = args.data / 'evidence-audit.json'
    if sha256(audit_path) != provenance['raw_response_audit']['sha256']:
        raise ValueError('Saved response audit differs from the published report')
    response_audit = {row['tool']: row for row in json.loads(audit_path.read_text())['tools']}
    registered_tools = {'tar': 'tar-depth-2', 'live-full': 'live-canonical', 'ast-full': 'live-ast'}
    selection = json.loads(selection_path.read_text())
    cases = selection["cases"]
    wanted = {(c["case_id"], c["source_sha256"]) for c in cases}
    results, responses, archive_hashes = {}, {}, {}
    for source in SOURCES:
        base = args.data / source
        rp, hp = base / "results.jsonl", base / "responses.jsonl.gz"
        archive_hashes[source] = {"results_jsonl_sha256": sha256(rp),
                                  "responses_jsonl_gz_sha256": sha256(hp)}
        if archive_hashes[source]['results_jsonl_sha256'] != registered[registered_tools.get(source, source)]['sha256']:
            raise ValueError('Baseline differs from the published completed run: ' + source)
        if archive_hashes[source]['responses_jsonl_gz_sha256'] != response_audit[registered_tools.get(source, source)]['sha256']['responses.jsonl.gz']:
            raise ValueError('Native responses differ from the published audit: ' + source)
        results[source] = read_matches(rp, wanted)
        result_hashes = {k: hashlib.sha256(json.dumps(v[0], sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
                         for k, v in results[source].items()}
        responses[source] = match_response(hp, wanted, result_hashes,
                                            tar=(source == "tar"))
        if len(results[source]) != len(cases) or len(responses[source]) != len(cases):
            raise RuntimeError(f"{source}: expected {len(cases)} result and response records; "
                               f"got {len(results[source])}, {len(responses[source])}")

    output = {"schema": 1, "selection_sha256": sha256(selection_path),
              "archive_sha256": archive_hashes, "cases": []}
    for c in cases:
        key = (c["case_id"], c["source_sha256"])
        row = {"number": c["number"], "exercise_id": c["exercise_id"],
               "case_id": c["case_id"], "source_sha256": c["source_sha256"],
               "source_classification": c["source_classification"], "engines": {}}
        for source in SOURCES:
            r, rh = results[source][key]
            wrapper, ph = responses[source][key]
            response = wrapper.get("response", {})
            item = {"status": r.get("status"), "cohort_status": r.get("cohort_status"),
                    "supported": r.get("supported"), "hint_available": r.get("hint_available"),
                    "timed_out": r.get("timed_out"), "fold": r.get("fold"),
                    "result_record_sha256": rh, "response_record_sha256": ph}
            if source == "tar":
                native = response.get("native_result") or {}
                trace = native.get("native_trace") or []
                target_exprs = strings(native.get("solution")) + strings(response.get("candidate_repair"))
                native_hints = []
                for entry in trace:
                    hint = entry.get("hint")
                    if hint is None:
                        continue
                    native_hints.append({"hint": hint, "operation": entry.get("operation"),
                                         "line": entry.get("line"), "column": entry.get("column"),
                                         "end_line": entry.get("end_line"),
                                         "end_column": entry.get("end_column"),
                                         "contains_complete_candidate_substring": any(x and x in hint for x in target_exprs)})
                item["native_hints"] = native_hints
                item["native_hint_available"] = response.get("native_hint_available")
                item["bounded_verification"] = {
                    "verified_correct": r.get("verified_correct"),
                    "independent_validation_status": (response.get("independent_validation") or {}).get("status"),
                    "native_solved": native.get("solved"), "native_timed_out": native.get("timed_out"),
                    "native_depth": native.get("depth"), "max_depth": native.get("max_depth")}
            elif source.startswith("fm24-"):
                item["hint_source"] = r.get("hint_source")
                item["native_action_count"] = r.get("native_action_count")
                item["mutation_candidates"] = r.get("mutation_candidates")
                item["native_hint"] = response.get("hint") or response.get("native_hint")
            else:
                item.update({"distance": r.get("distance"), "operation_count": r.get("operation_count"),
                             "located_operations": r.get("located_operations"),
                             "canonical_located_operations": r.get("canonical_located_operations"),
                             "aggregate_operation_count": r.get("aggregate_operation_count"),
                             "reference_count": r.get("reference_count"),
                             "pool_size": (response.get("comparison") or {}).get("poolSize"),
                             "evaluated_candidates": (response.get("comparison") or {}).get("evaluatedCandidates"),
                             "trace_verified": (response.get("trace") or {}).get("matchesDistance")})
            row["engines"][source] = item
        output["cases"].append(row)
    out = args.output
    out.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n")
    print(f"wrote {out.resolve()} ({len(cases)} cases x {len(SOURCES)} sources)")


if __name__ == "__main__":
    main()
