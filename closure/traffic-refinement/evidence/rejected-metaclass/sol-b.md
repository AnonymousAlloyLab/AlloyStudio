# TCFG01 tier 2 review B — GPT-6.1 Sol

Verdict: `constructed_breach` for frozen block manifest SHA-256 `52cf7df36483365262f8e98c8310bf08ae0b694762914147bbfce5ee3e248c32`. Finding TCFG01-SOL-B-001 affects TRF00-NUMERIC: the admitted production duration guard accepts an unsupported object although the normalized model and public boundary classify unsupported objects as invalid. This advisory review is not proof authority or closure of any original traffic obligation.

I first read both fresh Luna JSON records and their notes. Their JSON hashes are `c067d1affa85f1b9b0dc9d3763b657b388a34fea3dde1146fbcf83f58f11c9e3` and `5a171193c5dd21ed54c6f888441d79a39bcd6eb2914b6eb0278739ff403e3e1d`. They bind the current block and actual notes. This report binds those exact JSON bytes.

## Constructed witness

Sol A constructed `build/trf-closure/reviews/sol61-a/type_membership_witness.py`. I independently executed that witness against the still-frozen production source with `python3 -I`; my output is `build/trf-closure/reviews/sol61-b/type_membership_replay.json`. It records the exact block hash, production hash `0339bc237e8f10fe29a4862fe3bdf621a31c5e3dbf0d54bb3f12cf28ebc30307`, `bridgeStatus: PASS`, `exactBuiltinType: false`, and `acceptedUnsupportedObject: true`.

The essential input is:

```python
class Masquerade(type):
    def __eq__(cls, other):
        return other is int or other is float

class Unsupported(metaclass=Masquerade):
    def as_integer_ratio(self):
        return 1, 1

value = Unsupported()
assert not (type(value) is int or type(value) is float)
assert validated_seconds(value) is value
```

`type(value) not in (int, float)` performs equality comparison through tuple membership. It invokes `Masquerade.__eq__`, which claims equality with `int`, and therefore admits the unsupported object. The subsequent arbitrary `as_integer_ratio` method returns `(1, 1)`, passes the numeric guard and returns the input object. The replay records both metaclass equality and the unsupported ratio call. No built-in was monkey-patched: the caller supplies an ordinary object with a custom metaclass. The stated trust boundary excludes hostile trusted code, but does not turn caller-defined classification behavior into exact built-in identity. The public invalid-object rejection and exact AST correspondence therefore cross an unestablished semantic boundary.

The translator accepts precisely this faulty tuple-membership skeleton and returns `PASS`; its generated Lean evaluator maps unsupported input to the invalid variant, which always rejects. This is a concrete implementation-to-model mismatch. Repair requires identity comparisons such as `type(value) is not int and type(value) is not float`, corresponding translator admission changes and a regression witness, followed by a newly frozen block and rerun. This review does not modify source or assert that such a repair has already happened.

## Other checked surfaces

The normalized universal proofs themselves remain valid. I freshly rebuilt all three modules through `scripts/lean_offline.py` with pinned 4.34.1 and the registered flags. Four derived universal contracts in `build/trf-closure/reviews/sol61-b/Universal.lean` compiled with empty axiom dependencies: standard integer intervals, standard exact-rational comparisons and arbitrary accepted-result identity plus membership for both validators. Custom comparisons are constructively related to standard integer order.

The fresh auditor and both candidate audits match all 55 registered theorem names, owners, universe parameters and type hashes, with empty transitive axiom sets. Definitions are checked for axiom dependencies, generated/private theorem declarations are included and actual introduced-axiom output is rejected by `check_inventory`. All 19 declared input hashes and the block itself match the 20-entry candidate manifest; every candidate snapshot byte matches. Fresh object hashes equal both candidate builds and recorded hashes. These checks are recorded in `build/trf-closure/reviews/sol61-b/checks.json`.

All 20 registered bridge, numeric-gate and review-ladder tests passed; they did not cover caller-defined metaclass equality. Actual negative-control logs show the zero-admission mutant fails the independent specification, an introduced axiom and its false theorem appear in the auditor and a placeholder fails for `sorry`. The new ladder enforces current model labels, block/tier/prior-JSON/notes hashes and unresolved findings, and the verifier checks it on both source and snapshot. Review authorship is a declared advisory field, not independent model attestation.

The candidate report correctly remains `BLOCKED` for its skipped final reviews. This constructed finding gives an additional reason the old frozen block cannot achieve final closure. The proof and numerical test successes do not discharge the faulty implementation classification. Full TRF-00 and all original 23 obligations remain open. No Internet access or secret files were used, and no frozen source input was edited.
