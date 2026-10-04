import TrafficConfig.Extracted

/-!
Independent interval relations and constructive correspondence at the normalized
scalar boundary. This is neither a complete traffic profile nor a proof of the
Python runtime, arbitrary constructors, allocation accounting or TRF-00 closure.
-/
namespace AlloyStudio.TrafficConfig

/-- Counts accept only the integer variant; an integral-valued float is distinct. -/
def IntegerInterval (minimum maximum : Int) (value : Scalar) : Prop :=
  match value with
  | .integer number => lessEqual 0 minimum ∧ lessEqual minimum maximum ∧ lessEqual minimum number ∧ lessEqual number maximum
  | .floating _ _ => False
  | .invalid _ => False

/-- Cross multiplication is exact because the denominator is strictly positive. -/
def RationalInterval (minimumZero : Bool) (maximum numerator : Int)
    (denominator : Nat) : Prop :=
  0 < denominator ∧ less 0 maximum ∧
  (if minimumZero then lessEqual 0 numerator else less 0 numerator) ∧
  lessEqual numerator (maximum * Int.ofNat denominator)

/-- Invalid host values are excluded independently of evaluator branch order. -/
def SecondsInterval (minimumZero : Bool) (maximum : Int) (value : Scalar) : Prop :=
  match value with
  | .integer number => RationalInterval minimumZero maximum number 1
  | .floating numerator denominator => RationalInterval minimumZero maximum numerator denominator
  | .invalid _ => False

theorem guarded_accept_iff (value : Scalar) (reject expected : Prop) [Decidable reject]
    (characterization : ¬ reject ↔ expected) :
    (if reject then none else some value) = some value ↔ expected := by
  by_cases rejected : reject
  · rw [ite_eq_left rejected]
    constructor
    · intro impossible; cases impossible
    · intro accepted; exact False.elim ((characterization.mpr accepted) rejected)
  · rw [ite_eq_right rejected]
    constructor
    · intro _; exact characterization.mp rejected
    · intro _; rfl

theorem guarded_preserves (value result : Scalar) (reject : Prop) [Decidable reject]
    (accepted : (if reject then none else some value) = some result) : result = value := by
  by_cases rejected : reject
  · rw [ite_eq_left rejected] at accepted; cases accepted
  · rw [ite_eq_right rejected] at accepted; cases accepted; rfl

theorem integer_guard_iff (minimum maximum number : Int) :
    ¬ (less minimum 0 ∨ less maximum minimum ∨
      ¬ (lessEqual minimum number ∧ lessEqual number maximum)) ↔
      lessEqual 0 minimum ∧ lessEqual minimum maximum ∧
      lessEqual minimum number ∧ lessEqual number maximum := by
  constructor
  · intro accepted
    refine ⟨(not_less_iff_lessEqual _ _).mp (fun h => accepted (.inl h)),
      (not_less_iff_lessEqual _ _).mp (fun h => accepted (.inr (.inl h))), ?_⟩
    by_cases inside : lessEqual minimum number ∧ lessEqual number maximum
    · exact inside
    · exact False.elim (accepted (.inr (.inr inside)))
  · intro ⟨minimumValid, boundsValid, inside⟩ violation
    cases violation with
    | inl negative => exact (not_less_iff_lessEqual _ _).mpr minimumValid negative
    | inr rest =>
      cases rest with
      | inl reversed => exact (not_less_iff_lessEqual _ _).mpr boundsValid reversed
      | inr outside => exact outside inside

theorem seconds_guard_iff (minimumZero : Bool) (maximum numerator : Int) (denominator : Nat) :
    ¬ (less numerator 0 ∨ (minimumZero = false ∧ numerator = 0) ∨
      less (maximum * Int.ofNat denominator) numerator) ↔
      (if minimumZero then lessEqual 0 numerator else less 0 numerator) ∧
      lessEqual numerator (maximum * Int.ofNat denominator) := by
  constructor
  · intro admitted
    have lower : lessEqual 0 numerator :=
      (not_less_iff_lessEqual _ _).mp (fun h => admitted (.inl h))
    have upper : lessEqual numerator (maximum * Int.ofNat denominator) :=
      (not_less_iff_lessEqual _ _).mp (fun h => admitted (.inr (.inr h)))
    refine ⟨?_, upper⟩
    cases minimumZero with
    | false =>
      exact (positive_iff_nonnegative_nonzero numerator).mpr
        ⟨lower, fun h => admitted (.inr (.inl ⟨rfl, h⟩))⟩
    | true => exact lower
  · intro ⟨lower, upper⟩ violation
    cases violation with
    | inl negative =>
      cases minimumZero with
      | false =>
        exact (not_less_iff_lessEqual _ _).mpr
          ((positive_iff_nonnegative_nonzero numerator).mp lower).1 negative
      | true => exact (not_less_iff_lessEqual _ _).mpr lower negative
    | inr rest =>
      cases rest with
      | inr excessive => exact (not_less_iff_lessEqual _ _).mpr upper excessive
      | inl zero =>
        obtain ⟨flag, isZero⟩ := zero
        cases minimumZero with
        | false => exact ((positive_iff_nonnegative_nonzero numerator).mp lower).2 isZero
        | true => cases flag

