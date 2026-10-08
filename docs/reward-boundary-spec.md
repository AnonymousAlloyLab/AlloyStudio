# Behavioral reward boundary and retained evidence

This contract is specified before the Python boundary is changed for
`v0.0.7-alpha`. It complements the worker's continuously updated, bounded LFU
instance pools and the Lean reward contract. It does not claim unbounded Alloy
equivalence or establish that the solver implements Alloy semantics correctly.

## Score contract

For positive and negative sample counts `p, n` in `1..100`, accepted positives
`a <= p`, rejected negatives `r <= n`, and the worker's semantic correction
`c` in `0..2`, define `numerator = a*r` and `denominator = p*n+c`. The correction
counts SAT mismatch directions only when all sampled instances agree. The
existing ACGN fraction is retained, but conversion to thousandths uses exact
integer half-up rounding:

```
rounded = (2000*numerator + denominator) // (2*denominator)
```

The displayed score can be `1.000` only when both samples are nonempty, every
sample agrees, and the undercoverage and overcoverage solver results are both
complete UNSAT with no witnesses. In every other available case, the rounded
integer is capped at `999`; division by `1000` occurs only after this decision.
A concrete mismatch witness therefore cannot display `1.000`, even when
`10000/10001` ordinarily rounds to that value. Missing samples retain the
existing unavailable-score response and never become a fabricated zero or one.

Python independently recomputes the expected integer score from the validated
counts and categories. A worker supplying the old rounded-one response is
rejected as unusable evidence. Unknown, incomplete UNSAT, malformed category
polarity, incorrect counts, nonfinite numbers, or private metadata remain
rejected or stripped according to the existing projection. This preserves the
four category views, instance caps, module facts, fixed scope, and private
oracle boundary.

The frontend and Luna input projection also refuse to describe a score as full
agreement when counts disagree or a mismatch category is SAT, unfinished, or
missing. These are additional presentation checks; the production Python and
Java calculations are the score authorities. Candidate admission continues to
require its own complete, fact-aware bounded checks and administrator approval.

## Continuous observation and evidence lifetime

Each sequential behavioral request must reach the worker so that its current
LFU pools can be evaluated and updated. A completed HTTP result is not reused
as the score of a later request, even for an identical draft. The behavior
scheduler uses `cacheable=False`, while concurrent requests already sharing an
in-flight exact-key computation still share that single observation. The
feedback result cache, persistent JVM pool, lane bounds, timeouts, cancellation,
and administrator approval policy remain unchanged.

The bounded `behavior_cache` remains a store of immutable displayed snapshots
for legacy explanation requests without a channel. It retains at most 32
snapshots and 16 MiB for 120 seconds; it is not consulted by behavioral
evaluation. A new successful observation replaces the same exact-key snapshot.
Failed observations are not stored. Legacy guidance must match the requested
token to the retained snapshot and never recreate examples. Modern channel
evidence retains its existing accounted, immutable pins, so explanations can
refer to exactly the instances the student saw even after LFU updates. Neither
kind of evidence is a score cache.

## Registered finite checks

`tests/test_reward_lfu.py` constructs the former rounded-one counterexample in
both mismatch directions, checks the integer rounding boundary, rejects false
worker claims, exercises sequential observation and in-flight sharing, and
checks evidence snapshot isolation. Existing behavior, education HTTP,
candidate-admission, scheduler, and real Alloy tests remain regression checks.
These tests bind the production projection and scheduler calls to the stated
contract; they do not constitute a universal proof of the production runtime.
