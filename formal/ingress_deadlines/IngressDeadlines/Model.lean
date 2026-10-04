/- Independent deadline-control evaluator. No semantic validity flag or prior
check is enforced by delivery/reset. A missing source guard remains executable
and can emit a late cut. Work observations represent nondeterministic completion
samples, not an extra production clock call. All scalars are order-normalized
Int values under the separately declared finite-clock order-embedding TCB. -/
namespace AlloyStudio.IngressDeadlines.Model

inductive Action where
  | guard
  | work
  | reset
  | deliver
  deriving DecidableEq

structure Cut where
  clock : Int
  deadline : Int
  deriving DecidableEq

structure State where
  current : Int
  deadline : Int
  samples : List Int
  deliveries : List Cut
  resets : List Cut
  deriving DecidableEq

/-- Work and guard consume separate explicit observations. Deliver never checks
clock validity. Reset records the old-phase cut without checking it and installs
a new deadline from the same current sample returned by the source guard.
The duration parameter denotes the realized normalized deadline difference;
it does not assert exact real arithmetic for Python's rounded floating addition. Thus unsafe programs are not repaired by
this evaluator. Lists retain the most recent event first. -/
def step (check : Int → Int → Bool) (duration : Int) (action : Action)
    (state : State) : Option State :=
  match action with
  | .guard =>
      match state.samples with
      | [] => none
      | now :: rest =>
          if check now state.deadline then
            some { state with current := now, samples := rest }
          else none
  | .work =>
      match state.samples with
      | [] => none
      | now :: rest => some { state with current := now, samples := rest }
  | .reset => some { state with
      deadline := state.current + duration
      resets := ⟨state.current, state.deadline⟩ :: state.resets }
  | .deliver => some { state with
      deliveries := ⟨state.current, state.deadline⟩ :: state.deliveries }

def run (check : Int → Int → Bool) (duration : Int) :
    List Action → State → Option State
  | [], state => some state
  | action :: rest, state =>
      match step check duration action state with
      | none => none
      | some next => run check duration rest next

/-- Pure syntax policy, separate from execution. A work/reset transition removes
the domination of a prior guard; each delivery or reset needs a later guard.
Guard checks are not inserted by this policy or by the evaluator. -/
def wellGuarded : List Action → Bool → Bool
  | [], _ => true
  | .guard :: rest, _ => wellGuarded rest true
  | .work :: rest, _ => wellGuarded rest false
  | .reset :: rest, fresh => if fresh then wellGuarded rest false else false
  | .deliver :: rest, fresh => if fresh then wellGuarded rest true else false

def initial (deadline current : Int) (samples : List Int) : State :=
  ⟨current, deadline, samples, [], []⟩

/-- Strict complement of the expired integer comparison. This definition is
explicit; no library equivalence with Int's `<` notation is assumed. -/
def Before (now deadline : Int) : Prop := ¬deadline ≤ now

def CutsSafe (cuts : List Cut) : Prop :=
  ∀ cut, cut ∈ cuts → Before cut.clock cut.deadline

/-- Freshness belongs to the proof of the program structure, never to the
runtime state or an assumed source field. -/
def StateSafe (fresh : Bool) (state : State) : Prop :=
  (fresh = true → Before state.current state.deadline) ∧
  CutsSafe state.deliveries ∧ CutsSafe state.resets

end AlloyStudio.IngressDeadlines.Model
