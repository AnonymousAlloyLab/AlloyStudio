import Std


/-!
AP01 proposed patch contracts, not assertions about the current implementation.
The current implementation passes the socket peer to Scheduler.issue_channel;
IIS rewrites API requests to loopback. These contracts require new code and new
implementation correspondence before they can support production claims.

IP values below are canonical numeric addresses supplied by a separate strict
wire parser. That parser must reject ports, zones, empty tokens, invalid syntax,
ambiguous duplicates and noncanonical aliases (including IPv4-mapped aliases),
and must measure actual encoded bytes. Its string-to-atom correspondence is an
explicit bridge obligation; the finite constructors are not a string parser.
Trusted proxies are exact IPs, default empty. Deployment must explicitly list
every trusted immediate/higher proxy, including applicable Cloudflare hops;
neither loopback nor a forwarding header automatically grants proxy trust.
IP is an application channel-quota identity, never a person or authentication
identity. NAT clients may collide. traffic_http.process_request reserves ingress
by raw TCP peer BEFORE headers: resolving this application identity cannot change
that admission. Raw-peer/global ingress caps and the trusted proxy's aggregate
burst capacity remain separate deployment obligations. Channel-less fallback
has no server-side supersession guarantee.
-/
namespace AlloyStudio.PatchContracts.Identity

inductive IP where
  | v4 (address : Fin 4294967296)
  | v6 (address : Fin 340282366920938463463374607431768211456)
  deriving DecidableEq

abbrev TrustedProxies := List IP

def trusted (allow : TrustedProxies) (peer : IP) : Bool := allow.contains peer
def defaultTrustedProxies : TrustedProxies := []

theorem default_proxy_trust_empty (peer : IP) :
    trusted defaultTrustedProxies peer = false := rfl

/-- Tokens are results of the separately required strict address parser. -/
inductive AddressToken where
  | valid (address : IP)
  | invalid
  deriving DecidableEq

structure DecodedForwarding where
  encodedBytes : Nat
  /-- Preserve field multiplicity rather than silently combining duplicates. -/
  fields : List (List AddressToken)
  deriving DecidableEq

def parseTokens : List AddressToken → Option (List IP)
  | [] => some []
  | .invalid :: _ => none
  | .valid ip :: rest => (parseTokens rest).map (ip :: ·)

inductive ParsedForwarding where
  | absent
  | rejected
  | chain (hops : List IP)
  deriving DecidableEq

/-- A bounded parser contract over decoded tokens, separate from trust selection. -/
def parseForwarding (raw : DecodedForwarding) : ParsedForwarding :=
  if raw.encodedBytes ≤ 4096 then
    match raw.fields with
    | [] => .absent
    | [tokens] =>
      if tokens.length ≤ 32 then
        match parseTokens tokens with
        | some (ip :: ips) => .chain (ip :: ips)
        | _ => .rejected
      else .rejected
    | _ => .rejected
  else .rejected

theorem oversized_forwarding_rejected (raw : DecodedForwarding)
    (h : ¬ raw.encodedBytes ≤ 4096) : parseForwarding raw = .rejected := by
  unfold parseForwarding
  rw [ite_eq_right h]

theorem duplicate_forwarding_rejected (bytes : Nat)
    (first second : List AddressToken) (rest : List (List AddressToken)) :
    parseForwarding ⟨bytes, first :: second :: rest⟩ = .rejected := by
  unfold parseForwarding
  split <;> rfl

theorem empty_forwarding_rejected (bytes : Nat) :
    parseForwarding ⟨bytes, [[]]⟩ = .rejected := by
  unfold parseForwarding
  split <;> rfl

theorem invalid_token_rejected (front suffix : List AddressToken) :
    parseTokens (front ++ .invalid :: suffix) = none := by
  induction front with
  | nil => rfl
  | cons token front ih =>
    cases token with
    | invalid => rfl
    | valid ip =>
      change (parseTokens (front ++ .invalid :: suffix)).map (ip :: ·) = none
      rw [ih]
      rfl

theorem parsed_tokens_preserve_length (tokens : List AddressToken) (ips : List IP)
    (h : parseTokens tokens = some ips) : ips.length = tokens.length := by
  induction tokens generalizing ips with
  | nil => cases h; rfl
  | cons token tokens ih =>
    cases token with
    | invalid => cases h
    | valid ip =>
      cases ht : parseTokens tokens with
      | none => simp only [parseTokens, ht, Option.map_none] at h; cases h
      | some rest =>
        simp only [parseTokens, ht, Option.map_some] at h
        cases h
        exact congrArg Nat.succ (ih rest ht)

