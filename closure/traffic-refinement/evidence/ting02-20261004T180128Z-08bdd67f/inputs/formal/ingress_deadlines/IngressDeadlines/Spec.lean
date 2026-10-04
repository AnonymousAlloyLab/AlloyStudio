import IngressDeadlines.Model
import IngressDeadlines.Extracted

namespace AlloyStudio.IngressDeadlines.Spec

open AlloyStudio.IngressDeadlines.Model

/-- Soundness of the source-extracted comparison, using ordinary integer order.
No accepted Boolean from the implementation is taken as an assumed fact. -/
theorem check_deadline_sound (now deadline : Int)
    (accepted : Extracted.checkDeadline now deadline = true) : Before now deadline := by
  unfold Extracted.checkDeadline at accepted
  by_cases expired : now ≥ deadline
  · rw [ite_eq_left expired] at accepted
    cases accepted
  · exact expired

theorem check_deadline_complete (now deadline : Int) (before : Before now deadline) :
    Extracted.checkDeadline now deadline = true := by
  unfold Extracted.checkDeadline
  have unexpired : ¬now ≥ deadline := before
  rw [ite_eq_right unexpired]

theorem initial_safe (deadline current : Int) (samples : List Int) :
    StateSafe false (initial deadline current samples) := by
  refine ⟨?_, ?_, ?_⟩
  · intro impossible
    cases impossible
  · intro cut member
    cases member
  · intro cut member
    cases member

theorem cuts_safe_cons (clock deadline : Int) (cuts : List Cut)
    (before : Before clock deadline) (safe : CutsSafe cuts) :
    CutsSafe (⟨clock, deadline⟩ :: cuts) := by
  intro cut member
  cases member with
  | head => exact before
  | tail _ inTail => exact safe cut inTail

/-- A guard's sample determines fresh state; work never supplies such evidence. -/
theorem guard_step_safe (duration : Int) (state next : State)
    (old : StateSafe false state)
    (executed : step Extracted.checkDeadline duration .guard state = some next) :
    StateSafe true next := by
  cases state with
  | mk current deadline samples deliveries resets =>
    cases samples with
    | nil => cases executed
    | cons now rest =>
      change (if Extracted.checkDeadline now deadline then
        some (State.mk now deadline rest deliveries resets) else none) = some next at executed
      cases checked : Extracted.checkDeadline now deadline with
      | false =>
          rw [checked] at executed
          cases executed
      | true =>
          rw [checked] at executed
          cases executed
          exact ⟨fun _ => check_deadline_sound now deadline checked, old.2⟩

theorem forget_fresh (fresh : Bool) (state : State) (safe : StateSafe fresh state) :
    StateSafe false state := by
  exact ⟨fun impossible => Bool.false_ne_true impossible |>.elim, safe.2⟩

theorem work_step_safe (duration : Int) (state next : State)
    (safe : StateSafe false state)
    (executed : step Extracted.checkDeadline duration .work state = some next) :
    StateSafe false next := by
  cases state with
  | mk current deadline samples deliveries resets =>
    cases samples with
    | nil => cases executed
    | cons now rest =>
      cases executed
      exact ⟨fun impossible => Bool.false_ne_true impossible |>.elim, safe.2⟩

theorem deliver_step_safe (duration : Int) (state next : State)
    (safe : StateSafe true state)
    (executed : step Extracted.checkDeadline duration .deliver state = some next) :
    StateSafe true next := by
  cases executed
  exact ⟨safe.1,
    cuts_safe_cons state.current state.deadline state.deliveries (safe.1 rfl) safe.2.1,
    safe.2.2⟩

theorem reset_step_safe (duration : Int) (state next : State)
    (safe : StateSafe true state)
    (executed : step Extracted.checkDeadline duration .reset state = some next) :
    StateSafe false next := by
  cases executed
  exact ⟨fun impossible => Bool.false_ne_true impossible |>.elim, safe.2.1,
    cuts_safe_cons state.current state.deadline state.resets (safe.1 rfl) safe.2.2⟩

