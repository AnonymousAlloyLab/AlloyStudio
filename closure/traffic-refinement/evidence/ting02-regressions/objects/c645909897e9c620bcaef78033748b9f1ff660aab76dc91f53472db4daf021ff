# Authenticated model import: specification before implementation

Version 1, 2026-09-29. This is the acceptance contract for the new admin feature,
not a claim of completed verification. It extends the SQLite specification:
its CLI-only/no-hot-reload restrictions are superseded only for the authenticated
routes described here. The learner API and public field allowlists stay in force.
An independent adversarial review must precede implementation.

## Authority and authentication

`/admin/` serves a public login shell. `/api/admin/*` is a separate private API.
No default password exists. A host-side interactive setup command writes
`admin.local.json`: a random 16-byte salt and scrypt password hash (N=131072,
r=8, p=1, dklen=32, explicit 256 MiB allocation ceiling), never the password.
Passwords must contain 12–1024 UTF-8 bytes, with no NUL or invalid Unicode;
empty or mismatched confirmation is rejected. Login bodies are at most 8 KiB.
It also stores one explicit allowed browser origin and application base path.
Missing, malformed or linked configuration disables administration. POSIX config
permissions are owner-only; IIS gives its backend account read access only.
Configuration, credentials and upload drafts are excluded from Git, packages,
closure snapshots, public files, diagnostics and logs.

Production uses a configured HTTPS origin. Behind IIS/Cloudflare the upstream
Host may be loopback; production origin validation must use the configured
origin, never X-Forwarded-* or arbitrary Host headers. HTTP is allowed only for
an explicitly configured literal loopback origin, a loopback peer, and matching
Host. Application paths are canonical absolute paths, without traversal,
encoding, query, fragments or backslashes. All applications sharing the same
browser origin are trusted; paths do not provide origin isolation.

Use independent opaque random 256-bit preauthentication and authenticated
cookies; server-side records contain token hashes. Preauthentication lasts five
minutes and is capped at 64 records. An authenticated session has a 30-minute
absolute and 15-minute idle expiry, capped at 16 records. Cookie names bind the
configured origin/path. Production uses host-only Secure, HttpOnly,
SameSite=Strict, Path=/ cookies with a valid __Host- prefix; loopback HTTP uses a
distinct unprefixed cookie. Login rotates into a new authenticated session.
Creating preauth state must not destroy an existing authenticated session.

Every mutation, including login, requires exact Origin, JSON content type and a
session-bound synchronizer CSRF token supplied in a custom header. Sensitive GETs
require authentication; the session bootstrap may issue a preauth token under
same-origin browser read protection, without CORS. Cross-site Fetch Metadata is
rejected. Duplicate Origin, Content-Length or relevant cookies, and any
Transfer-Encoding, are rejected. Unauthorized mutation is rejected before body
reads, parsing, solver work or provider calls. Bodies have explicit byte bounds
and a five-second total monotonic read deadline, not just an inactivity timeout.
Rate-limit login globally (five failed attempts per minute), allow only one KDF
at a time, and bound preauth/session stores. Denied requests do not renew idle
expiry. Password/config changes, logout and expiry invalidate authority; recheck
the config generation after KDF and before job disclosure or publication.

A publication guard checks session/config validity at the final authorization
linearization point and holds the auth lock through commit. This prevents a
concurrent in-process logout from racing commit. External config replacement
cannot be atomic with a SQLite commit: revocation is observed at the final
configuration check. Do not claim stronger cross-filesystem atomicity.

Every admin API response, including errors, has `Cache-Control: no-store,
private`, no CORS, nosniff and no-referrer. The admin page has a self-only CSP,
no framing, and renders all upload/provider text using textContent or form
values. Cloudflare must bypass caching `/api/admin/*`; edge configuration and
TLS remain deployment trust, not claims of the local test suite.

## Deterministic upload and naming contract

Accept one UTF-8 .als file, at most 256 KiB, through a JSON envelope at most
2 MiB. Decode browser files with fatal UTF-8 validation; reject NUL, invalid
Unicode, duplicate JSON keys, unrecognized fields and malformed Alloy. Filename
and model ID are metadata, never filesystem paths. IDs use the existing store
identifier format. Scope defaults to 5, range 1..8. Limit eight exercise groups,
64 source solution declarations total, 8 KiB per body and the existing aggregate
engine request bound. A bounded worker admits at most one preparation/provider
operation globally; excess work gets a retryable busy result. Draft/job records (including failed and completed jobs) are capped
at eight globally and two per session, expire after 15 minutes, and are memory
only until commit. There is no separate unbounded completed-job store. Owners
may discard a draft; expired records are reclaimed before admission.

Parse and type-check the full original upload with the actual Alloy API, then
inspect source-backed declarations. Synthetic $$Default/run helpers are not
exercises. Reject duplicate or overloaded declaration names explicitly even if
Alloy accepts them. Module macros are unsupported in this first interface;
unused macros are not reliably type-checked by a full Alloy parse. Imports must
resolve only from the bundled Alloy module resources, never the host filesystem.

Names matching case-preserving `[iI][nN][vV](0|[1-9][0-9]*)[cC](0|[1-9][0-9]*)`
form a group named by the prefix before C. Number components have at most six
digits. Ambiguous mixed-case bases, duplicate numeric variant IDs, and naming
collisions are rejected. `inv1C0`, `inv1C1`, ... therefore become the `inv1`
exercise. Every variant is a parameterless oracle. If a bare `inv1` declaration
also exists, it is the learner starter, not another oracle. Otherwise create a
backend-generated parameterless `inv1` learner slot with a comment starter.
Other source-backed parameterless predicates become standalone exercises with
their original names. Parameterized predicates and functions are retained
helpers, not exercises. The lowest numeric variant is the primary oracle;
standalone predicates use their sole body. Group order follows source order.

