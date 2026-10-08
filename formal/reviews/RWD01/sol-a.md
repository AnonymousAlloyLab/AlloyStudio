# RWD01 tier 2 Sol A adversarial review

Verdict: `no_constructed_breach`; findings: none.

I read both Luna JSON records and both coverage notes before independently auditing the frozen block, all reward/LFU proof definitions and theorem statements, Audit.lean, claim/theorem/witness registries, the formal README, public reward/LFU contract, root README reward claims, registered verifier and its project imports, Java scorer/pool/feedback boundaries, Python evidence projection and fresh-work call path, and the candidate raw audit and negative-control evidence. I used the mechanical-closure-verification audit protocol. I changed only this review's Markdown and JSON files; no proof, runtime source, frozen input, prior review, or generated candidate evidence was changed. I did not access private configuration, invoke a provider API, or use the Internet.

## Frozen binding and candidate evidence

The block SHA-256 is `0662430aa88a9b3da452e85ed89b7a17bc7475f221feeb7de614989acd871ba2`. All 18 block input entries match their hashes; the candidate canonical manifest also includes the block, giving 19 entries, each matching its read-only candidate snapshot. Candidate `reward-20261008T095536Z-7a013743` binds root `29a1ecb5d200d3ba4351a49a297bb90fe714358cc3c2b99d2acd66f4320ef79e` and verifier `5153725ed07b1843cabbb8763aea5b3356d7bd9040127112620e2f7f839f49c5`. Both raw audits have 92 unique declarations, the exact registered module/level/type hashes, and empty axiom dependencies; their canonical inventory digest and both olean hashes match the report. The two recorded builds are identical, each records exit code zero and an isolated network witness. Model status is `VERIFIED`; overall candidate status is `BLOCKED` with `CANDIDATE_ONLY_REVIEWS_NOT_CHECKED`. This review cannot change that report or discharge a theorem.

The inventory contains 61 explicit source theorem declarations plus 31 compiler-generated declarations. The seven primary claim buckets own 43 declarations; `RWD-INTERNAL` owns 49 helper/generated declarations. The entire 92-declaration set is uniquely owned. Luna A's prose count of 56 internal declarations is inaccurate, but its actual list and the frozen registry cover the same 49; that count error is not a constructed contract breach.

## Independent scoring audit

`score` first checks valid counts and both nonempty pools. The exact full-score condition is `valid` plus `fullAgreement`: nonempty pools, accepted/rejected counts equal their pool sizes, correction zero, and both independently represented completed-UNSAT booleans true. This condition is inhabited by `P=N=A=R=1,C=0,u=o=true`. The theorem is therefore neither an implication with an impossible full-score antecedent nor a certificate of Alloy solver truth. A false completion flag, sampled mismatch, or positive correction prevents the full branch; every available nonfull result is at most 999 regardless of rounded value. Empty pools and invalid counts return `none`, so the positive-denominator theorem has a satisfiable and appropriately limited antecedent.

Bounds follow from accepted/rejected counts being bounded by pool sizes, each pool size at most 100, and correction at most two: numerator at most 10000, denominator at most 10002, rounding numerator at most 20010002, and rounding denominator at most 20004. These are mathematical bounds on the stated valid domain. The model intentionally accepts a superset of production evidence with respect to correction/status relationships; it still reserves 1000 correctly on that superset. Authenticating a SAT/UNSAT status or deriving correction from an actual solver is excluded.

The frozen false-one witness has exact fraction `10000/10001`, rounds to 1000 under the old rule, and produces `some 999` after the cap. The full-agreement witness produces `some 1000`. I independently enumerated 1,587,600 scoring boundary valuations using pool sizes 0,1,2,99,100,101, all accepted/rejected values through pool-size-plus-one, corrections 0..3, and both Boolean flags. They respect unavailable/valid gates, bounds, full-score iff, and strict nonfull cap. This Python reconstruction is an adversarial probe, not formal evidence or a universal refinement claim.

## Independent LFU audit and preconditions

Lists represent admission order. The recursive victim uses strict suffix improvement, so its winner is present, minimum-frequency, and the first minimum. Erasure removes all matching identities, but the advertised reachable-state uniqueness invariant means eviction removes exactly the selected entry. Admission preserves capacity under its initial boundedness premise; uniqueness is preserved under its initial uniqueness premise. Positive capacity is essential to duplicate stability and immediate fresh-entry membership. Duplicate admission does not reset a frequency or reorder the entry. Fresh admission appends frequency one after making space. Repeated admissions compose those same bounded/unique invariants.

The zero-capacity branch returns an empty list. An initially over-capacity state is not claimed to be repaired, and admitting into an arbitrary frequency limit zero is not claimed to preserve valid frequencies. These cases do not contradict the registered preconditions or the production pool's positive capacity and fixed positive limit. `bump` is nondecreasing for all naturals, bounded when its input is bounded, and unchanged at saturation; an already over-limit value is outside its boundedness premise. `mismatch` preserves list length, identity order, identity uniqueness, and valid frequencies under the registered premises, and changes a retained matching identity's lookup to its bumped entry without replacing its identity. A unique reachable list guarantees only one increment for that identity per mismatch transition.

