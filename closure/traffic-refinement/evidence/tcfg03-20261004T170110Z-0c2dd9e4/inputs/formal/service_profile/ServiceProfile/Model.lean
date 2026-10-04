import TrafficProfile.Model

/- Independent configuration and successful-startup data boundary.
No validity proposition is stored in any record. External identity values are
explicit inputs; OS lock ownership and native handle identity are not represented. -/
namespace AlloyStudio.ServiceProfile.Model

structure Inputs where
  root : String
  snapshot : String
  generation : String
  serviceIdentity : String
  csrfSecret : String
  clock : Int

structure Cell where
  path : String
  kind : String
  value : String

structure Quantity where
  id : String
  numerator : Nat
  denominator : Nat
  allowZero : Bool

structure State where
  inputs : Inputs
  profile : List Quantity
  graph : List Cell
  http : HttpProfile.Model.InitialState

/-- The selected finite profile encodes each quantity as an exact nonnegative
rational. A zero denominator is invalid; zero numerator requires an explicit
zero-enabled feature. No floating-point approximation enters this check. -/
def quantityValid (quantity : Quantity) : Bool :=
  (!(quantity.id == "") && 0 < quantity.denominator) &&
    (quantity.allowZero || 0 < quantity.numerator)

def quantitiesValid (profile : List Quantity) : Bool :=
  profile.all quantityValid

/-- Identifier uniqueness is checked by an explicit scan, not an assumed field. -/
def namesUnique : List String → Bool
  | [] => true
  | name :: tail => !(tail.contains name) && namesUnique tail

def profileNames (profile : List Quantity) : List String :=
  profile.map Quantity.id

def profileValid (profile : List Quantity) : Bool :=
  !(profile.isEmpty) && quantitiesValid profile && namesUnique (profileNames profile)

def lookupCell (path : String) : List Cell → Option Cell
  | [] => none
  | head :: tail => if head.path == path then some head else lookupCell path tail

def cellMatches (graph : List Cell) (path kind value : String) : Bool :=
  match lookupCell path graph with
  | none => false
  | some cell => cell.kind == kind && cell.value == value

def cellValueMatches (graph : List Cell) (path value : String) : Bool :=
  match lookupCell path graph with
  | none => false
  | some cell => cell.value == value

def sameReference (graph : List Cell) (left right : String) : Bool :=
  match lookupCell left graph with
  | none => false
  | some a =>
    match lookupCell right graph with
    | none => false
    | some b => a.kind == "reference" && b.kind == "reference" && a.value == b.value

/-- Transparent byte-prefix scan. Paths are UTF-8 strings; slash is the
registered child separator. This avoids opaque library search implementations. -/
def bytePrefix : List UInt8 → List UInt8 → Bool
  | [], _ => true
  | _ :: _, [] => false
  | left :: rest, right :: tail => left == right && bytePrefix rest tail

def pathPrefix (headPath path : String) : Bool :=
  bytePrefix headPath.toUTF8.data.toList path.toUTF8.data.toList

/-- Empty means an owned container marker exists and has no projected children.
Checking a marker alone would also accept a nonempty resource collection. -/
def emptyContainer (graph : List Cell) (path : String) : Bool :=
  match lookupCell path graph with
  | none => false
  | some cell => cell.kind == "container" &&
      !(graph.any (fun child => bytePrefix (path.toUTF8.data.toList ++ [47]) child.path.toUTF8.data.toList))

def graphPathsUnique (graph : List Cell) : Bool :=
  namesUnique (graph.map Cell.path)

def allCellsMatch (graph : List Cell) (paths : List String) (kind value : String) : Bool :=
  paths.all (fun path => cellMatches graph path kind value)

def allValuesMatch (graph : List Cell) (values : List (String × String)) : Bool :=
  values.all (fun pair => cellValueMatches graph pair.1 pair.2)

def allReferencesMatch (graph : List Cell) (pairs : List (String × String)) : Bool :=
  pairs.all (fun pair => sameReference graph pair.1 pair.2)

def allTargetsMatch (graph : List Cell) (pairs : List (String × String)) (kind : String) : Bool :=
  pairs.all (fun pair => cellMatches graph pair.1 kind pair.2)

/-- Presence cardinalities are computed from graph records. They are not supplied
as a claimed counter alongside the objects they should count. -/
def classCount (graph : List Cell) (name : String) : Nat :=
  (graph.filter (fun cell => cell.kind == "class" && cell.value == name)).length

def handleCount (graph : List Cell) (name : String) : Nat :=
  (graph.filter (fun cell => cell.kind == "handle" && cell.value == name)).length

end AlloyStudio.ServiceProfile.Model
