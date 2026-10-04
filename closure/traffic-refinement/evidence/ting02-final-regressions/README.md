# TING02 final regression preservation

This archive preserves passing finite Linux regressions after the identified-but-
not-started thread repair. These tests are not closure or release approval.
The required GPT-6.1 Sol review attempts were stopped by automatic screening,
including the mathematical retry. That unresolved review requirement remains
separate from these regression results; no reviewer pass is asserted or created
here. Publication was not performed at preservation time.

The original records contain 1,047 Python tests, 23 HTTP observation cases across
each of three execution modes, 77 browser checks, seven dashboard checks, and
378 Java engine self-checks. All reports and captured logs retain their original
bytes. The browser and functional recorded source hashes match the final Python
snapshot. Provider calls were disabled; real deployment credentials and `.env`
files were excluded before staging.

Earlier passing and failed records remain unchanged in the separate
[`ting02-regressions`](../ting02-regressions/README.md) archive. In particular,
the initial 1,037-test run and its three old HTTP 404 expectations remain
available there. This new archive does not replace, edit, or reinterpret it.

`index.json` binds each preserved artifact and source payload by SHA-256;
`index.sha256` binds the index. Full Python manifests include immutable historical
fixtures. Identical payloads refer directly to existing files under
`closure/traffic-refinement/evidence/`; newly needed payloads are stored once
under `objects/`. Verification checks every referenced file's hash and length.
No mutable working-tree source is used as a replay payload, and historical
archives are not recursively copied into this archive.

The functional and runtime runs use the full final Python input set for replay
so that all transitive imports and historical fixtures are available. Browser
replay uses its exact 655-file input manifest. The private IIS ZIP is excluded;
the original package result preserves its checksum, and the package can be
assembled from the browser inputs and their existing compiled Java classes.
No Java engine compilation was performed during browser package assembly.
Previously archived exercise data and dependencies are referenced by hash
instead of copied again. Screenshots are outside these pass criteria.

Verify all bytes from the repository root:

```sh
python3 closure/traffic-refinement/evidence/ting02-final-regressions/replay.py
```

Reconstruct the exact final input bytes into a new owned directory:

```sh
python3 closure/traffic-refinement/evidence/ting02-final-regressions/replay.py \
  --run python-final --output build/trf-closure/ting02-final-replay
```

Other run names are `functional`, `browser`, and `runtime`. The destination must
not exist. Replay normalizes Python/shell permissions to 0755 and other files to
0644; source bytes remain exact. The saved runners document original commands
and staging, rather than authorizing a fresh copy of a mutable checkout.

The original executions depend on the available Python/JDK/Git/Lean/Node/
Playwright/Chromium environment. The materializer installs nothing and contacts
no network service. Regression execution must keep `OPENAI_DISABLED=1` and set
`TMPDIR` to an existing owned scratch directory. SHA-256, the Python/filesystem
implementation, and preservation of historical evidence remain explicit trust
dependencies. The checksum is an integrity record, not a digital signature.
