# Hint availability and guidance across all 181 invariants

This audit compares **Canonical, raw AST, FM24 historical, FM24 historical + mutation, and TAR** on every one of the 181 imported Alloy4Fun invariants. It separates **whether a hint exists** from **what guidance is actually present**. These are observed presentation properties, not learner-success scores.

The complete [machine-readable evidence](benchmarks/alloy4fun-181-hint-quality.json) contains per-invariant corpus counts and guidance profiles, plus one matched starter per invariant with current public portal responses and saved native baseline hints. The [ten-exercise pilot](alloy4fun-hint-quality.md) supplies the earlier qualitative reviews and literal-edit probes; those ten reviews have **not** been extrapolated into 181 subjective ratings.

## Scope and reproducibility

- Corpus layer: all **42,388 incorrect submissions** in the completed 61,598-model study. The corrected frozen labels include 21,715 BOTH, 8,095 OVERCONSTRAINED and 12,578 UNDERCONSTRAINED inputs. All five saved result and native-response archives were streamed, hash checked against the published evidence, and joined by case identity. The original results are unchanged.
- Current-portal layer: **181 existing catalogue starters**, selected without looking at outputs (175 UNDERCONSTRAINED and 6 OVERCONSTRAINED), with one fresh Canonical and AST request each. The portal uses its full compatible CORRECT pool, including its oracle; archived Canonical/AST and FM24 use their recorded five-fold held-out pools. The archived Canonical/AST starter controls remain in the JSON. This is not an equal-training-data comparison.
- TAR was not rerun: depth two, recorded 60-second search budget, and an independent bounded Alloy validation attempt for emitted candidate repairs. Successful, rejected and unresolved checks are distinguished below. A bounded check is not universal equivalence.
- No paid Luna explanations, new AI quality ratings, learner study, or all-corpus literal-edit success experiment was performed. Current request wall times are diagnostics, not a new runtime ranking.
- Public evidence omits oracle bodies, hidden target expressions and complete candidate repairs. FM24 operand type/name phrases are conservatively redacted, including source-side identities; operator names and explanatory wording remain. This records a disclosure difference, not an error in FM24.

The [frozen 181-input selection](../benchmarks/alloy4fun/protocol/quality-selection-181.json) is included in the repository, containing only learner drafts, public questions and source identities. The [reproducer](../benchmarks/alloy4fun/quality_all.py) reads one archive at a time and retains only its compact result index, per-invariant counters and the 181 selected responses. It never starts solvers or connects to a service. The fresh collector is single-worker; scratch data lives in `build/benchmarks/hint-quality-181/`, not the system temporary directory. To collect new current-portal evidence, first build the engine using the repository setup guide, then run the following from the repository root. A fresh study directory is required; existing evidence is never overwritten.

```bash
python3 -c "from pathlib import Path; import shutil; p=Path('build/benchmarks/hint-quality-181-reproduction'); p.mkdir(parents=True, exist_ok=False); shutil.copyfile('benchmarks/alloy4fun/protocol/quality-selection-181.json', p/'selection.json')"
python3 benchmarks/alloy4fun/quality_collect.py \
  --selection build/benchmarks/hint-quality-181-reproduction/selection.json \
  --output build/benchmarks/hint-quality-181-reproduction/current.json
python3 benchmarks/alloy4fun/quality_all.py \
  --study build/benchmarks/hint-quality-181-reproduction \
  --output build/benchmarks/hint-quality-181-reproduction/evidence.json \
  --markdown build/benchmarks/hint-quality-181-reproduction/report.md
```

The first two commands use the checked-in selection and local production portal. The final archive audit additionally requires the preserved research run directory `build/benchmarks/alloy4fun-v2` (or `--data /path/to/run`). Those large private native-response archives are not in a public clone; the published JSON and per-record/archive hashes are. A rerun uses the currently installed runtime, so record its new provenance rather than assuming it reproduces historical hints byte for byte.

## Corpus-wide availability

A hit means the saved run reports a nonempty native hint on an incorrect submission. It does not mean a completed repair, a syntactically valid first edit, or educational benefit. All eligible incorrect cases remain in each denominator, including failures and timeouts.

| Method | Hints / incorrect inputs | Availability | Invariants with ≥1 hint | Macro availability |
| --- | ---: | ---: | ---: | ---: |
| Canonical | 42,388 / 42,388 | 100.00% | 181 / 181 | 100.00% |
| AST | 42,388 / 42,388 | 100.00% | 181 / 181 | 100.00% |
| FM24 history | 14,422 / 42,388 | 34.02% | 156 / 181 | 30.68% |
| FM24 + mutation | 21,373 / 42,388 | 50.42% | 169 / 181 | 48.78% |
| TAR | 18,901 / 42,388 | 44.59% | 179 / 181 | 55.19% |

Macro availability weights each invariant equally; overall availability weights each submission equally. FM24 history and mutation-enabled FM24 are separate configurations and must not be merged into one result.

## Guidance properties observed across the whole incorrect corpus

For Canonical/AST, an **exact raw location** means metadata identifies the selected learner node; it is not independent evidence that the node is the learner’s conceptual defect. An insertion may use a shared related anchor. A **named replacement operator** communicates an operator while retaining hidden operands. A repeated action is a second occurrence of exactly the same action text within a response; different affected nodes can legitimately require the same action. Trace length measures reading burden only: the methods use different edit units and targets.

