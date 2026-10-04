package is.fivefivefive.CanDis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;

/** Bounded Alloy validation of the equivalence claims produced by the ablation arms. */
public final class EGraphSemanticSoundnessCheck {
    private static final List<String> ENGINES = List.of(
            "raw-egraph", "raw-egraph-debruijn",
            "java-egglog", "java-egglog-debruijn",
            "slotted-egraph", "canonical", "typed-slotted-port-egraph");
    private static final Path PROBE_ROOT = Paths.get(
            "src/is/fivefivefive/CanDis/ablation/soundness");

    private EGraphSemanticSoundnessCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        Options options = Options.parse(args);
        Map<String, Claim> claims = loadClaims(options);
        List<Claim> work = new ArrayList<>(claims.values());
        work.sort(Comparator.comparing(claim -> claim.relativePath));

        List<Result> results = new ArrayList<>(work.size());
        ExecutorService executor = Executors.newFixedThreadPool(options.threads);
        try {
            CompletionService<Result> completion = new ExecutorCompletionService<>(executor);
            for (Claim claim : work) {
                completion.submit(new VerifyTask(options.inputRoot, claim));
            }
            ExperimentProgress progress = ExperimentProgress.start(
                    System.err,
                    "EGraphSemanticSoundnessCheck/claims",
                    work.size(),
                    "claims",
                    "with " + options.threads + " workers");
            int completed = 0;
            while (completed < work.size()) {
                Future<Result> future = completion.poll(30, TimeUnit.SECONDS);
                if (future == null) {
                    progress.heartbeat(completed, work.size() - completed, null);
                    continue;
                }
                try {
                    results.add(future.get());
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    throw new IllegalStateException("Semantic soundness worker failed", cause);
                }
                progress.update(++completed);
            }
            progress.finish(completed);
        } finally {
            executor.shutdownNow();
        }
        results.sort(Comparator.comparing(result -> result.claim.relativePath));
        List<ProbeResult> probes = runProbes();

        Files.createDirectories(options.resultsRoot);
        writeJson(options.resultsRoot.resolve("semantic_soundness.json"), options, results, probes);
        writeCounterexamples(options.resultsRoot.resolve("semantic_counterexamples.csv"), results);
        writeMarkdown(options.resultsRoot.resolve("semantic_soundness.md"), options, results, probes);
        System.out.println("Wrote " + options.resultsRoot.resolve("semantic_soundness.json"));
        System.out.println("Wrote " + options.resultsRoot.resolve("semantic_counterexamples.csv"));
        System.out.println("Wrote " + options.resultsRoot.resolve("semantic_soundness.md"));

