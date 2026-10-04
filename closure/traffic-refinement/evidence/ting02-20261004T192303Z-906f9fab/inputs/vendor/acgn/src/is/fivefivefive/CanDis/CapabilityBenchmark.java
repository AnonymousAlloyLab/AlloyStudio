package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.parser.CompModule;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

/** Generates and reports a deterministic equivalence-by-construction benchmark. */
public final class CapabilityBenchmark {
    public static final long DEFAULT_SEED = 55520260811L;
    public static final int DEFAULT_TARGET = 500;
    private static final List<String> ARMS = List.of(
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
    private static final String BENCHMARK_DECLARATIONS = "\n\n"
            + "sig CapBenchA { capBenchR: set CapBenchA }\n"
            + "sig CapBenchB { capBenchS: set CapBenchB }\n\n";

    private CapabilityBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");
        Options options = Options.parse(args);
        if (options.mode == Mode.GENERATE) {
            generate(options);
        } else {
            report(options);
        }
    }

    private static void generate(Options options) throws Exception {
        Path models = options.output.resolve("models");
        deleteTree(models);
        Files.createDirectories(models);

        List<SeedPredicate> seeds = loadSeeds(options.dataset, options.seed);
        if (seeds.isEmpty()) {
            throw new IllegalStateException("No usable zero-parameter CORRECT Alloy predicates found");
        }
        Random random = new Random(options.seed);
        Set<String> uniquePairs = new HashSet<>();
        List<GeneratedPair> generated = new ArrayList<>();
        List<Skip> skips = new ArrayList<>();
        int sequence = 0;
        long requestedPairs = (long) Family.values().length * options.target;
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "CapabilityBenchmark/generate",
                requestedPairs,
                "accepted pairs",
                "rejected attempts do not advance completion");

        for (Family family : Family.values()) {
            int accepted = 0;
            int attempt = 0;
            int maximumAttempts = options.target * 30;
            while (accepted < options.target && attempt < maximumAttempts) {
                SeedPredicate seed = seeds.get(random.nextInt(seeds.size()));
                FormulaPair formulas = family.formulas(attempt, seed.predicateName);
                String uniqueKey = formulas.left + "\u0000" + formulas.right;
                if (!uniquePairs.add(uniqueKey)) {
                    skips.add(new Skip(family.id, seed.relativePath, "duplicate-formula-pair"));
                    attempt++;
                    progress.update(generated.size());
                    continue;
                }

                String predicate = String.format(Locale.ROOT, "cap%06d", sequence++);
                Path relative = Paths.get(family.id, "CORRECT", "generated_" + predicate + ".als");
                Path file = models.resolve(relative);
                Files.createDirectories(file.getParent());
                String source = generatedSource(seed, predicate, formulas);
                Files.writeString(file, source, StandardCharsets.UTF_8);

                Validation validation = validateGenerated(file, predicate);
                if (!validation.accepted) {
                    Files.deleteIfExists(file);
                    uniquePairs.remove(uniqueKey);
                    skips.add(new Skip(family.id, seed.relativePath, validation.reason));
                    attempt++;
                    progress.update(generated.size());
                    continue;
                }
                generated.add(new GeneratedPair(
                        relative.toString().replace('\\', '/'),
                        family,
                        formulas.subtype,
                        formulas.transformations,
                        formulas.soundnessCondition,
                        seed.relativePath,
                        seed.predicateName,
                        sha256(seed.source),
                        formulas.left,
                        formulas.right,
                        validation.leftAstSize,
                        validation.rightAstSize));
                accepted++;
                attempt++;
                progress.update(generated.size());
            }
            if (accepted < options.target) {
                skips.add(new Skip(family.id, "", "target-not-reached:" + accepted + "/" + options.target));
            }
        }
        progress.finish(generated.size());

