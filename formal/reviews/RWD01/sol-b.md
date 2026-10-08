# RWD01 tier 2 Sol B adversarial review

Verdict: `no_constructed_breach`; findings: none.

I read both prior Luna JSON records and their bound Markdown notes before independently reviewing the frozen block, claim/theorem/witness registries, Reward/LFU definitions, audit metaprogram, registered verifier and its project dependencies, public contract, and manifest-bound Java/Python boundary excerpts. No frozen input was modified. This review is a workflow record, not proof evidence or universal runtime refinement.

## Binding and candidate evidence

Block SHA-256: `0662430aa88a9b3da452e85ed89b7a17bc7475f221feeb7de614989acd871ba2`.

Prior JSON hashes:

- `formal/reviews/RWD01/luna-a.json`: `c0fe3543dc81e83c717bda2a0d2812fa8f70e36c9c3fca2c43424358dba2ee5f`
- `formal/reviews/RWD01/luna-b.json`: `347d733e43a82d8bdc2f1c68a4bca2ecf2d81c3cd1b2669bf92905d1b34bcd76`

I recomputed all 19 canonical candidate manifest bindings (18 block inputs plus the block), checked each retained input snapshot against its binding, and recomputed input root `29a1ecb5d200d3ba4351a49a297bb90fe714358cc3c2b99d2acd66f4320ef79e`. The registered verifier hash is `5153725ed07b1843cabbb8763aea5b3356d7bd9040127112620e2f7f839f49c5`.

Candidate `build/reward-contract/reward-20261008T095536Z-7a013743/report.json` has SHA-256 `9e88ab084e8aaed51750d8fe7f185cc038b00ec84911d610a16f9e96819c4c37`. Its state is `BLOCKED`, model state `VERIFIED`, with sole reason `CANDIDATE_ONLY_REVIEWS_NOT_CHECKED`. I independently passed both retained raw audit inventories through the registered signature/inventory gate, recomputed both inventory hashes and Reward/LFU object hashes, checked build source copies and network witness records, and checked the toolchain inventory root. Both recorded clean builds are identical, contain 92 theorem declarations, and have empty transitive axiom lists. I inspected existing compiler evidence; I did not create another build or claim to have rerun Lean.

The registry partitions into 43 named claim theorems and 49 `RWD-INTERNAL` declarations, totaling 92. Luna A's prose count of 56 internal declarations differs from the frozen registry; its actual enumerated list contains the same 49 entries. This is a prior review count error, not a constructed breach of a frozen invariant.

## Independent adversarial checks and nonvacuity

For scoring I tested 29,484 finite cases using a direct translation of the frozen definitions: all valid count combinations for pool sizes 0 through 8, all correction values 0 through 2 and both completeness flags, plus pool/count/correction boundary cases around 0, 1, 99, 100 and 101. Every observed available score was bounded, and `some 1000` was equivalent to validity, two nonempty fully classified agreeing pools, zero correction, and both completed-UNSAT flags. A failed flag, a sample mismatch, or positive correction could not return 1000. These executable searches are tests of the translation, not additional kernel proofs or Java/Python equivalence evidence.

Concrete satisfying and separating inputs make the gate nonvacuous: `⟨1,1,1,1,0,true,true⟩` returns 1000; replacing either completeness flag by false returns 999; `⟨1,1,0,1,0,true,true⟩` returns 0; a zero pool returns none. `⟨100,100,100,100,1,false,true⟩` has old rounded value 1000 and repaired score 999. Validity gives numerator at most 10000 and denominator at most 10002, hence intermediates at most 20010002 and 20004, within signed Java int; the available guard supplies a positive denominator. The model permits evidence combinations more general than real category semantics, but its gate remains safe for them and does not authenticate either Boolean flag.

For LFU I tested 53,485 admission/mismatch transitions from unique ordered lists of up to three entries with identities drawn from 0 through 3 and frequencies 0 through 3. I compared recursive victim selection with an independent first-minimum selector, checked all initially bounded capacities through 4 and admission identities through 4, and checked mismatch limits 0 through 3. Bounded size, uniqueness, duplicate stability for positive capacity, fresh presence, oldest minimum eviction, preserved identity order and length, nondecreasing frequencies, and conditional frequency bounds all held. The scope includes bounded/unique initial-state hypotheses where the corresponding theorem requires them; no theorem promises to repair arbitrarily oversized input lists. Admission at zero capacity intentionally clears the list, while duplicate-no-op and fresh-presence claims require positive capacity.

