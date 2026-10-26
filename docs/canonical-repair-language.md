# Canonical repair language

AlloyStudio's canonical repair language is a small positional CRUD notation.
Every statement names one address of the form **`[temporal, quantifier, [inside...]]`**.
It describes guidance against an immutable learner snapshot. It does not execute
hidden oracle code or automatically change the student's program.

This is the **ASRL/1 specification**, layered over the deployed
`repairGrammar.version = 1` dictionary. Distance, operation order and that compact
dictionary remain unchanged. Version 0.0.8-alpha adds a learner structural export
and [per-step retrieval for AI prompts](structural-guidance-rag.md); it does not
expose a source-edit execution endpoint. The reference parser and positional
resolver are in [`repair_language.py`](../repair_language.py); the formal syntax
is in [`canonical-repair-language.ebnf`](canonical-repair-language.ebnf).

## Four statements

```text
READ [0,M,[1,0]];
UPDATE MULTIPLICITY [0,M,[1,0]] TO "some";
DELETE CONTROL [0,M,[2]];
CREATE STRUCTURE NEAR [0,M,[1]];
CREATE BINDING AT [0,2,[]];
UPDATE BINDING [0,1,[]] TO ?;
UPDATE TEMPORAL [T,_,[0,1]] TO "always";
```

These are syntax illustrations, not one validated repair of a particular model.
`?` hides the replacement identity/expression. A quoted replacement is one
allowlisted operator, never an Alloy expression, identifier or binding name.
CREATE always leaves the new expression and its names hidden.

- **CREATE**: a missing construct near a learner anchor, or a missing binding
  at a quantifier-list gap. It does not assert that the anchor is the insertion
  destination or that one new expression alone completes the repair.
- **READ**: inspect the category and optional operator of exactly one learner
  occurrence. It does not change the snapshot or contribute edit distance.
- **UPDATE**: change the selected occurrence's operator or concealed identity.
  The category describes the existing construct. `TO ?` includes changes whose
  exact target cannot be disclosed or whose details the original trace omits.
- **DELETE**: remove the selected canonical occurrence according to the metric's
  trace. One statement must not be interpreted as an arbitrary subtree deletion.

CREATE, UPDATE and DELETE each retain one atomic edit unit. READ costs zero.
An aggregate cost has no invented point address and is not a fifth CRUD verb.

## The three-coordinate address

All numeric coordinates are **zero based** and refer to the same original learner
snapshot throughout a program. They never mean “the first matching text”.

1. **`[t,M,p]` — matrix occurrence.** `t` selects `normalizedForms[t]`, the
   learner's indexed temporal phase/normal form. `M` selects its quantifier-free
   matrix. `p = [i0,...,ik]` follows that matrix's ordered child edges. `[]`
   selects the matrix root. A shared e-graph object reached through two paths
   has two occurrences; the chosen path identifies only one of them.
2. **`[t,q,[]]` — quantifier occurrence.** `q` selects a declaration in that
   phase's **metric quantifier order**, which can differ from printed order.
   No inner path is accepted for a prefix binding in this version. For
   `CREATE BINDING AT`, `q` instead selects the gap before declaration `q`;
   `q = number-of-bindings` is the final gap. READ/UPDATE/DELETE require an
   existing declaration and cannot select that final gap.
3. **`[T,_,p]` — temporal-skeleton occurrence.** `T` selects the temporal tree
   used by the distance metric, `_` means no quantifier selection, and `p`
   follows its child edges. `[]` is the skeleton root. This is a separate
   namespace: binary temporal operators can merge left/right phase records,
   so a normal-form index must not be treated as a temporal-tree node index.

Examples of exact correspondence:

```text
normalForm[2].matrix.child[1].child[0]  <->  [2,M,[1,0]]
normalForm[2].quantifier[3]            <->  [2,3,[]]
temporal.child[0].child[1]             <->  [T,_,[0,1]]
```

