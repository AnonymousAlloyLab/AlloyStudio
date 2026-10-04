import Std

/-!
Normalized scalar boundary for strict numeric runtime configuration.
Python object classification and `float.as_integer_ratio` normalization are an
explicit bridge boundary; these datatypes do not establish their implementation.
No host rounding is used: finite floating values retain their exact ratio.
-/
namespace AlloyStudio.TrafficConfig

/-- Mathematical signed-integer order, written directly over the constructors
to avoid axiom-bearing derived integer-order lemmas in the standard library. -/
def less : Int → Int → Prop
  | .ofNat left, .ofNat right => left < right
  | .ofNat _, .negSucc _ => False
  | .negSucc _, .ofNat _ => True
  | .negSucc left, .negSucc right => right < left

def lessEqual : Int → Int → Prop
  | .ofNat left, .ofNat right => left ≤ right
  | .ofNat _, .negSucc _ => False
  | .negSucc _, .ofNat _ => True
  | .negSucc left, .negSucc right => right ≤ left

instance (left right : Int) : Decidable (less left right) := by
  cases left <;> cases right <;> unfold less <;> infer_instance

instance (left right : Int) : Decidable (lessEqual left right) := by
  cases left <;> cases right <;> unfold lessEqual <;> infer_instance

theorem not_less_iff_lessEqual (left right : Int) :
    (¬ less left right) ↔ lessEqual right left := by
  cases left with
  | ofNat left =>
    cases right with
    | ofNat right => exact ⟨Nat.le_of_not_gt, Nat.not_lt_of_ge⟩
    | negSucc right => exact ⟨fun _ => True.intro, fun _ h => h⟩
  | negSucc left =>
    cases right with
    | ofNat right => exact ⟨fun h => h True.intro, fun h => False.elim h⟩
    | negSucc right => exact ⟨Nat.le_of_not_gt, Nat.not_lt_of_ge⟩

theorem not_lessEqual_iff_less (left right : Int) :
    (¬ lessEqual left right) ↔ less right left := by
  cases left with
  | ofNat left =>
    cases right with
    | ofNat right => exact ⟨Nat.lt_of_not_ge, Nat.not_le_of_gt⟩
    | negSucc right => exact ⟨fun _ => True.intro, fun _ h => h⟩
  | negSucc left =>
    cases right with
    | ofNat right => exact ⟨fun h => h True.intro, fun h => False.elim h⟩
    | negSucc right => exact ⟨Nat.lt_of_not_ge, Nat.not_le_of_gt⟩

theorem positive_iff_nonnegative_nonzero (value : Int) :
    less 0 value ↔ lessEqual 0 value ∧ value ≠ 0 := by
  cases value with
  | ofNat value =>
    cases value with
    | zero =>
      constructor
      · intro h; exact False.elim (Nat.not_lt_zero _ h)
      · intro h; exact False.elim (h.2 rfl)
    | succ value =>
      constructor
      · intro _
        refine ⟨Nat.zero_le _, ?_⟩
        intro h; cases h
      · intro _; exact Nat.zero_lt_succ _
  | negSucc value =>
    constructor
    · intro h; exact False.elim h
    · intro h; exact False.elim h.1

theorem nat_sub_zero_of_le (left right : Nat) (ordered : left ≤ right) : left - right = 0 := by
  induction right generalizing left with
  | zero =>
    cases left with
    | zero => rfl
    | succ left => cases ordered
  | succ right inductionHypothesis =>
    cases left with
    | zero => exact Nat.zero_sub _
    | succ left =>
      exact (Nat.succ_sub_succ_eq_sub left right).trans
        (inductionHypothesis left (Nat.le_of_succ_le_succ ordered))

/-- Primitive subtraction characterizes standard-library integer nonnegativity.
This proof avoids axiom-bearing library order lemmas. -/
theorem nonnegative_subNatNat_iff (left right : Nat) :
    Int.NonNeg (Int.subNatNat left right) ↔ right ≤ left := by
  unfold Int.subNatNat
  cases difference : right - left with
  | zero =>
    constructor
    · intro _; exact Nat.le_of_sub_eq_zero difference
    · intro _; exact Int.NonNeg.mk _
  | succ value =>
    constructor
    · intro impossible; cases impossible
    · intro ordered
      have isZero : right - left = 0 := nat_sub_zero_of_le right left ordered
      rw [difference] at isZero
      cases isZero

/-- The constructor comparison is standard mathematical integer order. -/
theorem lessEqual_iff_standard (left right : Int) : lessEqual left right ↔ left ≤ right := by
  cases left with
  | ofNat left =>
    cases left with
    | zero =>
      cases right with
      | ofNat right =>
        constructor
        · intro _; exact Int.NonNeg.mk _
        · intro _; exact Nat.zero_le _
      | negSucc right =>
        constructor
        · intro impossible; exact False.elim impossible
        · intro impossible; cases impossible
    | succ left =>
      cases right with
      | ofNat right =>
        exact (nonnegative_subNatNat_iff right (left + 1)).symm
      | negSucc right =>
        constructor
        · intro impossible; exact False.elim impossible
        · intro impossible; cases impossible
  | negSucc left =>
    cases right with
    | ofNat right =>
      constructor
      · intro _; exact Int.NonNeg.mk _
      · intro _; exact True.intro
    | negSucc right =>
      constructor
      · intro ordered
        exact (nonnegative_subNatNat_iff (left + 1) (right + 1)).mpr
          (Nat.succ_le_succ ordered)
      · intro ordered
        exact Nat.le_of_succ_le_succ
          ((nonnegative_subNatNat_iff (left + 1) (right + 1)).mp ordered)

theorem less_iff_successor_lessEqual (left right : Int) :
    less left right ↔ lessEqual (left + 1) right := by
  cases left with
  | ofNat left =>
    cases right with
    | ofNat right => exact Iff.rfl
    | negSucc right => exact Iff.rfl
  | negSucc left =>
    cases left with
    | zero =>
      cases right with
      | ofNat right =>
        constructor
        · intro _; exact Nat.zero_le _
        · intro _; exact True.intro
      | negSucc right =>
        constructor
        · intro impossible; exact Nat.not_lt_zero _ impossible
        · intro impossible; exact False.elim impossible
    | succ left =>
      cases right with
      | ofNat right => exact Iff.rfl
      | negSucc right => exact ⟨Nat.le_of_lt_succ, Nat.lt_succ_of_le⟩

/-- The strict constructor comparison agrees with standard integer order. -/
theorem less_iff_standard (left right : Int) : less left right ↔ left < right :=
  (less_iff_successor_lessEqual left right).trans (lessEqual_iff_standard (left + 1) right)

inductive InvalidKind where
  | boolean
  | nan
  | positiveInfinity
  | negativeInfinity
  | other
  deriving DecidableEq

inductive Scalar where
  | integer (value : Int)
  | floating (numerator : Int) (denominator : Nat)
  | invalid (reason : InvalidKind)
  deriving DecidableEq

end AlloyStudio.TrafficConfig