theorem accepted_chain_bounded (raw : DecodedForwarding) (ips : List IP)
    (h : parseForwarding raw = .chain ips) :
    raw.encodedBytes ≤ 4096 ∧ ips ≠ [] ∧ ips.length ≤ 32 := by
  unfold parseForwarding at h
  split at h
  · rename_i hbytes
    cases hf : raw.fields with
    | nil => rw [hf] at h; cases h
    | cons tokens rest =>
      cases rest with
      | cons next rest => rw [hf] at h; cases h
      | nil =>
        rw [hf] at h
        dsimp only at h
        split at h
        · rename_i hlen
          cases hp : parseTokens tokens with
          | none => rw [hp] at h; cases h
          | some values =>
            cases values with
            | nil => rw [hp] at h; cases h
            | cons ip tail =>
              rw [hp] at h
              cases h
              refine ⟨hbytes, ?_, ?_⟩
              · intro empty; cases empty
              · rw [parsed_tokens_preserve_length tokens (ip :: tail) hp]
                exact hlen
        · cases h
  · cases h

/-- Input order is nearest hop first; XFF wire order is reversed at the boundary. -/
def nearestUntrusted (allow : TrustedProxies) : List IP → Option IP
  | [] => none
  | ip :: rest => if trusted allow ip then nearestUntrusted allow rest else some ip

def wireReverse : List IP → List IP
  | [] => []
  | ip :: rest => wireReverse rest ++ [ip]

private theorem append_nil_constructive (xs : List IP) : xs ++ [] = xs := by
  induction xs with
  | nil => rfl
  | cons x xs ih => exact congrArg (x :: ·) ih

private theorem append_assoc_constructive (xs ys zs : List IP) :
    (xs ++ ys) ++ zs = xs ++ (ys ++ zs) := by
  induction xs with
  | nil => rfl
  | cons x xs ih => exact congrArg (x :: ·) ih

theorem wire_reverse_append (xs ys : List IP) :
    wireReverse (xs ++ ys) = wireReverse ys ++ wireReverse xs := by
  induction xs with
  | nil => exact (append_nil_constructive (wireReverse ys)).symm
  | cons x xs ih =>
    change wireReverse (xs ++ ys) ++ [x] = wireReverse ys ++ (wireReverse xs ++ [x])
    rw [ih]
    exact append_assoc_constructive _ _ _

def rightScan (allow : TrustedProxies) (wire : List IP) : Option IP :=
  nearestUntrusted allow (wireReverse wire)

theorem trusted_nearest_hop_removed (allow : TrustedProxies) (ip : IP)
    (rest : List IP) (h : trusted allow ip = true) :
    nearestUntrusted allow (ip :: rest) = nearestUntrusted allow rest := by
  change (if trusted allow ip = true then nearestUntrusted allow rest else some ip) = _
  rw [h]
  rfl

theorem first_untrusted_hop_wins (allow : TrustedProxies) (ip : IP)
    (rest : List IP) (h : trusted allow ip = false) :
    nearestUntrusted allow (ip :: rest) = some ip := by
  change (if trusted allow ip = true then nearestUntrusted allow rest else some ip) = _
  rw [h]
  rfl

theorem selected_hop_is_untrusted (allow : TrustedProxies) (hops : List IP)
    (selected : IP) (h : nearestUntrusted allow hops = some selected) :
    trusted allow selected = false := by
  induction hops with
  | nil => cases h
  | cons ip rest ih =>
    change (if trusted allow ip = true then nearestUntrusted allow rest else some ip) = _ at h
    cases ht : trusted allow ip with
    | false =>
      rw [ht] at h
      have selectedEq : ip = selected := Option.some.inj h
      rw [← selectedEq]
      exact ht
    | true =>
      rw [ht] at h
      exact ih h

theorem scan_selected_prefix_stable (allow : TrustedProxies)
    (nearPrefix farSuffix : List IP) (selected : IP)
    (h : nearestUntrusted allow nearPrefix = some selected) :
    nearestUntrusted allow (nearPrefix ++ farSuffix) = some selected := by
  induction nearPrefix with
  | nil => cases h
  | cons ip rest ih =>
    simp only [List.cons_append, nearestUntrusted] at h ⊢
    cases ht : trusted allow ip with
    | false => rw [ht] at h; exact h
    | true => rw [ht] at h; exact ih h

