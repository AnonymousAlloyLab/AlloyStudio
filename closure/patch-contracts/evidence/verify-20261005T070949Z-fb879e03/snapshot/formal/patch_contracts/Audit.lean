import Lean
import Work
import Identity
import Governance
import Observability

/- Registered audit machinery, never an additional proof premise. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.toList.map Prod.fst |>.toArray.qsort Name.lt
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if !(["Work", "Identity", "Governance", "Observability"].contains owner.toString) then
      continue
    let some info := env.find? name | throwError "Missing patch contract declaration {name}"
    if info.type.hasMVar || info.type.hasFVar then
      throwError "Unclosed patch contract declaration {name}"
    let axioms ← collectAxioms name
    if !axioms.isEmpty then
      throwError "Patch contract has axiom dependencies: {name}: {axioms}"
    let kind := match info with
      | .thmInfo _ => "theorem"
      | .defnInfo _ => "definition"
      | .axiomInfo _ => "forbidden-project-axiom"
      | _ => "structural"
    liftM <| IO.println (Json.mkObj [
      ("kind", toJson kind),
      ("name", toJson name.toString),
      ("module", toJson owner.toString),
      ("type", toJson (toString (repr info.type))),
      ("levelParameters", toJson (info.levelParams.map Name.toString)),
      ("axioms", toJson (axioms.map Name.toString))]).compress
