# RWD01: counterexample-safe reward and LFU contract

This is an isolated proof block for the mathematical definitions in `Reward.lean` and `LFU.lean`. It does not extend or rewrite any historical portal proof block.

The registered inventory contains every theorem declaration, including compiler-generated declarations. The auditor rejects project axioms and nonempty transitive axiom dependencies of theorems or definitions. Two fresh builds use installed Lean 4.34.1 with `--trust=0`, warnings treated as errors, a sanitized environment and an isolated network namespace. There is no toolchain download during proving.

Run `python3 scripts/verify_reward_contract.py` from the repository root. `--candidate` runs the kernel checks but intentionally leaves the review workflow unresolved. Source-bound review notes follow the required two Luna, two GPT-6.1 Sol and two Astra ladder; they cannot discharge a theorem.

The model establishes the score's full-agreement gate, unavailable empty pools, bounds and integer-arithmetic safety; the LFU model establishes bounded and unique retention across repeated admissions, first minimum eviction, oldest equal-frequency ties, duplicate stability, immediately retained new witnesses, mismatch-frequency saturation and stable identity order, context replacement, and fresh work after completion. Explicit regression witnesses include the old rounding error and both LFU eviction policies.

Production Java/Python correspondence remains separately tested and is excluded from this model closure. Concrete solution identity construction, Alloy satisfiability/UNSAT correctness, actual allocated bytes/RSS, global unbounded equivalence and browser behavior are excluded. Diagnostic epoch saturation intentionally does not imply strict snapshot uniqueness.

The authority is a generated `build/reward-contract/<run>/report.json` bound to the frozen manifest, registered verifier, toolchain inventory and two kernel audits. A VERIFIED result applies only to the frozen finite mathematical surface under the declared kernel, verifier, hash, OS and hardware trust.