/-- Any attacker-controlled LEFT prefix cannot displace a selected nearer hop. -/
theorem spoofed_left_prefix_noninfluence (allow : TrustedProxies)
    (attackerPrefix authenticatedSuffix : List IP) (selected : IP)
    (h : rightScan allow authenticatedSuffix = some selected) :
    rightScan allow (attackerPrefix ++ authenticatedSuffix) = some selected := by
  unfold rightScan at h ⊢
  rw [wire_reverse_append]
  exact scan_selected_prefix_stable allow _ _ selected h

def resolveQuotaIdentity (allow : TrustedProxies) (socketPeer : IP)
    (forwarded : ParsedForwarding) : Option IP :=
  if trusted allow socketPeer then
    match forwarded with
    | .chain hops => rightScan allow hops
    | .absent | .rejected => none
  else some socketPeer

theorem unknown_proxy_header_noninfluence (allow : TrustedProxies) (peer : IP)
    (left right : ParsedForwarding) (h : trusted allow peer = false) :
    resolveQuotaIdentity allow peer left = resolveQuotaIdentity allow peer right := by
  unfold resolveQuotaIdentity
  rw [h]
  rfl

theorem untrusted_socket_is_identity (allow : TrustedProxies) (peer : IP)
    (header : ParsedForwarding) (h : trusted allow peer = false) :
    resolveQuotaIdentity allow peer header = some peer := by
  unfold resolveQuotaIdentity
  rw [h]
  rfl

theorem trusted_malformed_rejected (allow : TrustedProxies) (peer : IP)
    (h : trusted allow peer = true) :
    resolveQuotaIdentity allow peer .rejected = none := by
  unfold resolveQuotaIdentity
  rw [h]
  rfl

/-- Full application boundary: only the separately validated bounded parse is used. -/
def resolveDecodedQuotaIdentity (allow : TrustedProxies) (socketPeer : IP)
    (forwarded : DecodedForwarding) : Option IP :=
  resolveQuotaIdentity allow socketPeer (parseForwarding forwarded)

theorem trusted_invalid_header_never_falls_back (allow : TrustedProxies) (peer : IP)
    (raw : DecodedForwarding) (htrust : trusted allow peer = true)
    (hparse : parseForwarding raw = .rejected) :
    resolveDecodedQuotaIdentity allow peer raw = none := by
  unfold resolveDecodedQuotaIdentity
  rw [hparse]
  exact trusted_malformed_rejected allow peer htrust

theorem untrusted_decoded_headers_noninfluence (allow : TrustedProxies) (peer : IP)
    (left right : DecodedForwarding) (h : trusted allow peer = false) :
    resolveDecodedQuotaIdentity allow peer left = resolveDecodedQuotaIdentity allow peer right :=
  unknown_proxy_header_noninfluence allow peer _ _ h

def syntheticClient : IP := .v4 ⟨10, by decide⟩
def syntheticAttacker : IP := .v4 ⟨20, by decide⟩
def syntheticProxy : IP := .v4 ⟨30, by decide⟩

def badLeftmostIdentity (wire : List IP) : Option IP := wire.head?

theorem bad_leftmost_spoof_counterexample :
    badLeftmostIdentity [syntheticAttacker, syntheticClient] = some syntheticAttacker ∧
    rightScan [syntheticProxy] [syntheticAttacker, syntheticClient] = some syntheticClient ∧
    syntheticAttacker ≠ syntheticClient := by decide

/- Bounded channels after expiration, under the scheduler's atomic admission lock.
   Entries model live channels; no token unpredictability or person uniqueness is claimed. -/
abbrev Channels := List IP

def identityCount (identity : IP) : Channels → Nat
  | [] => 0
  | peer :: rest => (if peer = identity then 1 else 0) + identityCount identity rest

def channelAdmit (identity : IP) (channels : Channels) : Option Channels :=
  if channels.length < 512 ∧ identityCount identity channels < 32
  then some (identity :: channels) else none

def ChannelBounds (channels : Channels) : Prop :=
  channels.length ≤ 512 ∧ ∀ identity, identityCount identity channels ≤ 32

