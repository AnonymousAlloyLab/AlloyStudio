import TrafficProfile.Extracted
import TrafficConfig.Spec

/- Independent field, relation and initial-state specifications for TCFG02. -/
namespace AlloyStudio.HttpProfile.Spec

open AlloyStudio.TrafficConfig
open AlloyStudio.HttpProfile.Model

/-- Every field is independently listed; no validity proposition is stored in the raw record. -/
def FieldsValid (p : RawProfile) : Prop :=
  IntegerInterval 1 67108864 p.public_handlers ∧
  IntegerInterval 1 67108864 p.control_handlers ∧
  IntegerInterval 1 67108864 p.public_burst ∧
  IntegerInterval 1 67108864 p.public_rate ∧
  IntegerInterval 1 67108864 p.control_burst ∧
  IntegerInterval 1 67108864 p.control_rate ∧
  IntegerInterval 1 67108864 p.peer_burst ∧
  IntegerInterval 1 67108864 p.peer_rate ∧
  IntegerInterval 1 67108864 p.peer_entries ∧
  IntegerInterval 1 67108864 p.peer_idle_seconds ∧
  IntegerInterval 1 67108864 p.backlog ∧
  IntegerInterval 1 67108864 p.control_backlog ∧
  IntegerInterval 1 67108864 p.line_bytes ∧
  IntegerInterval 1 67108864 p.header_bytes ∧
  IntegerInterval 1 67108864 p.header_count ∧
  SecondsInterval false 300 p.header_seconds ∧
  SecondsInterval false 300 p.body_seconds ∧
  SecondsInterval false 300 p.write_seconds ∧
  SecondsInterval false 300 p.idle_seconds ∧
  IntegerInterval 1 67108864 p.json_depth ∧
  IntegerInterval 1 67108864 p.response_bytes ∧
  IntegerInterval 1 67108864 p.public_cache_bytes ∧
  IntegerInterval 1 67108864 p.public_cache_entries

def WellFormed (p : RawProfile) : Prop :=
  FieldsValid p ∧
  integerValue p.line_bytes ≤ integerValue p.header_bytes ∧
  integerValue p.header_count ≤ 100 ∧
  integerValue p.public_handlers + integerValue p.control_handlers ≤ 256

theorem integer_isSome_iff (value : Scalar) :
    (TrafficConfig.Extracted.validatedInt value 1 67108864).isSome = true ↔
      IntegerInterval 1 67108864 value := by
  constructor
  · intro accepted
    cases result : TrafficConfig.Extracted.validatedInt value 1 67108864 with
    | none => rw [result] at accepted; cases accepted
    | some output =>
      have preserved := validated_int_preserves value output 1 67108864 result
      cases preserved
      exact (validated_int_iff value 1 67108864).mp result
  · intro valid
    rw [(validated_int_iff value 1 67108864).mpr valid]
    rfl

theorem seconds_isSome_iff (value : Scalar) :
    (TrafficConfig.Extracted.validatedSeconds value false 300).isSome = true ↔
      SecondsInterval false 300 value := by
  constructor
  · intro accepted
    cases result : TrafficConfig.Extracted.validatedSeconds value false 300 with
    | none => rw [result] at accepted; cases accepted
    | some output =>
      have preserved := validated_seconds_preserves value output false 300 result
      cases preserved
      exact (validated_seconds_iff value false 300).mp result
  · intro valid
    rw [(validated_seconds_iff value false 300).mpr valid]
    rfl

theorem relation_iff (left right : Int) : (¬ less right left) ↔ left ≤ right :=
  (not_less_iff_lessEqual right left).trans (lessEqual_iff_standard left right)