        long counterexamples = results.stream()
                .filter(result -> result.outcome == Outcome.COUNTEREXAMPLE).count();
        long errors = results.stream().filter(result -> result.outcome == Outcome.ERROR).count();
        long probeViolations = probes.stream().filter(EGraphSemanticSoundnessCheck::isProbeViolation).count();
        if (counterexamples > 0 || errors > 0 || probeViolations > 0) {
            throw new AssertionError("Semantic soundness violations: counterexamples=" + counterexamples
                    + ", errors=" + errors + ", targetedProbes=" + probeViolations);
        }
    }

    private static boolean isProbeViolation(ProbeResult probe) {
        return !probe.error.isEmpty()
                || (probe.counterexample && probe.merged.values().stream().anyMatch(Boolean.TRUE::equals));
    }

    private static Map<String, Claim> loadClaims(Options options) throws IOException {
        Map<String, Claim> claims = new HashMap<>();
        for (String engine : ENGINES) {
            Path pairs = options.resultsRoot.resolve(engine).resolve("pairs.csv");
            List<String> lines = Files.readAllLines(pairs, StandardCharsets.UTF_8);
            for (String line : lines.subList(1, lines.size())) {
                List<String> fields = parseCsv(line);
                if (fields.size() < 7 || !Boolean.parseBoolean(fields.get(5))
                        || !Boolean.parseBoolean(fields.get(6))) {
                    continue;
                }
                Claim claim = claims.computeIfAbsent(fields.get(0), Claim::new);
                claim.problemClass = fields.get(1);
                claim.status = fields.get(2);
                claim.leftPredicate = fields.get(3);
                claim.rightPredicate = fields.get(4);
                claim.engines.add(engine);
            }
        }
        if (options.canonicalOnly) {
            claims.values().removeIf(claim -> !claim.engines.contains("canonical")
                    || claim.engines.contains("slotted-egraph"));
        }
        return claims;
    }

    private static Command equivalenceCheck(CompModule module) {
        Command fallback = null;
        for (Command command : module.getAllCommands()) {
            if (!command.check) {
                continue;
            }
            if (fallback == null) {
                fallback = command;
            }
            String label = command.label == null ? "" : command.label;
            if (label.equalsIgnoreCase("correct") || label.endsWith("/correct")) {
                return command;
            }
        }
        return fallback;
    }

    private static List<ProbeResult> runProbes() throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(PROBE_ROOT)) {
            files = paths.filter(path -> path.getFileName().toString().endsWith(".als"))
                    .sorted().toList();
        }
        List<ProbeResult> results = new ArrayList<>(files.size());
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "EGraphSemanticSoundnessCheck/probes",
                files.size(),
                "probes");
        int completed = 0;
        for (Path file : files) {
            results.add(runProbe(file));
            progress.update(++completed);
        }
        progress.finish(completed);
        return results;
    }

    private static ProbeResult runProbe(Path file) {
        ProbeResult result = new ProbeResult(file);
        try {
            CompModule module = CompUtil.parseEverything_fromFile(new A4Reporter(), null, file.toString());
            ModelUnit model = new ModelUnit(null, module);
            Map<String, Predicate> predicates = new HashMap<>();
            Map<String, Integer> predicateIds = new HashMap<>();
            int id = 1;
            for (Predicate predicate : model.getPredDeclList()) {
                predicates.put(predicate.getName(), predicate);
                predicateIds.put(predicate.getName(), id++);
            }
            String[] names = DatasetConventions.findPredicatePairNames(preferredPredicateBase(file), predicates);
            if (names == null) {
                throw new IllegalStateException("No predicate pair found");
            }
            AlloyTerm leftTerm = AlloyAstTermAdapter.fromPredicate(predicates.get(names[0]));
            AlloyTerm rightTerm = AlloyAstTermAdapter.fromPredicate(predicates.get(names[1]));
            result.merged.put("raw-egraph", new RawEGraph().compare(leftTerm, rightTerm).equivalent);
            result.merged.put("raw-egraph-debruijn",
                    new RawDeBruijnEGraph().compare(leftTerm, rightTerm).equivalent);
            result.merged.put("java-egglog", new JavaEgglog().compare(leftTerm, rightTerm).equivalent);
            result.merged.put("java-egglog-debruijn",
                    new JavaEgglogDeBruijn().compare(leftTerm, rightTerm).equivalent);
            result.merged.put("slotted-egraph", new SlottedEGraph().compare(leftTerm, rightTerm).equivalent);

            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(model, null);
            DoubleMap<Integer, Multigraph> forest = visitor.getForest();
            Canonical.Prepared left = Canonical.prepare(forest.get(predicateIds.get(names[0])));
            Canonical.Prepared right = Canonical.prepare(forest.get(predicateIds.get(names[1])));
            result.merged.put("canonical", Canonical.distance(left, right) == 0);
            CanonicalAlloyPipeline.Prepared exactLeft = CanonicalAlloyPipeline.prepare(left);
            CanonicalAlloyPipeline.Prepared exactRight = CanonicalAlloyPipeline.prepare(right);
            result.merged.put("typed-slotted-port-egraph",
                    CanonicalAlloyPipeline.distance(exactLeft, exactRight) == 0);

            Command command = equivalenceCheck(module);
            if (command == null) {
                throw new IllegalStateException("No Alloy check command found");
            }
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            A4Solution solution = TranslateAlloyToKodkod.execute_command(
                    new A4Reporter(), module.getAllReachableSigs(), command, options);
            result.counterexample = solution != null && solution.satisfiable();
        } catch (Throwable throwable) {
            result.error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
        }
        return result;
    }

    private static String preferredPredicateBase(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        int underscore = name.lastIndexOf('_');
        return underscore < 0 ? null : name.substring(underscore + 1);
    }

    private static void writeJson(
            Path path,
            Options options,
            List<Result> results,
            List<ProbeResult> probes) throws IOException {
        JSONObject root = new JSONObject();
        root.put("generatedAt", Instant.now().toString());
        root.put("inputRoot", options.inputRoot.toString());
        root.put("resultsRoot", options.resultsRoot.toString());
        root.put("claimSourceRunId", manifestField(options.resultsRoot, "runId"));
        root.put("claimSourceGitSha", manifestField(options.resultsRoot, "gitSha"));
        root.put("exactPipelineVersion", CanonicalAlloyPipeline.PIPELINE_VERSION);
        root.put("exactAdapterVersion", TheoryAlloyAdapter.ADAPTER_VERSION);
        root.put("exactSignatureVersion", TheoryAlloyAdapter.SIGNATURE_VERSION);
        root.put("boundedByModelCommands", true);
        root.put("canonicalOnly", options.canonicalOnly);
        root.put("threadCount", options.threads);
        root.put("checkedUniquePairs", results.size());
        JSONArray arms = new JSONArray();
        for (String engine : ENGINES) {
            arms.put(engineSummary(engine, results));
        }
        root.put("arms", arms);
        JSONArray checks = new JSONArray();
        for (Result result : results) {
            checks.put(result.toJson());
        }
        root.put("checks", checks);
        JSONArray probeArray = new JSONArray();
        for (ProbeResult probe : probes) {
            probeArray.put(probe.toJson());
        }
        root.put("targetedProbes", probeArray);
        Files.writeString(path, root.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeCounterexamples(Path path, List<Result> results) throws IOException {
        StringBuilder csv = new StringBuilder(
                "relativePath,problemClass,status,leftPredicate,rightPredicate,engines,command,solveMillis,error\n");
        for (Result result : results) {
            if (result.outcome != Outcome.COUNTEREXAMPLE) {
                continue;
            }
            csv.append(csv(result.claim.relativePath)).append(',')
                    .append(csv(result.claim.problemClass)).append(',')
                    .append(csv(result.claim.status)).append(',')
                    .append(csv(result.claim.leftPredicate)).append(',')
                    .append(csv(result.claim.rightPredicate)).append(',')
                    .append(csv(String.join(";", result.claim.engines))).append(',')
                    .append(csv(result.command)).append(',')
                    .append(String.format(Locale.ROOT, "%.3f", result.solveNanos / 1_000_000.0)).append(',')
                    .append(csv(result.error)).append('\n');
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private static void writeMarkdown(
            Path path,
            Options options,
            List<Result> results,
            List<ProbeResult> probes) throws IOException {
        StringBuilder markdown = new StringBuilder("# E-Graph Semantic Soundness Check\n\n");
        markdown.append("- Generated at: `").append(Instant.now()).append("`\n");
        markdown.append("- Checked unique predicate pairs: ").append(results.size()).append("\n");
        markdown.append("- Mode: ").append(options.canonicalOnly
                ? "canonical zero and slotted nonzero only" : "union of all equivalence claims").append("\n");
        markdown.append("- Threads: ").append(options.threads).append("\n");
        markdown.append("- Claim-source run: `")
                .append(manifestField(options.resultsRoot, "runId")).append("`\n");
        markdown.append("- Exact checker: `").append(CanonicalAlloyPipeline.PIPELINE_VERSION)
                .append("` / `").append(TheoryAlloyAdapter.ADAPTER_VERSION)
                .append("` / `").append(TheoryAlloyAdapter.SIGNATURE_VERSION)
                .append("`\n\n");
        markdown.append("This is a bounded semantic check using each model's own `check correct` command. ")
                .append("An Alloy counterexample disproves a merge; absence of a counterexample is evidence only ")
                .append("within that command's scope and temporal bounds.\n\n");
        markdown.append("## Results By Arm\n\n");
        markdown.append("| Arm | Claims checked | No counterexample | Counterexamples | Errors | Bounded precision |\n");
        markdown.append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
        for (String engine : ENGINES) {
            JSONObject summary = engineSummary(engine, results);
            long claims = summary.getLong("claims");
            long clean = summary.getLong("noCounterexample");
            markdown.append("| ").append(engine).append(" | ").append(claims)
                    .append(" | ").append(clean)
                    .append(" | ").append(summary.getLong("counterexamples"))
                    .append(" | ").append(summary.getLong("errors"))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f%%",
                            claims == 0 ? 0.0 : clean * 100.0 / claims))
                    .append(" |\n");
        }
        List<Result> counterexamples = results.stream()
                .filter(result -> result.outcome == Outcome.COUNTEREXAMPLE).toList();
        markdown.append("\n## Counterexamples\n\n");
        if (counterexamples.isEmpty()) {
            markdown.append("No bounded counterexamples were found.\n");
        } else {
            markdown.append("| Source | Status | Claiming arms |\n");
            markdown.append("| --- | --- | --- |\n");
            for (Result result : counterexamples) {
                markdown.append("| `").append(result.claim.relativePath).append("` | ")
                        .append(result.claim.status).append(" | ")
                        .append(String.join(", ", result.claim.engines)).append(" |\n");
            }
        }
        markdown.append("\n## Targeted Rule-Level Probes\n\n");
        markdown.append("These deliberately exercise binder cases absent from the observed zero-distance corpus. ")
                .append("A merge in a row with an Alloy counterexample is a semantic-soundness violation.\n\n");
        markdown.append("| Probe | Alloy counterexample | Raw | Raw DB | Java egglog | Egglog DB | Slotted | Canonical | Exact |\n");
        markdown.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n");
        for (ProbeResult probe : probes) {
            markdown.append("| `").append(probe.name).append("` | ")
                    .append(probe.error.isEmpty() ? probe.counterexample : "error")
                    .append(" | ").append(probe.merged.getOrDefault("raw-egraph", false))
                    .append(" | ").append(probe.merged.getOrDefault("raw-egraph-debruijn", false))
                    .append(" | ").append(probe.merged.getOrDefault("java-egglog", false))
                    .append(" | ").append(probe.merged.getOrDefault("java-egglog-debruijn", false))
                    .append(" | ").append(probe.merged.getOrDefault("slotted-egraph", false))
                    .append(" | ").append(probe.merged.getOrDefault("canonical", false))
                    .append(" | ").append(probe.merged.getOrDefault("typed-slotted-port-egraph", false))
                    .append(" |\n");
        }
        markdown.append("\n- `let_shadow_inv1`: detects capture during beta reduction when an inner quantifier shadows a name.\n");
        markdown.append("- `comprehension_order_inv2`: detects illegal permutation of comprehension columns.\n");
        markdown.append("- `signature_shadow_inv3`: detects confusion between a local binder and a same-named signature.\n");
        markdown.append("- `temporal_implication_inv4`: detects loss of the implication antecedent across temporal phases.\n");
        List<Result> errors = results.stream().filter(result -> result.outcome == Outcome.ERROR).toList();
        if (!errors.isEmpty()) {
            markdown.append("\n## Errors\n\n");
            for (Result result : errors) {
                markdown.append("- `").append(result.claim.relativePath).append("`: ")
                        .append(result.error).append("\n");
            }
        }
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static String manifestField(Path resultsRoot, String field) {
        Path manifest = resultsRoot.resolve("run-manifest.json");
        if (!Files.isRegularFile(manifest)) {
            return "unrecorded";
        }
        try {
            return new JSONObject(Files.readString(manifest, StandardCharsets.UTF_8))
                    .optString(field, "unrecorded");
        } catch (RuntimeException | IOException exception) {
            return "unreadable";
        }
    }

    private static JSONObject engineSummary(String engine, List<Result> results) {
        long claims = results.stream().filter(result -> result.claim.engines.contains(engine)).count();
        long clean = results.stream().filter(result -> result.claim.engines.contains(engine)
                && result.outcome == Outcome.NO_COUNTEREXAMPLE).count();
        long counterexamples = results.stream().filter(result -> result.claim.engines.contains(engine)
                && result.outcome == Outcome.COUNTEREXAMPLE).count();
        long errors = results.stream().filter(result -> result.claim.engines.contains(engine)
                && result.outcome == Outcome.ERROR).count();
        return new JSONObject().put("engine", engine).put("claims", claims)
                .put("noCounterexample", clean).put("counterexamples", counterexamples).put("errors", errors);
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

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private enum Outcome {
        NO_COUNTEREXAMPLE,
        COUNTEREXAMPLE,
        ERROR
    }

    private static final class Claim {
        private final String relativePath;
        private final Set<String> engines = new LinkedHashSet<>();
        private String problemClass = "";
        private String status = "";
        private String leftPredicate = "";
        private String rightPredicate = "";

        private Claim(String relativePath) {
            this.relativePath = relativePath;
        }
    }

    private static final class Result {
        private final Claim claim;
        private Outcome outcome = Outcome.ERROR;
        private String command = "";
        private String error = "";
        private long solveNanos;

        private Result(Claim claim) {
            this.claim = claim;
        }

        private JSONObject toJson() {
            JSONArray engines = new JSONArray();
            for (String engine : claim.engines) {
                engines.put(engine);
            }
            return new JSONObject().put("relativePath", claim.relativePath)
                    .put("problemClass", claim.problemClass).put("status", claim.status)
                    .put("leftPredicate", claim.leftPredicate).put("rightPredicate", claim.rightPredicate)
                    .put("engines", engines).put("outcome", outcome.name())
                    .put("command", command).put("solveNanos", solveNanos).put("error", error);
        }
    }

    private static final class VerifyTask implements Callable<Result> {
        private final Path inputRoot;
        private final Claim claim;

        private VerifyTask(Path inputRoot, Claim claim) {
            this.inputRoot = inputRoot;
            this.claim = claim;
        }

        @Override
        public Result call() {
            Result result = new Result(claim);
            long started = System.nanoTime();
            try {
                CompModule module = CompUtil.parseEverything_fromFile(
                        new A4Reporter(), null, inputRoot.resolve(claim.relativePath).toString());
                Command command = equivalenceCheck(module);
                if (command == null) {
                    throw new IllegalStateException("No Alloy check command found");
                }
                result.command = command.toString();
                A4Options options = new A4Options();
                options.solver = A4Options.SatSolver.SAT4J;
                A4Solution solution = TranslateAlloyToKodkod.execute_command(
                        new A4Reporter(), module.getAllReachableSigs(), command, options);
                result.outcome = solution != null && solution.satisfiable()
                        ? Outcome.COUNTEREXAMPLE : Outcome.NO_COUNTEREXAMPLE;
            } catch (Throwable throwable) {
                result.error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
                result.outcome = Outcome.ERROR;
            } finally {
                result.solveNanos = System.nanoTime() - started;
            }
            return result;
        }
    }

    private static final class ProbeResult {
        private final String name;
        private final String path;
        private final Map<String, Boolean> merged = new LinkedHashMap<>();
        private boolean counterexample;
        private String error = "";

        private ProbeResult(Path file) {
            this.name = file.getFileName().toString().replaceFirst("\\.als$", "");
            this.path = file.toString();
        }

        private JSONObject toJson() {
            JSONObject engines = new JSONObject();
            for (Map.Entry<String, Boolean> entry : merged.entrySet()) {
                engines.put(entry.getKey(), entry.getValue());
            }
            return new JSONObject().put("name", name).put("path", path)
                    .put("alloyCounterexample", counterexample)
                    .put("mergedByEngine", engines).put("error", error);
        }
    }

    private static final class Options {
        private Path inputRoot = Paths.get("classified-data");
        private Path resultsRoot = Paths.get("egraph_ablation");
        private int threads = 32;
        private boolean canonicalOnly;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input":
                        options.inputRoot = Paths.get(args[++i]);
                        break;
                    case "--results":
                        options.resultsRoot = Paths.get(args[++i]);
                        break;
                    case "--threads":
                        options.threads = Integer.parseInt(args[++i]);
                        break;
                    case "--canonical-only":
                        options.canonicalOnly = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (options.threads < 1) {
                throw new IllegalArgumentException("--threads must be positive");
            }
            return options;
        }
    }
}
