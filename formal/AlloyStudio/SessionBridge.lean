import AlloyStudio.Session

/-!
Finite Boolean kernels for the strict browser success guards. Each atom is an
already evaluated comparison; the canonical atom functions below state exactly
which comparisons the model supplies. The theorems cover every model input, and
the list kernels reject a malformed arity. Equality of raw JavaScript values,
wire decoding, and the asynchronous point at which atoms are captured remain
separate implementation obligations.
-/
namespace AlloyStudio.SessionBridge

open AlloyStudio.Session

/-- Conjunction in the same order as the supplied finite Boolean atoms. -/
def allTrue : List Bool → Bool
  | [] => true
  | atom :: rest => atom && allTrue rest

/-- A proposition with the same recursive shape, convenient for kernel proofs. -/
def AllTrue : List Bool → Prop
  | [] => True
  | atom :: rest => atom = true ∧ AllTrue rest

/-- Arity is part of the acceptance contract, including for an empty list. -/
def acceptAll (expectedArity : Nat) (atoms : List Bool) : Bool :=
  decide (atoms.length = expectedArity) && allTrue atoms

def feedbackSuccess (atoms : List Bool) : Bool := acceptAll 10 atoms

def guidanceSuccess (atoms : List Bool) : Bool := acceptAll 13 atoms

/-- All vectors of the requested width, false branch before true branch. -/
def allVectors : Nat → List (List Bool)
  | 0 => [[]]
  | width + 1 =>
    (allVectors width).map (fun rest => false :: rest) ++
    (allVectors width).map (fun rest => true :: rest)

private theorem map_member {α β : Type} (f : α → β) {value : α} {values : List α}
    (member : value ∈ values) : f value ∈ values.map f := by
  induction member with
  | head rest => exact List.Mem.head (rest.map f)
  | tail head _ ih => exact List.Mem.tail (f head) ih

private theorem append_member_right {α : Type} (left : List α) {right : List α}
    {value : α} (member : value ∈ right) : value ∈ left ++ right := by
  induction left with
  | nil => exact member
  | cons head _ ih => exact List.Mem.tail head ih

private theorem append_member_split {α : Type} {value : α} (left right : List α)
    (member : value ∈ left ++ right) : value ∈ left ∨ value ∈ right := by
  induction left with
  | nil => exact Or.inr member
  | cons head rest ih =>
    cases member with
    | head => exact Or.inl (List.Mem.head rest)
    | tail _ member =>
      cases ih member with
      | inl leftMember => exact Or.inl (List.Mem.tail head leftMember)
      | inr rightMember => exact Or.inr rightMember

private theorem map_member_preimage {α β : Type} (f : α → β) {value : β}
    (values : List α) (member : value ∈ values.map f) :
    ∃ source, source ∈ values ∧ f source = value := by
  induction values with
  | nil => cases member
  | cons head rest ih =>
    cases member with
    | head => exact ⟨head, List.Mem.head rest, rfl⟩
    | tail _ member =>
      obtain ⟨source, sourceMember, same⟩ := ih member
      exact ⟨source, List.Mem.tail head sourceMember, same⟩

theorem allVectors_contains (atoms : List Bool) : atoms ∈ allVectors atoms.length := by
  induction atoms with
  | nil => exact List.Mem.head []
  | cons atom rest ih =>
    cases atom with
    | false => exact List.mem_append_left _ (map_member (fun tail => false :: tail) ih)
    | true => exact append_member_right _ (map_member (fun tail => true :: tail) ih)

theorem allVectors_complete (width : Nat) (atoms : List Bool)
    (arity : atoms.length = width) : atoms ∈ allVectors width := by
  cases arity
  exact allVectors_contains atoms

