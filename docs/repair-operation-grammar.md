# Compact repair-operation grammar

This specification is written before the implementation. The HTTP adapter adds
`repairGrammar` and `repairSequence` to successful compiled-predicate feedback.
Canonical form remains the default distance; the same representation supports raw
AST feedback. Existing distances, operation order, costs, source/canonical
locators, and the legacy `operations` array are preserved.

## Contract

1. The grammar is a finite, versioned dictionary of actions, subject categories,
   operator names, components, and learner roles. A sequence row contains dictionary
   IDs, its positive cost, and an `operationIndex` referring to the existing public
   operation and its exact structural locator. No text matching or new node
   selection occurs during encoding.
2. Actions are add (`a`), remove (`d`), replace (`r`, including legacy `modify`),
   and grouped adjustment (`g`, for an existing aggregate). Subjects cover logical
   control, comparison, relational, multiplicity/cardinality, arithmetic, temporal,
   variable declarations/bindings, variable uses, relation/signature references,
   constants, predicate/function calls, general operators, general expression
   structure, and grouped changes. Operator ambiguity remains explicit: `+`, `-`,
   and `*` cannot determine integer arithmetic versus relation/set operations by
   token alone and therefore use the general operator subject.
3. Every emitted legacy operation has exactly one sequence row in the same order.
   Sum of sequence costs equals the reported distance. Aggregates retain their
   original cost and grouped status; they are never invented as atomic steps.
   Zero distance produces an empty sequence.
4. Binding declarations and variable uses have opaque occurrence slots (`b0`,
   `b1`, ...). A slot is associated with the already selected structural path;
   it is not a declaration-resolution claim. No learner or target binding name,
   source fragment, description, expression, value, call name, or free-form path
   enters the compact sequence. Existing learner snippets/locators remain in the
   legacy representation for highlighting and explanations.
5. Source and replacement operators enter the grammar only through the existing
   finite operator allowlist. Replacement names are allowed only for replace/
   modify. Insertions retain the source node as an insertion anchor: its operator
   does not identify a hidden inserted node. An inserted binding may be identified
   by the public quantifier component; an inserted temporal condition by the
   public temporal component; other insertion subjects remain general structure.
   Reference expressions, inserted operands, target binding names and target
   values stay hidden.
6. Components and roles use finite IDs. Structural paths are whitelisted numeric
   paths already used by the engine. Sequence rows carry an `operationIndex`
   rather than copying locator text or replacing any UTF-16 ranges.
7. Unknown or malformed operation kinds/components, nonpositive costs, invalid
   aggregate flags, excessive/malformed paths, disallowed operator hints, or a
   cost mismatch make the *whole* encoding unavailable with an empty sequence.
   Legacy feedback and its diagnostics remain unchanged. Partial or unverified
   encodings are never represented as complete.
8. Grammar projection is deterministic and linear in the bounded operation trace,
   introduces no JVM/provider call, and creates no per-edit files. The existing
   feedback/evidence caches retain the sequence with its response. This is a
   dictionary encoding of redacted guidance, not an executable source patch or a
   proof that the edit route is a complete correction.

The public schema is `repairGrammar = {version, status, actions, subjects,
operators, components, roles}` and `repairSequence = [{action, subject,
component, role, cost, operationIndex, path?, sourceOperator?,
replacementOperator?, bindingSlot?}]`. Dictionary keys are stable within version
1. A future change to existing IDs requires a grammar version change.

For example, one public `no`-to-`some` operator hint is stored as:

```json
{"action":"r","subject":"m","component":"m","role":"a","cost":1,"operationIndex":0,"path":"normalForm[0].matrix.child[1]","sourceOperator":"o18","replacementOperator":"o17"}
```

The dictionaries decode `r` as replace, subject `m` as a multiplicity/cardinality
operator, component `m` as matrix, `o18` as `no` and `o17` as `some`. Clicking this
row still selects the source/canonical ranges in legacy `operations[0]`. No
operand or target expression is present. A variable declaration row uses subject
`b` and an opaque `bindingSlot`, never its spelling.

Result-cache and evidence-pin retention include these fields in their existing
JSON byte accounting. The response cap, cache byte budget, evidence expiry and
body/metric/generation/channel identities remain unchanged. Luna continues to
project the learner-only legacy operations, so adding this encoding introduces
no extra provider request or prompt disclosure.

## Validation obligations

Tests must cover every category/action/operator, deterministic ordering and cost
preservation, opaque binding slots, renamed-binding non-disclosure, insertion
anchors, aggregates, repeated structural occurrences, invalid-input rejection,
zero distance, AST compatibility, and the actual HTTP adapter. Existing locator
projection and Luna traces must continue to pass because their data is unchanged.

The dedicated regression module is `tests/test_repair_grammar.py`. It also checks
the actual compiled JVM adapter, deep-copy/cache accounting and exact-identity
evidence pins. A saved-response audit in
`build/repair-grammar-validation/historical-corpus.json` successfully encoded all
177 successful canonical responses (1,603 operations) and 177 successful AST
responses (1,532 operations) in the existing 181-exercise hint-quality corpus.
The four non-success responses per metric are retained as historical unsupported
results. This is an encoding audit of saved responses, not a new benchmark run or
a claim that unsupported comparisons now succeed.
