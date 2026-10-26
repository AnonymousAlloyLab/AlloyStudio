# Canonical positional repair-language model

`RepairLanguage.lean` is an isolated Lean 4.34.1 model for the small canonical
CRUD language. It imports only the installed Lean standard library; proving and
checking require no network access. It does not change existing proofs or the
production engine.

## Address and command contract

Addresses have exactly three coordinates:

- `[i,M,[c0,...]]`: normal-form phase `i`, matrix occurrence reached by child indices.
- `[i,j,[]]`: binding `j` of phase `i`. Binding addresses cannot have child indices.
- `[T,_,[c0,...]]`: occurrence in the separate temporal skeleton.

The temporal skeleton and phase namespaces are disjoint. A normal-form index is
not treated as a temporal-node index. Natural-number coordinates are typed; this
model does not impose the production parser's decimal-width or serialized-length
bounds. In particular, phase indices, binding indices, child indices, and opaque
slot IDs use unbounded `Nat`; proving address shape does not prove their numeric
or depth limits, nor their existence in an actual canonical tree.

CREATE, UPDATE, and DELETE are repair descriptors attached to the immutable
original learner snapshot. They do **not** execute source changes. Binding CREATE
uses a gap; other CREATE descriptors identify a nearby existing anchor. UPDATE
and DELETE use an affected-node role. CREATE cannot carry a target operator.
Other non-UPDATE rows also cannot carry a target operator. Binding slots exist
exactly for binding/variable-use subjects. Operator IDs have type `Fin 72`, matching
the current append-only `repair_grammar.OPERATOR_NAMES` dictionary.

The address namespace also restricts the category: quantifier-component edits
must be BINDING, temporal-skeleton edits must be TEMPORAL, and matrix edits may
have any concrete category. GROUP is reserved for accounting summaries. Public
nodes in a valid READ snapshot obey the same namespace restrictions and cannot
have GROUP as their category.

Atomic rows have cost one and a well-shaped address. Accounting summaries have
positive weight, grouped/context roles, no address, and zero atomic weight. A
complete valid trace has consecutive operation IDs starting at zero, at most
4096 rows, and cost equal to the recorded distance (at most 1048576). A summary is
not one of the four positional CRUD commands and is never assigned a fabricated
tree location.

READ resolves exact addresses in an immutable finite association map of public
nodes. A well-formed snapshot has unique address keys. READ returns `none` if no
key exists, preserves the entire state, and contributes no edit cost. There is no
substring matching, SQL, mutable-address shifting, general filtering, or
pagination in this model.

## Proven properties

- Atomic valid rows have unit cost and valid address shape; summary rows are
  positive accounting entries with no atomic weight or position.
- CREATE exposes no target operator and has the address-appropriate anchor/gap
  role; UPDATE/DELETE have node roles; binding-slot/subject consistency holds.
- Atomic edit categories match their address namespace: quantifier edits are
  bindings, and temporal-skeleton edits are temporal. A READ from a valid snapshot
  returns only namespace-compatible concrete categories, never GROUP.
- Lookup returns an existing address/node pair; absent keys return `none`; in a
  unique-key snapshot every existing pair resolves to its corresponding node.
- READ preserves the entire snapshot, trace, and distance.
- Replacing hidden binding names/target expressions in private wrapper records
  cannot change the public projection. Projection preserves row count, ordered
  operation IDs, edit cost, and individual row validity.
- Inserting a READ anywhere in a descriptor program preserves its ordered edit
  descriptors and total weight.

The private wrapper stores an **already constructed public row** plus two hidden
strings. Its payload noninterference theorem is deliberately limited to changing
those strings while the public row stays fixed. It does not prove that a real
redactor chooses public categories/operators independently of private targets,
nor does it claim that intended public guidance reveals no information.

## Explicit implementation obligations outside this model

This is not a parser, engine, or production refinement proof. Parsing and rendering
the surface language, byte limits, snapshot-ID/hash binding and authentication,
mask allocation, mapping real canonical nodes into snapshots, checking that an
anchor exists, binding-gap bounds, and correct temporal-path export must be
validated by implementation tests. Address *shape* validity alone does not prove
that an address exists in a canonical tree. In particular, legacy exports that
only say `temporal` do not justify guessing a temporal child path.

The finite association map is an abstract lookup specification, not a tree walk.
Its unique keys do not establish that those keys were authoritatively derived
from the production canonical tree. The actual tree-to-address correspondence,
gap existence, and production resolver refinement remain outside these proofs.

No theorem applies CRUD changes to an Alloy source, proves repair equivalence,
proves edit optimality, or establishes full source-patch closure. Summary rows and
READs are separated from atomic operations so accounting cannot silently claim
those stronger properties.

## Offline bounded verification

From the repository root:

```sh
mkdir -p build/repair-language-lean
python3 scripts/lean_offline.py --cwd . -- -j 1 -M 1024 -o build/repair-language-lean/RepairLanguage.olean formal/repair_language/RepairLanguage.lean
python3 scripts/lean_offline.py --cwd . --lean-path build/repair-language-lean -- -j 1 -M 2048 formal/repair_language/Audit.lean > build/repair-language-lean/audit.log
```

`Audit.lean` prints representative theorem axiom sets and enumerates **all** model
theorem declarations, including generated declarations. The anonymous examples
are kernel-checked during model compilation, rather than exported as declarations.
The audit rejects project axioms,
unclosed metavariables, `sorryAx`, and dependencies outside Lean's standard logical
axioms (`propext`, `Quot.sound`, `Classical.choice`). The emitted sets identify which
of these standard dependencies are actually used; permitting the standard set is
not an assertion that every member occurs. There are no new axioms or unfinished
proofs in the source.

The 2026-10-23 run passed with 28 named properties and 15 concrete positive or
negative examples. The exhaustive audit visited 180 theorem declarations,
including generated declarations, and reported only `propext` and `Quot.sound` as
dependencies; `Classical.choice` was not used. The model compiler was capped at
1024 MiB and the audit compiler at 2048 MiB, each with one Lean worker. The first
audit attempts with lower memory caps failed for resource limits; they were not
accepted as proof evidence.

Linux CI checks this model and its exhaustive audit in the network-isolated
runner, separately from historical proof replays and current production tests.
