# Lean closure work and remaining obligations

This is an implementation plan, **not a completed proof package**. All 24 obligations
in [the machine-readable register](../closure/lean-obligations.json) are `OPEN`.
The portal's finite regression closure does not establish Lean theorem closure or
Java-to-Lean refinement. The alpha release can pass its regression gate while
formal closure remains `NOT_ESTABLISHED`.

Constructive supporting proofs now live in `formal/`, pinned to
`leanprover/lean4:v4.34.1`. B01 contains ordered occurrence forests, actual
single-node edit semantics, complete finite-pool selection, and a closure-decision
model. B03 retains B02's browser request/response models and constructed legacy
counterexamples, and adds fixed-arity guard equivalence, complete pool scans,
positional selection certificates and four finite implementation policies. The
browser guards and both metric selectors now consume generated policy kernels.
Their complete Boolean domains are checked against 9,224 kernel-proved rows,
with eight corresponding Java input cases. This is narrow policy correspondence,
**not a complete Java/Python/JavaScript semantic refinement**; it does not by
itself discharge the original end-to-end obligations below.

See [the runnable proof package](../formal/README.md), the frozen
[B01](../formal/blocks/B01.json) and [B03](../formal/blocks/B03.json) inventories,
and [the offline verifier](../scripts/verify_lean.py). The verifier separately
reports mathematical block status, finite bridge status and full implementation
closure. The latter remains `BLOCKED` until the remaining semantic correspondence
and all required proofs exist. B02 remains a historical frozen block. See
[the obligation fulfillment overview](implementation-bridges.md) for the four
bridge boundaries, supporting components and remaining work for every obligation.

The immediate proof target is the raw AST Zhang–Shasha metric and its private
trace checker. A later full-portal target also covers the canonical metric,
source positions, disclosure, behavior and request identity. Do not claim the
latter merely because the ordered-tree theorem compiles.

The register freezes two **planned** profiles: `raw-ast` requires L00–L11 and
L22–L23; `full-portal` requires all L00–L23. The first profile covers numerical
distance, trace replay, finite-pool selection and implementation correspondence,
not UI, confidentiality or Alloy semantic correctness. L00 must turn the chosen
profile into actual frozen claims before executing a formal closure. Neither
full profile is marked VERIFIED; the current verifier checks only its separately
frozen supporting proof blocks.

## Obligations

| ID | Obligation | Depends on |
| --- | --- | --- |
| L00 | Freeze definitions, toolchain and trust | — |
| L01 | Extract the selected raw predicate tree | L00 |
| L02 | Make raw AST labels unambiguous | L01 |
| L03 | Verify postorder, leftmost leaves and keyroots | L01 |
| L04 | Define unit node-edit semantics | L00 |
| L05 | Relate edit scripts to ordered mappings | L03, L04 |
| L06 | Prove the forest recurrence optimal | L04, L05 |
| L07 | Refine Zhang-Shasha tables to that recurrence | L03, L06 |
| L08 | Prove termination and bounded arithmetic | L07 |
| L09 | Verify backtracking and stable tie-breaking | L07, L08 |
| L10 | Verify private trace replay | L04, L09 |
| L11 | Select the nearest complete inclusive pool | L07 |
| L12 | Specify the actual Fast Rewrite normalization | L00, L01 |
| L13 | Verify canonical component costs | L12 |
| L14 | Verify canonical traces and honest aggregates | L13 |
| L15 | Preserve structural source identity | L01, L09, L14 |
| L16 | Verify UTF-16 ranges and display compaction | L15 |
| L17 | Verify public projection under explicit disclosure | L10, L11, L14, L16 |
| L18 | Verify Luna evidence and response boundaries | L17 |
| L19 | Verify bounded behavioral score and categories | L00 |
| L20 | Verify metric-aware browser state transitions | L16, L18 |
| L21 | Verify delivery and dashboard disclosure | L17 |
| L22 | Bind Lean statements to implementation objects | L08, L10, L11 |
| L23 | Make the formal closure gate fail closed | L00, L22 |

Each register entry supplies a planned Lean module/theorem name, the concrete
implementation files, dependencies and the required evidence. Names are planned
API names; they are not assertions that those declarations already exist.

## Lean modules and proof sequence

1. The Lean pin and Lake package are implemented with **no external package
   dependencies**. Lean 4.34.1 was selected before proof work on 2026-09-28.
   Proof commands use installed binaries inside a network-isolated namespace.
   The registered verifier hashes the installed compiler/library files; it never
   invokes elan, installs anything, or contacts a package registry.
