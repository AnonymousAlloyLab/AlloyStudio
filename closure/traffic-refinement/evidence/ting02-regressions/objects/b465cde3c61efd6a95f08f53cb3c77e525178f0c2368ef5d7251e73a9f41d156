# Implementation bridges and obligation fulfillment

**All 24 original obligations, L00–L23, remain OPEN.** The implementation has
four registered, finite Boolean policy bridges. A current successful formal run
can mark those bridges and their supporting mathematical blocks `VERIFIED`;
full implementation closure remains `BLOCKED` / `NOT_ESTABLISHED`.

The authoritative definitions are the [obligation ledger](../closure/lean-obligations.json),
[bridge registry](../formal/bridges/registry.json), and a freshly generated
`scripts/verify_lean.py` report. This document describes the implemented surface;
it is not evidence that a particular checkout passed. B01 and B03 are the active
proof blocks. B03 preserves the Session model and supersedes B02's integration
binding; historical B02 evidence cannot certify the changed browser source.

## What crosses the implementation boundary

The runtime uses policy tables exported from Lean. The
[generator and checker](../scripts/bridge_policies.py) reproduces the exact
generated region in `web/app.js` and `engine/src/live/BridgePolicies.java`.
Both AST and canonical pool comparisons call the shared
`VerifiedPoolSelection` helper, which consumes the generated choice and
completion policies.

| Bridge | Exact admitted input | Proved Lean relationship | Runtime evidence |
| --- | --- | --- | --- |
| BR-FEEDBACK | Exactly 10 Boolean comparison results | `feedbackSuccess_eq_guard` equates the kernel to the strict Session feedback guard when the response is successful | All 1,024 Boolean vectors executed by the actual JavaScript kernel |
| BR-GUIDANCE | Exactly 13 Boolean comparison results | `guidanceSuccess_eq_acceptGuidance` equates the kernel to the Session guidance guard | All 8,192 Boolean vectors executed by the actual JavaScript kernel |
| BR-POOL-CHOOSE | `[hasBest, improves]`, exactly 2 Booleans | `choosePolicy_kernel`; `chooseScored_incumbent`; `scanPolicy_firstMinimum` | All 4 vectors executed by JavaScript and the generated Java class |
| BR-POOL-FINISH | `[nonempty, complete]`, exactly 2 Booleans | `finishPolicy_kernel`; `completePolicy_eq_argmin` | All 4 vectors executed by JavaScript and the generated Java class |

The checker generates **9,224 Lean theorem rows**: 1,024 + 8,192 + 4 + 4.
Each exported result is checked by the kernel with `by decide`, then audited
for an empty transitive axiom set. The actual JavaScript implementation is
executed for those same 9,224 valuations; Java executes the 8 pool policy
valuations. Each of two clean formal builds performs these checks. The generated
artifact and row inventories must agree across builds.

Lean separately proves exact arity rejection and finite Boolean-vector
coverage. These theorems concern Lean functions. The runtime row checks support
correspondence on the declared, finite Boolean domains under the host runtime
and enumeration trust below. They do not prove that arbitrary JavaScript values
are correctly decoded into those Booleans. Runtime malformed-input checks are
regression evidence, not an all-values decoder proof.

The pool model supplies a stronger *mathematical* result: the policy scan equals
the existing stable first-minimum selection, and complete evaluation equals
`completeArgmin`. Its positional certificate checker proves that an accepted
winner is the first minimum over the supplied ordered cost vector, with every
entry evaluated successfully. Empty vectors and missing costs are rejected.
This certificate theorem does not establish that a Java wire decoder or runtime
certificate producer supplies that exact vector.

## Evidence categories and trust

- **PROVED:** the frozen Lean theorem statements, their model relationships,
  finite-vector coverage and all exported policy rows, after the current kernel
  run and complete declaration audit succeed. No transitive axioms, native
  proof evaluation, placeholders or custom axioms are allowed.
- **CHECKED:** generated source identity, finite runtime valuation agreement,
  unique registry mappings, source hashes and repeatability of the two builds.
- **TESTED:** 5,462 exhaustive bounded pool fixtures, runtime comparison
  integration, browser behavior and policy-class mutation witnesses. These are
  finite implementation observations. Their actual pass status belongs to the
  corresponding current run logs.
- **TRUSTED:** the installed Lean kernel/library and execution tooling; export,
  generation, enumeration and audit code; JavaScript execution, JVM and `javac`;
  Boolean-vector encoding; Python, hashing, processes, filesystem, OS and
  hardware. Kernel rechecking the exported rows reduces reliance on the
  exporter's answers; it does not formally verify the generator or host runtimes.
- **OUT OF SCOPE:** universal Java-loop refinement, raw JavaScript comparison
  semantics, async capture/render timing, certificate/wire decoding, parser
  correspondence, DP optimality, canonical normalization, disclosure and the
  other unresolved original obligations.

Tests include a deliberately incorrect Java policy class on an isolated
classpath. Changing choice changes the observed winner in both metric paths;
changing completion prevents a successful result in both paths. This checks
that the live adapters consume the policy decisions. Source checks and these
mutation witnesses do not prove arbitrary loop execution or input completeness.

