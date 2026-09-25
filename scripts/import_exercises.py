#!/usr/bin/env python3
"""Import the ACGN exercise corpus without reconstructing its Alloy environment.

The output is PRIVATE server data. Never place it under a static/public directory.
All source offsets are UTF-8 byte offsets, and retained environment segments are
copied verbatim. A small lexical scanner ignores comments and strings when
matching braces. Unknown or ambiguous grading harnesses fail closed.
"""

from __future__ import annotations

import argparse
from collections import defaultdict
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_FIELDS = (
    "id", "title", "group", "predicate", "description", "environmentBefore",
    "environmentAfter", "predicateHeader", "starter", "source",
)
STATUS_ORDER = {"under": 0, "over": 1, "both": 2, "correct": 3}
GRAPH_DESCRIPTIONS = {
    "inv1": "Every directed edge has its reverse edge: the graph is undirected.",
    "inv2": "No pair of nodes has edges in both directions, and no node has a self-loop.",
    "inv3": "The directed graph has no cycles, including self-loops.",
    "inv4": "Every node has an edge to every node, including itself.",
    "inv5": "No node has an edge to itself.",
    "inv6": "Every node can reach every other node if edges may be followed in either direction. A path of length zero is allowed.",
    "inv7": "Every node can reach every other node by following directed edges. A path of length zero is allowed.",
    "inv8": "Whenever there is a nonempty directed path from one node to another, there is also a direct edge between them.",
}


class ExtractionError(ValueError):
    """The source cannot be safely split into public and private components."""


@dataclass(frozen=True)
class Token:
    value: bytes
    start: int
    end: int
    depth: int


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def tokens(data: bytes) -> list[Token]:
    """Return code tokens and positions, skipping comments and quoted strings.

    Strings remain opaque tokens, so their content cannot be mistaken for code.
    Alloy primes are ordinary punctuation, not single-quoted strings. Nested
    block comments are rejected because parser interpretations differ.
    """
    result = []
    i = depth = 0
    while i < len(data):
        byte = data[i]
        if byte in b" \t\r\n\f":
            i += 1
            continue
        if data[i:i + 2] in (b"//", b"--"):
            end = data.find(b"\n", i + 2)
            i = len(data) if end < 0 else end + 1
            continue
        if data[i:i + 2] == b"/*":
            end = data.find(b"*/", i + 2)
            if end < 0:
                raise ExtractionError("Unterminated block comment")
            if b"/*" in data[i + 2:end]:
                raise ExtractionError("Ambiguous nested block comment")
            i = end + 2
            continue
        start = i
        if byte == ord('"'):
            i += 1
            while i < len(data):
                if data[i] == ord("\\"):
                    i += 2
                elif data[i] == ord('"'):
                    i += 1
                    break
                else:
                    i += 1
            else:
                raise ExtractionError("Unterminated string literal")
            result.append(Token(data[start:i], start, i, depth))
            continue
        if chr(byte).isascii() and (chr(byte).isalpha() or byte in b"_$"):
            i += 1
            while i < len(data) and (data[i] in b"_$" or
                    (chr(data[i]).isascii() and chr(data[i]).isalnum())):
                i += 1
        else:
            i += 1
        value = data[start:i]
        if value == b"}":
            depth -= 1
            if depth < 0:
                raise ExtractionError("Unmatched closing brace")
        result.append(Token(value, start, i, depth))
        if value == b"{":
            depth += 1
    if depth:
        raise ExtractionError("Unmatched opening brace")
    return result


def braced_declaration(ts: list[Token], keyword: bytes, name: bytes) -> dict:
    matches = [i for i, t in enumerate(ts[:-1])
               if t.depth == 0 and t.value == keyword and ts[i + 1].value == name]
    if len(matches) != 1:
        raise ExtractionError(f"Expected exactly one {keyword.decode()} {name.decode()}")
    index = matches[0]
    opener = index + 2
    while opener < len(ts) and ts[opener].value != b"{":
        if ts[opener].depth == 0 and ts[opener].value in {
            b"pred", b"fun", b"sig", b"fact", b"assert", b"check", b"run"
        }:
            raise ExtractionError("Declaration body not found before next paragraph")
        opener += 1
    if opener == len(ts):
        raise ExtractionError("Declaration has no body")
    closer = opener + 1
    while closer < len(ts) and not (ts[closer].value == b"}" and ts[closer].depth == 0):
        closer += 1
    if closer == len(ts):
        raise ExtractionError("Declaration has no closing brace")
    return {
        "start": ts[index].start, "headerEnd": ts[opener].start,
        "bodyStart": ts[opener].end, "bodyEnd": ts[closer].start,
        "end": ts[closer].end,
        "bodyTokens": [t.value for t in ts[opener + 1:closer]],
        "headerTokens": [t.value for t in ts[index:opener]],
    }


