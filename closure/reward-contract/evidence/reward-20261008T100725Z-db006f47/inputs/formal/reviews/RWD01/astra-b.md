# RWD01 tier 3 Astra B adversarial review

Verdict: `no_constructed_breach`; findings: none.

I read all four preceding Luna A/B and Sol A/B JSON records and their Markdown notes, then independently audited the frozen Reward/LFU definitions and proofs, block, claims, witnesses and theorem inventories, Audit.lean, formal README, public reward/LFU contract, registered verifier, relevant imported verifier routines, Java BehaviorReward/BehaviorPool/BehaviorFeedback, and the Python evidence projection and fresh behavioral evaluation path. I applied the mechanical-closure-verification audit protocol. This review changes only astra-b.md and astra-b.json, does not discharge a theorem, and does not assert universal production refinement. No Internet proving, provider API, private configuration, or source mutation was used.

## Frozen evidence and workflow

The block SHA-256 is `0662430aa88a9b3da452e85ed89b7a17bc7475f221feeb7de614989acd871ba2`. I recomputed the frozen input bindings and all retained candidate snapshot bindings: 18 input entries plus the block, 19 canonical manifest entries. The candidate root is `29a1ecb5d200d3ba4351a49a297bb90fe714358cc3c2b99d2acd66f4320ef79e`; the verifier hash is `5153725ed07b1843cabbb8763aea5b3356d7bd9040127112620e2f7f839f49c5`.

The reviewed report is `build/reward-contract/reward-20261008T095536Z-7a013743/report.json`, SHA-256 `9e88ab084e8aaed51750d8fe7f185cc038b00ec84911d610a16f9e96819c4c37`. It records model `VERIFIED`, overall `BLOCKED`, with `CANDIDATE_ONLY_REVIEWS_NOT_CHECKED` as the sole blocking reason. I independently replayed the registered inventory checker against both retained raw audit files, recomputed inventory hashes, both Reward/LFU olean hashes and build source hashes, compared network witness files to their report records, and checked the retained toolchain inventory root. Both build records agree exactly and record exit zero and isolation. I inspected existing compiler evidence rather than running another Lean build or claiming to have done so.

The audited set contains 92 declarations: 61 explicit source theorems and 31 generated declarations. The eight claim buckets uniquely own the entire set, with counts 12, 4, 3, 6, 4, 8, 6 and 49. All 92 actual module, universe-parameter and theorem-type hashes match the frozen registry; all actual axiom lists are empty. Luna A's prose count of 56 internal declarations is incorrect; its enumerated list and the registry contain 49. That prior-review count error does not violate a frozen model invariant. I verified each prior record's block and notes binding; this review's JSON binds all four prior JSON hashes.

## Scoring adversarial audit

The availability guard and valid-count guard are separate. Empty positive or negative pools return none, as do invalid counts. Availability ensures a positive denominator. Validity bounds accepted/rejected counts by their pool sizes, those sizes by 100 and correction by two, giving numerator at most 10000, denominator at most 10002, and rounding intermediates at most 20010002 and 20004. These mathematical bounds fit signed Java int; production Java additionally uses long intermediates.

The full-score iff is inhabited: evidence `(1,1,1,1,0,true,true)` returns 1000. Its full condition includes availability, exact sample agreement, zero correction and both completed-UNSAT flags; validity is also necessary. Each failed flag, positive correction, or sample mismatch defeats that branch, and all remaining available scores are capped at 999. The explicit old-rule witness `(100,100,100,100,1,false,true)` gives fraction 10000/10001, integer rounded value 1000, and repaired result 999. The model does not authenticate a solver's Boolean flag or require correction/status relationships beyond its own definition; its gate is safe even over this larger mathematical domain.

I executed an independent Python reconstruction over 7,056 scoring boundary valuations: pool sizes 0,1,2,99,100,101; accepted/rejected values drawn from zero, predecessor, equality and successor; corrections 0..3; both Boolean flags. The unavailable gate, exact full-score iff, score bounds and arithmetic bounds held on all applicable valuations. The false-one witness was separately replayed. This finite reconstruction is counterexample search, not a substitute for kernel evidence or a semantic-refinement proof.

## LFU adversarial audit

The list order is admission age. Recursive victim selection replaces a head only with a strictly lower-frequency suffix winner, so it computes the first minimum. Equal-frequency suffix winners leave the head selected. The minimum/member theorem and tie theorem have nonempty witnesses. Erasure removes every matching identity; on reachable unique lists this removes exactly the victim. Admission preserves bounded length under the initial boundedness premise and uniqueness under the initial uniqueness premise. Duplicate stability and fresh-entry membership require positive capacity. A fresh identity appends frequency one; a duplicate leaves order and frequency unchanged. Repeated refresh transitions compose boundedness and uniqueness.

