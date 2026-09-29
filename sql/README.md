# Registered exercise queries

`queries.json` is the finite source registry; `schema.json` is SQLeanParser's
external typing schema. `compiled-queries.json` contains the actual parser's
canonical SQL, public Lean AST, schema-check result, round-trip result and the
generated SQLite statement for every registered query. The ten entries are four
ordered reads, four complete-row inserts and two schema-inspection reads.

The deployed `exercise_sql.py` checks exact hashes of those three files and
`vendor/sqlean/provenance.json` before every operation. It accepts a registered
query ID and typed parameter sequence. It does not accept raw SQL. Prepared
statements bind all runtime values through Python's `sqlite3` API.

SQLeanParser currently does not parse bind placeholders. Source templates use
distinct typed literal sentinels; the generator first invokes the actual parser
to parse, schema-check, canonicalize and round-trip each template. A bounded
scanner then replaces exactly the registered complete literal tokens, in order,
with `?`. The scanner is not an alternative SQL parser. Missing, repeated,
embedded, mistyped, unknown or reordered slots fail generation.

Schema DDL and the enumerated connection controls in `exercise_sql.py` are trusted
fixed operations outside SQLeanParser's grammar. Exact schema checks include
every table and automatically generated unique index, their SQL definitions,
and the absence of unexpected triggers, views, indexes and tables. Schema
creation never repairs or replaces an existing schema. Callers must hold one
read transaction across schema validation and all snapshot reads.

Regenerate using the pinned executable on the development host:

```sh
python3 scripts/compile_sql_queries.py --parser /path/to/pinned/sqlean
python3 scripts/compile_sql_queries.py --parser /path/to/pinned/sqlean --check
python3 -m unittest discover -s tests -p test_sql_queries.py
```

Generation runs the executable in a Linux user/network namespace and never
downloads a toolchain. The supplied executable must match the SHA-256 recorded
in `vendor/sqlean/provenance.json`. The vendored Lean sources are also checked
against their recorded hashes. Changing the parser/toolchain requires an
explicit reviewed provenance update and regeneration. This is separate from
the portal's Lean 4.34.1 proof blocks: the pinned SQL parser uses Lean 4.34.0 and
its upstream proofs use standard Lean axioms.

The generator also requires rejection of seven syntax/type negative controls,
including placeholders, DDL, connection controls and invalid columns/types.
Portable unit tests check the frozen provenance and real SQLite behavior;
they do not pretend to execute the Lean parser. Mechanical closure must run
the separate `--check` command against the pinned executable, with missing
tooling reported as an infrastructure failure.
Only the CLI's explicit SQL parse/type rejection diagnostics count as negative
controls. Namespace failures, missing tools, killed processes and timeouts cannot
discharge them. Generator exit codes are 0 for success, 1 for rejected inputs or
artifacts, and 2 for infrastructure failure.

Windows, macOS and Linux deployments consume the committed artifacts. They need
neither a parser executable nor a Lean installation nor the original parser
checkout. These artifacts establish generation provenance and finite regression
evidence, not a universal proof of SQLite execution or SQL parser semantics.
