import Std

/-! Finite LFU transition model. Concrete Alloy solutions are represented by
opaque identities; their authenticated construction is outside this proof. -/
namespace LFU

structure Entry where
  identity : Nat
  frequency : Nat
  deriving DecidableEq

def Absent (identity : Nat) : List Entry → Prop
  | [] => True
  | x :: xs => identity ≠ x.identity ∧ Absent identity xs

instance absentDecidable (identity : Nat) : (xs : List Entry) → Decidable (Absent identity xs)
  | [] => isTrue trivial
  | x :: xs =>
    letI := absentDecidable identity xs
    inferInstanceAs (Decidable (identity ≠ x.identity ∧ Absent identity xs))

def Unique : List Entry → Prop
  | [] => True
  | x :: xs => Absent x.identity xs ∧ Unique xs

def ValidFrequencies (limit : Nat) : List Entry → Prop
  | [] => True
  | x :: xs => x.frequency ≤ limit ∧ ValidFrequencies limit xs

def erase (identity : Nat) : List Entry → List Entry
  | [] => []
  | x :: xs => if identity = x.identity then erase identity xs else x :: erase identity xs

/-- Older entries occur earlier; strict improvement retains the first tie. -/
def victim : List Entry → Option Entry
  | [] => none
  | x :: xs => match victim xs with
    | none => some x
    | some y => some (if y.frequency < x.frequency then y else x)

def survivors (capacity : Nat) (xs : List Entry) : List Entry :=
  if xs.length < capacity then xs else
    match victim xs with
    | none => []
    | some v => erase v.identity xs

def admission (capacity identity : Nat) (xs : List Entry) : List Entry :=
  if capacity = 0 then [] else
    if Absent identity xs then survivors capacity xs ++ [⟨identity, 1⟩] else xs

def bump (limit value : Nat) : Nat := if value < limit then value + 1 else value

def mismatch (limit identity : Nat) : List Entry → List Entry
  | [] => []
  | x :: xs =>
    { x with frequency := if x.identity = identity then bump limit x.frequency else x.frequency }
      :: mismatch limit identity xs

theorem member_cons {x y : Entry} {xs : List Entry} :
    x ∈ y :: xs ↔ x = y ∨ x ∈ xs := by
  constructor
  · intro h
    cases h with
    | head => exact Or.inl rfl
    | tail _ h => exact Or.inr h
  · intro h
    cases h with
    | inl h => cases h; exact .head _
    | inr h => exact .tail _ h

theorem absent_member (id : Nat) (xs : List Entry) (h : Absent id xs)
    (e : Entry) (member : e ∈ xs) : id ≠ e.identity := by
  induction xs with
  | nil => cases member
  | cons x xs ih =>
    cases member_cons.mp member with
    | inl equal => cases equal; exact h.1
    | inr tail => exact ih h.2 tail

theorem member_not_absent (id : Nat) (xs : List Entry) (e : Entry)
    (member : e ∈ xs) (same : id = e.identity) : ¬Absent id xs := by
  intro absent
  exact absent_member id xs absent e member same

theorem erase_absent (id : Nat) (xs : List Entry) : Absent id (erase id xs) := by
  induction xs with
  | nil => trivial
  | cons x xs ih =>
    unfold erase
    split
    · exact ih
    · exact ⟨by assumption, ih⟩

theorem erase_preserves_absent (target removed : Nat) (xs : List Entry)
    (h : Absent target xs) : Absent target (erase removed xs) := by
  induction xs with
  | nil => trivial
  | cons x xs ih =>
    unfold erase
    split
    · exact ih h.2
    · exact ⟨h.1, ih h.2⟩

theorem erase_length_le (id : Nat) (xs : List Entry) :
    (erase id xs).length ≤ xs.length := by
  induction xs with
  | nil => exact Nat.le_refl 0
  | cons x xs ih =>
    unfold erase
    split
    · exact Nat.le_trans ih (Nat.le_succ xs.length)
    · exact Nat.succ_le_succ ih

theorem erase_length_lt (id : Nat) (xs : List Entry) (h : ¬Absent id xs) :
    (erase id xs).length < xs.length := by
  induction xs with
  | nil => exact False.elim (h trivial)
  | cons x xs ih =>
    unfold erase
    split
    · have bound := erase_length_le id xs
      exact Nat.lt_succ_of_le bound
    · rename_i different
      have tail : ¬Absent id xs := fun absent => h ⟨different, absent⟩
      exact Nat.succ_lt_succ (ih tail)