| Property | Canonical | AST |
| --- | ---: | ---: |
| Atomic operations | 499,542 | 485,037 |
| Exact raw-node operations | 403,194 / 499,542 (80.71%) | 453,988 / 485,037 (93.60%) |
| Related raw-context operations | 27,580 / 499,542 (5.52%) | 0 / 485,037 (0.00%) |
| Exact canonical-node operations | 485,136 / 499,542 (97.12%) | 0 / 485,037 (0.00%) |
| Named replacement-operator operations | 96,494 / 499,542 (19.32%) | 56,138 / 485,037 (11.57%) |
| Hints whose every atomic operation has an exact raw location | 17,698 / 42,388 (41.75%) | 24,734 / 42,388 (58.35%) |
| Hints containing repeated action wording | 35,047 / 42,388 (82.68%) | 36,342 / 42,388 (85.74%) |
| Hints longer than 20 atomic operations | 6,294 / 42,388 (14.85%) | 6,111 / 42,388 (14.42%) |
| Trace-cost metadata matches distance | 42,388 / 42,388 (100.00%) | 42,388 / 42,388 (100.00%) |
| Median operations per hint | 9 | 9 |
| 95th percentile operations per hint | 31 | 30 |

Canonical locations are intentionally unavailable in AST mode. Canonical also emitted 446 aggregate notices, excluded from the atomic-unit percentages and lengths; these notices account for remaining edit cost without pretending to be an individual located edit. Replay/count checks concern the internal structural trace; **they do not prove that taking one displayed instruction literally will compile or improve behavior**. The earlier pilot constructed a type-error counterexample for a literal Canonical operator replacement. That limitation remains applicable; this audit does not reinterpret a high location or trace-consistency rate as repair correctness.

| Native baseline guidance property | FM24 history | FM24 + mutation | TAR |
| --- | ---: | ---: | ---: |
| Hints with an operator-purpose phrase | 9,230 / 14,422 | 9,230 / 21,373 | 0 / 18,901 |
| Hints naming an operator/quantifier | 13,056 / 14,422 | 13,056 / 21,373 | Not assessed by this FM24-specific detector |
| Hints with a relative-context phrase | 5,313 / 14,422 | 5,313 / 21,373 | Not assessed by this FM24-specific detector |
| Hints requiring operand-identity redaction | 3,542 / 14,422 | 3,542 / 21,373 | Not assessed by this FM24-specific detector |
| TAR hints with all native line/column ranges | — | — | 18,901 / 18,901 |
| TAR hints with a bounded-validated complete repair behind them | — | — | 18,538 / 18,901 |

TAR has **3 independently rejected candidates**, **358 validation errors** and **2 validation timeouts** among native-hint cases. These remain native-hint availability hits; they are not counted as validated repairs. An unknown validation result is not a constructed counterexample.

The phrase detector is intentionally mechanical: it tests the fixed FM24 templates for operator/quantifier names, phrases such as “to specify” or “to combine”, and “within the” or “inside of the”. It does not judge whether an explanation is relevant, correct, understandable or sufficient. TAR’s absence of those phrases reflects its terse mutation cues, not a failed repair. TAR line/column ranges were counted as supplied; these are not portal body-offset highlights. FM24’s relative context is also different from an exact editor address.

### Assessment

**Canonical and AST have the strongest availability in this corpus** and provide inspectable multi-step structure with source references. Their long and repetitive traces, hidden operands and normalization/tree-edit semantics leave interpretation to the learner. AST avoids canonical normalization, but its delete/insert operations can also differ from ordinary text edits. Neither trace length nor distance establishes a quality ordering between these two modes.

**FM24 history explains operator purpose more directly when a historical hint is available.** The mutation-enabled arm extends availability, but its extra mutation cues use terser wording. Some native hints disclose operand identities; the public comparison marks those omissions rather than awarding the portal an unfair specificity disadvantage under its stricter disclosure policy.

**TAR provides terse local mutation guidance, with a positively validated bounded repair behind 18,538 of its 18,901 native-hint cases.** That is a stronger repair-evidence property than structural distance for that verified subset, while its missing hints and broad wording limit the guidance available to an individual learner. The experiment does not show that displaying or following its hint alone reproduces its hidden repair.

A defensible product priority is to keep both portal views and their exact occurrence highlighting, then improve first-step pedagogy and compilation safety without exposing target expressions. An actual novice study and first-edit behavioral validation remain open; no overall educational winner is claimed.

## Current portal: one existing starter for every invariant

This layer tests the current public interface. All 181 inputs are incorrect under the corrected corpus labels, but they are strongly biased toward overcoverage (the corpus UNDERCONSTRAINED label). Four existing starters are whitespace-only: `cv_v1-inv1`, `cv_v1-inv3`, `cv_v1-inv4` and `productionLine_v1-inv1`. The current HTTP interface rejects their empty bodies before the hint engine, while the saved benchmark passes complete Alloy modules, where an empty predicate body is syntactically meaningful. Both current modes return hints for **all 177 nonempty starters**; the four validation outcomes are preserved without substitution. They do not replace the all-incorrect corpus denominator above.

| Method | Current/matched starter hints |
| --- | ---: |
| Canonical | 177 / 181 |
| AST | 177 / 181 |
| FM24 history | 49 / 181 |
| FM24 + mutation | 84 / 181 |
| TAR | 114 / 181 |

