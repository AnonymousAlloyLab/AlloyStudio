import AlloyStudio.RawAst

/-!
Ordered, single-node edits over occurrence forests.

These executable local operations model the promotion and consecutive adoption
performed by `RawAstTrace.replay`/`insertTarget`. Contexts place a local operation
at any depth, including the forest under the virtual root. The virtual root is
represented by `Context.top`, not a counted node. Intermediate forests carry no
Alloy typing requirement. These are semantic foundations for L04; they are not
a proof that Java replay implements the semantics (L10/L22).
-/

namespace AlloyStudio.Edits

open RawAst

/-- The number of roots, as distinct from the total number of nodes. -/
def width : Forest → Nat
  | .nil => 0
  | .cons _ tail => width tail + 1

/-- Split at a sibling boundary; out-of-range positions fail explicitly. -/
def splitRoots : Nat → Forest → Option (Forest × Forest)
  | 0, forest => some (.nil, forest)
  | _ + 1, .nil => none
  | index + 1, .cons head tail => do
      let (before, after) ← splitRoots index tail
      pure (.cons head before, after)

theorem splitRoots_append (before after : Forest) :
    splitRoots (width before) (before ++ after) = some (before, after) := by
  cases before with
  | nil => rfl
  | cons head tail =>
    change (do
      let (leading, suffix) ← splitRoots (width tail) (tail ++ after)
      pure (Forest.cons head leading, suffix)) = some (Forest.cons head tail, after)
    rw [splitRoots_append tail after]
    rfl

/-- Insert one node around precisely a contiguous interval of siblings. -/
def adopt (before children after : Forest) (id : OccurrenceId) (label : RawLabel) : Forest :=
  before ++ .cons (.node id label children) after

/-- Delete one node, preserving and promoting all of its ordered children. -/
def promote (before children after : Forest) : Forest :=
  (before ++ children) ++ after

def deleteAt (position : Nat) (forest : Forest) : Option Forest := do
  let (before, rest) ← splitRoots position forest
  match rest with
  | .nil => none
  | .cons (.node _ _ children) after => pure (promote before children after)

def insertAt (position childCount : Nat) (id : OccurrenceId) (label : RawLabel)
    (forest : Forest) : Option Forest := do
  let (before, rest) ← splitRoots position forest
  let (children, after) ← splitRoots childCount rest
  pure (adopt before children after id label)

def relabelAt (position : Nat) (label : RawLabel) (forest : Forest) : Option Forest := do
  let (before, rest) ← splitRoots position forest
  match rest with
  | .nil => none
  | .cons (.node id _ children) after => pure (adopt before children after id label)

theorem deleteAt_adopt (before children after : Forest) (id : OccurrenceId) (label : RawLabel) :
    deleteAt (width before) (adopt before children after id label) =
      some (promote before children after) := by
  unfold deleteAt adopt
  rw [splitRoots_append]
  rfl

theorem insertAt_promote (before children after : Forest) (id : OccurrenceId) (label : RawLabel) :
    insertAt (width before) (width children) id label (promote before children after) =
      some (adopt before children after id label) := by
  unfold insertAt promote
  rw [Forest.append_assoc, splitRoots_append]
  change (do
    let (adopted, suffix) ← splitRoots (width children) (children ++ after)
    pure (adopt before adopted suffix id label)) = some (adopt before children after id label)
  rw [splitRoots_append]
  rfl

theorem relabelAt_adopt (before children after : Forest) (id : OccurrenceId)
    (oldLabel newLabel : RawLabel) :
    relabelAt (width before) newLabel (adopt before children after id oldLabel) =
      some (adopt before children after id newLabel) := by
  unfold relabelAt adopt
  rw [splitRoots_append]
  rfl

theorem adopt_count (before children after : Forest) (id : OccurrenceId) (label : RawLabel) :
    (adopt before children after id label).count =
      (promote before children after).count + 1 := by
  unfold adopt promote
  rw [Forest.count_append, Forest.count_append, Forest.count_append]
  change before.count + (children.count + 1 + after.count) =
    (before.count + children.count + after.count) + 1
  rw [Nat.add_right_comm children.count 1 after.count, ← Nat.add_assoc,
    ← Nat.add_assoc]

theorem relabel_preserves_count (before children after : Forest) (id : OccurrenceId)
    (oldLabel newLabel : RawLabel) :
    (adopt before children after id oldLabel).count =
      (adopt before children after id newLabel).count := by
  unfold adopt
  rw [Forest.count_append, Forest.count_append]
  rfl

theorem adopt_occurrences (before children after : Forest) (id : OccurrenceId) (label : RawLabel) :
    (adopt before children after id label).occurrences =
      before.occurrences ++ ((children.occurrences ++ [id]) ++ after.occurrences) :=
  Forest.occurrences_append before (.cons (.node id label children) after)

