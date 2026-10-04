import AlloyStudio.Pool

/-!
Finite policy kernels for the pool loops, and their connection to the complete
pool model. The Boolean atom order is part of the interface. These proofs do not
establish Java parsing, cost computation, iteration, or numeric conversion.
-/
namespace AlloyStudio.PoolBridge

open AlloyStudio.Pool

/-- Ordered atoms: `[hasBest, improves]`; malformed vectors are rejected. -/
def choosePolicy : List Bool → Bool
  | [] => false
  | hasBest :: rest =>
    match rest with
    | [] => false
    | improves :: tail =>
      match tail with
      | [] => !hasBest || improves
      | _ :: _ => false

/-- Ordered atoms: `[nonempty, complete]`; malformed vectors are rejected. -/
def finishPolicy : List Bool → Bool
  | [] => false
  | nonempty :: rest =>
    match rest with
    | [] => false
    | complete :: tail =>
      match tail with
      | [] => nonempty && complete
      | _ :: _ => false

theorem choosePolicy_kernel (hasBest improves : Bool) :
    choosePolicy [hasBest, improves] = (!hasBest || improves) := rfl

theorem finishPolicy_kernel (nonempty complete : Bool) :
    finishPolicy [nonempty, complete] = (nonempty && complete) := rfl

theorem choosePolicy_rejects_arity (inputs : List Bool) (h : inputs.length ≠ 2) :
    choosePolicy inputs = false := by
  cases inputs with
  | nil => rfl
  | cons a rest =>
    cases rest with
    | nil => rfl
    | cons b rest =>
      cases rest with
      | nil => exact False.elim (h rfl)
      | cons c rest => rfl

theorem finishPolicy_rejects_arity (inputs : List Bool) (h : inputs.length ≠ 2) :
    finishPolicy inputs = false := by
  cases inputs with
  | nil => rfl
  | cons a rest =>
    cases rest with
    | nil => rfl
    | cons b rest =>
      cases rest with
      | nil => exact False.elim (h rfl)
      | cons c rest => rfl

/-- The finite domain and its order are also used when exporting the tables. -/
def booleanInputs : List (List Bool) :=
  [[false, false], [false, true], [true, false], [true, true]]

theorem choosePolicy_table : booleanInputs.map choosePolicy = [true, true, false, true] := rfl

theorem finishPolicy_table : booleanInputs.map finishPolicy = [false, false, false, true] := rfl

theorem booleanInputs_exhaustive (a b : Bool) : [a, b] ∈ booleanInputs := by
  cases a with
  | false =>
    cases b with
    | false => exact .head _
    | true => exact .tail _ (.head _)
  | true =>
    cases b with
    | false => exact .tail _ (.tail _ (.head _))
    | true => exact .tail _ (.tail _ (.tail _ (.head _)))

/-- The incumbent and strict comparison supply the policy's ordered atoms. -/
def chooseScored (best : Option (Scored α)) (candidate : Scored α) : Option (Scored α) :=
  let improves := match best with
    | none => false
    | some old => decide (candidate.cost < old.cost)
  if choosePolicy [best.isSome, improves] then some candidate else best

theorem chooseScored_empty (candidate : Scored α) :
    chooseScored none candidate = some candidate := rfl

theorem chooseScored_incumbent (old candidate : Scored α) :
    chooseScored (some old) candidate = some (prefer old candidate) := by
  change (if decide (candidate.cost < old.cost) then some candidate else some old) =
    some (if candidate.cost < old.cost then candidate else old)
  by_cases h : candidate.cost < old.cost
  · rw [ite_eq_left h, decide_eq_true h]
    rfl
  · rw [ite_eq_right h, decide_eq_false h]
    rfl

theorem chooseScored_tie (old candidate : Scored α) (h : candidate.cost = old.cost) :
    chooseScored (some old) candidate = some old := by
  rw [chooseScored_incumbent]
  unfold prefer
  have noImprovement : ¬candidate.cost < old.cost := by
    rw [h]
    exact Nat.lt_irrefl _
  rw [ite_eq_right noImprovement]

theorem finishPolicy_accepts_iff (nonempty complete : Bool) :
    finishPolicy [nonempty, complete] = true ↔ nonempty = true ∧ complete = true := by
  cases nonempty with
  | false =>
    constructor
    · intro impossible; cases impossible
    · intro h; cases h.1
  | true =>
    cases complete with
    | false =>
      constructor
      · intro impossible; cases impossible
      · intro h; cases h.2
    | true => exact ⟨fun _ => ⟨rfl, rfl⟩, fun _ => rfl⟩

/-- Final publication is gated by completeness and the existence of a winner. -/
def finishScored (complete : Bool) (best : Option (Scored α)) : Option (Scored α) :=
  if finishPolicy [best.isSome, complete] then best else none

theorem finishScored_complete (best : Option (Scored α)) :
    finishScored true best = best := by
  cases best <;> rfl

theorem finishScored_incomplete (best : Option (Scored α)) :
    finishScored false best = none := by
  cases best <;> rfl

theorem finishScored_result (complete : Bool) (best : Option (Scored α))
    (winner : Scored α) (accepted : finishScored complete best = some winner) :
    complete = true ∧ best = some winner := by
  cases complete with
  | false => rw [finishScored_incomplete] at accepted; cases accepted
  | true =>
    rw [finishScored_complete] at accepted
    exact ⟨rfl, accepted⟩