The model does not repair an arbitrary oversized initial list, and capacity zero intentionally empties the list. Nor does it promise that an admission of frequency one satisfies an arbitrary frequency limit zero. Those observations do not contradict the registered claims or the positive-capacity production policy. Bump is universally nondecreasing, conditionally bounded when its input is bounded, and unchanged at its limit. Mismatch preserves length, identities, order, uniqueness and conditional frequency bounds; lookup of a retained matching identity returns the bumped entry.

I executed the reconstruction on all 820 ordered lists of length zero through three with identities 0..2 and frequencies 0..2, including repeated identities to test which premises matter. Across 16,400 admission valuations (capacities 0..4, admitted identities 0..3), all applicable boundedness, uniqueness, duplicate and fresh-presence statements held. Across 13,120 mismatch valuations (limits 0..3, identities 0..3), length, order, nondecrease and conditional uniqueness/frequency bounds held. Recursive victim selection agreed with an independent first-minimum selector for every list. The oldest-tie and hot-retention witnesses were replayed; saturation was checked at zero, one, Integer.MAX_VALUE and Long.MAX_VALUE. These are finite tests of reconstructed definitions only.

A capacity-one trace admits identity 1 and then identity 2, leaving only 2. This concretely demonstrates why immediate fresh membership is not eternal retention; the public contract correctly permits later eviction. The hot-retention witness retains identity 1 after its mismatch increment while evicting colder identity 2. Frequency updates preserve age rather than turning age into access order.

## Context, completion and boundary

Changed contexts produce the new opaque context, no entries and epoch zero; equal contexts preserve the entire state. Epoch monotonicity applies to `changed`, not across context replacement. At saturation successive changes have the same epoch, so it is intentionally a diagnostic counter rather than a unique snapshot identity. `completed` removes in-flight state, making the next begin fresh; equal currently running bodies may join. These are explicit observational policy definitions with concrete inhabited cases, not a theorem about actual Python scheduling or JVM execution.

Java BehaviorPool uses insertion-ordered LinkedHashMap, strict minimum eviction, duplicate no-op, fresh frequency one and saturated increments. Java BehaviorReward applies count bounds and the nonfull cap, and BehaviorFeedback computes both completed mismatch checks, admits category witnesses before sampling, increments only mismatches, and drops context after failure. Context replacement is keyed by exact source and predicate in the inspected path. The server validates bounded evidence and category relationships, recomputes integer rounding/full agreement, and schedules fresh behavior with `cacheable=False`; the compatibility snapshot is retained for explanation consumers. These are inspected boundary paths, not universal correspondence evidence.

The excluded surfaces remain explicit: universal Java/Python semantic refinement; Alloy solver/UNSAT correctness, enumeration and concrete solution identity construction; unbounded equivalence; heap/RSS; unconditional fairness/liveness; browser/deployment functionality; historical obligations and future mutations. The mathematical context identity abstracts the private world and sources. A bounded number of abstract entries does not prove a byte-memory bound. No excluded surface is promoted to proved production behavior by this review.

## Auditor, verifier and negative controls

Audit.lean examines module ownership for all Reward/LFU constants, including generated/private declarations. Theorems must have closed types; project axioms are emitted as forbidden and definition/theorem transitive axiom dependencies are inspected. The trusted source checker rejects the registered forbidden constructs and restricts imports; both actual proof sources pass. The exact inventory gate rejects missing, extra or duplicate declarations, changed signatures and nonempty axiom dependencies. I replayed four in-memory gate perturbations (missing row, duplicate row, changed type and nonempty axiom dependency); each was rejected by the actual registered checker.

I checked the retained full-score and missing-admission negative fixtures equal the exact single-anchor mutations of the frozen sources, and verified each retained failure log includes an error in its registered constructed-witness theorem. The false-full mutant fails at repaired_rounding_counterexample; the omitted-admission mutant fails at oldest_tie_witness. The placeholder log names sorry; the actual rogue-axiom audit exposes a forbidden project axiom and a theorem with that dependency. All four registered negative controls have concrete retained failure evidence.

All four witness registry entries name actual audited theorem terms: FALSE-ONE maps to the old/repaired score pair, FULL-AGREEMENT to the nonempty full-score term, OLDEST-TIE to admission order, and LFU-RETENTION to the mismatch increment/admission trace. Witness validity comes from those kernel-checked closed equations, not the descriptive registry label.

The verifier binds inputs before and after builds, isolates imports into fresh object directories, sanitizes subprocess environment, uses the installed pinned toolchain offline, and compares deterministic inventory/object artifacts. The source/hash verifier, audit code, pinned kernel/compiler/Std distribution, Python/OS/filesystem/network-namespace semantics, SHA-256 and hardware remain trusted. Frozen signatures prevent accepting a different theorem merely under the same name. Review records are workflow gates bound to block, prior tiers and notes; they cannot replace a proof or upgrade this candidate report from BLOCKED.

## Exact coverage of all eight claims and 92 declarations

The following is the exact frozen claim partition. Supporting explicit list proofs were read in source; all generated/internal declarations were included in both independently replayed inventory checks. No bucket has a constructed breach.