The public JSON preserves **all** current Canonical/AST operations and native saved baseline hints for these same inputs, not only successful cases. The archived live-mode controls allow a reader to distinguish current full-pool behavior from the earlier benchmark configuration. No absent native hint is assigned a subjective guidance score.

## Per-invariant availability and guidance

Every row uses all incorrect submissions of that invariant. **C/A** means Canonical/AST. The raw-location column is the percentage of atomic operations carrying exact raw-node metadata; the length column is median atomic operations per available hint. FM24 columns count available native hints, with “H/+M” denoting history/mutation-enabled. Guidance details, statuses, operator specificity, repetition and the matched starter outputs are in the linked JSON. FM24 purpose counts apply equally to both variants because all extra mutation hints lack these particular purpose phrases. TAR checked counts show the verified subset of the adjacent hint count.

| Invariant | Incorrect N | Canonical hints | AST hints | FM24 H / +M hints | TAR hints / checked | C/A raw exact % | C/A median steps | FM24 purpose hints |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `classroom_fol-inv1` | 16 | 16 | 16 | 5 / 14 | 15 / 15 | 39.47% / 90.62% | 2 / 2 | 2 |
| `classroom_fol-inv2` | 4 | 4 | 4 | 0 / 1 | 4 / 4 | 90.00% / 100.00% | 3 / 2 | 0 |
| `classroom_fol-inv3` | 62 | 62 | 62 | 35 / 40 | 61 / 61 | 84.08% / 94.63% | 2 / 2 | 30 |
| `classroom_fol-inv4` | 114 | 114 | 114 | 76 / 83 | 80 / 80 | 80.04% / 93.80% | 3 / 2 | 72 |
| `classroom_fol-inv5` | 131 | 131 | 131 | 62 / 99 | 109 / 109 | 75.58% / 97.44% | 6 / 4 | 44 |
| `classroom_fol-inv6` | 35 | 35 | 35 | 16 / 24 | 14 / 14 | 100.00% / 92.62% | 5 / 3 | 14 |
| `classroom_fol-inv7` | 39 | 39 | 39 | 27 / 34 | 21 / 21 | 76.96% / 97.89% | 4 / 3 | 15 |
| `classroom_fol-inv8` | 101 | 101 | 101 | 39 / 62 | 49 / 49 | 82.37% / 94.45% | 8 / 10 | 18 |
| `classroom_fol-inv9` | 107 | 107 | 107 | 33 / 53 | 44 / 44 | 81.79% / 95.35% | 7 / 7 | 29 |
| `classroom_fol-inv10` | 39 | 39 | 39 | 8 / 23 | 13 / 13 | 92.62% / 96.12% | 8 / 5 | 4 |
| `classroom_fol-inv11` | 540 | 540 | 540 | 260 / 357 | 127 / 127 | 69.50% / 96.76% | 18 / 13 | 204 |
| `classroom_fol-inv12` | 367 | 367 | 367 | 177 / 246 | 110 / 110 | 81.47% / 96.93% | 14 / 11 | 99 |
| `classroom_fol-inv13` | 122 | 122 | 122 | 37 / 55 | 21 / 21 | 78.93% / 91.53% | 11 / 16 | 12 |
| `classroom_fol-inv14` | 510 | 510 | 510 | 200 / 306 | 131 / 131 | 72.94% / 95.80% | 20 / 9 | 82 |
| `classroom_fol-inv15` | 203 | 203 | 203 | 32 / 44 | 45 / 45 | 91.63% / 93.46% | 20 / 23 | 15 |
| `classroom_rl-inv1` | 20 | 20 | 20 | 13 / 19 | 20 / 20 | 90.00% / 100.00% | 2 / 1 | 6 |
| `classroom_rl-inv2` | 8 | 8 | 8 | 2 / 6 | 8 / 8 | 94.74% / 95.45% | 1 / 1 | 2 |
| `classroom_rl-inv3` | 52 | 52 | 52 | 26 / 41 | 47 / 47 | 89.50% / 83.98% | 3 / 2 | 13 |
| `classroom_rl-inv4` | 130 | 130 | 130 | 57 / 81 | 61 / 61 | 66.67% / 92.91% | 5 / 4 | 55 |
| `classroom_rl-inv5` | 170 | 170 | 170 | 90 / 141 | 156 / 156 | 75.51% / 93.74% | 3 / 3 | 69 |
| `classroom_rl-inv6` | 108 | 108 | 108 | 47 / 65 | 66 / 66 | 96.94% / 89.73% | 3 / 3 | 21 |
| `classroom_rl-inv7` | 155 | 155 | 155 | 83 / 104 | 95 / 95 | 94.57% / 92.93% | 5 / 3 | 67 |
| `classroom_rl-inv8` | 96 | 96 | 96 | 36 / 50 | 30 / 30 | 89.77% / 94.07% | 6 / 6 | 21 |
| `classroom_rl-inv9` | 260 | 260 | 260 | 128 / 163 | 124 / 124 | 87.16% / 96.64% | 5 / 4 | 111 |
| `classroom_rl-inv10` | 233 | 233 | 233 | 61 / 98 | 67 / 67 | 87.72% / 89.52% | 4 / 6 | 12 |
| `classroom_rl-inv11` | 294 | 294 | 294 | 31 / 82 | 96 / 96 | 82.63% / 90.41% | 9 / 9 | 13 |
| `classroom_rl-inv12` | 143 | 143 | 143 | 19 / 37 | 35 / 35 | 88.90% / 89.16% | 7 / 8 | 12 |
| `classroom_rl-inv13` | 96 | 96 | 96 | 19 / 28 | 27 / 27 | 84.15% / 90.46% | 7 / 8 | 8 |
| `classroom_rl-inv14` | 242 | 242 | 242 | 4 / 16 | 83 / 83 | 75.48% / 89.62% | 12 / 14 | 1 |
| `classroom_rl-inv15` | 258 | 258 | 258 | 66 / 112 | 102 / 102 | 84.84% / 89.26% | 8 / 9 | 38 |
| `coursesNew-inv1` | 178 | 178 | 178 | 49 / 99 | 140 / 140 | 82.36% / 93.22% | 3 / 4 | 36 |
| `coursesNew-inv2` | 34 | 34 | 34 | 3 / 7 | 26 / 26 | 83.62% / 97.10% | 2 / 4 | 1 |
| `coursesNew-inv3` | 246 | 246 | 246 | 117 / 169 | 189 / 189 | 78.58% / 92.60% | 8 / 3 | 69 |
| `coursesNew-inv4` | 101 | 101 | 101 | 43 / 66 | 59 / 27 | 82.28% / 95.76% | 3 / 4 | 37 |
| `coursesNew-inv5` | 392 | 392 | 392 | 86 / 147 | 71 / 44 | 83.96% / 96.95% | 9 / 16 | 76 |
| `coursesNew-inv6` | 412 | 412 | 412 | 1 / 68 | 217 / 211 | 79.93% / 95.57% | 9 / 16 | 0 |
| `coursesNew-inv7` | 407 | 407 | 407 | 2 / 33 | 213 / 211 | 69.31% / 96.22% | 17 / 13 | 2 |
| `coursesNew-inv8` | 318 | 318 | 318 | 27 / 120 | 291 / 291 | 73.32% / 96.97% | 6 / 7 | 3 |
| `coursesNew-inv9` | 643 | 643 | 643 | 0 / 2 | 135 / 135 | 84.93% / 96.78% | 20 / 21 | 0 |
| `coursesNew-inv10` | 126 | 126 | 126 | 29 / 55 | 51 / 51 | 80.46% / 93.05% | 5 / 7 | 14 |
| `coursesNew-inv11` | 139 | 139 | 139 | 0 / 38 | 106 / 106 | 71.64% / 94.44% | 6 / 8 | 0 |
| `coursesNew-inv12` | 215 | 215 | 215 | 74 / 123 | 136 / 136 | 71.45% / 95.33% | 14 / 7 | 60 |
| `coursesNew-inv13` | 136 | 136 | 136 | 0 / 1 | 58 / 52 | 85.13% / 94.42% | 20 / 20 | 0 |
| `coursesNew-inv14` | 199 | 199 | 199 | 0 / 5 | 125 / 125 | 82.08% / 95.17% | 16 / 21 | 0 |
| `coursesNew-inv15` | 37 | 37 | 37 | 0 / 0 | 11 / 11 | 84.53% / 95.54% | 47 / 66 | 0 |
| `coursesOld-inv1` | 536 | 536 | 536 | 185 / 328 | 367 / 366 | 86.46% / 93.02% | 3 / 5 | 119 |
| `coursesOld-inv2` | 74 | 74 | 74 | 13 / 36 | 66 / 66 | 83.86% / 96.18% | 2 / 3 | 6 |
| `coursesOld-inv3` | 501 | 501 | 501 | 224 / 371 | 381 / 376 | 75.68% / 89.43% | 6 / 3 | 71 |
| `coursesOld-inv4` | 380 | 380 | 380 | 231 / 314 | 170 / 66 | 88.00% / 95.02% | 3 / 5 | 163 |
| `coursesOld-inv5` | 790 | 790 | 790 | 250 / 429 | 169 / 110 | 83.04% / 95.34% | 7 / 11 | 223 |
| `coursesOld-inv6` | 681 | 681 | 681 | 270 / 370 | 257 / 246 | 79.57% / 93.49% | 13 / 12 | 119 |
| `coursesOld-inv7` | 758 | 758 | 758 | 0 / 83 | 293 / 286 | 73.41% / 94.94% | 16 / 15 | 0 |
| `coursesOld-inv8` | 505 | 505 | 505 | 254 / 351 | 351 / 351 | 74.89% / 93.18% | 7 / 5 | 131 |
| `coursesOld-inv9` | 1128 | 1128 | 1128 | 280 / 447 | 146 / 146 | 71.79% / 96.37% | 15 / 20 | 112 |
| `coursesOld-inv10` | 148 | 148 | 148 | 28 / 44 | 86 / 86 | 80.38% / 92.64% | 4 / 6 | 12 |
| `coursesOld-inv11` | 247 | 247 | 247 | 34 / 101 | 173 / 173 | 76.30% / 94.63% | 7 / 8 | 2 |
| `coursesOld-inv12` | 514 | 514 | 514 | 146 / 234 | 243 / 243 | 75.36% / 90.83% | 14 / 11 | 45 |
| `coursesOld-inv13` | 392 | 392 | 392 | 59 / 111 | 108 / 102 | 83.80% / 90.88% | 18 / 18 | 35 |
| `coursesOld-inv14` | 425 | 425 | 425 | 55 / 92 | 245 / 238 | 74.97% / 92.56% | 15 / 22 | 11 |
| `coursesOld-inv15` | 261 | 261 | 261 | 0 / 0 | 39 / 39 | 79.71% / 94.91% | 43 / 51 | 0 |
| `cv_v1-inv1` | 116 | 116 | 116 | 65 / 78 | 64 / 64 | 89.48% / 88.41% | 3 / 5 | 45 |
| `cv_v1-inv2` | 99 | 99 | 99 | 18 / 23 | 56 / 56 | 85.63% / 90.75% | 6 / 9 | 3 |
| `cv_v1-inv3` | 172 | 172 | 172 | 34 / 41 | 27 / 27 | 73.46% / 95.76% | 14 / 21 | 15 |
| `cv_v1-inv4` | 315 | 315 | 315 | 5 / 5 | 13 / 13 | 83.23% / 92.51% | 22 / 26 | 0 |
| `cv_v2-inv1` | 8 | 8 | 8 | 2 / 4 | 7 / 7 | 85.71% / 78.57% | 3 / 6 | 0 |
| `cv_v2-inv2` | 25 | 25 | 25 | 0 / 3 | 14 / 14 | 82.08% / 86.99% | 10 / 10 | 0 |
| `cv_v2-inv3` | 45 | 45 | 45 | 0 / 0 | 5 / 5 | 60.02% / 96.57% | 17 / 18 | 0 |
| `cv_v2-inv4` | 138 | 138 | 138 | 2 / 10 | 10 / 10 | 67.12% / 97.18% | 24 / 25 | 2 |
| `graphs-inv1` | 217 | 217 | 217 | 90 / 146 | 172 / 172 | 92.76% / 90.95% | 5 / 5 | 57 |
| `graphs-inv2` | 120 | 120 | 120 | 34 / 77 | 90 / 90 | 88.33% / 92.40% | 4 / 4 | 26 |
| `graphs-inv3` | 93 | 93 | 93 | 21 / 45 | 82 / 82 | 94.26% / 89.04% | 4 / 4 | 20 |
| `graphs-inv4` | 172 | 172 | 172 | 91 / 130 | 120 / 120 | 91.64% / 78.98% | 3 / 4 | 20 |
| `graphs-inv5` | 93 | 93 | 93 | 44 / 69 | 78 / 78 | 96.14% / 71.47% | 2 / 2 | 15 |
| `graphs-inv6` | 458 | 458 | 458 | 202 / 317 | 230 / 230 | 94.22% / 91.39% | 7 / 11 | 156 |
| `graphs-inv7` | 125 | 125 | 125 | 52 / 88 | 95 / 92 | 86.80% / 82.60% | 3 / 4 | 44 |
| `graphs-inv8` | 54 | 54 | 54 | 9 / 13 | 36 / 36 | 92.33% / 88.91% | 5 / 7 | 5 |
| `lts-inv1` | 95 | 95 | 95 | 52 / 69 | 14 / 14 | 79.56% / 93.50% | 3 / 7 | 8 |
| `lts-inv2` | 42 | 42 | 42 | 4 / 6 | 39 / 39 | 77.32% / 93.47% | 4 / 4 | 4 |
| `lts-inv3` | 268 | 268 | 268 | 133 / 170 | 46 / 46 | 83.33% / 92.49% | 5 / 8 | 17 |
| `lts-inv4` | 434 | 434 | 434 | 18 / 47 | 42 / 1 | 89.80% / 92.71% | 16 / 17 | 11 |
| `lts-inv5` | 210 | 210 | 210 | 20 / 42 | 19 / 18 | 91.06% / 97.53% | 9 / 12 | 5 |
| `lts-inv6` | 32 | 32 | 32 | 9 / 14 | 12 / 12 | 96.46% / 86.67% | 3 / 4 | 3 |
| `lts-inv7` | 186 | 186 | 186 | 10 / 21 | 12 / 0 | 89.97% / 97.15% | 40 / 33 | 4 |
| `productionLineNew-inv1` | 39 | 39 | 39 | 14 / 21 | 11 / 11 | 93.39% / 90.00% | 5 / 5 | 10 |
| `productionLineNew-inv2` | 215 | 215 | 215 | 54 / 101 | 44 / 44 | 92.44% / 96.26% | 10 / 13 | 51 |
| `productionLineNew-inv3` | 43 | 43 | 43 | 20 / 24 | 31 / 31 | 90.56% / 90.99% | 2 / 2 | 6 |
| `productionLineNew-inv4` | 117 | 117 | 117 | 36 / 45 | 36 / 36 | 94.74% / 94.50% | 10 / 11 | 20 |
| `productionLineNew-inv5` | 118 | 118 | 118 | 3 / 9 | 81 / 81 | 78.99% / 92.00% | 8 / 12 | 2 |
| `productionLineNew-inv6` | 167 | 167 | 167 | 98 / 118 | 136 / 136 | 89.77% / 90.86% | 2 / 4 | 87 |
| `productionLineNew-inv7` | 234 | 234 | 234 | 127 / 181 | 195 / 195 | 87.59% / 81.74% | 8 / 9 | 79 |
| `productionLineNew-inv8` | 105 | 105 | 105 | 17 / 35 | 99 / 99 | 84.55% / 94.95% | 4 / 7 | 16 |
| `productionLineNew-inv9` | 398 | 398 | 398 | 23 / 39 | 53 / 53 | 88.34% / 93.32% | 26 / 27 | 20 |
| `productionLineNew-inv10` | 97 | 97 | 97 | 0 / 0 | 27 / 27 | 94.89% / 89.60% | 13 / 19 | 0 |
| `productionLine_v1-inv1` | 94 | 94 | 94 | 74 / 77 | 19 / 19 | 90.22% / 95.14% | 5 / 8 | 53 |
| `productionLine_v1-inv2` | 101 | 101 | 101 | 40 / 52 | 88 / 87 | 83.66% / 91.62% | 3 / 4 | 40 |
| `productionLine_v1-inv3` | 30 | 30 | 30 | 8 / 15 | 16 / 9 | 89.86% / 93.40% | 4 / 6 | 8 |
| `productionLine_v1-inv4` | 135 | 135 | 135 | 22 / 39 | 72 / 64 | 86.23% / 91.84% | 8 / 8 | 18 |
| `productionLine_v2-inv1` | 59 | 59 | 59 | 14 / 27 | 36 / 36 | 93.40% / 88.21% | 5 / 7 | 11 |
| `productionLine_v2-inv2` | 298 | 298 | 298 | 108 / 146 | 91 / 91 | 91.91% / 94.53% | 7 / 10 | 77 |
| `productionLine_v2-inv3` | 42 | 42 | 42 | 5 / 12 | 22 / 22 | 85.42% / 90.68% | 2 / 3 | 4 |
| `productionLine_v2-inv4` | 154 | 154 | 154 | 40 / 58 | 57 / 57 | 91.57% / 91.45% | 8 / 8 | 23 |
| `productionLine_v2-inv5` | 216 | 216 | 216 | 76 / 103 | 137 / 137 | 78.55% / 92.95% | 8 / 9 | 68 |
| `productionLine_v2-inv6` | 288 | 288 | 288 | 153 / 202 | 215 / 215 | 91.97% / 91.13% | 4 / 6 | 107 |
| `productionLine_v2-inv7` | 249 | 249 | 249 | 143 / 188 | 222 / 222 | 82.99% / 86.43% | 5 / 4 | 56 |
| `productionLine_v2-inv8` | 134 | 134 | 134 | 33 / 64 | 114 / 114 | 85.88% / 94.71% | 4 / 5 | 23 |
| `productionLine_v2-inv9` | 514 | 514 | 514 | 71 / 109 | 130 / 130 | 88.75% / 91.96% | 15 / 20 | 60 |
| `productionLine_v2-inv10` | 291 | 291 | 291 | 0 / 4 | 50 / 50 | 95.09% / 89.67% | 12 / 16 | 0 |
| `socialMedia-inv1` | 933 | 933 | 933 | 573 / 701 | 540 / 540 | 88.14% / 91.46% | 3 / 5 | 460 |
| `socialMedia-inv2` | 310 | 310 | 310 | 191 / 232 | 202 / 202 | 91.35% / 86.30% | 3 / 3 | 130 |
| `socialMedia-inv3` | 1797 | 1797 | 1797 | 414 / 716 | 492 / 491 | 83.92% / 93.19% | 10 / 14 | 282 |
| `socialMedia-inv4` | 824 | 824 | 824 | 345 / 586 | 597 / 597 | 82.08% / 95.96% | 5 / 6 | 269 |
| `socialMedia-inv5` | 2018 | 2018 | 2018 | 1490 / 1738 | 647 / 647 | 87.71% / 95.53% | 8 / 8 | 798 |
| `socialMedia-inv6` | 394 | 394 | 394 | 127 / 203 | 163 / 163 | 84.74% / 93.65% | 4 / 6 | 70 |
| `socialMedia-inv7` | 2569 | 2569 | 2569 | 1066 / 1335 | 754 / 753 | 88.00% / 95.48% | 20 / 13 | 821 |
| `socialMedia-inv8` | 607 | 607 | 607 | 57 / 154 | 207 / 207 | 89.94% / 94.31% | 12 / 14 | 43 |
| `trainStationNew-inv1` | 376 | 376 | 376 | 141 / 219 | 175 / 175 | 84.97% / 86.94% | 7 / 8 | 95 |
| `trainStationNew-inv2` | 185 | 185 | 185 | 85 / 138 | 84 / 84 | 91.57% / 92.10% | 4 / 5 | 67 |
| `trainStationNew-inv3` | 1143 | 1143 | 1143 | 654 / 815 | 457 / 457 | 82.83% / 93.53% | 9 / 7 | 568 |
| `trainStationNew-inv4` | 313 | 313 | 313 | 124 / 178 | 142 / 140 | 81.74% / 95.58% | 9 / 7 | 73 |
| `trainStationNew-inv5` | 637 | 637 | 637 | 173 / 263 | 184 / 180 | 89.61% / 92.36% | 17 / 11 | 139 |
| `trainStationNew-inv6` | 257 | 257 | 257 | 114 / 189 | 184 / 184 | 94.75% / 89.55% | 7 / 4 | 62 |
| `trainStationNew-inv7` | 172 | 172 | 172 | 33 / 50 | 101 / 101 | 79.31% / 87.76% | 10 / 15 | 32 |
| `trainStationNew-inv8` | 403 | 403 | 403 | 223 / 287 | 261 / 261 | 88.42% / 91.38% | 3 / 5 | 179 |
| `trainStationNew-inv9` | 537 | 537 | 537 | 128 / 226 | 197 / 197 | 86.17% / 90.70% | 10 / 10 | 92 |
| `trainStationNew-inv10` | 293 | 293 | 293 | 69 / 124 | 208 / 208 | 76.50% / 92.25% | 10 / 11 | 30 |
| `trainStationOld-inv1` | 39 | 39 | 39 | 5 / 10 | 29 / 29 | 67.21% / 84.49% | 11 / 8 | 3 |
| `trainStationOld-inv2` | 64 | 64 | 64 | 38 / 48 | 24 / 24 | 61.34% / 97.78% | 3 / 5 | 10 |
| `trainStationOld-inv3` | 150 | 150 | 150 | 32 / 74 | 28 / 28 | 72.20% / 87.60% | 9 / 7 | 24 |
| `trainStationOld-inv4` | 60 | 60 | 60 | 20 / 30 | 39 / 39 | 73.55% / 92.02% | 8 / 4 | 14 |
| `trainStationOld-inv5` | 122 | 122 | 122 | 0 / 0 | 1 / 1 | 51.85% / 96.95% | 46 / 38 | 0 |
| `trainStationOld-inv6` | 79 | 79 | 79 | 0 / 0 | 18 / 18 | 40.47% / 85.64% | 22 / 13 | 0 |
| `trainStationOld-inv7` | 33 | 33 | 33 | 0 / 1 | 23 / 23 | 52.66% / 88.84% | 11 / 6 | 0 |
| `trainStationOld-inv8` | 31 | 31 | 31 | 0 / 0 | 6 / 6 | 72.55% / 84.43% | 27 / 21 | 0 |
| `trainStationOld-inv9` | 82 | 82 | 82 | 0 / 0 | 1 / 1 | 52.79% / 93.39% | 19 / 7 | 0 |
| `trainStationOld-inv10` | 2 | 2 | 2 | 0 / 1 | 2 / 2 | 62.50% / 87.50% | 14 / 1 | 0 |
| `trainStationOld-inv11` | 16 | 16 | 16 | 6 / 7 | 12 / 12 | 85.09% / 91.95% | 11 / 4 | 4 |
| `trainStationOld-inv13` | 10 | 10 | 10 | 4 / 6 | 0 / 0 | 29.92% / 98.88% | 9 / 9 | 4 |
| `trainStationOld-inv14` | 26 | 26 | 26 | 0 / 0 | 2 / 2 | 66.74% / 90.16% | 35 / 31 | 0 |
| `trainStationOld-inv15` | 31 | 31 | 31 | 0 / 0 | 7 / 7 | 69.00% / 93.95% | 13 / 14 | 0 |
| `trainStationOld-inv16` | 6 | 6 | 6 | 0 / 0 | 2 / 0 | 75.00% / 100.00% | 26 / 14 | 0 |
| `trainStationOld-inv17` | 14 | 14 | 14 | 0 / 0 | 0 / 0 | 38.92% / 99.76% | 31 / 30 | 0 |
| `trash_fol-inv1` | 47 | 47 | 47 | 13 / 29 | 47 / 47 | 72.41% / 89.13% | 2 / 3 | 13 |
| `trash_fol-inv2` | 27 | 27 | 27 | 10 / 20 | 25 / 23 | 74.31% / 86.40% | 2 / 3 | 10 |
| `trash_fol-inv3` | 11 | 11 | 11 | 0 / 11 | 11 / 11 | 85.71% / 82.50% | 3 / 1 | 0 |
| `trash_fol-inv4` | 47 | 47 | 47 | 25 / 37 | 46 / 46 | 81.10% / 94.38% | 3 / 2 | 7 |
| `trash_fol-inv5` | 77 | 77 | 77 | 40 / 57 | 67 / 67 | 84.79% / 96.11% | 2 / 3 | 39 |
| `trash_fol-inv6` | 129 | 129 | 129 | 54 / 80 | 94 / 94 | 80.43% / 93.60% | 3 / 6 | 31 |
| `trash_fol-inv7` | 215 | 215 | 215 | 122 / 162 | 209 / 209 | 91.32% / 91.65% | 5 / 5 | 21 |
| `trash_fol-inv8` | 9 | 9 | 9 | 5 / 7 | 9 / 9 | 79.31% / 80.00% | 2 / 3 | 1 |
| `trash_fol-inv9` | 37 | 37 | 37 | 4 / 8 | 32 / 32 | 82.77% / 90.62% | 4 / 9 | 4 |
| `trash_fol-inv10` | 99 | 99 | 99 | 31 / 50 | 87 / 86 | 73.51% / 93.88% | 4 / 6 | 15 |
| `trash_ltl-inv1` | 28 | 28 | 28 | 10 / 16 | 23 / 23 | 78.81% / 98.75% | 5 / 3 | 10 |
| `trash_ltl-inv2` | 143 | 143 | 143 | 100 / 127 | 65 / 65 | 33.25% / 97.72% | 5 / 4 | 76 |
| `trash_ltl-inv3` | 11 | 11 | 11 | 7 / 10 | 11 / 11 | 41.67% / 82.14% | 6 / 1 | 7 |
| `trash_ltl-inv4` | 127 | 127 | 127 | 63 / 115 | 123 / 123 | 66.63% / 89.72% | 6 / 3 | 42 |
| `trash_ltl-inv5` | 358 | 358 | 358 | 176 / 289 | 153 / 153 | 74.43% / 90.43% | 5 / 5 | 129 |
| `trash_ltl-inv6` | 267 | 267 | 267 | 144 / 208 | 188 / 188 | 53.41% / 92.52% | 9 / 2 | 122 |
| `trash_ltl-inv7` | 53 | 53 | 53 | 29 / 45 | 45 / 45 | 47.78% / 92.16% | 6 / 1 | 27 |
| `trash_ltl-inv8` | 278 | 278 | 278 | 138 / 186 | 123 / 123 | 56.17% / 92.56% | 12 / 6 | 46 |
| `trash_ltl-inv9` | 89 | 89 | 89 | 41 / 51 | 69 / 69 | 55.00% / 88.38% | 11 / 2 | 18 |
| `trash_ltl-inv10` | 294 | 294 | 294 | 196 / 256 | 36 / 36 | 31.84% / 97.11% | 8 / 7 | 97 |
| `trash_ltl-inv11` | 133 | 133 | 133 | 33 / 84 | 92 / 92 | 57.80% / 87.01% | 11 / 3 | 30 |
| `trash_ltl-inv12` | 371 | 371 | 371 | 106 / 237 | 260 / 260 | 50.81% / 91.81% | 8 / 4 | 82 |
| `trash_ltl-inv13` | 38 | 38 | 38 | 6 / 15 | 36 / 36 | 47.80% / 88.62% | 4 / 2 | 3 |
| `trash_ltl-inv14` | 94 | 94 | 94 | 14 / 38 | 63 / 63 | 62.89% / 88.30% | 14 / 4 | 14 |
| `trash_ltl-inv15` | 50 | 50 | 50 | 33 / 43 | 26 / 26 | 45.33% / 92.12% | 8 / 4 | 23 |
| `trash_ltl-inv16` | 96 | 96 | 96 | 31 / 58 | 58 / 58 | 41.89% / 91.21% | 11 / 2 | 25 |
| `trash_ltl-inv17` | 99 | 99 | 99 | 0 / 20 | 40 / 40 | 52.93% / 84.01% | 12 / 5 | 0 |
| `trash_ltl-inv18` | 194 | 194 | 194 | 79 / 121 | 97 / 97 | 46.10% / 93.18% | 6 / 3 | 61 |
| `trash_ltl-inv19` | 64 | 64 | 64 | 26 / 33 | 30 / 30 | 40.68% / 95.40% | 11 / 3 | 13 |
| `trash_ltl-inv20` | 80 | 80 | 80 | 36 / 59 | 46 / 46 | 33.03% / 98.71% | 5 / 2 | 9 |
| `trash_rl-inv1` | 33 | 33 | 33 | 14 / 23 | 32 / 32 | 89.87% / 92.22% | 2 / 2 | 6 |
| `trash_rl-inv2` | 44 | 44 | 44 | 21 / 35 | 42 / 42 | 84.17% / 92.96% | 2 / 3 | 21 |
| `trash_rl-inv3` | 41 | 41 | 41 | 14 / 25 | 40 / 40 | 94.40% / 80.00% | 3 / 2 | 9 |
| `trash_rl-inv4` | 75 | 75 | 75 | 46 / 63 | 74 / 74 | 94.43% / 89.72% | 4 / 2 | 17 |
| `trash_rl-inv5` | 178 | 178 | 178 | 83 / 132 | 116 / 116 | 89.63% / 90.56% | 3 / 4 | 77 |
| `trash_rl-inv6` | 190 | 190 | 190 | 100 / 143 | 103 / 99 | 82.05% / 92.99% | 3 / 6 | 48 |
| `trash_rl-inv7` | 254 | 254 | 254 | 138 / 204 | 241 / 241 | 95.74% / 90.48% | 4 / 3 | 28 |
| `trash_rl-inv8` | 6 | 6 | 6 | 0 / 1 | 6 / 6 | 92.86% / 91.67% | 2 / 3 | 0 |
| `trash_rl-inv9` | 108 | 108 | 108 | 22 / 37 | 85 / 85 | 97.79% / 88.59% | 4 / 6 | 18 |
| `trash_rl-inv10` | 128 | 128 | 128 | 57 / 92 | 114 / 114 | 90.24% / 85.12% | 4 / 6 | 27 |

## Evidence limits and integrity

This is a complete 181-invariant **availability and guidance-feature audit**, not 181 independent human quality evaluations. Every archive contains 61,598 distinct expected cases; all incorrect subsets and per-invariant hint counts are checked against the published final report. TAR response/result hash bindings are also checked. The output records archive, source-manifest, selection, current-response, collector/runtime and analyzer SHA-256 identities. Original run artifacts, the ten-case pilot, and their provenance remain unchanged.

See the [full comparison](alloy4fun-comparison.md) for runtime, resource configuration, fold policy, limitations, the two corrected legacy labels and the corrected-engine rerun history. Correct controls are intentionally excluded from the availability denominator here; a nonempty hint on a held-out CORRECT predicate is not evidence that the original full correct-pool policy failed.
