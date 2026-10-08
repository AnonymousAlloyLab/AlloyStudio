# RWD01 tier 3 Astra A adversarial review

Verdict: `no_constructed_breach`; findings: none.

I first read all four prior JSON records and their Markdown notes, then independently reviewed the frozen block, all Reward/LFU definitions and handwritten proofs, Audit.lean, the claim/theorem/witness registries, formal README, public reward/LFU contract, registered verifier and its invoked local helpers, and the bound Java/Python behavior boundaries. I applied the mechanical-closure-verification audit protocol. This is the requested finite review record, not theorem evidence or a replacement for the registered closure decision. No Internet, provider API, or private configuration was accessed. Only this review's Markdown and JSON files were written.

## Exact binding and recorded candidate state

Frozen block SHA-256: `0662430aa88a9b3da452e85ed89b7a17bc7475f221feeb7de614989acd871ba2`.

Candidate: `build/reward-contract/reward-20261008T095536Z-7a013743/report.json`, SHA-256 `9e88ab084e8aaed51750d8fe7f185cc038b00ec84911d610a16f9e96819c4c37`.

Canonical candidate input root: `29a1ecb5d200d3ba4351a49a297bb90fe714358cc3c2b99d2acd66f4320ef79e`. Registered verifier SHA-256: `5153725ed07b1843cabbb8763aea5b3356d7bd9040127112620e2f7f839f49c5`.

I recomputed the 18 declared input hashes plus the block hash, compared the resulting 19-entry manifest with the retained candidate manifest, rehashed every candidate input snapshot, and recomputed its canonical root. All matched. Both retained raw audits passed the actual registered inventory checker against all 92 exact name/module/universe/type-hash records, with empty transitive theorem axiom lists. I checked their inventory hashes, both Reward/LFU object hashes, copied proof source bytes, network witness records, and the retained toolchain inventory root. The two recorded build records are equal. I inspected and rechecked retained evidence; I did not run another Lean build or promote my reconstruction tests to kernel evidence.

The candidate's state remains `BLOCKED`, with `modelStatus: VERIFIED` and sole blocking reason `CANDIDATE_ONLY_REVIEWS_NOT_CHECKED`. Its four negative controls record rejection. The full-score mutation fails at the closed repaired-rounding witness; omission of admission fails at both LFU closed witnesses. The placeholder log rejects sorry, and the actual rogue-axiom audit exposes both the project axiom and its dependent theorem. This review does not alter that report or establish final closure.

## Mathematical counterexample search and nonvacuity

**RWD-GATE:** The outer guard requires valid bounded counts and positive pool sizes. The full branch requires available pools, exact accepted/rejected agreement, zero correction, and both independent completed-UNSAT Boolean flags. Every other available branch is bounded by 999. Thus the theorem's necessary and sufficient condition includes validity; it is not an assertion about invalid counts or authenticated solver truth. A zero positive or negative pool yields none, and available evidence has strictly positive denominator. The inhabited input `(P,N,A,R,C,u,o)=(1,1,1,1,0,true,true)` yields 1000. Flipping either flag yields 999; setting A to zero yields zero. No required antecedent here is impossible.

**RWD-ARITHMETIC:** Validity gives A,R at most 100, numerator at most 10000, denominator at most 10002, and rounding intermediates at most 20010002 and 20004. These inequalities hold on the explicitly bounded natural-count domain. They do not purport to prove Java compilation or arbitrary machine arithmetic.

**RWD-WITNESSES:** `(100,100,100,100,1,false,true)` has old rounded value 1000 and repaired score 999. The positive full-agreement witness returns 1000. These are closed kernel-checked equations, not witnesses supplied solely by prose. The model deliberately permits some status/correction combinations that production would reject, but the cap remains safe on that larger domain.

**LFU-ADMISSION:** Admission preserves bounded size given initial boundedness and preserves unique identities given initial uniqueness. Duplicate admission with positive capacity is a complete no-op. Fresh admission with positive capacity appends frequency one and immediately contains that entry. Iterated refresh composes the same invariants. Zero capacity empties the list, and no claim repairs an initially oversized list. No registered theorem claims admission preserves a frequency bound of zero.

**LFU-EVICTION:** The recursive victim chooses a suffix winner only when its frequency is strictly less than the head. It therefore agrees with the first minimum in list order, including nested equal-frequency cases. Erasure removes all entries with the victim identity; reachable uniqueness makes this exactly one removal. The closed capacity-two tie trace evicts identity 1 and retains 2 before admitting 3. Mismatching identity 1 first raises its frequency to two and causes identity 2 to be evicted instead. Immediate retention never promises retention after arbitrary later admissions.

**LFU-UPDATE:** Bump is nondecreasing on all natural values, bounded under the input-at-most-limit premise, and unchanged at the limit. Mismatch preserves length, identity order, absence/uniqueness, and conditional frequency validity. The lookup theorem increments a retained hit without changing identity. Already-over-limit values remain over limit and are outside the boundedness premise. Frequencies are not access counters; the production boundary calls this transition on classification mismatch.

