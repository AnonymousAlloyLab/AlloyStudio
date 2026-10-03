# Persistent analysis and bounded traffic in v0.0.4-alpha

The default backend now starts reusable JVM workers and sends successive edits
through a bounded binary protocol. Identical requests share pending work or reuse
an exact-context result. This removes JVM startup from normal edits without
changing Canonical/AST ranking, correct-pool membership, behavioral scope, or the
public hint projection. Canonical remains the default.

The [specification](backend-performance-spec.md), [TB01 model proofs](../formal/traffic/README.md),
and [proof handoff](traffic-proof-handoff.md) are frozen historical inputs. Their
statements about the pre-refactor implementation describe the proof-stage baseline.
This document describes the subsequent implementation. It does not amend their
claims or promote finite tests to a semantic refinement proof.

## Runtime changes

`engine_workers.py` owns two feedback workers and one behavioral worker by default.
`runtime_dependencies.py` shares the process reservation budget with one separate
administrator validation lane. Reservations are acquired before process creation
and returned after termination is acknowledged. A failed reap retains its
reservation; shutdown reports failure instead of declaring it cleaned up.

Each worker processes one request at a time. Frames carry a protocol version,
worker incarnation, request ticket, operation kind, and request-context digest.
Lengths are checked before allocation; malformed UTF-8, duplicate JSON fields,
trailing data, stale identities, oversized frames, and fatal worker failures
retire the worker. A timeout also stops the worker and attempts to reap it;
an unacknowledged reap retains its reservation. Failed requests are not retried
in a one-shot JVM. Later requests may start replacements within the same budget.

The worker calls the same Java feedback functions as the one-shot CLI. Each job
parses fresh modules and graphs, releases the request-local graph arena without
resetting process-wide identity counters, and deletes its owned job directory.
The worker parser adapter avoids Alloy's accumulating `deleteOnExit`
registrations. Reference bodies are still scanned in their complete original
order. **Prepared/parsed reference-pool caching (R3) is deferred.** The speedup in
this release comes from process reuse, duplicate sharing and completed-result reuse.

`traffic_scheduler.py` atomically checks the result cache, joins an existing exact
job, or admits a new one. Keys retain the complete payload, including raw source,
environment, ordered references and metric, together with catalogue and service
generations. They are not digest-only cache keys. Results are immutable encoded
bytes and callers receive separate decoded copies. Catalogue publication changes
the generation; replacing engine binaries or configuration requires a restart.

Browser channels are opaque server-issued capabilities. A revision binds the
exercise, draft, metric and catalogue generation. A newer revision detaches that
channel's old subscribers and removes jobs that have not started and have no
remaining subscribers. Running jobs retain ownership of their workers until they
finish; another user's subscriber is not cancelled. The browser also checks its
current context before showing feedback, examples or guidance.

Feedback and behavioral evidence are retained separately from the evictable result
cache. Explanation tokens bind the displayed evidence to its exact context. The
new browser does not rerun analysis merely to explain expired evidence; it reports
unavailability. Identical Luna requests share one provider call, with bounded
followers and a cache keyed by the exact prompt, model, instruction text and
credential identity. Provider failures are not cached. Validation used disabled
or mocked Luna and made no paid API calls.

## Default operating envelope

These are finite application counters and configured deadlines, not a bound on
whole-process resident memory or an operating-system guarantee:

- JVM reservations: two feedback, one behavior, one administrator; maximum four
  simultaneously reserved child processes in a backend process. Each JVM has a
  256 MiB heap; persistent workers also cap direct memory at 64 MiB.
- Worker recycling: the next idle checkout retires a worker that has completed
  512 jobs, accumulated 1,000,000 full-module parses, or reached 1,800 seconds.
  An in-progress job may cross the parse/age threshold before retirement.
  Each job limits cumulative parser-source writes to 64 MiB; this does not bound
  every possible JVM scratch file. Protocol request/response bounds are 1/4 MiB.
  Startup has a 10-second deadline; each pool permits at most 12 launch attempts
  in a rolling minute, including initial prewarming and failed starts.
