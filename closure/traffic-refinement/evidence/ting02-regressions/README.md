# TING02 regression preservation

This archive preserves finite Linux regression records, including the initial
failed run. It does not turn those records into a universal equivalence proof
or native IIS/macOS verification. Reports and captured logs retain their original
bytes. Provider calls were disabled and private deployment configuration was
excluded before staging.

The successful records contain 1,045 Python tests, 23 HTTP observation cases in
each of three execution modes, 77 browser checks, seven dashboard checks, and
378 Java engine self-checks. The initial 1,037-test run failed three subtests
whose old expectation was HTTP 404; the tightened inbound parser returned HTTP
400 for a decoded backslash, a NUL, and a newline-bearing identifier. Its report,
log, and exact input inventory remain separately available. No failure has been
rewritten as a pass.

`index.json` binds every saved artifact and every source payload by SHA-256.
`index.sha256` binds that index. The full Python manifests include previously
archived closure fixtures; copying those archives recursively would waste space
and obscure their identity. Identical payloads instead refer to their existing
immutable paths under `closure/traffic-refinement/evidence/`. New payloads are
deduplicated under `objects/`. Verification reads every referenced payload and
checks its hash and length. No mutable working-tree source is a replay input.

The browser's 655 recorded inputs and the functional check's 219 recorded inputs
match the successful Python snapshot exactly. Functional and runtime replays use
that complete snapshot to include their transitive imports and archived baseline
fixtures. Browser replay uses its own exact 655-file manifest. The runtime report
independently binds the checked class files and JARs.

No IIS ZIP is archived here. Browser package assembly used the existing compiled
Java classes; it did not rebuild the engine. The original package result retains
its archive hash and is reproducible from the browser inputs. Exercise datasets
and JARs are reused from their existing historical evidence instead of being
copied again. Screenshots are not part of these mechanical pass criteria.

From the repository root, verify all stored and referenced bytes:

```sh
python3 closure/traffic-refinement/evidence/ting02-regressions/replay.py
```

Reconstruct a new isolated input directory (it must not already exist):

```sh
python3 closure/traffic-refinement/evidence/ting02-regressions/replay.py \
  --run python-final --output build/trf-closure/ting02-replay
```

Other run names are `python-initial-failure`, `functional`, `browser`, and
`runtime`. File bytes are exact; replay normalizes Python/shell file permissions
to 0755 and other files to 0644. Toolchains and operating-system behavior remain
external dependencies: the original environment supplied Python, JDK 17-compatible
Java tools, Git, local Lean resources, Node, Playwright, and Chromium. No network
installation is performed by the materializer.

For a functional replay, run `scripts/check_ingress_functional_invariance.py`
inside the reconstructed directory with `OPENAI_DISABLED=1` and `TMPDIR` pointing
to an existing owned scratch directory. For the Python suite use
`python3 -B -m unittest discover -s tests -v` under those same conditions.
The archived runner scripts document the original staging and browser commands;
they are provenance records, not instructions to copy a current mutable checkout.

Hash checks detect missing or changed dependencies. They rely on SHA-256,
Python/file-system correctness, and preservation of the referenced historical
evidence. The checksum file is an integrity record, not a cryptographic signature.
