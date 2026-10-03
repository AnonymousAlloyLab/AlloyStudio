import Std

/-!
TB01 resource fragment, independent of the runtime. Owners and incarnations are
natural numbers issued by the scheduler; an external reap acknowledgement is a
model input, not an assertion that a signal kills an OS process. There are three
lanes. Byte charges are supplied by the caller, not measured by this model.
The generic ledger proves simultaneous uniqueness and immediate idempotence only.
Its externally supplied owner IDs must not be reused while stale terminal events
can arrive; owner_reuse_breach constructs why. The pool separately issues fresh
incarnations. No HTTP parser, JVM evaluation, elapsed-time progress, memory measurement or
Python/Java correspondence is asserted here.
-/
namespace AlloyStudio.Traffic.Resources

inductive Lane where
  | feedback | behavior | admin
  deriving DecidableEq

structure Charge where
  owner : Nat
  lane : Lane
  bytes : Nat
  deriving DecidableEq

abbrev Ledger := List Charge

def total (weight : Charge → Nat) : Ledger → Nat
  | [] => 0
  | c :: cs => weight c + total weight cs

def count (cs : Ledger) : Nat := total (fun _ => 1) cs
def bytes (cs : Ledger) : Nat := total Charge.bytes cs
def laneCount (lane : Lane) (cs : Ledger) : Nat :=
  total (fun c => if c.lane = lane then 1 else 0) cs
def laneBytes (lane : Lane) (cs : Ledger) : Nat :=
  total (fun c => if c.lane = lane then c.bytes else 0) cs

def owns (owner : Nat) : Ledger → Bool
  | [] => false
  | c :: cs => if c.owner = owner then true else owns owner cs

/-- Release deletes only this owner's charges, including no-op repeated release. -/
def release (owner : Nat) : Ledger → Ledger
  | [] => []
  | c :: cs => if c.owner = owner then release owner cs else c :: release owner cs

structure Limits where
  count : Nat
  bytes : Nat
  laneCount : Lane → Nat
  laneBytes : Lane → Nat

def Within (cap : Limits) (cs : Ledger) : Prop :=
  count cs ≤ cap.count ∧ bytes cs ≤ cap.bytes ∧
    (∀ l, laneCount l cs ≤ cap.laneCount l) ∧
    (∀ l, laneBytes l cs ≤ cap.laneBytes l)

/-- Finite executable admission guards; no invariant is stored inside a state. -/
def fits (cap : Limits) (cs : Ledger) : Bool :=
  decide (count cs ≤ cap.count) && decide (bytes cs ≤ cap.bytes) &&
  decide (laneCount .feedback cs ≤ cap.laneCount .feedback) &&
  decide (laneCount .behavior cs ≤ cap.laneCount .behavior) &&
  decide (laneCount .admin cs ≤ cap.laneCount .admin) &&
  decide (laneBytes .feedback cs ≤ cap.laneBytes .feedback) &&
  decide (laneBytes .behavior cs ≤ cap.laneBytes .behavior) &&
  decide (laneBytes .admin cs ≤ cap.laneBytes .admin)

def acquire (cap : Limits) (c : Charge) (cs : Ledger) : Ledger :=
  if owns c.owner cs then cs else if fits cap (c :: cs) then c :: cs else cs

private theorem and_true {a b : Bool} (h : (a && b) = true) : a = true ∧ b = true := by
  cases a <;> cases b
  · cases h
  · cases h
  · cases h
  · exact ⟨rfl, rfl⟩

private theorem fits_sound (cap : Limits) (cs : Ledger)
    (h : fits cap cs = true) : Within cap cs := by
  have h7 := and_true h
  have h6 := and_true h7.1
  have h5 := and_true h6.1
  have h4 := and_true h5.1
  have h3 := and_true h4.1
  have h2 := and_true h3.1
  have h1 := and_true h2.1
  refine ⟨of_decide_eq_true h1.1, of_decide_eq_true h1.2, ?_, ?_⟩
  · intro l
    cases l with
    | feedback => exact of_decide_eq_true h2.2
    | behavior => exact of_decide_eq_true h3.2
    | admin => exact of_decide_eq_true h4.2
  · intro l
    cases l with
    | feedback => exact of_decide_eq_true h5.2
    | behavior => exact of_decide_eq_true h6.2
    | admin => exact of_decide_eq_true h7.2

private theorem release_total_le (owner : Nat) (weight : Charge → Nat) (cs : Ledger) :
    total weight (release owner cs) ≤ total weight cs := by
  induction cs with
  | nil => exact Nat.le_refl 0
  | cons c cs ih =>
    unfold release
    split
    · exact Nat.le_trans ih (Nat.le_add_left _ _)
    · exact Nat.add_le_add_left ih (weight c)

theorem release_preserves_bounds (cap : Limits) (cs : Ledger) (owner : Nat)
    (h : Within cap cs) : Within cap (release owner cs) :=
  ⟨Nat.le_trans (release_total_le owner _ cs) h.1,
   Nat.le_trans (release_total_le owner _ cs) h.2.1,
   fun l => Nat.le_trans (release_total_le owner _ cs) (h.2.2.1 l),
   fun l => Nat.le_trans (release_total_le owner _ cs) (h.2.2.2 l)⟩

theorem acquire_preserves_bounds (cap : Limits) (cs : Ledger) (c : Charge)
    (h : Within cap cs) : Within cap (acquire cap c cs) := by
  unfold acquire
  split
  · exact h
  · split
    · rename_i hfit
      exact fits_sound cap _ hfit
    · exact h

inductive LedgerCommand where
  | acquire (charge : Charge)
  | release (owner : Nat)

def ledgerStep (cap : Limits) (cs : Ledger) : LedgerCommand → Ledger
  | .acquire charge => acquire cap charge cs
  | .release owner => release owner cs