2. In `RawAst.lean`, define a finite ordered tree with a tagged label and stable
   occurrence ID. Define the adapter independently of Java, constructor by
   constructor. The metric root is `Predicate.getBody()`, including ACGN's `Body`
   and parser `NOOP` wrappers. Declaration/header nodes outside the body are
   excluded. Do not normalize names, commute operands or erase wrappers; those
   changes would silently define another metric. Prove labels/children match the
   actual `DatasetConventions` functions on every admitted constructor.
3. In `Edits.lean`, work with forests so root deletion and child promotion are
   expressible. A deletion removes **one node** and splices its children into
   the same sibling position. Insertion is its inverse and adopts a contiguous
   range of siblings. Relabeling changes one label. Each costs one; matching
   costs zero. Intermediate trees need not be well-typed Alloy. This is why a
   tree edit is guidance, not an executable textual patch.
4. In `Mapping.lean`, define legal injective partial mappings with ancestor and
   left-to-right order preserved in both directions. Prove script/mapping cost
   equivalence. Then define an independent recursive forest minimum in
   `ForestDistance.lean`, with termination measured by combined node count.
   Prove both soundness and completeness of the recurrence. Merely unfolding
   its definition proves a recurrence equation, not optimality over scripts.
5. In `Index.lean`, prove postorder interval and leftmost-leaf lemmas. In
   `ZhangShasha.lean`, refine keyroot iteration and forest/subtree DP to that
   independent specification. Use immutable `Array` updates first; represent
   valid indices by `Fin`. Prove each read is initialized for the correct
   subtree interval and every iteration preserves the table invariant.
6. In `Limits.lean`, show successful Java-sized inputs fit the actual integer
   and work budgets. A bound failure is an error, not an alternative distance.
   Define equality of successful results only within the admitted domain.
7. In `Trace.lean`, prove each predecessor choice follows an optimal cell and
   decreases a measure. Preserve original learner occurrence IDs despite
   deletions. In `Replay.lean`, reconstruct the private target with real
   promotion/adoption and prove replay cost equals the final DP distance.
   Add explicit countermodels for subtree deletion, greedy sibling alignment,
   duplicate mapping indices and a missing edit.
8. In `Pool.lean`, implement a fold over a nonempty, completely validated pool.
   Prove first-minimum tie behavior and `selectedCost <= cost candidate` for
   every member. Prove the oracle is included. An invalid or resource-limited
   candidate invalidates the whole comparison, even after a zero-cost match.
   Trust in original CORRECT labels is explicit; this is not the nearest
   semantically correct predicate outside the finite pool.

The central theorem shapes should state an independent conclusion, schematically:

```text
zsDistance a b = minimumCostOfLegalNodeEditScripts a b
replay a (trace a b) = some b
sumCosts (trace a b) = zsDistance a b
successfulPoolResult pool = r -> forall c in pool, r.cost <= distance learner c
```

These are specification sketches, not compiled Lean declarations. Avoid circular
records with a field asserting `distance = minimum` and then projecting that field
as the advertised algorithm proof.

## Connecting proofs to the Java portal

A verified Lean implementation does not, by itself, verify the Java routines.
Choose one of these routes and freeze it in L22:

- Replace the relevant runtime computation with extracted verified code, then
  state the compiler/runtime and FFI trust assumptions explicitly; or
- Emit a private certificate from Java and verify it with a small independently
  proved checker. Include the labelled input trees, exact indices, a complete
  ordered mapping/edit script, selected pool membership and sufficient DP
  evidence to check the lower bound. Replay alone proves that a script works;
  it does **not** prove it is minimal. Checking only the winning candidate also
  does not prove that every pool member was evaluated.

For the checker route, use versioned bounded wire types, reject unknown tags,
nonfinite/overflowing counts, duplicate keys and trailing data. Prove decoder
soundness, range checks and `checkerAccepts -> theoremConclusion`. Bind the
certificate to the selected metric, learner hash, complete pool hash, adapter
version, implementation hashes and toolchain. Reference trees and proof witnesses
stay in the private backend; the public response contains only approved hints.
A hash reference alone is not a proof that the decoded certificate represents
its claimed AST. The extraction/adapter bridge remains a required obligation.

