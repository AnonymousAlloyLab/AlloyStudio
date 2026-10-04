import AdmissionExtracted
namespace AlloyStudio.IngressAdmission.Spec

theorem erase_subset (owner value : Nat) (owners : List Nat) :
    Owns value (erase owner owners) → Owns value owners := by
  induction owners with
  | nil => exact fun impossible => impossible
  | cons head tail ih =>
    unfold erase
    split
    · exact fun present => Or.inr present
    · intro present
      cases present with
      | inl same => exact Or.inl same
      | inr rest => exact Or.inr (ih rest)

theorem erase_length (owner : Nat) (owners : List Nat) (present : Owns owner owners) :
    (erase owner owners).length + 1 = owners.length := by
  induction owners with
  | nil => exact False.elim present
  | cons head tail ih =>
    unfold erase
    split
    · rfl
    · rename_i different
      have inTail : Owns owner tail := by
        cases present with
        | inl same => exact False.elim (different same)
        | inr rest => exact rest
      exact congrArg Nat.succ (ih inTail)

theorem erase_unique (owner : Nat) (owners : List Nat) (unique : Unique owners) :
    Unique (erase owner owners) := by
  induction owners with
  | nil => exact True.intro
  | cons head tail ih =>
    unfold erase
    split
    · exact unique.2
    · exact ⟨fun present => unique.1 (erase_subset owner head tail present), ih unique.2⟩

theorem erase_absent (owner : Nat) (owners : List Nat) (unique : Unique owners) :
    ¬ Owns owner (erase owner owners) := by
  induction owners with
  | nil => exact fun impossible => impossible
  | cons head tail ih =>
    unfold erase
    split
    · rename_i same
      cases same
      exact unique.1
    · rename_i different
      intro present
      cases present with
      | inl same => exact different same
      | inr rest => exact ih unique.2 rest

theorem erase_other (owner value : Nat) (owners : List Nat) (different : value ≠ owner) :
    Owns value owners → Owns value (erase owner owners) := by
  induction owners with
  | nil => exact fun impossible => impossible
  | cons head tail ih =>
    unfold erase
    split
    · rename_i same
      intro present
      cases present with
      | inl equal => exact False.elim (different (Eq.trans equal same.symm))
      | inr rest => exact rest
    · intro present
      cases present with
      | inl same => exact Or.inl same
      | inr rest => exact Or.inr (ih rest)

theorem initial_valid (limit : Nat) : Valid limit initial :=
  ⟨rfl, True.intro, Nat.zero_le limit⟩

theorem reserve_valid (limit owner : Nat) (credit : Bool) (state : State)
    (valid : Valid limit state) : Valid limit (Extracted.reserve limit owner credit state).state := by
  unfold Extracted.reserve reserve
  split
  · exact valid
  · rename_i fresh
    split
    · exact valid
    · rename_i room
      cases credit with
      | false => exact valid
      | true =>
        have less : state.active < limit := Nat.lt_of_not_ge (fun full => room (decide_eq_true full))
        exact ⟨congrArg (fun n => n + 1) valid.1, ⟨fresh, valid.2.1⟩,
          Nat.succ_le_of_lt less⟩

theorem release_valid (limit owner : Nat) (state : State) (valid : Valid limit state) :
    Valid limit (Extracted.release owner state) := by
  unfold Extracted.release release
  split
  · rename_i present
    have length := erase_length owner state.owners present
    have count : state.active - 1 = (erase owner state.owners).length := by
      rw [valid.1, ← length]
      rfl
    exact ⟨count, erase_unique owner state.owners valid.2.1,
      Nat.le_trans (Nat.sub_le state.active 1) valid.2.2⟩
  · exact valid

theorem release_idempotent (owner : Nat) (state : State) (unique : Unique state.owners) :
    Extracted.release owner (Extracted.release owner state) = Extracted.release owner state := by
  unfold Extracted.release release
  split
  · rename_i present
    have absent := erase_absent owner state.owners unique
    exact ite_eq_right absent
  · rename_i absent
    rfl

theorem release_other_preserved (owner value : Nat) (state : State)
    (different : value ≠ owner) (present : Owns value state.owners) :
    Owns value (Extracted.release owner state).owners := by
  unfold Extracted.release release
  split
  · exact erase_other owner value state.owners different present
  · exact present

