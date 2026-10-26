import Std

/-!
Typed, non-executable model of AlloyStudio's canonical point-addressed repair
descriptors and READ. CREATE/UPDATE/DELETE describe repairs; they do not apply source patches.
This module does not model parsing, redaction from an Alloy AST, solver semantics,
or equivalence of a descriptor to a concrete repair. See README.md for boundaries.
-/
namespace RepairLanguage

inductive Action where
  | create | update | delete | summary
  deriving DecidableEq, Repr

inductive Subject where
  | control | comparison | relation | multiplicity | arithmetic | temporal
  | binding | variableUse | reference | constant | call | operator | structureNode | group
  deriving DecidableEq, Repr

inductive Component where
  | temporal | quantifier | matrix
  deriving DecidableEq, Repr

inductive Role where
  | node | anchor | gap | context
  deriving DecidableEq, Repr

/-- There are exactly 72 append-only IDs in repair_grammar.OPERATOR_NAMES v1. -/
abbrev OperatorId := Fin 72

inductive TemporalCoordinate where
  | phase (index : Nat)
  | skeleton
  deriving DecidableEq, Repr

inductive QuantifierCoordinate where
  | matrix | binding (index : Nat) | temporal
  deriving DecidableEq, Repr

/-- `[phase,M,children]`, `[phase,binding,[]]`, or `[T,_,children]`.
A normal-form phase index is not a temporal-skeleton node index. -/
structure Address where
  temporal : TemporalCoordinate
  quantifier : QuantifierCoordinate
  inside : List Nat := []
  deriving DecidableEq, Repr

def ValidAddress (address : Address) : Prop :=
  match address.temporal, address.quantifier with
  | .phase _, .matrix => True
  | .phase _, .binding _ => address.inside = []
  | .skeleton, .temporal => True
  | _, _ => False

instance (address : Address) : Decidable (ValidAddress address) := by
  unfold ValidAddress
  split <;> infer_instance

def addressComponent (address : Address) : Component :=
  match address.quantifier with
  | .matrix => .matrix
  | .binding _ => .quantifier
  | .temporal => .temporal

/-- A binding CREATE denotes a gap; other CREATEs expose only a nearby anchor. -/
def createRole (address : Address) : Role :=
  match address.quantifier with
  | .binding _ => .gap
  | _ => .anchor

/-- Matrix occurrences admit every concrete category. Prefix bindings and the
temporal skeleton each have a single category; GROUP is accounting only. -/
def CategoryFits (component : Component) (subject : Subject) : Prop :=
  subject ≠ .group ∧
  (component = .quantifier → subject = .binding) ∧
  (component = .temporal → subject = .temporal)

instance (component : Component) (subject : Subject) : Decidable (CategoryFits component subject) := by
  unfold CategoryFits
  infer_instance

structure PublicOp where
  operationIndex : Nat
  cost : Nat
  action : Action
  subject : Subject
  component : Component
  role : Role
  sourceOperator : Option OperatorId := none
  targetOperator : Option OperatorId := none
  address : Option Address := none
  bindingSlot : Option Nat := none
  deriving DecidableEq, Repr

/-- Summary weight represents omitted work and is not an atomic edit. -/
def ValidOp (op : PublicOp) : Prop :=
  op.cost ≤ 1048576 ∧
  (if op.action = .summary then
    0 < op.cost ∧ op.subject = .group ∧ op.role = .context ∧ op.address = none
   else op.cost = 1 ∧ op.subject ≠ .group ∧ op.role ≠ .context ∧
     ∃ address, op.address = some address ∧ ValidAddress address ∧
       op.component = addressComponent address ∧
       (op.action = .create → op.role = createRole address)) ∧
  ((op.action = .update ∨ op.action = .delete) → op.role = .node) ∧
  (op.action ≠ .update → op.targetOperator = none) ∧
  (op.bindingSlot.isSome = true ↔ op.subject = .binding ∨ op.subject = .variableUse) ∧
  (op.action ≠ .summary → CategoryFits op.component op.subject)

instance (op : PublicOp) : Decidable (ValidOp op) := by
  unfold ValidOp
  infer_instance

def atomicWeight (op : PublicOp) : Nat := if op.action = .summary then 0 else 1

def editCost (trace : List PublicOp) : Nat := (trace.map PublicOp.cost).sum

