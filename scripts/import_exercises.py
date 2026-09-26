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
import os
from pathlib import Path
import re
import sys
import tempfile


ROOT = Path(__file__).resolve().parents[1]
PUBLIC_FIELDS = (
    "id", "title", "group", "predicate", "description", "environmentBefore",
    "environmentAfter", "predicateHeader", "starter", "source",
)
STATUS_ORDER = {"under": 0, "over": 1, "both": 2, "correct": 3}
DESCRIPTION_PATH = Path(__file__).with_name("exercise_descriptions.json")
DESCRIPTION_PROVENANCE = "Reviewed natural-language interpretation of the source-bound corpus predicate"


class ExtractionError(ValueError):
    """The source cannot be safely split into public and private components."""


def load_descriptions(path: Path = DESCRIPTION_PATH) -> dict:
    """Load prose only; bind each interpretation to the exact original model."""
    def unique_fields(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ExtractionError("Duplicate description entry")
            result[key] = value
        return result

    document = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=unique_fields)
    if (not isinstance(document, dict) or set(document) != {"schemaVersion", "descriptions"}
            or document["schemaVersion"] != 1 or not isinstance(document["descriptions"], dict)):
        raise ExtractionError("Invalid description document")
    for identifier, entry in document["descriptions"].items():
        if (not re.fullmatch(r"[A-Za-z0-9_]+-inv\d+", identifier)
                or not isinstance(entry, dict) or set(entry) != {"description", "sourceSha256"}
                or not isinstance(entry["sourceSha256"], str)
                or not re.fullmatch(r"[0-9a-f]{64}", entry["sourceSha256"])
                or not isinstance(entry["description"], str)
                or not entry["description"].strip() or len(entry["description"]) > 1600
                or any(ord(char) < 32 for char in entry["description"])):
            raise ExtractionError("Invalid exercise description")
    return document["descriptions"]


def description_for(identifier: str, source_hash: str, descriptions: dict) -> str | None:
    entry = descriptions.get(identifier)
    # A model with the same group/inv name may have different semantics. Never
    # apply a previously authored requirement solely because its ID matches.
    return entry["description"] if entry and entry["sourceSha256"] == source_hash else None


def refresh_descriptions(catalogue: dict, descriptions: dict) -> None:
    """Validate every binding before changing only the two prose metadata fields."""
    updates = []
    seen = set()
    for record in catalogue["exercises"]:
        verify_record(record)
        identifier = record["id"]
        description = description_for(identifier, record["source"]["sha256"], descriptions)
        if identifier in seen or not description:
            raise ExtractionError(f"Missing, stale, or duplicate description binding: {identifier}")
        seen.add(identifier)
        updates.append((record, description))
    for record, description in updates:
        record["description"] = description
        record["descriptionProvenance"] = DESCRIPTION_PROVENANCE


def render_description_guide(catalogue: dict) -> str:
    """Render only public metadata; never include predicate implementations."""
    lines = ["# Live programming exercise descriptions", "",
             f"Natural-language requirements for all {len(catalogue['exercises'])} bundled exercises. Each requirement",
             "uses its exercise's original model declarations and facts. Temporal descriptions",
             "distinguish the initial state, later states, and properties that hold at every state.",
             "", "These are task specifications; predicate implementations are kept private.", ""]
    previous = None
    for record in catalogue["exercises"]:
        if record["group"] != previous:
            previous = record["group"]
            lines.extend(["## " + previous, ""])
        lines.extend(["### " + record["id"], "", record["description"], ""])
    return "\n".join(lines)


def write_private_catalogue(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary = tempfile.mkstemp(prefix=".catalogue-", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


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
    descriptions = load_descriptions()
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
            description = description_for(f"{group}-{predicate}", digest(data), descriptions)
            record = {
                "id": f"{group}-{predicate}",
                "title": f"{group.replace('_', ' ')} · {predicate}",
                "group": group, "predicate": predicate,
                "description": description or (
                    f"Revise {predicate} using the canonical-distance feedback. "
                    "A natural-language requirement has not been reviewed for this source model."
                ),
                # Corpus paths are portable provenance identifiers, not native
                # filesystem paths; keep JSON identical on Windows and POSIX.
                "source": {"path": path.relative_to(source_root).as_posix(), "sha256": digest(data)},
                "descriptionProvenance": DESCRIPTION_PROVENANCE if description else "No requirement text available for this source",
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
    parser.add_argument("--source-root", type=Path,
                        default=Path(os.environ.get("ACGN_ROOT", ROOT.parent / "ACGN")))
    parser.add_argument("--output", type=Path, default=ROOT / "exercises" / "catalogue.json")
    parser.add_argument("--check", action="store_true", help="Verify deterministic regeneration without writing")
    parser.add_argument("--refresh-descriptions", action="store_true",
                        help="Update only descriptions in an existing catalogue; no original ACGN checkout needed")
    parser.add_argument("--guide", type=Path,
                        help="Also write (or --check) a Markdown guide containing only public descriptions")
    args = parser.parse_args()
    if args.refresh_descriptions:
        catalogue = json.loads(args.output.read_text(encoding="utf-8"))
        refresh_descriptions(catalogue, load_descriptions())
    else:
        catalogue = build_catalogue(args.source_root.resolve())
    encoded = (json.dumps(catalogue, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if args.check:
        if not args.output.is_file() or args.output.read_bytes() != encoded:
            print("Catalogue differs from deterministic corpus import", file=sys.stderr)
            return 1
    else:
        write_private_catalogue(args.output, encoded)
    if args.guide:
        guide = render_description_guide(catalogue).encode("utf-8")
        if args.check:
            if not args.guide.is_file() or args.guide.read_bytes() != guide:
                print("Description guide differs from catalogue descriptions", file=sys.stderr)
                return 1
        else:
            args.guide.parent.mkdir(parents=True, exist_ok=True)
            args.guide.write_bytes(guide)
    print(json.dumps({"catalogue": str(args.output), "sha256": digest(encoded),
                      "imported": len(catalogue["exercises"]),
                      "dropped": catalogue["droppedGroups"],
                      "rejectedCandidates": len(catalogue["rejectedCandidates"])}))
    return 0 if not catalogue["droppedGroups"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
