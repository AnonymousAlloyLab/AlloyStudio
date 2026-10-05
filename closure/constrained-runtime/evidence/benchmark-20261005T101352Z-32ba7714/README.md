# Constrained runtime benchmark evidence

This is preserved finite test evidence, not a formal closure certificate. The
benchmark's `PASS` is the finite contract in `input-manifest.json` and the frozen
`snapshot/scripts/benchmark_constrained_runtime.py`. No prior closure status is
inherited. `manifest.json` seals the complete files in this directory using
`scripts/evidence_store.py`; `summary.json` is the measured result.

All 181 exercises were run with a starter and the first known-correct body using
both Canonical and AST: 724 requests for each engine in each of two Linux child
processes pinned to two and four available logical CPUs. Baseline and current
engine phases were sequential. Every one of 1,448 current complete response
values matched its preserved AP01 counterpart; hashes use canonical JSON and do
not omit response fields. Four JSONL files retain all 2,896 baseline/current
case records, including request and full-response hashes, without model bodies.

The historical AP01 baseline report is copied unchanged in `baseline/summary.json`.
Its SHA-256 matches the existing append-only work-calibration evidence identified
in `provenance.json`. The harness required the preserved class inventory to match
that report and the runtime JAR hashes to agree. Only the unsupported outer
`workMillis` transport metadata was removed for the older worker protocol; the
request and context identity were unchanged. Those old binaries remain in the
local scratch path recorded by provenance and are not included in this evidence
directory. A future rerun requires those exact binaries or its own separately
established baseline; rebuilding current source is not an equivalent baseline.

On each CPU scenario, 14 public page/revalidation visits started no JVM; 12
simultaneous identical checks shared one computation (11 joined), and 12 repeats
used cached results. Eight distinct edits across both algorithms started no
additional JVM. Three behavioral fixtures, including productionLineNew inv3,
retained all four category entries and the limit of three instances per category.
Some categories had zero instances. The administrator service existed and its
default network admission remained 404; authenticated workflows were not part
of this benchmark. A 20 ms cooperative limit returned atomic WORK_LIMIT, followed
by a normal successful response in the same JVM with no additional launch.

The bulk phase retained the normal 512-task recycle policy: two launches over
724 measured plus cold/warm requests, one planned recycle, and process high-water
one. The later combined HTTP fixture observed high-water two for feedback and
behavior lanes. The heap cap was 256 MiB per JVM; 50 ms RSS samples are observed
process-tree memory, not a RAM quota or an exact memory peak. Affinity constrains
logical CPU scheduling, not exclusive physical cores. Cold/warm and distribution
timings are observations on this host, not timing bounds or a claimed speedup.

`historical/timeout-audit-before.json` preserves the original constructed traces:
cold startup consumed the work deadline; three capacity waits poisoned the failed
startup circuit without launching a child; and queued expiry waited for a busy
dispatcher. These were synthetic Python protocol children, not JVM runs. The
original witness source is archived for context and must not be rerun in place
against changed production code or treated as a fully frozen historical build.
The regression logs and source snapshots record the current targeted checks.

No model/reference bodies, credential files, staged runtime directories, network
provider calls, or admin passwords were published here. This evidence excludes
Windows/IIS execution, the full 61,598-model corpus, universal timing/liveness,
and new formal proof-to-production correspondence. See `provenance.json` for
the trust boundary. Existing historical reports were not changed.