theorem rejection_unchanged (limit owner : Nat) (credit : Bool) (state : State)
    (rejected : (Extracted.reserve limit owner credit state).outcome ≠ .accepted) :
    (Extracted.reserve limit owner credit state).state = state := by
  unfold Extracted.reserve reserve at rejected ⊢
  by_cases duplicate : Owns owner state.owners
  · rw [ite_eq_left duplicate]
  · rw [ite_eq_right duplicate] at rejected ⊢
    by_cases full : compare Comparison.ge state.active limit = true
    · rw [ite_eq_left full]
    · rw [ite_eq_right full] at rejected ⊢
      cases credit with
      | false => rfl
      | true => exact False.elim (rejected rfl)

inductive Event where
  | reserve (owner : Nat) (credit : Bool)
  | release (owner : Nat)

def transition (limit : Nat) (event : Event) (state : State) : State :=
  match event with
  | .reserve owner credit => (Extracted.reserve limit owner credit state).state
  | .release owner => Extracted.release owner state

def run (limit : Nat) : List Event → State → State
  | [], state => state
  | event :: rest, state => run limit rest (transition limit event state)

theorem trace_valid (limit : Nat) (events : List Event) (state : State)
    (valid : Valid limit state) : Valid limit (run limit events state) := by
  induction events generalizing state with
  | nil => exact valid
  | cons event rest ih =>
    apply ih
    cases event with
    | reserve owner credit => exact reserve_valid limit owner credit state valid
    | release owner => exact release_valid limit owner state valid

theorem all_reachable_count_and_capacity (limit : Nat) (events : List Event) :
    Valid limit (run limit events initial) := trace_valid limit events initial (initial_valid limit)

theorem live_le_registry (threads : List ThreadEntry) : liveCount threads ≤ threads.length := by
  induction threads with
  | nil => exact Nat.le_refl 0
  | cons head tail ih =>
    unfold liveCount
    split
    · rw [Nat.zero_add]
      exact Nat.le_trans ih (Nat.le_succ tail.length)
    · rw [Nat.one_add]
      exact Nat.succ_le_succ ih

theorem registered_length (threads : List ThreadEntry) :
    (registered threads).length = threads.length := by
  induction threads with
  | nil => rfl
  | cons head tail ih => exact congrArg Nat.succ ih

theorem retained_threads_bounded (limit : Nat) (state : State) (threads : List ThreadEntry)
    (valid : Valid limit state) (ownership : state.owners = registered threads) :
    liveCount threads ≤ limit := by
  have count : threads.length = state.active := by
    exact Eq.trans (registered_length threads).symm
      (Eq.trans (congrArg List.length ownership.symm) valid.1.symm)
  exact Nat.le_trans (live_le_registry threads) (count ▸ valid.2.2)

theorem one_pending_per_listener (listener : ListenerState) : pendingCount listener ≤ 1 := by
  unfold pendingCount
  cases listener.pending with
  | false => exact Nat.zero_le 1
  | true => exact Nat.le_refl 1

theorem pair_pending_at_most_two (pub control : ListenerState) :
    pendingCount pub + pendingCount control ≤ 2 :=
  Nat.add_le_add (one_pending_per_listener pub) (one_pending_per_listener control)

theorem enabled_combined_capacity (pub control : State) (publicLimit controlLimit : Nat)
    (hp : Valid publicLimit pub) (hc : Valid controlLimit control) :
    totalActive ⟨pub, some control⟩ ≤ publicLimit + controlLimit :=
  Nat.add_le_add hp.2.2 hc.2.2

theorem disabled_combined_capacity (pub : State) (publicLimit : Nat)
    (hp : Valid publicLimit pub) : totalActive ⟨pub, none⟩ ≤ publicLimit := hp.2.2

theorem public_cannot_borrow_control (topology : Topology) (next : State) :
    (changePublic topology next).control = topology.control := rfl

theorem control_cannot_borrow_public (topology : Topology) (next : State) :
    (changeControl topology next).pub = topology.pub := rfl

theorem combined_credit_bound (pub control publicBudget controlBudget : Nat)
    (hp : pub ≤ publicBudget) (hc : control ≤ controlBudget) :
    pub + control ≤ publicBudget + controlBudget := Nat.add_le_add hp hc

