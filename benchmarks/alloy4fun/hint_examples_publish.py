#!/usr/bin/env python3
"""Publish matched five-arm hints and recorded production AI explanations.

This renderer performs no model, solver, credential, or network access. Inputs
are the frozen selection, quality_extract.py baselines, and current production
responses recorded by the study collector. Only named public fields survive.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import html
import json
from pathlib import Path
import re

from quality_all import public_native_hint

ROOT = Path(__file__).resolve().parents[2]
METRICS = ("canonical", "ast")
LABELS = {"canonical": "Canonical", "ast": "AST", "tar": "TAR",
          "fm24-history": "HiGenA-H", "fm24-mutation": "HiGenA-M"}
ARCHIVED = ("live-full", "ast-full", "tar", "fm24-history", "fm24-mutation")
# Reviewer observations are bound to the exact recorded feedback and AI text.
# They are separate annotations, never replacements for the captured responses.
REVIEWER_OBSERVATIONS = (
    {
        "case_id": "classroom_fol/both/viXduxC7rXDCtisTc_inv4.als", "metric": "canonical",
        "response_sha256": "f8f88b3a29b40dd583f98591fc75140d523fb2ffbd6047281ac282c5d91b6db2",
        "ai_sha256": "12dd79193827233db657a32d32ffdfd6301957ab35c094355395eee7fbcbc15c",
        "operation_ids": ["operation-1"], "kind": "raw_canonical_conflation",
        "observation": "The explanation moves from the raw membership checks joined by OR to a 'current AND' "
            "without explaining the outer negation. In the recorded canonical form, normalization of that "
            "negation introduces AND between negated membership checks. The raw highlighted OR and the "
            "canonical AND are different representations of the same learner condition, so this wording "
            "can mislead a learner about which operator was actually written."
    },
    {
        "case_id": "classroom_rl/both/Hr9vwoarznSfbhMwt_inv13.als", "metric": "canonical",
        "response_sha256": "0867dd764851c43ed174c109751c42f879a902a3ca2918baffecd496e6c8d9d8",
        "ai_sha256": "0128bf2e32fd7dc0f4baaee6b8a9af8f1cb0a2dc1fc3e5b470e70a2bcbe04929",
        "operation_ids": ["operation-1", "operation-2"], "kind": "operation_target_swap",
        "observation": "The operation IDs match the required schema, but the explanations swap their "
            "source targets. Operation 1 highlights Student in the Tutors.Person clause, while its AI "
            "description claims that Teacher in the Person.Tutors clause is highlighted. Operation 2 "
            "makes the converse swap. Matching IDs alone does not establish that prose describes the "
            "corresponding structural location."
    },
    {
        "case_id": "classroom_rl/both/Hr9vwoarznSfbhMwt_inv13.als", "metric": "ast",
        "response_sha256": "60d6c6dbe21c0f37dfbf46d78437c5b4264c9c02639573eee549e5504313dc63",
        "ai_sha256": "fe6c2eab72a2fe8b9e3ec165cc0a229b1f0954631366e9d5f437fbf37475aec7",
        "operation_ids": ["operation-1", "operation-2"], "kind": "relational_join_direction",
        "observation": "The explanations reverse the direction of the joins. For the declared Tutors "
            "relation, Person.Tutors selects link targets (people being tutored), while Tutors.Person "
            "selects link sources (people who tutor). The recorded prose describes these roles the other "
            "way around, so its role-based guidance is semantically misleading."
    },
    {
        "case_id": "graphs/under/HAnaxx4EM5eht5CJC_inv8.als", "metric": "ast",
        "response_sha256": "4c42731713a8e2f05289328a130b5ce1545e20f7d4be621626ddb99cb84d15cd",
        "ai_sha256": "2d808f8e5362f2a812965b33dab9d32f13c38d8f7d693ddb3bda3cdb6a83a8ca",
        "operation_ids": ["operation-4", "operation-5"], "kind": "repeated_term_position",
        "observation": "The descriptions for operations 4 and 5 call their e1 and e2 occurrences the "
            "start and destination of the conclusion. Their recorded raw ranges, 25–27 and 31–33, "
            "actually select the first edge in the antecedent. Operations 8 and 9, at ranges 69–71 "
            "and 75–77, select the corresponding conclusion occurrences. Repeated variable names do "
            "not justify substituting one structural position for another; the captured prose ignores "
            "that distinction here."
    },
    {
        "case_id": "lts/over/DAbaXwpJYtYK2WtJT_inv6.als", "metric": "canonical",
        "response_sha256": "946a3f3b1822e2eab156b7b21f5d9b01b20012de94c5fe669cf1ea169abe233d",
        "ai_sha256": "28a75c33fef0555b36c8fbd830143483b54a8171e5bdc4e2201b13b1948e7aaa",
        "operation_ids": ["operation-2"], "kind": "relational_join_column",
        "observation": "The description interprets State.trans.e as checking for a transition labeled "
            "by event e. The environment declares trans: Event -> State inside State, so State.trans "
            "has Event -> State columns. Joining e: Event on its right matches against the final "
            "State column, not the event column. The recorded interpretation therefore assigns the "
            "lookup an event-selection meaning that its column order does not support."
    },
    {
        "case_id": "lts/over/prccZRkfvy2ZDAZzQ_inv7.als", "metric": "canonical",
        "response_sha256": "c334a03074ea5b552d9ab73153a8b116a5f68e14fbeee82feec58c95a4d88878",
        "ai_sha256": "33c3e62f0f638b3548f71fd9ed7931ccf358e059bc42485bfd0962ef74109d88",
        "operation_ids": ["operation-18", "operation-23", "operation-24"], "kind": "binding_scope_misattribution",
        "observation": "Operation 18 attributes the canonical (one State) to s: Init, but its recorded "
            "raw range 17–22 selects State in the x,y comprehension declaration. Operations 23 and 24 "
            "also describe the quantifier as ranging over Init; the actual source is all s:Init.^adj, "
            "which ranges over states reachable from Init. These descriptions conflate distinct "
            "declarations and omit a meaningful part of the quantified domain."
    },
    {
        "case_id": "productionLineNew/both/iyLpDaX5nsZ9sMEgQ_inv4.als", "metric": "canonical",
        "response_sha256": "2f4c560dfce8623c0772d55471acd4998d11a980a79c3a28354f862d665bfe4d",
        "ai_sha256": "b65e20b8e527645a95b83196edc69dd850801e4480c023a0bc1b10e848df9250",
        "operation_ids": ["operation-1", "operation-2", "operation-3", "operation-4"],
        "kind": "operation_clause_swap",
        "observation": "The descriptions swap the two clauses. Operations 1 and 2 select parts and p "
            "in the material clause (raw ranges 95–100 and 93–94), but discuss the component clause. "
            "Operations 3 and 4 select the corresponding component-clause occurrences (44–49 and "
            "42–43), but discuss the material clause. The repeated names match, while the described "
            "structural positions do not."
    },
    {
        "case_id": "socialMedia/over/jNH8BMhrMrJFjs5ib_inv2.als", "metric": "canonical",
        "response_sha256": "01f0a16fca71ba08e863c5b4cef00736ff116bef2b64ab8efbdcda01712f02a8",
        "ai_sha256": "fc651365fa5b01d2c388f57a79ca0bb209d82f3da3f8d9eea409ad3a37e28c1e",
        "operation_ids": ["operation-1", "operation-2"], "kind": "learner_expression_substitution",
        "observation": "The explanations say the learner compares u with u.follows. The actual body "
            "compares u.follows with itself using not in; its quantified canonical body is false. "
            "The AI silently substitutes a different left operand and then reasons about that different "
            "predicate, so the explanation does not describe the submitted condition."
    },
)


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def record_sha(value):
    """Use the exact object serialization used by the production collector."""
    raw = json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode()
    return hashlib.sha256(raw).hexdigest()


def checked_hash_map(value):
    if not isinstance(value, dict):
        raise ValueError("Expected a source hash map")
    for key, digest in value.items():
        if (not isinstance(key, str) or Path(key).is_absolute() or ".." in Path(key).parts
                or not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)):
            raise ValueError("Invalid source hash map entry")
    return dict(value)


def fields(value, names):
    """Copy scalar fields only; every nested public structure is projected below."""
    if not isinstance(value, dict):
        return {}
    return {name: value[name] for name in names if name in value and
            (value[name] is None or type(value[name]) in (str, int, float, bool))}


def location(value):
    out = fields(value, ("status", "precision", "coordinateSystem", "offsetEncoding", "reason"))
    out["ranges"] = [fields(row, ("start", "end", "text", "formIndex", "startLine",
        "startColumn", "endLine", "endColumn", "moduleLine", "moduleColumn"))
        for row in (value or {}).get("ranges", [])]
    return out


def operation(value):
    out = fields(value, ("kind", "cost", "component", "path", "aggregate", "action",
        "description", "reason", "nextStep", "sourceTerm", "sourceRole", "sourceNodeKind",
        "sourceOperator", "replacementOperator"))
    for name in ("sourceLocation", "canonicalLocation"):
        if name in value:
            out[name] = location(value[name])
    return out


def public_feedback(value):
    out = fields(value, ("status", "metric", "distance", "exerciseId", "revision",
        "requestedMetric", "astSize"))
    out["canonicalForm"] = [form for form in value.get("canonicalForm", []) if isinstance(form, str)]
    out["operations"] = [operation(row) for row in value.get("operations", [])]
    out["breakdown"] = fields(value.get("breakdown"), ("quantifier", "matrix", "temporal", "ast"))
    out["operationSummary"] = fields(value.get("operationSummary"), ("insert", "delete", "replace", "aggregate"))
    out["comparison"] = fields(value.get("comparison"), ("complete", "evaluatedCandidates", "poolSize", "strategy"))
    trace = value.get("trace", {})
    out["trace"] = fields(trace, ("note", "cost", "kind", "hasAggregates", "matchesDistance",
        "matrixReplayVerified", "matrixTraceAlgorithm", "quantifierCostVerified",
        "astReplayVerified", "astTraceAlgorithm", "certifiedOptimalScript"))
    out["trace"]["components"] = [fields(row, ("component", "distance", "hintCount", "countMatchesDistance"))
        for row in trace.get("components", [])]
    if "repairGrammar" in value:
        grammar = value["repairGrammar"]
        out["repairGrammar"] = fields(grammar, ("status", "version"))
        for name in ("actions", "components", "operators", "roles", "subjects"):
            if isinstance(grammar.get(name), dict):
                out["repairGrammar"][name] = {key: val for key, val in grammar[name].items()
                    if isinstance(key, str) and isinstance(val, str)}
    if "repairSequence" in value:
        out["repairSequence"] = [fields(row, ("action", "bindingSlot", "component", "cost",
            "operationIndex", "path", "replacementOperator", "role", "subject"))
            for row in value["repairSequence"]]
    return out


def public_ai(value, feedback):
    out = fields(value, ("status", "model", "message", "summary"))
    for name in ("operations", "instances"):
        out[name] = [fields(row, ("id", "description")) for row in value.get(name, [])]
    if out.get("status") == "ok":
        expected = [f"operation-{i + 1}" for i in range(len(feedback["operations"]))]
        if [row.get("id") for row in out["operations"]] != expected:
            raise ValueError("AI operation identities differ from the recorded learner trace")
        if not isinstance(out.get("summary"), str) or not out["summary"].strip():
            raise ValueError("Successful AI response has no summary")
        if any(not isinstance(row.get("description"), str) or not row["description"].strip()
               for row in out["operations"]):
            raise ValueError("Successful AI response has an empty operation explanation")
    return out


def public_baseline(name, value):
    out = fields(value, ("status", "cohort_status", "supported", "hint_available", "timed_out", "fold",
        "result_record_sha256", "response_record_sha256", "distance", "operation_count",
        "located_operations", "canonical_located_operations", "aggregate_operation_count",
        "reference_count", "pool_size", "evaluated_candidates", "trace_verified",
        "hint_source", "native_action_count", "mutation_candidates", "native_hint_available"))
    if name == "tar":
        out["native_hints"] = []
        for hint in value.get("native_hints", []):
            if hint.get("contains_complete_candidate_substring"):
                raise ValueError("TAR hint contains a candidate expression; redact before publication")
            row = fields(hint, ("operation", "line", "column", "end_line", "end_column"))
            raw = hint.get("hint", "")
            row.update(hint=public_native_hint(raw), publication_redacted=public_native_hint(raw) != raw)
            out["native_hints"].append(row)
        out["bounded_verification"] = fields(value.get("bounded_verification"),
            ("verified_correct", "independent_validation_status", "validation_error_class",
             "native_solved", "native_timed_out", "native_depth", "max_depth"))
    elif name.startswith("fm24-"):
        raw = value.get("native_hint", "")
        out.update(native_hint=public_native_hint(raw), publication_redacted=public_native_hint(raw) != raw)
    return out


def index_cases(value, name):
    rows = value.get("cases", [])
    result = {row["case_id"]: row for row in rows}
    if len(result) != len(rows):
        raise ValueError(f"Duplicate case identity in {name}")
    return result


def build(study):
    paths = {name: study / f"{name}.json" for name in ("selection", "baselines", "current")}
    selection, baselines, current = (load(paths[name]) for name in paths)
    selection_sha = sha(paths["selection"])
    for name, value in (("baselines", baselines), ("current", current)):
        if value.get("selection_sha256") != selection_sha:
            raise ValueError(f"{name} does not bind to this selection")
    selected = index_cases(selection, "selection")
    baseline_index = index_cases(baselines, "baselines")
    current_index = index_cases(current, "current")
    if not 100 <= len(selected) <= 200:
        raise ValueError("Publication requires between 100 and 200 examples")
    if set(selected) != set(baseline_index) or set(selected) != set(current_index):
        raise ValueError("The three inputs do not contain exactly the same selected cases")
    if len({row["exercise_id"] for row in selected.values()}) != len(selected):
        raise ValueError("Maximum-coverage selection must use one example per invariant")
    cases = []
    for selected_row in selection["cases"]:
        cid = selected_row["case_id"]
        archived, fresh = baseline_index[cid], current_index[cid]
        if archived["source_sha256"] != selected_row["source_sha256"]:
            raise ValueError("Selected source hash differs from archived source")
        if any(row["exercise_id"] != selected_row["exercise_id"] for row in (archived, fresh)):
            raise ValueError("Joined case belongs to a different invariant")
        row = fields(selected_row, ("number", "exercise_id", "case_id", "source_sha256",
            "source_classification", "group", "predicate", "question", "learner_body", "predicate_header",
            "environment_before", "environment_after", "environment_sha256", "body_token_sha256", "selection_rationale"))
        row.setdefault("question", selected_row.get("description", ""))
        row["current"] = {}
        for metric in METRICS:
            recorded = fresh["responses"][metric]
            if recorded.get("response_sha256") != record_sha(recorded["response"]):
                raise ValueError(f"Current response content hash differs: {cid} {metric}")
            if "ai" in recorded and recorded.get("ai_sha256") != record_sha(recorded["ai"]):
                raise ValueError(f"Current AI content hash differs: {cid} {metric}")
            feedback = public_feedback(recorded["response"])
            atomic = [op for op in feedback["operations"] if not op.get("aggregate", False)]
            if feedback.get("status") != "ok" or not atomic or not feedback.get("distance", 0) > 0:
                raise ValueError(f"Current {metric} lacks a nonempty hint: {cid}")
            ai = public_ai(recorded.get("ai", {"status": "not_collected"}), feedback)
            item = fields(recorded, ("response_bytes_sha256", "response_sha256", "request_sha256",
                "ai_sha256", "ai_response_sha256", "evidence_sha256", "request_evidence_sha256",
                "education_evidence_sha256", "collected_at_utc", "feedback_attempts", "ai_attempts",
                "wall_seconds_diagnostic_only", "ai_wall_seconds_diagnostic_only", "ai_provider", "ai_effort"))
            if isinstance(recorded.get("ai_attempts"), list):
                item["ai_attempts"] = []
                for attempt in recorded["ai_attempts"]:
                    safe_attempt = fields(attempt, ("collected_at_utc", "wall_seconds_diagnostic_only"))
                    safe_attempt["result"] = fields(attempt.get("result"), ("status", "model", "message"))
                    item["ai_attempts"].append(safe_attempt)
            item.update(response=feedback, ai=ai)
            item["reviewer_observations"] = [dict(note) for note in REVIEWER_OBSERVATIONS
                if note["case_id"] == cid and note["metric"] == metric
                and note["response_sha256"] == recorded.get("response_sha256")
                and note["ai_sha256"] == recorded.get("ai_sha256")]
            row["current"][metric] = item
        row["archived"] = {}
        for name in ARCHIVED:
            value = archived["engines"][name]
            if value.get("hint_available") is not True:
                raise ValueError(f"Archived {name} lacks a recorded hint: {cid}")
            row["archived"][name] = public_baseline(name, value)
        tar = row["archived"]["tar"]
        if not tar["native_hints"]:
            raise ValueError("Selected TAR native hint is empty")
        for name in ("fm24-history", "fm24-mutation"):
            if not row["archived"][name].get("native_hint", "").strip():
                raise ValueError(f"Selected {name} native hint is empty")
        cases.append(row)
    population = selection.get("population", {})
    provenance = fields(current, ("collected_at_utc", "started_at_utc", "updated_at_utc", "revision",
        "instructions_sha256", "behavior", "collection", "git_revision", "git_head",
        "engine_timeout_seconds", "ai_timeout_seconds", "ai_model", "ai_provider", "ai_concurrency",
        "engine_workers", "behavior_included"))
    provenance["limits"] = fields(current.get("limits"), ("feedback_workers", "engine_timeout_seconds",
        "ai_concurrency", "ai_timeout_seconds", "ai_attempts_maximum"))
    provenance["input_sha256"] = {name: sha(path) for name, path in paths.items()}
    provenance["renderer"] = {
        "path": "benchmarks/alloy4fun/hint_examples_publish.py", "sha256": sha(Path(__file__)),
    }
    audit_path = study / "collection-source-audit.json"
    audit = load(audit_path)
    provenance["collection_source_audit"] = {
        "file": "collection-source-audit.json", "sha256": sha(audit_path),
        "source_sha256": checked_hash_map(audit.get("sha256")),
    }
    try:
        provenance["study_directory"] = study.resolve().relative_to(ROOT).as_posix()
    except ValueError:
        provenance["study_directory"] = "path/to/saved-study"
    for name in ("runtime_sha256", "source_sha256"):
        value = current.get(name)
        if isinstance(value, dict):
            provenance[name] = {key: val for key, val in value.items() if isinstance(key, str)
                and isinstance(val, str) and re.fullmatch(r"[0-9a-f]{64}", val)}
    provenance["baseline_archive_sha256"] = baselines.get("archive_sha256", {})
    return {"schema": 1, "title": "Five-arm matched hint examples with production AI guidance",
        "selection_policy": selection.get("selection_policy"), "population": population,
        "counts": {"examples": len(cases), "invariants": len({row["exercise_id"] for row in cases}),
            "families": len({row.get("group", row["case_id"].split("/")[0]) for row in cases}),
            "classifications": dict(Counter(row["source_classification"] for row in cases)),
            "higena_h_m_native_equal_cases": sum(
                row["engines"]["fm24-history"].get("native_hint") == row["engines"]["fm24-mutation"].get("native_hint")
                for row in baseline_index.values()),
            "higena_redacted_cases": sum(row["archived"]["fm24-history"].get("publication_redacted", False)
                or row["archived"]["fm24-mutation"].get("publication_redacted", False) for row in cases),
            "tar_bounded_statuses": dict(Counter(
                "verified" if row["archived"]["tar"]["bounded_verification"].get("verified_correct") is True
                else "rejected" if row["archived"]["tar"]["bounded_verification"].get("verified_correct") is False
                else "unresolved" for row in cases)),
            "ai_statuses": {metric: dict(Counter(row["current"][metric]["ai"].get("status", "missing")
                for row in cases)) for metric in METRICS},
            "ai_first_attempt_statuses": {metric: dict(Counter(
                row["current"][metric]["ai_attempts"][0]["result"].get("status", "missing")
                for row in cases if row["current"][metric].get("ai_attempts"))) for metric in METRICS},
            "ai_retried_explanations": sum(len(row["current"][metric].get("ai_attempts", [])) > 1
                for row in cases for metric in METRICS),
            "reviewer_observations": sum(len(row["current"][metric]["reviewer_observations"])
                for row in cases for metric in METRICS)},
        "provenance": provenance, "cases": cases}


def prose(value):
    """Render external question/hint text literally, without Markdown/HTML effects."""
    text = html.escape(str(value), quote=False)
    text = re.sub(r"([\\`*_{}\[\]()#+.!|])", r"\\\1", text)
    return " ".join(text.split())


def codeblock(value, language="text"):
    # Retain leading newlines: raw source coordinates include these blank lines.
    value = str(value).rstrip("\n")
    runs = [len(x) for x in re.findall(r"`+", value)]
    fence = "`" * max(3, max(runs, default=0) + 1)
    return [fence + language, value, fence, ""]


def source_location_lines(op):
    loc = op.get("sourceLocation", {})
    ranges = loc.get("ranges", [])
    if loc.get("status") != "located" or not ranges:
        return ["Raw-code location: unavailable; this hint has no reliable raw-code highlight.", ""]
    lines = [f"Raw-code location: **{prose(loc.get('precision', 'unspecified'))}** "
             f"in {prose(loc.get('coordinateSystem', 'unspecified'))} coordinates.", ""]
    for span in ranges:
        if "startLine" in span:
            lines += [f"Line {span['startLine']}, column {span.get('startColumn', '?')} "
                      f"to line {span.get('endLine', '?')}, column {span.get('endColumn', '?')}:", ""]
        if span.get("text"):
            lines += codeblock(span["text"], "alloy")
    return lines


def render_current(metric, item):
    feedback, ai = item["response"], item["ai"]
    index, op = next((i, op) for i, op in enumerate(feedback["operations"]) if not op.get("aggregate", False))
    atomic_count = sum(not op.get("aggregate", False) for op in feedback["operations"])
    lines = [f"**{LABELS[metric]} — distance {feedback['distance']}; "
             f"{atomic_count} atomic operation{'s' if atomic_count != 1 else ''}.**", "",
             f"First atomic hint (operation {index + 1}): {prose(op.get('action') or op.get('description') or '')}", ""]
    lines += source_location_lines(op)
    if ai.get("status") == "ok":
        explanation = next(x["description"] for x in ai["operations"] if x["id"] == f"operation-{index + 1}")
        lines += [f"AI explanation ({prose(ai.get('model', 'unspecified'))}): {prose(explanation)}", "",
                  f"AI summary: {prose(ai['summary'])}", ""]
    else:
        lines += [f"AI status: **{prose(ai.get('status', 'missing'))}**. "
                  + prose(ai.get("message", "No AI explanation was recorded; none is synthesized here.")), ""]
    attempts = item.get("ai_attempts", [])
    if len(attempts) > 1:
        lines += ["AI collection attempts: " + " → ".join(prose(attempt["result"].get("status", "missing"))
            for attempt in attempts) + ". The displayed response is the final recorded attempt.", ""]
    for note in item.get("reviewer_observations", []):
        lines += ["**Reviewer observation — separate from the captured AI output:** " + prose(note["observation"]), ""]
    return lines


def markdown(out, evidence_link):
    counts, population, provenance = out["counts"], out["population"], out["provenance"]
    eligible = population.get("eligible_native_hint_cases", "unknown")
    all_invariants = population.get("all_invariants", 181)
    lines = ["# Matched hint examples: Canonical, AST, TAR, HiGenA-H and HiGenA-M", "",
        f"This collection contains **{counts['examples']} incorrect learner predicates across "
        f"{counts['invariants']} of {all_invariants} invariants and {counts['families']} model families**, "
        "with a recorded nonempty hint from all five arms. Each invariant contributes one example. "
        "The two live-programming arms include freshly collected deterministic hints and the actual "
        "AI explanations returned by the production guidance pipeline.", "",
        f"The [complete public evidence]({evidence_link}) contains every current operation and AI "
        "operation explanation, learner environments, native baseline hints, archived Canonical/AST "
        "controls, and provenance hashes. The sections below show the first atomic operation of each "
        "live arm, its corresponding AI explanation, and the AI summary; TAR and HiGenA hints are shown in full.", "",
        "For benchmark-wide results, see the [full-corpus comparison](alloy4fun-comparison.md) "
        "and the [181-invariant hint-quality report](alloy4fun-181-hint-quality.md).", "",
        "## How the examples were chosen", "",
        f"- The saved 61,598-model benchmark contains **{eligible:,} eligible incorrect cases** "
        f"spanning **{population.get('eligible_invariants', counts['invariants'])} invariants** "
        "under the selection conditions: all five arms emitted native hints and both deterministic live "
        "distances are positive. TAR verification is reported separately; it does not remove an invariant "
        "with an otherwise eligible hint. "
        "This collection covers every invariant in that intersection. It is a purposive illustration "
        "conditioned on successful hint availability, not a random sample or a corpus-wide hit-rate estimate.",
        "- Within each invariant, selection first prefers a bounded-verified TAR case where available, "
        "then prefers both archived traces at most 20 operations long, "
        "balances corrected incorrect classifications, then favors a combined trace length near eight, "
        "shorter learner bodies, and lexical case identity. AI outputs were not examined for selection.",
        "- Selected classifications: " + "; ".join(f"**{n} {prose(label)}**" for label, n in counts["classifications"].items()) + ". "
        "UNDERCONSTRAINED admits unwanted instances; OVERCONSTRAINED rejects required instances; "
        "BOTH exhibits both kinds of mismatch in the dataset's bounded classification.",
        "- **HiGenA-H** is the historical-data configuration of *Alloy repair hint generation based "
        "on historical data*. **HiGenA-M** enables its mutation fallback. All cases in this five-arm "
        "intersection use HiGenA-M's history path: mutation-only successes cannot also have a HiGenA-H "
        f"hit. The two recorded native hint strings are identical in **{counts['higena_h_m_native_equal_cases']} "
        f"of {counts['examples']} examples**. Equal H/M text is retained, and does not demonstrate "
        "a mutation advantage or an independent repair.", "",
        "## Reading the comparison", "",
        "- Current Canonical and AST hints use the portal's full compatible correct pool, including "
        "its oracle. The archived live controls and HiGenA used the recorded held-out pools. TAR, "
        "HiGenA-H and HiGenA-M were extracted from the preserved benchmark, not rerun. These are "
        "matched learner inputs, not an equal-training-data experiment.",
        "- Canonical and AST distances count different edit units and can select different nearest "
        "correct predicates. A first tree edit is guidance, not necessarily an executable source patch "
        "or a proof that the highlighted fragment is conceptually wrong. A related location or insertion "
        "anchor is explicitly distinguished from an exact learner node. Canonical display may omit "
        "binding distinctions retained by the distance representation.",
        "- AI receives the public question, learner body, and redacted current trace. The collector "
        "deliberately supplies no behavioral-instance evidence, so a summary may mention unavailable "
        "examples. These are actual recorded responses, not hand-authored surrogate explanations; "
        "unavailable responses remain labeled. AI wording is unverified advice, not an established "
        "repair or a measured learning outcome. Schema validation checks structure and operation IDs; "
        "it does not establish that the prose is semantically correct or describes the matching source "
        "location. A successful response can also contain an explanation that ends mid-sentence at "
        "the production character cap; the recorded wording is preserved. Provider credentials and "
        "deployment configuration stay private.",
        "- Oracle bodies, hidden target operands and complete candidate repairs are omitted. Native "
        "baseline strings use the existing conservative publication filter: quoted operand identities "
        "and operand-type names are hidden, while operator names and explanatory prose remain. This "
        "redaction is a disclosure policy, not a claim that the baseline failed.",
        "- TAR's independent verification is bounded Alloy checking, not universal equivalence. "
        "No new runtime ranking, learner experiment, or quality score is inferred from these examples.", "",
        "AI response status by live arm: " + "; ".join(f"{LABELS[metric]} " + ", ".join(
            f"{n} {status}" for status, n in counts["ai_statuses"][metric].items()) for metric in METRICS) + ".", "",
        f"{counts['ai_retried_explanations']} explanation "
        f"{'request was' if counts['ai_retried_explanations'] == 1 else 'requests were'} retried after a non-success response; "
        "the attempt statuses and timestamps are retained in the JSON and marked on affected examples. "
        "The collector requests one explanation per live-arm trace, with at most one retry; production "
        "caching can reuse a response, so explanation counts are not an API-call or billing count.", "",
        "Recorded TAR bounded verification: " + "; ".join(f"{n} {status}" for status, n in
            counts["tar_bounded_statuses"].items()) + ". Unresolved checks do not establish a correct repair.", "",
        f"{counts['reviewer_observations']} separate reviewer observations annotate specific recorded AI "
        "problems below: raw/canonical operator confusion, swapped operation targets, wrong repeated-term "
        "positions, and incorrect relational-join roles or columns. The captured AI wording remains unchanged. These observations are "
        "bound to the exact response hashes, are not model-generated explanations, and are not an "
        "exhaustive semantic audit or an estimated error rate.", "",
        "## Index by model family", ""]
    groups = defaultdict(list)
    for row in out["cases"]:
        groups[row.get("group", row["case_id"].split("/")[0])].append(row)
    for group, rows in groups.items():
        lines.append(f"- **{prose(group)}** ({len(rows)}): " + ", ".join(
            f"[{prose(row.get('predicate', row['exercise_id']))}](#example-{row['number']:03d})" for row in rows))
    lines += ["", "## Examples", ""]
    for row in out["cases"]:
        lines += [f"<a id=\"example-{row['number']:03d}\"></a>", "",
            f"### {row['number']:03d}. {prose(row['exercise_id'])}", "",
            f"**Question:** {prose(row['question'])}", "",
            f"Classification: **{prose(row['source_classification'])}**. "
            f"Case: {prose(row['case_id'])}.", "", "**Learner predicate body**", ""]
        lines += codeblock(row["learner_body"], "alloy")
        if "environment_before" in row and "environment_after" in row:
            lines += ["<details>", "<summary>Model environment</summary>", ""]
            environment = (row["environment_before"] + row.get("predicate_header", "pred " + row.get("predicate", "practice") + " ")
                           + "{\n  // TODO: write the learner predicate here\n}" + row["environment_after"])
            lines += codeblock(environment, "alloy")
            lines += ["</details>", ""]
        forms = row["current"]["canonical"]["response"]["canonicalForm"]
        lines += ["**Current learner canonical display**", ""]
        lines += codeblock("\n".join(forms) if forms else "Canonical display unavailable.")
        for metric in METRICS:
            lines += render_current(metric, row["current"][metric])
        verification = row["archived"]["tar"]["bounded_verification"]
        checked = verification.get("verified_correct")
        status = ("independently verified within the recorded bound" if checked is True else
                  "independent bounded check rejected the candidate" if checked is False else
                  "independent bounded verification unresolved")
        lines += [f"**TAR — saved native hint(s); {status}.**", ""]
        if checked is not True and verification.get("independent_validation_status"):
            detail = verification["independent_validation_status"]
            if verification.get("validation_error_class"):
                detail += ": " + verification["validation_error_class"]
            lines += ["Recorded validation outcome: " + prose(detail) + ".", ""]
        for i, native in enumerate(row["archived"]["tar"]["native_hints"], 1):
            lines += [f"{i}. {prose(native['hint'])}", ""]
            if native.get("line") is not None:
                lines += [f"   Recorded source range: line {native['line']}, column {native.get('column', '?')} "
                          f"to line {native.get('end_line', '?')}, column {native.get('end_column', '?')}.", ""]
        for arm in ("fm24-history", "fm24-mutation"):
            baseline = row["archived"][arm]
            lines += [f"**{LABELS[arm]} — saved native hint; source: {prose(baseline.get('hint_source', 'unspecified'))}.**", "",
                      prose(baseline["native_hint"]), ""]
        lines += [f"Full traces, all AI operation descriptions and environment: case {row['number']} "
                  f"in the [public JSON]({evidence_link}) (match `case_id`).", ""]
    lines += ["## Reproducing this document", "",
        "The renderer reads saved local research evidence only; it does not call a model, start a "
        "JVM, load credentials, or modify the archived benchmark. It checks case/source identities, "
        "selection hashes, five-arm hint availability, current nonempty atomic traces, and successful "
        "AI operation identities before publishing an allowlisted JSON projection. It also recomputes "
        "each original captured feedback and AI object hash, before public-field projection, using "
        "the collector's serialization and retains the "
        "separate source-audit file hash and source map. These checks establish record consistency, "
        "not semantic correctness of AI advice.", "",
        "```bash", "python3 benchmarks/alloy4fun/hint_examples_publish.py \\",
        f"  --study {provenance['study_directory']} \\",
        "  --output docs/benchmarks/alloy4fun-five-arm-hint-examples.json \\",
        "  --markdown docs/alloy4fun-five-arm-hint-examples.md", "```", "",
        "The input directory contains `selection.json`, `baselines.json` from `quality_extract.py`, "
        "`current.json` from the production collector, and `collection-source-audit.json`. Full private response archives are needed "
        "to re-extract baselines; they are not distributed in the public repository. The published "
        "JSON retains the public selected inputs and archive/record hashes for inspection. Fresh AI "
        "calls can vary; the text in this document reproduces the saved responses.", "",
        "Input SHA-256 bindings:", ""]
    for name, digest in provenance["input_sha256"].items():
        lines += [f"- `{name}.json`: `{digest}`"]
    lines += [f"- `collection-source-audit.json`: `{provenance['collection_source_audit']['sha256']}`"]
    lines.append("")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--study", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=ROOT / "docs/benchmarks/alloy4fun-five-arm-hint-examples.json")
    parser.add_argument("--markdown", type=Path, default=ROOT / "docs/alloy4fun-five-arm-hint-examples.md")
    args = parser.parse_args()
    out = build(args.study)
    if any(row["current"][metric]["ai"].get("status") == "not_collected"
           for row in out["cases"] for metric in METRICS):
        parser.error("Collection is incomplete; publication requires a recorded AI result for every live-arm trace.")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.markdown.parent.mkdir(parents=True, exist_ok=True)
    import os
    evidence_link = Path(os.path.relpath(args.output, args.markdown.parent)).as_posix()
    args.output.write_text(json.dumps(out, indent=2, ensure_ascii=False, allow_nan=False) + "\n", encoding="utf-8")
    args.markdown.write_text(markdown(out, evidence_link), encoding="utf-8")
    print(f"Published {len(out['cases'])} matched cases and all recorded live AI guidance")


if __name__ == "__main__":
    main()
