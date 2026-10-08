import Std

/-! An executable model of counterexample-safe bounded behavioral scoring.
This module proves only the definitions below, not Alloy solver correctness. -/
namespace Reward

structure Evidence where
  positive : Nat
  negative : Nat
  accepted : Nat
  rejected : Nat
  correction : Nat
  underUnsatComplete : Bool
  overUnsatComplete : Bool
  deriving DecidableEq

def valid (e : Evidence) : Prop :=
  e.accepted ≤ e.positive ∧ e.rejected ≤ e.negative ∧
  e.positive ≤ 100 ∧ e.negative ≤ 100 ∧ e.correction ≤ 2

def available (e : Evidence) : Prop := e.positive > 0 ∧ e.negative > 0

def samplesAgree (e : Evidence) : Prop :=
  e.accepted = e.positive ∧ e.rejected = e.negative

def fullAgreement (e : Evidence) : Prop :=
  available e ∧ samplesAgree e ∧ e.correction = 0 ∧
  e.underUnsatComplete = true ∧ e.overUnsatComplete = true

instance (e : Evidence) : Decidable (valid e) := by unfold valid; infer_instance
instance (e : Evidence) : Decidable (available e) := by unfold available; infer_instance
instance (e : Evidence) : Decidable (samplesAgree e) := by unfold samplesAgree; infer_instance
instance (e : Evidence) : Decidable (fullAgreement e) := by unfold fullAgreement; infer_instance

def numerator (e : Evidence) : Nat := e.accepted * e.rejected

def denominator (e : Evidence) : Nat := e.positive * e.negative + e.correction

/-- Integer half-up rounding; callers gate zero denominators before evaluation. -/
def rounded (e : Evidence) : Nat :=
  (2000 * numerator e + denominator e) / (2 * denominator e)

def score (e : Evidence) : Option Nat :=
  if valid e ∧ available e then
    some (if fullAgreement e then 1000 else min 999 (rounded e))
  else none

theorem empty_positive_unavailable (e : Evidence) (h : e.positive = 0) :
    score e = none := by
  unfold score available
  have absent : ¬(valid e ∧ (e.positive > 0 ∧ e.negative > 0)) := by
    intro witness
    have impossible := witness.2.1
    rw [h] at impossible
    exact Nat.lt_irrefl 0 impossible
  exact ite_eq_right absent

theorem empty_negative_unavailable (e : Evidence) (h : e.negative = 0) :
    score e = none := by
  unfold score available
  have absent : ¬(valid e ∧ (e.positive > 0 ∧ e.negative > 0)) := by
    intro witness
    have impossible := witness.2.2
    rw [h] at impossible
    exact Nat.lt_irrefl 0 impossible
  exact ite_eq_right absent

theorem invalid_unavailable (e : Evidence) (h : ¬valid e) : score e = none := by
  unfold score
  exact ite_eq_right (fun witness => h witness.1)

theorem available_denominator_positive (e : Evidence) (h : available e) :
    denominator e > 0 := by
  have hp := h.1
  have hn := h.2
  have product := Nat.mul_pos hp hn
  unfold denominator
  exact Nat.lt_of_lt_of_le product (Nat.le_add_right _ _)

theorem constructive_min_left (a b : Nat) : min a b ≤ a := by
  change (if a ≤ b then a else b) ≤ a
  split
  · exact Nat.le_refl a
  · exact Nat.le_of_lt (Nat.lt_of_not_ge (by assumption))

theorem score_is_bounded (e : Evidence) (millis : Nat) (h : score e = some millis) :
    millis ≤ 1000 := by
  unfold score at h
  split at h
  · split at h
    · exact Option.some.inj h ▸ Nat.le_refl 1000
    · have equal := Option.some.inj h
      have bound := constructive_min_left 999 (rounded e)
      exact equal ▸ Nat.le_trans bound (by decide : 999 ≤ 1000)
  · cases h

theorem nonfull_score_lt_one (e : Evidence) (millis : Nat)
    (notFull : ¬fullAgreement e) (h : score e = some millis) : millis < 1000 := by
  unfold score at h
  split at h
  · have equal := Option.some.inj h
    have bound := constructive_min_left 999 (rounded e)
    exact equal ▸ Nat.lt_of_le_of_lt bound (by decide : 999 < 1000)
  · cases h

