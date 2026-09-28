# Finite closure candidate

Run `python3 scripts/verify_closure.py` after finishing changes. Each invocation creates
an exclusive `closure/runs/<UTC timestamp>-<random suffix>/` directory. It freezes
claims, inputs, verifiers, witnesses, provenance, and actual dependency versions;
then makes two fresh copies and runs the registered checks with external networking
disabled in child namespaces. Only loopback is enabled. It never calls OpenAI.

The machine-readable `reports/closure-report.json` in that run is authoritative.
The generated Markdown report is a rendering of it. A successful run exits 0,
BLOCKED exits 1, and INFRASTRUCTURE_FAILURE exits 2. Completed run files are made
read-only. There is no override; any repair requires a new run and input root.

`python3 scripts/verify_closure.py --self-test` exercises the decision function with
negative controls without creating or asserting a closure run. Required local
dependencies are JDK 17+, Python 3, Git, Node, installed locked Playwright dependencies,
the matching cached Chromium browsers, `unshare`, and `ip`. No dependency is
downloaded during verification. Unavailable tooling produces infrastructure failure.

## Catalogue

`C-CATALOGUE`: the 181 bundled exercises and lexical adversarial fixtures are checked
for exact source-byte preservation, private/public splitting, and corruption detection.
All 181 task descriptions must cover the catalogue exactly, match their frozen
source hashes, reach the public API, and reproduce the prose-only guide. Refresh
tests reject stale bindings before changing metadata and preserve every other
catalogue field. These are coverage, binding, and delivery assertions; semantic
equivalence between English prose and Alloy is outside this mechanical claim.

## Engine

`C-ENGINE`: the 378 frozen Java regression assertions and 181 starter/reference pairs plus 181
reference self-comparisons are tested. Expanded matrix-trace witnesses cover actual
node edits, private replay under the selected variable/child alignments, and public
learner-context privacy. This is finite regression evidence; it is not a proof of
Alloy semantics or of canonicalization soundness. Any remaining temporal aggregates
remain explicitly marked; no formally certified optimal edit-script claim is made.

## Raw AST distance

`C-AST`: a separate finite oracle enumerates all ancestry/order-preserving mappings
for all 10,404 pairs of two-label ordered trees with one through four nodes. Its
preorder/parent-walk enumeration is independent of the production postorder forest
dynamic program. Assertions compare the distance and atomic trace cost and require
private replay with single-node deletion/promotion and insertion/adoption. Explicit
negative witnesses reject invalid mappings, cost mismatches and resource limits.
This finite enumeration is not a proof of Zhang-Shasha optimality for arbitrary trees.

Parser fixtures retain the actual raw AST wrappers, operand order and variable
spelling; they check quantifiers, calls, repeated source occurrences, Unicode,
learner-only insertion anchors and hidden reference names/literals. Every one of
the 181 bundled exercises has starter/oracle and oracle-identity comparisons.
Synthetic pools and a largest real pool check complete evaluation including the
oracle, deterministic ties and failures after an early zero match. This claim does
not promote raw AST distance to Alloy semantic equivalence or textual-patch correctness.

## Metric selection and education

`C-METRICS`: frozen HTTP/worker fixtures bind requests, response identities and cache
entries to the selected canonical or AST metric. Canonical remains the default;
unknown choices and mismatched worker results must fail closed. Mocked Luna tests
require the selected trace, complete atomic-operation IDs, metric-specific components
and learner-only evidence. Live model behavior remains outside this claim.
`C-BROWSER` separately checks mode-switch races, stale feedback/guidance rejection,
real AST edit rendering and source-only AST highlights.

## CI/CD dashboard

`C-CICD`: local tests check summary serialization allowlists, missing/malformed and
symlinked inputs, clean-revision binding, historical closure labeling, captured
process-output suppression, release tag/version agreement and the exact four-file
public artifact allowlist. Loopback HTTP fixtures check the directory index, relative
trailing-slash redirect, exact static-file allowlist, traversal/private-file refusal,
current content types/bytes and a GitHub connection policy confined to dashboard
responses. Workflow fixtures require full official-action pins,
read-only permissions, no secret expressions and disabled OpenAI calls.

Seven dashboard Chromium scenarios run through the Python verifier in each frozen
build. They cover missing and stale results, mocked workflow and prerelease data,
safe links, inaccessible/rate-limited GitHub responses, no automatic external fetch
and relative assets under an IIS application prefix. These test fixtures use no live
GitHub API. Actual Actions jobs, native hosted Windows/macOS execution, public
artifact/release publication and production deployment require separate evidence.
The release workflow prepares readiness metadata; it does not claim to deploy IIS.
Command-selection fixtures require Git-associated Bash for standard and portable
Windows Git layouts and reject fallback to an unrelated WSL launcher.

