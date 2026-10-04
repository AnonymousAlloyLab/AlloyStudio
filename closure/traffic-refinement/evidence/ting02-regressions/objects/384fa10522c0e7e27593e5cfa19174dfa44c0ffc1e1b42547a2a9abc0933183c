import Std

/-!
Finite complete-pool selection. This module proves properties of the definitions
below, not a refinement of Java evaluation or Python pool decoding. In particular,
the supplied evaluator is not assumed to compute a semantic Alloy distance.
-/
namespace AlloyStudio.Pool

structure Scored (α : Type) where
  value : α
  cost : Nat
  deriving DecidableEq, Repr

/-- The strict-improvement choice used by both Java pool loops. -/
def prefer (old candidate : Scored α) : Scored α :=
  if candidate.cost < old.cost then candidate else old

theorem prefer_assoc (a b c : Scored α) :
    prefer (prefer a b) c = prefer a (prefer b c) := by
  unfold prefer
  by_cases hab : b.cost < a.cost
  · by_cases hbc : c.cost < b.cost
    · have hac := Nat.lt_trans hbc hab
      rw [ite_eq_left hab, ite_eq_left hbc, ite_eq_left hac]
    · rw [ite_eq_left hab, ite_eq_right hbc, ite_eq_left hab]
  · by_cases hbc : c.cost < b.cost
    · by_cases hac : c.cost < a.cost
      · rw [ite_eq_right hab, ite_eq_left hbc, ite_eq_left hac]
      · rw [ite_eq_right hab, ite_eq_left hbc, ite_eq_right hac]
    · have hac : ¬c.cost < a.cost :=
        Nat.not_lt_of_ge (Nat.le_trans (Nat.le_of_not_gt hab) (Nat.le_of_not_gt hbc))
      rw [ite_eq_right hab, ite_eq_right hbc, ite_eq_right hac, ite_eq_right hab]

/-- Tail-recursive scan in the same direction as the Java loop. -/
def scan (best : Scored α) : List (Scored α) → Scored α
  | [] => best
  | x :: xs => scan (prefer best x) xs

/-- Recursive presentation used only to prove the scan's first-tie policy. -/
def firstMinimum : List (Scored α) → Option (Scored α)
  | [] => none
  | x :: xs =>
    match firstMinimum xs with
    | none => some x
    | some y => some (prefer x y)

theorem scan_prefer (a b : Scored α) (xs : List (Scored α)) :
    scan (prefer a b) xs = prefer a (scan b xs) := by
  induction xs generalizing a b with
  | nil => rfl
  | cons x xs ih =>
    change scan (prefer (prefer a b) x) xs = prefer a (scan (prefer b x) xs)
    rw [prefer_assoc, ih]

theorem firstMinimum_cons (x : Scored α) (xs : List (Scored α)) :
    firstMinimum (x :: xs) = some (scan x xs) := by
  induction xs generalizing x with
  | nil => rfl
  | cons y ys ih =>
    change (match firstMinimum (y :: ys) with
      | none => some x
      | some y => some (prefer x y)) = some (scan x (y :: ys))
    rw [ih]
    change some (prefer x (scan y ys)) = some (scan (prefer x y) ys)
    rw [scan_prefer]

def IsFirstMinimum (xs : List (Scored α)) (winner : Scored α) : Prop :=
  ∃ before after, xs = before ++ winner :: after ∧
    (∀ candidate ∈ before, winner.cost < candidate.cost) ∧
    (∀ candidate ∈ after, winner.cost ≤ candidate.cost)

theorem firstMinimum_none_iff (xs : List (Scored α)) :
    firstMinimum xs = none ↔ xs = [] := by
  cases xs with
  | nil => exact ⟨fun _ => rfl, fun _ => rfl⟩
  | cons x xs =>
    constructor
    · intro h
      rw [firstMinimum_cons] at h
      cases h
    · intro h; cases h

/-- Constructive membership eliminators avoid extensionality-based library
rewrites in the exported theorem dependency graph. -/
theorem member_cons {x y : α} {xs : List α} :
    x ∈ y :: xs ↔ x = y ∨ x ∈ xs := by
  constructor
  · intro h
    cases h with
    | head => exact Or.inl rfl
    | tail _ h => exact Or.inr h
  · intro h
    cases h with
    | inl h => cases h; exact .head _
    | inr h => exact .tail _ h