inductive LedgerReachable (cap : Limits) : Ledger → Prop where
  | empty : LedgerReachable cap []
  | step {cs} (reached : LedgerReachable cap cs) (command : LedgerCommand) :
      LedgerReachable cap (ledgerStep cap cs command)

theorem reachable_resource_bounds (cap : Limits) (cs : Ledger)
    (h : LedgerReachable cap cs) : Within cap cs := by
  induction h with
  | empty => exact ⟨Nat.zero_le _, Nat.zero_le _, fun _ => Nat.zero_le _, fun _ => Nat.zero_le _⟩
  | step reached command ih =>
    cases command with
    | acquire c => exact acquire_preserves_bounds cap _ c ih
    | release owner => exact release_preserves_bounds cap _ owner ih

private theorem release_not_owned (owner : Nat) (cs : Ledger) :
    owns owner (release owner cs) = false := by
  induction cs with
  | nil => rfl
  | cons c cs ih =>
    unfold release
    split
    · exact ih
    · rename_i different
      change (if c.owner = owner then true else owns owner (release owner cs)) = false
      rw [ite_eq_right different]
      exact ih

theorem release_idempotent (owner : Nat) (cs : Ledger) :
    release owner (release owner cs) = release owner cs := by
  induction cs with
  | nil => rfl
  | cons c cs ih =>
    by_cases same : c.owner = owner
    · have one : release owner (c :: cs) = release owner cs := by
        change (if c.owner = owner then _ else _) = _
        exact ite_eq_left same
      rw [one]
      exact ih
    · have one : release owner (c :: cs) = c :: release owner cs := by
        change (if c.owner = owner then _ else _) = _
        exact ite_eq_right same
      rw [one]
      change (if c.owner = owner then _ else _) = _
      rw [ite_eq_right same, ih]

/-- At most one record per owner; this is proved from transitions, not stored. -/
def UniqueOwners : Ledger → Prop
  | [] => True
  | c :: cs => owns c.owner cs = false ∧ UniqueOwners cs

private theorem release_preserves_absence (owner other : Nat) (cs : Ledger)
    (h : owns other cs = false) : owns other (release owner cs) = false := by
  induction cs with
  | nil => rfl
  | cons c cs ih =>
    have different : c.owner ≠ other := by
      intro same
      unfold owns at h
      rw [ite_eq_left same] at h
      cases h
    have tail : owns other cs = false := by
      unfold owns at h
      rw [ite_eq_right different] at h
      exact h
    unfold release
    split
    · exact ih tail
    · change (if c.owner = other then true else owns other (release owner cs)) = false
      rw [ite_eq_right different]
      exact ih tail

private theorem release_unique (owner : Nat) (cs : Ledger)
    (h : UniqueOwners cs) : UniqueOwners (release owner cs) := by
  induction cs with
  | nil => exact True.intro
  | cons c cs ih =>
    unfold release
    split
    · exact ih h.2
    · exact ⟨release_preserves_absence owner c.owner cs h.1, ih h.2⟩

private theorem acquire_unique (cap : Limits) (c : Charge) (cs : Ledger)
    (h : UniqueOwners cs) : UniqueOwners (acquire cap c cs) := by
  unfold acquire
  split
  · exact h
  · rename_i absent
    split
    · exact ⟨Bool.eq_false_iff.mpr absent, h⟩
    · exact h

theorem reachable_unique_owners (cap : Limits) (cs : Ledger)
    (h : LedgerReachable cap cs) : UniqueOwners cs := by
  induction h with
  | empty => exact True.intro
  | step reached command ih =>
    cases command with
    | acquire c => exact acquire_unique cap c _ ih
    | release owner => exact release_unique owner _ ih


/-- A ticket includes both incarnation and the exact (abstract) execution key. -/
structure Ticket where
  incarnation : Nat
  serial : Nat
  context : Nat
  deriving DecidableEq

inductive Phase where
  | starting
  | idle
  | busy (ticket : Ticket)
  | stopping
  deriving DecidableEq

structure Worker where
  incarnation : Nat
  lane : Lane
  nextTicket : Nat
  phase : Phase
  deriving DecidableEq

inductive WorkerCommand where
  | launch
  | start (context : Nat)
  | complete (ticket : Ticket)
  | stop
  deriving DecidableEq

/-- Stop retains the worker; completion accepts only the entire busy ticket. -/
def workerStep (w : Worker) (command : WorkerCommand) : Worker :=
  match command with
  | .launch =>
      match w.phase with
      | .starting => { w with phase := .idle }
      | .idle => w
      | .busy _ => w
      | .stopping => w
  | .start context =>
      match w.phase with
      | .starting => w
      | .idle =>
          { w with phase := .busy ⟨w.incarnation, w.nextTicket, context⟩, nextTicket := w.nextTicket + 1 }
      | .busy _ => w
      | .stopping => w
  | .complete received =>
      match w.phase with
      | .starting => w
      | .idle => w
      | .busy expected =>
          if received = expected then { w with phase := .idle } else w
      | .stopping => w
  | .stop => { w with phase := .stopping }

def alter (incarnation : Nat) (command : WorkerCommand) : List Worker → List Worker
  | [] => []
  | w :: ws =>
      (if w.incarnation = incarnation then workerStep w command else w) ::
        alter incarnation command ws

/-- Positive matching reap is the only removal transition. -/
def reap (incarnation : Nat) : List Worker → List Worker
  | [] => []
  | w :: ws =>
      if w.incarnation = incarnation ∧ w.phase = .stopping then reap incarnation ws
      else w :: reap incarnation ws

structure Pool where
  workers : List Worker
  nextIncarnation : Nat
  launches : Nat
  deriving DecidableEq

def emptyPool : Pool := ⟨[], 0, 0⟩

/-- A process owns one ledger charge, including while starting or stopping. -/
def processCharges : List Worker → Ledger
  | [] => []
  | w :: ws => ⟨w.incarnation, w.lane, 0⟩ :: processCharges ws