### RWD-GATE (12 declarations)

- `Reward.empty_positive_unavailable`
- `Reward.empty_negative_unavailable`
- `Reward.invalid_unavailable`
- `Reward.available_denominator_positive`
- `Reward.score_is_bounded`
- `Reward.nonfull_score_lt_one`
- `Reward.full_score_iff`
- `Reward.counterexample_correction_lt_one`
- `Reward.undercoverage_check_lt_one`
- `Reward.overcoverage_check_lt_one`
- `Reward.sample_mismatch_lt_one`
- `Reward.nonfull_rounding`

### RWD-ARITHMETIC (4 declarations)

- `Reward.score_numerator_bound`
- `Reward.score_denominator_bound`
- `Reward.java_rounding_numerator_safe`
- `Reward.java_rounding_denominator_safe`

### RWD-WITNESSES (3 declarations)

- `Reward.old_rounding_false_one`
- `Reward.repaired_rounding_counterexample`
- `Reward.nonempty_agreement_witness`

### LFU-ADMISSION (6 declarations)

- `LFU.admit_bounded`
- `LFU.admit_duplicate_unchanged`
- `LFU.admit_unique`
- `LFU.fresh_admission_present`
- `LFU.continuous_admissions_bounded`
- `LFU.continuous_admissions_unique`

### LFU-EVICTION (4 declarations)

- `LFU.victim_member_minimum`
- `LFU.victim_oldest_tie`
- `LFU.oldest_tie_witness`
- `LFU.mismatch_retention_witness`

### LFU-UPDATE (8 declarations)

- `LFU.bump_nondecreasing`
- `LFU.bump_bounded`
- `LFU.bump_saturated`
- `LFU.mismatch_length`
- `LFU.mismatch_unique`
- `LFU.mismatch_valid_frequencies`
- `LFU.mismatch_identity_order`
- `LFU.mismatch_retained_hit`

### LFU-CONTEXT (6 declarations)

- `LFU.diagnostic_version_nondecreasing`
- `LFU.diagnostic_version_saturates`
- `LFU.context_switch_discards_pool`
- `LFU.same_context_preserves_pool`
- `LFU.completed_request_is_fresh`
- `LFU.identical_inflight_may_join`

### RWD-INTERNAL (49 declarations)

- `LFU.Decision.fresh.sizeOf_spec`
- `LFU.Decision.join.sizeOf_spec`
- `LFU.Decision.ofNat_ctorIdx`
- `LFU.Entry.mk.sizeOf_spec`
- `LFU.State.mk.sizeOf_spec`
- `LFU.absent_append`
- `LFU.absent_member`
- `LFU.appended_member`
- `LFU.constructive_length_append`
- `LFU.erase.eq_def`
- `LFU.erase_absent`
- `LFU.erase_length_le`
- `LFU.erase_length_lt`
- `LFU.erase_preserves_absent`
- `LFU.erase_unique`
- `LFU.instDecidableEqDecision._proof_1`
- `LFU.instDecidableEqDecision._proof_2`
- `LFU.instDecidableEqEntry.decEq._proof_1`
- `LFU.instDecidableEqEntry.decEq._proof_2`
- `LFU.instDecidableEqEntry.decEq._proof_3`
- `LFU.instDecidableEqState.decEq._proof_1`
- `LFU.instDecidableEqState.decEq._proof_2`
- `LFU.instDecidableEqState.decEq._proof_3`
- `LFU.instDecidableEqState.decEq._proof_4`
- `LFU.lookup.eq_def`
- `LFU.member_cons`
- `LFU.member_not_absent`
- `LFU.mismatch.eq_def`
- `LFU.mismatch_absent_iff`
- `LFU.survivors_absent`
- `LFU.survivors_length_lt`
- `LFU.survivors_unique`
- `LFU.unique_append_new`
- `LFU.victim.eq_def`
- `LFU.victim_none_iff`
- `Reward.Evidence.mk.sizeOf_spec`
- `Reward.constructive_min_left`
- `Reward.instDecidableEqEvidence.decEq._proof_1`
- `Reward.instDecidableEqEvidence.decEq._proof_2`
- `Reward.instDecidableEqEvidence.decEq._proof_3`
- `Reward.instDecidableEqEvidence.decEq._proof_4`
- `Reward.instDecidableEqEvidence.decEq._proof_5`
- `Reward.instDecidableEqEvidence.decEq._proof_6`
- `Reward.instDecidableEqEvidence.decEq._proof_7`
- `Reward.instDecidableEqEvidence.decEq._proof_8`
- `_private.LFU.0.LFU.Absent.match_1.eq_1`
- `_private.LFU.0.LFU.Absent.match_1.eq_2`
- `_private.LFU.0.LFU.victim.match_1.eq_1`
- `_private.LFU.0.LFU.victim.match_1.eq_2`

No constructed counterexample violates the frozen claim set. This verdict records the result of this adversarial review only; the registered mechanical verifier remains the authority for final finite closure under the declared TCB.
