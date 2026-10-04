/- Count/identity abstraction of exact Python set operations. There are no
invariant checks in the evaluator: malformed guards or deltas remain executable. -/
namespace AlloyStudio.IngressAdmission

def Owns (owner : Nat) : List Nat → Prop
  | [] => False
  | head :: tail => owner = head ∨ Owns owner tail

instance ownsDecidable (owner : Nat) : (owners : List Nat) → Decidable (Owns owner owners)
  | [] => isFalse (fun h => h)
  | head :: tail => @instDecidableOr (owner = head) (Owns owner tail)
      (inferInstance) (ownsDecidable owner tail)

def Unique : List Nat → Prop
  | [] => True
  | head :: tail => (¬ Owns head tail) ∧ Unique tail

def erase (owner : Nat) : List Nat → List Nat
  | [] => []
  | head :: tail => if owner = head then tail else head :: erase owner tail

structure State where
  active : Nat
  owners : List Nat
  deriving DecidableEq

inductive Outcome where
  | duplicate | capacity | rate | accepted
  deriving DecidableEq

structure Result where
  outcome : Outcome
  state : State
  deriving DecidableEq

inductive Comparison where
  | ge | gt | le | lt | eq | ne
  deriving DecidableEq

def compare (operator : Comparison) (a b : Nat) : Bool :=
  match operator with
  | .ge => decide (b ≤ a)
  | .gt => decide (b < a)
  | .le => decide (a ≤ b)
  | .lt => decide (a < b)
  | .eq => decide (a = b)
  | .ne => decide (a ≠ b)

/-- Ownership-erased credit work may refuse. It has no state-writing ability in
this abstraction; the source adapter checks the erased block's complete syntax,
write roots and invoked primitives separately. -/
def reserve (operator : Comparison) (increment : Nat) (limit owner : Nat)
    (creditAllowed : Bool) (state : State) : Result :=
  if Owns owner state.owners then ⟨.duplicate, state⟩
  else if compare operator state.active limit then ⟨.capacity, state⟩
  else if creditAllowed then
    ⟨.accepted, ⟨state.active + increment, owner :: state.owners⟩⟩
  else ⟨.rate, state⟩

def release (decrement : Nat) (owner : Nat) (state : State) : State :=
  if Owns owner state.owners then
    ⟨state.active - decrement, erase owner state.owners⟩
  else state

def initial : State := ⟨0, []⟩

def Valid (limit : Nat) (state : State) : Prop :=
  state.active = state.owners.length ∧ Unique state.owners ∧ state.active ≤ limit

/-- Thread lifecycle phases are distinct from admission ownership. A registry
entry persists for new/running/dead threads; only dead can be reaped. -/
inductive Phase where
  | reserved | allocated | attempted | identified | running | dead
  deriving DecidableEq

/-- Ident publication precedes the native bootstrap's started event. Python's
is_alive therefore returns false for identified-but-not-started native threads. -/
def publishedIdentity : Phase → Bool
  | .identified | .running | .dead => true
  | .reserved | .allocated | .attempted => false

def reportedAlive : Phase → Bool
  | .running => true
  | .reserved | .allocated | .attempted | .identified | .dead => false

inductive JoinObservation where
  | refused | returned
  deriving DecidableEq

/-- Successful zero-time join establishes that bootstrap passed its started
event, not that the thread has terminated. RuntimeError is retained capacity. -/
def zeroTimeJoin : Phase → JoinObservation
  | .running | .dead => .returned
  | .reserved | .allocated | .attempted | .identified => .refused

inductive ReapCheck where
  | identityOnly | joined
  deriving DecidableEq

/-- The deliberately permissive identity-only check remains executable, so the
independent specification can construct its pre-start reclamation breach. -/
def reclaimable (check : ReapCheck) (phase : Phase) : Bool :=
  match check with
  | .identityOnly => publishedIdentity phase && !reportedAlive phase
  | .joined => decide (zeroTimeJoin phase = .returned) && !reportedAlive phase

inductive ObservationState where
  | ready | inProgress | uncertain
  deriving DecidableEq

structure ThreadEntry where
  owner : Nat
  phase : Phase
  observation : ObservationState
  metadataReliable : Bool
  deriving DecidableEq

def freshEntry (owner : Nat) : ThreadEntry := ⟨owner, .reserved, .ready, true⟩

def marked (entry : ThreadEntry) : Bool := entry.observation != .ready

def uncertaintyCount : List ThreadEntry → Nat
  | [] => 0
  | entry :: rest => (if marked entry then 1 else 0) + uncertaintyCount rest

/-- Ghost reliability is computed by the same event history, never supplied as
a premise to reclaim an owner. An exceptional observation may poison metadata. -/
def ReliableEntry (entry : ThreadEntry) : Prop :=
  entry.observation ≠ .uncertain → entry.metadataReliable = true

def ReliableEntries : List ThreadEntry → Prop
  | [] => True
  | entry :: rest => ReliableEntry entry ∧ ReliableEntries rest

def beginEntry (current : Bool) (entry : ThreadEntry) : ThreadEntry :=
  if current then entry
  else if entry.observation = .ready then { entry with observation := .inProgress }
  else entry

def failEntry (sticky : Bool) (entry : ThreadEntry) : ThreadEntry :=
  if entry.observation = .inProgress then
    { entry with observation := if sticky then .uncertain else .ready, metadataReliable := false }
  else entry

def completeLiveEntry (entry : ThreadEntry) : ThreadEntry :=
  { entry with observation := .ready }

