# Traffic implementation obligation progress

The [successor ledger](../closure/traffic-refinement/status.json) preserves the
statements, pass conditions and dependencies of all 23 original traffic
obligations. It does not modify the frozen TB01 model package or reinterpret its
model proofs as production refinement. A closed subclaim does not close its
parent automatically.

Work proceeds in dependency order, beginning with TRF-00. The first concrete
subclaim is [TCFG01: numeric configuration guards](../formal/traffic_config/README.md).
Its registered verifier is `python3 scripts/verify_traffic_config.py`. Run-specific
reports, input snapshots, toolchain hashes, audit streams and negative controls
are kept in owned directories under `build/trf-closure/`.
The current review ladder is two GPT-6 Luna agents, two GPT-6.1 Sol agents, then
two GPT-6 Astra agents. Historical frozen review records retain their actual
model identities; current verification rejects the earlier GPT-6 Sol tier.

## Verified numeric subclaim

The [final machine-readable report](../closure/traffic-refinement/evidence/tcfg01-20261004T151737Z-7ab9b8eb/report.json)
records **VERIFIED** for `TRF00-NUMERIC`, under its declared trusted computing base.
Closure ID: `tcfg01-20261004T151737Z-7ab9b8eb`.
Input root: `6f492a657d251b98e7feb0667050db90bcffd2dc4409683b585bd93615607f82`.

The registered gate checked 55 theorem declarations with empty transitive axiom
sets in two clean, identical offline Lean 4.34.1 builds. It checked the restricted
production-AST translation, all six current source-bound reviews and their notes,
22 verifier tests, and rejection of the altered zero guard, introduced axiom and
placeholder proof. The [evidence archive](../closure/traffic-refinement/evidence/tcfg01-20261004T151737Z-7ab9b8eb/archive.json)
binds the unchanged raw report, frozen inputs, audits, network-isolation records,
negative-control outputs and logs. Reviews remain advisory; kernel checking and
the registered deterministic verifier determine the result.

This closes **one numeric subclaim and zero complete TRF obligations**. All 23
original obligations remain OPEN, including the rest of TRF-00. The report applies
to its frozen source root, not later edits or undeclared deployment environments.

## Constructed breaches and repairs

The [original counterexamples](../closure/traffic-refinement/configuration-counterexamples.json)
bind to the released source revision. They do not require a JVM, provider call or
private exercise input:

- `NaN` worker task/parse/age limits could prevent recycling even after a billion
  synthetic tasks and seconds.
- A `NaN` process capacity admitted nine reservations despite the intended finite
  four-process envelope. Fractional process limits also admitted an integral
  process beyond the stated fractional capacity.
- A zero-entry result cache retained an entry. `NaN` cache TTL never expired it;
  `NaN` evidence limits allowed 129 pins beyond the intended 128-entry default.
- Invalid scheduler and explainer constructor profiles were accepted.

These were internal configuration boundaries, not numeric settings exposed in
learner requests. The repairs validate exact scalar types, finite intervals and
lane relationships before allocating state, locks, threads, or processes. Booleans
and numeric subclasses are rejected. Duration comparisons use exact integer
ratios, including extremely small finite floats and integers too large for a
floating conversion. Accepted values are returned unchanged.

The same guards cover HTTP profile fields, process limits, worker recycling,
scheduler/cache/evidence bounds, explanation limits and direct portal construction.
The helper is included in the portable backend and IIS package. Relocated fixture
copies and isolated runtime-checker entrypoints include the new dependency.

The GPT-6.1 Sol review constructed a further breach in the first candidate:
a custom metaclass could overload equality so that tuple membership treated an
unsupported object as a built-in numeric type. The normalized Lean theorems still
held, but that Python classification violated the intended correspondence. The
full gate rejected the candidate. The repaired guard uses explicit type identity,
and regressions require rejection before any user-defined ratio method runs.
The [rejected candidate evidence](../closure/traffic-refinement/evidence/rejected-metaclass/archive.json)
preserves the old source, executable witness, observed result, reviews and rejected
report. Original development runs also remain under `build/trf-closure/`.

## What the numeric proof establishes

The restricted translator reads the actual Python guards and generates the Lean
program. It permits only registered type checks, exact ratio normalization,
constant exceptions, arithmetic guards and identity returns. A changed arithmetic
condition changes the generated program. Unknown control flow, dynamic calls,
changed normalization or transformed returns are rejected.

Independent interval predicates specify the intended accepted values. The Lean
theorems connect the generated guard program to those predicates, prove value
preservation and check invalid-value witnesses. A deliberately changed zero guard
must fail the independent specification. Separate negative controls reject proof
placeholders and introduced axioms. All declarations, including generated and
private ones, are audited for transitive axiom dependencies.

This is a proof about the admitted scalar program under the explicitly declared
Python normalization, translation, kernel and host trust boundary. Constructor
integration is tested separately; it is not a proof of arbitrary Python execution,
resource allocation or the complete portal.

## Runtime regression evidence

The [source-bound regression report](../closure/traffic-refinement/evidence/python-regressions/report.json)
records **844 passing Python tests in 294.529 seconds**, with the raw test log and
76 unchanged source hashes. This includes the new numeric rejection and metaclass
controls, runtime scheduler/worker tests, packaging all 181 exercises, extracted
backend feedback, fresh-clone setup, and Windows build/relocation fixtures.
OpenAI calls were disabled. These are finite tests on Linux; native Windows and
macOS execution and universal constructor/host refinement are not established.

## Remaining TRF-00 requirements

TRF-00 remains open until all of its original requirements pass together:

1. One complete executable profile must specify every required byte, age and RSS
   limit, deployment mode, relationship and source binding. Shared-host and proxy
   budgets cannot be inferred from per-process application counters.
2. The complete independent observation contract must bind successful results,
   failures, ordering, locations, behavioral instances and allowed disclosure.
3. Initial-state correspondence must account for random generations and OS
   handles as explicit inputs or prove a stated abstraction. Valid numeric
   scalars alone do not prove unique valid initialization.
4. The full profile, observation contract, production mapping, verifiers and
   provenance must be frozen and verified together.

TRF-01 through TRF-22 retain their original open status and dependency graph.
Finite differential tests remain TESTED evidence; they do not discharge universal
worker-history or host-language refinement. Prepared-reference caching remains
deferred. No existing release or historical proof record is relabeled by this work.
