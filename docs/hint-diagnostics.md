# Alloy4Fun engine and corpus diagnostics

This report investigates the completed Canonical and raw AST runs on the
61,598-model cohort and the completed TAR baseline. It accompanies the
[final five-arm comparison](alloy4fun-comparison.md), completed on 2026-09-30.
Original measured results were preserved while separate diagnostic programs checked
the failures and the 1,670 corpus-labelled CORRECT inputs with positive
Canonical distance.

**Findings:** the original AST error message masked an infrastructure failure mechanism;
the canonical display sometimes omits a quantified body; and two CORRECT labels
fail the intended Alloy check. The structural minimum and self-distance checks
did not find an arithmetic or pool-selection error in the 1,670 cases examined.
The display and temporary-file lifecycle were corrected for the completed rerun;
the two legacy labels have explicit hash-bound overrides. TAR's final four
engine errors and separate failed/unknown repair checks are recorded below.

## 1. AST: temporary-file failures are misreported as AST limits

The raw AST run recorded three `REFERENCE_POOL_UNAVAILABLE` errors: two on
CORRECT controls and one on an OVERCONSTRAINED input. The original public message said the
reference pool could not be evaluated within the raw AST limits. However,
[AstFeedback](../engine/src/live/AstFeedback.java) catches **every `Throwable`**
in that phase and returns this same message, discarding the exception details.
That message did not establish that a limit was exceeded. The corrected message reports an incomplete reference comparison without inventing a size-limit cause.

The diagnostic checked the original inputs against the frozen source, classes,
payloads and folds:

- All 87 reference comparisons passed when repeated.
- Fifteen complete evaluations passed; a further 200 repetitions of the three
  original case IDs also passed.
- Learner trees contained 6, 29 and 9 nodes. The largest candidate contained
  32 nodes. The largest forest-work product was 8,560, below the 8,000,000
  limit. These failures are not reproducible size-limit violations.
- A separate warmed replay of 998 requests reproduced three reference-pool
  failures. The private diagnostic captured `ErrorFatal`, caused by
  `IOException: No space left on device`, inside Alloy's
  `CompUtil.parseEverything_fromString`. Heap use stayed below 145 MB in the
  approximately 519 MB JVM heap.

The filesystem failure is reproducible even when global disk space is available:

| Empty-file creation probe | Shared `/tmp` failures | Fresh directory on the same filesystem |
| --- | ---: | ---: |
| Initial 50,000 creations per directory | 16 `ENOSPC` failures | 0 failures |
| Recheck after the reported disk cleanup, 50,000 per directory | 28 `ENOSPC` failures | 0 failures |

The recheck had approximately 109.7 billion available bytes and 15 million free
inodes. Each probe immediately removed only the files it had just created.
There were approximately **8.76 million `alloy_heredoc*.als` files** in the shared
temporary directory. Its own directory file was approximately 588 MB.
This establishes a directory-specific creation problem. Saturation of an ext4
directory index is a strong explanation, but the kernel-internal cause was not
directly verified.

Alloy creates these source files and registers `deleteOnExit`. The benchmark's
[Live worker shutdown](../benchmarks/alloy4fun/live/adapter.py) and FM24 native
worker shutdown originally used `SIGKILL`, including at normal benchmark completion. That
bypasses JVM exit cleanup. This is a **benchmark lifecycle defect** that can
leave generated parser files behind. The production portal normally runs a
separate JVM to completion, but killed/timeout processes can also leave files.
The entire existing temporary-file population is not attributed to this one run.

The original three exceptions were discarded, so their exact historical causes
cannot be proved retrospectively. The warmed replay demonstrates a matching
failure mechanism. None of the three original outcomes has been overwritten by
a successful retry.

## 2. Canonical: correctness does not imply identity to a held-out pool

