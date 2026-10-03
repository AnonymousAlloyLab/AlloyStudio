import Std

/-!
TB01: constructive ingress policy model. Naturals are exact, discrete logical
clock ticks and token units; one tick authorizes exactly `rate` units. There is
no fractional rounding or machine overflow in this model. Headers and JSON
fields below are parsed syntax, not a claim about a concrete byte decoder.
Allocation plans are commands returned only after length checks, not allocations
performed by Lean. Runtime parser, listener, clock, buffer, and transport
correspondence remain separate obligations.
-/
namespace AlloyStudio.Traffic.Ingress

/-- Saturation uses only constructive comparison on naturals. -/
def saturate (cap available : Nat) : Nat :=
  if available ≤ cap then available else cap

theorem saturate_le_cap (cap available : Nat) : saturate cap available ≤ cap := by
  unfold saturate
  split
  · assumption
  · exact Nat.le_refl _

theorem saturate_le_available (cap available : Nat) : saturate cap available ≤ available := by
  unfold saturate
  split
  · exact Nat.le_refl _
  · rename_i tooLarge
    exact Nat.le_of_not_ge tooLarge

theorem add_elapsed_eq (before after : Nat) (forward : before ≤ after) :
    before + (after - before) = after := by
  induction before generalizing after with
  | zero => rw [Nat.zero_add, Nat.sub_zero]
  | succ before ih =>
    cases after with
    | zero => exact False.elim (Nat.not_succ_le_zero before forward)
    | succ after =>
      rw [Nat.succ_sub_succ_eq_sub]
      change before.succ + (after - before) = after.succ
      rw [Nat.succ_add, ih after (Nat.le_of_succ_le_succ forward)]

theorem sum_rates_mul (a b t : Nat) : (a + b) * t = a * t + b * t := by
  rw [Nat.mul_comm (a + b), Nat.mul_add, Nat.mul_comm t a, Nat.mul_comm t b]

structure BucketProfile where
  burst : Nat
  rate : Nat
  deriving DecidableEq

structure Bucket where
  credit : Nat
  now : Nat
  admitted : Nat
  authorized : Nat
  deriving DecidableEq

def initialBucket (p : BucketProfile) : Bucket := ⟨p.burst, 0, 0, 0⟩

inductive BucketEvent where
  | tick (now : Nat)
  | charge
  | reject
  deriving DecidableEq

/-- Admission charges one credit on every call; the model has no cache-hit or
retry exemption. A failed charge or backwards clock leaves the bucket intact. -/
def bucketStep (p : BucketProfile) (s : Bucket) : BucketEvent → Bucket
  | .tick now =>
    if s.now ≤ now then
      let refill := p.rate * (now - s.now)
      ⟨saturate p.burst (s.credit + refill), now, s.admitted, s.authorized + refill⟩
    else s
  | .charge =>
    match s.credit with
    | 0 => s
    | n + 1 => ⟨n, s.now, s.admitted + 1, s.authorized⟩
  | .reject => s

def BucketSafe (p : BucketProfile) (s : Bucket) : Prop :=
  s.credit ≤ p.burst ∧
  s.admitted + s.credit ≤ p.burst + s.authorized ∧
  s.authorized = p.rate * s.now

inductive BucketReachable (p : BucketProfile) : Bucket → Prop where
  | initial : BucketReachable p (initialBucket p)
  | step {s : Bucket} (reachable : BucketReachable p s) (event : BucketEvent) :
      BucketReachable p (bucketStep p s event)

theorem initial_bucket_safe (p : BucketProfile) : BucketSafe p (initialBucket p) := by
  change p.burst ≤ p.burst ∧ 0 + p.burst ≤ p.burst + 0 ∧ 0 = p.rate * 0
  exact ⟨Nat.le_refl _, by rw [Nat.zero_add, Nat.add_zero]; exact Nat.le_refl _, (Nat.mul_zero _).symm⟩

theorem bucket_step_safe (p : BucketProfile) (s : Bucket) (event : BucketEvent)
    (safe : BucketSafe p s) : BucketSafe p (bucketStep p s event) := by
  obtain ⟨creditBound, conserved, refillEquation⟩ := safe
  cases event with
  | reject => exact ⟨creditBound, conserved, refillEquation⟩
  | tick now =>
    change BucketSafe p (if s.now ≤ now then
      ⟨saturate p.burst (s.credit + p.rate * (now - s.now)), now, s.admitted,
        s.authorized + p.rate * (now - s.now)⟩ else s)
    split
    · rename_i forward
      refine ⟨saturate_le_cap _ _, ?_, ?_⟩
      · change s.admitted + saturate p.burst (s.credit + p.rate * (now - s.now)) ≤ _
        calc
          _ ≤ s.admitted + (s.credit + p.rate * (now - s.now)) :=
            Nat.add_le_add_left (saturate_le_available _ _) _
          _ = (s.admitted + s.credit) + p.rate * (now - s.now) :=
            (Nat.add_assoc _ _ _).symm
          _ ≤ (p.burst + s.authorized) + p.rate * (now - s.now) :=
            Nat.add_le_add_right conserved _
          _ = _ := Nat.add_assoc _ _ _
      · change s.authorized + p.rate * (now - s.now) = p.rate * now
        rw [refillEquation, ← Nat.mul_add, add_elapsed_eq _ _ forward]
    · exact ⟨creditBound, conserved, refillEquation⟩
  | charge =>
    change BucketSafe p (match s.credit with
      | 0 => s
      | n + 1 => ⟨n, s.now, s.admitted + 1, s.authorized⟩)
    cases creditEquation : s.credit with
    | zero => exact ⟨creditBound, conserved, refillEquation⟩
    | succ n =>
      rw [creditEquation] at creditBound conserved
      refine ⟨?_, ?_, refillEquation⟩
      · exact Nat.le_trans (Nat.le_succ n) creditBound
      · change (s.admitted + 1) + n ≤ p.burst + s.authorized
        rw [Nat.add_assoc, Nat.add_comm 1 n]
        exact conserved

