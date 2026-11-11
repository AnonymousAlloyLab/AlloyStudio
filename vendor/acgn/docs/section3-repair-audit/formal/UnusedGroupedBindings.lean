import Std

namespace UnusedGroupedBindings

-- A retained member of the same admissible-value domain supplies the witness.
-- A may itself be a type of relations; no nonempty-carrier assumption is needed.
theorem all_drop_unused {A : Type} (d : A → Prop) (body : A → Prop) :
    (∀ x, d x → ∀ _y, d _y → body x) ↔ (∀ x, d x → body x) := by
  constructor
  · intro h x hx; exact h x hx x hx
  · intro h x hx _y _hy; exact h x hx

theorem some_drop_unused {A : Type} (d : A → Prop) (body : A → Prop) :
    (∃ x, d x ∧ ∃ _y, d _y ∧ body x) ↔ (∃ x, d x ∧ body x) := by
  constructor
  · rintro ⟨x,hx,_y,_hy,h⟩; exact ⟨x,hx,h⟩
  · rintro ⟨x,hx,h⟩; exact ⟨x,hx,x,hx,h⟩

theorem no_drop_unused {A : Type} (d : A → Prop) (body : A → Prop) :
    (¬ ∃ x, d x ∧ ∃ _y, d _y ∧ body x) ↔ (¬ ∃ x, d x ∧ body x) := by
  constructor
  · intro h hsmall; exact h ((some_drop_unused d body).mpr hsmall)
  · intro h hbig; exact h ((some_drop_unused d body).mp hbig)

-- The body may contain the remaining binders, dependent domains and phases.
-- Unused means independent of the removed value across that whole body.
theorem all_drop_first {A : Type} (d : A → Prop) (body : A → Prop) :
    (∀ _y, d _y → ∀ x, d x → body x) ↔ (∀ x, d x → body x) := by
  constructor
  · intro h x hx; exact h x hx x hx
  · intro h _y _hy x hx; exact h x hx

theorem some_drop_first {A : Type} (d : A → Prop) (body : A → Prop) :
    (∃ _y, d _y ∧ ∃ x, d x ∧ body x) ↔ (∃ x, d x ∧ body x) := by
  constructor
  · rintro ⟨_y,_hy,x,hx,h⟩; exact ⟨x,hx,h⟩
  · rintro ⟨x,hx,h⟩; exact ⟨x,hx,x,hx,h⟩

def referenceBody {U P : Type} (posts : U → P → Prop) (ad : P → Prop) (u : U) :=
  ∀ a, ad a → posts u a → ∀ p, posts u p → ad p

theorem d8_reference {U P : Type} (posts : U → P → Prop) (ad : P → Prop) :
    (∀ u1 : U, ∀ _u2 : U, referenceBody posts ad u1) ↔
    (∀ u1 : U, referenceBody posts ad u1) := by
  constructor
  · intro h u1; exact h u1 u1
  · intro h u1 _u2; exact h u1

-- Exact old observed/declared gate for the incident, not a soundness premise.
theorem old_gate_rejects : ([0] : List Nat) ≠ [0,1] := by decide
theorem reduced_gate_accepts : ([0] : List Nat) = [0] := rfl

theorem all_vacuous {A : Type} (d : A → Prop) (b : Prop) :
    (∀ x, d x → b) ↔ ((∃ x, d x) → b) := by
  constructor
  · intro h ⟨x,hx⟩; exact h x hx
  · intro h x hx; exact h ⟨x,hx⟩

theorem some_vacuous {A : Type} (d : A → Prop) (b : Prop) :
    (∃ x, d x ∧ b) ↔ (∃ x, d x) ∧ b := by
  constructor
  · rintro ⟨x,hx,hb⟩; exact ⟨⟨x,hx⟩,hb⟩
  · rintro ⟨⟨x,hx⟩,hb⟩; exact ⟨x,hx,hb⟩

def One {A : Type} (p : A → Prop) := ∃ x, p x ∧ ∀ y, p y → y = x
def Lone {A : Type} (p : A → Prop) := ∀ x y, p x → p y → x = y

