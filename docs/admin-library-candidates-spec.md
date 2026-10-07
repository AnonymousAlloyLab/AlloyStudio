# Exercise library and candidate review contract

Specification before implementation, 2026-10-07. This extends the existing
authenticated administrator and SQLite contracts. Historical proof/evidence
records keep their original scope and inputs.

## Learner presentation

The local-workspace badge is hidden by default. It is shown only when the
browser's actual hostname is `localhost`, `127.0.0.1`, or literal IPv6 loopback.
Public IIS, Apache, and Nginx hostnames do not show it. Proxy headers are not an
authority for this browser presentation.

A green problem-set check records progress in the learner's browser, not a
global account or correctness certificate. Award it only for the same current
exercise, body, revision, selected metric, and public content version when the
validated structural distance is exactly zero and the behavioral result is
perfect. Perfect means an available score of 1, positive and negative samples
both present and entirely agreeing, zero semantic counterexamples, facts
enforced, and complete UNSAT undercoverage and overcoverage categories. Rounded
display text alone never awards progress. Stale or failed responses cannot
award it. Store only the exercise identifier, content version, metric, and time;
no predicate body or API credentials. Previously earned progress persists across
draft edits, and changed exercise content invalidates the stored marker.

The server exposes a stable `contentVersion` digest of the public exercise
fields on list, detail, structural, and behavioral responses. It contains no
private predicate material. Administrator version checks use a separate digest
of the authoritative exercise context; both are independent of service startup.

## Administrator edits and removal

All library/cache reads and mutations use the existing authenticated private
API, origin checks, CSRF checks, network policy, strict JSON decoding, byte
bounds, and no-store responses. Failed authorization precedes body processing,
database writes, solver work, or provider calls.

The editor changes only the public title (1–256 UTF-8 bytes) and question
(1–8192 bytes). It preserves model/predicate names, environments, facts, helpers,
starter bodies, oracle bodies, and source witnesses. Edit requests include the
current administrator version. A changed version rejects stale publication.
Removal requires the selected identifier and explicit confirmation. A private
tombstone removes the question from the live learner library without deleting
the original corpus or upload evidence. Removed questions cannot accept new
candidate approvals. An empty active library is a valid administrative outcome.

Library reads return at most 50 summaries per page, without full questions;
read a single question to edit it. Candidate lists contain at most 25 summaries
per page without bodies or advice; read one candidate for its private details.
Every serialized response must fit the ingress response byte limit. The UI has bounded page
navigation and separate reviewed edit/remove actions. Adding models retains the
existing validated upload workflow. Commits recheck administrator authority
under the publication guard and publish one fully validated snapshot generation.

## Candidate admission and retention

Retention is enabled only when backend administration is configured. There is
no learner endpoint for asserting correctness or supplying candidate
certificates. Only the backend's validated behavioral result may admit the exact
student body in its checked environment. Admission requires the perfect result
defined above; structural distance need not be zero. This is a pre-admission
gate, not a filter deferred until administrator or AI review. An actual score
other than exactly 1, any undercoverage or overcoverage witness, a timeout,
unknown result, incomplete enumeration, or malformed evidence prevents admission.
The cache is a private review queue and is separate from the approved correct
pool. Its admission evidence establishes agreement within the recorded Alloy
bounds, not equivalence at every possible scope. Existing known-correct
token-identical bodies are not added to the cache.

The private SQLite cache holds at most 100 candidates globally and 10 per
exercise across all retained versions and statuses, with at most 8 KiB per contained predicate body. Identity binds the
exercise version, exact body SHA-256, and lexical body hash. Repeated admission
deduplicates equivalent token sequences in that context. Each admission has a
fresh random identifier; eviction and re-admission cannot revive an old decision.
Candidate versions bind the entire record and each mutation increments its
revision. Check current approved-pool membership atomically at admission, not
only the older learner request's snapshot. Evict old terminal
records first, then the oldest pending record when necessary. Cache capacity
does not authorize eviction of approved correct-pool entries.

