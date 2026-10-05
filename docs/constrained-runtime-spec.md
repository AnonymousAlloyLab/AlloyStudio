# Constrained runtime contract for v0.0.5-alpha

This contract precedes the changes for a two-to-four-core VPS or older laptop.
It supplements AP01; it does not rewrite historical proof inputs or claim that
earlier proofs cover a changed implementation. Supporting features remain:
Canonical (the default), raw AST, complete correct pools including oracles,
behavioral score and four groups of instances, diagrams, Luna guidance,
administration, SQLite, the dashboard, and portable local/IIS deployment.

## Finite obligations

- **LP05-01, execution profile:** the default `constrained` profile has one
  feedback worker, one behavior worker, one advertised JVM processor and a
  20-second acquisition/startup allowance. `standard` has two feedback workers,
  two advertised JVM processors and a 10-second allowance. Both retain 256 MiB
  JVM heaps and the separate administrator lane. Explicit worker settings remain
  supported; no feature is disabled to reduce concurrency. These quantities
  bound application configuration, not host RSS or OS CPU scheduling.
- **LP05-02, cold versus warm deadline:** acquisition has its own bounded
  allowance, followed by the full configured execution budget. No phase renews
  its deadline on partial progress. Request-path cleanup waits at most 0.25
  seconds and retains ownership until reaping is confirmed. Scheduler waiting
  includes acquisition, execution and cleanup allowances. Queued requests expire
  at the queue deadline even when the preceding job is still running.
- **LP05-03, startup classification:** waiting for a shared process slot, or
  shutdown before spawning, cannot increment the failed-start circuit. Launch
  attempt credits still follow the existing lane policy. A real failed launch
  or readiness handshake does advance the circuit; uncertain processes retain
  their reservations.
- **LP05-04, cooperative work deadline:** a private feedback frame carries an
  integer millisecond work allowance derived from two-thirds of the remaining
  execution allowance, capped at 60,000 ms. Legacy frames/default one-shot
  evaluation use 8,000 ms; an operator JVM property can set the one-shot value.
  Charged work samples a monotonic clock at a bounded checkpoint cadence, with a
  mandatory final publication check. Expiry is sticky and yields `WORK_LIMIT`
  without a partial winner or a poisoned worker. Unbudgeted library calls and
  explicitly untimed calibration helpers preserve their existing behavior.
- **LP05-05, result preservation:** completed comparisons preserve their full
  ordered correct pool, tie-breaking, distance, edits and public redaction.
  A timing refusal may differ on slower machines; it must never masquerade as
  a complete result. Both metrics remain in parity and recovery checks.
- **LP05-06, IIS consistency:** launcher, task configuration and local CLI carry
  the same profile options. IIS-supported execution/startup settings are at most
  30 seconds each. Queue, cleanup, reply and provider allowances must fit below
  the documented 120-second ARR timeout and 150-second browser attempt budget
  for the supported defaults. Existing higher global ARR timeouts are retained.
  Default-denied administrator paths are tested as such; optional permitted
  administration is a distinct setup. Other IIS sites must remain unchanged.
  Ordinary analysis has a 71.25-second configured phase envelope at the maximum
  IIS settings. A legacy explanation without retained evidence can additionally
  evaluate before its 40-second provider call: its envelope is 111.25 seconds.
  These are sums of application allowances, not scheduler-preemption guarantees.
  The provider's 40-second socket timeout is not an absolute whole-operation
  deadline; slow provider streaming can exceed that nominal envelope. ARR and
  the browser retain their independent request limits.
- **LP05-07, evidence and release:** preserve prior results, record new runs in
  owned directories, check all retained features, rebuild the private IIS ZIP,
  and publish the source prerelease only after local and native CI checks pass.
  The private ZIP and deployment credentials are not public release assets.

## Refinement and measurement boundaries

The new Lean work is a narrow semantic refinement of extracted charged-work and
checkpoint transitions, with explicit parser/runtime/clock trust. It is not a
proof of every Java call site, parser or solver interruption, garbage collection,
OS scheduling, whole-process memory, IIS or Cloudflare. No `sorry`, introduced
axioms or network access is allowed in proof checking. Each frozen proof block
uses the requested two Luna, two GPT-6.1 Sol, two Astra review ladder; reviews
require a concrete counterexample for an alleged invariant breach and do not
replace mechanical verification.

Measurements pin the benchmark process and its children to two or four CPUs.
Record the actual affinity and memory observations. Affinity does not simulate
an older CPU's clock speed, a VPS CPU quota, or a machine-wide RAM cap. Cold and
warm comparisons, concurrent visits/edits, expensive drafts, refusals and recovery
must be recorded separately. Real provider calls remain outside these tests;
Luna integration is exercised using disabled or controlled fixtures.

Failure of a performance or correspondence check remains visible. Evidence from
an earlier source hash is not relabeled as current verification. Native Windows
IIS results and local Linux package checks are reported separately.