theorem erase_unique (id : Nat) (xs : List Entry) (h : Unique xs) :
    Unique (erase id xs) := by
  induction xs with
  | nil => trivial
  | cons x xs ih =>
    unfold erase
    split
    · exact ih h.2
    · exact ⟨erase_preserves_absent x.identity id xs h.1, ih h.2⟩

theorem victim_none_iff (xs : List Entry) : victim xs = none ↔ xs = [] := by
  cases xs with
  | nil => exact ⟨fun _ => rfl, fun _ => rfl⟩
  | cons x xs =>
    constructor
    · intro h
      unfold victim at h
      cases hv : victim xs with
      | none => rw [hv] at h; cases h
      | some y => rw [hv] at h; cases h
    · intro h; cases h

theorem victim_member_minimum (xs : List Entry) (v : Entry) (h : victim xs = some v) :
    v ∈ xs ∧ ∀ e ∈ xs, v.frequency ≤ e.frequency := by
  induction xs generalizing v with
  | nil => cases h
  | cons x xs ih =>
    unfold victim at h
    cases tail : victim xs with
    | none =>
      rw [tail] at h
      have same := Option.some.inj h
      subst v
      constructor
      · exact .head _
      · intro e member
        cases member_cons.mp member with
        | inl same => subst e; exact Nat.le_refl _
        | inr tailmember =>
          have empty := (victim_none_iff xs).mp tail
          rw [empty] at tailmember
          cases tailmember
    | some y =>
      rw [tail] at h
      change some (if y.frequency < x.frequency then y else x) = some v at h
      obtain ⟨present, minimal⟩ := ih y tail
      by_cases improve : y.frequency < x.frequency
      · rw [ite_eq_left improve] at h
        have same := Option.some.inj h
        subst v
        constructor
        · exact .tail _ present
        · intro e member
          cases member_cons.mp member with
          | inl same => subst e; exact Nat.le_of_lt improve
          | inr member => exact minimal e member
      · rw [ite_eq_right improve] at h
        have same := Option.some.inj h
        subst v
        constructor
        · exact .head _
        · intro e member
          cases member_cons.mp member with
          | inl same => subst e; exact Nat.le_refl _
          | inr member =>
            exact Nat.le_trans (Nat.le_of_not_gt improve) (minimal e member)

/-- Strictly smaller suffix winners replace the head; an equal tie never does. -/
theorem victim_oldest_tie (x y : Entry) (xs : List Entry)
    (suffix : victim xs = some y) (tie : x.frequency = y.frequency) :
    victim (x :: xs) = some x := by
  unfold victim
  rw [suffix]
  change some (if y.frequency < x.frequency then y else x) = some x
  have cannotImprove : ¬ y.frequency < x.frequency := by
    rw [tie]
    exact Nat.lt_irrefl _
  rw [ite_eq_right cannotImprove]

theorem survivors_length_lt (capacity : Nat) (xs : List Entry)
    (positive : capacity > 0) (bounded : xs.length ≤ capacity) :
    (survivors capacity xs).length < capacity := by
  unfold survivors
  split
  · assumption
  · rename_i full
    cases hv : victim xs with
    | none => exact positive
    | some v =>
      change (erase v.identity xs).length < capacity
      have member := (victim_member_minimum xs v hv).1
      have absent := member_not_absent v.identity xs v member rfl
      have length := erase_length_lt v.identity xs absent
      exact Nat.lt_of_lt_of_le length bounded

theorem constructive_length_append (xs ys : List Entry) :
    (xs ++ ys).length = xs.length + ys.length := by
  induction xs with
  | nil => exact (Nat.zero_add ys.length).symm
  | cons x xs ih =>
    change (xs ++ ys).length + 1 = xs.length + 1 + ys.length
    rw [ih]
    exact (Nat.succ_add xs.length ys.length).symm

theorem admit_bounded (capacity id : Nat) (xs : List Entry)
    (bounded : xs.length ≤ capacity) : (admission capacity id xs).length ≤ capacity := by
  unfold admission
  split
  · rename_i zero; rw [zero]; exact Nat.le_refl 0
  · rename_i nonzero
    split
    · have short := survivors_length_lt capacity xs (Nat.pos_of_ne_zero nonzero) bounded
      rw [constructive_length_append]
      change (survivors capacity xs).length + 1 ≤ capacity
      exact Nat.succ_le_of_lt short
    · exact bounded

theorem admit_duplicate_unchanged (capacity id : Nat) (xs : List Entry)
    (positive : capacity > 0) (present : ¬Absent id xs) : admission capacity id xs = xs := by
  unfold admission
  rw [ite_eq_right (Nat.ne_of_gt positive), ite_eq_right present]

