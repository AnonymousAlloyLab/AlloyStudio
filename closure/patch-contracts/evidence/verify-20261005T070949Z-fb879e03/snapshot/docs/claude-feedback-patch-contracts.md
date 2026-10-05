# Claude feedback: proposed patch contracts

This specification addresses the supplied review of `521019eb898a6f203083ad629f1aef5ad513e331`
(`v0.0.4.f1-alpha`). **AP01 proves properties of proposed contracts, not that these
patches have been implemented.** Production Python, Java, JavaScript and IIS
configuration remain unchanged. Existing TRF-00/TRF-01 reports remain historical
results at their recorded roots. AP01 does not close the remaining production
traffic obligations or authorize a new release.

All existing evidence stays in the repository. The suggestion to move it out of
git is explicitly excluded. New evidence also stays in the repository; this proof
block needs no additional copies of the corpus or JARs.

The authoritative finite claims and open implementation bridges are in
[`spec.json`](../closure/patch-contracts/spec.json). The four proposed models are
[`Work.lean`](../formal/patch_contracts/Work.lean),
[`Identity.lean`](../formal/patch_contracts/Identity.lean),
[`Governance.lean`](../formal/patch_contracts/Governance.lean) and
[`Observability.lean`](../formal/patch_contracts/Observability.lean).
The supplied [review](../closure/patch-contracts/source-feedback.txt) is evidence
to assess, not implementation instructions. The independent
[source assessment](../closure/patch-contracts/source-assessment.json) records
source locations and qualifications. Its original scratch results are preserved
under `build/claude-contracts-20261005/`.

## Evidence assessed

Five bounded witness groups execute current production Python methods with
synthetic clocks, inert workers/threads and synthetic admin configuration. They
prohibit process creation and sockets. These checks do not start JVMs, query a
provider, use credentials, or replay an expensive overload on the host.

1. Twelve **instantaneous synthetic** request timeouts consume the shared
   twelve-start bucket; subsequent feedback and behavior requests fail until its
   60-second expiry. This confirms accounting, not the reported live workload.
   With default 12-second requests and two feedback workers, the attachment does
   not supply enough timing evidence to establish twelve full-duration timeouts
   in one rolling minute. Canonical input already has byte, pool, storage and
   heap limits; the gap is cumulative internal work accounting. Raw AST's
   per-pair limits likewise do not establish a request-global bound.
2. Thirty-two same-peer editing channels succeed; the next fails until the
   900-second idle expiry. A distinct peer is admitted below the global limit.
   Allocation is lazy on feedback, not every mere page view. A channel-less
   request must omit `channel`; `channel: null` is rejected.
3. A scratch change to `engine_workers.py` changes its frozen hash while the
   narrow strict-ingress bridge still passes. This does not demonstrate that the
   entire CI suite accepts arbitrary changes. The full registered closure gate
   already rehashes its inventory; existing documentation qualifies VERIFIED as
   historical. The new contract concerns automatic **current-source freshness**.
4. Extracted production public/control health branches give the same `200 ok`
   with zero or two mocked workers. The real reaper retains a synthetic uncertain
   thread's slot. Zero idle workers alone is not an outage: lazy startup can be
   possible. Access logging is suppressed, but a constant handler-error stderr
   message exists; the claim that there are no operator signals needs qualification.
5. With administration enabled, synthetic HTTPS bootstrap/CSRF/authorization
   succeeds; five wrong passwords from one source block another source's valid
   new login until time 60. Actual deployment exposure was not inspected.
   Disabled administration or an independent access boundary changes reachability;
   already authenticated sessions are not revoked by this throttle.

Reproduce these finite checks without live load:

```sh
python scripts/claude_feedback_witnesses.py --output-root build/claude-witness-new
```

The output directory must be new. The registered proof verifier runs the same
checks in an offline namespace, against hashed inputs. Results must be described
as **TESTED with named substitutes**, not production availability proofs.

## AP01-C01: cumulative work budget

Every accepted comparison receives one natural-number fuel budget for the whole
request. Charge before each atomic operation; zero fuel performs no next step.
All executed prefixes, including exhaustion, have length at most initial fuel.
The budget spans learner setup, every reference and final hint construction.
Batching or switching candidates must not reset it. Exhaustion/oversize produces
an explicit unsupported result with no partial distance or hint payload.

`Work.all_execution_paths_bounded`, `zero_cannot_act`, `aggregate_exhaustion` and
`exhausted_no_partial_output` prove the abstract contract. The work list is a
finite schedule supplied to the model. Defining a Java parser call or an entire
Hungarian solve as one operation would not establish the intended cost bound.

