import Std

/-!
Constructive SQL/value separation model. SQL is a byte sequence selected only
from a registry or a fixed control list. Text parameters are arbitrary bytes;
they are never lexed as SQL. The concrete source bridge must separately establish
that every production sink implements these constructors and that its registry
is the inspected registry. This file proves neither SQLite internals nor a JSON
parser implementation. `Wire` describes a parsed JSON value, before validation.
-/
namespace SqlSeparation

abbrev Bytes := List UInt8

inductive Value where
  | text (bytes : Bytes)
  | integer (number : Int)
  deriving DecidableEq, Repr

inductive Parameter where
  | text (maxBytes : Nat)
  | integer
  deriving DecidableEq, Repr

structure Entry where
  sql : Bytes
  parameters : List Parameter
  deriving DecidableEq, Repr

abbrev Registry := List (String × Entry)

def lookup : Registry → String → Option Entry
  | [], _ => none
  | (name, entry) :: rest, identifier =>
      if name = identifier then some entry else lookup rest identifier

def accepts : Parameter → Value → Bool
  | .text maximum => fun value =>
      match value with
      | .text bytes => decide (bytes.length ≤ maximum)
      | .integer _ => false
  | .integer => fun value =>
      match value with
      | .text _ => false
      | .integer number => decide (-(2 ^ 63 : Int) ≤ number ∧ number < 2 ^ 63)

def acceptsAll : List Parameter → List Value → Bool
  | [] => fun values =>
      match values with
      | [] => true
      | _ :: _ => false
  | parameter :: parameters => fun values =>
      match values with
      | [] => false
      | value :: rest => accepts parameter value && acceptsAll parameters rest

structure Bound where
  sql : Bytes
  values : List Value
  deriving DecidableEq, Repr

/-- An arbitrary validation gate may reject; it has no facility to change values.
    Concrete validators with more restrictions can refine this model directly. -/
def executeWith (registry : Registry) (identifier : String) (values : List Value)
    (gate : Entry → List Value → Bool) : Option Bound :=
  match lookup registry identifier with
  | none => none
  | some entry =>
      if gate entry values then some ⟨entry.sql, values⟩ else none

theorem executeWith_separates (registry : Registry) (identifier : String)
    (values : List Value) (gate : Entry → List Value → Bool) (call : Bound)
    (accepted : executeWith registry identifier values gate = some call) :
    ∃ entry, lookup registry identifier = some entry ∧
      call.sql = entry.sql ∧ call.values = values := by
  unfold executeWith at accepted
  cases found : lookup registry identifier with
  | none => rw [found] at accepted; cases accepted
  | some entry =>
      rw [found] at accepted
      change (if gate entry values then some (Bound.mk entry.sql values)
        else none) = some call at accepted
      split at accepted
      · cases accepted
        exact ⟨entry, rfl, rfl, rfl⟩
      · cases accepted

theorem executeWith_same_id_same_syntax (registry : Registry) (identifier : String)
    (first second : List Value) (leftGate rightGate : Entry → List Value → Bool)
    (left right : Bound)
    (leftAccepted : executeWith registry identifier first leftGate = some left)
    (rightAccepted : executeWith registry identifier second rightGate = some right) :
    left.sql = right.sql := by
  obtain ⟨leftEntry, leftFound, leftSql, _⟩ :=
    executeWith_separates registry identifier first leftGate left leftAccepted
  obtain ⟨rightEntry, rightFound, rightSql, _⟩ :=
    executeWith_separates registry identifier second rightGate right rightAccepted
  rw [leftFound] at rightFound
  cases rightFound
  exact leftSql.trans rightSql.symm

def execute (registry : Registry) (identifier : String) (values : List Value) : Option Bound :=
  executeWith registry identifier values (fun entry supplied => acceptsAll entry.parameters supplied)

theorem execute_separates (registry : Registry) (identifier : String)
    (values : List Value) (call : Bound)
    (accepted : execute registry identifier values = some call) :
    ∃ entry, lookup registry identifier = some entry ∧
      call.sql = entry.sql ∧ call.values = values := by
  exact executeWith_separates registry identifier values _ call accepted

theorem same_id_same_syntax (registry : Registry) (identifier : String)
    (first second : List Value) (left right : Bound)
    (leftAccepted : execute registry identifier first = some left)
    (rightAccepted : execute registry identifier second = some right) :
    left.sql = right.sql := by
  obtain ⟨leftEntry, leftFound, leftSql, _⟩ :=
    execute_separates registry identifier first left leftAccepted
  obtain ⟨rightEntry, rightFound, rightSql, _⟩ :=
    execute_separates registry identifier second right rightAccepted
  rw [leftFound] at rightFound
  cases rightFound
  exact leftSql.trans rightSql.symm