The last correspondence requires an authoritative, uncollapsed temporal path.
The public trace preserves numeric `temporal.child[...]` segments after masking
labels. A bare `temporal` label is insufficient evidence of an exact occurrence
and is not upgraded to `[T,_,[]]` by the adapter. Temporal insertion paths can
denote target-side destinations, so CREATE also requires explicit learner-anchor
evidence; a coincidentally existing learner path does not establish that role.

The coordinates are structural addresses, not raw-code character offsets.
`operationIndex` remains the join to the existing raw/canonical locators. An
exact canonical occurrence can still have an unavailable or merely related raw
source location. No precision is gained by changing the address notation.

## Concrete categories

The category alphabet preserves the current dictionary's thirteen concrete
subjects. Its separate `GROUP` accounting subject is excluded from CRUD:

- `CONTROL` (`c`): `and`, `or`, `not`, `implies`, `iff`, conditional structure.
- `COMPARISON` (`p`): equality, inequality, membership, order and disjointness.
- `RELATION` (`r`): join, product, restriction, intersection, override,
  transpose, closure and multiplicity-qualified arrows.
- `MULTIPLICITY` (`m`): multiplicity/cardinality operators outside a prefix
  declaration, including `some`, `no`, `one`, `lone`, `set`, `exactly`, `#`.
- `ARITHMETIC` (`a`): unambiguously classified integer operations and casts.
- `TEMPORAL` (`t`): past/future operators, prime, or a temporal condition.
- `BINDING` (`b`): a declaration, including its quantification, domain,
  cardinality or disjointness information where the original trace groups them.
- `VARIABLE_USE` (`v`): one bound-variable occurrence, with its name masked.
- `REFERENCE` (`n`): one relation/signature reference, with its name masked.
- `CONSTANT` (`k`): one constant occurrence, with its value masked.
- `CALL` (`f`): one predicate/function call, with its name masked.
- `OPERATOR` (`o`): an operator whose finer family is unresolved.
- `STRUCTURE` (`s`): an expression or missing construct without a more specific
  disclosed category.

`+`, `-` and `*` remain `OPERATOR` when only the current untyped token is known.
Do not guess arithmetic versus relational meaning. A quantifier-component edit
is `BINDING`, even if its allowed operator is `all` or `some`. The complete
operator alphabet is the existing 72-token `repair_grammar.OPERATOR_NAMES`.
No arbitrary identifiers or Alloy snippets are accepted as operators.

For CREATE, an existing anchor's category/operator does not establish the hidden
new node's category. The current adapter retains `STRUCTURE` for matrix
insertions and `BINDING` for prefix insertions. More specific CREATE categories
require separately authorized public evidence; the parser does not infer it.
The inspection result describes the selected learner **anchor**; the CREATE
statement separately records the disclosed category of the missing construct.

## Snapshot and program semantics

A program binds its positions to an issuer-supplied snapshot identifier:

```text
ON "<64 lowercase hexadecimal characters>" {
    READ [0,M,[1]];
    UPDATE MULTIPLICITY [0,M,[1]] TO "some";
}
```

The placeholder in the header above is explanatory, not valid input. A caller
must supply the real identifier of its learner snapshot. The identifier must
be bound by the host to the exercise, learner revision and canonicalization
version. Matching a string alone is not authentication or a freshness proof.
The reference implementation refuses a program whose ID differs from its input
snapshot; it does not issue deployment credentials or add an HTTP endpoint.

Let a snapshot be `S = (id, temporalTree, phases)`, with each phase containing an
ordered binding list and a matrix occurrence tree. Define `walk(node, []) = node`
and `walk(node, i::p) = walk(node.children[i], p)` when that edge exists;
otherwise resolution fails. Then:

```text
resolve(S, [t,M,p]) = walk(S.phases[t].matrix, p)
resolve(S, [t,q,[]]) = S.phases[t].bindings[q]
resolve(S, [T,_,p]) = walk(S.temporalTree, p)
```

Each address resolves to at most one occurrence. Bounds failures do not fall back
to text matching, an enclosing node or another phase. The binding CREATE check
uses `0 <= q <= len(bindings)` rather than node lookup; its result is a gap,
not a fabricated existing declaration.

