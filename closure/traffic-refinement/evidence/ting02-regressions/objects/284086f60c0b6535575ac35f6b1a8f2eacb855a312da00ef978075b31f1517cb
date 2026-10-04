import Std

/-!
TB01: constructive finite reuse policy, independent of the Java evaluator.
Keys are structural values, never hashes. Byte strings are lists of octets.
A pending key owns one computation; `running` marks dispatch of that owner.
All operations below are single atomic model events. Their correspondence to
Python locks, Java execution, capability issuance and browser async capture is
not established here. `eval` is an arbitrary independently supplied mathematical
function: cache transparency is universal in it, not warm/fresh JVM equivalence.
-/
namespace AlloyStudio.Traffic.Reuse

/- Locally proved list facts avoid the propositional-extensionality dependencies
   of convenience lemmas in the standard library. -/
def memberBool {α : Type} (same : α → α → Bool) (x : α) (xs : List α) : Bool :=
  xs.foldr (fun y rest => if same x y then true else rest) false

theorem memberBool_false {α : Type} [DecidableEq α] (x : α) (xs : List α)
    (h : (memberBool (fun a b => decide (a = b))) x xs = false) : x ∉ xs := by
  induction xs with
  | nil => intro impossible; cases impossible
  | cons y ys ih =>
    change (if decide (x = y) then true else (memberBool (fun a b => decide (a = b))) x ys) = false at h
    split at h
    next _ => cases h
    next hne =>
      intro hm
      cases hm with
      | head => exact hne (decide_eq_true rfl)
      | tail _ hm => exact ih h hm

def retain {α : Type} : List α → (α → Bool) → List α
  | [], _ => []
  | x :: xs, p => if p x then x :: retain xs p else retain xs p

theorem filter_mem {α : Type} (p : α → Bool) (xs : List α) (x : α)
    (h : x ∈ retain xs p) : x ∈ xs := by
  induction xs with
  | nil => cases h
  | cons y ys ih =>
    change x ∈ (if p y then y :: retain ys p else retain ys p) at h
    split at h
    next _ =>
      cases h with
      | head => exact .head _
      | tail _ h => exact .tail _ (ih h)
    next _ => exact .tail _ (ih h)

theorem filter_length {α : Type} (p : α → Bool) (xs : List α) :
    (retain xs p).length ≤ xs.length := by
  induction xs with
  | nil => exact Nat.le_refl _
  | cons x xs ih =>
    change (if p x then x :: retain xs p else retain xs p).length ≤ xs.length + 1
    split
    next _ => exact Nat.succ_le_succ ih
    next _ => exact Nat.le_trans ih (Nat.le_succ _)

theorem map_length {α β : Type} (f : α → β) (xs : List α) :
    (xs.map f).length = xs.length := by
  induction xs with
  | nil => rfl
  | cons x xs ih => exact congrArg Nat.succ ih

theorem nodup_filter {α : Type} (p : α → Bool) (xs : List α)
    (h : xs.Nodup) : (retain xs p).Nodup := by
  induction xs with
  | nil => exact .nil
  | cons x xs ih =>
    cases h with
    | cons hx ht =>
      change (if p x then x :: retain xs p else retain xs p).Nodup
      split
      next _ => exact .cons (fun y hy => hx y (filter_mem p xs y hy)) (ih ht)
      next _ => exact ih ht

abbrev Bytes := List UInt8

inductive Metric where
  | canonical | ast
  deriving DecidableEq

inductive Kind where
  | structural (metric : Metric)
  | behavior
  deriving DecidableEq

structure Context where
  service : Nat
  snapshot : Nat
  exercise : Nat
  raw : Bytes
  environment : Bytes
  predicate : Bytes
  orderedPool : List Bytes
  primaryOracle : Bytes
  engine : Bytes
  dependency : Bytes
  rules : Bytes
  projectionPolicy : Nat
  solverBounds : List Nat
  samplingPolicy : Nat
  deriving DecidableEq

structure Key where
  context : Context
  kind : Kind
  options : List Nat
  deriving DecidableEq

def structuralKey (c : Context) (m : Metric) (options : List Nat) : Key :=
  ⟨c, .structural m, options⟩

def behaviorKey (c : Context) (_selectedMetric : Metric) (options : List Nat) : Key :=
  ⟨c, .behavior, options⟩

theorem exact_context (a b : Key) (h : a = b) : a.context = b.context :=
  congrArg Key.context h

theorem exact_raw (a b : Key) (h : a = b) : a.context.raw = b.context.raw :=
  congrArg (fun k : Key => k.context.raw) h

theorem exact_snapshot (a b : Key) (h : a = b) :
    a.context.snapshot = b.context.snapshot :=
  congrArg (fun k : Key => k.context.snapshot) h

theorem exact_ordered_pool (a b : Key) (h : a = b) :
    a.context.orderedPool = b.context.orderedPool :=
  congrArg (fun k : Key => k.context.orderedPool) h

theorem exact_kind_options (a b : Key) (h : a = b) :
    a.kind = b.kind ∧ a.options = b.options :=
  ⟨congrArg Key.kind h, congrArg Key.options h⟩

theorem behavior_metric_independent (c : Context) (m n : Metric) (o : List Nat) :
    behaviorKey c m o = behaviorKey c n o := rfl

theorem structural_metric_distinct (c : Context) (o : List Nat) :
    structuralKey c .canonical o ≠ structuralKey c .ast o := by
  intro h
  have hk := congrArg Key.kind h
  cases hk

