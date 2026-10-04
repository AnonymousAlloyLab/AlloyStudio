import ServiceProfile.Extracted
import ServiceProfile.Frozen
import TrafficProfile.Spec

/- Independent initial ownership requirements are Frozen's separately authored
empty/counter/value/alias inventory. Frozen.graph is a reviewed correspondence
fixture, not an independently defined evaluator or an assumed validity proof. -/
namespace AlloyStudio.ServiceProfile.Spec

open AlloyStudio.ServiceProfile.Model

theorem true_and_left (left right : Bool) (accepted : (left && right) = true) :
    left = true := by
  cases left with
  | false => cases accepted
  | true => rfl

theorem true_and_right (left right : Bool) (accepted : (left && right) = true) :
    right = true := by
  cases left with
  | false => cases accepted
  | true => exact accepted

/-- The Boolean domain check entails ordinary arithmetic positivity; zero is
admitted only by the separately explicit disabled-feature marker. -/
theorem quantity_domain (quantity : Quantity) (accepted : quantityValid quantity = true) :
    0 < quantity.denominator ∧ (quantity.allowZero = true ∨ 0 < quantity.numerator) := by
  cases quantity with
  | mk identifier numerator denominator allowZero =>
    have first := true_and_left _ _ accepted
    have second := true_and_right _ _ accepted
    have denominatorPositive := of_decide_eq_true (true_and_right _ _ first)
    cases allowZero with
    | false => exact ⟨denominatorPositive, Or.inr (of_decide_eq_true second)⟩
    | true => exact ⟨denominatorPositive, Or.inl rfl⟩

theorem quantities_member_valid (profile : List Quantity)
    (accepted : quantitiesValid profile = true) (quantity : Quantity)
    (member : quantity ∈ profile) : quantityValid quantity = true := by
  induction profile with
  | nil => cases member
  | cons head tail inductionHypothesis =>
    have first := true_and_left _ _ accepted
    have rest := true_and_right _ _ accepted
    cases member with
    | head => exact first
    | tail _ memberTail => exact inductionHypothesis rest memberTail

/-- Concrete graph correspondence and independent public admission relation.
External identity values are copied explicitly rather than guessed deterministic. -/
def InitialRelation (input : Inputs) (state : State) : Prop :=
  state.inputs = input ∧ state.profile = Frozen.profile ∧ state.graph = Frozen.graph ∧
    HttpProfile.Spec.InitialRelation HttpProfile.Extracted.defaults false input.clock state.http

/-- Independently enumerated started scheduler threads, preserving their lane
and daemon ownership. Starting is not a statement about scheduler program counter. -/
def StartedThreadsValid (graph : List Cell) : Prop :=
  allCellsMatch graph ["threading.Thread#1.$started", "threading.Thread#2.$started",
    "threading.Thread#3.$started"] "bool" "true" = true ∧
  allCellsMatch graph ["threading.Thread#1.daemon", "threading.Thread#2.daemon",
    "threading.Thread#3.daemon"] "bool" "true" = true ∧
  allCellsMatch graph ["threading.Thread#1.target", "threading.Thread#2.target",
    "threading.Thread#3.target"] "callable" "Scheduler#1._worker" = true ∧
  allCellsMatch graph ["threading.Thread#1.args/0", "threading.Thread#2.args/0"]
    "str" "feedback" = true ∧
  cellMatches graph "threading.Thread#3.args/0" "str" "behavior" = true

/-- Validity is separately checked against independently specified resource,
reference and scalar requirements; graph equality alone does not imply validity. -/
def InitialValid (state : State) : Prop :=
  profileValid state.profile = true ∧
  graphPathsUnique state.graph = true ∧
  Frozen.emptyContainers.all (emptyContainer state.graph) = true ∧
  allCellsMatch state.graph Frozen.zeroCounters "int" "0" = true ∧
  allCellsMatch state.graph Frozen.falseBooleans "bool" "false" = true ∧
  allCellsMatch state.graph Frozen.noneValues "none" "null" = true ∧
  allValuesMatch state.graph Frozen.scalarValues = true ∧
  allReferencesMatch state.graph Frozen.referenceEqualities = true ∧
  allTargetsMatch state.graph Frozen.referenceTargets "reference" = true ∧
  allTargetsMatch state.graph Frozen.externalBindings "external" = true ∧
  classCount state.graph "threading.Thread" = 3 ∧
  StartedThreadsValid state.graph ∧
  handleCount state.graph "bound-listener" = 1 ∧
  classCount state.graph "_Worker" = 0 ∧
  cellMatches state.graph "threading.Event#1.$set" "bool" "true" = true ∧
  HttpProfile.Spec.InitialValid HttpProfile.Extracted.defaults state.http