def ValidTrace (trace : List PublicOp) (distance : Nat) : Prop :=
  trace.length ≤ 4096 ∧ distance ≤ 1048576 ∧ editCost trace = distance ∧
  (∀ op ∈ trace, ValidOp op) ∧
  trace.map PublicOp.operationIndex = List.range trace.length

instance (trace : List PublicOp) (distance : Nat) : Decidable (ValidTrace trace distance) := by
  unfold ValidTrace
  infer_instance

theorem summary_is_not_atomic (op : PublicOp) (h : op.action = .summary) :
    atomicWeight op = 0 := by simp [atomicWeight, h]

theorem atomic_cost_one (op : PublicOp) (hv : ValidOp op) (h : op.action ≠ .summary) :
    op.cost = 1 := by
  have rules := hv.2.1
  simp only [h, ↓reduceIte] at rules
  exact rules.1

theorem atomic_has_valid_address (op : PublicOp) (hv : ValidOp op) (h : op.action ≠ .summary) :
    ∃ address, op.address = some address ∧ ValidAddress address := by
  have rules := hv.2.1
  simp only [h, ↓reduceIte] at rules
  obtain ⟨address, hp, hv, _⟩ := rules.2.2.2
  exact ⟨address, hp, hv⟩

theorem summary_positive_context (op : PublicOp) (hv : ValidOp op)
    (h : op.action = .summary) :
    0 < op.cost ∧ op.subject = .group ∧ op.role = .context ∧ op.address = none := by
  simpa [h] using hv.2.1

theorem create_hides_target_operator (op : PublicOp) (hv : ValidOp op)
    (h : op.action = .create) : op.targetOperator = none :=
  hv.2.2.2.1 (by simp [h])

theorem create_role_matches_address (op : PublicOp) (hv : ValidOp op)
    (h : op.action = .create) :
    ∃ address, op.address = some address ∧ ValidAddress address ∧ op.role = createRole address := by
  have rules := hv.2.1
  simp [h] at rules
  obtain ⟨address, hp, ha, _, hr⟩ := rules.2.2.2
  exact ⟨address, hp, ha, hr⟩

theorem updates_and_deletes_have_node_role (op : PublicOp) (hv : ValidOp op)
    (h : op.action = .update ∨ op.action = .delete) : op.role = .node := hv.2.2.1 h

theorem binding_slots_iff_binding_subject (op : PublicOp) (hv : ValidOp op) :
    op.bindingSlot.isSome = true ↔ op.subject = .binding ∨ op.subject = .variableUse :=
  hv.2.2.2.2.1

theorem atomic_category_matches_namespace (op : PublicOp) (hv : ValidOp op)
    (h : op.action ≠ .summary) : CategoryFits op.component op.subject := hv.2.2.2.2.2 h

theorem quantifier_edit_is_binding (op : PublicOp) (hv : ValidOp op)
    (h : op.action ≠ .summary) (hc : op.component = .quantifier) : op.subject = .binding :=
  (atomic_category_matches_namespace op hv h).2.1 hc

theorem skeleton_edit_is_temporal (op : PublicOp) (hv : ValidOp op)
    (h : op.action ≠ .summary) (hc : op.component = .temporal) : op.subject = .temporal :=
  (atomic_category_matches_namespace op hv h).2.2 hc

/-- Public node metadata. This finite model has no source/target expression text. -/
structure PublicNode where
  subject : Subject
  operatorId : Option OperatorId := none
  bindingSlot : Option Nat := none
  deriving DecidableEq, Repr

def ValidNodeAt (address : Address) (node : PublicNode) : Prop :=
  ValidAddress address ∧ CategoryFits (addressComponent address) node.subject

instance (address : Address) (node : PublicNode) : Decidable (ValidNodeAt address node) := by
  unfold ValidNodeAt
  infer_instance

abbrev Snapshot := List (Address × PublicNode)

/-- Exact key equality, never substring or repeated-label matching. -/
def lookupAddress (address : Address) : Snapshot → Option PublicNode
  | [] => none
  | (key, node) :: rest => if key = address then some node else lookupAddress address rest

/-- Well-formed snapshots have unique structural keys; construction from an
actual canonical tree is a separate implementation obligation. -/
def ValidSnapshot (snapshot : Snapshot) : Prop :=
  (snapshot.map Prod.fst).Nodup ∧ ∀ entry ∈ snapshot, ValidNodeAt entry.1 entry.2

