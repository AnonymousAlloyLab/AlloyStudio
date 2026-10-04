package is.fivefivefive.CanDis;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
import parser.ast.nodes.Predicate;
import parser.etc.Pair;
import parser.util.AlloyUtil;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import is.fivefivefive.CanDis.theory.BoundedFiniteUnfoldingOracle;
import is.fivefivefive.CanDis.theory.CertificateVerifier;
import is.fivefivefive.CanDis.theory.ProductionGraphCanonicalizer;

public class Alloy4FunAugmenter {
    private static final String DEFAULT_INPUT = "classified-data";
    private static final String DEFAULT_OUTPUT = "alloy4fun-augmented";
    private static final int DEFAULT_THREAD_COUNT = 32;
    private static final int DEFAULT_REWARD_POOL_SIZE = 100;
    private static final int[] REPAIR_RADII = { 1, 2, 5, 10 };
    private static final double[] RELATIVE_REPAIR_RADII = { 0.05, 0.10, 0.20, 0.50 };
    private static final int RELATIVE_REPAIR_CURVE_STEPS = 100;
    private static final int RAW_EDIT_REPAIR_PLOT_MAX_DISTANCE = 50;
    private static final int RAW_EDIT_REPAIR_PLOT_TICK_COUNT = 5;
    private static final int REPORT_BUFFER_SIZE = 1024 * 1024;
    private static final int EXPECTED_FULL_SOURCE_FILES = 66_080;
    private static final int EXPECTED_FULL_CONSIDERED_FILES = 61_598;

    public static void main(String[] args) throws IOException {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        Options options = Options.parse(args);
        Files.createDirectories(options.outputDir);
        List<Path> files = alloyFiles(options.inputDir);
        if (options.limit > 0 && options.limit < files.size()) {
            files = new ArrayList<>(files.subList(0, options.limit));
        }

        long stageStarted = beginStage("Parsing " + files.size() + " Alloy models");
        List<ModelRecord> parsedRecords = parseRecords(files, options);
        endStage("Parsing Alloy models", stageStarted);
        Path parseRetriesPath = options.outputDir.resolve("parse_retries.csv");
        writeParseRetries(parseRetriesPath, parsedRecords);

        List<ModelRecord> astIdenticalPredicatePairs = new ArrayList<>();
        List<ModelRecord> records = new ArrayList<>(parsedRecords.size());
        for (ModelRecord record : parsedRecords) {
            if (record.astIdenticalStudentOracle) {
                astIdenticalPredicatePairs.add(record);
            } else {
                records.add(record);
            }
        }
        validateCorpusSelection(options, files.size(), records.size(), astIdenticalPredicatePairs.size());
        Path astIdenticalPairsPath = options.outputDir.resolve("ast_identical_predicate_pairs.csv");
        writeAstIdenticalPredicatePairs(astIdenticalPairsPath, astIdenticalPredicatePairs);
        System.err.println("[Alloy4FunAugmenter] Excluded " + astIdenticalPredicatePairs.size()
                + " AST-identical student/oracle pairs before pool construction; considering "
                + records.size() + " models.");

        stageStarted = beginStage("Building correct-reference pools");
        Map<String, QuestionGroup> groups = groups(records);
        ExperimentProgress poolProgress = ExperimentProgress.start(
                System.err,
                "Alloy4FunAugmenter/reference-pools",
                groups.size(),
                "groups");
        int completedGroups = 0;
        for (QuestionGroup group : groups.values()) {
            group.buildReferences(options.verbose);
            poolProgress.update(++completedGroups);
        }
        poolProgress.finish(completedGroups);
        endStage("Building correct-reference pools", stageStarted);

        List<AstIdenticalComparison> astIdenticalComparisons = astIdenticalComparisons(groups);
        Path astIdentityAudit = options.outputDir.resolve("ast_identical_cross_file_comparisons.csv");
        writeAstIdenticalComparisons(astIdentityAudit, astIdenticalComparisons);
        if (options.auditOnly) {
            System.out.println("Wrote " + astIdentityAudit);
            return;
        }

        stageStarted = beginStage("Writing augmented correct pools");
        writeAugmentedFiles(groups, options.outputDir);
        endStage("Writing augmented correct pools", stageStarted);

        stageStarted = beginStage("Comparing canonically equivalent correct pairs");
        CorrectPoolEquivalences correctPoolEquivalences =
                correctAstDifferentCanonicalEquivalentPairs(groups, options.threadCount);
        writeCorrectPoolEquivalenceJson(
                options.outputDir.resolve("correct_ast_diff_canonical_equiv.json"),
                options,
                correctPoolEquivalences.certificateIntegrated,
                "Pairs within each augmented correct pool whose raw AST distance is greater than zero "
                        + "and Certificate-Integrated IR distance is zero.");
        writeCorrectPoolEquivalenceJson(
                options.outputDir.resolve("correct_ast_diff_fast_rewrite_equiv.json"),
                options,
                correctPoolEquivalences.fastRewrite,
                "Pairs within each augmented correct pool whose raw AST distance is greater than zero "
                        + "and Fast Rewrite IR distance is zero.");
        endStage("Comparing canonically equivalent correct pairs", stageStarted);
        logRankingWork(groups);

        List<ModelRecord> unmatched = incorrectWithoutReference(groups);
        List<ModelRecord> withoutAstDistinctReference = incorrectWithoutAstDistinctReference(groups);
        stageStarted = beginStage("Ranking incorrect predicates");
        List<IncorrectMatch> matches = nearestIncorrectMatches(groups, options);
        endStage("Ranking incorrect predicates", stageStarted);
        Path incorrectZeroAudit = options.outputDir.resolve(
                "incorrect_nearest_zero_distances.csv");
        int certifiedIncorrectZeroes = writeIncorrectNearestZeroAudit(
                incorrectZeroAudit, matches);
        if (certifiedIncorrectZeroes != 0) {
            throw new IllegalStateException(
                    "Certified ranking produced " + certifiedIncorrectZeroes
                            + " incorrect predicate(s) at zero distance from a truth; see "
                            + incorrectZeroAudit);
        }
        if (!options.skipRewards) {
            stageStarted = beginStage("Computing rewards");
            computeRewards(matches, options);
            endStage("Computing rewards", stageStarted);
        }

        stageStarted = beginStage("Writing reports and plots");
        writeJson(options.outputDir.resolve("index.json"), options, files.size(),
                astIdenticalPredicatePairs.size(), groups, records, matches,
                unmatched, withoutAstDistinctReference, correctPoolEquivalences);
        writeMarkdown(
                options.outputDir.resolve("summary.md"),
                options,
                files.size(),
                astIdenticalPredicatePairs.size(),
                groups,
                records,
                matches,
                unmatched,
                withoutAstDistinctReference,
                correctPoolEquivalences);
        writeRewardCsv(options.outputDir.resolve("canonical_reward_points.csv"), matches);
        writeRewardPlots(options.outputDir, matches);
        writeRelativeRepairCoveragePlot(options.outputDir.resolve("relative_repair_coverage_comparison.svg"), matches);
        writeRawEditRepairCoveragePlot(options.outputDir.resolve("raw_edit_repair_coverage_ast_canonical.svg"), matches);
        writePlotScript(options.outputDir.resolve("plot_canonical_rewards.py"));
        endStage("Writing reports and plots", stageStarted);
        System.out.println("Wrote " + options.outputDir);
        System.out.println("Wrote " + parseRetriesPath);
        System.out.println("Wrote " + astIdenticalPairsPath);
        System.out.println("Wrote " + incorrectZeroAudit);
        System.out.println("Wrote " + options.outputDir.resolve("index.json"));
        System.out.println("Wrote " + options.outputDir.resolve("correct_ast_diff_canonical_equiv.json"));
        System.out.println("Wrote " + options.outputDir.resolve("correct_ast_diff_fast_rewrite_equiv.json"));
        System.out.println("Wrote " + options.outputDir.resolve("summary.md"));
        System.out.println("Wrote " + options.outputDir.resolve("canonical_reward_points.csv"));
        System.out.println("Wrote " + options.outputDir.resolve("canonical_distance_vs_reward_error_raw.svg"));
        System.out.println("Wrote " + options.outputDir.resolve("canonical_distance_vs_reward_error_log.svg"));
        System.out.println("Wrote " + options.outputDir.resolve("relative_repair_coverage_comparison.svg"));
        System.out.println("Wrote " + options.outputDir.resolve("raw_edit_repair_coverage_ast_canonical.svg"));
        System.out.println("Wrote " + options.outputDir.resolve("plot_canonical_rewards.py"));
    }

    private static long beginStage(String label) {
        System.err.println("[Alloy4FunAugmenter] " + label + "...");
        return System.nanoTime();
    }

    private static void endStage(String label, long started) {
        double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
        System.err.println("[Alloy4FunAugmenter] " + label + " completed in "
                + String.format(java.util.Locale.ROOT, "%.3f", seconds) + " s");
    }

    private static void validateCorpusSelection(
            Options options,
            int sourceFiles,
            int consideredFiles,
            int astIdenticalFiles) {
        if (sourceFiles != consideredFiles + astIdenticalFiles) {
            throw new IllegalStateException("Corpus partition mismatch: source=" + sourceFiles
                    + ", considered=" + consideredFiles + ", AST-identical=" + astIdenticalFiles);
        }
        if (sourceFiles == EXPECTED_FULL_SOURCE_FILES
                && consideredFiles != EXPECTED_FULL_CONSIDERED_FILES) {
            throw new IllegalStateException("Full-corpus AST filter invariant violated: expected "
                    + EXPECTED_FULL_CONSIDERED_FILES + " considered files after excluding student/oracle "
                    + "AST-identical predicates from " + EXPECTED_FULL_SOURCE_FILES + ", but found "
                    + consideredFiles + " (excluded " + astIdenticalFiles + "). Input=" + options.inputDir);
        }
    }