theorem validated_int_iff (value : Scalar) (minimum maximum : Int) :
    Extracted.validatedInt value minimum maximum = some value ↔
      IntegerInterval minimum maximum value := by
  by_cases badBounds : less minimum 0 ∨ less maximum minimum
  · rw [Extracted.validatedInt.eq_def, ite_eq_left badBounds]
    constructor
    · intro impossible; cases impossible
    · intro valid
      cases value with
      | integer number =>
        exact False.elim ((integer_guard_iff minimum maximum number).mpr valid
          (badBounds.elim Or.inl (fun h => .inr (.inl h))))
      | floating numerator denominator => exact False.elim valid
      | invalid reason => exact False.elim valid
  · rw [Extracted.validatedInt.eq_def, ite_eq_right badBounds]
    cases value with
    | integer number =>
      change (if ¬ (lessEqual minimum number ∧ lessEqual number maximum)
        then none else some (Scalar.integer number)) = some (Scalar.integer number) ↔
        lessEqual 0 minimum ∧ lessEqual minimum maximum ∧ lessEqual minimum number ∧ lessEqual number maximum
      apply guarded_accept_iff
      constructor
      · intro inside
        apply (integer_guard_iff minimum maximum number).mp
        intro violation
        cases violation with
        | inl h => exact badBounds (.inl h)
        | inr rest =>
          cases rest with
          | inl h => exact badBounds (.inr h)
          | inr h => exact inside h
      · intro valid
        intro outside
        exact outside valid.2.2
    | floating numerator denominator =>
      constructor
      · intro impossible; cases impossible
      · intro impossible; exact False.elim impossible
    | invalid reason =>
      constructor
      · intro impossible; cases impossible
      · intro impossible; exact False.elim impossible

theorem validated_int_preserves (value result : Scalar) (minimum maximum : Int)
    (accepted : Extracted.validatedInt value minimum maximum = some result) :
    result = value := by
  by_cases badBounds : less minimum 0 ∨ less maximum minimum
  · rw [Extracted.validatedInt.eq_def, ite_eq_left badBounds] at accepted; cases accepted
  · rw [Extracted.validatedInt.eq_def, ite_eq_right badBounds] at accepted
    cases value with
    | integer number => exact guarded_preserves _ _ _ accepted
    | floating numerator denominator => cases accepted
    | invalid reason => cases accepted

theorem validated_seconds_iff (value : Scalar) (minimumZero : Bool) (maximum : Int) :
    Extracted.validatedSeconds value minimumZero maximum = some value ↔
      SecondsInterval minimumZero maximum value := by
  by_cases badBounds : lessEqual maximum 0
  · rw [Extracted.validatedSeconds.eq_def, ite_eq_left badBounds]
    constructor
    · intro impossible; cases impossible
    · intro valid
      cases value with
      | integer number => exact False.elim ((not_lessEqual_iff_less maximum 0).mpr valid.2.1 badBounds)
      | floating numerator denominator => exact False.elim ((not_lessEqual_iff_less maximum 0).mpr valid.2.1 badBounds)
      | invalid reason => exact False.elim valid
  · rw [Extracted.validatedSeconds.eq_def, ite_eq_right badBounds]
    have positive : less 0 maximum := (not_lessEqual_iff_less maximum 0).mp badBounds
    cases value with
    | integer number =>
      change (if less number 0 ∨ (minimumZero = false ∧ number = 0) ∨
        less (maximum * Int.ofNat 1) number then none else some (Scalar.integer number)) = some (Scalar.integer number) ↔
        RationalInterval minimumZero maximum number 1
      apply guarded_accept_iff
      constructor
      · intro admitted
        exact ⟨by decide, positive, (seconds_guard_iff minimumZero maximum number 1).mp admitted⟩
      · intro valid
        exact (seconds_guard_iff minimumZero maximum number 1).mpr valid.2.2
    | floating numerator denominator =>
      cases denominator with
      | zero =>
        constructor
        · intro impossible; cases impossible
        · intro impossible; exact False.elim (Nat.not_lt_zero _ impossible.1)
      | succ denominator =>
        change (if less numerator 0 ∨ (minimumZero = false ∧ numerator = 0) ∨
          less (maximum * Int.ofNat (denominator + 1)) numerator
          then none else some (Scalar.floating numerator (denominator + 1))) =
          some (Scalar.floating numerator (denominator + 1)) ↔
          RationalInterval minimumZero maximum numerator (denominator + 1)
        apply guarded_accept_iff
        constructor
        · intro admitted
          exact ⟨Nat.zero_lt_succ _, positive,
            (seconds_guard_iff minimumZero maximum numerator (denominator + 1)).mp admitted⟩
        · intro valid
          exact (seconds_guard_iff minimumZero maximum numerator (denominator + 1)).mpr valid.2.2
    | invalid reason =>
      constructor
      · intro impossible; cases impossible
      · intro impossible; exact False.elim impossible