theorem admission_preserves_channel_bounds (identity : IP) (before after : Channels)
    (hb : ChannelBounds before) (ha : channelAdmit identity before = some after) :
    ChannelBounds after := by
  unfold channelAdmit at ha
  split at ha
  · rename_i guard
    cases ha
    refine ⟨?_, ?_⟩
    · simp only [List.length_cons]
      exact guard.1
    · intro other
      unfold identityCount
      split
      · rename_i heq
        subst other
        exact Nat.add_comm 1 (identityCount identity before) ▸ guard.2
      · rw [Nat.zero_add]
        exact hb.2 other
  · cases ha

inductive ChannelReachable : Channels → Prop where
  | empty : ChannelReachable []
  | admitted {before after} (reached : ChannelReachable before)
      (identity : IP) (h : channelAdmit identity before = some after) : ChannelReachable after

theorem reachable_channel_bounds (channels : Channels) (h : ChannelReachable channels) :
    ChannelBounds channels := by
  induction h with
  | empty => exact ⟨Nat.zero_le _, fun _ => Nat.zero_le _⟩
  | admitted reached identity admitted ih =>
    exact admission_preserves_channel_bounds identity _ _ ih admitted

theorem identity_count_replicate (peer : IP) (n : Nat) :
    identityCount peer (List.replicate n peer) = n := by
  induction n with
  | zero => rfl
  | succ n ih =>
    change (if peer = peer then 1 else 0) + identityCount peer (List.replicate n peer) = n + 1
    rw [ite_eq_left rfl, ih]
    exact Nat.add_comm 1 n

private theorem replicate_length_constructive (peer : IP) (n : Nat) :
    (List.replicate n peer).length = n := by
  induction n with
  | zero => rfl
  | succ n ih => exact congrArg Nat.succ ih

theorem honest_tabs_admitted_until_32 (peer : IP) (n : Nat) (h : n < 32) :
    channelAdmit peer (List.replicate n peer) = some (List.replicate (n + 1) peer) := by
  unfold channelAdmit
  rw [replicate_length_constructive, identity_count_replicate]
  have hn : n < 512 := Nat.lt_trans h (by decide)
  rw [ite_eq_left ⟨hn, h⟩]
  rfl

theorem honest_tabs_reachable (peer : IP) (n : Nat) (h : n ≤ 32) :
    ChannelReachable (List.replicate n peer) := by
  induction n with
  | zero => exact .empty
  | succ n ih =>
    exact .admitted (ih (Nat.le_trans (Nat.le_succ n) h)) peer
      (honest_tabs_admitted_until_32 peer n h)

theorem honest_33rd_tab_refused (peer : IP) :
    ChannelReachable (List.replicate 32 peer) ∧
    channelAdmit peer (List.replicate 32 peer) = none := by
  refine ⟨honest_tabs_reachable peer 32 (Nat.le_refl _), ?_⟩
  unfold channelAdmit
  rw [replicate_length_constructive, identity_count_replicate]
  rfl

theorem distinct_identity_count_unchanged (a b : IP) (channels : Channels)
    (h : a ≠ b) : identityCount b (a :: channels) = identityCount b channels := by
  change (if a = b then 1 else 0) + identityCount b channels = _
  rw [ite_eq_right h, Nat.zero_add]

theorem separate_identities_admit_below_global_limit (identity : IP) (channels : Channels)
    (hglobal : channels.length < 512) (hlocal : identityCount identity channels < 32) :
    channelAdmit identity channels = some (identity :: channels) := by
  unfold channelAdmit
  rw [ite_eq_left ⟨hglobal, hlocal⟩]

theorem distinct_identity_replicate_count (a b : IP) (n : Nat) (h : a ≠ b) :
    identityCount b (List.replicate n a) = 0 := by
  induction n with
  | zero => rfl
  | succ n ih =>
    change identityCount b (a :: List.replicate n a) = 0
    rw [distinct_identity_count_unchanged a b _ h]
    exact ih

theorem full_peer_quota_does_not_block_distinct_identity (a b : IP) (h : a ≠ b) :
    channelAdmit b (List.replicate 32 a) = some (b :: List.replicate 32 a) := by
  apply separate_identities_admit_below_global_limit
  · rw [replicate_length_constructive]
    decide
  · rw [distinct_identity_replicate_count a b 32 h]
    decide

