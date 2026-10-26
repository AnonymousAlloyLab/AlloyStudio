# CRUD-grounded structural retrieval for guidance

The guidance agent receives the learner's canonical structure at the exact
positions named by the canonical repair language. Each edit explanation relates
that selected occurrence to the public exercise question and asks the learner
what to inspect. Binding names, inserted expressions and complete correct
predicates remain outside the structural retrieval payload.

## Contract

1. Export structure from the same prepared **learner** canonical graph used for
   the distance calculation. Never export the selected correct predicate or
   normalize another model merely to answer a retrieval request.
2. Use `[t,M,[child,...]]` for matrix occurrences, `[t,q,[]]` for metric-ordered
   prefix bindings, and `[T,_,[child,...]]` for the separate temporal skeleton.
   Shared graph nodes can have several occurrence addresses. Labels do not
   identify occurrences, and no lookup falls back to substring matching.
3. Rebuild each public node from finite categories, allowed operator tokens,
   numeric addresses, generated node IDs and child counts. Discard unknown
   fields. Node IDs express sharing within this export, not semantic equivalence
   between arbitrary e-classes or across revisions.
4. Retrieve the selected node, bounded enclosing/child/sibling context and
   binding context for each operation. Binding CREATE selects a valid gap;
   matrix CREATE operations identify an existing anchor when one exists. Current
   temporal insertion paths describe target-side destinations and remain
   unresolved; a new phase can also lack any learner anchor. Aggregates and
   unsupported positions retain their costs without invented atomic commands.
5. Bind retrieval to a content-identified learner snapshot. A request using
   another snapshot ID fails. All operations in one pass refer to the original
   learner snapshot. The concealed edit payload cannot construct authoritative
   intermediate repaired states. The next learner edit produces a new snapshot.
6. Reuse the portal's existing pinned feedback and revision/dependency checks.
   Client-supplied graphs, DSL and repair targets are not explanation inputs.
   Retrieval must not launch a JVM, call an embedding service, query the private
   correct pool, or make one provider call per operation.
7. Bound structural export, retrieval neighborhoods and total prompt bytes.
   Missing or capped structure is explicitly unavailable/partial; the original
   distance, existing hints and learner evidence remain usable. Never silently
   claim a partial graph is complete.
8. Preserve the selected provider, operation IDs, instance explanations,
   zero-distance encouragement, progressive hint UI and output validation.
   AST mode retains its existing AST guidance and cannot acquire fictitious
   canonical positions.

## Prompt behavior

The stable tutor instructions explain the four CRUD verbs and their address
semantics. Dynamic retrieval results are supplied as data beside the learner's
raw body, canonical display, public question and bounded examples. For each
operation the tutor should explain the existing operator in its retrieved scope,
connect a possible mismatch to the question, and suggest one point to inspect.
It must not treat an insertion anchor as a proven faulty operator, complete a
concealed target, assume earlier hints have already been applied, or print a
complete repair route. The user sees short natural-language guidance rather than
node IDs or the DSL.

This separation of stable instructions and relevant retrieved context follows
the [official OpenAI prompt-engineering guidance](https://developers.openai.com/api/docs/guides/prompt-engineering).
The payload also passes through the existing Anthropic, Gemini and compatible
provider adapters; retrieval itself is provider independent.

## Runtime and limits

`LearnerStructure` exports the selected representative's occurrence graph from
the prepared learner after the existing metric work budget closes. It performs
no additional parsing, normalization, equivalence checks or solver calls. It is
bounded to 2,048 occurrences, 128 phases, 64 child edges per address and 256 KiB.
Its 25 ms elapsed-time allowance is cooperative: traversal checkpoints enforce
it, while individual sorting/reflection and serialization calls are not
preempted. If adding structure would exceed the existing response byte limit,
the exporter drops the enrichment instead of discarding a completed distance.
The metric's final checkpoint still rejects an observed expired work budget;
optional export and final serialization run afterward and are not covered by
that deadline. The updated publication template checks this control flow and
byte fallback structurally, without renewing historical Lean proof bindings.

`server.py` projects this structure again before caching and pinning feedback.
`canonical_retrieval.py` builds a detached index from that pin. Retrieval includes
at most six ancestors, six children, four adjacent siblings and six prefix
bindings per operation, with explicit truncation. The complete structural prompt
addition is at most 32 KiB and also fits the remaining 128 KiB education-input
allowance. Zero-distance passes provide READ-only overviews for at most three
phase roots and the temporal root. No process-global learner graph cache is added.

For an internal guiding agent that revisits a step:

```python
from canonical_retrieval import prepare

snapshot = prepare(pinned_feedback, student_body,
                   question=public_question, context_identity=dependency_version)
step = snapshot.retrieve(snapshot.snapshot_id, operation_index=0)
node = snapshot.retrieve(snapshot.snapshot_id, address=[0, "M", [1, 0]])
```

The operation index is zero based. These calls are in-process reads, not new
public endpoints. Reuse the prepared object within a guidance pass; prepare a
new object from newly pinned feedback after a learner edit. Output objects are
copies. A mismatched snapshot ID returns unavailable. The existing explanation
cache includes complete prompt bytes, instructions, provider identity and
dependency context, so changed retrieval evidence cannot reuse stale prose.

## Checks

Run the focused suites from the repository root:

```sh
python3 -m unittest discover -s tests -p 'test_canonical_retrieval.py' -v
python3 -m unittest discover -s tests -p 'test_structural_guidance.py' -v
python3 -m unittest discover -s tests -p 'test_learner_structure.py' -v
python3 -m unittest discover -s tests -p 'test_education*.py' -v
```

The opt-in live harness uses the configured `gpt-6-luna` profile and makes five
guidance requests, including multiplicity, binding, temporal, matching and
`graphs.inv7` examples. It writes only projected learner evidence and validated
answers beneath `build/`, never credentials or private pool bodies:

```sh
python3 scripts/check_structural_guidance.py --live --output build/structural-guidance-live
```

The ordinary test suites use synthetic providers and make no paid calls. The
public prerelease notes distinguish automated contracts, live responses and
platform-specific deployment checks.

## Proof boundary

The existing [ASRL Lean model](../formal/repair_language/README.md) establishes
model-level address/role, lookup and accounting properties. It does not prove
the Java exporter, Python retrieval, provider output or complete portal correct.
The production bridge is checked through implementation tests and live guidance
examples. Prompt instructions and output shape checks do not constitute a proof
that a generative model can never suggest a solution.
