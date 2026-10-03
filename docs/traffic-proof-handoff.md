# Traffic proofs and implementation handoff

The [TB01 proof block](../formal/traffic/README.md) supplies executable Lean models
for the [backend performance specification](backend-performance-spec.md).
Production code is unchanged. The implementation can now be organized around
the model transitions; it must not equate an abstract proof with completed
Python/Java/JavaScript correspondence.

The authoritative proof surface is
[claims.json](../formal/traffic/claims.json), with exact declaration/type hashes in
[theorems.json](../formal/traffic/theorems.json). Reproduce its evidence using
`python3 scripts/verify_traffic_proofs.py`; its report separates mathematical block
status from unestablished production refinement. All 23 end-to-end
[TRF obligations](../closure/traffic-obligations.json) remain OPEN until their
implementation, correspondence and acceptance requirements are fulfilled.

## What the implementation must preserve

Retain Canonical as default, both Canonical and AST analyses, complete ordered
correct pools including every oracle, first-minimum ties, current canonical and
raw source locations, diagnostics and hidden-solution projection. Keep behavior
facts, bounds, score rounding, category meanings and up-to-three examples; keep
Luna's per-operation/per-instance guidance and summary. Resource failures remain
explicit failures. Do not substitute a simpler metric, incomplete pool or changed
solver sampling policy to meet a performance target.

The mathematical models deliberately do not implement Alloy. Their exact key
values and pure observations provide the scheduler interface; they are not proofs
of ACGN canonicalization, AST optimality, parser behavior or fresh/warm JVM
equivalence. The original L00–L23 obligations are not discharged by TB01.

The three modules are independent fragments, not a proved composition of the
whole scheduler. Resource bytes are declared reservation charges, not measured
Python/JVM heap sizes. The generic resource ledger proves bounds, simultaneous
owner uniqueness and immediate release idempotence; a delayed release after an
externally reused owner ID is a separate hazard. The process pool issues fresh
incarnations, while the implementation must also issue fresh lifecycle IDs for
request/cache/evidence reservations. Do not reuse a freed integer identifier
while stale callbacks can still reference it.

## Exact model support

The frozen inventory has **560 theorem declarations**, including private/generated
declarations and supporting lemmas, across three model claims. The ordinary gate
must still verify that exact inventory and the six-review ladder for each run.
The [22 witness groups](../formal/traffic/witnesses.json) cover the specification's
18 numbered traces plus owner reuse, metric omission, mismatched delivery and
terminal failure. Miniature examples such as late-invalid pool scanning and
diagnostic value leakage illustrate a policy error; they do not execute Java.

| Model | Main executable interface | Proved mathematical relationship |
| --- | --- | --- |
| Resources | `ledgerStep`, `poolStep`, `warmJobs` | Reachable owned count/byte/lane bounds; fresh process incarnations and busy tickets; stop retains reservations; matching reap; arbitrary finite warm-job replay preserves launch count. |
| Reuse | `acceptRequest`, `cancel`, `supersede`, `dispatch`, `complete`, `fail`, `latestAdmission` | Reachable unique work/count bounds, running ownership, independent-evaluator cache/pin soundness, key-to-delivery agreement, shared cancellation and orphan cleanup, current-delivery publication. |
| Ingress | `bucketStep`, `ingressStep`, decoded admission/frame/retry and handler policies | Logical token conservation, separate reserved envelopes, absolute-deadline policy, validated allocation plans and current-frame identity, bounded retry credits and pre-handler ownership. |