theorem reachable_bucket_safe (p : BucketProfile) (s : Bucket)
    (reachable : BucketReachable p s) : BucketSafe p s := by
  induction reachable with
  | initial => exact initial_bucket_safe p
  | step previous event ih => exact bucket_step_safe p _ event ih

theorem admissions_le_budget (p : BucketProfile) (s : Bucket)
    (reachable : BucketReachable p s) : s.admitted ≤ p.burst + p.rate * s.now := by
  have safe := reachable_bucket_safe p s reachable
  have bounded := Nat.le_trans (Nat.le_add_right s.admitted s.credit) safe.2.1
  rw [safe.2.2] at bounded
  exact bounded

theorem backwards_tick_unchanged (p : BucketProfile) (s : Bucket) (now : Nat)
    (backwards : ¬ s.now ≤ now) : bucketStep p s (.tick now) = s := by
  change (if s.now ≤ now then _ else s) = s
  exact ite_eq_right backwards

theorem rejected_request_cannot_mint (p : BucketProfile) (s : Bucket) :
    bucketStep p s .reject = s := rfl

theorem bucket_clock_monotone (p : BucketProfile) (s : Bucket) (event : BucketEvent) :
    s.now ≤ (bucketStep p s event).now := by
  cases event with
  | reject => exact Nat.le_refl _
  | tick now =>
    change s.now ≤ (if s.now ≤ now then
      ⟨saturate p.burst (s.credit + p.rate * (now - s.now)), now, s.admitted,
        s.authorized + p.rate * (now - s.now)⟩ else s).now
    split
    · assumption
    · exact Nat.le_refl _
  | charge =>
    change s.now ≤ (match s.credit with
      | 0 => s
      | n + 1 => ⟨n, s.now, s.admitted + 1, s.authorized⟩).now
    cases s.credit <;> exact Nat.le_refl _

structure Envelope where
  publicProfile : BucketProfile
  controlProfile : BucketProfile
  deriving DecidableEq

structure IngressState where
  publicBucket : Bucket
  controlBucket : Bucket
  deriving DecidableEq

inductive IngressEvent where
  | publicRequest
  | controlRequest
  | tick (now : Nat)
  | reject
  deriving DecidableEq

def initialIngress (p : Envelope) : IngressState :=
  ⟨initialBucket p.publicProfile, initialBucket p.controlProfile⟩

def ingressStep (p : Envelope) (s : IngressState) : IngressEvent → IngressState
  | .publicRequest => ⟨bucketStep p.publicProfile s.publicBucket .charge, s.controlBucket⟩
  | .controlRequest => ⟨s.publicBucket, bucketStep p.controlProfile s.controlBucket .charge⟩
  | .tick now =>
      ⟨bucketStep p.publicProfile s.publicBucket (.tick now),
       bucketStep p.controlProfile s.controlBucket (.tick now)⟩
  | .reject => s

inductive IngressReachable (p : Envelope) : IngressState → Prop where
  | initial : IngressReachable p (initialIngress p)
  | step {s : IngressState} (reachable : IngressReachable p s) (event : IngressEvent) :
      IngressReachable p (ingressStep p s event)

theorem ingress_reachable_components (p : Envelope) (s : IngressState)
    (reachable : IngressReachable p s) :
    BucketReachable p.publicProfile s.publicBucket ∧
    BucketReachable p.controlProfile s.controlBucket := by
  induction reachable with
  | initial => exact ⟨.initial, .initial⟩
  | step previous event ih =>
    cases event with
    | publicRequest => exact ⟨.step ih.1 .charge, ih.2⟩
    | controlRequest => exact ⟨ih.1, .step ih.2 .charge⟩
    | tick now => exact ⟨.step ih.1 (.tick now), .step ih.2 (.tick now)⟩
    | reject => exact ih

theorem combined_admissions_le_envelope (p : Envelope) (s : IngressState) (now : Nat)
    (reachable : IngressReachable p s)
    (publicTime : s.publicBucket.now ≤ now) (controlTime : s.controlBucket.now ≤ now) :
    s.publicBucket.admitted + s.controlBucket.admitted ≤
      (p.publicProfile.burst + p.controlProfile.burst) +
      (p.publicProfile.rate + p.controlProfile.rate) * now := by
  obtain ⟨publicReach, controlReach⟩ := ingress_reachable_components p s reachable
  have publicBound := Nat.le_trans (admissions_le_budget _ _ publicReach)
    (Nat.add_le_add_left (Nat.mul_le_mul_left p.publicProfile.rate publicTime) _)
  have controlBound := Nat.le_trans (admissions_le_budget _ _ controlReach)
    (Nat.add_le_add_left (Nat.mul_le_mul_left p.controlProfile.rate controlTime) _)
  have total := Nat.add_le_add publicBound controlBound
  rw [sum_rates_mul]
  calc
    _ ≤ (p.publicProfile.burst + p.publicProfile.rate * now) +
        (p.controlProfile.burst + p.controlProfile.rate * now) := total
    _ = _ := by
      rw [Nat.add_assoc, ← Nat.add_assoc (p.publicProfile.rate * now),
        Nat.add_comm (p.publicProfile.rate * now) p.controlProfile.burst,
        Nat.add_assoc p.controlProfile.burst, ← Nat.add_assoc]

