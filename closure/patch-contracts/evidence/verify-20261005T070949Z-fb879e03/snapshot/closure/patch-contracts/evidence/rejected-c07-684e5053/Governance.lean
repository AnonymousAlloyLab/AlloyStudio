import Std

/-!
AP01 proposed contracts. Hashes and paths are abstract atoms; proving equality
of them does not prove SHA-256 collision resistance or filesystem correctness.
The approved inventory is an independent input, never a candidate's own output.
No theorem below is a production implementation correspondence claim.
-/
namespace AlloyStudio.PatchContracts.Governance

abbrev PathId := Nat
abbrev Digest := Nat
structure Entry where
  path : PathId
  digest : Digest
  deriving DecidableEq
abbrev Observation := PathId → Option Digest

def checks : List Entry → Observation → Bool
  | [], _ => true
  | e :: rest, observed =>
    if observed e.path = some e.digest then checks rest observed else false

theorem checked_entry_exact (entries : List Entry) (observed : Observation)
    (h : checks entries observed = true) (e : Entry) (member : e ∈ entries) :
    observed e.path = some e.digest := by
  induction entries with
  | nil => cases member
  | cons first rest ih =>
    unfold checks at h
    split at h
    · rename_i exactFirst
      cases member with
      | head => exact exactFirst
      | tail _ later => exact ih h later
    · cases h

theorem changed_entry_blocks (entries : List Entry) (observed : Observation)
    (e : Entry) (member : e ∈ entries)
    (changed : observed e.path ≠ some e.digest) : checks entries observed ≠ true := by
  intro passed
  exact changed (checked_entry_exact entries observed passed e member)

theorem missing_entry_blocks (entries : List Entry) (observed : Observation)
    (e : Entry) (member : e ∈ entries) (missing : observed e.path = none) :
    checks entries observed ≠ true := by
  apply changed_entry_blocks entries observed e member
  intro same
  rw [missing] at same
  cases same

structure ApprovedSurface where
  root : Digest
  entries : List Entry

structure RecordedResult where
  root : Digest
  verifier : Digest
  passed : Bool
  deriving DecidableEq

def currentVerified (surface : ApprovedSurface) (expectedVerifier : Digest)
    (record : RecordedResult) (observed : Observation) : Bool :=
  if surface.entries = [] then false
  else if record.root = surface.root then
    if record.verifier = expectedVerifier then
      if record.passed = true then checks surface.entries observed else false
    else false
  else false

theorem current_requires_complete_equality (surface : ApprovedSurface)
    (verifier : Digest) (record : RecordedResult) (observed : Observation)
    (h : currentVerified surface verifier record observed = true) :
    surface.entries ≠ [] ∧ record.root = surface.root ∧
    record.verifier = verifier ∧ record.passed = true ∧
    checks surface.entries observed = true := by
  unfold currentVerified at h
  split at h
  · cases h
  · rename_i nonempty
    split at h
    · rename_i root
      split at h
      · rename_i boundVerifier
        split at h
        · rename_i passed
          exact ⟨nonempty, root, boundVerifier, passed, h⟩
        · cases h
      · cases h
    · cases h

theorem empty_inventory_not_verified (root verifier : Digest)
    (record : RecordedResult) (observed : Observation) :
    currentVerified ⟨root, []⟩ verifier record observed = false := rfl

theorem current_changed_input_rejected (surface : ApprovedSurface)
    (verifier : Digest) (record : RecordedResult) (observed : Observation)
    (e : Entry) (member : e ∈ surface.entries)
    (changed : observed e.path ≠ some e.digest) :
    currentVerified surface verifier record observed ≠ true := by
  intro h
  exact changed_entry_blocks surface.entries observed e member changed
    (current_requires_complete_equality surface verifier record observed h).2.2.2.2

/-- The historical record is immutable; freshness is a separate classification. -/
def classify (surface : ApprovedSurface) (verifier : Digest)
    (record : RecordedResult) (observed : Observation) : RecordedResult × Bool :=
  (record, currentVerified surface verifier record observed)

