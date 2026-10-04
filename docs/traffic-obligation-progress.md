# Traffic implementation obligation progress

The [successor ledger](../closure/traffic-refinement/status.json) preserves the
statements, pass conditions and dependencies of all 23 original traffic
obligations. It does not modify the frozen TB01 model package or reinterpret its
model proofs as production refinement. A closed subclaim does not close its
parent automatically.

**Current status: TRF-00 and TRF-01 VERIFIED at their recorded source roots; 21
original obligations remain OPEN.** The detailed TCFG03/TING02 results and trust boundary
appear below. Work proceeds in dependency order. The first concrete
subclaim is [TCFG01: numeric configuration guards](../formal/traffic_config/README.md).
Its registered verifier is `python3 scripts/verify_traffic_config.py`. The next
[HTTP profile and initialization block](../formal/traffic_profile/README.md) uses
`python3 scripts/verify_http_profile.py`. Run-specific
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

At this stage, **one numeric subclaim and zero complete TRF obligations** were
closed. All 23 original obligations remained OPEN, including the rest of TRF-00. The report applies
to its frozen source root, not later edits or undeclared deployment environments.

## Verified HTTP profile and initialization subclaim

The [TCFG02 final report](../closure/traffic-refinement/evidence/tcfg02-20261004T161717Z-ff079208/report.json)
records **VERIFIED** for `TRF00-HTTP-PROFILE-INIT`, under its declared TCB.
Closure ID: `tcfg02-20261004T161717Z-ff079208`.
Input root: `c5818c72dd393a93e8b19aa8db986553c4f49ab65dcf20b20193e81da3eff506`.
The [independent specification](http-profile-obligation.md) preceded implementation.

This block establishes acceptance for all 23 HTTP fields and the three existing
relations, preservation of accepted scalar values, and validity/uniqueness of the
abstract initial admission state for a supplied Boolean lane and signed clock
sample. The registered AST bridge binds the actual profile code and the
Admission, TokenBucket and listener constructor consumers. Dataclass/container
semantics and the explicitly erased lock/object identities remain declared trust;
this is not a proof of arbitrary Python execution or later admission histories.

The gate checked two clean identical offline builds with **95 empty-axiom
theorems: 40 new and 55 reused from TCFG01**. All eight negative controls passed:
three changed relations, wrong lane selection, wrong token unit, nonzero initial
occupancy, introduced axiom and placeholder proof were rejected. All 37 registered
bridge/gate/runtime tests passed. The [immutable archive](../closure/traffic-refinement/evidence/tcfg02-20261004T161717Z-ff079208/archive.json)
contains the bound inputs, raw audits, correspondence records, logs and report.

Six source-bound advisory reviews completed in the current model order. Automatic
screening blocked Astra B's broader production-code review; its final review is
explicitly limited to the Lean mathematics and recorded proof evidence. The other
five reviews include implementation coverage as recorded in their notes. Reviews
supply no proof authority and cannot override the registered gate.

The [final integration report](../closure/traffic-refinement/evidence/tcfg02-python-regressions/report.json)
records **876 passing Python tests in 294.528 seconds**, with 103 unchanged input
hashes. It includes existing portal, worker, scheduler, administration and SQL
tests, all-181 exercise packaging, relocation/fresh-clone checks and Windows build
fixtures. OpenAI calls were disabled. This was Linux execution; native Windows
and macOS execution is not claimed. The earlier 875-test run is preserved as a
superseded source binding because the copy repair occurred during that run.

The [five original constructor witnesses](../closure/traffic-refinement/evidence/http-profile-before-repair/archive.json)
show arbitrary/invalid profile acceptance, truthy non-Boolean lane selection and
malformed/raising clocks after allocation. Constructors now validate and copy
before their registered runtime allocations. A further
[metadata counterexample](../closure/traffic-refinement/evidence/http-profile-copy-breach/archive.json)
showed that `dataclasses.replace` could substitute defaults when an exact profile
overrode its instance dataclass metadata. Explicit copying of the 23 registered
attributes now preserves values and isolates the admitted configuration from later
changes to the caller's original. Both the failure and passing regression remain
available. These concern internal configuration inputs, not a learner-facing
configuration API.