theorem unknown_id_rejected (registry : Registry) (identifier : String)
    (values : List Value) (unknown : lookup registry identifier = none) :
    execute registry identifier values = none := by
  unfold execute executeWith
  rw [unknown]

theorem rejected_parameters_emit_nothing (registry : Registry) (identifier : String)
    (values : List Value) (entry : Entry)
    (found : lookup registry identifier = some entry)
    (rejected : acceptsAll entry.parameters values = false) :
    execute registry identifier values = none := by
  unfold execute executeWith
  rw [found]
  change (if acceptsAll entry.parameters values then some (Bound.mk entry.sql values)
    else none) = none
  rw [rejected]
  rfl

def controlAt : List Bytes → Nat → Option Bytes
  | [], _ => none
  | statement :: _, 0 => some statement
  | _ :: rest, index + 1 => controlAt rest index

inductive Action where
  | query (identifier : String) (values : List Value)
  | control (index : Nat)
  deriving DecidableEq, Repr

def perform (registry : Registry) (controls : List Bytes) : Action → Option Bound
  | .query identifier values => execute registry identifier values
  | .control index =>
      match controlAt controls index with
      | none => none
      | some statement => some ⟨statement, []⟩

def SyntaxAllowed (registry : Registry) (controls : List Bytes) (call : Bound) : Prop :=
  (∃ identifier entry, lookup registry identifier = some entry ∧ call.sql = entry.sql) ∨
  (∃ index, controlAt controls index = some call.sql)

theorem perform_closed (registry : Registry) (controls : List Bytes)
    (action : Action) (call : Bound)
    (accepted : perform registry controls action = some call) :
    SyntaxAllowed registry controls call := by
  cases action with
  | query identifier values =>
      obtain ⟨entry, found, statement, _⟩ :=
        execute_separates registry identifier values call accepted
      exact Or.inl ⟨identifier, entry, found, statement⟩
  | control index =>
      change (match controlAt controls index with
        | none => none
        | some statement => some (Bound.mk statement [])) = some call at accepted
      cases found : controlAt controls index with
      | none => rw [found] at accepted; cases accepted
      | some statement =>
          rw [found] at accepted
          cases accepted
          exact Or.inr ⟨index, found⟩

def runTrace (registry : Registry) (controls : List Bytes) : List Action → List Bound
  | [] => []
  | action :: rest =>
      match perform registry controls action with
      | none => runTrace registry controls rest
      | some call => call :: runTrace registry controls rest

def TraceClosed (registry : Registry) (controls : List Bytes) : List Bound → Prop
  | [] => True
  | call :: rest => SyntaxAllowed registry controls call ∧ TraceClosed registry controls rest

theorem runTrace_closed (registry : Registry) (controls : List Bytes)
    (actions : List Action) : TraceClosed registry controls (runTrace registry controls actions) := by
  induction actions with
  | nil => exact True.intro
  | cons action rest ih =>
      unfold runTrace
      cases outcome : perform registry controls action with
      | none => exact ih
      | some call => exact ⟨perform_closed registry controls action call outcome, ih⟩

/- Semantic JSON boundary: values are strings/numbers or rejected shapes. No
   frontend validation premise appears. Actual UTF-8/JSON codec implementations
   and extraction from a request object remain explicit concrete bridge duties. -/
inductive Wire where
  | text (bytes : Bytes)
  | integer (number : Int)
  | other
  deriving DecidableEq, Repr

def encodeValue : Value → Wire
  | .text bytes => .text bytes
  | .integer number => .integer number

def decodeValue : Wire → Option Value
  | .text bytes => some (.text bytes)
  | .integer number => some (.integer number)
  | .other => none

def encodeValues : List Value → List Wire
  | [] => []
  | value :: rest => encodeValue value :: encodeValues rest

def decodeValues : List Wire → Option (List Value)
  | [] => some []
  | value :: rest =>
      match decodeValue value with
      | none => none
      | some decoded =>
          match decodeValues rest with
          | none => none
          | some remaining => some (decoded :: remaining)

theorem value_roundtrip (value : Value) : decodeValue (encodeValue value) = some value := by
  cases value <;> rfl

theorem values_roundtrip (values : List Value) :
    decodeValues (encodeValues values) = some values := by
  induction values with
  | nil => rfl
  | cons value rest ih =>
      unfold encodeValues decodeValues
      rw [value_roundtrip, ih]

def backend (registry : Registry) (identifier : String) (wire : List Wire) : Option Bound :=
  match decodeValues wire with
  | none => none
  | some values => execute registry identifier values