Keep the complete uploaded source unchanged in a private SQLite TEXT witness.
Use verified UTF-8 byte spans to take exact oracle/starter body slices. Include
private modifiers in declaration-removal spans. Remove all oracle declarations
from the learner projection, preserving every retained environment fragment
exactly. Generated selected-predicate wrappers are explicitly derived text;
they do not purport to be byte-identical to the original wrapper. Never rewrite
predicate bodies, signature/fact/helper code, module declarations or commands.
Reject unresolved dependencies on removed oracle declarations, including direct
run commands, recursion and references hidden in helpers or facts. Do not delete
or rewrite commands to make an upload pass. A retained learner starter cannot
be embedded as a hidden solution to another generated exercise.

Independently reparse every generated starter and reference in its fixed
exercise environment. Require satisfiable module facts and UNSAT for
`facts and not (primary iff variant)` for EVERY variant. This reuses the existing
Alloy import validator and its recorded bounds/engine identity. The whole upload
gets a 60-second total parse/solver deadline, with a 256-MiB Java heap and sanitized failures. An
invalid late variant rejects the entire upload. Identical token bodies may be
stored once after full-source typing; retain all original declaration names and
body/span hashes in provenance. No primary match permits skipping later variants.

Equivalence is bounded: scope 1..8, bitwidth 5, max sequence equal to scope,
trace range 1..10. It is not an unbounded proof or proof that the primary oracle
matches the administrator's intended question. Unsupported contexts fail closed.

## Luna and review

Use the configured server-side GPT-6 Luna key and Responses API, with store=false
and a separate admin prompt/schema from learner hints. The administrator may
enter a natural-language question seed (at most 8 KiB UTF-8). Suggested and
reviewed titles are at most 256 UTF-8 bytes and questions at most 8 KiB; both
must be nonempty. Provider work has a 40-second request timeout and a 1-MiB
response ceiling, with a 50-second subprocess deadline that terminates stalled
transport even if a provider drips bytes indefinitely. After deterministic validation, Luna
formats import metadata and suggests short titles/questions for each exact
backend-selected exercise name, including single-predicate uploads. The upload
and optional seed are sent to OpenAI only through this authenticated feature;
the page states that plainly. The key never enters a prompt or response.

Luna is not an authority for code, group identity, environment or equivalence.
The strict accepted response contains only a list of exact protected predicate
names with title/question strings. Reject unknown/missing/duplicate names,
extra fields, code/source/header/environment fields, invalid types and limits.
Assembly copies protected code only from backend-owned validated draft data.
Provider failure, refusal or missing key leaves manual question editing usable.
All suggestions are plain text. Titles/questions are PUBLIC after publication;
the preview requires administrator review for correctness and solution leakage.
No automatic prose filter is advertised as a semantic no-solution guarantee.

## Jobs, persistence and publication

Prepare and suggest operations run as bounded background work and return 202
with random owner-bound IDs; polling must authenticate the owner and return
sanitized state. Never disclose another session's job. Expired/revoked jobs cannot
publish. Bind drafts to source hash, generation and revision; successful commit
is single-use. Publication accepts only reviewed title/question metadata, never
caller-supplied source, equivalence certificates or arbitrary SQL.

Commit every group, all solutions and the original upload witness in ONE SQLite
transaction using registered SQLeanParser-generated parameterized queries.
Duplicate existing IDs reject without overwrite. Any validation or authorization
failure rolls back the whole batch. Existing `auxiliary` TEXT/provenance columns
may hold a new strictly validated `adminUpload` kind; the physical schema remains
version 1. Validate its source hash, body spans/hashes and exercise bindings on
load, including after package backup/restore. Do not serialize predicate source
into metadata JSON. Existing CLI imports and legacy data remain readable.

Construct a complete validated snapshot before commit, then atomically publish
its pointer to the running server. Readers capture one snapshot generation;
new exercises become available immediately. Imports are add-only: existing
exercises, reference pools and cache meanings are not altered. Concurrent jobs
and commits cannot expose partial state. IIS gives Modify only to the private
exercises directory for SQLite journals, while code and credentials remain
read-only to the backend identity. Startup accepts positive catalogue counts,
not a hard-coded seed count. Packaging includes admin assets/helpers/validator
classes but excludes live credentials and in-memory drafts.

## Finite acceptance and trust

The register is `closure/admin-spec.json`. Construct executable negative
witnesses for each invariant breach found in review. Test actual HTTP sessions,
framing/deadlines, password rotation/logout races, actual Alloy grouping and
counterexamples, source preservation, malicious Luna responses, transaction
rollback, hot visibility, public redaction, relocated packaging and browser
login/upload/review. Register tests with the finite closure runner and freeze
all implementation/specification/verifier inputs for two clean offline builds.
Existing Lean proofs do not certify the new admin code. Native IIS/NTFS, actual
Cloudflare enforcement, provider model behavior and unbounded Alloy equivalence
remain explicit exclusions. Trusted components include Python crypto/HTTP,
SQLite, Alloy/SAT4J, pinned SQLean artifacts, OS permissions, TLS/edge policy,
administrator intent and same-origin browser isolation.

Design references: [OWASP password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html),
[CSRF prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html),
[session management](https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html),
[OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs).
