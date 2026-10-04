import Lean
import AlloyStudio.SessionBridge
import AlloyStudio.PoolBridge

/- Registered extraction tool. Its output is not accepted as proof: every
   finite row is independently rechecked by the Lean kernel before use. -/
open Lean

def policyAtoms (arity mask : Nat) : List Bool :=
  (List.range arity).map fun bit => decide (mask / 2 ^ bit % 2 = 1)

def exportedPolicy (arity : Nat) (policy : List Bool → Bool) : Json :=
  Json.mkObj [("arity", toJson arity),
    ("acceptedMasks", toJson ((List.range (2 ^ arity)).filter fun mask => policy (policyAtoms arity mask)))]

def main : IO Unit :=
  IO.println (Json.mkObj [
    ("feedbackSuccess", exportedPolicy 10 AlloyStudio.SessionBridge.feedbackSuccess),
    ("guidanceSuccess", exportedPolicy 13 AlloyStudio.SessionBridge.guidanceSuccess),
    ("poolChoose", exportedPolicy 2 AlloyStudio.PoolBridge.choosePolicy),
    ("poolFinish", exportedPolicy 2 AlloyStudio.PoolBridge.finishPolicy)]).compress