theorem global_bound_refuses_even_fresh_identity (identity : IP) (channels : Channels)
    (h : 512 ≤ channels.length) : channelAdmit identity channels = none := by
  unfold channelAdmit
  apply ite_eq_right
  intro admitted
  exact Nat.not_lt_of_ge h admitted.1

/-- Two distinct people sharing one NAT address still share the quota. -/
theorem nat_collision_witness :
    (0 : Nat) ≠ 1 ∧ channelAdmit syntheticClient (List.replicate 32 syntheticClient) = none :=
  ⟨by decide, (honest_33rd_tab_refused syntheticClient).2⟩

/- Browser operation contract: a single channel attempt and at most one fallback
   send. Retrying a channel-less learner request would violate this contract. -/
inductive ChannelOutcome where
  | issued (token : Nat)
  | explicitlyUnavailable
  | availabilityFailure
  | aborted
  | authenticationFailure
  | malformed
  | otherFailure
  deriving DecidableEq

inductive RequestMode where
  | channel (token : Nat)
  | fallback
  deriving DecidableEq

def fallbackPermitted : ChannelOutcome → Bool
  | .explicitlyUnavailable | .availabilityFailure => true
  | _ => false

def requestPlan : ChannelOutcome → Option RequestMode
  | .issued token => some (.channel token)
  | .explicitlyUnavailable | .availabilityFailure => some .fallback
  | _ => none

theorem fallback_only_for_availability (outcome : ChannelOutcome)
    (h : requestPlan outcome = some .fallback) : fallbackPermitted outcome = true := by
  cases outcome <;> first | rfl | cases h

theorem abort_auth_malformed_never_fallback :
    requestPlan .aborted = none ∧ requestPlan .authenticationFailure = none ∧
    requestPlan .malformed = none := ⟨rfl, rfl, rfl⟩

def badUnconditionalFallback (_ : ChannelOutcome) : RequestMode := .fallback

theorem unconditional_fallback_counterexample :
    badUnconditionalFallback .aborted = .fallback ∧
    fallbackPermitted .aborted = false ∧ requestPlan .aborted = none := ⟨rfl, rfl, rfl⟩

/-- Wire serialization distinguishes an absent key from a present JSON null. -/
inductive ChannelWireField where
  | omitted
  | token (value : Nat)
  | jsonNull
  deriving DecidableEq

def serializeChannel : RequestMode → ChannelWireField
  | .channel token => .token token
  | .fallback => .omitted

def serverChannelFieldAccepted : ChannelWireField → Bool
  | .omitted => true
  | .token _ => true
  | .jsonNull => false

def cancellationTarget : RequestMode → Option Nat
  | .channel token => some token
  | .fallback => none

/-- This is eligibility for channel supersession, not a delivery guarantee. -/
def serverSupersessionEligible : RequestMode → Bool
  | .channel _ => true
  | .fallback => false

theorem fallback_omits_channel_and_never_cancels :
    serializeChannel .fallback = .omitted ∧ cancellationTarget .fallback = none ∧
    serverSupersessionEligible .fallback = false := ⟨rfl, rfl, rfl⟩

theorem present_null_is_not_omission :
    serverChannelFieldAccepted .jsonNull = false ∧
    serverChannelFieldAccepted (serializeChannel .fallback) = true := ⟨rfl, rfl⟩

structure Snapshot where
  revision : Nat
  selection : Nat
  exercise : Nat
  body : Nat
  metric : Nat
  deriving DecidableEq

/-- Body/exercise/metric atoms require an injective equality-preserving wire bridge. -/
def responseCurrent (captured live : Snapshot) (aborted : Bool) : Bool :=
  decide (captured.revision = live.revision ∧ captured.selection = live.selection ∧
    captured.exercise = live.exercise ∧ captured.body = live.body ∧
    captured.metric = live.metric ∧ aborted = false)

def applyResponse (_mode : RequestMode) (captured live : Snapshot) (aborted : Bool) : Bool :=
  responseCurrent captured live aborted

theorem all_modes_retain_local_guards (mode : RequestMode) (captured live : Snapshot)
    (aborted : Bool) (h : applyResponse mode captured live aborted = true) :
    captured.revision = live.revision ∧ captured.selection = live.selection ∧
    captured.exercise = live.exercise ∧ captured.body = live.body ∧
    captured.metric = live.metric ∧ aborted = false := of_decide_eq_true h