        generated.sort(Comparator.comparing(pair -> pair.relativePath));
        writeMetadata(options, seeds, generated, skips);
        System.out.printf(Locale.ROOT,
                "Generated %,d valid nontrivial pairs from %,d real Alloy4Fun seeds (seed=%d).%n",
                generated.size(), seeds.size(), options.seed);
    }

    private static List<SeedPredicate> loadSeeds(Path dataset, long rngSeed) throws IOException {
        List<Path> candidates;
        try (Stream<Path> paths = Files.walk(dataset)) {
            candidates = paths.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".als"))
                    .filter(CapabilityBenchmark::isCorrectPath)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        candidates = new ArrayList<>(candidates);
        Collections.shuffle(candidates, new Random(rngSeed));
        List<SeedPredicate> seeds = new ArrayList<>();
        for (Path file : candidates) {
            if (seeds.size() >= 256) {
                break;
            }
            try {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (containsBenchmarkIdentifier(source)) {
                    continue;
                }
                CompModule module = AlloyUtil.compileAlloyModule(file.toString());
                if (module == null) {
                    continue;
                }
                ModelUnit model = new ModelUnit(null, module);
                Map<String, Predicate> predicates = predicateMap(model);
                String preferred = preferredPredicateBase(file);
                String[] names = DatasetConventions.findPredicatePairNames(preferred, predicates);
                if (names == null) {
                    continue;
                }
                Predicate predicate = predicates.get(names[0]);
                if (predicate == null || predicate.getBody() == null
                        || (predicate.getParamList() != null && !predicate.getParamList().isEmpty())) {
                    continue;
                }
                seeds.add(new SeedPredicate(
                        dataset.relativize(file).toString().replace('\\', '/'),
                        names[0], source));
            } catch (VirtualMachineError error) {
                throw error;
            } catch (Throwable ignored) {
                // Unusable seeds are not generated cases; aggregate availability is reported.
            }
        }
        return seeds;
    }

    private static boolean isCorrectPath(Path path) {
        for (Path part : path) {
            if ("correct".equalsIgnoreCase(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBenchmarkIdentifier(String source) {
        return source.matches("(?s).*\\b(CapBenchA|CapBenchB|capBenchR|capBenchS)\\b.*");
    }

    private static String generatedSource(SeedPredicate seed, String predicate, FormulaPair pair) {
        String assertion = "CapBenchEquivalent_" + predicate;
        return seed.source + (seed.source.endsWith("\n") ? "" : "\n")
                + BENCHMARK_DECLARATIONS
                + "pred " + predicate + " { " + pair.left + " }\n"
                + "pred " + predicate + "c { " + pair.right + " }\n"
                + "assert " + assertion + " { " + predicate + " iff " + predicate + "c }\n"
                + "check " + assertion + " for 4\n";
    }

    private static Validation validateGenerated(Path file, String predicateName) {
        try {
            CompModule module = AlloyUtil.compileAlloyModule(file.toString());
            if (module == null) {
                return Validation.rejected("parse-or-type-error");
            }
            ModelUnit model = new ModelUnit(null, module);
            Map<String, Predicate> predicates = predicateMap(model);
            Predicate left = predicates.get(predicateName);
            Predicate right = predicates.get(predicateName + "c");
            if (left == null || right == null || left.getBody() == null || right.getBody() == null) {
                return Validation.rejected("generated-predicate-not-found");
            }
            if (DatasetConventions.sameRawAst(left.getBody(), right.getBody())) {
                return Validation.rejected("parser-ast-identical");
            }
            return Validation.accepted(astSize(left.getBody()), astSize(right.getBody()));
        } catch (VirtualMachineError error) {
            throw error;
        } catch (Throwable throwable) {
            return Validation.rejected("parse-or-type-error:" + throwable.getClass().getSimpleName());
        }
    }

    private static Map<String, Predicate> predicateMap(ModelUnit model) {
        Map<String, Predicate> predicates = new HashMap<>();
        for (Predicate predicate : model.getPredDeclList()) {
            predicates.put(predicate.getName(), predicate);
        }
        return predicates;
    }

    private static int astSize(parser.ast.nodes.Node node) {
        if (node == null) {
            return 0;
        }
        int size = 1;
        if (node.getChildren() != null) {
            for (parser.ast.nodes.Node child : node.getChildren()) {
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
        return underscore < 0 ? null : name.substring(underscore + 1);
    }

    private static void writeMetadata(
            Options options,
            List<SeedPredicate> seeds,
            List<GeneratedPair> generated,
            List<Skip> skips) throws IOException {
        Files.createDirectories(options.output);
        try (Writer writer = Files.newBufferedWriter(
                options.output.resolve("metadata.csv"), StandardCharsets.UTF_8)) {
            writer.write("relativePath,family,subtype,expectedFirstCapable,transformations,soundnessCondition,"
                    + "groundTruth,seedPath,seedPredicate,seedSourceSha256,leftFormula,rightFormula,leftAstSize,rightAstSize\n");
            for (GeneratedPair pair : generated) {
                csv(writer, pair.relativePath);
                csv(writer, pair.family.id);
                csv(writer, pair.subtype);
                csv(writer, pair.family.expectedFirst);
                csv(writer, String.join(";", pair.transformations));
                csv(writer, pair.soundnessCondition);
                csv(writer, "equivalence-by-construction");
                csv(writer, pair.seedPath);
                csv(writer, pair.seedPredicate);
                csv(writer, pair.seedSourceSha256);
                csv(writer, pair.leftFormula);
                csv(writer, pair.rightFormula);
                writer.write(Integer.toString(pair.leftAstSize));
                writer.write(',');
                writer.write(Integer.toString(pair.rightAstSize));
                writer.write('\n');
            }
        }
        try (Writer writer = Files.newBufferedWriter(
                options.output.resolve("skips.csv"), StandardCharsets.UTF_8)) {
            writer.write("family,seedPath,reason\n");
            for (Skip skip : skips) {
                csv(writer, skip.family);
                csv(writer, skip.seedPath);
                csvLast(writer, skip.reason);
                writer.write('\n');
            }
        }

        JSONObject root = new JSONObject();
        root.put("schemaVersion", "candis-capability-benchmark-v1");
        root.put("generatedAt", Instant.now().toString());
        root.put("rngSeed", options.seed);
        root.put("targetPerFamily", options.target);
        root.put("datasetRoot", options.dataset.toAbsolutePath().normalize().toString());
        root.put("realSeedPredicates", seeds.size());
        root.put("generatedPairs", generated.size());
        root.put("groundTruth", "equivalence-by-construction");
        JSONArray pairs = new JSONArray();
        for (GeneratedPair pair : generated) {
            pairs.put(pair.toJson());
        }
        root.put("pairs", pairs);
        JSONArray skipped = new JSONArray();
        for (Skip skip : skips) {
            skipped.put(skip.toJson());
        }
        root.put("skips", skipped);
        Files.writeString(options.output.resolve("metadata.json"), root.toString(2) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void report(Options options) throws IOException {
        List<Map<String, String>> metadataRows = readCsv(options.output.resolve("metadata.csv"));
        Map<String, PairRecord> pairs = new TreeMap<>();
        for (Map<String, String> row : metadataRows) {
            PairRecord pair = new PairRecord(row);
            pairs.put(pair.relativePath, pair);
        }
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "CapabilityBenchmark/report-arms",
                ARMS.size(),
                "arms");
        int completedArms = 0;
        for (String arm : ARMS) {
            Path pairCsv = options.output.resolve("arms").resolve(arm).resolve("pairs.csv");
            for (Map<String, String> row : readCsv(pairCsv)) {
                PairRecord pair = pairs.get(row.get("relativePath"));
                if (pair != null) {
                    pair.outcomes.put(arm, Outcome.from(row));
                }
            }
            progress.update(++completedArms);
        }
        progress.finish(completedArms);

        Map<String, FamilyReport> reports = new LinkedHashMap<>();
        for (Family family : Family.values()) {
            List<PairRecord> familyPairs = pairs.values().stream()
                    .filter(pair -> family.id.equals(pair.family)).toList();
            reports.put(family.id, new FamilyReport(family, familyPairs));
        }
        Map<String, NaturalMetrics> natural = loadNaturalMetrics(options.natural);
        writeCapabilityCsv(options.output.resolve("results.csv"), reports);
        writePairResults(options.output.resolve("pair_results.csv"), pairs.values());
        writeUnexpectedFailures(options.output.resolve("unexpected_failures.csv"), pairs.values());
        writeTransitionCsv(options.output.resolve("transitions.csv"), reports);
        writeReportJson(options, reports, pairs.values(), natural);
        writeReportMarkdown(options, reports, pairs.values(), natural);
        System.out.println("Wrote " + options.output.resolve("REPORT.md"));
        requireTypedCapabilityClosure(options, pairs.values());
    }

    private static void requireTypedCapabilityClosure(
            Options options,
            Iterable<PairRecord> pairs) {
        int generated = 0;
        int closed = 0;
        int errors = 0;
        for (PairRecord pair : pairs) {
            generated++;
            Outcome outcome = pair.outcomes.get("typed-slotted-port-egraph");
            if (outcome != null && outcome.success && outcome.zero) {
                closed++;
            } else {
                errors++;
            }
        }
        int expected = Math.multiplyExact(Family.values().length, options.target);
        if (generated != expected || closed != expected || errors != 0) {
            throw new IllegalStateException(
                    "Certificate-integrated capability closure failed: "
                            + closed + "/" + expected
                            + " zero-distance pairs (generated=" + generated
                            + ", failures=" + errors + ")");
        }
    }

    private static void writeCapabilityCsv(Path path, Map<String, FamilyReport> reports) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("family,arm,generated,evaluated,errors,zero,recoveryRate,falseNegatives,"
                    + "averageDistance,p50Distance,p95Distance,engineCpuSeconds,aggregateEngineWallSeconds,"
                    + "averageRepresentationUnits,averageEstimatedBytes,unexpectedFailures\n");
            for (FamilyReport report : reports.values()) {
                for (String arm : ARMS) {
                    ArmMetrics metrics = report.metrics.get(arm);
                    csv(writer, report.family.id);
                    csv(writer, arm);
                    writer.write(metrics.csvValues());
                    writer.write('\n');
                }
            }
            List<PairRecord> all = reports.values().stream().flatMap(report -> report.pairs.stream()).toList();
            List<PairRecord> composed = all.stream().filter(pair -> pair.family.contains("_")).toList();
            writeAggregateRows(writer, "__all__", all);
            writeAggregateRows(writer, "__composed__", composed);
        }
    }

    private static void writeAggregateRows(Writer writer, String label, List<PairRecord> pairs) throws IOException {
        for (String arm : ARMS) {
            ArmMetrics metrics = new ArmMetrics(pairs, arm, false);
            csv(writer, label);
            csv(writer, arm);
            writer.write(metrics.csvValues());
            writer.write('\n');
        }
    }

    private static void writePairResults(Path path, Iterable<PairRecord> pairs) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,family,subtype,expectedFirstCapable,seedPath,seedPredicate");
            for (String arm : ARMS) {
                writer.write(',');
                writer.write(arm + "Distance");
                writer.write(',');
                writer.write(arm + "Zero");
            }
            writer.write('\n');
            for (PairRecord pair : pairs) {
                csv(writer, pair.relativePath);
                csv(writer, pair.family);
                csv(writer, pair.subtype);
                csv(writer, pair.expectedFirst);
                csv(writer, pair.seedPath);
                csv(writer, pair.seedPredicate);
                for (String arm : ARMS) {
                    Outcome outcome = pair.outcomes.get(arm);
                    writer.write(outcome == null ? "" : Integer.toString(outcome.distance));
                    writer.write(',');
                    writer.write(outcome == null ? "" : Boolean.toString(outcome.zero));
                    if (!arm.equals(ARMS.get(ARMS.size() - 1))) {
                        writer.write(',');
                    }
                }
                writer.write('\n');
            }
        }
    }

    private static void writeUnexpectedFailures(Path path, Iterable<PairRecord> pairs) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,family,subtype,expectedArm,distance,success,error\n");
            for (PairRecord pair : pairs) {
                Outcome outcome = pair.outcomes.get(pair.expectedFirst);
                if (outcome != null && outcome.zero) {
                    continue;
                }
                csv(writer, pair.relativePath);
                csv(writer, pair.family);
                csv(writer, pair.subtype);
                csv(writer, pair.expectedFirst);
                writer.write(outcome == null ? "" : Integer.toString(outcome.distance));
                writer.write(',');
                writer.write(outcome != null && outcome.success ? "true" : "false");
                writer.write(',');
                csvLast(writer, outcome == null ? "missing-result" : outcome.error);
                writer.write('\n');
            }
        }
    }

    private static void writeTransitionCsv(Path path, Map<String, FamilyReport> reports) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("family,fromArm,toArm,retainedZero,newlyZero,noLongerZero\n");
            for (FamilyReport report : reports.values()) {
                for (String[] edge : TRANSITIONS) {
                    Transition transition = report.transition(edge[0], edge[1]);
                    csv(writer, report.family.id);
                    csv(writer, edge[0]);
                    csv(writer, edge[1]);
                    writer.write(transition.retained + "," + transition.added + "," + transition.lost + "\n");
                }
            }
        }
    }

    private static void writeReportJson(
            Options options,
            Map<String, FamilyReport> reports,
            Iterable<PairRecord> pairs,
            Map<String, NaturalMetrics> natural) throws IOException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", "candis-capability-results-v1");
        root.put("generatedAt", Instant.now().toString());
        root.put("rngSeed", options.seed);
        root.put("groundTruth", "equivalence-by-construction");
        root.put("arms", new JSONArray(ARMS));
        JSONArray familyJson = new JSONArray();
        for (FamilyReport report : reports.values()) {
            familyJson.put(report.toJson());
        }
        root.put("families", familyJson);
        int pairCount = 0;
        for (PairRecord ignored : pairs) {
            pairCount++;
        }
        root.put("pairCount", pairCount);
        JSONObject naturalJson = new JSONObject();
        for (Map.Entry<String, NaturalMetrics> entry : natural.entrySet()) {
            naturalJson.put(entry.getKey(), entry.getValue().toJson());
        }
        root.put("naturalCorpus", naturalJson);
        Path soundness = options.output.resolve("soundness.json");
        if (Files.isRegularFile(soundness)) {
            root.put("boundedSoundness", new JSONObject(Files.readString(soundness, StandardCharsets.UTF_8)));
        }
        Files.writeString(options.output.resolve("results.json"), root.toString(2) + "\n",
                StandardCharsets.UTF_8);
    }

    private static void writeReportMarkdown(
            Options options,
            Map<String, FamilyReport> reports,
            Iterable<PairRecord> pairs,
            Map<String, NaturalMetrics> natural) throws IOException {
        int pairCount = 0;
        for (PairRecord ignored : pairs) {
            pairCount++;
        }
        StringBuilder markdown = new StringBuilder("# Targeted Equivalence Capability Benchmark\n\n");
        markdown.append("- Generated at: `").append(Instant.now()).append("`\n");
        markdown.append("- RNG seed: `").append(options.seed).append("`\n");
        markdown.append("- Valid, parser-AST-different pairs: ").append(pairCount).append("\n");
        markdown.append("- Ground truth: equivalence by construction using only the implemented rule set\n");
        markdown.append("- Seed source: zero-parameter predicates from Alloy4Fun `CORRECT` folders\n\n");
        markdown.append("Dataset labels and generated ground truth are distinct: `CORRECT` is used only to select "
                + "real seed predicates; benchmark equivalence follows from each recorded transformation and side condition.\n\n");

        markdown.append("## Recovery By Family\n\n");
        markdown.append("| Transformation family");
        for (String arm : ARMS) {
            markdown.append(" | ").append(arm);
        }
        markdown.append(" |\n| ---");
        for (int i = 0; i < ARMS.size(); i++) {
            markdown.append(" | ---:");
        }
        markdown.append(" |\n");
        for (FamilyReport report : reports.values()) {
            markdown.append("| ").append(report.family.displayName);
            for (String arm : ARMS) {
                ArmMetrics metrics = report.metrics.get(arm);
                markdown.append(" | ").append(metrics.zero).append("/").append(metrics.generated)
                        .append(" (").append(percent(metrics.recoveryRate())).append(")");
            }
            markdown.append(" |\n");
        }
        appendAggregateRecovery(markdown, "All composed families", reports.values().stream()
                .flatMap(report -> report.pairs.stream()).filter(pair -> pair.family.contains("_")).toList());
        appendAggregateRecovery(markdown, "All families", reports.values().stream()
                .flatMap(report -> report.pairs.stream()).toList());

        markdown.append("\n## Expected Capability Boundary\n\n");
        markdown.append("Expectations are annotations only and do not affect generation or evaluation. "
                + "Observed first means the earliest listed arm with 100% recovery.\n\n");
        markdown.append("| Transformation | Expected first capable | Observed first capable | Match | Failures at expected arm |\n");
        markdown.append("| --- | --- | --- | --- | ---: |\n");
        for (FamilyReport report : reports.values()) {
            String observed = report.observedFirst();
            ArmMetrics expected = report.metrics.get(report.family.expectedFirst);
            markdown.append("| ").append(report.family.displayName).append(" | ")
                    .append(report.family.expectedFirst).append(" | ")
                    .append(observed.isEmpty() ? "none" : observed).append(" | ")
                    .append(report.family.expectedFirst.equals(observed) ? "yes" : "no")
                    .append(" | ").append(expected.falseNegatives()).append(" |\n");
        }

        if (!natural.isEmpty()) {
            markdown.append("\n## Natural-Corpus Context\n\n")
                    .append("The generated benchmark isolates specific capabilities; the natural corpus reflects their observed mixture and prevalence.\n\n")
                    .append("| Arm | Successful pairs | CORRECT zero coverage | Incorrect zeroes | Avg distance |\n")
                    .append("| --- | ---: | ---: | ---: | ---: |\n");
            for (String arm : ARMS) {
                NaturalMetrics metrics = natural.get(arm);
                if (metrics == null) continue;
                markdown.append("| ").append(arm).append(" | ").append(metrics.successful)
                        .append(" | ").append(metrics.correctZero).append(" / ").append(metrics.correct)
                        .append(" (").append(percent(metrics.correctCoverage())).append(") | ")
                        .append(metrics.incorrectZero).append(" | ").append(number(metrics.averageDistance()))
                        .append(" |\n");
            }
        }
        appendSoundnessSummary(markdown, options.output.resolve("soundness.json"));

        markdown.append("\n## Distance And Cost\n\n");
        markdown.append("Wall time below is aggregate per-pair engine latency; CPU is summed worker-thread CPU. "
                + "Process RSS belongs to the separate full-corpus ablation.\n\n");
        markdown.append("| Family | Arm | Avg / P50 / P95 distance | Engine CPU s | Aggregate engine wall s | Avg representation units | Avg estimated bytes |\n");
        markdown.append("| --- | --- | --- | ---: | ---: | ---: | ---: |\n");
        for (FamilyReport report : reports.values()) {
            for (String arm : ARMS) {
                ArmMetrics metrics = report.metrics.get(arm);
                markdown.append("| ").append(report.family.id).append(" | ").append(arm).append(" | ")
                        .append(number(metrics.averageDistance())).append(" / ")
                        .append(metrics.percentile(0.50)).append(" / ").append(metrics.percentile(0.95))
                        .append(" | ").append(number(metrics.engineCpuNanos / 1e9))
                        .append(" | ").append(number(metrics.engineNanos / 1e9))
                        .append(" | ").append(number(metrics.averageRepresentationUnits()))
                        .append(" | ").append(number(metrics.averageEstimatedBytes())).append(" |\n");
            }
        }

        markdown.append("\n## Pair-Level Transitions\n\n");
        markdown.append("| Family | Transition | Retained | Newly zero | No longer zero |\n");
        markdown.append("| --- | --- | ---: | ---: | ---: |\n");
        for (FamilyReport report : reports.values()) {
            for (String[] edge : TRANSITIONS) {
                Transition transition = report.transition(edge[0], edge[1]);
                markdown.append("| ").append(report.family.id).append(" | ")
                        .append(edge[0]).append(" -> ").append(edge[1]).append(" | ")
                        .append(transition.retained).append(" | ").append(transition.added)
                        .append(" | ").append(transition.lost).append(" |\n");
            }
        }

        List<Map<String, String>> skips = readCsv(options.output.resolve("skips.csv"));
        Map<String, Integer> skipCounts = new TreeMap<>();
        for (Map<String, String> skip : skips) {
            String key = skip.get("family") + ": " + skip.get("reason");
            skipCounts.put(key, skipCounts.getOrDefault(key, 0) + 1);
        }
        markdown.append("\n## Generation Skips\n\n");
        if (skipCounts.isEmpty()) {
            markdown.append("No generation attempts were skipped.\n");
        } else {
            markdown.append("| Family and reason | Attempts |\n| --- | ---: |\n");
            for (Map.Entry<String, Integer> entry : skipCounts.entrySet()) {
                markdown.append("| ").append(entry.getKey()).append(" | ").append(entry.getValue()).append(" |\n");
            }
        }

        markdown.append("\n## Reproduce\n\n```bash\n")
                .append("./scripts/run_capability_benchmark.sh --dataset classified-data --output capability_benchmark")
                .append(" --target ").append(options.target).append(" --seed ").append(options.seed)
                .append("\n```\n");
        Files.writeString(options.output.resolve("REPORT.md"), markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void appendAggregateRecovery(StringBuilder markdown, String label, List<PairRecord> pairs) {
        markdown.append("| ").append(label);
        for (String arm : ARMS) {
            ArmMetrics metrics = new ArmMetrics(pairs, arm, false);
            markdown.append(" | ").append(metrics.zero).append("/").append(metrics.generated)
                    .append(" (").append(percent(metrics.recoveryRate())).append(")");
        }
        markdown.append(" |\n");
    }

    static void appendSoundnessSummary(StringBuilder markdown, Path soundnessPath) throws IOException {
        if (!Files.isRegularFile(soundnessPath)) return;
        JSONObject soundness = new JSONObject(Files.readString(soundnessPath, StandardCharsets.UTF_8));
        JSONArray checks = soundness.getJSONArray("checks");
        int inconclusive = 0;
        int solverCounterexamples = 0;
        int conclusiveFailures = 0;
        int temporalChecks = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            boolean uncertain = check.getBoolean("inconclusive");
            boolean counterexample = check.getBoolean("solverReportedCounterexample");
            boolean error = !check.getString("error").isEmpty();
            if (uncertain) inconclusive++;
            if (uncertain && counterexample) solverCounterexamples++;
            if (!uncertain && check.optBoolean("temporal", false)) temporalChecks++;
            if (!uncertain && (counterexample || error)) conclusiveFailures++;
        }
        markdown.append("\n## Bounded Soundness Sanity Check\n\n")
                .append("- Sampled family/subtype cases: ").append(checks.length()).append("\n")
                .append("- Conclusive failures: ").append(conclusiveFailures).append("\n")
                .append("- Completed bounded temporal checks: ").append(temporalChecks).append("\n")
                .append("- Inconclusive checks: ").append(inconclusive).append("\n")
                .append("- Raw solver-reported counterexamples among inconclusive checks: ")
                .append(solverCounterexamples).append("\n\n")
                .append("Only completed checks contribute bounded evidence, not semantic proofs. "
                        + "Inconclusive checks remain unresolved; backend notes and per-command bounds are retained "
                        + "in `SOUNDNESS.md` and `soundness.csv`.\n");
    }

    private static Map<String, NaturalMetrics> loadNaturalMetrics(Path root) throws IOException {
        Map<String, NaturalMetrics> metrics = new LinkedHashMap<>();
        for (String arm : ARMS) {
            Path pairs = root.resolve(arm).resolve("pairs.csv");
            if (!Files.isRegularFile(pairs)) continue;
            NaturalMetrics armMetrics = new NaturalMetrics();
            for (Map<String, String> row : readCsv(pairs)) armMetrics.add(row);
            metrics.put(arm, armMetrics);
        }
        return metrics;
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> header = parseCsvLine(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>(Math.max(0, lines.size() - 1));
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) {
                continue;
            }
            List<String> fields = parseCsvLine(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) {
                row.put(header.get(j), j < fields.size() ? fields.get(j) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (quoted) {
                if (character == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (character == '"') {
                    quoted = false;
                } else {
                    field.append(character);
                }
            } else if (character == '"') {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(character);
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static void csv(Writer writer, String value) throws IOException {
        csvValue(writer, value);
        writer.write(',');
    }

    private static void csvLast(Writer writer, String value) throws IOException {
        csvValue(writer, value);
    }

    private static void csvValue(Writer writer, String value) throws IOException {
        String safe = value == null ? "" : value;
        writer.write('"');
        writer.write(safe.replace("\"", "\"\""));
        writer.write('"');
    }

    private static String controlFormula(int value) {
        String[] atoms = {
                "some CapBenchA",
                "some CapBenchB",
                "no CapBenchA",
                "no CapBenchB",
                "some capBenchR",
                "some capBenchS",
                "capBenchR in (CapBenchA -> CapBenchA)",
                "CapBenchA in CapBenchA + CapBenchB"
        };
        int normalized = Math.floorMod(value, 512);
        String first = atoms[normalized & 7];
        String second = atoms[(normalized >>> 3) & 7];
        String third = atoms[(normalized >>> 6) & 7];
        String op1 = (normalized & 1) == 0 ? " and " : " or ";
        String op2 = (normalized & 2) == 0 ? " or " : " and ";
        return "((" + first + op1 + second + ")" + op2 + third + ")";
    }

    private static String seedFormula(String seedPredicate, int value) {
        return "(" + seedPredicate + " and " + controlFormula(value) + ")";
    }

    private enum Family {
        ALPHA("alpha", "Alpha-equivalence", "raw-egraph-debruijn") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                if ((i & 1) != 0) {
                    return pair(i, "capture-avoiding-nested-swap",
                            "all x: CapBenchA | some y: CapBenchA | (x->y in capBenchR and " + p + ")",
                            "all y: CapBenchA | some x: CapBenchA | (y->x in capBenchR and " + p + ")",
                            List.of("capture-avoiding-alpha-renaming", "nested-quantifier-renaming"),
                            "the outer and inner names are swapped under their lexical scopes without changing binding targets");
                }
                return pair(i, "nested-rename",
                        "all x: CapBenchA | some y: CapBenchA | (x->y in capBenchR and " + p + ")",
                        "all alphaOuter: CapBenchA | some alphaInner: CapBenchA | "
                                + "(alphaOuter->alphaInner in capBenchR and " + p + ")",
                        List.of("rename-bound-variables", "nested-quantifier-renaming"),
                        "fresh names are absent from the seed and preserve lexical scope");
            }
        },
        ACI("aci", "Associativity / commutativity / idempotence", "raw-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                String r = controlFormula(i + 347);
                switch (i % 6) {
                    case 0:
                        return pair(i, "reorder-and", "(" + p + " and " + q + " and " + r + ")",
                                "(" + r + " and " + p + " and " + q + ")", List.of("commutativity-and"), "AND is commutative");
                    case 1:
                        return pair(i, "reorder-or", "(" + p + " or " + q + " or " + r + ")",
                                "(" + q + " or " + r + " or " + p + ")", List.of("commutativity-or"), "OR is commutative");
                    case 2:
                        return pair(i, "duplicate-and", p, "(" + p + " and " + p + ")",
                                List.of("idempotence-and"), "P and P is equivalent to P");
                    case 3:
                        return pair(i, "duplicate-or", p, "(" + p + " or " + p + ")",
                                List.of("idempotence-or"), "P or P is equivalent to P");
                    case 4:
                        return pair(i, "reassociate-intersection",
                                "(some (((CapBenchA + CapBenchB) & CapBenchA) & CapBenchA) and " + p + ")",
                                "(some ((CapBenchA + CapBenchB) & (CapBenchA & CapBenchA)) and " + p + ")",
                                List.of("associativity-intersection", "idempotence-intersection"),
                                "relational intersection has the homogeneous ACI port certified by this profile");
                    default:
                        return pair(i, "flatten-unflatten-union",
                                "(some ((CapBenchA + CapBenchB) + CapBenchA) and " + p + ")",
                                "(some (CapBenchA + (CapBenchB + CapBenchA)) and " + p + ")",
                                List.of("associativity-union", "flatten-unflatten"),
                                "relational union is associative; the repeated operand also exercises ACI normalization");
                }
            }
        },
        BINDER("binder_permutation", "Binder-block permutations", "slotted-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                if ((i & 1) == 0) {
                    return pair(i, "two-binder-universal",
                            "all x, y: CapBenchA | (x->y in capBenchR and " + p + ")",
                            "all a, b: CapBenchA | (b->a in capBenchR and " + p + ")",
                            List.of("alpha-renaming", "same-type-binder-permutation"),
                            "the finite universal binder block ranges over the same Cartesian product");
                }
                return pair(i, "three-binder-existential",
                        "some x, y, z: CapBenchA | (x->y in capBenchR and y->z in capBenchR and " + p + ")",
                        "some a, b, c: CapBenchA | (c->b in capBenchR and b->a in capBenchR and " + p + ")",
                        List.of("alpha-renaming", "same-type-binder-permutation", "multi-binder-block"),
                        "the finite existential binder block is renamed by a bijection");
            }
        },
        PRENEX("safe_prenex", "Safe prenex transformations", "raw-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                if ((i & 1) == 0) {
                    return pair(i, "existential-conjunction",
                            "((some x: CapBenchA | x->x in capBenchR) and " + p + ")",
                            "(some x: CapBenchA | (x->x in capBenchR and " + p + "))",
                            List.of("safe-existential-conjunction-prenex"),
                            "x is not free in the outside conjunct");
                }
                return pair(i, "universal-disjunction",
                        "((all x: CapBenchA | x->x in capBenchR) or " + p + ")",
                        "(all x: CapBenchA | (x->x in capBenchR or " + p + "))",
                        List.of("safe-universal-disjunction-prenex"),
                        "x is not free in the outside disjunct");
            }
        },
        LOGICAL("logical_normalization", "Negation / logical normalization", "raw-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                switch (i % 6) {
                    case 0:
                        return pair(i, "double-negation", "not not (" + p + ")", p,
                                List.of("double-negation"), "classical double negation");
                    case 1:
                        return pair(i, "de-morgan", "not (" + p + " and " + q + ")",
                                "((not " + p + ") or (not " + q + "))", List.of("de-morgan"), "De Morgan duality");
                    case 2:
                        return pair(i, "implication", "(" + p + " implies " + q + ")",
                                "((not " + p + ") or " + q + ")", List.of("implication-elimination"), "P implies Q equals not P or Q");
                    case 3:
                        return pair(i, "iff", "(" + p + " iff " + q + ")",
                                "(((not " + p + ") or " + q + ") and ((not " + q + ") or " + p + "))",
                                List.of("iff-elimination"), "biconditional expansion");
                    case 4:
                        return pair(i, "quantifier-negation",
                                "not (all x: CapBenchA | (x->x in capBenchR and " + p + "))",
                                "some x: CapBenchA | not (x->x in capBenchR and " + p + ")",
                                List.of("all-to-some-negation"), "quantifier-negation duality");
                    default:
                        return pair(i, "no-to-all-not",
                                "no x: CapBenchA | (x->x in capBenchR and " + p + ")",
                                "all x: CapBenchA | not (x->x in capBenchR and " + p + ")",
                                List.of("no-to-all-not"), "no binding satisfies P iff every binding does not satisfy P");
                }
            }
        },
        TEMPORAL("temporal_normalization", "Temporal normalization", "raw-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                switch (i % 6) {
                    case 0:
                        return pair(i, "always-eventually-dual", "not always (" + p + ")",
                                "eventually (not " + p + ")", List.of("temporal-negation-dual"), "not always P equals eventually not P");
                    case 1:
                        return pair(i, "eventually-always-dual", "not eventually (" + p + ")",
                                "always (not " + p + ")", List.of("temporal-negation-dual"), "not eventually P equals always not P");
                    case 2:
                        return pair(i, "historically-once-dual", "not historically (" + p + ")",
                                "once (not " + p + ")", List.of("past-temporal-negation-dual"), "not historically P equals once not P");
                    case 3:
                        return pair(i, "once-historically-dual", "not once (" + p + ")",
                                "historically (not " + p + ")", List.of("past-temporal-negation-dual"), "not once P equals historically not P");
                    case 4:
                        return pair(i, "until-releases-dual", "not ((" + p + ") until (" + q + "))",
                                "((not " + p + ") releases (not " + q + "))", List.of("binary-temporal-negation-dual"), "not(P until Q) equals not P releases not Q");
                    default:
                        return pair(i, "since-triggered-dual", "not ((" + p + ") since (" + q + "))",
                                "((not " + p + ") triggered (not " + q + "))", List.of("past-binary-temporal-negation-dual"), "not(P since Q) equals not P triggered not Q");
                }
            }
        },
        ALPHA_AC("alpha_ac", "Composed: alpha + AC", "raw-egraph-debruijn") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                return pair(i, "alpha-and-reorder",
                        "all x: CapBenchA | (x->x in capBenchR and " + p + " and " + q + ")",
                        "all renamed: CapBenchA | (" + q + " and renamed->renamed in capBenchR and " + p + ")",
                        List.of("alpha-renaming", "commutativity-and"), "renaming is fresh and AND is commutative");
            }
        },
        ALPHA_BINDER("alpha_binder_permutation", "Composed: alpha + binder permutation", "slotted-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                return pair(i, "alpha-block-permutation",
                        "all x, y: CapBenchA | (x->y in capBenchR and " + p + ")",
                        "all freshA, freshB: CapBenchA | (freshB->freshA in capBenchR and " + p + ")",
                        List.of("alpha-renaming", "same-type-binder-permutation"), "fresh bijective renaming of one binder block");
            }
        },
        BINDER_PRENEX("binder_permutation_prenex", "Composed: binder permutation + prenex", "slotted-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                return pair(i, "permuted-existential-prenex",
                        "((some x, y: CapBenchA | x->y in capBenchR) and " + p + ")",
                        "some a, b: CapBenchA | (b->a in capBenchR and " + p + ")",
                        List.of("safe-existential-conjunction-prenex", "same-type-binder-permutation"),
                        "bound names are absent from the outside conjunct and the block permutation is bijective");
            }
        },
        AC_LOGICAL("ac_logical", "Composed: AC + logical normalization", "raw-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                return pair(i, "de-morgan-reorder", "not (" + p + " and " + q + ")",
                        "((not " + q + ") or (not " + p + "))",
                        List.of("de-morgan", "commutativity-or"), "De Morgan duality followed by OR commutativity");
            }
        },
        MIXED("mixed", "Composed: mixed 2-4 transformations", "slotted-egraph") {
            @Override FormulaPair formulas(int i, String seed) {
                String p = seedFormula(seed, i);
                String q = controlFormula(i + 173);
                return pair(i, "negation-quantifier-prenex-permutation",
                        "not ((some x, y: CapBenchA | x->y in capBenchR) and (" + p + " and " + q + "))",
                        "all a, b: CapBenchA | (not (b->a in capBenchR) or (not " + q + ") or (not " + p + "))",
                        List.of("de-morgan", "quantifier-negation", "safe-universal-disjunction-prenex", "same-type-binder-permutation"),
                        "outside formulas contain no bound names and the final binder permutation is bijective");
            }
        };

        private final String id;
        private final String displayName;
        private final String expectedFirst;

        Family(String id, String displayName, String expectedFirst) {
            this.id = id;
            this.displayName = displayName;
            this.expectedFirst = expectedFirst;
        }

        abstract FormulaPair formulas(int index, String seedPredicate);

        FormulaPair pair(
                int index,
                String subtype,
                String left,
                String right,
                List<String> transformations,
                String condition) {
            return new FormulaPair(subtype, left, right, transformations, condition);
        }
    }

    private static final class FormulaPair {
        private final String subtype;
        private final String left;
        private final String right;
        private final List<String> transformations;
        private final String soundnessCondition;

        private FormulaPair(String subtype, String left, String right, List<String> transformations, String condition) {
            this.subtype = subtype;
            this.left = left;
            this.right = right;
            this.transformations = transformations;
            this.soundnessCondition = condition;
        }
    }

    private static final class SeedPredicate {
        private final String relativePath;
        private final String predicateName;
        private final String source;

        private SeedPredicate(String relativePath, String predicateName, String source) {
            this.relativePath = relativePath;
            this.predicateName = predicateName;
            this.source = source;
        }
    }

    private static final class GeneratedPair {
        private final String relativePath;
        private final Family family;
        private final String subtype;
        private final List<String> transformations;
        private final String soundnessCondition;
        private final String seedPath;
        private final String seedPredicate;
        private final String seedSourceSha256;
        private final String leftFormula;
        private final String rightFormula;
        private final int leftAstSize;
        private final int rightAstSize;

        private GeneratedPair(
                String relativePath, Family family, String subtype, List<String> transformations,
                String soundnessCondition, String seedPath, String seedPredicate, String seedSourceSha256,
                String leftFormula, String rightFormula, int leftAstSize, int rightAstSize) {
            this.relativePath = relativePath;
            this.family = family;
            this.subtype = subtype;
            this.transformations = transformations;
            this.soundnessCondition = soundnessCondition;
            this.seedPath = seedPath;
            this.seedPredicate = seedPredicate;
            this.seedSourceSha256 = seedSourceSha256;
            this.leftFormula = leftFormula;
            this.rightFormula = rightFormula;
            this.leftAstSize = leftAstSize;
            this.rightAstSize = rightAstSize;
        }

        private JSONObject toJson() {
            return new JSONObject()
                    .put("relativePath", relativePath)
                    .put("family", family.id)
                    .put("subtype", subtype)
                    .put("expectedFirstCapable", family.expectedFirst)
                    .put("transformations", new JSONArray(transformations))
                    .put("soundnessCondition", soundnessCondition)
                    .put("groundTruth", "equivalence-by-construction")
                    .put("seedPath", seedPath)
                    .put("seedPredicate", seedPredicate)
                    .put("seedSourceSha256", seedSourceSha256)
                    .put("leftFormula", leftFormula)
                    .put("rightFormula", rightFormula)
                    .put("leftAstSize", leftAstSize)
                    .put("rightAstSize", rightAstSize);
        }
    }

    private static final class Skip {
        private final String family;
        private final String seedPath;
        private final String reason;

        private Skip(String family, String seedPath, String reason) {
            this.family = family;
            this.seedPath = seedPath;
            this.reason = reason;
        }

        private JSONObject toJson() {
            return new JSONObject().put("family", family).put("seedPath", seedPath).put("reason", reason);
        }
    }

    private static final class Validation {
        private final boolean accepted;
        private final String reason;
        private final int leftAstSize;
        private final int rightAstSize;

        private Validation(boolean accepted, String reason, int leftAstSize, int rightAstSize) {
            this.accepted = accepted;
            this.reason = reason;
            this.leftAstSize = leftAstSize;
            this.rightAstSize = rightAstSize;
        }

        private static Validation accepted(int left, int right) {
            return new Validation(true, "", left, right);
        }

        private static Validation rejected(String reason) {
            return new Validation(false, reason, 0, 0);
        }
    }

    private static final class PairRecord {
        private final String relativePath;
        private final String family;
        private final String subtype;
        private final String expectedFirst;
        private final String seedPath;
        private final String seedPredicate;
        private final Map<String, Outcome> outcomes = new LinkedHashMap<>();

        private PairRecord(Map<String, String> row) {
            relativePath = row.get("relativePath");
            family = row.get("family");
            subtype = row.get("subtype");
            expectedFirst = row.get("expectedFirstCapable");
            seedPath = row.get("seedPath");
            seedPredicate = row.get("seedPredicate");
        }
    }

    private static final class Outcome {
        private final boolean success;
        private final boolean zero;
        private final int distance;
        private final long engineNanos;
        private final long engineCpuNanos;
        private final long representationUnits;
        private final long estimatedBytes;
        private final String error;

        private Outcome(boolean success, boolean zero, int distance, long engineNanos,
                long engineCpuNanos, long representationUnits, long estimatedBytes, String error) {
            this.success = success;
            this.zero = zero;
            this.distance = distance;
            this.engineNanos = engineNanos;
            this.engineCpuNanos = engineCpuNanos;
            this.representationUnits = representationUnits;
            this.estimatedBytes = estimatedBytes;
            this.error = error == null ? "" : error;
        }

        private static Outcome from(Map<String, String> row) {
            boolean success = Boolean.parseBoolean(row.get("success"));
            int distance = integer(row.get("distance"), -1);
            return new Outcome(success, success && Boolean.parseBoolean(row.get("equivalent")), distance,
                    number(row.get("engineNanos")), number(row.get("engineCpuNanos")),
                    number(row.get("representationUnits")), number(row.get("estimatedBytes")), row.get("error"));
        }
    }

    private static final class NaturalMetrics {
        private int successful;
        private int correct;
        private int correctZero;
        private int incorrect;
        private int incorrectZero;
        private long distance;

        private void add(Map<String, String> row) {
            if (!Boolean.parseBoolean(row.get("success"))) return;
            successful++;
            boolean zero = Boolean.parseBoolean(row.get("equivalent"));
            boolean isCorrect = "CORRECT".equalsIgnoreCase(row.get("status"));
            if (isCorrect) {
                correct++;
                if (zero) correctZero++;
            } else {
                incorrect++;
                if (zero) incorrectZero++;
            }
            distance += integer(row.get("distance"), 0);
        }

        private double correctCoverage() {
            return correct == 0 ? 0.0 : (double) correctZero / correct;
        }

        private double averageDistance() {
            return successful == 0 ? 0.0 : (double) distance / successful;
        }

        private JSONObject toJson() {
            return new JSONObject().put("successful", successful).put("correct", correct)
                    .put("correctZero", correctZero).put("correctCoverage", correctCoverage())
                    .put("incorrect", incorrect).put("incorrectZero", incorrectZero)
                    .put("averageDistance", averageDistance());
        }
    }

    private static final class FamilyReport {
        private final Family family;
        private final List<PairRecord> pairs;
        private final Map<String, ArmMetrics> metrics = new LinkedHashMap<>();

        private FamilyReport(Family family, List<PairRecord> pairs) {
            this.family = family;
            this.pairs = pairs;
            for (String arm : ARMS) {
                metrics.put(arm, new ArmMetrics(pairs, arm, family.expectedFirst.equals(arm)));
            }
        }

        private String observedFirst() {
            for (String arm : ARMS) {
                ArmMetrics value = metrics.get(arm);
                if (value.generated > 0 && value.zero == value.generated) {
                    return arm;
                }
            }
            return "";
        }

        private Transition transition(String before, String after) {
            int retained = 0;
            int added = 0;
            int lost = 0;
            for (PairRecord pair : pairs) {
                boolean left = pair.outcomes.containsKey(before) && pair.outcomes.get(before).zero;
                boolean right = pair.outcomes.containsKey(after) && pair.outcomes.get(after).zero;
                if (left && right) retained++;
                else if (!left && right) added++;
                else if (left) lost++;
            }
            return new Transition(retained, added, lost);
        }

        private JSONObject toJson() {
            JSONObject json = new JSONObject()
                    .put("family", family.id)
                    .put("displayName", family.displayName)
                    .put("expectedFirstCapable", family.expectedFirst)
                    .put("observedFirstCapable", observedFirst());
            JSONObject armJson = new JSONObject();
            for (String arm : ARMS) {
                armJson.put(arm, metrics.get(arm).toJson());
            }
            json.put("arms", armJson);
            JSONArray transitionJson = new JSONArray();
            for (String[] edge : TRANSITIONS) {
                transitionJson.put(transition(edge[0], edge[1]).toJson(edge[0], edge[1]));
            }
            return json.put("transitions", transitionJson);
        }
    }

    private static final class ArmMetrics {
        private final int generated;
        private final boolean expectedArm;
        private int evaluated;
        private int errors;
        private int zero;
        private long engineNanos;
        private long engineCpuNanos;
        private long representationUnits;
        private long estimatedBytes;
        private final List<Integer> distances = new ArrayList<>();

        private ArmMetrics(List<PairRecord> pairs, String arm, boolean expectedArm) {
            generated = pairs.size();
            this.expectedArm = expectedArm;
            for (PairRecord pair : pairs) {
                Outcome outcome = pair.outcomes.get(arm);
                if (outcome == null || !outcome.success) {
                    errors++;
                    continue;
                }
                evaluated++;
                if (outcome.zero) zero++;
                distances.add(outcome.distance);
                engineNanos += outcome.engineNanos;
                engineCpuNanos += outcome.engineCpuNanos;
                representationUnits += outcome.representationUnits;
                estimatedBytes += outcome.estimatedBytes;
            }
        }

        private int falseNegatives() {
            return generated - zero;
        }

        private double recoveryRate() {
            return generated == 0 ? 0.0 : (double) zero / generated;
        }

        private double averageDistance() {
            return distances.stream().mapToInt(Integer::intValue).average().orElse(0.0);
        }

        private int percentile(double p) {
            if (distances.isEmpty()) return 0;
            List<Integer> sorted = new ArrayList<>(distances);
            Collections.sort(sorted);
            int index = Math.max(0, Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1));
            return sorted.get(index);
        }

        private double averageRepresentationUnits() {
            return evaluated == 0 ? 0.0 : (double) representationUnits / evaluated;
        }

        private double averageEstimatedBytes() {
            return evaluated == 0 ? 0.0 : (double) estimatedBytes / evaluated;
        }

        private String csvValues() {
            return generated + "," + evaluated + "," + errors + "," + zero + ","
                    + number(recoveryRate()) + "," + falseNegatives() + ","
                    + number(averageDistance()) + "," + percentile(0.50) + "," + percentile(0.95) + ","
                    + number(engineCpuNanos / 1e9) + "," + number(engineNanos / 1e9) + ","
                    + number(averageRepresentationUnits()) + "," + number(averageEstimatedBytes()) + ","
                    + (expectedArm ? falseNegatives() : 0);
        }

        private JSONObject toJson() {
            return new JSONObject()
                    .put("generated", generated).put("evaluated", evaluated).put("errors", errors)
                    .put("zero", zero).put("recoveryRate", recoveryRate())
                    .put("falseNegatives", falseNegatives())
                    .put("averageDistance", averageDistance()).put("p50Distance", percentile(0.50))
                    .put("p95Distance", percentile(0.95)).put("engineCpuNanos", engineCpuNanos)
                    .put("aggregateEngineWallNanos", engineNanos)
                    .put("averageRepresentationUnits", averageRepresentationUnits())
                    .put("averageEstimatedBytes", averageEstimatedBytes())
                    .put("unexpectedFailures", expectedArm ? falseNegatives() : 0);
        }
    }

    private static final class Transition {
        private final int retained;
        private final int added;
        private final int lost;

        private Transition(int retained, int added, int lost) {
            this.retained = retained;
            this.added = added;
            this.lost = lost;
        }

        private JSONObject toJson(String before, String after) {
            return new JSONObject().put("from", before).put("to", after)
                    .put("retainedZero", retained).put("newlyZero", added).put("noLongerZero", lost);
        }
    }

    private enum Mode { GENERATE, REPORT }

    private static final class Options {
        private Mode mode;
        private Path dataset = Paths.get("classified-data");
        private Path output = Paths.get("capability_benchmark");
        private Path natural = Paths.get("egraph_ablation");
        private int target = DEFAULT_TARGET;
        private long seed = DEFAULT_SEED;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--generate": options.mode = Mode.GENERATE; break;
                    case "--report": options.mode = Mode.REPORT; break;
                    case "--dataset": options.dataset = Paths.get(args[++i]); break;
                    case "--output": options.output = Paths.get(args[++i]); break;
                    case "--natural": options.natural = Paths.get(args[++i]); break;
                    case "--target": options.target = Integer.parseInt(args[++i]); break;
                    case "--seed": options.seed = Long.parseLong(args[++i]); break;
                    default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (options.mode == null) throw new IllegalArgumentException("Specify --generate or --report");
            if (options.target < 1) throw new IllegalArgumentException("--target must be positive");
            return options;
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte item : digest) hex.append(String.format(Locale.ROOT, "%02x", item));
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static long number(String value) {
        try { return Long.parseLong(value); } catch (RuntimeException ignored) { return 0L; }
    }

    private static int integer(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (RuntimeException ignored) { return fallback; }
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100.0);
    }
}