theorem public_cannot_borrow_control (p : Envelope) (s : IngressState) :
    (ingressStep p s .publicRequest).controlBucket = s.controlBucket := rfl

theorem control_cannot_borrow_public (p : Envelope) (s : IngressState) :
    (ingressStep p s .controlRequest).publicBucket = s.publicBucket := rfl

/-- Fixed absolute expiration is independent of the latest progress time. -/
structure ReadWindow where
  lastProgress : Nat
  absolute : Nat
  idle : Nat
  deriving DecidableEq

def ReadAllowed (w : ReadWindow) (now : Nat) : Prop :=
  w.lastProgress ≤ now ∧ now < w.absolute ∧ now < w.lastProgress + w.idle

instance (w : ReadWindow) (now : Nat) : Decidable (ReadAllowed w now) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _))

def readProgress (w : ReadWindow) (now : Nat) : Option ReadWindow :=
  if ReadAllowed w now then some ⟨now, w.absolute, w.idle⟩ else none

theorem read_progress_preserves_deadline (w next : ReadWindow) (now : Nat)
    (accepted : readProgress w now = some next) :
    next.absolute = w.absolute ∧ now < w.absolute ∧ w.lastProgress ≤ now := by
  unfold readProgress at accepted
  split at accepted
  · rename_i allowed
    have same := Option.some.inj accepted
    cases same
    exact ⟨rfl, allowed.2.1, allowed.1⟩
  · cases accepted

theorem expired_read_rejected (w : ReadWindow) (now : Nat) (expired : w.absolute ≤ now) :
    readProgress w now = none := by
  unfold readProgress
  apply ite_eq_right
  intro allowed
  exact Nat.not_lt_of_ge expired allowed.2.1

/-- An allocation command contains only the already-checked announced size. -/
def planAllocation (cap announced : Nat) : Option Nat :=
  if announced ≤ cap then some announced else none

theorem allocation_is_bounded (cap announced allocated : Nat)
    (accepted : planAllocation cap announced = some allocated) :
    allocated = announced ∧ allocated ≤ cap := by
  unfold planAllocation at accepted
  split at accepted
  · rename_i bounded
    have same := Option.some.inj accepted
    cases same
    exact ⟨rfl, bounded⟩
  · cases accepted

theorem oversized_prefix_rejected (cap announced : Nat) (oversized : ¬ announced ≤ cap) :
    planAllocation cap announced = none := by
  unfold planAllocation
  exact ite_eq_right oversized

inductive ContentEncoding where
  | identity
  | unsupported
  deriving DecidableEq

structure Headers where
  requestLineBytes : Nat
  headerBytes : Nat
  headerCount : Nat
  contentLengths : List (Option Nat)
  transferEncodingPresent : Bool
  encoding : ContentEncoding
  deriving DecidableEq

structure RequestLimits where
  requestLine : Nat
  headers : Nat
  headerCount : Nat
  body : Nat
  nesting : Nat
  deriving DecidableEq

/-- No header, exactly one well-formed Content-Length, and no other shape.
Duplicate equal lengths are rejected just like conflicting lengths. -/
def strictLength : List (Option Nat) → Option Nat
  | [] => some 0
  | first :: rest =>
    match rest with
    | [] => first
    | _ :: _ => none

theorem strict_length_singleton (length : Nat) : strictLength [some length] = some length := rfl

theorem duplicate_content_length_rejected (first second : Option Nat) (rest : List (Option Nat)) :
    strictLength (first :: second :: rest) = none := rfl

def HeaderAllowed (p : RequestLimits) (h : Headers) (w : ReadWindow) (now : Nat) : Prop :=
  h.requestLineBytes ≤ p.requestLine ∧ h.headerBytes ≤ p.headers ∧
  h.headerCount ≤ p.headerCount ∧ h.transferEncodingPresent = false ∧
  h.encoding = .identity ∧ ReadAllowed w now

instance (p : RequestLimits) (h : Headers) (w : ReadWindow) (now : Nat) :
    Decidable (HeaderAllowed p h w now) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _))

/-- Header validation precedes any body allocation command. -/
def planBody (p : RequestLimits) (h : Headers) (w : ReadWindow) (now : Nat) : Option Nat :=
  if HeaderAllowed p h w now then
    match strictLength h.contentLengths with
    | none => none
    | some length => planAllocation p.body length
  else none

theorem body_allocation_validated (p : RequestLimits) (h : Headers)
    (w : ReadWindow) (now length : Nat) (accepted : planBody p h w now = some length) :
    HeaderAllowed p h w now ∧ strictLength h.contentLengths = some length ∧ length ≤ p.body := by
  unfold planBody at accepted
  split at accepted
  · rename_i valid
    cases parsed : strictLength h.contentLengths with
    | none => rw [parsed] at accepted; cases accepted
    | some announced =>
      rw [parsed] at accepted
      have bounded := allocation_is_bounded p.body announced length accepted
      cases bounded.1
      exact ⟨valid, rfl, bounded.2⟩
  · cases accepted