**At this stage two subclaims were verified; all 23 complete original obligations
remained OPEN.** TCFG01 and TB01 frozen inputs and earlier evidence were unchanged.
The remaining service profile, observation and initialization requirements were
subsequently discharged together by TCFG03 below.

## TRF-00 closed: complete selected startup profile

The [TCFG03 registered report](../closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/report.json)
records **VERIFIED for TRF-00**, preserving the original statement, pass condition
and dependencies. The [archive](../closure/traffic-refinement/evidence/tcfg03-20261004T170110Z-0c2dd9e4/archive.json)
binds the unchanged report, frozen inputs, toolchain inventory, raw audits,
constructor correspondence, isolation records, negative controls and test logs.
Its input root is `0a865739ab334bc954132df3351d48471bb183dfc9e18e99ad1f81ce01a70aad`.

The [specification](service-profile-obligation.md) freezes one fresh Linux process
on direct loopback port 8080, with persistent-engine mode, two feedback scheduler
threads and one behavior thread. The cut is successful `Portal.__init__` return,
before prewarming or traffic. Root, catalogue snapshot, generation, service ID,
CSRF value and clock are explicit inputs. Threads are started, not necessarily
parked. Native handles, lock identities/ownership and program counters are erased;
empty-queue thread bootstrap stutters under the declared startup TCB.

The complete finite profile contains **213 quantities**: 195 bound to actual source
values, 16 deployment targets and two explicitly disabled prepared-cache limits.
The source/dependency manifest binds 459 files. The restricted interpreter connects
159 constructor statements to 33 objects and 308 initial-state cells. Independent
validity requirements cover empty owned collections, zero occupancy, flags,
configuration, aliases and explicit inputs; the graph fixture alone is not the
specification. The [independent observation contract](traffic-observation-contract.md)
binds ten archived pre-optimization sources and preserves computation, ordering,
failures, locations, behavioral categories and disclosure fields.

The gate checked **136 empty-axiom theorems**, including 41 new declarations and
95 reused from TCFG01/TCFG02, in two identical clean offline Lean 4.34.1 builds.
All seven negative controls passed: changed occupancy, cache alias and clock were
rejected by the independent specification; introduced axioms and placeholders
were rejected by both source checking and actual compiler/transitive-audit
controls. The 64 registered bridge, observation, constructor and verifier tests
passed. A real fresh-process witness checked the 308 cells with three started
threads, one listener and zero JVM launches, then closed its resources.

The [full regression report](../closure/traffic-refinement/evidence/tcfg03-python-regressions/report.json)
records **935 passing Python tests in 301.247 seconds** against unchanged
source hashes. OpenAI calls were disabled. These are Linux tests, including
Windows build fixtures; native Windows or macOS execution is not claimed.
All six [source-bound advisory reviews](../formal/reviews/TCFG03/) completed in
order: two GPT-6 Luna, two GPT-6.1 Sol, two GPT-6 Astra. The registered deterministic
gate, not the reviews, determines closure.

Development failures are retained. A
[reconstructed loader-root counterexample](../closure/traffic-refinement/evidence/tcfg03-loader-root-counterexample/evidence-index.json)
shows how an adapter that ignored a changed catalogue root could hide that change;
the repaired interpreter requires the exact root argument. This is a reconstructed
vulnerable fixture, not a byte-exact historical-source claim. The
[rejected proof draft](../closure/traffic-refinement/evidence/tcfg03-rejected-propext/evidence-index.json)
contained a transitive `propext` dependency, removed before the accepted inventory.
A [readonly-scratch infrastructure failure](../closure/traffic-refinement/evidence/tcfg03-rejected-readonly-scratch/archive.json)
was repaired by making only copied mutation-control sources writable. The final
gate rebuilt all proof evidence after that repair.

The Astra review also constructed an
[optional-delivery-field witness](../closure/traffic-refinement/evidence/tcfg03-optional-delivery-boundary/archive.json).
The observation schema permits delivery metadata to be absent, even when subscriber
context is supplied; it checks equality when a metadata field is present. A
computational comparison PASS therefore does **not** prove a complete delivery
envelope, authorization or capability binding. Those remain later obligations.

