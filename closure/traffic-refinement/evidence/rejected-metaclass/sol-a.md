# TCFG01 tier 2 advisory review — GPT-6.1 Sol A

Verdict: `constructed_breach` for frozen block manifest SHA-256 `52cf7df36483365262f8e98c8310bf08ae0b694762914147bbfce5ee3e248c32`. This review covers TRF00-NUMERIC only and does not discharge any complete TRF-00 or other traffic obligation. I read the fresh Luna A and B JSON/notes before this review; the record binds both current Luna JSON digests. Advisory review records are not proof authority.

## Constructed breach: unsupported object accepted as a duration

`traffic_limits.py:23` checks `type(value) not in (int, float)`. Tuple membership uses equality, so this is not an exact built-in type identity check. A user-defined metaclass can compare equal to `int` or `float` while the object's type is neither. The subsequent call at line 26 invokes the unsupported object's `as_integer_ratio`; returning `(1, 1)` passes all arithmetic checks and the function returns that original unsupported object.

The executable witness is `build/trf-closure/reviews/sol61-a/type_membership_witness.py` (SHA-256 `22497ecf1db742106319a0da88b0d421571e246c0c366a7c78624c419843fcdc`). Replay:

```sh
python3 -I build/trf-closure/reviews/sol61-a/type_membership_witness.py
```

Its preserved result, `build/trf-closure/reviews/sol61-a/type_membership_witness.json` (SHA-256 `904fc7a1e34a5ccb5ce64a1ed209a39cbd290b14facea4e468ec7754743ae340`), records `exactBuiltinType: false`, `acceptedUnsupportedObject: true`, both untrusted protocol invocations, and `bridgeStatus: PASS`. It binds the exact current block digest and production digest `0339bc237e8f10fe29a4862fe3bdf621a31c5e3dbf0d54bb3f12cf28ebc30307`.

Minimal witness logic:

```python
class Masquerade(type):
    def __eq__(cls, other):
        return other is int or other is float

class Unsupported(metaclass=Masquerade):
    def as_integer_ratio(self):
        return 1, 1

x = Unsupported()
assert type(x) is not int and type(x) is not float
assert validated_seconds(x) is x
```

This contradicts the claimed production-to-normalized boundary: `Scalar.invalid .other` is rejected by `Extracted.validatedSeconds` and by the independent `SecondsInterval` relation, while the frozen production accepts this unsupported value. The translator reproduces the membership check in its admitted skeleton and lowers it as exact classification, so bridge success does not detect this semantic mismatch. The witness does not monkey-patch built-ins, change trusted code, or require a production source mutation. The explicit Python TCB trusts exact built-in type identity and built-in ratio semantics; tuple membership on an arbitrary input type does not restrict execution to those semantics. Excluding arbitrary Python program translation does not eliminate the advertised rejection of unsupported input objects.

The integer guard and bound-parameter checks use `is` identity and do not share this defect. The targeted repair is to use explicit identity comparisons for both admitted duration types, update the bridge skeleton, add this counterexample as a rejection regression, and freeze a new block before rebuilding and renewing the entire review ladder.

## Other checks performed

All 19 frozen input hashes matched before the witness. The bridge's nine regression tests and the review ladder's five regression tests passed. A scratch finite probe (`build/trf-closure/reviews/sol61-a/probe.py`) executed 106,210 comparisons against independent Python integer/Fraction interval predicates, including huge integers, wrong bound types, signed zero, adjacent maximum floats, minimum subnormals, nonfinite floats, ordinary numeric subclasses and hostile object methods. All those finite cases matched and preserved accepted object identity. Nineteen malicious AST/module mutations were rejected; three admitted arithmetic mutations changed the generated Lean program. These finite checks did not include custom metaclass equality until the constructed witness and cannot override it.

I inspected the source-bound candidate `build/trf-closure/tcfg01-20261004T150223Z-a3645fc8/report.json`: two identical offline builds each reported 55 registered theorems and zero axioms; zero-admission, introduced-axiom and placeholder negative controls passed. The candidate remains `BLOCKED` pending reviews. Those kernel results concern the normalized program and do not repair this input-classification mismatch. I did not run additional Lean commands.

The revised ladder uses the requested `gpt-6.1-sol` label, cumulative prior-tier JSON hashes and notes hashes, rejects unresolved findings, and incorporates all review records into the frozen build manifest. Model identity remains an advisory declaration rather than cryptographic attestation; no constructed ladder breach was found. The pre-policy archived Sol reviews were not used as current-tier evidence.