structure Delivery where
  channel : Nat
  subscriber : Nat
  selection : Nat
  revision : Nat
  metric : Metric
  snapshot : Nat
  raw : Bytes
  deriving DecidableEq

def deliveryMatches (k : Key) (d : Delivery) : Prop :=
  d.raw = k.context.raw ∧ d.snapshot = k.context.snapshot ∧
  d.selection = k.context.exercise ∧
  match k.kind with
  | .structural m => d.metric = m
  | .behavior => True

instance (k : Key) (d : Delivery) : Decidable (deliveryMatches k d) := by
  unfold deliveryMatches
  cases k.kind <;> infer_instance

/- Results contain no subscriber identity; each envelope attaches it separately. -/
structure Observation where
  distance : Nat
  trace : List Nat
  locations : List Nat
  diagnostics : Bytes
  behaviorEvidence : List Nat
  deriving DecidableEq

structure Subscriber where
  key : Key
  delivery : Delivery
  pinned : Option Observation
  deriving DecidableEq

structure Entry where
  key : Key
  value : Observation
  deriving DecidableEq

structure Envelope where
  delivery : Delivery
  value : Observation
  deriving DecidableEq

structure Limits where
  jobs : Nat
  subscribers : Nat
  cache : Nat
  deriving DecidableEq

structure State where
  jobs : List Key := []
  running : List Key := []
  subscribers : List Subscriber := []
  cache : List Entry := []
  deriving DecidableEq

def initial : State := {}

def cached : List Entry → Key → Option Observation
  | [], _ => none
  | e :: rest, k => if e.key = k then some e.value else cached rest k

/- Admission first checks the global subscriber limit, including cache hits.
   Cache lookup, in-flight lookup and insertion are one operation. -/
def acceptRequest (lim : Limits) (s : State) (k : Key) (d : Delivery) : State :=
  if s.subscribers.length < lim.subscribers ∧ deliveryMatches k d then
    match cached s.cache k with
    | some v => { s with subscribers := ⟨k, d, some v⟩ :: s.subscribers }
    | none =>
      if (memberBool (fun a b => decide (a = b))) k s.jobs = true then
        { s with subscribers := ⟨k, d, none⟩ :: s.subscribers }
      else if s.jobs.length < lim.jobs then
        { s with jobs := k :: s.jobs, subscribers := ⟨k, d, none⟩ :: s.subscribers }
      else s
  else s

def hasSubscriber (s : State) (k : Key) : Bool :=
  (memberBool (fun a b => decide (a = b))) k (s.subscribers.map Subscriber.key)

/- Empty queued owners are reclaimed; running owners remain until completion.
   Detaching a subscriber cannot discard a job with another subscriber. -/
def pruneQueued (s : State) : State :=
  { s with jobs := retain s.jobs (fun k => (memberBool (fun a b => decide (a = b))) k s.running || hasSubscriber s k) }

/- A capability selects a channel. This model proves scoping given that value;
   randomness, expiry and caller authentication belong to the implementation. -/
def cancel (s : State) (channel subscriber : Nat) : State :=
  pruneQueued { s with subscribers := retain s.subscribers (fun x =>
      if x.delivery.channel = channel ∧ x.delivery.subscriber = subscriber then false else true) }

def supersede (s : State) (channel : Nat) : State :=
  pruneQueued { s with subscribers := retain s.subscribers (fun x => if x.delivery.channel = channel then false else true) }

def dispatch (s : State) (k : Key) : State :=
  if (memberBool (fun a b => decide (a = b))) k s.jobs = true ∧ (memberBool (fun a b => decide (a = b))) k s.running = false ∧ hasSubscriber s k = true then
    { s with running := k :: s.running }
  else s

/- Successful model completion computes the independent evaluator. No frame or
   worker is silently assumed to implement this function. Terminal failures
   use the separate `fail` transition and are not inserted as successes. -/
def complete (eval : Key → Observation) (lim : Limits) (s : State) (k : Key) : State :=
  if (memberBool (fun a b => decide (a = b))) k s.running = true then
    { jobs := retain s.jobs (fun x => x != k)
      running := retain s.running (fun x => x != k)
      subscribers := s.subscribers.map (fun x =>
        if x.key = k then { x with pinned := some (eval k) } else x)
      cache := if s.cache.length < lim.cache then ⟨k, eval k⟩ :: s.cache else s.cache }
  else s

/- A terminal evaluator failure releases the computation owner, never a worker
   process reservation. The latter needs the independent stop/reap protocol.
   No unsuccessful observation enters the successful-result cache. -/
def fail (s : State) (k : Key) : State :=
  if (memberBool (fun a b => decide (a = b))) k s.running = true then
    { s with jobs := retain s.jobs (fun x => x != k)
             running := retain s.running (fun x => x != k)
             subscribers := retain s.subscribers (fun x =>
               if x.key = k then x.pinned.isSome else true) }
  else s

def evict (s : State) (k : Key) : State :=
  { s with cache := retain s.cache (fun x => x.key != k) }

def downstreamEnabled (s : State) (x : Subscriber) : Bool :=
  (memberBool (fun a b => decide (a = b))) x s.subscribers

def envelope (x : Subscriber) : Option Envelope :=
  x.pinned.map (fun value => ⟨x.delivery, value⟩)

/- Equality checks the full captured identity: exact bytes, snapshot, metric,
   revision, selection, subscriber and server-issued channel. -/
def browserAccept (current : Delivery) (e : Envelope) : Bool :=
  decide (e.delivery = current)