**Patch bridge B01:** instrument parsing, normalization, expansion, memo lookup
and miss work, DP/assignment cells, replay, redaction and serialization. Bound
allocation before matrices/trees are materialized. Cover all native calls or
retain separately bounded/time-limited boundaries. Reject unsupported input
before uncharged expensive work. Choose finite production thresholds with
catalogue-wide measurements; they are not established by a natural-number proof.

Acceptance must include repeated conjunctions, deep trees, expensive assignments,
large pools and trace construction. Record peak memory, work counters, timeout
classification and boundary cases at budget−1/budget/budget+1 in owned scratch
folders. Abstract fuel does not prove a wall-clock or RSS bound.

## AP01-C02: preserve complete-pool results

Success requires complete traversal. On success the bounded fold equals the
unbounded fold; minimum distance is unchanged. Unsupported work publishes no
partial winner. The explicit `[9, 0]` counterexample rejects stopping after a
first candidate at distance 9. Oracle and every accepted correct candidate remain
in the comparison pool; canonical remains the default and AST stays available.

`Work.complete_exact`, `accepted_traversal_complete`, `bounded_minimum_exact` and
`accepted_payload_preserved` prove these model facts.

**Patch bridge B02:** establish that the schedule contains every required phase
and pool member; preserve actual distance semantics, stable tie-breaking,
winning-reference identity, raw/canonical positions, redaction, and metric
selection. The minimum fold alone does not prove argmin identity or tie-breaking.
Its initial value must be an actual candidate or a justified upper sentinel.
For all completed baseline cases compare full deterministic public output;
unsupported must be distinguished from wrong/zero/partial results. Do not change
learner guidance or expose hidden target expressions to meet a work budget.

## AP01-C03: worker recovery without unbounded churn

Use separate lane-local policies: an all-start token budget and a startup-failure
circuit. Every launch attempt, including prewarm/replacement, consumes a token.
Only failed startup increases the failure circuit. Request timeout and planned
retirement do not. Starting, live and unreaped workers all reserve capacity;
only confirmed reap frees it. Feedback cannot consume behavior's modeled budget.

`Work.reachable_capacity_and_spawn_bound`, `both_lanes_remain_bounded`,
`reachable_attempts_bounded`, `retirement_retains_capacity_reservation` and
`bounded_modeled_recovery` establish these properties. Renewal needs modeled
elapsed time and a positive configured period. With free capacity and positive
quotas, tick/renew/start constructs a permitted retry; it does not prove OS
startup, fair scheduling, a timely reap or successful user requests.

The proposed model uses renewable **epochs**, not the current rolling window.
Its bound is per epoch, not an assertion of twelve starts in every rolling minute;
adjacent epoch boundaries can permit two bursts. Preserve a rolling policy with
a separate proof if that exact production envelope is required. Numeric quotas
and lane allocations require explicit resource calibration, including the shared
process budget and other JVM consumers.

**Patch bridge B03:** classify actual handshake failures and retirements, meter
all spawn paths, protect transitions with locks, preserve global capacity and
reap ownership, and drive renewal from monotonic time. Regressions must construct
failed startup, timeout, normal retirement, unreaped child, mixed lanes, expired
window and launch-burst traces. A limiter counting only failed startups admits
arbitrarily many successful-start/timeout/reap cycles: `unlimited_retry_churn`
provides the constructed counterexample.

## AP01-C04: proxy identity with an explicit trust boundary

Default trusted proxies are empty. An untrusted socket peer ignores all forwarded
metadata. A configured trusted immediate proxy requires strict, bounded decoding:
at most one field, 4096 encoded bytes and 32 valid nonempty hops. Reject malformed
or ambiguous trusted metadata. Scan from nearest hop toward the client, removing
configured trusted hops; the nearest untrusted address wins. An attacker-controlled
left prefix cannot alter a previously selected nearest untrusted hop.

`Identity.unknown_proxy_header_noninfluence`, `accepted_chain_bounded` and
`spoofed_left_prefix_noninfluence` prove these facts over canonical IP atoms.
Blind leftmost-XFF selection has an explicit spoofing counterexample.

**Patch bridge B04:** implement the strict wire-to-IP parser, canonicalize/reject
aliases consistently, configure exact trusted addresses and the actual
Cloudflare→IIS→backend chain, and verify how each proxy sanitizes/appends headers.
Header presence never grants trust. The model does not implement string IP parsing
or CIDR policy. IP addresses are quota identities, not people or authentication.

HTTP admission reserves slots by the **physical TCP peer before parsing headers**.
Application identity must not be retroactively applied to that earlier boundary.
A trusted proxy needs a separately bounded aggregate transport profile; check
its deployment/resource limits independently. Fixing channel identity alone
cannot remove transport-level aggregation.

## AP01-C05: channel quota preservation