Nonvacuous LFU traces are `admission 2 3 [(1,1),(2,1)] = [(2,1),(3,1)]`, and after mismatching identity 1, `admission 2 3 [(1,2),(2,1)] = [(1,2),(3,1)]`. Re-admitting an existing identity leaves its frequency/order unchanged. Bump at its limit remains that limit; the bounded-frequency theorem assumes the input frequency is bounded. Context replacement with a different identity empties entries and resets the diagnostic epoch, and equal context preserves the state. The monotone diagnostic epoch theorem concerns `changed`, not resets across context replacement. Completion discards in-flight state, making subsequent begin fresh; matching still-running bodies may join. These definitions have inhabited states and positive-capacity examples; the completion theorem does not establish scheduler implementation refinement.

## Audit, manifest dependencies, witnesses and failure paths

The audit traverses every declaration owned by Reward/LFU, including private and generated declarations. The theorem gate checks exact names, modules, universe parameters and type hashes; missing/extra/duplicate declarations fail. The audit rejects project axioms and checks transitive axiom dependencies of both theorem and definition constants. Proof sources use the registered narrow language with only Std imports; no placeholders, custom axioms, native proof evaluation or arbitrary metaprogramming occurs in the positive sources. Audit.lean is explicitly trusted verifier code rather than an admitted theorem module.

Static Python import traversal identifies the complete local verifier dependency set as `scripts/lean_offline.py`, `scripts/verify_lean.py` and `scripts/bridge_policies.py`; each is manifest-bound along with the entrypoint and audit. Remaining imports are Python standard library under TCB-HOST. Lean/Std and compiler objects are covered by the declared toolchain inventory; the verifier sanitizes child environment, uses installed pinned binaries, creates fresh object directories, checks network isolation and compares toolchain hashes before/after. Runtime server imports and Alloy/Java dependencies are not model proof dependencies and remain outside universal refinement. I did not read private configuration, use networking, or call provider APIs.

All four registered witnesses map to exact audited theorem names: FALSE-ONE to the old/repaired rounding pair; FULL-AGREEMENT to the nonempty 1000 witness; OLDEST-TIE to the admission trace; LFU-RETENTION to the mismatch-retention trace. Their explicit closed terms and equations reside in the frozen proof sources and are kernel checked, rather than assertions inferred from the registry text.

I checked the retained negative-control fixtures equal the precise single-anchor mutations of the frozen sources. The full-score mutant fails specifically at `Reward.repaired_rounding_counterexample`; the omitted-admission mutant fails at `LFU.oldest_tie_witness` and `LFU.mismatch_retention_witness`. The placeholder log rejects sorry. The rogue-axiom audit exposes both `Reward.rogue` and its dependency in `Reward.bad`. In-memory replay of the registered inventory gate separately rejected a missing theorem, duplicate theorem, changed signature, nonempty transitive axiom dependency, and project-axiom row. A mutated witness byte stream was rejected by the frozen-input hash gate. No accepted witness failure or manifest escape was constructed.

## Claim and exact theorem coverage

Each frozen claim is covered by the following exact registered declarations. Internal generated equations and equality/size proofs were included in the independently rechecked inventory; supporting handwritten list/erase/victim proofs were also read in source.

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

## Scope and outcome

No constructed counterexample violates a frozen claim. The Java scorer and pool excerpts use the reviewed bounded rounding, duplicate admission, ordered minimum eviction and saturating update policies; the behavior worker computes classification after admissions and checks both completed mismatch directions; the Python projection recomputes and validates the gate, and fresh behavior scheduling uses `cacheable=False`. These are scope checks of the inspected paths, not universal semantic-refinement proofs.

The explicit exclusions remain necessary: universal Java/Python refinement; Alloy solver/UNSAT truth and enumeration/identity construction; unbounded equivalence; allocated bytes/RSS and unconditional liveness; browser/deployment behavior; historical obligations and future mutations. Diagnostic epochs are not unique snapshot identities, and immediate admission is not eternal retention. The declared Lean, verifier, hashing, host and hardware TCB is trusted, not empty.

`no_constructed_breach` records this review's result only. Overall finite closure remains governed by the registered mechanical verifier and required six-review bindings; the reviewed candidate is still `BLOCKED` while those bindings are incomplete.