theorem transfer_encoding_rejected (p : RequestLimits) (h : Headers)
    (w : ReadWindow) (now : Nat) (present : h.transferEncodingPresent = true) :
    planBody p h w now = none := by
  unfold planBody
  apply ite_eq_right
  intro valid
  have impossible := present.symm.trans valid.2.2.2.1
  cases impossible

structure DecodedBody where
  bytes : List UInt8
  utf8Valid : Bool
  duplicateKeys : Bool
  knownFields : Bool
  validSchema : Bool
  nesting : Nat
  deriving DecidableEq

def BodyAllowed (p : RequestLimits) (body : DecodedBody) (announced : Nat) : Prop :=
  body.bytes.length = announced ∧ body.utf8Valid = true ∧ body.duplicateKeys = false ∧
  body.knownFields = true ∧ body.validSchema = true ∧ body.nesting ≤ p.nesting

instance (p : RequestLimits) (body : DecodedBody) (announced : Nat) :
    Decidable (BodyAllowed p body announced) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _))

/-- Header and body windows are separate fixed deadlines captured on entry into
their phases. A body cannot dispatch after its phase's absolute expiration. -/
def dispatchRequest (p : RequestLimits) (h : Headers) (headerWindow bodyWindow : ReadWindow)
    (headerTime now : Nat) (body : DecodedBody) : Option DecodedBody :=
  match planBody p h headerWindow headerTime with
  | none => none
  | some announced =>
    if BodyAllowed p body announced ∧ ReadAllowed bodyWindow now then some body else none

theorem dispatched_request_validated (p : RequestLimits) (h : Headers)
    (headerWindow bodyWindow : ReadWindow) (headerTime now : Nat) (body result : DecodedBody)
    (accepted : dispatchRequest p h headerWindow bodyWindow headerTime now body = some result) :
    result = body ∧ HeaderAllowed p h headerWindow headerTime ∧
    strictLength h.contentLengths = some body.bytes.length ∧ body.bytes.length ≤ p.body ∧
    BodyAllowed p body body.bytes.length ∧ now < bodyWindow.absolute := by
  unfold dispatchRequest at accepted
  cases planned : planBody p h headerWindow headerTime with
  | none => rw [planned] at accepted; cases accepted
  | some announced =>
    rw [planned] at accepted
    change (if BodyAllowed p body announced ∧ ReadAllowed bodyWindow now then
      some body else none) = some result at accepted
    split at accepted
    · rename_i valid
      have same := Option.some.inj accepted
      cases same
      have headerValid := body_allocation_validated p h headerWindow headerTime announced planned
      have size := valid.1.1
      cases size
      exact ⟨rfl, headerValid.1, headerValid.2.1, headerValid.2.2, valid.1, valid.2.2.1⟩
    · cases accepted

/-- Parsed forwarding information is consulted only for explicitly trusted peers.
A production bridge must validate the actual socket peer and forwarding chain. -/
def containsPeer : List Nat → Nat → Bool
  | [], _ => false
  | first :: rest, peer => if first == peer then true else containsPeer rest peer

def quotaPeer (trustedPeers : List Nat) (actualPeer : Nat) (validatedForwardedPeer : Option Nat) : Nat :=
  if containsPeer trustedPeers actualPeer = true then validatedForwardedPeer.getD actualPeer else actualPeer

theorem direct_peer_cannot_spoof (trustedPeers : List Nat) (actualPeer : Nat)
    (forwarded : Option Nat) (direct : ¬ containsPeer trustedPeers actualPeer = true) :
    quotaPeer trustedPeers actualPeer forwarded = actualPeer := by
  unfold quotaPeer
  exact ite_eq_right direct

inductive AnalysisKind where
  | feedback
  | behavior
  | explanation
  | administrator
  | other
  deriving DecidableEq

structure Ticket where
  incarnation : Nat
  sequence : Nat
  kind : AnalysisKind
  exactContext : List UInt8
  deriving DecidableEq

structure Message where
  ticket : Ticket
  protocolVersion : Nat
  utf8Valid : Bool
  duplicateKeys : Bool
  knownFields : Bool
  validSchema : Bool
  deriving DecidableEq

structure Frame where
  announced : Nat
  payload : List UInt8
  decoded : Option Message
  deriving DecidableEq

inductive WorkerPhase where
  | idle
  | busy (ticket : Ticket) (deadline : Nat)
  | retired
  deriving DecidableEq

structure FrameResult where
  next : WorkerPhase
  published : Option Message
  deriving DecidableEq

def FrameAllowed (expected : Ticket) (deadline now : Nat) (frame : Frame) (message : Message) : Prop :=
  frame.payload.length = frame.announced ∧ message.ticket = expected ∧
  message.protocolVersion = 1 ∧ message.utf8Valid = true ∧ message.duplicateKeys = false ∧
  message.knownFields = true ∧ message.validSchema = true ∧ now < deadline

instance (expected : Ticket) (deadline now : Nat) (frame : Frame) (message : Message) :
    Decidable (FrameAllowed expected deadline now frame message) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _))

