import Std

/-!
AP01 proposed contracts, not an extraction or proof of the current Python/Java.
An operation is one charged atomic operation; expanding parser, normalization,
matrix/assignment, replay and serialization work into these operations is an
implementation obligation. The supplied program must contain the complete
traversal and every reference. A single request owns the fuel across all phases.
Restart reservations include starting, live and unreaped processes. Reap is an
external acknowledgement. Time and successful starts are explicit model inputs.
-/
namespace AlloyStudio.PatchContracts.Work

private theorem subtract_restore (a b : Nat) (h : b ≤ a) : a - b + b = a := by
  induction b generalizing a with
  | zero => rfl
  | succ b ih =>
    cases a with
    | zero => exact False.elim (Nat.not_succ_le_zero b h)
    | succ a =>
      rw [Nat.succ_sub_succ]
      exact congrArg Nat.succ (ih a (Nat.le_of_succ_le_succ h))

private theorem append_nil {A : Type} (xs : List A) : xs ++ [] = xs := by
  induction xs with
  | nil => rfl
  | cons a xs ih => exact congrArg (List.cons a) ih

private theorem append_assoc {A : Type} (a b c : List A) : (a ++ b) ++ c = a ++ (b ++ c) := by
  induction a with
  | nil => rfl
  | cons x xs ih => exact congrArg (List.cons x) ih

def fullFold {S A : Type} (step : S → A → S) : List A → S → S
  | [], s => s
  | a :: rest, s => fullFold step rest (step s a)

/-- The successor is eliminated before `step` is evaluated; zero cannot act. -/
def boundedFold {S A : Type} (step : S → A → S) : Nat → List A → S → Option (S × Nat)
  | fuel, [], s => some (s, fuel)
  | 0, _ :: _, _ => none
  | fuel + 1, a :: rest, s => boundedFold step fuel rest (step s a)

theorem zero_cannot_act {S A : Type} (step : S → A → S) (a : A) (rest : List A) (s : S) :
    boundedFold step 0 (a :: rest) s = none := rfl

/-- Count charged transitions on both successful and exhausted evaluation paths. -/
def evaluationSteps {A : Type} : Nat → List A → Nat
  | _, [] => 0
  | 0, _ :: _ => 0
  | fuel + 1, _ :: rest => evaluationSteps fuel rest + 1

theorem all_execution_paths_bounded {A : Type} (fuel : Nat) (work : List A) :
    evaluationSteps fuel work ≤ fuel := by
  induction fuel generalizing work with
  | zero => cases work <;> exact Nat.le_refl 0
  | succ fuel ih =>
    cases work with
    | nil => exact Nat.zero_le _
    | cons _ rest => exact Nat.succ_le_succ (ih rest)

theorem atomic_charge_before_step {S A : Type} (step : S → A → S)
    (fuel : Nat) (a : A) (rest : List A) (s : S) :
    boundedFold step (fuel + 1) (a :: rest) s = boundedFold step fuel rest (step s a) := rfl

theorem complete_exact {S A : Type} (step : S → A → S) (work : List A)
    (fuel : Nat) (s : S) (enough : work.length ≤ fuel) :
    boundedFold step fuel work s = some (fullFold step work s, fuel - work.length) := by
  induction work generalizing fuel s with
  | nil => cases fuel <;> rfl
  | cons a rest ih =>
    cases fuel with
    | zero => cases Nat.not_succ_le_zero _ enough
    | succ fuel =>
      change boundedFold step fuel rest (step s a) =
        some (fullFold step rest (step s a), (fuel + 1) - (rest.length + 1))
      rw [Nat.succ_sub_succ]
      exact ih fuel (step s a) (Nat.le_of_succ_le_succ enough)

theorem exhausted_rejects {S A : Type} (step : S → A → S) (work : List A)
    (fuel : Nat) (s : S) (short : fuel < work.length) :
    boundedFold step fuel work s = none := by
  induction work generalizing fuel s with
  | nil => cases Nat.not_lt_zero _ short
  | cons a rest ih =>
    cases fuel with
    | zero => rfl
    | succ fuel => exact ih fuel (step s a) (Nat.lt_of_succ_lt_succ short)

