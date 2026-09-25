package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import edu.mit.csail.sdg.parser.CompModule;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.learn.Rewarder;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.util.InstancePool;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Node;
import parser.ast.nodes.Call;
import parser.ast.nodes.Function;
import parser.ast.nodes.PredOrFun;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;
import parser.etc.Pair;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.theory.BoundedFiniteUnfoldingOracle;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.ProductionGraphCanonicalizer;

public class CanonicalBatchTest {
    private static final String DEFAULT_INPUT = "classified-data";
    private static final String DEFAULT_OUTPUT = "distance_results";
    private static final int DEFAULT_REWARD_POOL_SIZE = 100;
    private static final int DEFAULT_THREAD_COUNT = 32;

    public static void main(String[] args) throws IOException {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        Options options = Options.parse(args);
        Files.createDirectories(options.outputDir);
        Path jsonPath = options.outputDir.resolve("distances.json");
        Path markdownPath = options.outputDir.resolve("summary.md");

        List<Path> files = alloyFiles(options.inputDir);
        if (options.limit > 0 && options.limit < files.size()) {
            files = new ArrayList<>(files.subList(0, options.limit));
        }

        Summary summary = new Summary(!options.skipRewards);
        try (Writer json = Files.newBufferedWriter(jsonPath, StandardCharsets.UTF_8)) {
            writeJsonHeader(json, options, files.size());
            int[] written = {0};
            processFiles(files, options, result -> {
                summary.add(result);
                if (result.skipped) {
                    return;
                }
                if (written[0]++ > 0) {
                    json.write(",\n");
                }
                writeJsonResult(json, result);
                if (options.verbose) {
                    System.err.println(result.relativePath + " -> " + result.status());
                }
            });
            writeJsonFooter(json, summary);
        }
        writeMarkdown(markdownPath, options, summary);
        System.out.println("Wrote " + jsonPath);
        System.out.println("Wrote " + markdownPath);
    }