**One complete original obligation is verified; TRF-01 through TRF-22 remain OPEN.**
TRF-00 proves the frozen abstract initial relation under explicit translation,
Python, allocation, kernel and host trust. Finite RSS/scratch/deployment budgets
are specified here, not enforced by this proof; enforcement remains TRF-03/TRF-21.
This does not prove future worker histories, solver truth, provider wording or
native deployments. TB01, TCFG01 and TCFG02 frozen inputs and historical evidence
remain unchanged. The next obligation is TRF-01, strict inbound admission before
expensive allocation.

## TRF-01 begun: sampled read deadlines verified

[The TING01 report](../closure/traffic-refinement/evidence/ting01-20261004T172620Z-16bb5a5f/report.json) records **VERIFIED** for child
`TRF01-DEADLINE-CUTS`; **TRF-01 itself remains OPEN**. The [specification](ingress-deadline-obligation.md)
preceded the production repair. Closure ID: `ting01-20261004T172620Z-16bb5a5f`.
Input root: `a8dcf1edc4506ba12754fdde3475b1b7a315eabe9d763211cf206dafe57d5da8`.

The repair rejects expired buffered headers, checks after standard-library header
parsing and JSON decoding, and starts the body phase from exactly the validated
header clock sample. Public and administrator JSON readers share the final check.
[Preserved witnesses](../closure/traffic-refinement/evidence/ting01-discovery/evidence-index.json)
bind the original source to late-header health dispatch and late-decoder channel
dispatch. Missing final CRLF was already rejected and is recorded as a negative
control, not a repaired defect.

Two identical clean offline Lean 4.34.1 builds checked **56 empty-axiom theorem
declarations: 33 explicit and 23 generated**. The bridge maps one primitive and
fourteen methods to six successful-path programs under its declared work/call
interpretation. The model permits unguarded late execution; a separate structural
policy rejects missing guards. Six negative controls passed, including actual
source comparison/placement mutations and compiler/transitive-audit rejection of
placeholders and rogue axioms. All **69 registered tests** passed. The unchanged
raw report, inputs, audits, isolation witnesses and logs are [archived](../closure/traffic-refinement/evidence/ting01-20261004T172620Z-16bb5a5f/archive.json).

All six source-bound advisory reviews completed in order: two GPT-6 Luna, two
GPT-6.1 Sol and two GPT-6 Astra. The [full regression report](../closure/traffic-refinement/evidence/ting01-python-regressions/report.json)
records **982 passing Python tests in 299.641 seconds**, with unchanged input
hashes and OpenAI disabled. This is Linux evidence, including Windows build
fixtures; it is not native Windows/macOS or IIS execution.

The [first broader regression failure](../closure/traffic-refinement/evidence/ting01-regression-before-fixture-update/archive.json)
was the historical TCFG02 bridge correctly rejecting a new runtime class member.
Its positive and mutation tests now use their hash-checked archived source fixture;
a separate regression checks that the unchanged legacy verifier rejects the newer
layout. Current deadline behavior is checked by TING01. No historical verifier or
proof record was relaxed to admit the new revision.

The theorem concerns a successful **sampled completion cut**, not unsampled wall
time after the check or total handler lifetime. Python, finite clock ordering,
work abstraction, standard-library calls, Lean and host isolation remain explicit
trust. Full byte/request-line/JSON parsing, pre-handler allocation refinement and
public/control lane composition remain open. Discovery found additional decoder
cases to address, including embedded bare CR in request lines and repeated charset
parameters. The [capacity investigation](../closure/traffic-refinement/evidence/ting01-capacity-discovery/evidence-index.json)
found no constructed reservation breach, but is finite test evidence only. The
TRF-00 default has the private control listener disabled; proving enabled private
admission requires a separately frozen topology and paired-listener witness.