theorem lookup_returns_existing (address : Address) (snapshot : Snapshot) (node : PublicNode)
    (h : lookupAddress address snapshot = some node) : (address, node) ∈ snapshot := by
  induction snapshot with
  | nil => simp [lookupAddress] at h
  | cons entry rest ih =>
    obtain ⟨key, value⟩ := entry
    by_cases heq : key = address
    · subst key
      simp [lookupAddress] at h
      subst value
      simp
    · simp [lookupAddress, heq] at h
      exact List.mem_cons_of_mem _ (ih h)

theorem absent_address_returns_none (address : Address) (snapshot : Snapshot)
    (h : address ∉ snapshot.map Prod.fst) : lookupAddress address snapshot = none := by
  induction snapshot with
  | nil => rfl
  | cons entry rest ih =>
    obtain ⟨key, value⟩ := entry
    simp only [List.map_cons, List.mem_cons, not_or] at h
    simp [lookupAddress, Ne.symm h.1, ih h.2]

theorem unique_address_resolves_member (address : Address) (snapshot : Snapshot) (node : PublicNode)
    (hn : (snapshot.map Prod.fst).Nodup) (hm : (address, node) ∈ snapshot) :
    lookupAddress address snapshot = some node := by
  induction snapshot with
  | nil => simp at hm
  | cons entry rest ih =>
    obtain ⟨key, value⟩ := entry
    simp only [List.map_cons, List.nodup_cons] at hn
    rcases List.mem_cons.mp hm with head | tail
    · cases head
      simp [lookupAddress]
    · have different : key ≠ address := by
        intro equal
        subst key
        exact hn.1 (List.mem_map.mpr ⟨(address, node), tail, rfl⟩)
      simp [lookupAddress, different, ih hn.2 tail]

structure State where
  snapshot : Snapshot
  trace : List PublicOp
  distance : Nat
  deriving DecidableEq, Repr

def read (address : Address) (state : State) : State × Option PublicNode :=
  (state, lookupAddress address state.snapshot)

theorem read_preserves_state (address : Address) (state : State) :
    (read address state).1 = state := rfl

theorem read_preserves_distance (address : Address) (state : State) :
    (read address state).1.distance = state.distance := rfl

theorem read_preserves_edit_cost (address : Address) (state : State) :
    editCost (read address state).1.trace = editCost state.trace := rfl

theorem read_returns_existing (address : Address) (state : State) (node : PublicNode)
    (h : (read address state).2 = some node) : (address, node) ∈ state.snapshot :=
  lookup_returns_existing address state.snapshot node h

theorem read_returns_namespace_category (address : Address) (state : State) (node : PublicNode)
    (hv : ValidSnapshot state.snapshot) (h : (read address state).2 = some node) :
    CategoryFits (addressComponent address) node.subject :=
  (hv.2 (address, node) (read_returns_existing address state node h)).2

theorem read_never_returns_group (address : Address) (state : State) (node : PublicNode)
    (hv : ValidSnapshot state.snapshot) (h : (read address state).2 = some node) :
    node.subject ≠ .group := (read_returns_namespace_category address state node hv h).1

/-- The public part is already constructed. This wrapper models payload erasure,
not correctness of a production redaction algorithm. -/
structure PrivateOp where
  publicPart : PublicOp
  hiddenBindingName : String
  hiddenTargetExpression : String
  deriving DecidableEq, Repr

def project (trace : List PrivateOp) : List PublicOp := trace.map PrivateOp.publicPart

def resecret (name expression : PrivateOp → String) (trace : List PrivateOp) : List PrivateOp :=
  trace.map fun op => {op with hiddenBindingName := name op, hiddenTargetExpression := expression op}

/-- Changing only either hidden payload cannot change the projected trace. -/
theorem projection_payload_noninterference (name expression : PrivateOp → String)
    (trace : List PrivateOp) : project (resecret name expression trace) = project trace := by
  simp [project, resecret, List.map_map]

theorem projection_preserves_count (trace : List PrivateOp) :
    (project trace).length = trace.length := by simp [project]

