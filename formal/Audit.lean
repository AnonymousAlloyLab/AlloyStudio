import Lean
import AlloyStudio

/- Registered verifier code, not part of the theorem surface. Every theorem and
   axiom originating in a project module is reported, including private names.
   The Python gate pins this file and compares the complete inventory twice. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.toList.map Prod.fst |>.toArray.qsort Name.lt
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if !owner.toString.startsWith "AlloyStudio" then continue
    let some info := env.find? name | throwError "Missing project declaration {name}"
    match info with
    | .thmInfo value =>
      if value.type.hasMVar || value.type.hasFVar then
        throwError "Unclosed theorem type {name}"
      let axioms ← collectAxioms name
      let row := Json.mkObj [
        ("kind", toJson ("theorem" : String)),
        ("name", toJson name.toString),
        ("module", toJson owner.toString),
        ("type", toJson (toString (repr value.type))),
        ("levelParameters", toJson (value.levelParams.map Name.toString)),
        ("axioms", toJson (axioms.map Name.toString))]
      liftM <| IO.println row.compress
    | .axiomInfo _ =>
      liftM <| IO.println (Json.mkObj [
        ("kind", toJson ("forbidden-project-axiom" : String)),
        ("name", toJson name.toString),
        ("module", toJson owner.toString)]).compress
    | _ => pure ()