theorem allVectors_member_arity (width : Nat) (atoms : List Bool)
    (member : atoms ∈ allVectors width) : atoms.length = width := by
  induction width generalizing atoms with
  | zero =>
    cases member with
    | head => rfl
    | tail _ member => cases member
  | succ width ih =>
    cases append_member_split _ _ member with
    | inl member =>
      obtain ⟨rest, restMember, same⟩ :=
        map_member_preimage (fun tail => false :: tail) (allVectors width) member
      cases same
      exact congrArg Nat.succ (ih rest restMember)
    | inr member =>
      obtain ⟨rest, restMember, same⟩ :=
        map_member_preimage (fun tail => true :: tail) (allVectors width) member
      cases same
      exact congrArg Nat.succ (ih rest restMember)

/-- The runtime and proof schema use this exact order. -/
def feedbackAtomNames : List String :=
  ["currentRevision", "currentSelection", "currentMetric", "currentExercise",
   "currentBody", "active", "echoExercise", "echoRevision",
   "echoRequestedMetric", "echoEngineMetric"]

/-- The runtime and proof schema use this exact order. -/
def guidanceAtomNames : List String :=
  ["currentRevision", "currentSelection", "currentMetric", "currentExercise",
   "currentBody", "editorEnabled", "sameGeneration", "sameCapturedToken",
   "active", "echoExercise", "echoRevision", "echoRequestedMetric", "echoToken"]

def feedbackAtoms (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) : List Bool :=
  [decide (captured.revision = state.identity.revision),
   decide (captured.selection = state.identity.selection),
   decide (captured.metric = state.identity.metric),
   decide (captured.exerciseId = state.identity.exerciseId),
   decide (captured.body = state.identity.body),
   decide (aborted = false),
   decide (response.exerciseId = some captured.exerciseId),
   decide (response.revision = some captured.revision),
   decide (response.requestedMetric = some captured.metric),
   decide (response.engineMetric = some captured.metric)]

def guidanceAtoms (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) : List Bool :=
  [decide (capture.request.revision = state.identity.revision),
   decide (capture.request.selection = state.identity.selection),
   decide (capture.request.metric = state.identity.metric),
   decide (capture.request.exerciseId = state.identity.exerciseId),
   decide (capture.request.body = state.identity.body),
   decide (state.editorDisabled = false),
   decide (capture.educationGeneration = state.educationGeneration),
   decide (capture.behaviorToken = state.behaviorToken),
   decide (aborted = false),
   decide (responseExercise = capture.request.exerciseId),
   decide (responseRevision = capture.request.revision),
   decide (responseMetric = some capture.request.metric),
   decide (responseToken = capture.behaviorToken)]

private theorem and_true_iff (left right : Bool) :
    (left && right) = true ↔ left = true ∧ right = true := by
  cases left <;> cases right <;> decide

theorem allTrue_iff (atoms : List Bool) : allTrue atoms = true ↔ AllTrue atoms := by
  induction atoms with
  | nil => exact ⟨fun _ => True.intro, fun _ => rfl⟩
  | cons atom rest ih =>
    constructor
    · intro accepted
      have parts := (and_true_iff atom (allTrue rest)).mp accepted
      exact ⟨parts.1, ih.mp parts.2⟩
    · intro accepted
      exact (and_true_iff atom (allTrue rest)).mpr ⟨accepted.1, ih.mpr accepted.2⟩

theorem AllTrue_iff_members (atoms : List Bool) :
    AllTrue atoms ↔ ∀ atom, atom ∈ atoms → atom = true := by
  induction atoms with
  | nil =>
    constructor
    · intro _ _ member
      cases member
    · intro _
      exact True.intro
  | cons head rest ih =>
    constructor
    · intro accepted atom member
      cases member with
      | head => exact accepted.1
      | tail _ member => exact ih.mp accepted.2 atom member
    · intro accepted
      exact ⟨accepted head (List.Mem.head rest),
        ih.mpr (fun atom member => accepted atom (List.Mem.tail head member))⟩