def launchReady (incarnation : Nat) : List Worker → Bool
  | [] => false
  | w :: ws =>
      if w.incarnation = incarnation ∧ w.phase = .starting then true
      else launchReady incarnation ws

inductive PoolCommand where
  | reserve (lane : Lane)
  | worker (incarnation : Nat) (command : WorkerCommand)
  | reaped (incarnation : Nat)

/-- Reservation precedes launch. Failed guards leave state unchanged. -/
def poolStep (cap : Limits) (p : Pool) : PoolCommand → Pool
  | .reserve lane =>
      let w : Worker := ⟨p.nextIncarnation, lane, 0, .starting⟩
      if owns p.nextIncarnation (processCharges p.workers) then p else
        if fits cap (processCharges (w :: p.workers)) then
          ⟨w :: p.workers, p.nextIncarnation + 1, p.launches⟩
        else p
  | .worker incarnation command =>
      ⟨alter incarnation command p.workers, p.nextIncarnation,
        p.launches + (if command = .launch ∧ launchReady incarnation p.workers = true
                      then 1 else 0)⟩
  | .reaped incarnation =>
      ⟨reap incarnation p.workers, p.nextIncarnation, p.launches⟩

private theorem workerStep_owner (w : Worker) (command : WorkerCommand) :
    (workerStep w command).incarnation = w.incarnation ∧
    (workerStep w command).lane = w.lane := by
  cases w with
  | mk incarnation lane nextTicket phase =>
    cases command <;> cases phase <;> try exact ⟨rfl, rfl⟩
    dsimp only [workerStep]
    split <;> exact ⟨rfl, rfl⟩

private theorem alter_charges (incarnation : Nat) (command : WorkerCommand)
    (ws : List Worker) : processCharges (alter incarnation command ws) = processCharges ws := by
  induction ws with
  | nil => rfl
  | cons w ws ih =>
    change processCharges ((if w.incarnation = incarnation then _ else _) :: _) = _
    by_cases same : w.incarnation = incarnation
    · rw [ite_eq_left same]
      change (Charge.mk (workerStep w command).incarnation (workerStep w command).lane 0) :: _ = _
      rw [(workerStep_owner w command).1, (workerStep_owner w command).2, ih]
      rfl
    · rw [ite_eq_right same]
      change (Charge.mk w.incarnation w.lane 0) :: _ = _
      rw [ih]
      rfl

private theorem reap_total_le (incarnation : Nat) (weight : Charge → Nat)
    (ws : List Worker) :
    total weight (processCharges (reap incarnation ws)) ≤ total weight (processCharges ws) := by
  induction ws with
  | nil => exact Nat.le_refl 0
  | cons w ws ih =>
    unfold reap
    split
    · exact Nat.le_trans ih (Nat.le_add_left _ _)
    · exact Nat.add_le_add_left ih _

theorem stop_retains_all_reservations (incarnation : Nat) (ws : List Worker) :
    processCharges (alter incarnation .stop ws) = processCharges ws :=
  alter_charges incarnation .stop ws

theorem worker_commands_retain_reservations (incarnation : Nat) (command : WorkerCommand)
    (ws : List Worker) : processCharges (alter incarnation command ws) = processCharges ws :=
  alter_charges incarnation command ws

private theorem poolStep_bounds (cap : Limits) (p : Pool) (command : PoolCommand)
    (h : Within cap (processCharges p.workers)) :
    Within cap (processCharges (poolStep cap p command).workers) := by
  cases command with
  | reserve lane =>
    change Within cap (processCharges (if owns p.nextIncarnation (processCharges p.workers)
      then p else if fits cap (processCharges
      (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)) then
      Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).workers)
    split
    · exact h
    · split
      · rename_i accepted
        exact fits_sound cap _ accepted
      · exact h
  | worker incarnation command =>
    change Within cap (processCharges (alter incarnation command p.workers))
    rw [alter_charges]
    exact h
  | reaped incarnation =>
    exact ⟨Nat.le_trans (reap_total_le incarnation _ _) h.1,
      Nat.le_trans (reap_total_le incarnation _ _) h.2.1,
      fun l => Nat.le_trans (reap_total_le incarnation _ _) (h.2.2.1 l),
      fun l => Nat.le_trans (reap_total_le incarnation _ _) (h.2.2.2 l)⟩

inductive PoolReachable (cap : Limits) : Pool → Prop where
  | empty : PoolReachable cap emptyPool
  | step {p} (reached : PoolReachable cap p) (command : PoolCommand) :
      PoolReachable cap (poolStep cap p command)

theorem reachable_process_bounds (cap : Limits) (p : Pool)
    (h : PoolReachable cap p) : Within cap (processCharges p.workers) := by
  induction h with
  | empty => exact ⟨Nat.zero_le _, Nat.zero_le _, fun _ => Nat.zero_le _, fun _ => Nat.zero_le _⟩
  | step reached command ih => exact poolStep_bounds cap _ command ih

/-- There is at most one active evaluation in each worker record. -/
def activeTickets (w : Worker) : List Ticket :=
  match w.phase with
  | .starting => []
  | .idle => []
  | .busy ticket => [ticket]
  | .stopping => []

theorem one_task_per_worker (w : Worker) : (activeTickets w).length ≤ 1 := by
  cases w with
  | mk incarnation lane nextTicket phase =>
    cases phase <;> exact Nat.le_of_ble_eq_true rfl

theorem wrong_ticket_is_noop (w : Worker) (expected received : Ticket)
    (busy : w.phase = .busy expected) (different : received ≠ expected) :
    workerStep w (.complete received) = w := by
  unfold workerStep
  rw [busy]
  exact ite_eq_right different

theorem completion_to_idle (w : Worker) (ticket : Ticket)
    (busy : w.phase = .busy ticket) :
    workerStep w (.complete ticket) = { w with phase := .idle } := by
  unfold workerStep
  rw [busy]
  exact ite_eq_left (Eq.refl ticket)