Reachable states admit at most 32 channels per identity and 512 globally. A full
identity bucket does not block a distinct identity below the global bound; the
33rd same-identity channel is rejected. NAT sharing still collides, and a full
global bucket rejects even a fresh identity.

`Identity.reachable_channel_bounds`, `honest_33rd_tab_refused` and
`full_peer_quota_does_not_block_distinct_identity` prove these allocation facts.

**Patch bridge B05:** map validated application identity to the scheduler under
its lock. Retain idle expiry, disconnect ownership, tokens and aggregate bounds;
allocation-only Lean traces do not prove the expiry implementation. Test IIS
aggregation, 32/33 and 512/513 boundaries, multiple identities, NAT and renewal.

## AP01-C06: bounded browser fallback

One operation makes at most one channel attempt and one fallback request.
Fallback is permitted only for explicitly classified channel availability errors,
never abort, authentication failure or malformed protocol. It **omits** the
channel field and sends no channel cancellation. All modes retain the complete
captured/current revision, selection, raw body, metric, exercise and abort checks.
A stale response cannot change the visible result.

`Identity.operation_channel_attempt_at_most_one`,
`operation_fallback_request_at_most_one`, `fallback_only_for_availability`,
`fallback_omits_channel_and_never_cancels` and `stale_browser_response_discarded`
prove the proposed state-machine rules. Channel-less mode has no server channel
supersession/cancellation guarantee; client freshness alone does not remove
server work.

**Patch bridge B06:** bind response/error classifications, serialization and
snapshot fields to `web/app.js`; preserve feedback/behavior/explanation echo
checks. Test out-of-order results, edit/selection/metric changes, abort races,
invalid responses and unavailable channels. No retry storm or silent auth bypass.

## AP01-C07: complete current-source freshness

Compare every entry of an independently frozen approved manifest with the current
observation. Missing, changed, extra or duplicate paths fail. Observed and approved inventories
are finite lists in the same canonical order; exact equality covers every entry.
Require a nonempty inventory and
bound report root, verifier identity and prior pass. Do not regenerate a manifest
from modified source and use it to self-attest freshness. A stale current checkout
does not rewrite a valid historical result.

`Governance.checked_entry_exact`, `missing_entry_blocks`,
`current_requires_complete_equality`, `current_requires_exact_inventory`,
`unexpected_inventory_rejected`, `duplicate_inventory_rejected`,
`current_changed_input_rejected` and
`source_drift_does_not_rewrite_history` prove this checker model.

**Patch bridge B07:** wire a complete historical-manifest check into CI/current
status, keeping source inventory distinct from the final evidence inventory.
Register the intended manifest independently, reject missing/duplicate/unexpected
entries, and bind the actual report/verifier provenance. Failures must say stale,
not erase historical VERIFIED. SHA-256, filesystem reads, inventory completeness
and the Python checker remain explicit trust/implementation boundaries.

## AP01-C08: separate ingress from business logic

A pure business callback receives only a validated request. Rejection yields no
call/reply. Acceptance produces the validation/call trace and the callback's
exact reply. Replacing a callback leaves that ingress trace unchanged.

`Governance.rejected_request_not_dispatched`, `successful_request_exactly_once`
and `business_replacement_preserves_ingress` prove this typed interface model.
They do not justify arbitrary Python callbacks capturing raw sockets or state.

**Patch bridge B08:** introduce a narrow validated-request/capability interface;
forbid callbacks from lazy body reads, re-decoding raw headers, modifying admission
state, bypassing serialization bounds or swallowing ownership obligations. Model
exceptions, concurrent mutation and route coverage. Update correspondence for the
new boundary before narrowing the frozen handler check. Keep old closure intact
as historical evidence. Business semantics still need their own tests/contracts.

## AP01-C09: retain evidence in the repository

The proposed store appends only a previously absent identifier. Conflicts reject;
all prior identifier→digest lookups remain unchanged, and the new record is
retrievable. `Governance.evidence_collision_refused`,
`evidence_append_preserves_existing` and `evidence_append_records_new` prove this
model. **No evidence relocation, deletion or history rewrite is part of AP01.**

**Patch bridge B09:** use unique in-repo evidence paths and fail on overwrite.
Keep relevant source snapshots/manifests sufficient to replay a frozen run.
Unchanged dependencies may be referenced by existing in-repo content hashes when
retrieval and completeness are checked; this block needs only its small sources,
audits, witness outputs and reports. This is not a filesystem durability proof.

## AP01-C10: useful private diagnostics

Keep public liveness semantics explicit. Only the private control listener gets
an allowlisted aggregate diagnostic snapshot: lane counts, starting/unreaped
owners, launch credits, startup-circuit state, retry delay, retained/active handler
counts and generation. Separate ready, busy, starting, unavailable, degraded and
stopping states. Zero workers with launch credit can mean starting; uncertain
ownership is visible and remains reserved.

