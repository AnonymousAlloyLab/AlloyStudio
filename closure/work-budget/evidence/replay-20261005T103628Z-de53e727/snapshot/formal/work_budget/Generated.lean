import Semantics

-- Generated from parsed Java branches and assignments; never edit by hand.
namespace AlloyStudio.WorkBudget.Generated
open AlloyStudio.WorkBudget

def initialChecks : Int := 1024

def checkpoint (s : State) (_units now : Int) : Result :=
  if (s.active = false) then
    .ok s
  else
    if (s.exhausted = true) then
      .exhausted s
    else
      if (s.timed = true) then
        let elapsed := javaLong (now - s.started)
        if ((elapsed < 0) ∨ (elapsed ≥ s.limit)) then
          let s : State := { s with exhausted := true }
          .exhausted s
        else
          .ok s
      else
        .ok s

def sampleClock (s : State) (units now : Int) : Result :=
  if (s.timed = false) then
    .ok s
  else
    let s : State := { s with checks := javaInt (s.checks - 1) }
    if (s.checks = 0) then
      let s : State := { s with checks := 1024 }
      match checkpoint s units now with
      | .ok s =>
        .ok s
      | .invalid s => .invalid s
      | .exhausted s => .exhausted s
    else
      .ok s

def charge (s : State) (units now : Int) : Result :=
  if (s.active = false) then
    .ok s
  else
    if (units < 0) then
      .invalid s
    else
      if ((s.exhausted = true) ∨ (units > s.remaining)) then
        let s : State := { s with exhausted := true }
        .exhausted s
      else
        match sampleClock s units now with
        | .ok s =>
          let s : State := { s with remaining := javaLong (s.remaining - units) }
          .ok s
        | .invalid s => .invalid s
        | .exhausted s => .exhausted s

end AlloyStudio.WorkBudget.Generated
