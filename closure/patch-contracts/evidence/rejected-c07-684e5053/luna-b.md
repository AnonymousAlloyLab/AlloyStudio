# AP01 tier 1 independent review (Luna B)

Reviewed the frozen `proposed-contracts` candidate with block SHA-256 `684e505341eef4b975b0f5e00f935841456a4372fb32508f449e2577c9b80c3e`, focusing on proxy trust and quotas, browser fallback, administration admission, diagnostics, and governance. Production bridges marked OPEN were treated as explicit exclusions from the model claims.

## Finding: AP01-C07 accepts an unexpected current entry

`Governance.Observation` is `PathId → Option Digest`; `checks` only visits entries in `ApprovedSurface.entries`. It never compares the approved inventory with the complete set of observed paths. Thus the mapped theorem `current_requires_complete_equality` establishes equality for the listed entries, but not complete inventory equality, as the public claim and B07 bridge require.

Constructed counterexample (compiled with the pinned Lean 4.34.1 offline entrypoint and required flags in `build/claude-contracts-20261005/review-luna-b/Counterexample.lean`):

```lean
def approved : ApprovedSurface := ⟨100, [⟨7, 1⟩]⟩
def recorded : RecordedResult := ⟨100, 200, true⟩
def observedWithExtra : Observation := fun path =>
  if path = 7 then some 1 else if path = 8 then some 2 else none

theorem extra_current_entry_accepted :
    currentVerified approved 200 recorded observedWithExtra = true := by decide

theorem extra_entry_really_present : observedWithExtra 8 = some 2 := by decide
```

The model therefore reports current verification while the observation contains path 8, absent from the approved inventory. This is an invariant/claim mismatch in the frozen proposed contract, independent of production implementation correspondence. To close it, represent an enumerable complete observed inventory and require exact set-and-digest equality, including rejection of unexpected paths; then bind the corresponding theorem into the frozen claim and candidate.

## Scope and attempted counterexamples

I checked the stated proxy boundary: untrusted-peer headers do not affect identity, trusted malformed headers fail closed, the selected hop is untrusted, and a forged left prefix cannot displace the nearest untrusted hop under the stated decoded-token assumptions. The comments appropriately leave raw parsing, canonical IP conversion, and deployed proxy configuration OPEN.

I checked the channel, browser, admin, and diagnostics models for a counterexample to their mapped finite transition claims. The modeled channel bounds, one-shot fallback budget, stale-snapshot guard, route-policy equality, nonallowlisted quota preservation, private diagnostic projection, and retained ownership visibility did not yield a further constructed breach. These checks do not establish production correspondence or the correctness of excluded parsers, network policy deployment, atomic snapshots, JVM behavior, or runtime resource bounds.
