import TrafficConfig.Scalar

/- Generated from the admitted Python numeric guard AST. Do not hand-edit. -/
namespace AlloyStudio.TrafficConfig.Extracted

def validatedInt (value : Scalar) (minimum maximum : Int) : Option Scalar :=
  if (((less minimum 0)) ∨ ((less maximum minimum))) then none else
  match value with
  | .integer number =>
    if (¬ ((lessEqual minimum number) ∧ (lessEqual number maximum))) then none else some value
  | .floating _ _ => none
  | .invalid _ => none

def validatedSeconds (value : Scalar) (minimumZero : Bool) (maximum : Int) : Option Scalar :=
  if ((lessEqual maximum 0)) then none else
  let ratio := match value with
    | .integer number => some (number, 1)
    | .floating numerator denominator =>
      if denominator = 0 then none else some (numerator, denominator)
    | .invalid _ => none
  match ratio with
  | none => none
  | some (numerator, denominator) =>
    if (((less numerator 0)) ∨ ((minimumZero = false) ∧ ((numerator = 0))) ∨ ((less (maximum * (Int.ofNat denominator)) numerator))) then none else some value

end AlloyStudio.TrafficConfig.Extracted