/-- A malformed, truncated, late, oversized, unexpected or duplicate response
retires this worker. Successful consumption removes the busy ticket immediately. -/
def receiveFrame (cap now : Nat) (phase : WorkerPhase) (frame : Frame) : FrameResult :=
  match phase with
  | .idle => ⟨.retired, none⟩
  | .retired => ⟨.retired, none⟩
  | .busy expected deadline =>
    match planAllocation cap frame.announced with
    | none => ⟨.retired, none⟩
    | some _ =>
      match frame.decoded with
      | none => ⟨.retired, none⟩
      | some message =>
        if FrameAllowed expected deadline now frame message then
          ⟨.idle, some message⟩ else ⟨.retired, none⟩

theorem accepted_frame_matches_ticket (cap now : Nat) (phase : WorkerPhase)
    (frame : Frame) (message : Message)
    (accepted : (receiveFrame cap now phase frame).published = some message) :
    ∃ expected deadline, phase = .busy expected deadline ∧
      FrameAllowed expected deadline now frame message ∧ frame.announced ≤ cap ∧
      (receiveFrame cap now phase frame).next = .idle := by
  cases phase with
  | idle => cases accepted
  | retired => cases accepted
  | busy expected deadline =>
    change (match planAllocation cap frame.announced with
      | none => (⟨.retired, none⟩ : FrameResult)
      | some _ => match frame.decoded with
        | none => ⟨.retired, none⟩
        | some decoded => if FrameAllowed expected deadline now frame decoded then
            ⟨.idle, some decoded⟩ else ⟨.retired, none⟩).published = some message at accepted
    cases planned : planAllocation cap frame.announced with
    | none => rw [planned] at accepted; cases accepted
    | some allocated =>
      rw [planned] at accepted
      cases decoded : frame.decoded with
      | none => rw [decoded] at accepted; cases accepted
      | some parsed =>
        rw [decoded] at accepted
        change (if FrameAllowed expected deadline now frame parsed then
          (⟨.idle, some parsed⟩ : FrameResult) else ⟨.retired, none⟩).published = some message at accepted
        by_cases valid : FrameAllowed expected deadline now frame parsed
        · rw [ite_eq_left valid] at accepted
          have same := Option.some.inj accepted
          cases same
          have bounded := allocation_is_bounded cap frame.announced allocated planned
          refine ⟨expected, deadline, rfl, valid, ?_, ?_⟩
          · rw [← bounded.1]
            exact bounded.2
          · change (match planAllocation cap frame.announced with
              | none => (⟨.retired, none⟩ : FrameResult)
              | some _ => match frame.decoded with
                | none => ⟨.retired, none⟩
                | some parsed => if FrameAllowed expected deadline now frame parsed then
                    ⟨.idle, some parsed⟩ else ⟨.retired, none⟩).next = .idle
            rw [planned, decoded]
            change (if FrameAllowed expected deadline now frame message then
              (⟨.idle, some message⟩ : FrameResult) else ⟨.retired, none⟩).next = .idle
            rw [ite_eq_left valid]
        · rw [ite_eq_right valid] at accepted
          cases accepted

theorem frame_completion_at_most_once (cap now : Nat) (phase : WorkerPhase)
    (first second : Frame) (message : Message)
    (accepted : (receiveFrame cap now phase first).published = some message) :
    (receiveFrame cap now (receiveFrame cap now phase first).next second).published = none := by
  obtain ⟨expected, deadline, _, _, _, idle⟩ :=
    accepted_frame_matches_ticket cap now phase first message accepted
  rw [idle]
  rfl

structure RetryIdentity where
  exactBody : List UInt8
  snapshot : Nat
  revision : Nat
  deriving DecidableEq

structure RetryState where
  identity : RetryIdentity
  kind : AnalysisKind
  deadline : Nat
  attempts : Nat
  deriving DecidableEq

inductive Rejection where
  | rateCapacity
  | globalCapacity
  | invalid
  | unsupported
  | analysisTimeout
  | authentication
  | unknownOutcome
  deriving DecidableEq

def learnerOperation : AnalysisKind → Bool
  | .feedback => true
  | .behavior => true
  | .explanation => true
  | .administrator => false
  | .other => false

/-- Capacity constructors mean admission rejected before any dispatch. This is
an explicit semantic interpretation still requiring a concrete HTTP bridge. -/
def capacityRejection : Rejection → Bool
  | .rateCapacity => true
  | .globalCapacity => true
  | .invalid => false
  | .unsupported => false
  | .analysisTimeout => false
  | .authentication => false
  | .unknownOutcome => false

def RetryAllowed (state : RetryState) (current : RetryIdentity) (why : Rejection) (now : Nat) : Prop :=
  learnerOperation state.kind = true ∧ capacityRejection why = true ∧
  state.attempts < 2 ∧ current = state.identity ∧ now < state.deadline

instance (state : RetryState) (current : RetryIdentity) (why : Rejection) (now : Nat) :
    Decidable (RetryAllowed state current why now) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _))

/-- Uses a bucket already advanced by the common ingress clock. Every successful
retry spends one token; exhausted credit produces no retry command. -/
def retry (p : BucketProfile) (bucket : Bucket) (state : RetryState)
    (current : RetryIdentity) (why : Rejection) (now : Nat) : Option (Bucket × RetryState) :=
  if RetryAllowed state current why now ∧ bucket.now = now then
    match bucket.credit with
    | 0 => none
    | _ + 1 => some (bucketStep p bucket .charge,
        ⟨state.identity, state.kind, state.deadline, state.attempts + 1⟩)
  else none

