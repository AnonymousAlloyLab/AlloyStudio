# Independent observations for TRF-00

The [observation specification](../closure/traffic-refinement/observation-spec.json)
freezes what a completed request means before any warm-worker or caching
equivalence claim is made. The
[checker](../scripts/traffic_observation.py) imports no production projection,
server, scheduler, worker or provider code. The schema and its constraints do
not contain an assumed answer-equality field.

The executable baseline is commit
`e75b20419f8f92c2adc59ce083008ec897434cb1`. Ten source snapshots are retained under
`closure/traffic-refinement/observation-baseline/`; the
[manifest](../closure/traffic-refinement/observation-baseline.json) records their
SHA-256 digests and mechanically extracted projection inventories. Extraction
checks public catalogue/detail fields, feedback fields, metric identities and
the approved replacement-operator vocabulary. `check_baseline_git()` separately
checks the archived bytes against local Git objects. Clean verification reads
only the archived, manifest-bound files and does not need Git or the network.

For feedback, every status, diagnostic, metric, distance component, ordered edit,
trace flag, learner canonical form and source/canonical location is observable.
The pool declaration must be complete; the caller can bind its exact size and
metric. Arrays remain ordered, including edits and repeated occurrences with
the same text. Dictionary key ordering alone is irrelevant. Locations use
half-open UTF-16 ranges; when the exact learner body is supplied, the checker
validates its text, surrogate boundaries and body line/column positions.

For behavior, the score, scope, sample counts, four ordered categories and every
displayed witness field remain observable. The independent checks include the
category truth table, bounds of three witnesses and ten states, relation arity,
enumeration completeness and the rational half-up score rounded to 0.001.
Changing witness order, tuple order, atom names or the score is not an allowed
solver-nondeterminism exception. The checker validates evidence structure and
internal consistency; it does not prove that a witness satisfies Alloy formulas.

For explanations, operation IDs, instance IDs, their order, every description
and the summary remain observable. When comparing executions with the same
explicit provider response, wording is exact. Comparing separately sampled
external provider prose is outside computational equality. No broad removal of
explanation text, diagnostics or failure status is performed.

Delivery fields are a separate boundary: exercise ID, revision and requested
metric are checked against the supplied subscriber context, while evidence-token
shapes are checked. Computational comparison removes only the five explicitly
listed delivery fields. Their authorization and evidence binding need their own
state-machine obligations; token shape is not proof of either. Callers must
supply context to claim input/delivery binding. Without context the API checks
only the standalone observation contract.

Catalogue/detail schemas are closed, including the structured source provenance.
Authenticated administration, dashboard and control observations retain every
JSON leaf recursively; no field is silently discarded. Asset observations bind
MIME, byte count and the exact body digest under declared hash trust. The
specification separately lists HTTP status/header/entity-revalidation semantics
and permitted scheduling/transport changes. This checker is a finite decoded
response checker, not an HTTP parser or an administration authorization proof.

`validate_observation(kind, value, context)` returns only error codes and paths.
`semantic_observation(...)` rejects malformed/unknown analysis fields before
returning canonical bytes. `compare_observations(...)` returns an exact-match
verdict and explicitly distinguishes an equal failure from a successful
analysis. Thus matching timeouts cannot become successful hint-generation hits.

The constructed tests include changed costs, incomplete pools, swapped edits,
the wrong occurrence of repeated text, UTF-16 splits, nested target disclosure,
category/score drift and delivery confusion. Local smoke checks additionally
accepted actual canonical, AST and behavior responses for `some A` versus
`no A`, and all 181 currently projected catalogue/detail records. These finite
checks do not establish arbitrary-history worker independence or disclose any
private solution data.

The trust boundary is Python built-in JSON/type/Unicode/integer semantics, this
registered schema interpreter, archived-source extraction, SHA-256, the host
filesystem/process implementation and hardware. The Alloy evaluator, semantic
non-disclosure of natural language, capability authorization and the remaining
traffic obligations are not certified by defining this observation contract.