theorem allTrue_iff_members (atoms : List Bool) :
    allTrue atoms = true ↔ ∀ atom, atom ∈ atoms → atom = true :=
  (allTrue_iff atoms).trans (AllTrue_iff_members atoms)

theorem acceptAll_iff (expectedArity : Nat) (atoms : List Bool) :
    acceptAll expectedArity atoms = true ↔
      atoms.length = expectedArity ∧ AllTrue atoms := by
  constructor
  · intro accepted
    have parts := (and_true_iff (decide (atoms.length = expectedArity))
      (allTrue atoms)).mp accepted
    exact ⟨of_decide_eq_true parts.1, (allTrue_iff atoms).mp parts.2⟩
  · intro accepted
    exact (and_true_iff (decide (atoms.length = expectedArity)) (allTrue atoms)).mpr
      ⟨decide_eq_true accepted.1, (allTrue_iff atoms).mpr accepted.2⟩

theorem acceptAll_wrong_arity (expectedArity : Nat) (atoms : List Bool)
    (wrong : atoms.length ≠ expectedArity) : acceptAll expectedArity atoms = false := by
  unfold acceptAll
  rw [decide_eq_false wrong]
  rfl

/-- Exhaustively checked rows plus rejection outside the schema cover all lists.
This theorem concerns Lean functions; applying it to another language requires
an independently established semantics bridge for that language's function. -/
theorem policy_eq_acceptAll_of_finite_rows (width : Nat) (policy : List Bool → Bool)
    (rows : ∀ atoms, atoms ∈ allVectors width → policy atoms = acceptAll width atoms)
    (wrongArity : ∀ atoms, atoms.length ≠ width → policy atoms = false)
    (atoms : List Bool) : policy atoms = acceptAll width atoms := by
  by_cases arity : atoms.length = width
  · exact rows atoms (allVectors_complete width atoms arity)
  · exact (wrongArity atoms arity).trans (acceptAll_wrong_arity width atoms arity).symm

theorem feedbackSuccess_iff (atoms : List Bool) :
    feedbackSuccess atoms = true ↔ atoms.length = 10 ∧ AllTrue atoms :=
  acceptAll_iff 10 atoms

theorem guidanceSuccess_iff (atoms : List Bool) :
    guidanceSuccess atoms = true ↔ atoms.length = 13 ∧ AllTrue atoms :=
  acceptAll_iff 13 atoms

theorem feedback_names_arity : feedbackAtomNames.length = 10 := rfl

theorem guidance_names_arity : guidanceAtomNames.length = 13 := rfl

theorem feedback_atoms_arity (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) :
    (feedbackAtoms state captured response aborted).length = 10 := rfl

theorem guidance_atoms_arity (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) :
    (guidanceAtoms state capture aborted responseExercise responseRevision
      responseMetric responseToken).length = 13 := rfl

