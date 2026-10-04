# TCFG02: HTTP profile and initial admission state

This is the independent specification for **TRF00-HTTP-PROFILE-INIT**, a finite
next subclaim of TRF-00. It specifies a candidate verification surface and reports
no completed verification run. Its machine-readable authority is
[http-profile-spec.json](../closure/traffic-refinement/http-profile-spec.json).
Closing this subclaim will close zero complete original traffic obligations.

The intended production boundary is the 23-field `TrafficProfile`, a defensive
normalization operation, a pure admission initializer, and the actual constructor
assignments that consume that initializer. `TrafficProfile` moves to
`traffic_profile.py`; `traffic_http.py` re-exports the same class. The frozen
[TCFG01](../formal/traffic_config/README.md) scalar validators and proofs remain
unchanged and are an explicit dependency.

## Independent accepted profile

All 23 names, units, defaults and scalar classifications are recorded individually
in the JSON specification. Nineteen fields accept only exact built-in Python
integers from 1 through 67,108,864. The four fields `header_seconds`,
`body_seconds`, `write_seconds` and `idle_seconds` accept exact built-in integers
or finite floats strictly above zero and at most 300 seconds. Float comparisons
use their exact integer ratios. `peer_idle_seconds` remains an integer field.
Booleans, numeric subclasses, nonfinite values and other objects are rejected.

An accepted profile also satisfies all three independent relations:

- `line_bytes <= header_bytes`.
- `header_count <= 100`.
- `public_handlers + control_handlers <= 256`.

The complete default profile is an explicit inhabitation witness. Its public and
control handler limits are 30 and 2, bursts are 60 and 4, and rates are 30 and 2.
These give default totals of 32 handlers, 64 burst credits and 32 requests per
second. The latter two totals are properties of the default profile; custom
profiles are not constrained to those default totals by this subclaim.

The acceptance theorem must quantify over every normalized 23-field input and
prove acceptance **if and only if** the independently defined scalar domains and
three relations hold. A separate theorem establishes preservation of every
accepted scalar representation. The expected schema and predicates cannot be
generated from the extracted production program: that would allow a dropped
field or weakened guard to change its own specification.

## Normalization and constructor boundary

The runtime normalizer accepts the exact `TrafficProfile` type, rejects subclasses
and arbitrary objects, and constructs a fresh exact profile from its registered
fields. Construction validates the copied values and relations. Accepted scalar
values remain unchanged, while the original profile container is not retained
as the admitted configuration. A deliberately invalid exact profile must be
rejected during normalization; later mutation of the caller's original must not
change the admitted copy. Ordinary immutable integer/float field values do not
require deep copying.

Only an explicitly registered `None` default at a constructor boundary may select
the default profile. A falsey arbitrary object must not silently become defaults.
The lane selector must be an exact Boolean. The initial clock sample must be an
exact built-in integer, excluding Booleans and subclasses. Its unit is
nanoseconds; negative origins and arbitrarily large integers are admissible.
An origin sample is not a configurable duration, and accepting it proves nothing
about future clock observations.

Configuration, lane and sampled-clock checks precede the registered Admission
lock, bucket and registry allocations and listener socket binding. This boundary
does not claim that an enclosing `Portal` has allocated no resources. Invalid
values use constant diagnostics without rendering those values or calling their
custom numeric methods.

The restricted AST bridge must account for the complete admitted executable
syntax of the profile and pure initializer, the real `Admission.__init__` and
`TokenBucket.from_initial` assignments, the listener's normalization boundary, and the relevant
imports and re-exports. It must reject missing, duplicate or miswired fields,
unknown control flow, transformed returns, runtime bypasses and later source
rebinding of the extracted classes or helpers. A theorem about a pure helper
without checked constructor consumption does not discharge this claim.

## Initial state and explicit abstraction

For a normalized profile, Boolean lane and supplied signed clock sample, the
independent initial-state relation selects that lane's handler limit, burst and
rate. Bucket capacity is burst times 1,000,000,000 nanocredits, initial credit
equals capacity, and the last clock observation equals the supplied sample.
`active`, `peak`, `accepted` and `rejected` are zero. The owner set, anonymous-owner
sequence and peer ordered map are empty.