**Attribution correction:** the fold restriction below was introduced by the
benchmark. The original `Alloy4FunAugmenter.buildReferences` includes the oracle
and every successful CORRECT submission (with AST deduplication), and
`nearestIncorrectMatches` ranks only incorrect submissions. Consequently, these
held-out CORRECT results do not demonstrate a failure of the user's complete-pool
construction. The portal's import also has no fold exclusion; its admission
checks preserve the selected exercise's environment and oracle.

The benchmark uses whole-branch cross-validation. A model's own branch is absent
from its training pool; every pool still includes the teacher oracle. The
production method selects the closest member of that **finite known-correct
pool**. It does not search the set of all logically correct predicates or invoke
Alloy equivalence checking while calculating structural distance.

Reconstructing the selected pools and checking every positive CORRECT case gave:

| Check | Result |
| --- | ---: |
| Positive-distance CORRECT cases examined | 1,670 |
| Recomputed learner/reference distances | 116,455 |
| Recorded minimum different from recomputed minimum | 0 |
| Selected-pool reference with distance zero | 0 |
| Raw or token-identical learner body in the selected pool | 0 |
| Nonzero self-distance, including independent reparsing | 0 / 1,670 |
| Identical submission present only in the held-out fold | 694 |

Thus these checks find no omitted zero-cost candidate under the declared pool
policy. Adding the learner itself as a diagnostic reference gives zero in every
tested case. This does not prove the distance implementation correct for all
inputs; it checks these recorded minima and identities.

The normalizer applies implemented structural rewrites. It is not a complete
canonical representation of arbitrary Alloy semantics under all facts and
bounds. Two predicates can therefore satisfy the same bounded specification
while retaining different normalized structures.

To check that this explanation was not hiding incorrect labels, all 1,670
original learner bodies were independently checked against the **named
`correct` command**, preserving facts and scopes, with SAT4J and
`noOverflow=false`:

| Outcome | Cases |
| --- | ---: |
| No counterexample within the original bounds | 1,668 |
| Counterexample found | 2 |
| Timeout, error or unknown | 0 |

These are bounded equivalence checks, not proofs of equivalence at every scope.
They establish that a positive structural distance is not a reliable declaration
that a learner needs a correctness repair.

A follow-up membership audit of the corrected cohort found **19,210/19,210**
CORRECT bodies represented in full compatible-context pools, and **7,804** with
no identical tokenized body after their query fold is removed. All **1,668**
Canonical and **4,089** AST nonempty CORRECT results fall in the latter group;
zero occur when an identical tokenized reference remains in the held-out pool.
The artifact is `build/benchmarks/alloy4fun-v2/pool-policy-membership-audit.json`.
It records lexical membership and input hashes; it does not claim measured
full-pool distances or runtimes. The original-pool operational experiment remains
distinct from the completed held-out arms.

## 3. A real canonical-display defect hides quantified bodies

There is a separate implementation defect behind some apparent zero-distance
expectations: the displayed canonical string is incomplete.

Among the 1,670 positive cases, **52 learner predicates contain 55 quantified
nodes whose bodies disappear from the display**: 46 `ONE`, seven `NO`, and two
`LONE` nodes. Eight cases had the same displayed canonical string as a reference despite a positive structural distance. The omission explains seven of those collisions; one remains after the body is restored and is investigated separately below. These 52 cases are the affected subset of the 1,670-case audit, not a full-corpus prevalence estimate.

[CanonicalDistance.eGraphFormula](../vendor/acgn/src/is/fivefivefive/CanDis/core/CanonicalDistance.java)
previously routed these opcodes through `unaryFormula`, which printed only child zero.
Residual quantified nodes contain a declaration child and a body child. The
distance calculation still traverses that body.
[CanonicalLocator](../engine/src/live/CanonicalLocator.java) previously followed the same
incomplete rendering, leaving the omitted body's canonical location unavailable.

This public synthetic witness reproduced the defect without using any private
exercise solution:

```alloy
sig A { r: set A }
pred inv1 { some A and (lone a: A | some a.r) }
```

Compare it with the same model containing `no a.r` in place of `some a.r`.
Before the fix, both displayed:

