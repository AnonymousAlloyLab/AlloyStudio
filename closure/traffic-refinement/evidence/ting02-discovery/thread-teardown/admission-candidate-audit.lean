import Lean
import AdmissionSpec
open Lean Elab Command
run_elab do
  let env ← getEnv
  for (name, info) in env.constants.toList do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if !owner.toString.startsWith "Admission" then continue
    match info with
    | .thmInfo _ | .defnInfo _ =>
      let axioms ← collectAxioms name
      if !axioms.isEmpty then liftM <| IO.println s!"{name}: {axioms}"
    | .axiomInfo _ => throwError "Project axiom {name}"
    | _ => pure ()