theorem success_uses_at_most_initial_fuel {S A : Type} (step : S → A → S)
    (work : List A) (fuel : Nat) (s out : S) (remaining : Nat)
    (success : boundedFold step fuel work s = some (out, remaining)) :
    work.length ≤ fuel ∧ out = fullFold step work s ∧ remaining + work.length = fuel := by
  by_cases enough : work.length ≤ fuel
  · rw [complete_exact step work fuel s enough] at success
    have pair := Option.some.inj success
    have stateEq := congrArg Prod.fst pair
    have fuelEq := congrArg Prod.snd pair
    exact ⟨enough, stateEq.symm, by
      dsimp at fuelEq
      rw [← fuelEq]
      exact subtract_restore fuel work.length enough⟩
  · have short : fuel < work.length := Nat.lt_of_not_ge enough
    rw [exhausted_rejects step work fuel s short] at success
    cases success

/-- Batch boundaries never renew fuel. This includes the entire reference pool. -/
def runRequest {S A : Type} (step : S → A → S) (fuel : Nat)
    (phases : List (List A)) (s : S) : Option (S × Nat) :=
  boundedFold step fuel phases.flatten s

theorem aggregate_exhaustion {S A : Type} (step : S → A → S) (phases : List (List A))
    (fuel : Nat) (s : S) (short : fuel < phases.flatten.length) :
    runRequest step fuel phases s = none := exhausted_rejects step _ fuel s short

/-- Logging is an independent witness that a successful fold visits every item. -/
theorem full_traversal {A : Type} (work seen : List A) :
    fullFold (fun log a => log ++ [a]) work seen = seen ++ work := by
  induction work generalizing seen with
  | nil => exact (append_nil seen).symm
  | cons a rest ih =>
    rw [fullFold, ih, append_assoc]
    rfl

theorem accepted_traversal_complete {A : Type} (work : List A) (fuel : Nat)
    (seen : List A) (remaining : Nat)
    (h : boundedFold (fun log a => log ++ [a]) fuel work [] = some (seen, remaining)) :
    seen = work := by
  have exactWork := (success_uses_at_most_initial_fuel _ work fuel [] seen remaining h).2.1
  rw [full_traversal] at exactWork
  exact exactWork

def minimumDistance (distances : List Nat) (initial : Nat) : Nat :=
  fullFold Nat.min distances initial

theorem bounded_minimum_exact (distances : List Nat) (fuel initial out remaining : Nat)
    (h : boundedFold Nat.min fuel distances initial = some (out, remaining)) :
    distances.length ≤ fuel ∧ out = minimumDistance distances initial := by
  have result := success_uses_at_most_initial_fuel Nat.min distances fuel initial out remaining h
  exact ⟨result.1, result.2.1⟩

theorem later_closer_candidate_cannot_be_truncated :
    boundedFold Nat.min 1 [9, 0] 100 = none ∧ minimumDistance [9, 0] 100 = 0 :=
  ⟨rfl, rfl⟩

inductive Response (Payload : Type) where
  | unsupported
  | accepted (payload : Payload)

/-- No partial result/hints are present in the rejection constructor. -/
def publish {S A P : Type} (sizeOK : Bool) (step : S → A → S)
    (fuel : Nat) (work : List A) (s : S) (payload : P) : Response P :=
  if sizeOK then
    match boundedFold step fuel work s with
    | none => .unsupported
    | some _ => .accepted payload
  else .unsupported

theorem oversize_unsupported {S A P : Type} (step : S → A → S)
    (fuel : Nat) (work : List A) (s : S) (payload : P) :
    publish false step fuel work s payload = .unsupported := rfl

theorem exhausted_no_partial_output {S A P : Type} (step : S → A → S)
    (fuel : Nat) (work : List A) (s : S) (payload : P) (short : fuel < work.length) :
    publish true step fuel work s payload = .unsupported := by
  unfold publish
  rw [exhausted_rejects step work fuel s short]
  rfl

