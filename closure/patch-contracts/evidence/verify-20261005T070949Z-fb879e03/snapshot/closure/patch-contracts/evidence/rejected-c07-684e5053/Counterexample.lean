import Governance
open AlloyStudio.PatchContracts.Governance

def approved : ApprovedSurface := ⟨100, [⟨7, 1⟩]⟩
def recorded : RecordedResult := ⟨100, 200, true⟩
def observedWithExtra : Observation := fun path =>
  if path = 7 then some 1 else if path = 8 then some 2 else none

theorem extra_current_entry_accepted :
    currentVerified approved 200 recorded observedWithExtra = true := by decide

theorem extra_entry_really_present : observedWithExtra 8 = some 2 := by decide