inductive ProbeResult where
  | failed | live | terminated
  deriving DecidableEq

/-- Poisoned metadata deliberately returns a false termination observation.
The evaluator does not repair it by consulting the true native phase. -/
def probeResult (entry : ThreadEntry) : ProbeResult :=
  if entry.metadataReliable then
    if zeroTimeJoin entry.phase = .refused then .failed
    else if reportedAlive entry.phase then .live else .terminated
  else .terminated

def registered : List ThreadEntry → List Nat
  | [] => []
  | head :: tail => head.owner :: registered tail

def liveCount : List ThreadEntry → Nat
  | [] => 0
  | head :: tail => (if head.phase = .dead then 0 else 1) + liveCount tail

/-- Each serving listener serializes its own accept/callback boundary. Pending
is a single socket, not a queue; capacity refusals do not allocate handlers. -/
structure ListenerState where
  pending : Bool
  handlers : State

def pendingCount (listener : ListenerState) : Nat := if listener.pending then 1 else 0

structure Topology where
  pub : State
  control : Option State

def totalActive (topology : Topology) : Nat :=
  topology.pub.active + (match topology.control with | none => 0 | some s => s.active)

def changePublic (topology : Topology) (next : State) : Topology :=
  { topology with pub := next }

def changeControl (topology : Topology) (next : State) : Topology :=
  { topology with control := some next }

inductive LifeAction where
  | reserve | allocate | register | attempt | identify | start | terminate
  | markObservation | clearObservation | joinReturned | joinRefused
  | unregister | release | refuse | close
  deriving DecidableEq

structure Life where
  reserved : Bool
  allocated : Bool
  registered : Bool
  attempted : Bool
  identified : Bool
  started : Bool
  alive : Bool
  observationMarked : Bool
  deriving DecidableEq

def lifeInitial : Life := ⟨false, false, false, false, false, false, false, false⟩

/-- Deliberately unconditional effects. In particular release does not test
alive: a bad extracted cleanup path can release a live thread in this evaluator. -/
def lifeStep (action : LifeAction) (state : Life) : Life :=
  match action with
  | .reserve => { state with reserved := true }
  | .allocate => { state with allocated := true }
  | .register => { state with registered := true }
  | .attempt => { state with attempted := true }
  | .identify => { state with identified := true, alive := true }
  | .start => { state with started := true, alive := true }
  | .terminate => { state with alive := false }
  | .markObservation => { state with observationMarked := true }
  | .clearObservation => { state with observationMarked := false }
  | .unregister => { state with registered := false }
  | .release => { state with reserved := false }
  | .joinReturned | .joinRefused | .refuse | .close => state

def lifeRun : List LifeAction → Life → Life
  | [], state => state
  | action :: rest, state => lifeRun rest (lifeStep action state)

def LifeSafe (state : Life) : Prop :=
  state.alive = true → state.reserved = true ∧ state.registered = true

def PrefixSafe : List LifeAction → Life → Prop
  | [], state => LifeSafe state
  | action :: rest, state => LifeSafe state ∧ PrefixSafe rest (lifeStep action state)

instance lifeSafeDecidable (state : Life) : Decidable (LifeSafe state) := by
  unfold LifeSafe
  infer_instance

instance prefixSafeDecidable : (actions : List LifeAction) → (state : Life) →
    Decidable (PrefixSafe actions state)
  | [], state => lifeSafeDecidable state
  | action :: rest, state => @instDecidableAnd (LifeSafe state)
      (PrefixSafe rest (lifeStep action state)) (lifeSafeDecidable state)
      (prefixSafeDecidable rest (lifeStep action state))

def findPhase (owner : Nat) : List ThreadEntry → Option Phase
  | [] => none
  | head :: tail => if owner = head.owner then some head.phase else findPhase owner tail

def findEntry (owner : Nat) : List ThreadEntry → Option ThreadEntry
  | [] => none
  | head :: tail => if owner = head.owner then some head else findEntry owner tail

def updateEntry (owner : Nat) (update : ThreadEntry → ThreadEntry) :
    List ThreadEntry → List ThreadEntry
  | [] => []
  | head :: tail => if owner = head.owner then update head :: tail
                   else head :: updateEntry owner update tail

def setPhase (owner : Nat) (phase : Phase) : List ThreadEntry → List ThreadEntry
  | [] => []
  | head :: tail => if owner = head.owner then { head with phase := phase } :: tail
                   else head :: setPhase owner phase tail

def removeThread (owner : Nat) : List ThreadEntry → List ThreadEntry
  | [] => []
  | head :: tail => if owner = head.owner then tail else head :: removeThread owner tail

structure Resources where
  admission : State
  threads : List ThreadEntry

inductive ResourceEvent where
  | reserve (owner : Nat) (credit : Bool)
  | phase (owner : Nat) (phase : Phase)
  | reapDead (owner : Nat)
  | failedBeforeAttempt (owner : Nat)
  | beginObservation (owner : Nat) (current : Bool)
  | observationFailure (owner : Nat)
  | completeObservation (owner : Nat)

def resourcesInitial : Resources := ⟨initial, []⟩

def ResourcesValid (limit : Nat) (state : Resources) : Prop :=
  Valid limit state.admission ∧ state.admission.owners = registered state.threads

/-- Exact natural interpretation of Python min for nonnegative credits. -/
def clip (capacity balance : Nat) : Nat := if capacity ≤ balance then capacity else balance

end AlloyStudio.IngressAdmission
