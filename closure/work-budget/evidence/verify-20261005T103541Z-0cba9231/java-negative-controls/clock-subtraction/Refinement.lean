import Generated
import Work

namespace AlloyStudio.WorkBudget

/-- The real field initializer establishes the first 1024-call interval. -/
theorem initial_cadence : Generated.initialChecks = 1024 := rfl

/-- Semantic equality of the extracted Java branches and the independent contract. -/
theorem checkpoint_refines (s : State) (units now : Int) :
    Generated.checkpoint s units now = checkpointSpec s now := by
  cases ha : s.active <;> cases he : s.exhausted <;> cases ht : s.timed <;>
    simp_all [Generated.checkpoint, checkpointSpec]

theorem sample_refines (s : State) (units now : Int)
    (lo : 1 ≤ s.checks) (hi : s.checks ≤ 1024) :
    Generated.sampleClock s units now = sampleSpec s now := by
  have wrapped : javaInt (s.checks - 1) = s.checks - 1 := javaInt_exact _ (by omega) (by omega)
  unfold Generated.sampleClock sampleSpec
  split <;> rename_i timed
  · rfl
  · simp only [wrapped]
    have one : s.checks - 1 = 0 ↔ s.checks = 1 := by omega
    simp only [one]
    split <;> rename_i count
    · rw [checkpoint_refines]
      cases checkpointSpec { s with checks := 1024 } now <;> rfl
    · rfl

private theorem checkpoint_ok_remaining (s next : State) (now : Int)
    (h : checkpointSpec s now = .ok next) : next.remaining = s.remaining := by
  unfold checkpointSpec at h
  split at h
  · cases h; rfl
  · split at h
    · cases h
    · split at h
      · cases h
      · cases h; rfl

private theorem sample_ok_remaining (s next : State) (now : Int)
    (h : sampleSpec s now = .ok next) : next.remaining = s.remaining := by
  unfold sampleSpec at h
  split at h
  · cases h; rfl
  · split at h
    · exact checkpoint_ok_remaining { s with checks := 1024 } next now h
    · cases h; rfl

theorem charge_refines (s : State) (units now : Int) (valid : Valid s) :
    Generated.charge s units now = chargeSpec s units now := by
  have remLo := valid.1
  have remHi := valid.2.1
  unfold Generated.charge chargeSpec
  split <;> rename_i active
  · rfl
  · split <;> rename_i negative
    · rfl
    · split <;> rename_i refused
      · rfl
      · rw [sample_refines s units now valid.2.2.1 valid.2.2.2]
        cases h : sampleSpec s now with
        | invalid next => rfl
        | exhausted next => rfl
        | ok next =>
          have remains := sample_ok_remaining s next now h
          have exact : javaLong (next.remaining - units) = next.remaining - units := by
            apply javaLong_exact <;> unfold longMin longMax at * <;> simp_all <;> omega
          simp only [exact]

/-- A successful active charge has validated its full nonnegative cost. -/
theorem admitted_charge_cost (s next : State) (units now : Int)
    (valid : Valid s) (active : s.active = true)
    (accepted : Generated.charge s units now = .ok next) :
    0 ≤ units ∧ units ≤ s.remaining ∧ next.remaining = s.remaining - units := by
  rw [charge_refines s units now valid] at accepted
  unfold chargeSpec at accepted
  simp only [active, Bool.true_eq_false, ↓reduceIte] at accepted
  split at accepted <;> rename_i negative
  · cases accepted
  · split at accepted <;> rename_i refused
    · cases accepted
    · cases h : sampleSpec s now with
      | invalid state => simp only [h] at accepted; cases accepted
      | exhausted state => simp only [h] at accepted; cases accepted
      | ok state =>
        simp only [h] at accepted
        have remains := sample_ok_remaining s state now h
        injection accepted with accepted
        subst next
        exact ⟨by omega, by omega, by simp only [remains]⟩

