# SmartContract: Canonical feedback for Sending Ether

## Reproduction and cause

On 2026-10-16, the supplied `SmartContract.als` reproduced `UNSUPPORTED_FORM`
when Canonical prepared `sendEther[from, to: Address, amt: Int]`, including an
unchanged reference body. The same failure occurred in a small portable model.
The supplied source SHA-256 was
`40f6aa3ceb154777ee6ea401482e9df2da06e1342845462d55ddc34b894b9703`.

The internal exception was:

```text
Imported call lacks an independently pinned declaration: util/integer/minus/EXPRESSION/2
```

An isolated `plus` call failed for the corresponding `plus/EXPRESSION/2`
signature. The comparison guard, predicate parameters, next-state prime, and
relational override prepared successfully without those calls. This identifies
the missing library signatures as the cause; the public error did not expose
private reference source or internal exceptions.

## Change and scope

The bundled `alloy.jar` supplies `models/util/integer.als`, which declares both
`plus` and `minus` as expression functions taking two `Int` arguments. The
[callable ledger](../vendor/acgn/src/is/fivefivefive/ACGN/alloy/AlloyLibraryCallableLedger.java)
now pins those two signatures. The broader alpha.5 library audit extends the
ledger to `alloy-library-callables-v5`; see [library coverage](alloy-library-callables.md).
The vendor snapshot records the change and its upstream file hashes.

The existing call representation preserves module identity, call kind, declared
arity, import authority, argument order, and occurrence metadata. The change
adds no arithmetic rewrite rules. A locally declared function named `minus`
keeps its own declaration identity. Exact self-comparison now works; equivalent
arithmetic expressions are still subject to the existing structural metric and
need not receive distance zero.

The arithmetic boundary described in the historical
[alpha.3 validation report](releases/v0.0.7-alpha.3.md#validation-and-boundaries)
was accurate for that release. Historical benchmark and proof snapshots retain
their original hashes. The current-source freshness check passes its record
integrity gate and continues to report the historical AP01, TRF-00 and TRF-01
closures as `STALE`; these regression results do not renew formal closure.

## Validation

- The supplied full model prepares successfully. Uploading and publishing
  `sendEther` and dependent `deposit` succeeds, and both exact bodies return
  distance zero in Canonical and AST modes.
- A real local `/api/feedback` check against the uploaded full model returns
  HTTP 200 and distance zero for the unchanged Sending Ether body. Changing its
  `>=` guard to `>` returns distance one and one located operation in both modes.
  All four requests reuse one persistent feedback JVM. Public source ranges
  match the submitted body exactly.
- A bounded Alloy witness with two addresses, four-bit integers and two states
  confirms the strict guard rejects a transfer of the sender's entire balance
  that the original predicate admits.
- Seven portable regressions cover identity, defective edits, trace replay,
  source locations, argument order, callee identity, import aliases, local-name
  separation and ten malformed-signature rejection controls. The combined
  targeted run passes 22 tests, including dependency integration, vendor
  provenance and relocated runtime checks.
- The engine self-test passes 500 checks. Existing call-authority,
  call-extraction and non-temporal corpus regressions pass 4,487, 161 and 45
  checks respectively. An independent code review found no concrete issues.

The portable regression requires the compiled engine and JDK, and does not
depend on the private SmartContract file, API credentials, or external services:

```bash
python scripts/build_engine.py
python -m unittest tests.test_integer_call_feedback -v
```

The integration test uses the supplied private model when present, otherwise a
portable representative. Diagnostic scratch and local HTTP evidence are under
`build/smart-contract-canonical-20261016/`; no private model was added to source
control.

## Loading the fix

Rebuild the engine and restart the backend so persistent workers and cached
feedback use the new classes. The local engine has been rebuilt and tested.
For IIS, build and deploy a new timestamped package using the existing
[upgrade workflow](../deploy/iis/README.md); an already-running deployment does
not acquire changed Java classes from this checkout automatically. The uploaded
model does not need to be rewritten or re-imported.