theorem default_handler_total : Extracted.publicHandlers + Extracted.controlHandlers = 32 := rfl
theorem default_burst_total : Extracted.publicBurst + Extracted.controlBurst = 64 := rfl
theorem default_rate_total : Extracted.publicRate + Extracted.controlRate = 32 := rfl
theorem default_control_disabled : Extracted.controlPortDefault = 0 := rfl

theorem witness_public_full :
    (Extracted.reserve 1 2 true (Extracted.reserve 1 1 true initial).state).outcome = .capacity := rfl

theorem witness_bad_guard :
    (IngressAdmission.reserve .gt 1 1 2 true
      (Extracted.reserve 1 1 true initial).state).state.active = 2 := rfl

theorem allocation_prefix_safe : PrefixSafe Extracted.allocation lifeInitial := by decide
theorem refusal_prefix_safe : PrefixSafe Extracted.refusal lifeInitial := by decide
theorem preattempt_failure_prefix_safe : PrefixSafe Extracted.preAttemptFailure lifeInitial := by decide
theorem failed_constructor_prefix_safe : PrefixSafe Extracted.constructorFailure lifeInitial := by decide
theorem ambiguous_start_prefix_safe : PrefixSafe Extracted.retainedOnAmbiguousStart lifeInitial := by decide
theorem unstarted_join_refusal_prefix_safe :
    PrefixSafe Extracted.retainedOnUnstartedJoin lifeInitial := by decide
theorem live_join_return_prefix_safe :
    PrefixSafe Extracted.retainedOnLiveJoin lifeInitial := by decide
theorem identified_native_thread_not_started :
    (lifeRun Extracted.retainedOnUnstartedJoin lifeInitial).identified = true ∧
    (lifeRun Extracted.retainedOnUnstartedJoin lifeInitial).started = false ∧
    (lifeRun Extracted.retainedOnUnstartedJoin lifeInitial).alive = true := ⟨rfl, rfl, rfl⟩
theorem identified_reports_not_alive : reportedAlive .identified = false := rfl
theorem identified_join_refuses : zeroTimeJoin .identified = .refused := rfl
theorem identity_only_reaps_unstarted_counterexample :
    reclaimable .identityOnly .identified = true ∧
    zeroTimeJoin .identified = .refused := ⟨rfl, rfl⟩
theorem identified_release_counterexample :
    ¬ LifeSafe (lifeRun [.reserve, .allocate, .register, .attempt, .identify,
                        .unregister, .release] lifeInitial) :=
  fun impossible => Bool.noConfusion (impossible rfl).1
theorem joined_reaping_only_dead (phase : Phase) (accepted : Extracted.canReap phase = true) :
    phase = .dead := by
  cases phase <;> cases accepted <;> rfl
theorem refused_join_retains (phase : Phase) (refused : zeroTimeJoin phase = .refused) :
    Extracted.canReap phase = false := by
  cases phase <;> first | rfl | cases refused
theorem successful_join_can_still_be_alive :
    zeroTimeJoin .running = .returned ∧ reportedAlive .running = true ∧
    Extracted.canReap .running = false := ⟨rfl, rfl, rfl⟩
theorem terminated_join_reclaims : Extracted.canReap .dead = true := rfl
theorem allocated_owns_before_start :
    (lifeRun [.reserve, .allocate, .register] lifeInitial).reserved = true ∧
    (lifeRun [.reserve, .allocate, .register] lifeInitial).registered = true := ⟨rfl, rfl⟩
theorem reaping_prefix_safe :
    PrefixSafe Extracted.reaping (lifeRun Extracted.allocation lifeInitial) := by decide
theorem released_thread_is_dead :
    (lifeRun Extracted.reaping (lifeRun Extracted.allocation lifeInitial)).alive = false := rfl
theorem live_release_counterexample :
    ¬ LifeSafe (lifeRun [.reserve, .allocate, .register, .start, .release] lifeInitial) :=
  fun impossible => Bool.noConfusion (impossible rfl).1

theorem registered_setPhase (owner : Nat) (phase : Phase) (threads : List ThreadEntry) :
    registered (setPhase owner phase threads) = registered threads := by
  induction threads with
  | nil => rfl
  | cons head tail ih =>
    unfold setPhase
    split
    · rfl
    · exact congrArg (List.cons head.owner) ih

