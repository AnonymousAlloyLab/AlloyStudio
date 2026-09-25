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
dependencies are JDK 17+, Python 3, Node, installed locked Playwright dependencies,
the matching cached Chromium browsers, `unshare`, and `ip`. No dependency is
downloaded during verification. Unavailable tooling produces infrastructure failure.

## Catalogue

`C-CATALOGUE`: the 181 bundled exercises and lexical adversarial fixtures are checked
for exact source-byte preservation, private/public splitting, and corruption detection.

## Engine

`C-ENGINE`: the frozen Java regression cases and 181 starter/reference pairs plus 181
reference self-comparisons are tested. Expanded matrix-trace witnesses cover actual
node edits, private replay under the selected variable/child alignments, and public
learner-context privacy. This is finite regression evidence; it is not a proof of
Alloy semantics or of canonicalization soundness. Any remaining temporal aggregates
remain explicitly marked; no formally certified optimal edit-script claim is made.

## HTTP

`C-HTTP`: the frozen request-validation, public projection, private path, resource
limit, and worker error cases are tested against a loopback HTTP server.

## Correct pools

`C-CORRECT-POOLS`: the private document covers all 181 exercises with 7,550
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

`C-LUNA`: mocked transport tests check outbound field allowlisting, credential
placement, response parsing, and failure behavior. Live Luna availability and the
truth of AI explanations are OUT_OF_SCOPE. Actual API quota/access is an independent
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

## Builds

`C-BUILDS`: both fresh builds, IIS packaging, and test sets must pass. Class-file,
web-asset, and IIS archive/checksum hashes,
claim-status sets, proof inventory (empty), correspondence mapping (zero objects),
and provenance metadata must agree. Times, temporary paths, ephemeral ports, and
raw log timings are not reproducible artifacts.

## Integrity

`C-INTEGRITY`: hash bindings, manifest completeness within the declared project
inputs, frozen verifier registrations, evidence freshness, witnesses, trust,
provenance, and decision negative controls are mechanically checked. The negative
controls cover mutation, missing dependencies, stale evidence, absent provenance,
missing witnesses, correspondence defects, undefined behavior, and scope leakage.

## Delivery

`C-DELIVERY`: the vendored framework's exact snapshot hashes and recorded origin
commit string are checked. Git history attribution is trusted; Git object membership
is not verified. The declared delivery tree is scanned for the frozen credential
filename and token patterns, and credential/catalogue ignore rules are checked.
Local credentials in `openai.local.json` and `secrets/` are excluded from the
source distribution and frozen snapshots.
This finite pattern check is not a claim that arbitrary possible secrets can be detected.

## IIS

`C-IIS`: frozen package and compatibility tests check deterministic archive
contents and manifest hashes, public/private file isolation, rejected package
inputs, explicit trusted origins behind a proxy, UTF-8 Java and key handling,
the packaged runtime, and the task launcher's process, configuration, and
sanitized failure behavior. The browser claim additionally checks real feedback
through a virtual application prefix. These assertions run on Linux. They do
not establish that IIS, Windows PowerShell 5.1, NTFS ACLs, or Task Scheduler ran
successfully on Windows. The supplied target acceptance script and actual
Windows deployment remain outside this offline closure.

The TCB includes language runtimes and toolchains, browser and Playwright, vendored
ACGN/Alloy code and jars, test interpretation, Linux namespaces, OS userland and
libraries, corpus classifications and oracle truth, hardware, and SHA-256. Browser-cache files are hashed as external trusted
inputs and checked again before final decision. Project inputs include all source,
configuration, tests, fixtures, vendor files, and installed `node_modules`, excluding
declared generated outputs and private credentials.

VERIFIED means only that all frozen finite obligations passed under the declared
TCB. No proofs are compiled, no implementation objects are mapped to proofs, and
no universal correctness, absence of bugs, or future deployment claim is made.