The inspected upstream
`docs/section3-repair-audit/formal/OrderedTreeEditDistance.lean` is useful prior
art: it defines a forest recurrence, proves its equation and empty-forest costs,
and checks two finite internal-node examples. It does not discharge the portal's
Java DP/backtracking refinement, complete-pool selection or disclosure obligations.
Its finite examples use native evaluation, whose trust must be audited separately.
No external ACGN checkout is needed to read or validate this obligation register.

## Canonical, location and educational boundaries

L12–L14 must formalize the **pinned Fast Rewrite implementation**. A theorem about
ACGN's separately developed certified e-graph quotient is not a theorem about
this portal's compatibility metric. Freeze the actual operator policies, binding
order, coherent/local alpha maps and unordered assignment constraints. Any semantic
preservation claim also needs a declared Alloy relational/temporal semantics and
proofs of the individual rewrite side conditions.

L15–L16 track occurrences rather than matching text. State a relation between the
original parser occurrence and normalized node. Duplicated or synthesized nodes
need explicit rules; conflicting merged origins must lose single-node precision.
Prove UTF-16 boundaries, predicate containment, exact canonical rendering and
whitespace-map composition. A selected source occurrence is not a proof that a
specific textual repair is correct.

L17 uses an explicit declassification policy. Distance, approved replacement
operators, pool counts, learner fragments and witness truth categories are intended
disclosures. A universal claim that changing the private oracle cannot change the
response would therefore be false. The useful deterministic theorem is: equal
approved public views produce equal public serializations; credentials and hidden
operands have no extra serialization channel. Public instances can allow a learner
to infer properties; inference prevention is not claimed.

L18 verifies complete operation/instance IDs, prompt projection and response
validation. Luna prose remains untrusted. Schema validation and phrase rejection
cannot prove that arbitrary generated prose always gives good guidance or can
never suggest a solution. L19 specifies the bounded Rewarder-style arithmetic,
module facts and category partition; it does not establish unbounded equivalence
or independent correctness of SAT/UNSAT results. L20 models metric changes as
state transitions, including caches, revision guards and aborted requests.

## Evidence and the final gate

The separate `scripts/verify_lean.py` checks frozen mathematical blocks; the current
Python test verifier is not a proof checker. A full implementation verifier's
registered inputs must include every Lean source,
lockfile, theorem inventory, statement hash, axiom policy, bridge map, Java adapter
and certificate schema. Retain zero-proof status until all required obligations
for the selected profile have evidence.

For every project theorem, including private and generated declarations, collect
the exact elaborated type and its transitive axiom dependencies. The implemented
policy accepts **no axiom dependencies**, including `propext`, `Classical.choice`,
`Quot.sound` or `sorryAx`. Native proof evaluation and custom axioms are forbidden.
The strict build disables automatically generated injectivity lemmas, some of
which otherwise depend on extensionality. A clean `lake build` is insufficient.
Lean documents axiom dependency inspection and the extra compiler trust introduced
by native proofs in its [axiom reference](https://lean-lang.org/doc/reference/4.34.1/Axioms/).

The formal run must then:

1. Freeze the claim/profile selection, source and verifier hashes, TCB and complete
   implementation-to-theorem mapping. Prove no required implementation object is
   unmapped or mapped ambiguously.
2. Run the pinned Lean build in two isolated environments with dependencies already
   resolved; do not fetch unpinned packages during verification.
3. Run theorem/axiom inventory checks, certificate positive witnesses, and negative
   controls for wrong labels, child order, costs, pool omissions, paths and hashes.
4. Compare the declared deterministic artifacts and evidence identities. Bind every
   theorem and witness to the same frozen root; never inherit a previous green run.
5. Produce only `VERIFIED`, `BLOCKED` or `INFRASTRUCTURE_FAILURE`. Any required OPEN
   obligation, failed proof, missing bridge or undeclared assumption blocks formal
   closure. Dashboard status must say whether it reports regression closure or
   formal closure.

Lean's installed kernel/library, registered audit/verifier code, operating
system, hardware and hashes remain explicit execution trust boundaries. The
theorem axiom set is empty; that does not eliminate those physical/tooling trust
boundaries. Alloy parser and solver correspondence is not silently assumed.
Actual IIS/Cloudflare behavior, GitHub availability, quota and Luna output quality
remain deployment/runtime evidence unless separately formalized. No finite Lean
plan or test suite establishes universal bug freedom.
