package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.adapter.AlloyAstTermAdapter;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.core.egraph.AblationEngine;
import is.fivefivefive.CanDis.core.egraph.AlloyTerm;
import is.fivefivefive.CanDis.core.egraph.EGraphStats;
import is.fivefivefive.CanDis.core.egraph.JavaEgglog;
import is.fivefivefive.CanDis.core.egraph.JavaEgglogDeBruijn;
import is.fivefivefive.CanDis.core.egraph.RawDeBruijnEGraph;
import is.fivefivefive.CanDis.core.egraph.RawEGraph;
import is.fivefivefive.CanDis.core.egraph.SlottedEGraph;
import is.fivefivefive.CanDis.theory.BoundedFiniteUnfoldingOracle;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.ProductionGraphCanonicalizer;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Predicate;
import parser.ast.nodes.Function;
import parser.ast.nodes.Call;
import parser.ast.nodes.PredOrFun;
import parser.util.AlloyUtil;

/** Runs one process-isolated arm of the Alloy e-graph ablation. */
public final class EGraphAblationStudy {
    private static final String DEFAULT_INPUT = "classified-data";
    private static final String DEFAULT_OUTPUT = "egraph_ablation/raw-egraph";
    private static final int DEFAULT_THREADS = AblationParallelism.defaultWorkers();
    private static final ThreadMXBean THREAD_CPU = ManagementFactory.getThreadMXBean();

    private EGraphAblationStudy() {
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        Options options = Options.parse(args);
        Files.createDirectories(options.output);
        List<Path> files = alloyFiles(options.input);
        if (options.limit > 0 && options.limit < files.size()) {
            files = new ArrayList<>(files.subList(0, options.limit));
        }

        HeapSampler sampler = new HeapSampler();
        sampler.start();
        long started = System.nanoTime();
        List<FileResult> results = processFiles(files, options);
        long wallNanos = System.nanoTime() - started;
        long peakHeapBytes = sampler.stop();

        Summary summary = new Summary(options.engine, files.size(), wallNanos, peakHeapBytes);
        for (FileResult result : results) {
            summary.add(result);
        }
        writeCsv(options.output.resolve("pairs.csv"), results);
        writeJson(options.output.resolve("summary.json"), options, summary);
        writeProperties(options.output.resolve("metrics.properties"), options, summary);
        System.out.println("Completed " + options.engine.id + " on " + summary.successes + "/"
                + summary.files + " predicate pairs (skipped " + summary.skipped
                + " AST-identical pairs) in " + formatSeconds(wallNanos) + " s.");
    }