    private static void processFiles(
            List<Path> files,
            Options options,
            ResultSink sink) throws IOException {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream discarded = null;
        if (!options.verbose) {
            discarded = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });
            System.setOut(discarded);
            System.setErr(discarded);
        }
        int workers = Math.max(1, options.threadCount);
        int maximumInFlight = workers > Integer.MAX_VALUE / 4
                ? Integer.MAX_VALUE
                : workers * 4;
        int memoryIntensiveWorkers = ExperimentMemoryBudget.effectiveWorkers(workers);
        Semaphore memoryIntensivePermits = new Semaphore(memoryIntensiveWorkers, true);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            CompletionService<IndexedResult> completions =
                    new ExecutorCompletionService<>(executor);
            Map<Future<IndexedResult>, Integer> active = new HashMap<>();
            Map<Integer, FileResult> ready = new TreeMap<>();
            int submitted = 0;
            int completed = 0;
            int emitted = 0;
            long started = System.nanoTime();
            long lastProgress = started;
            int progressStep = Math.max(
                    1,
                    Math.min(1000, Math.max(1, (files.size() + 19) / 20)));
            originalErr.println("CanonicalBatchTest: processing " + files.size()
                    + " files with " + workers + " workers and at most "
                    + maximumInFlight + " tasks in flight; memory-intensive concurrency "
                    + memoryIntensiveWorkers + ".");
            while (submitted < files.size() && submitted - emitted < maximumInFlight) {
                int index = submitted++;
                Future<IndexedResult> future = completions.submit(
                        () -> processIndexedFile(
                                index, files.get(index), options, memoryIntensivePermits));
                active.put(future, index);
            }

            while (completed < files.size()) {
                Future<IndexedResult> future;
                try {
                    future = completions.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Canonical batch processing was interrupted", e);
                }
                if (future == null) {
                    String unresolved = emitted < files.size()
                            ? options.inputDir.relativize(files.get(emitted)).toString()
                            : "<none>";
                    originalErr.println("CanonicalBatchTest: still working; " + completed
                            + "/" + files.size() + " complete and " + active.size()
                            + " tasks in flight; earliest unresolved item is "
                            + (emitted + 1) + "/" + files.size() + " (" + unresolved + ").");
                    lastProgress = System.nanoTime();
                    continue;
                }
                Integer expectedIndex = active.remove(future);
                IndexedResult indexed;
                try {
                    indexed = future.get();
                    if (expectedIndex != null && expectedIndex.intValue() != indexed.index) {
                        throw new IllegalStateException("Completion index mismatch");
                    }
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    if (cause instanceof VirtualMachineError) {
                        throw (VirtualMachineError) cause;
                    }
                    int index = expectedIndex == null ? completed : expectedIndex;
                    indexed = new IndexedResult(
                            index,
                            failedResult(options.inputDir, files.get(index),
                                    cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Canonical batch result retrieval was interrupted", e);
                }
                ready.put(indexed.index, indexed.result);
                completed++;

                while (ready.containsKey(emitted)) {
                    sink.accept(ready.remove(emitted));
                    emitted++;
                }
                long now = System.nanoTime();
                if (completed % progressStep == 0
                        || completed == files.size()
                        || now - lastProgress >= TimeUnit.SECONDS.toNanos(30)) {
                    long elapsedNanos = Math.max(1L, now - started);
                    double seconds = elapsedNanos / 1_000_000_000.0;
                    double rate = completed / seconds;
                    long remainingSeconds = rate <= 0.0
                            ? 0L
                            : Math.round((files.size() - completed) / rate);
                    originalErr.printf(
                            java.util.Locale.ROOT,
                            "CanonicalBatchTest: %,d/%,d complete (%.1f%%), %.2f files/s, ETA %s.%n",
                            completed,
                            files.size(),
                            files.isEmpty() ? 100.0 : 100.0 * completed / files.size(),
                            rate,
                            formatDuration(remainingSeconds));
                    lastProgress = now;
                }

                while (submitted < files.size() && submitted - emitted < maximumInFlight) {
                    int index = submitted++;
                    Future<IndexedResult> next = completions.submit(
                            () -> processIndexedFile(
                                    index, files.get(index), options, memoryIntensivePermits));
                    active.put(next, index);
                }
            }
        } finally {
            executor.shutdownNow();
            if (!options.verbose) {
                System.setOut(originalOut);
                System.setErr(originalErr);
                discarded.close();
            }
        }
    }

    private static IndexedResult processIndexedFile(
            int index,
            Path file,
            Options options,
            Semaphore memoryIntensivePermits) {
        FileResult result = processFile(options.inputDir, file, options, memoryIntensivePermits);
        if (result.error != null) {
            result = processFile(options.inputDir, file, options, memoryIntensivePermits);
        }
        return new IndexedResult(index, result);
    }

    private static void acquireMemoryIntensivePermit(Semaphore permits) {
        try {
            permits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Canonical batch memory permit was interrupted", exception);
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long remainder = seconds % 60;
        return hours > 0
                ? String.format(java.util.Locale.ROOT, "%dh %02dm %02ds", hours, minutes, remainder)
                : String.format(java.util.Locale.ROOT, "%dm %02ds", minutes, remainder);
    }

    private static FileResult failedResult(Path inputRoot, Path file, String error) {
        FileResult result = new FileResult(inputRoot, file);
        result.error = error;
        return result;
    }

    private static FileResult processFile(
            Path inputRoot,
            Path file,
            Options options,
            Semaphore memoryIntensivePermits) {
        FileResult result = new FileResult(inputRoot, file);
        try {
            CompModule module = AlloyUtil.compileAlloyModule(file.toString());
            if (module == null) {
                result.error = "Alloy parser returned no module.";
                return result;
            }
            ModelUnit model = new ModelUnit(null, module);
            PredicatePair pair = findPredicatePair(file, model);
            if (pair == null) {
                result.error = "No predicate pair of the form X and X[Cc] found.";
                return result;
            }
            result.leftPredicate = pair.leftName;
            result.rightPredicate = pair.rightName;
            result.leftRawAstSize = rawAstSize(pair.left.getBody());
            result.rightRawAstSize = rawAstSize(pair.right.getBody());
            result.rawAstSize = Math.max(result.leftRawAstSize, result.rightRawAstSize);
            if (DatasetConventions.sameRawAst(pair.left.getBody(), pair.right.getBody())) {
                result.rawAstTreeDistance = 0;
                result.skipped = true;
                result.skipReason = "Identical raw AST predicate body.";
                return result;
            }

            String leftBody = predicateBody(file, pair.leftName, pair.left);
            String rightBody = predicateBody(file, pair.rightName, pair.right);
            result.predicateBodySize = Math.max(leftBody.length(), rightBody.length());
            result.rawAstTreeDistance = rawAstTreeDistance(pair.left.getBody(), pair.right.getBody());
            result.normalizedRawAstDistance = normalizedDistance(result.rawAstTreeDistance, result.rawAstSize);

            MASGVisitor visitor = focusedVisitor(module, model, pair);
            DoubleMap<Integer, Multigraph> forest = visitor.getForest();
            Integer leftForestId = visitor.getForestId(pair.leftName);
            Integer rightForestId = visitor.getForestId(pair.rightName);
            Multigraph left = leftForestId == null ? null : forest.get(leftForestId);
            Multigraph right = rightForestId == null ? null : forest.get(rightForestId);
            if (left == null || right == null) {
                result.error = "Could not find both predicate graphs in MASG forest.";
                return result;
            }

            result.leftGraphId = pair.leftId;
            result.rightGraphId = pair.rightId;
            result.predicateBodyLevenshteinDistance = levenshteinDistance(leftBody, rightBody);
            result.normalizedLevenshteinDistance = normalizedDistance(
                    result.predicateBodyLevenshteinDistance,
                    result.predicateBodySize);
            result.leftVertices = left.size();
            result.rightVertices = right.size();
            acquireMemoryIntensivePermit(memoryIntensivePermits);
            try {
                computeDistanceMetrics(left, right, result);
            } finally {
                memoryIntensivePermits.release();
            }
            if (options.skipRewards) {
                result.rewardSkipped = true;
            } else {
                acquireMemoryIntensivePermit(memoryIntensivePermits);
                try {
                    computeRewardMetrics(module, result, options.rewardPoolSize);
                } finally {
                    memoryIntensivePermits.release();
                }
            }
            return result;
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable t) {
            if (options.verbose) {
                t.printStackTrace(System.err);
            }
            result.error = t.getClass().getSimpleName() + ": " + t.getMessage();
            return result;
        }
    }

    private static void computeDistanceMetrics(
            Multigraph left,
            Multigraph right,
            FileResult result) {
        Canonical.Prepared leftCanonical = Canonical.prepare(left);
        Canonical.Prepared rightCanonical = Canonical.prepare(right);
        result.leftLegacyCanonicalFormSize = Canonical.canonicalFormSize(leftCanonical);
        result.rightLegacyCanonicalFormSize = Canonical.canonicalFormSize(rightCanonical);
        result.legacyCanonicalFormSize = Math.max(
                result.leftLegacyCanonicalFormSize,
                result.rightLegacyCanonicalFormSize);
        CanonicalDistance.DistanceBreakdown legacyBreakdown =
                Canonical.distanceBreakdown(leftCanonical, rightCanonical);
        result.legacyCanonicalDistance = legacyBreakdown.distance();
        result.legacyTemporalDistance = legacyBreakdown.temporalDistance();
        result.legacyQuantifierDistance = legacyBreakdown.quantifierDistance();
        result.legacyMatrixDistance = legacyBreakdown.matrixDistance();
        result.normalizedLegacyCanonicalDistance = normalizedDistance(
                result.legacyCanonicalDistance,
                result.legacyCanonicalFormSize);

        long exactStarted = System.nanoTime();
        CanonicalAlloyPipeline.Prepared leftExact = CanonicalAlloyPipeline.prepare(leftCanonical);
        result.leftExactPreparationNanos = System.nanoTime() - exactStarted;
        exactStarted = System.nanoTime();
        CanonicalAlloyPipeline.Prepared rightExact = CanonicalAlloyPipeline.prepare(rightCanonical);
        result.rightExactPreparationNanos = System.nanoTime() - exactStarted;
        result.leftCanonicalFormSize = leftExact.repairObservationSize();
        result.rightCanonicalFormSize = rightExact.repairObservationSize();
        result.canonicalFormSize = Math.max(result.leftCanonicalFormSize, result.rightCanonicalFormSize);
        result.leftCanonicalRepresentativeTreeSize = leftExact.representativeTreeSize();
        result.rightCanonicalRepresentativeTreeSize = rightExact.representativeTreeSize();
        result.canonicalRepresentativeTreeSize = Math.max(
                result.leftCanonicalRepresentativeTreeSize,
                result.rightCanonicalRepresentativeTreeSize);
        result.representationSizesAvailable = true;
        exactStarted = System.nanoTime();
        QuotientRepairDistance.Result quotient =
                CanonicalAlloyPipeline.distanceEvaluation(leftExact, rightExact);
        result.distance = quotient.distance();
        result.quotientTemporalDistance = quotient.temporalDistance();
        result.quotientQuantifierDistance = quotient.quantifierDistance();
        result.quotientMatrixDistance = quotient.matrixDistance();
        result.exactDistanceNanos = System.nanoTime() - exactStarted;
        result.quotientDistanceExactForStoredOrbits = quotient.exactForStoredOrbits();
        result.quotientBinderAlignments = quotient.binderAlignments();
        result.normalizedCanonicalDistance = normalizedDistance(result.distance, result.canonicalFormSize);
        exactStarted = System.nanoTime();
        result.canonicalRepresentativeTreeDistance =
                CanonicalAlloyPipeline.canonicalRepresentativeTreeDistance(leftExact, rightExact);
        result.canonicalRepresentativeTreeDistanceNanos = System.nanoTime() - exactStarted;
        result.normalizedCanonicalRepresentativeTreeDistance = normalizedDistance(
                result.canonicalRepresentativeTreeDistance,
                result.canonicalRepresentativeTreeSize);
        result.leftExactDigest = leftExact.digest();
        result.rightExactDigest = rightExact.digest();
        result.exactEclasses = leftExact.eclassCount() + rightExact.eclassCount();
        result.exactEnodes = leftExact.enodeCount() + rightExact.enodeCount();
        result.exactSlots = leftExact.slotCount() + rightExact.slotCount();
        result.exactRebuilds = leftExact.rebuildCount() + rightExact.rebuildCount();
        result.exactConstructionNanos = leftExact.constructionNanos()
                + rightExact.constructionNanos();
        result.exactUnfoldingNanos = leftExact.unfoldingNanos()
                + rightExact.unfoldingNanos();
        result.exactObservationNanos = leftExact.observationNanos()
                + rightExact.observationNanos();
        result.exactRepairProjectionNanos = leftExact.repairProjectionNanos()
                + rightExact.repairProjectionNanos();
        result.leftIRTemporalFOL = Canonical.irTemporalFol(leftCanonical);
        result.rightIRTemporalFOL = Canonical.irTemporalFol(rightCanonical);
        result.edits = Canonical.edits(leftCanonical, rightCanonical);
    }

    private static MASGVisitor focusedVisitor(
            CompModule module,
            ModelUnit model,
            PredicatePair pair) {
        Set<String> callables = callableClosure(model, pair.leftName, pair.rightName);
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), callables, module);
        try {
            visitor.visit(model, null);
            return visitor;
        } catch (RuntimeException focusedFailure) {
            MASGVisitor fallback = new MASGVisitor(new GlobalVariables(), module);
            fallback.visit(model, null);
            return fallback;
        }
    }

    private static Set<String> callableClosure(
            ModelUnit model,
            String leftName,
            String rightName) {
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

    private static void computeRewardMetrics(CompModule module, FileResult result, int poolSize) {
        result.rewardComputed = true;
        try {
            Pair<InstancePool, InstancePool> instances = Rewarder.instances(module, result.rightPredicate, poolSize);
            result.rewardPoolSize = poolSize;
            result.candidateReward = Rewarder.computeReward(
                    module,
                    instances,
                    result.rightPredicate,
                    result.leftPredicate,
                    result.leftPredicate,
                    poolSize);
            result.groundTruthReward = Rewarder.computeReward(
                    module,
                    instances,
                    result.rightPredicate,
                    result.rightPredicate,
                    result.rightPredicate,
                    poolSize);
            result.rewardGap = result.groundTruthReward - result.candidateReward;
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable t) {
            result.rewardError = t.getClass().getSimpleName() + ": " + t.getMessage();
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
        String left = names[0];
        String right = names[1];
        return new PredicatePair(left, right, ids.get(left), ids.get(right),
                predicates.get(left), predicates.get(right));
    }

    private static String predicateBody(Path file, String predicateName, Predicate predicate) {
        try {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            String body = predicateBodyFromSource(source, predicateName);
            if (body != null) {
                return normalizeWhitespace(body);
            }
        } catch (IOException ignored) {
        }
        if (predicate == null || predicate.getBody() == null || predicate.getBody().getBodyExpr() == null) {
            return "";
        }
        return predicate.getBody().getBodyExpr().toString();
    }

    private static String predicateBodyFromSource(String source, String predicateName) {
        String needle = "pred " + predicateName;
        int start = source.indexOf(needle);
        while (start >= 0) {
            int nameEnd = start + needle.length();
            if (nameEnd < source.length() && Character.isJavaIdentifierPart(source.charAt(nameEnd))) {
                start = source.indexOf(needle, nameEnd);
                continue;
            }
            int open = source.indexOf('{', nameEnd);
            if (open < 0) {
                return null;
            }
            int close = matchingBrace(source, open);
            return close < 0 ? null : source.substring(open + 1, close);
        }
        return null;
    }

    private static int matchingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String normalizeWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replace = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                int delete = previous[j] + 1;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(replace, Math.min(delete, insert));
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[right.length()];
    }

    private static int rawAstTreeDistance(Node left, Node right) {
        if (left == null) {
            return rawAstSize(right);
        }
        if (right == null) {
            return rawAstSize(left);
        }
        int distance = rawAstLabel(left).equals(rawAstLabel(right)) ? 0 : 1;
        distance += rawAstForestDistance(
                DatasetConventions.rawAstChildren(left),
                DatasetConventions.rawAstChildren(right));
        return distance;
    }

    private static int rawAstForestDistance(List<Node> left, List<Node> right) {
        int[][] dp = new int[left.size() + 1][right.size() + 1];
        for (int i = 1; i <= left.size(); i++) {
            dp[i][0] = dp[i - 1][0] + rawAstSize(left.get(i - 1));
        }
        for (int j = 1; j <= right.size(); j++) {
            dp[0][j] = dp[0][j - 1] + rawAstSize(right.get(j - 1));
        }
        for (int i = 1; i <= left.size(); i++) {
            for (int j = 1; j <= right.size(); j++) {
                int delete = dp[i - 1][j] + rawAstSize(left.get(i - 1));
                int insert = dp[i][j - 1] + rawAstSize(right.get(j - 1));
                int update = dp[i - 1][j - 1] + rawAstTreeDistance(left.get(i - 1), right.get(j - 1));
                dp[i][j] = Math.min(update, Math.min(delete, insert));
            }
        }
        return dp[left.size()][right.size()];
    }

    private static int rawAstSize(Node node) {
        if (node == null) {
            return 0;
        }
        int size = 1;
        for (Node child : DatasetConventions.rawAstChildren(node)) {
            size += rawAstSize(child);
        }
        return size;
    }

    private static double normalizedDistance(int distance, int size) {
        return size == 0 ? 0.0 : (double) distance / size;
    }

    private static String rawAstLabel(Node node) {
        return DatasetConventions.rawAstLabel(node);
    }

    private static String preferredPredicateBase(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        int underscore = name.lastIndexOf('_');
        if (underscore < 0 || underscore + 1 >= name.length()) {
            return null;
        }
        return name.substring(underscore + 1);
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

    private static void writeJsonHeader(Writer writer, Options options, int fileCount) throws IOException {
        writer.write("{\n");
        writer.write("  \"generatedAt\": \"" + escape(Instant.now().toString()) + "\",\n");
        writer.write("  \"inputRoot\": \"" + escape(options.inputDir.toString()) + "\",\n");
        writer.write("  \"fileCount\": " + fileCount + ",\n");
        writer.write("  \"threadCount\": " + options.threadCount + ",\n");
        writer.write("  \"memoryIntensiveWorkerLimit\": "
                + ExperimentMemoryBudget.effectiveWorkers(options.threadCount) + ",\n");
        writer.write("  \"maximumHeapBytes\": " + Runtime.getRuntime().maxMemory() + ",\n");
        writer.write("  \"canonicalEngine\": \"CanonicalAlloyPipeline\",\n");
        writer.write("  \"certificateIntegratedEngine\": \"CanonicalAlloyPipeline\",\n");
        writer.write("  \"fastRewriteEngine\": \"Canonical/CanonicalDistance\",\n");
        writer.write("  \"canonicalPipelineVersion\": \"" + CanonicalAlloyPipeline.PIPELINE_VERSION + "\",\n");
        writer.write("  \"measurementProjectionVersion\": \""
                + CanonicalAlloyPipeline.MEASUREMENT_PROJECTION_VERSION + "\",\n");
        writer.write("  \"quotientMetricVersion\": \""
                + CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION + "\",\n");
        writer.write("  \"canonicalRepresentativeTedVersion\": \""
                + CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION + "\",\n");
        writer.write("  \"alloyAdapterVersion\": \"" + TheoryAlloyAdapter.ADAPTER_VERSION + "\",\n");
        writer.write("  \"invariantCheckMode\": \"" + TheoryAlloyAdapter.INVARIANT_MODE + "\",\n");
        writer.write("  \"canonicalizerVersion\": \"" + ProductionGraphCanonicalizer.VERSION + "\",\n");
        writer.write("  \"certificateVerifierVersion\": \"" + CertificateVerifier.VERSION + "\",\n");
        writer.write("  \"certificateMode\": \"required\",\n");
        writer.write("  \"finiteUnfoldingVersion\": \"" + BoundedFiniteUnfoldingOracle.VERSION + "\",\n");
        writer.write("  \"fastRewriteCanonicalRetained\": true,\n");
        writer.write("  \"legacyCanonicalRetained\": true,\n");
        writer.write("  \"implementationFieldMapping\": {\"certificateIntegrated\": \"canonical/distance*\", "
                + "\"fastRewrite\": \"legacyCanonical*\"},\n");
        writer.write("  \"rewardsEnabled\": " + !options.skipRewards + ",\n");
        writer.write("  \"results\": [\n");
    }

    private static void writeJsonFooter(Writer writer, Summary summary) throws IOException {
        writer.write("\n  ],\n");
        writer.write("  \"summary\": {\n");
        writer.write("    \"total\": " + summary.total + ",\n");
        writer.write("    \"successes\": " + summary.successes + ",\n");
        writer.write("    \"skippedIdenticalRawAstPairs\": " + summary.skipped + ",\n");
        writer.write("    \"failures\": " + summary.failures + ",\n");
        writer.write("    \"averageDistance\": " + number(summary.averageDistance()) + ",\n");
        writer.write("    \"averageCertificateIntegratedCanonicalDistance\": "
                + number(summary.averageDistance()) + ",\n");
        writer.write("    \"averageCanonicalRepresentativeTreeDistance\": "
                + number(summary.averageCanonicalRepresentativeTreeDistance()) + ",\n");
        writer.write("    \"averageLegacyCanonicalDistance\": "
                + number(summary.averageLegacyCanonicalDistance()) + ",\n");
        writer.write("    \"averageFastRewriteCanonicalDistance\": "
                + number(summary.averageLegacyCanonicalDistance()) + ",\n");
        writer.write("    \"averagePredicateBodyLevenshteinDistance\": " + number(summary.averageLevenshteinDistance()) + ",\n");
        writer.write("    \"averageRawAstTreeDistance\": " + number(summary.averageRawAstTreeDistance()) + ",\n");
        writer.write("    \"averageRawAstSize\": " + number(summary.averageRawAstSize()) + ",\n");
        writer.write("    \"averageCanonicalFormSize\": " + number(summary.averageCanonicalFormSize()) + ",\n");
        writer.write("    \"averageCertificateIntegratedFormSize\": "
                + number(summary.averageCanonicalFormSize()) + ",\n");
        writer.write("    \"averageCanonicalRepresentativeTreeSize\": "
                + number(summary.averageCanonicalRepresentativeTreeSize()) + ",\n");
        writer.write("    \"averageLegacyCanonicalFormSize\": "
                + number(summary.averageLegacyCanonicalFormSize()) + ",\n");
        writer.write("    \"averageFastRewriteCanonicalFormSize\": "
                + number(summary.averageLegacyCanonicalFormSize()) + ",\n");
        writer.write("    \"averageNormalizedLevenshteinDistance\": "
                + number(summary.averageNormalizedLevenshteinDistance()) + ",\n");
        writer.write("    \"averageNormalizedRawAstDistance\": " + number(summary.averageNormalizedRawAstDistance()) + ",\n");
        writer.write("    \"averageNormalizedCanonicalDistance\": " + number(summary.averageNormalizedCanonicalDistance()) + ",\n");
        writer.write("    \"averageNormalizedCertificateIntegratedDistance\": "
                + number(summary.averageNormalizedCanonicalDistance()) + ",\n");
        writer.write("    \"averageNormalizedCanonicalRepresentativeTreeDistance\": "
                + number(summary.averageNormalizedCanonicalRepresentativeTreeDistance()) + ",\n");
        writer.write("    \"averageNormalizedLegacyCanonicalDistance\": "
                + number(summary.averageNormalizedLegacyCanonicalDistance()) + ",\n");
        writer.write("    \"averageNormalizedFastRewriteCanonicalDistance\": "
                + number(summary.averageNormalizedLegacyCanonicalDistance()) + ",\n");
        writer.write("    \"correctCanonicalZeroRawAstNonzero\": "
                + summary.correctCanonicalZeroRawAstNonzero + ",\n");
        writer.write("    \"incorrectCanonicalZero\": " + summary.incorrectCanonicalZero + ",\n");
        writer.write("    \"inexactAlphaSearches\": " + summary.inexactAlphaSearches + ",\n");
        writer.write("    \"averageQuotientDistanceNanos\": "
                + number(summary.averageQuotientDistanceNanos()) + ",\n");
        writer.write("    \"averageCanonicalRepresentativeTreeDistanceNanos\": "
                + number(summary.averageRepresentativeDistanceNanos()) + ",\n");
        writer.write("    \"minDistance\": " + summary.minDistanceJson() + ",\n");
        writer.write("    \"maxDistance\": " + summary.maxDistanceJson() + ",\n");
        writer.write("    \"rewardSuccesses\": " + summary.rewardSuccesses + ",\n");
        writer.write("    \"rewardFailures\": " + summary.rewardFailures() + ",\n");
        writer.write("    \"averageCandidateReward\": " + number(summary.averageCandidateReward()) + ",\n");
        writer.write("    \"averageGroundTruthReward\": " + number(summary.averageGroundTruthReward()) + ",\n");
        writer.write("    \"averageRewardGap\": " + number(summary.averageRewardGap()) + ",\n");
        writer.write("    \"distanceCandidateRewardPearsonSamples\": " + summary.distanceRewardSamples + ",\n");
        writer.write("    \"distanceCandidateRewardPearson\": " + number(summary.distanceRewardCorrelation()) + ",\n");
        writer.write("    \"canonicalRepresentativeTedCandidateRewardPearson\": "
                + number(summary.representativeRewardCorrelation()) + ",\n");
        writer.write("    \"legacyCanonicalCandidateRewardPearson\": "
                + number(summary.legacyRewardCorrelation()) + ",\n");
        writer.write("    \"levenshteinCandidateRewardPearson\": " + number(summary.levenshteinRewardCorrelation()) + ",\n");
        writer.write("    \"rawAstCandidateRewardPearson\": " + number(summary.rawAstRewardCorrelation()) + ",\n");
        writer.write("    \"normalizedRawAstCandidateRewardPearson\": "
                + number(summary.normalizedRawAstRewardCorrelation()) + ",\n");
        writer.write("    \"normalizedCanonicalCandidateRewardPearson\": "
                + number(summary.normalizedCanonicalRewardCorrelation()) + ",\n");
        writer.write("    \"normalizedCanonicalRepresentativeTedCandidateRewardPearson\": "
                + number(summary.normalizedRepresentativeRewardCorrelation()) + ",\n");
        writer.write("    \"normalizedLegacyCanonicalCandidateRewardPearson\": "
                + number(summary.normalizedLegacyRewardCorrelation()) + ",\n");
        writer.write("    \"representationSamples\": " + summary.representationSamples + ",\n");
        writer.write("    \"averageStudentRawAstSize\": " + number(summary.averageStudentRawAstSize()) + ",\n");
        writer.write("    \"averageStudentCanonicalFormSize\": "
                + number(summary.averageStudentCanonicalFormSize()) + ",\n");
        writer.write("    \"studentCanonicalCompressionRatePercent\": "
                + number(summary.studentCanonicalCompressionRatePercent()) + ",\n");
        writer.write("    \"representationByProblemClassAndStatus\": [\n");
        writeJsonRepresentationGroups(writer, summary.groupStats);
        writer.write("\n    ]\n");
        writer.write("  }\n");
        writer.write("}\n");
    }

    private static void writeJsonRepresentationGroups(Writer writer, Map<String, Stats> groups) throws IOException {
        int index = 0;
        for (Stats stats : groups.values()) {
            if (index++ > 0) {
                writer.write(",\n");
            }
            writer.write("      {\"problemClass\": \"" + escape(stats.problemClass)
                    + "\", \"statusFolder\": \"" + escape(stats.statusFolder)
                    + "\", \"modelCount\": " + stats.representationSamples
                    + ", \"totalRawAstSize\": " + stats.studentRawAstSizeSum
                    + ", \"totalCanonicalFormSize\": " + stats.studentCanonicalFormSizeSum
                    + ", \"averageRawAstSize\": " + number(stats.averageStudentRawAstSize())
                    + ", \"averageCanonicalFormSize\": " + number(stats.averageStudentCanonicalFormSize())
                    + ", \"compressionRatePercent\": "
                    + number(stats.studentCanonicalCompressionRatePercent()) + "}");
        }
    }

    private static void writeJsonResult(Writer writer, FileResult result) throws IOException {
        writer.write("    {\n");
        writer.write("      \"fileName\": \"" + escape(result.fileName) + "\",\n");
        writer.write("      \"relativePath\": \"" + escape(result.relativePath) + "\",\n");
        writer.write("      \"problemClass\": \"" + escape(result.problemClass) + "\",\n");
        writer.write("      \"statusFolder\": \"" + escape(result.statusFolder) + "\",\n");
        writer.write("      \"success\": " + result.success() + ",\n");
        if (result.success()) {
            writer.write("      \"leftPredicate\": \"" + escape(result.leftPredicate) + "\",\n");
            writer.write("      \"rightPredicate\": \"" + escape(result.rightPredicate) + "\",\n");
            writer.write("      \"leftGraphId\": " + result.leftGraphId + ",\n");
            writer.write("      \"rightGraphId\": " + result.rightGraphId + ",\n");
            writer.write("      \"leftVertices\": " + result.leftVertices + ",\n");
            writer.write("      \"rightVertices\": " + result.rightVertices + ",\n");
            writer.write("      \"leftRawAstSize\": " + result.leftRawAstSize + ",\n");
            writer.write("      \"rightRawAstSize\": " + result.rightRawAstSize + ",\n");
            writer.write("      \"rawAstSize\": " + result.rawAstSize + ",\n");
            writer.write("      \"predicateBodySize\": " + result.predicateBodySize + ",\n");
            writer.write("      \"predicateBodyLevenshteinDistance\": " + result.predicateBodyLevenshteinDistance + ",\n");
            writer.write("      \"normalizedLevenshteinDistance\": "
                    + number(result.normalizedLevenshteinDistance) + ",\n");
            writer.write("      \"rawAstTreeDistance\": " + result.rawAstTreeDistance + ",\n");
            writer.write("      \"normalizedRawAstDistance\": " + number(result.normalizedRawAstDistance) + ",\n");
            writer.write("      \"leftCanonicalFormSize\": " + result.leftCanonicalFormSize + ",\n");
            writer.write("      \"rightCanonicalFormSize\": " + result.rightCanonicalFormSize + ",\n");
            writer.write("      \"canonicalFormSize\": " + result.canonicalFormSize + ",\n");
            writer.write("      \"leftCanonicalRepresentativeTreeSize\": "
                    + result.leftCanonicalRepresentativeTreeSize + ",\n");
            writer.write("      \"rightCanonicalRepresentativeTreeSize\": "
                    + result.rightCanonicalRepresentativeTreeSize + ",\n");
            writer.write("      \"canonicalRepresentativeTreeSize\": "
                    + result.canonicalRepresentativeTreeSize + ",\n");
            writer.write("      \"leftLegacyCanonicalFormSize\": "
                    + result.leftLegacyCanonicalFormSize + ",\n");
            writer.write("      \"rightLegacyCanonicalFormSize\": "
                    + result.rightLegacyCanonicalFormSize + ",\n");
            writer.write("      \"legacyCanonicalFormSize\": " + result.legacyCanonicalFormSize + ",\n");
            writer.write("      \"leftCanonicalCompressionRatePercent\": "
                    + number(result.leftCanonicalCompressionRatePercent()) + ",\n");
            writer.write("      \"distance\": " + result.distance + ",\n");
            writer.write("      \"quotientTemporalDistance\": "
                    + result.quotientTemporalDistance + ",\n");
            writer.write("      \"quotientQuantifierDistance\": "
                    + result.quotientQuantifierDistance + ",\n");
            writer.write("      \"quotientMatrixDistance\": "
                    + result.quotientMatrixDistance + ",\n");
            writer.write("      \"normalizedCanonicalDistance\": " + number(result.normalizedCanonicalDistance) + ",\n");
            writer.write("      \"canonicalRepresentativeTreeDistance\": "
                    + result.canonicalRepresentativeTreeDistance + ",\n");
            writer.write("      \"normalizedCanonicalRepresentativeTreeDistance\": "
                    + number(result.normalizedCanonicalRepresentativeTreeDistance) + ",\n");
            writer.write("      \"legacyCanonicalDistance\": " + result.legacyCanonicalDistance + ",\n");
            writer.write("      \"legacyTemporalDistance\": "
                    + result.legacyTemporalDistance + ",\n");
            writer.write("      \"legacyQuantifierDistance\": "
                    + result.legacyQuantifierDistance + ",\n");
            writer.write("      \"legacyMatrixDistance\": "
                    + result.legacyMatrixDistance + ",\n");
            writer.write("      \"normalizedLegacyCanonicalDistance\": "
                    + number(result.normalizedLegacyCanonicalDistance) + ",\n");
            writer.write("      \"leftExactDigest\": \"" + result.leftExactDigest + "\",\n");
            writer.write("      \"rightExactDigest\": \"" + result.rightExactDigest + "\",\n");
            writer.write("      \"exactEclasses\": " + result.exactEclasses + ",\n");
            writer.write("      \"exactEnodes\": " + result.exactEnodes + ",\n");
            writer.write("      \"exactSlots\": " + result.exactSlots + ",\n");
            writer.write("      \"exactRebuilds\": " + result.exactRebuilds + ",\n");
            writer.write("      \"leftExactPreparationNanos\": "
                    + result.leftExactPreparationNanos + ",\n");
            writer.write("      \"rightExactPreparationNanos\": "
                    + result.rightExactPreparationNanos + ",\n");
            writer.write("      \"exactDistanceNanos\": " + result.exactDistanceNanos + ",\n");
            writer.write("      \"canonicalRepresentativeTreeDistanceNanos\": "
                    + result.canonicalRepresentativeTreeDistanceNanos + ",\n");
            writer.write("      \"quotientDistanceExactForStoredOrbits\": "
                    + result.quotientDistanceExactForStoredOrbits + ",\n");
            writer.write("      \"quotientBinderAlignments\": "
                    + result.quotientBinderAlignments + ",\n");
            writer.write("      \"exactConstructionNanos\": "
                    + result.exactConstructionNanos + ",\n");
            writer.write("      \"exactUnfoldingNanos\": " + result.exactUnfoldingNanos + ",\n");
            writer.write("      \"exactObservationNanos\": "
                    + result.exactObservationNanos + ",\n");
            writer.write("      \"exactRepairProjectionNanos\": "
                    + result.exactRepairProjectionNanos + ",\n");
            writer.write("      \"rewardPoolSize\": " + result.rewardPoolSize + ",\n");
            if (result.rewardSkipped) {
                writer.write("      \"candidateReward\": null,\n");
                writer.write("      \"groundTruthReward\": null,\n");
                writer.write("      \"rewardGap\": null,\n");
                writer.write("      \"rewardSkipped\": true,\n");
            } else if (result.rewardError == null) {
                writer.write("      \"candidateReward\": " + number(result.candidateReward) + ",\n");
                writer.write("      \"groundTruthReward\": " + number(result.groundTruthReward) + ",\n");
                writer.write("      \"rewardGap\": " + number(result.rewardGap) + ",\n");
            } else {
                writer.write("      \"candidateReward\": null,\n");
                writer.write("      \"groundTruthReward\": null,\n");
                writer.write("      \"rewardGap\": null,\n");
                writer.write("      \"rewardError\": \"" + escape(result.rewardError) + "\",\n");
            }
            writer.write("      \"leftIRTemporalFOL\": ");
            writeJsonStringArray(writer, result.leftIRTemporalFOL);
            writer.write(",\n");
            writer.write("      \"rightIRTemporalFOL\": ");
            writeJsonStringArray(writer, result.rightIRTemporalFOL);
            writer.write(",\n");
            writer.write("      \"legacyDiagnosticEditCount\": " + result.edits.size() + ",\n");
            writer.write("      \"legacyDiagnosticEdits\": ");
            writeJsonStringArray(writer, result.edits);
            writer.write("\n");
        } else if (result.skipped) {
            writer.write("      \"skipped\": true,\n");
            writer.write("      \"leftPredicate\": \"" + escape(result.leftPredicate) + "\",\n");
            writer.write("      \"rightPredicate\": \"" + escape(result.rightPredicate) + "\",\n");
            writer.write("      \"leftRawAstSize\": " + result.leftRawAstSize + ",\n");
            writer.write("      \"rightRawAstSize\": " + result.rightRawAstSize + ",\n");
            writer.write("      \"rawAstSize\": " + result.rawAstSize + ",\n");
            writer.write("      \"predicateBodySize\": " + result.predicateBodySize + ",\n");
            writer.write("      \"predicateBodyLevenshteinDistance\": "
                    + result.predicateBodyLevenshteinDistance + ",\n");
            writer.write("      \"normalizedLevenshteinDistance\": "
                    + number(result.normalizedLevenshteinDistance) + ",\n");
            writer.write("      \"leftCanonicalFormSize\": " + result.leftCanonicalFormSize + ",\n");
            writer.write("      \"rightCanonicalFormSize\": " + result.rightCanonicalFormSize + ",\n");
            writer.write("      \"canonicalFormSize\": " + result.canonicalFormSize + ",\n");
            writer.write("      \"leftCanonicalCompressionRatePercent\": "
                    + number(result.leftCanonicalCompressionRatePercent()) + ",\n");
            writer.write("      \"rawAstTreeDistance\": " + result.rawAstTreeDistance + ",\n");
            writer.write("      \"skipReason\": \"" + escape(result.skipReason) + "\"\n");
        } else {
            writer.write("      \"skipped\": false,\n");
            writer.write("      \"error\": \"" + escape(result.error) + "\"\n");
        }
        writer.write("    }");
    }

    private static void writeMarkdown(Path path, Options options, Summary summary) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# Canonical Rewrite Distance Summary\n\n");
            writer.write("- Input root: `" + options.inputDir + "`\n");
            writer.write("- Thread count: " + options.threadCount + "\n");
            writer.write("- Memory-intensive worker limit: "
                    + ExperimentMemoryBudget.effectiveWorkers(options.threadCount) + "\n");
            writer.write("- Maximum JVM heap bytes: " + Runtime.getRuntime().maxMemory() + "\n");
            writer.write("- Certificate-Integrated IR engine: `CanonicalAlloyPipeline` (`"
                    + CanonicalAlloyPipeline.PIPELINE_VERSION + "`)\n");
            writer.write("- Fast Rewrite IR engine: `Canonical` / `CanonicalDistance`\n");
            writer.write("- Exact graph: `TypedSlottedPortEGraph`; invariants: `"
                    + TheoryAlloyAdapter.INVARIANT_MODE + "`; certificates: required\n");
            writer.write("- Primary metric: established repair metric over the certified quotient; "
                    + "compatibility manifest ID `"
                    + CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION + "`\n");
            writer.write("- Canonical representative TED retained only as baseline: `"
                    + CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION + "`\n");
            writer.write("- Co-maintained Fast Rewrite IR metric retained as a differential oracle: yes\n");
            writer.write("- Total files: " + summary.total + "\n");
            writer.write("- Successful distances: " + summary.successes + "\n");
            writer.write("- Skipped identical raw AST predicate pairs: " + summary.skipped + "\n");
            writer.write("- Failures: " + summary.failures + "\n");
            writer.write("- Average Certificate-Integrated IR repair distance: "
                    + number(summary.averageDistance()) + "\n");
            writer.write("- Average canonical representative TED baseline: "
                    + number(summary.averageCanonicalRepresentativeTreeDistance()) + "\n");
            writer.write("- Average Fast Rewrite IR distance: "
                    + number(summary.averageLegacyCanonicalDistance()) + "\n");
            writer.write("- Average predicate-body Levenshtein distance: "
                    + number(summary.averageLevenshteinDistance()) + "\n");
            writer.write("- Average raw AST tree distance: " + number(summary.averageRawAstTreeDistance()) + "\n");
            writer.write("- Average raw AST size: " + number(summary.averageRawAstSize()) + "\n");
            writer.write("- Average Certificate-Integrated IR repair observation size: "
                    + number(summary.averageCanonicalFormSize()) + "\n");
            writer.write("- Average canonical representative tree size: "
                    + number(summary.averageCanonicalRepresentativeTreeSize()) + "\n");
            writer.write("- Average Fast Rewrite IR NormalForm size: "
                    + number(summary.averageLegacyCanonicalFormSize()) + "\n");
            writer.write("- Average normalized predicate-body Levenshtein distance: "
                    + number(summary.averageNormalizedLevenshteinDistance()) + "\n");
            writer.write("- Average normalized raw AST distance: "
                    + number(summary.averageNormalizedRawAstDistance()) + "\n");
            writer.write("- Average normalized Certificate-Integrated IR distance: "
                    + number(summary.averageNormalizedCanonicalDistance()) + "\n");
            writer.write("- Average normalized canonical representative TED: "
                    + number(summary.averageNormalizedCanonicalRepresentativeTreeDistance()) + "\n");
            writer.write("- Average normalized Fast Rewrite IR distance: "
                    + number(summary.averageNormalizedLegacyCanonicalDistance()) + "\n");
            writer.write("- CORRECT models with canonical distance 0 and raw AST distance > 0: "
                    + summary.correctCanonicalZeroRawAstNonzero + "\n");
            writer.write("- Incorrect zero-distance merges: " + summary.incorrectCanonicalZero + "\n");
            writer.write("- Inexact alpha searches: " + summary.inexactAlphaSearches + "\n");
            writer.write("- Average certified repair metric time: "
                    + number(summary.averageQuotientDistanceNanos() / 1_000_000.0) + " ms\n");
            writer.write("- Average canonical representative TED time: "
                    + number(summary.averageRepresentativeDistanceNanos() / 1_000_000.0) + " ms\n");
            writer.write("- Min distance: " + summary.minDistanceMarkdown() + "\n");
            writer.write("- Max distance: " + summary.maxDistanceMarkdown() + "\n\n");

            writer.write("## Repair Observation Compression\n\n");
            writer.write("Compression rate is `100 * (raw AST size - repair observation size) / raw AST size`. "
                    + "Negative values indicate expansion. Sizes are for the student predicate associated with "
                    + "the directory label; identical-AST pairs are excluded.\n\n");
            writer.write("| Problem class | Correctness division | Models | Avg raw AST size | Avg repair observation size | Compression rate |\n");
            writer.write("| --- | --- | ---: | ---: | ---: | ---: |\n");
            for (Stats stats : summary.groupStats.values()) {
                writer.write("| " + stats.problemClass + " | " + stats.statusFolder + " | "
                        + stats.representationSamples + " | " + number(stats.averageStudentRawAstSize()) + " | "
                        + number(stats.averageStudentCanonicalFormSize()) + " | "
                        + number(stats.studentCanonicalCompressionRatePercent()) + "% |\n");
            }
            writer.write("\n");

            writer.write("## Distance Averages Overall And By Problem Class And Status\n\n");
            writer.write("Raw columns use edit-distance units. Relative columns divide each distance by the larger "
                    + "corresponding representation of the student-oracle pair: body characters for Levenshtein, "
                    + "raw AST nodes for AST distance, and canonical-form size for canonical distance. Identical "
                    + "raw-AST pairs skipped by the test are excluded.\n\n");
            writer.write("| Problem class | Semantic correctness class | Comparisons | "
                    + "Avg Levenshtein | Avg raw AST | Avg Fast Rewrite IR | Avg representative TED | Avg Certificate-Integrated IR | "
                    + "Avg relative Levenshtein | Avg relative raw AST | Avg relative Fast Rewrite IR | Avg relative representative TED | Avg relative Certificate-Integrated IR |\n");
            writer.write("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            writer.write("| **All problem classes** | **All statuses** | **" + summary.successes + "** | **"
                    + number(summary.averageLevenshteinDistance()) + "** | **"
                    + number(summary.averageRawAstTreeDistance()) + "** | **"
                    + number(summary.averageLegacyCanonicalDistance()) + "** | **"
                    + number(summary.averageCanonicalRepresentativeTreeDistance()) + "** | **"
                    + number(summary.averageDistance()) + "** | **"
                    + number(summary.averageNormalizedLevenshteinDistance()) + "** | **"
                    + number(summary.averageNormalizedRawAstDistance()) + "** | **"
                    + number(summary.averageNormalizedLegacyCanonicalDistance()) + "** | **"
                    + number(summary.averageNormalizedCanonicalRepresentativeTreeDistance()) + "** | **"
                    + number(summary.averageNormalizedCanonicalDistance()) + "** |\n");
            for (Stats stats : summary.groupStats.values()) {
                writer.write("| " + stats.problemClass + " | " + stats.statusFolder + " | "
                        + stats.successes + " | " + number(stats.averageLevenshteinDistance()) + " | "
                        + number(stats.averageRawAstTreeDistance()) + " | "
                        + number(stats.averageLegacyCanonicalDistance()) + " | "
                        + number(stats.averageCanonicalRepresentativeTreeDistance()) + " | "
                        + number(stats.averageDistance()) + " | "
                        + number(stats.averageNormalizedLevenshteinDistance()) + " | "
                        + number(stats.averageNormalizedRawAstDistance()) + " | "
                        + number(stats.averageNormalizedLegacyCanonicalDistance()) + " | "
                        + number(stats.averageNormalizedCanonicalRepresentativeTreeDistance()) + " | "
                        + number(stats.averageNormalizedCanonicalDistance()) + " |\n");
            }
            writer.write("\n");

            writer.write("## Reward Comparison\n\n");
            writer.write("- Rewarded files: " + summary.rewardSuccesses + "\n");
            writer.write("- Reward failures: " + summary.rewardFailures() + "\n");
            writer.write("- Reward pool size: " + options.rewardPoolSize + "\n");
            writer.write("- Rewards enabled: " + !options.skipRewards + "\n");
            writer.write("- Average candidate reward: " + number(summary.averageCandidateReward()) + "\n");
            writer.write("- Average ground-truth self reward: " + number(summary.averageGroundTruthReward()) + "\n");
            writer.write("- Average reward gap: " + number(summary.averageRewardGap()) + "\n");
            writer.write("- Pearson correlation sample: non-CORRECT rewarded predicates ("
                    + summary.distanceRewardSamples + " files)\n");
            writer.write("- Pearson correlation, Certificate-Integrated IR distance vs candidate reward: "
                    + number(summary.distanceRewardCorrelation()) + "\n\n");
            writer.write("- Pearson correlation, canonical representative TED vs candidate reward: "
                    + number(summary.representativeRewardCorrelation()) + "\n");
            writer.write("- Pearson correlation, Fast Rewrite IR distance vs candidate reward: "
                    + number(summary.legacyRewardCorrelation()) + "\n");
            writer.write("- Pearson correlation, Levenshtein vs candidate reward: "
                    + number(summary.levenshteinRewardCorrelation()) + "\n");
            writer.write("- Pearson correlation, raw AST tree distance vs candidate reward: "
                    + number(summary.rawAstRewardCorrelation()) + "\n\n");
            writer.write("- Pearson correlation, normalized raw AST distance vs candidate reward: "
                    + number(summary.normalizedRawAstRewardCorrelation()) + "\n");
            writer.write("- Pearson correlation, normalized Certificate-Integrated IR distance vs candidate reward: "
                    + number(summary.normalizedCanonicalRewardCorrelation()) + "\n\n");
            writer.write("- Pearson correlation, normalized canonical representative TED vs candidate reward: "
                    + number(summary.normalizedRepresentativeRewardCorrelation()) + "\n\n");
            writer.write("- Pearson correlation, normalized Fast Rewrite IR distance vs candidate reward: "
                    + number(summary.normalizedLegacyRewardCorrelation()) + "\n\n");

            writer.write("## By Problem Class And Status\n\n");
            writer.write("| Problem class | Status | Files | Successes | Skipped | Failures | Avg distance | Avg reward | Corr(distance,reward) | Min | Max |\n");
            writer.write("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (Map.Entry<String, Stats> entry : summary.groupStats.entrySet()) {
                Stats stats = entry.getValue();
                writer.write("| " + stats.problemClass + " | " + stats.statusFolder + " | "
                        + stats.total + " | " + stats.successes + " | " + stats.skipped + " | "
                        + stats.failures + " | "
                        + number(stats.averageDistance()) + " | " + number(stats.averageCandidateReward()) + " | "
                        + number(stats.distanceRewardCorrelation()) + " | " + stats.minDistanceMarkdown() + " | "
                        + stats.maxDistanceMarkdown() + " |\n");
            }

            if (!summary.failureSamples.isEmpty()) {
                writer.write("\n## Failure Samples\n\n");
                for (FileResult result : summary.failureSamples) {
                    writer.write("- `" + result.relativePath + "`: " + result.error + "\n");
                }
            }
        }
    }

    private static void writeJsonStringArray(Writer writer, List<String> values) throws IOException {
        writer.write("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                writer.write(", ");
            }
            writer.write("\"" + escape(values.get(i)) + "\"");
        }
        writer.write("]");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private static String number(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static double compressionRatePercent(long rawAstSize, long canonicalFormSize) {
        if (rawAstSize <= 0) {
            return 0.0;
        }
        return 100.0 * (rawAstSize - canonicalFormSize) / rawAstSize;
    }

    private static class Options {
        private Path inputDir = Paths.get(DEFAULT_INPUT);
        private Path outputDir = Paths.get(DEFAULT_OUTPUT);
        private int limit = -1;
        private int rewardPoolSize = DEFAULT_REWARD_POOL_SIZE;
        private int threadCount = DEFAULT_THREAD_COUNT;
        private boolean verbose;
        private boolean skipRewards;

        private static Options parse(String[] args) {
            Options options = new Options();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                if ("--limit".equals(args[i]) && i + 1 < args.length) {
                    options.limit = Integer.parseInt(args[++i]);
                } else if ("--reward-pool".equals(args[i]) && i + 1 < args.length) {
                    options.rewardPoolSize = Integer.parseInt(args[++i]);
                } else if ("--threads".equals(args[i]) && i + 1 < args.length) {
                    options.threadCount = Integer.parseInt(args[++i]);
                } else if ("--verbose".equals(args[i])) {
                    options.verbose = true;
                } else if ("--skip-rewards".equals(args[i])) {
                    options.skipRewards = true;
                } else if (positional == 0) {
                    options.inputDir = Paths.get(args[i]);
                    positional++;
                } else if (positional == 1) {
                    options.outputDir = Paths.get(args[i]);
                    positional++;
                }
            }
            return options;
        }
    }

    @FunctionalInterface
    private interface ResultSink {
        void accept(FileResult result) throws IOException;
    }

    private static final class IndexedResult {
        private final int index;
        private final FileResult result;

        private IndexedResult(int index, FileResult result) {
            this.index = index;
            this.result = result;
        }
    }

    private static class PredicatePair {
        private final String leftName;
        private final String rightName;
        private final int leftId;
        private final int rightId;
        private final Predicate left;
        private final Predicate right;

        private PredicatePair(String leftName, String rightName, int leftId, int rightId, Predicate left, Predicate right) {
            this.leftName = leftName;
            this.rightName = rightName;
            this.leftId = leftId;
            this.rightId = rightId;
            this.left = left;
            this.right = right;
        }
    }

    private static class FileResult {
        private final String fileName;
        private final String relativePath;
        private final String problemClass;
        private final String statusFolder;
        private String leftPredicate;
        private String rightPredicate;
        private int leftGraphId;
        private int rightGraphId;
        private int leftVertices;
        private int rightVertices;
        private int leftRawAstSize;
        private int rightRawAstSize;
        private int rawAstSize;
        private int predicateBodySize;
        private int predicateBodyLevenshteinDistance;
        private double normalizedLevenshteinDistance;
        private int rawAstTreeDistance;
        private double normalizedRawAstDistance;
        private int leftCanonicalFormSize;
        private int rightCanonicalFormSize;
        private int canonicalFormSize;
        private int leftCanonicalRepresentativeTreeSize;
        private int rightCanonicalRepresentativeTreeSize;
        private int canonicalRepresentativeTreeSize;
        private int leftLegacyCanonicalFormSize;
        private int rightLegacyCanonicalFormSize;
        private int legacyCanonicalFormSize;
        private int distance;
        private int quotientTemporalDistance;
        private int quotientQuantifierDistance;
        private int quotientMatrixDistance;
        private double normalizedCanonicalDistance;
        private int canonicalRepresentativeTreeDistance;
        private double normalizedCanonicalRepresentativeTreeDistance;
        private int legacyCanonicalDistance;
        private int legacyTemporalDistance;
        private int legacyQuantifierDistance;
        private int legacyMatrixDistance;
        private double normalizedLegacyCanonicalDistance;
        private String leftExactDigest;
        private String rightExactDigest;
        private long exactEclasses;
        private long exactEnodes;
        private long exactSlots;
        private long exactRebuilds;
        private long leftExactPreparationNanos;
        private long rightExactPreparationNanos;
        private long exactDistanceNanos;
        private long canonicalRepresentativeTreeDistanceNanos;
        private long exactConstructionNanos;
        private long exactUnfoldingNanos;
        private long exactObservationNanos;
        private long exactRepairProjectionNanos;
        private boolean quotientDistanceExactForStoredOrbits;
        private long quotientBinderAlignments;
        private int rewardPoolSize;
        private double candidateReward;
        private double groundTruthReward;
        private double rewardGap;
        private String rewardError;
        private boolean rewardComputed;
        private boolean rewardSkipped;
        private List<String> leftIRTemporalFOL = new ArrayList<>();
        private List<String> rightIRTemporalFOL = new ArrayList<>();
        private List<String> edits = new ArrayList<>();
        private boolean representationSizesAvailable;
        private boolean skipped;
        private String skipReason;
        private String error;

        private FileResult(Path root, Path file) {
            Path relative = root.relativize(file);
            this.fileName = file.getFileName().toString();
            this.relativePath = relative.toString().replace('\\', '/');
            this.problemClass = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";
            this.statusFolder = DatasetConventions.normalizeStatusFolder(
                    relative.getNameCount() > 1 ? relative.getName(1).toString() : "");
        }

        private boolean success() {
            return error == null && !skipped;
        }

        private double leftCanonicalCompressionRatePercent() {
            return compressionRatePercent(leftRawAstSize, leftCanonicalFormSize);
        }

        private String status() {
            if (success()) {
                return "distance " + distance;
            }
            return skipped ? "skipped " + skipReason : "error " + error;
        }
    }

    private static class Summary {
        private final boolean rewardsEnabled;
        private int total;
        private int successes;
        private int skipped;
        private int failures;
        private long distanceSum;
        private long canonicalRepresentativeTreeDistanceSum;
        private long legacyCanonicalDistanceSum;
        private long levenshteinDistanceSum;
        private long rawAstTreeDistanceSum;
        private long rawAstSizeSum;
        private long canonicalFormSizeSum;
        private long canonicalRepresentativeTreeSizeSum;
        private long legacyCanonicalFormSizeSum;
        private double normalizedLevenshteinDistanceSum;
        private double normalizedRawAstDistanceSum;
        private double normalizedCanonicalDistanceSum;
        private double normalizedCanonicalRepresentativeTreeDistanceSum;
        private double normalizedLegacyCanonicalDistanceSum;
        private int correctCanonicalZeroRawAstNonzero;
        private int incorrectCanonicalZero;
        private int inexactAlphaSearches;
        private long quotientDistanceNanosSum;
        private long representativeDistanceNanosSum;
        private Integer minDistance;
        private Integer maxDistance;
        private int rewardSuccesses;
        private double candidateRewardSum;
        private double groundTruthRewardSum;
        private double rewardGapSum;
        private int distanceRewardSamples;
        private double distanceRewardXSum;
        private double distanceRewardYSum;
        private double distanceRewardXXSum;
        private double distanceRewardYYSum;
        private double distanceRewardXYSum;
        private double levenshteinRewardXSum;
        private double levenshteinRewardXXSum;
        private double levenshteinRewardXYSum;
        private double rawAstRewardXSum;
        private double rawAstRewardXXSum;
        private double rawAstRewardXYSum;
        private double normalizedRawAstRewardXSum;
        private double normalizedRawAstRewardXXSum;
        private double normalizedRawAstRewardXYSum;
        private double normalizedCanonicalRewardXSum;
        private double normalizedCanonicalRewardXXSum;
        private double normalizedCanonicalRewardXYSum;
        private double representativeRewardXSum;
        private double representativeRewardXXSum;
        private double representativeRewardXYSum;
        private double normalizedRepresentativeRewardXSum;
        private double normalizedRepresentativeRewardXXSum;
        private double normalizedRepresentativeRewardXYSum;
        private double legacyRewardXSum;
        private double legacyRewardXXSum;
        private double legacyRewardXYSum;
        private double normalizedLegacyRewardXSum;
        private double normalizedLegacyRewardXXSum;
        private double normalizedLegacyRewardXYSum;
        private int representationSamples;
        private long studentRawAstSizeSum;
        private long studentCanonicalFormSizeSum;
        private Map<String, Stats> groupStats = new java.util.TreeMap<>();
        private List<FileResult> failureSamples = new ArrayList<>();

        private Summary(boolean rewardsEnabled) {
            this.rewardsEnabled = rewardsEnabled;
        }

        private void add(FileResult result) {
            total++;
            Stats stats = groupStats.computeIfAbsent(
                    result.problemClass + "/" + result.statusFolder,
                    key -> new Stats(result.problemClass, result.statusFolder));
            stats.add(result);
            if (result.representationSizesAvailable) {
                representationSamples++;
                studentRawAstSizeSum += result.leftRawAstSize;
                studentCanonicalFormSizeSum += result.leftCanonicalFormSize;
            }
            if (result.skipped) {
                skipped++;
            } else if (result.success()) {
                successes++;
                distanceSum += result.distance;
                canonicalRepresentativeTreeDistanceSum +=
                        result.canonicalRepresentativeTreeDistance;
                legacyCanonicalDistanceSum += result.legacyCanonicalDistance;
                levenshteinDistanceSum += result.predicateBodyLevenshteinDistance;
                rawAstTreeDistanceSum += result.rawAstTreeDistance;
                rawAstSizeSum += result.rawAstSize;
                canonicalFormSizeSum += result.canonicalFormSize;
                canonicalRepresentativeTreeSizeSum += result.canonicalRepresentativeTreeSize;
                legacyCanonicalFormSizeSum += result.legacyCanonicalFormSize;
                normalizedLevenshteinDistanceSum += result.normalizedLevenshteinDistance;
                normalizedRawAstDistanceSum += result.normalizedRawAstDistance;
                normalizedCanonicalDistanceSum += result.normalizedCanonicalDistance;
                normalizedCanonicalRepresentativeTreeDistanceSum +=
                        result.normalizedCanonicalRepresentativeTreeDistance;
                normalizedLegacyCanonicalDistanceSum += result.normalizedLegacyCanonicalDistance;
                quotientDistanceNanosSum += result.exactDistanceNanos;
                representativeDistanceNanosSum += result.canonicalRepresentativeTreeDistanceNanos;
                if (!result.quotientDistanceExactForStoredOrbits) {
                    inexactAlphaSearches++;
                }
                if ("CORRECT".equals(result.statusFolder)
                        && result.distance == 0
                        && result.rawAstTreeDistance > 0) {
                    correctCanonicalZeroRawAstNonzero++;
                }
                if (!"CORRECT".equals(result.statusFolder) && result.distance == 0) {
                    incorrectCanonicalZero++;
                }
                minDistance = minDistance == null ? result.distance : Math.min(minDistance, result.distance);
                maxDistance = maxDistance == null ? result.distance : Math.max(maxDistance, result.distance);
                if (result.rewardComputed && result.rewardError == null) {
                    rewardSuccesses++;
                    candidateRewardSum += result.candidateReward;
                    groundTruthRewardSum += result.groundTruthReward;
                    rewardGapSum += result.rewardGap;
                    if (isCorrelationEligible(result)) {
                        distanceRewardSamples++;
                        distanceRewardXSum += result.distance;
                        distanceRewardYSum += result.candidateReward;
                        distanceRewardXXSum += (double) result.distance * result.distance;
                        distanceRewardYYSum += result.candidateReward * result.candidateReward;
                        distanceRewardXYSum += result.distance * result.candidateReward;
                        levenshteinRewardXSum += result.predicateBodyLevenshteinDistance;
                        levenshteinRewardXXSum += (double) result.predicateBodyLevenshteinDistance
                                * result.predicateBodyLevenshteinDistance;
                        levenshteinRewardXYSum += result.predicateBodyLevenshteinDistance * result.candidateReward;
                        rawAstRewardXSum += result.rawAstTreeDistance;
                        rawAstRewardXXSum += (double) result.rawAstTreeDistance * result.rawAstTreeDistance;
                        rawAstRewardXYSum += result.rawAstTreeDistance * result.candidateReward;
                        normalizedRawAstRewardXSum += result.normalizedRawAstDistance;
                        normalizedRawAstRewardXXSum += result.normalizedRawAstDistance
                                * result.normalizedRawAstDistance;
                        normalizedRawAstRewardXYSum += result.normalizedRawAstDistance * result.candidateReward;
                        normalizedCanonicalRewardXSum += result.normalizedCanonicalDistance;
                        normalizedCanonicalRewardXXSum += result.normalizedCanonicalDistance
                                * result.normalizedCanonicalDistance;
                        normalizedCanonicalRewardXYSum += result.normalizedCanonicalDistance * result.candidateReward;
                        representativeRewardXSum += result.canonicalRepresentativeTreeDistance;
                        representativeRewardXXSum += (double) result.canonicalRepresentativeTreeDistance
                                * result.canonicalRepresentativeTreeDistance;
                        representativeRewardXYSum += result.canonicalRepresentativeTreeDistance
                                * result.candidateReward;
                        normalizedRepresentativeRewardXSum +=
                                result.normalizedCanonicalRepresentativeTreeDistance;
                        normalizedRepresentativeRewardXXSum +=
                                result.normalizedCanonicalRepresentativeTreeDistance
                                * result.normalizedCanonicalRepresentativeTreeDistance;
                        normalizedRepresentativeRewardXYSum +=
                                result.normalizedCanonicalRepresentativeTreeDistance
                                * result.candidateReward;
                        legacyRewardXSum += result.legacyCanonicalDistance;
                        legacyRewardXXSum += (double) result.legacyCanonicalDistance
                                * result.legacyCanonicalDistance;
                        legacyRewardXYSum += result.legacyCanonicalDistance * result.candidateReward;
                        normalizedLegacyRewardXSum += result.normalizedLegacyCanonicalDistance;
                        normalizedLegacyRewardXXSum += result.normalizedLegacyCanonicalDistance
                                * result.normalizedLegacyCanonicalDistance;
                        normalizedLegacyRewardXYSum += result.normalizedLegacyCanonicalDistance
                                * result.candidateReward;
                    }
                }
            } else {
                failures++;
                if (failureSamples.size() < 25) {
                    failureSamples.add(result);
                }
            }
        }

        private double averageDistance() {
            return successes == 0 ? 0.0 : (double) distanceSum / successes;
        }

        private double averageLegacyCanonicalDistance() {
            return successes == 0 ? 0.0 : (double) legacyCanonicalDistanceSum / successes;
        }

        private double averageCanonicalRepresentativeTreeDistance() {
            return successes == 0 ? 0.0
                    : (double) canonicalRepresentativeTreeDistanceSum / successes;
        }

        private double averageLevenshteinDistance() {
            return successes == 0 ? 0.0 : (double) levenshteinDistanceSum / successes;
        }

        private double averageRawAstTreeDistance() {
            return successes == 0 ? 0.0 : (double) rawAstTreeDistanceSum / successes;
        }

        private double averageRawAstSize() {
            return successes == 0 ? 0.0 : (double) rawAstSizeSum / successes;
        }

        private double averageCanonicalFormSize() {
            return successes == 0 ? 0.0 : (double) canonicalFormSizeSum / successes;
        }

        private double averageLegacyCanonicalFormSize() {
            return successes == 0 ? 0.0 : (double) legacyCanonicalFormSizeSum / successes;
        }

        private double averageCanonicalRepresentativeTreeSize() {
            return successes == 0 ? 0.0
                    : (double) canonicalRepresentativeTreeSizeSum / successes;
        }

        private double averageNormalizedLevenshteinDistance() {
            return successes == 0 ? 0.0 : normalizedLevenshteinDistanceSum / successes;
        }

        private double averageNormalizedRawAstDistance() {
            return successes == 0 ? 0.0 : normalizedRawAstDistanceSum / successes;
        }

        private double averageNormalizedCanonicalDistance() {
            return successes == 0 ? 0.0 : normalizedCanonicalDistanceSum / successes;
        }

        private double averageNormalizedCanonicalRepresentativeTreeDistance() {
            return successes == 0 ? 0.0
                    : normalizedCanonicalRepresentativeTreeDistanceSum / successes;
        }

        private double averageNormalizedLegacyCanonicalDistance() {
            return successes == 0 ? 0.0 : normalizedLegacyCanonicalDistanceSum / successes;
        }

        private double averageQuotientDistanceNanos() {
            return successes == 0 ? 0.0 : (double) quotientDistanceNanosSum / successes;
        }

        private double averageRepresentativeDistanceNanos() {
            return successes == 0 ? 0.0 : (double) representativeDistanceNanosSum / successes;
        }

        private double averageStudentRawAstSize() {
            return representationSamples == 0 ? 0.0 : (double) studentRawAstSizeSum / representationSamples;
        }

        private double averageStudentCanonicalFormSize() {
            return representationSamples == 0 ? 0.0 : (double) studentCanonicalFormSizeSum / representationSamples;
        }

        private double studentCanonicalCompressionRatePercent() {
            return compressionRatePercent(studentRawAstSizeSum, studentCanonicalFormSizeSum);
        }

        private int rewardFailures() {
            return rewardsEnabled ? successes - rewardSuccesses : 0;
        }

        private double averageCandidateReward() {
            return rewardSuccesses == 0 ? 0.0 : candidateRewardSum / rewardSuccesses;
        }

        private double averageGroundTruthReward() {
            return rewardSuccesses == 0 ? 0.0 : groundTruthRewardSum / rewardSuccesses;
        }

        private double averageRewardGap() {
            return rewardSuccesses == 0 ? 0.0 : rewardGapSum / rewardSuccesses;
        }

        private double distanceRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    distanceRewardXSum,
                    distanceRewardYSum,
                    distanceRewardXXSum,
                    distanceRewardYYSum,
                    distanceRewardXYSum);
        }

        private double levenshteinRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    levenshteinRewardXSum,
                    distanceRewardYSum,
                    levenshteinRewardXXSum,
                    distanceRewardYYSum,
                    levenshteinRewardXYSum);
        }

        private double rawAstRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    rawAstRewardXSum,
                    distanceRewardYSum,
                    rawAstRewardXXSum,
                    distanceRewardYYSum,
                    rawAstRewardXYSum);
        }

        private double normalizedRawAstRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    normalizedRawAstRewardXSum,
                    distanceRewardYSum,
                    normalizedRawAstRewardXXSum,
                    distanceRewardYYSum,
                    normalizedRawAstRewardXYSum);
        }

        private double normalizedCanonicalRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    normalizedCanonicalRewardXSum,
                    distanceRewardYSum,
                    normalizedCanonicalRewardXXSum,
                    distanceRewardYYSum,
                    normalizedCanonicalRewardXYSum);
        }

        private double representativeRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    representativeRewardXSum,
                    distanceRewardYSum,
                    representativeRewardXXSum,
                    distanceRewardYYSum,
                    representativeRewardXYSum);
        }

        private double normalizedRepresentativeRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    normalizedRepresentativeRewardXSum,
                    distanceRewardYSum,
                    normalizedRepresentativeRewardXXSum,
                    distanceRewardYYSum,
                    normalizedRepresentativeRewardXYSum);
        }

        private double legacyRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    legacyRewardXSum,
                    distanceRewardYSum,
                    legacyRewardXXSum,
                    distanceRewardYYSum,
                    legacyRewardXYSum);
        }

        private double normalizedLegacyRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    normalizedLegacyRewardXSum,
                    distanceRewardYSum,
                    normalizedLegacyRewardXXSum,
                    distanceRewardYYSum,
                    normalizedLegacyRewardXYSum);
        }

        private String minDistanceJson() {
            return minDistance == null ? "null" : minDistance.toString();
        }

        private String maxDistanceJson() {
            return maxDistance == null ? "null" : maxDistance.toString();
        }

        private String minDistanceMarkdown() {
            return minDistance == null ? "n/a" : minDistance.toString();
        }

        private String maxDistanceMarkdown() {
            return maxDistance == null ? "n/a" : maxDistance.toString();
        }
    }

    private static class Stats {
        private final String problemClass;
        private final String statusFolder;
        private int total;
        private int successes;
        private int skipped;
        private int failures;
        private long distanceSum;
        private long canonicalRepresentativeTreeDistanceSum;
        private long legacyCanonicalDistanceSum;
        private long levenshteinDistanceSum;
        private long rawAstTreeDistanceSum;
        private double normalizedLevenshteinDistanceSum;
        private double normalizedRawAstDistanceSum;
        private double normalizedCanonicalDistanceSum;
        private double normalizedCanonicalRepresentativeTreeDistanceSum;
        private double normalizedLegacyCanonicalDistanceSum;
        private Integer minDistance;
        private Integer maxDistance;
        private int rewardSuccesses;
        private double candidateRewardSum;
        private int distanceRewardSamples;
        private double distanceRewardXSum;
        private double distanceRewardYSum;
        private double distanceRewardXXSum;
        private double distanceRewardYYSum;
        private double distanceRewardXYSum;
        private int representationSamples;
        private long studentRawAstSizeSum;
        private long studentCanonicalFormSizeSum;

        private Stats(String problemClass, String statusFolder) {
            this.problemClass = problemClass;
            this.statusFolder = statusFolder;
        }

        private void add(FileResult result) {
            total++;
            if (result.representationSizesAvailable) {
                representationSamples++;
                studentRawAstSizeSum += result.leftRawAstSize;
                studentCanonicalFormSizeSum += result.leftCanonicalFormSize;
            }
            if (result.skipped) {
                skipped++;
            } else if (result.success()) {
                successes++;
                distanceSum += result.distance;
                canonicalRepresentativeTreeDistanceSum +=
                        result.canonicalRepresentativeTreeDistance;
                legacyCanonicalDistanceSum += result.legacyCanonicalDistance;
                levenshteinDistanceSum += result.predicateBodyLevenshteinDistance;
                rawAstTreeDistanceSum += result.rawAstTreeDistance;
                normalizedLevenshteinDistanceSum += result.normalizedLevenshteinDistance;
                normalizedRawAstDistanceSum += result.normalizedRawAstDistance;
                normalizedCanonicalDistanceSum += result.normalizedCanonicalDistance;
                normalizedCanonicalRepresentativeTreeDistanceSum +=
                        result.normalizedCanonicalRepresentativeTreeDistance;
                normalizedLegacyCanonicalDistanceSum += result.normalizedLegacyCanonicalDistance;
                minDistance = minDistance == null ? result.distance : Math.min(minDistance, result.distance);
                maxDistance = maxDistance == null ? result.distance : Math.max(maxDistance, result.distance);
                if (result.rewardComputed && result.rewardError == null) {
                    rewardSuccesses++;
                    candidateRewardSum += result.candidateReward;
                    if (isCorrelationEligible(result)) {
                        distanceRewardSamples++;
                        distanceRewardXSum += result.distance;
                        distanceRewardYSum += result.candidateReward;
                        distanceRewardXXSum += (double) result.distance * result.distance;
                        distanceRewardYYSum += result.candidateReward * result.candidateReward;
                        distanceRewardXYSum += result.distance * result.candidateReward;
                    }
                }
            } else {
                failures++;
            }
        }

        private double averageDistance() {
            return successes == 0 ? 0.0 : (double) distanceSum / successes;
        }

        private double averageLevenshteinDistance() {
            return successes == 0 ? 0.0 : (double) levenshteinDistanceSum / successes;
        }

        private double averageCanonicalRepresentativeTreeDistance() {
            return successes == 0 ? 0.0
                    : (double) canonicalRepresentativeTreeDistanceSum / successes;
        }

        private double averageLegacyCanonicalDistance() {
            return successes == 0 ? 0.0 : (double) legacyCanonicalDistanceSum / successes;
        }

        private double averageRawAstTreeDistance() {
            return successes == 0 ? 0.0 : (double) rawAstTreeDistanceSum / successes;
        }

        private double averageNormalizedLevenshteinDistance() {
            return successes == 0 ? 0.0 : normalizedLevenshteinDistanceSum / successes;
        }

        private double averageNormalizedRawAstDistance() {
            return successes == 0 ? 0.0 : normalizedRawAstDistanceSum / successes;
        }

        private double averageNormalizedCanonicalDistance() {
            return successes == 0 ? 0.0 : normalizedCanonicalDistanceSum / successes;
        }

        private double averageNormalizedCanonicalRepresentativeTreeDistance() {
            return successes == 0 ? 0.0
                    : normalizedCanonicalRepresentativeTreeDistanceSum / successes;
        }

        private double averageNormalizedLegacyCanonicalDistance() {
            return successes == 0 ? 0.0 : normalizedLegacyCanonicalDistanceSum / successes;
        }

        private double averageCandidateReward() {
            return rewardSuccesses == 0 ? 0.0 : candidateRewardSum / rewardSuccesses;
        }

        private double averageStudentRawAstSize() {
            return representationSamples == 0 ? 0.0 : (double) studentRawAstSizeSum / representationSamples;
        }

        private double averageStudentCanonicalFormSize() {
            return representationSamples == 0 ? 0.0 : (double) studentCanonicalFormSizeSum / representationSamples;
        }

        private double studentCanonicalCompressionRatePercent() {
            return compressionRatePercent(studentRawAstSizeSum, studentCanonicalFormSizeSum);
        }

        private double distanceRewardCorrelation() {
            return correlation(
                    distanceRewardSamples,
                    distanceRewardXSum,
                    distanceRewardYSum,
                    distanceRewardXXSum,
                    distanceRewardYYSum,
                    distanceRewardXYSum);
        }

        private String minDistanceMarkdown() {
            return minDistance == null ? "n/a" : minDistance.toString();
        }

        private String maxDistanceMarkdown() {
            return maxDistance == null ? "n/a" : maxDistance.toString();
        }
    }

    private static boolean isCorrelationEligible(FileResult result) {
        return !"CORRECT".equals(result.statusFolder);
    }

    private static double correlation(int n, double xSum, double ySum, double xxSum, double yySum, double xySum) {
        if (n < 2) {
            return 0.0;
        }
        double numerator = n * xySum - xSum * ySum;
        double xDenominator = n * xxSum - xSum * xSum;
        double yDenominator = n * yySum - ySum * ySum;
        if (xDenominator <= 0.0 || yDenominator <= 0.0) {
            return 0.0;
        }
        return numerator / Math.sqrt(xDenominator * yDenominator);
    }
}