theorem full_score_iff (e : Evidence) :
    score e = some 1000 ↔ valid e ∧ fullAgreement e := by
  constructor
  · intro h
    have acceptable : valid e ∧ available e := by
      unfold score at h
      split at h
      · assumption
      · cases h
    constructor
    · exact acceptable.1
    · by_cases full : fullAgreement e
      · exact full
      · have impossible := nonfull_score_lt_one e 1000 full h
        exact False.elim (Nat.lt_irrefl 1000 impossible)
  · intro h
    unfold score
    rw [ite_eq_left ⟨h.1, h.2.1⟩, ite_eq_left h.2]

theorem counterexample_correction_lt_one (e : Evidence) (millis : Nat)
    (counterexample : e.correction > 0) (h : score e = some millis) : millis < 1000 := by
  apply nonfull_score_lt_one e millis ?_ h
  intro full
  have absent := full.2.2.1
  rw [absent] at counterexample
  exact Nat.lt_irrefl 0 counterexample

theorem undercoverage_check_lt_one (e : Evidence) (millis : Nat)
    (counterexample : e.underUnsatComplete = false) (h : score e = some millis) :
    millis < 1000 := by
  apply nonfull_score_lt_one e millis ?_ h
  intro full
  have complete := full.2.2.2.1
  rw [counterexample] at complete
  cases complete

theorem overcoverage_check_lt_one (e : Evidence) (millis : Nat)
    (counterexample : e.overUnsatComplete = false) (h : score e = some millis) :
    millis < 1000 := by
  apply nonfull_score_lt_one e millis ?_ h
  intro full
  have complete := full.2.2.2.2
  rw [counterexample] at complete
  cases complete

theorem sample_mismatch_lt_one (e : Evidence) (millis : Nat)
    (mismatch : ¬samplesAgree e) (h : score e = some millis) : millis < 1000 := by
  apply nonfull_score_lt_one e millis ?_ h
  exact fun full => mismatch full.2.1

theorem nonfull_rounding (e : Evidence) (acceptable : valid e)
    (nonempty : available e) (notFull : ¬fullAgreement e) :
    score e = some (min 999 (rounded e)) := by
  unfold score
  rw [ite_eq_left ⟨acceptable, nonempty⟩, ite_eq_right notFull]

theorem score_numerator_bound (e : Evidence) (h : valid e) : numerator e ≤ 10000 := by
  obtain ⟨ha, hr, hp, hn, _⟩ := h
  have a : e.accepted ≤ 100 := Nat.le_trans ha hp
  have r : e.rejected ≤ 100 := Nat.le_trans hr hn
  exact Nat.mul_le_mul a r

theorem score_denominator_bound (e : Evidence) (h : valid e) : denominator e ≤ 10002 := by
  obtain ⟨_, _, hp, hn, hc⟩ := h
  have product := Nat.mul_le_mul hp hn
  unfold denominator
  exact Nat.add_le_add product hc

theorem java_rounding_numerator_safe (e : Evidence) (h : valid e) :
    2000 * numerator e + denominator e ≤ 20010002 := by
  have hn := score_numerator_bound e h
  have hd := score_denominator_bound e h
  exact Nat.add_le_add (Nat.mul_le_mul_left 2000 hn) hd

theorem java_rounding_denominator_safe (e : Evidence) (h : valid e) :
    2 * denominator e ≤ 20004 := by
  have hd := score_denominator_bound e h
  exact Nat.mul_le_mul_left 2 hd

/-- Constructed regression: the old rounding reports 1000 with a mismatch. -/
def oldRoundingCounterexample : Evidence :=
  ⟨100, 100, 100, 100, 1, false, true⟩

theorem old_rounding_false_one : rounded oldRoundingCounterexample = 1000 := by decide

theorem repaired_rounding_counterexample : score oldRoundingCounterexample = some 999 := by decide

theorem nonempty_agreement_witness : score ⟨1, 1, 1, 1, 0, true, true⟩ = some 1000 := by decide

end Reward