theorem member_append {x : α} (xs ys : List α) :
    x ∈ xs ++ ys ↔ x ∈ xs ∨ x ∈ ys := by
  induction xs with
  | nil =>
    constructor
    · exact Or.inr
    · intro h; cases h with
      | inl h => cases h
      | inr h => exact h
  | cons y xs ih =>
    constructor
    · intro h
      cases (member_cons.mp h) with
      | inl h => cases h; exact Or.inl (.head _)
      | inr h =>
        cases ih.mp h with
        | inl h => exact Or.inl (.tail _ h)
        | inr h => exact Or.inr h
    · intro h
      cases h with
      | inl h =>
        cases member_cons.mp h with
        | inl h => cases h; exact .head _
        | inr h => exact .tail _ (ih.mpr (Or.inl h))
      | inr h => exact .tail _ (ih.mpr (Or.inr h))

theorem map_member (f : α → β) {x : α} {xs : List α} (h : x ∈ xs) :
    f x ∈ xs.map f := by
  induction h with
  | head => exact .head _
  | tail _ _ ih => exact .tail _ ih

theorem firstMinimum_spec (xs : List (Scored α)) (winner : Scored α)
    (h : firstMinimum xs = some winner) : IsFirstMinimum xs winner := by
  induction xs generalizing winner with
  | nil => cases h
  | cons x xs ih =>
    cases e : firstMinimum xs with
    | none =>
      have empty := (firstMinimum_none_iff xs).mp e
      subst xs
      have equal := Option.some.inj h
      cases equal
      exact ⟨[], [], rfl, (fun _ h => nomatch h), (fun _ h => nomatch h)⟩
    | some tailWinner =>
      obtain ⟨before, after, shape, earlier, later⟩ := ih tailWinner e
      change (match firstMinimum xs with
        | none => some x
        | some y => some (prefer x y)) = some winner at h
      rw [e] at h
      have h := Option.some.inj h
      by_cases improve : tailWinner.cost < x.cost
      · unfold prefer at h
        rw [ite_eq_left improve] at h
        subst winner
        refine ⟨x :: before, after, ?_, ?_, later⟩
        · exact congrArg (List.cons x) shape
        · intro candidate member
          rcases member_cons.mp member with equal | member
          · subst candidate; exact improve
          · exact earlier candidate member
      · unfold prefer at h
        rw [ite_eq_right improve] at h
        subst winner
        refine ⟨[], xs, rfl, (fun _ h => nomatch h), ?_⟩
        intro candidate member
        rw [shape] at member
        rcases (member_append before (tailWinner :: after)).mp member with member | member
        · exact Nat.le_trans (Nat.le_of_not_gt improve) (Nat.le_of_lt (earlier candidate member))
        · rcases member_cons.mp member with equal | member
          · subst candidate; exact Nat.le_of_not_gt improve
          · exact Nat.le_trans (Nat.le_of_not_gt improve) (later candidate member)

theorem firstMinimum_member_and_minimal (xs : List (Scored α)) (winner : Scored α)
    (h : firstMinimum xs = some winner) :
    winner ∈ xs ∧ ∀ candidate ∈ xs, winner.cost ≤ candidate.cost := by
  obtain ⟨before, after, shape, earlier, later⟩ := firstMinimum_spec xs winner h
  subst xs
  constructor
  · exact (member_append before (winner :: after)).mpr (Or.inr (.head _))
  · intro candidate member
    rcases (member_append before (winner :: after)).mp member with member | member
    · exact Nat.le_of_lt (earlier candidate member)
    · rcases member_cons.mp member with equal | member
      · subst candidate; exact Nat.le_refl _
      · exact later candidate member

/-- Explicit complete evaluation: an error anywhere prevents a scored pool. -/
def evaluate (cost : α → Option Nat) : List α → Option (List (Scored α))
  | [] => some []
  | x :: xs =>
    match cost x with
    | none => none
    | some n =>
      match evaluate cost xs with
      | none => none
      | some scored => some (⟨x, n⟩ :: scored)

theorem evaluate_none_iff (cost : α → Option Nat) (xs : List α) :
    evaluate cost xs = none ↔ ∃ x ∈ xs, cost x = none := by
  induction xs with
  | nil =>
    constructor
    · intro h; cases h
    · intro ⟨x, h, _⟩; cases h
  | cons x xs ih =>
    cases hcost : cost x with
    | none =>
      constructor
      · intro _; exact ⟨x, .head _, hcost⟩
      · intro _; unfold evaluate; rw [hcost]
    | some n =>
      cases hrest : evaluate cost xs with
      | none =>
        constructor
        · intro _
          obtain ⟨y, member, failed⟩ := ih.mp hrest
          exact ⟨y, .tail _ member, failed⟩
        · intro _; unfold evaluate; rw [hcost, hrest]
      | some rest =>
        constructor
        · intro h; unfold evaluate at h; rw [hcost, hrest] at h; cases h
        · intro ⟨y, member, failed⟩
          cases member_cons.mp member with
          | inl equal =>
            cases equal
            rw [hcost] at failed
            cases failed
          | inr member =>
            have impossible := ih.mpr ⟨y, member, failed⟩
            rw [hrest] at impossible
            cases impossible