```text
root normal form := inv1(((LONE DECLROOT_default((ONE A), a)) && (SOME A)))
```

The distance is **1**, before and after the fix. Previously the raw locator selected the correct changed expression while the canonical locator reported unavailable. Replacing `lone` with
`one` also reproduces the omission. With two atoms and no relation edges, the
two LONE predicates have different truth values, so collapsing their displayed
text does not justify changing their structural distance to zero.

Run the regression witness after building the engine and benchmark bridge:

```bash
python3 benchmarks/alloy4fun/reproduce_display_collision.py
```

It now checks that both witnesses retain their bodies, have distinct canonical strings, retain distance 1, and locate the changed canonical node. It uses an owned temporary directory and removes it after the JVM exits.

The fix applies unary rendering only to nodes with exactly one child; residual quantifiers use the existing all-child rendering and corresponding locator traversal. Of the 55 omitted bodies, 38 were rooted at `IN`, 11 at `AND`, four at `EQUALS`, one at `SOME`, and one at `ONE`. Three predicates contained two affected nodes. All affected nodes had exactly two children: declaration and body. This is an operator-arity dispatch defect, not excessive whitespace or a numerical-distance error.

Replaying all **52 original requests and their unchanged reference pools** restored all **55** body spans. Distances and operation fields other than `canonicalLocation` were identical; exact canonical highlights increased **341 → 588**. Fourteen focused rendering, highlighting, and vendor-provenance checks passed.

### One remaining display collision has a different cause

`productionLine_v2/correct/XDQrJquXurHSvKuXF_inv2.als` still has equal displayed text and distance **2** after its body is restored. Reordering conjunction branches moves a local `ONE`-bound variable to a different structural binding path. The binding matcher permits the relevant remapping for `ALL` and `SOME`, but requires identical paths for `ONE`. It therefore charges two variable replacements even though both displays use the same source variable name. The raw locator selects the actual variable occurrence, not both textual matches.

A separate diagnostic supplied the explicit correspondence between the four binders and obtained matrix cost **0** with the same graphs. This identifies a conservative, position-sensitive binder-matching limitation; it is separate from the repaired body omission. The rerun retains the production matching policy and records its nonzero result. Equal displayed text is not an injective encoding of the graph's binding identities, and this audit does not claim otherwise.

## 4. Two stored CORRECT labels are wrong

The two counterexample-producing records are
`socialMedia/correct/6j7rC3GMvjoGpyX7u_inv1.als` and
`socialMedia/correct/RjBNwdcpzytR8C39D_inv1.als`. They share identical source
SHA-256 `3832147bb3c35b0fcc15615abaaa4d8d7abda89cff27cd03f658fbdab7fffd1c`,
contain an empty `inv1`, and each has Canonical distance 5. Their intended
correctness check finds a counterexample.