theorem duplicate_completion_is_noop (w : Worker) (ticket : Ticket)
    (busy : w.phase = .busy ticket) :
    workerStep (workerStep w (.complete ticket)) (.complete ticket) =
      workerStep w (.complete ticket) := by
  rw [completion_to_idle w ticket busy]
  rfl

theorem completion_requires_current_ticket (w : Worker) (received : Ticket)
    (changed : workerStep w (.complete received) ≠ w) :
    w.phase = .busy received := by
  cases phaseEq : w.phase with
  | starting =>
    apply False.elim
    apply changed
    unfold workerStep
    rw [phaseEq]
  | idle =>
    apply False.elim
    apply changed
    unfold workerStep
    rw [phaseEq]
  | stopping =>
    apply False.elim
    apply changed
    unfold workerStep
    rw [phaseEq]
  | busy expected =>
    by_cases same : received = expected
    · cases same
      rfl
    · exact False.elim (changed (wrong_ticket_is_noop w expected received phaseEq same))


private theorem reap_preserves_absence (incarnation owner : Nat) (ws : List Worker)
    (h : owns owner (processCharges ws) = false) :
    owns owner (processCharges (reap incarnation ws)) = false := by
  induction ws with
  | nil => rfl
  | cons w ws ih =>
    have different : w.incarnation ≠ owner := by
      intro same
      change (if w.incarnation = owner then true else _) = false at h
      rw [ite_eq_left same] at h
      cases h
    have tail : owns owner (processCharges ws) = false := by
      change (if w.incarnation = owner then true else _) = false at h
      rw [ite_eq_right different] at h
      exact h
    unfold reap
    split
    · exact ih tail
    · change (if w.incarnation = owner then true else _) = false
      rw [ite_eq_right different]
      exact ih tail

private theorem reap_unique (incarnation : Nat) (ws : List Worker)
    (h : UniqueOwners (processCharges ws)) :
    UniqueOwners (processCharges (reap incarnation ws)) := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih =>
    unfold reap
    split
    · exact ih h.2
    · exact ⟨reap_preserves_absence incarnation w.incarnation ws h.1, ih h.2⟩

private theorem poolStep_unique (cap : Limits) (p : Pool) (command : PoolCommand)
    (h : UniqueOwners (processCharges p.workers)) :
    UniqueOwners (processCharges (poolStep cap p command).workers) := by
  cases command with
  | reserve lane =>
    change UniqueOwners (processCharges (if owns p.nextIncarnation (processCharges p.workers)
      then p else if fits cap (processCharges
      (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)) then
      Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).workers)
    split
    · exact h
    · rename_i absent
      split
      · exact ⟨Bool.eq_false_iff.mpr absent, h⟩
      · exact h
  | worker incarnation command =>
    change UniqueOwners (processCharges (alter incarnation command p.workers))
    rw [alter_charges]
    exact h
  | reaped incarnation => exact reap_unique incarnation _ h

theorem reachable_unique_process_owners (cap : Limits) (p : Pool)
    (h : PoolReachable cap p) : UniqueOwners (processCharges p.workers) := by
  induction h with
  | empty => exact True.intro
  | step reached command ih => exact poolStep_unique cap _ command ih

theorem warm_start_does_not_launch (cap : Limits) (p : Pool) (incarnation context : Nat) :
    (poolStep cap p (.worker incarnation (.start context))).launches = p.launches := by
  change p.launches + (if WorkerCommand.start context = .launch ∧ _ then 1 else 0) = _
  have different : ¬ (WorkerCommand.start context = .launch ∧
      launchReady incarnation p.workers = true) := fun h => WorkerCommand.noConfusion h.1
  rw [ite_eq_right different]
  rfl

theorem completion_does_not_launch (cap : Limits) (p : Pool) (incarnation : Nat)
    (ticket : Ticket) :
    (poolStep cap p (.worker incarnation (.complete ticket))).launches = p.launches := by
  change p.launches + (if WorkerCommand.complete ticket = .launch ∧ _ then 1 else 0) = _
  have different : ¬ (WorkerCommand.complete ticket = .launch ∧
      launchReady incarnation p.workers = true) := fun h => WorkerCommand.noConfusion h.1
  rw [ite_eq_right different]
  rfl

def warmPool (incarnation : Nat) (lane : Lane) (serial nextIncarnation launches : Nat) : Pool :=
  ⟨[⟨incarnation, lane, serial, .idle⟩], nextIncarnation, launches⟩

def warmJob (cap : Limits) (p : Pool) (incarnation serial context : Nat) : Pool :=
  poolStep cap (poolStep cap p (.worker incarnation (.start context)))
    (.worker incarnation (.complete ⟨incarnation, serial, context⟩))

private theorem pool_ext (p q : Pool) (workers : p.workers = q.workers)
    (next : p.nextIncarnation = q.nextIncarnation) (launches : p.launches = q.launches) : p = q := by
  cases p
  cases q
  cases workers
  cases next
  cases launches
  rfl

theorem warm_job_reuses_worker (cap : Limits) (incarnation : Nat) (lane : Lane)
    (serial nextIncarnation launches context : Nat) :
    warmJob cap (warmPool incarnation lane serial nextIncarnation launches)
      incarnation serial context =
    warmPool incarnation lane (serial + 1) nextIncarnation launches := by
  apply pool_ext
  · change alter incarnation (.complete ⟨incarnation, serial, context⟩)
      (alter incarnation (.start context) [⟨incarnation, lane, serial, .idle⟩]) =
      [⟨incarnation, lane, serial + 1, .idle⟩]
    have first : alter incarnation (.start context) [⟨incarnation, lane, serial, .idle⟩] =
        [⟨incarnation, lane, serial + 1, .busy ⟨incarnation, serial, context⟩⟩] := by
      change (if incarnation = incarnation then _ else _) :: [] = _
      rw [ite_eq_left (Eq.refl incarnation)]
      rfl
    rw [first]
    change (if incarnation = incarnation then _ else _) :: [] = _
    rw [ite_eq_left (Eq.refl incarnation)]
    change (if Ticket.mk incarnation serial context = Ticket.mk incarnation serial context
      then _ else _) :: [] = _
    rw [ite_eq_left (Eq.refl (Ticket.mk incarnation serial context))]
  · rfl
  · change (poolStep cap _ (.worker incarnation (.complete _))).launches = launches
    rw [completion_does_not_launch, warm_start_does_not_launch]
    rfl