theorem registered_removeThread (owner : Nat) (threads : List ThreadEntry) :
    registered (removeThread owner threads) = erase owner (registered threads) := by
  induction threads with
  | nil => rfl
  | cons head tail ih =>
    change registered (if owner = head.owner then tail else head :: removeThread owner tail) =
      (if owner = head.owner then registered tail else head.owner :: erase owner (registered tail))
    by_cases same : owner = head.owner
    · rw [ite_eq_left same, ite_eq_left same]
    · rw [ite_eq_right same, ite_eq_right same]
      exact congrArg (List.cons head.owner) ih

theorem findPhase_owned (owner : Nat) (phase : Phase) (threads : List ThreadEntry)
    (found : findPhase owner threads = some phase) : Owns owner (registered threads) := by
  induction threads with
  | nil => cases found
  | cons head tail ih =>
    unfold findPhase at found
    split at found
    · rename_i same
      exact Or.inl same
    · exact Or.inr (ih found)

def retire (owner : Nat) (state : Resources) : Resources :=
  ⟨Extracted.release owner state.admission, removeThread owner state.threads⟩

def reapObserved (owner : Nat) (state : Resources) : Resources :=
  match findPhase owner state.threads with
  | none => state
  | some phase => if Extracted.canReap phase then retire owner state else state

def resourceStep (limit : Nat) (event : ResourceEvent) (state : Resources) : Resources :=
  match event with
  | .reserve owner credit =>
    let result := Extracted.reserve limit owner credit state.admission
    if result.outcome = .accepted then
      ⟨result.state, ⟨owner, .reserved⟩ :: state.threads⟩
    else ⟨result.state, state.threads⟩
  | .phase owner phase => ⟨state.admission, setPhase owner phase state.threads⟩
  | .reapDead owner => reapObserved owner state
  | .failedBeforeAttempt owner =>
    if findPhase owner state.threads = some .reserved then retire owner state
    else if findPhase owner state.threads = some .allocated then retire owner state
    else state

theorem retire_valid (limit owner : Nat) (state : Resources)
    (valid : ResourcesValid limit state) (present : Owns owner state.admission.owners) :
    ResourcesValid limit (retire owner state) := by
  refine ⟨release_valid limit owner state.admission valid.1, ?_⟩
  unfold retire Extracted.release release
  rw [ite_eq_left present]
  exact Eq.trans (congrArg (erase owner) valid.2) (registered_removeThread owner state.threads).symm

theorem resource_step_valid (limit : Nat) (event : ResourceEvent) (state : Resources)
    (valid : ResourcesValid limit state) : ResourcesValid limit (resourceStep limit event state) := by
  cases event with
  | reserve owner credit =>
    dsimp only [resourceStep]
    have count := reserve_valid limit owner credit state.admission valid.1
    split
    · rename_i accepted
      refine ⟨count, ?_⟩
      unfold Extracted.reserve reserve at accepted ⊢
      by_cases duplicate : Owns owner state.admission.owners
      · rw [ite_eq_left duplicate] at accepted
        cases accepted
      · rw [ite_eq_right duplicate] at accepted ⊢
        by_cases full : compare .ge state.admission.active limit = true
        · rw [ite_eq_left full] at accepted
          cases accepted
        · rw [ite_eq_right full] at accepted ⊢
          cases credit with
          | false => cases accepted
          | true => exact congrArg (List.cons owner) valid.2
    · rename_i rejected
      exact ⟨count, Eq.trans (congrArg State.owners
        (rejection_unchanged limit owner credit state.admission rejected)) valid.2⟩
  | phase owner phase =>
    exact ⟨valid.1, Eq.trans valid.2 (registered_setPhase owner phase state.threads).symm⟩
  | reapDead owner =>
    change ResourcesValid limit (reapObserved owner state)
    unfold reapObserved
    cases found : findPhase owner state.threads with
    | none => exact valid
    | some phase =>
      change ResourcesValid limit (if Extracted.canReap phase then retire owner state else state)
      split
      · exact retire_valid limit owner state valid
          (valid.2 ▸ findPhase_owned owner phase state.threads found)
      · exact valid
  | failedBeforeAttempt owner =>
    dsimp only [resourceStep]
    split
    · rename_i fresh
      exact retire_valid limit owner state valid
        (valid.2 ▸ findPhase_owned owner .reserved state.threads fresh)
    · split
      · rename_i unstarted
        exact retire_valid limit owner state valid
          (valid.2 ▸ findPhase_owned owner .allocated state.threads unstarted)
      · exact valid

