# TING02 v5 finite regression preservation

This immutable archive preserves the passing Linux regressions recorded after
the v5 durable thread-observation uncertainty repair. These finite checks do not
establish closure, universal functional equivalence, a reviewer result, or
release approval. Earlier archives and original scratch evidence are retained
unchanged.

The original reports record **1053 Python tests**, 23 HTTP observation
cases in each of three execution modes (69 total), 77 browser checks, seven
dashboard checks, and 378 Java engine checks. The final Python report records
`changedInputPaths=[]`. Browser and functional source subsets and the runtime
class/dependency hashes agree with that final manifest. The browser run used
existing Java binaries and did not compile the engine. Providers were disabled.

`artifacts/` holds exact original reports, logs, and the Python input manifest.
`harnesses/` preserves the original Python/browser runners and this archive's
builder. The functional checker and runtime code are bound source inputs.
`index.json` binds artifact bytes and 4841 final Python input paths;
`index.sha256` binds the index. The checksum is an integrity record, not a
signature; retain the index hash independently when citing this archive.

Identical content refers directly to immutable existing files beneath
`closure/traffic-refinement/evidence/`. New content is stored once under
`objects/SHA256`. Every referenced byte sequence is checked; historical archives
are not recursively copied. Original path manifests are preserved even when
their payloads share the same object. Mutable working-tree sources are not used
as materialization payloads.

The private IIS ZIP is excluded. Its original packaging report and logs retain
its checksum and result; the public browser input set and existing compiled
classes remain available for package reconstruction. Private configuration and
environment files were excluded from the recorded source manifests and are not
read by this archive's tools.

From the repository root, check all artifact and referenced-input bytes,
report bindings, and source-subset agreement:

```sh
python -I -S -B closure/traffic-refinement/evidence/ting02-v5-regressions/replay.py
```

Materialize the exact final Python input set into a new owned scratch directory:

```sh
python -I -S -B closure/traffic-refinement/evidence/ting02-v5-regressions/replay.py \
  --run python-final --output build/trf-closure/ting02-v5-replay
```

Other run names are `browser`, `functional`, and `runtime`. Browser materializes
its exact 655-input manifest. Functional and runtime materialize the full final
Python set, which includes their matching recorded subsets and supporting
fixtures. The destination must not exist. Python and shell files receive mode
0755, other files 0644; file bytes stay exact. This command materializes inputs;
it does not execute or rerun any regression suite.

The original executions depend on the recorded Python/JDK/Git/Lean/Node/
Playwright/Chromium environment. A functional rerun also needs the Git baseline
commit named in its report; Git history and installed toolchains are not bundled.
The saved runners describe original commands and staging, not permission to
copy current mutable sources. Any separately requested regression execution
must retain `OPENAI_DISABLED=1` and an owned existing `TMPDIR`. Archive checking
and materialization install nothing and contact no network service.

Scope remains the recorded finite Linux cases. Native Windows/macOS behavior,
deployed IIS/proxy behavior, arbitrary execution histories, screenshots, reviewer
approval, and closure are outside this archive. SHA-256, Python/filesystem
behavior, installed execution tools, and continued availability of historical
evidence are explicit dependencies. Preserve this directory unchanged and use
a new archive for later inputs or findings.
