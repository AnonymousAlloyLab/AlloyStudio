# TRF-01: sampled header and body completion deadlines

TING01 is the first concrete child of the unchanged TRF-01 obligation. It covers
read-phase deadline checks; it does not close the complete decoder, capacity or
public/control ingress obligation. The [independent specification](../closure/traffic-refinement/ingress-deadline-spec.json)
was written before the repair. The prior TRF-00 result remains an immutable result
for its earlier source root; this successor changes two request-processing files
and must receive its own evidence and input root.

## Required behavior and observed failure

A completed header or body phase must have a successful clock observation before
its fixed deadline. Equality is expiration. Reading buffered bytes does not grant
extra time. Header parsing and JSON decoding must finish before their respective
completion checks; a successful check before a slow decoder is insufficient.
Starting the body phase must validate the existing header deadline and use that
same sampled clock value as its origin.

The original implementation admitted a buffered header at time 6 after deadline
5. A real socket witness also returned a health response after header processing
crossed its budget. Another witness completed actual JSON decoding, paused beyond
the body budget, and still issued a channel. The discovery artifacts preserve the
original source bytes, input clocks, execution and hashes. A missing final CRLF
header terminator was already rejected; it is a negative control, not a repaired
bug. Other discovered decoder concerns, including embedded bare CR in a request
line and repeated charset parameters, remain recorded work for subsequent
TRF-01 blocks; this deadline result does not certify their parsing.

## Implementation and proof correspondence

`DeadlineReader.check_deadline` reads one clock sample, rejects `now >= deadline`
and returns the accepted sample. Buffered and socket reads check on entry and
completion. The HTTP parser checks after the standard-library parser returns
success; both public and administrator body readers check after JSON decoding.
`begin_body` derives its new deadline from the returned validated sample rather
than taking another clock reading.

The [restricted bridge](../scripts/ingress_deadline_bridge.py) reads the actual
comparison and guard placements. It admits exactly the recorded work syntax and
call/return interpretation in fourteen methods, producing six successful-path
programs. Other successful work is represented as an arbitrary time-advancing
segment. Exception and rejection paths do not dispatch. Removing a final guard
changes the generated program; it does not silently restore the intended guard.
Unknown work, phase writes, call targets and returns are rejected.

The independent Lean evaluator executes guard, work, phase-reset and delivery
actions over explicit clock observations. Delivery and reset are unconditional:
the evaluator does not itself forbid an unsafe delivery. A separate structural
policy requires a guard after the latest work segment. Theorems prove safe cuts
for accepted executions of the extracted programs. Constructed unguarded late
execution remains possible in the evaluator and is rejected by that policy.

`Before now deadline` is explicitly defined as `not (deadline <= now)` over
integers. This is the strict complement of expiration. The proofs do not rely on
Lean library order-conversion lemmas with transitive `propext` dependencies.
Finite Python clock ordering is embedded into these logical integers under the
declared translator/runtime trust. The model's reset duration is the realized
normalized difference after floating addition, not an assertion that floating
arithmetic equals exact real arithmetic.

## Verification boundary

The registered verifier is `python3 scripts/verify_ingress_deadlines.py`. It
requires two clean identical offline Lean builds, an empty transitive axiom set,
the actual source bridge, finite socket/clock/mutation tests, and six source-bound
advisory reviews: two GPT-6 Luna, two GPT-6.1 Sol and two GPT-6 Astra. Reviews do not
determine proof validity. A candidate run cannot become VERIFIED before the review
records are included in its frozen root.

A completion cut is the successful clock observation. No theorem asserts that
the OS cannot pause the process between that observation and the next instruction,
or that total handler lifetime is below a read budget. Python/stdlib execution,
finite clock order, the registered work abstraction, Lean, hashing and host
isolation remain explicit trust. The full HTTP/JSON grammar, arbitrary host-language
execution, pre-handler memory refinement, enabled control-listener topology,
Windows/macOS and IIS/Cloudflare deployment remain outside this child claim.

TRF-01 remains OPEN until its original pass condition is discharged as a whole.