theorem stale_response_discarded (mode : RequestMode) (captured live : Snapshot)
    (aborted : Bool)
    (stale : captured.revision ≠ live.revision ∨ captured.selection ≠ live.selection ∨
      captured.exercise ≠ live.exercise ∨ captured.body ≠ live.body ∨
      captured.metric ≠ live.metric ∨ aborted = true) :
    applyResponse mode captured live aborted = false := by
  cases h : applyResponse mode captured live aborted with
  | false => rfl
  | true =>
    have g := all_modes_retain_local_guards mode captured live aborted h
    rcases stale with hrev | hsel | hex | hbody | hmetric | habort
    · exact False.elim (hrev g.1)
    · exact False.elim (hsel g.2.1)
    · exact False.elim (hex g.2.2.1)
    · exact False.elim (hbody g.2.2.2.1)
    · exact False.elim (hmetric g.2.2.2.2.1)
    · rw [habort] at g
      cases g.2.2.2.2.2

inductive BrowserPhase where
  | initial
  | awaitingChannel
  | ready (mode : RequestMode)
  | awaitingResponse (mode : RequestMode)
  | finished
  deriving DecidableEq

inductive BrowserEvent where
  | begin
  | channelResult (outcome : ChannelOutcome)
  | send
  | response (captured live : Snapshot) (aborted : Bool)
  deriving DecidableEq

inductive BrowserEffect where
  | none
  | attemptChannel
  | send (mode : RequestMode)
  | apply
  | discard
  deriving DecidableEq

def browserStep : BrowserPhase → BrowserEvent → BrowserPhase × BrowserEffect
  | .initial, .begin => (.awaitingChannel, .attemptChannel)
  | .awaitingChannel, .channelResult outcome =>
    match requestPlan outcome with
    | some mode => (.ready mode, .none)
    | none => (.finished, .none)
  | .ready mode, .send => (.awaitingResponse mode, .send mode)
  | .awaitingResponse mode, .response captured live aborted =>
    (.finished, if applyResponse mode captured live aborted then .apply else .discard)
  | phase, _ => (phase, .none)

theorem stale_browser_response_discarded (mode : RequestMode) (captured live : Snapshot)
    (aborted : Bool)
    (stale : captured.revision ≠ live.revision ∨ captured.selection ≠ live.selection ∨
      captured.exercise ≠ live.exercise ∨ captured.body ≠ live.body ∨
      captured.metric ≠ live.metric ∨ aborted = true) :
    browserStep (.awaitingResponse mode) (.response captured live aborted) =
      (.finished, .discard) := by
  change (BrowserPhase.finished, if applyResponse mode captured live aborted then BrowserEffect.apply else BrowserEffect.discard) = _
  rw [stale_response_discarded mode captured live aborted stale]
  rfl

def channelBudget : BrowserPhase → Nat
  | .initial => 1
  | _ => 0

def fallbackBudget : BrowserPhase → Nat
  | .initial | .awaitingChannel | .ready .fallback => 1
  | _ => 0

def channelCost : BrowserEffect → Nat
  | .attemptChannel => 1
  | _ => 0

def fallbackCost : BrowserEffect → Nat
  | .send .fallback => 1
  | _ => 0

theorem step_channel_budget (phase : BrowserPhase) (event : BrowserEvent) :
    channelCost (browserStep phase event).2 + channelBudget (browserStep phase event).1
      ≤ channelBudget phase := by
  cases phase with
  | initial => cases event <;> exact Nat.le_refl _
  | awaitingChannel =>
    cases event with
    | channelResult outcome => cases outcome <;> exact Nat.le_refl _
    | _ => exact Nat.le_refl _
  | ready mode => cases event <;> exact Nat.le_refl _
  | awaitingResponse mode =>
    cases event with
    | response captured live aborted =>
      change channelCost (if applyResponse mode captured live aborted then .apply else .discard) + 0 ≤ 0
      split <;> exact Nat.le_refl _
    | _ => exact Nat.le_refl _
  | finished => cases event <;> exact Nat.le_refl _