theorem successful_retry_bounded (p : BucketProfile) (bucket nextBucket : Bucket)
    (state next : RetryState) (current : RetryIdentity) (why : Rejection) (now : Nat)
    (accepted : retry p bucket state current why now = some (nextBucket, next)) :
    RetryAllowed state current why now ∧ next.attempts ≤ 2 ∧
    next.identity = state.identity ∧ next.kind = state.kind ∧ next.deadline = state.deadline ∧
    nextBucket = bucketStep p bucket .charge ∧ nextBucket.admitted = bucket.admitted + 1 := by
  unfold retry at accepted
  split at accepted
  · rename_i allowed
    cases credit : bucket.credit with
    | zero => rw [credit] at accepted; cases accepted
    | succ n =>
      rw [credit] at accepted
      have same := Option.some.inj accepted
      have pairFirst := congrArg Prod.fst same
      have pairSecond := congrArg Prod.snd same
      cases pairFirst
      cases pairSecond
      refine ⟨allowed.1, allowed.1.2.2.1, rfl, rfl, rfl, rfl, ?_⟩
      change (match bucket.credit with
        | 0 => bucket
        | k + 1 => ⟨k, bucket.now, bucket.admitted + 1, bucket.authorized⟩).admitted = _
      rw [credit]
  · cases accepted

theorem retry_preserves_rate_reachability (p : BucketProfile) (bucket nextBucket : Bucket)
    (state next : RetryState) (current : RetryIdentity) (why : Rejection) (now : Nat)
    (reachable : BucketReachable p bucket)
    (accepted : retry p bucket state current why now = some (nextBucket, next)) :
    BucketReachable p nextBucket := by
  have properties := successful_retry_bounded p bucket nextBucket state next current why now accepted
  rw [properties.2.2.2.2.2.1]
  exact .step reachable .charge

inductive RetryReachable (p : BucketProfile) (initial : RetryState) : RetryState → Prop where
  | initial : RetryReachable p initial initial
  | step {state next : RetryState} {bucket nextBucket : Bucket} {current : RetryIdentity}
      {why : Rejection} {now : Nat} (reachable : RetryReachable p initial state)
      (accepted : retry p bucket state current why now = some (nextBucket, next)) :
      RetryReachable p initial next

theorem reachable_retries_bounded (p : BucketProfile) (initial state : RetryState)
    (zero : initial.attempts = 0) (reachable : RetryReachable p initial state) :
    state.attempts ≤ 2 ∧ state.deadline = initial.deadline ∧
    state.identity = initial.identity ∧ state.kind = initial.kind := by
  induction reachable with
  | initial =>
    refine ⟨?_, rfl, rfl, rfl⟩
    rw [zero]
    exact Nat.zero_le _
  | step previous accepted ih =>
    have properties := successful_retry_bounded _ _ _ _ _ _ _ _ accepted
    exact ⟨properties.2.1, properties.2.2.2.2.1.trans ih.2.1,
      properties.2.2.1.trans ih.2.2.1, properties.2.2.2.1.trans ih.2.2.2⟩

/-- Reservation precedes a handler-allocation command. `owners` names the
already-reserved handlers on one particular listener, before route parsing. -/
structure HandlerPlan where
  owners : List Nat
  allocateTicket : Nat
  deriving DecidableEq

def reserveHandler (cap : Nat) (owners : List Nat) (ticket : Nat) : Option HandlerPlan :=
  if owners.length < cap ∧ containsPeer owners ticket = false then
    some ⟨ticket :: owners, ticket⟩ else none

theorem handler_allocation_has_reservation (cap : Nat) (owners : List Nat)
    (ticket : Nat) (plan : HandlerPlan) (accepted : reserveHandler cap owners ticket = some plan) :
    plan.owners = ticket :: owners ∧ plan.allocateTicket = ticket ∧
    plan.owners.length ≤ cap ∧ containsPeer owners ticket = false := by
  unfold reserveHandler at accepted
  split at accepted
  · rename_i available
    have same := Option.some.inj accepted
    cases same
    exact ⟨rfl, rfl, available.1, available.2⟩
  · cases accepted

structure ListenerProfile where
  publicCap : Nat
  controlCap : Nat
  deriving DecidableEq

structure HandlerState where
  publicOwners : List Nat
  controlOwners : List Nat
  deriving DecidableEq

inductive HandlerEvent where
  | publicAdmission (ticket : Nat)
  | controlAdmission (ticket : Nat)
  | publicRelease (ticket : Nat)
  | controlRelease (ticket : Nat)
  deriving DecidableEq

def releaseHandler (ticket : Nat) : List Nat → List Nat
  | [] => []
  | owner :: rest =>
    if owner = ticket then rest else owner :: releaseHandler ticket rest

theorem release_handler_length (ticket : Nat) (owners : List Nat) :
    (releaseHandler ticket owners).length ≤ owners.length := by
  induction owners with
  | nil => exact Nat.le_refl _
  | cons owner rest ih =>
    change (if owner = ticket then rest else owner :: releaseHandler ticket rest).length ≤ rest.length + 1
    split
    · exact Nat.le_succ _
    · exact Nat.succ_le_succ ih