theorem absent_append (id : Nat) (xs ys : List Entry) :
    Absent id (xs ++ ys) ↔ Absent id xs ∧ Absent id ys := by
  induction xs with
  | nil => exact ⟨fun h => ⟨trivial, h⟩, fun h => h.2⟩
  | cons x xs ih =>
    constructor
    · intro h
      obtain ⟨left, right⟩ := ih.mp h.2
      exact ⟨⟨h.1, left⟩, right⟩
    · intro h
      exact ⟨h.1.1, ih.mpr ⟨h.1.2, h.2⟩⟩

theorem unique_append_new (xs : List Entry) (e : Entry)
    (unique : Unique xs) (absent : Absent e.identity xs) : Unique (xs ++ [e]) := by
  induction xs with
  | nil => exact ⟨trivial, trivial⟩
  | cons x xs ih =>
    constructor
    · apply (absent_append x.identity xs [e]).mpr
      exact ⟨unique.1, ⟨Ne.symm absent.1, trivial⟩⟩
    · exact ih unique.2 absent.2

theorem survivors_unique (capacity : Nat) (xs : List Entry) (h : Unique xs) :
    Unique (survivors capacity xs) := by
  unfold survivors
  split
  · exact h
  · cases victim xs with
    | none => trivial
    | some v => exact erase_unique v.identity xs h

theorem survivors_absent (capacity id : Nat) (xs : List Entry) (h : Absent id xs) :
    Absent id (survivors capacity xs) := by
  unfold survivors
  split
  · exact h
  · cases victim xs with
    | none => trivial
    | some v => exact erase_preserves_absent id v.identity xs h

theorem admit_unique (capacity id : Nat) (xs : List Entry) (h : Unique xs) :
    Unique (admission capacity id xs) := by
  unfold admission
  split
  · trivial
  · split
    · rename_i absent
      exact unique_append_new _ _ (survivors_unique capacity xs h)
        (survivors_absent capacity id xs absent)
    · exact h

theorem appended_member (xs : List Entry) (x : Entry) : x ∈ xs ++ [x] := by
  induction xs with
  | nil => exact .head _
  | cons y xs ih => exact .tail _ ih

theorem fresh_admission_present (capacity id : Nat) (xs : List Entry)
    (positive : capacity > 0) (fresh : Absent id xs) :
    Entry.mk id 1 ∈ admission capacity id xs := by
  unfold admission
  rw [ite_eq_right (Nat.ne_of_gt positive), ite_eq_left fresh]
  exact appended_member _ _

theorem bump_nondecreasing (limit value : Nat) : value ≤ bump limit value := by
  unfold bump
  split
  · exact Nat.le_succ value
  · exact Nat.le_refl value

theorem bump_bounded (limit value : Nat) (bounded : value ≤ limit) :
    bump limit value ≤ limit := by
  unfold bump
  split
  · exact Nat.succ_le_of_lt (by assumption)
  · exact bounded

theorem bump_saturated (limit : Nat) : bump limit limit = limit := by
  unfold bump
  rw [ite_eq_right (Nat.lt_irrefl limit)]

theorem mismatch_length (limit id : Nat) (xs : List Entry) :
    (mismatch limit id xs).length = xs.length := by
  induction xs with
  | nil => rfl
  | cons x xs ih => exact congrArg Nat.succ ih

theorem mismatch_absent_iff (limit target changed : Nat) (xs : List Entry) :
    Absent target (mismatch limit changed xs) ↔ Absent target xs := by
  induction xs with
  | nil => exact ⟨fun h => h, fun h => h⟩
  | cons x xs ih =>
    constructor
    · intro h; exact ⟨h.1, ih.mp h.2⟩
    · intro h; exact ⟨h.1, ih.mpr h.2⟩

theorem mismatch_unique (limit id : Nat) (xs : List Entry) (unique : Unique xs) :
    Unique (mismatch limit id xs) := by
  induction xs with
  | nil => trivial
  | cons x xs ih =>
    exact ⟨(mismatch_absent_iff limit x.identity id xs).mpr unique.1, ih unique.2⟩

theorem mismatch_valid_frequencies (limit id : Nat) (xs : List Entry)
    (valid : ValidFrequencies limit xs) : ValidFrequencies limit (mismatch limit id xs) := by
  induction xs with
  | nil => trivial
  | cons x xs ih =>
    constructor
    · change (if x.identity = id then bump limit x.frequency else x.frequency) ≤ limit
      split
      · exact bump_bounded limit x.frequency valid.1
      · exact valid.1
    · exact ih valid.2