theorem step_fallback_budget (phase : BrowserPhase) (event : BrowserEvent) :
    fallbackCost (browserStep phase event).2 + fallbackBudget (browserStep phase event).1
      ≤ fallbackBudget phase := by
  cases phase with
  | initial => cases event <;> exact Nat.le_refl _
  | awaitingChannel =>
    cases event with
    | channelResult outcome => cases outcome <;> first | exact Nat.le_refl _ | exact Nat.zero_le _
    | _ => exact Nat.le_refl _
  | ready mode => cases mode <;> cases event <;> exact Nat.le_refl _
  | awaitingResponse mode =>
    cases event with
    | response captured live aborted =>
      change fallbackCost (if applyResponse mode captured live aborted then .apply else .discard) + 0 ≤ 0
      split <;> exact Nat.le_refl _
    | _ => exact Nat.le_refl _
  | finished => cases event <;> exact Nat.le_refl _

def traceCost (cost : BrowserEffect → Nat) (phase : BrowserPhase) : List BrowserEvent → Nat
  | [] => 0
  | event :: rest => cost (browserStep phase event).2 +
      traceCost cost (browserStep phase event).1 rest

theorem trace_within_budget (cost : BrowserEffect → Nat) (budget : BrowserPhase → Nat)
    (stepBound : ∀ p e, cost (browserStep p e).2 + budget (browserStep p e).1 ≤ budget p)
    (phase : BrowserPhase) (events : List BrowserEvent) :
    traceCost cost phase events ≤ budget phase := by
  induction events generalizing phase with
  | nil => exact Nat.zero_le _
  | cons event rest ih =>
    exact Nat.le_trans (Nat.add_le_add_left (ih (browserStep phase event).1) _)
      (stepBound phase event)

theorem operation_channel_attempt_at_most_one (events : List BrowserEvent) :
    traceCost channelCost .initial events ≤ 1 :=
  trace_within_budget channelCost channelBudget step_channel_budget .initial events

theorem operation_fallback_request_at_most_one (events : List BrowserEvent) :
    traceCost fallbackCost .initial events ≤ 1 :=
  trace_within_budget fallbackCost fallbackBudget step_fallback_budget .initial events

/- Administration: one explicit network policy must be enforced at BOTH IIS and
   every backend admin route, before prelogin creation or login admission. Direct
   backend access still runs the backend check. Network/CIDR matching and route
   coverage need separate correspondence. This prevents unallowed clients from
   consuming auth quota; allowed clients can still share a five-failure lockout. -/
abbrev AdminNetwork := IP → Bool

structure AdminPolicy where
  enabled : Bool
  trustedNetworks : List AdminNetwork

def defaultAdminPolicy (enabled : Bool) : AdminPolicy := ⟨enabled, []⟩

def adminNetworkAdmits (policy : AdminPolicy) (identity : IP) : Bool :=
  policy.enabled && policy.trustedNetworks.any (fun network => network identity)

inductive AdminRoute where
  | staticUI | prelogin | login | authenticated
  deriving DecidableEq

def iisAdminAdmits (policy : AdminPolicy) (identity : IP) (_route : AdminRoute) : Bool :=
  adminNetworkAdmits policy identity

def backendAdminAdmits (policy : AdminPolicy) (identity : IP) (_route : AdminRoute) : Bool :=
  adminNetworkAdmits policy identity

theorem edge_backend_share_all_route_policy (policy : AdminPolicy) (identity : IP)
    (route : AdminRoute) :
    iisAdminAdmits policy identity route = backendAdminAdmits policy identity route := rfl

theorem administration_default_deny (enabled : Bool) (identity : IP) (route : AdminRoute) :
    backendAdminAdmits (defaultAdminPolicy enabled) identity route = false := by
  cases enabled <;> rfl

structure AuthQuota where
  preloginSessions : Nat
  recentFailures : Nat
  deriving DecidableEq

inductive SyntheticAuthRequest where
  | prelogin
  | failedLogin
  deriving DecidableEq

def authRoute : SyntheticAuthRequest → AdminRoute
  | .prelogin => .prelogin
  | .failedLogin => .login

def consumeAuthQuota (request : SyntheticAuthRequest) (quota : AuthQuota) : AuthQuota :=
  match request with
  | .prelogin => if quota.preloginSessions < 64 then
      { quota with preloginSessions := quota.preloginSessions + 1 } else quota
  | .failedLogin => if quota.recentFailures < 5 then
      { quota with recentFailures := quota.recentFailures + 1 } else quota

def backendAuthStep (policy : AdminPolicy) (identity : IP)
    (request : SyntheticAuthRequest) (quota : AuthQuota) : AuthQuota :=
  if backendAdminAdmits policy identity (authRoute request)
  then consumeAuthQuota request quota else quota

