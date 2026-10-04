# CI Lean dependency preparation evidence

This immutable archive records a CI dependency mismatch and the validation of
its Linux workflow preparation step. It does not claim closure, a successful
rerun of the complete CI workflow, reviewer approval, or deployment correctness.

The original Linux workflow installed Python, Java, and Node but no Lean. The
existing admission mutant test skips when the exact installed Lean toolchain is
absent. CI invokes `verify_closure.py --unittest-report`, whose existing skip
handler records `BLOCK` and returns a failing result. The preserved witness
uses an empty owned `ELAN_HOME` and the unchanged real test/report code. It
narrows discovery to that one test: ordinary unittest says `OK (skipped=1)`,
while the required-test report records `successful=false` and exit code 1.
No proof is executed in this missing-dependency witness.

The preparation step downloads the official Lean 4.34.1 Linux x86_64 archive,
checks its pinned SHA-256, and extracts it into the resolver's required
`ELAN_HOME/toolchains/leanprover--lean4---v4.34.1` layout. It exports the owned
`ELAN_HOME` for subsequent steps, then checks the tool through the existing
network-isolated `lean_offline.py --version` entrypoint. Downloading is a
dependency-preparation operation; Lean execution still starts behind the
existing network namespace boundary. The exact workflow run body, prefixed
with `set -euo pipefail`, was run in owned scratch and returned `PASS`.
The CI workflow tests record 16 passes.
The newly prepared toolchain also passed all 10 admission-bridge tests,
including the test that skips with the empty `ELAN_HOME`.

The official asset is
[`lean-4.34.1-linux.tar.zst`](https://github.com/leanprover/lean4/releases/download/v4.34.1/lean-4.34.1-linux.tar.zst),
asset ID `586761733`, 580,432,872 bytes. Its official SHA-256 is:

```text
47bf4bbd78f70c2e9670598ab7124d92b6efb7330ff33e5fbb4030f6fd72e4e4
```

`artifacts/` preserves the original local witness, reports and logs, official
release/runner metadata, native-pass and CI-run metadata, and preparation/test
validation records. The original run `37228525806` at commit
`ad6ea7ccc40f04d8022063fcf6012e6aef7fb3da` eventually failed its Linux Python
regression step; Windows and macOS jobs passed. The witness establishes a
concrete blocking missing-Lean path, not a claim that every possible failure
in the original Linux run has been diagnosed.
The earlier `workflow-validation.json` still records archive execution as
`PENDING`; the later `prepare-execution.json` records its completed `PASS`.
Both observations are preserved byte-for-byte.

`before/workflow.yml` comes from that exact commit and matches the original
investigation hash. `after/` snapshots the CI-only workflow and documentation
change. No production, test, proof, or frozen verifier bytes were changed by
this investigation or preservation. `replay-inputs.json` binds the existing
repository code needed for the small witness. The 580 MB downloaded archive,
extracted toolchain, private configuration, and environment files are excluded.
Original scratch evidence and older archives remain intact.

Verify archive bytes from the repository root:

```sh
python -I -S -B closure/traffic-refinement/evidence/ci-lean-preparation/verify.py
```

Optionally reproduce only the missing-toolchain witness into a new owned path:

```sh
python -I -S -B closure/traffic-refinement/evidence/ci-lean-preparation/verify.py \
  --reproduce --scratch build/trf-closure/ci-lean-missing-replay
```

The optional replay requires the bound repository source bytes and the normal
installed toolchain used by the original harness's baseline lookup. It changes
only the preserved harness's output-directory assignment, then uses an empty
`ELAN_HOME` for the real test. It downloads nothing, runs no complete suite, and
does not modify archive evidence. Its success means the expected skip-to-BLOCK
behavior was reproduced, not that the tested obligation passed.

`index.json` hashes every archive payload, including this README and verifier;
`index.sha256` binds the index. Retain the index hash independently when citing
the archive. These hashes are integrity evidence, not signatures. Later CI
results or input changes require a new archive.