The initializer theorem must establish that this relation has exactly one
abstract state for those explicit inputs and that the state satisfies each
registered validity property. In particular, the handler limit is positive and
at most 256; capacity is between 1,000,000,000 and
67,108,864,000,000,000 nanocredits; rate is positive and at most 67,108,864;
credit equals capacity; active occupancy equals the owner cardinality and stays
within the selected handler limit; and the empty peer registry stays within its
positive entry bound. Both public and control initial states must be witnessed.

Concrete empty sets, lists and ordered dictionaries map to their respective empty
abstract collections. The proof does not replace owned records with unexplained
counters: its initial occupancy equation is tied to the explicitly empty owner
set. It makes no induction claim about later reservations, releases or refill.

Lock identity, callback identity and container allocation identity are erased by
the declared abstraction. Successful ordinary construction and a successful clock
callback supplying its validated sample without hostile mutation or reentrancy
are trusted interpretations. The result is uniqueness of the abstract initial
state for fixed external inputs, not equality of separately allocated Python
objects or a proof that allocation always succeeds.

## Required claims and finite evidence

The specification registers seven internal checks: exact schema coverage,
universal acceptance, scalar preservation and defensive copying, the valid
default witness, initial-state validity and uniqueness, actual constructor
linkage, and checked positive/negative/mutation witnesses. Their required pass
predicates are fixed in the JSON file before implementation verification.

The finite witnesses include both default lanes with zero and negative clock
origins; valid boundary values for handler totals, headers, durations and counts;
the adjacent invalid relation values; and zero/Boolean rejection for every
profile field. Runtime checks cover profile impostors, invalid copied fields,
copy independence, incorrect lane/sample types, large signed clock values, one
clock sample, and rejection before the registered runtime allocation points.

Mutation controls must detect a missing or misclassified field, each weakened
relation, incorrect lane selection, wrong token unit or initial field, a
constructor bypass, weakened type checks or rebinding, an introduced axiom and a
placeholder proof. Witness results and Python regression tests remain finite
TESTED evidence; they do not become universal Python execution proofs.

A closure run must freeze a complete input manifest, theorem inventory, verifier
implementations, witness files, mappings, public-claim provenance and trust. It
requires two clean identical offline builds using the installed Lean 4.34.1
toolchain. Every project theorem, including private and generated declarations,
must have an empty transitive axiom set. No `sorry`, `admit`, introduced axioms,
`native_decide` or downloaded proof dependencies are admitted. Reuse TCFG01's
constructive integer comparisons where necessary to keep this audit empty.

The current advisory review sequence remains two GPT-6 Luna, two GPT-6.1 Sol,
then two GPT-6 Astra source-bound reviews. Constructed breaches must be checked
mechanically. Reviews cannot supply proof evidence or override a failed verifier.
The registered deterministic verifier must report `VERIFIED`, `BLOCKED` or
`INFRASTRUCTURE_FAILURE`, with raw evidence bound to its closure ID, input root
and verifier hash. This design document is not that report.

## Trust and remaining TRF-00 work

Trust includes the pinned Lean kernel/compiler/distribution; the frozen restricted
translator, linkage checker, audit and closure scripts; exact Python built-in
type, arithmetic, ratio, dataclass and ordinary attribute semantics; the declared
initial lock/container/callback interpretation; and the host runtime, hashing,
isolation, filesystem/process semantics and hardware. The boundary excludes
monkey-patched dependencies, hostile trusted code and concurrent mutation during
normalization. Full trust records live in the JSON specification.

TRF-00 remains open after this subclaim because the complete service and
deployment profile is not frozen, the independent observation schema is not
bound, and whole-service initial-state correspondence is not established.
Worker/process/RSS limits, queues, channels, evidence and snapshots, shared-host
and proxy budgets, random service identities and OS handles require their own
explicit configuration and mappings. The complete profile, observations,
production mapping, verifiers and provenance must ultimately pass together.
All future admission histories and all other original TRF obligations retain
their existing status.