/-- Arbitrary explicit observation histories, including late work completion,
are covered. Safety follows from the actual extracted comparison and separately
checked program shape, not from a validity assumption on the trace. -/
theorem run_safe (duration : Int) (program : List Action) (fresh : Bool)
    (state result : State) (shape : wellGuarded program fresh = true)
    (safe : StateSafe fresh state)
    (executed : run Extracted.checkDeadline duration program state = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets := by
  induction program generalizing fresh state with
  | nil =>
      cases executed
      exact safe.2
  | cons action rest inductionHypothesis =>
      unfold run at executed
      cases transition : step Extracted.checkDeadline duration action state with
      | none =>
          rw [transition] at executed
          cases executed
      | some next =>
          rw [transition] at executed
          cases action with
          | guard =>
              exact inductionHypothesis true next shape
                (guard_step_safe duration state next (forget_fresh fresh state safe) transition) executed
          | work =>
              exact inductionHypothesis false next shape
                (work_step_safe duration state next (forget_fresh fresh state safe) transition) executed
          | reset =>
              cases fresh with
              | false => cases shape
              | true =>
                  exact inductionHypothesis false next shape
                    (reset_step_safe duration state next safe transition) executed
          | deliver =>
              cases fresh with
              | false => cases shape
              | true =>
                  exact inductionHypothesis true next shape
                    (deliver_step_safe duration state next safe transition) executed

theorem initial_run_safe (duration deadline current : Int) (samples : List Int)
    (program : List Action) (result : State)
    (shape : wellGuarded program false = true)
    (executed : run Extracted.checkDeadline duration program
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  run_safe duration program false (initial deadline current samples) result shape
    (initial_safe deadline current samples) executed

/-- Concrete negative control: execution permits an unguarded late delivery.
Only the independent policy rejects this missing-guard program. -/
theorem unguarded_program_rejected : wellGuarded [.work, .deliver] false = false := rfl

theorem unguarded_late_delivery_executes :
    run Extracted.checkDeadline 5 [.work, .deliver] (initial 5 0 [6]) =
      some ⟨6, 5, [], [⟨6, 5⟩], []⟩ := rfl

theorem unguarded_late_delivery_unsafe : ¬CutsSafe [⟨6, 5⟩] := by
  intro safe
  have impossible := safe ⟨6, 5⟩ (List.Mem.head [])
  have expired : (5 : Int) ≤ 6 := by decide
  exact impossible expired

theorem recv_program_guarded : wellGuarded Extracted.recvProgram false = true := rfl

theorem readline_program_guarded : wellGuarded Extracted.readlineProgram false = true := rfl

theorem read1_program_guarded : wellGuarded Extracted.read1Program false = true := rfl

theorem parse_program_guarded : wellGuarded Extracted.parseProgram false = true := rfl

theorem body_program_guarded : wellGuarded Extracted.bodyProgram false = true := rfl

theorem begin_body_program_guarded : wellGuarded Extracted.beginBodyProgram false = true := rfl

/-- The reset clock is the previously sampled guard clock. There is no second
clock observation in which the old phase could expire before resetting. -/
theorem reset_uses_current_sample (duration : Int) (state : State) :
    step Extracted.checkDeadline duration .reset state =
      some { state with
        deadline := state.current + duration
        resets := ⟨state.current, state.deadline⟩ :: state.resets } := rfl

/-- Work destroys guard domination structurally, even if the program performed
a guard earlier. A new guard is required before either observation cut. -/
theorem work_after_guard_cannot_deliver :
    wellGuarded [.guard, .work, .deliver] false = false := rfl

theorem work_after_guard_cannot_reset :
    wellGuarded [.guard, .work, .reset] false = false := rfl

theorem unguarded_reset_executes :
    run Extracted.checkDeadline 5 [.work, .reset] (initial 5 0 [6]) =
      some ⟨6, 11, [], [], [⟨6, 5⟩]⟩ := rfl

theorem recv_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.recvProgram
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.recvProgram result
    recv_program_guarded executed

theorem readline_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.readlineProgram
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.readlineProgram result
    readline_program_guarded executed

theorem read1_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.read1Program
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.read1Program result
    read1_program_guarded executed

theorem parse_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.parseProgram
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.parseProgram result
    parse_program_guarded executed

theorem body_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.bodyProgram
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.bodyProgram result
    body_program_guarded executed

theorem begin_body_completion_safe (duration deadline current : Int) (samples : List Int)
    (result : State)
    (executed : run Extracted.checkDeadline duration Extracted.beginBodyProgram
      (initial deadline current samples) = some result) :
    CutsSafe result.deliveries ∧ CutsSafe result.resets :=
  initial_run_safe duration deadline current samples Extracted.beginBodyProgram result
    begin_body_program_guarded executed

theorem expired_boundary_guard_rejected :
    run Extracted.checkDeadline 5 [.guard, .deliver] (initial 5 0 [5]) = none := rfl

theorem late_work_completion_rejected :
    run Extracted.checkDeadline 5 [.guard, .work, .guard, .deliver]
      (initial 5 0 [0, 6, 6]) = none := rfl

theorem expired_phase_cannot_reset :
    run Extracted.checkDeadline 5 [.guard, .reset] (initial 5 0 [5]) = none := rfl

end AlloyStudio.IngressDeadlines.Spec