theorem accepted_payload_preserved {S A P : Type} (step : S → A → S)
    (fuel : Nat) (work : List A) (s : S) (payload : P) (enough : work.length ≤ fuel) :
    publish true step fuel work s payload = .accepted payload := by
  unfold publish
  rw [complete_exact step work fuel s enough]
  rfl

/-- A constructed branching-cost witness, not a measured Java runtime claim. -/
def repeatedAndCost : Nat → Nat
  | 0 => 1
  | n + 1 => 1 + 2 * repeatedAndCost n

theorem repeated_and_cost_grows (n : Nat) : n < repeatedAndCost n := by
  induction n with
  | zero => exact Nat.zero_lt_succ 0
  | succ n ih =>
    change n + 1 < 1 + 2 * repeatedAndCost n
    rw [Nat.add_comm 1]
    apply Nat.lt_succ_of_le
    exact Nat.le_trans ih (Nat.le_mul_of_pos_left _ (by decide))

structure Limits where
  capacity : Nat
  startsPerEpoch : Nat
  failedStartLimit : Nat
  period : Nat

structure LaneState where
  live : Nat
  starting : Nat
  unreaped : Nat
  tokens : Nat
  attempts : Nat
  failedStarts : Nat
  now : Nat
  renewal : Nat

def reserved (s : LaneState) : Nat := s.live + s.starting + s.unreaped

def initial (c : Limits) : LaneState := ⟨0, 0, 0, c.startsPerEpoch, 0, 0, 0, c.period⟩

inductive Event where
  | start | ready | startupFailure | plannedRetirement | requestTimeout | reaped
  | tick (elapsed : Nat)
  | renew

/-- The failed-start circuit is separate from the budget charged for EVERY start. -/
def laneStep (c : Limits) (s : LaneState) : Event → LaneState
  | .start =>
    if 0 < s.tokens ∧ s.failedStarts < c.failedStartLimit ∧ reserved s < c.capacity then
      { s with starting := s.starting + 1, tokens := s.tokens - 1, attempts := s.attempts + 1 }
    else s
  | .ready => if 0 < s.starting then { s with starting := s.starting - 1, live := s.live + 1 } else s
  | .startupFailure => if 0 < s.starting then
      { s with starting := s.starting - 1, unreaped := s.unreaped + 1, failedStarts := s.failedStarts + 1 } else s
  | .plannedRetirement | .requestTimeout => if 0 < s.live then
      { s with live := s.live - 1, unreaped := s.unreaped + 1 } else s
  | .reaped => if 0 < s.unreaped then { s with unreaped := s.unreaped - 1 } else s
  | .tick elapsed => { s with now := s.now + elapsed }
  | .renew => if s.renewal ≤ s.now then
      { s with tokens := c.startsPerEpoch, attempts := 0, failedStarts := 0, renewal := s.now + c.period } else s

def Invariant (c : Limits) (s : LaneState) : Prop :=
  reserved s ≤ c.capacity ∧ s.tokens + s.attempts = c.startsPerEpoch ∧
    s.renewal ≤ s.now + c.period

private theorem move_unit_left (a b : Nat) (h : 0 < b) : (a + 1) + (b - 1) = a + b := by
  rw [Nat.add_assoc, Nat.add_comm 1, subtract_restore b 1 h]

private theorem move_unit_right (a b : Nat) (h : 0 < a) : (a - 1) + (b + 1) = a + b := by
  rw [Nat.add_succ, ← Nat.succ_add]
  exact congrArg (fun x => x + b) (subtract_restore a 1 h)

private theorem retire_reservation (s : LaneState) (h : 0 < s.live) :
    (s.live - 1) + s.starting + (s.unreaped + 1) = reserved s := by
  rw [Nat.add_assoc]
  change (s.live - 1) + ((s.starting + s.unreaped) + 1) = _
  rw [move_unit_right s.live _ h, ← Nat.add_assoc]
  rfl

