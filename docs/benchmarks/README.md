# Completed Alloy4Fun benchmark evidence

The five-arm deterministic experiment completed on **2026-09-30**: Canonical,
raw AST Zhang–Shasha, TAR depth 2, FM24 history, and FM24 history with mutation
each recorded **61,598** outcomes. The final comparison covers **42,388 incorrect
inputs** and **19,210 held-out CORRECT submissions** after two documented legacy
label corrections. It uses a 60-second request deadline and excludes Luna.

Read the [comparison and interpretation](../alloy4fun-comparison.md) first.
The [diagnostic report](../hint-diagnostics.md) explains the corrected and
retained defects, label provenance, temporary-file failure, and memory incident.
The [execution guide](../../benchmarks/alloy4fun/README.md) and
[protocol](../../benchmarks/alloy4fun/protocol/README.md) describe the adapters,
inputs, metrics, and reproduction requirements.

## Public artifacts

| Artifact | Contents |
| --- | --- |
| [Final results JSON](alloy4fun-results.json) | Complete per-tool/per-exercise counts, latency statistics and thresholds, paired coverage, repair validation, run metadata, and input/source/runtime hashes. |
| [Evidence verification](alloy4fun-evidence.json) | Final artifact hash checks, five-arm saved-response correspondence, preserved prior results, completion records, and verification scope. |
| [Hit rates, SVG](alloy4fun-hit-rates.svg) / [PNG](alloy4fun-hit-rates.png) | Timely native-hint availability on the 42,388 incorrect inputs. |
| [Availability curves, SVG](alloy4fun-timely-hints.svg) / [PNG](alloy4fun-timely-hints.png) | Measured wall-time thresholds, with the unequal worker configurations disclosed. |
| [Figure manifest](alloy4fun-figures.json) | Results input SHA-256, generator hash, four figure hashes, denominator, label corrections, and resource profile. |
| [Ten-exercise quality pilot](../alloy4fun-hint-quality.md) / [evidence JSON](alloy4fun-hint-quality.json) | Fresh current portal hints, matched saved TAR/FM24 outputs, source ranges, held-out Live controls, literal-edit probes, and two separate Luna reviews; a purposive all-underconstrained sample, not a learning study. |
| [181-invariant hint-quality audit](../alloy4fun-181-hint-quality.md) / [evidence JSON](alloy4fun-181-hint-quality.json) | Complete incorrect-input coverage and per-invariant guidance properties, with current starter responses and explicit distinctions between metadata, bounded repair validation, and teaching quality. |
| [181-invariant instance audit](../instance-audit-v003.md) / [evidence JSON](instance-audit-v003.json) | Real solver examples rendered at desktop and mobile widths; geometry, tuple correspondence, runtime bindings, and empty-starter limitations. |

The frozen final results JSON has SHA-256:

```text
f2de6de630721cd22adcb6fb57154b33cf39fcc4a05f900d404dc0beaae07f8e
```

The figures bind to those exact bytes. Updating surrounding explanatory Markdown
does not rerun the engines or replace the measured outcomes. Any future baseline
fix, alternate pool policy, or timing study needs separately identified results.

## What supports each conclusion