theorem source_drift_does_not_rewrite_history (surface : ApprovedSurface)
    (verifier : Digest) (record : RecordedResult) (observed : Observation) :
    (classify surface verifier record observed).1 = record := rfl

/-- A self-generated manifest passes itself yet fails the independently approved one. -/
def observedChanged : Observation := fun path => if path = 7 then some 2 else none

theorem self_attestation_counterexample :
    checks [⟨7, 2⟩] observedChanged = true ∧
    checks [⟨7, 1⟩] observedChanged = false := by decide

/- Semantic interface contract, not a claim that arbitrary Python functions are
   pure or cannot capture a raw socket. The production bridge must establish
   capability separation, including exceptions and lazy reads. -/
inductive BoundaryEvent where
  | rejected
  | validated
  | called
  deriving DecidableEq

structure BoundaryResult (Reply : Type) where
  trace : List BoundaryEvent
  reply : Option Reply

def dispatch {Raw Valid Reply : Type} (decode : Raw → Option Valid)
    (business : Valid → Reply) (raw : Raw) : BoundaryResult Reply :=
  match decode raw with
  | none => ⟨[.rejected], none⟩
  | some request => ⟨[.validated, .called], some (business request)⟩

theorem rejected_request_not_dispatched {Raw Valid Reply : Type}
    (decode : Raw → Option Valid) (business : Valid → Reply) (raw : Raw)
    (h : decode raw = none) :
    (dispatch decode business raw).trace = [.rejected] ∧
    (dispatch decode business raw).reply = none := by
  unfold dispatch
  rw [h]
  exact ⟨rfl, rfl⟩

theorem successful_request_exactly_once {Raw Valid Reply : Type}
    (decode : Raw → Option Valid) (business : Valid → Reply)
    (raw : Raw) (valid : Valid) (h : decode raw = some valid) :
    (dispatch decode business raw).trace = [.validated, .called] ∧
    (dispatch decode business raw).reply = some (business valid) := by
  unfold dispatch
  rw [h]
  exact ⟨rfl, rfl⟩

theorem business_replacement_preserves_ingress {Raw Valid ReplyA ReplyB : Type}
    (decode : Raw → Option Valid) (first : Valid → ReplyA)
    (second : Valid → ReplyB) (raw : Raw) :
    (dispatch decode first raw).trace = (dispatch decode second raw).trace := by
  unfold dispatch
  cases decode raw <;> rfl

/-- Existing evidence stays at its identifier. Conflicting append is refused. -/
abbrev EvidenceStore := List Entry

def lookup (store : EvidenceStore) (path : PathId) : Option Digest :=
  match store with
  | [] => none
  | e :: rest => if path = e.path then some e.digest else lookup rest path

def appendEvidence (store : EvidenceStore) (e : Entry) : Option EvidenceStore :=
  if lookup store e.path = none then some (e :: store) else none

theorem evidence_collision_refused (store : EvidenceStore) (e : Entry)
    (present : lookup store e.path ≠ none) : appendEvidence store e = none := by
  unfold appendEvidence
  rw [ite_eq_right present]

theorem evidence_append_preserves_existing (store result : EvidenceStore)
    (e : Entry) (path : PathId) (digest : Digest)
    (old : lookup store path = some digest)
    (success : appendEvidence store e = some result) :
    lookup result path = some digest := by
  unfold appendEvidence at success
  split at success
  · rename_i absent
    cases success
    unfold lookup
    split
    · rename_i eqPath
      rw [eqPath, absent] at old
      cases old
    · exact old
  · cases success

theorem evidence_append_records_new (store result : EvidenceStore) (e : Entry)
    (success : appendEvidence store e = some result) :
    lookup result e.path = some e.digest := by
  unfold appendEvidence at success
  split at success
  · cases success
    unfold lookup
    rw [ite_eq_left rfl]
  · cases success

end AlloyStudio.PatchContracts.Governance
