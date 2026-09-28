import Std

/-!
Executable guard models: the legacy definitions capture the inspected browser
compatibility rules; the strict definitions require complete successful-response
echoes. Constructed counterexamples motivate the stricter guards. These proofs
do not establish JavaScript semantics or a wire-decoder correspondence.
-/
namespace AlloyStudio.Session

inductive Metric where
  | canonical
  | ast
  deriving DecidableEq, Repr

structure RequestIdentity where
  exerciseId : String
  body : String
  revision : Nat
  selection : Nat
  metric : Metric
  deriving DecidableEq, Repr

structure BrowserState where
  identity : RequestIdentity
  editorDisabled : Bool
  behaviorToken : Option String
  educationGeneration : Nat
  deriving DecidableEq, Repr

/-- The five comparisons after the feedback await, in source order. -/
def CurrentSnapshot (captured current : RequestIdentity) : Prop :=
  captured.revision = current.revision ∧
  captured.selection = current.selection ∧
  captured.metric = current.metric ∧
  captured.exerciseId = current.exerciseId ∧ captured.body = current.body

instance (captured current : RequestIdentity) : Decidable (CurrentSnapshot captured current) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _))

theorem currentSnapshot_identity (captured current : RequestIdentity)
    (h : CurrentSnapshot captured current) : captured = current := by
  cases captured with
  | mk exercise body revision selection metric =>
    cases current with
    | mk exercise' body' revision' selection' metric' =>
      obtain ⟨hr, hs, hm, he, hb⟩ := h
      cases hr; cases hs; cases hm; cases he; cases hb
      rfl

def SourceContextCurrent (context : RequestIdentity) (state : BrowserState) : Prop :=
  CurrentSnapshot context state.identity ∧ state.editorDisabled = false

def LegacyResponseMetricMatches (requested : Metric) (echo : Option Metric) : Prop :=
  echo = some requested ∨ (requested = .canonical ∧ echo = none)

instance (requested : Metric) (echo : Option Metric) : Decidable (LegacyResponseMetricMatches requested echo) :=
  inferInstanceAs (Decidable (_ ∨ (_ ∧ _)))

def OptionalEchoMatches [DecidableEq α] (expected : α) (echo : Option α) : Prop :=
  echo = none ∨ echo = some expected

instance [DecidableEq α] (expected : α) (echo : Option α) :
    Decidable (OptionalEchoMatches expected echo) :=
  inferInstanceAs (Decidable (_ ∨ _))

structure FeedbackResponse where
  exerciseId : Option String
  revision : Option Nat
  requestedMetric : Option Metric
  successful : Bool
  engineMetric : Option Metric
  deriving DecidableEq, Repr

def LegacyFeedbackGuard (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) : Prop :=
  CurrentSnapshot captured state.identity ∧ aborted = false ∧
  OptionalEchoMatches captured.exerciseId response.exerciseId ∧
  OptionalEchoMatches captured.revision response.revision ∧
  LegacyResponseMetricMatches captured.metric response.requestedMetric ∧
  (response.successful = true → response.engineMetric = some captured.metric)

instance (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) :
    Decidable (LegacyFeedbackGuard state captured response aborted) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _ ∧ _ ∧ _ ∧ _))

/-- A locator requires exact echoed exercise/revision, even when general
feedback can be shown for a compatible older server response. -/
def locatorContext (captured : RequestIdentity) (response : FeedbackResponse) :
    Option RequestIdentity :=
  if response.exerciseId = some captured.exerciseId ∧ response.revision = some captured.revision
  then some captured else none

/-- AST rendering does not construct a canonical correspondence. -/
def canonicalContext (captured : RequestIdentity) : Option RequestIdentity :=
  match captured.metric with
  | .canonical => some captured
  | .ast => none

structure Presentation where
  identity : RequestIdentity
  locator : Option RequestIdentity
  canonical : Option RequestIdentity
  deriving DecidableEq, Repr

def legacyRenderResponse (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) : Option Presentation :=
  if LegacyFeedbackGuard state captured response aborted then
    some ⟨captured, locatorContext captured response, canonicalContext captured⟩
  else none

