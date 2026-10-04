import Lean
import TrafficConfig.Scalar
import TrafficConfig.Extracted
import TrafficConfig.Spec
import TrafficProfile.Model
import TrafficProfile.Extracted
import TrafficProfile.Spec
import ServiceProfile.Model
import ServiceProfile.Frozen
import ServiceProfile.Extracted
import ServiceProfile.Spec

/- Registered audit machinery; not a proof premise. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.toList.map Prod.fst |>.toArray.qsort Name.lt
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if !(owner.toString.startsWith "TrafficConfig." || owner.toString.startsWith "TrafficProfile." || owner.toString.startsWith "ServiceProfile.") then continue
    let some info := env.find? name | throwError "Missing HTTP profile declaration {name}"
    match info with
    | .thmInfo value =>
      if value.type.hasMVar || value.type.hasFVar then
        throwError "Unclosed HTTP profile theorem {name}"
      let axioms ← collectAxioms name
      liftM <| IO.println (Json.mkObj [
        ("kind", toJson ("theorem" : String)),
        ("name", toJson name.toString),
        ("module", toJson owner.toString),
        ("type", toJson (toString (repr value.type))),
        ("levelParameters", toJson (value.levelParams.map Name.toString)),
        ("axioms", toJson (axioms.map Name.toString))]).compress
    | .axiomInfo _ =>
      liftM <| IO.println (Json.mkObj [
        ("kind", toJson ("forbidden-project-axiom" : String)),
        ("name", toJson name.toString),
        ("module", toJson owner.toString)]).compress
    | .defnInfo _ =>
      let axioms ← collectAxioms name
      if !axioms.isEmpty then
        throwError "Configuration definition has axiom dependencies: {name}: {axioms}"
    | _ => pure ()
