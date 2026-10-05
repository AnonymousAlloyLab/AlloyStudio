import Std

/-!
AP01 proposed diagnostic contract, not current /api/health semantics. A production
bridge must take an atomic snapshot (or explicit separately dated samples),
validate configured bounds and restrict the route to the control listener.
Counters are bounded numeric atoms here, never arbitrary payloads or strings.
Readiness is a sampled classification, not a proof of future request completion.
-/
namespace AlloyStudio.PatchContracts.Observability

abbrev Counter := Fin 1048576
structure Lane where
  ready : Counter
  busy : Counter
  starting : Counter
  unreaped : Counter
  launchCredits : Fin 13
  startupCircuitOpen : Bool
  retryAfterSeconds : Fin 61
  deriving DecidableEq

structure Snapshot where
  generation : Counter
  stopping : Bool
  feedback : Lane
  behavior : Lane
  retainedHandlers : Counter
  activeHandlers : Counter
  deriving DecidableEq

inductive LaneStatus where
  | ready
  | busy
  | starting
  | unavailable
  deriving DecidableEq

def laneStatus (lane : Lane) : LaneStatus :=
  if 0 < lane.ready.val then .ready
  else if 0 < lane.busy.val then .busy
  else if 0 < lane.starting.val then .starting
  else if 0 < lane.launchCredits.val then
    if lane.startupCircuitOpen then .unavailable else .starting
  else .unavailable

theorem ready_requires_idle_worker (lane : Lane)
    (h : laneStatus lane = .ready) : 0 < lane.ready.val := by
  unfold laneStatus at h
  split at h
  · assumption
  · split at h
    · cases h
    · split at h
      · cases h
      · split at h
        · split at h <;> cases h
        · cases h

theorem idle_worker_classifies_ready (lane : Lane) (h : 0 < lane.ready.val) :
    laneStatus lane = .ready := by
  unfold laneStatus
  rw [ite_eq_left h]

theorem no_capacity_unavailable (lane : Lane)
    (idle : lane.ready.val = 0) (busy : lane.busy.val = 0)
    (starting : lane.starting.val = 0) (credits : lane.launchCredits.val = 0) :
    laneStatus lane = .unavailable := by
  unfold laneStatus
  rw [idle, busy, starting, credits]
  rfl

def coldLane : Lane := ⟨⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨1, by decide⟩, false, ⟨0, by decide⟩⟩

theorem zero_workers_need_not_mean_unavailable :
    coldLane.ready.val = 0 ∧ coldLane.busy.val = 0 ∧
    coldLane.starting.val = 0 ∧ laneStatus coldLane = .starting := by decide

def degraded (snapshot : Snapshot) : Bool :=
  decide (0 < snapshot.retainedHandlers.val) ||
  decide (0 < snapshot.feedback.unreaped.val) ||
  decide (0 < snapshot.behavior.unreaped.val)

inductive ServiceStatus where
  | stopping
  | degraded
  | available
  | busy
  | starting
  | unavailable
  deriving DecidableEq

/-- Service summary describes the feedback lane; DTO also reports behavior. -/
def serviceStatus (snapshot : Snapshot) : ServiceStatus :=
  if snapshot.stopping then .stopping
  else if degraded snapshot then .degraded
  else match laneStatus snapshot.feedback with
    | .ready => .available
    | .busy => .busy
    | .starting => .starting
    | .unavailable => .unavailable

theorem stopping_dominates (snapshot : Snapshot) (h : snapshot.stopping = true) :
    serviceStatus snapshot = .stopping := by
  unfold serviceStatus
  rw [h]
  rfl

theorem retained_ownership_visible (snapshot : Snapshot)
    (live : snapshot.stopping = false) (retained : 0 < snapshot.retainedHandlers.val) :
    serviceStatus snapshot = .degraded := by
  unfold serviceStatus degraded
  rw [live, decide_eq_true retained]
  rfl

structure Diagnostic where
  snapshot : Snapshot
  feedbackStatus : LaneStatus
  behaviorStatus : LaneStatus
  serviceStatus : ServiceStatus
  deriving DecidableEq

def project (snapshot : Snapshot) : Diagnostic :=
  ⟨snapshot, laneStatus snapshot.feedback, laneStatus snapshot.behavior,
    serviceStatus snapshot⟩

/-- Private is arbitrary: learner/oracle/provider/credential data have no DTO slot. -/
structure State (Private : Type) where
  snapshot : Snapshot
  privateData : Private

inductive Listener where
  | publicListener
  | controlListener
  deriving DecidableEq

/-- Route admission is explicit; public liveness is a separate, unchanged API. -/
def diagnose {Private : Type} (listener : Listener) (state : State Private) :
    State Private × Option Diagnostic :=
  (state, match listener with
    | .publicListener => none
    | .controlListener => some (project state.snapshot))

theorem public_diagnostics_denied {Private : Type} (state : State Private) :
    (diagnose .publicListener state).2 = none := rfl

theorem diagnostics_do_not_mutate {Private : Type} (listener : Listener)
    (state : State Private) : (diagnose listener state).1 = state := rfl

theorem diagnostics_do_not_release_retained {Private : Type} (listener : Listener)
    (state : State Private) :
    (diagnose listener state).1.snapshot.retainedHandlers = state.snapshot.retainedHandlers := rfl

theorem diagnostics_no_private_content {PrivateA PrivateB : Type}
    (snapshot : Snapshot) (a : PrivateA) (b : PrivateB) (listener : Listener) :
    (diagnose listener ⟨snapshot, a⟩).2 =
    (diagnose listener ⟨snapshot, b⟩).2 := rfl

theorem control_snapshot_exact {Private : Type} (state : State Private) :
    (diagnose .controlListener state).2 = some (project state.snapshot) := rfl

theorem generation_preserved (snapshot : Snapshot) :
    (project snapshot).snapshot.generation = snapshot.generation := rfl

theorem diagnostic_counter_bounded (snapshot : Snapshot) :
    (project snapshot).snapshot.retainedHandlers.val < 1048576 :=
  snapshot.retainedHandlers.isLt

/-- A constant liveness response cannot distinguish these two capacities. -/
def livenessResponse (_snapshot : Snapshot) : Bool := true

def idleLane : Lane := ⟨⟨1, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, false, ⟨0, by decide⟩⟩
def deadLane : Lane := ⟨⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, ⟨0, by decide⟩, false, ⟨0, by decide⟩⟩
def liveSnapshot : Snapshot := ⟨⟨0, by decide⟩, false, idleLane, idleLane, ⟨0, by decide⟩, ⟨0, by decide⟩⟩
def deadSnapshot : Snapshot := ⟨⟨0, by decide⟩, false, deadLane, deadLane, ⟨0, by decide⟩, ⟨0, by decide⟩⟩

theorem constant_liveness_counterexample :
    livenessResponse liveSnapshot = livenessResponse deadSnapshot ∧
    serviceStatus liveSnapshot = .available ∧
    serviceStatus deadSnapshot = .unavailable := by decide

end AlloyStudio.PatchContracts.Observability
