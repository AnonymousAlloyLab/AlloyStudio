import Lean
import SqlSeparation

/- Standalone inventory for the SQL separation proof surface. The runtime bridge
   checks the exact source and complete inventory separately. No project axiom
   or theorem axiom dependency is accepted, including Lean's standard axioms. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.toList.map Prod.fst |>.toArray.qsort Name.lt
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if owner != `SqlSeparation then continue
    let some info := env.find? name | throwError "Missing SQL declaration {name}"
    match info with
    | .thmInfo value =>
      if value.type.hasMVar || value.type.hasFVar then
        throwError "Unclosed SQL theorem {name}"
      let axioms ← collectAxioms name
      if !axioms.isEmpty then
        throwError "SQL theorem has axiom dependencies: {name}: {axioms}"
      liftM <| IO.println (Json.mkObj [
        ("kind", toJson ("theorem" : String)),
        ("name", toJson name.toString),
        ("module", toJson owner.toString),
        ("type", toJson (toString (repr value.type))),
        ("levelParameters", toJson (value.levelParams.map Name.toString)),
        ("axioms", toJson (axioms.map Name.toString))]).compress
    | .axiomInfo _ => throwError "Forbidden project SQL axiom: {name}"
    | _ => pure ()