theorem evaluate_success (cost : α → Option Nat) (xs : List α)
    (scored : List (Scored α)) (h : evaluate cost xs = some scored) :
    scored.map Scored.value = xs ∧
    scored.length = xs.length ∧
    ∀ item ∈ scored, cost item.value = some item.cost := by
  induction xs generalizing scored with
  | nil =>
    have h := Option.some.inj h
    subst scored
    exact ⟨rfl, rfl, fun _ h => nomatch h⟩
  | cons x xs ih =>
    cases hc : cost x with
    | none =>
      unfold evaluate at h
      rw [hc] at h
      cases h
    | some n =>
      cases hr : evaluate cost xs with
      | none => unfold evaluate at h; rw [hc, hr] at h; cases h
      | some rest =>
        unfold evaluate at h
        rw [hc, hr] at h
        have h := Option.some.inj h
        subst scored
        obtain ⟨values, count, certified⟩ := ih rest hr
        refine ⟨congrArg (List.cons x) values, congrArg Nat.succ count, ?_⟩
        intro item member
        rcases member_cons.mp member with equal | member
        · subst item; exact hc
        · exact certified item member

def completeArgmin (cost : α → Option Nat) (xs : List α) : Option (Scored α) :=
  (evaluate cost xs).bind firstMinimum

/-- Successful selection includes a certificate for every candidate, strict
first-minimum order, membership, and the minimum bound over the whole pool. -/
theorem argmin_complete_first_minimum (cost : α → Option Nat) (xs : List α)
    (winner : Scored α) (h : completeArgmin cost xs = some winner) :
    ∃ scored, evaluate cost xs = some scored ∧
      scored.map Scored.value = xs ∧ scored.length = xs.length ∧
      (∀ item ∈ scored, cost item.value = some item.cost) ∧
      IsFirstMinimum scored winner ∧ winner.value ∈ xs ∧
      cost winner.value = some winner.cost ∧
      (∀ item ∈ scored, winner.cost ≤ item.cost) := by
  unfold completeArgmin at h
  cases he : evaluate cost xs with
  | none => rw [he] at h; cases h
  | some scored =>
    rw [he] at h
    change firstMinimum scored = some winner at h
    obtain ⟨values, count, all⟩ := evaluate_success cost xs scored he
    obtain ⟨member, minimal⟩ := firstMinimum_member_and_minimal scored winner h
    refine ⟨scored, rfl, values, count, all, firstMinimum_spec scored winner h, ?_,
      all winner member, minimal⟩
    rw [← values]
    exact map_member Scored.value member

theorem failed_candidate_prevents_result (cost : α → Option Nat) (xs : List α)
    (x : α) (member : x ∈ xs) (failed : cost x = none) :
    completeArgmin cost xs = none := by
  have h := (evaluate_none_iff cost xs).mpr ⟨x, member, failed⟩
  unfold completeArgmin
  rw [h]
  rfl

/-- The importer constructs exactly this inclusive order: students, then oracle. -/
def inclusivePool (students : List α) (oracle : α) : List α := students ++ [oracle]

theorem inclusivePool_oracle_member (students : List α) (oracle : α) :
    oracle ∈ inclusivePool students oracle :=
  (member_append students [oracle]).mpr (Or.inr (.head _))

theorem inclusivePool_nonempty (students : List α) (oracle : α) :
    inclusivePool students oracle ≠ [] := by
  intro h
  have member := inclusivePool_oracle_member students oracle
  rw [h] at member
  cases member

theorem failure_after_zero (cost : α → Option Nat) (zero invalid : α)
    (before suffix : List α) (_zeroCost : cost zero = some 0)
    (failure : cost invalid = none) :
    completeArgmin cost (zero :: (before ++ invalid :: suffix)) = none := by
  apply failed_candidate_prevents_result cost _ invalid
  · exact .tail _ ((member_append before (invalid :: suffix)).mpr (Or.inr (.head _)))
  · exact failure

/-- Regression witness: a zero never hides a later failure. -/
example : completeArgmin (fun n : Nat => if n = 0 then some 0 else none) [0, 1] = none := by
  decide

/-- Equal costs retain the earlier input even when a later candidate also wins. -/
example : firstMinimum [Scored.mk "first" 2, Scored.mk "second" 2] =
    some (Scored.mk "first" 2) := by decide

end AlloyStudio.Pool