Capture is a best-effort optional side effect: it starts no JVM, provider call,
or new background worker, performs no full-corpus scan, and uses at most a
100-ms SQLite busy wait. Acquire application publication/capture gates without
blocking and skip capture when unavailable; no wait behind administrator work.
Concurrent capture can be skipped. A cache failure
cannot replace successful learner feedback with an error. Cache bodies and
review advice are never exposed by the learner API, public assets, diagnostics,
or logs. The learner page states that successful drafts may be retained privately
for administrator review.

Pending candidates may be reviewed, approved, or dismissed. Terminal decisions
cannot be replayed. Deletion and question-version changes make old candidate
decisions stale. State changes and approval are version-checked and atomic;
untrusted clients cannot supply a model body, review verdict, or equivalence
certificate through an approval request.

## Advisory AI and approval

The administrator explicitly clicks **Review with Sol**. The existing private
OpenAI key is reused; no key is placed in the browser or review input. Use
`gpt-6.1-sol`, `reasoning.effort=high`, the Responses API, `store=false`, no tools,
and at most 8000 output tokens. See the [official model documentation](https://developers.openai.com/api/docs/models/gpt-6.1-sol)
and [reasoning guide](https://developers.openai.com/api/docs/guides/reasoning).

The request is bounded to 1 MiB and contains the exact candidate, immutable
exercise environment, question, authoritative oracle bodies, and a compact
bounded-check summary. Treat all code and question text as untrusted data.
Advice is structured as a bound exercise/version/body identity, a
`recommend`, `reject`, or `uncertain` verdict, a reason up to 1200 UTF-8 bytes,
and at most three counterexample ideas of 400 bytes each. Reject malformed,
extra-field, incomplete, or mismatched responses. A rejection needs a concrete
counterexample idea, which remains an unverified suggestion.

Provider transport is capped at 40 seconds and 1 MiB response; a separate worker
has a hard 50-second lifetime. Missing key, timeout, refusal, and malformed
advice leave manual administrator decisions usable. Repeated viewing performs
no API call. Completed advice is cached against the candidate's exact identity.
Share the existing one-active-administrator-operation slot across upload,
review, and approval work; all job records remain owner-bound and bounded by
the existing 15-minute/eight-global/two-per-owner draft limits.

AI advice never adds to the correct pool. Approval requires administrator
authority and a fresh Alloy API check of the contained candidate against the
primary oracle, with the unchanged environment and facts enforced. Use scope
at least 5 (or the exercise's larger original validation scope, at most 8),
bitwidth 5, sequence bound equal to scope, and trace range 1–10. Require
satisfiable facts and UNSAT disagreement. Parse/type failures, counterexamples,
timeouts, stale state, and incomplete certificates reject publication. The
candidate serves as the validator's starter so legacy invalid starter drafts
do not prevent checking an otherwise valid solution.

A successful approval atomically retains a validated private approval witness,
marks the cache entry approved, and extends the effective known-correct pool.
Retain at most 100 administrator-approved additions per exercise and 10000
globally; reaching this separate witness capacity rejects further approvals.
The primary behavioral oracle remains unchanged. Future canonical and AST
feedback can select the new correct predicate. Approval is bounded agreement,
not an unbounded mathematical proof. A mistaken advisory verdict does not
remove the administrator's final authority or bypass the Alloy validator.

## SQL and verification boundary

Use registered fixed SQLeanParser-generated parameterized statements for reads,
question updates, and auxiliary-row changes. Values never become SQL identifiers
or syntax. Extend the existing finite query registry explicitly; build the
pinned vendored parser offline and regenerate its AST identities/integrity
bindings. Preserve historical SQL01 evidence rather than relabeling its old
ten-query inventory as covering new statements. New trusted SQLite controls
must be finite and explicit, including the cache busy-timeout control.

Before completion, test malformed/stale/unauthorized requests, exact-score and
disagreement gating, cache caps/dedup/eviction, rollback/revocation, hidden data,
provider identity and timeouts, real Alloy rejection/approval, both distance
modes' extended pools, learner progress races, library edits/removal, and
packaged public assets. Record the current proof/production correspondence
scope without claiming historical closure for changed inputs.

## Implemented interfaces

The existing administrator login/session, upload and draft APIs remain available.
All new reads use authenticated POST requests with the existing CSRF token:

- `library` takes `offset`; `questions/detail` takes `exerciseId`.
- `questions/edit` takes `exerciseId`, current `version`, `title`, and `question`.
- `questions/remove` takes `exerciseId`, current `version`, and a matching
  `confirmation` identifier. The original identifier remains reserved.
- `candidates` takes `offset`; `candidates/detail` takes `id` and current `version`.
- `candidates/review`, `candidates/approve`, and `candidates/dismiss` take only
  `id` and current `version`. No client body, advice, or certificate is accepted.

Paths are relative to `/api/admin/`. Review and approval return owner-bound jobs
polled through `drafts/{id}`. The UI discards observed terminal jobs after saving
their results; advice and approvals remain in SQLite. This frees the existing
two-record owner limit without creating another worker pool.

## Validation, 2026-10-07

All **1333 registered Python tests** have passing latest outcomes, including
targeted reruns after repairs. The combined ordinary-test receipt is
`build/admin-features/python-validation.json`; the initial full-run and failed
intermediate receipts are retained. The final packaged browser suite passed
**118 scenarios**, and the existing upload administration suite passed its
14 scenarios. Desktop/mobile views were inspected.

Recurring real Alloy integrations check facts-aware admission and approval,
malformed and inequivalent predicates, inconsistent facts, and both distance
modes. The separate eleven-check report is
`build/admin-features/real-alloy/report.json`: one novel equivalent body changed
canonical distance **5 to 0** and AST distance **11 to 0** after approval, while
the original source/pool witnesses stayed unchanged. The bundled database still
contains 181 exercises and 7731 original correct-pool bodies.

Constructed review witnesses caught and repaired a wrong validator import,
queued stale reviews starting paid work, null versions bypassing preview checks,
and completed UI jobs exhausting the two-record owner limit. Tests also cover
revocation/expiry at publication, per-exercise limits across versions, fresh
identities after eviction, current approved membership, strict response bounds,
and hidden candidate data. Provider calls are mocked; no paid API request was
made during validation. Native Windows/IIS execution is not claimed by Linux
packaging and static PowerShell checks.

The pinned SQLeanParser rebuilt offline with its original executable hash and
generated **16 fixed statements**, with seven actual parser rejection controls.
Two isolated Lean 4.34.1 builds audited **83 theorems** (77 existing model and
six current-registry bridge theorems), with empty axiom dependencies and
deterministic artifacts. Source correspondence remains a restricted, trusted
Python AST extraction; this is not a proof of arbitrary Python, SQLite, Alloy,
or the whole application. The current candidate report
`build/admin-features/sql-proof/SQL-CURRENT-20261007T221544Z-6daed535/report.json`
has `verificationStatus: PASS`, `status: BLOCKED` solely for
`CANDIDATE_ONLY_REVIEWS_NOT_CHECKED`, and `formalClosure: NOT_CLAIMED`.
Historical SQL01 inputs and reviews remain unchanged.

The browser-tested private IIS archive is
`build/iis/alloy-studio-iis-20261007-222132-004386Z.zip`, SHA-256
`421c27c496a8a54945c9bd3f716d42887c396e9ac2fb2a0fc9853a81e6d9dda8`.
Its 255 entries include the new backend modules, current UI, and contract. The
browser suite used this exact archive. Password and OpenAI configurations remain
excluded, and the deployment badge stays hidden even on loopback previews.