theorem accepted_iff (p : RawProfile) : Extracted.accepted p ↔ WellFormed p := by
  constructor
  · intro accepted
    obtain ⟨h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19, h20, h21, h22, h23, h24, h25⟩ := accepted
    exact ⟨⟨(integer_isSome_iff _).mp h0,
      (integer_isSome_iff _).mp h1,
      (integer_isSome_iff _).mp h2,
      (integer_isSome_iff _).mp h3,
      (integer_isSome_iff _).mp h4,
      (integer_isSome_iff _).mp h5,
      (integer_isSome_iff _).mp h6,
      (integer_isSome_iff _).mp h7,
      (integer_isSome_iff _).mp h8,
      (integer_isSome_iff _).mp h9,
      (integer_isSome_iff _).mp h10,
      (integer_isSome_iff _).mp h11,
      (integer_isSome_iff _).mp h12,
      (integer_isSome_iff _).mp h13,
      (integer_isSome_iff _).mp h14,
      (seconds_isSome_iff _).mp h15,
      (seconds_isSome_iff _).mp h16,
      (seconds_isSome_iff _).mp h17,
      (seconds_isSome_iff _).mp h18,
      (integer_isSome_iff _).mp h19,
      (integer_isSome_iff _).mp h20,
      (integer_isSome_iff _).mp h21,
      (integer_isSome_iff _).mp h22⟩,
      (relation_iff _ _).mp h23, (relation_iff _ _).mp h24, (relation_iff _ _).mp h25⟩
  · intro valid
    obtain ⟨fields, line, count, handlers⟩ := valid
    obtain ⟨h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19, h20, h21, h22⟩ := fields
    exact ⟨(integer_isSome_iff _).mpr h0,
      (integer_isSome_iff _).mpr h1,
      (integer_isSome_iff _).mpr h2,
      (integer_isSome_iff _).mpr h3,
      (integer_isSome_iff _).mpr h4,
      (integer_isSome_iff _).mpr h5,
      (integer_isSome_iff _).mpr h6,
      (integer_isSome_iff _).mpr h7,
      (integer_isSome_iff _).mpr h8,
      (integer_isSome_iff _).mpr h9,
      (integer_isSome_iff _).mpr h10,
      (integer_isSome_iff _).mpr h11,
      (integer_isSome_iff _).mpr h12,
      (integer_isSome_iff _).mpr h13,
      (integer_isSome_iff _).mpr h14,
      (seconds_isSome_iff _).mpr h15,
      (seconds_isSome_iff _).mpr h16,
      (seconds_isSome_iff _).mpr h17,
      (seconds_isSome_iff _).mpr h18,
      (integer_isSome_iff _).mpr h19,
      (integer_isSome_iff _).mpr h20,
      (integer_isSome_iff _).mpr h21,
      (integer_isSome_iff _).mpr h22,
      (relation_iff _ _).mpr line, (relation_iff _ _).mpr count, (relation_iff _ _).mpr handlers⟩

theorem normalize_iff (p : RawProfile) :
    Extracted.normalize p = some p ↔ WellFormed p := by
  unfold Extracted.normalize
  by_cases accepted : Extracted.accepted p
  · rw [ite_eq_left accepted]
    exact ⟨fun _ => (accepted_iff p).mp accepted, fun _ => rfl⟩
  · rw [ite_eq_right accepted]
    constructor
    · intro impossible; cases impossible
    · intro valid; exact False.elim (accepted ((accepted_iff p).mpr valid))

theorem normalize_preserves (p result : RawProfile)
    (accepted : Extracted.normalize p = some result) : result = p := by
  unfold Extracted.normalize at accepted
  by_cases valid : Extracted.accepted p
  · rw [ite_eq_left valid] at accepted
    cases accepted
    rfl
  · rw [ite_eq_right valid] at accepted
    cases accepted

theorem count_as_nat (value : Scalar) (valid : IntegerInterval 1 67108864 value) :
    ∃ number : Nat, integerValue value = Int.ofNat number ∧
      1 ≤ number ∧ number ≤ 67108864 := by
  cases value with
  | integer value =>
    cases value with
    | ofNat number => exact ⟨number, rfl, valid.2.2.1, valid.2.2.2⟩
    | negSucc number => exact False.elim valid.2.2.1
  | floating numerator denominator => exact False.elim valid
  | invalid reason => exact False.elim valid

