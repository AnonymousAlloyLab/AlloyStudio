# Reproducing the Alloy4Fun comparison

The [comparison report](../../docs/alloy4fun-comparison.md) presents the measurements. The [measurement protocol](protocol/README.md) defines their denominators and limits. This directory executes five deterministic variants: Live canonical, Live raw AST using Zhang–Shasha, TAR at depth 2, FM24 historical hints, and FM24 historical hints with one mutation. Canonical remains the portal default.

All five registered arms are **complete**, with 61,598 recorded outcomes each. The [versioned results JSON](../../docs/benchmarks/alloy4fun-results.json) records the final metrics, source audit, passing raw-response audit, and execution provenance. The measured experiment is the five-fold held-out comparison described below; an original-policy full-pool operational comparison remains unmeasured.

Use Python 3.10 or newer, Git, and a JDK 17 with `java` and `javac` on `PATH`. TAR compilation additionally requires a Java 8 `rt.jar` as its bootstrap class library; runtime execution still uses Java 17. These commands use a POSIX shell and the benchmark transports use POSIX process groups and nonblocking pipes. Measurements were performed on Linux; the complete baseline experiment has not been validated on Windows or macOS. No OpenAI key, Luna request, portal server, MongoDB, Neo4j, Maven, or IIS package is needed.

The full **ACGN `classified-data` corpus** and **`alloy4fun-augmented/ast_identical_predicate_pairs.csv`** are external inputs. The portal's exercise catalogue alone does not contain the full benchmark cohort. The preparation script requires exactly 66,080 classified files and 4,482 excluded student/oracle raw-AST-identical pairs, producing 61,598 evaluation cases: 42,388 incorrect and 19,210 CORRECT controls. Original historical metadata is downloaded separately from [Zenodo record 8123547](https://zenodo.org/records/8123547).

Run the following from the Alloy Studio repository root. Adjust the three external paths; the example Java 8 path is a Linux installation path. On another installation, pass the actual Java 8 `jre/lib/rt.jar` path. Keep a fresh `BENCH_DATA` directory for a new experiment; graph preparation can reuse existing intermediate records, which is useful for recovery but does not measure a fresh preprocessing run.

```bash
BENCH_CORPUS=/home/augustus/ACGN/classified-data
BENCH_EXCLUSIONS=/home/augustus/ACGN/alloy4fun-augmented/ast_identical_predicate_pairs.csv
BENCH_BASELINES=/home/augustus/lp_baselines
BENCH_JAVA8_RT=/usr/lib/jvm/java-8-openjdk-amd64/jre/lib/rt.jar
BENCH_DATA=build/benchmarks/alloy4fun-reproduction
BENCH_WORKERS=16
```

The completed Canonical, AST, and two FM24 arms used **16 workers each**. After a recorded global-memory incident, TAR completed a fresh run with **four workers inside a 6 GiB cgroup**, retaining the 60-second budget. The four earlier completed arms remained byte-for-byte unchanged. The interrupted 16-worker TAR attempt is archived separately under `build/benchmarks/alloy4fun-v2/tar-interrupted-oom-20260929T234811/`; none of its rows enters the final comparison. Runtime and deadline-conditioned availability therefore describe unequal resource configurations, not an equal-resource speed ranking. Earlier overlapping runs also remain separate diagnostic evidence. The two legacy label corrections are recorded in [label-corrections.json](protocol/label-corrections.json), with source labels retained separately.

The commands below document the component invocations, not an automatic registration of a new experiment. Before a fresh run, choose a host-safe aggregate memory limit and record the actual worker counts, sequential schedule, and input provenance. The strict publisher currently accepts the registered uniform 16-worker profile or the specific documented 16/4 recovery profile; another worker configuration requires a separately registered reporting contract. No script here creates a fresh resource profile automatically. Copying the completed run's schedule, guard report, or preservation hashes into a new experiment would misrepresent its execution and fail validation. The published metrics and figures are available without rerunning the private corpus.

Each JVM owns a private directory under `build/benchmarks/tmp/`; override the base with `ALLOY_BENCHMARK_TMP_ROOT` if needed. Parent processes remove only their own worker directories after exit or forced termination. Live and TAR workers recycle after 256 learner requests, charging cleanup/restart to the next request. FM24 workers recycle after 256 native actions: cleanup is charged to the completing action and startup to the next one, within the relevant learner request deadlines. Tests can also avoid the host's system temporary directory:

```bash
mkdir -p build/tests/tmp
export TMPDIR="$PWD/build/tests/tmp"
```