| Specification targets | Supporting mathematics and remaining boundary |
| --- | --- |
| TRF-00 | Models quantify over explicit finite natural-number limits; a concrete operational profile, encodings and complete application TCB remain to be frozen. |
| TRF-01–03 | Parsed-value admission, token conservation, reservation/count bounds. Real decoding, measured bytes, all resource classes and composed atomic admission remain OPEN. |
| TRF-04–05 | Single pending computation, subscriber scoping, queued orphan removal and latest-channel identity. Server capability generation and round-robin fairness are not proved. |
| TRF-06–07 | Worker incarnations/tickets, stop/reap policy and frame guards. Actual OS exit, binary protocol parsing and cross-module ticket interpretation remain OPEN. |
| TRF-08–09 | Structured context equality and derived cache/pin soundness for an independent mathematical evaluator. Actual key extraction and Java observation correspondence remain OPEN. |
| TRF-10 | A constructed late-invalid-candidate witness; prepared-reference reuse and complete Java scan refinement are not proved. |
| TRF-11 | Warm lifecycle replay has no new model launches. JVM state reset, parser registrations, solver cleanup and fresh/warm evaluator equivalence are not proved. |
| TRF-12 | Behavior key is independent of selected structural metric. Score arithmetic, category/solver semantics and nondeterministic observations are not proved here. |
| TRF-13–14 | Current delivery guards, observed supersession and evidence pins. Async capture, token/credential epoch binding and Luna request/response integration remain OPEN. |
| TRF-15 | Value-leak counterexample and environment-name filtering. The actual public-value projection, permitted environment values and all production sinks remain OPEN. |
| TRF-16 | Logical deadlines, bounded eligible retries and lifecycle safety fragments. Composed restart/shutdown orchestration and physical scheduling are not proved. |
| TRF-17–18 | Production transition, decoder and frontend correspondence remain OPEN; no newly generated production policies are installed. |
| TRF-19–21 | Implementation behavior, load/performance and platform tests remain for the refactor. Proof-model witnesses are not substitutes. |
| TRF-22 | The separate TB01 model gate checks frozen inputs, two builds, inventories, axioms, witness bindings and review provenance. Full production closure remains unestablished. |

Memoization is universal in the supplied mathematical `eval` function, not an
assumption that Alloy or a warm JVM computes that function. Nondeterministic
behavioral enumeration needs an explicit observation relation before that model
can be applied to it. Likewise the warm replay theorem contains successful
completion events; it does not assert that every actual job eventually completes.

## Implementation order and review gates

1. **Freeze a concrete resource profile and measure the one-shot baseline.**
   Fill the OPEN byte, age, deadline, RSS, parser-count and queue limits in the
   specification. Record exact inputs, runtimes and hardware. Bound connection
   admission before creating handlers. Keep public/control reservations distinct
   and combined budgets authoritative. Do not use HTTP Origin or client revisions
   as quota/cancellation authentication.
2. **Introduce exact request keys and atomic ownership.** Capture one validated
   snapshot; use exact learner bytes, exercise/environment/pool/policy identities,
   algorithm and bounds. Reserve cache/in-flight/job/subscriber resources under
   one lock or serial event loop. Join identical work, cap followers, detach only
   the cancelling subscriber and retain another caller's job. Treat a digest as
   an index with an exact equality check or explicitly register collision trust.
3. **Add private framed persistent workers.** Keep the current one-shot CLI for
   differential tests/rollback. The new dispatcher invokes the existing evaluators
   sequentially, one job per JVM; it cannot repeatedly call the EOF-reading
   `main`. Match protocol version, worker incarnation and request ticket. Cap
   frames before allocation and discard malformed, stale or duplicate replies.
   No credential inheritance; use a minimal launch-environment allowlist.
4. **Implement lifecycle ownership literally.** A cancelled/timed-out worker
   keeps its process reservation until confirmed exit/reaping. Separate running
   job, process and retained-delivery resources. Make terminal handling idempotent.
   Replacements cannot briefly exceed the total limit. Default obsolete-running
   work can finish without display, avoiding kill/restart on each edit. Recycle
   under bounded task/age/parser-memory budgets and limit restart attempts.