def extract_model(data: bytes, predicate: str) -> dict:
    """Extract a single source model, preserving every unrelated source byte."""
    data.decode("utf-8", errors="strict")
    ts = tokens(data)
    name = predicate.encode("ascii")
    oracle_name = name + b"c"
    student = braced_declaration(ts, b"pred", name)
    oracle = braced_declaration(ts, b"pred", oracle_name)
    # The worker evaluates a predicate without arguments. Reject parameterized
    # targets instead of silently dropping the parameter environment.
    for declaration, expected_name in ((student, name), (oracle, oracle_name)):
        if declaration["headerTokens"] not in (
            [b"pred", expected_name], [b"pred", expected_name, b"[", b"]"]
        ):
            raise ExtractionError("Target or oracle predicate has an unsupported header")
    correct = braced_declaration(ts, b"check", b"correct")
    under = braced_declaration(ts, b"pred", b"under")
    over = braced_declaration(ts, b"pred", b"over")
    expected_bodies = (
        (correct, [name, b"<", b"=", b">", oracle_name]),
        (under, [name, b"and", b"!", oracle_name]),
        (over, [b"!", name, b"and", oracle_name]),
    )
    for declaration, expected in expected_bodies:
        if declaration["bodyTokens"] != expected:
            raise ExtractionError("Unrecognized grading harness body")
    removed = [
        {"start": oracle["start"], "end": oracle["end"], "reason": "oracle predicate"},
        {"start": correct["start"], "end": correct["end"], "reason": "oracle comparison command"},
        {"start": under["start"], "end": under["end"], "reason": "oracle underconstraint harness"},
        {"start": over["start"], "end": over["end"], "reason": "oracle overconstraint harness"},
    ]
    for alias in (b"over", b"under"):
        matches = [i for i, t in enumerate(ts[:-1]) if t.depth == 0
                   and t.value == b"run" and ts[i + 1].value == alias]
        if len(matches) != 1:
            raise ExtractionError("Expected exactly one run per grading predicate")
        index = matches[0]
        line_end = data.find(b"\n", ts[index + 1].end)
        line_end = len(data) if line_end < 0 else line_end
        # Preserve whitespace/comments and remove only the recognized command.
        if any(t.start >= ts[index + 1].end and t.start < line_end for t in ts):
            raise ExtractionError("Unexpected text following grading run command")
        removed.append({"start": ts[index].start, "end": ts[index + 1].end,
                        "reason": f"grading run {alias.decode()}"})
    removed.sort(key=lambda span: span["start"])
    for left, right in zip(removed, removed[1:]):
        if left["end"] > right["start"]:
            raise ExtractionError("Overlapping private spans")
    for span in removed:
        if span["start"] < student["end"] and span["end"] > student["start"]:
            raise ExtractionError("Private span overlaps student predicate")

    def copy_segments(start: int, end: int) -> tuple[str, list[dict]]:
        cursor = start
        segments = []
        for span in removed:
            if span["end"] <= start or span["start"] >= end:
                continue
            if span["start"] < start or span["end"] > end:
                raise ExtractionError("Private span crosses environment boundary")
            if cursor < span["start"]:
                segments.append({"start": cursor, "end": span["start"]})
            cursor = span["end"]
        if cursor < end:
            segments.append({"start": cursor, "end": end})
        return b"".join(data[s["start"]:s["end"]] for s in segments).decode("utf-8"), segments

    before, before_segments = copy_segments(0, student["start"])
    after, after_segments = copy_segments(student["end"], len(data))
    public_code = (before + after).encode("utf-8")
    # No surviving code can reference the hidden oracle or harness aliases.
    if any(t.value in {oracle_name, b"under", b"over", b"correct"} for t in tokens(public_code)):
        raise ExtractionError("Preserved environment references private grading symbols")
    for declaration in (student, oracle):
        declaration.pop("bodyTokens")
        declaration.pop("headerTokens")
    return {
        "environmentBefore": before,
        "environmentAfter": after,
        "predicateHeader": data[student["start"]:student["headerEnd"]].decode("utf-8"),
        "starter": data[student["bodyStart"]:student["bodyEnd"]].decode("utf-8"),
        "oracleBody": data[oracle["bodyStart"]:oracle["bodyEnd"]].decode("utf-8"),
        "originalSource": data.decode("utf-8"),
        "preservation": {
            "encoding": "utf-8", "offsetUnit": "byte", "sourceBytes": len(data),
            "studentDeclaration": student, "oracleDeclaration": oracle,
            "removedSpans": removed,
            "environmentBeforeSegments": before_segments,
            "environmentAfterSegments": after_segments,
            "environmentBeforeSha256": digest(before.encode("utf-8")),
            "environmentAfterSha256": digest(after.encode("utf-8")),
        },
    }


