# AP01 repaired candidate tier 1 review (Luna B)

Reviewed the frozen `proposed-contracts` candidate with block SHA-256 `5eea585967c67134dcb967a342b5c76c9c387892423239423c6df2808cb17cfc`. The prior Luna B C07 counterexample is preserved in the rejected-candidate archive. This review checks the repair and again probes proxy trust and quotas, browser fallback, administration admission, diagnostics, and governance; production bridges marked OPEN remain outside the model claims.

## C07 repair

The repaired `Observation` is a finite entry list. `exactInventory` requires structural equality with the approved list and unique paths. `currentVerified` requires this predicate in addition to a nonempty approved inventory and matching recorded root, verifier, and pass status. The former counterexample with approved `[(7,1)]` and observed `[(7,1),(8,2)]` is rejected. I compiled a scratch regression with the pinned Lean 4.34.1 offline entrypoint and required flags: extra observed entries reject, a duplicate approved path rejects, and an observed inventory with a different order/content rejects. I did not find a constructed counterexample to the repaired finite C07 contract.

The model makes canonical list order part of the external observation boundary. The production bridge must establish that its enumerator uses that same canonical order and is complete; the model does not prove filesystem enumeration or hash correctness.

## Other attempted counterexamples and scope

I rechecked the modeled proxy trust boundary and forwarded-hop selection, channel allocation limits, browser one-attempt/one-fallback state machine, admin-route quota guard, and diagnostic projection/ownership visibility. No constructed counterexample to their stated finite model invariants was found. These checks do not establish production correspondence, strict wire parsing, actual IIS/backend enforcement, atomic diagnostics snapshots, runtime/JVM bounds, SHA-256 correctness, or other expressly open bridge obligations.
