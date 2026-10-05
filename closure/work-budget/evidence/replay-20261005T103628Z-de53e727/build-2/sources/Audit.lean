import Lean
import Work
import Semantics
import Generated
import Refinement

/- Registered audit machinery, never an additional proof premise. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.toList.map Prod.fst |>.toArray.qsort Name.lt
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if !(["Work", "Semantics", "Generated", "Refinement"].contains owner.toString) then
      continue
    let some info := env.find? name | throwError "Missing patch contract declaration {name}"
    if info.type.hasMVar || info.type.hasFVar then
      throwError "Unclosed patch contract declaration {name}"
    let axioms := (← collectAxioms name).qsort Name.lt
    let allowed := ["propext", "Classical.choice", "Quot.sound"]
    if axioms.any (fun n => !allowed.contains n.toString) then
      throwError "Work budget has unregistered axiom dependencies: {name}: {axioms}"
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