theorem frontend_backend_composition (registry : Registry) (identifier : String)
    (rawValues : List Value) :
    backend registry identifier (encodeValues rawValues) = execute registry identifier rawValues := by
  unfold backend
  rw [values_roundtrip]

theorem arbitrary_wire_separates (registry : Registry) (identifier : String)
    (wire : List Wire) (call : Bound)
    (accepted : backend registry identifier wire = some call) :
    ∃ values entry, decodeValues wire = some values ∧
      lookup registry identifier = some entry ∧
      call.sql = entry.sql ∧ call.values = values := by
  unfold backend at accepted
  cases decoded : decodeValues wire with
  | none => rw [decoded] at accepted; cases accepted
  | some values =>
      rw [decoded] at accepted
      obtain ⟨entry, found, statement, boundValues⟩ :=
        execute_separates registry identifier values call accepted
      exact ⟨values, entry, rfl, found, statement, boundValues⟩

/-- Opaque concrete validation can reject any input, but cannot reinterpret it
    as a statement or mutate the positional tuple that the binder receives. -/
def performWith (registry : Registry) (controls : List Bytes)
    (gate : Entry → List Value → Bool) : Action → Option Bound
  | .query identifier values => executeWith registry identifier values gate
  | .control index =>
      match controlAt controls index with
      | none => none
      | some statement => some ⟨statement, []⟩

theorem performWith_closed (registry : Registry) (controls : List Bytes)
    (gate : Entry → List Value → Bool) (action : Action) (call : Bound)
    (accepted : performWith registry controls gate action = some call) :
    SyntaxAllowed registry controls call := by
  cases action with
  | query identifier values =>
      obtain ⟨entry, found, statement, _⟩ :=
        executeWith_separates registry identifier values gate call accepted
      exact Or.inl ⟨identifier, entry, found, statement⟩
  | control index =>
      change (match controlAt controls index with
        | none => none
        | some statement => some (Bound.mk statement [])) = some call at accepted
      cases found : controlAt controls index with
      | none => rw [found] at accepted; cases accepted
      | some statement =>
          rw [found] at accepted
          cases accepted
          exact Or.inr ⟨index, found⟩

def runTraceWith (registry : Registry) (controls : List Bytes)
    (gate : Entry → List Value → Bool) : List Action → List Bound
  | [] => []
  | action :: rest =>
      match performWith registry controls gate action with
      | none => runTraceWith registry controls gate rest
      | some call => call :: runTraceWith registry controls gate rest

theorem runTraceWith_closed (registry : Registry) (controls : List Bytes)
    (gate : Entry → List Value → Bool) (actions : List Action) :
    TraceClosed registry controls (runTraceWith registry controls gate actions) := by
  induction actions with
  | nil => exact True.intro
  | cons action rest ih =>
      unfold runTraceWith
      cases outcome : performWith registry controls gate action with
      | none => exact ih
      | some call => exact ⟨performWith_closed registry controls gate action call outcome, ih⟩

def backendWith (registry : Registry) (identifier : String) (wire : List Wire)
    (gate : Entry → List Value → Bool) : Option Bound :=
  match decodeValues wire with
  | none => none
  | some values => executeWith registry identifier values gate

theorem frontend_backendWith_composition (registry : Registry) (identifier : String)
    (rawValues : List Value) (gate : Entry → List Value → Bool) :
    backendWith registry identifier (encodeValues rawValues) gate =
      executeWith registry identifier rawValues gate := by
  unfold backendWith
  rw [values_roundtrip]

theorem arbitrary_wireWith_separates (registry : Registry) (identifier : String)
    (wire : List Wire) (gate : Entry → List Value → Bool) (call : Bound)
    (accepted : backendWith registry identifier wire gate = some call) :
    ∃ values entry, decodeValues wire = some values ∧
      lookup registry identifier = some entry ∧
      call.sql = entry.sql ∧ call.values = values := by
  unfold backendWith at accepted
  cases decoded : decodeValues wire with
  | none => rw [decoded] at accepted; cases accepted
  | some values =>
      rw [decoded] at accepted
      obtain ⟨entry, found, statement, boundValues⟩ :=
        executeWith_separates registry identifier values gate call accepted
      exact ⟨values, entry, rfl, found, statement, boundValues⟩

/- Negative control: interpolating arbitrary text into syntax violates the
   separation property. This constructor does not model a production sink.
   Bytes spell `SELECT '` + parameter + `'`; quote payload changes SQL bytes. -/
def unsafeInterpolate (value : Bytes) : Bytes := [83, 69, 76, 69, 67, 84, 32, 39] ++ value ++ [39]

theorem unsafe_interpolation_counterexample :
    unsafeInterpolate [120] ≠ unsafeInterpolate [120, 39, 59, 45, 45] := by
  decide

end SqlSeparation