## Lean obligation plan

`C-LEAN-PLAN`: metadata tests check the 24 planned obligations, unique IDs and theorem
names, dependency DAG, existing implementation paths, dependency-closed profiles,
documentation/table agreement, proposed toolchain pin and dashboard counts. All
24 obligations must remain `OPEN` with empty evidence; formal closure remains
`NOT_ESTABLISHED`. The `raw-ast` profile and complete `full-portal` profile are plans,
not completed proofs. No Lean installation or proof checker runs in this gate.

The regression closure still has an empty proof inventory and zero required
implementation-to-proof mappings. A future formal closure must register its own
proof checker, statement/axiom evidence and implementation correspondence; passing
this consistency test does not discharge any planned Lean theorem. See
[the implementation plan](../docs/lean-closure.md) and
[obligation register](lean-obligations.json).

## HTTP

`C-HTTP`: the frozen request-validation, public projection, private path, resource
limit, and worker error cases are tested against a loopback HTTP server.

## Correct pools

`C-CORRECT-POOLS`: the bundled server-side document covers all 181 exercises with 7,550
context-compatible corpus-correct candidates and 181 explicit oracles. Source,
body, context, classification-path, ordering, and exclusion witnesses are checked
offline. There are 92 excluded source contexts and five pools with only an
oracle. This finite artifact check trusts the corpus labels and oracle truth;
it does not independently solve each correctness assertion or certify corpus
completeness beyond the frozen inventory and importer surface.

## Nearest correct

`C-NEAREST-CORRECT`: actual Java comparisons check exhaustive finite-pool minimum
selection, deterministic ties, winner-only repair traces, and private failures.
All 181 starter requests compare all admitted candidates and have distance no
greater than their included oracle. Tests cover alternative correct formulations
that reach zero despite positive oracle distance, invalid candidates after an
early zero, rejected partial worker responses, content-bound validation caching,
and Luna's projection of completed comparison counts. No globally closest
semantic solution or solver synthesis claim is made.

## Luna

`C-LUNA`: mocked transport and HTTP tests check outbound field allowlisting,
learner-only raw/canonical context and validated spans, all operation and instance
IDs, structured output validation, bounded hint lengths, obvious solution-shaped
output refusal, credential placement, caching, and failure behavior. Exact cached
behavior snapshots are bound to an exercise/draft token before annotation; client
supplied evidence is rejected. Live Luna availability, the truth of AI explanations,
and a universal guarantee against inferable solutions in prose are OUT_OF_SCOPE.
Actual API quota/access is an independent
deployment condition and cannot be discharged by these offline tests.

## Credentials

`C-CREDENTIALS`: synthetic-key tests check JSON configuration, private file
permissions on POSIX and Windows-mode branches, explicit override precedence,
bounded and fail-closed reads, and relative paths after relocating the backend.
Tests also check credential rotation cannot reuse another account's cached
explanation, private configuration is ignored and excluded from snapshots,
and HTTP cannot retrieve or replace deployment credentials. The IIS claim
checks the blank template in archives and private task configuration selection.
No portal key entry is provided. Windows ACL enforcement is a deployment
dependency; actual Windows execution and encryption at rest are not claimed.

## Browser

`C-BROWSER`: the frozen `tests/browser.mjs` workflow assertions run in Chromium on
both clean builds. The exact assertions and logs define the finite browser surface.
HTML error pages, malformed API JSON, and redirected guidance must show the HTTP
status and requested endpoint without exposing response bodies. Drafts and
canonical feedback survive those failures, with retry after recovery.
The virtual-application fixture serves the actual packaged HTML while stale
unversioned JS/CSS URLs return poisoned content. The browser must request the
content-versioned assets and complete navigation, feedback, and download.

## Source and canonical locators

