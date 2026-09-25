package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.ErrorWarning;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.asg.Multigraph;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.alloyasg.etc.DoubleMap;
import parser.ast.nodes.ModelUnit;
import parser.ast.nodes.Predicate;
import parser.util.AlloyUtil;

public final class CanonicalBacktranslationEquivalenceTest {
    private static final int DEFAULT_SCOPE = 3;
    private static final Path DEFAULT_INPUT = Paths.get("classified-data");
    private static final Path DEFAULT_OUTPUT = Paths.get("alloy4fun-augmented/backtranslation_equivalence_mismatches.json");

    private CanonicalBacktranslationEquivalenceTest() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Files.createDirectories(options.output.getParent());
        List<Path> files = alloyFiles(options.input);
        if (options.limit > 0 && options.limit < files.size()) {
            files = new ArrayList<>(files.subList(0, options.limit));
        }
        List<EquivalenceResult> results = new ArrayList<>();
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "CanonicalBacktranslationEquivalenceTest",
                files.size(),
                "files");
        int completed = 0;
        for (Path file : files) {
            results.addAll(checkFile(options.input, file, options.scope));
            progress.update(++completed);
        }
        progress.finish(completed);
        writeJson(options, files.size(), results);
        long mismatches = results.stream().filter(result -> result.mismatch()).count();
        long failures = results.stream().filter(result -> result.error != null).count();
        System.out.println("Checked " + results.size() + " backtranslated predicates from " + files.size() + " files");
        System.out.println("Mismatches: " + mismatches);
        System.out.println("Failures: " + failures);
        System.out.println("Wrote " + options.output);
        if (mismatches > 0 || failures > 0) {
            throw new AssertionError("Backtranslation equivalence mismatches=" + mismatches + ", failures=" + failures);
        }
    }

    private static List<EquivalenceResult> checkFile(Path inputRoot, Path file, int scope) {
        List<EquivalenceResult> results = new ArrayList<>();
        try {
            CompModule originalModule = AlloyUtil.compileAlloyModule(file.toString());
            ModelUnit model = new ModelUnit(null, originalModule);
            Map<String, Predicate> predicates = predicates(model);
            PredicatePair pair = predicatePair(file, predicates);
            if (pair == null) {
                results.add(EquivalenceResult.failure(inputRoot, file, "<unknown>", "No X/XC predicate pair found."));
                return results;
            }
            Map<String, Multigraph> graphs = predicateGraphs(
                    originalModule, model);
            checkPredicate(inputRoot, file, pair.leftName, graphs.get(pair.leftName), scope, results);
            checkPredicate(inputRoot, file, pair.rightName, graphs.get(pair.rightName), scope, results);
        } catch (Throwable t) {
            results.add(EquivalenceResult.failure(inputRoot, file, "<file>",
                    t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        return results;
    }

    private static void checkPredicate(
            Path inputRoot,
            Path file,
            String originalName,
            Multigraph graph,
            int scope,
            List<EquivalenceResult> results) {
        EquivalenceResult result = new EquivalenceResult(inputRoot, file, originalName);
        results.add(result);
        if (graph == null) {
            result.error = "No MASG graph found for predicate.";
            return;
        }
        result.generatedPredicate = "__canonical_bt_" + sanitize(originalName);
        try {
            result.irTemporalFol = Canonical.irTemporalFol(graph);
            result.generatedSource = CanonicalBacktranslator.predicate(result.generatedPredicate, graph);
            String source = Files.readString(file, StandardCharsets.UTF_8)
                    + "\n\n" + result.generatedSource + "\n";
            Path generatedFile = Files.createTempFile("canonical-backtranslation-equivalence-", ".als");
            Files.writeString(generatedFile, source, StandardCharsets.UTF_8);
            CompModule module = RewarderFreeCompile.compile(generatedFile);
            result.overcoverage = satisfiable(module,
                    result.generatedPredicate + "[] && !(" + originalName + "[])",
                    scope);
            result.undercoverage = satisfiable(module,
                    "!(" + result.generatedPredicate + "[]) && " + originalName + "[]",
                    scope);
        } catch (Throwable t) {
            result.error = throwableSummary(t);
        }
    }

    private static String throwableSummary(Throwable t) {
        StringBuilder summary = new StringBuilder();
        summary.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(8, stack.length);
        for (int i = 0; i < limit; i++) {
            summary.append("\\n  at ").append(stack[i]);
        }
        return summary.toString();
    }

    private static boolean satisfiable(CompModule module, String expression, int scope) throws Exception {
        A4Reporter reporter = reporter();
        Expr expr = CompUtil.parseOneExpression_fromString(module, expression);
        Command command = new Command(true, scope, scope, scope, expr);
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution solution = TranslateAlloyToKodkod.execute_command(reporter, module.getAllReachableSigs(), command, options);
        return solution != null && solution.satisfiable();
    }

    private static Map<String, Predicate> predicates(ModelUnit model) {
        Map<String, Predicate> predicates = new HashMap<>();
        for (Predicate predicate : model.getPredDeclList()) {
            predicates.put(predicate.getName(), predicate);
        }
        return predicates;
    }

    private static Map<String, Multigraph> predicateGraphs(
            CompModule module,
            ModelUnit model) {
        MASGVisitor visitor = new MASGVisitor(new GlobalVariables(), module);
        visitor.visit(model, null);
        DoubleMap<Integer, Multigraph> forest = visitor.getForest();
        Map<String, Multigraph> graphs = new HashMap<>();
        for (Integer key : forest.keys()) {
            Multigraph graph = forest.get(key);
            if (graph != null && graph.getRoot() != null && graph.getRoot().getSymbol() != null) {
                graphs.put(graph.getRoot().getSymbol().getName(), graph);
            }
        }
        return graphs;
    }

    private static PredicatePair predicatePair(Path file, Map<String, Predicate> predicates) {
        String[] names = DatasetConventions.findPredicatePairNames(preferredPredicateBase(file), predicates);
        return names == null ? null : new PredicatePair(names[0], names[1]);
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

    private static void writeJson(Options options, int fileCount, List<EquivalenceResult> results) throws IOException {
        int mismatches = 0;
        int failures = 0;
        for (EquivalenceResult result : results) {
            if (result.mismatch()) {
                mismatches++;
            }
            if (result.error != null) {
                failures++;
            }
        }
        try (Writer writer = Files.newBufferedWriter(options.output, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writer.write("  \"generatedAt\": \"" + escape(Instant.now().toString()) + "\",\n");
            writer.write("  \"inputRoot\": \"" + escape(options.input.toString()) + "\",\n");
            writer.write("  \"scope\": " + options.scope + ",\n");
            writer.write("  \"fileCount\": " + fileCount + ",\n");
            writer.write("  \"predicateCount\": " + results.size() + ",\n");
            writer.write("  \"mismatchCount\": " + mismatches + ",\n");
            writer.write("  \"failureCount\": " + failures + ",\n");
            writer.write("  \"mismatches\": [\n");
            boolean first = true;
            for (EquivalenceResult result : results) {
                if (!result.mismatch() && result.error == null) {
                    continue;
                }
                if (!first) {
                    writer.write(",\n");
                }
                first = false;
                writeResultJson(writer, result);
            }
            writer.write("\n  ]\n");
            writer.write("}\n");
        }
    }

    private static void writeResultJson(Writer writer, EquivalenceResult result) throws IOException {
        writer.write("    {\n");
        writer.write("      \"relativePath\": \"" + escape(result.relativePath) + "\",\n");
        writer.write("      \"predicate\": \"" + escape(result.predicate) + "\",\n");
        writer.write("      \"generatedPredicate\": \"" + escape(result.generatedPredicate) + "\",\n");
        writer.write("      \"overcoverage\": " + result.overcoverage + ",\n");
        writer.write("      \"undercoverage\": " + result.undercoverage + ",\n");
        if (result.error == null) {
            writer.write("      \"error\": null,\n");
        } else {
        writer.write("      \"error\": \"" + escape(result.error) + "\",\n");
        }
        writer.write("      \"irTemporalFol\": [");
        for (int i = 0; i < result.irTemporalFol.size(); i++) {
            if (i > 0) {
                writer.write(", ");
            }
            writer.write("\"" + escape(result.irTemporalFol.get(i)) + "\"");
        }
        writer.write("],\n");
        writer.write("      \"generatedSource\": \"" + escape(result.generatedSource) + "\"\n");
        writer.write("    }");
    }

    private static String sanitize(String value) {
        return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static A4Reporter reporter() {
        return new A4Reporter() {
            @Override
            public void warning(ErrorWarning msg) {
            }
        };
    }

    private static final class RewarderFreeCompile {
        private static CompModule compile(Path file) {
            return CompUtil.parseEverything_fromFile(reporter(), null, file.toString());
        }
    }

    private static final class PredicatePair {
        private final String leftName;
        private final String rightName;

        private PredicatePair(String leftName, String rightName) {
            this.leftName = leftName;
            this.rightName = rightName;
        }
    }

    private static final class EquivalenceResult {
        private final String relativePath;
        private final String predicate;
        private String generatedPredicate;
        private String generatedSource;
        private List<String> irTemporalFol = new ArrayList<>();
        private boolean overcoverage;
        private boolean undercoverage;
        private String error;

        private EquivalenceResult(Path inputRoot, Path file, String predicate) {
            this.relativePath = inputRoot.relativize(file).toString().replace('\\', '/');
            this.predicate = predicate;
            this.generatedPredicate = "";
            this.generatedSource = "";
        }

        private static EquivalenceResult failure(Path inputRoot, Path file, String predicate, String error) {
            EquivalenceResult result = new EquivalenceResult(inputRoot, file, predicate);
            result.error = error;
            return result;
        }

        private boolean mismatch() {
            return overcoverage || undercoverage;
        }
    }

    private static final class Options {
        private Path input = DEFAULT_INPUT;
        private Path output = DEFAULT_OUTPUT;
        private int scope = DEFAULT_SCOPE;
        private int limit = -1;

        private static Options parse(String[] args) {
            Options options = new Options();
            int positional = 0;
            for (int i = 0; i < args.length; i++) {
                if ("--scope".equals(args[i]) && i + 1 < args.length) {
                    options.scope = Integer.parseInt(args[++i]);
                } else if ("--limit".equals(args[i]) && i + 1 < args.length) {
                    options.limit = Integer.parseInt(args[++i]);
                } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                    options.output = Paths.get(args[++i]);
                } else if (positional == 0) {
                    options.input = Paths.get(args[i]);
                    positional++;
                }
            }
            return options;
        }
    }
}