theorem projection_preserves_ordered_ids (trace : List PrivateOp) :
    (project trace).map PublicOp.operationIndex = trace.map (fun op => op.publicPart.operationIndex) := by
  simp [project, List.map_map]

theorem projection_preserves_cost (trace : List PrivateOp) :
    editCost (project trace) = (trace.map (fun op => op.publicPart.cost)).sum := by
  simp [project, editCost, List.map_map, Function.comp_def]

theorem projection_preserves_row_validity (trace : List PrivateOp)
    (h : ∀ op ∈ trace, ValidOp op.publicPart) : ∀ op ∈ project trace, ValidOp op := by
  intro op hm
  obtain ⟨privateOp, hin, heq⟩ := List.mem_map.mp hm
  exact heq ▸ h privateOp hin

/-- `edit` records a descriptor; it does not execute a source transformation. -/
inductive Command where
  | edit (op : PublicOp)
  | read (address : Address)
  deriving DecidableEq, Repr

/-- Summaries are accounting rows, never valid positional commands. -/
def ValidCommand : Command → Prop
  | .edit op => ValidOp op ∧ op.action ≠ .summary
  | .read address => ValidAddress address

theorem summary_not_a_valid_command (op : PublicOp) (h : op.action = .summary) :
    ¬ ValidCommand (.edit op) := by simp [ValidCommand, h]

def declaredEdits : List Command → List PublicOp
  | [] => []
  | .edit op :: rest => op :: declaredEdits rest
  | .read _ :: rest => declaredEdits rest

def programWeight (commands : List Command) : Nat := editCost (declaredEdits commands)

theorem read_insertion_preserves_declared_edits (before after : List Command) (address : Address) :
    declaredEdits (before ++ .read address :: after) = declaredEdits (before ++ after) := by
  induction before with
  | nil => rfl
  | cons head tail ih =>
    cases head <;> simp [declaredEdits, ih]

theorem read_insertion_preserves_weight (before after : List Command) (address : Address) :
    programWeight (before ++ .read address :: after) = programWeight (before ++ after) := by
  simp only [programWeight, read_insertion_preserves_declared_edits]

/-- A named replacement is permitted; a hidden target expression is not a field. -/
def exampleUpdate : PublicOp := {
  operationIndex := 0, cost := 1, action := .update, subject := .control,
  component := .matrix, role := .node,
  sourceOperator := some ⟨0, by decide⟩, targetOperator := some ⟨1, by decide⟩,
  address := some ⟨.phase 0, .matrix, [1]⟩ }

example : ValidOp exampleUpdate := by decide
example : ¬ ValidOp { exampleUpdate with action := .create, role := .anchor } := by decide
example : ¬ ValidOp { exampleUpdate with bindingSlot := some 0 } := by decide
example : ¬ ValidOp { exampleUpdate with action := .summary } := by decide
example : ValidTrace [exampleUpdate] 1 := by decide
example : ValidAddress ⟨.phase 0, .binding 1, []⟩ := by decide
example : ¬ ValidAddress ⟨.phase 0, .binding 1, [0]⟩ := by decide
example : ¬ ValidAddress ⟨.skeleton, .matrix, []⟩ := by decide
example : lookupAddress ⟨.phase 0, .matrix, [1]⟩
    [(⟨.phase 0, .matrix, [0]⟩, ⟨.control, some ⟨0, by decide⟩, none⟩)] = none := by decide

/-- Constructed namespace mismatches, independent of operator/slot rejection. -/
def exampleBinding : PublicOp := { exampleUpdate with
  subject := .binding
  component := .quantifier
  address := some ⟨.phase 0, .binding 1, []⟩
  bindingSlot := some 0 }

example : ValidOp exampleBinding := by decide
example : ¬ ValidOp { exampleBinding with subject := .variableUse } := by decide
example : ¬ ValidOp { exampleUpdate with
    component := .temporal
    address := some ⟨.skeleton, .temporal, []⟩ } := by decide
example : ¬ ValidNodeAt ⟨.phase 0, .matrix, []⟩ ⟨.group, none, none⟩ := by decide
example : ¬ ValidNodeAt ⟨.phase 0, .binding 0, []⟩ ⟨.control, none, none⟩ := by decide
example : ¬ ValidNodeAt ⟨.skeleton, .temporal, []⟩ ⟨.binding, none, none⟩ := by decide

end RepairLanguage
