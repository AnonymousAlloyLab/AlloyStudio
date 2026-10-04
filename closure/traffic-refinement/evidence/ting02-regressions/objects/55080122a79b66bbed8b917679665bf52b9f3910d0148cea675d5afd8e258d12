# Alloy4Fun comparison protocol

This is the measurement contract for the completed Alloy Studio, TAR, and FM24 historical-data comparison. All five registered arms contain 61,598 outcomes and passed the final raw-response correspondence audit. The [comparison report](../../../docs/alloy4fun-comparison.md) and [versioned results JSON](../../../docs/benchmarks/alloy4fun-results.json) contain measured results; the cohort counts below define denominators rather than implying hint success. `metrics.py` computes only metrics justified by executed per-case observations.

## Cohort and denominators

The requested 61,598-model cohort is **66,080 classified source files minus 4,482 pairs whose student and paired oracle have identical raw ASTs**. The retained cohort contains **42,388 incorrect** and **19,210 CORRECT** cases. Freeze a case manifest containing relative source path, SHA-256, target predicate, family, classification, raw-AST exclusion witness, oracle/context identities, and scope. Original source counts were independently enumerated here as 12,576 `under`, 21,715 `both`, 23,694 `correct`, and 8,095 `over`. The original retained labels were 42,386 incorrect and 19,212 CORRECT. [Two source-hash-bound corrections](label-corrections.json) move legacy empty-predicate records from CORRECT to UNDERCONSTRAINED without changing source files or selection. The manifest preserves both source and effective labels; the corrected reporting contract is `alloy4fun-comparison-v3`. Preserve every eligible case in the audit, including unsupported and failed requests.

The main hint Hit Rate is native next-step hints returned within the resource budget divided by **all eligible incorrect cases**. Report micro-average and equal-weight mean over `(group, predicate)` exercises. A supplementary support-conditional Hit Rate answers a different question and must not replace the all-case result. Test CORRECT cases separately: in the measured five-fold experiment these are **held-out CORRECT submissions**, whose branch's references are withheld. Nonempty hints therefore measure generalization to withheld correct solutions, not failure to recognize admitted members of the original complete correct pool. They are not automatically logically incorrect recommendations. Distance zero and positive distance are not semantic correctness certificates.

`UNDERCONSTRAINED` means the student accepts an oracle-rejected instance (portal **overcoverage**). `OVERCONSTRAINED` means the student rejects an oracle-accepted instance (portal **undercoverage**). Preserve these names separately.

## Two experimental questions

1. **Full-corpus operational audit:** run each implementation on all 61,598 retained cases, with the configured historical/correct pools recorded. Every row must identify whether history includes the evaluated submission or its trajectory. This is an in-corpus operational result when it does; it is not a held-out generalization estimate.
2. **Held-out historical generalization:** partition complete learner paths 70/30, then build historical graphs and correct pools using training paths only. Do not split adjacent submissions independently. Use a fixed seed and serialize split IDs. A full-corpus cross-fitted estimate may use grouped folds so each case is queried once against other folds; report this as a separate protocol from the paper's single 70/30 split. If path/student metadata cannot be recovered, state that historical held-out replication is blocked. A random file split is a labeled fallback sensitivity analysis, never an equivalent substitute.

The completed five-arm run measures question 2 over the full evaluation cohort;
it does not complete question 1 for the original `Alloy4FunAugmenter` policy.
The original augmenter adds all admitted CORRECT submissions plus an oracle,
deduplicates ASTs, and ranks only incorrect submissions. Its complete pool has
no held-out fold. A claimed defect in known-correct member recognition requires
a witness that the matching reference was actually admitted to that query's
pool; a withheld reference is not such a witness. Preserve these policies and
their measured outcomes separately rather than changing old labels or results.
The full-compatible-pool membership audit is evidence that bodies are included,
not an executed full-pool hint-generation or latency experiment. A separate
original-policy operational arm is still needed to measure that question.

FM24 describes 70/30 full paths, a 60-second request timeout, TAR mutation depth 2, and two distinct variants: historical graph only and historical graph plus one mutation for missing states (local `FM24.pdf`, §§4–5, pp. 7–14). TAR's SEFM22 paper reports static and temporal repair on other cohorts, including depth 3 results with a 60-second budget (local `SEFM22.pdf`, §4, pp. 11–14). These published rates cannot be directly ranked against the new 61,598-model experiment.

