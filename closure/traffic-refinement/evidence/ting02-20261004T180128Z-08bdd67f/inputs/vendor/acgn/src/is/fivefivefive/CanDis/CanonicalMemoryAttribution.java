package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.Call;
import parser.ast.nodes.Function;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.PredOrFun;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Opt-in phase and allocation diagnostics for the production canonical path. */
public final class CanonicalMemoryAttribution {
    public static final long DEFAULT_SEED = 55520260811L;
    public static final int DEFAULT_LIMIT = 2000;

    private CanonicalMemoryAttribution() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        Options options = Options.parse(args);
        if (options.report) {
            report(options);
        } else {
            run(options);
        }
    }

    private static void run(Options options) throws Exception {
        Files.createDirectories(options.output);
        List<Path> files = selectFiles(options.input, options.limit, options.seed);
        PhaseRecorder recorder = new PhaseRecorder();
        recorder.start();
        long started = System.nanoTime();
        RunState state = processFiles(files, options, recorder);
        long wallNanos = System.nanoTime() - started;
        long peakHeapBytes = recorder.stop();

        RunSummary summary = new RunSummary(state.results, state.retries);
        writePairs(options.output.resolve("pairs.csv"), state.results);
        writeSelection(options.output.resolve("selection.csv"), options.input, files);
        writePhaseEvents(options.output.resolve("phase_events.csv"), recorder.events);
        writeHeapSamples(options.output.resolve("heap_samples.csv"), recorder.samples);
        writePhaseSummary(options.output.resolve("phase_summary.csv"), recorder.events);

        long heapBeforeClear = usedHeap();
        int retainedFiles = state.files.size();
        int retainedResults = state.results.size();
        state.files.clear();
        state.results.clear();
        recorder.releaseEvents();
        long heapAfterClear = usedHeap();
        long heapAfterDiagnosticGc = -1L;
        if (options.postRunGc) {
            System.gc();
            Thread.sleep(300L);
            heapAfterDiagnosticGc = usedHeap();
        }

        writeMetrics(options, summary, wallNanos, peakHeapBytes, heapBeforeClear, heapAfterClear,
                heapAfterDiagnosticGc, retainedFiles, retainedResults, filesHash(options.input, files));
        System.out.printf(Locale.ROOT,
                "Canonical memory run: %,d successful / %,d selected, %,d AST-identical skipped, "
                        + "%,d errors, %d workers, %.3f s.%n",
                summary.successes, summary.selected, summary.skipped, summary.errors,
                options.workers, wallNanos / 1e9);
    }

    private static RunState processFiles(List<Path> selected, Options options, PhaseRecorder recorder) {
        List<Path> files = new ArrayList<>(selected);
        List<FileResult> results = new ArrayList<>(
                Collections.nCopies(files.size(), null));
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream sink = new PrintStream(new OutputStream() {
            @Override public void write(int value) { }
        });
        System.setOut(sink);
        System.setErr(sink);
        ExecutorService executor = Executors.newFixedThreadPool(options.workers);
        int retries = 0;
        try {
            CompletionService<IndexedFileResult> completion =
                    new ExecutorCompletionService<>(executor);
            Map<Future<IndexedFileResult>, Integer> active = new HashMap<>();
            for (int index = 0; index < files.size(); index++) {
                final int fileIndex = index;
                Future<IndexedFileResult> future = completion.submit(
                        () -> new IndexedFileResult(fileIndex,
                                processFile(options.input, files.get(fileIndex), recorder)));
                active.put(future, fileIndex);
            }
            ExperimentProgress progress = ExperimentProgress.start(
                    originalErr,
                    "CanonicalMemoryAttribution/files",
                    files.size(),
                    "files",
                    "with " + options.workers + " workers");
            int completed = 0;
            while (completed < files.size()) {
                Future<IndexedFileResult> future;
                try {
                    future = completion.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Memory attribution was interrupted", exception);
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
                    results.set(index, FileResult.failure(options.input, files.get(index), error(cause)));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Memory attribution was interrupted", exception);
                }
                progress.update(++completed);
            }
            progress.finish(completed);
        } finally {
            executor.shutdownNow();
            System.setOut(originalOut);
            System.setErr(originalErr);
            sink.close();
        }

        // Alloy parser internals can race. Match the production ablation's serial retry policy.
        int failed = 0;
        for (FileResult result : results) {
            failed += result.error.isEmpty() ? 0 : 1;
        }
        ExperimentProgress retryProgress = ExperimentProgress.start(
                originalErr,
                "CanonicalMemoryAttribution/retry",
                failed,
                "files");
        int completedRetries = 0;
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).error.isEmpty()) {
                continue;
            }
            FileResult retry = processFile(options.input, files.get(i), recorder);
            retries++;
            if (retry.error.isEmpty()) {
                retry.retried = true;
                results.set(i, retry);
            }
            retryProgress.update(++completedRetries);
        }
        retryProgress.finish(completedRetries);
        results.sort(Comparator.comparing(result -> result.relativePath));
        return new RunState(files, results, retries);
    }

    private static FileResult processFile(Path input, Path file, PhaseRecorder recorder) {
        FileResult result = new FileResult(input, file);
        try {
            recorder.beginStage("raw-ast");
            CompModule module = AlloyUtil.compileAlloyModule(file.toString());
            if (module == null) {
                throw new IllegalStateException("Alloy parser returned no module");
            }
            ModelUnit model = new ModelUnit(null, module);
            PredicatePair pair = findPredicatePair(file, model);
            if (pair == null) {
                throw new IllegalStateException("No predicate pair of the form X and X[Cc] found");
            }
            result.rawAstNodes = predicateAstSize(pair.left) + predicateAstSize(pair.right);
            recorder.endStage("raw-ast", ProxyCounts.rawAst(result.rawAstNodes));
            if (DatasetConventions.sameRawAst(pair.left.getBody(), pair.right.getBody())) {
                result.skipped = true;
                return result;
            }

            recorder.beginStage("masg");
            MASGVisitor visitor = focusedVisitor(module, model, pair);
            DoubleMap<Integer, Multigraph> forest = visitor.getForest();
            Multigraph left = graph(forest, visitor.getForestId(pair.leftName));
            Multigraph right = graph(forest, visitor.getForestId(pair.rightName));
            if (left == null || right == null) {
                throw new IllegalStateException("Could not find both predicate graphs in MASG forest");
            }
            result.masgVertices = left.getVertices().size() + right.getVertices().size();
            result.masgEdges = left.getEdges().size() + right.getEdges().size();
            recorder.endStage("masg", ProxyCounts.masg(result.masgVertices, result.masgEdges));

            CanonicalDistance.Prepared leftPrepared = prepare(left, result, recorder);
            CanonicalDistance.Prepared rightPrepared = prepare(right, result, recorder);
            result.normalForms = leftPrepared.normalFormCount() + rightPrepared.normalFormCount();
            result.quantifiers = leftPrepared.quantifierCount() + rightPrepared.quantifierCount();
            result.temporalNodes = leftPrepared.temporalNodeCount() + rightPrepared.temporalNodeCount();
            result.canonicalSize = leftPrepared.canonicalSize() + rightPrepared.canonicalSize();
            result.eclasses = leftPrepared.eclassCount() + rightPrepared.eclassCount();
            result.enodes = leftPrepared.enodeCount() + rightPrepared.enodeCount();
            result.metadataEntries = leftPrepared.metadataEntryCount() + rightPrepared.metadataEntryCount();
            recorder.boundary("canonical-prepared", ProxyCounts.canonical(result));

            recorder.beginStage("distance");
            CanonicalDistance.beginAllocationTracking();
            CanonicalDistance.AllocationStats allocations;
            try {
                result.distance = CanonicalDistance.distance(leftPrepared, rightPrepared);
            } finally {
                allocations = CanonicalDistance.endAllocationTracking();
            }
            result.distanceScratchBytes = allocations.estimatedBytes();
            result.largestScratchBufferBytes = allocations.largestBufferBytes();
            result.scratchArrays = allocations.arrayCount();
            result.scratchMatrices = allocations.matrixCount();
            result.success = true;
            recorder.endStage("distance", ProxyCounts.distance(result));
            recorder.boundary("result-bookkeeping", ProxyCounts.result());
        } catch (Throwable throwable) {
            result.error = error(throwable);
        } finally {
            recorder.clearPhase();
        }
        return result;
    }

    private static CanonicalDistance.Prepared prepare(
            Multigraph graph,
            FileResult result,
            PhaseRecorder recorder) {
        IRAgent agent = new IRAgent(graph);
        agent.computeNormalForm((stage, active, normalForms) -> {
            ProxyCounts proxy = ProxyCounts.normalForms(normalForms);
            if (stage.startsWith("begin-")) {
                recorder.beginStage("normalize-" + stage.substring("begin-".length()));
            } else {
                result.maxNormalizedNodes = Math.max(result.maxNormalizedNodes, proxy.normalizedNodes);
                result.maxNormalizationProxyBytes = Math.max(result.maxNormalizationProxyBytes, proxy.proxyBytes);
                recorder.endStage("normalize-" + stage, proxy);
            }
        });
        recorder.beginStage("canonical-metadata");
        CanonicalDistance.Prepared prepared = CanonicalDistance.prepare(agent.normalForms());
        recorder.endStage("canonical-metadata", ProxyCounts.normalForms(agent.normalForms()));
        return prepared;
    }

    private static Multigraph graph(DoubleMap<Integer, Multigraph> forest, Integer id) {
        return id == null ? null : forest.get(id);
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
        for (Predicate predicate : model.getPredDeclList()) declarations.put(predicate.getName(), predicate);
        for (Function function : model.getFunDeclList()) declarations.put(function.getName(), function);
        Set<String> selected = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(leftName);
        pending.add(rightName);
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            if (!selected.add(name)) continue;
            PredOrFun declaration = declarations.get(name);
            if (declaration != null) collectCalls(declaration, declarations, selected, pending);
        }
        return selected;
    }

    private static void collectCalls(
            Node node,
            Map<String, PredOrFun> declarations,
            Set<String> selected,
            ArrayDeque<String> pending) {
        if (node instanceof Call) {
            String name = ((Call) node).getName();
            if (declarations.containsKey(name) && !selected.contains(name)) pending.addLast(name);
        }
        for (Node child : DatasetConventions.rawAstChildren(node)) {
            collectCalls(child, declarations, selected, pending);
        }
    }

    private static PredicatePair findPredicatePair(Path file, ModelUnit model) {
        Map<String, Predicate> predicates = new LinkedHashMap<>();
        for (Predicate predicate : model.getPredDeclList()) predicates.put(predicate.getName(), predicate);
        String[] names = DatasetConventions.findPredicatePairNames(preferredPredicateBase(file), predicates);
        return names == null ? null
                : new PredicatePair(names[0], names[1], predicates.get(names[0]), predicates.get(names[1]));
    }

    private static String preferredPredicateBase(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) name = name.substring(0, dot);
        int underscore = name.lastIndexOf('_');
        return underscore < 0 ? null : name.substring(underscore + 1);
    }

    private static int predicateAstSize(Predicate predicate) {
        int size = 1 + astSize(predicate.getBody());
        if (predicate.getParamList() != null) {
            for (Node parameter : predicate.getParamList()) size += astSize(parameter);
        }
        return size;
    }

    private static int astSize(Node node) {
        if (node == null) return 0;
        int size = 1;
        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) size += astSize(child);
        }
        return size;
    }

    private static List<Path> selectFiles(Path root, int limit, long seed) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.walk(root)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".als"))
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .toList();
        }
        files = new ArrayList<>(files);
        Collections.shuffle(files, new java.util.Random(seed));
        if (limit > 0 && files.size() > limit) files = new ArrayList<>(files.subList(0, limit));
        return files;
    }

    private static void writePairs(Path path, List<FileResult> results) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,success,skipped,retried,distance,rawAstNodes,masgVertices,masgEdges,"
                    + "maxNormalizedNodes,maxNormalizationProxyBytes,normalForms,quantifiers,temporalNodes,"
                    + "canonicalSize,eclasses,enodes,metadataEntries,distanceScratchBytes,"
                    + "largestScratchBufferBytes,scratchArrays,scratchMatrices,error\n");
            for (FileResult result : results) {
                csv(writer, result.relativePath);
                writer.write(result.success + "," + result.skipped + "," + result.retried + ","
                        + result.distance + "," + result.rawAstNodes + "," + result.masgVertices + ","
                        + result.masgEdges + "," + result.maxNormalizedNodes + ","
                        + result.maxNormalizationProxyBytes + "," + result.normalForms + ","
                        + result.quantifiers + "," + result.temporalNodes + "," + result.canonicalSize + ","
                        + result.eclasses + "," + result.enodes + "," + result.metadataEntries + ","
                        + result.distanceScratchBytes + "," + result.largestScratchBufferBytes + ","
                        + result.scratchArrays + "," + result.scratchMatrices + ",");
                csvLast(writer, result.error);
                writer.write('\n');
            }
        }
    }

    private static void writeSelection(Path path, Path root, List<Path> files) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("ordinal,relativePath\n");
            for (int i = 0; i < files.size(); i++) {
                writer.write(Integer.toString(i));
                writer.write(',');
                csvLast(writer, root.relativize(files.get(i)).toString().replace('\\', '/'));
                writer.write('\n');
            }
        }
    }

    private static void writePhaseEvents(Path path, Iterable<PhaseEvent> events) throws IOException {
        List<PhaseEvent> sorted = new ArrayList<>();
        for (PhaseEvent event : events) sorted.add(event);
        sorted.sort(Comparator.comparingLong(event -> event.elapsedNanos));
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("elapsedNanos,threadId,stage,durationNanos,heapDeltaBytes,usedHeapBytes,activeWorkers,rawAstNodes,masgVertices,"
                    + "masgEdges,normalForms,quantifiers,temporalNodes,normalizedNodes,proxyBytes\n");
            for (PhaseEvent event : sorted) writer.write(event.csv() + "\n");
        }
    }

    private static void writeHeapSamples(Path path, Iterable<HeapSample> samples) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("elapsedNanos,usedHeapBytes,activeWorkers,phaseCounts\n");
            for (HeapSample sample : samples) {
                writer.write(sample.elapsedNanos + "," + sample.usedHeapBytes + "," + sample.activeWorkers + ",");
                csvLast(writer, sample.phaseCounts);
                writer.write('\n');
            }
        }
    }

    private static void writePhaseSummary(Path path, Iterable<PhaseEvent> events) throws IOException {
        Map<String, PhaseAggregate> phases = new TreeMap<>();
        for (PhaseEvent event : events) phases.computeIfAbsent(event.stage, ignored -> new PhaseAggregate()).add(event);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("stage,samples,averageDurationNanos,totalDurationNanos,averageHeapDeltaBytes,"
                    + "totalPositiveHeapDeltaBytes,maxHeapDeltaBytes,averageUsedHeapBytes,maxUsedHeapBytes,averageActiveWorkers,"
                    + "averageRawAstNodes,averageMasgVertices,averageMasgEdges,averageNormalForms,"
                    + "averageQuantifiers,averageTemporalNodes,averageNormalizedNodes,averageProxyBytes\n");
            for (Map.Entry<String, PhaseAggregate> entry : phases.entrySet()) {
                csv(writer, entry.getKey());
                writer.write(entry.getValue().csv() + "\n");
            }
        }
    }

    private static void writeMetrics(
            Options options,
            RunSummary summary,
            long wallNanos,
            long peakHeapBytes,
            long beforeClear,
            long afterClear,
            long afterGc,
            int retainedFiles,
            int retainedResults,
            String selectionHash) throws IOException {
        JSONObject json = new JSONObject()
                .put("schemaVersion", "candis-canonical-memory-v1")
                .put("generatedAt", Instant.now().toString())
                .put("inputRoot", options.input.toAbsolutePath().normalize().toString())
                .put("workers", options.workers).put("limit", options.limit).put("rngSeed", options.seed)
                .put("selectionSha256", selectionHash).put("selected", summary.selected)
                .put("successful", summary.successes).put("skippedAstIdentical", summary.skipped)
                .put("errors", summary.errors).put("serialRetries", summary.retries)
                .put("wallNanos", wallNanos).put("peakUsedHeapBytes", peakHeapBytes)
                .put("heapBeforeBookkeepingClearBytes", beforeClear)
                .put("heapAfterBookkeepingClearBeforeGcBytes", afterClear)
                .put("diagnosticGcEnabled", options.postRunGc)
                .put("heapAfterDiagnosticGcBytes", afterGc)
                .put("retainedFileRecordsBeforeClear", retainedFiles)
                .put("retainedPrimitiveResultsBeforeClear", retainedResults)
                .put("totalRawAstNodes", summary.rawAstNodes).put("totalMasgVertices", summary.masgVertices)
                .put("totalMasgEdges", summary.masgEdges).put("totalMaxNormalizedNodes", summary.normalizedNodes)
                .put("totalCanonicalSize", summary.canonicalSize).put("totalEclasses", summary.eclasses)
                .put("totalEnodes", summary.enodes).put("totalMetadataEntries", summary.metadataEntries)
                .put("totalDistanceScratchBytesAllocated", summary.scratchBytes)
                .put("largestDistanceScratchBufferBytes", summary.largestScratch)
                .put("totalScratchArrays", summary.scratchArrays)
                .put("totalScratchMatrices", summary.scratchMatrices);
        Files.writeString(options.output.resolve("metrics.json"), json.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void report(Options options) throws IOException {
        List<ReportRun> runs = new ArrayList<>();
        for (int worker : options.reportWorkers) {
            Path directory = options.output.resolve("workers-" + worker);
            runs.add(ReportRun.read(worker, directory));
        }
        int mismatches = compareOutputs(runs);
        writeCombinedPhaseCsv(options.output.resolve("phase_summary.csv"), runs);
        writeReportJson(options.output.resolve("results.json"), runs, mismatches);
        writeReportMarkdown(options, runs, mismatches);
        System.out.println("Wrote " + options.output.resolve("REPORT.md"));
    }

    private static int compareOutputs(List<ReportRun> runs) throws IOException {
        if (runs.isEmpty()) return 0;
        Map<String, String> baseline = readDistances(runs.get(0).directory.resolve("pairs.csv"));
        int mismatches = 0;
        for (int i = 1; i < runs.size(); i++) {
            Map<String, String> candidate = readDistances(runs.get(i).directory.resolve("pairs.csv"));
            Set<String> keys = new TreeSet<>(baseline.keySet());
            keys.addAll(candidate.keySet());
            for (String key : keys) {
                if (!java.util.Objects.equals(baseline.get(key), candidate.get(key))) mismatches++;
            }
        }
        return mismatches;
    }

    private static Map<String, String> readDistances(Path path) throws IOException {
        List<Map<String, String>> rows = CapabilityCsv.read(path);
        Map<String, String> distances = new TreeMap<>();
        for (Map<String, String> row : rows) {
            distances.put(row.get("relativePath"), row.get("success") + ":" + row.get("skipped")
                    + ":" + row.get("distance") + ":" + row.get("error"));
        }
        return distances;
    }

    private static void writeCombinedPhaseCsv(Path path, List<ReportRun> runs) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("workers,stage,samples,averageDurationNanos,totalDurationNanos,averageHeapDeltaBytes,"
                    + "totalPositiveHeapDeltaBytes,maxHeapDeltaBytes,averageUsedHeapBytes,maxUsedHeapBytes,averageActiveWorkers,"
                    + "averageRawAstNodes,averageMasgVertices,averageMasgEdges,averageNormalForms,"
                    + "averageQuantifiers,averageTemporalNodes,averageNormalizedNodes,averageProxyBytes\n");
            for (ReportRun run : runs) {
                for (Map<String, String> row : CapabilityCsv.read(run.directory.resolve("phase_summary.csv"))) {
                    writer.write(run.workers + ",");
                    for (int i = 0; i < CapabilityCsv.PHASE_COLUMNS.size(); i++) {
                        String column = CapabilityCsv.PHASE_COLUMNS.get(i);
                        if (i + 1 == CapabilityCsv.PHASE_COLUMNS.size()) csvLast(writer, row.get(column));
                        else csv(writer, row.get(column));
                    }
                    writer.write('\n');
                }
            }
        }
    }

    private static void writeReportJson(Path path, List<ReportRun> runs, int mismatches) throws IOException {
        JSONArray array = new JSONArray();
        for (ReportRun run : runs) array.put(run.toJson());
        JSONObject json = new JSONObject().put("schemaVersion", "candis-canonical-memory-report-v1")
                .put("generatedAt", Instant.now().toString()).put("runs", array)
                .put("crossWorkerOutputMismatches", mismatches);
        Files.writeString(path, json.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeReportMarkdown(Options options, List<ReportRun> runs, int mismatches) throws IOException {
        StringBuilder md = new StringBuilder("# Canonical Memory Attribution\n\n");
        md.append("- Deterministic seed: `").append(options.seed).append("`\n")
                .append("- Requested sample size: ").append(options.limit).append(" files\n")
                .append("- Normal-path explicit GC: none\n")
                .append("- Separate post-run diagnostic GC: enabled\n")
                .append("- Cross-worker output mismatches: ").append(mismatches).append("\n\n");
        md.append("The phase hooks are opt-in and no-op in production. Heap is process-global, so phase-boundary "
                + "samples under concurrency are attribution evidence rather than per-object retained-size measurements.\n\n");
        md.append("## Worker Scaling\n\n")
                .append("| Workers | Successful / selected | Wall s | Throughput | Process CPU s | Max RSS MiB | Peak heap MiB | Before clear MiB | After clear MiB | Post-GC MiB |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ReportRun run : runs) {
            md.append("| ").append(run.workers).append(" | ").append(run.successful).append(" / ")
                    .append(run.selected).append(" | ").append(decimal(run.wallSeconds())).append(" | ")
                    .append(decimal(run.throughput())).append(" | ").append(decimal(run.processCpuSeconds))
                    .append(" | ").append(decimal(mib(run.maxRssBytes))).append(" | ")
                    .append(decimal(mib(run.peakHeapBytes))).append(" | ")
                    .append(decimal(mib(run.beforeClearBytes))).append(" | ")
                    .append(decimal(mib(run.afterClearBytes))).append(" | ")
                    .append(decimal(mib(run.afterGcBytes))).append(" |\n");
        }
        md.append("\n## Structural And Scratch Proxies\n\n")
                .append("| Workers | AST nodes | MASG vertices / edges | Max normalized nodes | Canonical size | Eclasses / enodes | Metadata entries | Scratch allocated MiB | Largest scratch KiB |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (ReportRun run : runs) {
            md.append("| ").append(run.workers).append(" | ").append(run.rawAstNodes).append(" | ")
                    .append(run.masgVertices).append(" / ").append(run.masgEdges).append(" | ")
                    .append(run.normalizedNodes).append(" | ").append(run.canonicalSize).append(" | ")
                    .append(run.eclasses).append(" / ").append(run.enodes).append(" | ")
                    .append(run.metadataEntries).append(" | ").append(decimal(mib(run.scratchBytes)))
                    .append(" | ").append(decimal(run.largestScratch / 1024.0)).append(" |\n");
        }
        if (!runs.isEmpty()) {
            List<Map<String, String>> phases = CapabilityCsv.read(
                    runs.get(0).directory.resolve("phase_summary.csv"));
            phases.sort(Comparator.comparingDouble((Map<String, String> row) ->
                    decimalValue(row.get("totalDurationNanos"))).reversed());
            md.append("\n## One-Worker Phase Attribution\n\n")
                    .append("The one-worker run gives the least-confounded phase deltas. Positive heap delta is allocation pressure observed between paired begin/end hooks; objects can die before collection.\n\n")
                    .append("| Phase | Samples | Total phase s | Avg phase ms | Positive heap delta MiB | Max single delta MiB |\n")
                    .append("| --- | ---: | ---: | ---: | ---: | ---: |\n");
            for (Map<String, String> phase : phases) {
                md.append("| ").append(phase.get("stage")).append(" | ").append(phase.get("samples"))
                        .append(" | ").append(decimal(decimalValue(phase.get("totalDurationNanos")) / 1e9))
                        .append(" | ").append(decimal(decimalValue(phase.get("averageDurationNanos")) / 1e6))
                        .append(" | ").append(decimal(decimalValue(phase.get("totalPositiveHeapDeltaBytes")) / (1024.0 * 1024.0)))
                        .append(" | ").append(decimal(decimalValue(phase.get("maxHeapDeltaBytes")) / (1024.0 * 1024.0)))
                        .append(" |\n");
            }
        }
        md.append("\n## Diagnosis\n\n");
        if (runs.size() >= 2) {
            ReportRun first = runs.get(0);
            ReportRun last = runs.get(runs.size() - 1);
            double heapScale = ratio(last.peakHeapBytes, first.peakHeapBytes);
            double postGcFraction = ratio(last.afterGcBytes, last.peakHeapBytes);
            md.append("- Peak used heap scales by ").append(decimal(heapScale)).append("x from ")
                    .append(first.workers).append(" to ").append(last.workers)
                    .append(" workers. This is the signature of overlapping parser/MASG/normalization working sets")
                    .append(heapScale > 1.5 ? ", not a representation-size increase." : ".")
                    .append("\n");
            md.append("- After the timed work and diagnostic GC, retained heap is ")
                    .append(decimal(postGcFraction * 100.0)).append("% of peak. ")
                    .append(postGcFraction < 0.5
                            ? "The peak is predominantly transient allocation/retention until collection."
                            : "A substantial live set remains and warrants object-level profiling.")
                    .append("\n");
            md.append("- Post-GC heap is ").append(decimal(mib(first.afterGcBytes))).append(" MiB at ")
                    .append(first.workers).append(" worker and ").append(decimal(mib(last.afterGcBytes)))
                    .append(" MiB at ").append(last.workers)
                    .append(" workers. Its worker independence points to JVM/parser-wide state rather than retained worker-local canonical forms.\n");
            md.append("- The largest tracked edit-distance scratch buffer is ")
                    .append(decimal(last.largestScratch / 1024.0)).append(" KiB; cumulative scratch allocation is ")
                    .append(decimal(mib(last.scratchBytes))).append(" MiB. This separates temporary DP churn from live canonical graph size.\n");
        }
        md.append("- Pair results are primitive records and are cleared before diagnostic GC; all worker settings use the same selection hash.\n")
                .append("- Cross-worker semantic-output mismatches: ").append(mismatches).append(".\n");

        Path production = options.production;
        if (Files.isRegularFile(production.resolve("metrics.properties"))) {
            Properties properties = new Properties();
            try (java.io.Reader reader = Files.newBufferedReader(production.resolve("metrics.properties"), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            long productionHeap = longValue(properties.getProperty("peakUsedHeapBytes"));
            TimeMetrics time = TimeMetrics.read(production.resolve("process.time"));
            md.append("\n## Full-Corpus Context\n\nThe current production canonical arm reports ")
                    .append(decimal(mib(productionHeap))).append(" MiB sampled peak heap and ")
                    .append(decimal(mib(time.maxRssBytes))).append(" MiB maximum RSS. The deterministic subset above isolates how much of that peak scales with concurrent working sets.\n");
        }

        md.append("\n## Reproduce\n\n```bash\n./scripts/run_canonical_memory_attribution.sh --input classified-data --output canonical_memory --limit ")
                .append(options.limit).append(" --seed ").append(options.seed).append("\n```\n");
        Files.writeString(options.output.resolve("REPORT.md"), md.toString(), StandardCharsets.UTF_8);
    }

    private static String filesHash(Path root, List<Path> files) {
        StringBuilder value = new StringBuilder();
        for (Path file : files) value.append(root.relativize(file).toString().replace('\\', '/')).append('\n');
        return sha256(value.toString());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long usedHeap() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    private static String error(Throwable throwable) {
        return throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
    }

    private static void csv(Writer writer, String value) throws IOException {
        csvValue(writer, value);
        writer.write(',');
    }

    private static void csvLast(Writer writer, String value) throws IOException {
        csvValue(writer, value);
    }

    private static void csvValue(Writer writer, String value) throws IOException {
        writer.write('"');
        writer.write((value == null ? "" : value).replace("\"", "\"\""));
        writer.write('"');
    }

    private static long longValue(String value) {
        try { return Long.parseLong(value); } catch (RuntimeException ignored) { return 0L; }
    }

    private static double decimalValue(String value) {
        try { return Double.parseDouble(value); } catch (RuntimeException ignored) { return 0.0; }
    }

    private static double ratio(long numerator, long denominator) {
        return denominator <= 0 ? 0.0 : (double) numerator / denominator;
    }

    private static double mib(long bytes) {
        return bytes < 0 ? -1.0 : bytes / (1024.0 * 1024.0);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static final class RunState {
        private final List<Path> files;
        private final List<FileResult> results;
        private final int retries;

        private RunState(List<Path> files, List<FileResult> results, int retries) {
            this.files = files;
            this.results = results;
            this.retries = retries;
        }
    }

    private static final class PredicatePair {
        private final String leftName;
        private final String rightName;
        private final Predicate left;
        private final Predicate right;

        private PredicatePair(String leftName, String rightName, Predicate left, Predicate right) {
            this.leftName = leftName;
            this.rightName = rightName;
            this.left = left;
            this.right = right;
        }
    }

    private static final class FileResult {
        private final String relativePath;
        private boolean success;
        private boolean skipped;
        private boolean retried;
        private int distance = -1;
        private int rawAstNodes;
        private int masgVertices;
        private int masgEdges;
        private int maxNormalizedNodes;
        private long maxNormalizationProxyBytes;
        private int normalForms;
        private int quantifiers;
        private int temporalNodes;
        private int canonicalSize;
        private long eclasses;
        private long enodes;
        private int metadataEntries;
        private long distanceScratchBytes;
        private long largestScratchBufferBytes;
        private long scratchArrays;
        private long scratchMatrices;
        private String error = "";

        private FileResult(Path root, Path file) {
            relativePath = root.relativize(file).toString().replace('\\', '/');
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

    private static final class RunSummary {
        private final int selected;
        private final int retries;
        private int successes;
        private int skipped;
        private int errors;
        private long rawAstNodes;
        private long masgVertices;
        private long masgEdges;
        private long normalizedNodes;
        private long canonicalSize;
        private long eclasses;
        private long enodes;
        private long metadataEntries;
        private long scratchBytes;
        private long largestScratch;
        private long scratchArrays;
        private long scratchMatrices;

        private RunSummary(List<FileResult> results, int retries) {
            selected = results.size();
            this.retries = retries;
            for (FileResult result : results) {
                if (result.success) successes++;
                else if (result.skipped) skipped++;
                else errors++;
                rawAstNodes += result.rawAstNodes;
                masgVertices += result.masgVertices;
                masgEdges += result.masgEdges;
                normalizedNodes += result.maxNormalizedNodes;
                canonicalSize += result.canonicalSize;
                eclasses += result.eclasses;
                enodes += result.enodes;
                metadataEntries += result.metadataEntries;
                scratchBytes += result.distanceScratchBytes;
                largestScratch = Math.max(largestScratch, result.largestScratchBufferBytes);
                scratchArrays += result.scratchArrays;
                scratchMatrices += result.scratchMatrices;
            }
        }
    }

    private static final class ProxyCounts {
        private int rawAstNodes;
        private int masgVertices;
        private int masgEdges;
        private int normalForms;
        private int quantifiers;
        private int temporalNodes;
        private int normalizedNodes;
        private long proxyBytes;

        private static ProxyCounts rawAst(int nodes) {
            ProxyCounts counts = new ProxyCounts();
            counts.rawAstNodes = nodes;
            counts.proxyBytes = nodes * 96L;
            return counts;
        }

        private static ProxyCounts masg(int vertices, int edges) {
            ProxyCounts counts = new ProxyCounts();
            counts.masgVertices = vertices;
            counts.masgEdges = edges;
            counts.proxyBytes = vertices * 144L + edges * 56L;
            return counts;
        }

        private static ProxyCounts normalForms(List<NormalForm> forms) {
            ProxyCounts counts = new ProxyCounts();
            counts.normalForms = forms.size();
            IdentityHashMap<EGraphNode, Boolean> seen = new IdentityHashMap<>();
            for (NormalForm form : forms) {
                counts.quantifiers += form.getParams().size() + form.getMatrixQuantiVars().size()
                        + form.getInheritedQuantiVars().size();
                countNodes(form.getMatrixEGraph(), seen);
            }
            counts.temporalNodes = forms.size();
            counts.normalizedNodes = seen.size();
            counts.proxyBytes = forms.size() * 128L + counts.quantifiers * 112L + seen.size() * 112L;
            return counts;
        }

        private static void countNodes(EGraphNode node, IdentityHashMap<EGraphNode, Boolean> seen) {
            if (node == null || seen.put(node, Boolean.TRUE) != null) return;
            for (EGraphNode child : node.getChildren()) countNodes(child, seen);
        }

        private static ProxyCounts canonical(FileResult result) {
            ProxyCounts counts = new ProxyCounts();
            counts.normalForms = result.normalForms;
            counts.quantifiers = result.quantifiers;
            counts.temporalNodes = result.temporalNodes;
            counts.normalizedNodes = result.metadataEntries;
            counts.proxyBytes = result.canonicalSize * 64L + result.metadataEntries * 48L
                    + result.eclasses * 32L + result.enodes * 48L;
            return counts;
        }

        private static ProxyCounts distance(FileResult result) {
            ProxyCounts counts = canonical(result);
            counts.proxyBytes += result.largestScratchBufferBytes;
            return counts;
        }

        private static ProxyCounts result() {
            ProxyCounts counts = new ProxyCounts();
            counts.proxyBytes = 256L;
            return counts;
        }
    }

    private static final class PhaseRecorder {
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicLong peak = new AtomicLong();
        private final ConcurrentHashMap<Long, String> activePhases = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<Long, StageStart> stageStarts = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<PhaseEvent> events = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<HeapSample> samples = new ConcurrentLinkedQueue<>();
        private long started;
        private Thread sampler;

        private void start() {
            started = System.nanoTime();
            running.set(true);
            sampler = new Thread(() -> {
                while (running.get()) {
                    sample();
                    try {
                        Thread.sleep(10L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                sample();
            }, "canonical-memory-sampler");
            sampler.setDaemon(true);
            sampler.start();
        }

        private void beginStage(String phase) {
            long thread = Thread.currentThread().getId();
            long now = System.nanoTime();
            long heap = usedHeap();
            activePhases.put(thread, phase);
            stageStarts.put(thread, new StageStart(phase, now, heap));
        }

        private void clearPhase() {
            activePhases.remove(Thread.currentThread().getId());
            stageStarts.remove(Thread.currentThread().getId());
        }

        private void boundary(String stage, ProxyCounts proxy) {
            long heap = usedHeap();
            peak.accumulateAndGet(heap, Math::max);
            events.add(new PhaseEvent(System.nanoTime() - started, Thread.currentThread().getId(), stage,
                    0L, 0L, heap, activePhases.size(), proxy));
        }

        private void endStage(String stage, ProxyCounts proxy) {
            long now = System.nanoTime();
            long heap = usedHeap();
            long thread = Thread.currentThread().getId();
            StageStart start = stageStarts.remove(thread);
            long duration = start == null ? 0L : Math.max(0L, now - start.startedNanos);
            long heapDelta = start == null ? 0L : heap - start.usedHeapBytes;
            peak.accumulateAndGet(heap, Math::max);
            events.add(new PhaseEvent(now - started, thread, stage, duration, heapDelta,
                    heap, activePhases.size(), proxy));
            activePhases.remove(thread);
        }

        private void sample() {
            long heap = usedHeap();
            peak.accumulateAndGet(heap, Math::max);
            Map<String, Integer> counts = new TreeMap<>();
            for (String phase : activePhases.values()) counts.put(phase, counts.getOrDefault(phase, 0) + 1);
            StringBuilder encoded = new StringBuilder();
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (encoded.length() > 0) encoded.append(';');
                encoded.append(entry.getKey()).append('=').append(entry.getValue());
            }
            samples.add(new HeapSample(System.nanoTime() - started, heap, activePhases.size(), encoded.toString()));
        }

        private long stop() {
            running.set(false);
            try {
                sampler.join();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            return peak.get();
        }

        private void releaseEvents() {
            events.clear();
            samples.clear();
            activePhases.clear();
            stageStarts.clear();
        }
    }

    private static final class StageStart {
        private final String stage;
        private final long startedNanos;
        private final long usedHeapBytes;

        private StageStart(String stage, long startedNanos, long usedHeapBytes) {
            this.stage = stage;
            this.startedNanos = startedNanos;
            this.usedHeapBytes = usedHeapBytes;
        }
    }

    private static final class PhaseEvent {
        private final long elapsedNanos;
        private final long threadId;
        private final String stage;
        private final long durationNanos;
        private final long heapDeltaBytes;
        private final long usedHeapBytes;
        private final int activeWorkers;
        private final ProxyCounts proxy;

        private PhaseEvent(long elapsedNanos, long threadId, String stage, long durationNanos,
                long heapDeltaBytes, long usedHeapBytes,
                int activeWorkers, ProxyCounts proxy) {
            this.elapsedNanos = elapsedNanos;
            this.threadId = threadId;
            this.stage = stage;
            this.durationNanos = durationNanos;
            this.heapDeltaBytes = heapDeltaBytes;
            this.usedHeapBytes = usedHeapBytes;
            this.activeWorkers = activeWorkers;
            this.proxy = proxy;
        }

        private String csv() {
            return elapsedNanos + "," + threadId + ",\"" + stage.replace("\"", "\"\"") + "\","
                    + durationNanos + "," + heapDeltaBytes + "," + usedHeapBytes + "," + activeWorkers + "," + proxy.rawAstNodes + ","
                    + proxy.masgVertices + "," + proxy.masgEdges + "," + proxy.normalForms + ","
                    + proxy.quantifiers + "," + proxy.temporalNodes + "," + proxy.normalizedNodes + ","
                    + proxy.proxyBytes;
        }
    }

    private static final class HeapSample {
        private final long elapsedNanos;
        private final long usedHeapBytes;
        private final int activeWorkers;
        private final String phaseCounts;

        private HeapSample(long elapsedNanos, long usedHeapBytes, int activeWorkers, String phaseCounts) {
            this.elapsedNanos = elapsedNanos;
            this.usedHeapBytes = usedHeapBytes;
            this.activeWorkers = activeWorkers;
            this.phaseCounts = phaseCounts;
        }
    }

    private static final class PhaseAggregate {
        private long samples;
        private long durationNanos;
        private long heapDeltaBytes;
        private long positiveHeapDeltaBytes;
        private long maxHeapDeltaBytes = Long.MIN_VALUE;
        private long heap;
        private long maxHeap;
        private long workers;
        private long rawAst;
        private long vertices;
        private long edges;
        private long forms;
        private long quantifiers;
        private long temporal;
        private long normalized;
        private long proxyBytes;

        private void add(PhaseEvent event) {
            samples++;
            durationNanos += event.durationNanos;
            heapDeltaBytes += event.heapDeltaBytes;
            positiveHeapDeltaBytes += Math.max(0L, event.heapDeltaBytes);
            maxHeapDeltaBytes = Math.max(maxHeapDeltaBytes, event.heapDeltaBytes);
            heap += event.usedHeapBytes;
            maxHeap = Math.max(maxHeap, event.usedHeapBytes);
            workers += event.activeWorkers;
            rawAst += event.proxy.rawAstNodes;
            vertices += event.proxy.masgVertices;
            edges += event.proxy.masgEdges;
            forms += event.proxy.normalForms;
            quantifiers += event.proxy.quantifiers;
            temporal += event.proxy.temporalNodes;
            normalized += event.proxy.normalizedNodes;
            proxyBytes += event.proxy.proxyBytes;
        }

        private String csv() {
            return samples + "," + average(durationNanos) + "," + durationNanos + ","
                    + average(heapDeltaBytes) + "," + positiveHeapDeltaBytes + ","
                    + (maxHeapDeltaBytes == Long.MIN_VALUE ? 0L : maxHeapDeltaBytes) + ","
                    + average(heap) + "," + maxHeap + "," + average(workers) + ","
                    + average(rawAst) + "," + average(vertices) + "," + average(edges) + ","
                    + average(forms) + "," + average(quantifiers) + "," + average(temporal) + ","
                    + average(normalized) + "," + average(proxyBytes);
        }

        private String average(long value) {
            return String.format(Locale.ROOT, "%.3f", samples == 0 ? 0.0 : (double) value / samples);
        }
    }

    private static final class ReportRun {
        private final int workers;
        private final Path directory;
        private final int selected;
        private final int successful;
        private final long wallNanos;
        private final long peakHeapBytes;
        private final long beforeClearBytes;
        private final long afterClearBytes;
        private final long afterGcBytes;
        private final long rawAstNodes;
        private final long masgVertices;
        private final long masgEdges;
        private final long normalizedNodes;
        private final long canonicalSize;
        private final long eclasses;
        private final long enodes;
        private final long metadataEntries;
        private final long scratchBytes;
        private final long largestScratch;
        private final long maxRssBytes;
        private final double processCpuSeconds;

        private ReportRun(int workers, Path directory, JSONObject json, TimeMetrics time) {
            this.workers = workers;
            this.directory = directory;
            selected = json.getInt("selected");
            successful = json.getInt("successful");
            wallNanos = json.getLong("wallNanos");
            peakHeapBytes = json.getLong("peakUsedHeapBytes");
            beforeClearBytes = json.getLong("heapBeforeBookkeepingClearBytes");
            afterClearBytes = json.getLong("heapAfterBookkeepingClearBeforeGcBytes");
            afterGcBytes = json.getLong("heapAfterDiagnosticGcBytes");
            rawAstNodes = json.getLong("totalRawAstNodes");
            masgVertices = json.getLong("totalMasgVertices");
            masgEdges = json.getLong("totalMasgEdges");
            normalizedNodes = json.getLong("totalMaxNormalizedNodes");
            canonicalSize = json.getLong("totalCanonicalSize");
            eclasses = json.getLong("totalEclasses");
            enodes = json.getLong("totalEnodes");
            metadataEntries = json.getLong("totalMetadataEntries");
            scratchBytes = json.getLong("totalDistanceScratchBytesAllocated");
            largestScratch = json.getLong("largestDistanceScratchBufferBytes");
            maxRssBytes = time.maxRssBytes;
            processCpuSeconds = time.cpuSeconds;
        }

        private static ReportRun read(int workers, Path directory) throws IOException {
            JSONObject json = new JSONObject(Files.readString(directory.resolve("metrics.json"), StandardCharsets.UTF_8));
            return new ReportRun(workers, directory, json, TimeMetrics.read(directory.resolve("process.time")));
        }

        private double wallSeconds() { return wallNanos / 1e9; }
        private double throughput() { return wallNanos <= 0 ? 0.0 : successful / wallSeconds(); }

        private JSONObject toJson() {
            return new JSONObject().put("workers", workers).put("selected", selected).put("successful", successful)
                    .put("wallNanos", wallNanos).put("processCpuSeconds", processCpuSeconds)
                    .put("maxRssBytes", maxRssBytes).put("peakUsedHeapBytes", peakHeapBytes)
                    .put("heapBeforeClearBytes", beforeClearBytes).put("heapAfterClearBytes", afterClearBytes)
                    .put("heapAfterDiagnosticGcBytes", afterGcBytes).put("totalRawAstNodes", rawAstNodes)
                    .put("totalMasgVertices", masgVertices).put("totalMasgEdges", masgEdges)
                    .put("totalMaxNormalizedNodes", normalizedNodes).put("totalCanonicalSize", canonicalSize)
                    .put("totalEclasses", eclasses).put("totalEnodes", enodes)
                    .put("totalMetadataEntries", metadataEntries).put("totalScratchBytes", scratchBytes)
                    .put("largestScratchBufferBytes", largestScratch);
        }
    }

    private static final class TimeMetrics {
        private long maxRssBytes;
        private double cpuSeconds;

        private static TimeMetrics read(Path path) throws IOException {
            TimeMetrics metrics = new TimeMetrics();
            if (!Files.isRegularFile(path)) return metrics;
            double user = 0.0;
            double system = 0.0;
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("Maximum resident set size")) {
                    metrics.maxRssBytes = longValue(afterColon(trimmed)) * 1024L;
                } else if (trimmed.startsWith("User time")) {
                    user = decimalValue(afterColon(trimmed));
                } else if (trimmed.startsWith("System time")) {
                    system = decimalValue(afterColon(trimmed));
                }
            }
            metrics.cpuSeconds = user + system;
            return metrics;
        }

        private static String afterColon(String line) {
            int colon = line.lastIndexOf(':');
            return colon < 0 ? "" : line.substring(colon + 1).trim();
        }
    }

    private static final class CapabilityCsv {
        private static final List<String> PHASE_COLUMNS = List.of(
                "stage", "samples", "averageDurationNanos", "totalDurationNanos", "averageHeapDeltaBytes",
                "totalPositiveHeapDeltaBytes", "maxHeapDeltaBytes", "averageUsedHeapBytes", "maxUsedHeapBytes", "averageActiveWorkers",
                "averageRawAstNodes", "averageMasgVertices", "averageMasgEdges", "averageNormalForms",
                "averageQuantifiers", "averageTemporalNodes", "averageNormalizedNodes", "averageProxyBytes");

        private static List<Map<String, String>> read(Path path) throws IOException {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            if (lines.isEmpty()) return Collections.emptyList();
            List<String> header = parse(lines.get(0));
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.size(); i++) {
                if (lines.get(i).isEmpty()) continue;
                List<String> fields = parse(lines.get(i));
                Map<String, String> row = new LinkedHashMap<>();
                for (int j = 0; j < header.size(); j++) row.put(header.get(j), j < fields.size() ? fields.get(j) : "");
                rows.add(row);
            }
            return rows;
        }

        private static List<String> parse(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char character = line.charAt(i);
                if (quoted) {
                    if (character == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (character == '"') quoted = false;
                    else field.append(character);
                } else if (character == '"') quoted = true;
                else if (character == ',') { fields.add(field.toString()); field.setLength(0); }
                else field.append(character);
            }
            fields.add(field.toString());
            return fields;
        }
    }

    private static final class Options {
        private boolean report;
        private Path input = Paths.get("classified-data");
        private Path output = Paths.get("canonical_memory/workers-1");
        private Path production = Paths.get("egraph_ablation/canonical");
        private int workers = 1;
        private int limit = DEFAULT_LIMIT;
        private long seed = DEFAULT_SEED;
        private boolean postRunGc;
        private List<Integer> reportWorkers = List.of(1, 8, 32);

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--run": options.report = false; break;
                    case "--report": options.report = true; break;
                    case "--input": options.input = Paths.get(args[++i]); break;
                    case "--output": options.output = Paths.get(args[++i]); break;
                    case "--production": options.production = Paths.get(args[++i]); break;
                    case "--workers": options.workers = Integer.parseInt(args[++i]); break;
                    case "--report-workers": options.reportWorkers = parseWorkers(args[++i]); break;
                    case "--limit": options.limit = Integer.parseInt(args[++i]); break;
                    case "--seed": options.seed = Long.parseLong(args[++i]); break;
                    case "--post-run-gc": options.postRunGc = true; break;
                    default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (options.workers < 1) throw new IllegalArgumentException("--workers must be positive");
            if (options.limit < 1) throw new IllegalArgumentException("--limit must be positive");
            return options;
        }

        private static List<Integer> parseWorkers(String value) {
            List<Integer> workers = new ArrayList<>();
            for (String item : value.split(",")) workers.add(Integer.parseInt(item.trim()));
            return workers;
        }
    }
}