    private static List<FileResult> processFiles(List<Path> files, Options options) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream sink = new PrintStream(new OutputStream() {
            @Override
            public void write(int value) {
            }
        });
        if (!options.verbose) {
            System.setOut(sink);
            System.setErr(sink);
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, options.threads));
        try {
            CompletionService<IndexedFileResult> completion =
                    new ExecutorCompletionService<>(executor);
            Map<Future<IndexedFileResult>, Integer> active = new HashMap<>();
            int workers = Math.max(1, options.threads);
            int maximumInFlight = workers > Integer.MAX_VALUE / 4
                    ? Integer.MAX_VALUE : workers * 4;
            int submitted = 0;
            while (submitted < files.size() && active.size() < maximumInFlight) {
                final int fileIndex = submitted++;
                Future<IndexedFileResult> future = completion.submit(
                        () -> new IndexedFileResult(fileIndex,
                                processFile(options, files.get(fileIndex))));
                active.put(future, fileIndex);
            }
            List<FileResult> results = new ArrayList<>(
                    java.util.Collections.nCopies(files.size(), null));
            ExperimentProgress progress = ExperimentProgress.start(
                    originalErr,
                    "EGraphAblationStudy/" + options.engine.id,
                    files.size(),
                    "files",
                    "with " + Math.max(1, options.threads) + " workers");
            int completed = 0;
            while (completed < files.size()) {
                Future<IndexedFileResult> future;
                try {
                    future = completion.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ablation run was interrupted", exception);
                }
                if (future == null) {
                    progress.heartbeat(completed, active.size(), null);
                    continue;
                }
                Integer expectedIndex = active.remove(future);
                int index = expectedIndex == null ? completed : expectedIndex;
                try {
                    IndexedFileResult indexed = future.get();
                    results.set(indexed.index, indexed.result);
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    if (cause instanceof VirtualMachineError) {
                        throw (VirtualMachineError) cause;
                    }
                    results.set(index, FileResult.failure(options.input, files.get(index),
                            cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Ablation run was interrupted", exception);
                }
                progress.update(++completed);
                while (submitted < files.size() && active.size() < maximumInFlight) {
                    final int fileIndex = submitted++;
                    Future<IndexedFileResult> next = completion.submit(
                            () -> new IndexedFileResult(fileIndex,
                                    processFile(options, files.get(fileIndex))));
                    active.put(next, fileIndex);
                }
            }
            progress.finish(completed);

            // Parser/library races are transient in this corpus; retry only failed files serially.
            List<Integer> failedIndexes = new ArrayList<>();
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).error != null) {
                    failedIndexes.add(index);
                }
            }
            ExperimentProgress retryProgress = ExperimentProgress.start(
                    originalErr,
                    "EGraphAblationStudy/" + options.engine.id + "/retry",
                    failedIndexes.size(),
                    "files");
            int retried = 0;
            for (int index : failedIndexes) {
                FileResult retry = processFile(options, files.get(index));
                if (retry.error == null) {
                    results.set(index, retry);
                }
                retryProgress.update(++retried);
            }
            retryProgress.finish(retried);
            return results;
        } finally {
            executor.shutdownNow();
            sink.close();
            if (!options.verbose) {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static FileResult processFile(Options options, Path file) {
        FileResult result = new FileResult(options.input, file);
        long totalStarted = System.nanoTime();
        long totalCpuStarted = currentThreadCpuNanos();
        try {
            long parseStarted = System.nanoTime();
            long parseCpuStarted = currentThreadCpuNanos();
            CompModule module = AlloyUtil.compileAlloyModule(file.toString());
            if (module == null) {
                throw new IllegalStateException("Alloy parser returned no module");
            }
            ModelUnit model = new ModelUnit(null, module);
            PredicatePair pair = findPredicatePair(file, model);
            if (pair == null) {
                throw new IllegalStateException("No predicate pair of the form X and X[Cc] found");
            }
            result.leftPredicate = pair.leftName;
            result.rightPredicate = pair.rightName;
            result.rawAstNodes = predicateAstSize(pair.left) + predicateAstSize(pair.right);
            result.parseNanos = System.nanoTime() - parseStarted;
            result.parseCpuNanos = elapsedThreadCpuNanos(parseCpuStarted);
            if (DatasetConventions.sameRawAst(pair.left.getBody(), pair.right.getBody())) {
                result.skipped = true;
                return result;
            }

            long engineStarted = System.nanoTime();
            long engineCpuStarted = currentThreadCpuNanos();
            if (options.engine == Engine.CANONICAL) {
                runCanonical(module, model, pair, result);
            } else if (options.engine == Engine.TYPED_SLOTTED_PORT) {
                runTypedSlottedPort(module, model, pair, result);
            } else {
                AlloyTerm left = AlloyAstTermAdapter.fromPredicate(pair.left);
                AlloyTerm right = AlloyAstTermAdapter.fromPredicate(pair.right);
                AblationEngine.Result comparison = options.engine.newEngine().compare(left, right);
                result.distance = comparison.distance;
                result.equivalent = comparison.equivalent;
                result.stats = comparison.stats;
                result.representationUnits = comparison.stats.enodes;
            }
            result.engineNanos = System.nanoTime() - engineStarted;
            result.engineCpuNanos = elapsedThreadCpuNanos(engineCpuStarted);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            result.error = throwable.getClass().getSimpleName() + ": " + throwable.getMessage();
            if (options.verbose) {
                throwable.printStackTrace(System.err);
            }
        } finally {
            result.totalNanos = System.nanoTime() - totalStarted;
            result.totalCpuNanos = elapsedThreadCpuNanos(totalCpuStarted);
        }
        return result;
    }

    private static long currentThreadCpuNanos() {
        if (!THREAD_CPU.isCurrentThreadCpuTimeSupported()) {
            return -1L;
        }
        if (!THREAD_CPU.isThreadCpuTimeEnabled()) {
            try {
                THREAD_CPU.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException | SecurityException ignored) {
                return -1L;
            }
        }
        return THREAD_CPU.getCurrentThreadCpuTime();
    }

    private static long elapsedThreadCpuNanos(long started) {
        long finished = currentThreadCpuNanos();
        return started < 0 || finished < 0 ? 0L : Math.max(0L, finished - started);
    }

    private static void runCanonical(
            CompModule module,
            ModelUnit model,
            PredicatePair pair,
            FileResult result) {
        MASGVisitor visitor = focusedVisitor(module, model, pair);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        Integer leftId = visitor.getForestId(pair.leftName);
        Integer rightId = visitor.getForestId(pair.rightName);
        Multigraph left = leftId == null ? null : forest.get(leftId);
        Multigraph right = rightId == null ? null : forest.get(rightId);
        if (left == null || right == null) {
            throw new IllegalStateException("Could not find both predicate graphs in MASG forest");
        }
        Canonical.Prepared leftPrepared = Canonical.prepare(left);
        Canonical.Prepared rightPrepared = Canonical.prepare(right);
        int leftSize = Canonical.canonicalFormSize(leftPrepared);
        int rightSize = Canonical.canonicalFormSize(rightPrepared);
        result.representationUnits = leftSize + rightSize;
        result.distance = Canonical.distance(leftPrepared, rightPrepared);
        result.equivalent = result.distance == 0;
        long eclasses = Canonical.eclassCount(leftPrepared) + Canonical.eclassCount(rightPrepared);
        long enodes = Canonical.enodeCount(leftPrepared) + Canonical.enodeCount(rightPrepared);
        result.stats = new EGraphStats(eclasses, enodes, 0, 0,
                0, 0, 0, 0, 0, result.representationUnits * 64L);
    }

    private static void runTypedSlottedPort(
            CompModule module,
            ModelUnit model,
            PredicatePair pair,
            FileResult result) {
        MASGVisitor visitor = focusedVisitor(module, model, pair);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        Integer leftId = visitor.getForestId(pair.leftName);
        Integer rightId = visitor.getForestId(pair.rightName);
        Multigraph left = leftId == null ? null : forest.get(leftId);
        Multigraph right = rightId == null ? null : forest.get(rightId);
        if (left == null || right == null) {
            throw new IllegalStateException("Could not find both predicate graphs in MASG forest");
        }
        Canonical.Prepared leftNormalized = Canonical.prepare(left);
        Canonical.Prepared rightNormalized = Canonical.prepare(right);
        CanonicalAlloyPipeline.Prepared leftPrepared =
                CanonicalAlloyPipeline.prepare(leftNormalized);
        CanonicalAlloyPipeline.Prepared rightPrepared =
                CanonicalAlloyPipeline.prepare(rightNormalized);
        result.representationUnits = (long) leftPrepared.repairObservationSize()
                + rightPrepared.repairObservationSize();
        result.distance = CanonicalAlloyPipeline.distance(leftPrepared, rightPrepared);
        result.equivalent = result.distance == 0;
        result.stats = new EGraphStats(
                leftPrepared.eclassCount() + rightPrepared.eclassCount(),
                leftPrepared.enodeCount() + rightPrepared.enodeCount(),
                0,
                leftPrepared.rebuildCount() + rightPrepared.rebuildCount(),
                0,
                0,
                leftPrepared.slotCount() + rightPrepared.slotCount(),
                0,
                0,
                leftPrepared.estimatedBytes() + rightPrepared.estimatedBytes());
    }

    private static MASGVisitor focusedVisitor(
            CompModule module,
            ModelUnit model,
            PredicatePair pair) {
        Set<String> callables = callableClosure(model, pair.leftName, pair.rightName);
        MASGVisitor visitor = new MASGVisitor(
                new GlobalVariables(), callables, module);
        try {
            visitor.visit(model, null);
            return visitor;
        } catch (RuntimeException focusedFailure) {
            MASGVisitor fallback = new MASGVisitor(new GlobalVariables(), module);
            fallback.visit(model, null);
            return fallback;
        }
    }

    private static Set<String> callableClosure(ModelUnit model, String leftName, String rightName) {
        Map<String, PredOrFun> declarations = new HashMap<>();
        for (Predicate predicate : model.getPredDeclList()) {
            declarations.put(predicate.getName(), predicate);
        }
        for (Function function : model.getFunDeclList()) {
            declarations.put(function.getName(), function);
        }
        Set<String> selected = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(leftName);
        pending.add(rightName);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!selected.add(name)) {
                continue;
            }
            PredOrFun declaration = declarations.get(name);
            if (declaration != null) {
                collectCalledDeclarations(declaration, declarations, selected, pending);
            }
        }
        return selected;
    }

    private static void collectCalledDeclarations(
            Node node,
            Map<String, PredOrFun> declarations,
            Set<String> selected,
            ArrayDeque<String> pending) {
        if (node instanceof Call) {
            String name = ((Call) node).getName();
            if (declarations.containsKey(name) && !selected.contains(name)) {
                pending.addLast(name);
            }
        }
        for (Node child : DatasetConventions.rawAstChildren(node)) {
            collectCalledDeclarations(child, declarations, selected, pending);
        }
    }

    private static PredicatePair findPredicatePair(Path file, ModelUnit model) {
        Map<String, Predicate> predicates = new HashMap<>();
        Map<String, Integer> ids = new HashMap<>();
        int id = 1;
        for (Predicate predicate : model.getPredDeclList()) {
            predicates.put(predicate.getName(), predicate);
            ids.put(predicate.getName(), id++);
        }
        String[] names = DatasetConventions.findPredicatePairNames(preferredPredicateBase(file), predicates);
        if (names == null) {
            return null;
        }
        return new PredicatePair(names[0], names[1], ids.get(names[0]), ids.get(names[1]),
                predicates.get(names[0]), predicates.get(names[1]));
    }

    private static int predicateAstSize(Predicate predicate) {
        int size = 1;
        if (predicate.getParamList() != null) {
            for (Node parameter : predicate.getParamList()) {
                size += astSize(parameter);
            }
        }
        size += astSize(predicate.getBody());
        return size;
    }

    private static int astSize(Node node) {
        if (node == null) {
            return 0;
        }
        int size = 1;
        List<Node> children = node.getChildren();
        if (children != null) {
            for (Node child : children) {
                size += astSize(child);
            }
        }
        return size;
    }

    private static String preferredPredicateBase(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        int underscore = name.lastIndexOf('_');
        return underscore < 0 || underscore + 1 >= name.length()
                ? null
                : name.substring(underscore + 1);
    }

    private static List<Path> alloyFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".als"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static void writeCsv(Path path, List<FileResult> results) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,problemClass,status,leftPredicate,rightPredicate,success,equivalent,"
                    + "distance,parseNanos,engineNanos,totalNanos,parseCpuNanos,engineCpuNanos,totalCpuNanos,"
                    + "rawAstNodes,representationUnits,eclasses,enodes,"
                    + "unions,rebuilds,rewriteApplications,iterations,slots,slotMappings,redundantSlots,"
                    + "estimatedBytes,error\n");
            for (FileResult result : results) {
                if (result.skipped) {
                    continue;
                }
                EGraphStats stats = result.stats == null ? EGraphStats.empty() : result.stats;
                csv(writer, result.relativePath);
                csv(writer, result.problemClass);
                csv(writer, result.status);
                csv(writer, result.leftPredicate);
                csv(writer, result.rightPredicate);
                writer.write(Boolean.toString(result.error == null));
                writer.write(',');
                writer.write(Boolean.toString(result.equivalent));
                writer.write(',');
                writer.write(Integer.toString(result.distance));
                writer.write(',');
                writer.write(Long.toString(result.parseNanos));
                writer.write(',');
                writer.write(Long.toString(result.engineNanos));
                writer.write(',');
                writer.write(Long.toString(result.totalNanos));
                writer.write(',');
                writer.write(Long.toString(result.parseCpuNanos));
                writer.write(',');
                writer.write(Long.toString(result.engineCpuNanos));
                writer.write(',');
                writer.write(Long.toString(result.totalCpuNanos));
                writer.write(',');
                writer.write(Integer.toString(result.rawAstNodes));
                writer.write(',');
                writer.write(Long.toString(result.representationUnits));
                writer.write(',');
                writer.write(Long.toString(stats.eclasses));
                writer.write(',');
                writer.write(Long.toString(stats.enodes));
                writer.write(',');
                writer.write(Long.toString(stats.unions));
                writer.write(',');
                writer.write(Long.toString(stats.rebuilds));
                writer.write(',');
                writer.write(Long.toString(stats.rewriteApplications));
                writer.write(',');
                writer.write(Long.toString(stats.iterations));
                writer.write(',');
                writer.write(Long.toString(stats.slots));
                writer.write(',');
                writer.write(Long.toString(stats.slotMappings));
                writer.write(',');
                writer.write(Long.toString(stats.redundantSlots));
                writer.write(',');
                writer.write(Long.toString(stats.estimatedBytes));
                writer.write(',');
                csvLast(writer, result.error);
                writer.write('\n');
            }
        }
    }

    private static void csv(Writer writer, String value) throws IOException {
        csvLast(writer, value);
        writer.write(',');
    }

    private static void csvLast(Writer writer, String value) throws IOException {
        String text = value == null ? "" : value;
        writer.write('"');
        writer.write(text.replace("\"", "\"\""));
        writer.write('"');
    }

    private static void writeJson(Path path, Options options, Summary summary) throws IOException {
        JSONObject root = new JSONObject();
        root.put("generatedAt", Instant.now().toString());
        root.put("inputRoot", options.input.toString());
        root.put("engine", options.engine.id);
        root.put("engineDescription", options.engine.description);
        root.put("engineIdentifier", options.engine.engineIdentifier());
        root.put("theoryFaithfulEngineUsed", options.engine.usesExactEngine());
        root.put("invariantCheckMode", options.engine.invariantMode());
        root.put("canonicalizerVersion", options.engine.canonicalizerVersion());
        root.put("certificateMode", options.engine.certificateMode());
        root.put("canonicalPipelineVersion", options.engine.pipelineVersion());
        root.put("measurementProjectionVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.MEASUREMENT_PROJECTION_VERSION
                : "not-applicable");
        root.put("quotientMetricVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION
                : "not-applicable");
        root.put("canonicalRepresentativeTedVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION
                : "not-applicable");
        root.put("sharedBaselineRuleSet", JavaEgglog.ruleSetVersion());
        root.put("sharedBaselineRewriteRules", new JSONArray(JavaEgglog.ruleNames()));
        root.put("threadCount", options.threads);
        root.put("logicalProcessors", AblationParallelism.logicalProcessors());
        root.put("threadPolicy", AblationParallelism.POLICY);
        root.put("limit", options.limit);
        root.put("overall", summary.overall.toJson(summary));
        JSONArray groups = new JSONArray();
        for (Map.Entry<GroupKey, Accumulator> entry : summary.groups.entrySet()) {
            JSONObject group = entry.getValue().toJson(null);
            group.put("problemClass", entry.getKey().problemClass);
            group.put("status", entry.getKey().status);
            groups.put(group);
        }
        root.put("byProblemAndStatus", groups);
        JSONArray failures = new JSONArray();
        for (FileResult failure : summary.failureExamples) {
            failures.put(new JSONObject()
                    .put("relativePath", failure.relativePath)
                    .put("error", failure.error));
        }
        root.put("failureExamples", failures);
        Files.writeString(path, root.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeProperties(Path path, Options options, Summary summary) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("engine", options.engine.id);
        properties.setProperty("description", options.engine.description);
        properties.setProperty("engineIdentifier", options.engine.engineIdentifier());
        properties.setProperty("theoryFaithfulEngineUsed",
                Boolean.toString(options.engine.usesExactEngine()));
        properties.setProperty("invariantCheckMode", options.engine.invariantMode());
        properties.setProperty("canonicalizerVersion", options.engine.canonicalizerVersion());
        properties.setProperty("certificateMode", options.engine.certificateMode());
        properties.setProperty("canonicalPipelineVersion", options.engine.pipelineVersion());
        properties.setProperty("measurementProjectionVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.MEASUREMENT_PROJECTION_VERSION
                : "not-applicable");
        properties.setProperty("quotientMetricVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION
                : "not-applicable");
        properties.setProperty("canonicalRepresentativeTedVersion", options.engine.usesExactEngine()
                ? CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION
                : "not-applicable");
        properties.setProperty("alloyAdapterVersion", options.engine.usesExactEngine()
                ? TheoryAlloyAdapter.ADAPTER_VERSION : "not-applicable");
        properties.setProperty("finiteUnfoldingVersion", options.engine.usesExactEngine()
                ? BoundedFiniteUnfoldingOracle.VERSION : "not-applicable");
        properties.setProperty("certificateVerifierVersion", options.engine.usesExactEngine()
                ? CertificateVerifier.VERSION : "not-applicable");
        properties.setProperty("sharedBaselineRuleSet", JavaEgglog.ruleSetVersion());
        properties.setProperty("sharedBaselineRuleCount", Integer.toString(JavaEgglog.ruleNames().size()));
        properties.setProperty("cpuAccounting", "thread-cpu-v1");
        properties.setProperty("inputRoot", options.input.toString());
        properties.setProperty("threads", Integer.toString(options.threads));
        properties.setProperty("logicalProcessors", Integer.toString(AblationParallelism.logicalProcessors()));
        properties.setProperty("threadPolicy", AblationParallelism.POLICY);
        properties.setProperty("files", Long.toString(summary.files));
        properties.setProperty("successes", Long.toString(summary.successes));
        properties.setProperty("skippedIdenticalRawAstPairs", Long.toString(summary.skipped));
        properties.setProperty("failures", Long.toString(summary.failures));
        properties.setProperty("equivalentPairs", Long.toString(summary.overall.equivalent));
        properties.setProperty("totalDistance", Long.toString(summary.overall.distance));
        properties.setProperty("averageDistance", Double.toString(summary.overall.averageDistance()));
        properties.setProperty("p50Distance", Integer.toString(summary.overall.distancePercentile(0.50)));
        properties.setProperty("p95Distance", Integer.toString(summary.overall.distancePercentile(0.95)));
        properties.setProperty("wallNanos", Long.toString(summary.wallNanos));
        properties.setProperty("peakUsedHeapBytes", Long.toString(summary.peakHeapBytes));
        properties.setProperty("parseTaskNanos", Long.toString(summary.overall.parseNanos));
        properties.setProperty("engineTaskNanos", Long.toString(summary.overall.engineNanos));
        properties.setProperty("parseCpuNanos", Long.toString(summary.overall.parseCpuNanos));
        properties.setProperty("engineCpuNanos", Long.toString(summary.overall.engineCpuNanos));
        properties.setProperty("totalCpuNanos", Long.toString(summary.overall.totalCpuNanos));
        properties.setProperty("averageEngineNanos", Double.toString(summary.overall.averageEngineNanos()));
        properties.setProperty("p50EngineNanos", Long.toString(summary.overall.percentile(0.50)));
        properties.setProperty("p95EngineNanos", Long.toString(summary.overall.percentile(0.95)));
        properties.setProperty("averageRepresentationUnits", Double.toString(summary.overall.averageRepresentationUnits()));
        properties.setProperty("averageEclasses", Double.toString(summary.overall.averageEclasses()));
        properties.setProperty("averageEnodes", Double.toString(summary.overall.averageEnodes()));
        properties.setProperty("averageEstimatedBytes", Double.toString(summary.overall.averageEstimatedBytes()));
        properties.setProperty("peakEstimatedBytes", Long.toString(summary.overall.peakEstimatedBytes));
        try (OutputStream output = Files.newOutputStream(path)) {
            properties.store(output, "E-graph ablation metrics");
        }
    }

    private static String formatSeconds(long nanos) {
        return String.format(Locale.ROOT, "%.3f", nanos / 1_000_000_000.0);
    }

    private enum Engine {
        RAW("raw-egraph", "Conventional fixed-arity e-graph with the shared Alloy rewrite program"),
        RAW_DEBRUIJN("raw-egraph-debruijn",
                "Fixed-arity e-graph storing bound variables as De Bruijn indices"),
        EGGLOG("java-egglog", "Java egglog core replica with shared variadic rules and rebuilding"),
        EGGLOG_DEBRUIJN("java-egglog-debruijn",
                "Java egglog core storing bound variables as De Bruijn indices"),
        SLOTTED("slotted-egraph", "Retained slotted e-graph with shared variadic rules, renamed IDs, and permutation groups"),
        CANONICAL("canonical",
                "Fast Rewrite IR temporal/prenex/slotted method with bounded rewrite saturation"),
        TYPED_SLOTTED_PORT(
                "typed-slotted-port-egraph",
                "Complete CanonicalAlloyPipeline over the exact TypedSlottedPortEGraph");

        private final String id;
        private final String description;

        Engine(String id, String description) {
            this.id = id;
            this.description = description;
        }

        private AblationEngine newEngine() {
            switch (this) {
                case RAW:
                    return new RawEGraph();
                case RAW_DEBRUIJN:
                    return new RawDeBruijnEGraph();
                case EGGLOG:
                    return new JavaEgglog();
                case EGGLOG_DEBRUIJN:
                    return new JavaEgglogDeBruijn();
                case SLOTTED:
                    return new SlottedEGraph();
                default:
                    throw new IllegalStateException("Canonical uses the production pipeline directly");
            }
        }

        private boolean usesExactEngine() {
            return this == TYPED_SLOTTED_PORT;
        }

        private String engineIdentifier() {
            return usesExactEngine() ? "TypedSlottedPortEGraph" : "legacy-bounded/" + id;
        }

        private String invariantMode() {
            return usesExactEngine() ? TheoryAlloyAdapter.INVARIANT_MODE : "legacy-arm-local-checks";
        }

        private String canonicalizerVersion() {
            return usesExactEngine() ? ProductionGraphCanonicalizer.VERSION : "legacy-bounded";
        }

        private String certificateMode() {
            return usesExactEngine() ? "required" : "not-applicable";
        }

        private String pipelineVersion() {
            return usesExactEngine() ? CanonicalAlloyPipeline.PIPELINE_VERSION : "legacy-bounded";
        }

        private static Engine parse(String value) {
            String normalized = value.toLowerCase(Locale.ROOT).replace('_', '-');
            for (Engine engine : values()) {
                if (engine.id.equals(normalized)) {
                    return engine;
                }
            }
            if ("raw".equals(normalized)) {
                return RAW;
            }
            if ("raw-debruijn".equals(normalized) || "raw-db".equals(normalized)) {
                return RAW_DEBRUIJN;
            }
            if ("egglog".equals(normalized)) {
                return EGGLOG;
            }
            if ("egglog-debruijn".equals(normalized) || "egglog-db".equals(normalized)) {
                return EGGLOG_DEBRUIJN;
            }
            if ("slotted".equals(normalized)) {
                return SLOTTED;
            }
            if ("typed-slotted-port".equals(normalized)
                    || "theory".equals(normalized)
                    || "exact".equals(normalized)) {
                return TYPED_SLOTTED_PORT;
            }
            throw new IllegalArgumentException("Unknown engine: " + value);
        }
    }

    private static final class Options {
        private Path input = Paths.get(DEFAULT_INPUT);
        private Path output = Paths.get(DEFAULT_OUTPUT);
        private Engine engine = Engine.RAW;
        private int threads = DEFAULT_THREADS;
        private int limit;
        private boolean verbose;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--input":
                        options.input = Paths.get(requireValue(args, ++i, "--input"));
                        break;
                    case "--output":
                        options.output = Paths.get(requireValue(args, ++i, "--output"));
                        break;
                    case "--engine":
                        options.engine = Engine.parse(requireValue(args, ++i, "--engine"));
                        break;
                    case "--threads":
                        options.threads = Integer.parseInt(requireValue(args, ++i, "--threads"));
                        break;
                    case "--limit":
                        options.limit = Math.max(0, Integer.parseInt(requireValue(args, ++i, "--limit")));
                        break;
                    case "--verbose":
                        options.verbose = true;
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            options.threads = AblationParallelism.effectiveWorkers(options.threads);
            return options;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static final class PredicatePair {
        private final String leftName;
        private final String rightName;
        private final int leftId;
        private final int rightId;
        private final Predicate left;
        private final Predicate right;

        private PredicatePair(
                String leftName,
                String rightName,
                int leftId,
                int rightId,
                Predicate left,
                Predicate right) {
            this.leftName = leftName;
            this.rightName = rightName;
            this.leftId = leftId;
            this.rightId = rightId;
            this.left = left;
            this.right = right;
        }
    }

    private static final class FileResult {
        private final String relativePath;
        private final String problemClass;
        private final String status;
        private String leftPredicate = "";
        private String rightPredicate = "";
        private long parseNanos;
        private long engineNanos;
        private long totalNanos;
        private long parseCpuNanos;
        private long engineCpuNanos;
        private long totalCpuNanos;
        private int rawAstNodes;
        private long representationUnits;
        private int distance = -1;
        private boolean equivalent;
        private boolean skipped;
        private EGraphStats stats = EGraphStats.empty();
        private String error;

        private FileResult(Path root, Path file) {
            Path relative = root.relativize(file);
            this.relativePath = relative.toString().replace('\\', '/');
            this.problemClass = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";
            this.status = DatasetConventions.normalizeStatusFolder(
                    relative.getNameCount() > 1 ? relative.getName(1).toString() : "");
        }

        private static FileResult failure(Path root, Path file, String error) {
            FileResult result = new FileResult(root, file);
            result.error = error;
            return result;
        }
    }

    private static final class IndexedFileResult {
        private final int index;
        private final FileResult result;

        private IndexedFileResult(int index, FileResult result) {
            this.index = index;
            this.result = result;
        }
    }

    private static final class Summary {
        private final Engine engine;
        private final long files;
        private final long wallNanos;
        private final long peakHeapBytes;
        private final Accumulator overall = new Accumulator();
        private final Map<GroupKey, Accumulator> groups = new LinkedHashMap<>();
        private final List<FileResult> failureExamples = new ArrayList<>();
        private long successes;
        private long skipped;
        private long failures;

        private Summary(Engine engine, long files, long wallNanos, long peakHeapBytes) {
            this.engine = engine;
            this.files = files;
            this.wallNanos = wallNanos;
            this.peakHeapBytes = peakHeapBytes;
        }

        private void add(FileResult result) {
            if (result.skipped) {
                skipped++;
                return;
            }
            if (result.error != null) {
                failures++;
                if (failureExamples.size() < 100) {
                    failureExamples.add(result);
                }
                return;
            }
            successes++;
            overall.add(result);
            groups.computeIfAbsent(new GroupKey(result.problemClass, result.status), ignored -> new Accumulator())
                    .add(result);
        }
    }

    private static final class GroupKey implements Comparable<GroupKey> {
        private final String problemClass;
        private final String status;

        private GroupKey(String problemClass, String status) {
            this.problemClass = problemClass;
            this.status = status;
        }

        @Override
        public int compareTo(GroupKey other) {
            int comparison = problemClass.compareTo(other.problemClass);
            return comparison != 0 ? comparison : status.compareTo(other.status);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof GroupKey)) {
                return false;
            }
            GroupKey key = (GroupKey) other;
            return problemClass.equals(key.problemClass) && status.equals(key.status);
        }

        @Override
        public int hashCode() {
            return 31 * problemClass.hashCode() + status.hashCode();
        }
    }

    private static final class Accumulator {
        private long count;
        private long equivalent;
        private long distance;
        private long parseNanos;
        private long engineNanos;
        private long totalNanos;
        private long parseCpuNanos;
        private long engineCpuNanos;
        private long totalCpuNanos;
        private long rawAstNodes;
        private long representationUnits;
        private long eclasses;
        private long enodes;
        private long unions;
        private long rebuilds;
        private long rewrites;
        private long iterations;
        private long slots;
        private long slotMappings;
        private long redundantSlots;
        private long estimatedBytes;
        private long peakEstimatedBytes;
        private final List<Long> engineTimes = new ArrayList<>();
        private final List<Integer> distances = new ArrayList<>();

        private void add(FileResult result) {
            EGraphStats stats = result.stats;
            count++;
            equivalent += result.equivalent ? 1 : 0;
            distance += result.distance;
            parseNanos += result.parseNanos;
            engineNanos += result.engineNanos;
            totalNanos += result.totalNanos;
            parseCpuNanos += result.parseCpuNanos;
            engineCpuNanos += result.engineCpuNanos;
            totalCpuNanos += result.totalCpuNanos;
            rawAstNodes += result.rawAstNodes;
            representationUnits += result.representationUnits;
            eclasses += stats.eclasses;
            enodes += stats.enodes;
            unions += stats.unions;
            rebuilds += stats.rebuilds;
            rewrites += stats.rewriteApplications;
            iterations += stats.iterations;
            slots += stats.slots;
            slotMappings += stats.slotMappings;
            redundantSlots += stats.redundantSlots;
            estimatedBytes += stats.estimatedBytes;
            peakEstimatedBytes = Math.max(peakEstimatedBytes, stats.estimatedBytes);
            engineTimes.add(result.engineNanos);
            distances.add(result.distance);
        }

        private JSONObject toJson(Summary summary) {
            JSONObject json = new JSONObject();
            json.put("count", count);
            json.put("equivalentPairs", equivalent);
            json.put("equivalentRate", ratio(equivalent, count));
            json.put("totalDistance", distance);
            json.put("averageDistance", averageDistance());
            json.put("p50Distance", distancePercentile(0.50));
            json.put("p95Distance", distancePercentile(0.95));
            json.put("parseTaskNanos", parseNanos);
            json.put("engineTaskNanos", engineNanos);
            json.put("totalTaskNanos", totalNanos);
            json.put("parseCpuNanos", parseCpuNanos);
            json.put("engineCpuNanos", engineCpuNanos);
            json.put("totalCpuNanos", totalCpuNanos);
            json.put("averageEngineNanos", averageEngineNanos());
            json.put("p50EngineNanos", percentile(0.50));
            json.put("p95EngineNanos", percentile(0.95));
            json.put("averageRawAstNodes", average(rawAstNodes));
            json.put("averageRepresentationUnits", averageRepresentationUnits());
            json.put("averageEclasses", averageEclasses());
            json.put("averageEnodes", averageEnodes());
            json.put("averageUnions", average(unions));
            json.put("averageRebuilds", average(rebuilds));
            json.put("averageRewriteApplications", average(rewrites));
            json.put("averageIterations", average(iterations));
            json.put("averageSlots", average(slots));
            json.put("averageSlotMappings", average(slotMappings));
            json.put("averageRedundantSlots", average(redundantSlots));
            json.put("averageEstimatedBytes", averageEstimatedBytes());
            json.put("peakEstimatedBytes", peakEstimatedBytes);
            if (summary != null) {
                json.put("files", summary.files);
                json.put("successes", summary.successes);
                json.put("skippedIdenticalRawAstPairs", summary.skipped);
                json.put("failures", summary.failures);
                json.put("wallNanos", summary.wallNanos);
                json.put("throughputPairsPerSecond",
                        summary.wallNanos == 0 ? 0.0 : summary.successes * 1_000_000_000.0 / summary.wallNanos);
                json.put("peakUsedHeapBytes", summary.peakHeapBytes);
            }
            return json;
        }

        private double average(long value) {
            return count == 0 ? 0.0 : (double) value / count;
        }

        private double averageEngineNanos() {
            return average(engineNanos);
        }

        private double averageDistance() {
            return average(distance);
        }

        private double averageRepresentationUnits() {
            return average(representationUnits);
        }

        private double averageEclasses() {
            return average(eclasses);
        }

        private double averageEnodes() {
            return average(enodes);
        }

        private double averageEstimatedBytes() {
            return average(estimatedBytes);
        }

        private long percentile(double percentile) {
            if (engineTimes.isEmpty()) {
                return 0;
            }
            List<Long> sorted = new ArrayList<>(engineTimes);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private int distancePercentile(double percentile) {
            if (distances.isEmpty()) {
                return 0;
            }
            List<Integer> sorted = new ArrayList<>(distances);
            sorted.sort(Comparator.naturalOrder());
            int index = (int) Math.ceil(percentile * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
        }

        private static double ratio(long numerator, long denominator) {
            return denominator == 0 ? 0.0 : (double) numerator / denominator;
        }
    }

    private static final class HeapSampler {
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peak = new AtomicLong();
        private Thread thread;

        private void start() {
            running.set(true);
            thread = new Thread(() -> {
                Runtime runtime = Runtime.getRuntime();
                while (running.get()) {
                    long used = runtime.totalMemory() - runtime.freeMemory();
                    peak.accumulateAndGet(used, Math::max);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }, "egraph-ablation-heap-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private long stop() {
            running.set(false);
            if (thread != null) {
                try {
                    thread.join(1000);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
            return peak.get();
        }
    }
}
