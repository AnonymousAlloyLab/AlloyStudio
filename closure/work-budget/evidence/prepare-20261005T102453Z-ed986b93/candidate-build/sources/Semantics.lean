import Std

/-!
LP05-04 restricted charged-work semantics. Integers describe Java signed values;
wrap functions model Java overflow exactly. A State is one thread's active budget.
The clock observation is an explicit input, not an axiom about elapsed wall time.
-/
namespace AlloyStudio.WorkBudget

def longMax : Int := 9223372036854775807
def longMin : Int := -9223372036854775808
def javaLong (n : Int) : Int := (n + 9223372036854775808) % 18446744073709551616 - 9223372036854775808
def javaInt (n : Int) : Int := (n + 2147483648) % 4294967296 - 2147483648

theorem javaLong_exact (n : Int) (lo : longMin ≤ n) (hi : n ≤ longMax) : javaLong n = n := by
  unfold javaLong longMin longMax at *
  have hn : 0 ≤ n + 9223372036854775808 := by omega
  have hm : n + 9223372036854775808 < 18446744073709551616 := by omega
  rw [Int.emod_eq_of_lt hn hm]
  omega

theorem javaInt_exact (n : Int) (lo : -2147483648 ≤ n) (hi : n ≤ 2147483647) : javaInt n = n := by
  unfold javaInt
  have hn : 0 ≤ n + 2147483648 := by omega
  have hm : n + 2147483648 < 4294967296 := by omega
  rw [Int.emod_eq_of_lt hn hm]
  omega

structure State where
  active : Bool
  remaining : Int
  exhausted : Bool
  timed : Bool
  started : Int
  limit : Int
  checks : Int

inductive Result where
  | ok (state : State)
  | invalid (state : State)
  | exhausted (state : State)

def resultState : Result → State
  | .ok s => s
  | .invalid s => s
  | .exhausted s => s

def Valid (s : State) : Prop :=
  0 ≤ s.remaining ∧ s.remaining ≤ longMax ∧ 1 ≤ s.checks ∧ s.checks ≤ 1024

/-- Independent contract: publication rejects an observed expired/invalid clock. -/
def checkpointSpec (s : State) (now : Int) : Result :=
  if s.active = false then .ok s
  else if s.exhausted = true then .exhausted s
  else if s.timed = true ∧ (javaLong (now - s.started) < 0 ∨ s.limit ≤ javaLong (now - s.started))
    then .exhausted { s with exhausted := true }
  else .ok s

/-- Exactly one observation per 1024 admitted charge calls, never a timer thread. -/
def sampleSpec (s : State) (now : Int) : Result :=
  if s.timed = false then .ok s
  else if s.checks = 1 then checkpointSpec { s with checks := 1024 } now
  else .ok { s with checks := s.checks - 1 }

/-- Independent fuel contract: invalid/refused work cannot spend fuel. -/
def chargeSpec (s : State) (units now : Int) : Result :=
  if s.active = false then .ok s
  else if units < 0 then .invalid s
  else if s.exhausted = true ∨ s.remaining < units then .exhausted { s with exhausted := true }
  else match sampleSpec s now with
    | .ok next => .ok { next with remaining := next.remaining - units }
    | .invalid next => .invalid next
    | .exhausted next => .exhausted next
end AlloyStudio.WorkBudget