-- An unused independent coordinate still multiplies the assignment count.
theorem one_product {A B : Type} (p : A → Prop) (d : B → Prop) :
    One (fun z : A × B => p z.1 ∧ d z.2) ↔ One p ∧ One d := by
  constructor
  · rintro ⟨⟨x,y⟩,⟨hx,hy⟩,h⟩
    exact ⟨⟨x,hx,fun a ha => congrArg Prod.fst (h (a,y) ⟨ha,hy⟩)⟩,
      ⟨y,hy,fun b hb => congrArg Prod.snd (h (x,b) ⟨hx,hb⟩)⟩⟩
  · rintro ⟨⟨x,hx,ux⟩,⟨y,hy,uy⟩⟩
    refine ⟨(x,y),⟨hx,hy⟩,?_⟩
    rintro ⟨a,b⟩ ⟨ha,hb⟩
    exact Prod.ext (ux a ha) (uy b hb)

theorem lone_product {A B : Type} (p : A → Prop) (d : B → Prop) :
    Lone (fun z : A × B => p z.1 ∧ d z.2) ↔
    ((∃ y, d y) → Lone p) ∧ ((∃ x, p x) → Lone d) := by
  constructor
  · intro h
    constructor
    · rintro ⟨y,hy⟩ x x' hx hx'
      exact congrArg Prod.fst (h (x,y) (x',y) ⟨hx,hy⟩ ⟨hx',hy⟩)
    · rintro ⟨x,hx⟩ y y' hy hy'
      exact congrArg Prod.snd (h (x,y) (x,y') ⟨hx,hy⟩ ⟨hx,hy'⟩)
  · rintro ⟨hp,hd⟩ ⟨x,y⟩ ⟨x',y'⟩ ⟨hx,hy⟩ ⟨hx',hy'⟩
    exact Prod.ext (hp ⟨y,hy⟩ x x' hx hx') (hd ⟨x,hx⟩ y y' hy hy')

-- Any positive number of independent removed coordinates has the same
-- nonempty/unique/at-most-one tests as their common admissible domain.
theorem exists_assignments {A I : Type} (i : I) (d : A → Prop) :
    (∃ f : I → A, ∀ j, d (f j)) ↔ (∃ a, d a) := by
  constructor
  · rintro ⟨f,h⟩; exact ⟨f i,h i⟩
  · rintro ⟨a,h⟩; exact ⟨fun _ => a,fun _ => h⟩

theorem one_assignments {A I : Type} (i : I) (d : A → Prop) :
    One (fun f : I → A => ∀ j, d (f j)) ↔ One d := by
  constructor
  · rintro ⟨f,h,u⟩
    exact ⟨f i,h i,fun a ha => congrArg (fun g => g i) (u (fun _ => a) (fun _ => ha))⟩
  · rintro ⟨a,ha,u⟩
    exact ⟨fun _ => a,fun _ => ha,fun f hf => funext (fun j => u (f j) (hf j))⟩

theorem lone_assignments {A I : Type} (i : I) (d : A → Prop) :
    Lone (fun f : I → A => ∀ j, d (f j)) ↔ Lone d := by
  constructor
  · intro h a b ha hb
    exact congrArg (fun f => f i) (h (fun _ => a) (fun _ => b) (fun _ => ha) (fun _ => hb))
  · intro h f g hf hg
    exact funext (fun j => h (f j) (g j) (hf j) (hg j))

theorem one_fibers {A B : Type} (p : A → Prop) (d : A → B → Prop) :
    One (fun z : A × B => p z.1 ∧ d z.1 z.2) ↔
    One (fun a => p a ∧ ∃ b, d a b) ∧ (∀ a, p a → Lone (d a)) := by
  constructor
  · rintro ⟨⟨a,b⟩,⟨ha,hb⟩,u⟩
    constructor
    · refine ⟨a,⟨ha,b,hb⟩,?_⟩
      rintro a' ⟨ha',b',hb'⟩
      exact congrArg Prod.fst (u (a',b') ⟨ha',hb'⟩)
    · intro a' ha' b1 b2 h1 h2
      exact (congrArg Prod.snd (u (a',b1) ⟨ha',h1⟩)).trans
        (congrArg Prod.snd (u (a',b2) ⟨ha',h2⟩)).symm
  · rintro ⟨⟨a,⟨ha,b,hb⟩,u⟩,v⟩
    refine ⟨(a,b),⟨ha,hb⟩,?_⟩
    rintro ⟨a',b'⟩ ⟨ha',hb'⟩
    have eq := u a' ⟨ha',b',hb'⟩
    subst a'
    exact Prod.ext rfl (v a ha b' b hb' hb)

theorem lone_fibers {A B : Type} (p : A → Prop) (d : A → B → Prop) :
    Lone (fun z : A × B => p z.1 ∧ d z.1 z.2) ↔
    Lone (fun a => p a ∧ ∃ b, d a b) ∧ (∀ a, p a → Lone (d a)) := by
  constructor
  · intro h
    constructor
    · rintro a a' ⟨ha,b,hb⟩ ⟨ha',b',hb'⟩
      exact congrArg Prod.fst (h (a,b) (a',b') ⟨ha,hb⟩ ⟨ha',hb'⟩)
    · intro a ha b b' hb hb'
      exact congrArg Prod.snd (h (a,b) (a,b') ⟨ha,hb⟩ ⟨ha,hb'⟩)
  · rintro ⟨h,v⟩ ⟨a,b⟩ ⟨a',b'⟩ ⟨ha,hb⟩ ⟨ha',hb'⟩
    have eq := h a a' ⟨ha,b,hb⟩ ⟨ha',b',hb'⟩
    subst a'
    exact Prod.ext rfl (v a ha b b' hb hb')

-- Last-variable deletion is not generally valid on an empty domain.
theorem empty_all_counterexample : (∀ x : Empty, False) ∧ ¬ False := by
  constructor
  · intro x; exact nomatch x
  · intro h; exact h
theorem empty_some_counterexample : ¬ (∃ _x : Empty, True) ∧ True := by
  constructor
  · rintro ⟨x,_⟩; exact nomatch x
  · trivial
theorem disjoint_counterexample :
    ¬ (∃ x y : Unit, x ≠ y ∧ True) ∧ (∃ _x : Unit, True) := by
  constructor
  · rintro ⟨x,y,h,_⟩; exact h (Subsingleton.elim x y)
  · exact ⟨(),trivial⟩

theorem all_after_tautology {A : Type} (d p : A → Prop) :
    (∀ x, d x → (p x ∨ ¬ p x)) ↔ True := by
  constructor
  · intro _; trivial
  · intro _ x _; exact Classical.em (p x)

theorem some_after_tautology {A : Type} (d p : A → Prop) :
    (∃ x, d x ∧ (p x ∨ ¬ p x)) ↔ (∃ x, d x) := by
  constructor
  · rintro ⟨x,hx,_⟩; exact ⟨x,hx⟩
  · rintro ⟨x,hx⟩; exact ⟨x,hx,Classical.em (p x)⟩

theorem unused_subset_some {A : Type} (d : A → Prop) (b : Prop) :
    (∃ s : A → Prop, (∀ x, s x → d x) ∧ b) ↔ b := by
  constructor
  · rintro ⟨_,_,hb⟩; exact hb
  · intro hb; exact ⟨fun _ => False,fun _ h => False.elim h,hb⟩

theorem unused_subset_all {A : Type} (d : A → Prop) (b : Prop) :
    (∀ s : A → Prop, (∀ x, s x → d x) → b) ↔ b := by
  constructor
  · intro h; exact h (fun _ => False) (fun _ hf => False.elim hf)
  · intro hb _ _; exact hb

-- A repeated display name does not identify two declaration occurrences.
theorem original_declarations_distinct : ([0,1,2] : List Nat).Nodup := by decide
theorem overwritten_declaration_inventory : ¬ ([1,1,2] : List Nat).Nodup := by decide
theorem shadowed_first_slot {A : Type} (d : A → Prop) (body : A → A → Prop) :
    (∀ _oldX, d _oldX → ∀ x, d x → ∀ y, d y → body x y) ↔
    (∀ x, d x → ∀ y, d y → body x y) :=
  all_drop_first d (fun x => ∀ y, d y → body x y)

#print axioms all_drop_unused
#print axioms some_drop_unused
#print axioms no_drop_unused
#print axioms all_drop_first
#print axioms some_drop_first
#print axioms d8_reference
#print axioms old_gate_rejects
#print axioms reduced_gate_accepts
#print axioms all_vacuous
#print axioms some_vacuous
#print axioms one_product
#print axioms lone_product
#print axioms exists_assignments
#print axioms one_assignments
#print axioms lone_assignments
#print axioms one_fibers
#print axioms lone_fibers
#print axioms empty_all_counterexample
#print axioms empty_some_counterexample
#print axioms disjoint_counterexample
#print axioms all_after_tautology
#print axioms some_after_tautology
#print axioms unused_subset_some
#print axioms unused_subset_all
#print axioms original_declarations_distinct
#print axioms overwritten_declaration_inventory
#print axioms shadowed_first_slot
end UnusedGroupedBindings