/-- Scanning valid scores applies exactly the finite choice kernel at each step. -/
def scanPolicy (best : Option (Scored α)) : List (Scored α) → Option (Scored α)
  | [] => best
  | candidate :: rest => scanPolicy (chooseScored best candidate) rest

theorem scanPolicy_incumbent (best : Scored α) (xs : List (Scored α)) :
    scanPolicy (some best) xs = some (scan best xs) := by
  induction xs generalizing best with
  | nil => rfl
  | cons candidate rest ih =>
    change scanPolicy (chooseScored (some best) candidate) rest =
      some (scan (prefer best candidate) rest)
    rw [chooseScored_incumbent, ih]

theorem scanPolicy_firstMinimum (xs : List (Scored α)) :
    scanPolicy none xs = firstMinimum xs := by
  cases xs with
  | nil => rfl
  | cons candidate rest =>
    change scanPolicy (chooseScored none candidate) rest = firstMinimum (candidate :: rest)
    rw [chooseScored_empty, scanPolicy_incumbent, firstMinimum_cons]

/-- A failed evaluation suppresses the whole result; every supplied cost is read. -/
def completePolicy (cost : α → Option Nat) (xs : List α) : Option (Scored α) :=
  match evaluate cost xs with
  | none => finishScored false none
  | some scored => finishScored true (scanPolicy none scored)

theorem completePolicy_eq_argmin (cost : α → Option Nat) (xs : List α) :
    completePolicy cost xs = completeArgmin cost xs := by
  unfold completePolicy completeArgmin
  cases h : evaluate cost xs with
  | none => rfl
  | some scored =>
    change finishScored true (scanPolicy none scored) = firstMinimum scored
    rw [finishScored_complete, scanPolicy_firstMinimum]

/-- Positional decoding only: missing or out-of-range cost entries have no cost. -/
def costAt : List (Option Nat) → Nat → Option Nat
  | [] => fun _ => none
  | cost :: rest => fun index =>
    match index with
    | 0 => cost
    | Nat.succ earlier => costAt rest earlier

/-- The claimed winner is an index in the original, ordered cost vector. -/
def checkCertificate (costs : List (Option Nat)) (winnerIndex : Nat) : Bool :=
  match completePolicy (costAt costs) (List.range costs.length) with
  | none => false
  | some winner => decide (winner.value = winnerIndex)

/-- Acceptance supplies the model result; it is not assumed as a premise. -/
theorem certificate_result (costs : List (Option Nat)) (winnerIndex : Nat)
    (accepted : checkCertificate costs winnerIndex = true) :
    ∃ winnerCost, completeArgmin (costAt costs) (List.range costs.length) =
      some (Scored.mk winnerIndex winnerCost) := by
  unfold checkCertificate at accepted
  rw [completePolicy_eq_argmin] at accepted
  cases h : completeArgmin (costAt costs) (List.range costs.length) with
  | none => rw [h] at accepted; cases accepted
  | some winner =>
    rw [h] at accepted
    have equal : winner.value = winnerIndex := of_decide_eq_true accepted
    cases winner with
    | mk value cost =>
      change value = winnerIndex at equal
      subst value
      exact ⟨cost, rfl⟩

/-- The accepted index is the first global minimum, with complete cost evidence. -/
theorem certificate_first_minimum (costs : List (Option Nat)) (winnerIndex : Nat)
    (accepted : checkCertificate costs winnerIndex = true) :
    ∃ winnerCost scored,
      evaluate (costAt costs) (List.range costs.length) = some scored ∧
      scored.map Scored.value = List.range costs.length ∧
      scored.length = (List.range costs.length).length ∧
      (∀ item ∈ scored, costAt costs item.value = some item.cost) ∧
      IsFirstMinimum scored (Scored.mk winnerIndex winnerCost) ∧
      winnerIndex ∈ List.range costs.length ∧
      costAt costs winnerIndex = some winnerCost ∧
      (∀ item ∈ scored, winnerCost ≤ item.cost) := by
  obtain ⟨winnerCost, result⟩ := certificate_result costs winnerIndex accepted
  exact ⟨winnerCost,
    argmin_complete_first_minimum (costAt costs) (List.range costs.length)
      (Scored.mk winnerIndex winnerCost) result⟩

theorem certificate_complete (costs : List (Option Nat)) (winner : Scored Nat)
    (result : completeArgmin (costAt costs) (List.range costs.length) = some winner) :
    checkCertificate costs winner.value = true := by
  unfold checkCertificate
  rw [completePolicy_eq_argmin, result]
  exact decide_eq_true rfl

theorem certificate_rejects_missing (costs : List (Option Nat)) (index winnerIndex : Nat)
    (member : index ∈ List.range costs.length) (missing : costAt costs index = none) :
    checkCertificate costs winnerIndex = false := by
  unfold checkCertificate
  rw [completePolicy_eq_argmin,
    failed_candidate_prevents_result (costAt costs) (List.range costs.length)
      index member missing]

theorem certificate_rejects_empty (winnerIndex : Nat) :
    checkCertificate [] winnerIndex = false := rfl

end AlloyStudio.PoolBridge
