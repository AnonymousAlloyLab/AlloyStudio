# TING02 observation-interruption evidence

This immutable public-source archive preserves a local concurrency regression
and its repair witness. It does **not** establish closure, prove the production
implementation correct, or report a deployed-system result.

The unmodified witness injects one `RuntimeError` from the status lock's
`acquire` method. The real CPython 3.10.11
`Thread._wait_for_tstate_lock` exception handler releases that lock and marks
the thread stopped even though a barrier still holds its handler alive.
The v4 reaper retains the first failed observation, then trusts the poisoned
metadata on a later observation and admits a second handler under a cap of one.
The v5 reaper retains a durable uncertainty marker and keeps the reservation.

- `traffic_http.py`, `witness.py`, `before.json`, `after.json`, `tests.txt`, and
  `block.json` preserve the supplied discovery files byte-for-byte.
- `after-source/traffic_http.py` preserves the repaired source identified by
  `after.json`.
- `spec-first/` preserves all 12 non-cache files from the specification freeze,
  including the preceding contract/specification and the six hashed
  preimplementation proof inputs. These proof files are historical inputs,
  not the subsequently repaired proof or evidence that the v5 proof passed.
- `replay-support/` contains the transitive public imports `traffic_profile.py`
  and `traffic_limits.py`. Both hashes match the preserved candidate manifest.
- `runtime.json` records the local Python version and exact relevant stdlib
  helper source. `evidence-index.json` binds every payload file by SHA-256 and
  size and identifies the provenance of copied originals.

`before.json` records `configuredLimit=1`, `liveHandlers=2`, and
`counterexample=true`. `after.json` records the same limit, `liveHandlers=1`,
and `counterexample=false`; the reservation survives reobservation. Both
results say the first handler is still running while reported liveness is
false. The original test log records 14 passing tests. `block.json` is a
candidate input manifest despite its name; it contains no verification-result
status, and its other input files are not bundled or verified by this archive.

The source SHA-256 values are:

```text
before f15442ab8c1372927355b0909529b427498017a4416ee43a284c6b5dc7b28deb
after  f99a209a40e827472a6d70fab7e414ff19e6112b92b0f775a8788f5aca4b9dca
```

From the repository root, verify archive integrity without executing a witness:

```sh
python -I -S -B closure/traffic-refinement/evidence/ting02-observation-interruption/verify_evidence.py
```

Replay both preserved source versions using the recorded CPython 3.10.11
runtime and matching helper implementation:

```sh
mkdir -p build/trf-closure/ting02-observation-replay
python -I -S -B closure/traffic-refinement/evidence/ting02-observation-interruption/verify_evidence.py --replay --scratch build/trf-closure/ting02-observation-replay
```

The verifier checks integrity before replay, copies only the archived witness,
source and two imports into an isolated temporary directory, and invokes the
original CLI (`witness.py SOURCE_PATH`) separately for each source. `-I -S -B`
excludes environment import paths and site packages and suppresses bytecode
output. Recreating the original relative witness location makes its computed
import root the temporary directory. Each result must exactly equal its
preserved JSON. Temporary files are removed afterward; archive files are never
modified. The witness creates a local listener and socket pairs; it makes no
external network request and calls no provider or model.

Replay is deliberately limited to the recorded CPython version and helper
hash. Different Python implementations or stdlib versions may not exhibit this
private-helper behavior. A runtime mismatch is not evidence that the original
regression was absent. The injection is constructed local fault evidence, not
a claim about the frequency of natural interruptions.

The index excludes itself to avoid a self-hash cycle. Its file list includes
this README and the verification script; `payloadRootSha256` hashes the UTF-8
JSON file list with sorted keys and compact separators. The external
`evidence-index.json` SHA-256 is the archive identity to retain when citing it.
Integrity is tamper evidence relative to that retained identity, not a
signature. Preserve this directory unchanged; create a new archive for later
inputs or findings. Successful checks mean only that these bytes and this
specific local replay agree; they never mean closure.