theorem promote_occurrences (before children after : Forest) :
    (promote before children after).occurrences =
      (before.occurrences ++ children.occurrences) ++ after.occurrences := by
  unfold promote
  rw [Forest.occurrences_append, Forest.occurrences_append]

theorem relabel_preserves_occurrences (before children after : Forest) (id : OccurrenceId)
    (oldLabel newLabel : RawLabel) :
    (adopt before children after id oldLabel).occurrences =
      (adopt before children after id newLabel).occurrences :=
  (adopt_occurrences before children after id oldLabel).trans
    (adopt_occurrences before children after id newLabel).symm

theorem promote_occurrences_sublist (before children after : Forest) (id : OccurrenceId)
    (label : RawLabel) :
    (promote before children after).occurrences.Sublist
      (adopt before children after id label).occurrences := by
  rw [promote_occurrences, adopt_occurrences, sequence_append_assoc]
  exact (List.Sublist.refl before.occurrences).append
    ((List.sublist_append_left children.occurrences [id]).append
      (List.Sublist.refl after.occurrences))

theorem promotion_preserves_unique_occurrences (before children after : Forest)
    (id : OccurrenceId) (label : RawLabel)
    (unique : (adopt before children after id label).UniqueOccurrences) :
    (promote before children after).UniqueOccurrences :=
  unique.sublist (promote_occurrences_sublist before children after id label)

/-- A hole under any number of real ancestors; top is the uncounted sentinel. -/
inductive Context where
  | top
  | inside (outer : Context) (before : Forest) (id : OccurrenceId)
      (label : RawLabel) (after : Forest)
    deriving Repr

def Context.plug : Context → Forest → Forest
  | .top, forest => forest
  | .inside outer before id label after, forest => outer.plug (adopt before forest after id label)

/-- Every constructor performs exactly one node edit. Matching is script reflexivity. -/
inductive Step : Forest → Forest → Prop where
  | delete (context : Context) (before children after : Forest)
      (id : OccurrenceId) (label : RawLabel) :
      Step (context.plug (adopt before children after id label))
        (context.plug (promote before children after))
  | insert (context : Context) (before children after : Forest)
      (id : OccurrenceId) (label : RawLabel) :
      Step (context.plug (promote before children after))
        (context.plug (adopt before children after id label))
  | relabel (context : Context) (before children after : Forest)
      (id : OccurrenceId) (oldLabel newLabel : RawLabel) :
      Step (context.plug (adopt before children after id oldLabel))
        (context.plug (adopt before children after id newLabel))

theorem Step.inverse {before after : Forest} (step : Step before after) : Step after before := by
  cases step with
  | delete context before children after id label =>
    exact Step.insert context before children after id label
  | insert context before children after id label =>
    exact Step.delete context before children after id label
  | relabel context before children after id oldLabel newLabel =>
    exact Step.relabel context before children after id newLabel oldLabel

inductive Script : Forest → Forest → Nat → Prop where
  | refl (forest : Forest) : Script forest forest 0
  | cons {before middle after : Forest} {cost : Nat}
      (step : Step before middle) (rest : Script middle after cost) :
      Script before after (cost + 1)

theorem Script.single {before after : Forest} (step : Step before after) :
    Script before after 1 := Script.cons step (Script.refl after)

theorem Script.append {before middle after : Forest} {leftCost rightCost : Nat}
    (left : Script before middle leftCost) (right : Script middle after rightCost) :
    Script before after (leftCost + rightCost) := by
  induction left with
  | refl forest => exact (Nat.zero_add rightCost).symm ▸ right
  | @cons before next middle cost step rest ih =>
    exact (Nat.succ_add cost rightCost).symm ▸ Script.cons step (ih right)

theorem Script.inverse {before after : Forest} {cost : Nat}
    (script : Script before after cost) : Script after before cost := by
  induction script with
  | refl forest => exact Script.refl forest
  | cons step rest ih => exact ih.append (Script.single step.inverse)

/-- Unit costs are preserved by inversion of every finite edit script. -/
theorem edit_inverse_and_cost {before after : Forest} {cost : Nat}
    (script : Script before after cost) : Script after before cost := script.inverse

/-- A root deletion promotes its children rather than deleting the subtree. -/
theorem root_deletion_keeps_children (id : OccurrenceId) (label : RawLabel) (children : Forest) :
    deleteAt 0 (.cons (.node id label children) .nil) = some children := by
  change some (children ++ Forest.nil) = some children
  exact congrArg some (Forest.append_nil children)

theorem insertion_into_empty (id : OccurrenceId) (label : RawLabel) :
    insertAt 0 0 id label .nil = some (.cons (.node id label .nil) .nil) := rfl

end AlloyStudio.Edits