READ returns a public descriptor `(address, category, operator?)`. All four
statements leave `S` unchanged in the reference interpreter: C/U/D produce
validated **guidance descriptors**, not source patches. Applying a concealed
target, handling binding scope and maintaining Alloy types require additional
private edit semantics and checks. They are not implied by parsing this language.

Thus addresses do not shift after an earlier DELETE or CREATE. Multiple hints
can share an anchor without becoming duplicate edits. A later learner revision
requires a newly bound program; old coordinates cannot simply be replayed on it.

## Validation and legacy projection

The reference accepts at most 4,096 statements, 64 child edges per address,
eight decimal digits per numeric index, and 1 MiB of UTF-8 program text.
Booleans, negatives, fractions, wildcards, free identifiers, arbitrary expressions,
unknown categories/operators, and trailing unparsed text are rejected.
Validation completes before any read result or guidance batch is returned.

READ/UPDATE/DELETE require an existing occurrence. UPDATE/DELETE require the
declared category to match the selected public node. CREATE uses NEAR for a
matrix/temporal anchor and AT only for a prefix-binding gap. A temporal-skeleton
edit must use `TEMPORAL`; a prefix edit must use `BINDING`. UPDATE may name only
one allowed replacement operator or `?`; no other verb accepts a replacement.

The reference legacy bridge reuses the existing v1 encoder, so name masking and
subject classification are preserved. Matrix and quantifier paths can be
translated directly. Noncanonical metrics, aggregates, collapsed temporal paths,
out-of-range occurrences and incompatible evidence remain explicitly unresolved.
UPDATE/DELETE require affected-node evidence; an insertion-anchor role cannot be
upgraded to an affected occurrence. An exported source operator must agree with
the selected snapshot node or anchor. Binding CREATE gaps have no existing node
whose operator could be checked.
Their original indexes and costs remain in a residual ledger. A partial projection
is never labelled a complete positional program, and an aggregate is never
expanded into fictitious unit edits. For a completed projection:

```text
number of CRUD edit statements = number of original atomic operations
order and operationIndex       = original trace order and indexes
sum(edit costs)                = recorded distance
```

For an incomplete projection, `positioned cost + residual cost = distance`.
READ statements do not enter either edit count. Changing display syntax alone
does not establish behavioral improvement, correctness, or raw-source patchability.

## Formal model and implementation boundary

The isolated Lean model in [`formal/repair_language`](../formal/repair_language)
formalizes the finite vocabulary, three-coordinate address shapes, public
projection, read-only lookup and cost laws. Its audit lists theorem dependencies.
It is a model-level specification: Python parsing, Java canonicalization,
snapshot issuance and production refinement remain separate implementation
obligations. The reference tests exercise those Python contracts and the direct
legacy-path mappings; they are not promoted to a proof of the production portal.

## Reproducing the checks

Run the reference tests and the unchanged deployed-dictionary regressions from
the repository root:

```sh
python3 -m unittest discover -s tests -p test_repair_language.py -v
python3 -m unittest discover -s tests -p test_repair_grammar.py -v
```

The 2026-10-23 run passed all **17 reference tests and 24 existing dictionary
tests**, including the real JVM canonical/AST trace adapter test. Cases cover
shared nodes reached through distinct paths, repeated labels, binding gaps,
namespace separation, stale snapshot IDs, contradictory source evidence,
payload masking, aggregate/residual cost conservation, syntax and resource bounds.
The JVM test skips when the engine is not built; it did not skip in this run.

The [Lean verification instructions](../formal/repair_language/README.md) reproduce
the offline model compile and exhaustive theorem audit. That run checked 28 named
properties and 15 examples, with no unfinished proofs or new axioms. Dependencies
were only Lean's standard `propext` and `Quot.sound`. These are model-level results,
with the production correspondence obligations stated above still separate.

The 2026-10-26 integration adds an eighteenth reference test: a temporal insertion
destination is not treated as a learner anchor merely because that path exists.
The production retrieval bridge conservatively leaves all current temporal
CREATE rows unresolved; matrix/binding CREATE and temporal UPDATE/DELETE use
the applicable structural evidence.
