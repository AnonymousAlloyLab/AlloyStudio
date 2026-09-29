# SQLite exercise storage: security specification

Specification version: 1. The later [authenticated import contract](admin-security-spec.md)
supersedes the CLI-only and no-hot-reload scope restrictions below for its
explicitly authenticated routes; all learner projection and SQL boundaries remain.
 Implementation must follow this specification and its
acceptance register in `closure/sqlite-spec.json`. This document is a design and
test contract, not a claim of completed security verification. Persistence work
starts only after the specification has received an independent adversarial
review. Review findings must identify a concrete input or transition; tests and
executable evidence, not review opinions, determine acceptance.

## Authority and scope

The private SQLite database is authoritative for exercises, predicate bodies,
ordered correct pools and one or more oracle solutions. The checked-in seed
database must reproduce the existing 181 exercises and all 7,731 candidates
(7,550 classified correct-student entries and 181 oracle entries), including
their original order, environments and provenance. Existing pools keep their
oracle last; primary-oracle identity must be independent of pool position.
Legacy JSON files
are migration inputs and regression witnesses, not a second live datastore.
An existing invalid database must fail startup; it must never silently fall back
to JSON or replace administrator changes with seed data.

Predicates and model source occupy ordinary SQLite text columns, not serialized
JSON records. Non-predicate provenance metadata may retain JSON encoding.
Schema version and exact schema shape are checked before use, including every
table, index, trigger, view and virtual-table declaration. Unexpected objects
are rejected, including triggers that could rewrite an imported row. Deployment archives
contain the private database and the code/query artifacts needed to operate it;
neither an ACGN nor an SQLeanParser checkout is required on a deployment machine.

The web process reads the database through a read-only connection. Updates are
performed only by a private CLI and Python service interface on the host. No
administration HTTP route, browser oracle editor, upload endpoint or arbitrary
SQL interface is introduced. An authenticated administration page is deferred.
After an administrator commits changes, restart the web process to load one
consistent snapshot and invalidate feedback caches. All metadata/exercise/
solution reads belong to one explicit read transaction, including validation;
a concurrent commit cannot mix catalogue generations. Hot reload is out of scope.

## Threat model and disclosure

Learner requests, exercise IDs, predicate bodies and browser storage are
untrusted. Imported files can contain malformed structures, duplicate IDs,
malicious SQL-looking text, invalid Alloy and excessive sizes. A database or
query artifact can be accidentally damaged or replaced by a wrong version.
The administrator, repository/toolchain provenance and OS account permissions
are trusted; hostile administrators and attackers with arbitrary code/file-write
access to the backend account are outside this feature's protection boundary.

SQL values must never be interpolated into SQL text. Private oracles, correct
bodies, original source witnesses, database paths and query diagnostics must not
be added to public API payloads. Existing public field allowlists remain the
boundary. Database files, journals, WAL/SHM files, parser artifacts and import
files must never be served by the application or put in the public IIS root.
Errors from learner-facing operations must be sanitized. No API key is stored
in the database, migration metadata, query artifacts, examples or logs.

## SQLeanParser boundary

Use the actual SQLeanParser at upstream commit
`6da54ef2874cbe7e0069bf8c192f12de3a8644f9`, not a replacement Python SQL parser.
Its current grammar admits SELECT/INSERT/UPDATE/DELETE, but not bind parameters,
DDL, PRAGMA or transaction statements. The available executable is Linux-only;
the deployment must not require that executable or a Lean installation.

All application data queries originate in a finite registry. During generation,
the actual parser must parse, schema-check and canonicalize each template and
produce its AST. Distinct registered string/integer literal sentinels identify
data slots. A bounded token transformation replaces only those complete literal
tokens with SQLite `?` parameters. Every slot must appear exactly once in the
registered order, have the declared type, and survive parser round-trip checking.
Unknown, repeated, missing or ambiguous slots fail generation. Runtime execution
uses only registered query identifiers, exact generated SQL and bound values.
Integer slots require `type(value) is int` within signed 64-bit bounds (not
Python booleans or floats). Text slots require a string with valid UTF-8 and no
NUL; reject null and implicit conversions. Each slot's byte bound is registered.
Unknown queries, changed artifacts, wrong arity/types and non-finite values fail
before SQLite execution. There is no runtime string concatenation of identifiers
or values and no unparsed-query fallback.

Ship the query registry, parser-produced canonical text/AST, schema, generated
prepared statements and their source/provenance hashes. Reproduce the artifacts
offline with the pinned parser. A runtime integrity check binds these artifacts;
this is integrity under trusted code/provenance, not authenticity against someone
who can rewrite all program files. DDL used to create a schema template and fixed
SQLite connection controls are separately enumerated trusted operations outside
SQLeanParser's grammar; they accept no caller-controlled SQL. A fixed `BEGIN`
control establishes explicit read/write snapshots; commits and rollbacks use
the Python connection API. Do not call these operations SQLean-certified.

SQLeanParser's upstream proofs use standard Lean axioms. This integration makes
no empty-axiom or end-to-end SQL execution proof claim and is separate from the
portal's existing finite Lean blocks. Python/SQLite, the template-to-parameter
compiler, parser executable/toolchain, filesystem, hashing and OS remain trusted
components with exact versions recorded by verification.

## Import contract and atomicity