Historical TRF-00 evidence and original obligation definitions are unchanged.
TING01 has a new source root for the deadline repairs; it does not reclassify that
historical root as current. One complete obligation remains verified and 22 remain
open. Next within TRF-01: strict raw request/header/JSON decoding correspondence.

## TRF-01 previous successor: repaired candidate, final review gate blocked

The [TING02 candidate report](../closure/traffic-refinement/evidence/ting02-20261004T182729Z-cd397804/report.json)
records two identical clean offline Lean 4.34.1 builds, **303 theorem
declarations with empty transitive axiom sets**, 52 registered mapping roles,
97 passing ingress checks and six passing candidate claims. Its authoritative
status is **BLOCKED**, not VERIFIED. The [archive](../closure/traffic-refinement/evidence/ting02-20261004T182729Z-cd397804/archive.json)
preserves the exact report, input snapshot, proof audits, toolchain hashes,
negative controls, correspondence and test records. The complete contract and
declared trust are documented in [strict ingress closure](strict-ingress-closure.md).

Production now checks raw request-line syntax, target encoding, Host and header
occurrences before standard parser normalization or business dispatch. Shared
JSON readers enforce exact length, bounded nesting and UTF-8 object policies.
Header/body completion checks retain the sampled deadline repairs. Reservations
precede thread allocation and remain held until a zero-time join succeeds and
the thread reports it is no longer alive. Public and optional loopback control
listeners retain independent, nonborrowable capacities.

The [preserved development evidence](../closure/traffic-refinement/evidence/ting02-discovery/evidence-index.json)
includes malformed-request acceptance, premature release during thread teardown,
interrupted startup, an unconstrained POST/body-policy bit in the first formal
composition, and an unmapped handler override. The refined wire model derives
method policy, header/body lengths and parsing inputs from the same raw bytes;
the source bridge closes the complete handler and reader classes.

A further [native scheduling witness](../closure/traffic-refinement/evidence/ting02-ident-publication/evidence-index.json)
showed that CPython may publish a thread identity before marking it started,
while `is_alive()` remains false. The earlier reaper then admitted two handlers
under a limit of one. The repaired witness keeps one reservation and one handler.
The model explicitly represents identification before startup, constructs the old
identity-only counterexample, and proves that a refused join retains ownership.
The bridge rejects the old guard even if its class hash is registered.

The [latest regression archive](../closure/traffic-refinement/evidence/ting02-final-regressions/index.json)
preserves **1,047 passing Python tests in 320.572 seconds**, unchanged run inputs,
378 Java engine checks, 77 portal browser checks and seven dashboard checks.
All 23 cases across archived HTTP/projector behavior, current one-shot mode and
current persistent mode matched: 69 bounded semantic observations. These cover
both metrics, correct-pool membership, behavior, Unicode/invalid drafts, public
views, revalidation and worker reuse. Providers were disabled or mocked. An
isolated private IIS package includes the complete catalogue and runtime
dependencies. These are local Linux checks, not native IIS/Cloudflare validation
or an arbitrary-history equivalence theorem. Earlier valid results and failed
test expectations remain preserved in separate archives.

Both Luna tier-one reviews for that candidate completed. Automatic screening stopped both
required GPT-6.1 Sol reviewers before final verdicts; a mathematics-only task was
also stopped. Tier three has not begun for this successor because its required
tier-two records are missing. The [workflow record](../closure/traffic-refinement/evidence/ting02-review-blocked/workflow.json)
states these limits; no reviewer verdict was fabricated. A possible
interruption-related CPython join/liveness concern was raised before screening
stopped one review. No completed source-bound counterexample or scope analysis
was received in that attempt; the following refinement subsequently reproduced it.

The [final gate attempt](../closure/traffic-refinement/evidence/ting02-review-blocked/final-gate-report.json)
rejects the missing Sol-A record before certificate assembly. Accordingly,
**TRF-01 remains OPEN and `v0.0.4.f1-alpha` is not published**. The production
repairs, contracts and tests remain in the workspace. Completing the review
workflow and resolving any constructed findings must precede a fresh final gate
and release. The original registry is unchanged: one complete original obligation
is VERIFIED at its historical root and 22 remain OPEN.

