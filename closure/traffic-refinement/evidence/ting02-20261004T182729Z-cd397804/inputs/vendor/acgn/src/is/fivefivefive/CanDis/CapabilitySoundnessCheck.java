package is.fivefivefive.CanDis;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.ExprBinary;
import edu.mit.csail.sdg.ast.ExprCall;
import edu.mit.csail.sdg.ast.ExprConstant;
import edu.mit.csail.sdg.ast.ExprUnary;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.ast.VisitQueryOnce;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

/** Bounded SAT sanity checks for a deterministic sample of generated capability pairs. */
public final class CapabilitySoundnessCheck {
    private CapabilitySoundnessCheck() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");
        Options options = Options.parse(args);
        List<Map<String, String>> metadata = readCsv(options.root.resolve("metadata.csv"));
        List<Map<String, String>> selected = select(metadata, options.perSubtype);
        List<Result> results = new ArrayList<>();
        ExperimentProgress progress = ExperimentProgress.start(
                System.err,
                "CapabilitySoundnessCheck",
                selected.size(),
                "checks");
        int completed = 0;
        for (Map<String, String> row : selected) {
            results.add(check(options.root.resolve("models").resolve(row.get("relativePath")), row));
            progress.update(++completed);
        }
        progress.finish(completed);
        Files.createDirectories(options.output);
        writeCsv(options.output.resolve("soundness.csv"), results);
        writeJson(options.output.resolve("soundness.json"), options, results);
        writeMarkdown(options.output.resolve("SOUNDNESS.md"), options, results);
        long failures = results.stream().filter(Result::failed).count();
        System.out.printf(Locale.ROOT, "Bounded capability soundness: %,d checks, %,d failures.%n",
                results.size(), failures);
        if (failures > 0) {
            throw new AssertionError("Generated capability soundness failures: " + failures);
        }
    }

    private static List<Map<String, String>> select(List<Map<String, String>> metadata, int perSubtype) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Map<String, String>> selected = new ArrayList<>();
        for (Map<String, String> row : metadata) {
            String key = row.get("family") + ":" + row.get("subtype");
            int count = counts.getOrDefault(key, 0);
            if (count < perSubtype) {
                selected.add(row);
                counts.put(key, count + 1);
            }
        }
        return selected;
    }

    static Result check(Path file, Map<String, String> metadata) {
        Result result = new Result(metadata);
        try {
            CompModule module = CompUtil.parseEverything_fromFile(new A4Reporter(), null, file.toString());
            Command target = null;
            for (Command command : module.getAllCommands()) {
                String label = command.label == null ? "" : command.label;
                if (command.check && label.contains("CapBenchEquivalent_cap")) {
                    target = command;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalStateException("Generated equivalence command not found");
            }
            result.scope = target.overall;
            Command prepared = prepareCommand(module, target);
            result.temporal = CompUtil.isTemporalModel(module.getAllReachableSigs(), prepared);
            result.temporalModeExposed = prepared != target;
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            A4Solution solution = TranslateAlloyToKodkod.execute_command(
                    new A4Reporter(), module.getAllReachableSigs(), prepared, options);
            if (solution == null) throw new IllegalStateException("Solver returned no result");
            result.minTrace = solution.getMinTrace();
            result.maxTrace = solution.getMaxTrace();
            if (result.temporal && (result.minTrace < 1 || result.maxTrace < result.minTrace)) {
                throw new IllegalStateException("Temporal command did not use bounded temporal solving");
            }
            result.counterexample = solution.satisfiable();
            result.inconclusive = false;
            if (result.temporalModeExposed) {
                result.note = "Exposed temporal mode with Q and after true; source predicates and scopes unchanged";
            }
        } catch (Throwable throwable) {
            result.error = throwable.getClass().getSimpleName() + ": " + String.valueOf(throwable.getMessage());
        }
        return result;
    }

    static Command prepareCommand(CompModule module, Command command) throws Err {
        if (CompUtil.isTemporalModel(module.getAllReachableSigs(), command)) return command;
        Boolean temporal = command.formula.accept(new VisitQueryOnce<Boolean>() {
            private final Set<Func> functions = Collections.newSetFromMap(new IdentityHashMap<>());

            @Override
            public Boolean visit(ExprCall call) throws Err {
                Boolean argument = super.visit(call);
                if (argument != null) return argument;
                return functions.add(call.fun) ? call.fun.getBody().accept(this) : null;
            }

            @Override
            public Boolean visit(ExprUnary unary) throws Err {
                switch (unary.op) {
                    case AFTER: case BEFORE: case PRIME: case ALWAYS: case EVENTUALLY:
                    case HISTORICALLY: case ONCE: return true;
                    default: return super.visit(unary);
                }
            }

            @Override
            public Boolean visit(ExprBinary binary) throws Err {
                switch (binary.op) {
                    case UNTIL: case RELEASES: case SINCE: case TRIGGERED: return true;
                    default: return super.visit(binary);
                }
            }
        });
        if (temporal == null) return command;
        // Alloy 6.1's mode detector skips callee bodies. On infinite traces,
        // Q and after true is Q; exposing the operator selects Pardinus without
        // inlining bindings or adding a signature that would change univ.
        Command prepared = command.change(command.formula.and(ExprConstant.TRUE.after()));
        if (!CompUtil.isTemporalModel(module.getAllReachableSigs(), prepared)) {
            throw new IllegalStateException("Could not expose the temporal command to Alloy");
        }
        return prepared;
    }

    private static void writeCsv(Path path, List<Result> results) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("relativePath,family,subtype,solverReportedCounterexample,inconclusive,note,error,temporal,temporalModeExposed,scope,minTrace,maxTrace,solver\n");
            for (Result result : results) {
                csv(writer, result.relativePath);
                csv(writer, result.family);
                csv(writer, result.subtype);
                writer.write(result.counterexample + "," + result.inconclusive + ",");
                csv(writer, result.note);
                csv(writer, result.error);
                writer.write(result.temporal + "," + result.temporalModeExposed + "," + result.scope
                        + "," + result.minTrace + "," + result.maxTrace + ",SAT4J");
                writer.write('\n');
            }
        }
    }

    private static void writeJson(Path path, Options options, List<Result> results) throws IOException {
        JSONArray checks = new JSONArray();
        for (Result result : results) checks.put(result.toJson());
        JSONObject json = new JSONObject().put("schemaVersion", "candis-capability-soundness-v2")
                .put("generatedAt", Instant.now().toString()).put("perSubtype", options.perSubtype)
                .put("interpretation", "finite-scope/trace SAT sanity check, not proof; bounds recorded per command")
                .put("checks", checks);
        Files.writeString(path, json.toString(2) + "\n", StandardCharsets.UTF_8);
    }

    private static void writeMarkdown(Path path, Options options, List<Result> results) throws IOException {
        long counterexamples = results.stream().filter(result -> result.counterexample).count();
        long errors = results.stream().filter(result -> !result.error.isEmpty()).count();
        long inconclusive = results.stream().filter(result -> result.inconclusive).count();
        long failures = results.stream().filter(Result::failed).count();
        long temporal = results.stream().filter(result -> result.temporal && !result.inconclusive).count();
        Set<String> families = new LinkedHashSet<>();
        Set<String> subtypes = new LinkedHashSet<>();
        for (Result result : results) {
            families.add(result.family);
            subtypes.add(result.family + ":" + result.subtype);
        }
        String markdown = "# Capability Benchmark Bounded Soundness\n\n"
                + "- Cases per transformation subtype: " + options.perSubtype + "\n"
                + "- Checked cases: " + results.size() + "\n"
                + "- Families: " + families.size() + "\n"
                + "- Family/subtype combinations: " + subtypes.size() + "\n"
                + "- Solver-reported counterexamples: " + counterexamples + "\n"
                + "- Solver/translation errors: " + errors + "\n"
                + "- Completed bounded temporal checks: " + temporal + "\n"
                + "- Inconclusive checks: " + inconclusive + "\n"
                + "- Failed checks (including inconclusive): " + failures + "\n\n"
                + "These checks execute the generated Alloy equivalence assertions with SAT4J. "
                + "Temporal commands use Pardinus bounded temporal solving; exact scope and trace bounds are recorded per check. "
                + "Unsatisfiability within those bounds is a finite-scope sanity check, not a semantic proof; "
                + "the benchmark ground truth remains the recorded sound transformation and side condition. "
                + "An inconclusive result or solver error fails the check, including temporal cases.\n";
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> readCsv(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return Collections.emptyList();
        List<String> header = parseCsv(lines.get(0));
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isEmpty()) continue;
            List<String> fields = parseCsv(lines.get(i));
            Map<String, String> row = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) {
                row.put(header.get(j), j < fields.size() ? fields.get(j) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private static List<String> parseCsv(String line) {
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

    private static void csv(Writer writer, String value) throws IOException {
        csvValue(writer, value);
        writer.write(',');
    }

    private static void csvValue(Writer writer, String value) throws IOException {
        writer.write('"');
        writer.write((value == null ? "" : value).replace("\"", "\"\""));
        writer.write('"');
    }

    static final class Result {
        private final String relativePath;
        private final String family;
        private final String subtype;
        private boolean counterexample;
        private boolean inconclusive = true;
        private boolean temporal;
        private boolean temporalModeExposed;
        private int scope = -1;
        private int minTrace = -1;
        private int maxTrace = -1;
        private String note = "";
        private String error = "";

        private Result(Map<String, String> metadata) {
            relativePath = metadata.get("relativePath");
            family = metadata.get("family");
            subtype = metadata.get("subtype");
        }

        boolean failed() {
            return inconclusive || counterexample || !error.isEmpty();
        }

        JSONObject toJson() {
            return new JSONObject().put("relativePath", relativePath).put("family", family)
                    .put("subtype", subtype).put("solverReportedCounterexample", counterexample)
                    .put("inconclusive", inconclusive).put("note", note).put("error", error)
                    .put("temporal", temporal).put("temporalModeExposed", temporalModeExposed)
                    .put("scope", scope).put("minTrace", minTrace).put("maxTrace", maxTrace)
                    .put("solver", "SAT4J");
        }
    }

    private static final class Options {
        private Path root = Paths.get("capability_benchmark");
        private Path output;
        private int perSubtype = 1;

        private static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--root": options.root = Paths.get(args[++i]); break;
                    case "--output": options.output = Paths.get(args[++i]); break;
                    case "--per-subtype": options.perSubtype = Integer.parseInt(args[++i]); break;
                    default: throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            if (options.perSubtype < 1) throw new IllegalArgumentException("--per-subtype must be positive");
            if (options.output == null) options.output = options.root;
            return options;
        }
    }
}