inductive Step (eval : Key → Observation) (lim : Limits) : State → State → Prop where
  | admission (s k d) : Step eval lim s (acceptRequest lim s k d)
  | cancellation (s channel subscriber) : Step eval lim s (cancel s channel subscriber)
  | supersession (s channel) : Step eval lim s (supersede s channel)
  | dispatching (s k) : Step eval lim s (dispatch s k)
  | completion (s k) : Step eval lim s (complete eval lim s k)
  | failure (s k) : Step eval lim s (fail s k)
  | eviction (s k) : Step eval lim s (evict s k)

inductive Reachable (eval : Key → Observation) (lim : Limits) : State → Prop where
  | initial : Reachable eval lim initial
  | next {s t} : Reachable eval lim s → Step eval lim s t → Reachable eval lim t

def UniqueJobs (s : State) : Prop := s.jobs.Nodup

def Bounded (lim : Limits) (s : State) : Prop :=
  s.jobs.length ≤ lim.jobs ∧ s.subscribers.length ≤ lim.subscribers ∧
  s.cache.length ≤ lim.cache

def CacheSound (eval : Key → Observation) (s : State) : Prop :=
  ∀ e ∈ s.cache, e.value = eval e.key

def PinnedSound (eval : Key → Observation) (s : State) : Prop :=
  ∀ x ∈ s.subscribers, ∀ v, x.pinned = some v → v = eval x.key

theorem cached_sound (eval : Key → Observation) (entries : List Entry)
    (h : ∀ e ∈ entries, e.value = eval e.key) (k : Key) (v : Observation)
    (hit : cached entries k = some v) : v = eval k := by
  induction entries with
  | nil => cases hit
  | cons e rest ih =>
    change (if e.key = k then some e.value else cached rest k) = some v at hit
    split at hit
    next he =>
      have hv : e.value = v := Option.some.inj hit
      exact hv ▸ he ▸ h e (List.mem_cons_self)
    next _ =>
      exact ih (fun e he => h e (List.mem_cons_of_mem _ he)) hit

theorem admit_unique (lim : Limits) (s : State) (k : Key) (d : Delivery)
    (h : UniqueJobs s) : UniqueJobs (acceptRequest lim s k d) := by
  unfold acceptRequest
  split
  next _ =>
    split
    next _ _ => exact h
    next _ =>
      split
      next _ => exact h
      next hmissing =>
        split
        next _ =>
          have hm : (memberBool (fun a b => decide (a = b))) k s.jobs = false := Bool.eq_false_iff.mpr hmissing
          exact .cons (fun x hx hk => memberBool_false k s.jobs hm (hk ▸ hx)) h
        next _ => exact h
  next _ => exact h

theorem step_unique (eval : Key → Observation) (lim : Limits) {s t : State}
    (h : UniqueJobs s) (step : Step eval lim s t) : UniqueJobs t := by
  cases step with
  | admission k d => exact admit_unique lim s k d h
  | cancellation => exact nodup_filter _ _ h
  | supersession => exact nodup_filter _ _ h
  | dispatching k => unfold dispatch; split <;> exact h
  | completion k =>
    unfold complete
    split
    next _ => exact nodup_filter _ _ h
    next _ => exact h
  | failure k =>
    unfold fail
    split
    next _ => exact nodup_filter _ _ h
    next _ => exact h
  | eviction => exact h

