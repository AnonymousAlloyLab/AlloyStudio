# TCFG01 revised tier 1 adversarial review — Luna A

Verdict: `no_constructed_breach` for revised frozen block manifest SHA-256 `52cf7df36483365262f8e98c8310bf08ae0b694762914147bbfce5ee3e248c32`. I verified all 19 declared input hashes. Comparing the preserved pre-policy block with the current one shows the scalar Lean model, extracted validator, and production `traffic_limits.py` hashes are unchanged; the revision updates the README, registers the new ladder and its tests, updates the traffic verifier, and changes the review policy to GPT-6.1 Sol. This is an advisory review of normalized numeric guards and review-record enforcement, not proof authority or TRF-00 closure.

`verify_traffic_config.py` imports `check_reviews` from the newly frozen `scripts/review_ladder.py`, adds both ladder files to the exact required input set, invokes the ladder on the source tree before snapshotting and again on the frozen snapshot, and adds its five-test suite to the verifier test run. The ladder fixes the order and model labels as Luna, GPT-6.1 Sol, then Astra. Each record must match its expected model label, tier number, exact block digest and the accumulated hashes of prior-tier JSON records; each notes file is hashed and its digest must match the JSON record. Both records in a tier are checked against the same prior-tier map, then both JSON digests enter the map for the next tier. It returns hashes for all six JSON files and six notes so the verifier includes them in the source-bound manifest.

I ran `python3 -I tests/test_review_ladder.py`: all five tests passed. They confirm that six records and six notes are returned, a previous-generation Sol model label is rejected, missing prior-tier bindings are rejected, edited notes are rejected, and stale block bindings or unresolved findings are rejected. The new test fixture also builds the tiers in the required hash order. All 19 manifest-bound inputs matched before and after these checks; the production guard and Lean extraction hashes remain the same as the preserved candidate.

No executable counterexample to the revised enforcement was found. As with the previous ladder, model identity is validated from the record's `reviewerModel` field; the verifier does not cryptographically attest which model authored a local review. This is consistent with the block's explicit statement that review records are advisory, not proof evidence. The revised block and README accurately describe the model labels and hash chaining that the verifier checks. The old review set and old block are preserved separately; this renewed Luna A record starts with `priorReviews: {}` and binds only the revised block manifest.

Reproduction from the repository root:

```sh
python3 - <<'PY'
import hashlib, json
from pathlib import Path
root = Path('.').resolve()
block = json.loads((root/'formal/traffic_config/block.json').read_text())
assert all(hashlib.sha256((root/p).read_bytes()).hexdigest() == h
           for p, h in block['inputs'].items())
print('checked', len(block['inputs']), 'manifest inputs')
PY
python3 -I tests/test_review_ladder.py
```

The unit tests and hash checks validate the registered ladder behavior and input consistency. I did not run the proof gate; it is being rerun separately. No failed witness was produced or needed.