/-- Listener identity is known before parsing route headers. Each listener owns
its own reservations; public traffic cannot reserve or release control owners. -/
def handlerStep (p : ListenerProfile) (s : HandlerState) : HandlerEvent → HandlerState
  | .publicAdmission ticket =>
    match reserveHandler p.publicCap s.publicOwners ticket with
    | none => s
    | some plan => ⟨plan.owners, s.controlOwners⟩
  | .controlAdmission ticket =>
    match reserveHandler p.controlCap s.controlOwners ticket with
    | none => s
    | some plan => ⟨s.publicOwners, plan.owners⟩
  | .publicRelease ticket => ⟨releaseHandler ticket s.publicOwners, s.controlOwners⟩
  | .controlRelease ticket => ⟨s.publicOwners, releaseHandler ticket s.controlOwners⟩

def HandlerSafe (p : ListenerProfile) (s : HandlerState) : Prop :=
  s.publicOwners.length ≤ p.publicCap ∧ s.controlOwners.length ≤ p.controlCap

inductive HandlerReachable (p : ListenerProfile) : HandlerState → Prop where
  | initial : HandlerReachable p ⟨[], []⟩
  | step {s : HandlerState} (reachable : HandlerReachable p s) (event : HandlerEvent) :
      HandlerReachable p (handlerStep p s event)

theorem handler_step_safe (p : ListenerProfile) (s : HandlerState) (event : HandlerEvent)
    (safe : HandlerSafe p s) : HandlerSafe p (handlerStep p s event) := by
  cases event with
  | publicAdmission ticket =>
    change HandlerSafe p (match reserveHandler p.publicCap s.publicOwners ticket with
      | none => s
      | some plan => ⟨plan.owners, s.controlOwners⟩)
    cases reservation : reserveHandler p.publicCap s.publicOwners ticket with
    | none => exact safe
    | some plan =>
      exact ⟨(handler_allocation_has_reservation _ _ _ _ reservation).2.2.1, safe.2⟩
  | controlAdmission ticket =>
    change HandlerSafe p (match reserveHandler p.controlCap s.controlOwners ticket with
      | none => s
      | some plan => ⟨s.publicOwners, plan.owners⟩)
    cases reservation : reserveHandler p.controlCap s.controlOwners ticket with
    | none => exact safe
    | some plan =>
      exact ⟨safe.1, (handler_allocation_has_reservation _ _ _ _ reservation).2.2.1⟩
  | publicRelease ticket => exact ⟨Nat.le_trans (release_handler_length _ _) safe.1, safe.2⟩
  | controlRelease ticket => exact ⟨safe.1, Nat.le_trans (release_handler_length _ _) safe.2⟩

theorem reachable_handler_bounds (p : ListenerProfile) (s : HandlerState)
    (reachable : HandlerReachable p s) : HandlerSafe p s := by
  induction reachable with
  | initial => exact ⟨Nat.zero_le _, Nat.zero_le _⟩
  | step previous event ih => exact handler_step_safe p _ event ih

theorem combined_handler_bound (p : ListenerProfile) (s : HandlerState)
    (reachable : HandlerReachable p s) :
    s.publicOwners.length + s.controlOwners.length ≤ p.publicCap + p.controlCap := by
  have safe := reachable_handler_bounds p s reachable
  exact Nat.add_le_add safe.1 safe.2

theorem public_handler_cannot_borrow_control (p : ListenerProfile) (s : HandlerState) (ticket : Nat) :
    (handlerStep p s (.publicAdmission ticket)).controlOwners = s.controlOwners := by
  change (match reserveHandler p.publicCap s.publicOwners ticket with
    | none => s
    | some plan => ⟨plan.owners, s.controlOwners⟩).controlOwners = s.controlOwners
  cases reserveHandler p.publicCap s.publicOwners ticket <;> rfl

inductive EnvName where
  | path
  | locale
  | scratch
  | openAIKey
  | adminSecret
  | javaOptions
  | other
  deriving DecidableEq

structure EnvEntry where
  name : EnvName
  value : List UInt8
  deriving DecidableEq

def environmentNameAllowed : EnvName → Bool
  | .path => true
  | .locale => true
  | .scratch => true
  | .openAIKey => false
  | .adminSecret => false
  | .javaOptions => false
  | .other => false

def launchEnvironment : List EnvEntry → List EnvEntry
  | [] => []
  | entry :: rest =>
    if environmentNameAllowed entry.name = true then entry :: launchEnvironment rest
    else launchEnvironment rest

def EnvironmentAllowed : List EnvEntry → Prop
  | [] => True
  | entry :: rest => environmentNameAllowed entry.name = true ∧ EnvironmentAllowed rest

theorem launch_environment_allowlisted (environment : List EnvEntry) :
    EnvironmentAllowed (launchEnvironment environment) := by
  induction environment with
  | nil => exact True.intro
  | cons entry rest ih =>
    change EnvironmentAllowed (if environmentNameAllowed entry.name = true then
      entry :: launchEnvironment rest else launchEnvironment rest)
    split
    · rename_i allowed
      exact ⟨allowed, ih⟩
    · exact ih

/- Concrete model witnesses. Their rejected command is executable data; these
are not concrete socket/JVM/browser regression executions. -/