theorem count_bounds (value : Scalar) (valid : IntegerInterval 1 67108864 value) :
    0 < integerValue value ∧ integerValue value ≤ 67108864 := by
  obtain ⟨number, equation, positive, upper⟩ := count_as_nat value valid
  rw [equation]
  exact ⟨(less_iff_standard _ _).mp positive,
    (lessEqual_iff_standard _ _).mp upper⟩

theorem capacity_bounds (value : Scalar) (valid : IntegerInterval 1 67108864 value) :
    1000000000 ≤ integerValue value * 1000000000 ∧
    integerValue value * 1000000000 ≤ 67108864000000000 := by
  obtain ⟨number, equation, positive, upper⟩ := count_as_nat value valid
  rw [equation]
  constructor
  · apply (lessEqual_iff_standard _ _).mp
    exact Nat.mul_le_mul_right 1000000000 positive
  · apply (lessEqual_iff_standard _ _).mp
    exact Nat.mul_le_mul_right 1000000000 upper

theorem handler_bounds (p : RawProfile) (valid : WellFormed p) :
    (0 < integerValue p.public_handlers ∧ integerValue p.public_handlers ≤ 256) ∧
    (0 < integerValue p.control_handlers ∧ integerValue p.control_handlers ≤ 256) := by
  have publicValid := valid.1.1
  have controlValid := valid.1.2.1
  obtain ⟨publicCount, publicEq, publicPositive, _⟩ := count_as_nat _ publicValid
  obtain ⟨controlCount, controlEq, controlPositive, _⟩ := count_as_nat _ controlValid
  have total := valid.2.2.2
  rw [publicEq, controlEq] at total
  have combined := (lessEqual_iff_standard _ _).mpr total
  change publicCount + controlCount ≤ 256 at combined
  rw [publicEq, controlEq]
  exact ⟨⟨(less_iff_standard _ _).mp publicPositive,
    (lessEqual_iff_standard _ _).mp (Nat.le_trans (Nat.le_add_right publicCount controlCount) combined)⟩,
    ⟨(less_iff_standard _ _).mp controlPositive,
    (lessEqual_iff_standard _ _).mp (Nat.le_trans (Nat.le_add_left controlCount publicCount) combined)⟩⟩

/-- These independent selectors express the two configured lanes, not an invariant premise. -/
def selectedLimit (p : RawProfile) (control : Bool) : Int :=
  if control then integerValue p.control_handlers else integerValue p.public_handlers

def selectedBurst (p : RawProfile) (control : Bool) : Int :=
  if control then integerValue p.control_burst else integerValue p.public_burst

def selectedRate (p : RawProfile) (control : Bool) : Int :=
  if control then integerValue p.control_rate else integerValue p.public_rate

/-- Independent initial assignments, with each owned collection represented explicitly. -/
def InitialRelation (p : RawProfile) (control : Bool) (now : Int) (state : InitialState) : Prop :=
  state.limit = selectedLimit p control ∧
  state.capacity = selectedBurst p control * 1000000000 ∧
  state.rate = selectedRate p control ∧
  state.credit = state.capacity ∧
  state.last = now ∧
  state.active = 0 ∧ state.peak = 0 ∧ state.accepted = 0 ∧ state.rejected = 0 ∧
  state.owners = [] ∧ state.anonymous_owners = [] ∧ state.peers = []

/-- Bounds derive from accepted fields and empty owned collections. -/
def InitialValid (p : RawProfile) (state : InitialState) : Prop :=
  0 < state.limit ∧ state.limit ≤ 256 ∧
  0 < state.rate ∧ state.rate ≤ 67108864 ∧
  1000000000 ≤ state.capacity ∧ state.capacity ≤ 67108864000000000 ∧
  0 ≤ state.credit ∧ state.credit = state.capacity ∧
  state.active = state.owners.length ∧ state.active = 0 ∧
  Int.ofNat state.active ≤ state.limit ∧
  state.peak = 0 ∧ state.accepted = 0 ∧ state.rejected = 0 ∧
  state.anonymous_owners = [] ∧
  (∀ owner, owner ∈ state.anonymous_owners → owner ∈ state.owners) ∧
  state.peers = [] ∧ Int.ofNat state.peers.length ≤ integerValue p.peer_entries