theorem step_preserves_invariant (c : Limits) (s : LaneState) (event : Event)
    (h : Invariant c s) : Invariant c (laneStep c s event) := by
  rcases h with ⟨cap, budget, renewal⟩
  cases event with
  | start =>
    dsimp only [laneStep]
    split
    · rename_i allowed
      refine ⟨?_, ?_, renewal⟩
      · change s.live + (s.starting + 1) + s.unreaped ≤ c.capacity
        rw [Nat.add_succ, Nat.succ_add]
        exact allowed.2.2
      · change (s.tokens - 1) + (s.attempts + 1) = c.startsPerEpoch
        rw [move_unit_right _ _ allowed.1]
        exact budget
    · exact ⟨cap, budget, renewal⟩
  | ready =>
    dsimp only [laneStep]
    split
    · rename_i pending
      refine ⟨?_, budget, renewal⟩
      change (s.live + 1) + (s.starting - 1) + s.unreaped ≤ c.capacity
      rw [move_unit_left _ _ pending]
      exact cap
    · exact ⟨cap, budget, renewal⟩
  | startupFailure =>
    dsimp only [laneStep]
    split
    · rename_i pending
      refine ⟨?_, budget, renewal⟩
      change s.live + (s.starting - 1) + (s.unreaped + 1) ≤ c.capacity
      rw [Nat.add_assoc, move_unit_right _ _ pending, ← Nat.add_assoc]
      exact cap
    · exact ⟨cap, budget, renewal⟩
  | plannedRetirement | requestTimeout =>
    dsimp only [laneStep]
    split
    · rename_i live
      refine ⟨?_, budget, renewal⟩
      change (s.live - 1) + s.starting + (s.unreaped + 1) ≤ c.capacity
      rw [retire_reservation s live]
      exact cap
    · exact ⟨cap, budget, renewal⟩
  | reaped =>
    dsimp only [laneStep]
    split
    · exact ⟨Nat.le_trans (Nat.add_le_add_left (Nat.sub_le s.unreaped 1) _) cap, budget, renewal⟩
    · exact ⟨cap, budget, renewal⟩
  | tick elapsed =>
    exact ⟨cap, budget, Nat.le_trans renewal
      (Nat.add_le_add_right (Nat.le_add_right s.now elapsed) c.period)⟩
  | renew =>
    dsimp only [laneStep]
    split
    · exact ⟨cap, rfl, Nat.le_refl _⟩
    · exact ⟨cap, budget, renewal⟩

inductive Reachable (c : Limits) : LaneState → Prop where
  | init : Reachable c (initial c)
  | next {s : LaneState} : Reachable c s → (event : Event) → Reachable c (laneStep c s event)

theorem reachable_capacity_and_spawn_bound (c : Limits) (s : LaneState) (h : Reachable c s) :
    Invariant c s := by
  induction h with
  | init => exact ⟨Nat.zero_le _, rfl, by change c.period ≤ 0 + c.period; rw [Nat.zero_add]; exact Nat.le_refl _⟩
  | next reached event ih => exact step_preserves_invariant c _ event ih

theorem every_start_charged (c : Limits) (s : LaneState)
    (allowed : 0 < s.tokens ∧ s.failedStarts < c.failedStartLimit ∧ reserved s < c.capacity) :
    (laneStep c s .start).attempts = s.attempts + 1 ∧
    (laneStep c s .start).tokens + 1 = s.tokens := by
  dsimp only [laneStep]
  rw [ite_eq_left allowed]
  exact ⟨rfl, subtract_restore s.tokens 1 allowed.1⟩

theorem reachable_attempts_bounded (c : Limits) (s : LaneState) (h : Reachable c s) :
    s.attempts ≤ c.startsPerEpoch := by
  have bound := (reachable_capacity_and_spawn_bound c s h).2.1
  rw [← bound]
  exact Nat.le_add_left s.attempts s.tokens

theorem no_tokens_no_spawn (c : Limits) (s : LaneState) (empty : s.tokens = 0) :
    laneStep c s .start = s := by
  dsimp only [laneStep]
  apply ite_eq_right
  intro h
  rw [empty] at h
  exact Nat.not_lt_zero 0 h.1