def edgeAndBackendAuthStep (policy : AdminPolicy) (identity : IP)
    (request : SyntheticAuthRequest) (quota : AuthQuota) : AuthQuota :=
  if iisAdminAdmits policy identity (authRoute request)
  then backendAuthStep policy identity request quota else quota

theorem nonallowlisted_backend_cannot_consume_quota (policy : AdminPolicy) (identity : IP)
    (request : SyntheticAuthRequest) (quota : AuthQuota)
    (h : adminNetworkAdmits policy identity = false) :
    backendAuthStep policy identity request quota = quota := by
  unfold backendAuthStep backendAdminAdmits
  rw [h]
  rfl

theorem nonallowlisted_edge_cannot_consume_quota (policy : AdminPolicy) (identity : IP)
    (request : SyntheticAuthRequest) (quota : AuthQuota)
    (h : adminNetworkAdmits policy identity = false) :
    edgeAndBackendAuthStep policy identity request quota = quota := by
  unfold edgeAndBackendAuthStep iisAdminAdmits
  rw [h]
  rfl

def resolvedBackendAuthStep (allow : TrustedProxies) (policy : AdminPolicy)
    (socketPeer : IP) (raw : DecodedForwarding)
    (request : SyntheticAuthRequest) (quota : AuthQuota) : AuthQuota :=
  match resolveDecodedQuotaIdentity allow socketPeer raw with
  | none => quota
  | some identity => backendAuthStep policy identity request quota

theorem untrusted_nonallowlisted_peer_cannot_forge_auth_admission
    (allow : TrustedProxies) (policy : AdminPolicy) (peer : IP)
    (raw : DecodedForwarding) (request : SyntheticAuthRequest) (quota : AuthQuota)
    (htrust : trusted allow peer = false) (hallow : adminNetworkAdmits policy peer = false) :
    resolvedBackendAuthStep allow policy peer raw request quota = quota := by
  unfold resolvedBackendAuthStep resolveDecodedQuotaIdentity
  rw [untrusted_socket_is_identity allow peer _ htrust]
  exact nonallowlisted_backend_cannot_consume_quota policy peer request quota hallow

def authTrace (step : SyntheticAuthRequest → AuthQuota → AuthQuota)
    (quota : AuthQuota) : List SyntheticAuthRequest → AuthQuota
  | [] => quota
  | request :: rest => authTrace step (step request quota) rest

theorem arbitrary_unallowed_requests_preserve_auth_quota
    (policy : AdminPolicy) (identity : IP) (quota : AuthQuota)
    (requests : List SyntheticAuthRequest) (h : adminNetworkAdmits policy identity = false) :
    authTrace (backendAuthStep policy identity) quota requests = quota := by
  induction requests with
  | nil => rfl
  | cons request rest ih =>
    unfold authTrace
    rw [nonallowlisted_backend_cannot_consume_quota policy identity request quota h]
    exact ih

def syntheticFiveFailures : List SyntheticAuthRequest := List.replicate 5 .failedLogin
def zeroAuthQuota : AuthQuota := ⟨0, 0⟩
def signInLocked (quota : AuthQuota) : Bool := decide (5 ≤ quota.recentFailures)

/-- Current global lockout counterexample: no credentials, network or KDF involved. -/
theorem old_global_five_failure_lockout_witness :
    signInLocked (authTrace consumeAuthQuota zeroAuthQuota syntheticFiveFailures) = true := by
  decide

/-- The old global gate has no person/IP parameter, so another client is blocked too. -/
def oldLoginAdmits (quota : AuthQuota) : Bool := decide (quota.recentFailures < 5)

theorem five_synthetic_failures_deny_next_client :
    oldLoginAdmits (authTrace consumeAuthQuota zeroAuthQuota syntheticFiveFailures) = false := by
  decide

def syntheticAllowedPolicy : AdminPolicy := ⟨true, [fun _ => true]⟩

theorem allowed_clients_can_still_share_lockout :
    signInLocked (authTrace (backendAuthStep syntheticAllowedPolicy syntheticClient)
      zeroAuthQuota syntheticFiveFailures) = true := by decide

theorem default_policy_synthetic_failures_do_not_lock :
    authTrace (backendAuthStep (defaultAdminPolicy true) syntheticClient)
      zeroAuthQuota syntheticFiveFailures = zeroAuthQuota := by decide

end AlloyStudio.PatchContracts.Identity
