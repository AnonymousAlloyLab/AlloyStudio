import Std

/- The closure decision is executable data, not an axiom about an implementation.
   The verifier must obtain each count from its registered evidence collector. -/
namespace AlloyStudio.Foundation

inductive Result where
  | verified
  | blocked
  | infrastructureFailure
  deriving DecidableEq, Repr

structure Counts where
  infrastructureErrors : Nat
  required : Nat
  proved : Nat
  unresolved : Nat
  unmapped : Nat
  invalidWitnesses : Nat
  undeclaredTrust : Nat
  inputChanges : Nat
  failedBuilds : Nat
  nondeterministicArtifacts : Nat
  orphanClaims : Nat
  missingReviews : Nat
  deriving DecidableEq, Repr

def failures (c : Counts) : Nat :=
  c.unresolved + c.unmapped + c.invalidWitnesses + c.undeclaredTrust +
  c.inputChanges + c.failedBuilds + c.nondeterministicArtifacts +
  c.orphanClaims + c.missingReviews

def decideClosure (c : Counts) : Result :=
  if c.infrastructureErrors = 0 then
    if failures c = 0 then
      if c.proved = c.required then .verified else .blocked
    else .blocked
  else .infrastructureFailure

theorem verified_has_complete_count (c : Counts)
    (h : decideClosure c = .verified) : c.proved = c.required := by
  unfold decideClosure at h
  split at h
  · split at h
    · split at h
      · assumption
      · cases h
    · cases h
  · cases h

theorem verified_has_no_failures (c : Counts)
    (h : decideClosure c = .verified) : failures c = 0 := by
  unfold decideClosure at h
  split at h
  · split at h
    · assumption
    · cases h
  · cases h

theorem verified_has_no_infrastructure_error (c : Counts)
    (h : decideClosure c = .verified) : c.infrastructureErrors = 0 := by
  unfold decideClosure at h
  split at h
  · assumption
  · cases h

theorem complete_clean_counts_verify (c : Counts)
    (hi : c.infrastructureErrors = 0) (hf : failures c = 0)
    (hp : c.proved = c.required) : decideClosure c = .verified := by
  unfold decideClosure
  rw [ite_eq_left hi, ite_eq_left hf, ite_eq_left hp]

-- Explicit negative-control inputs: one unresolved obligation cannot pass even
-- when every other counter and the apparent theorem total look successful.
def unresolvedWitness : Counts :=
  ⟨0, 24, 24, 1, 0, 0, 0, 0, 0, 0, 0, 0⟩

theorem unresolved_witness_is_blocked :
    decideClosure unresolvedWitness = .blocked := by decide

def missingReviewWitness : Counts :=
  ⟨0, 24, 24, 0, 0, 0, 0, 0, 0, 0, 0, 1⟩

theorem missing_review_witness_is_blocked :
    decideClosure missingReviewWitness = .blocked := by decide

end AlloyStudio.Foundation