/-- Connect an admitted real Java batch to AP01's existing atomic-work fold.
This proves the guard admits only complete batches; call-site completeness and
execution of each claimed operation remain outside this narrow bridge. -/
theorem admitted_batch_matches_atomic_contract {S A : Type}
    (step : S → A → S) (work : List A) (payload : S) (s next : State) (now : Int)
    (valid : Valid s) (active : s.active = true)
    (accepted : Generated.charge s (Int.ofNat work.length) now = .ok next) :
    AlloyStudio.PatchContracts.Work.boundedFold step s.remaining.toNat work payload =
      some (AlloyStudio.PatchContracts.Work.fullFold step work payload,
            s.remaining.toNat - work.length) := by
  apply AlloyStudio.PatchContracts.Work.complete_exact
  have cost := (admitted_charge_cost s next _ now valid active accepted).2.1
  have nonneg := valid.1
  have cast : (s.remaining.toNat : Int) = s.remaining := Int.toNat_of_nonneg nonneg
  change (work.length : Int) ≤ s.remaining at cost
  omega

private theorem checkpoint_preserves_valid (s : State) (now : Int) (valid : Valid s) :
    Valid (resultState (checkpointSpec s now)) := by
  unfold checkpointSpec
  split
  · exact valid
  · split
    · exact valid
    · split
      · exact valid
      · exact valid

theorem sample_preserves_valid (s : State) (units now : Int) (valid : Valid s) :
    Valid (resultState (Generated.sampleClock s units now)) := by
  rw [sample_refines s units now valid.2.2.1 valid.2.2.2]
  unfold sampleSpec
  split
  · exact valid
  · split
    · apply checkpoint_preserves_valid
      exact ⟨valid.1, valid.2.1, by change (1 : Int) ≤ 1024; decide, by change (1024 : Int) ≤ 1024; decide⟩
    · dsimp [resultState, Valid]
      exact ⟨valid.1, valid.2.1, by have := valid.2.2.1; omega, by have := valid.2.2.2; omega⟩

/-- This induction invariant covers subsequent sampled calls as well as the first. -/
theorem charge_preserves_valid (s : State) (units now : Int) (valid : Valid s) :
    Valid (resultState (Generated.charge s units now)) := by
  rw [charge_refines s units now valid]
  unfold chargeSpec
  split
  · exact valid
  · split
    · exact valid
    · split <;> rename_i refused
      · exact valid
      · have sampleValid := sample_preserves_valid s units now valid
        rw [sample_refines s units now valid.2.2.1 valid.2.2.2] at sampleValid
        cases h : sampleSpec s now with
        | invalid next => simpa only [h] using sampleValid
        | exhausted next => simpa only [h] using sampleValid
        | ok next =>
          have remains := sample_ok_remaining s next now h
          simp only [h, resultState, Valid] at sampleValid ⊢
          exact ⟨by omega, by unfold longMax at *; omega, sampleValid.2.2⟩

/-- Countdown decreases on each timed call until the source-derived sampling branch. -/
theorem timed_countdown_decreases (s : State) (units now : Int) (valid : Valid s)
    (timed : s.timed = true) (before : 1 < s.checks) :
    Generated.sampleClock s units now = .ok { s with checks := s.checks - 1 } := by
  rw [sample_refines s units now valid.2.2.1 valid.2.2.2]
  simp [sampleSpec, timed, show s.checks ≠ 1 by omega]

/-- At the next due sample, real checkpoint semantics is invoked and cadence resets. -/
theorem due_sample_observes_clock (s : State) (units now : Int) (valid : Valid s)
    (timed : s.timed = true) (due : s.checks = 1) :
    Generated.sampleClock s units now = checkpointSpec { s with checks := 1024 } now := by
  rw [sample_refines s units now valid.2.2.1 valid.2.2.2]
  simp [sampleSpec, timed, due]

/-- Sticky expiry cannot be revived by a later clock value or spare fuel. -/
theorem exhausted_checkpoint_sticky (s : State) (units now : Int)
    (active : s.active = true) (stopped : s.exhausted = true) :
    Generated.checkpoint s units now = .exhausted s := by
  rw [checkpoint_refines]
  simp [checkpointSpec, active, stopped]

/-- Mandatory publication observation rejects expiry independently of countdown. -/
theorem expired_publication_refused (s : State) (units now : Int)
    (active : s.active = true) (running : s.exhausted = false) (timed : s.timed = true)
    (elapsed : s.limit ≤ javaLong (now - s.started)) :
    Generated.checkpoint s units now = .exhausted { s with exhausted := true } := by
  rw [checkpoint_refines]
  simp [checkpointSpec, active, running, timed, elapsed]
end AlloyStudio.WorkBudget