`C-LOCATORS`: frozen JVM fixtures check that recorded canonical child paths select
the corresponding rendered nodes, including distinct occurrences of equal text
and quantifier ordering. Source-origin fixtures check parser positions retained
through normalization, removed or merged origins, and equality of the fixture
canonical forms and distance with presentation metadata present or erased.
Other fixtures cover related and ambiguous source context, quantifier headers,
comments, Unicode, target isolation, and explicit unavailable or whole-form context.
HTTP-boundary fixtures check UTF-16 offsets, body containment, coordinate/text
recomputation, invalid and partial mapping refusal, single-node precision, and
literal-preserving canonical whitespace compaction with remapped ranges.
The separate educational projection validates learner locator spans before they
enter the Luna prompt. `C-BROWSER` separately discharges
the paired color/underline, selected repeated occurrence, ambiguity, scroll/resize,
keyboard and stale-draft interaction assertions. Node precision identifies the
structural occurrence selected by the trace when that identity is retained;
normalization can merge or remove it, requiring related or unavailable context.
These finite fixture checks do not establish universal provenance preservation,
a proof of a source defect, or executable source-patch correctness.

## Behavioral feedback

`C-BEHAVIOR`: frozen real-JVM fixtures check the ACGN reward formula, fact-aware
sampling and semantic-counterexample correction, bounded truth categories,
temporal tuples, oracle isolation, and explicit undefined scores. Projection and
HTTP fixtures check arithmetic, witness limits, metadata exclusion, request
validation, independent worker/cache behavior, and sanitized failures. Browser
rendering, example/state selection, three-decimal formatting, and stale-draft
handling are discharged separately by `C-BROWSER`.

The score uses ACGN's fixed scope and sampling constants with model facts enforced,
an intentional difference from the original Rewarder's omitted module facts.
SAT4J/Alloy solving and classification of the original oracle are trusted. No
exhaustive unbounded model coverage, calibrated probability, or semantic
equivalence proof is claimed; a displayed rounded 1.000 can still have a bounded
counterexample. The finite fixtures, not all possible models, define this claim.

## Builds

`C-BUILDS`: both fresh builds, IIS packaging, and test sets must pass. Class-file,
web-asset, and IIS archive/checksum hashes,
claim-status sets, proof inventory (empty), correspondence mapping (zero objects),
and provenance metadata must agree. Times, temporary paths, ephemeral ports, and
raw log timings are not reproducible artifacts.

The portal build now creates the archive as part of its normal dependency chain.
The separate packaging CLI is also exercised and must compile fresh source before
writing the ZIP. Engine-only builds deliberately stop before packaging.

## Integrity

`C-INTEGRITY`: hash bindings, manifest completeness within the declared project
inputs, frozen verifier registrations, evidence freshness, witnesses, trust,
provenance, and decision negative controls are mechanically checked. The negative
controls cover mutation, missing dependencies, stale evidence, absent provenance,
missing witnesses, correspondence defects, undefined behavior, and scope leakage.

## Delivery

`C-DELIVERY`: the vendored framework's delivered snapshot hashes and recorded base
commit string are checked. The vendored copy includes a local presentation metadata
patch for parser origins; it is not byte-identical to that upstream commit. Git
history attribution is trusted; Git object membership is not verified. The declared
delivery tree is scanned for the frozen credential
filename and token patterns. Git ignore rules are exercised in an isolated
repository: the bundled catalogue and pools must be eligible for inclusion,
while credentials and generated deployment output remain excluded. Dataset
coverage and source-witness checks bind the included exercise data.
A source-only temporary Git repository is cloned with `core.autocrlf=true`;
`.gitattributes` must preserve Bash/corpus bytes and every vendored snapshot hash.
This Linux fixture reproduces checkout conversion settings, not native Windows execution.
Local credentials in `openai.local.json` and `secrets/` are excluded from the
source distribution and frozen snapshots.
This finite pattern check is not a claim that arbitrary possible secrets can be detected.

## IIS

Source checkouts include both exercise files. The Windows build can use them
without an original ACGN checkout; package refusal names restoration from Git
when bundled data are missing. Optional custom or legacy imports still support
`-ACGNRoot` or `ACGN_ROOT`. Tests cover verified pair publication, reruns without
an original checkout, partial-pair refusal, missing and empty custom corpora,
and safe diagnostic codes. A trusted IIS ZIP can restore exactly the two server-side data files after
manifest hash/count and source-witness checks; malformed, missing, duplicate,
or altered required members are refused before publication, and unrelated ZIP
paths are never extracted. Normal clones use the included catalogue and pools
directly. They need neither ZIP restoration nor the original classified-data
directory. Solutions remain excluded from public HTTP responses but can be
read in the repository data.

The read-only API diagnostic is exercised against local HTTP fixtures for
backend failure, proxy errors, redirects, malformed health responses, and
bounded reads. Its output excludes response bodies, URLs, and credentials.
It identifies a failing connection boundary; an actual IIS repair and target
acceptance still require execution on the deployment host.