theorem legacy_rendered_result_matches_request (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (shown : Presentation)
    (h : legacyRenderResponse state captured response aborted = some shown) :
    shown.identity = state.identity ∧ aborted = false ∧
    LegacyResponseMetricMatches state.identity.metric response.requestedMetric ∧
    (response.successful = true → response.engineMetric = some state.identity.metric) := by
  unfold legacyRenderResponse at h
  by_cases guard : LegacyFeedbackGuard state captured response aborted
  · rw [ite_eq_left guard] at h
    have equal := Option.some.inj h
    cases equal
    obtain ⟨current, active, _, _, metric, engine⟩ := guard
    have same := currentSnapshot_identity captured state.identity current
    cases same
    exact ⟨rfl, active, metric, engine⟩
  · rw [ite_eq_right guard] at h
    cases h

theorem legacy_stale_response_rejected (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (stale : captured ≠ state.identity) :
    legacyRenderResponse state captured response aborted = none := by
  unfold legacyRenderResponse
  apply ite_eq_right
  intro guard
  exact stale (currentSnapshot_identity captured state.identity guard.1)

theorem legacy_aborted_response_rejected (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) : legacyRenderResponse state captured response true = none := by
  unfold legacyRenderResponse
  apply ite_eq_right
  intro guard
  cases guard.2.1

theorem legacy_metric_switch_rejects_previous_response (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (changed : captured.metric ≠ state.identity.metric) :
    legacyRenderResponse state captured response aborted = none := by
  apply legacy_stale_response_rejected
  intro same
  exact changed (congrArg RequestIdentity.metric same)

theorem locator_requires_echoed_identity (captured : RequestIdentity) (response : FeedbackResponse)
    (context : RequestIdentity) (h : locatorContext captured response = some context) :
    context = captured ∧ response.exerciseId = some captured.exerciseId ∧
      response.revision = some captured.revision := by
  unfold locatorContext at h
  by_cases echoed : response.exerciseId = some captured.exerciseId ∧ response.revision = some captured.revision
  · rw [ite_eq_left echoed] at h
    exact ⟨(Option.some.inj h).symm, echoed⟩
  · rw [ite_eq_right echoed] at h
    cases h

theorem ast_has_no_canonical_context (captured : RequestIdentity) (ast : captured.metric = .ast) :
    canonicalContext captured = none := by
  unfold canonicalContext
  rw [ast]

theorem legacy_rendered_ast_has_no_canonical_correspondence (state : BrowserState)
    (captured : RequestIdentity) (response : FeedbackResponse) (aborted : Bool)
    (shown : Presentation) (ast : state.identity.metric = .ast)
    (h : legacyRenderResponse state captured response aborted = some shown) : shown.canonical = none := by
  unfold legacyRenderResponse at h
  by_cases guard : LegacyFeedbackGuard state captured response aborted
  · rw [ite_eq_left guard] at h
    have same := currentSnapshot_identity captured state.identity guard.1
    have equal := Option.some.inj h
    cases equal
    exact ast_has_no_canonical_context captured ((congrArg RequestIdentity.metric same).trans ast)
  · rw [ite_eq_right guard] at h
    cases h

theorem missing_metric_echo_only_canonical (requested : Metric)
    (h : LegacyResponseMetricMatches requested none) : requested = .canonical := by
  cases h with
  | inl impossible => cases impossible
  | inr canonical => exact canonical.1

/-- Logical metric-indexed cache keys. Actual serialized storage key and server
cache correspondence are separate implementation obligations. -/
def cacheKey (identity : RequestIdentity) : String × Metric :=
  (identity.exerciseId, identity.metric)

theorem different_metrics_have_different_cache_keys (left right : RequestIdentity)
    (different : left.metric ≠ right.metric) : cacheKey left ≠ cacheKey right := by
  intro equal
  exact different (congrArg Prod.snd equal)

structure GuidanceCapture where
  request : RequestIdentity
  behaviorToken : Option String
  educationGeneration : Nat
  deriving DecidableEq, Repr

/-- The await guard includes current education object identity (represented by
a generation), request identity, behavioral evidence token, and abort state. -/
def GuidanceCurrent (state : BrowserState) (capture : GuidanceCapture) (aborted : Bool) : Prop :=
  SourceContextCurrent capture.request state ∧
  capture.educationGeneration = state.educationGeneration ∧
  capture.behaviorToken = state.behaviorToken ∧ aborted = false

instance (state : BrowserState) (capture : GuidanceCapture) (aborted : Bool) :
    Decidable (GuidanceCurrent state capture aborted) :=
  inferInstanceAs (Decidable ((_ ∧ _) ∧ _ ∧ _ ∧ _))

def legacyAcceptGuidance (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) : Bool :=
  decide (GuidanceCurrent state capture aborted ∧
    responseExercise = capture.request.exerciseId ∧
    responseRevision = capture.request.revision ∧
    LegacyResponseMetricMatches capture.request.metric responseMetric ∧
    responseToken = capture.behaviorToken)

theorem legacy_accepted_guidance_matches_context (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String)
    (h : legacyAcceptGuidance state capture aborted responseExercise responseRevision responseMetric responseToken = true) :
    capture.request = state.identity ∧ capture.educationGeneration = state.educationGeneration ∧
    responseToken = state.behaviorToken ∧ aborted = false ∧
    responseExercise = state.identity.exerciseId ∧ responseRevision = state.identity.revision ∧
    LegacyResponseMetricMatches state.identity.metric responseMetric := by
  have accepted := of_decide_eq_true h
  obtain ⟨⟨⟨current, _⟩, generation, token, active⟩, exercise, revision, metric, responseToken⟩ := accepted
  have same := currentSnapshot_identity capture.request state.identity current
  refine ⟨same, generation, responseToken.trans token, active, ?_, ?_, ?_⟩
  · exact exercise.trans (congrArg RequestIdentity.exerciseId same)
  · exact revision.trans (congrArg RequestIdentity.revision same)
  · rw [← congrArg RequestIdentity.metric same]
    exact metric

def legacyIdentity : RequestIdentity := ⟨"exercise-1", "some A", 7, 3, .canonical⟩

def legacyState : BrowserState := ⟨legacyIdentity, false, none, 1⟩

def legacyMissingEcho : FeedbackResponse := ⟨none, none, none, true, some .canonical⟩

/-- A concrete counterexample to the stronger claim that the existing fallback
requires echoed identity before displaying successful feedback. It does not
contradict the current-snapshot theorem above. -/
theorem legacy_missing_echo_counterexample :
    legacyRenderResponse legacyState legacyIdentity legacyMissingEcho false =
      some ⟨legacyIdentity, none, some legacyIdentity⟩ ∧
    legacyMissingEcho.successful = true ∧
    ¬(legacyMissingEcho.exerciseId = some legacyIdentity.exerciseId ∧
      legacyMissingEcho.revision = some legacyIdentity.revision ∧
      legacyMissingEcho.requestedMetric = some legacyIdentity.metric) := by
  decide

/-- The old guidance guard also admits a successful canonical explanation
without its requestedMetric echo, despite exact exercise and revision echoes. -/
theorem legacy_guidance_missing_metric_counterexample :
    legacyAcceptGuidance legacyState ⟨legacyIdentity, none, 1⟩ false
      "exercise-1" 7 none none = true ∧
    (none : Option Metric) ≠ some legacyIdentity.metric := by
  decide

/-- Exact echoes required for a successful response in the strengthened model.
The body and selection remain bound by the captured current-request guard. -/
def ExactEcho (captured : RequestIdentity) (response : FeedbackResponse) : Prop :=
  response.exerciseId = some captured.exerciseId ∧
  response.revision = some captured.revision ∧
  response.requestedMetric = some captured.metric

instance (captured : RequestIdentity) (response : FeedbackResponse) :
    Decidable (ExactEcho captured response) :=
  inferInstanceAs (Decidable (_ ∧ _ ∧ _))

def FeedbackGuard (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) : Prop :=
  LegacyFeedbackGuard state captured response aborted ∧
  (response.successful = true → ExactEcho captured response)

instance (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) :
    Decidable (FeedbackGuard state captured response aborted) :=
  inferInstanceAs (Decidable (_ ∧ _))

def renderResponse (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) : Option Presentation :=
  if FeedbackGuard state captured response aborted then
    some ⟨captured, locatorContext captured response, canonicalContext captured⟩
  else none

/-- This statement binds the captured body/selection/metric/current state and
every required successful-response echo. It remains a model-level theorem. -/
theorem rendered_result_matches_request (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (shown : Presentation)
    (h : renderResponse state captured response aborted = some shown) :
    shown.identity = state.identity ∧ aborted = false ∧
    (response.successful = true →
      response.exerciseId = some state.identity.exerciseId ∧
      response.revision = some state.identity.revision ∧
      response.requestedMetric = some state.identity.metric ∧
      response.engineMetric = some state.identity.metric) := by
  unfold renderResponse at h
  by_cases guard : FeedbackGuard state captured response aborted
  · rw [ite_eq_left guard] at h
    have equal := Option.some.inj h
    cases equal
    obtain ⟨⟨current, active, _, _, _, engine⟩, echoed⟩ := guard
    have same := currentSnapshot_identity captured state.identity current
    cases same
    refine ⟨rfl, active, ?_⟩
    intro successful
    obtain ⟨exercise, revision, metric⟩ := echoed successful
    exact ⟨exercise, revision, metric, engine successful⟩
  · rw [ite_eq_right guard] at h
    cases h

theorem stale_response_rejected (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (stale : captured ≠ state.identity) :
    renderResponse state captured response aborted = none := by
  unfold renderResponse
  apply ite_eq_right
  intro guard
  exact stale (currentSnapshot_identity captured state.identity guard.1.1)

theorem aborted_response_rejected (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) : renderResponse state captured response true = none := by
  unfold renderResponse
  apply ite_eq_right
  intro guard
  cases guard.1.2.1

theorem metric_switch_rejects_previous_response (state : BrowserState) (captured : RequestIdentity)
    (response : FeedbackResponse) (aborted : Bool) (changed : captured.metric ≠ state.identity.metric) :
    renderResponse state captured response aborted = none := by
  apply stale_response_rejected
  intro same
  exact changed (congrArg RequestIdentity.metric same)

theorem rendered_ast_has_no_canonical_correspondence (state : BrowserState)
    (captured : RequestIdentity) (response : FeedbackResponse) (aborted : Bool)
    (shown : Presentation) (ast : state.identity.metric = .ast)
    (h : renderResponse state captured response aborted = some shown) : shown.canonical = none := by
  unfold renderResponse at h
  by_cases guard : FeedbackGuard state captured response aborted
  · rw [ite_eq_left guard] at h
    have same := currentSnapshot_identity captured state.identity guard.1.1
    have equal := Option.some.inj h
    cases equal
    exact ast_has_no_canonical_context captured ((congrArg RequestIdentity.metric same).trans ast)
  · rw [ite_eq_right guard] at h
    cases h

theorem strict_guard_rejects_legacy_missing_echo :
    renderResponse legacyState legacyIdentity legacyMissingEcho false = none := by decide

def acceptGuidance (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String) : Bool :=
  decide (GuidanceCurrent state capture aborted ∧
    responseExercise = capture.request.exerciseId ∧
    responseRevision = capture.request.revision ∧
    responseMetric = some capture.request.metric ∧
    responseToken = capture.behaviorToken)

theorem accepted_guidance_matches_context (state : BrowserState) (capture : GuidanceCapture)
    (aborted : Bool) (responseExercise : String) (responseRevision : Nat)
    (responseMetric : Option Metric) (responseToken : Option String)
    (h : acceptGuidance state capture aborted responseExercise responseRevision responseMetric responseToken = true) :
    capture.request = state.identity ∧ capture.educationGeneration = state.educationGeneration ∧
    responseToken = state.behaviorToken ∧ aborted = false ∧
    responseExercise = state.identity.exerciseId ∧ responseRevision = state.identity.revision ∧
    responseMetric = some state.identity.metric := by
  have accepted := of_decide_eq_true h
  obtain ⟨⟨⟨current, _⟩, generation, token, active⟩, exercise, revision, metric, responseToken⟩ := accepted
  have same := currentSnapshot_identity capture.request state.identity current
  refine ⟨same, generation, responseToken.trans token, active, ?_, ?_, ?_⟩
  · exact exercise.trans (congrArg RequestIdentity.exerciseId same)
  · exact revision.trans (congrArg RequestIdentity.revision same)
  · exact metric.trans (congrArg (fun request => some request.metric) same)

theorem strict_guidance_rejects_legacy_missing_metric :
    acceptGuidance legacyState ⟨legacyIdentity, none, 1⟩ false
      "exercise-1" 7 none none = false := by decide

/-- These examples reflect supported compatibility, not a claim of wire identity
when an old server omits the fields. -/
example : LegacyResponseMetricMatches .canonical none := Or.inr ⟨rfl, rfl⟩

example : ¬LegacyResponseMetricMatches .ast none := by
  intro h
  have impossible := missing_metric_echo_only_canonical .ast h
  cases impossible

end AlloyStudio.Session