def verify_record(record: dict) -> None:
    """Re-extract and check the exact public/private split against hashed source."""
    data = record["originalSource"].encode("utf-8")
    if digest(data) != record["source"]["sha256"]:
        raise ExtractionError("Original source hash mismatch")
    expected = extract_model(data, record["predicate"])
    for key, value in expected.items():
        if record.get(key) != value:
            raise ExtractionError(f"Source preservation mismatch in {record['id']}: {key}")
    # Verify every source byte is accounted for exactly once, including the
    # student declaration and only the explicitly removed grading spans.
    p = record["preservation"]
    spans = p["environmentBeforeSegments"] + p["environmentAfterSegments"] + p["removedSpans"]
    spans = spans + [{"start": p["studentDeclaration"]["start"],
                      "end": p["studentDeclaration"]["end"]}]
    cursor = 0
    for span in sorted(spans, key=lambda s: s["start"]):
        if span["start"] != cursor or span["end"] <= cursor:
            raise ExtractionError("Source byte partition contains a gap or overlap")
        cursor = span["end"]
    if cursor != len(data):
        raise ExtractionError("Source byte partition is incomplete")


def build_catalogue(source_root: Path) -> dict:
    corpus = source_root / "classified-data"
    if not corpus.is_dir():
        raise ExtractionError(f"Corpus not found: {corpus}")
    groups = defaultdict(list)
    ignored = []
    for path in sorted(corpus.rglob("*.als")):
        match = re.fullmatch(r".+_(inv\d+)\.als", path.name)
        relative = path.relative_to(corpus)
        if not match or len(relative.parts) != 3:
            ignored.append(str(path.relative_to(source_root)))
            continue
        groups[(relative.parts[0], match.group(1))].append(path)
    records, dropped, rejected = [], [], []
    for (group, predicate), paths in sorted(groups.items(), key=lambda entry:
            (entry[0][0], int(entry[0][1][3:]))):
        for path in sorted(paths, key=lambda p: (STATUS_ORDER.get(p.parent.name, 99), p.name)):
            try:
                data = path.read_bytes()
                extracted = extract_model(data, predicate)
                if extracted["starter"].strip() == extracted["oracleBody"].strip():
                    raise ExtractionError("Starter is the literal hidden oracle")
            except (ExtractionError, UnicodeError) as error:
                rejected.append({"path": str(path.relative_to(source_root)), "reason": str(error)})
                continue
            description = GRAPH_DESCRIPTIONS.get(predicate) if group == "graphs" else None
            record = {
                "id": f"{group}-{predicate}",
                "title": f"{group.replace('_', ' ')} · {predicate}",
                "group": group, "predicate": predicate,
                "description": description or (
                    f"Revise {predicate} using the canonical-distance feedback. "
                    "The source corpus supplies no natural-language requirement for this exercise."
                ),
                "source": {"path": str(path.relative_to(source_root)), "sha256": digest(data)},
                "descriptionProvenance": "Reviewed natural-language interpretation of corpus oracle" if description else "No requirement text available",
                "sourceClassification": path.parent.name,
                **extracted,
            }
            verify_record(record)
            records.append(record)
            break
        else:
            dropped.append({"group": group, "predicate": predicate,
                            "reason": "No unambiguous source with a non-oracle starter", "candidates": len(paths)})
    return {
        "schemaVersion": 1,
        "provenance": {
            "corpus": "ACGN/classified-data", "candidateFiles": sum(map(len, groups.values())),
            "discoveredGroups": len(groups), "importedGroups": len(records),
            "selection": "First safe source by classification under, over, both, correct; then filename",
            "environmentPolicy": "Preserve original UTF-8 bytes except student declaration and recognized oracle/grading spans; retain all signatures, facts, opens, helpers, comments, and whitespace",
            "publicFields": list(PUBLIC_FIELDS),
        },
        "exercises": records, "droppedGroups": dropped,
        "rejectedCandidates": rejected, "ignoredFiles": ignored,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=Path("/home/augustus/ACGN"))
    parser.add_argument("--output", type=Path, default=ROOT / "exercises" / "catalogue.json")
    parser.add_argument("--check", action="store_true", help="Verify deterministic regeneration without writing")
    args = parser.parse_args()
    catalogue = build_catalogue(args.source_root.resolve())
    encoded = (json.dumps(catalogue, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.check:
        if not args.output.is_file() or args.output.read_bytes() != encoded:
            print("Catalogue differs from deterministic corpus import", file=sys.stderr)
            return 1
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(encoded)
    print(json.dumps({"catalogue": str(args.output), "sha256": digest(encoded),
                      "imported": len(catalogue["exercises"]),
                      "dropped": catalogue["droppedGroups"],
                      "rejectedCandidates": len(catalogue["rejectedCandidates"])}))
    return 0 if not catalogue["droppedGroups"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
