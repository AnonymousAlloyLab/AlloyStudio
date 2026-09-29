# SQLite specification adversarial review

This is a design review before persistence implementation, not a security proof
or completed feature verification. The inputs reviewed were:

| Input | SHA-256 |
| --- | --- |
| `docs/sqlite-security-spec.md` version 1 | `a5e63ef8ab119cad9fb4e83e46a48b17e4e0710397fac03433f6e1576ff7a472` |
| `closure/sqlite-spec.json` version 1 | `75f2bc3d4a9b67b43329a0674394d2766f24716b321b3d3f56bf86700dc4ebd5` |
| Existing `exercises/correct-pools.json` | `7c98076f11f2641d70121147211227cb9a2da4c5626951c72c6addf6b9062eb3` |

The initial review identified R1–R5 below; the final specification and register
at the hashes above incorporate all five tightenings, including the user's
subsequent requirement for Alloy API equivalence checks. The constructed cases
and required behavior are retained here as implementation test obligations.
Each finding is **resolved at specification level**; executable implementation
verification is still pending. No application persistence implementation preceded
this completed specification review.

## Findings and their resolutions

### R1: preserve every oracle and the original candidate order

The initial statement that there are 7,550 candidates was incomplete. Counting the actual
arrays gives **181 exercises, 181 pools, 7,550 correct-student candidates and 181
explicit oracle candidates: 7,731 candidates total**. Every existing pool places
its oracle last. A migration that treats 7,550 as the total can omit all oracles
while matching that count.

Keep the primary-oracle identity separate from candidate ordinal. For example,
an existing pool `[correct A, oracle B]` where both distances are 1 must continue
selecting A on its first tie; moving the oracle first changes the result even
though both bodies are retained. The new import format may order its oracle list
first, but that must not reorder migrated pools.

Specification disposition: **RESOLVED**. The revised contract requires the exact
counts and byte-exact ordered candidate parity,
including kinds, provenance, all explicit oracles and a separate primary-oracle
selection. The witness counts are recorded in
`build/sqlean-probes/spec-counts.json`.

### R2: compilation is insufficient to enforce a predicate-body boundary

Constructed input, supplied as a single oracle body:

```alloy
some Node }
fact injected { no Node }
pred extra { some Node
```

Wrapping this in `sig Node {}\npred inv { BODY }` compiles successfully. Running
the actual `live.LiveFeedback` engine against ordinary `some Node` returned
`status: ok` and distance 0. The body escaped its predicate and introduced a
module fact and another declaration. The offline executable witness is recorded
in `build/sqlean-probes/body-boundary-witness.json`.

Specification disposition: **RESOLVED**. The revised contract requires exact body
containment, independently of compilation, for
every oracle, additional correct body and starter. Match the supplied predicate
header/name and balanced body boundary using the existing Alloy-aware extraction
logic or a parser-derived source boundary. Strings and comments must not create
false delimiter matches. Reject any supplied body that changes the fixed prefix,
suffix, declaration set or module facts. Add this exact witness as a rejected
import fixture and preserve the prior database.

### R3: mandatory bounded equivalence must reject counterexamples and vacuity

The initial specification trusted correctness labels after compilation, which
did not satisfy the added user requirement. `some Node` and `no Node` both
compile in `sig Node {}` but are not equivalent. They must not be accepted as
two solutions merely because each parses.

The equivalence query must compare each additional oracle and correct body to
the primary under the same preserved facts, with an explicit common scope and
options. A SAT counterexample rejects the import. Timeout, solver failure,
unsupported constructs and unknown results also reject it; none may be treated
as equivalence.

There is a concrete vacuity case: with
`fact impossible { some Node and no Node }`, both differing predicates pass an
equivalence query because no instance satisfies the facts. Require a separate
satisfiability check of the module facts alone and reject this inconsistent
context. This is distinct from a primary predicate having no satisfying instance
under otherwise satisfiable facts, which can be a valid predicate.

Facts must actually be included: with `fact { one Node }`, `some Node` and
`one Node` are equivalent in that context. This provides a positive fixture that
would fail if facts were accidentally omitted.

The result remains bounded. `#Node > 5` and `some Node and no Node` agree within
scope 5 but differ at scope 6. Store the scope, integer bitwidth, maximum sequence
length, engine identity and result; describe the result as bounded equivalence,
never an unrestricted theorem. Define whether the migration retains historical
administrator labels without rechecking semantics: do not silently make a new
semantic guarantee about all 7,731 existing candidates.

