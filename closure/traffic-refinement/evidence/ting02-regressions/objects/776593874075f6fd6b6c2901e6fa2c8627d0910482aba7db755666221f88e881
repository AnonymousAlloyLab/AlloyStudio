# B02 adversarial review, Luna B

Verdict: `no_constructed_breach`. The manifest and all three frozen source hashes match. I found no executable counterexample to the registered guard claims.

The guidance model binds the full captured `RequestIdentity` (including exercise, body, revision, selection, and metric) to the current state, then requires the captured education generation and behavior token to remain current and the request to be unaborted. It also requires exact exercise and revision echoes, the requested metric, and the token echo. The Lean probe accepts an exact canonical response and rejects individual mutations to response exercise, revision, metric, token, or the education generation. This is an executable model probe; it does not establish JavaScript semantic refinement.

The legacy canonical missing-metric case is intentionally admitted by `LegacyResponseMetricMatches`: `legacyAcceptGuidance` evaluates to `true` for that exact response. The strict `acceptGuidance` evaluates to `false`; a missing metric echo is not a counterexample to the strict contract. The browser code likewise permits a missing metric only for non-`ok` canonical responses; successful guidance takes the exact-metric branch of `responseMetricMatches`.

The logical cache theorem separates identities with different metrics because the metric is part of the modeled key. The implementation-input source uses `history:<exercise>` for canonical progress and `history:<exercise>:ast` for AST progress, with each entry's basis checked while loading. The manifest expressly leaves actual serialized-key correspondence unproved, and I make no refinement claim about browser storage or JavaScript execution.

The requested offline Lean probe is `build/b02-luna-b/probe.lean`. It passed with the manifest flags using the supplied `build/lean-session-check` path. Its printed results, in order, were `true`, `false`, `true`, then five `false` values for the mutated guidance inputs, followed by the distinct logical canonical and AST keys. No internet, toolchain install, or elan invocation was used. Frozen files were not edited.

Command (from the repository root):

```sh
python3 scripts/lean_offline.py --cwd formal --lean-path build/lean-session-check -- ../build/b02-luna-b/probe.lean --trust=0 -DwarningAsError=true -DgenInjectivity=false -j1
```

The command exited 0.