The private import file identifies an exercise, its title/group/description,
predicate name/header, fixed environment, starter, and a nonempty ordered list
of oracle bodies; it may also provide additional known-correct bodies. IDs and
names follow the formats below. Reject unknown fields, duplicate
JSON keys, empty or duplicate solutions, excessive sizes, malformed UTF-8 and
invalid Alloy sources before publication. Each starter/solution must remain
exactly one body of the selected predicate. Reject unbalanced delimiters,
unterminated comments/strings and a closing brace that escapes the body, then
confirm the selected predicate's AST span and unchanged surrounding source.
For example, `some Node } fact injected { no Node } pred extra { some Node`
must fail even though a concatenated model might compile.

All oracles participate in nearest-correct selection for both metrics. The first
oracle is the explicitly documented primary reference for behavioral scoring
and the four example categories. The stored order is stable; no successful zero
match may stop validation of later candidates. The importer uses the Alloy API
to parse/type-check the starter and every reference in the supplied fixed
environment. It then requires a SAT result for module facts alone (avoiding
vacuous equivalence from an inconsistent context) and an UNSAT result for
`facts and not (primary iff candidate)` for every additional oracle and
known-correct body. A counterexample, timeout, unsupported dependency or unknown
result rejects the entire import. Facts/helpers that depend on the selected
predicate, recursion, unsupported parameters or failed body containment are
rejected rather than compared in a changed context.

The import may supply `equivalenceScope` (default 5, range 1..8). The bitwidth
is 5 and the maximum sequence length is the selected scope; trace bounds are
1..10. Record these bounds, the engine/solver identity, body/environment hashes
and the completed result. This establishes bounded agreement only. For example,
`#Node > 5` and `some Node and no Node` agree at scope 5 and differ at scope 6.
The first oracle remains an administrator-supplied intended solution; equivalence
does not prove the exercise's natural-language requirement. An unsatisfiable
primary predicate is allowed if its module facts are satisfiable. Existing
classified legacy pools retain their provenance and are not relabeled as having
passed this new-import equivalence gate.

Adding an exercise, all its solutions and provenance is one transaction. Duplicate
exercise IDs fail without overwrite. Failure before commit must preserve the
previous logical database contents; no half exercise, empty pool, duplicate
ordinal, orphan solution or missing primary oracle may become visible. Schema
and constraints, connection permissions and application validation all contribute;
none is advertised as a proof of arbitrary SQLite execution.

Migration validates the complete legacy catalogue/pool pair and its witnesses
before publishing a database. Never overwrite an existing database implicitly.
Database backups used for packaging must capture a consistent committed snapshot,
not copy a live file while omitting its journal. Preserve administrator additions
when packaging. Refuse unsupported versions, malformed schemas and invalid stored
records. Reject linked database/import/output paths where they could redirect a
write or disclose another file. Check lexical path components for symbolic links
and Windows reparse points before resolution; do not lose evidence of a linked
database parent by resolving it first. Unsupported schema versions are rejected;
future version changes require an explicit migration command, not implicit repair.

Bounds: database file 128 MiB; import file 2 MiB; query source/canonical text
64 KiB per statement; query artifact 4 MiB; 10,000 exercises; 4,096 candidates
per stored pool and 256 references per new import; predicate/starter body 8 KiB;
complete authored environment/model 256 KiB; title/group 256 UTF-8 bytes and
description 8 KiB. IDs match `[A-Za-z0-9][A-Za-z0-9_.-]{0,127}`; predicate names
match `[A-Za-z_][A-Za-z0-9_]{0,127}` with a parameterless `pred NAME` header.
Reject embedded NULs and invalid Unicode. The Alloy validation process gets
60 seconds for an entire import and bounded heap. SQLite lock waits and snapshot
backup each have a 5-second deadline; backup uses a monotonic progress deadline,
including busy retries, since connection timeout alone does not bound backup.
Additionally, the complete serialized canonical/AST/behavior engine request must
fit the engine's 1,048,576-byte limit, reserving space for a future 8-KiB learner
body under worst-case JSON escaping. Per-body bounds alone do not suffice:
256 otherwise valid 8-KiB reference bodies exceed the engine request limit.

## Navigation contract

Previous and Next follow the visible search/filter order without wrapping. They
are disabled at boundaries, with no visible selected exercise, and while a detail
selection is loading. They use the existing selection path so drafts are saved,
pending work is invalidated and late responses cannot replace the new exercise.
Keyboard access, focus indication and narrow/mobile layouts remain usable.

## Acceptance and reporting

Every requirement in the register has concrete positive and adversarial fixtures.
At minimum: exact migration parity; quoted SQL-looking text round trips as data;
unregistered/tampered queries fail closed; multi-oracle pool/behavior semantics
and actual Alloy equivalence/counterexample/fact-vacuity/containment witnesses;
transaction rollback; corrupt schema/records; no HTTP/private-file leakage;
relocated SQLite-only startup and IIS packaging; and navigation boundary/filter/
draft/race behavior. Use real SQLite and the real Alloy engine for integration
witnesses. Use the actual SQLeanParser for query-generation evidence.

The final finite regression run freezes source, specification, query artifacts,
seed/schema databases, parser provenance, verifier and test inputs. Two isolated
clean builds must agree on registered reproducible artifacts. Build timestamps
in deployment filenames are permitted nondeterminism; archive content and
explicitly named comparison fixtures remain reproducible. Existing proof reports
do not certify changed browser or persistence code. Unresolved required claims
block this feature's closure report; unsupported tooling is an infrastructure
failure. Native IIS/NTFS execution and unrestricted security remain excluded.