/-- Replay actual start/completion events for every listed job, without recycling. -/
def warmJobs (cap : Limits) (incarnation serial : Nat) (p : Pool) : List Nat → Pool
  | [] => p
  | context :: contexts =>
      warmJobs cap incarnation (serial + 1)
        (warmJob cap p incarnation serial context) contexts

theorem sequential_jobs_reuse_fixed_worker (cap : Limits) (incarnation : Nat) (lane : Lane)
    (serial nextIncarnation launches : Nat) (contexts : List Nat) :
    warmJobs cap incarnation serial (warmPool incarnation lane serial nextIncarnation launches)
      contexts = warmPool incarnation lane (serial + contexts.length) nextIncarnation launches := by
  induction contexts generalizing serial with
  | nil => rfl
  | cons context contexts ih =>
    change warmJobs cap incarnation (serial + 1)
      (warmJob cap (warmPool incarnation lane serial nextIncarnation launches)
        incarnation serial context) contexts = _
    rw [warm_job_reuses_worker, ih]
    have arithmetic : serial + 1 + contexts.length = serial + (contexts.length + 1) := by
      rw [Nat.add_assoc, Nat.add_comm 1 contexts.length]
    rw [arithmetic]
    rfl

theorem sequential_jobs_no_new_launches (cap : Limits) (incarnation : Nat) (lane : Lane)
    (serial nextIncarnation launches : Nat) (contexts : List Nat) :
    (warmJobs cap incarnation serial (warmPool incarnation lane serial nextIncarnation launches)
      contexts).launches = launches := by
  rw [sequential_jobs_reuse_fixed_worker]
  rfl


/-- Busy tickets were issued by this incarnation and strictly precede the next serial. -/
def WorkerValid (w : Worker) : Prop :=
  match w.phase with
  | .starting => True
  | .idle => True
  | .busy ticket => ticket.incarnation = w.incarnation ∧ ticket.serial < w.nextTicket
  | .stopping => True

def WorkersValid : List Worker → Prop
  | [] => True
  | w :: ws => WorkerValid w ∧ WorkersValid ws

private theorem workerStep_valid (w : Worker) (command : WorkerCommand)
    (h : WorkerValid w) : WorkerValid (workerStep w command) := by
  cases w with
  | mk incarnation lane nextTicket phase =>
    cases command <;> cases phase <;>
      try (first | exact h | exact True.intro | exact ⟨rfl, Nat.lt_succ_self _⟩)
    dsimp only [workerStep]
    split
    · exact True.intro
    · exact h

private theorem alter_valid (incarnation : Nat) (command : WorkerCommand)
    (ws : List Worker) (h : WorkersValid ws) : WorkersValid (alter incarnation command ws) := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih =>
    change WorkerValid (if w.incarnation = incarnation then _ else _) ∧ _
    refine ⟨?_, ih h.2⟩
    split
    · exact workerStep_valid w command h.1
    · exact h.1

private theorem reap_valid (incarnation : Nat) (ws : List Worker)
    (h : WorkersValid ws) : WorkersValid (reap incarnation ws) := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih =>
    unfold reap
    split
    · exact ih h.2
    · exact ⟨h.1, ih h.2⟩

private theorem poolStep_valid (cap : Limits) (p : Pool) (command : PoolCommand)
    (h : WorkersValid p.workers) : WorkersValid (poolStep cap p command).workers := by
  cases command with
  | reserve lane =>
    change WorkersValid (if owns p.nextIncarnation (processCharges p.workers) then p else
      if fits cap (processCharges (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers))
      then Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).workers
    split
    · exact h
    · split
      · exact ⟨True.intro, h⟩
      · exact h
  | worker incarnation command => exact alter_valid incarnation command p.workers h
  | reaped incarnation => exact reap_valid incarnation p.workers h

theorem reachable_valid_tickets (cap : Limits) (p : Pool) (h : PoolReachable cap p) :
    WorkersValid p.workers := by
  induction h with
  | empty => exact True.intro
  | step reached command ih => exact poolStep_valid cap _ command ih

def Before (next : Nat) : List Worker → Prop
  | [] => True
  | w :: ws => w.incarnation < next ∧ Before next ws

private theorem before_mono (a b : Nat) (ws : List Worker) (le : a ≤ b)
    (h : Before a ws) : Before b ws := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih => exact ⟨Nat.lt_of_lt_of_le h.1 le, ih h.2⟩

private theorem alter_before (next incarnation : Nat) (command : WorkerCommand)
    (ws : List Worker) (h : Before next ws) : Before next (alter incarnation command ws) := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih =>
    change (if w.incarnation = incarnation then workerStep w command else w).incarnation < next ∧ _
    refine ⟨?_, ih h.2⟩
    split
    · rw [(workerStep_owner w command).1]
      exact h.1
    · exact h.1

private theorem reap_before (next incarnation : Nat) (ws : List Worker)
    (h : Before next ws) : Before next (reap incarnation ws) := by
  induction ws with
  | nil => exact True.intro
  | cons w ws ih =>
    unfold reap
    split
    · exact ih h.2
    · exact ⟨h.1, ih h.2⟩

