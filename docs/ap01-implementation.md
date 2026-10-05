# AP01 implementation report

This report maps each production bridge of the
[AP01 patch contracts](claude-feedback-patch-contracts.md) to the code that
implements it, the tests that exercise it, and what remains open. The contracts'
Lean models were verified separately (AP01's recorded VERIFIED run). **No bridge
below is claimed VERIFIED.** The code is not refined against the Lean models,
and deployment checks on a Windows/IIS host have not been performed. The
machine-readable ledger is
[`implementation-status.json`](../closure/patch-contracts/implementation-status.json).

## Summary

| Bridge | What changed | Main tests |
| --- | --- | --- |
| B01 work budget | One request-global fuel budget per comparison, charged before work in normalization, binder search, tree/assignment cells, trace replay and rendering, plus retained-allocation bounds | `WorkBudgetSelfTest`, `test_work_budget.py` |
| B02 complete pool | Exhaustion publishes no partial winner; completed comparisons are byte-identical to the unbudgeted engine | catalogue equivalence (below), boundary sweeps |
| B03 worker recovery | Per-lane epoch launch tokens and a separate startup-failure circuit replace the shared 12-starts window | `test_worker_lane_policy.py`, `test_engine_workers.py` |
| B04 proxy identity | Strict `X-Forwarded-For` parsing; exact trusted proxies, none by default | `test_traffic_identity.py`, `test_ap01_http.py` |
| B05 channel quota | Channels are issued against the resolved identity, not the TCP peer | `test_ap01_http.py` |
| B06 browser fallback | At most one channel attempt per check; each channel-less analysis request is sent once on explicit capacity refusal or an unreachable endpoint | `traffic-browser.mjs` |
| B07 freshness | Historical closures are classified CURRENT or STALE against their approved inventories; CI reports it | `test_source_freshness.py` |
| B08 ingress boundary | Business routes receive a frozen request record and named service capabilities; a separate registry pins reviewed dispatcher structure | `test_ingress_boundary_bridge.py` |
| B09 evidence | Append-only evidence helper with exclusive creation and sealed manifests | `test_evidence_store.py` |
| B10 diagnostics | Bounded aggregate snapshot on the private control listener only | `test_ap01_http.py` |
| B11 administration | Default-deny network admission before any sign-in state, at the backend and the IIS edge | `test_ap01_http.py`, `test_iis_package.py` |

## B01–B02: analysis work budget

[`WorkBudget`](../vendor/acgn/src/is/fivefivefive/CanDis/WorkBudget.java) holds one
fuel counter per comparison. `charge(n)` runs before the work it pays for; a
charge larger than the remaining fuel performs no step and exhausts the budget.
Exhaustion is sticky, so code that swallows the exception (the locators, Alloy's
visitor, reflective calls) still cannot publish a result. `allocate(n)` also
counts retained nodes and memo entries against a per-request allocation limit.
`requireRetained` bounds the slot-permutation closure, whose number of
permutations can grow as n! for n interchangeable quantifiers. These allocation
counters cover selected retained nodes and entries, not all Java allocations,
heap use or process RSS. Outside a budgeted comparison every call is a no-op, so
uploads and self-tests behave as before.

`LiveFeedback.evaluate` begins the budget before the learner is parsed and ends
it after the response is built, for both metrics. Exhaustion, or a response
over 1 MiB of UTF-8 bytes, returns `status: "unsupported"` with diagnostic code `WORK_LIMIT`
and no distance, hints, canonical form or comparison fields. The persistent
worker returns that as a normal result, so the JVM is not retired.

The vendored edits are recorded as the `request-work-budget` local patch in
[`vendor/acgn/snapshot.json`](../vendor/acgn/snapshot.json), with base hashes for
modified files and an `added` marker for `WorkBudget.java`.

### Calibration

[`scripts/calibrate_work_budget.py`](../scripts/calibrate_work_budget.py) covers
every exercise's starter, oracle, benchmark draft and up to ten longest
known-correct answers in both metrics (4,274 requests), then runs adversarial
families at the production budget. Fresh catalogue measurements use the
production budget. Completed measurements may be reused only when the newly
built engine/driver classes and regenerated request bytes match and every
recorded work/allocation counter fits the current limits. The retained
[calibration summary](../closure/patch-contracts/evidence/work-calibration-20261005T090137Z-f76c415f/summary.json)
and [reuse-guard recheck](../closure/patch-contracts/evidence/work-calibration-recheck-20261005T090424Z-d9b056ca/report.json)
bind this cohort to its engine inputs. The earlier unbound calibration summary
remains historical evidence and is not used to establish response preservation.
Recorded measurements on this host are:

| Metric | Requests | Max units | p99 units | Max retained entries | Max time |
| --- | ---: | ---: | ---: | ---: | ---: |
| Canonical | 2,137 | 14,215,229 | 1,742,185 | 94,778 | 0.532 s |
| Raw AST | 2,137 | 14,507,346 | 8,438,920 | 0 | 0.457 s |

`WORK_BUDGET = 250,000,000` units gives 17.59× canonical and 17.23× AST headroom
over the largest recorded request. `ALLOCATION_LIMIT = 1,000,000` gives 10.55×
headroom over the largest counted allocation total. All 4,274 catalogue requests
completed successfully. A zero allocation counter for AST does not imply zero
heap allocation.

The adversarial set contains 14 metric-specific rows: seven `WORK_LIMIT`
results, three successful comparisons, two `AST_UNAVAILABLE` results, and two
`SYNTAX_ERROR` results. The syntax errors are rejected input, not demonstrations
of budget exhaustion. The maximum recorded time among valid adversarial drafts
is 2.307 s. These runs use a 256 MiB heap and two active processors; they do not
establish a wall-clock, process-memory or liveness bound on another host. The
earlier synthetic twelve-timeout accounting witness is separate from these
measurements and is not a demonstrated twelve-timeout production schedule.

### Complete-pool preservation

On the same 4,274 requests, the instrumented engine and an engine built from
commit `521019eb898a6f203083ad629f1aef5ad513e331` produced **4,274 byte-identical
full public responses**, checked by SHA-256 of complete UTF-8 JSON responses:
distance, edit operations, locator positions, redaction and public comparison
metadata. This compares observable responses, not every private intermediate
value. The
`WorkBudgetSelfTest` checks budget−1, budget and budget+1 around each fixture's
exact requirement in both metrics. It also sweeps every short budget over the
`[9, 0]` case from `Work.lean`, where a later candidate is the exact match, and
requires `WORK_LIMIT` at every one, never the first candidate's distance.

## B03: worker recovery

[`LanePolicy`](../engine_workers.py) transcribes `Work.laneStep`. Each lane has
its own capacity, launch tokens per 60-second epoch (12 feedback, 6 behavior)
and a startup-failure circuit (3 failed starts/readiness handshakes). Every
launch, including prewarm and replacement, spends a token. Only a failed startup
or readiness handshake advances the circuit; request timeouts and planned retirement move a worker to unreaped
without touching it. Starting, live and unreaped workers all hold capacity until
`stop()` confirms the reap. Startup failure and transport-thread races retain
ownership until cleanup completes; a stale failed stop cannot resurrect an
already reaped worker. Shutdown waits for in-flight constructors within its
deadline, and reports incomplete drain when ownership remains. Renewal happens
only when monotonic time reaches the
epoch boundary, and reading diagnostics never renews. Charging every start,
rather than only failures, is deliberate: `Work.unlimited_retry_churn` shows a
failure-only limiter admits endless start/timeout/reap cycles. With the work
budget, measured costly drafts can end in `WORK_LIMIT` as a normal response;
that outcome does not retire the JVM or consume a replacement launch token.
The separate request deadline still applies to parsing, external code and work
not covered by the charge sites.

This lane policy applies to persistent feedback and behavior workers. Explicit
`--engine-mode oneshot` and administrator one-shot JVM operations retain the
shared process-capacity bound but do not use these persistent lane launch tokens.
There is no automatic fallback from the persistent pool to one-shot execution.

## B04–B06: identity, channels and browser fallback

[`traffic_identity.py`](../traffic_identity.py) accepts only canonical IP
spellings in trusted-proxy configuration and forwarded headers: no ports, zones,
brackets or IPv4-mapped aliases. Kernel-provided mapped socket addresses are
normalized to IPv4; socket zone identifiers are rejected. A forwarded header
is honoured only from an exact configured proxy (`--trusted-proxy`, default
none). It must be one field of at most 4096 bytes and 32 hops, scanned from
the nearest hop, skipping only listed proxies. Bad or absent metadata from a
trusted proxy yields no identity, and `/api/channel` answers 400
`identity_rejected` rather than falling back to the proxy address. HTTP
connection admission still counts the TCP peer before headers are read.

`portal_routes.public_post` issues channels against that identity, so the
existing 32-per-identity and 512-global bounds apply per client behind a
configured proxy. Without a trusted proxy, IIS users still share the loopback
identity.

In [`web/app.js`](../web/app.js), a check makes at most one channel attempt,
reusing an existing channel when available. An explicit
capacity refusal (`429`/`503` with `busy`/`capacity`) or an unreachable endpoint
permits channel-less requests for that check. They omit the `channel` key
(never `null`), are sent once without retry, and never trigger `/api/cancel`.
Authentication failures, malformed responses and other errors never fall back.
Channel redirects are not followed and cannot be mistaken for capacity errors;
a successful response must contain an exact 43-character channel token matching
the backend format.
Every response keeps the existing revision, selection, body, metric, exercise
and abort guards. A `WORK_LIMIT` result does not trigger a behavioral request.

## B07: current-source freshness

[`scripts/source_freshness.py`](../scripts/source_freshness.py) transcribes
`Governance.currentVerified`. [`freshness-registry.json`](../closure/freshness-registry.json)
pins only hashes of historical evidence: each report, its approved inventory,
input root and verifier. Each record's integrity is checked first, including its
ledger entry; a broken record is INVALID and fails the CI step. The current
checkout is CURRENT only if it matches the approved inventory exactly, with no
missing, changed or extra entries (including recursively nested unregistered
`.lean` files in registered proof directories). Linked paths and noncanonical
path aliases fail closed. Otherwise source drift is STALE, and the historical
result is reported unchanged. The README table is regenerated from this check,
and a test keeps them equal.

TRF-00, TRF-01 and AP01 are all STALE for this checkout. TRF-00 was already
stale at the `v0.0.4.f1-alpha` tag: ten of its inputs, including `server.py` and
`traffic_http.py`, had changed since its recorded root.

## B08: ingress and business separation

`server.Handler` keeps every ingress method AST-identical to the frozen TRF-01
template: request line, headers, deadline reader, bounded JSON body, reply
serialization. Its dispatchers now validate, resolve identity, apply
administration admission, and then dispatch through named callbacks in
[`portal_routes.py`](../portal_routes.py) with a frozen `ValidatedRequest` record
and frozen `RouteServices` containing named business capabilities. The request's
allowlisted header projection is immutable; its validated JSON body remains
ordinary business data. Neither interface directly exposes the listener,
socket, reader or admission owner. Business functions return a `Reply` that the
handler serializes under its existing bounds. Bound service operations remain
trusted Python code; this interface is not an introspection-proof sandbox.

[`scripts/ingress_boundary_bridge.py`](../scripts/ingress_boundary_bridge.py)
checks this boundary:
- the Handler method set and inheritance/decorator form are pinned, rejecting
  new overrides or inherited dispatch paths outside the reviewed boundary
- the ingress methods equal the TRF-01 template
- a new [boundary registry](../closure/patch-contracts/ingress-boundary.json)
  pins the reviewed ASTs of seven boundary methods and the service class/builder;
  inverted admission guards and validation hidden in dead branches are rejected
- callbacks receive named services, and the registered business-source subset
  rejects known ingress access and dynamic introspection
- channels use the resolved identity

The historical TRF-01 bridges now replay against the recorded evidence inputs,
following the existing pattern of `test_service_initial_bridge.py`; no
historical template or contract was regenerated.

## B09–B11: evidence, diagnostics and administration

[`scripts/evidence_store.py`](../scripts/evidence_store.py) creates evidence
directories and files exclusively, refuses overwrites, linked paths and
nonportable aliases, and seals only a complete matching inventory. Further
appends through the helper are rejected after sealing. Manifest checks detect
later changes or added files. Independent filesystem writers, cross-process
races and crash durability remain outside this helper's guarantee. All prior
evidence stays in the repository.

`GET /api/diagnostics` exists only on the private control listener
(`--control-port`). It reports per-lane ready, busy, starting, unreaped, launch
credits, circuit state and retry delay, plus active and retained handler counts
and a catalogue epoch. All values are range-checked numbers or status words, and
there is no field for learner, oracle, peer or credential data. The service
status follows `Observability.serviceStatus`: stopping, then degraded (retained
or unreaped ownership), then the feedback lane. Zero workers with launch credit
reads as starting, not unavailable. The engine and handler counters are two
separately locked samples and are labelled as non-atomic. Public `/api/health`
is unchanged.

Administration is denied unless the resolved client identity is in
`--admin-network` (CIDR, default none). The check runs before bootstrap,
prelogin cookies, CSRF tokens or failed-login counting, and covers the static
`/admin` UI. AuthManager also receives the resolved identity: an external
forwarded client cannot use the HTTP-loopback exception merely because its
reverse proxy connects from `127.0.0.1`. Direct loopback HTTP remains supported;
configured HTTPS administration retains its origin, cookie and CSRF checks.

The IIS `web.config` denies `/admin` and `/api/admin` until the operator adds
allowed edge addresses. Admitted admin UI requests are proxied to the backend
for its independent network check. The package includes backend copies of all
three admin assets, byte-identical to the versioned public assets.
`Start-AlloyStudio.ps1` validates the public configuration against the unchanged
private template, permitting only canonical exact `REMOTE_ADDR` exceptions in
the admin deny rule. It rejects arbitrary rewrite changes and forwarded-header
allow conditions. Behind Cloudflare the permitted IIS edge addresses and
backend operator networks refer to different hops; both require configuration.

`run_backend.py` and `Manage-AlloyStudio.ps1` validate and pass `trusted_proxies`
and `admin_networks` from protected task configuration. Installation now exposes
`-ControlPort` and `-EngineMode`; `-Action Status` reads private diagnostics when
configured. These Python/XML behaviors and package contents are tested on Linux;
the PowerShell scripts, ARR forwarding and IIS enforcement have not been run on
a Windows host. See the
[IIS guide](../deploy/iis/README.md#administration-network-admission-default-deny).

## Behaviour changes

- Drafts exhausting the instrumented work/allocation budget receive "This
  predicate needs more analysis work than live feedback allows" without retiring
  the worker; request timeouts remain possible outside that measured boundary.
- Administration returns 404 until `--admin-network` (and, behind IIS, the edge
  rule) allows the operator's address.
- When editing channels run out, checks continue without server-side
  supersession instead of failing.
- Proxy-forwarded external clients are refused HTTP administration even when
  their network is permitted; configure the public HTTPS administrator origin.
- New backend options: `--trusted-proxy`, `--admin-network`; the control listener
  also serves `/api/diagnostics`.

## Remaining open

- No Lean refinement of the new code; bridge B08 is a structural check.
- Charge-site completeness is a manual audit. The Alloy parser and SAT4J
  remain separately bounded by input caps and deadlines, and the behavioral lane
  is bounded by its deadline, not by fuel.
- Abstract units do not prove wall-clock or memory bounds on other hosts.
- The Cloudflare→IIS→backend header chain, ARR settings and IIS enforcement of
  the administration rule must be validated on the target.
- The PowerShell launch/management scripts and actual IIS/ARR behavior were not
  executed here; Linux Python/XML tests do not substitute for Windows acceptance.

## Final regression run

The [sealed implementation regression record](../closure/patch-contracts/evidence/implementation-20261005T092516Z-20c783aa/report.json)
records a Linux run on 2026-10-05: **1,178 Python tests**, **378 engine checks**,
**85 public browser checks**, **14 administrator browser checks**, and
**seven dashboard checks** passed. No Python test was skipped or left unresolved.
The selected 760 implementation, test and runtime inputs were unchanged across
the run. This record is a test result, not a formal closure certificate.

The first combined Python run found nine failing socket-test cases whose small
fixtures did not supply the new named route capabilities. Those fixtures now
provide explicit services and reject unexpected callbacks; the real sockets,
deadline probes, cache-publication races and original assertions are preserved.
The failed run is retained, and the complete suite was rerun successfully.

A finite HTTP comparison passed 69 observations (23 cases in archived one-shot,
current one-shot and current persistent modes). It uses the same current Java
classes in all arms, with the synthetic loopback administrator network explicitly
permitted in current arms. The new default-deny policy is checked separately;
it intentionally differs from the historical default. The original comparison
that exposed those six policy differences is retained alongside the corrected
comparison, without ignoring any mismatched rows. Repeated persistent feedback
added zero launches and zero computations.

The real worker tests also performed 100 alternating Canonical/AST edits with
zero additional launches after prewarming. The default metric remains Canonical.
These finite tests and calibration do not close any production Lean refinement
obligation.


A fresh private IIS distribution was built as
`build/iis/alloy-studio-iis-20261005-092311-050172Z.zip`
(SHA-256 `e9ca841a019a825a47876d6039819950da5a2ac66c74890548ecf3892ea7dddd`).
Its 181 compiled engine classes exactly match the calibrated class inventory.
The archive includes the current backend and matching versioned admin assets;
it is an administrator artifact, not a public-download asset. The Linux build
and package checks do not establish execution on Windows/IIS.
