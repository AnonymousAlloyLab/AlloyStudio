# B03 adversarial review, Luna B

Verdict: `no_constructed_breach`. Every registered B03 source and implementation-input hash matches the frozen manifest. I found no executable counterexample to the pool choice, completion, or certificate claims within their declared scope.

The Lean model uses strict `candidate.cost < old.cost` replacement. The policy valuation `[hasBest=true, improves=false]` therefore retains the incumbent at a tie; `scanPolicy_firstMinimum` connects this scan to `firstMinimum`. `finishPolicy` accepts only the `nonempty=true, complete=true` row. `completePolicy_eq_argmin` connects the scan to the complete model, while `certificate_first_minimum` requires a successful complete evaluation, ordered cost evidence for every index in `List.range costs.length`, winner membership, and `IsFirstMinimum`. The positional `costAt` map makes missing entries fail evaluation and makes out-of-range positions absent. Thus a later missing/invalid cost cannot be hidden by an earlier zero.

The generated Java masks agree with the Lean Boolean rows: `CHOOSE` accepts masks 0, 2, and 3, and `FINISH` accepts only mask 3. The argument-to-bit mapping in Java uses `hasBest`/`nonempty` as bit 0 and `improves`/`complete` as bit 1, matching the export. `VerifiedPoolSelection` increments only after a valid, in-order candidate; rejects a skipped, duplicate, extra, null, or negative-cost evaluation; latches failure; and publishes only when the declared count is complete and a winner exists. Both `LiveFeedback` and `AstFeedback` iterate through every validated reference before calling `result`, so zero distance does not short-circuit a later failure. Their adapters cap the pool at 4096 and convert any pool evaluation failure to a response without a partial comparison.

Offline Lean probe: `build/b03-luna-b/PoolProbe.lean`. It was run with the manifest flags via:

```sh
python3 scripts/lean_offline.py --cwd formal --lean-path build/bridge-objects -- ../build/b03-luna-b/PoolProbe.lean --trust=0 -DwarningAsError=true -DgenInjectivity=false -j1
```

The command exited 0. Its observed results were: tie selection retained `first`; scanning equal minima returned index/value `0`; the zero-then-invalid complete model returned `none`; certificate checks for `[some 3, some 3]` at index `0` and index `1` returned `true` and `false`; `[some 0, none]` and the empty certificate both returned `false`. It also printed the types of `certificate_first_minimum`, `certificate_complete`, and `completePolicy_eq_argmin` for inspection.

The actual Java accumulator probe at `build/b03-luna-b/PoolAccumulatorProbe.java` compiled against the frozen `BridgePolicies.java` and `VerifiedPoolSelection.java`, then printed `equal-cost=first,index=0,evaluated=2`, `strict-improvement=new`, `invalid-after-zero=thrown`, and `publish-after-invalid=thrown`. The previously completed finite bridge check in `build/bridge-first-check/report.json` records 9,224 kernel-checked rows, 9,224 JavaScript valuations, eight Java valuations, rejection of malformed inputs, and rejection of the false tie row.

Boundary: these checks do not prove cost extraction or distance arithmetic, Java/Alloy semantic correspondence, arbitrary host-language loops, decoder behavior, or async bookkeeping. The Lean model uses `Nat`; the runtime accumulator receives `int` costs, while adapters reject negative results. The registered scope expressly excludes universal extraction, host-loop, and algorithm correspondence. This review records an adversarial inspection and executable probes; it is not proof authority and does not extend B03's claims. No frozen file was edited, and no internet, elan, or toolchain installation was used.
