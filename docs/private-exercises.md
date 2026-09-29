# Private exercise administration

The live datastore is `exercises/exercises.sqlite3`. The checked-in seed contains
181 exercises and 7,731 ordered references, including every oracle. Normal
clones and IIS packages use Python's bundled SQLite; neither a separate ACGN
checkout nor Lean/SQLeanParser is needed to run the portal. The legacy JSON pair
remains only as a migration input and regression witness.

## Add an exercise

Build the Java engine first (`./scripts/setup.sh` on Linux/macOS or
`.\scripts\build.ps1` on Windows). Copy `examples/private-exercise.json` to a
private file and edit its fields. The example is a standalone teaching fixture;
it contains no corpus oracle or credential. Keep your real import files outside
the public IIS directory and public repository.

Run from the backend/repository folder (use `python` instead of `python3` on
Windows if appropriate):

```bash
python3 scripts/manage_exercises.py info
python3 scripts/manage_exercises.py validate /private/path/exercise.json
python3 scripts/manage_exercises.py add /private/path/exercise.json
```

`validate` performs the real Alloy checks without storing anything. `add` repeats
validation and commits the exercise with its entire solution pool in one
transaction. Duplicate IDs are rejected without overwrite. Restart the backend
after adding exercises; each server process holds one consistent startup snapshot.
The Python service entry point is `exercise_store.add_exercise(root, document,
java='java')`. The authenticated [model upload page](admin-setup.md) provides
a separate, password-protected batch import workflow at `/admin/`, including
source preservation, variant equivalence checks and live snapshot publication.
Host-side CLI additions still require a restart.

`oracleSolutions` must contain at least one distinct body. Every oracle and every
optional `correctSolutions` body joins the nearest-correct pool for both Canonical
and Raw AST modes. The first oracle controls the behavioral score and the four
instance categories. Canonical remains the initial metric. Previous/Next follow
the current exercise filter and retain drafts.

## Validation and limits

Alloy parses and type-checks the starter and every solution. Body containment
checks prevent an imported body from closing its predicate and introducing facts
or declarations. The supporting facts must be satisfiable. Every additional
solution must agree with the first oracle under those facts: SAT4J must find no
counterexample to their equivalence. Invalid syntax/types, a counterexample,
unsupported dependencies or new String literals outside the primary model’s
fixed String universe, solver failure or the 60-second deadline reject the
whole import. No private solver trace or solution is returned over HTTP.

This is **bounded equivalence**, not an unrestricted proof. `equivalenceScope`
is 1–8 (default 5), integer bitwidth is 5, maximum sequence length is the scope,
and temporal traces are bounded to 1–10 states (static models have one state).
The database records the completed bounds, solver identity, body/environment
hashes, the full class-tree hash and all seven dependency JAR hashes. Local
filesystem modules are rejected; imported utilities must come from the bundled
JAR supplying the actual Alloy parser. The primary oracle remains the administrator's intended
solution; agreement does not prove the English description. Legacy classified
solutions preserve their original trust/provenance and are not relabeled as
having passed this new import gate.

Imports are at most 2 MiB, with up to 256 distinct references, 8 KiB per body,
and 256 KiB per complete model. The importer also checks aggregate engine input
size. Database growth is bounded to 128 MiB. IDs permit ASCII letters, digits,
underscore, dash and dot (128 characters, alphanumeric first character). The
header must name a parameterless `pred`; unknown input fields and duplicate JSON
keys are refused. The complete contract is in
[sqlite-security-spec.md](sqlite-security-spec.md).

## Migration, backups and upgrades

`python3 scripts/manage_exercises.py migrate` creates the database from a valid
legacy catalogue/pool pair only if no database exists. An existing invalid
database stops preparation; it never triggers fallback or replacement. Keep
administrator backups before changing deployments. The IIS packager uses SQLite's
consistent backup API, including committed WAL state, and validates that snapshot.
It packages added exercises as well as the seed. Preserve the deployed database
when upgrading application code; replacing it with the seed would discard your
administrator additions. Keep database, journal, WAL and SHM files private.

## SQLeanParser integration

Every data query comes from `sql/queries.json`. The actual pinned SQLeanParser
parses, type-checks and canonicalizes these templates; its AST and round-trip
results are in `sql/compiled-queries.json`. A checked transformation replaces
unique literal slots with SQLite bind parameters. Runtime accepts registered
query IDs and typed values only, verifies artifact hashes and requires no parser
executable. It never interpolates imported text into SQL.

The parser does not support SQLite placeholders, DDL, PRAGMA or transaction
commands. Fixed schema creation and connection controls are explicitly trusted
operations outside that grammar. SQLeanParser is a trusted external component;
its standard Lean axioms are not included in the portal's empty-axiom proof blocks.
No end-to-end SQL execution theorem is claimed.

Developer regeneration on this Linux verification host, with the pinned parser
already built, runs offline:

```bash
python3 scripts/compile_sql_queries.py --parser /home/augustus/SQLeanParser/.lake/build/bin/sqlean --check
```

Omit `--check` to regenerate after an intentional registry change. Deployment
users do not run this command. Vendored source and provenance bind the upstream
commit and Lean toolchain; the generator verifies the pinned executable hash and
runs it with external networking disabled.