**LFU-CONTEXT:** Equal context preserves the state; unequal context empties entries and resets epoch to zero. Epoch monotonicity is about the `changed` transition, not context reset. Saturation is explicitly diagnostic and cannot establish injective snapshots. The executable completion policy clears in-flight work; `begin` after completion is fresh, whereas an identical currently running body may join. All of these cases have ordinary inhabited inputs. The theorems establish this policy model, not actual scheduler execution or authentic context construction.

**RWD-INTERNAL:** I included every helper, generated equation, equality proof and size declaration in the independently checked 92-declaration inventory, and read the handwritten supporting membership, absence, erase, append and victim proofs. These declarations support the seven public claim buckets without adding production refinement. There are 43 declarations in the seven primary buckets and 49 in this bucket, comprising 61 explicit source theorems and 31 generated declarations overall. Luna A's prose count of 56 internal declarations is inaccurate; its actual list covers the same 49. That count error does not violate a frozen claim.

As independent adversarial probes, I evaluated 13,456 scoring boundary valuations with pool sizes 0, 1, 2, 50, 99, 100, 101, near-boundary accepted/rejected counts, corrections 0 through 3 and all flag combinations. I checked availability, bounds, exact full-score equivalence and the nonfull cap. I also evaluated 130,176 admission/mismatch transitions over lists of length zero through three, identities zero through two, frequencies 0, 1, 2, Integer.MAX_VALUE and Integer.MAX_VALUE+1, capacities zero through four, and mismatch limits 0, 1, 2 and Integer.MAX_VALUE. These include duplicate and invalid-frequency states; only properties with satisfied premises were demanded. I compared the recursive victim to an independent stable first-minimum selector and checked membership, size, uniqueness, duplicate stability, order, conditional bounds and retained-hit updates. Long.MAX_VALUE saturation and both explicit eviction traces were checked. No violation was found. These probes test a direct Python reconstruction and remain TESTED evidence, not new formal declarations.

## Verifier acceptance boundary

The scanner admits the frozen narrow proof language, while rejecting placeholders, custom axioms, native proof shortcuts and metaprogramming escapes. The trusted audit enumerates Reward/LFU-owned theorem declarations including private/generated constants, rejects project axioms, and rejects transitive axiom dependencies in definitions as well as reporting them for theorems. The inventory gate compares exact closed theorem signatures rather than merely names. In-memory modifications for missing declaration, duplicate declaration, changed type, nonempty axiom dependency, wrong module and changed universe parameters all failed the actual gate.

The theorem registry is fully and uniquely partitioned by the eight claims; all four witness records reference admitted closed witness theorems. Both public provenance targets are manifest-bound and explicitly distinguish executable model proofs from separately tested production correspondence. The verifier entrypoint and its local imports through lean_offline, verify_lean and bridge_policies are bound. The invoked helpers pin the installed Lean version, sanitize subprocess environments, isolate each compiler process from networking, use fresh object directories, compare deterministic audits/artifacts, and hash inputs/toolchain before and after. Audit/verifier execution, host semantics, hashing and the installed Lean distribution remain the declared trusted base.

The required review gate binds the block bytes, prescribed tier/model, each review's exact notes bytes and all prior-tier JSON hashes. A successful review is only a workflow prerequisite; it cannot discharge any of the 92 declarations. I verified the four prior notes hashes and bound all four prior JSON hashes below. I did not construct an accepted stale-signature, omitted-declaration, axiom, witness, or manifest counterexample against this frozen root.

## Production boundary and outcome

I separately inspected BehaviorReward.java, BehaviorPool.java, BehaviorFeedback.java and the server.py projection/fresh-request path. Their reviewed branches use bounded long intermediates, the full-score cap, insertion-ordered minimum eviction, duplicate stability, saturating mismatch updates, category-witness admissions before classification, both complete UNSAT checks, replacement of the retained context and `cacheable=False` behavioral scheduling. Python validates bounded evidence and recomputes the score. These source reads are plausibility/scope checks, not universal Java/Python semantic refinement or new runtime-test execution.

The finite closure excludes solver correctness/UNSAT completeness, concrete trace identity construction, unbounded equivalence, allocated bytes/RSS, unconditional fairness/liveness, browser/deployment behavior, historical obligations and future mutations. Those exclusions are material and remain explicit. No constructed invariant breach was found in the frozen mathematical claims, required witnesses, binding, nonvacuity or claim scope. The review verdict is `no_constructed_breach`; final closure remains the registered verifier's responsibility under the declared TCB.

## Exhaustive finite claim coverage

The following is the exact finite ownership set checked against the registry and both raw audits. Every declaration is covered once.

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

## Prior review bindings

- `formal/reviews/RWD01/luna-a.json`: `c0fe3543dc81e83c717bda2a0d2812fa8f70e36c9c3fca2c43424358dba2ee5f`
- `formal/reviews/RWD01/luna-b.json`: `347d733e43a82d8bdc2f1c68a4bca2ecf2d81c3cd1b2669bf92905d1b34bcd76`
- `formal/reviews/RWD01/sol-a.json`: `5a5718360fe957e2723a53a66f8e78a17f197cc6f5fd48093e3c6d85028495a6`
- `formal/reviews/RWD01/sol-b.json`: `543d95f08b3decbc2fe7ed47e4b66dd3cd853a611bafdac5e92ade1c94897afc`