/-- The successful flag is an explicit input condition, not a trusted axiom. -/
theorem feedbackSuccess_iff_guard (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (successful : response.successful = true) :
    feedbackSuccess (feedbackAtoms state captured response aborted) = true ↔
      FeedbackGuard state captured response aborted := by
  constructor
  · intro accepted
    have atoms := (feedbackSuccess_iff _).mp accepted
    obtain ⟨revision, selection, metric, exercise, body, active,
      echoExercise, echoRevision, echoMetric, echoEngine, _⟩ := atoms.2
    have he := of_decide_eq_true echoExercise
    have hr := of_decide_eq_true echoRevision
    have hm := of_decide_eq_true echoMetric
    exact ⟨⟨⟨of_decide_eq_true revision, of_decide_eq_true selection,
      of_decide_eq_true metric, of_decide_eq_true exercise, of_decide_eq_true body⟩,
      of_decide_eq_true active, Or.inr he, Or.inr hr, Or.inl hm,
      fun _ => of_decide_eq_true echoEngine⟩, fun _ => ⟨he, hr, hm⟩⟩
  · intro guard
    obtain ⟨⟨⟨revision, selection, metric, exercise, body⟩, active,
      _, _, _, engine⟩, exactEcho⟩ := guard
    obtain ⟨echoExercise, echoRevision, echoMetric⟩ := exactEcho successful
    apply (feedbackSuccess_iff _).mpr
    exact ⟨rfl, decide_eq_true revision, decide_eq_true selection,
      decide_eq_true metric, decide_eq_true exercise, decide_eq_true body,
      decide_eq_true active, decide_eq_true echoExercise, decide_eq_true echoRevision,
      decide_eq_true echoMetric, decide_eq_true (engine successful), True.intro⟩

theorem guidanceSuccess_iff_acceptGuidance (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) :
    guidanceSuccess (guidanceAtoms state capture aborted responseExercise responseRevision
      responseMetric responseToken) = true ↔
    acceptGuidance state capture aborted responseExercise responseRevision
      responseMetric responseToken = true := by
  constructor
  · intro accepted
    have atoms := (guidanceSuccess_iff _).mp accepted
    obtain ⟨revision, selection, metric, exercise, body, editor, generation, token,
      active, echoExercise, echoRevision, echoMetric, echoToken, _⟩ := atoms.2
    apply decide_eq_true
    exact ⟨⟨⟨⟨of_decide_eq_true revision, of_decide_eq_true selection,
      of_decide_eq_true metric, of_decide_eq_true exercise, of_decide_eq_true body⟩,
      of_decide_eq_true editor⟩, of_decide_eq_true generation, of_decide_eq_true token,
      of_decide_eq_true active⟩, of_decide_eq_true echoExercise,
      of_decide_eq_true echoRevision, of_decide_eq_true echoMetric,
      of_decide_eq_true echoToken⟩
  · intro accepted
    have guard := of_decide_eq_true accepted
    obtain ⟨⟨⟨⟨revision, selection, metric, exercise, body⟩, editor⟩,
      generation, token, active⟩, echoExercise, echoRevision, echoMetric, echoToken⟩ := guard
    apply (guidanceSuccess_iff _).mpr
    exact ⟨rfl, decide_eq_true revision, decide_eq_true selection,
      decide_eq_true metric, decide_eq_true exercise, decide_eq_true body,
      decide_eq_true editor, decide_eq_true generation, decide_eq_true token,
      decide_eq_true active, decide_eq_true echoExercise, decide_eq_true echoRevision,
      decide_eq_true echoMetric, decide_eq_true echoToken, True.intro⟩

private theorem bool_eq_of_true_iff (left right : Bool)
    (same : left = true ↔ right = true) : left = right := by
  cases left with
  | false =>
    cases right with
    | false => rfl
    | true => cases same.mpr rfl
  | true =>
    cases right with
    | false => cases same.mp rfl
    | true => rfl

theorem feedbackSuccess_eq_guard (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (successful : response.successful = true) :
    feedbackSuccess (feedbackAtoms state captured response aborted) =
      decide (FeedbackGuard state captured response aborted) := by
  apply bool_eq_of_true_iff
  exact ⟨fun accepted => decide_eq_true
    ((feedbackSuccess_iff_guard state captured response aborted successful).mp accepted),
    fun accepted => (feedbackSuccess_iff_guard state captured response aborted successful).mpr
      (of_decide_eq_true accepted)⟩

theorem guidanceSuccess_eq_acceptGuidance (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) :
    guidanceSuccess (guidanceAtoms state capture aborted responseExercise responseRevision
      responseMetric responseToken) =
    acceptGuidance state capture aborted responseExercise responseRevision
      responseMetric responseToken := by
  apply bool_eq_of_true_iff
  exact guidanceSuccess_iff_acceptGuidance state capture aborted responseExercise
    responseRevision responseMetric responseToken

end AlloyStudio.SessionBridge
