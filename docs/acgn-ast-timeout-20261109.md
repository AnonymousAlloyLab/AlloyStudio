# Investigation of the first AST timeout

The new ACGN AST run recorded a 60.016641-second timeout on case **17,377**:
`coursesOld/correct/TheS7RkMu4px6Rj7m_inv2.als` (fold 2, CORRECT control).
The student predicate is:

```alloy
no (Person-Professor).teaches
```

Its source SHA-256 is
`3d674df061ee505c73d0b17f3596088021d2966cd8f324ea1f32f162986cc8c1`.
The request compares all **111** compatible, cross-fitted correct references,
including the oracle. The historical AST observation was distance **0** in
**0.289757 seconds** under its original execution profile.

## Direct checks

The exact new request was reconstructed from the frozen inputs, preserving
reference order, fold exclusion and oracle inclusion. A separately bounded
one-CPU investigation service used the frozen classes and libraries. Six real
requests all returned `ok`, distance **0**, a **6-node AST**, and a complete
111-reference comparison. Their JSON responses had identical content after
removing the benchmark timing field.

- External exFAT scratch: **3.828 s cold**, then **2.464 s** and **2.106 s warm**.
- Local ext4 scratch: **1.647 s cold**, then **0.799 s** and **0.761 s warm**.

These are diagnostic repeats of one selected case, not a replacement benchmark
or estimates of corpus-wide runtime. They establish that this input compiles and
that the updated metric/trace computation completes successfully.

## Where the deadline is spent

The original row has `cold_worker: true` and no received engine timing. The
benchmark adapter starts its end-to-end clock **before** retiring an old worker,
deleting its scratch, launching a replacement and transmitting the new request.
A recycled worker can retain parser temporary files from 256 preceding requests;
the probe observed **112 additional `.als` files per request** for this pool.
This benchmark transport differs from the portal's request-owned worker parser
scratch, which is cleared after each response.

Other cold requests in the same running arm expose a substantial cost outside
the engine's own request timer:

- `coursesOld.inv1`: **32.876 s total**, **2.290 s engine**.
- `coursesOld.inv5`: **31.578 s total**, **3.139 s engine**.

A constructed virtual-clock counterexample injected 61 seconds into the previous
worker's retirement, then submitted this same successfully checked predicate.
The unchanged adapter returned `timeout`, with no engine response, and attributed
61.008 seconds to the incoming request. This is an explicitly simulated timing
control; it does not claim to replay the original delay.

The corresponding progress window also contains this task's clean-source export
on the same external exFAT volume. Exported-file creation times span
**20:07:37–20:09:03 UTC**, and the source copy occupies about **1.9 GB**. That
validation activity introduced storage contention into the experiment. Future
validation exports must use separate storage or wait until measurement finishes.

**Diagnosis:** evidence points to an end-to-end worker recycling/storage timeout.
The original trace lacks cleanup/startup stage timings, so their exact split
cannot be recovered. The successful isolated repeats and the large measured cold
request overhead support this diagnosis; they do not prove an exact historical
filesystem delay. Old/new timing comparisons must disclose both the changed
worker count and storage conditions.

## Preservation and continuation

The main supervised run continues. Its source/input snapshot and the original
timeout row are unchanged. The timeout stays in the original denominator; none of
these repeats is substituted for it. TAR, HiGenA-H and HiGenA-M remain untouched.
Diagnostic scratch was owned by the separate investigation service and cleaned
when its JVMs exited.

The [machine-readable evidence](benchmarks/acgn-ast-timeout-20261109.json) records
request/source hashes, all six repeats, the constructed control, cold-tail
observations and the bounds of the interpretation. Private request bodies and
full reference pools remain outside this public report.