1. **Fetch the pinned author artifacts and historical metadata.** The FM24 fetcher checks repository commits, bundled JAR SHA-256 values, Maven dependency hashes, and publisher checksums for all 17 historical JSON files. It refuses an existing checkout at a different commit. [sources.json](fm24/sources.json) records the exact resources. TAR is checked out separately at the commit used by this comparison; skip its clone/checkout commands if that exact checkout already exists.

```bash
python3 benchmarks/alloy4fun/fm24/fetch_resources.py \
  --destination "$BENCH_BASELINES"

git clone https://github.com/Kaixi26/TAR.git "$BENCH_BASELINES/TAR"
git -C "$BENCH_BASELINES/TAR" checkout --detach aad990e405b811bd245518cbb63ecc4f065c5275
```

The FM24 resources are the paper's [Alloy4Fun fork](https://github.com/anaines14/Alloy4Fun), [SpecAssistant](https://github.com/K1yps/SpecAssistant), and [HiGenA](https://github.com/anaines14/higena). Their downloaded code and binaries remain outside this repository. No root license file was found in those FM24 checkouts; public download availability is not a redistribution license. Dependency and upstream licenses retain their own terms.

2. **Build the production engine and the two benchmark bridges.** This avoids rebuilding the portal or deployment ZIP. The TAR builder downloads one pinned Eclipse annotation dependency and also compiles the independent Alloy repair validator. `--javac /path/to/javac` is available for a nondefault compiler; `--java8-rt` selects the Java 8 bootstrap library. Leave TAR's output at its default location because `run_tar.py` reads that build manifest.

```bash
python3 scripts/build_engine.py
mkdir -p build/benchmarks/alloy4fun/classes
javac -encoding UTF-8 \
  -cp 'build/engine/classes:vendor/acgn/lib/*' \
  -d build/benchmarks/alloy4fun/classes \
  benchmarks/alloy4fun/live/LiveWorker.java

python3 benchmarks/alloy4fun/tar/build.py \
  --tar-root "$BENCH_BASELINES/TAR" \
  --java8-rt "$BENCH_JAVA8_RT"
```

3. **Freeze the evaluation cohort, recover whole student branches, and preserve the complete historical training inventory.** The 4,482 excluded teacher-exact submissions remain available as training nodes and transitions, but are never additional evaluation cases. Excluding those transitions from history would remove observed paths to successful answers.

```bash
python3 benchmarks/alloy4fun/prepare.py \
  --corpus "$BENCH_CORPUS" \
  --exclusions "$BENCH_EXCLUSIONS" \
  --output "$BENCH_DATA" --workers "$BENCH_WORKERS"

python3 benchmarks/alloy4fun/fm24/extend_history.py \
  --inventory "$BENCH_DATA/inventory.jsonl" \
  --payloads "$BENCH_DATA/payloads.jsonl" \
  --output "$BENCH_DATA/history-payloads.jsonl"

python3 benchmarks/alloy4fun/fm24/lineage.py \
  --metadata-dir "$BENCH_BASELINES/alloy4fun-zenodo-8123547" \
  --cases "$BENCH_DATA/cases.jsonl" \
  --output "$BENCH_DATA/lineage.json"

python3 benchmarks/alloy4fun/fm24/lineage.py \
  --metadata-dir "$BENCH_BASELINES/alloy4fun-zenodo-8123547" \
  --cases "$BENCH_DATA/inventory.jsonl" \
  --output "$BENCH_DATA/lineage-all.json"
```

Each branch starts at the first descendant of the original instructor model. All descendants, including submissions to other questions, share a fold. The fold is the big-endian integer SHA-256 of `alloy4fun-path-v1:` plus `original:branch_root`, modulo five. Each evaluation case uses the other four folds for historical graphs and CORRECT student reference pools. Every pool also retains its fixed teacher oracle. This five-fold experiment differs from the FM24 paper's single 70/30 split.

The fold exclusion was introduced by this benchmark. Original `Alloy4FunAugmenter` pools contain all admitted CORRECT submissions and an oracle, deduplicate raw ASTs, and rank only incorrect submissions. The production pool importer also has no fold exclusion, but restricts candidates to its selected exercise's exact environment and oracle and uses lexical deduplication. Benchmark nonempty hints on held-out CORRECT submissions must not be attributed to either full-pool policy. The recorded full-compatible-pool membership audit establishes inclusion of those bodies, not new full-pool distances, hint rates, or runtimes. Removing only the fold filter would still not reproduce the original augmenter's grouping and deduplication rules.

4. **Build fresh FM24 graphs and compile its native bridge.** This step calls the unchanged author normalizer and APTED components and writes all five graph files before any online measurement. Use the new output directory shown below to measure uncached preprocessing.

