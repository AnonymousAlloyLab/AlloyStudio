package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.core.egraph.JavaEgglog;

/** Launches each ablation arm in a fresh JVM and combines time/RSS measurements. */
public final class EGraphAblationSuite {
    private static final List<String> ENGINES = List.of(
            "raw-egraph", "raw-egraph-debruijn",
            "java-egglog", "java-egglog-debruijn",
            "slotted-egraph", "canonical", "typed-slotted-port-egraph");
    private static final List<String[]> TRANSITIONS = List.of(
            new String[] {"raw-egraph", "raw-egraph-debruijn"},
            new String[] {"raw-egraph", "java-egglog"},
            new String[] {"raw-egraph-debruijn", "java-egglog-debruijn"},
            new String[] {"java-egglog", "java-egglog-debruijn"},
            new String[] {"java-egglog-debruijn", "slotted-egraph"},
            new String[] {"slotted-egraph", "canonical"},
            new String[] {"canonical", "typed-slotted-port-egraph"});

    private EGraphAblationSuite() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Files.createDirectories(options.output);
        AblationRunManifest.Context context;
        if (options.reportOnly) {
            context = AblationRunManifest.loadCompatibleArms(options.output, ENGINES);
            Path requestedInput = options.input.toAbsolutePath().normalize();
            if (!requestedInput.equals(Paths.get(context.datasetRoot))) {
                throw new IllegalStateException("Report input " + requestedInput
                        + " does not match manifest dataset " + context.datasetRoot);
            }
            options.threads = context.workers;
            options.maxHeap = context.heap;
            options.limit = context.limit;
            options.seed = context.seed;
        } else {
            context = AblationRunManifest.capture(
                    options.input,
                    options.maxHeap,
                    options.threads,
                    options.limit,
                    options.seed);
        }
        List<RunMetrics> runs = new ArrayList<>();
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "EGraphAblationSuite/arms",
                ENGINES.size(),
                "arms",
                options.reportOnly ? "in report-only mode" : "in isolated JVMs");
        int completedArms = 0;
        for (String engine : ENGINES) {
            if (options.reportOnly) {
                Path engineDir = options.output.resolve(engine);
                runs.add(RunMetrics.read(
                        engineDir.resolve("metrics.properties"),
                        engineDir.resolve("process.time"),
                        engineDir.resolve("pairs.csv")));
            } else {
                System.out.println("Running " + engine + "...");
                runs.add(runEngine(engine, options, context, progress, completedArms));
            }
            progress.update(++completedArms);
        }
        progress.finish(completedArms);
        validateParallelism(options, runs);
        writeComparisonJson(options.output.resolve("comparison.json"), options, context, runs);
        writeMinimumDistances(options.output.resolve("minimum_distances.csv"), runs);
        writeDisagreements(options.output.resolve("equivalence_disagreements.csv"), runs);
        writeCanonicalOnly(options.output.resolve("canonical_only_vs_slotted.md"), context, runs);
        writeMarkdown(options.output.resolve("summary.md"), options, context, runs);
        AblationRunManifest.writeRoot(
                options.output, context, AblationRunManifest.allGeneratedOutputs(ENGINES));
        System.out.println("Wrote " + options.output.resolve("comparison.json"));
        System.out.println("Wrote " + options.output.resolve("minimum_distances.csv"));
        System.out.println("Wrote " + options.output.resolve("equivalence_disagreements.csv"));
        System.out.println("Wrote " + options.output.resolve("canonical_only_vs_slotted.md"));
        System.out.println("Wrote " + options.output.resolve("summary.md"));
        System.out.println("Wrote " + options.output.resolve(AblationRunManifest.ROOT_MANIFEST));
    }

    private static RunMetrics runEngine(
            String engine,
            Options options,
            AblationRunManifest.Context context,
            ExperimentProgress progress,
            int completedArms) throws Exception {
        Path engineDir = options.output.resolve(engine);
        Files.createDirectories(engineDir);
        Path timeFile = engineDir.resolve("process.time");
        Path logFile = engineDir.resolve("run.log");
        String java = Paths.get(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add("/usr/bin/time");
        command.add("-v");
        command.add("-o");
        command.add(timeFile.toString());
        command.add(java);
        command.add("-Xms32m");
        command.add("-Xmx" + options.maxHeap);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(EGraphAblationStudy.class.getName());
        command.add("--input");
        command.add(options.input.toString());
        command.add("--output");
        command.add(engineDir.toString());
        command.add("--engine");
        command.add(engine);
        command.add("--threads");
        command.add(Integer.toString(options.threads));
        if (options.limit > 0) {
            command.add("--limit");
            command.add(Integer.toString(options.limit));
        }
        if (options.verbose) {
            command.add("--verbose");
        }

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(Paths.get("").toAbsolutePath().toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(logFile.toFile());
        Process process = builder.start();
        while (!process.waitFor(30, TimeUnit.SECONDS)) {
            progress.heartbeat(
                    completedArms,
                    1,
                    "running " + engine + "; details in " + logFile);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new IllegalStateException(engine + " exited with " + exitCode + "; see " + logFile);
        }
        Path propertiesPath = engineDir.resolve("metrics.properties");
        if (!Files.isRegularFile(propertiesPath)) {
            throw new IllegalStateException(engine + " did not produce " + propertiesPath);
        }
        RunMetrics metrics = RunMetrics.read(propertiesPath, timeFile, engineDir.resolve("pairs.csv"));
        AblationRunManifest.writeArm(engineDir, engine, context);
        return metrics;
    }

    private static void writeComparisonJson(
            Path path,
            Options options,
            AblationRunManifest.Context context,
            List<RunMetrics> runs) throws IOException {
        JSONObject root = new JSONObject();
        root.put("generatedAt", Instant.now().toString());
        root.put("runManifest", context.toJson());
        root.put("inputRoot", options.input.toString());
        root.put("threadCount", options.threads);
        root.put("logicalProcessors", AblationParallelism.logicalProcessors());
        root.put("threadPolicy", AblationParallelism.POLICY);
        root.put("maxHeap", options.maxHeap);
        root.put("limit", options.limit);
        root.put("seed", options.seed);
        root.put("javaVersion", System.getProperty("java.version"));
        root.put("sharedBaselineRuleSet", JavaEgglog.ruleSetVersion());
        root.put("sharedBaselineRewriteRules", new JSONArray(JavaEgglog.ruleNames()));
        JSONArray runArray = new JSONArray();
        for (RunMetrics run : runs) {
            runArray.put(run.toJson());
        }
        root.put("runs", runArray);
        JSONArray transitions = new JSONArray();
        for (String[] transition : TRANSITIONS) {
            transitions.put(transitionJson(
                    findRun(runs, transition[0]), findRun(runs, transition[1])));
        }
        root.put("equivalenceTransitions", transitions);
        root.put("equivalenceDisagreementPairs", disagreementCount(runs));
        Files.writeString(path, root.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeDisagreements(Path path, List<RunMetrics> runs) throws IOException {
        Set<String> paths = new TreeSet<>();
        for (RunMetrics run : runs) {
            paths.addAll(run.statusByPath.keySet());
        }
        StringBuilder csv = new StringBuilder("relativePath,status");
        for (RunMetrics run : runs) {
            csv.append(',').append(run.engine);
        }
        csv.append('\n');
        for (String predicatePath : paths) {
            boolean first = runs.get(0).equivalentPaths.contains(predicatePath);
            boolean differs = false;
            for (int i = 1; i < runs.size(); i++) {
                differs |= runs.get(i).equivalentPaths.contains(predicatePath) != first;
            }
            if (!differs) {
                continue;
            }
            csv.append(csvValue(predicatePath)).append(',')
                    .append(csvValue(runs.get(0).statusByPath.getOrDefault(predicatePath, "")));
            for (RunMetrics run : runs) {
                csv.append(',').append(run.equivalentPaths.contains(predicatePath));
            }
            csv.append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeCanonicalOnly(
            Path path,
            AblationRunManifest.Context context,
            List<RunMetrics> runs) throws IOException {
        RunMetrics raw = findRun(runs, "raw-egraph");
        RunMetrics rawDeBruijn = findRun(runs, "raw-egraph-debruijn");
        RunMetrics egglog = findRun(runs, "java-egglog");
        RunMetrics egglogDeBruijn = findRun(runs, "java-egglog-debruijn");
        RunMetrics slotted = findRun(runs, "slotted-egraph");
        RunMetrics canonical = findRun(runs, "canonical");
        Set<String> canonicalOnly = new TreeSet<>(canonical.equivalentPaths);
        canonicalOnly.removeAll(slotted.equivalentPaths);
        int correct = 0;
        int incorrect = 0;
        Map<String, Integer> grouped = new java.util.TreeMap<>();
        for (String predicatePath : canonicalOnly) {
            String status = canonical.statusByPath.getOrDefault(predicatePath, "");
            if ("CORRECT".equals(status)) {
                correct++;
            } else {
                incorrect++;
            }
            int slash = predicatePath.indexOf('/');
            String problem = slash < 0 ? predicatePath : predicatePath.substring(0, slash);
            String key = problem + "\u0000" + status;
            grouped.put(key, grouped.getOrDefault(key, 0) + 1);
        }
        RunMetrics exact = findRun(runs, "typed-slotted-port-egraph");
        StringBuilder markdown = new StringBuilder("# Fast Rewrite IR-Only Equivalences\n\n");
        markdown.append("This file is generated from the same manifests and pair CSVs as the combined ablation report.\n\n");
        markdown.append("- Run ID: `").append(context.runId).append("`\n");
        markdown.append("- Git SHA: `").append(context.gitSha).append("`\n");
        markdown.append("- Dataset SHA-256: `").append(context.datasetSha256).append("`\n");
        markdown.append("- Canonical-only pairs: ").append(canonicalOnly.size()).append("\n");
        markdown.append("- CORRECT: ").append(correct).append("\n");
        markdown.append("- Incorrect: ").append(incorrect).append("\n\n");
        markdown.append("## By Problem And Status\n\n");
        markdown.append("| Problem class | Status | Pairs |\n");
        markdown.append("| --- | --- | ---: |\n");
        for (Map.Entry<String, Integer> entry : grouped.entrySet()) {
            String key = entry.getKey();
            int separator = key.indexOf('\0');
            String problem = key.substring(0, separator);
            String status = key.substring(separator + 1);
            markdown.append("| ").append(problem).append(" | ").append(status).append(" | ")
                    .append(entry.getValue()).append(" |\n");
        }
        markdown.append("\n## Pairs\n\n");
        markdown.append("| Source | Status | Raw zero | Raw DB zero | Egglog zero | Egglog DB zero | Slotted zero | Fast Rewrite IR zero | Certificate-Integrated IR zero |\n");
        markdown.append("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (String predicatePath : canonicalOnly) {
            markdown.append("| `").append(predicatePath).append("` | ")
                    .append(canonical.statusByPath.getOrDefault(predicatePath, "")).append(" | ")
                    .append(raw.equivalentPaths.contains(predicatePath)).append(" | ")
                    .append(rawDeBruijn.equivalentPaths.contains(predicatePath)).append(" | ")
                    .append(egglog.equivalentPaths.contains(predicatePath)).append(" | ")
                    .append(egglogDeBruijn.equivalentPaths.contains(predicatePath)).append(" | ")
                    .append(slotted.equivalentPaths.contains(predicatePath)).append(" | true | ")
                    .append(exact.equivalentPaths.contains(predicatePath)).append(" |\n");
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void writeMinimumDistances(Path path, List<RunMetrics> runs) throws IOException {
        Set<String> paths = new TreeSet<>();
        for (RunMetrics run : runs) {
            paths.addAll(run.statusByPath.keySet());
        }
        StringBuilder csv = new StringBuilder("relativePath,status");
        for (RunMetrics run : runs) {
            csv.append(',').append(run.engine);
        }
        csv.append('\n');
        for (String predicatePath : paths) {
            csv.append(csvValue(predicatePath)).append(',')
                    .append(csvValue(statusFor(predicatePath, runs)));
            for (RunMetrics run : runs) {
                Integer distance = run.distanceByPath.get(predicatePath);
                csv.append(',');
                if (distance != null) {
                    csv.append(distance);
                }
            }
            csv.append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String statusFor(String path, List<RunMetrics> runs) {
        for (RunMetrics run : runs) {
            String status = run.statusByPath.get(path);
            if (status != null) {
                return status;
            }
        }
        return "";
    }

    private static void writeMarkdown(
            Path path,
            Options options,
            AblationRunManifest.Context context,
            List<RunMetrics> runs) throws IOException {
        RunMetrics fastRewrite = findRun(runs, "canonical");
        RunMetrics canonical = findRun(runs, "typed-slotted-port-egraph");
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Alloy E-Graph Ablation\n\n");
        markdown.append("- Generated at: `").append(Instant.now()).append("`\n");
        markdown.append("- Run ID: `").append(context.runId).append("`\n");
        markdown.append("- Git SHA: `").append(context.gitSha).append("` (dirty: ")
                .append(context.dirtyTree).append(")\n");
        markdown.append("- Dataset SHA-256: `").append(context.datasetSha256).append("`\n");
        markdown.append("- Input root: `").append(options.input).append("`\n");
        markdown.append("- Predicate-pair limit: ")
                .append(options.limit == 0 ? "full corpus" : Integer.toString(options.limit)).append("\n");
        markdown.append("- Deterministic seed: `").append(options.seed).append("`\n");
        markdown.append("- Worker threads per arm: ").append(options.threads).append("\n");
        markdown.append("- Logical processors: ").append(AblationParallelism.logicalProcessors()).append("\n");
        markdown.append("- Thread policy: `").append(AblationParallelism.POLICY).append("`\n");
        markdown.append("- JVM heap cap per arm: `").append(options.maxHeap).append("`\n");
        markdown.append("- Java: `").append(System.getProperty("java.version")).append("`\n\n");

        markdown.append("## Arms\n\n");
        markdown.append("1. **Conventional e-graph:** fixed-arity Alloy constructors, the shared rule program, "
                + "union-find, hash-consing, and congruence rebuilding.\n");
        markdown.append("2. **Conventional e-graph + De Bruijn:** the same fixed-arity engine, with bound variables "
                + "stored as nameless nearest-binder indices.\n");
        markdown.append("3. **Java egglog core:** variadic Alloy constructors plus union facts, semi-naive rule rounds, "
                + "and congruence rebuilding. This is a Java replica of the egglog execution core used here, "
                + "not a textual-language-compatible port of every egglog feature.\n");
        markdown.append("4. **Java egglog + De Bruijn:** the same variadic engine and rules, with nameless "
                + "bound-variable storage.\n");
        markdown.append("5. **Slotted e-graph:** the same raw terms and rules represented as shape-hash-consed "
                + "renamed eclass invocations with exposed slots, slot redundancy, and finite permutation groups.\n");
        markdown.append("6. **Fast Rewrite IR:** the co-maintained temporal/prenex/slotted implementation, "
                + "with bounded rewrite saturation and direct execution of the reference repair metric.\n");
        markdown.append("7. **Certificate-Integrated IR:** the complete `CanonicalAlloyPipeline`, using "
                + "certified insertion, exact-support typed slots, strict invariant checks, congruence rebuild, "
                + "and finite-unfolding observation.\n\n");

        markdown.append("## Shared Rule Program\n\n");
        markdown.append("The first five arms use the same `").append(JavaEgglog.ruleSetVersion())
                .append("` rule set; only their term/eclass representation differs. The rules are: ")
                .append(String.join(", ", JavaEgglog.ruleNames())).append(".\n\n");

        markdown.append("## Runtime And Memory\n\n");
        markdown.append("Each arm ran in a fresh JVM. Wall time, process CPU, and maximum RSS come from "
                + "`/usr/bin/time -v`; peak used heap is sampled every 10 ms. Engine CPU uses worker-thread CPU "
                + "counters around representation construction, saturation, and comparison, excluding parsing. "
                + "Aggregate task time is summed worker latency and may exceed wall time under parallelism.\n\n");
        markdown.append("| Arm | Successful / eligible | AST-same skipped | Equivalent pairs | Process wall s | Dataset wall s | Pairs/s | Process CPU s | Engine CPU s | "
                + "Aggregate task s | Avg engine ms | P50 ms | P95 ms | Peak heap MiB | Max RSS MiB | Avg structural KiB |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(run.successes).append(" / ").append(run.files - run.skipped).append(" | ")
                    .append(run.skipped).append(" | ")
                    .append(run.equivalentPairs).append(" | ")
                    .append(number(run.processElapsedSeconds)).append(" | ")
                    .append(number(run.wallNanos / 1_000_000_000.0)).append(" | ")
                    .append(number(run.throughput())).append(" | ")
                    .append(number(run.userSeconds + run.systemSeconds)).append(" | ")
                    .append(number(run.engineCpuNanos / 1_000_000_000.0)).append(" | ")
                    .append(number(run.engineTaskNanos / 1_000_000_000.0)).append(" | ")
                    .append(number(run.averageEngineNanos / 1_000_000.0)).append(" | ")
                    .append(number(run.p50EngineNanos / 1_000_000.0)).append(" | ")
                    .append(number(run.p95EngineNanos / 1_000_000.0)).append(" | ")
                    .append(number(mib(run.peakUsedHeapBytes))).append(" | ")
                    .append(number(run.maxRssKb / 1024.0)).append(" | ")
                    .append(number(run.averageEstimatedBytes / 1024.0)).append(" |\n");
        }

        RunMetrics raw = findRun(runs, "raw-egraph");
        RunMetrics rawDeBruijn = findRun(runs, "raw-egraph-debruijn");
        RunMetrics egglog = findRun(runs, "java-egglog");
        RunMetrics egglogDeBruijn = findRun(runs, "java-egglog-debruijn");
        RunMetrics slotted = findRun(runs, "slotted-egraph");
        markdown.append("\n## Observations\n\n");
        markdown.append("- De Bruijn storage adds ")
                .append(differenceSize(rawDeBruijn.equivalentPaths, raw.equivalentPaths))
                .append(" zero-distance pairs to the fixed-arity arm, with ")
                .append(differenceSize(raw.equivalentPaths, rawDeBruijn.equivalentPaths)).append(" losses.\n");
        markdown.append("- Variadic egglog encoding adds ")
                .append(differenceSize(egglog.equivalentPaths, raw.equivalentPaths))
                .append(" zero-distance pairs over the fixed-arity e-graph, with ")
                .append(differenceSize(raw.equivalentPaths, egglog.equivalentPaths)).append(" losses.\n");
        markdown.append("- De Bruijn storage adds ")
                .append(differenceSize(egglogDeBruijn.equivalentPaths, egglog.equivalentPaths))
                .append(" zero-distance pairs to the variadic egglog arm, with ")
                .append(differenceSize(egglog.equivalentPaths, egglogDeBruijn.equivalentPaths)).append(" losses.\n");
        markdown.append("- Under De Bruijn storage, variadic egglog encoding adds ")
                .append(differenceSize(egglogDeBruijn.equivalentPaths, rawDeBruijn.equivalentPaths))
                .append(" pairs over the fixed-arity arm, with ")
                .append(differenceSize(rawDeBruijn.equivalentPaths, egglogDeBruijn.equivalentPaths))
                .append(" losses.\n");
        markdown.append("- Slot-aware shapes add ")
                .append(differenceSize(slotted.equivalentPaths, egglogDeBruijn.equivalentPaths))
                .append(" pairs over the De Bruijn egglog arm, with ")
                .append(differenceSize(egglogDeBruijn.equivalentPaths, slotted.equivalentPaths)).append(" losses.\n");
        int fastRewriteAdds = differenceSize(fastRewrite.equivalentPaths, slotted.equivalentPaths);
        int fastRewriteLosses = differenceSize(slotted.equivalentPaths, fastRewrite.equivalentPaths);
        markdown.append("- The Fast Rewrite IR adds ")
                .append(fastRewriteAdds).append(" zeroes over slotted storage and loses ")
                .append(fastRewriteLosses).append(".\n");
        int canonicalAdds = differenceSize(canonical.equivalentPaths, fastRewrite.equivalentPaths);
        int canonicalLosses = differenceSize(fastRewrite.equivalentPaths, canonical.equivalentPaths);
        markdown.append("- The Certificate-Integrated IR adds ")
                .append(canonicalAdds).append(" zeroes over the Fast Rewrite IR and loses ")
                .append(canonicalLosses).append(". Its zero set contains ")
                .append(canonical.incorrectEquivalent).append(" predicates labeled incorrect; the slotted arm contains ")
                .append(slotted.incorrectEquivalent).append(".\n");
        markdown.append("- Relative to the full method, the slotted arm uses ")
                .append(number(ratio(slotted.engineCpuNanos, canonical.engineCpuNanos) * 100.0))
                .append("% of engine CPU time and ")
                .append(number(ratio(slotted.maxRssKb, canonical.maxRssKb) * 100.0))
                .append("% of maximum RSS. End-to-end wall time is parser-dominated.\n");

        markdown.append("\n## Implementation Tradeoff\n\n");
        markdown.append("The Fast Rewrite IR directly executes the repaired temporal/prenex rewrite system and "
                + "established metric for high-throughput corpus analysis. The Certificate-Integrated IR "
                + "checks typed ports, law provenance, binder automorphisms, congruence quiescence, and graph "
                + "invariants before accepting equality. It therefore provides a stronger fail-closed semantic-"
                + "assurance boundary while preserving the same repair objective.\n\n");
        markdown.append("On this run, certificate integration costs ")
                .append(number(ratio(canonical.processElapsedSeconds, fastRewrite.processElapsedSeconds)))
                .append("x wall time and ")
                .append(number(ratio(canonical.engineCpuNanos, fastRewrite.engineCpuNanos)))
                .append("x engine CPU, with ")
                .append(number(ratio(canonical.maxRssKb, fastRewrite.maxRssKb)))
                .append("x maximum RSS. The Fast Rewrite IR remains an active artifact path for broad "
                        + "experiments; the Certificate-Integrated IR is the audit path when certified "
                        + "admissibility matters more than throughput. Dataset labels and bounded solver checks "
                        + "are empirical evidence, not an unbounded semantic proof.\n");

        markdown.append("\n## Agreement With Dataset Labels\n\n");
        markdown.append("`Equivalent` means eclass equality or canonical distance zero; it is not an additional SAT proof. "
                + "The dataset's `CORRECT` label is the positive semantic-equivalence class, and every other status is negative.\n\n");
        markdown.append("| Arm | CORRECT zero / CORRECT | CORRECT coverage | Incorrect zero / incorrect | Incorrect zero rate |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(run.correctEquivalent).append(" / ").append(run.correctPairs).append(" | ")
                    .append(number(ratio(run.correctEquivalent, run.correctPairs) * 100.0)).append("% | ")
                    .append(run.incorrectEquivalent).append(" / ").append(run.incorrectPairs).append(" | ")
                    .append(number(ratio(run.incorrectEquivalent, run.incorrectPairs) * 100.0)).append("% |\n");
        }

        markdown.append("\n## Equivalent Discovery Efficiency\n\n");
        markdown.append("A found semantic equivalent is a zero-distance pair carrying the dataset's SAT-validated "
                + "`CORRECT` label. Rates therefore exclude zero-distance pairs from incorrect classes.\n\n");
        markdown.append("| Arm | Found equivalents | CORRECT coverage | Found / wall s | Found / process CPU s | "
                + "Found / engine CPU s | Found / GiB max RSS |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            double processCpuSeconds = run.userSeconds + run.systemSeconds;
            double engineCpuSeconds = run.engineCpuNanos / 1_000_000_000.0;
            double maxRssGiB = run.maxRssKb / (1024.0 * 1024.0);
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(run.correctEquivalent).append(" | ")
                    .append(number(ratio(run.correctEquivalent, run.correctPairs) * 100.0)).append("% | ")
                    .append(number(ratio(run.correctEquivalent, run.processElapsedSeconds))).append(" | ")
                    .append(number(ratio(run.correctEquivalent, processCpuSeconds))).append(" | ")
                    .append(number(ratio(run.correctEquivalent, engineCpuSeconds))).append(" | ")
                    .append(number(ratio(run.correctEquivalent, maxRssGiB))).append(" |\n");
        }

        markdown.append("\n## Minimum Edit Distance\n\n");
        markdown.append("For the five retained e-graph baselines, this is the minimum unit-cost rooted-tree edit distance ")
                .append("over concrete root witnesses retained during saturation; slotted witnesses are normalized ")
                .append("under alpha-renaming and declaration permutation groups, while the two De Bruijn arms ")
                .append("index bound variables before e-graph storage and distance. Eclass equality has distance zero. ")
                .append("Both canonical arms use the established repair metric. The Certificate-Integrated IR obtains admissible "
                        + "scope and operator alignments from the certified semantic artifact; normalized "
                        + "finite-unfolding keys define equality but are not edited to obtain distance.\n\n");
        markdown.append("| Arm | Pairs | All avg | CORRECT avg | Incorrect avg | P50 | P95 |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(run.distancePairs).append(" | ")
                    .append(number(run.averageDistance())).append(" | ")
                    .append(number(run.averageCorrectDistance())).append(" | ")
                    .append(number(run.averageIncorrectDistance())).append(" | ")
                    .append(run.distancePercentile(0.50)).append(" | ")
                    .append(run.distancePercentile(0.95)).append(" |\n");
        }

        markdown.append("\n## Relative To Full Method\n\n");
        markdown.append("Ratios below use engine CPU time and maximum RSS; values below 1 use less than the "
                + "exact typed slotted-port arm.\n\n");
        markdown.append("| Arm | Engine CPU ratio | Max RSS ratio | Representation-unit ratio |\n");
        markdown.append("| --- | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(number(ratio(run.engineCpuNanos, canonical.engineCpuNanos))).append(" | ")
                    .append(number(ratio(run.maxRssKb, canonical.maxRssKb))).append(" | ")
                    .append(number(ratio(run.averageRepresentationUnits, canonical.averageRepresentationUnits)))
                    .append(" |\n");
        }

        markdown.append("\n## Pair-Level Transitions\n\n");
        markdown.append("These edges isolate variable encoding, variadic representation, slots, and the full method.\n\n");
        markdown.append("| Transition | Retained zeroes | Newly zero | No longer zero |\n");
        markdown.append("| --- | ---: | ---: | ---: |\n");
        for (String[] transition : TRANSITIONS) {
            RunMetrics before = findRun(runs, transition[0]);
            RunMetrics after = findRun(runs, transition[1]);
            markdown.append("| ").append(before.engine).append(" -> ").append(after.engine).append(" | ")
                    .append(intersectionSize(before.equivalentPaths, after.equivalentPaths)).append(" | ")
                    .append(differenceSize(after.equivalentPaths, before.equivalentPaths)).append(" | ")
                    .append(differenceSize(before.equivalentPaths, after.equivalentPaths)).append(" |\n");
        }

        markdown.append("\n## Representation\n\n");
        markdown.append("| Arm | Avg units | Avg eclasses | Avg enodes | Avg estimated bytes | Peak estimated bytes |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (RunMetrics run : runs) {
            markdown.append("| ").append(run.engine).append(" | ")
                    .append(number(run.averageRepresentationUnits)).append(" | ")
                    .append(number(run.averageEclasses)).append(" | ")
                    .append(number(run.averageEnodes)).append(" | ")
                    .append(number(run.averageEstimatedBytes)).append(" | ")
                    .append(run.peakEstimatedBytes).append(" |\n");
        }
        markdown.append("\nThe structural byte count is an implementation-level estimate for graph objects; "
                + "Max RSS is the primary measured memory result. Fast Rewrite IR units retain the repaired "
                + "canonical-form size; Certificate-Integrated IR units count its normalized finite-unfolding "
                + "key. E-class and e-node columns for the certificate-integrated arm are reachable strict graph "
                + "counts across both predicates.\n");
        markdown.append("\n## Reproduce\n\n");
        markdown.append("```bash\n");
        markdown.append("./scripts/run_egraph_ablation.sh --input ").append(options.input)
                .append(" --output ").append(options.output)
                .append(" --threads ").append(options.threads)
                .append(" --max-heap ").append(options.maxHeap).append("\n");
        markdown.append("```\n\n");
        markdown.append("Use `--limit N` for a smoke run. Use `--report-only` to regenerate the combined JSON, "
                + "disagreement CSV, and Markdown from retained per-arm files without rerunning the engines.\n");
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static RunMetrics findRun(List<RunMetrics> runs, String engine) {
        for (RunMetrics run : runs) {
            if (engine.equals(run.engine)) {
                return run;
            }
        }
        throw new IllegalStateException("Missing run: " + engine);
    }

    private static double ratio(double value, double baseline) {
        return baseline == 0.0 ? 0.0 : value / baseline;
    }

    private static double mib(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class Options {
        private Path input = Paths.get("classified-data");
        private Path output = Paths.get("egraph_ablation");
        private int threads = AblationParallelism.defaultWorkers();
        private int limit;
        private long seed = CapabilityBenchmark.DEFAULT_SEED;
        private String maxHeap = "3g";
        private boolean verbose;
        private boolean reportOnly;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input":
                        options.input = Paths.get(value(args, ++i, "--input"));
                        break;
                    case "--output":
                        options.output = Paths.get(value(args, ++i, "--output"));
                        break;
                    case "--threads":
                        options.threads = Integer.parseInt(value(args, ++i, "--threads"));
                        break;
                    case "--limit":
                        options.limit = Math.max(0, Integer.parseInt(value(args, ++i, "--limit")));
                        break;
                    case "--seed":
                        options.seed = Long.parseLong(value(args, ++i, "--seed"));
                        break;
                    case "--max-heap":
                        options.maxHeap = value(args, ++i, "--max-heap");
                        break;
                    case "--verbose":
                        options.verbose = true;
                        break;
                    case "--report-only":
                        options.reportOnly = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            options.threads = AblationParallelism.effectiveWorkers(options.threads);
            return options;
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static final class RunMetrics {
        private String engine;
        private String description;
        private int threadCount;
        private long files;
        private long successes;
        private long skipped;
        private long failures;
        private long equivalentPairs;
        private long wallNanos;
        private long peakUsedHeapBytes;
        private long engineCpuNanos;
        private long engineTaskNanos;
        private double averageEngineNanos;
        private long p50EngineNanos;
        private long p95EngineNanos;
        private double averageRepresentationUnits;
        private double averageEclasses;
        private double averageEnodes;
        private double averageEstimatedBytes;
        private long peakEstimatedBytes;
        private long maxRssKb;
        private double userSeconds;
        private double systemSeconds;
        private double processElapsedSeconds;
        private long correctPairs;
        private long correctEquivalent;
        private long incorrectPairs;
        private long incorrectEquivalent;
        private long distancePairs;
        private long totalDistance;
        private long correctDistancePairs;
        private long correctDistance;
        private long incorrectDistancePairs;
        private long incorrectDistance;
        private final Set<String> equivalentPaths = new HashSet<>();
        private final Map<String, String> statusByPath = new HashMap<>();
        private final Map<String, Integer> distanceByPath = new HashMap<>();
        private final List<Integer> distances = new ArrayList<>();

        private static RunMetrics read(Path propertiesPath, Path timePath, Path pairsPath) throws IOException {
            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesPath)) {
                properties.load(input);
            }
            RunMetrics run = new RunMetrics();
            run.engine = properties.getProperty("engine");
            run.description = properties.getProperty("description", "");
            run.threadCount = Integer.parseInt(properties.getProperty("threads", "0"));
            run.files = longValue(properties, "files");
            run.successes = longValue(properties, "successes");
            run.skipped = Long.parseLong(properties.getProperty("skippedIdenticalRawAstPairs", "0"));
            run.failures = longValue(properties, "failures");
            run.equivalentPairs = longValue(properties, "equivalentPairs");
            run.wallNanos = longValue(properties, "wallNanos");
            run.peakUsedHeapBytes = longValue(properties, "peakUsedHeapBytes");
            run.engineCpuNanos = longValue(properties, "engineCpuNanos");
            run.engineTaskNanos = Long.parseLong(properties.getProperty(
                    "engineTaskNanos", properties.getProperty("engineCpuNanos", "0")));
            run.averageEngineNanos = doubleValue(properties, "averageEngineNanos");
            run.p50EngineNanos = longValue(properties, "p50EngineNanos");
            run.p95EngineNanos = longValue(properties, "p95EngineNanos");
            run.averageRepresentationUnits = doubleValue(properties, "averageRepresentationUnits");
            run.averageEclasses = doubleValue(properties, "averageEclasses");
            run.averageEnodes = doubleValue(properties, "averageEnodes");
            run.averageEstimatedBytes = doubleValue(properties, "averageEstimatedBytes");
            run.peakEstimatedBytes = longValue(properties, "peakEstimatedBytes");
            for (String line : Files.readAllLines(timePath, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Maximum resident set size (kbytes):")) {
                    run.maxRssKb = Long.parseLong(afterColon(trimmed));
                } else if (trimmed.startsWith("User time (seconds):")) {
                    run.userSeconds = Double.parseDouble(afterColon(trimmed));
                } else if (trimmed.startsWith("System time (seconds):")) {
                    run.systemSeconds = Double.parseDouble(afterColon(trimmed));
                } else if (trimmed.startsWith("Elapsed (wall clock) time")) {
                    int marker = trimmed.indexOf("): ");
                    if (marker >= 0) {
                        run.processElapsedSeconds = parseElapsed(trimmed.substring(marker + 3).trim());
                    }
                }
            }
            List<String> pairLines = Files.readAllLines(pairsPath, StandardCharsets.UTF_8);
            int distanceIndex = pairLines.isEmpty() ? -1 : parseCsv(pairLines.get(0)).indexOf("distance");
            for (int i = 1; i < pairLines.size(); i++) {
                List<String> fields = parseCsv(pairLines.get(i));
                if (fields.size() < 7 || !Boolean.parseBoolean(fields.get(5))) {
                    continue;
                }
                boolean equivalent = Boolean.parseBoolean(fields.get(6));
                String predicatePath = fields.get(0);
                String status = fields.get(2);
                run.statusByPath.put(predicatePath, status);
                if ("CORRECT".equals(status)) {
                    run.correctPairs++;
                    run.correctEquivalent += equivalent ? 1 : 0;
                } else {
                    run.incorrectPairs++;
                    run.incorrectEquivalent += equivalent ? 1 : 0;
                }
                if (equivalent) {
                    run.equivalentPaths.add(predicatePath);
                }
                if (distanceIndex >= 0 && distanceIndex < fields.size()
                        && !fields.get(distanceIndex).isEmpty()) {
                    int distance = Integer.parseInt(fields.get(distanceIndex));
                    run.distanceByPath.put(predicatePath, distance);
                    run.distances.add(distance);
                    run.distancePairs++;
                    run.totalDistance += distance;
                    if ("CORRECT".equals(status)) {
                        run.correctDistancePairs++;
                        run.correctDistance += distance;
                    } else {
                        run.incorrectDistancePairs++;
                        run.incorrectDistance += distance;
                    }
                }
            }
            return run;
        }

        private double throughput() {
            return wallNanos == 0 ? 0.0 : successes * 1_000_000_000.0 / wallNanos;
        }

        private double averageDistance() {
            return ratio(totalDistance, distancePairs);
        }

        private double averageCorrectDistance() {
            return ratio(correctDistance, correctDistancePairs);
        }

        private double averageIncorrectDistance() {
            return ratio(incorrectDistance, incorrectDistancePairs);
        }

        private int distancePercentile(double percentile) {
            if (distances.isEmpty()) {
                return 0;
            }
            List<Integer> sorted = new ArrayList<>(distances);
            sorted.sort(Integer::compareTo);
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private JSONObject toJson() {
            return new JSONObject()
                    .put("engine", engine)
                    .put("description", description)
                    .put("threadCount", threadCount)
                    .put("files", files)
                    .put("successes", successes)
                    .put("eligiblePairs", files - skipped)
                    .put("skippedIdenticalRawAstPairs", skipped)
                    .put("failures", failures)
                    .put("equivalentPairs", equivalentPairs)
                    .put("wallNanos", wallNanos)
                    .put("throughputPairsPerSecond", throughput())
                    .put("engineCpuNanos", engineCpuNanos)
                    .put("engineTaskNanos", engineTaskNanos)
                    .put("averageEngineNanos", averageEngineNanos)
                    .put("p50EngineNanos", p50EngineNanos)
                    .put("p95EngineNanos", p95EngineNanos)
                    .put("peakUsedHeapBytes", peakUsedHeapBytes)
                    .put("maximumRssKb", maxRssKb)
                    .put("processUserSeconds", userSeconds)
                    .put("processSystemSeconds", systemSeconds)
                    .put("processElapsedSeconds", processElapsedSeconds)
                    .put("correctPairs", correctPairs)
                    .put("correctEquivalentPairs", correctEquivalent)
                    .put("correctCoverage", ratio(correctEquivalent, correctPairs))
                    .put("verifiedEquivalentPairsPerWallSecond",
                            ratio(correctEquivalent, processElapsedSeconds))
                    .put("verifiedEquivalentPairsPerProcessCpuSecond",
                            ratio(correctEquivalent, userSeconds + systemSeconds))
                    .put("verifiedEquivalentPairsPerEngineCpuSecond",
                            ratio(correctEquivalent, engineCpuNanos / 1_000_000_000.0))
                    .put("verifiedEquivalentPairsPerGiBMaximumRss",
                            ratio(correctEquivalent, maxRssKb / (1024.0 * 1024.0)))
                    .put("incorrectPairs", incorrectPairs)
                    .put("incorrectEquivalentPairs", incorrectEquivalent)
                    .put("incorrectZeroRate", ratio(incorrectEquivalent, incorrectPairs))
                    .put("distancePairs", distancePairs)
                    .put("totalDistance", totalDistance)
                    .put("averageDistance", averageDistance())
                    .put("averageCorrectDistance", averageCorrectDistance())
                    .put("averageIncorrectDistance", averageIncorrectDistance())
                    .put("p50Distance", distancePercentile(0.50))
                    .put("p95Distance", distancePercentile(0.95))
                    .put("averageRepresentationUnits", averageRepresentationUnits)
                    .put("averageEclasses", averageEclasses)
                    .put("averageEnodes", averageEnodes)
                    .put("averageEstimatedBytes", averageEstimatedBytes)
                    .put("peakEstimatedBytes", peakEstimatedBytes);
        }

        private static long longValue(Properties properties, String key) {
            return Long.parseLong(properties.getProperty(key, "0"));
        }

        private static double doubleValue(Properties properties, String key) {
            return Double.parseDouble(properties.getProperty(key, "0"));
        }

        private static String afterColon(String line) {
            return line.substring(line.lastIndexOf(':') + 1).trim();
        }

        private static double parseElapsed(String value) {
            String[] fields = value.split(":");
            double seconds = 0.0;
            for (String field : fields) {
                seconds = seconds * 60.0 + Double.parseDouble(field);
            }
            return seconds;
        }

        private static List<String> parseCsv(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char value = line.charAt(i);
                if (value == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (value == ',' && !quoted) {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(value);
                }
            }
            fields.add(field.toString());
            return fields;
        }
    }

    private static void validateParallelism(Options options, List<RunMetrics> runs) {
        for (RunMetrics run : runs) {
            if (run.threadCount != options.threads) {
                throw new IllegalStateException(run.engine + " reported " + run.threadCount
                        + " worker threads; every arm must report " + options.threads);
            }
        }
    }

    private static JSONObject transitionJson(RunMetrics before, RunMetrics after) {
        return new JSONObject()
                .put("from", before.engine)
                .put("to", after.engine)
                .put("retained", intersectionSize(before.equivalentPaths, after.equivalentPaths))
                .put("newlyEquivalent", differenceSize(after.equivalentPaths, before.equivalentPaths))
                .put("noLongerEquivalent", differenceSize(before.equivalentPaths, after.equivalentPaths));
    }

    private static int intersectionSize(Set<String> left, Set<String> right) {
        Set<String> smaller = left.size() <= right.size() ? left : right;
        Set<String> larger = smaller == left ? right : left;
        int count = 0;
        for (String value : smaller) {
            count += larger.contains(value) ? 1 : 0;
        }
        return count;
    }

    private static int differenceSize(Set<String> left, Set<String> right) {
        int count = 0;
        for (String value : left) {
            count += right.contains(value) ? 0 : 1;
        }
        return count;
    }

    private static int disagreementCount(List<RunMetrics> runs) {
        Set<String> paths = new HashSet<>();
        for (RunMetrics run : runs) {
            paths.addAll(run.statusByPath.keySet());
        }
        int count = 0;
        for (String path : paths) {
            boolean first = runs.get(0).equivalentPaths.contains(path);
            for (int i = 1; i < runs.size(); i++) {
                if (runs.get(i).equivalentPaths.contains(path) != first) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static String csvValue(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
