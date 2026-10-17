# Canonical support for bundled Alloy library calls

## Scope and authority

The alpha.5 audit enumerates every `models/util/*.als` resource in the bundled
`vendor/acgn/lib/alloy.jar` (SHA-256
`ac0ee637172ed035a4ca43327d5e09c05990aa0d073887308e30f88c2471370c`).
It finds **175 public function/predicate signatures in 11 modules**, plus
**14 macros** in `util/time`. Before this repair, alpha.4 admitted 20 signatures;
the initial Sending Ether fix added `plus` and `minus`, and this audit adds the
remaining 153. The final finite ledger contains 175 signatures.

The ledger records module, member, expression/formula kind and declared argument
count independently of observed calls. Unknown modules or members, wrong kinds,
and wrong arities remain rejected. Successful Alloy parsing and typechecking
remain prerequisites. Nested names follow actual parsed module opens; their
identities retain parent module arguments. Production source-mapped calls also
match the exact parser `ExprCall` target. Legacy visitors without source maps
require an already referenced declaration in the client module; that compatibility
path establishes declaration authority rather than occurrence authenticity.
A client may use its own private imports, but cannot reach a library's private
imports through qualification. Library calls stay opaque, with ordered arguments and
distinct declaration identities; this change introduces no arithmetic or library
body rewrites and does not identify merely semantically equivalent call bodies.

## Complete declaration inventory

Each name below includes its argument count in parentheses. Overloads are listed
separately; `fun` denotes an expression and `pred` a formula. The source hashes
refer to the exact unmodified resources inside the pinned JAR.

### `util/boolean`

Functions: `Not(1)`, `And(2)`, `Or(2)`, `Xor(2)`, `Nand(2)`, `Nor(2)`, `subset_(2)`.

Predicates: `isTrue(1)`, `isFalse(1)`.

Resource SHA-256: `4fd45f9b876a6b308ffc1058bc8bcef86b164d6eac7df96d3f63ae747ac019ff`.

### `util/graph`

Functions: `roots(1)`, `leaves(1)`, `innerNodes(1)`.

Predicates: `undirected(1)`, `noSelfLoops(1)`, `weaklyConnected(1)`, `stronglyConnected(1)`, `rootedAt(2)`, `ring(1)`, `dag(1)`, `forest(1)`, `tree(1)`, `treeRootedAt(2)`.

Resource SHA-256: `5c32fdb9855bfc4d171f60e20eed517e3b2627f96bfe196ce7d14f334e4a7d0d`.

### `util/integer`

Functions: `add(2)`, `plus(2)`, `sub(2)`, `minus(2)`, `mul(2)`, `div(2)`, `rem(2)`, `negate(1)`, `signum(1)`, `int2elem(3)`, `elem2int(2)`, `max(0)`, `min(0)`, `next(0)`, `prev(0)`, `max(1)`, `min(1)`, `prevs(1)`, `nexts(1)`, `larger(2)`, `smaller(2)`.

Predicates: `eq(2)`, `gt(2)`, `lt(2)`, `gte(2)`, `lte(2)`, `zero(1)`, `pos(1)`, `neg(1)`, `nonpos(1)`, `nonneg(1)`.

Resource SHA-256: `d15e0dfd7b8150f2e77cdb9557196de90896163ba16a7611b3f8fd9cf9dff7eb`.

### `util/natural`

Functions: `inc(1)`, `dec(1)`, `add(2)`, `sub(2)`, `mul(2)`, `div(2)`, `max(1)`, `min(1)`.

Predicates: `gt(2)`, `lt(2)`, `gte(2)`, `lte(2)`.

Resource SHA-256: `5f276844e31623507fe1d51b91a62a5fa2886e107d5274c0114c861410ad28db`.

### `util/ordering`

Functions: `first(0)`, `last(0)`, `prev(0)`, `next(0)`, `prevs(1)`, `nexts(1)`, `larger(2)`, `smaller(2)`, `max(1)`, `min(1)`.

Predicates: `lt(2)`, `gt(2)`, `lte(2)`, `gte(2)`.

Resource SHA-256: `c7b35d80efdf8d33a967417578523b36a5f6dfdafe9b4a20862dee0b283402bf`.

### `util/relation`

Functions: `dom(1)`, `ran(1)`.

Predicates: `total(2)`, `functional(2)`, `function(2)`, `surjective(2)`, `injective(2)`, `bijective(2)`, `bijection(3)`, `reflexive(2)`, `irreflexive(1)`, `symmetric(1)`, `antisymmetric(1)`, `transitive(1)`, `acyclic(2)`, `complete(2)`, `preorder(2)`, `equivalence(2)`, `partialOrder(2)`, `totalOrder(2)`.

Resource SHA-256: `ba0f9561c26076e032c5894fa6291f705ec48d4c3160a9859dc30cc92511c885`.

### `util/seqrel`

Functions: `elems(1)`, `first(1)`, `last(1)`, `rest(1)`, `butlast(1)`, `inds(1)`, `lastIdx(1)`, `afterLastIdx(1)`, `idxOf(2)`, `lastIdxOf(2)`, `indsOf(2)`, `add(2)`, `setAt(3)`, `insert(3)`, `delete(2)`, `append(2)`, `subseq(3)`, `firstIdx(0)`, `finalIdx(0)`.