theorem identified_reap_is_noop (limit owner : Nat) (state : Resources)
    (identified : findPhase owner state.threads = some .identified) :
    resourceStep limit (.reapDead owner) state = state := by
  change reapObserved owner state = state
  unfold reapObserved
  rw [identified]
  rfl

theorem attempted_reap_is_noop (limit owner : Nat) (state : Resources)
    (attempted : findPhase owner state.threads = some .attempted) :
    resourceStep limit (.reapDead owner) state = state := by
  change reapObserved owner state = state
  unfold reapObserved
  rw [attempted]
  rfl

def resourceRun (limit : Nat) : List ResourceEvent → Resources → Resources
  | [], state => state
  | event :: rest, state => resourceRun limit rest (resourceStep limit event state)

theorem resource_trace_valid (limit : Nat) (events : List ResourceEvent) (state : Resources)
    (valid : ResourcesValid limit state) : ResourcesValid limit (resourceRun limit events state) := by
  induction events generalizing state with
  | nil => exact valid
  | cons event rest ih => exact ih _ (resource_step_valid limit event state valid)

theorem reachable_registry_exact (limit : Nat) (events : List ResourceEvent) :
    ResourcesValid limit (resourceRun limit events resourcesInitial) :=
  resource_trace_valid limit events resourcesInitial ⟨initial_valid limit, rfl⟩

theorem all_live_threads_bounded (limit : Nat) (events : List ResourceEvent) :
    liveCount (resourceRun limit events resourcesInitial).threads ≤ limit :=
  retained_threads_bounded limit _ _ (reachable_registry_exact limit events).1
    (reachable_registry_exact limit events).2

theorem registry_cardinality_bounded (limit : Nat) (events : List ResourceEvent) :
    (resourceRun limit events resourcesInitial).threads.length ≤ limit := by
  let state := resourceRun limit events resourcesInitial
  have valid := reachable_registry_exact limit events
  have length : state.threads.length = state.admission.active :=
    Eq.trans (registered_length state.threads).symm
      (Eq.trans (congrArg List.length valid.2.symm) valid.1.1.symm)
  exact length ▸ valid.1.2.2

theorem selected_public_limit : Extracted.laneLimit false = 30 := rfl
theorem selected_control_limit : Extracted.laneLimit true = 2 := rfl

theorem credit_refill_le_capacity (capacity credit elapsed rate : Nat) :
    Extracted.refillCredit capacity credit elapsed rate ≤ capacity := by
  unfold Extracted.refillCredit clip
  split
  · exact Nat.le_refl capacity
  · rename_i less
    exact Nat.le_of_lt (Nat.lt_of_not_ge less)

theorem admitted_credit_enough (credit : Nat) (available : Extracted.availableCredit credit = true) :
    Extracted.tokenUnit ≤ credit := of_decide_eq_true available

theorem subtract_then_add (credit unit : Nat) (enough : unit ≤ credit) :
    credit - unit + unit = credit := by
  induction unit generalizing credit with
  | zero => rfl
  | succ unit ih =>
    cases credit with
    | zero => exact False.elim (Nat.not_succ_le_zero _ enough)
    | succ credit =>
      rw [Nat.succ_sub_succ_eq_sub]
      exact congrArg Nat.succ (ih credit (Nat.le_of_succ_le_succ enough))

theorem admitted_charge_exact (credit : Nat) (available : Extracted.availableCredit credit = true) :
    Extracted.chargeCredit credit + Extracted.tokenUnit = credit :=
  subtract_then_add credit Extracted.tokenUnit (admitted_credit_enough credit available)

theorem admitted_charge_capacity (capacity credit : Nat) (bound : credit ≤ capacity) :
    Extracted.chargeCredit credit ≤ capacity := Nat.le_trans (Nat.sub_le _ _) bound

theorem combined_remaining_credit (publicCapacity controlCapacity pub control : Nat)
    (hp : pub ≤ publicCapacity) (hc : control ≤ controlCapacity) :
    pub + control ≤ publicCapacity + controlCapacity := Nat.add_le_add hp hc

end AlloyStudio.IngressAdmission.Spec