The source also contains an extra earlier check. The current ACGN
`DataClassify.java` chooses commands by numerical position, which is fragile,
but executing that unchanged classifier on this source returns
`UNDERCONSTRAINED`, **not CORRECT**. Thus the current positional selection does
not explain these two stored labels. Their historical provenance is now established: both exact files were already stored under `socialMedia/correct/` in upstream [commit c64be99](https://github.com/AlloyUserStudy/Alloy4FunDataAnalysis/commit/c64be99b6cfa2e7cc34fe55b42f413c69c3b270a), dated **2024-06-28**. They remained byte-identical in the local 2025 legacy snapshot and ACGN import `496795d21879af730d138cf103cc99be590f5e83` (2026-07-25). This confirms the labels predate ACGN's import.

The original 2022 Alloy4Fun records already contain the empty `inv1` and record execution of **`Injectividade`**, command index 0, with no counterexample (`sat=0`). That auxiliary assertion is different from the intended `correct` check at index 1. It likely contributed to the legacy label, but the upstream repository does not contain its classification program; the exact historical labeling algorithm is not proven. A full 66,080-file hash scan finds exactly these two copies.

For the rerun, [the correction registry](../benchmarks/alloy4fun/protocol/label-corrections.json) changes their effective labels to **UNDERCONSTRAINED**, bound to both case ID and source SHA-256. Original corpus files and labels remain preserved. The intended `correct` check is satisfiable, `over` is unsatisfiable, and `under` is satisfiable. Thus the rerun retains all **61,598** models, with **42,388 incorrect** cases and **19,210 CORRECT** controls.

A constructed counterexample was saved privately as Alloy instance XML. The two
records do **not** contaminate any selected training pool in this experiment:
their exact environment partition contains only these two submissions, both in
fold zero, so each query excludes both and retains its teacher oracle. No other
query shares that context.

This diagnostic validated the 1,670 positive CORRECT cases, not all corpus labels.
The remaining labels have not acquired independent correctness guarantees from
this investigation.

## 5. TAR completion 9,964 fails while printing a found repair

The completed guarded TAR run recorded `engine_error` for
`trash_rl/over/zK2D3ghpYBcs2ux86_inv9.als`, at **completion index 9,964**
(completion order differs from source-manifest order). Its original request took
**1.110 seconds**. The saved native response reports mutation depth 1, six
attempted candidates, 155 generated candidates, and
`java.lang.IllegalArgumentException`; no serialized solution or native hint
trace was returned.

An isolated diagnostic using the same frozen TAR classes reproduced the failure
after native search found a one-mutation candidate passing TAR's own bounded
check. Serialization then threw at `pt.haslab.util.ExprToString.java:104`:
`Can't stringify ExprList with op 'TOTALORDER'`. The actual trigger is the
`DISJOINT` printer: its branch at lines 94–100 lacks a terminating `break`, so it
falls through into the unsupported `TOTALORDER` branch. The learner contains a
`disj` expression, which survives in the found candidate. The error text names
the branch reached by fall-through, rather than the original construct.

[TarRunner](../benchmarks/alloy4fun/tar/TarRunner.java) calls this printer before
storing the solution and collecting mutator hints. Its catch then sets
`solved=false`, obscuring the distinction between failed search and failed
output. The upstream TAR `RepairCLI` also uses the same printer for solution
output. Thus this is a defect in TAR's output helper, exercised by the benchmark
adapter; it is not an ACGN distance failure, timeout, or OOM event. No independent
repair validation occurred for the original request, so the discovered native
candidate is not credited as an independently verified repair or an emitted hint.

The original benchmark row and runtime are preserved. Diagnostic source, the
copied original row/response, stack trace, source/build hashes, and memory evidence
are under `build/benchmarks/tar-error-9964/`; `report.json` records the result.
The diagnostic ran separately at **2026-09-30 08:49:48.903–08:49:50.155 UTC**,
overlapping the ongoing TAR batch for approximately 1.25 seconds. Its service
had a 768 MiB memory ceiling, one-CPU quota, reduced priority, and no swap; measured
peak memory was about 80 MiB, with zero OOM events and no residual scratch files.
This short diagnostic overlap is disclosed as a timing limitation and is not
merged into benchmark results. Correcting this printer would require a separately
versioned adapter/baseline measurement, not rewriting the recorded outcome.

## 6. Final TAR failure and validation inventory

The completed TAR arm recorded **61,598** outcomes: **19,210 already correct**,
**18,901 repaired**, **21,842 no repair**, **1,641 request deadline failures**,
and **four engine errors**. These categories are disjoint. All incorrect-input
misses remain in the 42,388-case Hit Rate denominator. A `no_repair` outcome
means the configured search did not return a repair; it does not establish that
no repair exists.

### Four failures before emitting a solution or hint

| Completion ordinal | Case ID | Native candidate depth | Request wall time |
| ---: | --- | ---: | ---: |
| 9,964 | `trash_rl/over/zK2D3ghpYBcs2ux86_inv9.als` | 1 | 1.109712 s |
| 33,157 | `trash_rl/over/Teed5JaE8L3L3JqMZ_inv9.als` | 1 | 0.297347 s |
| 39,300 | `trash_rl/under/NxodxdjWERaPL5WFt_inv9.als` | 2 | 0.018647 s |
| 52,294 | `trash_rl/both/M5ddBc6EYZS233e4n_inv9.als` | 1 | 0.037974 s |

All four responses contain `java.lang.IllegalArgumentException` and a positive
candidate depth, but neither a serialized solution nor a hint trace. The frozen
wrapper assigns depth only after finding a candidate and assigns the solution
only after printing it. That state localizes the failure to serialization before
hint generation. All four learner inputs contain a `disj` construct. The first
case's `DISJOINT` fall-through was dynamically reproduced as described above;
the same specific cause for the other three is a strong static inference, not
three additional executed reproductions. None received independent validation
or counts as a successful hint. The baseline printer was not patched during
measurement.

### Validation of the 18,901 emitted candidates

| Independent outcome | Count | Meaning |
| --- | ---: | --- |
| Bounded check passed | 18,538 | No counterexample under the original check, facts, and bounds. |
| Counterexample found | 3 | The returned, reparsed candidate failed the original check. |
| `ErrorType` | 284 | Candidate could not be checked successfully by the production Alloy runtime. |
| `ErrorSyntax` | 74 | Candidate could not be parsed successfully by that runtime. |
| Validation timeout | 2 | Separate 15-second validation deadline; semantic outcome unknown. |

The 358 errors came from the Java validator, not a Python adapter exception or
transport failure. This does not identify a unique underlying cause for every
case. Two current candidate hashes exactly match earlier separately replayed
examples: `coursesOld/both/h3G5Biw4hXHdxBABc_inv4.als` loses qualification of an
overloaded field, and `graphs/both/EAzbvGWRaJnCfoBtD_inv7.als` loses comprehension
delimiters during printing. Those are established examples, not a diagnosis of
all 358. Of the 74 syntax-error outputs, 67 contain parenthesized declaration
patterns and seven contain an ordering-module symbol; these are static features,
not independently reproduced causes for each output.

The three counterexample-producing cases are:

- `trash_fol/under/39TPKYQ5Fb6ixnQnW_inv2.als` (completion 14,154).
- `trash_fol/both/zqAktmMuWYgwS4Mzu_inv2.als` (completion 23,329).
- `coursesOld/both/5se7eW7CkLtyvAQ93_inv4.als` (completion 35,184).

In the first two, static inspection of the native mutation trace, returned text,
and printer suggests that removing global-signature qualification lets a local
variable capture the printed name. In the third, discarding qualification of
an overloaded field after changing a quantifier domain can select a different
field on reparse. The failed independent checks are measured facts; these
specific causal explanations remain static inferences without new end-to-end
solver replays. Both search and validation explicitly use `noOverflow=false`,
so the earlier default-overflow mismatch is not an explanation for these rows.

The two validation timeouts are
`trainStationOld/under/qwZiHJwA7cJpgkLkt_inv16.als` and
`trainStationOld/under/R3unrZZAXJwugdgcA_inv16.als`, at completions 35,999 and
39,487. Their source hashes are identical. Observed validation-process times
were 15.083 and 15.015 seconds, including termination overhead. They remain
unknown and are distinct from the 1,641 hint-generation timeouts.

The final native hint/repair-candidate rate is **18,901/42,388 = 44.5905%**.
The independently passed bounded repair rate is **18,538/42,388 = 43.7341%**.
The other **360** validation outcomes remain unknown, and the three measured
counterexamples remain failures. All original outcomes are retained.

The read-only inventory, including source/candidate hashes, status counts by
corpus label, validation-error counts by model family, and source-code locations,
is `build/benchmarks/alloy4fun-v2/final-review/tar-outcome-audit.json`, SHA-256
`3164cbbc6e9590574896999ffb6680724434c79c88871d936a0388d01ecdfeff`.
It binds the final TAR files and the earlier diagnostic records; it introduces
no replacement timings or solver outcomes.

## Implemented corrections and remaining boundaries

1. Give every engine worker an owned temporary directory and clean it after the
   process exits, including timeout and forced termination. Avoid accumulating
   parser files for a long-lived JVM. Retain safe phase/class/I/O-category
   diagnostics privately; public errors must not reveal reference expressions.
2. Render quantified nodes according to their actual children and update
   canonical span construction together. Preserve the correct nonzero distance
   in the synthetic witness; restore the missing body and its highlight.
3. Select classification commands by their intended labels and validate the
   corpus's correctness labels and reference pools before making semantic-quality
   claims. Preserve the original results and record corrected reruns separately.
4. Present semantic correctness separately from structural distance. A bounded
   equivalence pass should not be presented as a request to repair an already
   correct predicate merely because it differs from a known solution's form.

A further configuration boundary needs review: the Canonical adapter uses the
fixed `alloyOverflowForbidding()` compatibility profile, whereas the corpus
checks here use `noOverflow=false`. This investigation has not established that
the profile difference caused the positive distances, nor proved the soundness
of all normalization rules under either setting.

The renderer and locator corrections are implemented. Production feedback, behavioral examples, upload inspection, exercise validation, and runtime self-tests use a private per-JVM directory under `build/runtime/tmp/`. IIS places this directory under its protected writable log directory. Operators can override it with `ALLOY_ENGINE_TMP_ROOT`; linked paths are rejected. No existing shared temporary files are removed.

All benchmark engines use owned per-JVM directories under `build/benchmarks/tmp/` (or `ALLOY_BENCHMARK_TMP_ROOT`). Parent processes remove only their own directories after the child exits, including killed or timed-out children. Live and TAR workers recycle after 256 learner requests; FM24 workers recycle after 256 native actions (normalization, hinting, or mutation). This bounds retained parser files. Tests cover actual JVM timeout cleanup, startup failure, normal completion, worker recycling, and preservation of neighboring directories.

The completed five-arm comparison uses `build/benchmarks/alloy4fun-v2/`, corrected labels, the repaired display, and isolated temporary directories. Old measured outcomes remain in `build/benchmarks/alloy4fun/`. All five new runs have 61,598 outcomes and passed the saved-response correspondence audit. The remaining recommendations about semantic correctness, broader corpus validation, and safe private exception diagnostics are not represented as completed guarantees.

## Local evidence

Diagnostic artifacts are under the ignored `build/` directory. They include
input and source hashes; private corpus pairs must not be copied into portal
responses or public assets.

- `build/benchmarks/ast-pool-diagnostic/report.json`: original three-case repeats
  and size/forest-budget checks.
- `build/benchmarks/ast-warm-diagnostic/investigation-summary.json`: warmed
  replay, parser lifecycle and syscall evidence.
- `build/benchmarks/ast-warm-diagnostic/after-user-cleanup/directory-create-report.json`:
  the separate post-cleanup probe.
- `build/benchmarks/canonical-diagnostic/final-summary.json`: all 1,670 pool,
  distance and renderer checks.
- `build/benchmarks/canonical-diagnostic/equivalence-summary.json` and
  `equivalence.jsonl`: all 1,670 bounded Alloy checks.
- `build/benchmarks/canonical-diagnostic/label-classifier-reproduction.json` and
  `label-pool-contamination.json`: the two label anomalies and their isolation.
  The adjacent counterexample XML is private and is not a public report asset.
- `build/benchmarks/canonical-display-witness/report.json`: public synthetic
  witness sources and responses.

Additional evidence for these corrections:

- `build/benchmarks/canonical-diagnostic/label-provenance.json`: original metadata, upstream Git blobs, legacy snapshot, ACGN import, and full source-hash duplicate audit.
- `build/benchmarks/canonical-diagnostic/post-fix/summary.json`: unchanged distances and edits, 55 restored body spans, and before/after highlight counts.
- `build/benchmarks/canonical-diagnostic/post-fix/display-pairs.json`: all eight original display collisions after the renderer correction.

## Memory incident and preserved-run recovery

At **2026-09-29 23:48:11 CDT**, the kernel reported global out-of-memory pressure shortly after the 16-worker TAR arm started. The 16 likely TAR JVMs in the launch-time process cluster used **8.67 GiB RSS** collectively; attribution to individual launch PIDs is inferred from the cluster and workload configuration. The kernel killed `baobab`, which had about **10.7 GiB RSS**. Its D-Bus service failed, and the graphical session logged out one second later. Only 8 KiB of swap remained. This was memory exhaustion, not disk exhaustion. The 512 MiB per-JVM heap setting did not bound aggregate Java, native, Python, and desktop memory; the benchmark contributed to the pressure.

Canonical, AST, FM24 history, and FM24 with mutation had already completed. All four retain exactly **61,598** matched numeric/raw records, valid closed gzip streams, and matching source/runtime hashes. Their 16 files are preserved byte-for-byte. Canonical and AST both finished without errors; the three original AST failures now succeed. Canonical distances and operation counts are unchanged across all 61,598 cases, and AST distances/counts are unchanged for every previously successful case.

The interrupted TAR archive retains **296** numeric rows and an incomplete compressed stream containing **220** complete, validated pairs. The 76 numeric-only rows lack complete raw evidence. All original archive bytes remain preserved. The fresh TAR arm completed the entire cohort with four workers and **zero resumed rows**; none of those interrupted measurements was mixed into the new timing population.

TAR now commits each raw response as its own complete gzip member, flushes and syncs it, then commits its numeric row. Each raw record binds source, manifest, and row hashes. Resume validation refuses incomplete or inconsistent pairs without changing the files. Hard-kill regressions cover committed pairs, the gap between raw/row writes, and partial compressed members.

The restart uses a separate user service with **MemoryMax=6 GiB**, **MemoryHigh=4 GiB**, **MemorySwapMax=0**, **CPUQuota=400%**, and **Nice=10**. A separate 128 MiB supervisor requires 10 GiB available before launch and stops the workload below a 4 GiB global reserve. The workload is bound to that supervisor, so losing the watchdog stops the workload. Kernel cgroup grouping contains child JVMs and native solver allocations. A deliberately over-limit 64 MiB test produced `CONSTRAINT_MEMCG` and killed only its test service; a separate real smoke run verified the effective production limits. These checks establish local enforcement, not immunity to every possible system failure.

The unchanged four-arm results and the fresh four-worker TAR run support the stated hint-availability comparison under their different resource configurations. Finalization verified the preserved and archived hashes, fresh TAR provenance, completed guard evidence, and nonoverlapping arm schedule. The separate 1.25-second diagnostic overlap described in section 5 remains disclosed.

The TAR guard ran from **2026-09-30 05:05:15.504 UTC to 20:00:40.350 UTC** and
recorded `COMPLETED`, exit status 0, service result `success`. Peak workload
memory was **3,258,179,584 bytes** (3.034 GiB); minimum sampled global available
memory was **11,227,684,864 bytes** (10.457 GiB). All cgroup memory high/max/OOM
event counters were zero, the watchdog did not stop the workload, and no worker
scratch entries remained. Automatic finalization completed at **20:01:07 UTC**;
its peak was **2,050,547,712 bytes**, also with zero OOM events and no scratch
residue. The retired TAR, finalization, and supervisor services are inactive.

Evidence: `build/benchmarks/alloy4fun-v2/recovery/oom-diagnosis.json`, `four-arm-audit.json`, `four-arm-runtime-audit.json`, `tar-prefix-audit.json`, `memory-guard-review.json`, `tar-guard.json`, `finalization-guard.json`, and `pipeline-completion.json`. The guard completed the response audit, report generation, and figure generation. [Public evidence bindings](benchmarks/alloy4fun-evidence.json) identify the final artifacts and preservation checks; no recorded measurement was replaced by its diagnostic retry.