theorem one_computation_per_key (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : UniqueJobs s := by
  induction h with
  | initial => exact List.Pairwise.nil
  | next _ step ih => exact step_unique eval lim ih step

theorem admit_bounded (lim : Limits) (s : State) (k : Key) (d : Delivery)
    (h : Bounded lim s) : Bounded lim (acceptRequest lim s k d) := by
  rcases h with ⟨hj, hs, hc⟩
  unfold acceptRequest
  split
  next hspace =>
    split
    next _ _ => exact ⟨hj, Nat.succ_le_of_lt hspace.1, hc⟩
    next _ =>
      split
      next _ => exact ⟨hj, Nat.succ_le_of_lt hspace.1, hc⟩
      next _ =>
        split
        next hjob => exact ⟨Nat.succ_le_of_lt hjob, Nat.succ_le_of_lt hspace.1, hc⟩
        next _ => exact ⟨hj, hs, hc⟩
  next _ => exact ⟨hj, hs, hc⟩

theorem step_bounded (eval : Key → Observation) (lim : Limits) {s t : State}
    (h : Bounded lim s) (step : Step eval lim s t) : Bounded lim t := by
  rcases h with ⟨hj, hs, hc⟩
  cases step with
  | admission k d => exact admit_bounded lim s k d ⟨hj, hs, hc⟩
  | cancellation channel subscriber =>
    exact ⟨Nat.le_trans (filter_length _ _) hj, Nat.le_trans (filter_length _ _) hs, hc⟩
  | supersession channel =>
    exact ⟨Nat.le_trans (filter_length _ _) hj, Nat.le_trans (filter_length _ _) hs, hc⟩
  | dispatching k => unfold dispatch; split <;> exact ⟨hj, hs, hc⟩
  | completion k =>
    unfold complete
    split
    next _ =>
      refine ⟨Nat.le_trans (filter_length _ _) hj, ?_, ?_⟩
      · simpa only [map_length] using hs
      · split
        next hspace => exact Nat.succ_le_of_lt hspace
        next _ => exact hc
    next _ => exact ⟨hj, hs, hc⟩
  | failure k =>
    unfold fail
    split
    next _ => exact ⟨Nat.le_trans (filter_length _ _) hj,
      Nat.le_trans (filter_length _ _) hs, hc⟩
    next _ => exact ⟨hj, hs, hc⟩
  | eviction k => exact ⟨hj, hs, Nat.le_trans (filter_length _ _) hc⟩

theorem reachable_bounds (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : Bounded lim s := by
  induction h with
  | initial => exact ⟨Nat.zero_le _, Nat.zero_le _, Nat.zero_le _⟩
  | next _ step ih => exact step_bounded eval lim ih step

theorem map_mem {α β : Type} (f : α → β) (xs : List α) (y : β)
    (h : y ∈ xs.map f) : ∃ x ∈ xs, f x = y := by
  induction xs with
  | nil => cases h
  | cons x xs ih =>
    cases h with
    | head => exact ⟨x, .head _, rfl⟩
    | tail _ h =>
      obtain ⟨a, ha, he⟩ := ih h
      exact ⟨a, .tail _ ha, he⟩

theorem admit_sound (eval : Key → Observation) (lim : Limits) (s : State)
    (k : Key) (d : Delivery) (hc : CacheSound eval s) (hp : PinnedSound eval s) :
    CacheSound eval (acceptRequest lim s k d) ∧ PinnedSound eval (acceptRequest lim s k d) := by
  unfold acceptRequest
  split
  next _ =>
    split
    next v hit =>
      refine ⟨hc, ?_⟩
      intro x hx w hw
      cases hx with
      | head =>
        have he : v = w := Option.some.inj hw
        exact he ▸ cached_sound eval s.cache hc k v hit
      | tail _ hx => exact hp x hx w hw
    next _ =>
      split
      next _ =>
        refine ⟨hc, ?_⟩
        intro x hx w hw
        cases hx with
        | head => cases hw
        | tail _ hx => exact hp x hx w hw
      next _ =>
        split
        next _ =>
          refine ⟨hc, ?_⟩
          intro x hx w hw
          cases hx with
          | head => cases hw
          | tail _ hx => exact hp x hx w hw
        next _ => exact ⟨hc, hp⟩
  next _ => exact ⟨hc, hp⟩

theorem step_sound (eval : Key → Observation) (lim : Limits) {s t : State}
    (hc : CacheSound eval s) (hp : PinnedSound eval s) (step : Step eval lim s t) :
    CacheSound eval t ∧ PinnedSound eval t := by
  cases step with
  | admission k d => exact admit_sound eval lim s k d hc hp
  | cancellation channel subscriber =>
    exact ⟨hc, fun x hx v hv => hp x (filter_mem _ _ _ hx) v hv⟩
  | supersession channel =>
    exact ⟨hc, fun x hx v hv => hp x (filter_mem _ _ _ hx) v hv⟩
  | dispatching k => unfold dispatch; split <;> exact ⟨hc, hp⟩
  | completion k =>
    unfold complete
    split
    next _ =>
      constructor
      · intro e he
        split at he
        next _ =>
          cases he with
          | head => rfl
          | tail _ he => exact hc e he
        next _ => exact hc e he
      · intro x hx v hv
        obtain ⟨old, hold, hx⟩ := map_mem _ _ _ hx
        split at hx
        next he =>
          cases hx
          have hv' : eval k = v := Option.some.inj hv
          exact hv' ▸ he ▸ rfl
        next _ =>
          cases hx
          exact hp x hold v hv
    next _ => exact ⟨hc, hp⟩
  | failure k =>
    unfold fail
    split
    next _ => exact ⟨hc, fun x hx v hv => hp x (filter_mem _ _ _ hx) v hv⟩
    next _ => exact ⟨hc, hp⟩
  | eviction k => exact ⟨fun e he => hc e (filter_mem _ _ _ he), hp⟩

theorem reachable_cache_and_pins (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : CacheSound eval s ∧ PinnedSound eval s := by
  induction h with
  | initial =>
    constructor
    · intro e he; cases he
    · intro x hx; cases hx
  | next _ step ih => exact step_sound eval lim ih.1 ih.2 step

theorem cache_hit_observation (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) (k : Key) (v : Observation)
    (hit : cached s.cache k = some v) : v = eval k :=
  cached_sound eval s.cache (reachable_cache_and_pins eval lim s h).1 k v hit

theorem pinned_observation (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) (x : Subscriber) (hx : x ∈ s.subscribers)
    (v : Observation) (pin : x.pinned = some v) : v = eval x.key :=
  (reachable_cache_and_pins eval lim s h).2 x hx v pin

theorem delivery_identity (x : Subscriber) (e : Envelope) (h : envelope x = some e) :
    e.delivery = x.delivery := by
  unfold envelope at h
  cases hp : x.pinned with
  | none => rw [hp] at h; cases h
  | some v =>
    rw [hp] at h
    have he : (⟨x.delivery, v⟩ : Envelope) = e := Option.some.inj h
    exact congrArg Envelope.delivery he.symm

theorem publish_only_current (current : Delivery) (e : Envelope)
    (h : browserAccept current e = true) : e.delivery = current :=
  of_decide_eq_true h

theorem eviction_preserves_pins (s : State) (k : Key) :
    (evict s k).subscribers = s.subscribers := rfl

theorem cancellation_preserves_computation (s : State) (channel subscriber : Nat) :
    (cancel s channel subscriber).running = s.running := rfl

theorem retain_kept {α : Type} (p : α → Bool) (xs : List α) (x : α)
    (hx : x ∈ xs) (hp : p x = true) : x ∈ retain xs p := by
  induction xs with
  | nil => cases hx
  | cons y ys ih =>
    change x ∈ (if p y then y :: retain ys p else retain ys p)
    cases hx with
    | head => rw [hp]; exact .head _
    | tail _ hx =>
      split
      next _ => exact .tail _ (ih hx)
      next _ => exact ih hx

theorem cancel_other_channel (s : State) (channel subscriber : Nat) (x : Subscriber)
    (hx : x ∈ s.subscribers) (different : x.delivery.channel ≠ channel) :
    x ∈ (cancel s channel subscriber).subscribers := by
  apply retain_kept _ _ _ hx
  change (if x.delivery.channel = channel ∧ x.delivery.subscriber = subscriber then false else true) = true
  split
  next h => exact False.elim (different h.1)
  next _ => rfl

theorem cancel_other_subscriber (s : State) (channel subscriber : Nat) (x : Subscriber)
    (hx : x ∈ s.subscribers) (different : x.delivery.subscriber ≠ subscriber) :
    x ∈ (cancel s channel subscriber).subscribers := by
  apply retain_kept _ _ _ hx
  change (if x.delivery.channel = channel ∧ x.delivery.subscriber = subscriber then false else true) = true
  split
  next h => exact False.elim (different h.2)
  next _ => rfl

theorem retain_condition {α : Type} (p : α → Bool) (xs : List α) (x : α)
    (hx : x ∈ retain xs p) : p x = true := by
  induction xs with
  | nil => cases hx
  | cons y ys ih =>
    change x ∈ (if p y then y :: retain ys p else retain ys p) at hx
    split at hx
    next hy =>
      cases hx with
      | head => exact hy
      | tail _ hx => exact ih hx
    next _ => exact ih hx

theorem superseded_removed (s : State) (channel : Nat) (x : Subscriber)
    (hx : x.delivery.channel = channel) : x ∉ (supersede s channel).subscribers := by
  intro hm
  have hc := retain_condition _ _ x hm
  change (if x.delivery.channel = channel then false else true) = true at hc
  rw [ite_eq_left hx] at hc
  cases hc

theorem memberBool_true {α : Type} [DecidableEq α] (x : α) (xs : List α)
    (h : (memberBool (fun a b => decide (a = b))) x xs = true) : x ∈ xs := by
  induction xs with
  | nil => cases h
  | cons y ys ih =>
    change (if decide (x = y) then true else (memberBool (fun a b => decide (a = b))) x ys) = true at h
    split at h
    next he => cases of_decide_eq_true he; exact .head _
    next _ => exact .tail _ (ih h)

theorem no_obsolete_downstream (s : State) (channel : Nat) (x : Subscriber)
    (hx : x.delivery.channel = channel) :
    downstreamEnabled (supersede s channel) x = false := by
  cases hb : downstreamEnabled (supersede s channel) x with
  | false => rfl
  | true => exact False.elim (superseded_removed s channel x hx (memberBool_true _ _ hb))

/- A latest request is accepted only after the explicit supersession event.
   These events can be combined by an implementation under its scheduler lock.
   No round-robin fairness or pre-notification dispatch guarantee is claimed. -/
def latestAdmission (lim : Limits) (s : State) (k : Key) (d : Delivery) : State :=
  acceptRequest lim (supersede s d.channel) k d

theorem latestAdmission_reachable (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) (k : Key) (d : Delivery) :
    Reachable eval lim (latestAdmission lim s k d) :=
  .next (.next h (.supersession s d.channel)) (.admission _ k d)

theorem memberBool_of_mem {α : Type} [DecidableEq α] (x : α) (xs : List α)
    (h : x ∈ xs) : (memberBool (fun a b => decide (a = b))) x xs = true := by
  induction xs with
  | nil => cases h
  | cons y ys ih =>
    change (if decide (x = y) then true else (memberBool (fun a b => decide (a = b))) x ys) = true
    split
    next _ => rfl
    next hne =>
      cases h with
      | head => exact False.elim (hne (decide_eq_true rfl))
      | tail _ h => exact ih h

theorem map_mem_of_mem {α β : Type} (f : α → β) (xs : List α) (x : α)
    (h : x ∈ xs) : f x ∈ xs.map f := by
  induction xs with
  | nil => cases h
  | cons y ys ih =>
    cases h with
    | head => exact .head _
    | tail _ h => exact .tail _ (ih h)

theorem subscriber_enables (s : State) (x : Subscriber) (h : x ∈ s.subscribers) :
    hasSubscriber s x.key = true :=
  memberBool_of_mem _ _ (map_mem_of_mem _ _ _ h)

theorem prune_keeps_subscribed (s : State) (x : Subscriber)
    (hx : x ∈ s.subscribers) (hk : x.key ∈ s.jobs) :
    x.key ∈ (pruneQueued s).jobs := by
  apply retain_kept _ _ _ hk
  change ((memberBool (fun a b => decide (a = b))) x.key s.running || hasSubscriber s x.key) = true
  have he := congrArg (fun b => (memberBool (fun a b => decide (a = b))) x.key s.running || b) (subscriber_enables s x hx)
  exact he.trans (by cases (memberBool (fun a b => decide (a = b))) x.key s.running <;> rfl)

theorem cancel_other_keeps_job (s : State) (channel subscriber : Nat) (x : Subscriber)
    (hx : x ∈ s.subscribers) (hk : x.key ∈ s.jobs)
    (different : x.delivery.channel ≠ channel) :
    x.key ∈ (cancel s channel subscriber).jobs := by
  have kept := cancel_other_channel s channel subscriber x hx different
  exact prune_keeps_subscribed _ x kept hk

def RunningOwned (s : State) : Prop :=
  s.running.Nodup ∧ ∀ k ∈ s.running, k ∈ s.jobs

theorem prune_owns (s : State) (h : RunningOwned s) : RunningOwned (pruneQueued s) := by
  refine ⟨h.1, ?_⟩
  intro k hk
  apply retain_kept _ _ _ (h.2 k hk)
  change ((memberBool (fun a b => decide (a = b))) k s.running || hasSubscriber s k) = true
  exact congrArg (fun b => b || hasSubscriber s k) (memberBool_of_mem k s.running hk)

theorem admit_owns (lim : Limits) (s : State) (k : Key) (d : Delivery)
    (h : RunningOwned s) : RunningOwned (acceptRequest lim s k d) := by
  unfold acceptRequest
  split
  next _ =>
    split
    next _ _ => exact h
    next _ =>
      split
      next _ => exact h
      next _ =>
        split
        next _ => exact ⟨h.1, fun x hx => .tail _ (h.2 x hx)⟩
        next _ => exact h
  next _ => exact h

theorem step_owns (eval : Key → Observation) (lim : Limits) {s t : State}
    (h : RunningOwned s) (step : Step eval lim s t) : RunningOwned t := by
  cases step with
  | admission k d => exact admit_owns lim s k d h
  | cancellation channel subscriber => exact prune_owns _ h
  | supersession channel => exact prune_owns _ h
  | dispatching k =>
    unfold dispatch
    split
    next hg =>
      refine ⟨.cons (fun x hx he => memberBool_false k s.running hg.2.1 (he ▸ hx)) h.1, ?_⟩
      intro x hx
      cases hx with
      | head => exact memberBool_true k s.jobs hg.1
      | tail _ hx => exact h.2 x hx
    next _ => exact h
  | completion k =>
    unfold complete
    split
    next _ =>
      refine ⟨nodup_filter _ _ h.1, ?_⟩
      intro x hx
      exact retain_kept (fun a : Key => a != k) s.jobs x
        (h.2 x (filter_mem _ _ _ hx)) (retain_condition (fun a : Key => a != k) s.running x hx)
    next _ => exact h
  | failure k =>
    unfold fail
    split
    next _ =>
      refine ⟨nodup_filter _ _ h.1, ?_⟩
      intro x hx
      exact retain_kept (fun a : Key => a != k) s.jobs x
        (h.2 x (filter_mem _ _ _ hx)) (retain_condition (fun a : Key => a != k) s.running x hx)
    next _ => exact h
  | eviction k => exact h

theorem reachable_running_owners (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : RunningOwned s := by
  induction h with
  | initial => exact ⟨.nil, fun _ hx => nomatch hx⟩
  | next _ step ih => exact step_owns eval lim ih step

theorem filter_length_strict {α : Type} (p : α → Bool) (xs : List α) (x : α)
    (hx : x ∈ xs) (hp : p x = false) : (retain xs p).length < xs.length := by
  induction xs with
  | nil => cases hx
  | cons y ys ih =>
    change (if p y then y :: retain ys p else retain ys p).length < ys.length + 1
    cases hx with
    | head =>
      rw [hp]
      exact Nat.lt_succ_of_le (filter_length p ys)
    | tail _ hx =>
      split
      next _ => exact Nat.succ_lt_succ (ih hx)
      next _ => exact Nat.lt_succ_of_le (filter_length p ys)

theorem nodup_subset_length {α : Type} [DecidableEq α] (xs ys : List α)
    (hn : xs.Nodup) (hsub : ∀ x ∈ xs, x ∈ ys) : xs.length ≤ ys.length := by
  induction xs generalizing ys with
  | nil => exact Nat.zero_le _
  | cons x xs ih =>
    cases hn with
    | cons hx hn =>
      let p := fun y : α => if x = y then false else true
      have htail : ∀ y ∈ xs, y ∈ retain ys p := by
        intro y hy
        apply retain_kept _ _ _ (hsub y (.tail _ hy))
        exact ite_eq_right (hx y hy)
      have hb := ih (retain ys p) hn htail
      have hp : p x = false := ite_eq_left rfl
      have hs := filter_length_strict p ys x (hsub x (.head _)) hp
      exact Nat.le_trans (Nat.succ_le_succ hb) hs

theorem running_count_le_jobs (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : s.running.length ≤ s.jobs.length := by
  have ho := reachable_running_owners eval lim s h
  exact nodup_subset_length s.running s.jobs ho.1 ho.2

theorem running_count_bounded (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : s.running.length ≤ lim.jobs :=
  Nat.le_trans (running_count_le_jobs eval lim s h) (reachable_bounds eval lim s h).1

def SubscribersBoundToKeys (s : State) : Prop :=
  ∀ x ∈ s.subscribers, deliveryMatches x.key x.delivery

theorem admit_binding (lim : Limits) (s : State) (k : Key) (d : Delivery)
    (h : SubscribersBoundToKeys s) : SubscribersBoundToKeys (acceptRequest lim s k d) := by
  unfold acceptRequest
  split
  next hg =>
    split
    next _ _ =>
      intro x hx
      cases hx with
      | head => exact hg.2
      | tail _ hx => exact h x hx
    next _ =>
      split
      next _ =>
        intro x hx
        cases hx with
        | head => exact hg.2
        | tail _ hx => exact h x hx
      next _ =>
        split
        next _ =>
          intro x hx
          cases hx with
          | head => exact hg.2
          | tail _ hx => exact h x hx
        next _ => exact h
  next _ => exact h

theorem step_binding (eval : Key → Observation) (lim : Limits) {s t : State}
    (h : SubscribersBoundToKeys s) (step : Step eval lim s t) : SubscribersBoundToKeys t := by
  cases step with
  | admission k d => exact admit_binding lim s k d h
  | cancellation channel subscriber => exact fun x hx => h x (filter_mem _ _ _ hx)
  | supersession channel => exact fun x hx => h x (filter_mem _ _ _ hx)
  | dispatching k => unfold dispatch; split <;> exact h
  | completion k =>
    unfold complete
    split
    next _ =>
      intro x hx
      obtain ⟨old, ho, he⟩ := map_mem _ _ _ hx
      split at he
      next _ => cases he; exact h old ho
      next _ => cases he; exact h x ho
    next _ => exact h
  | failure k =>
    unfold fail
    split
    next _ => exact fun x hx => h x (filter_mem _ _ _ hx)
    next _ => exact h
  | eviction k => exact h

theorem reachable_subscriber_binding (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) : SubscribersBoundToKeys s := by
  induction h with
  | initial => exact fun _ hx => nomatch hx
  | next _ step ih => exact step_binding eval lim ih step

theorem delivered_context_current (eval : Key → Observation) (lim : Limits)
    (s : State) (h : Reachable eval lim s) (x : Subscriber) (hx : x ∈ s.subscribers)
    (e : Envelope) (he : envelope x = some e) (current : Delivery)
    (accept : browserAccept current e = true) : deliveryMatches x.key current := by
  have hd : e.delivery = x.delivery := delivery_identity x e he
  have hc : e.delivery = current := publish_only_current current e accept
  have same : x.delivery = current := hd.symm.trans hc
  exact same ▸ reachable_subscriber_binding eval lim s h x hx

theorem dispatch_requires_subscriber (s : State) (k : Key)
    (changed : dispatch s k ≠ s) : k ∈ s.jobs ∧ ∃ x ∈ s.subscribers, x.key = k := by
  unfold dispatch at changed
  split at changed
  next hg =>
    exact ⟨memberBool_true k s.jobs hg.1,
      map_mem Subscriber.key s.subscribers k (memberBool_true _ _ hg.2.2)⟩
  next _ => exact False.elim (changed rfl)

theorem joining_keeps_computation_owner (lim : Limits) (s : State) (k : Key) (d : Delivery)
    (present : k ∈ s.jobs) : (acceptRequest lim s k d).jobs = s.jobs := by
  unfold acceptRequest
  split
  next _ =>
    split
    next _ _ => rfl
    next _ =>
      have hp := memberBool_of_mem k s.jobs present
      split
      next _ => rfl
      next hn => exact False.elim (hn hp)
  next _ => rfl

theorem latest_channel_identity (lim : Limits) (s : State) (k : Key) (d : Delivery) :
    ∀ x ∈ (latestAdmission lim s k d).subscribers,
      x.delivery.channel = d.channel → x.key = k ∧ x.delivery = d := by
  intro x hx hc
  unfold latestAdmission acceptRequest at hx
  split at hx
  next _ =>
    split at hx
    next _ _ =>
      cases hx with
      | head => exact ⟨rfl, rfl⟩
      | tail _ hx => exact False.elim (superseded_removed s d.channel x hc hx)
    next _ =>
      split at hx
      next _ =>
          cases hx with
        | head => exact ⟨rfl, rfl⟩
        | tail _ hx => exact False.elim (superseded_removed s d.channel x hc hx)
      next _ =>
        split at hx
        next _ =>
              cases hx with
          | head => exact ⟨rfl, rfl⟩
          | tail _ hx => exact False.elim (superseded_removed s d.channel x hc hx)
        next _ =>
              exact False.elim (superseded_removed s d.channel x hc hx)
  next _ =>
    exact False.elim (superseded_removed s d.channel x hc hx)

theorem failure_never_caches (s : State) (k : Key) : (fail s k).cache = s.cache := by
  unfold fail
  split <;> rfl

/- Only mathematical model examples follow. Octets are explicit UTF-8 ASCII
   values; these examples do not certify the production UTF-8 decoder. -/
def context0 : Context :=
  ⟨1, 0, 7, [115, 111, 109, 101, 32, 65], [101], [112], [[65]], [65],
   [106], [100], [114], 1, [4, 4, 3], 1⟩

def key0 : Key := structuralKey context0 .canonical []
def key1 : Key := structuralKey { context0 with raw := [66] } .canonical []
def deliveryA : Delivery := ⟨10, 1, 7, 4, .canonical, 0, context0.raw⟩
def deliveryB : Delivery := ⟨20, 2, 7, 9, .canonical, 0, context0.raw⟩
def exampleLimits : Limits := ⟨2, 2, 1⟩
def exampleEval (k : Key) : Observation :=
  ⟨k.context.raw.length, [0], [(k.context.raw.takeWhile (fun b => b == 32)).length], [], [1, 2]⟩

def joined : State := acceptRequest exampleLimits (acceptRequest exampleLimits initial key0 deliveryA) key0 deliveryB

theorem W03_join_one_job : joined.jobs = [key0] ∧ joined.subscribers.length = 2 := by decide

theorem W03_cancel_preserves_B :
    (cancel joined 10 1).jobs = [key0] ∧
    (cancel joined 10 1).subscribers = [⟨key0, deliveryB, none⟩] := by decide

theorem W03_last_queued_cancel_reclaims :
    (cancel (cancel joined 10 1) 20 2).jobs = [] := by decide

def joinedRunning : State := dispatch joined key0

theorem W03_running_not_killed :
    (cancel (cancel joinedRunning 10 1) 20 2).running = [key0] ∧
    (cancel (cancel joinedRunning 10 1) 20 2).jobs = [key0] := by decide

def naiveCancelShared (s : State) (k : Key) : State :=
  { s with jobs := retain s.jobs (fun x => x != k)
           running := retain s.running (fun x => x != k) }

theorem W03_naive_cancel_breach :
    (naiveCancelShared joined key0).jobs = [] ∧
    (naiveCancelShared joined key0).subscribers.length = 2 := by decide

def paddedContext : Context := { context0 with raw := [32, 32, 115, 111, 109, 101, 32, 65] }
def paddedKey : Key := structuralKey paddedContext .canonical []
def naiveNormalizedKey (k : Key) : Key :=
  { k with context := { k.context with raw := k.context.raw.dropWhile (fun b => b == 32) } }

theorem W04_normalization_breach :
    naiveNormalizedKey key0 = naiveNormalizedKey paddedKey ∧ key0 ≠ paddedKey ∧
    (exampleEval key0).locations = [0] ∧
    (exampleEval paddedKey).locations = [2] := by decide

def nextGeneration : Key :=
  { key0 with context := { context0 with snapshot := 1, orderedPool := [[66]] } }

def naiveGenerationKey (k : Key) : Nat × Bytes := (k.context.exercise, k.context.raw)

theorem W05_generation_breach :
    naiveGenerationKey key0 = naiveGenerationKey nextGeneration ∧ key0 ≠ nextGeneration := by decide

theorem W05_old_cache_not_new_generation :
    cached [⟨key0, exampleEval key0⟩] nextGeneration = none := by decide

def naiveMetricKey (c : Context) (_m : Metric) : Context := c

theorem metric_omission_breach :
    naiveMetricKey context0 .canonical = naiveMetricKey context0 .ast ∧
    structuralKey context0 .canonical [] ≠ structuralKey context0 .ast [] := by decide

/- Subscriber capacity is global: inventing channel IDs cannot increase it. -/
def deliveryC : Delivery := { deliveryB with channel := 10000, subscriber := 10000, raw := key1.context.raw }

theorem W08_new_channel_identity_valid : deliveryMatches key1 deliveryC := by decide

theorem W08_new_channel_rejected_at_global_cap :
    acceptRequest exampleLimits joined key1 deliveryC = joined := by decide

def completed : State := complete exampleEval exampleLimits joinedRunning key0

theorem W11_eviction_keeps_evidence :
    (evict completed key0).cache = [] ∧
    (evict completed key0).jobs = [] ∧
    (evict completed key0).subscribers =
      [⟨key0, deliveryB, some (exampleEval key0)⟩,
       ⟨key0, deliveryA, some (exampleEval key0)⟩] := by decide

/- Values, not just field names, determine approved disclosure. -/
structure RawDiagnostic where
  approvedMessage : Bytes
  privateReference : Bytes
  deriving DecidableEq

def approvedProjection (r : RawDiagnostic) : Bytes := r.approvedMessage

def naiveAllowedFieldProjection (r : RawDiagnostic) : Bytes := r.privateReference

def diagnosticWitness : RawDiagnostic := ⟨[79, 75], [83, 69, 67, 82, 69, 84]⟩

theorem W16_field_allowlist_breach :
    naiveAllowedFieldProjection diagnosticWitness = diagnosticWitness.privateReference ∧
    naiveAllowedFieldProjection diagnosticWitness ≠ approvedProjection diagnosticWitness := by decide

def editedDelivery : Delivery := { deliveryA with revision := 5, raw := [66] }
def oldSubscriber : Subscriber := ⟨key0, deliveryA, none⟩
def serverBeforeNotification : State := acceptRequest exampleLimits initial key0 deliveryA

theorem W18_async_boundary :
    downstreamEnabled serverBeforeNotification oldSubscriber = true ∧
    browserAccept editedDelivery ⟨deliveryA, exampleEval key0⟩ = false ∧
    downstreamEnabled (supersede serverBeforeNotification 10) oldSubscriber = false := by decide

/- Tiny independent complete scan. It does not formalize Alloy preparation. -/
def scanRest (best : Nat) : List (Option Nat) → Option Nat
  | [] => some best
  | none :: _ => none
  | some n :: rest => scanRest (min best n) rest

def fullScan : List (Option Nat) → Option Nat
  | [] => some 0
  | none :: _ => none
  | some n :: rest => scanRest n rest

def naiveStopAtZero : List (Option Nat) → Option Nat
  | [] => some 0
  | none :: _ => none
  | some n :: rest => if n = 0 then some 0 else naiveStopAtZero rest

theorem W10_late_malformed_breach :
    fullScan [some 0, none] = none ∧ naiveStopAtZero [some 0, none] = some 0 := by decide

theorem terminal_failure_not_cached :
    (fail joinedRunning key0).jobs = [] ∧
    (fail joinedRunning key0).running = [] ∧
    (fail joinedRunning key0).subscribers = [] ∧
    (fail joinedRunning key0).cache = [] := by decide

theorem mismatched_delivery_rejected :
    acceptRequest exampleLimits initial key1 deliveryA = initial := by decide

end AlloyStudio.Traffic.Reuse