def lookup (id : Nat) : List Entry → Option Entry
  | [] => none
  | x :: xs => if x.identity = id then some x else lookup id xs

theorem mismatch_identity_order (limit id : Nat) (xs : List Entry) :
    (mismatch limit id xs).map Entry.identity = xs.map Entry.identity := by
  induction xs with
  | nil => rfl
  | cons x xs ih => exact congrArg (List.cons x.identity) ih

theorem mismatch_retained_hit (limit id : Nat) (xs : List Entry) (entry : Entry)
    (found : lookup id xs = some entry) :
    lookup id (mismatch limit id xs) =
      some { entry with frequency := bump limit entry.frequency } := by
  induction xs with
  | nil => cases found
  | cons x xs ih =>
    unfold lookup at found
    by_cases head : x.identity = id
    · rw [ite_eq_left head] at found
      have same := Option.some.inj found
      subst entry
      unfold mismatch lookup
      rw [ite_eq_left head]
      change some { x with frequency := if x.identity = id then bump limit x.frequency else x.frequency } =
        some { x with frequency := bump limit x.frequency }
      rw [ite_eq_left head]
    · rw [ite_eq_right head] at found
      unfold mismatch lookup
      rw [ite_eq_right head]
      exact ih found

def refresh (capacity : Nat) (newIds : List Nat) (xs : List Entry) : List Entry :=
  match newIds with
  | [] => xs
  | id :: ids => refresh capacity ids (admission capacity id xs)

theorem continuous_admissions_bounded (capacity : Nat) (ids : List Nat)
    (xs : List Entry) (bounded : xs.length ≤ capacity) :
    (refresh capacity ids xs).length ≤ capacity := by
  induction ids generalizing xs with
  | nil => exact bounded
  | cons id ids ih => exact ih _ (admit_bounded capacity id xs bounded)

theorem continuous_admissions_unique (capacity : Nat) (ids : List Nat)
    (xs : List Entry) (unique : Unique xs) : Unique (refresh capacity ids xs) := by
  induction ids generalizing xs with
  | nil => exact unique
  | cons id ids ih => exact ih _ (admit_unique capacity id xs unique)

structure State where
  context : Nat
  entries : List Entry
  epoch : Nat
  deriving DecidableEq

/-- A saturating diagnostic epoch is not an injective snapshot identity. -/
def changed (limit : Nat) (state : State) : State :=
  { state with epoch := bump limit state.epoch }

def replaceContext (context : Nat) (state : State) : State :=
  if context = state.context then state else ⟨context, [], 0⟩

theorem diagnostic_version_nondecreasing (limit : Nat) (state : State) :
    state.epoch ≤ (changed limit state).epoch := bump_nondecreasing limit state.epoch

theorem diagnostic_version_saturates (limit : Nat) (state : State)
    (full : state.epoch = limit) : (changed limit state).epoch = state.epoch := by
  change bump limit state.epoch = state.epoch
  rw [full, bump_saturated]

theorem context_switch_discards_pool (context : Nat) (state : State)
    (different : context ≠ state.context) :
    replaceContext context state = ⟨context, [], 0⟩ := by
  unfold replaceContext
  rw [ite_eq_right different]

theorem same_context_preserves_pool (state : State) :
    replaceContext state.context state = state := by
  unfold replaceContext
  rw [ite_eq_left rfl]

/-- Completed result snapshots never represent running evaluation work. -/
inductive Decision where
  | fresh
  | join
  deriving DecidableEq

def begin (body : Nat) (inflight : Option Nat) : Decision :=
  match inflight with
  | none => .fresh
  | some running => if body = running then .join else .fresh

def completed (_inflight : Option Nat) : Option Nat := none

theorem completed_request_is_fresh (body : Nat) (inflight : Option Nat) :
    begin body (completed inflight) = .fresh := rfl

theorem identical_inflight_may_join (body : Nat) : begin body (some body) = .join := by
  change (if body = body then Decision.join else Decision.fresh) = Decision.join
  rw [ite_eq_left rfl]

/-- Explicit witness for oldest-tie eviction followed by bounded admission. -/
theorem oldest_tie_witness :
    admission 2 3 [⟨1, 1⟩, ⟨2, 1⟩] = [⟨2, 1⟩, ⟨3, 1⟩] := by decide

/-- A repeatedly mismatching entry survives a colder older one. -/
theorem mismatch_retention_witness :
    admission 2 3 (mismatch 2147483647 1 [⟨1, 1⟩, ⟨2, 1⟩]) = [⟨1, 2⟩, ⟨3, 1⟩] := by decide

end LFU