theorem initial_relation (p : RawProfile) (control : Bool) (now : Int) :
    InitialRelation p control now (Extracted.initial p control now) := by
  cases control <;> exact ⟨rfl, rfl, rfl, rfl, rfl, rfl, rfl, rfl, rfl, rfl, rfl, rfl⟩

theorem initial_unique (p : RawProfile) (control : Bool) (now : Int)
    (state : InitialState) (relation : InitialRelation p control now state) :
    state = Extracted.initial p control now := by
  cases state with
  | mk limit capacity rate credit last active peak accepted rejected owners anonymous_owners peers =>
    obtain ⟨hl, hc, hr, hcr, ht, ha, hp, hac, hre, ho, han, hpe⟩ := relation
    cases hl; cases hc; cases hr; cases hcr; cases ht
    cases ha; cases hp; cases hac; cases hre; cases ho; cases han; cases hpe
    cases control <;> rfl

theorem initial_exists_unique (p : RawProfile) (control : Bool) (now : Int) :
    ∃ state, InitialRelation p control now state ∧
      ∀ other, InitialRelation p control now other → other = state :=
  ⟨Extracted.initial p control now, initial_relation p control now,
    fun other relation => initial_unique p control now other relation⟩

/-- Nonnegative count values without axiom-bearing integer order derivations. -/
theorem count_nonnegative (value : Scalar) (valid : IntegerInterval 1 67108864 value) :
    0 ≤ integerValue value := by
  obtain ⟨number, equation, _, _⟩ := count_as_nat value valid
  rw [equation]
  exact (lessEqual_iff_standard _ _).mp (Nat.zero_le number)

theorem capacity_nonnegative (value : Scalar) (valid : IntegerInterval 1 67108864 value) :
    0 ≤ integerValue value * 1000000000 := by
  obtain ⟨number, equation, _, _⟩ := count_as_nat value valid
  rw [equation]
  exact (lessEqual_iff_standard _ _).mp (Nat.zero_le (number * 1000000000))

theorem initial_valid (p : RawProfile) (control : Bool) (now : Int) (valid : WellFormed p) :
    InitialValid p (Extracted.initial p control now) := by
  obtain ⟨publicBounds, controlBounds⟩ := handler_bounds p valid
  obtain ⟨h0, h1, h2, h3, h4, h5, h6, h7, h8, h9, h10, h11, h12, h13, h14, h15, h16, h17, h18, h19, h20, h21, h22⟩ := valid.1
  have peersBound := count_nonnegative p.peer_entries h8
  cases control with
  | false =>
    have rateBounds := count_bounds p.public_rate h3
    have capacityBounds := capacity_bounds p.public_burst h2
    exact ⟨publicBounds.1, publicBounds.2, rateBounds.1, rateBounds.2,
      capacityBounds.1, capacityBounds.2, capacity_nonnegative _ h2, rfl,
      rfl, rfl, count_nonnegative _ h0, rfl, rfl, rfl, rfl,
      (fun _ impossible => by cases impossible), rfl, peersBound⟩
  | true =>
    have rateBounds := count_bounds p.control_rate h5
    have capacityBounds := capacity_bounds p.control_burst h4
    exact ⟨controlBounds.1, controlBounds.2, rateBounds.1, rateBounds.2,
      capacityBounds.1, capacityBounds.2, capacity_nonnegative _ h4, rfl,
      rfl, rfl, count_nonnegative _ h1, rfl, rfl, rfl, rfl,
      (fun _ impossible => by cases impossible), rfl, peersBound⟩

theorem accepted_initial_valid (p : RawProfile) (control : Bool) (now : Int)
    (accepted : Extracted.accepted p) : InitialValid p (Extracted.initial p control now) :=
  initial_valid p control now ((accepted_iff p).mp accepted)

theorem initial_last (p : RawProfile) (control : Bool) (now : Int) :
    (Extracted.initial p control now).last = now := rfl