5. **Qualify warm evaluator reuse before enabling it.** Audit exception paths,
   graph arenas, identity counters, parser objects and behavior solver handles.
   `Throwable` catches must not conceal fatal VM failure from the supervisor.
   Alloy's `deleteOnExit` registrations require an equivalent owned-file adapter
   or bounded recycling; unlinking files does not remove those registrations.
   Same-account JVM filesystem access is not a process sandbox.
6. **Add context-complete cache and browser reuse.** Preserve independent
   subscriber delivery metadata. Behavior may be shared across metric switches;
   structural traces and their explanations may not. Pin admitted explanation
   evidence under the byte budget rather than re-running evicted feedback.
   Suppress obsolete stages after server-observed supersession; browser guards
   reject stale display immediately. Preserve Live-off/manual-check semantics.
7. **Only then optimize reference preparation.** Cached canonical/parser objects
   have not been shown deeply immutable. Prove or check the actual ownership and
   mutation contract, retain complete-pool evaluation and independently gate this
   change. JVM reuse does not require parsed-reference reuse in its first version.

## Required production bridges

For each change, record the concrete file/function, model transition and its
linearization point. Supply a deterministic extraction/bridge record with source
hashes and an explicit interpretation. Do not list a theorem name beside a
function and call that semantic correspondence.

| Boundary | Required implementation evidence |
| --- | --- |
| Admission and credits | Pre-handler reservation, strict actual HTTP parser, bounded maps/buffers, monotone clock extraction, integer overflow policy, atomic debit/refill, trusted proxy/control listener configuration. |
| Owned resources | Concrete job/subscriber/worker tables derive the same counts and byte reservations as the model; every exceptional path has a mapped owner transition. |
| Process lifecycle | OS handle/incarnation identity, launch reservation, actual exit/reap observation, timeout/kill ordering, no unbudgeted fallback or overlapping replacement. |
| Frame guards | Real bytes decode uniquely into modeled values; lengths checked before allocation, bounded read deadline, no extra frames accepted. |
| Exact keys | Actual UTF-8/body/metric/snapshot/environment/pool/bounds extraction and structured encoding agree with the mathematical identity. |
| Reuse | Locks make lookup/insert/join atomic; values are immutable; fresh/warm evaluator results are compared and residual mutation/cleanup is qualified. |
| Browser delivery | Existing success guards consume the right captured raw comparisons at the correct async point; cancellation is channel-owned; no premature downstream work after observed supersession. |
| Disclosure | Value-level public projection, not just field allowlists; diagnostics, worker frames, telemetry and environment cannot carry hidden code or credentials. |

Finite generated Boolean policies can provide a narrow mechanically checked
bridge where useful, following the existing Session/Pool policy approach. They
do not prove raw decoder correctness, arbitrary host loops, locking or OS events.
Treat those as separate obligations. Do not assume an arbitrary Java evaluator
is pure merely because a Lean memoization theorem is parameterized by a function.

## Acceptance and claim boundaries

Execute the specification's bounded fake-worker/provider tests, fresh/warm
Canonical and AST comparisons across all 181 invariants, behavioral witness
checks, race/cancellation/fatal-worker tests and Linux/macOS/Windows-IIS lifecycle
checks. Use owned scratch and conservative concurrency. Preserve valid previous
benchmark results; this work is not a rerun or modification of that experiment.

The primary launch gate is **100 distinct valid sequential edits after prewarm
with zero additional JVM starts**, under a pre-frozen profile that accommodates
the workload without recycling or injected faults. Report computations and
starts separately. Fewer requests/launches is not itself evidence of equivalent
answers; the differential gates must pass too. Measure cold/warm p50/p95 and
final-edit-to-visible-feedback latency under equal resource budgets.

TB01 proves only its finite registered mathematical surface. Full closure also
requires fresh/warm implementation correspondence, actual decoder/clock/process
semantics, resource measurements and the remaining specification limits. OS,
compiler/runtime, proof-verifier and hardware trust is explicit. No assumption
of bounded physical memory, actual process termination, fair wall-clock service
or unmodified behavior of unseen Java state is hidden in the model claims.