theorem extracted_profile_exact : Extracted.profile = Frozen.profile := rfl

theorem extracted_graph_exact : Extracted.graph = Frozen.graph := rfl

theorem profile_wellFormed : profileValid Frozen.profile = true := by decide

theorem profile_quantities_valid : quantitiesValid Frozen.profile = true := by decide

theorem profile_rational_domain (quantity : Quantity) (member : quantity ∈ Frozen.profile) :
    0 < quantity.denominator ∧ (quantity.allowZero = true ∨ 0 < quantity.numerator) :=
  quantity_domain quantity (quantities_member_valid Frozen.profile profile_quantities_valid quantity member)

theorem initial_graph_unique_paths : graphPathsUnique Frozen.graph = true := by decide

theorem initial_empty_owned_collections :
    Frozen.emptyContainers.all (emptyContainer Frozen.graph) = true := by decide

theorem initial_zero_counters :
    allCellsMatch Frozen.graph Frozen.zeroCounters "int" "0" = true := by decide

theorem initial_open_flags :
    allCellsMatch Frozen.graph Frozen.falseBooleans "bool" "false" = true := by decide

theorem initial_absent_configuration :
    allCellsMatch Frozen.graph Frozen.noneValues "none" "null" = true := by decide

theorem initial_scalar_configuration :
    allValuesMatch Frozen.graph Frozen.scalarValues = true := by decide

theorem initial_shared_references :
    allReferencesMatch Frozen.graph Frozen.referenceEqualities = true := by decide

theorem initial_reference_targets :
    allTargetsMatch Frozen.graph Frozen.referenceTargets "reference" = true := by decide

theorem initial_external_inputs :
    allTargetsMatch Frozen.graph Frozen.externalBindings "external" = true := by decide

theorem initial_started_thread_count : classCount Frozen.graph "threading.Thread" = 3 := by decide

theorem initial_started_thread_bindings : StartedThreadsValid Frozen.graph := by
  exact ⟨by decide, by decide, by decide, by decide, by decide⟩

theorem initial_bound_listener_count : handleCount Frozen.graph "bound-listener" = 1 := by decide

theorem initial_no_worker_process : classCount Frozen.graph "_Worker" = 0 := by decide

theorem initial_administrator_idle :
    cellMatches Frozen.graph "threading.Event#1.$set" "bool" "true" = true := by decide

theorem initial_relation (input : Inputs) : InitialRelation input (Extracted.initial input) :=
  ⟨rfl, extracted_profile_exact, extracted_graph_exact,
    HttpProfile.Spec.initial_relation HttpProfile.Extracted.defaults false input.clock⟩

theorem initial_valid (input : Inputs) : InitialValid (Extracted.initial input) := by
  exact ⟨profile_wellFormed, initial_graph_unique_paths, initial_empty_owned_collections,
    initial_zero_counters, initial_open_flags, initial_absent_configuration,
    initial_scalar_configuration, initial_shared_references, initial_reference_targets,
    initial_external_inputs, initial_started_thread_count, initial_started_thread_bindings,
    initial_bound_listener_count,
    initial_no_worker_process, initial_administrator_idle,
    HttpProfile.Spec.initial_valid HttpProfile.Extracted.defaults false input.clock
      HttpProfile.Spec.defaults_wellFormed⟩

