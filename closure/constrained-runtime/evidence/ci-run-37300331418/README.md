# Retained Linux CI failure before safe test-name diagnostics

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37300331418
Revision: `5e550d6451decfaaad904b1aeb0bd30fa27e36f2`
Result: **FAIL**. The failing job was **Linux build and regressions**, job `111731390591`; its **Python regressions** step failed between `2026-10-05T11:03:45Z` and `2026-10-05T11:18:42Z`.

The retained Linux dashboard reports build PASS, runtime PASS with 378 checks, and Python FAIL with a null count. Later proof replay, freshness and browser steps were skipped. The macOS and Windows portable jobs passed; their dashboards do not claim a full Python suite run.

The failure cause and failed test names were initially **unknown**. The old wrapper only read the private outcome report on a zero subprocess exit, then deleted that private file. Consequently these public artifacts contain neither the failed method identifiers nor the suite count. No failed-test names, timeout diagnosis, or application defect is inferred from elapsed time or the null count. The full subprocess output is deliberately not retained here.

Commit `9876c04616c0245d471ae8019dab5c8af954c7f6` adds a source-AST registered-name projection for subsequent runs: fixed failure identifiers and count can be reported after a nonzero exit while dynamic test names, subtest values, exception text and captured output remain private. It does not retrospectively repair or explain this report. The next run must supply its own diagnosis.

Only the three original sanitized dashboard JSON files, public Actions job/step metadata and this explanation are retained. The manifest hashes their exact bytes. This is CI test evidence, not Lean proof closure or a claim that the release passed all gates.
