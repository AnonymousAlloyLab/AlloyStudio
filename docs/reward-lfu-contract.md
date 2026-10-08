# Counterexample-safe rewards and continuously updated LFU pools

This specification precedes the implementation of the v0.0.7-alpha reward change. It governs the bounded, facts-aware behavioral score. A score of `1.000` is a certificate of agreement in the configured Alloy scope, not a proof of equivalence at every scope.

## Score contract

Each evaluation independently checks both mismatch directions with all reachable model facts: undercoverage and overcoverage. The two private instance pools hold at most 100 oracle-positive and 100 oracle-negative solutions. After admitting newly generated solutions and mismatch witnesses, the learner is evaluated on every retained solution.

Let `P,N` be final pool sizes, `A,R` the positive accepted and negative rejected counts, and `C` the number of satisfiable mismatch directions **only when all retained samples agree** (otherwise zero). The retained ACGN fraction is `n/d`, with `n=A*R` and `d=P*N+C`. If either pool is empty, the score is unavailable. Integer half-up rounding to thousandths is `(2000*n+d)/(2*d)`.

The value 1000 is reserved for nonempty, completely agreeing pools **and independently completed UNSAT checks in both mismatch directions**. Every other available result is capped at 999. SAT, incomplete checks, unknown checks, or any retained mismatch cannot produce `1.000`, even if rounding the fraction would do so. The public Python adapter recomputes this policy from allowlisted evidence rather than trusting a floating point score supplied by a worker.

The regression witness is `P=N=A=R=100,C=1`: the old fraction `10000/10001` rounds to 1.000 despite a counterexample. The new score is at most 0.999. On normal valid counts the intermediate `2000*n+d` is at most 20,010,002, within a Java signed integer.

## Pool update contract

A single retained private context contains the exact oracle source, predicate identity, scope, reachable facts, parsed Alloy world, and the two pools. Changing that context discards the previous parsed world, solutions, cursors and pools. Solution identity is computed from the complete concrete trace rather than a lossy hash; the identity and oracle expressions are private.

A cold context seeds each pool from up to 100 enumerated solutions. Each subsequent fresh evaluation advances each available enumeration cursor by at most one solution and admits the private witnesses obtained from the four current category checks. After these admissions, the learner is classified against the final pools. A mismatch increments that retained entry's frequency once for this evaluation, capped at `Integer.MAX_VALUE`. A matching sample does not increase frequency.

Admission of an already present concrete solution is a no-op. Admission to a full pool evicts the least frequent entry; an equal-frequency tie evicts the oldest admitted entry. Updating frequency does not change admission order. New entries start with frequency one. There are no duplicate solution identities, and a new witness is present immediately after its admission. Later admissions may evict it under the same bounded LFU policy; bounded memory does not promise eternal witness retention. Independent mismatch checks remain necessary regardless of pool contents.

Pool version counters are monotone and saturate at `Long.MAX_VALUE`; they are diagnostic values, not unique snapshot identities. Completed behavioral-result caching is bypassed. Simultaneous identical in-flight work may still share one evaluation; later requests execute a fresh pool update. Persistent JVM reuse remains enabled. The small compatibility cache stores fresh snapshots only for existing explanation/evidence consumers and does not substitute for a new behavioral evaluation.

## Lean obligations and implementation boundary

The new isolated `formal/reward` block proves properties of an executable mathematical scorer and LFU transition model, using the installed pinned Lean 4.34.1 toolchain without networking. Its obligations cover unavailable empty pools, thousandth bounds, exclusion of known counterexamples, the necessary and sufficient full-score gate, integer rounding, arithmetic bounds, bounded pool size, unique identities, duplicate-admission stability, frequency saturation, oldest minimum eviction, witness admission, context replacement, and nondecreasing version counters.

The Lean proofs do not certify Alloy's solver, A4Solution enumeration, Java object memory usage, Python/Java compilation, browser code, or deployments. Source bindings and cross-language probes are separately **tested** evidence. A model theorem and a passing runtime test are not promoted to a universal semantic refinement theorem. The trust boundary includes the pinned Lean kernel/distribution, the offline verifier and its audit code, SHA-256, the operating system, and hardware. No project axioms, placeholders or native proof-evaluation shortcuts are admitted.

Validation results and the exact theorem inventory are generated after the proof and source inputs are frozen. Each run uses two isolated clean builds with no network interface, audits every declaration in the new modules, binds evidence to source hashes, and preserves negative-control failures. Historical proof blocks and evidence are left unchanged.