`Observability.ready_requires_idle_worker`, `zero_workers_need_not_mean_unavailable`,
`retained_ownership_visible`, `public_diagnostics_denied`,
`diagnostics_do_not_mutate` and `diagnostics_no_private_content` prove the model.
The output has only bounded numeric/status fields; changing arbitrary private
payloads cannot change it when the aggregate snapshot is fixed. This excludes
content leakage through fields, not timing or aggregate inference.

**Patch bridge B10:** collect an atomic snapshot with documented lock ordering,
or explicitly label non-atomic samples and their epochs. Match counters to actual
capacity and uncertain reservations, reject out-of-range values, and keep the
control route off public/IIS routing. A diagnostics read must not spawn, solve,
contact Luna, reap, release slots or mutate quotas. No optimistic recovery by
releasing uncertain ownership. Sampled readiness cannot promise future liveness.

## AP01-C11: administration admission before shared login work

Apply an independently configured operator network policy to all admin routes
before bootstrap, preauth allocation or failed-login charging. Default deny.
Unallowed sources cannot change the protected attempt state over any modeled
request sequence. Edge and backend use the same explicit policy; forwarding does
not allow an untrusted peer to fabricate admission. Keep password, origin, CSRF,
session, KDF and aggregate concurrency bounds.

`Identity.arbitrary_unallowed_requests_preserve_auth_quota`,
`untrusted_nonallowlisted_peer_cannot_forge_auth_admission` and
`edge_backend_share_all_route_policy` prove these model facts. The old five-failure
lockout is constructive; allowed sources can still share that lockout. This is
not an availability guarantee against allowed or distributed sources.

**Patch bridge B11:** implement/verify IIS route restrictions and equivalent
backend policy, including direct-listener bypasses, address canonicalization and
all route spellings. Validate the deployed network path; a configuration model
does not establish actual IIS enforcement. Test denied bootstrap/login requests
leave quota state unchanged and authorized workflows still work.

## Proof gate and implementation order

Run canonical-work and worker-budget implementation together, then proxy/channel
fallback, then complete freshness and private diagnostics; isolate the ingress
interface before narrowing its correspondence checks. Admin policy is a separate
explicit deployment patch. Every bridge above stays **OPEN** until code, faithful
correspondence and its regression/deployment checks are supplied.

AP01's gate freezes source/claim/theorem/verifier hashes, compiles twice in clean
network-isolated directories, audits all project definitions and theorem
transitive dependencies for an empty axiom set, checks deterministic artifacts,
replays bounded witnesses, and validates required claim-to-theorem mappings.
There are no `sorry`, admitted proofs, added axioms or unchecked native decisions.
A theorem with explicit premises proves only the implication it states; the
production bridge must establish those premises. Lean's kernel, compiler/library
installation, registered audit/checker, Python/OS/hardware, filesystem semantics
and SHA-256 are declared trust, not hidden proof assumptions.

The first Luna review round constructed an extra-observed-path counterexample
to AP01-C07. The rejected candidate, reviews and witness are preserved at
[`rejected-c07-684e5053`](../closure/patch-contracts/evidence/rejected-c07-684e5053/).
The revised model enumerates both inventories and adds rejection/acceptance and
duplicate-path regression theorems.

The advisory review ladder is two GPT-6 Luna, two GPT-6.1 Sol, then two GPT-6 Astra
reviewers. A breach requires a constructed counterexample. Reviews cannot
substitute for the mechanical proof gate. New counterexamples remain in the repo;
changed candidates require new manifests/reviews. Historical evidence is never
silently reused as current proof.

Reproduction commands:

```sh
# Maintainer action: create a new candidate BEFORE collecting its six reviews.
python scripts/verify_patch_contracts.py --prepare
# Check the already frozen candidate and its bound reviews.
python scripts/verify_patch_contracts.py --verify
```

The machine-readable report is authoritative for the finite proposed-contract
surface. It must expose open production bridges and must not advertise the live
application as fixed or universally correct. Counts distinguish authored public
contract theorems from generated helper/equation declarations; neither declaration
counts nor exhaustive truth-table rows measure product assurance.

## Empirical and maintenance follow-up

The review's learning claim cannot be settled by Lean: hint availability, literal
edit applicability, guidance usefulness and learning gains are separate outcomes.
The reported step/wording percentages remain attributed to the supplied review
until their dataset queries are independently replayed. A learner study needs
prespecified task selection, baselines, participants, assistance conditions,
completion/transfer outcomes and uncertainty reporting. No educational benefit
is inferred from AP01 or the existing availability benchmark.

The untracked `.env.example`, README length and absent formatter/linter are
maintenance observations, not invariant proofs. This task does not read/stage
local configuration, change credentials or introduce a repository-wide formatter.