## TRF-01 closed: observation-history refinement

A local CPython status-lock interruption reproduced the earlier concern: one
failed observation retained a slot, but a later probe incorrectly reclaimed it
and admitted two live handlers under a limit of one. The v5 contract was frozen
before its production repair. The reaper now marks an observation before calling
`join` or `is_alive`, clears the mark only after both return normally, and never
probes an uncertain entry again. Current-thread entries are skipped first.
Exceptional uncertainty retains bounded capacity until restart.

The [preserved native witness](../closure/traffic-refinement/evidence/ting02-observation-interruption/README.md)
keeps one reservation and one live handler after repair. Its archive includes the
unmodified failing source and witness, the repaired source, both results, the
specification-first freeze and an isolated replay with integrity negative controls.
The Lean refinement derives status reliability from the same reachable event
history as ownership; it cannot assume that an apparently successful retry repairs
corrupted metadata. `reachable_reclamation_dead` proves that an owner actually
removed after observation had reached the dead lifecycle state. Additional
theorems cover uncertain-owner reservation, bounded retained capacity and
current-thread skipping. The gate rejects omission of these required theorems.

The [final registered report](../closure/traffic-refinement/evidence/ting02-20261004T192303Z-906f9fab/report.json)
records **VERIFIED for the original complete TRF-01**, with no blockers or
infrastructure failures. Closure ID: `ting02-20261004T192303Z-906f9fab`.
Input root: `22226ba37c2ab3f66eb5587a4b96b5908eeba9ede1801b50e65fe65bfd37ccfe`.
The [immutable archive](../closure/traffic-refinement/evidence/ting02-20261004T192303Z-906f9fab/archive.json)
binds the exact report, all 524 final inputs, raw audits, toolchain inventory,
correspondences, test logs, negative controls and six completed review records.
The final gate checked two identical clean offline Lean 4.34.1 builds,
**356 theorem declarations with empty transitive axiom sets**, **52 mapping roles**,
**103 registered checks**, and all six claim pass conditions. Both GPT-6 Luna,
both GPT-6.1 Sol and both GPT-6 Astra reviews completed on this same block, in
the required order. Each later tier binds its predecessors by hash. No reviewer
found a constructed breach in the final candidate; the registered gate decides
closure independently of those advisory verdicts.

The [new regression archive](../closure/traffic-refinement/evidence/ting02-v5-regressions/README.md)
preserves **1,053 passing Python tests**, unchanged run inputs, 69 functional
observations, 77 portal and seven dashboard checks, and the 378-check engine
report bound to identical binaries. The private IIS package was assembled and
validated with all 181 exercises, 7,731 correct candidates and its dependencies.
Browser, functional and engine input hashes match the final Python snapshot.
All four archived input sets were reconstructed and verified. Earlier passing
and failed evidence remains unchanged.

This is finite closure for the frozen source under the explicit Lean, translation,
CPython primitive/lock/thread, sampled clock, host and SHA-256 trust boundary.
Observation-call failures are modeled; arbitrary interruption of surrounding
bookkeeping bytecodes, whole-process resource accounting, native IIS/Cloudflare
behavior and unlisted obligations are excluded. Providers were disabled or mocked.
The successor ledger now records **two complete original obligations VERIFIED
and 21 OPEN**; overall traffic closure remains `NOT_ESTABLISHED`. The original
obligation registry and historical reports are unchanged. The earlier review
blocker is superseded by this new result, not erased or promoted retroactively.

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

## Next obligation and remaining boundaries

TRF-01 (strict inbound admission before expensive allocation) is in progress.
Its sampled read-deadline child is verified; strict decoder and admission
composition remain next in the preserved dependency order. TRF-01 through TRF-22 retain their original statements,
pass conditions and dependencies and remain OPEN. Their dependency on TRF-00 is
now fulfilled for the selected frozen profile; their own pass conditions still
need proof, correspondence and the registered evidence.

Finite differential tests remain TESTED evidence; they do not discharge universal
worker-history or host-language refinement. Prepared-reference caching remains
deferred. No existing release or historical proof record is relabeled by this work.
