# Retained CI failure 37302832049

Run: https://github.com/AnonymousAlloyLab/AlloyStudio/actions/runs/37302832049
Revision: `9876c04616c0245d471ae8019dab5c8af954c7f6`
Result: **FAIL**, at **Linux build and regressions / Python regressions**, job `111739395307`, from `2026-10-05T11:26:49Z` to `2026-10-05T11:45:34Z`.

The repaired wrapper reports **1,233 tests run** and these source-registered failure identifiers:

- `test_nearest_correct.NearestCorrectCorpusTests.test_all_181_starters_use_complete_correct_pool`
- `test_nearest_correct.NearestCorrectCorpusTests.test_real_socialmedia_pool_improves_over_oracle`

`python-summary.json` is a safe projection of the wrapper's logged JSON: fixed report fields and exact source-AST registered test identifiers. It is not the private outcome file, and contains no exception text, assertion values, subtest parameters or captured child output. The names identify cases to investigate; they do not establish whether the cause is resource contention, timeout, application logic, or another condition. The cause remains unknown at capture.

The macOS and Windows portable jobs passed. Linux build and engine checks passed; later Lean replay, freshness and browser steps were skipped after the Python failure. These facts do not constitute a passing release gate.

Only original sanitized dashboard JSONs, public Actions metadata, this explanation, and (where available) the fixed CI summary projection are retained. No raw logs or private model/configuration data are included. The manifest binds the exact payload hashes; historical reports remain unchanged.