Predicates: `isSeq(1)`, `isEmpty(1)`, `hasDups(1)`.

Resource SHA-256: `902c0ace4a15f8a0aef750b61f3f6732b1c342fe8d69a58013daa0f0df6b01fa`.

### `util/sequence`

Functions: `at(2)`, `elems(1)`, `first(1)`, `last(1)`, `inds(1)`, `lastIdx(1)`, `afterLastIdx(1)`, `idxOf(2)`, `lastIdxOf(2)`, `indsOf(2)`, `firstIdx(0)`, `finalIdx(0)`.

Predicates: `noDuplicates(0)`, `allExist(0)`, `allExistNoDuplicates(0)`, `rest(2)`, `isEmpty(1)`, `hasDups(1)`, `startsWith(2)`, `add(3)`, `setAt(4)`, `insert(4)`, `copy(3)`, `append(3)`, `subseq(4)`.

These 25 declarations are recorded in the ledger, but the bundled module fails
Alloy typechecking before Canonical runs. They are not counted as executable
library coverage; see the limitation below.

Resource SHA-256: `7d133258d26f819f782250d2cfeceabc73c4b5bca414f00ed837cafdbea7bd09`.

### `util/sequniv`

Functions: `elems(1)`, `first(1)`, `last(1)`, `rest(1)`, `butlast(1)`, `inds(1)`, `lastIdx(1)`, `afterLastIdx(1)`, `idxOf(2)`, `lastIdxOf(2)`, `indsOf(2)`, `add(2)`, `setAt(3)`, `insert(3)`, `delete(2)`, `append(2)`, `subseq(3)`.

Predicates: `isSeq(1)`, `isEmpty(1)`, `hasDups(1)`.

Resource SHA-256: `bdc59358de5aa7f9e0822767c24768a7bece84b400b42756cb1fb13449742c75`.

### `util/ternary`

Functions: `dom(1)`, `ran(1)`, `mid(1)`, `select12(1)`, `select13(1)`, `select23(1)`, `flip12(1)`, `flip13(1)`, `flip23(1)`.

Resource SHA-256: `9eacb2c2b129900b1e87565f0150cd1002e761fb5e33e07a2f5cca147e0be432`.

### `util/time`

No function/predicate CALL declarations. Its 14 macros are expanded by Alloy
before the adapter receives the AST; the ordering calls they introduce use the
same authenticated import path.

Resource SHA-256: `90cdd902e857705b6469c082ca145057a44f096c0b35b4782de5f05c777f5808`.

## Bundled parser limitations

### Legacy `util/sequence` (all platforms)

The pinned `util/sequence` resource has three pre-existing cardinality/receiver
precedence errors, at lines 125, 138 and 154. For example, `#s.inds.add[1]` tries
to pass a set of `SeqIdx` to integer `add`, before taking its cardinality. The
intended arithmetic would require `(#s.inds).add[1]`. Loading this module fails
in the Alloy parser, independently of Canonical. Its 25 signatures remain
auditable, but are not presented as working runtime support. The JAR is unchanged.

Use Alloy's ordinary `seq` syntax (`util/sequniv`) or `util/seqrel` when appropriate;
these are separate representations and are not automatic replacements for an
existing `util/sequence` model. Uploaded code is never rewritten to hide the error.

### `util/natural` on Windows

The bundled parser constructs its implicit integer import with the host separator
(`util\integer` on Windows). Canonical now normalizes **module filename metadata**
to `/` before ledger lookup and identity construction, while preserving parser
call names and aliases. This fixes implicit integer calls, including Sending Ether.

There is a separate parser error before Canonical: `util/natural` line 21 explicitly
opens `util/integer as integer`, colliding with the implicit `util\integer` under
the same alias. The parser compares those filenames without separator normalization.
An explicitly aliased `open util/integer as integer` in a Windows user model has
the same problem; ordinary implicit integer calls and distinct aliases work.

Consequently, executable coverage is **150 signatures on Linux/macOS and 138 on
Windows**. Natural's 12 declarations are tested as parser-invalid on Windows and
must work on Linux/macOS; sequence's 25 remain parser-invalid everywhere. No
library source or JAR bytes are changed to hide either defect. The source census
suppresses the parser's automatic integer import using a test-only virtual
filename, retaining the exact bundled source bytes; runtime coverage independently
uses normal production parsing and asserts these platform-specific boundaries.

## Validation and deployment

Executable coverage and final test results are recorded in the
[alpha.5 release notes](releases/v0.0.7-alpha.5.md). The regression harness derives
its signature census from the bundled parser declarations, independently of the
production ledger. Scratch belongs to `build/`; tests require no provider key.

```bash
python scripts/build_engine.py
python -m unittest tests.test_library_call_feedback tests.test_integer_call_feedback -v
```

The [Sending Ether regression](smart-contract-canonical-feedback.md) additionally
covers a parameterized temporal update, structural source locations, both hint
metrics, a bounded Alloy counterexample and persistent HTTP-worker reuse.

Rebuild and restart the backend to load the updated classes and discard old
worker/cache state. IIS users must deploy a fresh timestamped package using the
[upgrade guide](../deploy/iis/README.md). Keep private configs and the exercise
database. No private package or credential is attached to the public release.

Historical benchmark and proof artifacts retain their original hashes. These
tests extend supported syntax; they do not renew stale formal closure records.
