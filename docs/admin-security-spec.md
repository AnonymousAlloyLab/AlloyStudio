# Authenticated model import: specification before implementation

Version 1, 2026-09-29; upload-capacity and dependency/parameter amendments 2026-10-11. This is the acceptance contract for the new admin feature,
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
identifier format. Scope defaults to 5, range 1..8. Limit 100 exercise groups,
256 source solution declarations total, 8 KiB per selected practice body and the existing aggregate
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
exercise. Every variant is an oracle. Parameter declarations must agree across
variants, including names, grouping, disjointness, multiplicities and domains.
If a bare `inv1` declaration
also exists, it is the learner starter, not another oracle. Otherwise create a
backend-generated `inv1` learner slot with the same parameter declarations and a
comment starter. A practice permits at most 16 explicit argument names, arity at
most eight, and an 8-KiB header. Receiver-style predicates remain retained helpers;
use explicit parameters when publishing a practice.
Other source-backed predicates become standalone exercises with
their original names. The administrator selects which groups become exercises;
nonselected predicates and their original bodies remain in the displayed model
environment. Functions remain helpers; administrators may select predicates with
parameters as practices. The fixed editor header contains their original typed
parameter declarations. The lowest numeric variant is the primary oracle;
standalone predicates use their sole body. Group order follows source order.
Newly selectable parameterized standalone helpers that remain unselected retain
the model-wide source budget rather than acquiring the practice body/header cap.
An oversized ordinary helper header is omitted from discovery metadata; selecting
it fails before validation. Every original numbered C solution still has the
8-KiB body/header bounds, including unselected groups.

Keep the complete uploaded source unchanged in a private SQLite TEXT witness.
Use verified UTF-8 byte spans to take exact oracle/starter body slices. Include
private modifiers in declaration-removal spans. Remove all selected oracle bodies
from the learner projection, preserving every retained environment fragment
exactly. Generated selected-predicate wrappers are explicitly derived text;
they do not purport to be byte-identical to the original wrapper. Never rewrite
predicate bodies, signature/fact/helper code, module declarations or commands.
For new version-2 witnesses, replace other selected standalone declarations with
explicitly generated blank learner slots of the same names and parameter headers
at their original positions. These slots preserve symbol resolution for retained
aggregates without revealing any selected answer. Version-1 archives retain their
original projection and remain verifiable. No unselected declaration is rewritten.
Reject unresolved dependencies on removed C aliases, including direct
run commands, recursion and references hidden in helpers or facts. Do not delete
or rewrite commands to make an upload pass. A retained learner starter cannot
be embedded as a hidden solution to another generated exercise. A selected oracle
or starter may not transitively call another selected slot or selected C alias.
Facts, signature facts and field domains may not transitively depend on any
selected slot. Retained nonrecursive callers used only by helpers, assertions or
commands are permitted: feedback executes generated facts-and-target queries,
not uploaded commands. Calling such an incoming helper from the selected body
would create a cycle and must still be rejected in each independently parsed body.

Version-three witnesses, introduced in `v0.0.7-alpha.3`, supersede the preceding
selected-to-selected prohibition for acyclic practice dependencies. The
[dependency and progress contract](practice-dependencies-progress-spec.md)
governs inspected call graphs, generated typed forwarding wrappers, immutable
completion receipts and private validation environments. Dependent practices
remain locked until the backend verifies their prerequisites. Facts, signature
facts, field domains and parameter domains still cannot depend on selected
practices; recursion and practice-group cycles remain rejected. Version-one and
version-two archives retain their original projections and verification rules.

Parameterized equivalence is pointwise at the existing bounds: search for an
argument assignment where the two bodies disagree, using the same parameter
variables and declaration domains. Comparing two separately universally closed
bodies is forbidden. Behavioral examples and scores must likewise evaluate the
same model and argument assignment, expose student parameter bindings, and retain
the existing no-counterexample requirement for score 1. No synthetic atoms may
change the model's `univ`, arithmetic or relation meaning. Header/body containment,
parameter-domain independence, work deadlines and JVM admission limits still apply.
An exact-bound declaration such as `n = Node` retains its original public header;
the private quantifier bridge uses a set domain with an explicit equality to
that original bound. It must preserve grouping, disjointness and dependent bounds,
rather than admit arbitrary subsets. For parameterized temporal queries, enumerate
the union of exact trace lengths 1..10: the pinned Alloy enumerator loses exposed
argument skolems when a variable-length enumeration grows. Each exact-length
cursor preserves the bindings, and exhaustion means all ten lengths completed.
Static and parameterless enumeration retain their existing path. Every sample
uses its own argument skolems; missing bindings fail closed rather than choosing
new arguments or dropping the assignment from the LFU identity.