`C-IIS`: frozen package and compatibility tests check deterministic archive
contents and manifest hashes, public/private file isolation, rejected package
inputs, explicit trusted origins behind a proxy, UTF-8 Java and key handling,
the packaged runtime, and the task launcher's process, configuration, and
sanitized failure behavior. The browser claim additionally checks real feedback
through a virtual application prefix. Build-chain fixtures change Java source,
seed obsolete classes and an older ZIP, and verify that the replacement ZIP
contains the freshly compiled change without obsolete classes. Custom class
outputs must be normalized into the packaged runtime path. Compiler and frontend
failures must propagate without reporting a refreshed archive; compilation failure
must preserve the previous classes, ZIP, and checksum. These assertions run on Linux. They do
not establish that IIS, Windows PowerShell 5.1, NTFS ACLs, or Task Scheduler ran
successfully on Windows. The supplied target acceptance script and actual
Windows deployment remain outside this offline closure.

Package fixtures bind relative JS/CSS URLs to the exact payload SHA-256 values
and require changed content to produce new URLs even with identical ZIP dates.
XML assertions require static no-cache/no-store configuration, disabled
timestamp-based ETags, and disabled IIS output caching. Python HTTP fixtures
check versioned URLs with old conditional headers return current bytes. Actual
IIS emitted headers, browser/CDN cache eviction, and production deployment
acceptance remain outside this offline closure.

The supplemental PowerShell path-policy fixtures use controlled filesystem
adapters on Linux. The separate native Windows fixtures exercise actual file
links, parent junctions, missing paths, cycles, and private/public overlap.
Neither fixture suite is discharged by this offline Python/browser closure;
their results must be reported separately, and native path resolution and
Windows deployment remain excluded from its claims.

## Runtime dependencies

`C-RUNTIME`: the extracted package must contain each of the seven declared JARs
at its snapshot SHA-256, including both AlloyASG archives. Tests remove and
corrupt every JAR individually and require failure even when overlapping
classes in another JAR could let an ordinary feedback request succeed. Missing
compiled entry classes and an unavailable Java executable also fail. A relocated
package under a path with spaces runs all 378 compiled engine checks with
ambient Java classpaths/options and Python import paths excluded.

The source-build witness uses a fresh relocated tree, preflights dependencies
without requiring compiled classes, compiles with an explicit ordered JAR
classpath, and executes the engine checks. Shared compiler fixtures check clean
staging, source changes, removal of obsolete classes, protected output paths,
Java 17 class headers, required entry classes, environment isolation, and
preservation of previous output on compiler or publication failure. Frozen structural assertions bind
the Windows build script to that dependency report and the platform path
separator. Simulated MSYS, MINGW and Cygwin launchers exercise the actual Bash
entry points and assert their native PowerShell delegation, absolute path
conversion, conversion-disabling environment, output-directory semantics, and
exit-code propagation. These tests run on Linux; actual Windows PowerShell invocation and
Windows native argument passing remain outside the offline closure. No Maven,
network download, or original ACGN checkout supplies a missing dependency.

## Local Linux and macOS launcher

`C-LOCAL`: frozen Linux regressions construct an isolated Git repository, clone
its complete bundled data into a path with spaces, and execute setup and startup
with no IIS archive, Node, original ACGN corpus, or credentials. All 181 records
and pool witnesses are validated, including the explicit oracle in each pool.
The tests require actual Java compilation, engine self-tests, HTTP feedback,
solution/credential HTTP isolation, and terminal shutdown. Separate recovery
fixtures exercise a private ZIP and poisoned ambient Java options. Runtime
selection tests cover missing, unusable, old, and
mismatched JDKs and Python selection failures. macOS discovery is tested with
simulated `java_home` and PATH fixtures on Linux. Native macOS execution on Intel
or Apple silicon remains OUT_OF_SCOPE.

The TCB includes language runtimes and toolchains, Git, browser and Playwright, vendored
ACGN/Alloy code and jars, test interpretation, Linux namespaces, OS userland and
libraries, corpus classifications and oracle truth, hardware, and SHA-256. Browser-cache files are hashed as external trusted
inputs and checked again before final decision. Project inputs include all source,
configuration, tests, fixtures, vendor files, and installed `node_modules`, excluding
declared generated outputs and private credentials.

VERIFIED means only that all frozen finite obligations passed under the declared
TCB. No proofs are compiled, no implementation objects are mapped to proofs, and
no universal correctness, absence of bugs, or future deployment claim is made.