    private static List<ModelRecord> parseRecords(List<Path> files, Options options) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ConcurrentMap<String, RawAstTree> astCache = new ConcurrentHashMap<>();
        ConcurrentMap<RepresentationKey, Canonical.Prepared> canonicalCache = new ConcurrentHashMap<>();
        ConcurrentMap<RepresentationKey, CanonicalAlloyPipeline.Prepared> exactCache =
                new ConcurrentHashMap<>();
        int canonicalWorkers = ExperimentMemoryBudget.effectiveWorkers(options.threadCount);
        Semaphore canonicalPermits = new Semaphore(canonicalWorkers, true);
        if (!options.verbose) {
            PrintStream sink = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });
            System.setOut(sink);
            System.setErr(sink);
        }
        int workers = Math.max(1, options.threadCount);
        int maximumInFlight = workers > Integer.MAX_VALUE / 4
                ? Integer.MAX_VALUE
                : workers * 4;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            CompletionService<IndexedModelRecord> completion =
                    new ExecutorCompletionService<>(executor);
            Map<Future<IndexedModelRecord>, Integer> active = new HashMap<>();
            List<ModelRecord> records = new ArrayList<>(
                    java.util.Collections.nCopies(files.size(), null));
            int submitted = 0;
            while (submitted < files.size() && active.size() < maximumInFlight) {
                final int fileIndex = submitted++;
                Future<IndexedModelRecord> future = completion.submit(
                        () -> new IndexedModelRecord(fileIndex, parseRecord(
                                options.inputDir,
                                files.get(fileIndex),
                                options.verbose,
                                astCache,
                                canonicalCache,
                                exactCache,
                                canonicalPermits)));
                active.put(future, fileIndex);
            }
            ExperimentProgress progress = ExperimentProgress.start(
                    originalErr,
                    "Alloy4FunAugmenter/parse",
                    files.size(),
                    "files",
                    "with " + workers + " workers, " + canonicalWorkers
                            + " canonical builders, and at most " + maximumInFlight
                            + " tasks in flight");
            int completed = 0;
            while (completed < files.size()) {
                Future<IndexedModelRecord> future;
                try {
                    future = completion.poll(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Alloy model parsing was interrupted", e);
                }
                if (future == null) {
                    progress.heartbeat(
                            completed,
                            active.size(),
                            earliestUnresolvedDetail(active, files, options.inputDir));
                    continue;
                }
                Integer expectedIndex = active.remove(future);
                int index = expectedIndex == null ? completed : expectedIndex;
                try {
                    IndexedModelRecord indexed = future.get();
                    index = indexed.index;
                    records.set(index, indexed.record);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Alloy model parsing was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rethrowVirtualMachineError(cause);
                    records.set(index, ModelRecord.failed(options.inputDir, files.get(index),
                            cause.getClass().getSimpleName() + ": " + cause.getMessage()));
                }
                progress.update(++completed);
                while (submitted < files.size() && active.size() < maximumInFlight) {
                    final int fileIndex = submitted++;
                    Future<IndexedModelRecord> next = completion.submit(
                            () -> new IndexedModelRecord(fileIndex, parseRecord(
                                    options.inputDir,
                                    files.get(fileIndex),
                                    options.verbose,
                                    astCache,
                                    canonicalCache,
                                    exactCache,
                                    canonicalPermits)));
                    active.put(next, fileIndex);
                }
            }
            progress.finish(completed);
            List<Integer> failedIndexes = new ArrayList<>();
            CompletionService<IndexedModelRecord> retries =
                    new ExecutorCompletionService<>(executor);
            for (int i = 0; i < records.size(); i++) {
                if (!records.get(i).success()) {
                    final int failedIndex = i;
                    failedIndexes.add(failedIndex);
                    retries.submit(() -> new IndexedModelRecord(failedIndex, parseRecord(
                                    options.inputDir,
                                    files.get(failedIndex),
                                    options.verbose,
                                    astCache,
                                    canonicalCache,
                                    exactCache,
                                    canonicalPermits)));
                }
            }
            if (!failedIndexes.isEmpty()) {
                originalErr.println("[Alloy4FunAugmenter] Retrying " + failedIndexes.size()
                        + " parse failures in parallel...");
            }
            ExperimentProgress retryProgress = ExperimentProgress.start(
                    originalErr,
                    "Alloy4FunAugmenter/parse-retry",
                    failedIndexes.size(),
                    "files");
            int retried = 0;
            while (retried < failedIndexes.size()) {
                try {
                    Future<IndexedModelRecord> future = retries.poll(30, TimeUnit.SECONDS);
                    if (future == null) {
                        retryProgress.heartbeat(
                                retried, failedIndexes.size() - retried, null);
                        continue;
                    }
                    IndexedModelRecord retry = future.get();
                    ModelRecord initial = records.get(retry.index);
                    retry.record.retriedAfterFailure = true;
                    retry.record.initialParseError = initial.error;
                    records.set(retry.index, retry.record);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rethrowVirtualMachineError(cause);
                }
                retryProgress.update(++retried);
            }
            retryProgress.finish(retried);
            astCache.clear();
            canonicalCache.clear();
            exactCache.clear();
            return records;
        } finally {
            executor.shutdownNow();
            if (!options.verbose) {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static ModelRecord parseRecord(
            Path inputRoot,
            Path file,
            boolean verbose,
            ConcurrentMap<String, RawAstTree> astCache,
            ConcurrentMap<RepresentationKey, Canonical.Prepared> canonicalCache,
            ConcurrentMap<RepresentationKey, CanonicalAlloyPipeline.Prepared> exactCache,
            Semaphore canonicalPermits) {
        ModelRecord record = new ModelRecord(inputRoot, file);
        try {
            CompModule module = AlloyUtil.compileAlloyModule(file.toString());
            if (module == null) {
                record.error = "Alloy parser returned no module.";
                return record;
            }
            ModelUnit model = new ModelUnit(null, module);
            PredicatePair pair = findPredicatePair(file, model);
            if (pair == null) {
                record.error = "No predicate pair of the form X and X[Cc] found.";
                return record;
            }
            if (record.invariantId == null || record.invariantId.isEmpty()) {
                record.invariantId = pair.leftName;
            }
            record.leftPredicate = pair.leftName;
            record.rightPredicate = pair.rightName;
            if (DatasetConventions.sameRawAst(pair.left.getBody(), pair.right.getBody())) {
                record.astIdenticalStudentOracle = true;
                return record;
            }
            record.studentBody = predicateBody(file, pair.leftName, pair.left);
            record.oracleBody = predicateBody(file, pair.rightName, pair.right);
            record.levenshteinSize = record.studentBody.length();
            record.prelude = preludeBeforePredicate(Files.readString(file, StandardCharsets.UTF_8), pair.leftName);
            record.studentAst = internAst(RawAstTree.from(pair.left.getBody()), astCache);
            record.oracleAst = internAst(RawAstTree.from(pair.right.getBody()), astCache);
            record.oracleAstFingerprint = record.oracleAst.fingerprint;
            record.contextFingerprint = modelContextFingerprint(model, pair);
            record.rawAstSize = record.studentAst.size;

            if (!"CORRECT".equals(record.statusFolder)) {
                return record;
            }
            acquireCanonicalPermit(canonicalPermits);
            try {
                MASGVisitor visitor = new MASGVisitor(
                        new GlobalVariables(), module);
                visitor.visit(model, null);
                DoubleMap<Integer, Multigraph> forest = visitor.getForest();
                Multigraph studentGraph = forest.get(pair.leftId);
                Multigraph oracleGraph = forest.get(pair.rightId);
                if (studentGraph == null || oracleGraph == null) {
                    record.error = "Could not find both predicate graphs in MASG forest.";
                } else {
                    record.studentCanonical = canonicalCache.computeIfAbsent(
                            RepresentationKey.student(record),
                            key -> Canonical.prepare(studentGraph));
                    record.oracleCanonical = canonicalCache.computeIfAbsent(
                            RepresentationKey.oracle(record),
                            key -> Canonical.prepare(oracleGraph));
                    record.studentExact = exactCache.computeIfAbsent(
                            RepresentationKey.student(record),
                            key -> CanonicalAlloyPipeline.prepare(record.studentCanonical)
                                    .compactForComparison());
                    record.oracleExact = exactCache.computeIfAbsent(
                            RepresentationKey.oracle(record),
                            key -> CanonicalAlloyPipeline.prepare(record.oracleCanonical)
                                    .compactForComparison());
                    record.legacyCanonicalSize = Canonical.canonicalFormSize(record.studentCanonical);
                    record.canonicalSize = record.studentExact.repairObservationSize();
                }
            } finally {
                canonicalPermits.release();
            }
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable t) {
            if (verbose) {
                t.printStackTrace(System.err);
            }
            record.error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return record;
    }

    private static void writeParseRetries(
            Path path,
            List<ModelRecord> records) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,initialError,finalStatus,finalError\n");
            for (ModelRecord record : records) {
                if (!record.retriedAfterFailure) {
                    continue;
                }
                writer.write(csv(record.relativePath));
                writer.write(',');
                writer.write(csv(record.initialParseError));
                writer.write(',');
                writer.write(record.success() ? "SUCCESS" : "FAILURE");
                writer.write(',');
                writer.write(csv(record.error));
                writer.write('\n');
            }
        }
    }

    private static PreparedPair loadPrepared(ModelRecord record, boolean oracle) {
        try {
            CompModule module = AlloyUtil.compileAlloyModule(record.file.toString());
            ModelUnit model = new ModelUnit(null, module);
            PredicatePair pair = findPredicatePair(record.file, model);
            if (pair == null) {
                throw new IllegalStateException("No predicate pair of the form X and X[Cc] found.");
            }
            MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
            visitor.visit(model, null);
            Multigraph graph = visitor.getForest().get(oracle ? pair.rightId : pair.leftId);
            if (graph == null) {
                throw new IllegalStateException("Could not find predicate graph in MASG forest.");
            }
            Canonical.Prepared legacy = Canonical.prepare(graph);
            return new PreparedPair(
                    legacy,
                    CanonicalAlloyPipeline.prepare(legacy).compactForComparison());
        } catch (RuntimeException e) {
            throw e;
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable t) {
            throw new IllegalStateException(
                    "Could not prepare " + (oracle ? "oracle" : "student") + " canonical form for "
                            + record.relativePath,
                    t);
        }
    }

    private static PreparedPair loadPreparedQuietly(ModelRecord record, boolean oracle, boolean verbose) {
        if (verbose) {
            return loadPrepared(record, oracle);
        }
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        PrintStream sink = new PrintStream(new OutputStream() {
            @Override
            public void write(int b) {
            }
        });
        System.setOut(sink);
        System.setErr(sink);
        try {
            return loadPrepared(record, oracle);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
            sink.close();
        }
    }

    private static PreparedPair loadPreparedBounded(
            ModelRecord record,
            boolean oracle,
            Semaphore canonicalPermits) {
        acquireCanonicalPermit(canonicalPermits);
        try {
            return loadPrepared(record, oracle);
        } finally {
            canonicalPermits.release();
        }
    }

    private static void acquireCanonicalPermit(Semaphore canonicalPermits) {
        try {
            canonicalPermits.acquire();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Canonical preparation was interrupted", exception);
        }
    }

    private static void rethrowVirtualMachineError(Throwable throwable) {
        if (throwable instanceof VirtualMachineError) {
            throw (VirtualMachineError) throwable;
        }
    }

    private static String earliestUnresolvedDetail(
            Map<? extends Future<?>, Integer> active,
            List<Path> files,
            Path inputRoot) {
        int earliest = Integer.MAX_VALUE;
        for (int index : active.values()) {
            earliest = Math.min(earliest, index);
        }
        if (earliest == Integer.MAX_VALUE) {
            return null;
        }
        return "earliest unresolved source item " + (earliest + 1) + "/" + files.size()
                + " (" + inputRoot.relativize(files.get(earliest)).toString().replace('\\', '/') + ")";
    }

    private static RawAstTree internAst(RawAstTree ast, ConcurrentMap<String, RawAstTree> cache) {
        RawAstTree existing = cache.putIfAbsent(ast.fingerprint, ast);
        return existing == null ? ast : existing;
    }

    private static String modelContextFingerprint(ModelUnit model, PredicatePair pair) {
        StringBuilder fingerprint = new StringBuilder();
        appendContextNodes(fingerprint, model.getOpenDeclList());
        appendContextNodes(fingerprint, model.getSigDeclList());
        for (Predicate predicate : model.getPredDeclList()) {
            if (!predicate.getName().equals(pair.leftName) && !predicate.getName().equals(pair.rightName)) {
                appendContextNode(fingerprint, predicate);
            }
        }
        appendContextNodes(fingerprint, model.getFunDeclList());
        appendContextNodes(fingerprint, model.getFactDeclList());
        return fingerprint.toString();
    }

    private static void appendContextNodes(StringBuilder target, List<? extends Node> nodes) {
        for (Node node : nodes) {
            appendContextNode(target, node);
        }
    }

    private static void appendContextNode(StringBuilder target, Node node) {
        RawAstTree tree = RawAstTree.from(node);
        RawAstTree.appendFingerprintPart(target, tree.fingerprint);
    }

    private static Map<String, QuestionGroup> groups(List<ModelRecord> records) {
        Map<String, QuestionGroup> groups = new java.util.TreeMap<>();
        for (ModelRecord record : records) {
            QuestionGroup group = groups.computeIfAbsent(record.groupKey(), key -> new QuestionGroup(record.questionSet, record.invariantId));
            group.records.add(record);
            if (record.success() && "CORRECT".equals(record.statusFolder)) {
                group.correct.add(record);
            } else if (record.success()) {
                group.incorrect.add(record);
            } else {
                group.failures.add(record);
            }
        }
        return groups;
    }

    private static List<IncorrectMatch> nearestIncorrectMatches(Map<String, QuestionGroup> groups, Options options) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        if (!options.verbose) {
            PrintStream sink = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });
            System.setOut(sink);
            System.setErr(sink);
        }
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, options.threadCount));
        try {
            CompletionService<RankingBatch> completion = new ExecutorCompletionService<>(executor);
            int canonicalWorkers = ExperimentMemoryBudget.effectiveWorkers(options.threadCount);
            Semaphore canonicalPermits = new Semaphore(canonicalWorkers, true);
            List<RankingWork> work = new ArrayList<>();
            for (QuestionGroup group : groups.values()) {
                if (group.rankingReferences().isEmpty()) {
                    continue;
                }
                Map<RepresentationKey, List<ModelRecord>> duplicates = new LinkedHashMap<>();
                for (ModelRecord record : group.incorrect) {
                    duplicates.computeIfAbsent(RepresentationKey.student(record), key -> new ArrayList<>())
                            .add(record);
                }
                for (List<ModelRecord> equivalentRecords : duplicates.values()) {
                    work.add(new RankingWork(group, equivalentRecords));
                }
            }
            int workers = Math.max(1, options.threadCount);
            int maximumInFlight = workers > Integer.MAX_VALUE / 4
                    ? Integer.MAX_VALUE : workers * 4;
            int taskCount = work.size();
            int submitted = 0;
            int active = 0;
            while (submitted < taskCount && active < maximumInFlight) {
                RankingWork next = work.get(submitted++);
                completion.submit(() -> rankIncorrectBatch(next, canonicalPermits));
                active++;
            }
            List<IncorrectMatch> matches = new ArrayList<>();
            ExperimentProgress progress = ExperimentProgress.start(
                    originalErr,
                    "Alloy4FunAugmenter/ranking",
                    taskCount,
                    "tasks",
                    "with " + Math.max(1, options.threadCount) + " workers and "
                            + canonicalWorkers + " canonical builders");
            int completed = 0;
            while (completed < taskCount) {
                try {
                    Future<RankingBatch> future = completion.poll(30, TimeUnit.SECONDS);
                    if (future == null) {
                        progress.heartbeat(completed, active, null);
                        continue;
                    }
                    RankingBatch batch = future.get();
                    active--;
                    IncorrectMatch template = batch.template;
                    if (batch.error != null) {
                        for (ModelRecord record : batch.records) {
                            record.rankingError = batch.error;
                            releaseRankingRepresentation(record);
                        }
                    } else if (template == null) {
                        for (ModelRecord record : batch.records) {
                            releaseRankingRepresentation(record);
                        }
                    } else {
                        for (ModelRecord record : batch.records) {
                            record.canonicalSize = template.record.canonicalSize;
                            record.legacyCanonicalSize = template.record.legacyCanonicalSize;
                            matches.add(record == template.record
                                    ? template
                                    : new IncorrectMatch(record, template));
                            releaseRankingRepresentation(record);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Incorrect-predicate ranking was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rethrowVirtualMachineError(cause);
                    throw new IllegalStateException(
                            "Incorrect-predicate ranking failed before reports could be published",
                            cause);
                }
                progress.update(++completed);
                while (submitted < taskCount && active < maximumInFlight) {
                    RankingWork next = work.get(submitted++);
                    completion.submit(() -> rankIncorrectBatch(next, canonicalPermits));
                    active++;
                }
            }
            progress.finish(completed);
            matches.sort(Comparator.comparing((IncorrectMatch match) -> match.record.relativePath));
            return matches;
        } finally {
            executor.shutdownNow();
            if (!options.verbose) {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static RankingBatch rankIncorrectBatch(
            RankingWork work,
            Semaphore canonicalPermits) {
        try {
            return RankingBatch.success(
                    work.records,
                    nearestIncorrectMatch(
                            work.group, work.records.get(0), canonicalPermits));
        } catch (ModelPreparationFailure | QuotientRepairDistance.ResourceLimitException failure) {
            return RankingBatch.failure(
                    work.records,
                    failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }

    private static void releaseRankingRepresentation(ModelRecord record) {
        record.studentAst = null;
        record.studentCanonical = null;
        record.studentExact = null;
        record.studentBody = null;
        record.prelude = null;
    }

    private static void logRankingWork(Map<String, QuestionGroup> groups) {
        long comparisons = 0;
        long deduplicatedComparisons = 0;
        long skippedAstIdenticalComparisons = 0;
        int incorrect = 0;
        int uniqueIncorrectRepresentations = 0;
        for (QuestionGroup group : groups.values()) {
            int referenceCount = group.rankingReferences().size();
            incorrect += group.incorrect.size();
            comparisons += (long) group.incorrect.size() * referenceCount;
            Set<RepresentationKey> representations = new HashSet<>();
            for (ModelRecord record : group.incorrect) {
                representations.add(RepresentationKey.student(record));
                skippedAstIdenticalComparisons += astIdenticalReferenceCount(group, record);
            }
            uniqueIncorrectRepresentations += representations.size();
            deduplicatedComparisons += (long) representations.size() * referenceCount;
        }
        System.err.println("[Alloy4FunAugmenter] Ranking workload: " + incorrect
                + " incorrect predicates, " + uniqueIncorrectRepresentations
                + " unique representations, " + comparisons + " logical comparisons, "
                + deduplicatedComparisons + " potential deduplicated comparisons, "
                + skippedAstIdenticalComparisons + " AST-identical comparisons skipped.");
    }

    private static List<ModelRecord> incorrectWithoutReference(Map<String, QuestionGroup> groups) {
        List<ModelRecord> unmatched = new ArrayList<>();
        for (QuestionGroup group : groups.values()) {
            if (group.rankingReferences().isEmpty()) {
                unmatched.addAll(group.incorrect);
            }
        }
        unmatched.sort(Comparator.comparing(record -> record.relativePath));
        return unmatched;
    }

    private static List<AstIdenticalComparison> astIdenticalComparisons(
            Map<String, QuestionGroup> groups) {
        List<AstIdenticalComparison> comparisons = new ArrayList<>();
        for (QuestionGroup group : groups.values()) {
            for (ModelRecord incorrect : group.incorrect) {
                for (Reference reference : group.rankingReferences()) {
                    if (incorrect.studentAst.fingerprint.equals(reference.ast.fingerprint)) {
                        comparisons.add(new AstIdenticalComparison(group, incorrect, reference));
                    }
                }
            }
        }
        comparisons.sort(Comparator
                .comparing((AstIdenticalComparison comparison) -> comparison.incorrect.relativePath)
                .thenComparing(comparison -> comparison.reference.augmentedName));
        return comparisons;
    }

    private static void writeAstIdenticalPredicatePairs(
            Path path,
            List<ModelRecord> excluded) throws IOException {
        try (Writer writer = outputWriter(path)) {
            writer.write("relativePath,questionSet,statusFolder,invariantId,studentPredicate,oraclePredicate,reason\n");
            for (ModelRecord record : excluded) {
                writer.write(csv(record.relativePath) + ",");
                writer.write(csv(record.questionSet) + ",");
                writer.write(csv(record.statusFolder) + ",");
                writer.write(csv(record.invariantId) + ",");
                writer.write(csv(record.leftPredicate) + ",");
                writer.write(csv(record.rightPredicate) + ",");
                writer.write(csv("student-oracle-raw-ast-identical"));
                writer.write("\n");
            }
        }
    }

    private static void writeAstIdenticalComparisons(
            Path path,
            List<AstIdenticalComparison> comparisons) throws IOException {
        try (Writer writer = outputWriter(path)) {
            writer.write("incorrectPath,questionSet,statusFolder,invariantId,referenceName,referenceKind,"
                    + "referenceSource,sameSupportContext,sameOracleAst,samePreludeText,calledSymbols,"
                    + "incorrectBody,referenceBody,rawAstFingerprint\n");
            for (AstIdenticalComparison comparison : comparisons) {
                ModelRecord incorrect = comparison.incorrect;
                Reference reference = comparison.reference;
                writer.write(csv(incorrect.relativePath) + ",");
                writer.write(csv(incorrect.questionSet) + ",");
                writer.write(csv(incorrect.statusFolder) + ",");
                writer.write(csv(incorrect.invariantId) + ",");
                writer.write(csv(reference.augmentedName) + ",");
                writer.write(csv(reference.kind) + ",");
                writer.write(csv(reference.source.relativePath) + ",");
                writer.write(comparison.sameSupportContext + ",");
                writer.write(comparison.sameOracleAst + ",");
                writer.write(comparison.samePreludeText + ",");
                writer.write(csv(calledSymbols(incorrect.studentAst)) + ",");
                writer.write(csv(incorrect.studentBody) + ",");
                writer.write(csv(reference.body) + ",");
                writer.write(csv(incorrect.studentAst.fingerprint));
                writer.write("\n");
            }
        }
    }

    private static int writeIncorrectNearestZeroAudit(
            Path path,
            List<IncorrectMatch> matches) throws IOException {
        int certifiedZeroes = 0;
        try (Writer writer = outputWriter(path)) {
            writer.write("metric,relativePath,questionSet,statusFolder,invariantId,"
                    + "referenceName,referenceKind,referenceSource,distance\n");
            for (IncorrectMatch match : matches) {
                if (match.canonical.first().distance == 0) {
                    certifiedZeroes++;
                    writeIncorrectNearestZeroRow(
                            writer, "certificate-integrated", match,
                            match.canonical.first());
                }
                if (match.legacyCanonical.first().distance == 0) {
                    writeIncorrectNearestZeroRow(
                            writer, "fast-rewrite", match,
                            match.legacyCanonical.first());
                }
            }
        }
        return certifiedZeroes;
    }

    private static int incorrectNearestZeroCount(
            List<IncorrectMatch> matches,
            boolean certificateIntegrated) {
        int count = 0;
        for (IncorrectMatch match : matches) {
            Nearest nearest = certificateIntegrated
                    ? match.canonical : match.legacyCanonical;
            if (nearest.first().distance == 0) {
                count++;
            }
        }
        return count;
    }

    private static void writeIncorrectNearestZeroRow(
            Writer writer,
            String metric,
            IncorrectMatch match,
            RankedReference nearest) throws IOException {
        writer.write(csv(metric) + ",");
        writer.write(csv(match.record.relativePath) + ",");
        writer.write(csv(match.record.questionSet) + ",");
        writer.write(csv(match.record.statusFolder) + ",");
        writer.write(csv(match.record.invariantId) + ",");
        writer.write(csv(nearest.reference.augmentedName) + ",");
        writer.write(csv(nearest.reference.kind) + ",");
        writer.write(csv(nearest.reference.source.relativePath) + ",");
        writer.write(Integer.toString(nearest.distance));
        writer.write("\n");
    }

    private static String calledSymbols(RawAstTree tree) {
        Set<String> names = new java.util.TreeSet<>();
        collectCalledSymbols(tree, names);
        return String.join(";", names);
    }

    private static void collectCalledSymbols(RawAstTree tree, Set<String> names) {
        int marker = tree.label.indexOf(":name=");
        if (tree.label.startsWith("Call") && marker >= 0) {
            names.add(tree.label.substring(marker + ":name=".length()));
        }
        for (RawAstTree child : tree.children) {
            collectCalledSymbols(child, names);
        }
    }

    private static List<ModelRecord> incorrectWithoutAstDistinctReference(Map<String, QuestionGroup> groups) {
        List<ModelRecord> skipped = new ArrayList<>();
        for (QuestionGroup group : groups.values()) {
            if (group.rankingReferences().isEmpty()) {
                continue;
            }
            for (ModelRecord record : group.incorrect) {
                if (astIdenticalReferenceCount(group, record) == group.rankingReferences().size()) {
                    skipped.add(record);
                }
            }
        }
        skipped.sort(Comparator.comparing(record -> record.relativePath));
        return skipped;
    }

    private static int astIdenticalReferenceCount(QuestionGroup group, ModelRecord record) {
        int count = 0;
        for (Reference reference : group.rankingReferences()) {
            if (record.studentAst.fingerprint.equals(reference.ast.fingerprint)) {
                count++;
            }
        }
        return count;
    }

    private static IncorrectMatch nearestIncorrectMatch(
            QuestionGroup group,
            ModelRecord incorrect,
            Semaphore canonicalPermits) {
        if (astIdenticalReferenceCount(group, incorrect) == group.rankingReferences().size()) {
            return null;
        }
        if (incorrect.studentCanonical == null || incorrect.studentExact == null) {
            PreparedPair prepared;
            try {
                prepared = loadPreparedBounded(incorrect, false, canonicalPermits);
            } catch (VirtualMachineError error) {
                throw error;
            } catch (RuntimeException exception) {
                throw new ModelPreparationFailure(
                        "Could not prepare incorrect predicate "
                                + incorrect.relativePath,
                        exception);
            }
            incorrect.studentCanonical = prepared.legacy;
            incorrect.studentExact = prepared.exact;
            incorrect.legacyCanonicalSize = Canonical.canonicalFormSize(incorrect.studentCanonical);
            incorrect.canonicalSize = incorrect.studentExact.repairObservationSize();
        }
        IncorrectMatch match = new IncorrectMatch(incorrect);
        for (Reference reference : group.rankingReferences()) {
            if (incorrect.studentAst.fingerprint.equals(reference.ast.fingerprint)) {
                continue;
            }
            int levenshtein = levenshteinDistance(incorrect.studentBody, reference.body);
            int rawAst = rawAstTreeDistance(incorrect.studentAst, reference.ast);
            int legacyCanonical = Canonical.distance(
                    incorrect.studentCanonical, reference.canonical);
            int canonical;
            try {
                canonical = CanonicalAlloyPipeline.distance(
                        incorrect.studentExact, reference.exact);
            } catch (IllegalStateException exception) {
                QuotientRepairDistance.Result candidate = QuotientRepairDistance.evaluate(
                        incorrect.studentExact.repairView(), reference.exact.repairView());
                throw new IllegalStateException(
                        "Incorrect-ranking kernel violation for "
                                + group.questionSet + "/" + group.invariantId
                                + ": " + incorrect.relativePath
                                + " [" + incorrect.statusFolder + "] versus "
                                + referenceLocation(reference)
                                + "; leftObservation="
                                + incorrect.studentExact.canonicalObservation().digest()
                                + "; rightObservation="
                                + reference.exact.canonicalObservation().digest()
                                + "; repair=(total=" + candidate.distance()
                                + ", temporal=" + candidate.temporalDistance()
                                + ", quantifiers=" + candidate.quantifierDistance()
                                + ", matrix=" + candidate.matrixDistance() + ")",
                        exception);
            }
            match.levenshtein.add(reference, levenshtein);
            match.rawAst.add(reference, rawAst);
            match.legacyCanonical.add(reference, legacyCanonical);
            match.canonical.add(reference, canonical);
        }
        match.sortRankings();
        return match;
    }

    private static void computeRewards(List<IncorrectMatch> matches, Options options) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        if (!options.verbose) {
            PrintStream sink = new PrintStream(new OutputStream() {
                @Override
                public void write(int b) {
                }
            });
            System.setOut(sink);
            System.setErr(sink);
        }
        int rewardWorkers = ExperimentMemoryBudget.effectiveWorkers(options.threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(rewardWorkers);
        try {
            CompletionService<Void> completion = new ExecutorCompletionService<>(executor);
            int maximumInFlight = rewardWorkers > Integer.MAX_VALUE / 4
                    ? Integer.MAX_VALUE : rewardWorkers * 4;
            int submitted = 0;
            int active = 0;
            while (submitted < matches.size() && active < maximumInFlight) {
                IncorrectMatch match = matches.get(submitted++);
                completion.submit(() -> {
                    computeReward(match, options.rewardPoolSize);
                    return null;
                });
                active++;
            }
            ExperimentProgress progress = ExperimentProgress.start(
                    originalErr,
                    "Alloy4FunAugmenter/rewards",
                    matches.size(),
                    "predicates",
                    "with " + rewardWorkers + " memory-bounded workers (requested "
                            + Math.max(1, options.threadCount) + ")");
            int completed = 0;
            while (completed < matches.size()) {
                try {
                    Future<Void> future = completion.poll(30, TimeUnit.SECONDS);
                    if (future == null) {
                        progress.heartbeat(completed, active, null);
                        continue;
                    }
                    future.get();
                    active--;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rethrowVirtualMachineError(cause);
                }
                progress.update(++completed);
                while (submitted < matches.size() && active < maximumInFlight) {
                    IncorrectMatch match = matches.get(submitted++);
                    completion.submit(() -> {
                        computeReward(match, options.rewardPoolSize);
                        return null;
                    });
                    active++;
                }
            }
            progress.finish(completed);
        } finally {
            executor.shutdownNow();
            if (!options.verbose) {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static void computeReward(IncorrectMatch match, int poolSize) {
        ModelRecord record = match.record;
        match.rewardPoolSize = poolSize;
        match.rewardComputed = true;
        try {
            CompModule module = AlloyUtil.compileAlloyModule(record.file.toString());
            Pair<InstancePool, InstancePool> instances = Rewarder.instances(module, record.rightPredicate, poolSize);
            match.candidateReward = Rewarder.computeReward(
                    module,
                    instances,
                    record.rightPredicate,
                    record.leftPredicate,
                    record.leftPredicate,
                    poolSize);
            match.rewardError = Math.max(0.0, 1.0 - match.candidateReward);
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable t) {
            match.rewardErrorMessage = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static void writeAugmentedFiles(Map<String, QuestionGroup> groups, Path outputDir) throws IOException {
        Path correctRoot = outputDir.resolve("correct");
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "Alloy4FunAugmenter/write-pools",
                groups.size(),
                "groups");
        int completed = 0;
        for (QuestionGroup group : groups.values()) {
            if (group.references.isEmpty()) {
                progress.update(++completed);
                continue;
            }
            Files.createDirectories(correctRoot.resolve(group.questionSet));
            Path file = correctRoot.resolve(group.questionSet).resolve(group.invariantId + ".als");
            try (Writer writer = outputWriter(file)) {
                writer.write("module alloy4fun_augmented_" + sanitize(group.questionSet) + "_" + sanitize(group.invariantId) + "\n");
                String prelude = group.prelude();
                int line = prelude.indexOf('\n');
                if (line >= 0) {
                    writer.write(prelude.substring(line + 1).trim());
                    writer.write("\n\n");
                }
                for (Reference reference : group.references) {
                    writer.write("pred " + reference.augmentedName + "[] {\n");
                    writer.write(reference.body.trim());
                    writer.write("\n}\n\n");
                }
            }
            progress.update(++completed);
        }
        progress.finish(completed);
    }

    private static void writeCorrectPoolEquivalenceJson(
            Path path,
            Options options,
            List<CorrectPoolPair> pairs,
            String criterion) throws IOException {
        try (Writer writer = outputWriter(path)) {
            writer.write("{\n");
            writer.write("  \"generatedAt\": \"" + escape(Instant.now().toString()) + "\",\n");
            writer.write("  \"inputRoot\": \"" + escape(options.inputDir.toString()) + "\",\n");
            writer.write("  \"outputRoot\": \"" + escape(options.outputDir.toString()) + "\",\n");
            writer.write("  \"criterion\": \"" + escape(criterion) + "\",\n");
            writer.write("  \"pairCount\": " + pairs.size() + ",\n");
            writer.write("  \"pairs\": [\n");
            for (int i = 0; i < pairs.size(); i++) {
                if (i > 0) {
                    writer.write(",\n");
                }
                writeCorrectPoolPairJson(writer, pairs.get(i));
            }
            writer.write("\n  ]\n");
            writer.write("}\n");
        }
    }

    private static CorrectPoolEquivalences correctAstDifferentCanonicalEquivalentPairs(
            Map<String, QuestionGroup> groups,
            int threadCount) {
        ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, threadCount));
        try {
            CompletionService<CorrectPoolBatch> completion =
                    new ExecutorCompletionService<>(executor);
            int taskCount = 0;
            for (QuestionGroup group : groups.values()) {
                List<Reference> references = group.references;
                for (int i = 0; i < references.size(); i++) {
                    final int leftIndex = i;
                    completion.submit(() -> correctPoolPairsForLeft(
                            group, references, leftIndex));
                    taskCount++;
                }
            }
            List<CorrectPoolPair> certificateIntegrated = new ArrayList<>();
            List<CorrectPoolPair> fastRewrite = new ArrayList<>();
            List<IllegalStateException> kernelViolations = new ArrayList<>();
            ExperimentProgress progress = ExperimentProgress.start(
                    System.err,
                    "Alloy4FunAugmenter/correct-pairs",
                    taskCount,
                    "tasks",
                    "with " + Math.max(1, threadCount) + " workers");
            int completed = 0;
            while (completed < taskCount) {
                try {
                    Future<CorrectPoolBatch> future =
                            completion.poll(30, TimeUnit.SECONDS);
                    if (future == null) {
                        progress.heartbeat(completed, taskCount - completed, null);
                        continue;
                    }
                    CorrectPoolBatch batch = future.get();
                    certificateIntegrated.addAll(batch.certificateIntegrated);
                    fastRewrite.addAll(batch.fastRewrite);
                    kernelViolations.addAll(batch.kernelViolations);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Correct-pool comparison was interrupted", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    rethrowVirtualMachineError(cause);
                    throw new IllegalStateException(
                            "Correct-pool comparison failed before reports could be published",
                            cause);
                }
                progress.update(++completed);
            }
            progress.finish(completed);
            if (!kernelViolations.isEmpty()) {
                IllegalStateException failure = new IllegalStateException(
                        "Correct-pool comparison found " + kernelViolations.size()
                                + " strict-kernel violation(s); first violation follows",
                        kernelViolations.get(0));
                for (int i = 1; i < kernelViolations.size(); i++) {
                    failure.addSuppressed(kernelViolations.get(i));
                }
                throw failure;
            }
            Comparator<CorrectPoolPair> pairOrder = Comparator
                    .comparing((CorrectPoolPair pair) -> pair.group.questionSet)
                    .thenComparing(pair -> pair.group.invariantId)
                    .thenComparing(pair -> pair.left.augmentedName)
                    .thenComparing(pair -> pair.right.augmentedName);
            certificateIntegrated.sort(pairOrder);
            fastRewrite.sort(pairOrder);
            return new CorrectPoolEquivalences(certificateIntegrated, fastRewrite);
        } finally {
            executor.shutdownNow();
        }
    }

    private static CorrectPoolBatch correctPoolPairsForLeft(
            QuestionGroup group,
            List<Reference> references,
            int leftIndex) {
        List<CorrectPoolPair> certificateIntegrated = new ArrayList<>();
        List<CorrectPoolPair> fastRewrite = new ArrayList<>();
        List<IllegalStateException> kernelViolations = new ArrayList<>();
        Reference left = references.get(leftIndex);
        for (int j = leftIndex + 1; j < references.size(); j++) {
            Reference right = references.get(j);
            if (left.ast.fingerprint.equals(right.ast.fingerprint)) {
                continue;
            }
            boolean certificateCandidate = left.canonicalSize == right.canonicalSize;
            boolean fastRewriteCandidate = left.legacyCanonicalSize == right.legacyCanonicalSize;
            if (!certificateCandidate && !fastRewriteCandidate) {
                continue;
            }
            int rawAstDistance = rawAstTreeDistance(left.ast, right.ast);
            int legacyCanonicalDistance = fastRewriteCandidate
                    ? Canonical.distance(left.canonical, right.canonical)
                    : -1;
            int canonicalDistance = -1;
            if (certificateCandidate) {
                try {
                    canonicalDistance = CanonicalAlloyPipeline.distance(left.exact, right.exact);
                } catch (IllegalStateException exception) {
                    kernelViolations.add(correctPoolKernelViolation(
                            group,
                            left,
                            right,
                            exception));
                    continue;
                }
            }
            if (canonicalDistance != 0 && legacyCanonicalDistance != 0) {
                continue;
            }
            if (canonicalDistance < 0) {
                try {
                    canonicalDistance = CanonicalAlloyPipeline.distance(left.exact, right.exact);
                } catch (IllegalStateException exception) {
                    kernelViolations.add(correctPoolKernelViolation(
                            group,
                            left,
                            right,
                            exception));
                    continue;
                }
            }
            if (legacyCanonicalDistance < 0) {
                legacyCanonicalDistance = Canonical.distance(left.canonical, right.canonical);
            }
            CorrectPoolPair pair = new CorrectPoolPair(
                    group,
                    left,
                    right,
                    rawAstDistance,
                    canonicalDistance,
                    legacyCanonicalDistance);
            if (canonicalDistance == 0) {
                certificateIntegrated.add(pair);
            }
            if (legacyCanonicalDistance == 0) {
                fastRewrite.add(pair);
            }
        }
        return new CorrectPoolBatch(certificateIntegrated, fastRewrite, kernelViolations);
    }

    private static IllegalStateException correctPoolKernelViolation(
            QuestionGroup group,
            Reference left,
            Reference right,
            IllegalStateException exception) {
        return new IllegalStateException(
                "Correct-pool kernel violation for "
                        + group.questionSet + "/" + group.invariantId
                        + ": " + referenceLocation(left)
                        + " versus " + referenceLocation(right)
                        + "; leftObservation="
                        + left.exact.canonicalObservation().digest()
                        + "; rightObservation="
                        + right.exact.canonicalObservation().digest(),
                exception);
    }

    private static String referenceLocation(Reference reference) {
        return reference.augmentedName + " [" + reference.kind + ", "
                + (reference.source == null
                        ? "generated"
                        : reference.source.relativePath)
                + "]";
    }

    private static void writeCorrectPoolPairJson(Writer writer, CorrectPoolPair pair) throws IOException {
        writer.write("    {\n");
        writer.write("      \"questionSet\": \"" + escape(pair.group.questionSet) + "\",\n");
        writer.write("      \"invariantId\": \"" + escape(pair.group.invariantId) + "\",\n");
        writer.write("      \"augmentedFile\": \"" + escape("correct/" + pair.group.questionSet + "/" + pair.group.invariantId + ".als") + "\",\n");
        writer.write("      \"rawAstDistance\": " + pair.rawAstDistance + ",\n");
        writer.write("      \"canonicalDistance\": " + pair.canonicalDistance + ",\n");
        writer.write("      \"certificateIntegratedDistance\": "
                + pair.canonicalDistance + ",\n");
        writer.write("      \"fastRewriteCanonicalDistance\": "
                + pair.legacyCanonicalDistance + ",\n");
        writer.write("      \"legacyCanonicalDistance\": "
                + pair.legacyCanonicalDistance + ",\n");
        writer.write("      \"left\": ");
        writeReferenceJson(writer, pair.left);
        writer.write(",\n");
        writer.write("      \"right\": ");
        writeReferenceJson(writer, pair.right);
        writer.write("\n");
        writer.write("    }");
    }

    private static void writeReferenceJson(Writer writer, Reference reference) throws IOException {
        writer.write("{\"name\": \"" + escape(reference.augmentedName)
                + "\", \"kind\": \"" + escape(reference.kind)
                + "\", \"source\": \"" + escape(reference.source.relativePath)
                + "\", \"body\": \"" + escape(reference.body.trim()) + "\"}");
    }

    private static void writeJson(
            Path path,
            Options options,
            int fileCount,
            int astIdenticalPredicatePairCount,
            Map<String, QuestionGroup> groups,
            List<ModelRecord> records,
            List<IncorrectMatch> matches,
            List<ModelRecord> unmatched,
            List<ModelRecord> withoutAstDistinctReference,
            CorrectPoolEquivalences correctPoolEquivalences) throws IOException {
        Summary summary = new Summary(groups, records, matches, unmatched, withoutAstDistinctReference);
        DistanceTableStats distanceTable = distanceTableStats(matches);
        int certifiedIncorrectZeroes = incorrectNearestZeroCount(matches, true);
        int fastRewriteIncorrectZeroes = incorrectNearestZeroCount(matches, false);
        try (Writer writer = outputWriter(path)) {
            writer.write("{\n");
            writer.write("  \"generatedAt\": \"" + escape(Instant.now().toString()) + "\",\n");
            writer.write("  \"inputRoot\": \"" + escape(options.inputDir.toString()) + "\",\n");
            writer.write("  \"outputRoot\": \"" + escape(options.outputDir.toString()) + "\",\n");
            writer.write("  \"sourceFileCount\": " + fileCount + ",\n");
            writer.write("  \"astIdenticalPredicatePairsExcluded\": "
                    + astIdenticalPredicatePairCount + ",\n");
            writer.write("  \"consideredFileCount\": " + records.size() + ",\n");
            writer.write("  \"threadCount\": " + options.threadCount + ",\n");
            writer.write("  \"rewardWorkerLimit\": "
                    + ExperimentMemoryBudget.effectiveWorkers(options.threadCount) + ",\n");
            writer.write("  \"maximumHeapBytes\": " + Runtime.getRuntime().maxMemory() + ",\n");
            writer.write("  \"rewardPoolSize\": " + options.rewardPoolSize + ",\n");
            writer.write("  \"rewardsEnabled\": " + !options.skipRewards + ",\n");
            writer.write("  \"canonicalEngine\": \"CanonicalAlloyPipeline\",\n");
            writer.write("  \"certificateIntegratedEngine\": \"CanonicalAlloyPipeline\",\n");
            writer.write("  \"fastRewriteEngine\": \"Canonical/CanonicalDistance\",\n");
            writer.write("  \"canonicalPipelineVersion\": \""
                    + CanonicalAlloyPipeline.PIPELINE_VERSION + "\",\n");
            writer.write("  \"measurementProjectionVersion\": \""
                    + CanonicalAlloyPipeline.MEASUREMENT_PROJECTION_VERSION + "\",\n");
            writer.write("  \"quotientMetricVersion\": \""
                    + CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION + "\",\n");
            writer.write("  \"canonicalRepresentativeTedVersion\": \""
                    + CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION + "\",\n");
            writer.write("  \"alloyAdapterVersion\": \""
                    + TheoryAlloyAdapter.ADAPTER_VERSION + "\",\n");
            writer.write("  \"invariantCheckMode\": \""
                    + TheoryAlloyAdapter.INVARIANT_MODE + "\",\n");
            writer.write("  \"canonicalizerVersion\": \""
                    + ProductionGraphCanonicalizer.VERSION + "\",\n");
            writer.write("  \"certificateVerifierVersion\": \""
                    + CertificateVerifier.VERSION + "\",\n");
            writer.write("  \"certificateMode\": \"required\",\n");
            writer.write("  \"finiteUnfoldingVersion\": \""
                    + BoundedFiniteUnfoldingOracle.VERSION + "\",\n");
            writer.write("  \"fastRewriteCanonicalRetained\": true,\n");
            writer.write("  \"legacyCanonicalRetained\": true,\n");
            writer.write("  \"implementationFieldMapping\": {\"certificateIntegrated\": \"canonical*\", "
                    + "\"fastRewrite\": \"legacyCanonical*\"},\n");
            writer.write("  \"summary\": {\n");
            writer.write("    \"groups\": " + summary.groups + ",\n");
            writer.write("    \"parsedModels\": " + summary.parsedModels + ",\n");
            writer.write("    \"parseFailures\": " + summary.parseFailures + ",\n");
            writer.write("    \"correctModels\": " + summary.correctModels + ",\n");
            writer.write("    \"incorrectModels\": " + summary.incorrectModels + ",\n");
            writer.write("    \"oracleReferences\": " + summary.oracleReferences + ",\n");
            writer.write("    \"uniqueCorrectStudentReferences\": " + summary.uniqueCorrectStudentReferences + ",\n");
            writer.write("    \"incorrectModelsWithNearestDistances\": " + matches.size() + ",\n");
            writer.write("    \"incorrectNearestCertificateIntegratedZeroes\": "
                    + certifiedIncorrectZeroes + ",\n");
            writer.write("    \"incorrectNearestFastRewriteZeroes\": "
                    + fastRewriteIncorrectZeroes + ",\n");
            writer.write("    \"correctPoolCertificateIntegratedEquivalentPairs\": "
                    + correctPoolEquivalences.certificateIntegrated.size() + ",\n");
            writer.write("    \"correctPoolFastRewriteEquivalentPairs\": "
                    + correctPoolEquivalences.fastRewrite.size() + ",\n");
            writer.write("    \"rewardSuccesses\": " + summary.rewardSuccesses + ",\n");
            writer.write("    \"rewardFailures\": " + summary.rewardFailures + ",\n");
            writer.write("    \"averageCandidateReward\": " + number(summary.averageCandidateReward()) + ",\n");
            writer.write("    \"averageRewardError\": " + number(summary.averageRewardError()) + ",\n");
            writer.write("    \"averageNearestCertificateIntegratedDistance\": "
                    + number(distanceTable.overall.averageCanonical()) + ",\n");
            writer.write("    \"averageNearestFastRewriteDistance\": "
                    + number(distanceTable.overall.averageFastRewrite()) + ",\n");
            writer.write("    \"averageRelativeNearestCertificateIntegratedDistance\": "
                    + number(distanceTable.overall.averageCanonicalRatio()) + ",\n");
            writer.write("    \"averageRelativeNearestFastRewriteDistance\": "
                    + number(distanceTable.overall.averageFastRewriteRatio()) + ",\n");
            writer.write("    \"incorrectModelsWithoutAstDistinctReference\": "
                    + summary.incorrectModelsWithoutAstDistinctReference + ",\n");
            writer.write("    \"incorrectModelsWithoutCorrectReference\": " + unmatched.size() + ",\n");
            writer.write("    \"referencePoolFailures\": " + summary.referencePoolFailures + ",\n");
            writer.write("    \"incorrectRankingFailures\": " + summary.rankingFailures + "\n");
            writer.write("  },\n");
            writer.write("  \"questions\": [\n");
            int index = 0;
            for (QuestionGroup group : groups.values()) {
                if (index++ > 0) {
                    writer.write(",\n");
                }
                writeGroupJson(writer, group);
            }
            writer.write("\n  ],\n");
            writer.write("  \"incorrectNearest\": [\n");
            for (int i = 0; i < matches.size(); i++) {
                if (i > 0) {
                    writer.write(",\n");
                }
                writeMatchJson(writer, matches.get(i));
            }
            writer.write("\n  ],\n");
            writer.write("  \"incorrectWithoutAstDistinctReference\": [\n");
            for (int i = 0; i < withoutAstDistinctReference.size(); i++) {
                if (i > 0) {
                    writer.write(",\n");
                }
                writeUnmatchedJson(writer, withoutAstDistinctReference.get(i));
            }
            writer.write("\n  ],\n");
            writer.write("  \"incorrectWithoutReference\": [\n");
            for (int i = 0; i < unmatched.size(); i++) {
                if (i > 0) {
                    writer.write(",\n");
                }
                writeUnmatchedJson(writer, unmatched.get(i));
            }
            writer.write("\n  ],\n");
            writer.write("  \"incorrectRankingFailures\": [\n");
            int rankingFailureIndex = 0;
            for (ModelRecord record : records) {
                if (record.rankingError == null) {
                    continue;
                }
                if (rankingFailureIndex++ > 0) {
                    writer.write(",\n");
                }
                writeRankingFailureJson(writer, record);
            }
            writer.write("\n  ]\n");
            writer.write("}\n");
        }
    }

    private static void writeMarkdown(
            Path path,
            Options options,
            int fileCount,
            int astIdenticalPredicatePairCount,
            Map<String, QuestionGroup> groups,
            List<ModelRecord> records,
            List<IncorrectMatch> matches,
            List<ModelRecord> unmatched,
            List<ModelRecord> withoutAstDistinctReference,
            CorrectPoolEquivalences correctPoolEquivalences) throws IOException {
        Summary summary = new Summary(groups, records, matches, unmatched, withoutAstDistinctReference);
        Map<String, QuestionSetStats> questionStats = questionSetStats(groups, matches);
        Map<String, CorrectTruthStats> correctTruthStats = correctTruthStats(
                groups,
                correctPoolEquivalences);
        Map<String, RankingModeStats> modeStats = rankingModeStats(groups);
        DistanceTableStats distanceTable = distanceTableStats(matches);
        Map<String, RepairRadiusStats> repairOverall = repairRadiusStatsOverall(matches);
        Map<String, RepairRadiusStats> repairByStatus = repairRadiusStatsByStatus(matches);
        Map<String, RepairRadiusStats> repairByQuestionSet = repairRadiusStatsByQuestionSet(matches);
        Map<String, RepairRadiusStats> repairByQuestionSetAndStatus = repairRadiusStatsByQuestionSetAndStatus(matches);
        int certifiedIncorrectZeroes = incorrectNearestZeroCount(matches, true);
        int fastRewriteIncorrectZeroes = incorrectNearestZeroCount(matches, false);

        try (Writer writer = outputWriter(path)) {
            writer.write("# Alloy4Fun Augmented Dataset Summary\n\n");
            writer.write("- Generated at: `" + Instant.now().toString() + "`\n");
            writer.write("- Input root: `" + options.inputDir + "`\n");
            writer.write("- Output root: `" + options.outputDir + "`\n");
            writer.write("- Source Alloy files: " + fileCount + "\n");
            writer.write("- AST-identical student/oracle files excluded before pools: "
                    + astIdenticalPredicatePairCount + "\n");
            writer.write("- Alloy files considered and used: " + records.size() + "\n");
            writer.write("- Thread count: " + options.threadCount + "\n");
            writer.write("- Reward worker limit: "
                    + ExperimentMemoryBudget.effectiveWorkers(options.threadCount) + "\n");
            writer.write("- Maximum JVM heap bytes: " + Runtime.getRuntime().maxMemory() + "\n\n");
            writer.write("- Certificate-Integrated IR engine: `CanonicalAlloyPipeline` (`"
                    + CanonicalAlloyPipeline.PIPELINE_VERSION + "`)\n");
            writer.write("- Fast Rewrite IR engine: `Canonical` / `CanonicalDistance`\n");
            writer.write("- Canonical repair metric compatibility manifest ID: `"
                    + CanonicalAlloyPipeline.QUOTIENT_METRIC_VERSION + "`\n");
            writer.write("- Canonical representative TED is diagnostic only: `"
                    + CanonicalAlloyPipeline.REPRESENTATIVE_TED_VERSION + "`\n");
            writer.write("- Exact graph: `TypedSlottedPortEGraph`; invariants: `"
                    + TheoryAlloyAdapter.INVARIANT_MODE + "`; certificates: required\n");
            writer.write("- Co-maintained Fast Rewrite IR rankings retained in `index.json` as a differential oracle: yes\n\n");
            writer.write("- Reward pool size: " + options.rewardPoolSize + "\n\n");
            writer.write("- Rewards enabled: " + !options.skipRewards + "\n\n");

            writer.write("## Corpus\n\n");
            writer.write("- Question groups: " + summary.groups + "\n");
            writer.write("- Parsed models: " + summary.parsedModels + "\n");
            writer.write("- Parse failures: " + summary.parseFailures + "\n");
            writer.write("- CORRECT models: " + summary.correctModels + "\n");
            writer.write("- Incorrect models: " + summary.incorrectModels + "\n");
            writer.write("- Oracle references: " + summary.oracleReferences + "\n");
            writer.write("- AST-unique CORRECT student references: " + summary.uniqueCorrectStudentReferences + "\n");
            writer.write("- Incorrect models with rankings: " + matches.size() + "\n");
            writer.write("- Incorrect models skipped because every truth was AST-identical: "
                    + withoutAstDistinctReference.size() + "\n");
            writer.write("- Incorrect models without references: " + unmatched.size() + "\n\n");
            writer.write("- Incorrect predicates with zero nearest Certificate-Integrated IR distance: "
                    + certifiedIncorrectZeroes + " (release gate requires zero)\n");
            writer.write("- Incorrect predicates with zero nearest Fast Rewrite IR distance: "
                    + fastRewriteIncorrectZeroes
                    + " (diagnostic; Fast Rewrite IR does not certify semantic equality)\n\n");
            writer.write("- Reference-pool preparation failures: "
                    + summary.referencePoolFailures + "\n");
            writer.write("- Incorrect-predicate ranking failures: "
                    + summary.rankingFailures + "\n\n");
            writer.write("- Reward successes: " + summary.rewardSuccesses + "\n");
            writer.write("- Reward failures: " + summary.rewardFailures + "\n");
            writer.write("- Average candidate reward: " + number(summary.averageCandidateReward()) + "\n");
            writer.write("- Average reward error `(1 - reward)`: " + number(summary.averageRewardError()) + "\n\n");

            writer.write("## Correct Truth Pools\n\n");
            writer.write("Truth predicates include one oracle predicate per invariant together with every CORRECT "
                    + "student predicate. AST deduplication is applied to this combined pool. The two zero-pair "
                    + "columns report unordered AST-distinct truths at distance zero for each implementation. "
                    + "Unique forms are connected components under the corresponding zero-distance relation.\n\n");
            writer.write("| Problem class | Correct truth predicates | AST-distinct truths | "
                    + "Unique Fast Rewrite forms | Fast Rewrite zero pairs | "
                    + "Unique Certificate-Integrated forms | Certificate-Integrated zero pairs |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            CorrectTruthStats totalTruthStats = new CorrectTruthStats("Total");
            for (CorrectTruthStats stats : correctTruthStats.values()) {
                writer.write("| " + stats.questionSet + " | " + stats.correctPredicates + " | "
                        + stats.astDistinctPredicates + " | " + stats.uniqueFastRewriteForms + " | "
                        + stats.fastRewriteEquivalentPairs + " | "
                        + stats.uniqueCertificateForms + " | "
                        + stats.certificateEquivalentPairs + " |\n");
                totalTruthStats.add(stats);
            }
            writer.write("| **Total** | **" + totalTruthStats.correctPredicates + "** | **"
                    + totalTruthStats.astDistinctPredicates + "** | **"
                    + totalTruthStats.uniqueFastRewriteForms + "** | **"
                    + totalTruthStats.fastRewriteEquivalentPairs + "** | **"
                    + totalTruthStats.uniqueCertificateForms + "** | **"
                    + totalTruthStats.certificateEquivalentPairs + "** |\n\n");

            writer.write("## Ranking Pools\n\n");
            writer.write("| Mode | Groups | Incorrect predicates | Min refs | Max refs |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: |\n");
            for (RankingModeStats stats : modeStats.values()) {
                writer.write("| " + stats.mode + " | " + stats.groups + " | " + stats.incorrect + " | "
                        + stats.minRefsMarkdown() + " | " + stats.maxRefsMarkdown() + " |\n");
            }
            writer.write("\n");

            writer.write("## Nearest Correct-Predicate Distance Averages\n\n");
            writer.write("For each incorrect predicate and metric, the distance is the minimum over every "
                    + "AST-distinct correct predicate in the same invariant's truth pool, including the oracle. "
                    + "The three metrics may select different nearest predicates. Relative distances divide by "
                    + "the incorrect predicate's own body length, raw AST size, or canonical-form size. "
                    + "CORRECT predicates are excluded.\n\n");
            writer.write("| Problem class | Incorrectness class | Incorrect predicates | "
                    + "Avg nearest Levenshtein | Avg nearest raw AST | Avg nearest Fast Rewrite IR | "
                    + "Avg nearest Certificate-Integrated IR | Avg relative Levenshtein | "
                    + "Avg relative raw AST | Avg relative Fast Rewrite IR | "
                    + "Avg relative Certificate-Integrated IR |\n");
            writer.write("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            DistanceStats allDistances = distanceTable.overall;
            writer.write("| **All problem classes** | **All incorrectness classes** | **"
                    + allDistances.count + "** | **" + number(allDistances.averageLevenshtein())
                    + "** | **" + number(allDistances.averageRawAst())
                    + "** | **" + number(allDistances.averageFastRewrite())
                    + "** | **" + number(allDistances.averageCanonical())
                    + "** | **" + number(allDistances.averageLevenshteinRatio())
                    + "** | **" + number(allDistances.averageRawAstRatio())
                    + "** | **" + number(allDistances.averageFastRewriteRatio())
                    + "** | **" + number(allDistances.averageCanonicalRatio()) + "** |\n");
            for (DistanceStats stats : distanceTable.byProblemAndStatus.values()) {
                writer.write("| " + stats.problemClass + " | " + stats.statusFolder + " | "
                        + stats.count + " | " + number(stats.averageLevenshtein()) + " | "
                        + number(stats.averageRawAst()) + " | " + number(stats.averageFastRewrite()) + " | "
                        + number(stats.averageCanonical()) + " | "
                        + number(stats.averageLevenshteinRatio()) + " | "
                        + number(stats.averageRawAstRatio()) + " | "
                        + number(stats.averageFastRewriteRatio()) + " | "
                        + number(stats.averageCanonicalRatio()) + " |\n");
            }
            writer.write("\n");

            writer.write("## Relative Repair Coverage Comparison\n\n");
            writer.write("The SVG plots smooth empirical coverage curves from 0% to 100% of each metric's representation size. The table gives selected checkpoints.\n\n");
            writer.write("| Fraction of representation size | Levenshtein / body chars | Raw AST / AST size | Fast Rewrite IR / form size | Certificate-Integrated IR / observation size |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: |\n");
            RepairRadiusStats allRepairCoverage = repairOverall.get("All incorrect");
            if (allRepairCoverage != null) {
                for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                    writer.write("| <= " + percentLabel(RELATIVE_REPAIR_RADII[i]) + " | "
                            + countPercent(allRepairCoverage.levenshteinRelativeWithin[i], allRepairCoverage.count)
                            + " | " + countPercent(allRepairCoverage.rawAstRelativeWithin[i], allRepairCoverage.count)
                            + " | " + countPercent(allRepairCoverage.fastRewriteRelativeWithin[i], allRepairCoverage.count)
                            + " | " + countPercent(allRepairCoverage.canonicalRelativeWithin[i], allRepairCoverage.count)
                            + " |\n");
                }
            }
            writer.write("\n");
            writer.write("- Plot: `relative_repair_coverage_comparison.svg`\n\n");

            writer.write("## Raw Edit Distance Coverage Comparison\n\n");
            writer.write("The SVG plots empirical coverage curves over absolute edit-distance radius for Raw AST "
                    + "and Certificate-Integrated IR repairs. The table also retains Fast Rewrite IR coverage. "
                    + "Its x-axis is capped at " + RAW_EDIT_REPAIR_PLOT_MAX_DISTANCE
                    + " edits; predicates beyond that radius remain in the coverage denominator.\n\n");
            writer.write("| Edit-distance radius | Raw AST | Fast Rewrite IR | Certificate-Integrated IR |\n");
            writer.write("| --- | ---: | ---: | ---: |\n");
            if (allRepairCoverage != null) {
                for (int i = 0; i < REPAIR_RADII.length; i++) {
                    writer.write("| <= " + REPAIR_RADII[i] + " | "
                            + countPercent(allRepairCoverage.rawAstWithin[i], allRepairCoverage.count)
                            + " | " + countPercent(allRepairCoverage.fastRewriteWithin[i], allRepairCoverage.count)
                            + " | " + countPercent(allRepairCoverage.canonicalWithin[i], allRepairCoverage.count)
                            + " |\n");
                }
            }
            writer.write("\n");
            writer.write("- Plot: `raw_edit_repair_coverage_ast_canonical.svg`\n\n");

            writer.write("## Repair Radius Coverage\n\n");
            writer.write("Counts show incorrect predicates whose nearest CORRECT reference is within the inclusive repair radius.\n\n");
            writer.write("Absolute radii use edit-distance units. Relative radii use distance divided by the incorrect predicate's own representation size.\n\n");
            writeRepairRadiusTable(writer, "Overall", repairOverall);
            writeRepairRadiusTable(writer, "By Incorrectness Status", repairByStatus);
            writeRepairRadiusTable(writer, "By Question Set", repairByQuestionSet);
            writeRepairRadiusTable(writer, "By Question Set and Status", repairByQuestionSetAndStatus);
            writeRelativeRepairRadiusTable(writer, "Overall Relative", repairOverall);
            writeRelativeRepairRadiusTable(writer, "Relative by Incorrectness Status", repairByStatus);
            writeRelativeRepairRadiusTable(writer, "Relative by Question Set", repairByQuestionSet);
            writeRelativeRepairRadiusTable(writer, "Relative by Question Set and Status", repairByQuestionSetAndStatus);

            writer.write("## Reward Error Correlations\n\n");
            RewardDistanceStats levenshteinReward = rewardDistanceStats(matches, "levenshtein");
            RewardDistanceStats rawAstReward = rewardDistanceStats(matches, "rawAst");
            RewardDistanceStats fastRewriteReward = rewardDistanceStats(matches, "fastRewrite");
            RewardDistanceStats canonicalReward = rewardDistanceStats(matches, "canonical");
            RewardDistanceStats levenshteinRatioReward = rewardRatioStats(matches, "levenshtein");
            RewardDistanceStats rawAstRatioReward = rewardRatioStats(matches, "rawAst");
            RewardDistanceStats fastRewriteRatioReward = rewardRatioStats(matches, "fastRewrite");
            RewardDistanceStats canonicalRatioReward = rewardRatioStats(matches, "canonical");
            writer.write("- Rewarded incorrect predicates: " + canonicalReward.count + "\n\n");
            writer.write("| Metric | Pearson distance vs raw `1 - reward` | Pearson distance vs `log10(1 - reward)` | Pearson relative distance vs raw `1 - reward` | Pearson relative distance vs `log10(1 - reward)` |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: |\n");
            writer.write("| Levenshtein | " + number(levenshteinReward.rawCorrelation()) + " | "
                    + number(levenshteinReward.logCorrelation()) + " | "
                    + number(levenshteinRatioReward.rawCorrelation()) + " | "
                    + number(levenshteinRatioReward.logCorrelation()) + " |\n");
            writer.write("| Raw AST | " + number(rawAstReward.rawCorrelation()) + " | "
                    + number(rawAstReward.logCorrelation()) + " | "
                    + number(rawAstRatioReward.rawCorrelation()) + " | "
                    + number(rawAstRatioReward.logCorrelation()) + " |\n");
            writer.write("| Fast Rewrite IR | " + number(fastRewriteReward.rawCorrelation()) + " | "
                    + number(fastRewriteReward.logCorrelation()) + " | "
                    + number(fastRewriteRatioReward.rawCorrelation()) + " | "
                    + number(fastRewriteRatioReward.logCorrelation()) + " |\n");
            writer.write("| Certificate-Integrated IR | " + number(canonicalReward.rawCorrelation()) + " | "
                    + number(canonicalReward.logCorrelation()) + " | "
                    + number(canonicalRatioReward.rawCorrelation()) + " | "
                    + number(canonicalRatioReward.logCorrelation()) + " |\n\n");
            writer.write("- Raw plot: `canonical_distance_vs_reward_error_raw.svg`\n");
            writer.write("- Log plot: `canonical_distance_vs_reward_error_log.svg`\n");
            writer.write("- CSV: `canonical_reward_points.csv`\n\n");

            writer.write("## By Question Set\n\n");
            writer.write("| Question set | Groups | CORRECT | Incorrect | References | Ranked incorrect | Oracle-only groups |\n");
            writer.write("| --- | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (QuestionSetStats stats : questionStats.values()) {
                writer.write("| " + stats.questionSet + " | " + stats.groups + " | " + stats.correct + " | "
                        + stats.incorrect + " | " + stats.references + " | " + stats.rankedIncorrect
                        + " | " + stats.oracleOnlyGroups + " |\n");
            }
        }
    }

    private static void writeRepairRadiusTable(
            Writer writer,
            String title,
            Map<String, RepairRadiusStats> stats) throws IOException {
        writer.write("### " + title + "\n\n");
        writer.write("| Slice | Count");
        for (int radius : REPAIR_RADII) {
            writer.write(" | AST <= " + radius);
        }
        for (int radius : REPAIR_RADII) {
            writer.write(" | Fast Rewrite <= " + radius);
        }
        for (int radius : REPAIR_RADII) {
            writer.write(" | Certificate-Integrated <= " + radius);
        }
        writer.write(" |\n");
        writer.write("| --- | ---:");
        for (int ignored : REPAIR_RADII) {
            writer.write(" | ---:");
        }
        for (int ignored : REPAIR_RADII) {
            writer.write(" | ---:");
        }
        for (int ignored : REPAIR_RADII) {
            writer.write(" | ---:");
        }
        writer.write(" |\n");
        for (RepairRadiusStats row : stats.values()) {
            writer.write("| " + row.label + " | " + row.count);
            for (int i = 0; i < REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.rawAstWithin[i], row.count));
            }
            for (int i = 0; i < REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.fastRewriteWithin[i], row.count));
            }
            for (int i = 0; i < REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.canonicalWithin[i], row.count));
            }
            writer.write(" |\n");
        }
        writer.write("\n");
    }

    private static void writeRelativeRepairRadiusTable(
            Writer writer,
            String title,
            Map<String, RepairRadiusStats> stats) throws IOException {
        writer.write("### " + title + "\n\n");
        writer.write("| Slice | Count");
        for (double radius : RELATIVE_REPAIR_RADII) {
            writer.write(" | Levenshtein <= " + percentLabel(radius));
        }
        for (double radius : RELATIVE_REPAIR_RADII) {
            writer.write(" | AST <= " + percentLabel(radius));
        }
        for (double radius : RELATIVE_REPAIR_RADII) {
            writer.write(" | Fast Rewrite <= " + percentLabel(radius));
        }
        for (double radius : RELATIVE_REPAIR_RADII) {
            writer.write(" | Certificate-Integrated <= " + percentLabel(radius));
        }
        writer.write(" |\n");
        writer.write("| --- | ---:");
        for (double ignored : RELATIVE_REPAIR_RADII) {
            writer.write(" | ---:");
        }
        for (double ignored : RELATIVE_REPAIR_RADII) {
            writer.write(" | ---:");
        }
        for (double ignored : RELATIVE_REPAIR_RADII) {
            writer.write(" | ---:");
        }
        for (double ignored : RELATIVE_REPAIR_RADII) {
            writer.write(" | ---:");
        }
        writer.write(" |\n");
        for (RepairRadiusStats row : stats.values()) {
            writer.write("| " + row.label + " | " + row.count);
            for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.levenshteinRelativeWithin[i], row.count));
            }
            for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.rawAstRelativeWithin[i], row.count));
            }
            for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.fastRewriteRelativeWithin[i], row.count));
            }
            for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                writer.write(" | " + countPercent(row.canonicalRelativeWithin[i], row.count));
            }
            writer.write(" |\n");
        }
        writer.write("\n");
    }

    private static void writeGroupJson(Writer writer, QuestionGroup group) throws IOException {
        writer.write("    {\n");
        writer.write("      \"questionSet\": \"" + escape(group.questionSet) + "\",\n");
        writer.write("      \"invariantId\": \"" + escape(group.invariantId) + "\",\n");
        writer.write("      \"augmentedFile\": \"" + escape("correct/" + group.questionSet + "/" + group.invariantId + ".als") + "\",\n");
        writer.write("      \"correctCount\": " + group.correct.size() + ",\n");
        writer.write("      \"incorrectCount\": " + group.incorrect.size() + ",\n");
        writer.write("      \"parseFailureCount\": " + group.failures.size() + ",\n");
        writer.write("      \"referenceError\": "
                + (group.referenceError == null
                        ? "null" : "\"" + escape(group.referenceError) + "\"")
                + ",\n");
        long rankingFailures = group.incorrect.stream()
                .filter(record -> record.rankingError != null)
                .count();
        writer.write("      \"rankingFailureCount\": " + rankingFailures + ",\n");
        writer.write("      \"truthCandidateCount\": " + group.truthCandidateCount + ",\n");
        writer.write("      \"astDistinctTruthCount\": " + group.references.size() + ",\n");
        writer.write("      \"astDuplicateTruthCount\": " + group.astDuplicateTruthCount + ",\n");
        writer.write("      \"referenceCount\": " + group.references.size() + ",\n");
        writer.write("      \"rankingMode\": \"" + escape(group.rankingMode()) + "\",\n");
        writer.write("      \"rankingReferenceCount\": " + group.rankingReferences().size() + ",\n");
        writer.write("      \"references\": [");
        for (int i = 0; i < group.references.size(); i++) {
            Reference reference = group.references.get(i);
            if (i > 0) {
                writer.write(", ");
            }
            writer.write("{\"name\": \"" + escape(reference.augmentedName) + "\", \"kind\": \""
                    + escape(reference.kind) + "\", \"source\": \"" + escape(reference.source.relativePath) + "\"}");
        }
        writer.write("]\n");
        writer.write("    }");
    }

    private static void writeRewardCsv(Path path, List<IncorrectMatch> matches) throws IOException {
        try (Writer writer = outputWriter(path)) {
            writer.write("relativePath,questionSet,statusFolder,invariantId,levenshteinDistance,rawAstDistance,canonicalDistance,legacyCanonicalDistance,levenshteinSize,rawAstSize,canonicalSize,legacyCanonicalSize,levenshteinDistanceRatio,rawAstDistanceRatio,canonicalDistanceRatio,legacyCanonicalDistanceRatio,candidateReward,rewardError,rewardPoolSize,rewardErrorMessage\n");
            for (IncorrectMatch match : matches) {
                writer.write(csv(match.record.relativePath) + ",");
                writer.write(csv(match.record.questionSet) + ",");
                writer.write(csv(match.record.statusFolder) + ",");
                writer.write(csv(match.record.invariantId) + ",");
                writer.write(match.levenshtein.first().distance + ",");
                writer.write(match.rawAst.first().distance + ",");
                writer.write(match.canonical.first().distance + ",");
                writer.write(match.legacyCanonical.first().distance + ",");
                writer.write(match.record.levenshteinSize + ",");
                writer.write(match.record.rawAstSize + ",");
                writer.write(match.record.canonicalSize + ",");
                writer.write(match.record.legacyCanonicalSize + ",");
                writer.write(number(ratio(match.levenshtein.first().distance, match.record.levenshteinSize)) + ",");
                writer.write(number(ratio(match.rawAst.first().distance, match.record.rawAstSize)) + ",");
                writer.write(number(ratio(match.canonical.first().distance, match.record.canonicalSize)) + ",");
                writer.write(number(ratio(
                        match.legacyCanonical.first().distance,
                        match.record.legacyCanonicalSize)) + ",");
                if (match.rewardComputed && match.rewardErrorMessage == null) {
                    writer.write(number(match.candidateReward) + ",");
                    writer.write(number(match.rewardError) + ",");
                } else {
                    writer.write(",,");
                }
                writer.write(match.rewardPoolSize + ",");
                writer.write(csv(match.rewardComputed ? match.rewardErrorMessage : "Reward computation skipped."));
                writer.write("\n");
            }
        }
    }

    private static void writeRewardPlots(Path outputDir, List<IncorrectMatch> matches) throws IOException {
        List<IncorrectMatch> rewarded = new ArrayList<>();
        for (IncorrectMatch match : matches) {
            if (match.rewardComputed && match.rewardErrorMessage == null) {
                rewarded.add(match);
            }
        }
        if (rewarded.isEmpty()) {
            return;
        }
        writeRewardPlot(
                outputDir.resolve("canonical_distance_vs_reward_error_raw.svg"),
                rewarded,
                false,
                "Canonical distance vs reward error");
        writeRewardPlot(
                outputDir.resolve("canonical_distance_vs_reward_error_log.svg"),
                rewarded,
                true,
                "Canonical distance vs log reward error");
    }

    private static void writeRewardPlot(
            Path path,
            List<IncorrectMatch> matches,
            boolean logScale,
            String title) throws IOException {
        int width = 1000;
        int height = 620;
        int left = 80;
        int right = 30;
        int top = 58;
        int bottom = 76;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;
        double xMax = 1.0;
        double yMax = 1.0;
        double yMin = logScale ? rewardErrorFloor(matches) : 0.0;
        for (IncorrectMatch match : matches) {
            xMax = Math.max(xMax, match.canonical.first().distance);
            yMax = Math.max(yMax, yValue(match, logScale, yMin));
        }
        double yPlotMin = logScale ? Math.floor(Math.log10(yMin)) : 0.0;
        double yPlotMax = logScale ? Math.ceil(Math.log10(yMax)) : Math.max(1.0, yMax);
        if (yPlotMin == yPlotMax) {
            yPlotMax += 1.0;
        }
        try (Writer writer = outputWriter(path)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                    + "\" viewBox=\"0 0 " + width + " " + height + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"" + (width / 2) + "\" y=\"32\" text-anchor=\"middle\" "
                    + "font-family=\"sans-serif\" font-size=\"20\">" + escapeXml(title) + "</text>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + (top + plotHeight) + "\" x2=\""
                    + (left + plotWidth) + "\" y2=\"" + (top + plotHeight)
                    + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + top + "\" x2=\"" + left + "\" y2=\""
                    + (top + plotHeight) + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            for (int tick = 0; tick <= 5; tick++) {
                double xValue = xMax * tick / 5.0;
                double x = left + xValue * plotWidth / xMax;
                writer.write("<line x1=\"" + number(x) + "\" y1=\"" + (top + plotHeight)
                        + "\" x2=\"" + number(x) + "\" y2=\"" + (top + plotHeight + 5)
                        + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + number(x) + "\" y=\"" + (top + plotHeight + 22)
                        + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">"
                        + number(xValue) + "</text>\n");
            }
            for (int tick = 0; tick <= 5; tick++) {
                double yTick = yPlotMin + (yPlotMax - yPlotMin) * tick / 5.0;
                double y = top + plotHeight - (yTick - yPlotMin) * plotHeight / (yPlotMax - yPlotMin);
                String label = logScale ? "1e" + Math.round(yTick) : number(yTick);
                writer.write("<line x1=\"" + (left - 5) + "\" y1=\"" + number(y)
                        + "\" x2=\"" + left + "\" y2=\"" + number(y) + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + (left - 10) + "\" y=\"" + number(y + 4)
                        + "\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"12\">"
                        + escapeXml(label) + "</text>\n");
            }
            for (IncorrectMatch match : matches) {
                double x = left + match.canonical.first().distance * plotWidth / xMax;
                double yRaw = yValue(match, logScale, yMin);
                double yScaled = logScale ? Math.log10(yRaw) : yRaw;
                double y = top + plotHeight - (yScaled - yPlotMin) * plotHeight / (yPlotMax - yPlotMin);
                writer.write("<circle cx=\"" + number(x) + "\" cy=\"" + number(y)
                        + "\" r=\"3\" fill=\"" + statusColor(match.record.statusFolder)
                        + "\" fill-opacity=\"0.62\"><title>"
                        + escapeXml(match.record.relativePath + " d=" + match.canonical.first().distance
                                + " reward=" + number(match.candidateReward))
                        + "</title></circle>\n");
            }
            writer.write("<text x=\"" + (left + plotWidth / 2) + "\" y=\"" + (height - 22)
                    + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">Minimum canonical distance to oracle/correct pool</text>\n");
            writer.write("<text x=\"18\" y=\"" + (top + plotHeight / 2)
                    + "\" transform=\"rotate(-90 18 " + (top + plotHeight / 2)
                    + ")\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">1 - reward</text>\n");
            writer.write("</svg>\n");
        }
    }

    private static void writeRelativeRepairCoveragePlot(Path path, List<IncorrectMatch> matches) throws IOException {
        if (matches.isEmpty()) {
            return;
        }
        double[] levenshteinRatios = relativeRepairRatios(matches, "levenshtein");
        double[] rawAstRatios = relativeRepairRatios(matches, "rawAst");
        double[] canonicalRatios = relativeRepairRatios(matches, "canonical");
        int width = 980;
        int height = 620;
        int left = 88;
        int right = 42;
        int top = 70;
        int bottom = 86;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;
        int baseline = top + plotHeight;
        try (Writer writer = outputWriter(path)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                    + "\" viewBox=\"0 0 " + width + " " + height + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"" + (width / 2) + "\" y=\"34\" text-anchor=\"middle\" "
                    + "font-family=\"sans-serif\" font-size=\"20\">Relative repair coverage by metric</text>\n");
            writer.write("<text x=\"" + (width / 2) + "\" y=\"54\" text-anchor=\"middle\" "
                    + "font-family=\"sans-serif\" font-size=\"12\" fill=\"#555\">Empirical coverage curve for nearest correct repairs from 0% to 100% of representation size</text>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + baseline + "\" x2=\""
                    + (left + plotWidth) + "\" y2=\"" + baseline
                    + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + top + "\" x2=\"" + left + "\" y2=\""
                    + baseline + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            for (int tick = 0; tick <= 5; tick++) {
                double yValue = tick / 5.0;
                double y = baseline - yValue * plotHeight;
                writer.write("<line x1=\"" + left + "\" y1=\"" + number(y)
                        + "\" x2=\"" + (left + plotWidth) + "\" y2=\"" + number(y)
                        + "\" stroke=\"#e8e8e8\"/>\n");
                writer.write("<line x1=\"" + (left - 5) + "\" y1=\"" + number(y)
                        + "\" x2=\"" + left + "\" y2=\"" + number(y) + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + (left - 10) + "\" y=\"" + number(y + 4)
                        + "\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"12\">"
                        + escapeXml(percentLabel(yValue)) + "</text>\n");
            }
            for (int tick = 0; tick <= 5; tick++) {
                double fraction = tick / 5.0;
                double x = left + fraction * plotWidth;
                writer.write("<line x1=\"" + number(x) + "\" y1=\"" + top
                        + "\" x2=\"" + number(x) + "\" y2=\"" + baseline
                        + "\" stroke=\"#f0f0f0\"/>\n");
                writer.write("<line x1=\"" + number(x) + "\" y1=\"" + baseline
                        + "\" x2=\"" + number(x) + "\" y2=\"" + (baseline + 5)
                        + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + number(x) + "\" y=\"" + (baseline + 24)
                        + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">"
                        + escapeXml(percentLabel(fraction)) + "</text>\n");
            }
            writeRelativeCoverageSeries(writer, levenshteinRatios, "levenshtein", left, top, plotWidth, plotHeight);
            writeRelativeCoverageSeries(writer, rawAstRatios, "rawAst", left, top, plotWidth, plotHeight);
            writeRelativeCoverageSeries(writer, canonicalRatios, "canonical", left, top, plotWidth, plotHeight);
            writeMetricLegendItem(writer, width - 340, 82, "Levenshtein / lexical size", metricColor("levenshtein"));
            writeMetricLegendItem(writer, width - 340, 104, "Raw AST / AST size", metricColor("rawAst"));
            writeMetricLegendItem(writer, width - 340, 126, "Canonical / canonical size", metricColor("canonical"));
            writer.write("<text x=\"" + (left + plotWidth / 2) + "\" y=\"" + (height - 26)
                    + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">Repair radius as fraction of representation size</text>\n");
            writer.write("<text x=\"22\" y=\"" + (top + plotHeight / 2)
                    + "\" transform=\"rotate(-90 22 " + (top + plotHeight / 2)
                    + ")\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">Incorrect predicates repaired within radius</text>\n");
            writer.write("</svg>\n");
        }
    }

    private static void writeRawEditRepairCoveragePlot(Path path, List<IncorrectMatch> matches) throws IOException {
        if (matches.isEmpty()) {
            return;
        }
        int[] rawAstDistances = rawRepairDistances(matches, "rawAst");
        int[] canonicalDistances = rawRepairDistances(matches, "canonical");
        int xMax = RAW_EDIT_REPAIR_PLOT_MAX_DISTANCE;
        int width = 980;
        int height = 620;
        int left = 88;
        int right = 42;
        int top = 70;
        int bottom = 86;
        int plotWidth = width - left - right;
        int plotHeight = height - top - bottom;
        int baseline = top + plotHeight;
        try (Writer writer = outputWriter(path)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                    + "\" viewBox=\"0 0 " + width + " " + height + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"" + (width / 2) + "\" y=\"34\" text-anchor=\"middle\" "
                    + "font-family=\"sans-serif\" font-size=\"20\">Repair coverage by edit distance</text>\n");
            //writer.write("<text x=\"" + (width / 2) + "\" y=\"54\" text-anchor=\"middle\" "
            //        + "font-family=\"sans-serif\" font-size=\"12\" fill=\"#555\">Empirical coverage curve for nearest correct repairs, excluding Levenshtein</text>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + baseline + "\" x2=\""
                    + (left + plotWidth) + "\" y2=\"" + baseline
                    + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            writer.write("<line x1=\"" + left + "\" y1=\"" + top + "\" x2=\"" + left + "\" y2=\""
                    + baseline + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
            for (int tick = 0; tick <= 5; tick++) {
                double yValue = tick / 5.0;
                double y = baseline - yValue * plotHeight;
                writer.write("<line x1=\"" + left + "\" y1=\"" + number(y)
                        + "\" x2=\"" + (left + plotWidth) + "\" y2=\"" + number(y)
                        + "\" stroke=\"#e8e8e8\"/>\n");
                writer.write("<line x1=\"" + (left - 5) + "\" y1=\"" + number(y)
                        + "\" x2=\"" + left + "\" y2=\"" + number(y) + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + (left - 10) + "\" y=\"" + number(y + 4)
                        + "\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"12\">"
                        + escapeXml(percentLabel(yValue)) + "</text>\n");
            }
            for (int tick = 0; tick <= RAW_EDIT_REPAIR_PLOT_TICK_COUNT; tick++) {
                int radius = (int) Math.round(xMax * tick / (double) RAW_EDIT_REPAIR_PLOT_TICK_COUNT);
                double x = left + radius * plotWidth / (double) xMax;
                writer.write("<line x1=\"" + number(x) + "\" y1=\"" + top
                        + "\" x2=\"" + number(x) + "\" y2=\"" + baseline
                        + "\" stroke=\"#f0f0f0\"/>\n");
                writer.write("<line x1=\"" + number(x) + "\" y1=\"" + baseline
                        + "\" x2=\"" + number(x) + "\" y2=\"" + (baseline + 5)
                        + "\" stroke=\"#222\"/>\n");
                writer.write("<text x=\"" + number(x) + "\" y=\"" + (baseline + 24)
                        + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">"
                        + radius + "</text>\n");
            }
            writeRawEditCoverageSeries(writer, rawAstDistances, "rawAst", xMax, left, top, plotWidth, plotHeight);
            writeRawEditCoverageSeries(writer, canonicalDistances, "canonical", xMax, left, top, plotWidth, plotHeight);
            writeMetricLegendItem(writer, width - 320, 82, "Raw AST", metricColor("rawAst"));
            writeMetricLegendItem(writer, width - 320, 104, "Canonical", metricColor("canonical"));
            writer.write("<text x=\"" + (left + plotWidth / 2) + "\" y=\"" + (height - 26)
                    + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">Absolute edit-distance repair radius</text>\n");
            writer.write("<text x=\"22\" y=\"" + (top + plotHeight / 2)
                    + "\" transform=\"rotate(-90 22 " + (top + plotHeight / 2)
                    + ")\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"14\">Incorrect predicates repaired within radius</text>\n");
            writer.write("</svg>\n");
        }
    }

    private static void writeRawEditCoverageSeries(
            Writer writer,
            int[] sortedDistances,
            String metric,
            int xMax,
            int left,
            int top,
            int plotWidth,
            int plotHeight) throws IOException {
        String color = metricColor(metric);
        StringBuilder path = new StringBuilder();
        for (int radius = 0; radius <= xMax; radius++) {
            double covered = sortedDistances.length == 0
                    ? 0.0
                    : upperBound(sortedDistances, radius) / (double) sortedDistances.length;
            double x = left + radius * plotWidth / (double) xMax;
            double y = top + plotHeight - covered * plotHeight;
            path.append(radius == 0 ? "M " : " L ")
                    .append(number(x))
                    .append(' ')
                    .append(number(y));
        }
        writer.write("<path d=\"" + path + "\" fill=\"none\" stroke=\"" + color
                + "\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n");
        for (int radius : REPAIR_RADII) {
            if (radius > xMax) {
                continue;
            }
            int count = upperBound(sortedDistances, radius);
            double covered = sortedDistances.length == 0 ? 0.0 : count / (double) sortedDistances.length;
            double x = left + radius * plotWidth / (double) xMax;
            double y = top + plotHeight - covered * plotHeight;
            writer.write("<circle cx=\"" + number(x) + "\" cy=\"" + number(y)
                    + "\" r=\"5\" fill=\"" + color + "\"><title>"
                    + escapeXml(repairMetricTitle(metric) + " <= " + radius + ": "
                            + countPercent(count, sortedDistances.length))
                    + "</title></circle>\n");
        }
    }

    private static void writeRelativeCoverageSeries(
            Writer writer,
            double[] sortedRatios,
            String metric,
            int left,
            int top,
            int plotWidth,
            int plotHeight) throws IOException {
        String color = metricColor(metric);
        StringBuilder path = new StringBuilder();
        for (int i = 0; i <= RELATIVE_REPAIR_CURVE_STEPS; i++) {
            double fraction = i / (double) RELATIVE_REPAIR_CURVE_STEPS;
            double covered = relativeCoverageRatio(sortedRatios, fraction);
            double x = left + fraction * plotWidth;
            double y = top + plotHeight - covered * plotHeight;
            path.append(i == 0 ? "M " : " L ")
                    .append(number(x))
                    .append(' ')
                    .append(number(y));
        }
        writer.write("<path d=\"" + path + "\" fill=\"none\" stroke=\"" + color
                + "\" stroke-width=\"3\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n");
        for (double radius : RELATIVE_REPAIR_RADII) {
            int count = upperBound(sortedRatios, radius);
            double covered = sortedRatios.length == 0 ? 0.0 : count / (double) sortedRatios.length;
            double x = left + radius * plotWidth;
            double y = top + plotHeight - covered * plotHeight;
            writer.write("<circle cx=\"" + number(x) + "\" cy=\"" + number(y)
                    + "\" r=\"5\" fill=\"" + color + "\"><title>"
                    + escapeXml(repairMetricTitle(metric) + " <= " + percentLabel(radius)
                            + ": " + countPercent(count, sortedRatios.length))
                    + "</title></circle>\n");
        }
    }

    private static double[] relativeRepairRatios(List<IncorrectMatch> matches, String metric) {
        double[] ratios = new double[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            ratios[i] = repairMetricRatio(matches.get(i), metric);
        }
        Arrays.sort(ratios);
        return ratios;
    }

    private static int[] rawRepairDistances(List<IncorrectMatch> matches, String metric) {
        int[] distances = new int[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            distances[i] = repairMetricDistance(matches.get(i), metric);
        }
        Arrays.sort(distances);
        return distances;
    }

    private static double relativeCoverageRatio(double[] sortedRatios, double radiusFraction) {
        return sortedRatios.length == 0 ? 0.0 : upperBound(sortedRatios, radiusFraction) / (double) sortedRatios.length;
    }

    private static int upperBound(double[] sortedValues, double value) {
        int low = 0;
        int high = sortedValues.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedValues[mid] <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static int upperBound(int[] sortedValues, int value) {
        int low = 0;
        int high = sortedValues.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedValues[mid] <= value) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static int relativeCoverageCount(RepairRadiusStats coverage, String metric, int index) {
        if ("levenshtein".equals(metric)) {
            return coverage.levenshteinRelativeWithin[index];
        }
        if ("rawAst".equals(metric)) {
            return coverage.rawAstRelativeWithin[index];
        }
        return coverage.canonicalRelativeWithin[index];
    }

    private static double relativeCoverageRatio(RepairRadiusStats coverage, String metric, int index) {
        return coverage.count == 0 ? 0.0 : relativeCoverageCount(coverage, metric, index) / (double) coverage.count;
    }

    private static String metricColor(String metric) {
        if ("levenshtein".equals(metric)) {
            return "#2ca02c";
        }
        if ("rawAst".equals(metric)) {
            return "#ff7f0e";
        }
        return "#1f77b4";
    }

    private static void writeMetricLegendItem(Writer writer, int x, int y, String label, String color) throws IOException {
        writer.write("<line x1=\"" + x + "\" y1=\"" + y + "\" x2=\"" + (x + 24)
                + "\" y2=\"" + y + "\" stroke=\"" + color + "\" stroke-width=\"3\"/>\n");
        writer.write("<circle cx=\"" + (x + 12) + "\" cy=\"" + y + "\" r=\"4\" fill=\"" + color + "\"/>\n");
        writer.write("<text x=\"" + (x + 32) + "\" y=\"" + (y + 4)
                + "\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\">"
                + escapeXml(label) + "</text>\n");
    }

    private static void writeRepairRatioRegressionPlot(Path path, List<IncorrectMatch> matches) throws IOException {
        if (matches.isEmpty()) {
            return;
        }
        int width = 1150;
        int height = 1040;
        int left = 92;
        int right = 34;
        int top = 72;
        int bottom = 72;
        int gap = 70;
        int plotWidth = width - left - right;
        int plotHeight = (height - top - bottom - 2 * gap) / 3;
        try (Writer writer = outputWriter(path)) {
            writer.write("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"" + width + "\" height=\"" + height
                    + "\" viewBox=\"0 0 " + width + " " + height + "\">\n");
            writer.write("<rect width=\"100%\" height=\"100%\" fill=\"white\"/>\n");
            writer.write("<text x=\"" + (width / 2) + "\" y=\"34\" text-anchor=\"middle\" "
                    + "font-family=\"sans-serif\" font-size=\"20\">Repair distance ratio vs representation size</text>\n");
            writeRepairRatioLegend(writer, width - 430, 22);
            writeRepairRatioPanel(writer, matches, "levenshtein", left, top, plotWidth, plotHeight);
            writeRepairRatioPanel(writer, matches, "rawAst", left, top + plotHeight + gap, plotWidth, plotHeight);
            writeRepairRatioPanel(writer, matches, "canonical", left, top + 2 * (plotHeight + gap), plotWidth, plotHeight);
            writer.write("</svg>\n");
        }
    }

    private static void writeRepairRatioPanel(
            Writer writer,
            List<IncorrectMatch> matches,
            String metric,
            int left,
            int top,
            int plotWidth,
            int plotHeight) throws IOException {
        double xMax = 1.0;
        double yMax = 1.0;
        for (IncorrectMatch match : matches) {
            xMax = Math.max(xMax, repairMetricSize(match, metric));
            yMax = Math.max(yMax, repairMetricRatio(match, metric));
        }
        yMax = Math.max(1.0, yMax * 1.08);
        RepairRatioRegressionStats stats = repairRatioRegressionStats(matches, metric);
        int baseline = top + plotHeight;
        writer.write("<text x=\"" + left + "\" y=\"" + (top - 20)
                + "\" font-family=\"sans-serif\" font-size=\"16\" font-weight=\"600\">"
                + escapeXml(repairMetricTitle(metric)) + "</text>\n");
        writer.write("<text x=\"" + left + "\" y=\"" + (top - 4)
                + "\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#555\">Pearson r="
                + number(stats.correlation()) + ", slope=" + number(stats.slope()) + "</text>\n");
        writer.write("<line x1=\"" + left + "\" y1=\"" + baseline + "\" x2=\""
                + (left + plotWidth) + "\" y2=\"" + baseline
                + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
        writer.write("<line x1=\"" + left + "\" y1=\"" + top + "\" x2=\"" + left + "\" y2=\""
                + baseline + "\" stroke=\"#222\" stroke-width=\"1\"/>\n");
        for (int tick = 0; tick <= 5; tick++) {
            double xValue = xMax * tick / 5.0;
            double x = left + xValue * plotWidth / xMax;
            writer.write("<line x1=\"" + number(x) + "\" y1=\"" + top
                    + "\" x2=\"" + number(x) + "\" y2=\"" + baseline
                    + "\" stroke=\"#e6e6e6\"/>\n");
            writer.write("<line x1=\"" + number(x) + "\" y1=\"" + baseline
                    + "\" x2=\"" + number(x) + "\" y2=\"" + (baseline + 5)
                    + "\" stroke=\"#222\"/>\n");
            writer.write("<text x=\"" + number(x) + "\" y=\"" + (baseline + 21)
                    + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"11\">"
                    + escapeXml(axisNumber(xValue)) + "</text>\n");
        }
        for (int tick = 0; tick <= 5; tick++) {
            double yValue = yMax * tick / 5.0;
            double y = baseline - yValue * plotHeight / yMax;
            writer.write("<line x1=\"" + left + "\" y1=\"" + number(y)
                    + "\" x2=\"" + (left + plotWidth) + "\" y2=\"" + number(y)
                    + "\" stroke=\"#e6e6e6\"/>\n");
            writer.write("<line x1=\"" + (left - 5) + "\" y1=\"" + number(y)
                    + "\" x2=\"" + left + "\" y2=\"" + number(y) + "\" stroke=\"#222\"/>\n");
            writer.write("<text x=\"" + (left - 10) + "\" y=\"" + number(y + 4)
                    + "\" text-anchor=\"end\" font-family=\"sans-serif\" font-size=\"11\">"
                    + escapeXml(axisNumber(yValue)) + "</text>\n");
        }
        for (IncorrectMatch match : matches) {
            double xValue = repairMetricSize(match, metric);
            double yValue = repairMetricRatio(match, metric);
            double x = left + xValue * plotWidth / xMax;
            double y = baseline - yValue * plotHeight / yMax;
            writer.write("<circle cx=\"" + number(x) + "\" cy=\"" + number(y)
                    + "\" r=\"2.7\" fill=\"" + statusColor(match.record.statusFolder)
                    + "\" fill-opacity=\"0.50\"><title>"
                    + escapeXml(match.record.relativePath + " size=" + axisNumber(xValue)
                            + " ratio=" + number(yValue))
                    + "</title></circle>\n");
        }
        double y0 = clamp(stats.intercept(), 0.0, yMax);
        double y1 = clamp(stats.intercept() + stats.slope() * xMax, 0.0, yMax);
        double lineY0 = baseline - y0 * plotHeight / yMax;
        double lineY1 = baseline - y1 * plotHeight / yMax;
        writer.write("<line x1=\"" + left + "\" y1=\"" + number(lineY0)
                + "\" x2=\"" + (left + plotWidth) + "\" y2=\"" + number(lineY1)
                + "\" stroke=\"#111\" stroke-width=\"2.2\" stroke-opacity=\"0.82\"/>\n");
        writer.write("<text x=\"" + (left + plotWidth / 2) + "\" y=\"" + (baseline + 43)
                + "\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">"
                + escapeXml(repairMetricXAxis(metric)) + "</text>\n");
        writer.write("<text x=\"22\" y=\"" + (top + plotHeight / 2)
                + "\" transform=\"rotate(-90 22 " + (top + plotHeight / 2)
                + ")\" text-anchor=\"middle\" font-family=\"sans-serif\" font-size=\"12\">"
                + escapeXml(repairMetricYAxis(metric)) + "</text>\n");
    }

    private static void writeRepairRatioLegend(Writer writer, int x, int y) throws IOException {
        writeLegendItem(writer, x, y, "BOTH", statusColor("BOTH"));
        writeLegendItem(writer, x + 105, y, "OVER", statusColor("OVERCONSTRAINED"));
        writeLegendItem(writer, x + 215, y, "UNDER", statusColor("UNDERCONSTRAINED"));
    }

    private static void writeLegendItem(Writer writer, int x, int y, String label, String color) throws IOException {
        writer.write("<circle cx=\"" + x + "\" cy=\"" + y + "\" r=\"5\" fill=\"" + color
                + "\" fill-opacity=\"0.72\"/>\n");
        writer.write("<text x=\"" + (x + 10) + "\" y=\"" + (y + 4)
                + "\" font-family=\"sans-serif\" font-size=\"12\" fill=\"#333\">"
                + escapeXml(label) + "</text>\n");
    }

    private static void writePlotScript(Path path) throws IOException {
        try (Writer writer = outputWriter(path)) {
            writer.write("#!/usr/bin/env python3\n");
            writer.write("import csv, math\nfrom pathlib import Path\n\n");
            writer.write("ROOT = Path(__file__).resolve().parent\nCSV = ROOT / 'canonical_reward_points.csv'\n");
            writer.write("def corr(xs, ys):\n");
            writer.write("    if len(xs) < 2:\n        return 0.0\n");
            writer.write("    xb = sum(xs) / len(xs)\n    yb = sum(ys) / len(ys)\n");
            writer.write("    num = sum((x - xb) * (y - yb) for x, y in zip(xs, ys))\n");
            writer.write("    xd = math.sqrt(sum((x - xb) ** 2 for x in xs))\n");
            writer.write("    yd = math.sqrt(sum((y - yb) ** 2 for y in ys))\n");
            writer.write("    return 0.0 if xd == 0.0 or yd == 0.0 else num / (xd * yd)\n\n");
            writer.write("print('Use the generated SVG plots:')\n");
            writer.write("print(ROOT / 'canonical_distance_vs_reward_error_raw.svg')\n");
            writer.write("print(ROOT / 'canonical_distance_vs_reward_error_log.svg')\n");
            writer.write("print(ROOT / 'relative_repair_coverage_comparison.svg')\n");
            writer.write("print(ROOT / 'raw_edit_repair_coverage_ast_canonical.svg')\n");
            writer.write("with CSV.open() as f:\n");
            writer.write("    all_rows = list(csv.DictReader(f))\n");
            writer.write("rows = [r for r in all_rows if r.get('candidateReward')]\n");
            writer.write("print(f'Loaded {len(rows)} rewarded points from {CSV}')\n");
            writer.write("errs = [float(r['rewardError']) for r in rows]\n");
            writer.write("positive = [e for e in errs if e > 0.0]\n");
            writer.write("floor = min(positive) / 10.0 if positive else 1e-6\n");
            writer.write("logs = [math.log10(max(e, floor)) for e in errs]\n");
            writer.write("for key, ratio_key, label in [('levenshteinDistance', 'levenshteinDistanceRatio', 'Levenshtein'), ('rawAstDistance', 'rawAstDistanceRatio', 'Raw AST'), ('legacyCanonicalDistance', 'legacyCanonicalDistanceRatio', 'Fast Rewrite IR'), ('canonicalDistance', 'canonicalDistanceRatio', 'Certificate-Integrated IR')]:\n");
            writer.write("    xs = [float(r[key]) for r in rows]\n");
            writer.write("    ratios = [float(r[ratio_key]) for r in rows]\n");
            writer.write("    print(f\"Pearson {label} distance vs raw 1-reward: {corr(xs, errs):.6f}\")\n");
            writer.write("    print(f\"Pearson {label} distance vs log10(1-reward): {corr(xs, logs):.6f}\")\n");
            writer.write("    print(f\"Pearson {label} ratio vs raw 1-reward: {corr(ratios, errs):.6f}\")\n");
            writer.write("    print(f\"Pearson {label} ratio vs log10(1-reward): {corr(ratios, logs):.6f}\")\n");
            writer.write("for size_key, ratio_key, label in [('levenshteinSize', 'levenshteinDistanceRatio', 'Levenshtein'), ('rawAstSize', 'rawAstDistanceRatio', 'Raw AST'), ('legacyCanonicalSize', 'legacyCanonicalDistanceRatio', 'Fast Rewrite IR'), ('canonicalSize', 'canonicalDistanceRatio', 'Certificate-Integrated IR')]:\n");
            writer.write("    xs = [float(r[size_key]) for r in all_rows if r.get(size_key) and r.get(ratio_key)]\n");
            writer.write("    ys = [float(r[ratio_key]) for r in all_rows if r.get(size_key) and r.get(ratio_key)]\n");
            writer.write("    print(f\"Pearson {label} repair ratio vs representation size: {corr(xs, ys):.6f}\")\n");
        }
    }

    private static void writeMatchJson(Writer writer, IncorrectMatch match) throws IOException {
        ModelRecord record = match.record;
        writer.write("    {\n");
        writer.write("      \"fileName\": \"" + escape(record.fileName) + "\",\n");
        writer.write("      \"relativePath\": \"" + escape(record.relativePath) + "\",\n");
        writer.write("      \"questionSet\": \"" + escape(record.questionSet) + "\",\n");
        writer.write("      \"statusFolder\": \"" + escape(record.statusFolder) + "\",\n");
        writer.write("      \"invariantId\": \"" + escape(record.invariantId) + "\",\n");
        writer.write("      \"leftPredicate\": \"" + escape(record.leftPredicate) + "\",\n");
        writer.write("      \"rightPredicate\": \"" + escape(record.rightPredicate) + "\",\n");
        writer.write("      \"levenshteinSize\": " + record.levenshteinSize + ",\n");
        writer.write("      \"rawAstSize\": " + record.rawAstSize + ",\n");
        writer.write("      \"canonicalSize\": " + record.canonicalSize + ",\n");
        writer.write("      \"legacyCanonicalSize\": " + record.legacyCanonicalSize + ",\n");
        writer.write("      \"levenshteinDistanceRatio\": "
                + number(ratio(match.levenshtein.first().distance, record.levenshteinSize)) + ",\n");
        writer.write("      \"rawAstDistanceRatio\": "
                + number(ratio(match.rawAst.first().distance, record.rawAstSize)) + ",\n");
        writer.write("      \"canonicalDistanceRatio\": "
                + number(ratio(match.canonical.first().distance, record.canonicalSize)) + ",\n");
        writer.write("      \"legacyCanonicalDistanceRatio\": "
                + number(ratio(
                        match.legacyCanonical.first().distance,
                        record.legacyCanonicalSize)) + ",\n");
        writer.write("      \"rewardPoolSize\": " + match.rewardPoolSize + ",\n");
        if (match.rewardComputed && match.rewardErrorMessage == null) {
            writer.write("      \"candidateReward\": " + number(match.candidateReward) + ",\n");
            writer.write("      \"rewardError\": " + number(match.rewardError) + ",\n");
        } else {
            writer.write("      \"candidateReward\": null,\n");
            writer.write("      \"rewardError\": null,\n");
            writer.write("      \"rewardErrorMessage\": \"" + escape(match.rewardComputed
                    ? match.rewardErrorMessage
                    : "Reward computation skipped.") + "\",\n");
        }
        writer.write("      \"nearestLevenshtein\": ");
        writeNearestJson(writer, match.levenshtein);
        writer.write(",\n");
        writer.write("      \"nearestRawAst\": ");
        writeNearestJson(writer, match.rawAst);
        writer.write(",\n");
        writer.write("      \"nearestCanonical\": ");
        writeNearestJson(writer, match.canonical);
        writer.write(",\n");
        writer.write("      \"nearestLegacyCanonical\": ");
        writeNearestJson(writer, match.legacyCanonical);
        writer.write(",\n");
        writer.write("      \"levenshteinRanking\": ");
        writeRankingJson(writer, match.levenshtein);
        writer.write(",\n");
        writer.write("      \"rawAstRanking\": ");
        writeRankingJson(writer, match.rawAst);
        writer.write(",\n");
        writer.write("      \"canonicalRanking\": ");
        writeRankingJson(writer, match.canonical);
        writer.write(",\n");
        writer.write("      \"legacyCanonicalRanking\": ");
        writeRankingJson(writer, match.legacyCanonical);
        writer.write("\n");
        writer.write("    }");
    }

    private static void writeUnmatchedJson(Writer writer, ModelRecord record) throws IOException {
        writer.write("    {\n");
        writer.write("      \"fileName\": \"" + escape(record.fileName) + "\",\n");
        writer.write("      \"relativePath\": \"" + escape(record.relativePath) + "\",\n");
        writer.write("      \"questionSet\": \"" + escape(record.questionSet) + "\",\n");
        writer.write("      \"statusFolder\": \"" + escape(record.statusFolder) + "\",\n");
        writer.write("      \"invariantId\": \"" + escape(record.invariantId) + "\",\n");
        writer.write("      \"leftPredicate\": \"" + escape(record.leftPredicate) + "\",\n");
        writer.write("      \"rightPredicate\": \"" + escape(record.rightPredicate) + "\",\n");
        writer.write("      \"reason\": \"No usable truth reference exists for this question set and invariant id.\"\n");
        writer.write("    }");
    }

    private static void writeRankingFailureJson(
            Writer writer,
            ModelRecord record) throws IOException {
        writer.write("    {\"relativePath\": \"" + escape(record.relativePath)
                + "\", \"questionSet\": \"" + escape(record.questionSet)
                + "\", \"statusFolder\": \"" + escape(record.statusFolder)
                + "\", \"invariantId\": \"" + escape(record.invariantId)
                + "\", \"error\": \"" + escape(record.rankingError) + "\"}");
    }

    private static void writeNearestJson(Writer writer, Nearest nearest) throws IOException {
        RankedReference first = nearest.first();
        writer.write("{\"distance\": " + first.distance + ", \"rank\": " + first.rank + ", \"referenceName\": \""
                + escape(first.reference.augmentedName) + "\", \"referenceKind\": \""
                + escape(first.reference.kind) + "\", \"referenceSource\": \""
                + escape(first.reference.source.relativePath) + "\"}");
    }

    private static void writeRankingJson(Writer writer, Nearest nearest) throws IOException {
        writer.write("[");
        for (int i = 0; i < nearest.ranking.size(); i++) {
            RankedReference ranked = nearest.ranking.get(i);
            if (i > 0) {
                writer.write(", ");
            }
            writer.write("{\"rank\": " + ranked.rank + ", \"distance\": " + ranked.distance
                    + ", \"referenceName\": \"" + escape(ranked.reference.augmentedName)
                    + "\", \"referenceKind\": \"" + escape(ranked.reference.kind)
                    + "\", \"referenceSource\": \"" + escape(ranked.reference.source.relativePath) + "\"}");
        }
        writer.write("]");
    }

    private static Map<String, QuestionSetStats> questionSetStats(
            Map<String, QuestionGroup> groups,
            List<IncorrectMatch> matches) {
        Map<String, QuestionSetStats> stats = new java.util.TreeMap<>();
        for (QuestionGroup group : groups.values()) {
            QuestionSetStats setStats = stats.computeIfAbsent(
                    group.questionSet,
                    QuestionSetStats::new);
            setStats.groups++;
            setStats.correct += group.correct.size();
            setStats.incorrect += group.incorrect.size();
            setStats.references += group.references.size();
            if ("oracle-only".equals(group.rankingMode())) {
                setStats.oracleOnlyGroups++;
            }
        }
        for (IncorrectMatch match : matches) {
            QuestionSetStats setStats = stats.computeIfAbsent(
                    match.record.questionSet,
                    QuestionSetStats::new);
            setStats.rankedIncorrect++;
        }
        return stats;
    }

    private static Map<String, CorrectTruthStats> correctTruthStats(
            Map<String, QuestionGroup> groups,
            CorrectPoolEquivalences equivalences) {
        Map<String, CorrectTruthStats> stats = new java.util.TreeMap<>();
        Map<Reference, Reference> certificateParents = new java.util.IdentityHashMap<>();
        Map<Reference, Reference> fastRewriteParents = new java.util.IdentityHashMap<>();
        for (QuestionGroup group : groups.values()) {
            CorrectTruthStats setStats = stats.computeIfAbsent(
                    group.questionSet,
                    CorrectTruthStats::new);
            setStats.correctPredicates += group.truthCandidateCount;
            setStats.astDistinctPredicates += group.references.size();
            for (Reference reference : group.references) {
                certificateParents.put(reference, reference);
                fastRewriteParents.put(reference, reference);
            }
        }
        for (CorrectPoolPair pair : equivalences.certificateIntegrated) {
            stats.computeIfAbsent(pair.group.questionSet, CorrectTruthStats::new)
                    .certificateEquivalentPairs++;
            unionCanonicalForms(certificateParents, pair.left, pair.right);
        }
        for (CorrectPoolPair pair : equivalences.fastRewrite) {
            stats.computeIfAbsent(pair.group.questionSet, CorrectTruthStats::new)
                    .fastRewriteEquivalentPairs++;
            unionCanonicalForms(fastRewriteParents, pair.left, pair.right);
        }
        for (QuestionGroup group : groups.values()) {
            Set<Reference> certificateForms =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Set<Reference> fastRewriteForms =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Reference reference : group.references) {
                certificateForms.add(canonicalRoot(certificateParents, reference));
                fastRewriteForms.add(canonicalRoot(fastRewriteParents, reference));
            }
            stats.get(group.questionSet).uniqueCertificateForms += certificateForms.size();
            stats.get(group.questionSet).uniqueFastRewriteForms += fastRewriteForms.size();
        }
        return stats;
    }

    private static void unionCanonicalForms(
            Map<Reference, Reference> parents,
            Reference left,
            Reference right) {
        Reference leftRoot = canonicalRoot(parents, left);
        Reference rightRoot = canonicalRoot(parents, right);
        if (leftRoot != rightRoot) {
            parents.put(rightRoot, leftRoot);
        }
    }

    private static Reference canonicalRoot(
            Map<Reference, Reference> parents,
            Reference reference) {
        Reference root = reference;
        while (parents.get(root) != root) {
            root = parents.get(root);
        }
        while (reference != root) {
            Reference next = parents.get(reference);
            parents.put(reference, root);
            reference = next;
        }
        return root;
    }

    private static Map<String, RankingModeStats> rankingModeStats(Map<String, QuestionGroup> groups) {
        Map<String, RankingModeStats> stats = new java.util.TreeMap<>();
        for (QuestionGroup group : groups.values()) {
            RankingModeStats modeStats = stats.computeIfAbsent(
                    group.rankingMode(),
                    RankingModeStats::new);
            modeStats.groups++;
            modeStats.incorrect += group.incorrect.size();
            int refs = group.rankingReferences().size();
            modeStats.minRefs = modeStats.minRefs == null ? refs : Math.min(modeStats.minRefs, refs);
            modeStats.maxRefs = modeStats.maxRefs == null ? refs : Math.max(modeStats.maxRefs, refs);
        }
        return stats;
    }

    private static DistanceTableStats distanceTableStats(List<IncorrectMatch> matches) {
        DistanceTableStats table = new DistanceTableStats();
        for (IncorrectMatch match : matches) {
            table.add(match);
        }
        return table;
    }

    private static Map<String, RepairRadiusStats> repairRadiusStatsOverall(List<IncorrectMatch> matches) {
        Map<String, RepairRadiusStats> stats = new java.util.LinkedHashMap<>();
        RepairRadiusStats all = new RepairRadiusStats("All incorrect");
        for (IncorrectMatch match : matches) {
            all.add(match);
        }
        stats.put(all.label, all);
        return stats;
    }

    private static Map<String, RepairRadiusStats> repairRadiusStatsByStatus(List<IncorrectMatch> matches) {
        Map<String, RepairRadiusStats> stats = new java.util.TreeMap<>();
        for (IncorrectMatch match : matches) {
            RepairRadiusStats row = stats.computeIfAbsent(
                    match.record.statusFolder,
                    RepairRadiusStats::new);
            row.add(match);
        }
        return stats;
    }

    private static Map<String, RepairRadiusStats> repairRadiusStatsByQuestionSet(List<IncorrectMatch> matches) {
        Map<String, RepairRadiusStats> stats = new java.util.TreeMap<>();
        for (IncorrectMatch match : matches) {
            RepairRadiusStats row = stats.computeIfAbsent(
                    match.record.questionSet,
                    RepairRadiusStats::new);
            row.add(match);
        }
        return stats;
    }

    private static Map<String, RepairRadiusStats> repairRadiusStatsByQuestionSetAndStatus(List<IncorrectMatch> matches) {
        Map<String, RepairRadiusStats> stats = new java.util.TreeMap<>();
        for (IncorrectMatch match : matches) {
            String key = match.record.questionSet + " / " + match.record.statusFolder;
            RepairRadiusStats row = stats.computeIfAbsent(
                    key,
                    RepairRadiusStats::new);
            row.add(match);
        }
        return stats;
    }

    private static RepairRatioRegressionStats repairRatioRegressionStats(List<IncorrectMatch> matches, String metric) {
        RepairRatioRegressionStats stats = new RepairRatioRegressionStats();
        for (IncorrectMatch match : matches) {
            stats.add(repairMetricSize(match, metric), repairMetricRatio(match, metric));
        }
        return stats;
    }

    private static RewardDistanceStats rewardDistanceStats(List<IncorrectMatch> matches, String metric) {
        RewardDistanceStats stats = new RewardDistanceStats();
        double floor = rewardErrorFloor(matches);
        for (IncorrectMatch match : matches) {
            if (match.rewardComputed && match.rewardErrorMessage == null) {
                stats.add(rewardMetricDistance(match, metric), match.rewardError, floor);
            }
        }
        return stats;
    }

    private static RewardDistanceStats rewardRatioStats(List<IncorrectMatch> matches, String metric) {
        RewardDistanceStats stats = new RewardDistanceStats();
        double floor = rewardErrorFloor(matches);
        for (IncorrectMatch match : matches) {
            if (match.rewardComputed && match.rewardErrorMessage == null) {
                stats.add(rewardMetricRatio(match, metric), match.rewardError, floor);
            }
        }
        return stats;
    }

    private static int repairMetricDistance(IncorrectMatch match, String metric) {
        if ("levenshtein".equals(metric)) {
            return match.levenshtein.first().distance;
        }
        if ("rawAst".equals(metric)) {
            return match.rawAst.first().distance;
        }
        if ("fastRewrite".equals(metric)) {
            return match.legacyCanonical.first().distance;
        }
        return match.canonical.first().distance;
    }

    private static double repairMetricSize(IncorrectMatch match, String metric) {
        if ("levenshtein".equals(metric)) {
            return match.record.levenshteinSize;
        }
        if ("rawAst".equals(metric)) {
            return match.record.rawAstSize;
        }
        if ("fastRewrite".equals(metric)) {
            return match.record.legacyCanonicalSize;
        }
        return match.record.canonicalSize;
    }

    private static double repairMetricRatio(IncorrectMatch match, String metric) {
        if ("levenshtein".equals(metric)) {
            return ratio(match.levenshtein.first().distance, match.record.levenshteinSize);
        }
        if ("rawAst".equals(metric)) {
            return ratio(match.rawAst.first().distance, match.record.rawAstSize);
        }
        if ("fastRewrite".equals(metric)) {
            return ratio(match.legacyCanonical.first().distance, match.record.legacyCanonicalSize);
        }
        return ratio(match.canonical.first().distance, match.record.canonicalSize);
    }

    private static String repairMetricTitle(String metric) {
        if ("levenshtein".equals(metric)) {
            return "Levenshtein repair ratio";
        }
        if ("rawAst".equals(metric)) {
            return "Raw AST repair ratio";
        }
        return "Canonical repair ratio";
    }

    private static String repairMetricXAxis(String metric) {
        if ("levenshtein".equals(metric)) {
            return "Incorrect predicate body size, in characters";
        }
        if ("rawAst".equals(metric)) {
            return "Incorrect raw AST size, in nodes";
        }
        return "Incorrect canonical-form size";
    }

    private static String repairMetricYAxis(String metric) {
        if ("levenshtein".equals(metric)) {
            return "Levenshtein distance / body size";
        }
        if ("rawAst".equals(metric)) {
            return "AST distance / AST size";
        }
        return "Canonical distance / canonical size";
    }

    private static double rewardMetricDistance(IncorrectMatch match, String metric) {
        if ("levenshtein".equals(metric)) {
            return match.levenshtein.first().distance;
        }
        if ("rawAst".equals(metric)) {
            return match.rawAst.first().distance;
        }
        if ("fastRewrite".equals(metric)) {
            return match.legacyCanonical.first().distance;
        }
        return match.canonical.first().distance;
    }

    private static double rewardMetricRatio(IncorrectMatch match, String metric) {
        if ("levenshtein".equals(metric)) {
            return ratio(match.levenshtein.first().distance, match.record.levenshteinSize);
        }
        if ("rawAst".equals(metric)) {
            return ratio(match.rawAst.first().distance, match.record.rawAstSize);
        }
        if ("fastRewrite".equals(metric)) {
            return ratio(match.legacyCanonical.first().distance, match.record.legacyCanonicalSize);
        }
        return ratio(match.canonical.first().distance, match.record.canonicalSize);
    }

    private static double rewardErrorFloor(List<IncorrectMatch> matches) {
        double floor = Double.POSITIVE_INFINITY;
        for (IncorrectMatch match : matches) {
            if (match.rewardComputed && match.rewardErrorMessage == null && match.rewardError > 0.0) {
                floor = Math.min(floor, match.rewardError);
            }
        }
        return Double.isInfinite(floor) ? 1e-6 : floor / 10.0;
    }

    private static double yValue(IncorrectMatch match, boolean logScale, double floor) {
        return logScale ? Math.max(match.rewardError, floor) : match.rewardError;
    }

    private static double ratio(double distance, int size) {
        return size <= 0 ? 0.0 : distance / (double) size;
    }

    private static String statusColor(String status) {
        if ("OVERCONSTRAINED".equals(status)) {
            return "#d62728";
        }
        if ("UNDERCONSTRAINED".equals(status)) {
            return "#1f77b4";
        }
        if ("BOTH".equals(status)) {
            return "#9467bd";
        }
        return "#555555";
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String number(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    private static String axisNumber(double value) {
        if (Math.abs(value) >= 100.0 || Math.abs(value - Math.rint(value)) < 1e-9) {
            return String.format(java.util.Locale.ROOT, "%.0f", value);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String countPercent(int value, int total) {
        double percent = total == 0 ? 0.0 : 100.0 * value / total;
        return value + " (" + String.format(java.util.Locale.ROOT, "%.1f%%", percent) + ")";
    }

    private static String percentLabel(double value) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", value * 100.0);
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
                return body.trim();
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

    private static String preludeBeforePredicate(String source, String predicateName) {
        String needle = "pred " + predicateName;
        int start = source.indexOf(needle);
        return start < 0 ? source : source.substring(0, start);
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

    private static int levenshteinDistance(String left, String right) {
        if (left.equals(right)) {
            return 0;
        }
        int prefix = 0;
        int sharedLength = Math.min(left.length(), right.length());
        while (prefix < sharedLength && left.charAt(prefix) == right.charAt(prefix)) {
            prefix++;
        }
        int leftEnd = left.length();
        int rightEnd = right.length();
        while (leftEnd > prefix
                && rightEnd > prefix
                && left.charAt(leftEnd - 1) == right.charAt(rightEnd - 1)) {
            leftEnd--;
            rightEnd--;
        }
        int leftLength = leftEnd - prefix;
        int rightLength = rightEnd - prefix;
        if (leftLength == 0 || rightLength == 0) {
            return Math.max(leftLength, rightLength);
        }

        String rows = left;
        String columns = right;
        int rowEnd = leftEnd;
        int columnEnd = rightEnd;
        if (rightLength < leftLength) {
            rows = left;
            columns = right;
        } else {
            rows = right;
            columns = left;
            rowEnd = rightEnd;
            columnEnd = leftEnd;
            int swap = leftLength;
            leftLength = rightLength;
            rightLength = swap;
        }

        int[] previous = new int[rightLength + 1];
        int[] current = new int[rightLength + 1];
        for (int j = 0; j <= rightLength; j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= leftLength; i++) {
            current[0] = i;
            char rowCharacter = rows.charAt(rowEnd - leftLength + i - 1);
            for (int j = 1; j <= rightLength; j++) {
                char columnCharacter = columns.charAt(columnEnd - rightLength + j - 1);
                int replace = previous[j - 1] + (rowCharacter == columnCharacter ? 0 : 1);
                int delete = previous[j] + 1;
                int insert = current[j - 1] + 1;
                current[j] = Math.min(replace, Math.min(delete, insert));
            }
            int[] temp = previous;
            previous = current;
            current = temp;
        }
        return previous[rightLength];
    }

    private static int rawAstTreeDistance(RawAstTree left, RawAstTree right) {
        if (left == null) {
            return right == null ? 0 : right.size;
        }
        if (right == null) {
            return left.size;
        }
        int distance = left.label.equals(right.label) ? 0 : 1;
        distance += rawAstForestDistance(left.children, right.children);
        return distance;
    }

    private static int rawAstForestDistance(List<RawAstTree> left, List<RawAstTree> right) {
        if (left.isEmpty()) {
            int distance = 0;
            for (RawAstTree tree : right) {
                distance += tree.size;
            }
            return distance;
        }
        if (right.isEmpty()) {
            int distance = 0;
            for (RawAstTree tree : left) {
                distance += tree.size;
            }
            return distance;
        }
        if (left.size() == 1 && right.size() == 1) {
            return rawAstTreeDistance(left.get(0), right.get(0));
        }

        int[] previous = new int[right.size() + 1];
        int[] current = new int[right.size() + 1];
        for (int j = 1; j <= right.size(); j++) {
            previous[j] = previous[j - 1] + right.get(j - 1).size;
        }
        for (int i = 1; i <= left.size(); i++) {
            current[0] = previous[0] + left.get(i - 1).size;
            for (int j = 1; j <= right.size(); j++) {
                int delete = previous[j] + left.get(i - 1).size;
                int insert = current[j - 1] + right.get(j - 1).size;
                int update = previous[j - 1] + rawAstTreeDistance(left.get(i - 1), right.get(j - 1));
                current[j] = Math.min(update, Math.min(delete, insert));
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.size()];
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
        if (Files.isRegularFile(root) && root.toString().endsWith(".als")) {
            files.add(root);
            return files;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".als"))
                    .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static Writer outputWriter(Path path) throws IOException {
        return new BufferedWriter(
                new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8),
                REPORT_BUFFER_SIZE);
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

    private static class Options {
        private Path inputDir = Paths.get(DEFAULT_INPUT);
        private Path outputDir = Paths.get(DEFAULT_OUTPUT);
        private int threadCount = DEFAULT_THREAD_COUNT;
        private int rewardPoolSize = DEFAULT_REWARD_POOL_SIZE;
        private int limit = -1;
        private boolean verbose;
        private boolean skipRewards;
        private boolean auditOnly;

        private static Options parse(String[] args) {
            Options options = new Options();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                if ("--threads".equals(args[i]) && i + 1 < args.length) {
                    options.threadCount = Integer.parseInt(args[++i]);
                } else if ("--reward-pool".equals(args[i]) && i + 1 < args.length) {
                    options.rewardPoolSize = Integer.parseInt(args[++i]);
                } else if ("--limit".equals(args[i]) && i + 1 < args.length) {
                    options.limit = Integer.parseInt(args[++i]);
                } else if ("--skip-rewards".equals(args[i])) {
                    options.skipRewards = true;
                } else if ("--audit-only".equals(args[i])) {
                    options.auditOnly = true;
                } else if ("--verbose".equals(args[i])) {
                    options.verbose = true;
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

    private static final class RawAstTree {
        private final String label;
        private final List<RawAstTree> children;
        private final int size;
        private final String fingerprint;

        private RawAstTree(String label, List<RawAstTree> children, int size, String fingerprint) {
            this.label = label;
            this.children = children;
            this.size = size;
            this.fingerprint = fingerprint;
        }

        private static RawAstTree from(Node node) {
            if (node == null) {
                return null;
            }
            String label = rawAstLabel(node);
            List<Node> astChildren = DatasetConventions.rawAstChildren(node);
            List<RawAstTree> children = new ArrayList<>(astChildren.size());
            int size = 1;
            StringBuilder fingerprint = new StringBuilder();
            appendFingerprintPart(fingerprint, label);
            fingerprint.append('[');
            for (Node childNode : astChildren) {
                RawAstTree child = from(childNode);
                children.add(child);
                size += child.size;
                appendFingerprintPart(fingerprint, child.fingerprint);
            }
            fingerprint.append(']');
            return new RawAstTree(label, children, size, fingerprint.toString());
        }

        private static void appendFingerprintPart(StringBuilder target, String value) {
            target.append(value.length()).append(':').append(value);
        }
    }

    private static class ModelRecord {
        private final Path file;
        private final String fileName;
        private final String relativePath;
        private final String questionSet;
        private final String statusFolder;
        private String invariantId;
        private String leftPredicate;
        private String rightPredicate;
        private String studentBody;
        private String oracleBody;
        private String prelude;
        private RawAstTree studentAst;
        private RawAstTree oracleAst;
        private Canonical.Prepared studentCanonical;
        private Canonical.Prepared oracleCanonical;
        private CanonicalAlloyPipeline.Prepared studentExact;
        private CanonicalAlloyPipeline.Prepared oracleExact;
        private int levenshteinSize;
        private int rawAstSize;
        private int canonicalSize;
        private int legacyCanonicalSize;
        private boolean astIdenticalStudentOracle;
        private String contextFingerprint;
        private String oracleAstFingerprint;
        private String error;
        private String rankingError;
        private boolean retriedAfterFailure;
        private String initialParseError;

        private ModelRecord(Path root, Path file) {
            this.file = file;
            Path relative = root.relativize(file);
            this.fileName = file.getFileName().toString();
            this.relativePath = relative.toString().replace('\\', '/');
            this.questionSet = relative.getNameCount() > 0 ? relative.getName(0).toString() : "";
            this.statusFolder = DatasetConventions.normalizeStatusFolder(
                    relative.getNameCount() > 1 ? relative.getName(1).toString() : "");
            this.invariantId = preferredPredicateBase(file);
        }

        private static ModelRecord failed(Path root, Path file, String error) {
            ModelRecord record = new ModelRecord(root, file);
            record.error = error;
            return record;
        }

        private boolean success() {
            return error == null;
        }

        private String groupKey() {
            return questionSet + "/" + invariantId;
        }
    }

    private static final class IndexedModelRecord {
        private final int index;
        private final ModelRecord record;

        private IndexedModelRecord(int index, ModelRecord record) {
            this.index = index;
            this.record = record;
        }
    }

    private static final class PreparedPair {
        private final Canonical.Prepared legacy;
        private final CanonicalAlloyPipeline.Prepared exact;

        private PreparedPair(
                Canonical.Prepared legacy,
                CanonicalAlloyPipeline.Prepared exact) {
            this.legacy = legacy;
            this.exact = exact;
        }
    }

    private static class QuestionGroup {
        private final String questionSet;
        private final String invariantId;
        private final List<ModelRecord> records = new ArrayList<>();
        private final List<ModelRecord> correct = new ArrayList<>();
        private final List<ModelRecord> incorrect = new ArrayList<>();
        private final List<ModelRecord> failures = new ArrayList<>();
        private final List<Reference> references = new ArrayList<>();
        private int truthCandidateCount;
        private int astDuplicateTruthCount;
        private String referenceError;

        private QuestionGroup(String questionSet, String invariantId) {
            this.questionSet = questionSet;
            this.invariantId = invariantId;
        }

        private void buildReferences(boolean verbose) {
            ModelRecord oracleSource = oracleSource();
            if (oracleSource == null) {
                releaseOracleRepresentations();
                return;
            }
            try {
                if (oracleSource.oracleCanonical == null || oracleSource.oracleExact == null) {
                    PreparedPair prepared = loadPreparedQuietly(oracleSource, true, verbose);
                    oracleSource.oracleCanonical = prepared.legacy;
                    oracleSource.oracleExact = prepared.exact;
                }
                Reference oracle = new Reference(
                        invariantId + "_oracle",
                        "oracle",
                        oracleSource.oracleBody,
                        oracleSource.oracleAst,
                        oracleSource.oracleCanonical,
                        oracleSource.oracleExact,
                        oracleSource);
                Set<String> fingerprints = new HashSet<>();
                truthCandidateCount++;
                addAstDistinctReference(oracle, fingerprints);
                int index = 0;
                for (ModelRecord record : correct) {
                    truthCandidateCount++;
                    Reference reference = new Reference(
                            invariantId + "_correct_" + index,
                            "correct-student",
                            record.studentBody,
                            record.studentAst,
                            record.studentCanonical,
                            record.studentExact,
                            record);
                    if (addAstDistinctReference(reference, fingerprints)) {
                        index++;
                    }
                }
            } catch (VirtualMachineError error) {
                throw error;
            } catch (RuntimeException exception) {
                references.clear();
                referenceError = exception.getClass().getSimpleName()
                        + ": " + exception.getMessage();
            } finally {
                releaseCorrectRepresentations();
                releaseOracleRepresentations();
            }
        }

        private boolean addAstDistinctReference(Reference reference, Set<String> fingerprints) {
            if (!fingerprints.add(reference.ast.fingerprint)) {
                astDuplicateTruthCount++;
                return false;
            }
            references.add(reference);
            return true;
        }

        private void releaseCorrectRepresentations() {
            for (ModelRecord record : correct) {
                record.studentAst = null;
                record.studentCanonical = null;
                record.studentExact = null;
            }
        }

        private void releaseOracleRepresentations() {
            for (ModelRecord record : records) {
                record.oracleCanonical = null;
                record.oracleExact = null;
                record.oracleAst = null;
                record.oracleBody = null;
            }
        }

        private ModelRecord oracleSource() {
            if (!correct.isEmpty()) {
                return correct.get(0);
            }
            for (ModelRecord record : records) {
                if (record.success()) {
                    return record;
                }
            }
            return null;
        }

        private List<Reference> rankingReferences() {
            return references;
        }

        private String rankingMode() {
            return correct.isEmpty() ? "oracle-only" : "oracle+correct-student";
        }

        private String prelude() {
            if (!correct.isEmpty()) {
                return correct.get(0).prelude;
            }
            ModelRecord source = oracleSource();
            return source == null ? "module unknown\n" : source.prelude;
        }
    }

    private static class Reference {
        private final String augmentedName;
        private final String kind;
        private final String body;
        private final RawAstTree ast;
        private final Canonical.Prepared canonical;
        private final CanonicalAlloyPipeline.Prepared exact;
        private final int canonicalSize;
        private final int legacyCanonicalSize;
        private final ModelRecord source;

        private Reference(
                String augmentedName,
                String kind,
                String body,
                RawAstTree ast,
                Canonical.Prepared canonical,
                CanonicalAlloyPipeline.Prepared exact,
                ModelRecord source) {
            this.augmentedName = augmentedName;
            this.kind = kind;
            this.body = body;
            this.ast = ast;
            this.canonical = canonical;
            this.exact = exact;
            this.canonicalSize = exact.repairObservationSize();
            this.legacyCanonicalSize = Canonical.canonicalFormSize(canonical);
            this.source = source;
        }
    }

    private static class CorrectPoolPair {
        private final QuestionGroup group;
        private final Reference left;
        private final Reference right;
        private final int rawAstDistance;
        private final int canonicalDistance;
        private final int legacyCanonicalDistance;

        private CorrectPoolPair(
                QuestionGroup group,
                Reference left,
                Reference right,
                int rawAstDistance,
                int canonicalDistance,
                int legacyCanonicalDistance) {
            this.group = group;
            this.left = left;
            this.right = right;
            this.rawAstDistance = rawAstDistance;
            this.canonicalDistance = canonicalDistance;
            this.legacyCanonicalDistance = legacyCanonicalDistance;
        }
    }

    private static final class CorrectPoolBatch {
        private final List<CorrectPoolPair> certificateIntegrated;
        private final List<CorrectPoolPair> fastRewrite;
        private final List<IllegalStateException> kernelViolations;

        private CorrectPoolBatch(
                List<CorrectPoolPair> certificateIntegrated,
                List<CorrectPoolPair> fastRewrite,
                List<IllegalStateException> kernelViolations) {
            this.certificateIntegrated = certificateIntegrated;
            this.fastRewrite = fastRewrite;
            this.kernelViolations = kernelViolations;
        }
    }

    private static final class CorrectPoolEquivalences {
        private final List<CorrectPoolPair> certificateIntegrated;
        private final List<CorrectPoolPair> fastRewrite;

        private CorrectPoolEquivalences(
                List<CorrectPoolPair> certificateIntegrated,
                List<CorrectPoolPair> fastRewrite) {
            this.certificateIntegrated = certificateIntegrated;
            this.fastRewrite = fastRewrite;
        }
    }

    private static final class AstIdenticalComparison {
        private final ModelRecord incorrect;
        private final Reference reference;
        private final boolean sameSupportContext;
        private final boolean sameOracleAst;
        private final boolean samePreludeText;

        private AstIdenticalComparison(
                QuestionGroup group,
                ModelRecord incorrect,
                Reference reference) {
            this.incorrect = incorrect;
            this.reference = reference;
            this.sameSupportContext = incorrect.contextFingerprint.equals(reference.source.contextFingerprint);
            this.sameOracleAst = incorrect.oracleAstFingerprint.equals(reference.source.oracleAstFingerprint);
            this.samePreludeText = incorrect.prelude.equals(reference.source.prelude);
        }
    }

    private static final class RepresentationKey {
        private final String context;
        private final String body;
        private final String astFingerprint;

        private RepresentationKey(String context, String body, String astFingerprint) {
            this.context = context;
            this.body = body;
            this.astFingerprint = astFingerprint;
        }

        private static RepresentationKey student(ModelRecord record) {
            return new RepresentationKey(
                    record.relativePath + '\0' + record.groupKey() + '\0' + record.prelude,
                    record.studentBody,
                    record.studentAst.fingerprint);
        }

        private static RepresentationKey oracle(ModelRecord record) {
            return new RepresentationKey(
                    record.relativePath + '\0' + record.groupKey() + '\0' + record.prelude,
                    record.oracleBody,
                    record.oracleAst.fingerprint);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof RepresentationKey)) {
                return false;
            }
            RepresentationKey representation = (RepresentationKey) other;
            return context.equals(representation.context)
                    && body.equals(representation.body)
                    && astFingerprint.equals(representation.astFingerprint);
        }

        @Override
        public int hashCode() {
            int result = context.hashCode();
            result = 31 * result + body.hashCode();
            return 31 * result + astFingerprint.hashCode();
        }
    }

    private static final class RankingBatch {
        private final List<ModelRecord> records;
        private final IncorrectMatch template;
        private final String error;

        private RankingBatch(
                List<ModelRecord> records,
                IncorrectMatch template,
                String error) {
            this.records = records;
            this.template = template;
            this.error = error;
        }

        private static RankingBatch success(
                List<ModelRecord> records,
                IncorrectMatch template) {
            return new RankingBatch(records, template, null);
        }

        private static RankingBatch failure(
                List<ModelRecord> records,
                String error) {
            return new RankingBatch(records, null, error);
        }
    }

    private static final class RankingWork {
        private final QuestionGroup group;
        private final List<ModelRecord> records;

        private RankingWork(
                QuestionGroup group,
                List<ModelRecord> records) {
            this.group = group;
            this.records = records;
        }
    }

    private static final class ModelPreparationFailure extends IllegalStateException {
        private ModelPreparationFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static class IncorrectMatch {
        private final ModelRecord record;
        private final Nearest levenshtein;
        private final Nearest rawAst;
        private final Nearest canonical;
        private final Nearest legacyCanonical;
        private int rewardPoolSize;
        private double candidateReward;
        private double rewardError;
        private boolean rewardComputed;
        private String rewardErrorMessage;

        private IncorrectMatch(ModelRecord record) {
            this.record = record;
            this.levenshtein = new Nearest();
            this.rawAst = new Nearest();
            this.canonical = new Nearest();
            this.legacyCanonical = new Nearest();
        }

        private IncorrectMatch(ModelRecord record, IncorrectMatch template) {
            this.record = record;
            this.levenshtein = template.levenshtein;
            this.rawAst = template.rawAst;
            this.canonical = template.canonical;
            this.legacyCanonical = template.legacyCanonical;
        }

        private void sortRankings() {
            levenshtein.sort();
            rawAst.sort();
            canonical.sort();
            legacyCanonical.sort();
        }
    }

    private static class Nearest {
        private final List<RankedReference> ranking = new ArrayList<>();

        private void add(Reference candidate, int candidateDistance) {
            ranking.add(new RankedReference(candidate, candidateDistance));
        }

        private void sort() {
            ranking.sort(Comparator
                    .comparingInt((RankedReference ranked) -> ranked.distance)
                    .thenComparing(ranked -> ranked.reference.augmentedName)
                    .thenComparing(ranked -> ranked.reference.source.relativePath));
            int currentRank = 0;
            int previousDistance = Integer.MIN_VALUE;
            for (int i = 0; i < ranking.size(); i++) {
                RankedReference ranked = ranking.get(i);
                if (i == 0 || ranked.distance != previousDistance) {
                    currentRank = i + 1;
                    previousDistance = ranked.distance;
                }
                ranked.rank = currentRank;
            }
        }

        private RankedReference first() {
            return ranking.get(0);
        }
    }

    private static class RankedReference {
        private final Reference reference;
        private final int distance;
        private int rank;

        private RankedReference(Reference reference, int distance) {
            this.reference = reference;
            this.distance = distance;
        }
    }

    private static class QuestionSetStats {
        private final String questionSet;
        private int groups;
        private int correct;
        private int incorrect;
        private int references;
        private int rankedIncorrect;
        private int oracleOnlyGroups;

        private QuestionSetStats(String questionSet) {
            this.questionSet = questionSet;
        }
    }

    private static class CorrectTruthStats {
        private final String questionSet;
        private int correctPredicates;
        private int astDistinctPredicates;
        private int uniqueFastRewriteForms;
        private int fastRewriteEquivalentPairs;
        private int uniqueCertificateForms;
        private int certificateEquivalentPairs;

        private CorrectTruthStats(String questionSet) {
            this.questionSet = questionSet;
        }

        private void add(CorrectTruthStats other) {
            correctPredicates += other.correctPredicates;
            astDistinctPredicates += other.astDistinctPredicates;
            uniqueFastRewriteForms += other.uniqueFastRewriteForms;
            fastRewriteEquivalentPairs += other.fastRewriteEquivalentPairs;
            uniqueCertificateForms += other.uniqueCertificateForms;
            certificateEquivalentPairs += other.certificateEquivalentPairs;
        }
    }

    private static class RankingModeStats {
        private final String mode;
        private int groups;
        private int incorrect;
        private Integer minRefs;
        private Integer maxRefs;

        private RankingModeStats(String mode) {
            this.mode = mode;
        }

        private String minRefsMarkdown() {
            return minRefs == null ? "n/a" : minRefs.toString();
        }

        private String maxRefsMarkdown() {
            return maxRefs == null ? "n/a" : maxRefs.toString();
        }
    }

    private static class DistanceTableStats {
        private final DistanceStats overall =
                new DistanceStats("All problem classes", "All incorrectness classes");
        private final Map<String, DistanceStats> byProblemAndStatus = new java.util.TreeMap<>();

        private void add(IncorrectMatch match) {
            overall.add(match);
            String key = match.record.questionSet + '\0' + match.record.statusFolder;
            DistanceStats group = byProblemAndStatus.computeIfAbsent(
                    key,
                    ignored -> new DistanceStats(match.record.questionSet, match.record.statusFolder));
            group.add(match);
        }
    }

    private static class DistanceStats {
        private final String problemClass;
        private final String statusFolder;
        private int count;
        private long levenshteinSum;
        private long rawAstSum;
        private long fastRewriteSum;
        private long canonicalSum;
        private double levenshteinRatioSum;
        private double rawAstRatioSum;
        private double fastRewriteRatioSum;
        private double canonicalRatioSum;

        private DistanceStats(String problemClass, String statusFolder) {
            this.problemClass = problemClass;
            this.statusFolder = statusFolder;
        }

        private void add(IncorrectMatch match) {
            count++;
            levenshteinSum += match.levenshtein.first().distance;
            rawAstSum += match.rawAst.first().distance;
            fastRewriteSum += match.legacyCanonical.first().distance;
            canonicalSum += match.canonical.first().distance;
            levenshteinRatioSum += ratio(match.levenshtein.first().distance, match.record.levenshteinSize);
            rawAstRatioSum += ratio(match.rawAst.first().distance, match.record.rawAstSize);
            fastRewriteRatioSum += ratio(
                    match.legacyCanonical.first().distance,
                    match.record.legacyCanonicalSize);
            canonicalRatioSum += ratio(match.canonical.first().distance, match.record.canonicalSize);
        }

        private double averageLevenshtein() {
            return count == 0 ? 0.0 : (double) levenshteinSum / count;
        }

        private double averageRawAst() {
            return count == 0 ? 0.0 : (double) rawAstSum / count;
        }

        private double averageCanonical() {
            return count == 0 ? 0.0 : (double) canonicalSum / count;
        }

        private double averageFastRewrite() {
            return count == 0 ? 0.0 : (double) fastRewriteSum / count;
        }

        private double averageLevenshteinRatio() {
            return count == 0 ? 0.0 : levenshteinRatioSum / count;
        }

        private double averageRawAstRatio() {
            return count == 0 ? 0.0 : rawAstRatioSum / count;
        }

        private double averageCanonicalRatio() {
            return count == 0 ? 0.0 : canonicalRatioSum / count;
        }

        private double averageFastRewriteRatio() {
            return count == 0 ? 0.0 : fastRewriteRatioSum / count;
        }
    }

    private static class RepairRadiusStats {
        private final String label;
        private int count;
        private final int[] rawAstWithin = new int[REPAIR_RADII.length];
        private final int[] fastRewriteWithin = new int[REPAIR_RADII.length];
        private final int[] canonicalWithin = new int[REPAIR_RADII.length];
        private final int[] levenshteinRelativeWithin = new int[RELATIVE_REPAIR_RADII.length];
        private final int[] rawAstRelativeWithin = new int[RELATIVE_REPAIR_RADII.length];
        private final int[] fastRewriteRelativeWithin = new int[RELATIVE_REPAIR_RADII.length];
        private final int[] canonicalRelativeWithin = new int[RELATIVE_REPAIR_RADII.length];

        private RepairRadiusStats(String label) {
            this.label = label;
        }

        private void add(IncorrectMatch match) {
            count++;
            int levenshteinDistance = match.levenshtein.first().distance;
            int rawAstDistance = match.rawAst.first().distance;
            int fastRewriteDistance = match.legacyCanonical.first().distance;
            int canonicalDistance = match.canonical.first().distance;
            double levenshteinRatio = ratio(levenshteinDistance, match.record.levenshteinSize);
            double rawAstRatio = ratio(rawAstDistance, match.record.rawAstSize);
            double fastRewriteRatio = ratio(fastRewriteDistance, match.record.legacyCanonicalSize);
            double canonicalRatio = ratio(canonicalDistance, match.record.canonicalSize);
            for (int i = 0; i < REPAIR_RADII.length; i++) {
                if (rawAstDistance <= REPAIR_RADII[i]) {
                    rawAstWithin[i]++;
                }
                if (fastRewriteDistance <= REPAIR_RADII[i]) {
                    fastRewriteWithin[i]++;
                }
                if (canonicalDistance <= REPAIR_RADII[i]) {
                    canonicalWithin[i]++;
                }
            }
            for (int i = 0; i < RELATIVE_REPAIR_RADII.length; i++) {
                if (levenshteinRatio <= RELATIVE_REPAIR_RADII[i]) {
                    levenshteinRelativeWithin[i]++;
                }
                if (rawAstRatio <= RELATIVE_REPAIR_RADII[i]) {
                    rawAstRelativeWithin[i]++;
                }
                if (fastRewriteRatio <= RELATIVE_REPAIR_RADII[i]) {
                    fastRewriteRelativeWithin[i]++;
                }
                if (canonicalRatio <= RELATIVE_REPAIR_RADII[i]) {
                    canonicalRelativeWithin[i]++;
                }
            }
        }
    }

    private static class RepairRatioRegressionStats {
        private int count;
        private double xSum;
        private double ySum;
        private double xxSum;
        private double yySum;
        private double xySum;

        private void add(double size, double ratio) {
            count++;
            xSum += size;
            ySum += ratio;
            xxSum += size * size;
            yySum += ratio * ratio;
            xySum += size * ratio;
        }

        private double slope() {
            if (count < 2) {
                return 0.0;
            }
            double denominator = count * xxSum - xSum * xSum;
            if (denominator == 0.0) {
                return 0.0;
            }
            return (count * xySum - xSum * ySum) / denominator;
        }

        private double intercept() {
            if (count == 0) {
                return 0.0;
            }
            return (ySum / count) - slope() * (xSum / count);
        }

        private double correlation() {
            if (count < 2) {
                return 0.0;
            }
            double numerator = count * xySum - xSum * ySum;
            double xDenominator = count * xxSum - xSum * xSum;
            double yDenominator = count * yySum - ySum * ySum;
            if (xDenominator <= 0.0 || yDenominator <= 0.0) {
                return 0.0;
            }
            return numerator / Math.sqrt(xDenominator * yDenominator);
        }
    }

    private static class RewardDistanceStats {
        private int count;
        private double xSum;
        private double ySum;
        private double xxSum;
        private double yySum;
        private double xySum;
        private double logYSum;
        private double logYYSum;
        private double xLogYSum;

        private void add(double distance, double rewardError, double floor) {
            double logRewardError = Math.log10(Math.max(rewardError, floor));
            count++;
            xSum += distance;
            ySum += rewardError;
            xxSum += distance * distance;
            yySum += rewardError * rewardError;
            xySum += distance * rewardError;
            logYSum += logRewardError;
            logYYSum += logRewardError * logRewardError;
            xLogYSum += distance * logRewardError;
        }

        private double rawCorrelation() {
            return correlation(count, xSum, ySum, xxSum, yySum, xySum);
        }

        private double logCorrelation() {
            return correlation(count, xSum, logYSum, xxSum, logYYSum, xLogYSum);
        }

        private static double correlation(
                int count,
                double xSum,
                double ySum,
                double xxSum,
                double yySum,
                double xySum) {
            if (count < 2) {
                return 0.0;
            }
            double numerator = count * xySum - xSum * ySum;
            double xDenominator = count * xxSum - xSum * xSum;
            double yDenominator = count * yySum - ySum * ySum;
            if (xDenominator <= 0.0 || yDenominator <= 0.0) {
                return 0.0;
            }
            return numerator / Math.sqrt(xDenominator * yDenominator);
        }
    }

    private static class Summary {
        private int groups;
        private int parsedModels;
        private int parseFailures;
        private int correctModels;
        private int incorrectModels;
        private int oracleReferences;
        private int uniqueCorrectStudentReferences;

        private int incorrectModelsWithoutCorrectReference;
        private int incorrectModelsWithoutAstDistinctReference;
        private int rewardSuccesses;
        private int rewardFailures;
        private int referencePoolFailures;
        private int rankingFailures;
        private double candidateRewardSum;
        private double rewardErrorSum;

        private Summary(
                Map<String, QuestionGroup> groups,
                List<ModelRecord> records,
                List<IncorrectMatch> matches,
                List<ModelRecord> unmatched,
                List<ModelRecord> withoutAstDistinctReference) {
            this.groups = groups.size();
            this.incorrectModelsWithoutCorrectReference = unmatched.size();
            this.incorrectModelsWithoutAstDistinctReference = withoutAstDistinctReference.size();
            for (IncorrectMatch match : matches) {
                if (!match.rewardComputed) {
                    continue;
                }
                if (match.rewardErrorMessage == null) {
                    rewardSuccesses++;
                    candidateRewardSum += match.candidateReward;
                    rewardErrorSum += match.rewardError;
                } else {
                    rewardFailures++;
                }
            }
            for (ModelRecord record : records) {
                if (record.success()) {
                    parsedModels++;
                    if ("CORRECT".equals(record.statusFolder)) {
                        correctModels++;
                    } else {
                        incorrectModels++;
                    }
                } else {
                    parseFailures++;
                }
                if (record.rankingError != null) {
                    rankingFailures++;
                }
            }
            for (QuestionGroup group : groups.values()) {
                if (group.referenceError != null) {
                    referencePoolFailures++;
                }
                for (Reference reference : group.references) {
                    if ("oracle".equals(reference.kind)) {
                        oracleReferences++;
                    } else {
                        uniqueCorrectStudentReferences++;
                    }
                }
            }
        }

        private double averageCandidateReward() {
            return rewardSuccesses == 0 ? 0.0 : candidateRewardSum / rewardSuccesses;
        }

        private double averageRewardError() {
            return rewardSuccesses == 0 ? 0.0 : rewardErrorSum / rewardSuccesses;
        }
    }
}