The browser starts with no practices selected so helpers are not silently removed.
Rejected backend drafts are discarded, but immutable local source and selections
remain available for a changed selection and fresh validation. This local state
grants no suggestion/publication authority; logout, file changes and explicit
discard clear it. Old responses must not restore it after those events.

Discovery uses an empty `practicePredicates` list and cannot produce a publishable
draft. A nonempty list chooses exact, distinct group names; unknown names are
rejected, and final exercise order follows source order. Omitting this field keeps
the older private API's select-all behavior. Selection never splits an InvXC group.
Only selected oracle declarations and selected learner wrappers are removed.
All original code is parsed before selection; selected groups then receive full
validation in their retained, selection-specific environments.

Final preparation also checks every original numbered InvXC group, including
unselected groups, in the original parsed model. Require satisfiable model facts
and UNSAT for each variant's mismatch with its group's first variant at the
requested bounds. Selection must never bypass the original InvXC equivalence
requirement. The existing inspection JVM performs this gate without extra source
frames or JVM launches, and reports the checked group/variant counts and scope
for backend verification. Discovery remains type checking only. Compared original
expressions share the original model's reachable string-literal universe; selected
exercise validation retains its separate existing string-universe guard.

Independently reparse every generated starter and reference in its fixed
exercise environment. Require satisfiable module facts and UNSAT for
`facts and not (primary iff variant)` for EVERY variant. This reuses the existing
Alloy import validator and its recorded bounds/engine identity. The whole upload
gets a 60-second total parse/solver deadline, with a 256-MiB Java heap and sanitized failures. An
invalid late variant rejects the entire upload. Identical token bodies may be
stored once after full-source typing; retain all original declaration names and
body/span hashes in provenance. No primary match permits skipping later variants.

Upload validation uses one inspection JVM and one sequential, framed validation
JVM, with one live administrator JVM. Commands request two processors; the
existing shared runtime profile controls the effective processor count.
The preceding discovery stage uses one inspection JVM; the complete two-stage
browser workflow therefore starts three JVMs sequentially, regardless of whether
one or 100 groups are selected. Discovery is rechecked on final preparation,
since the authenticated private API can submit different source at that stage.
Each validation frame remains at most 1 MiB; the batch is at most 200 frames and
16 MiB. Dependency identity is checked before and after the whole preparation,
not rehashed for every exercise. Prepared data is capped at 8 MiB per draft and
32 MiB across retained prepared drafts. These serialized-data limits are not RSS
proofs. Publication JSON is capped at 2 MiB, independently of per-field limits;
oversized escaped metadata is rejected. All existing authorization, deadline,
equivalence, source-witness and atomic-publication gates remain in force.

The 2026-10-11 acceptance fixtures exercise 100 standalone groups through
preparation, atomic SQLite publication and reload; 100 equivalent variant pairs;
and 100 byte-distinct, token-identical pairs (200 validation frames). Each final
preparation starts exactly two JVMs and checks dependency identity exactly twice.
The tests also reject 101 groups and 257 variants before launching a JVM,
preserve unselected declarations through the public HTTP projection, and publish
100 questions through HTTP above the former 128-KiB body limit. Original-world
checks reject unequal unselected InvXC groups and prevent string literals from
making equivalence vacuous; equivalent retained helpers remain usable. A real batch
timeout test observes child reaping, empty owned scratch directories and a
successful retry. Failed/stale browser drafts are discarded without deleting a
newer upload, and discovery cannot invoke Luna or publish.

From the source checkout, run `python scripts/test_upload_capacity.py` for fresh, source-hashed evidence
under `build/upload-capacity-validation/`. On the recorded Linux host pinned to
two CPU cores, the three synthetic cases took 1.074, 1.372 and 1.726 seconds;
maximum individual JVM RSS was approximately 233–244 MiB. Every case had one
live admin JVM at most, zero remaining reservations and no scratch files after
completion. These examples do not establish arbitrary SAT runtimes or combined
service RSS. The Windows/macOS CI lanes include batch, selection and capacity
regressions; native IIS acceptance remains a deployment check.

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
