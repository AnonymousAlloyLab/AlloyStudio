# Bottom-up unused binding normalization

The shared pre-prenex normalization pass simplifies children before inspecting
their enclosing Boolean quantifier. Fast Rewrite and Certificate-Integrated IR
receive this same source normalization; Fast does not invoke full certification.

Liveness is lexical, not based on display names. Declaration groups are visited
right to left so surviving dependent domains and residual domain guards keep
earlier slots live. Removing an unused SET domain removes its false dependency
too. Tautologies and contradictions inside binding scopes are simplified before
the enclosing slot inventory is reduced. Early Boolean simplification is not
applied globally: doing so erased existing certificate idempotency receipts.

## Semantic guardrails

- ALL, SOME and NO may lose unused independent members of a shared domain when
  another retained member supplies a witness.
- Removing the last member preserves admissible-domain existence. For example,
  `all x:A | false` becomes `no A`, not `false`; `some x:A | true` becomes
  `some A`, not `true`.
- SET and LONE value domains admit an empty relation even on an empty carrier.
- ONE/LONE quantifier counting, disjointness, comprehension outputs, sums and
  callable interfaces have implicit semantic uses and are retained.
- Owners containing temporal REF nodes remain conservative: this matrix-only
  pass does not claim complete liveness through hidden phase references.
  This is an outstanding elimination/correspondence extension, not an excluded
  predicate or a successful proof of universal slot minimization.

## Source transport

Every reduction retains the original ordered lexical identities, occurrence
lineages, exact parser-authenticated types and retained original ordinals.
Surviving reduced declaration nodes carry an immutable reduction reference
through normalization clones. Altering their owner cannot bypass validation by
making a lineage lookup miss. Entire declarations removed by later semantic
normalization no longer require transport to a live slot.

Projection checks bind survivors to the actual selected prenex representative,
including shared branch representatives, or to the local binding coordinate.
Shared representatives retain every original lexical alias. Declaration
construction preserves individually allocated source nodes even for repeated
display names; body lookup still resolves to the last lexical binding.

## Proof and executable evidence

`docs/section3-repair-audit/formal/UnusedGroupedBindings.lean` contains the
quantifier laws, empty-domain/disjoint counterexamples, bottom-up Boolean laws,
empty-subset witnesses, and ordered declaration/shadowing facts. It is compiled
and inventoried by the existing governed rewrite audit. Its counting-product
and dependent-fiber lemmas are mathematical evidence only: the experimental
counting lowering was not accepted and is not enabled in Java.

The catalog links these laws to Java entry points and executable tests. This
is not a universal Java-to-Lean refinement proof. The 27 named theorem axiom
inventories use only explicit standard Lean foundations, without admitted
results or new axioms.

Focused tests cover both directions, original-slot projection, branch sharing,
malformed source inventories, owner corruption, slot reorder, and tampered
native trace distance. Independent Alloy SAT checks cover finite first-order
instances; exhaustive relation/subset enumeration and Lean laws cover the
unused SET-domain test that Alloy's higher-order negation backend cannot solve.

The retained original D8 references 49 and 259, in both directions, now produce
Fast/certified distance 17/17 with MATCHED native operations and successful
replay. Historical 19/18 and UNCHECKABLE receipts remain historical evidence,
not relabeled successes. The full4072/full66080 loop stays paused; these bounded
results do not establish full-corpus parity or public certification. The running
AlloyStudio frozen snapshot is unchanged; this change is in the ACGN checkout.