theorem initial_unique (input : Inputs) (state : State)
    (relation : InitialRelation input state) : state = Extracted.initial input := by
  cases state with
  | mk actualInput profile graph http =>
    have hi : actualInput = input := relation.1
    have hp : profile = Frozen.profile := relation.2.1
    have hg : graph = Frozen.graph := relation.2.2.1
    have hpublic : HttpProfile.Spec.InitialRelation HttpProfile.Extracted.defaults
        false input.clock http := relation.2.2.2
    have publicEq : http = HttpProfile.Extracted.initial HttpProfile.Extracted.defaults
        false input.clock := HttpProfile.Spec.initial_unique HttpProfile.Extracted.defaults false
          input.clock http hpublic
    cases hi
    cases hp
    cases hg
    cases publicEq
    rfl

theorem initial_exists_unique (input : Inputs) :
    ∃ state, InitialRelation input state ∧ InitialValid state ∧
      ∀ other, InitialRelation input other → other = state :=
  ⟨Extracted.initial input, initial_relation input, initial_valid input,
    fun other relation => initial_unique input other relation⟩

theorem related_state_valid (input : Inputs) (state : State)
    (relation : InitialRelation input state) : InitialValid state := by
  have equality := initial_unique input state relation
  cases equality
  exact initial_valid input

theorem related_states_equal (input : Inputs) (first second : State)
    (firstRelation : InitialRelation input first) (secondRelation : InitialRelation input second) :
    first = second :=
  (initial_unique input first firstRelation).trans (initial_unique input second secondRelation).symm

theorem initial_preserves_explicit_inputs (input : Inputs) :
    (Extracted.initial input).inputs = input := rfl

theorem initial_public_clock (input : Inputs) :
    (Extracted.initial input).http.last = input.clock := rfl

theorem initial_http_count_bounds (input : Inputs) :
    0 < (Extracted.initial input).http.limit ∧
    (Extracted.initial input).http.limit ≤ 256 :=
  ⟨(HttpProfile.Spec.initial_valid HttpProfile.Extracted.defaults false input.clock
    HttpProfile.Spec.defaults_wellFormed).1,
   (HttpProfile.Spec.initial_valid HttpProfile.Extracted.defaults false input.clock
    HttpProfile.Spec.defaults_wellFormed).2.1⟩

theorem initial_public_full_credit (input : Inputs) :
    (Extracted.initial input).http.credit = 60000000000 ∧
    (Extracted.initial input).http.capacity = 60000000000 := ⟨rfl, rfl⟩

theorem initial_public_empty_ownership (input : Inputs) :
    (Extracted.initial input).http.active = 0 ∧
    (Extracted.initial input).http.owners = [] ∧
    (Extracted.initial input).http.anonymous_owners = [] ∧
    (Extracted.initial input).http.peers = [] := ⟨rfl, rfl, rfl, rfl⟩

/-- Witness values are nonsensitive symbols, not actual credentials or a claim
that cryptographic random generators emit these particular strings. -/
def witnessInput : Inputs := {
  root := "owned-backend-root"
  snapshot := "validated-snapshot"
  generation := "explicit-generation"
  serviceIdentity := "explicit-service-identity"
  csrfSecret := "explicit-private-input-symbol"
  clock := 0
}

theorem initial_witness :
    InitialRelation witnessInput (Extracted.initial witnessInput) ∧
    InitialValid (Extracted.initial witnessInput) :=
  ⟨initial_relation witnessInput, initial_valid witnessInput⟩

theorem negative_clock_witness :
    InitialValid (Extracted.initial { witnessInput with clock := -1 }) :=
  initial_valid { witnessInput with clock := -1 }

end AlloyStudio.ServiceProfile.Spec

namespace AlloyStudio.Traffic

/-- TRF-00's planned name: the selected finite configuration is well formed and
for every explicit input has exactly one valid abstract successful-startup state.
Production correspondence and declared host trust are separate gate obligations. -/
theorem profile_wellFormed :
    ServiceProfile.Model.profileValid ServiceProfile.Frozen.profile = true ∧
    ∀ input : ServiceProfile.Model.Inputs,
      ∃ state : ServiceProfile.Model.State,
        ServiceProfile.Spec.InitialRelation input state ∧
        ServiceProfile.Spec.InitialValid state ∧
        ∀ other, ServiceProfile.Spec.InitialRelation input other → other = state :=
  ⟨ServiceProfile.Spec.profile_wellFormed, ServiceProfile.Spec.initial_exists_unique⟩

end AlloyStudio.Traffic
