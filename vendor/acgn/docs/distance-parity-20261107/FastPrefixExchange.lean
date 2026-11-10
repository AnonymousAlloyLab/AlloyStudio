import Std

namespace FastPrefixExchange

-- Mirrored literally by FastPrefixSourceTest; its executable receipt checks the
-- parsed typed coordinates, declaration owners, and actual Java transport.
def source := "sig A {r:set A} pred p {all x:A | all y:A | x in y.r}"
def sourceSha256 := "5365eba78a72ecb9952c98492691c14ef8a3683e704c08bc3808498ed4232ce0"
def owners := ["root/predicate[0]/decl[0]@pos", "root/predicate[0]/body[1]/decl[0]@pos"]
def typedSlots := ["root/predicate[0]/decl[0]@pos#0:ONE:A",
  "root/predicate[0]/body[1]/decl[0]@pos#0:ONE:A"]
def sourceCoordinates : List Nat := [0,1]
def exchangedCoordinates : List Nat := [1,0]
def phase : Nat := 0

-- Local slots cannot be captured by an outer exchange. Distinct original
-- owners remain attached to occurrences, not to their display positions.
inductive Slot where
  | x | y | localSlot (owner index : Nat)
  deriving DecidableEq
def swap : Slot → Slot
  | .x => .y
  | .y => .x
  | .localSlot owner index => .localSlot owner index

theorem swap_twice (s : Slot) : swap (swap s) = s := by cases s <;> rfl
theorem local_unchanged (o i : Nat) : swap (.localSlot o i) = .localSlot o i := rfl
theorem typed_read_transport {A : Type} (env : Slot → A) (s : Slot) :
    (fun t => env (swap t)) (swap s) = env s := by
  change env (swap (swap s)) = env s
  rw [swap_twice]

-- Any body interpretation, including local binders, keeps its value when
-- both the environment coordinates and its variable reads are transported.
theorem body_transport {A : Type} (body : (Slot → A) → Prop) (env : Slot → A) :
    body (fun s => env (swap (swap s))) ↔ body env := by
  simp only [swap_twice]

theorem all_exchange {A : Type} (domain : A → Prop) (body : A → A → Prop) :
    (∀ x, domain x → ∀ y, domain y → body x y) ↔
    (∀ y, domain y → ∀ x, domain x → body x y) := by
  constructor <;> intro h x hx y hy <;> exact h y hy x hx

theorem some_exchange {A : Type} (domain : A → Prop) (body : A → A → Prop) :
    (∃ x, domain x ∧ ∃ y, domain y ∧ body x y) ↔
    (∃ y, domain y ∧ ∃ x, domain x ∧ body x y) := by
  constructor
  · rintro ⟨x,hx,y,hy,h⟩; exact ⟨y,hy,x,hx,h⟩
  · rintro ⟨y,hy,x,hx,h⟩; exact ⟨x,hx,y,hy,h⟩

-- x in y.r is r y x for singleton A-valued bindings. This theorem covers
-- every carrier/relation, not one selected finite valuation.
theorem source_instance_sound {A : Type} (r : A → A → Prop) :
    (∀ x, True → ∀ y, True → r y x) ↔ (∀ y, True → ∀ x, True → r y x) :=
  all_exchange (fun _ => True) (fun x y => r y x)

-- ProductionGraphCanonicalizer.CanonicalWorkBudget.consume rejects when
-- consumed > maximum. The Java fixture observes the exact exception at 1.
def oldBudgetAllows (consumed maximum : Nat) : Prop := consumed ≤ maximum
theorem old_budget_rejects_second_item : ¬ oldBudgetAllows 2 1 := by
  unfold oldBudgetAllows
  decide
theorem source_slots_distinct : owners[0]? ≠ owners[1]? := by decide
theorem coordinate_bijection : sourceCoordinates.Perm exchangedCoordinates := by decide

#print axioms swap_twice
#print axioms local_unchanged
#print axioms typed_read_transport
#print axioms body_transport
#print axioms all_exchange
#print axioms some_exchange
#print axioms source_instance_sound
#print axioms old_budget_rejects_second_item
#print axioms source_slots_distinct
#print axioms coordinate_bijection
end FastPrefixExchange
