# RWD01 tier 1 Luna A review

Verdict: `no_constructed_breach`.

I reviewed the frozen mathematical definitions, public contract, theorem and witness registries, and verifier, plus the manifest-bound Java scorer/pool, Java behavioral path, and Python evidence projection/cache path as a boundary plausibility check. The candidate report records two identical offline Lean builds, 92 kernel-checked declarations with empty axioms, four rejected controls, and `modelStatus: VERIFIED`; overall status remains `BLOCKED` solely because the six-review ladder is incomplete. I did not treat the report or runtime code as a proof of Java/Python semantic refinement.

Coverage: every registered theorem is covered below by the exact claim bucket and declaration name. `RWD-INTERNAL` covers all 56 generated/internal declarations, also enumerated exactly here. No claim bucket has a constructed counterexample.

## RWD-GATE

Checked the validity/availability guard, positive denominator, 1000 bound, strict sub-1000 bound outside full agreement, and equivalence of the 1000 result with validity plus full agreement. Counterexample correction, either failed completeness flag, or a sample mismatch each force a strict sub-1000 result; nonfull rounding applies the 999 cap.

Exact declarations:

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

## RWD-ARITHMETIC

Checked the numerator/denominator maxima and the derived Java rounding intermediates against the declared count bounds.

Exact declarations:

- `Reward.score_numerator_bound`
- `Reward.score_denominator_bound`
- `Reward.java_rounding_numerator_safe`
- `Reward.java_rounding_denominator_safe`

## RWD-WITNESSES

Checked the old 10000/10001 false-one rounding witness, repaired 999 result, and nonempty complete-agreement 1000 witness.

Exact declarations:

- `Reward.old_rounding_false_one`
- `Reward.repaired_rounding_counterexample`
- `Reward.nonempty_agreement_witness`

## LFU-ADMISSION

Checked bounded/unique admission, duplicate no-op, fresh identity admission, and preservation of size/uniqueness across a sequence of admissions.

Exact declarations:

- `LFU.admit_bounded`
- `LFU.admit_duplicate_unchanged`
- `LFU.admit_unique`
- `LFU.fresh_admission_present`
- `LFU.continuous_admissions_bounded`
- `LFU.continuous_admissions_unique`

## LFU-EVICTION

Checked victim membership/minimal frequency, first (oldest) minimum tie selection, and explicit oldest-tie and mismatch-retention traces.

Exact declarations:

- `LFU.victim_member_minimum`
- `LFU.victim_oldest_tie`
- `LFU.oldest_tie_witness`
- `LFU.mismatch_retention_witness`

## LFU-UPDATE

Checked nondecreasing bounded saturating frequencies; mismatch preserves list length, uniqueness, valid frequencies, identity order, and lookup of a retained hit.

Exact declarations:

- `LFU.bump_nondecreasing`
- `LFU.bump_bounded`
- `LFU.bump_saturated`
- `LFU.mismatch_length`
- `LFU.mismatch_unique`
- `LFU.mismatch_valid_frequencies`
- `LFU.mismatch_identity_order`
- `LFU.mismatch_retained_hit`

## LFU-CONTEXT

Checked monotone/saturating diagnostic epochs, context replacement/retention, completed requests being fresh, and identical in-flight requests being permitted to join.

Exact declarations:

- `LFU.diagnostic_version_nondecreasing`
- `LFU.diagnostic_version_saturates`
- `LFU.context_switch_discards_pool`
- `LFU.same_context_preserves_pool`
- `LFU.completed_request_is_fresh`
- `LFU.identical_inflight_may_join`

## RWD-INTERNAL

Checked the registered inventory includes every generated/helper theorem; these declarations support the preceding list, erase, victim, identity, and structure properties and establish no additional runtime refinement claim.

Exact declarations:

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

## Counterexample and boundary

No concrete model counterexample was found. The score guard makes empty pools unavailable; rounding is capped for every available non-full result; the only uncapped 1000 branch requires both pools available, all sampled classifications accepted/rejected, zero counterexample correction, and both completed-UNSAT flags. For LFU, admission keeps bounded/unique lists under the stated bounded/unique preconditions, duplicate identities are unchanged, a fresh entry is appended, victim selection retains a minimum and chooses the earliest tie, and mismatch updates saturate without changing identity order. The Java scorer, pool, and the server projection are consistent with these rules in the reviewed paths.

The model does not constrain/authenticate opaque identities as complete Alloy traces or prove solver status truth, enumeration completeness, Java/Python refinement, browser behavior, or deployment behavior; those limits are explicit in the block and were not counted as breaches. No sources or proof/runtime inputs were changed.
