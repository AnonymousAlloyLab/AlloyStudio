# ACGN backend update, 9 November 2026

This development update rebases AlloyStudio's vendored backend from ACGN
`1e2667351532b0c632166fa21ae5fbc7308a8fe7` to
`8c9bd4b922ecee99d496be2515fa78cb09f3e12b`, including its transparent AST view.
NOOP and Body nodes are removed before AST counting, postorder indexing, tree
matching and edit replay. This changes the measured metric inputs, rather than
only hiding markers in a drawing. The public AST response identifies its input
view as `acgn-semantic-ast-noop-body-erasure-v1`.

## Integration contract

- Import the pinned upstream Java sources, parser compatibility overlays, and
  complete Alloy 6.2/CLI 1.11/JSON 20260814/SLF4J 2.0.20 dependency set. Compile the
  parser overlays ahead of the binary adapter and retain all eight declared
  libraries. Every installed dependency and source is hash-bound in
  `vendor/acgn/snapshot.json`.
- Preserve AlloyStudio's source-occurrence metadata, bundled utility signatures,
  canonical rendering guard, and request-global work/allocation/deadline limits.
  Local patches remain explicit in the snapshot. TraceResources also checks the
  enclosing work budget; variable-length provenance strings do not determine
  the fuel counter.
- Keep Canonical as the default. Select the minimum over the complete compatible
  CORRECT pool including the teacher oracle. Never publish a partially evaluated
  minimum, hidden target expressions, teacher names, or native trace payloads.
- Consume the updated ACGN's replay-checked native matrix, declaration and temporal
  choices instead of independently choosing a second trace. The three projected
  public component costs must match the authoritative numerical decomposition.
- Bind editor locators, canonical locators and structural tutor retrieval to the
  same comparison-form view that ACGN renders. Contract flattened metric-child
  paths back to actual child occurrences; fail instead of guessing an occurrence.
- Preserve bounded facts-enforced behavior queries, dependency unlocking,
  parameterized predicates, progress, administration, candidate admission, and
  warm worker process limits. Suppress private solver logging before initializing
  SLF4J 2.

The update does not renew any frozen Lean-to-production binding. Historical
proofs and evidence retain their original input hashes. New regression results
are tests/checks of this integration, not a claim of a new formal refinement.

## Updated paper example

The same previously published source pair is used:

```alloy
sig Node { adj : set Node }
// Student
all n:Node | Node in n.^adj+n.^~adj
// Selected correct reference from the earlier paper example
all n:Node | Node in n.(^adj + iden)
```

The updated ASTs have 17 and 13 nodes, with an 8-step replay: five deletions, two
relabelings and one insertion. The fixed pair's updated Canonical distance is 5.
The earlier wrapper-counting AST distance 10 and earlier Canonical distance 3
belong to the previous backend. The selected reference is retained to make the
figure comparable; it is not claimed to be the nearest reference under either
updated metric.

The new compact single-column assets are in
[`output/zhang-shasha-graphs-inv7-acgn-20261109`](../output/zhang-shasha-graphs-inv7-acgn-20261109/README.md).
The earlier figures and their provenance remain in their original directories.

## Full-corpus rerun contract

Only the ACGN-pinned Canonical and AST arms are rerun. TAR, HiGenA-H and HiGenA-M
remain unchanged. All five previous completed arm archives are hashed before
execution and checked afterward; none is opened for writing.

The exact existing 61,598-case cohort, corrected classifications, whole-path
five-fold assignments, fixed environments and oracle-inclusive training pools
are retained. There are 42,388 incorrect cases and 19,210 CORRECT controls.
The experiment is a new versioned run, with a complete frozen source/runtime
copy and copied hash-checked inputs. No saved outcome from a previous Canonical
or AST run is counted as a new observation.

`benchmarks/alloy4fun/run_pinned_acgn.py` prepares the frozen run and launches
only those two arms, sequentially. Each uses four warm sequential JVM workers,
512 MiB heap per JVM, a 60-second complete-request deadline and a 60-second internal
work allowance; the production portal's default 8-second allowance is unchanged.
The existing 250,000,000 fuel, 1,000,000 allocation-entry and 1 MiB response limits
remain in force, so a request may explicitly refuse work before 60 seconds.

A Linux user-service cgroup enforces a 4 CPU quota, 4 GiB memory-high threshold,
6 GiB hard limit and zero swap. A separate supervisor starts only with 10 GiB host
memory available and stops its own workload if available memory falls below 4 GiB
or experiment storage drops below its reserve. The source copy stays unchanged
throughout each manifest-bound run. All scratch is run-owned external storage;
no experiment is launched into the system temporary directory.

An arm is complete only after 61,598 unique case rows and 61,598 unique native
responses match the frozen evaluation identities. Expected timeouts and
unsupported/error outcomes remain in the denominator. Incomplete runs cannot
be reported as complete or silently resumed from mismatched response evidence.
Resource profiles differ from the earlier 16-worker Canonical/AST runs, so a
before/after runtime comparison must report that difference explicitly.
This workstation also places the new parser scratch on an external exFAT volume,
whereas the historical run used local ext4 storage. Warm benchmark JVMs recycle
after 256 requests; parser temporary files are removed when each JVM is reaped.
The recorded times include parsing, temporary-file I/O, transport and recycling.
They do not isolate the effect of the ACGN algorithm update or establish an
equal-resource speed ranking against the preserved baselines.

## Current verification/execution record

The rebased engine builds successfully and its 500-check engine self-test passes.
A constructed persistent-worker counterexample found that Alloy 6.2's
`SATFactory.get("sat4j")` probes unrelated native solvers, leaving a plingeling CNF
and native-library directory outside request scratch. The behavior/validation
adapters now select `SATFactory.DEFAULT` directly: the pinned JAR initializes
that field to exactly `SAT4JRef.INSTANCE`. This preserves SAT4J selection while
avoiding discovery. The cleanup assertion and 77 behavior/validation regressions
pass after this fix. Only those solver-setup methods changed after the distance
run was frozen; no Canonical/AST computation, locator or benchmark path changed.
The HTTP projection publishes only the registered AST view identifier and fixed
omitted-node list; arbitrary engine metadata is discarded. Two constructed
metadata-substitution controls and the existing source-location boundary checks
pass. The wrapper-erased paper example was exported from production, with a successful
private node-edit replay. Broader regression checks and the new corpus run are
in progress; final observations will be linked after their completion.

The machine-local run root is
`/media/augustus/8618-0ED0/AlloyStudio/acgn-rebase-20261109/`. It contains the
previous vendor snapshot, merge provenance, private diagnostics and new
experiment artifacts. This workstation path is not a portable installation
requirement and its private corpus outputs must not be committed publicly.
