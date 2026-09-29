# SQL injection boundary

This verification concerns **SQL code/data separation**, not immunity to every
security defect. For an accepted database operation, the SQL bytes must come
from the fixed, integrity-checked query registry or fixed schema/control list.
Changing a bound value must not change those bytes. Arbitrary HTTP clients are
included: this property must not depend on browser validation or authentication.
Requests may change which approved operation runs, how many rows it handles,
or whether validation rejects it.

## Obligations fixed before release verification

1. `SQL-LEAN`: Lean proves separation for all modeled parameter values, rejection
   of unknown query identifiers, closure of command traces, and composition with
   a structured request boundary. No project axioms, transitive axiom
   dependencies, `sorry`, native proof evaluation, or network access are allowed.
2. `SQL-BRIDGE`: Extract the current production query adapter, every SQLite sink,
   fixed controls, query artifacts, internal table selectors, and public request
   path. Unrecognized sink forms, escaped execution capabilities, changed
   registry bytes, or incomplete mappings block verification. Instantiate the
   Lean result with the actual registry bytes in two fresh builds.
3. `SQL-WITNESSES`: Actual SQLite and authenticated Alloy/HTTP publication retain
   the registered injection payloads as values, including reload and backup.
   Schema, existing rows, and permitted SQLite operations remain unchanged.
   Altered executable query artifacts fail before database execution.
4. `SQL-RELEASE`: Bind source, proofs, extractor, tests, toolchain, and review
   records to one frozen input root; require two deterministic offline proof
   builds, the full portal regression closure, and release CI before publication.

## Production correspondence

The admin page serializes form values as JSON. `server.py` dispatches fixed
routes to `admin_service.py`; `exercise_store.py` maps reviewed metadata and
immutable Alloy source into row values. Only `exercise_sql.py` selects a
registered statement and supplies a separate parameter tuple to `sqlite3`.
Learner exercise identifiers are dictionary keys, not SQL query fragments.
The proof quantifies over arbitrary backend values, so replacing the frontend
or bypassing its validation does not bypass the SQL separation boundary.

The bridge checks a restricted production source shape and generates a concrete
Lean registry from its inspected artifacts. This is checked correspondence under
the declared extractor/interpreter trust boundary, not a proof of complete
Python, JavaScript, JSON, browser, or SQLite semantics. The original 24 general
portal proof obligations remain separate and open.

## Trust and exclusions

The Lean kernel, installed pinned toolchain, source extractor and audit runner,
Python/JavaScript execution and JSON codecs, SQLite's prepared-statement binding,
SHA-256, filesystem, operating system and hardware are trusted. Fixed DDL and
SQLite controls are separately enumerated; they are outside SQLeanParser's SQL
grammar. The pinned [SQLeanParser](https://github.com/University-of-Wild-Chicken/SQLeanParser)
checks the finite query templates; parameter binding prevents input text from
becoming SQL syntax.

Host compromise, modified application code or trusted hash constants, engine
implementation vulnerabilities, malicious deployment extensions, future
revisions, authorization correctness beyond the registered tests, and universal
application security are excluded. No claim of absolute or assumption-free
immunity is made. Release evidence must distinguish **PROVED**, **CHECKED**,
**TESTED**, and **TRUSTED**.