- Scheduler: 32 jobs, 64 subscribers, 16 MiB of encoded retained key bytes,
  a five-second maximum age at dispatch. A queued job can remain accounted for
  beyond that age while its lane is occupied, but will be rejected when the
  dispatcher reaches it; subscribers also have an overall wait deadline.
  Feedback defaults to a 12-second evaluation budget;
  behavior uses at least 30 seconds. A detached running computation remains
  accounted for.
- Result caches: feedback 128 entries/16 MiB; behavior 32 entries/16 MiB;
  120-second TTL. Evidence: 128 entries/32 MiB, also 120 seconds. A full evidence
  store refuses a new pin instead of silently evicting an unexpired pin.
- Channels: 512 total, 32 per direct peer, 900-second idle expiry. Luna permits
  two provider leaders and 32 followers; completed guidance is limited to
  128 entries/8 MiB with a 120-second TTL.
- Public HTTP: 30 admitted handler threads, backlog 32, burst 60 and replenishment
  30 connections/second, including a bounded 1,024-entry direct-peer registry.
  Reservations precede handler-thread creation.
- Optional control listener: loopback only, two separately reserved handlers,
  backlog two, burst four and two connections/second. It serves health only and
  cannot borrow public slots. It is disabled until `--control-port` is supplied.
- HTTP request lines: 8 KiB; headers: 32 KiB/64 fields; JSON nesting: 32 levels;
  public POST bodies: 16 KiB, including an 8 KiB predicate body. Header, body and
  response-write deadlines are five seconds each. Existing larger authenticated
  upload limits remain a separate checked path.

The server rejects ambiguous framing, unsupported transfer encodings, duplicate
JSON keys and invalid Unicode before dispatch. It closes each HTTP connection
after its response. Quotas use the socket peer, not caller-supplied forwarding
headers. Behind IIS, learners therefore share the IIS peer's aggregate quota;
the implementation does not pretend that `X-Forwarded-For` is authenticated.

Public catalogue/detail responses support exact-content ETags and conditional
revalidation, as do static files served by the Python backend. API mutations and
administrator responses remain private/non-cacheable. IIS-served static assets
retain their existing no-store policy and hash-versioned asset references.
Cloudflare/IIS configuration and OS socket/process behavior remain deployment
dependencies. The private control port must not be exposed or proxied publicly.

## Tests and reproducibility

Local feedback qualification passed **1,086/1,086 exact comparisons**: 181
exercises × three draft variants × two metrics. There were 724 successful
observations and 362 matching invalid-input diagnostics; every selected correct
draft had zero distance in both metrics. Canonical evaluation time summed to
177.722 seconds fresh versus 31.517 seconds warm; AST summed to 117.701 versus
20.853 seconds. These are local qualification timings with other validation work
running, not an isolated production-load benchmark. Three worker launches served
the sequence, including two scheduled recyclings, and final reservations were zero.

A separate simple-predicate microbenchmark compared 100 distinct whitespace edits
(50 Canonical, 50 AST) without result caching. All responses matched; warm calls
took 0.610 seconds total versus 12.600 seconds fresh, with no launches beyond the
three prewarmed workers. This small fixture does not predict catalogue-wide or
multi-user latency.