```bash
python3 benchmarks/alloy4fun/fm24/prepare_graphs.py \
  --payloads "$BENCH_DATA/history-payloads.jsonl" \
  --evaluation-cases "$BENCH_DATA/cases.jsonl" \
  --lineage "$BENCH_DATA/lineage-all.json" \
  --baselines "$BENCH_BASELINES" \
  --output "$BENCH_DATA/fm24-final" --workers "$BENCH_WORKERS"
```

Keep `lineage.json` for the 61,598 evaluation cases and `lineage-all.json` for the 66,080 historical cases. Their overlapping model IDs have identical branch assignments. The directory names in these commands are also the strict summarizer's expected layout.

The adaptation replaces MongoDB persistence and Quarkus scheduling with an in-memory graph index. It applies the upstream MIN-TED policy's graph-wide min/max scaling before shortest-path selection. Equal-cost choices use stable lexical ordering. A constant weight range is explicitly unsupported because the upstream normalization divides by zero. Native expression round-trip failures likewise mark affected fold graphs unsupported; the adapter does not credit hints from silently incomplete graphs. Both conditions remain in the evaluation denominator. The bundled HiGenA artifact selects its own default edit mapping; this is an adapted author-artifact experiment, not an exact reproduction of the published web deployment.

5. **Run all five variants sequentially.** Each command processes the entire evaluation manifest because no `--limit` is supplied. For a new controlled study, use one worker configuration that fits the host's available memory and an enforced aggregate memory limit. A per-JVM heap limit does not bound the complete batch. The preserved reported experiment uses the explicit recovery configuration above. Each JVM has a 512 MiB heap, one active processor, and Serial GC. TAR can require substantial compute time: at a 60-second deadline, the 42,388 incorrect cases alone permit about 706 worker-hours for one variant.

```bash
python3 benchmarks/alloy4fun/run_live.py \
  --data "$BENCH_DATA" --folds "$BENCH_DATA/lineage.json" \
  --output "$BENCH_DATA/live-full" \
  --workers "$BENCH_WORKERS" --timeout 60

python3 benchmarks/alloy4fun/run_ast.py \
  --data "$BENCH_DATA" --folds "$BENCH_DATA/lineage.json" \
  --output "$BENCH_DATA/ast-full" \
  --workers "$BENCH_WORKERS" --timeout 60

python3 benchmarks/alloy4fun/run_fm24.py \
  --cases "$BENCH_DATA/cases.jsonl" \
  --data "$BENCH_DATA/fm24-final" --baselines "$BENCH_BASELINES" \
  --output "$BENCH_DATA/fm24-history" \
  --workers "$BENCH_WORKERS" --timeout 60

python3 benchmarks/alloy4fun/run_fm24.py \
  --cases "$BENCH_DATA/cases.jsonl" \
  --data "$BENCH_DATA/fm24-final" --baselines "$BENCH_BASELINES" \
  --output "$BENCH_DATA/fm24-mutation" \
  --workers "$BENCH_WORKERS" --timeout 60 --mutations

python3 benchmarks/alloy4fun/run_tar.py \
  --cases "$BENCH_DATA/cases.jsonl" \
  --output "$BENCH_DATA/tar" \
  --workers 4 --timeout 60 --depth 2
```

The completed registered experiment used the following guarded recovery command instead of invoking TAR directly. It is shown for provenance; do not rerun it against the completed output:

```bash
python3 benchmarks/alloy4fun/run_guarded_tar.py --data build/benchmarks/alloy4fun-v2
```

It requires the frozen `resource-profile.json` recovery inventory and refuses the now-existing `tar/` output, so it cannot overwrite completed or interrupted results. The original partial TAR directory is retained separately. The launcher starts a small user-service supervisor and a separate bounded TAR service: four workers, 6 GiB hard memory limit, 4 GiB soft threshold, no swap, four-core CPU quota, and reduced priority. It refuses startup below 10 GiB available memory and stops its own workload below a 4 GiB global reserve. The workload is bound to the supervisor. Do not repeat the failed unbounded 16-worker TAR launch.

The supervisor completed the full raw-response audit, final report publication, and figure generation after TAR succeeded. `recovery/tar-guard.json` and `recovery/finalization-guard.json` both record successful completion with zero OOM events; `recovery/pipeline-completion.json` binds the final results hash and confirms preservation of the four earlier arms. These files and `tar-recovery-progress.log` remain under the private `build/benchmarks/alloy4fun-v2/` directory. The transient user services do not enable system-wide lingering or promise to survive a user-manager shutdown. This recovery launcher targets the retained registered experiment; new experiments need their own recorded resource profile rather than borrowing its evidence.

TAR checkpoints contain one closed gzip member per raw response, committed before the associated numeric row. Complete matching pairs can resume under the exact same manifest. A missing partner, truncated member, changed row, or inconsistent source/manifest hash is refused without discarding existing bytes. The completed recovery run reports zero resumed rows and does not combine measurements from different worker counts.