private theorem poolStep_before (cap : Limits) (p : Pool) (command : PoolCommand)
    (h : Before p.nextIncarnation p.workers) :
    Before (poolStep cap p command).nextIncarnation (poolStep cap p command).workers := by
  cases command with
  | reserve lane =>
    change Before (if owns p.nextIncarnation (processCharges p.workers) then p else
      if fits cap (processCharges (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers))
      then Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).nextIncarnation
      (if owns p.nextIncarnation (processCharges p.workers) then p else
      if fits cap (processCharges (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers))
      then Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).workers
    split
    · exact h
    · split
      · exact ⟨Nat.lt_succ_self _, before_mono _ _ _ (Nat.le_succ _) h⟩
      · exact h
  | worker incarnation command => exact alter_before _ incarnation command p.workers h
  | reaped incarnation => exact reap_before _ incarnation p.workers h

theorem reachable_fresh_incarnation (cap : Limits) (p : Pool) (h : PoolReachable cap p) :
    Before p.nextIncarnation p.workers := by
  induction h with
  | empty => exact True.intro
  | step reached command ih => exact poolStep_before cap _ command ih

private theorem total_partition (weight : Charge → Nat) (cs : Ledger) :
    total weight cs =
      total (fun c => if c.lane = .feedback then weight c else 0) cs +
      (total (fun c => if c.lane = .behavior then weight c else 0) cs +
       total (fun c => if c.lane = .admin then weight c else 0) cs) := by
  induction cs with
  | nil => rfl
  | cons c cs ih =>
    cases c with
    | mk owner lane byteSize =>
      cases lane with
      | feedback =>
        change weight _ + total weight cs = (weight _ + _) + ((0 + _) + (0 + _))
        rw [ih, Nat.zero_add, Nat.zero_add, Nat.add_assoc]
      | behavior =>
        change weight _ + total weight cs = (0 + _) + ((weight _ + _) + (0 + _))
        rw [ih, Nat.zero_add, Nat.zero_add, Nat.add_assoc, Nat.add_left_comm]
      | admin =>
        change weight _ + total weight cs = (0 + _) + ((0 + _) + (weight _ + _))
        rw [ih, Nat.zero_add, Nat.zero_add, Nat.add_left_comm, Nat.add_left_comm (weight _)]

theorem global_count_is_lane_sum (cs : Ledger) :
    count cs = laneCount .feedback cs + (laneCount .behavior cs + laneCount .admin cs) :=
  total_partition (fun _ => 1) cs

theorem global_bytes_is_lane_sum (cs : Ledger) :
    bytes cs = laneBytes .feedback cs + (laneBytes .behavior cs + laneBytes .admin cs) :=
  total_partition Charge.bytes cs


/-- Execute a finite event trace. Events are the same transition used by reachability. -/
def poolReplay (cap : Limits) (p : Pool) : List PoolCommand → Pool
  | [] => p
  | command :: commands => poolReplay cap (poolStep cap p command) commands

theorem poolReplay_reachable (cap : Limits) (p : Pool) (commands : List PoolCommand)
    (h : PoolReachable cap p) : PoolReachable cap (poolReplay cap p commands) := by
  induction commands generalizing p with
  | nil => exact h
  | cons command commands ih => exact ih _ (PoolReachable.step h command)

theorem warmJobs_reachable (cap : Limits) (p : Pool) (incarnation serial : Nat)
    (contexts : List Nat) (h : PoolReachable cap p) :
    PoolReachable cap (warmJobs cap incarnation serial p contexts) := by
  induction contexts generalizing p serial with
  | nil => exact h
  | cons context contexts ih =>
    exact ih _ _ (PoolReachable.step (PoolReachable.step h (.worker incarnation (.start context)))
      (.worker incarnation (.complete ⟨incarnation, serial, context⟩)))

/-- A concrete finite profile for adversarial replay, not a production profile. -/
def witnessLimits : Limits := ⟨1, 0, fun _ => 1, fun _ => 0⟩

def prewarmedWitness : Pool :=
  poolReplay witnessLimits emptyPool [.reserve .feedback, .worker 0 .launch]

theorem prewarmedWitness_reachable : PoolReachable witnessLimits prewarmedWitness :=
  poolReplay_reachable _ _ _ PoolReachable.empty

theorem prewarmedWitness_state : prewarmedWitness = warmPool 0 .feedback 0 1 1 := rfl

/-- W01 broken policy: each caller retains its stale observation and launches later. -/
def launchOnStaleObservation (observedFree : Bool) (live : Nat) : Nat :=
  if observedFree then live + 1 else live

def w01BadReplay : Nat :=
  let observedA := decide (0 < 1)
  let observedB := decide (0 < 1)
  launchOnStaleObservation observedB (launchOnStaleObservation observedA 0)

def w01FixedReplay : Pool :=
  poolReplay witnessLimits emptyPool [.reserve .feedback, .reserve .feedback]

theorem w01_atomic_reservation_witness :
    w01BadReplay = 2 ∧ 1 < w01BadReplay ∧
    count (processCharges w01FixedReplay.workers) = 1 ∧
    w01FixedReplay.launches = 0 ∧
    PoolReachable witnessLimits w01FixedReplay :=
  ⟨rfl, by decide, rfl, rfl, poolReplay_reachable _ _ _ PoolReachable.empty⟩

/-- W02 broken policy keeps OS-live processes separate from prematurely returned permits. -/
structure EarlyReleaseState where
  permits : Nat
  live : Nat
  deriving DecidableEq

def earlyReleaseStart (cap : Nat) (s : EarlyReleaseState) : EarlyReleaseState :=
  if s.permits < cap then ⟨s.permits + 1, s.live + 1⟩ else s

def earlyReleaseCancel (s : EarlyReleaseState) : EarlyReleaseState :=
  ⟨s.permits - 1, s.live⟩

def w02BadReplay : EarlyReleaseState :=
  earlyReleaseStart 1 (earlyReleaseCancel (earlyReleaseStart 1 ⟨0, 0⟩))

def w02FixedReplay : Pool :=
  poolReplay witnessLimits prewarmedWitness
    [.worker 0 (.start 40), .worker 0 .stop, .reserve .feedback]