I independently enumerated 49,305 bounded unique state/admission valuations (up to four distinct identities, frequencies 0..3, capacities 0..4, and fresh/duplicate admissions) and checked all applicable mismatch limits 0..3, minimum/oldest selection, and witness membership. The oldest-tie and hot-retention witnesses were replayed in the reconstruction. Integer.MAX_VALUE and Long.MAX_VALUE saturation were checked explicitly. These finite reconstruction probes support counterexample search, not the universal Lean proof.

Context equality preserves the entire state; a changed opaque context returns the new context, empty entries, and epoch zero. Monotonic epochs concern the `changed` transition within a context; context replacement starts a new diagnostic epoch. Saturation does not establish injective snapshots. `completed` clears in-flight work, making a subsequent begin fresh; identical current in-flight work may join. These are explicit executable model policies with inhabited cases, not proved execution of a JVM, Python scheduler, parsed-world disposal, or context authentication.

## Audit and verifier audit

The proof scanner rejects placeholders, project axioms, native proof shortcuts, unsafe/opaque declarations, metaprogram escapes, and imports outside the admitted modules and Std. Both frozen proof modules pass its registered subset. Trusted Audit.lean selects Reward/LFU module ownership, rejects unclosed theorem types, reports every theorem and project axiom, and rejects definitions with transitive axiom dependencies. The registered Python checker compares the entire unique declaration set, exact module/level/type hashes, and empty axiom lists; it does not accept a changed signature under the old name. In-memory removal, duplication, signature mutation, and nonempty-axiom probes were each rejected by this actual checker.

Claims uniquely own the complete theorem inventory; registered concrete witnesses name admitted kernel-checked theorems. The review gate binds the exact block, model/tier, notes bytes, and all prior-tier JSON hashes and accepts only no constructed breach with empty findings. Reviews are workflow inputs, not proof terms. Missing reviews keep the candidate blocked. Source inventory and hashes are checked before and after verification, imports are isolated to fresh object directories, toolchain bin/lib files are bound before/after, proof subprocesses receive the sanitized offline environment, and deterministic inventory/artifacts are compared across builds. The four negative controls are tied to raw evidence: the score mutant fails at the repaired false-one witness, the admission mutant fails at the oldest-tie witness, placeholders are rejected for sorry, and the actual axiom auditor exposes the rogue project axiom plus its dependent theorem. No acceptance bypass or vacuous required model claim was constructed.

## Production and trust boundary

Java validates the same bounded counts, uses safe long intermediates, caps nonfull rounding, and derives completeness from both completed UNSAT category results. Its LinkedHashMap pool implements duplicate stability, strict minimum/oldest eviction, fresh frequency one, mismatch-only saturated increments, and diagnostic epoch saturation. The feedback path retains one context, advances enumeration, admits category witnesses before sampling, and drops a failed context. Python checks evidence shape/status/count relationships and recomputes the cap; `evaluate_behavior` schedules with `cacheable=False` while retaining snapshots for explanation consumers. These reads are boundary plausibility checks only.

Universal Java/Python refinement, authentic concrete trace identity, solver correctness/UNSAT completeness, unbounded equivalence, allocated memory/RSS, fairness/liveness, browser/deployment behavior, historical obligations, and future mutations remain excluded. The kernel/distribution, audit/verifier and hashing machinery, Python/OS/filesystem/network-namespace behavior, SHA-256 and hardware remain explicitly trusted. No claim here promotes runtime tests or review prose to a semantic refinement theorem.

## Exhaustive claim and declaration coverage

Every frozen claim bucket and its exact owned declarations is listed below. The independent audit above covers the executable definitions, preconditions, witness inhabitation, and audit/manifest rules supporting them. No listed bucket has a constructed breach.

### RWD-GATE

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

### RWD-ARITHMETIC

- `Reward.score_numerator_bound`
- `Reward.score_denominator_bound`
- `Reward.java_rounding_numerator_safe`
- `Reward.java_rounding_denominator_safe`

### RWD-WITNESSES

- `Reward.old_rounding_false_one`
- `Reward.repaired_rounding_counterexample`
- `Reward.nonempty_agreement_witness`

### LFU-ADMISSION

- `LFU.admit_bounded`
- `LFU.admit_duplicate_unchanged`
- `LFU.admit_unique`
- `LFU.fresh_admission_present`
- `LFU.continuous_admissions_bounded`
- `LFU.continuous_admissions_unique`

### LFU-EVICTION

- `LFU.victim_member_minimum`
- `LFU.victim_oldest_tie`
- `LFU.oldest_tie_witness`
- `LFU.mismatch_retention_witness`

### LFU-UPDATE

- `LFU.bump_nondecreasing`
- `LFU.bump_bounded`
- `LFU.bump_saturated`
- `LFU.mismatch_length`
- `LFU.mismatch_unique`
- `LFU.mismatch_valid_frequencies`
- `LFU.mismatch_identity_order`
- `LFU.mismatch_retained_hit`

### LFU-CONTEXT

- `LFU.diagnostic_version_nondecreasing`
- `LFU.diagnostic_version_saturates`
- `LFU.context_switch_discards_pool`
- `LFU.same_context_preserves_pool`
- `LFU.completed_request_is_fresh`
- `LFU.identical_inflight_may_join`

### RWD-INTERNAL

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