/-- The complete independent default witness, including integer/float representation. -/
def defaultProfile : RawProfile := {
  public_handlers := .integer 30
  control_handlers := .integer 2
  public_burst := .integer 60
  public_rate := .integer 30
  control_burst := .integer 4
  control_rate := .integer 2
  peer_burst := .integer 60
  peer_rate := .integer 30
  peer_entries := .integer 1024
  peer_idle_seconds := .integer 120
  backlog := .integer 32
  control_backlog := .integer 2
  line_bytes := .integer 8192
  header_bytes := .integer 32768
  header_count := .integer 64
  header_seconds := .floating 5 1
  body_seconds := .floating 5 1
  write_seconds := .floating 5 1
  idle_seconds := .floating 5 1
  json_depth := .integer 32
  response_bytes := .integer 8388608
  public_cache_bytes := .integer 16777216
  public_cache_entries := .integer 512
}

theorem defaults_exact : Extracted.defaults = defaultProfile := rfl

theorem defaults_accepted : Extracted.accepted Extracted.defaults := by decide

theorem defaults_wellFormed : WellFormed Extracted.defaults :=
  (accepted_iff _).mp defaults_accepted

theorem defaults_normalize : Extracted.normalize Extracted.defaults = some Extracted.defaults :=
  (normalize_iff _).mpr defaults_wellFormed

theorem default_handler_total :
    integerValue Extracted.defaults.public_handlers +
    integerValue Extracted.defaults.control_handlers = 32 := rfl

theorem default_burst_total :
    integerValue Extracted.defaults.public_burst +
    integerValue Extracted.defaults.control_burst = 64 := rfl

theorem default_rate_total :
    integerValue Extracted.defaults.public_rate +
    integerValue Extracted.defaults.control_rate = 32 := rfl

theorem default_public_witness :
    InitialRelation Extracted.defaults false 0 (Extracted.initial Extracted.defaults false 0) ∧
    InitialValid Extracted.defaults (Extracted.initial Extracted.defaults false 0) :=
  ⟨initial_relation _ _ _, initial_valid _ _ _ defaults_wellFormed⟩

theorem default_control_negative_clock_witness :
    InitialRelation Extracted.defaults true (-1) (Extracted.initial Extracted.defaults true (-1)) ∧
    InitialValid Extracted.defaults (Extracted.initial Extracted.defaults true (-1)) :=
  ⟨initial_relation _ _ _, initial_valid _ _ _ defaults_wellFormed⟩

theorem handler_boundary_witness :
    Extracted.accepted { Extracted.defaults with public_handlers := .integer 254 } := by decide

theorem handler_excess_witness :
    ¬ Extracted.accepted { Extracted.defaults with public_handlers := .integer 255 } := by decide

theorem header_boundary_witness :
    Extracted.accepted { Extracted.defaults with
      line_bytes := .integer 32768, header_count := .integer 100 } := by decide

theorem line_excess_witness :
    ¬ Extracted.accepted { Extracted.defaults with line_bytes := .integer 32769 } := by decide

theorem header_excess_witness :
    ¬ Extracted.accepted { Extracted.defaults with header_count := .integer 101 } := by decide

theorem duration_boundary_witness :
    Extracted.accepted { Extracted.defaults with header_seconds := .floating 300 1 } := by decide

theorem duration_excess_witness :
    ¬ Extracted.accepted { Extracted.defaults with header_seconds := .floating 301 1 } := by decide

theorem count_boundary_witness :
    Extracted.accepted { Extracted.defaults with peer_entries := .integer 67108864 } := by decide

theorem count_excess_witness :
    ¬ Extracted.accepted { Extracted.defaults with peer_entries := .integer 67108865 } := by decide

theorem zero_handlers_witness :
    ¬ Extracted.accepted { Extracted.defaults with public_handlers := .integer 0 } := by decide

theorem boolean_handlers_witness :
    ¬ Extracted.accepted { Extracted.defaults with public_handlers := .invalid .boolean } := by decide

end AlloyStudio.HttpProfile.Spec