def w02ReapedReplay : Pool :=
  poolReplay witnessLimits w02FixedReplay [.reaped 0, .reserve .feedback]

theorem w02_cancel_does_not_release_witness :
    w02BadReplay.live = 2 ∧ w02BadReplay.permits = 1 ∧
    w02FixedReplay.workers = [⟨0, .feedback, 1, .stopping⟩] ∧
    w02FixedReplay.nextIncarnation = 1 ∧
    w02ReapedReplay.workers = [⟨1, .feedback, 0, .starting⟩] ∧
    PoolReachable witnessLimits w02ReapedReplay :=
  ⟨rfl, rfl, rfl, rfl, rfl,
    poolReplay_reachable _ _ _ (poolReplay_reachable _ _ _ prewarmedWitness_reachable)⟩

/-- W06 broken policy checks only the serial and loses incarnation identity. -/
def serialOnlyAccept (expected received : Ticket) : Bool := decide (expected.serial = received.serial)

def sevenWarmContexts : List Nat := [10, 11, 12, 13, 14, 15, 16]

def w06OldBusy : Pool :=
  poolStep witnessLimits (warmJobs witnessLimits 0 0 prewarmedWitness sevenWarmContexts)
    (.worker 0 (.start 77))

def w06Replacement : Pool :=
  poolReplay witnessLimits w06OldBusy
    [.worker 0 .stop, .reaped 0, .reserve .feedback, .worker 1 .launch]

def w06NewBusy : Pool :=
  poolStep witnessLimits (warmJobs witnessLimits 1 0 w06Replacement sevenWarmContexts)
    (.worker 1 (.start 77))

def w06LateFrameReplay : Pool :=
  poolStep witnessLimits w06NewBusy (.worker 1 (.complete ⟨0, 7, 77⟩))

private theorem w06OldBusy_state :
    w06OldBusy = ⟨[⟨0, .feedback, 8, .busy ⟨0, 7, 77⟩⟩], 1, 1⟩ := by
  unfold w06OldBusy
  rw [prewarmedWitness_state, sequential_jobs_reuse_fixed_worker]
  rfl

private theorem w06Replacement_state : w06Replacement = warmPool 1 .feedback 0 2 2 := by
  unfold w06Replacement
  rw [w06OldBusy_state]
  rfl

private theorem w06NewBusy_state :
    w06NewBusy = ⟨[⟨1, .feedback, 8, .busy ⟨1, 7, 77⟩⟩], 2, 2⟩ := by
  unfold w06NewBusy
  rw [w06Replacement_state, sequential_jobs_reuse_fixed_worker]
  rfl

theorem w06_incarnation_ticket_witness :
    serialOnlyAccept ⟨1, 7, 77⟩ ⟨0, 7, 77⟩ = true ∧
    w06LateFrameReplay = w06NewBusy ∧
    w06NewBusy.workers = [⟨1, .feedback, 8, .busy ⟨1, 7, 77⟩⟩] ∧
    PoolReachable witnessLimits w06LateFrameReplay := by
  refine ⟨rfl, ?_, ?_, ?_⟩
  · unfold w06LateFrameReplay
    rw [w06NewBusy_state]
    rfl
  · rw [w06NewBusy_state]
  · apply PoolReachable.step
    apply PoolReachable.step
    apply warmJobs_reachable
    apply poolReplay_reachable
    apply PoolReachable.step
    exact warmJobs_reachable _ _ _ _ _ prewarmedWitness_reachable

/-- W14 bad edit policy retires and prewarms on every edit, using legal safe events. -/
def recycleEdit (p : Pool) (incarnation : Nat) : Pool :=
  poolReplay witnessLimits p [.worker incarnation .stop, .reaped incarnation,
    .reserve .feedback, .worker p.nextIncarnation .launch]

def w14BadReplay : Pool :=
  recycleEdit (recycleEdit (recycleEdit prewarmedWitness 0) 1) 2

def w14WarmReplay : Pool :=
  warmJobs witnessLimits 0 0 prewarmedWitness [40, 41, 42]

theorem w14_recycle_each_edit_witness :
    w14BadReplay.launches = 4 ∧ w14WarmReplay.launches = 1 ∧
    w14WarmReplay.workers = [⟨0, .feedback, 3, .idle⟩] ∧
    PoolReachable witnessLimits w14BadReplay ∧
    PoolReachable witnessLimits w14WarmReplay := by
  refine ⟨rfl, rfl, rfl, ?_, warmJobs_reachable _ _ _ _ _ prewarmedWitness_reachable⟩
  apply poolReplay_reachable
  apply poolReplay_reachable
  exact poolReplay_reachable _ _ _ prewarmedWitness_reachable


/-- Limitation replay: externally reusing a generic ledger owner permits a stale release.
This does not refute its bounds or simultaneous uniqueness; a runtime producer
must supply fresh lifecycle owners (as the separate process pool does). -/
def reusedOwnerLedger : Ledger :=
  acquire witnessLimits ⟨7, .feedback, 0⟩
    (release 7 (acquire witnessLimits ⟨7, .feedback, 0⟩ []))

theorem owner_reuse_breach :
    count reusedOwnerLedger = 1 ∧ count (release 7 reusedOwnerLedger) = 0 := ⟨rfl, rfl⟩

/-- W12 is only an abstract fatal/ordinary error classification counterexample.
It says nothing about which Java Throwable values the runtime actually catches. -/
def errorOutcome (fatal : Bool) (w : Worker) (ticket : Ticket) : Worker :=
  if fatal then workerStep w .stop else workerStep w (.complete ticket)

def poisonedWitness : Worker := ⟨0, .feedback, 1, .busy ⟨0, 0, 55⟩⟩

theorem w12_fatal_requires_retirement_witness :
    (workerStep (errorOutcome false poisonedWitness ⟨0, 0, 55⟩) (.start 56)).phase =
      .busy ⟨0, 1, 56⟩ ∧
    (workerStep (errorOutcome true poisonedWitness ⟨0, 0, 55⟩) (.start 56)).phase =
      .stopping := ⟨rfl, rfl⟩