For a pilot, add `--limit 100` and use separate output directories. Pilot results are not full-cohort results. To resume an interrupted run, repeat its exact command with `--resume`; configuration or bound source hashes must match. Do not rebuild adapters or modify graph inputs while a measurement is running. Online wall time includes deterministic parsing and hint production; TAR's independent bounded repair verification has its own 15-second timeout and separately recorded duration. Setup and graph construction are reported separately.

6. **Validate completed runs and publish the local report.** First audit every raw response against its numeric observation. This checks raw-response correspondence, not formal closure. The strict summarizer requires that complete passing audit, all five complete result files, and their `run.json` completion records. It validates cohort, source, binary, fold, and graph hashes before producing final results. A native hint Hit Rate is availability of guidance, not repair correctness or educational effectiveness; read the protocol before interpreting a ranking.

```bash
python3 benchmarks/alloy4fun/audit_results.py \
  --data "$BENCH_DATA" --output "$BENCH_DATA/evidence-audit.json"

python3 benchmarks/alloy4fun/summarize.py \
  --data "$BENCH_DATA" --baselines "$BENCH_BASELINES"
```

By default, this intentionally updates `docs/benchmarks/alloy4fun-results.json` and the generated-results section of `docs/alloy4fun-comparison.md`. To save a separate rerun report, use `--json-output` and `--markdown`; the chosen Markdown file must already contain the generated-results marker pair, for example by copying the comparison document first. `--allow-partial` writes a clearly marked progress artifact under `build/` and does not update final public results.

The cohort files, both required tool-build manifests, current compiled classes, pinned baseline JARs, evaluation `lineage.json`, FM24 graph files, `evidence-audit.json`, the registered `resource-profile.json`, all five completed runs, and their raw response archives must remain available for this check. The recovery profile additionally requires the hash-bound guard source and completed guard evidence, the separately archived interrupted TAR run, and unchanged hashes for the four preserved arms. Extra provenance files such as `environment.json` and `fm24-final/fresh-native-preprocessing.json` are included when present, but are not prerequisites. A separate `history-audit.json` is useful supplemental evidence and is not required by the strict summarizer. A fresh graph build already writes its timings to `fm24-final/preprocessing.json`; do not replace them with cached-rebuild timings and describe them as fresh construction.

The two final report figures have been generated with Matplotlib 3.10.9. To regenerate them from the completed JSON:

```bash
python3 benchmarks/alloy4fun/plot_results.py \
  --input docs/benchmarks/alloy4fun-results.json \
  --output-dir docs/benchmarks
```

This exports native Hit Rate and timely-hint availability figures in SVG and PNG, together with `alloy4fun-figures.json` binding the input and output hashes. Both canonical and raw-AST modes appear alongside TAR and both FM24 variants. The figures label the incorrect-input denominator, the four preserved 16-worker arms, and the fresh 4-worker TAR arm. They disclose the unequal resource configurations and do not present an equal-resource speed ranking. The generator refuses `RUNNING`, partial, unaudited, or inconsistent summaries; it creates no provisional public figures. A separate rerun can use its own completed summary and output directory.

For ad hoc metric inspection, the generic aggregator remains available. It keeps successful and failed cases in the denominator, but does not replace the strict completion and artifact validation above.

```bash
cat "$BENCH_DATA/live-full/results.jsonl" \
    "$BENCH_DATA/ast-full/results.jsonl" \
    "$BENCH_DATA/tar/results.jsonl" \
    "$BENCH_DATA/fm24-history/results.jsonl" \
    "$BENCH_DATA/fm24-mutation/results.jsonl" \
    > "$BENCH_DATA/results-all.jsonl"

python3 benchmarks/alloy4fun/protocol/metrics.py \
  "$BENCH_DATA/results-all.jsonl" --cases "$BENCH_DATA/cases.jsonl" \
  --timeout 60 > "$BENCH_DATA/metrics.json"

python3 -m unittest discover -s benchmarks/alloy4fun/protocol -p 'test_*.py' -v
python3 -m unittest discover -s benchmarks/alloy4fun/tar -p 'test_*.py' -v
python3 -m unittest discover -s benchmarks/alloy4fun/fm24 -p 'test_*.py' -v
```

The FM24 native integration tests currently look for downloaded resources at `/home/augustus/lp_baselines`; elsewhere they skip those native checks. The standalone worker and all preparation/running commands accept `--baselines` as shown. Keep raw `responses.jsonl.gz`, graph files, and extracted payloads under the ignored `build/` tree: they can contain complete student and reference expressions. Public reports should contain aggregate metrics and provenance rather than hidden solution bodies.