FM24 links its implementation to [anaines14/Alloy4Fun](https://github.com/anaines14/Alloy4Fun) in footnote 9. Its repository has an `evaluation` directory; the existing `extract_test_data.py` matches models having exactly three result records. Reusing that complete-case filtering for new all-corpus comparisons would silently remove failures. The [original Alloy4Fun data DOI](https://doi.org/10.5281/zenodo.4676413) is cited by TAR; resolve dataset provenance and trajectory metadata before claiming the same historical split.

## Inputs and fairness

- Use the same target predicate, preserved supporting environment, facts, oracle, scopes, bitwidth, sequence limit and temporal bounds across tools. Hash the full evaluation context. Fact enforcement is required by the portal configuration; do not mix a fact-free reward run with fact-enforced comparisons.
- All correct pools include the author oracle. Additional CORRECT student solutions must be training-only for held-out runs and compatible with the exact environment, target signature and oracle. Record counts before/after deduplication. Corpus labels are evidence at the original bounds; recheck imported truth candidates through the Alloy API if the environment or bounds change.
- Do not expose the oracle to Luna or learners. Reference bodies may be available privately to the deterministic baseline implementations as required by their algorithms. Reporting a hidden complete target is not equivalent to producing a useful partial hint.
- Run both the canonical and raw-AST production hint pipelines with nearest-correct selection, edit reconstruction and source location. Canonical remains the portal default. ACGN precomputed paired-oracle distances alone do not exercise either live path; neither mode substitutes for the other in this comparison.
- Native methods solve different tasks: TAR finds a complete repair; FM24 selects a historical next state and derives its first edit; Live supplies a redacted canonical edit trace. A full-repair metric must be separate from native hint availability. Any adapter used to derive a TAR first-edit hint must be described and timed.
- No API/Luna generation latency or cost is included in the deterministic core comparison. If separately evaluated later, disclose model, prompt, output limits, retries and cache policy, and charge timeouts as failures rather than discarding them. Do not infer educational quality from deterministic success rates.

## Metrics

| Metric | Definition and limitation |
| --- | --- |
| Native hint Hit Rate | At least one native next-step hint returned before timeout / all incorrect cases. An aggregate-only distance notice does not count. Does not assert executable edits or correctness. |
| Support coverage | Tool-supported incorrect cases / all incorrect cases; report extraction, parse, unsupported, memory, timeout, empty-result and other errors separately. |
| Macro Hit Rate | Arithmetic mean of Hit Rate over invariant exercises with at least one incorrect case. Also retain counts/rates per family, invariant and error class. |
| Runtime | Monotonic request wall time from input to hint, including parsing, search, reconstruction and adapters. Include unsuccessful and timed-out cases; report mean, median, p95, max and successful-only latency separately. Record externally measured latency even if cleanup exceeds 60 seconds. |
| Timely hint curves | Fraction of all incorrect cases receiving a hint by 0.1, 0.25, 1, 2, 5, 10, 30 and 60 seconds. |
| Localization coverage | Located operations / operations having locator measurements; separately identify exact-node, contextual and unavailable locations if the adapter exposes that distinction. A nonempty span is not an exact-node correctness certificate. |
| Hint size | Number of visible atomic operations and aggregate notices separately. Distance is not automatically the number of executable raw-code edits. |
| Full repair success | Only when a tool supplies a complete repair: the result parses and the preserved-facts bounded equivalence check finds no counterexample. Report scoped equivalence, not unbounded logical proof. |
| Executable/progressive first edit | Only after applying an actual operation and reparsing: report applicability, compilability and independently assessed behavior. Redacted placeholders are not executable replacements. |
| Behavioral improvement | On a fixed independent set of fact-satisfying oracle-positive and oracle-negative instances, compare pre/post agreement and over/undercoverage. Record bounds, set hash and counts; do not treat the tool's own canonical-distance decrease as independent semantic improvement. |
| Pairwise coverage | Both-hit / only-A / only-B / neither on the same manifest, plus disagreement cases. Do not present union coverage as one system's success rate. |
| Held-out correct-input control | Fraction of the 19,210 CORRECT cases receiving nonempty hints after their branch's references are withheld. This concerns unseen correct solutions; it does not test the original complete pool's member-recognition invariant. |

Behavioral scores on sampled instances are sampling-dependent, not a proof of equivalence. The production ACGN-style score enforces model facts; undefined positive/negative sample populations must remain unavailable rather than becoming 0 or 1. Do not equate classifications from a finite, fixed validation sample to the exact category satisfiability checks.

Report graph construction, correct-pool preparation and cache warming separately from online request time. Record source revisions, JARs, JVM flags, hardware, memory cap, worker count, startup/cold versus warm timing, seeds and run order. One-shot JVM latency and warmed persistent JVM latency answer different questions. New controlled comparisons should use the same execution profile across methods, and record peak memory and throughput as secondary operational measures. Do not compare a 16-worker historical batch throughput to sequential per-request latency.

The registered experiment has a documented recovery exception: Canonical, raw AST, FM24 historical, and FM24 historical plus mutation completed with **16 workers each** before a global-memory incident interrupted TAR. Those four completed runs were preserved byte-for-byte. The interrupted TAR evidence remains in a separate archive; its rows do not enter the comparison. TAR completed a fresh run with **4 workers**, a **6 GiB hard memory cap**, a 4 GiB soft threshold, no swap, a four-CPU quota, and reduced scheduling priority. An independent supervisor enforced a 4 GiB global-memory reserve. All five arms retained the same **60-second request budget** and the same full cohort. The final report binds the recovery resource profile, enforced-limit evidence, archive, and preserved-run hashes. TAR reports zero resumed rows; both the TAR and finalization guards completed without OOM events. Request latencies and deadline-conditioned hint availability are **descriptive measurements under unequal resource configurations**, not an equal-resource speed ranking. The four completed arms were not rerun or combined with new measurements to disguise that difference.

Reproduction requires the external full corpus, exclusion audit, historical metadata, pinned baseline artifacts, and the recorded execution profile; a public portal clone alone does not supply those private experiment inputs. The commands and transports were exercised on Linux, not as a portable Windows/macOS baseline harness. This is an adapted author-artifact comparison, not a replication of either paper's original cohort, split, or deployment. Native output contracts differ, so hint availability cannot establish superior repair quality or educational value. Runtime figures come from one recorded batch per arm, with no repeated-run uncertainty estimate. A new resource configuration needs its own registered reporting contract; the retained recovery evidence cannot stand in for another run.

A 60-second cap on all 42,388 incorrect cases allows up to 706 worker-hours per tool. Checkpoint row results durably and resume only when case/config/binary hashes match. Pilot runs can validate adapters and forecast resources but must be clearly labeled; they cannot substitute for the requested full cohort. Repeated runs and paired bootstrap uncertainty should resample whole learner trajectories when available, otherwise at least exercise groups; resampling dependent individual submissions understates uncertainty.

## Executable aggregation contract

`metrics.py` requires one JSONL observation per `(tool, case_id)` per run, with:

```json
{"case_id":"group/both/model_inv1.als","tool":"live-canonical","cohort_status":"BOTH","group":"group","predicate":"inv1","status":"ok","hint_available":true,"wall_seconds":0.25,"timed_out":false,"supported":true,"operation_count":2,"located_operations":1}
```

Optional fields are `engine_seconds`, `distance`, `operation_count`, and `located_operations`. Correct labels must be exactly `CORRECT`, `BOTH`, `OVERCONSTRAINED`, or `UNDERCONSTRAINED`. `hint_available` must be false for timeout or unsupported outcomes. Positive numeric values are measured observations, not expectations.

Provide an expected-case JSONL manifest with `case_id`, `cohort_status`, `group`, and `predicate`. Missing runs then stay in denominator counts; their timings are explicitly missing, not fabricated. Without that manifest the observed union defines the cohort and full-corpus completeness is unknown. Repeated runs must be aggregated separately; duplicate tool/case observations are rejected.

```sh
python3 benchmarks/alloy4fun/protocol/metrics.py rows.jsonl --cases cases.jsonl --timeout 60
python3 -m unittest discover -s benchmarks/alloy4fun/protocol -p 'test_*.py' -v
```

The current aggregation code implements observed hint availability, micro/macro/support-conditional rates, timeout/status counts, latency distributions and curves, operation/localization summaries and correct-input controls. The remaining quality metrics above require further actual repair/behavior evidence and are deliberately not invented by the aggregator.
