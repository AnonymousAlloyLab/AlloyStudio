#!/usr/bin/env python3
"""Import private nearest-correct pools with exact preserved-context witnesses.

Correctness labels are inherited from the classified-data/correct corpus, not
proved here. Each admitted body comes from precisely the learner's unchanged
supporting environment. Every exercise also retains one explicit oracle.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))
from scripts.import_exercises import extract_model, tokens


class PoolError(ValueError):
    pass


def canonical(value):
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False) + "\n").encode("utf-8")


def sha(data):
    return hashlib.sha256(data).hexdigest()


def environment_sha256(record):
    """Bind all untouched bytes and the original target declaration header."""
    return sha(canonical([record["environmentBefore"], record["predicateHeader"], record["environmentAfter"]]))


def body_token_sha256(body):
    """Conservative lexical deduplication, preserving operator token adjacency.

    The shared scanner splits punctuation into bytes. Retaining adjacency avoids
    merging distinct spellings such as a compound operator and separate symbols.
    Whitespace quantities and comments between tokens are ignored; the fact that
    a separator exists is retained. This is deliberately finer than AST identity.
    """
    ts = tokens(body.encode("utf-8"))
    return sha(canonical([[token.value.hex(), index > 0 and ts[index - 1].end == token.start]
                          for index, token in enumerate(ts)]))


def source_record(record, path, data, status):
    return {"path": path, "sha256": sha(data), "status": status}


def candidate(kind, body, context_hash, source, original):
    token_hash = body_token_sha256(body)
    return {"id": ("oracle-" if kind == "oracle" else "correct-") + token_hash,
            "kind": kind, "body": body, "bodySha256": sha(body.encode("utf-8")),
            "tokenSha256": token_hash, "environmentSha256": context_hash,
            "source": source, "originalSource": original}


def verify_pool(record, pool):
    try:
        return _verify_pool(record, pool)
    except (KeyError, TypeError, AttributeError, UnicodeError) as error:
        raise PoolError("Malformed correct-pool schema or source witness") from error


def _verify_pool(record, pool):
    """Check all candidate witnesses offline; no original ACGN checkout needed."""
    if pool.get("exerciseId") != record["id"]:
        raise PoolError("Pool exercise does not match catalogue record")
    context_hash = environment_sha256(record)
    if pool.get("environmentSha256") != context_hash:
        raise PoolError("Pool environment binding changed")
    candidates = pool.get("candidates")
    if not isinstance(candidates, list) or not candidates:
        raise PoolError("Correct pool is empty")
    if sum(c.get("kind") == "oracle" for c in candidates) != 1 or candidates[-1].get("kind") != "oracle":
        raise PoolError("Each pool must end with exactly one explicit oracle")
    seen = set()
    order = []
    for item in candidates:
        kind = item.get("kind")
        if kind not in ("oracle", "correct-student"):
            raise PoolError("Unregistered reference kind")
        body = item["body"]
        token_hash = body_token_sha256(body)
        if not body.strip() or token_hash != item["tokenSha256"] or token_hash in seen:
            raise PoolError("Candidate token hash is invalid or duplicated")
        seen.add(token_hash)
        if sha(body.encode("utf-8")) != item["bodySha256"]:
            raise PoolError("Candidate body hash changed")
        if item["id"] != ("oracle-" if kind == "oracle" else "correct-") + token_hash:
            raise PoolError("Candidate identity is not content bound")
        if item["environmentSha256"] != context_hash:
            raise PoolError("Candidate environment binding changed")
        original = item["originalSource"].encode("utf-8")
        provenance = item["source"]
        if sha(original) != provenance["sha256"]:
            raise PoolError("Candidate original-source witness hash changed")
        path = Path(provenance["path"])
        if path.is_absolute() or ".." in path.parts or len(path.parts) != 4:
            raise PoolError("Invalid source provenance path")
        if path.parts[0] != "classified-data" or path.parts[1] != record["group"]:
            raise PoolError("Candidate source belongs to another exercise group")
        if not path.name.endswith("_" + record["predicate"] + ".als"):
            raise PoolError("Candidate source belongs to another predicate")
        extracted = extract_model(original, record["predicate"])
        if environment_sha256(extracted) != context_hash:
            raise PoolError("Candidate witness has a different supporting environment")
        if body_token_sha256(extracted["oracleBody"]) != body_token_sha256(record["oracleBody"]):
            raise PoolError("Candidate witness has a different oracle")
        if kind == "oracle":
            if body != record["oracleBody"] or body != extracted["oracleBody"]:
                raise PoolError("Explicit oracle does not match selected exercise")
            if provenance != dict(record["source"], status=record["sourceClassification"]):
                raise PoolError("Oracle witness does not match catalogue provenance")
            if item["originalSource"] != record["originalSource"]:
                raise PoolError("Oracle source witness differs from selected catalogue source")
        else:
            if provenance["status"] != "correct" or path.parts[2] != "correct":
                raise PoolError("Student candidate lacks corpus correct classification")
            if body != extracted["starter"]:
                raise PoolError("Candidate is not the witnessed correct student body")
            order.append((token_hash, provenance["path"]))
    if order != sorted(order):
        raise PoolError("Correct candidates are not in deterministic tie order")


def verify_document(catalogue, document):
    try:
        return _verify_document(catalogue, document)
    except (KeyError, TypeError, AttributeError, UnicodeError) as error:
        raise PoolError("Malformed correct-pool document") from error


def _verify_document(catalogue, document):
    """Check coverage, all pool witnesses, source inventory, and exclusions."""
    if document.get("schemaVersion") != 1:
        raise PoolError("Unsupported correct-pool schema")
    records = {r["id"]: r for r in catalogue["exercises"]}
    pools = document["pools"]
    if len(pools) != len(records) or {p["exerciseId"] for p in pools} != set(records):
        raise PoolError("Correct pools do not cover the catalogue exactly once")
    inventory = document["sourceInventory"]
    source_by_path = {item["path"]: item for item in inventory}
    if len(source_by_path) != len(inventory) or inventory != sorted(inventory, key=lambda item: item["path"]):
        raise PoolError("Source inventory is duplicated or unordered")
    for item in inventory:
        parts = Path(item["path"]).parts
        if len(parts) != 4 or parts[0] != "classified-data" or parts[2] != "correct" or item["status"] != "correct":
            raise PoolError("Inventory contains a non-correct source")
        if item["exerciseId"] not in records or item["bytes"] <= 0 or len(item["sha256"]) != 64:
            raise PoolError("Invalid inventory record")
        record = records[item["exerciseId"]]
        if parts[1] != record["group"] or not parts[3].endswith("_" + record["predicate"] + ".als"):
            raise PoolError("Inventory source is assigned to the wrong exercise")
    for pool in pools:
        record = records[pool["exerciseId"]]
        verify_pool(record, pool)
        for item in pool["candidates"]:
            if item["kind"] == "correct-student":
                entry = source_by_path.get(item["source"]["path"])
                if not entry or entry["sha256"] != item["source"]["sha256"] or entry["exerciseId"] != record["id"]:
                    raise PoolError("Candidate is orphaned from source inventory")
                if entry["bytes"] != len(item["originalSource"].encode("utf-8")):
                    raise PoolError("Candidate inventory byte count changed")
    excluded = document["excludedSources"]
    excluded_paths = set()
    for item in excluded:
        path = item["source"]["path"]
        if path in excluded_paths or path not in source_by_path:
            raise PoolError("Excluded source is duplicated or orphaned")
        excluded_paths.add(path)
        entry = source_by_path[path]
        if item["source"] != {key: entry[key] for key in ("path", "sha256", "status")}:
            raise PoolError("Excluded provenance differs from inventory")
        raw = item["originalSource"].encode("utf-8")
        if sha(raw) != entry["sha256"] or item["source"]["sha256"] != entry["sha256"]:
            raise PoolError("Excluded source witness changed")
        record = records[entry["exerciseId"]]
        extracted = extract_model(raw, record["predicate"])
        observed = environment_sha256(extracted)
        if observed != item["environmentSha256"]:
            raise PoolError("Excluded context binding changed")
        if item["reason"] == "support-context-mismatch":
            if observed == environment_sha256(record):
                raise PoolError("Compatible source was falsely excluded")
        elif item["reason"] == "oracle-mismatch":
            if body_token_sha256(extracted["oracleBody"]) == body_token_sha256(record["oracleBody"]):
                raise PoolError("Matching oracle was falsely excluded")
        else:
            raise PoolError("Unregistered exclusion reason")
    p = document["provenance"]
    counts = {"sourceFiles": len(inventory), "excludedSources": len(excluded),
              "eligibleSourceFiles": len(inventory) - len(excluded), "pools": len(pools),
              "candidateCount": sum(len(pool["candidates"]) for pool in pools),
              "oracleCandidates": len(pools),
              "correctStudentCandidates": sum(len(pool["candidates"]) - 1 for pool in pools),
              "oracleOnlyPools": sum(len(pool["candidates"]) == 1 for pool in pools)}
    if any(p.get(key) != value for key, value in counts.items()):
        raise PoolError("Declared pool counts do not match witnesses")
    if p["correctStudentCandidates"] + p["duplicateCorrectSources"] + p["correctSourcesRepresentedByOracle"] != p["eligibleSourceFiles"]:
        raise PoolError("Correct-source deduplication counts do not balance")


def build_document(catalogue, source_root):
    by_key = {(r["group"], r["predicate"]): r for r in catalogue["exercises"]}
    correct = {r["id"]: {} for r in catalogue["exercises"]}
    contexts = {r["id"]: environment_sha256(r) for r in catalogue["exercises"]}
    oracle_keys = {r["id"]: body_token_sha256(r["oracleBody"]) for r in catalogue["exercises"]}
    inventory, excluded = [], []
    oracle_duplicates = duplicates = 0
    for path in sorted((source_root / "classified-data").glob("*/correct/*.als")):
        key = (path.parent.parent.name, path.stem.rsplit("_", 1)[-1])
        if key not in by_key:
            raise PoolError("Correct source belongs to an unknown catalogue exercise")
        record = by_key[key]
        data = path.read_bytes()
        relative = path.relative_to(source_root).as_posix()
        source = source_record(record, relative, data, "correct")
        inventory.append(dict(source, exerciseId=record["id"], bytes=len(data)))
        extracted = extract_model(data, record["predicate"])
        context_hash = environment_sha256(extracted)
        reason = "support-context-mismatch" if context_hash != contexts[record["id"]] else (
            "oracle-mismatch" if body_token_sha256(extracted["oracleBody"]) != oracle_keys[record["id"]] else None)
        if reason:
            excluded.append({"source": source, "reason": reason, "environmentSha256": context_hash,
                             "originalSource": extracted["originalSource"]})
            continue
        item = candidate("correct-student", extracted["starter"], context_hash, source, extracted["originalSource"])
        if item["tokenSha256"] == oracle_keys[record["id"]]:
            oracle_duplicates += 1
            continue
        if item["tokenSha256"] in correct[record["id"]]:
            duplicates += 1
            continue
        correct[record["id"]][item["tokenSha256"]] = item
    if not inventory:
        raise PoolError("No classified correct sources were found")
    pools = []
    for record in catalogue["exercises"]:
        students = sorted(correct[record["id"]].values(), key=lambda item: (item["tokenSha256"], item["source"]["path"]))
        oracle = candidate("oracle", record["oracleBody"], contexts[record["id"]],
                           dict(record["source"], status=record["sourceClassification"]), record["originalSource"])
        pools.append({"exerciseId": record["id"], "environmentSha256": contexts[record["id"]],
                      "candidates": students + [oracle]})
    document = {"schemaVersion": 1, "provenance": {
        "corpus": "ACGN/classified-data", "sourceFiles": len(inventory), "excludedSources": len(excluded),
        "eligibleSourceFiles": len(inventory) - len(excluded), "pools": len(pools),
        "candidateCount": sum(len(pool["candidates"]) for pool in pools), "oracleCandidates": len(pools),
        "correctStudentCandidates": sum(len(pool["candidates"]) - 1 for pool in pools),
        "oracleOnlyPools": sum(len(pool["candidates"]) == 1 for pool in pools),
        "duplicateCorrectSources": duplicates, "correctSourcesRepresentedByOracle": oracle_duplicates,
        "supportContextPolicy": "Exact UTF-8 bytes of environmentBefore, predicateHeader and environmentAfter must match the selected exercise; learner environment is never changed.",
        "oraclePolicy": "Exactly one explicit selected-catalogue oracle per pool, last; duplicate correct bodies are represented by that oracle.",
        "deduplicationPolicy": "Equal lexical token bytes with equal token adjacency; conservative relative to AST identity. First source path wins; ties rank sorted correct token hashes before oracle.",
        "labelTrust": "Correct-student status is inherited from the source folder. This import checks provenance and context, not unbounded Alloy semantic correctness.",
        "sourceInventoryTrust": "Every correct source path/hash is recorded; admitted representative and excluded sources carry offline-verifiable original-source witnesses. Nonrepresentative duplicate file hashes are recorded provenance, not separately witnessed bodies."
    }, "sourceInventory": inventory, "excludedSources": excluded, "pools": pools}
    verify_document(catalogue, document)
    return document


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path,
                        default=Path(os.environ.get("ACGN_ROOT", ROOT.parent / "ACGN")))
    parser.add_argument("--catalogue", type=Path, default=ROOT / "exercises/catalogue.json")
    parser.add_argument("--output", type=Path, default=ROOT / "exercises/correct-pools.json")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    catalogue = json.loads(args.catalogue.read_text())
    started = time.monotonic()
    if args.verify_only:
        document = json.loads(args.output.read_text())
        verify_document(catalogue, document)
    else:
        document = build_document(catalogue, args.source_root.resolve())
        encoded = canonical(document)
        if args.check:
            if args.output.read_bytes() != encoded:
                raise PoolError("Correct pools differ from deterministic regeneration")
        else:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_bytes(encoded)
    print(json.dumps({"path": str(args.output), "sha256": sha(args.output.read_bytes()),
                      "pools": len(document["pools"]), "candidates": document["provenance"]["candidateCount"],
                      "excluded": len(document["excludedSources"]), "seconds": round(time.monotonic() - started, 3)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