theorem validated_seconds_preserves (value result : Scalar) (minimumZero : Bool)
    (maximum : Int)
    (accepted : Extracted.validatedSeconds value minimumZero maximum = some result) :
    result = value := by
  by_cases badBounds : lessEqual maximum 0
  · rw [Extracted.validatedSeconds.eq_def, ite_eq_left badBounds] at accepted; cases accepted
  · rw [Extracted.validatedSeconds.eq_def, ite_eq_right badBounds] at accepted
    cases value with
    | integer number => exact guarded_preserves _ _ _ accepted
    | floating numerator denominator =>
      cases denominator with
      | zero => cases accepted
      | succ denominator => exact guarded_preserves _ _ _ accepted
    | invalid reason => cases accepted

theorem invalid_int_rejected (reason : InvalidKind) (minimum maximum : Int) :
    Extracted.validatedInt (.invalid reason) minimum maximum = none := by
  unfold Extracted.validatedInt
  split <;> rfl

theorem invalid_seconds_rejected (reason : InvalidKind) (minimumZero : Bool) (maximum : Int) :
    Extracted.validatedSeconds (.invalid reason) minimumZero maximum = none := by
  unfold Extracted.validatedSeconds
  split <;> rfl

theorem floating_int_rejected (numerator : Int) (denominator : Nat) (minimum maximum : Int) :
    Extracted.validatedInt (.floating numerator denominator) minimum maximum = none := by
  unfold Extracted.validatedInt
  split <;> rfl

theorem invalid_denominator_rejected (numerator : Int) (minimumZero : Bool) (maximum : Int) :
    Extracted.validatedSeconds (.floating numerator 0) minimumZero maximum = none := by
  unfold Extracted.validatedSeconds
  split <;> rfl

theorem default_zero_rejected :
    Extracted.validatedInt (.integer 0) 1 67108864 = none ∧
    Extracted.validatedSeconds (.integer 0) false 86400 = none := by decide

theorem default_negative_rejected :
    Extracted.validatedInt (.integer (-1)) 1 67108864 = none ∧
    Extracted.validatedSeconds (.floating (-1) 2) true 86400 = none := by decide

theorem default_oversized_rejected :
    Extracted.validatedInt (.integer 67108865) 1 67108864 = none ∧
    Extracted.validatedSeconds (.floating 172801 2) false 86400 = none := by decide

theorem default_huge_integer_rejected :
    Extracted.validatedInt (.integer 10000000000000000000000000000000000000000)
      1 67108864 = none ∧
    Extracted.validatedSeconds (.integer 10000000000000000000000000000000000000000)
      false 86400 = none := by decide

theorem default_maximum_admitted :
    Extracted.validatedInt (.integer 67108864) 1 67108864 = some (.integer 67108864) ∧
    Extracted.validatedSeconds (.floating 86400 1) false 86400 = some (.floating 86400 1) := by decide

theorem optional_zero_admitted :
    Extracted.validatedSeconds (.integer 0) true 86400 = some (.integer 0) ∧
    Extracted.validatedSeconds (.floating 0 1) true 86400 = some (.floating 0 1) := by decide

theorem fractional_seconds_admitted :
    Extracted.validatedSeconds (.floating 1 2) false 86400 = some (.floating 1 2) := by decide

end AlloyStudio.TrafficConfig