/-- A nonmatching or nonstopping record survives any reap acknowledgement. -/
theorem reap_retains_other_worker (incarnation : Nat) (w : Worker) (ws : List Worker)
    (member : w ∈ ws) (keep : ¬ (w.incarnation = incarnation ∧ w.phase = .stopping)) :
    w ∈ reap incarnation ws := by
  induction ws with
  | nil => cases member
  | cons head tail ih =>
    cases member with
    | head =>
      change w ∈ (if w.incarnation = incarnation ∧ w.phase = .stopping then _ else _)
      rw [ite_eq_right keep]
      exact List.Mem.head _
    | tail _ member =>
      unfold reap
      split
      · exact ih member
      · exact List.Mem.tail _ (ih member)

theorem release_requires_matching_reaped_owner (incarnation : Nat) (w : Worker)
    (ws : List Worker) (member : w ∈ ws) (removed : ¬ w ∈ reap incarnation ws) :
    w.incarnation = incarnation ∧ w.phase = .stopping := by
  by_cases matching : w.incarnation = incarnation ∧ w.phase = .stopping
  · exact matching
  · exact False.elim (removed (reap_retains_other_worker incarnation w ws member matching))

theorem reap_idempotent (incarnation : Nat) (ws : List Worker) :
    reap incarnation (reap incarnation ws) = reap incarnation ws := by
  induction ws with
  | nil => rfl
  | cons w ws ih =>
    by_cases matching : w.incarnation = incarnation ∧ w.phase = .stopping
    · have one : reap incarnation (w :: ws) = reap incarnation ws := by
        change (if _ then _ else _) = _
        exact ite_eq_left matching
      rw [one]
      exact ih
    · have one : reap incarnation (w :: ws) = w :: reap incarnation ws := by
        change (if _ then _ else _) = _
        exact ite_eq_right matching
      rw [one]
      change (if _ then _ else _) = _
      rw [ite_eq_right matching, ih]

theorem busy_worker_cannot_start_second_task (w : Worker) (ticket : Ticket) (context : Nat)
    (busy : w.phase = .busy ticket) : workerStep w (.start context) = w := by
  unfold workerStep
  rw [busy]

theorem start_issues_fresh_ticket (w : Worker) (context : Nat) (idle : w.phase = .idle) :
    (workerStep w (.start context)).phase = .busy ⟨w.incarnation, w.nextTicket, context⟩ ∧
    (workerStep w (.start context)).nextTicket = w.nextTicket + 1 := by
  unfold workerStep
  rw [idle]
  exact ⟨rfl, rfl⟩

theorem worker_serial_never_decreases (w : Worker) (command : WorkerCommand) :
    w.nextTicket ≤ (workerStep w command).nextTicket := by
  cases w with
  | mk incarnation lane nextTicket phase =>
    cases command <;> cases phase <;>
      try (first | exact Nat.le_refl _ | exact Nat.le_succ _)
    dsimp only [workerStep]
    split <;> exact Nat.le_refl _

theorem pool_incarnation_never_decreases (cap : Limits) (p : Pool) (command : PoolCommand) :
    p.nextIncarnation ≤ (poolStep cap p command).nextIncarnation := by
  cases command with
  | reserve lane =>
    change p.nextIncarnation ≤ (if owns p.nextIncarnation (processCharges p.workers) then p else
      if fits cap (processCharges (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers))
      then Pool.mk (⟨p.nextIncarnation, lane, 0, .starting⟩ :: p.workers)
        (p.nextIncarnation + 1) p.launches else p).nextIncarnation
    split
    · exact Nat.le_refl _
    · split
      · exact Nat.le_succ _
      · exact Nat.le_refl _
  | worker incarnation command => exact Nat.le_refl _
  | reaped incarnation => exact Nat.le_refl _

theorem replay_incarnation_never_decreases (cap : Limits) (p : Pool)
    (commands : List PoolCommand) : p.nextIncarnation ≤ (poolReplay cap p commands).nextIncarnation := by
  induction commands generalizing p with
  | nil => exact Nat.le_refl _
  | cons command commands ih =>
    exact Nat.le_trans (pool_incarnation_never_decreases cap p command) (ih _)


/-- The portion owned by one lifecycle, measured by any nonnegative resource weight. -/
def ownerTotal (owner : Nat) (weight : Charge → Nat) : Ledger → Nat
  | [] => 0
  | c :: cs => if c.owner = owner then weight c + ownerTotal owner weight cs
      else ownerTotal owner weight cs

theorem release_resource_conservation (owner : Nat) (weight : Charge → Nat) (cs : Ledger) :
    total weight (release owner cs) + ownerTotal owner weight cs = total weight cs := by
  induction cs with
  | nil => rfl
  | cons c cs ih =>
    by_cases same : c.owner = owner
    · have released : release owner (c :: cs) = release owner cs := by
        change (if _ then _ else _) = _
        exact ite_eq_left same
      have owned : ownerTotal owner weight (c :: cs) = weight c + ownerTotal owner weight cs := by
        change (if _ then _ else _) = _
        exact ite_eq_left same
      rw [released, owned]
      change total weight (release owner cs) + (weight c + ownerTotal owner weight cs) = _
      rw [Nat.add_left_comm, ih]
      rfl
    · have released : release owner (c :: cs) = c :: release owner cs := by
        change (if _ then _ else _) = _
        exact ite_eq_right same
      have owned : ownerTotal owner weight (c :: cs) = ownerTotal owner weight cs := by
        change (if _ then _ else _) = _
        exact ite_eq_right same
      rw [released, owned]
      change (weight c + total weight (release owner cs)) + ownerTotal owner weight cs = _
      rw [Nat.add_assoc, ih]
      rfl

end AlloyStudio.Traffic.Resources







