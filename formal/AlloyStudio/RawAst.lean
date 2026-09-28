import Std

/-!
Finite ordered occurrence trees for the raw metric.

`RawLabel` is deliberately the Java adapter's already encoded string. This
module does not replace that string with an injective tagged encoding: the
adapter's constructor coverage and the injectivity of its concatenations are
separate, presently unproved, implementation-correspondence obligations (L01,
L02). In particular, this module makes no claim about parsing Alloy, selecting
`Predicate.getBody()`, `Body`/NOOP retention, or Java object identity.

An occurrence identifier represents an original node independently of its
label or source position. The inductive types contain no sharing or cycles;
`UniqueOccurrences` is a separate, explicit property of assigned identifiers.
There is no implicit sorting, alpha-renaming, or wrapper removal.
-/

namespace AlloyStudio.RawAst

abbrev RawLabel := String
abbrev OccurrenceId := Nat

/-- Constructive list support, kept independent of extensionality axioms. -/
theorem sequence_append_assoc {α : Type} (left middle right : List α) :
    (left ++ middle) ++ right = left ++ (middle ++ right) := by
  cases left with
  | nil => rfl
  | cons head tail => exact congrArg (List.cons head) (sequence_append_assoc tail middle right)

theorem sequence_length_append {α : Type} (left right : List α) :
    (left ++ right).length = left.length + right.length := by
  cases left with
  | nil => exact (Nat.zero_add right.length).symm
  | cons head tail =>
    exact (congrArg Nat.succ (sequence_length_append tail right)).trans
      (Nat.succ_add tail.length right.length).symm

mutual
  inductive Tree where
    | node (occurrence : OccurrenceId) (label : RawLabel) (children : Forest) : Tree
    deriving Repr, DecidableEq

  inductive Forest where
    | nil : Forest
    | cons (head : Tree) (tail : Forest) : Forest
    deriving Repr, DecidableEq
end

namespace Forest

def append : Forest → Forest → Forest
  | .nil, right => right
  | .cons head tail, right => .cons head (append tail right)

instance : Append Forest := ⟨append⟩

@[simp] theorem nil_append (right : Forest) : Forest.nil ++ right = right := rfl

@[simp] theorem cons_append (head : Tree) (tail right : Forest) :
    Forest.cons head tail ++ right = Forest.cons head (tail ++ right) := rfl

@[simp] theorem append_nil (forest : Forest) : forest ++ Forest.nil = forest := by
  cases forest with
  | nil => rfl
  | cons head tail => exact congrArg (Forest.cons head) (append_nil tail)

theorem append_assoc (left middle right : Forest) :
    (left ++ middle) ++ right = left ++ (middle ++ right) := by
  cases left with
  | nil => rfl
  | cons head tail => exact congrArg (Forest.cons head) (append_assoc tail middle right)

def singleton (tree : Tree) : Forest := .cons tree .nil

end Forest

mutual
  def Tree.count : Tree → Nat
    | .node _ _ children => children.count + 1

  def Forest.count : Forest → Nat
    | .nil => 0
    | .cons head tail => head.count + tail.count
end

mutual
  /-- Original occurrence tokens in ordered postorder. -/
  def Tree.occurrences : Tree → List OccurrenceId
    | .node occurrence _ children => children.occurrences ++ [occurrence]

  def Forest.occurrences : Forest → List OccurrenceId
    | .nil => []
    | .cons head tail => head.occurrences ++ tail.occurrences
end

def Forest.UniqueOccurrences (forest : Forest) : Prop :=
  forest.occurrences.Nodup

@[simp] theorem Forest.count_append (left right : Forest) :
    (left ++ right).count = left.count + right.count := by
  cases left with
  | nil => exact (Nat.zero_add right.count).symm
  | cons head tail =>
    exact (congrArg (head.count + ·) (count_append tail right)).trans
      (Nat.add_assoc head.count tail.count right.count).symm

@[simp] theorem Forest.occurrences_append (left right : Forest) :
    (left ++ right).occurrences = left.occurrences ++ right.occurrences := by
  cases left with
  | nil => rfl
  | cons head tail =>
    exact (congrArg (head.occurrences ++ ·) (occurrences_append tail right)).trans
      (sequence_append_assoc head.occurrences tail.occurrences right.occurrences).symm

mutual
  theorem Tree.occurrences_length (tree : Tree) :
      tree.occurrences.length = tree.count := by
    cases tree with
    | node occurrence label children =>
      exact (sequence_length_append children.occurrences [occurrence]).trans
        (congrArg (· + 1) (Forest.occurrences_length children))

  theorem Forest.occurrences_length (forest : Forest) :
      forest.occurrences.length = forest.count := by
    cases forest with
    | nil => rfl
    | cons head tail =>
      exact (sequence_length_append head.occurrences tail.occurrences).trans
        ((congrArg (· + tail.occurrences.length) (Tree.occurrences_length head)).trans
          (congrArg (head.count + ·) (Forest.occurrences_length tail)))
end

theorem Tree.count_positive (tree : Tree) : 0 < tree.count := by
  cases tree with
  | node occurrence label children => exact Nat.zero_lt_succ children.count

/-- Labels carry the original spelling; equal source spans are irrelevant. -/
theorem differently_spelled_leaves_differ (occurrence : OccurrenceId)
    (left right : RawLabel) (different : left ≠ right) :
    Tree.node occurrence left .nil ≠ Tree.node occurrence right .nil := by
  intro equal
  cases equal
  exact different rfl

/-- Equal text is permitted at distinct occurrences and does not identify them. -/
theorem distinct_occurrences_differ (left right : OccurrenceId)
    (label : RawLabel) (different : left ≠ right) :
    Tree.node left label .nil ≠ Tree.node right label .nil := by
  intro equal
  cases equal
  exact different rfl

end AlloyStudio.RawAst