Specification disposition: **RESOLVED**. The revised specification, trust/exclusion
text and SDB-04 register require mandatory bounded equivalence and the positive,
negative and failure cases above. The selected bounds are scope 1–8 (default 5),
bitwidth 5, maximum sequence length equal to scope and trace length 1–10. A semantic rejection must occur before publication and preserve the prior
database.

### R4: concrete limits and parameter types must be fixed

The initial "bound sizes and lock waits" and "declared type" language left
material implementation choices unregistered. Concrete examples are an integer parameter `True` (Python
considers it an `int` via `isinstance`), `2**63` (not a SQLite signed integer), a
text value containing a lone UTF-16 surrogate, an import containing 4,097 correct
bodies when the live engine's limit is 4,096, and an import held behind another
writer indefinitely.

Specification disposition: **RESOLVED**. The revised contract enumerates the exact
accepted primitive types and signed integer range; reject booleans in integer slots and non-UTF-8 strings before execution.
Register finite file, field, candidate, database and query limits plus lock and
Alloy timeouts. Reject imports whose expanded engine request exceeds the live
engine's size limit. Include limit-minus-one, limit and limit-plus-one fixtures.
The current corpus fits 338 candidates in its largest pool; its largest body is
328 UTF-8 bytes, its pool JSON is 13,833,091 bytes and its catalogue is 682,155
bytes. Limits must admit the existing data without making the runtime's stricter
bounds unreachable or inconsistent.

### R5: validate the full schema and read one transactional snapshot

Checking only `table_info` and a version integer is insufficient. A database with
the expected columns plus an `AFTER INSERT` trigger that rewrites an exercise's
title can retain valid foreign keys and ordinals while changing an import's
contents. Schema verification must include unexpected triggers, views, indexes
and virtual tables as well as declared columns and constraints; accepted schema
objects and fixed connection controls must be enumerated.

The snapshot requirement must cover the loader, not just package backups. An
administrator can commit a new exercise F between a loader's catalogue SELECT
and its solution SELECT, producing an old exercise list and new solution list.
Both SELECTs are individually committed reads but together are inconsistent.

Specification disposition: **RESOLVED**. The revised contract requires an explicit
single read transaction for validation and loading, rejecting unexpected schema
objects before data queries. Test an extra
trigger, a modified constraint and a concurrent administrative commit between
the two reads. Use SQLite's consistent backup API for packaging, followed by
validation of the copied snapshot.

## Boundaries accepted with explicit tests

The sentinel design can preserve query structure if implemented exactly as
specified. Test complete literal tokens, not substrings: a sentinel embedded in
another string, a quoted identifier, a comment, a repeated occurrence, a wrong
type or a slot-order permutation must fail generation. A runtime value such as
`x'); DELETE FROM exercises; --` must round-trip as text through the registered
prepared statement without altering any other row. No generic SQL argument
should exist on the adapter or private import interface.

The actual parser accepts schema-checked SELECT/INSERT/UPDATE/DELETE and rejects
`?`, `:id`, CREATE TABLE, PRAGMA and BEGIN; these probes ran in a network namespace
and are recorded in `build/sqlean-probes/report.json`. Keeping fixed DDL and
connection controls as explicitly enumerated trusted operations is an honest
boundary. They must not be reported as SQLeanParser-certified operations. Verify
generation with the actual pinned executable; hash agreement alone is not parser
execution evidence.

The proposed read-only web process and private host CLI avoid introducing a new
public administration surface. Acceptance must still request the database,
`-wal`, `-shm`, journal, query registry, AST artifacts and import paths through
the HTTP server and inspect the public IIS tree. A package containing both the
database and legacy JSON must start from the database and never overwrite a new
administrator exercise with the legacy seed. A corrupt existing database must
fail rather than use that seed. Test linked ancestors as well as a directly
linked output, and preserve an existing database on every failed publication.

The navigation contract is sufficiently precise: filtered order, no wrapping,
disabled loading/boundary buttons, one existing selection path, draft preservation
and stale-response rejection. Tests should include a selected exercise removed
by a filter, zero search results, rapid Next/Previous actions around a delayed
detail response, and a mobile viewport.

## Review outcome

**READY FOR IMPLEMENTATION against the hashes above.** No constructed design
blocker remains after the five specification revisions. This conclusion permits
implementation; it does not verify that any implementation meets the contract.
The registered tests and frozen verification evidence must establish that later.
This review did not modify application persistence code, execute migrations or
change the upstream parser. No Internet access or Lean toolchain installation
was used.