def w07Start : ReadWindow := ⟨0, 5, 5⟩
def w07AfterFirstByte : ReadWindow := ⟨4, 5, 5⟩

theorem w07_idle_trickle_would_continue : 8 < w07AfterFirstByte.lastProgress + w07AfterFirstByte.idle := by
  decide

theorem w07_first_byte_then_absolute_rejection :
    readProgress w07Start 4 = some w07AfterFirstByte ∧
    readProgress w07AfterFirstByte 8 = none := by decide

def w09Announced : Nat := 4294967295
def w09Cap : Nat := 1048576

theorem w09_naive_allocation_exceeds_cap : w09Cap < w09Announced := by decide

theorem w09_prefix_rejected_before_allocation : planAllocation w09Cap w09Announced = none := by
  decide

def witnessLimits : RequestLimits := ⟨8192, 16384, 64, 16384, 32⟩
def w13Headers : Headers := ⟨32, 100, 2, [some 9, some 17], false, .identity⟩

theorem w13_conflicting_lengths_rejected :
    strictLength w13Headers.contentLengths = none ∧
    planBody witnessLimits w13Headers ⟨0, 5, 5⟩ 1 = none := by decide

theorem w13_spoofed_forwarding_does_not_change_identity :
    quotaPeer [7] 10 (some 111) = 10 ∧ quotaPeer [7] 10 (some 222) = 10 := by decide

def witnessEnvelope : Envelope := ⟨⟨60, 30⟩, ⟨4, 2⟩⟩

def runPublicRequests (p : Envelope) : Nat → IngressState → IngressState
  | 0, s => s
  | n + 1, s => runPublicRequests p n (ingressStep p s .publicRequest)

theorem run_public_requests_reachable (p : Envelope) (count : Nat) (s : IngressState)
    (reachable : IngressReachable p s) : IngressReachable p (runPublicRequests p count s) := by
  induction count generalizing s with
  | zero => exact reachable
  | succ n ih => exact ih _ (.step reachable .publicRequest)

def w15Saturated : IngressState := runPublicRequests witnessEnvelope 64 (initialIngress witnessEnvelope)

theorem w15_public_saturation_reachable : IngressReachable witnessEnvelope w15Saturated :=
  run_public_requests_reachable witnessEnvelope 64 _ .initial

theorem w15_public_cannot_spend_control_reserve :
    w15Saturated.publicBucket.credit = 0 ∧ w15Saturated.controlBucket.credit = 4 ∧
    (ingressStep witnessEnvelope w15Saturated .controlRequest).controlBucket.credit = 3 ∧
    (ingressStep witnessEnvelope w15Saturated .controlRequest).controlBucket.admitted = 1 := by decide

def runBucketAdmissions (p : BucketProfile) : Nat → Bucket → Bucket
  | 0, s => s
  | n + 1, s => runBucketAdmissions p n (bucketStep p s .charge)

theorem w15_shared_bucket_naively_starves_control :
    (runBucketAdmissions ⟨64, 32⟩ 64 (initialBucket ⟨64, 32⟩)).credit = 0 := by decide

/-- Separate listener ownership is selected before route headers exist. -/
theorem w15_public_handler_saturation_leaves_private_slot :
    handlerStep ⟨2, 2⟩ ⟨[1, 2], []⟩ (.publicAdmission 3) = ⟨[1, 2], []⟩ ∧
    handlerStep ⟨2, 2⟩ ⟨[1, 2], []⟩ (.controlAdmission 3) = ⟨[1, 2], [3]⟩ := by decide

def w17Environment : List EnvEntry :=
  [⟨.openAIKey, [75]⟩, ⟨.locale, [67]⟩, ⟨.javaOptions, [88]⟩]

def naiveRemoveJavaOptions : List EnvEntry → List EnvEntry
  | [] => []
  | entry :: rest =>
    if entry.name = .javaOptions then naiveRemoveJavaOptions rest
    else entry :: naiveRemoveJavaOptions rest

theorem w17_java_option_filter_leaks_key :
    naiveRemoveJavaOptions w17Environment = [⟨.openAIKey, [75]⟩, ⟨.locale, [67]⟩] := by decide

theorem w17_allowlist_removes_key : launchEnvironment w17Environment = [⟨.locale, [67]⟩] := by decide

def witnessTicket : Ticket := ⟨2, 7, .feedback, [65]⟩
def staleTicket : Ticket := ⟨1, 7, .feedback, [65]⟩
def staleMessage : Message := ⟨staleTicket, 1, true, false, true, true⟩

theorem w06_same_sequence_old_incarnation_rejected :
    receiveFrame 1024 1 (.busy witnessTicket 5) ⟨1, [65], some staleMessage⟩ =
      ⟨.retired, none⟩ := by decide

def witnessRetry : RetryState := ⟨⟨[65], 1, 4⟩, .feedback, 10, 0⟩

theorem retry_timeout_and_admin_rejected :
    retry ⟨4, 2⟩ (initialBucket ⟨4, 2⟩) witnessRetry witnessRetry.identity .analysisTimeout 0 = none ∧
    retry ⟨4, 2⟩ (initialBucket ⟨4, 2⟩) { witnessRetry with kind := .administrator }
      witnessRetry.identity .globalCapacity 0 = none := by decide

end AlloyStudio.Traffic.Ingress
