# TB01: traffic model proofs

This is an isolated mathematical proof block for the
[performance specification](../../docs/backend-performance-spec.md). It does not
change the server, worker launch path, browser or original B01/B03 proof blocks.
The [implementation handoff](../../docs/traffic-proof-handoff.md) identifies the
runtime work and the correspondence that must accompany it.

The block contains three independent executable models:

- `Traffic/Resources.lean`: owned reservations and persistent worker lifecycle.
- `Traffic/Reuse.lean`: exact computation identity, shared work and delivery.
- `Traffic/Ingress.lean`: logical admission credits and inbound boundaries.

The exact theorem statements, including private/generated theorems, are frozen
in `theorems.json`. `claims.json` maps the advertised mathematical claims to
those declarations. `witnesses.json` names executable model counterexamples and
their limitations; it does not turn prose scenarios into production exploits.
`block.json` freezes all verifier-relevant sources, the specification, the
handoff, trust and proof policy. No model invariant alone certifies Python locks,
HTTP parsing, Java evaluation, OS process exit or browser event timing.

Run from the repository root on the Linux proof host:

```sh
python3 scripts/verify_traffic_proofs.py
```

The proof process uses installed Lean **4.34.1**, `--trust=0`, one compiler worker,
warnings as errors and disabled generated injectivity lemmas. Every compiler and
audit subprocess runs in a fresh network namespace with a minimal environment.
There is no network/toolchain-download fallback. Scratch, logs and two independent
clean builds are stored under `build/traffic-proofs/`, never shared `/tmp`.

The gate checks the exact theorem/type inventory and rejects **every transitive
axiom dependency**, including standard axioms. Source restrictions reject
placeholders, introduced axioms, unchecked native proof evaluation and
metaprogramming escapes. The registered audit is separately declared verifier
trust. Deliberately invalid scratch fixtures check placeholder and custom-axiom
rejection; those fixtures are outside the accepted proof library.

Six source-bound advisory reviews follow the requested order: two GPT-6 Luna,
then two GPT-6 Sol, then two GPT-6 Astra. Higher tiers read the earlier reports;
an alleged invariant breach requires a concrete mechanically checkable witness.
Reviews never substitute for kernel proof checking. The normal gate requires
the complete ladder. `--candidate` is a development check that omits it and keeps
the overall result BLOCKED even when both mathematical builds pass.

The generated `report.json` is authoritative for a particular frozen input root.
`status: VERIFIED` is limited to **TB01 traffic-model-only**, its exact claim set,
the declared verifier and trust. `productionRefinementStatus` remains
`NOT_ESTABLISHED`; the original application obligations remain OPEN. Failed or
stale bindings block the model gate; unavailable isolation/tooling is an
infrastructure failure. No speedup, total-memory bound or production traffic
immunity follows from these mathematical results.