theorem timeout_does_not_poison_startup_circuit (c : Limits) (s : LaneState) :
    (laneStep c s .requestTimeout).failedStarts = s.failedStarts := by
  dsimp only [laneStep]
  split <;> rfl

theorem planned_retirement_does_not_poison_startup_circuit (c : Limits) (s : LaneState) :
    (laneStep c s .plannedRetirement).failedStarts = s.failedStarts := by
  dsimp only [laneStep]
  split <;> rfl

theorem only_startup_failure_increases_circuit (c : Limits) (s : LaneState) (e : Event)
    (other : e ≠ .startupFailure) : (laneStep c s e).failedStarts ≤ s.failedStarts := by
  cases e <;> dsimp only [laneStep]
  all_goals first | exact False.elim (other rfl) | skip
  all_goals first | exact Nat.le_refl _ | (split <;> first | exact Nat.le_refl _ | exact Nat.zero_le _)

theorem failed_start_counts_once (c : Limits) (s : LaneState) (pending : 0 < s.starting) :
    (laneStep c s .startupFailure).failedStarts = s.failedStarts + 1 := by
  dsimp only [laneStep]
  rw [ite_eq_left pending]

theorem retirement_retains_capacity_reservation (c : Limits) (s : LaneState)
    (live : 0 < s.live) : reserved (laneStep c s .requestTimeout) = reserved s := by
  dsimp only [laneStep]
  rw [ite_eq_left live]
  exact retire_reservation s live

/-- Recovery is an explicit finite transition construction, requiring free capacity.
    Neither a reap acknowledgement nor scheduling/OS progress is assumed to occur. -/
theorem bounded_modeled_recovery (c : Limits) (s : LaneState)
    (h : Invariant c s) (space : reserved s < c.capacity)
    (tokens : 0 < c.startsPerEpoch) (circuit : 0 < c.failedStartLimit) :
    let waited := laneStep c s (.tick c.period)
    let renewed := laneStep c waited .renew
    (laneStep c renewed .start).starting = s.starting + 1 := by
  dsimp
  have due : s.renewal ≤ s.now + c.period := h.2.2
  dsimp only [laneStep]
  rw [ite_eq_left due]
  split
  · rfl
  · rename_i denied
    exact False.elim (denied ⟨tokens, circuit, space⟩)

theorem renewal_requires_modeled_time (c : Limits) (s : LaneState)
    (future : s.now < s.renewal) : laneStep c s .renew = s := by
  dsimp only [laneStep]
  exact ite_eq_right (Nat.not_le_of_gt future)

/-- With a positive period, renewal cannot issue a second grant without time advancing. -/
theorem renewal_cannot_repeat (c : Limits) (s : LaneState)
    (positive : 0 < c.period) (due : s.renewal ≤ s.now) :
    laneStep c (laneStep c s .renew) .renew = laneStep c s .renew := by
  have grant : laneStep c s .renew =
      { s with tokens := c.startsPerEpoch, attempts := 0, failedStarts := 0, renewal := s.now + c.period } := by
    dsimp only [laneStep]
    exact ite_eq_left due
  rw [grant]
  exact renewal_requires_modeled_time c _ (Nat.lt_add_of_pos_right positive)

inductive Lane where | feedback | behavior

structure Pool where
  feedback : LaneState
  behavior : LaneState

structure PoolLimits where
  feedback : Limits
  behavior : Limits

def poolStep (c : PoolLimits) (p : Pool) (lane : Lane) (event : Event) : Pool :=
  match lane with
  | .feedback => { p with feedback := laneStep c.feedback p.feedback event }
  | .behavior => { p with behavior := laneStep c.behavior p.behavior event }

theorem feedback_cannot_consume_behavior_budget (c : PoolLimits) (p : Pool) (e : Event) :
    (poolStep c p .feedback e).behavior = p.behavior := rfl

theorem behavior_cannot_consume_feedback_budget (c : PoolLimits) (p : Pool) (e : Event) :
    (poolStep c p .behavior e).feedback = p.feedback := rfl