| Claim | Evidence | Boundary |
| --- | --- | --- |
| All five arms completed the same cohort | `runs`, `metrics.cohort`, and `provenance.case_manifest` in the results JSON; per-arm response audit | 307,990 request outcomes, not 307,990 successful hints. |
| Hint counts and statuses match saved responses | `evidence-audit.json`, rebound in the public evidence verification | Checks correspondence and recorded trace properties; does not independently remeasure latency or establish teaching quality. |
| The original source selection was retained | Case/source hashes, AST-identity selection input, whole-branch lineage hashes | 66,080 source files minus 4,482 AST-identical pairs; no silent replacement of the cohort. |
| Two original CORRECT labels needed correction | Hash-bound [correction registry](../../benchmarks/alloy4fun/protocol/label-corrections.json) and diagnostic bounded checks | Original source files and labels remain preserved; this is not a fresh audit of every corpus label. |
| Canonical/AST use the nearest training correct reference including the oracle | Frozen adapters, manifests, branch folds, and pool construction | Measured five-fold policy differs from the original complete-pool `Alloy4FunAugmenter` policy. |
| Nonempty held-out CORRECT hints do not show failure to recognize admitted full-pool members | Membership audit: all 19,210 represented in complete compatible-context pools; all nonempty Live controls lack an identical body in the held-out pool | Lexical membership audit, not a completed full-pool distance/runtime experiment. |
| TAR returned 18,901 hints and complete candidates | Native records and numeric/raw bindings | Output within the deadline; a found but unserialized candidate does not count. |
| 18,538 TAR repairs passed independent checking | Saved production Alloy/SAT4J validation responses | Original model facts, check and bounds; not unbounded equivalence. Three failed; 360 remained unknown. |
| Earlier valid results survived the memory incident | Preserved hashes for 16 completed-arm files and three interrupted TAR files | Fresh TAR has zero resumed rows; interrupted rows are excluded, not combined. |
| Guarded recovery completed without another workload OOM | TAR/finalization guard records, completion manifest, cgroup memory events | Observed local execution; no promise that any future workload cannot exhaust the host. |
| Canonical display and AST lifecycle corrections were effective on the audited cases | Replays in the diagnostic report plus error-free completed Live arms | Retained binder-matching and normalization-profile limitations are explicitly documented. |

## Local evidence retained outside the public report

The full measurement archive is `build/benchmarks/alloy4fun-v2/`. This directory
is ignored by Git; publishing a summary does not publish its private contents.

- `cases.jsonl`, `cohort.json`, lineage and payload files bind the evaluation
  population and prepared requests. Their exact paths and hashes are recorded
  in the manifests.
- `live-full/`, `ast-full/`, `tar/`, `fm24-history/`, and `fm24-mutation/` each
  contain `manifest.json`, `run.json`, `results.jsonl`, and `responses.jsonl.gz`.
  Native responses can contain complete reference or candidate expressions;
  they must not be served as portal assets.
- `evidence-audit.json` is the final five-arm correspondence audit. It records
  closed response streams, matching case sets, counts, statuses, and file hashes.
- `resource-profile.json`, `environment.json`, and the frozen runtime archive
  record the machine, execution schedule, code, dependency and worker settings.
- `recovery/tar-guard.json`, `recovery/finalization-guard.json`, and
  `recovery/pipeline-completion.json` record successful completion. The retired
  services are no longer required to remain active.
- `recovery/completed-arm-preservation.json` and
  `tar-interrupted-oom-20260929T234811/` preserve the completed arms and the
  interrupted TAR attempt. Partial archive bytes are retained even where a
  complete numeric/raw pair could not be recovered.
- `pool-policy-membership-audit.json` records complete versus held-out lexical
  pool membership. `final-review/` contains additional checks made after run
  completion; these do not rewrite measured responses.
- `final-review/tar-outcome-audit.json` inventories all four engine errors,
  three counterexample-producing repairs, 358 validation errors, and two
  validation timeouts. Its hash and causal-evidence limits are documented in
  the [final TAR diagnostic](../hint-diagnostics.md#6-final-tar-failure-and-validation-inventory).

Earlier measurements remain under `build/benchmarks/alloy4fun/`, and focused
diagnostics occupy separate directories listed in the diagnostic report.
Private Alloy counterexamples and target predicates stay in those local
artifacts. Public case identifiers and hashes allow those records to be located
without revealing the full target expressions.

## Limits on the conclusions

This is an adapted author-artifact comparison on a shared five-fold cohort,
not an exact replication of either paper's experiment. The complete original
ACGN pool-policy experiment has not been run. Native hint availability measures
whether guidance was emitted, not whether a learner could execute it correctly
or learn from it. Semantic improvement of individual redacted edits, human
learning, and Luna's explanations were not measured.

The four non-TAR arms used 16 workers; the recovered TAR run used four workers
with a 6 GiB cap and four-CPU quota. Runtime and deadline-conditioned coverage
therefore describe these configurations, not equal-resource speed rankings.
The documented 1.25-second diagnostic overlap remains part of observed TAR
timing. Independent repair checks are bounded and retain unknown outcomes.
The evidence audit establishes finite artifact consistency, not a Lean proof
or a universal correctness certificate for any engine.
