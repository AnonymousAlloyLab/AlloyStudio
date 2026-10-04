# TCFG01: strict scalar configuration guards

This block supports the narrow **TRF00-NUMERIC** subclaim: the admitted
production scalar guard program accepts exactly its independently specified
integer and exact-rational intervals and preserves accepted input values.
It does **not** close TRF-00 or any other complete traffic refinement obligation.

`traffic_limits.py` contains the production functions. The frozen restricted
translator in `scripts/traffic_config_bridge.py` checks their entire executable
AST against the admitted control-flow skeleton, including exact built-in type
checks, bound parameter checks, exception handling, normalization, constant
diagnostics and identity returns. It translates the actual arithmetic guards
into `TrafficConfig/Extracted.lean`; it does not replace them with a fixed correct
formula. The independent interval specification is in `TrafficConfig/Spec.lean`.

## Mathematical boundary

`Scalar.integer n` represents an exact built-in Python integer, excluding Boolean
values and numeric subclasses. `Scalar.floating numerator denominator`
represents a finite built-in float by its exact integer ratio. An integral-valued
float remains distinct from an integer for count validation. The invalid variant
covers Boolean values, NaN, both infinities and other unsupported objects.
Malformed zero denominators are rejected in the normalized model; negative
denominators cannot be produced by the trusted built-in ratio normalization.

The public correspondence theorems quantify over every normalized scalar and
every represented bound:

- `validated_int_iff` accepts exactly integer values between the inclusive bounds,
  with a nonnegative minimum and an ordered maximum.
- `validated_seconds_iff` accepts exactly positive-denominator ratios no greater
  than the positive duration limit, with a nonnegative or strictly positive
  numerator according to `minimumZero`.
- `validated_int_preserves` and `validated_seconds_preserves` show that acceptance
  returns the original scalar, without clipping, coercion or rounding.
- Invalid values, floats used as counts, and zero denominators are rejected.
  Concrete kernel-checked witnesses cover zero, negative, oversized, huge integer,
  exact-maximum and fractional values.

`Scalar.lean` defines signed integer comparisons by constructor cases to avoid
axiom-bearing derived order lemmas. `lessEqual_iff_standard` and
`less_iff_standard` prove that these comparisons agree with Lean's actual `Int`
order, using constructive proofs from primitive nonnegativity and natural-number
subtraction. The comparison semantics are consequently checked, not merely
assigned a familiar name.

The normalized Lean function parameters already have the required `Int`/`Bool`
types. Rejection of incorrectly typed Python bound parameters is checked by the
exact AST skeleton and regression tests, not by a theorem over arbitrary Python
objects. Python object classification and `as_integer_ratio` are explicit trusted
boundaries, rather than unproved Lean premises.

## Verification and trust

`theorems.json` registers all 55 theorem declarations, including generated and
private declarations, by exact type hash and universe parameters. `Audit.lean`
enumerates the imported project modules, reports every theorem's complete
transitive axiom set, rejects any axiom-bearing project definition, and exposes
introduced project axioms. The registered axiom allowlist is empty. No proof uses
`sorry`, introduced axioms, `native_decide`, or an assumed validator correctness
premise. Automatic injectivity helper generation is disabled uniformly by the
registered compiler flags, not bypassed by excluding generated declarations.

Closure requires `scripts/verify_traffic_config.py` to verify the exact frozen
input manifest, restricted AST correspondence, the full theorem inventory, two
clean identical builds and six source-bound adversarial reviews: two GPT-6 Luna,
then two GPT-6.1 Sol, then two GPT-6 Astra. `scripts/review_ladder.py` enforces the
current model identities; historical frozen blocks retain their recorded policy.
Each Lean process runs with the pinned installed Lean
4.34.1 toolchain inside a fresh user and network namespace, without Internet
access. Mutation controls require rejection of the zero-admission defect,
introduced axioms and a placeholder proof. Review and build records are evidence
for the exact input hashes, not a perpetual claim about later revisions.

A successful report establishes only the named subclaim, under the explicitly
recorded trust in the Lean kernel/compiler and installed distribution; the
restricted Python-to-Lean translator and audit/verifier scripts; exact built-in
Python type, arithmetic and ratio semantics; and the host standard library,
hashing, isolation and hardware. Host classification assumes no monkey-patched
built-ins or hostile trusted code.

Complete configuration inventory and unique initialization, constructor/locking
refinement, runtime allocation bounds, worker lifecycle, solver history
independence, whole-process memory containment and platform deployment remain
outside this block. The frozen TB01 traffic model and its claims are unchanged.
Development compilation alone does not establish final closure; consult the
source-bound verifier report and its six review records.
