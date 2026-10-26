import Lean
import RepairLanguage

#print axioms RepairLanguage.atomic_has_valid_address
#print axioms RepairLanguage.create_role_matches_address
#print axioms RepairLanguage.quantifier_edit_is_binding
#print axioms RepairLanguage.skeleton_edit_is_temporal
#print axioms RepairLanguage.summary_is_not_atomic
#print axioms RepairLanguage.lookup_returns_existing
#print axioms RepairLanguage.unique_address_resolves_member
#print axioms RepairLanguage.read_preserves_state
#print axioms RepairLanguage.read_never_returns_group
#print axioms RepairLanguage.projection_payload_noninterference
#print axioms RepairLanguage.projection_preserves_ordered_ids
#print axioms RepairLanguage.projection_preserves_cost
#print axioms RepairLanguage.read_insertion_preserves_weight

/- Audit tooling is not theorem evidence. Enumerate every exported theorem of the
model, including generated declarations, and reject new project axioms. Anonymous
examples are kernel-checked when compiling the model; they are not declarations. -/
open Lean Elab Command

run_elab do
  let env ← getEnv
  let names := env.constants.fold (init := (#[] : Array Name)) fun names name _ =>
    match env.getModuleIdxFor? name with
    | some idx =>
      if env.header.moduleNames[idx.toNat]! = `RepairLanguage then names.push name else names
    | none => names
  let names := names.qsort Name.lt
  let mut count := 0
  for name in names do
    let some idx := env.getModuleIdxFor? name | continue
    let owner := env.header.moduleNames[idx.toNat]!
    if owner != `RepairLanguage then continue
    let some info := env.find? name | throwError "Missing repair-language declaration {name}"
    match info with
    | .axiomInfo _ => throwError "Project axiom is forbidden: {name}"
    | .thmInfo value =>
      if value.type.hasMVar || value.type.hasFVar then
        throwError "Unclosed repair-language theorem {name}"
      let axioms ← collectAxioms name
      for axiomName in axioms do
        unless [``propext, ``Quot.sound, ``Classical.choice].contains axiomName do
          throwError "Unexpected theorem axiom dependency {name}: {axiomName}"
      count := count + 1
      liftM <| IO.println (Json.mkObj [
        ("kind", toJson ("theorem" : String)),
        ("name", toJson name.toString),
        ("axioms", toJson (axioms.map Name.toString))]).compress
    | _ => pure ()
  if count = 0 then throwError "No model theorems were audited"
  liftM <| IO.println (Json.mkObj [
    ("kind", toJson ("audit-summary" : String)),
    ("theorems", toJson count)]).compress