Review reports are workflow records. They do not replace kernel proof checking,
runtime checks or source binding. A finite bridge's `VERIFIED` status means only
that its registered checks passed for the frozen inputs under this explicit
trust boundary.

## Fulfillment by original obligation

Every row below is **OPEN end to end**. “Implemented support” identifies reusable
work, not discharge of the complete obligation. Existing implementation files
and regression tests are recorded separately from formal proof evidence.

| ID | Implemented support | Still required for end-to-end fulfillment |
| --- | --- | --- |
| L00 | Pinned offline toolchain, empty-axiom audit, frozen supporting blocks and closure-decision model | Freeze the complete implementation claim set, TCB and all semantic correspondences |
| L01 | RawAst ordered forests, node counts and postorder occurrence laws | All-constructor Java body extraction, label/child agreement and occurrence preservation |
| L02 | Existing raw label implementation | Injectivity of the actual Java label encoding under the metric's distinctions |
| L03 | Supporting postorder occurrence model | Actual index/leftmost/keyroot invariants, unique occurrences and initialized dependency coverage |
| L04 | Unit promotion/adoption/relabel semantics, script inverse and cost composition | Java edit correspondence, root/sentinel behavior and any required freshness guarantees |
| L05 | Unit edit model is available | Ordered mapping/script equivalence, both directions and equal costs |
| L06 | Existing distance implementation | Independent forest minimum specification and soundness/completeness over legal scripts |
| L07 | Existing Java DP and trace tables | Zhang–Shasha loop/table refinement to the independent semantic minimum |
| L08 | Existing runtime limits | Successful-result termination, budget and Java integer/long overflow refinement |
| L09 | Existing trace backtracking | Well-founded backtracking, minimal predecessor choices, exact edit emission and tie policy |
| L10 | Existing private replay | Java replay reconstruction with true promotion/adoption and complete mutation rejection |
| L11 | B01 complete-pool/first-minimum model; B03 policy scan/certificate theorems; finite Java policy bridge; bounded helper tests | Runtime iteration, decoding, arithmetic and evaluator correctness; exact complete-pool/oracle binding |
| L12 | Pinned Fast Rewrite implementation | Its actual normalization relation, termination and determinism; no substitution of another metric |
| L13 | Existing canonical component algorithms | Exact ordered/unordered cost specifications and optimality under their implemented constraints |
| L14 | Existing canonical trace and aggregate logic | Alignment, learner paths, unit costs and private replay; aggregate/atomic distinction |
| L15 | Existing occurrence/source-locator implementation | Origin preservation or precision loss through every relevant rewrite and renderer agreement |
| L16 | Existing source projection and UTF-16 handling | Range rebasing, surrogate/CRLF/tab boundaries, compaction and stale-span rejection |
| L17 | Existing public projection | Explicit approved-public-view serialization theorem and credential/hidden-operand exclusion |
| L18 | Finite guidance identity policy and its Session-model relationship | Evidence/operation IDs, prompt projection, response shape, decoding and redaction; prose stays untrusted |
| L19 | Existing bounded behavioral scoring | Arithmetic, rounding, facts and category partition; explicit solver/witness assumptions |
| L20 | Session identity model and counterexamples; strict guard equivalence; exhaustive finite JavaScript policy correspondence | Raw comparisons, decoding, async capture/render timing, serialized caches and other transitions |
| L21 | Existing packaging and dashboard allowlists | Public/private artifact inventory theorem and explicit external deployment trust |
| L22 | Four registered, uniquely mapped finite Boolean policy bridges | Full extraction, DP, replay, host-loop and wire/certificate correspondence |
| L23 | Closure-decision model; fail-closed source-bound proof/bridge verifier and overview | Full implementation-gate evidence collection/refinement and every prerequisite obligation |

The [detailed roadmap](lean-closure.md) and ledger retain each original statement,
implementation path, dependency and required evidence. The planned `raw-ast`
profile requires L00–L11 and L22–L23; `full-portal` requires all 24. Neither profile
is established by the finite policy bridges.

## Generate the current overview

After obtaining a report from the offline formal verifier:

```sh
python scripts/obligation_overview.py \
  --report build/lean-verification/your-run/report.json \
  --output build/obligation-overview
```

The renderer writes `overview.json` and `overview.md`. It includes all L00–L23,
their implementation paths, dependencies, supporting blocks and bridges,
remaining requirements and separate proof/bridge statuses. With no `--report`,
supporting evidence is `NOT_RUN`; the ledger alone cannot certify a bridge.

Before using a supplied report, the renderer checks its canonical input-root
hash and every registered input against the current file contents. Active block
manifests, proof sources, implementation sources, verifier/generator code,
registry, reviews and overview inputs must be bound. Missing, changed,
unregistered, out-of-root or symlinked paths prevent a current green status.
Only registered source paths are read; report-supplied paths cannot request
credential reads. Missing bridge claims, mapping counts, either clean build or
finite-domain row totals leave the bridges unresolved.

A successful render exits zero because it produced the requested overview;
read the JSON's statuses for verification results. Rendering is not a rerun of
Lean and does not authenticate arbitrary hand-written reports. The registered
verifier and evidence-production environment remain trusted. Future source
changes require a new verification run, and no green finite result promotes an
original obligation to `PROVED`.