Local gates passed 378 engine checks, 796 Python tests, 77 browser scenarios,
seven dashboard checks and 14 administrator browser checks. Three subsequently
added shutdown/constructor cases passed in the seven-case portal integration
rerun. The tagged CI run supplies the final clean-checkout test count and native
Windows/macOS results. The
[public release evidence](https://github.com/AnonymousAlloyLab/AlloyStudio/releases/tag/v0.0.4-alpha)
includes aggregate feedback/behavior reports, numeric reuse witnesses and the
TB01 proof report. Local working-tree reports retain that provenance; they are
not relabeled as clean-commit CI runs.

The release evidence records the final catalogue differential, Python and browser
results and the source/toolchain identities used. The differential compares the
complete public observations; it does not normalize away differing operators,
locations, distances, diagnostics, instance atoms, categories or scores.

`scripts/check_worker_equivalence.py --include-invalid` checks starter, known
correct and malformed drafts for both metrics across all 181 exercises. The
correct drafts must have zero distance in each metric. Its fresh and warm paths
call the actual production projection methods. `scripts/check_behavior_worker_equivalence.py`
separately compares behavioral results across the catalogue. Reports distinguish
successful examples from matching invalid/unsupported outcomes.

The real browser witness in `tests/persistent-browser.mjs` submits draft A, draft
B, then draft A again through the production server. It asserts three startup
JVM launches, four actual feedback/behavior computations, two cache hits, retained
evidence for disabled Luna, and zero children/reservations after SIGTERM. Additional
tests cover shared subscribers, cancellation isolation, queue saturation, stale
responses, token expiry, parser/framing rejection, HTTP deadlines, control-lane
saturation, worker recycling, shutdown, and secret/error projection.

Use an owned temporary directory for local checks:

```bash
mkdir -p build/traffic-implementation/tmp
export TMPDIR="$PWD/build/traffic-implementation/tmp"
export OPENAI_DISABLED=1
python3 -m unittest discover -s tests -v
python3 scripts/ci_check.py browser
python3 scripts/check_worker_equivalence.py --include-invalid
python3 scripts/check_behavior_worker_equivalence.py
python3 scripts/verify_traffic_proofs.py
```

Full-corpus benchmark outputs and previous successful test evidence are preserved.
This release does not rerun the 61,598-model TAR/FM24 comparison or claim that
reduced runtime changes hint quality. Native Windows/macOS CI supplements local
Linux tests; it is not an actual IIS deployment acceptance test.

## Proof-to-code status

TB01 contains 560 constructive Lean theorem declarations, checked in two isolated
offline builds with no admitted proofs or introduced axioms. The frozen verifier
also checks 22 concrete witnesses and six source-bound adversarial review records.
Its result is **VERIFIED for the traffic model only** under its declared TCB.

The implementation follows the model's ownership and identity design, but this is
traceability and finite testing, not a theorem about Python/Java/JavaScript:

- TRF-00–03: profile and ingress/resource limits are in `traffic_http.py`,
  `engine_workers.py` and `traffic_scheduler.py`; HTTP/worker/scheduler tests cover
  their finite boundary cases. Whole-process RSS containment remains outside this
  implementation.
- TRF-04–06 and TRF-08–09: scheduler admission, exact identities, joining,
  cancellation and cache behavior have constructed concurrency regressions.
- TRF-07 and TRF-11: Java/Python framing, fresh parser state, recycling and fatal
  retirement have transport tests and sequential fresh/warm differential checks.
- TRF-10: optional parsed-pool reuse is deferred; no prepared graph is shared.
- TRF-12–15: behavioral observations, frontend delivery context, evidence pins and
  the public projection have catalogue, browser, education and disclosure tests.
- TRF-16: bounded restart/retry and graceful shutdown have runtime/browser tests;
  uninterruptible OS processes and forced termination remain a trust boundary.
- TRF-17–18: semantic correspondence of concrete backend and browser transitions
  to Lean is **not established**. Qualified AST extraction in the differential
  harness binds the tested production functions; it is not a refinement proof.
- TRF-19–21: catalogue differentials, finite load/reuse tests and native CI provide
  test evidence, not universal fairness, latency or deployment guarantees.
- TRF-22: the existing offline TB01 proof/provenance gate passes for its frozen
  model inputs. It does not certify the newly written implementation.

Accordingly, the [23 end-to-end formal obligations](../closure/traffic-obligations.json)
remain OPEN in their frozen registry. Runtime regression success does not change
that status. The release claims a tested performance implementation, not complete
formal closure of the portal.