inductive PoolReachable (c : PoolLimits) : Pool → Prop where
  | init : PoolReachable c ⟨initial c.feedback, initial c.behavior⟩
  | next {p} : PoolReachable c p → (lane : Lane) → (event : Event) →
      PoolReachable c (poolStep c p lane event)

theorem both_lanes_remain_bounded (c : PoolLimits) (p : Pool) (h : PoolReachable c p) :
    Invariant c.feedback p.feedback ∧ Invariant c.behavior p.behavior := by
  induction h with
  | init => exact ⟨reachable_capacity_and_spawn_bound _ _ .init,
      reachable_capacity_and_spawn_bound _ _ .init⟩
  | next reached lane event ih =>
    cases lane with
    | feedback => exact ⟨step_preserves_invariant _ _ event ih.1, ih.2⟩
    | behavior => exact ⟨ih.1, step_preserves_invariant _ _ event ih.2⟩

/-- Source-shaped shared gate in ONE modeled window with no expiration events. -/
def oldSharedStart (used : Nat) (_lane : Lane) : Option Nat :=
  if used < 12 then some (used + 1) else none

def oldFeedbackStarts : Nat → Option Nat
  | 0 => some 0
  | n + 1 => match oldFeedbackStarts n with
    | none => none
    | some used => oldSharedStart used .feedback

theorem shared_twelve_start_interference :
    oldFeedbackStarts 12 = some 12 ∧ oldSharedStart 12 .behavior = none := by decide

/-- Removing all start budgets but retaining capacity: the problematic proposal. -/
def unlimitedStep (c : Limits) (s : LaneState) : Event → LaneState
  | .start => if reserved s < c.capacity then
      { s with starting := s.starting + 1, attempts := s.attempts + 1 } else s
  | event => laneStep c s event

def churnLimits : Limits := ⟨1, 12, 3, 60⟩
def churnState (attempts : Nat) : LaneState := ⟨0, 0, 0, 0, attempts, 0, 0, 60⟩

def unlimitedCycle (s : LaneState) : LaneState :=
  unlimitedStep churnLimits (unlimitedStep churnLimits
    (unlimitedStep churnLimits (unlimitedStep churnLimits s .start) .ready)
    .requestTimeout) .reaped

theorem unlimited_cycle_exact (n : Nat) : unlimitedCycle (churnState n) = churnState (n + 1) := rfl

inductive UnlimitedReachable : LaneState → Prop where
  | init : UnlimitedReachable (churnState 0)
  | next {s} : UnlimitedReachable s → (e : Event) → UnlimitedReachable (unlimitedStep churnLimits s e)

theorem unlimited_churn_reachable (n : Nat) : UnlimitedReachable (churnState n) := by
  induction n with
  | zero => exact .init
  | succ n ih =>
    rw [← unlimited_cycle_exact n]
    exact .next (.next (.next (.next ih .start) .ready) .requestTimeout) .reaped

/-- Arbitrarily many spawn attempts with zero startup failures and empty occupancy
    between cycles. This is a constructed trace, not an OS scheduling assertion. -/
theorem unlimited_retry_churn (n : Nat) :
    UnlimitedReachable (churnState (n + 1)) ∧
    (churnState (n + 1)).attempts > n ∧ (churnState (n + 1)).failedStarts = 0 :=
  ⟨unlimited_churn_reachable _, Nat.lt_succ_self n, rfl⟩

theorem unlimited_cycle_capacity (n : Nat) :
    reserved (churnState n) ≤ 1 ∧
    reserved (unlimitedStep churnLimits (churnState n) .start) ≤ 1 ∧
    reserved (unlimitedStep churnLimits (unlimitedStep churnLimits (churnState n) .start) .ready) ≤ 1 ∧
    reserved (unlimitedStep churnLimits
      (unlimitedStep churnLimits (unlimitedStep churnLimits (churnState n) .start) .ready) .requestTimeout) ≤ 1 ∧
    reserved (unlimitedCycle (churnState n)) = 0 :=
  ⟨Nat.zero_le 1, Nat.le_refl 1, Nat.le_refl 1, Nat.le_refl 1, rfl⟩

end AlloyStudio.PatchContracts.Work
