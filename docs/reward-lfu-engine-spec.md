# Fact-aware reward and continuously refreshed LFU pools

This specification precedes the v0.0.7-alpha Java changes. The live portal retains ACGN's bounded, fact-aware positive/negative classification score. It does not claim unbounded Alloy equivalence.

## Reserved full score

Let `p` and `n` be the positive and negative pool sizes, and `a` and `r` their matching classifications. Let `c` count satisfiable undercoverage/overcoverage directions only when both nonempty final pools match completely, preserving the existing ACGN correction convention. For nonempty pools set numerator `a*r`, denominator `p*n+c`, and calculate an integer thousandth by round-half-up: `(2000*numerator+denominator)/(2*denominator)`, using integer division. A thousandth of 1000 is permitted only if every final retained classification matches and both fact-aware mismatch queries return complete UNSAT results. Otherwise the thousandth is at most 999. Thus a satisfiable counterexample cannot produce a score of 1.000, even when ordinary rounding would do so. Empty positive or negative pools remain unavailable.

## Private instance retention

One sequential behavior worker owns at most one active exact oracle-source/predicate context. The scope and solver settings are fixed by the engine. Switching either context component releases the previous parsed module, cursor and pool references. Learner parsing, context matching, dependency checks, String-universe checks, module facts and public witness redaction remain mandatory.

The parsed oracle module is retained with its solutions; every new learner expression is parsed into that same module so Alloy signature identities match. Each polarity holds at most 100 solutions. Exact identity uses length-prefixed, sorted evaluated tuples of every reachable signature and field in every state, together with trace length and loop state; it never uses the truncated/anonymized public display projection. These identities and private solutions never cross the response boundary.

Cold initialization consumes at most 100 oracle-positive and 100 oracle-negative enumeration solutions. Every subsequent fresh successful evaluation advances each still-satisfiable cursor by one solution, independently of whether its solution is already retained. Category queries additionally admit their up-to-three private witnesses into the pool of the corresponding oracle polarity before the final classifications. This refresh is observable in retained state; completed behavior responses must not be reused by the Python response cache. Concurrent request coalescing remains allowed.

## LFU update rule

A new unique solution enters with frequency 1. Re-admitting an existing exact identity leaves that entry unchanged. When a pool is full, remove the earliest inserted entry among those with minimum frequency and append the new entry. The implementation stores only the bounded ordered entries; there is no growing global identity map or insertion counter.

Final classification evaluates every retained solution exactly once. A mismatch increments its frequency, saturating at `2147483647`; a match leaves it unchanged. This frequency measures a witness's observed usefulness for detecting learner defects, rather than page visits. An optional private epoch saturates at `9223372036854775807` and is diagnostic only: it is neither a freshness proof nor a unique cache key.

## Verification boundary

Lean proves the arithmetic full-score gate and ordered finite LFU transition invariants. Java unit fixtures and Alloy integration fixtures establish corresponding production behavior, including the concrete `10000/10001` rounding regression. Trust remains in the Java/compiler runtime, Alloy parser/solver, production bridge checks and the Lean kernel; the bounded model is not a proof of all application behavior.
