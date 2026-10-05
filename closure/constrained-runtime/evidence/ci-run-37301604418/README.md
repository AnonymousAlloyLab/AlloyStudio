# Retained CI failure 37301604418

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37301604418
Revision: `64ea353ca76db2e486592dcd869efa9b167695e5`
Result: **FAIL**, at **Linux build and regressions / Python regressions**, job `111735385739`, from `2026-10-05T11:15:21Z` to `2026-10-05T11:34:28Z`.

This tag run used the older wrapper. Its Linux dashboard records Python FAIL with a null count, and no test identifiers were published. The wrapper discarded the nonzero child outcome report and removed its private file. The cause and failed test names are therefore **unknown** in this evidence. Failures identified by a later run are not retroactively assigned to this run. The tag was not a published GitHub release when this evidence was captured.

The macOS and Windows portable jobs passed. Linux build and engine checks passed; later Lean replay, freshness and browser steps were skipped after the Python failure. These facts do not constitute a passing release gate.

Only original sanitized dashboard JSONs, public Actions metadata, this explanation, and (where available) the fixed CI summary projection are retained. No raw logs or private model/configuration data are included. The manifest binds the exact payload hashes; historical reports remain unchanged.
