# SQLite implementation review and evidence boundary

Implementation follows the reviewed specification and register. The original
specification/review hashes remain unchanged. This document records constructed
implementation witnesses and the corresponding fixes; it does not itself grant
verification. Final authority is the frozen two-build run under C-SQLITE and
C-NAVIGATION in `closure/claims.json`.

| Constructed failure | Implemented correction | Executable witness |
| --- | --- | --- |
| An import file grew after its size inspection; an unrestricted read could exceed the declared envelope. | Limit the actual read to the maximum plus one byte and reject excess, for imports and legacy migration. | `test_sqlite_store.py` controlled stale-size/growing-file case. |
| A legal 8 KiB learner body appeared twice in a behavioral request, but sizing reserved it once; the request exceeded the JVM input bound. | Reserve both serialized copies and the designated primary oracle. | `test_sqlite_adversarial.py` behavior budget case. |
| A successful import grew an initially legal database past its maximum size; the next startup rejected it. | Check allocated page count times page size inside the write transaction before commit; rollback on excess. | `test_sqlite_adversarial.py` database growth case. |
| With facts `#String = 1 and "A" in String`, candidate `"B" in String and no Node` made a combined equivalence query vacuously UNSAT. | Pin the primary String universe in facts and equivalence commands; reject newly introduced candidate literals. | Real Alloy String-universe cases in `test_exercise_validation.py` and atomic-publication case in `test_sqlite_adversarial.py`. |
| `open helper` resolved a helper file alongside Alloy's temporary source; that file was absent from provenance and deployment. | Reject external modules; accept only canonical built-in resource paths from the same JAR as the actual Alloy API. | Real local helper, shadowed built-in and allowed bundled ordering cases in `test_exercise_validation.py`. |
| A parser process exiting 127 counted as an expected syntax rejection. | Distinguish actual parse/type diagnostics from tooling failures; infrastructure cannot count as a negative control. | `test_sqlite_closure.py` parser execution and CLI cases. |
| Browser child reports with zero checks could produce a combined PASS. | Require the exact nonempty, unique source-declared scenarios in each report. | `test_sqlite_closure.py` missing/duplicate/reordered/empty report fixtures. |

Other registered tests cover exact 181/7,731 migration and tie order, malformed
later candidates, facts vacuity, scope-5 versus scope-6 disagreement, strict types,
SQL-looking text, unexpected schema objects, rollback, read-only connections,
concurrent WAL snapshots, all-oracle pool selection, primary behavioral selection,
private HTTP paths, bounded backups and relocated SQLite-only deployment.

The actual parser loaded by the bundled classpath may come from
`AlloyASG-Release.jar`, which contains Alloy classes/resources before `alloy.jar`.
The import certificate therefore binds **all seven JARs in classpath order** and
the full compiled class tree, checked before and after validation. Recording only
`alloy.jar` would not identify the whole trusted execution input.

Alloy results remain bounded to the recorded scope/bitwidth/sequence/trace
profile. Legacy corpus labels remain trusted provenance. SQLeanParser's standard
Lean axioms, the literal-slot transformation, fixed SQLite DDL/controls, SQLite
execution and OS are explicit trusted components. Native Windows/IIS/macOS
execution is not established by Linux tests. No new Lean theorem block was added,
and historical proof reports do not certify the changed application source.
