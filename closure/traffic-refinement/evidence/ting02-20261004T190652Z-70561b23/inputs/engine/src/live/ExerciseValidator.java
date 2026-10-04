package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.alloy4.Util;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.ExprConstant;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Private import gate: type checking, body containment and bounded equivalence. */
public final class ExerciseValidator {
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final int MAX_BODY_BYTES = 8_192;
    private static final int MAX_SOURCE_BYTES = 262_144;
    private static final int MAX_REFERENCES = 256;
    private static final Set<String> FIELDS = Set.of("predicate", "sourcePrefix", "sourceSuffix",
            "starter", "oracleBodies", "correctBodies", "scope");

    private ExerciseValidator() { }

    public static void main(String[] arguments) throws Exception {
        PrintStream wire = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        JSONObject result;
        try {
            byte[] bytes = System.in.readNBytes(MAX_INPUT_BYTES + 1);
            if (bytes.length > MAX_INPUT_BYTES) result = rejected("REQUEST_TOO_LARGE", -1);
            else {
                String input = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                result = evaluate(new JSONObject(input));
            }
        } catch (Throwable error) {
            result = rejected("INVALID_REQUEST", -1);
        }
        wire.println(result);
    }

    public static JSONObject evaluate(JSONObject request) {
        String predicate, prefix, suffix, starter;
        int scope;
        List<String> oracles, correct, candidates = new ArrayList<>();
        try {
            if (!FIELDS.containsAll(request.keySet())) throw new IllegalArgumentException();
            predicate = text(request.get("predicate"), 128, false);
            if (!predicate.matches("[A-Za-z_][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException();
            prefix = text(request.get("sourcePrefix"), MAX_SOURCE_BYTES, false);
            suffix = text(request.get("sourceSuffix"), MAX_SOURCE_BYTES, false);
            if (!prefix.endsWith("{\n") || !suffix.startsWith("\n}")) throw new IllegalArgumentException();
            starter = text(request.get("starter"), MAX_BODY_BYTES, true);
            Object requestedScope = request.opt("scope");
            if (requestedScope == null) scope = 5;
            else if (requestedScope instanceof Integer value && value >= 1 && value <= 8) scope = value;
            else throw new IllegalArgumentException();
            oracles = bodies(request.getJSONArray("oracleBodies"));
            correct = bodies(request.getJSONArray("correctBodies"));
            candidates.addAll(oracles);
            candidates.addAll(correct);
            if (oracles.isEmpty() || candidates.size() > MAX_REFERENCES
                    || new HashSet<>(candidates).size() != candidates.size()) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            return rejected("INVALID_REQUEST", -1);
        }

        try {
            checkedModel(prefix, suffix, starter, predicate, -1);
            CompModule world = null;
            // Every candidate is independently parsed before SAT checking. Even
            // an exact earlier match cannot skip a malformed later reference.
            for (int index = 0; index < candidates.size(); index++) {
                CompModule checked = checkedModel(prefix, suffix, candidates.get(index), predicate, index);
                if (index == 0) world = checked;
            }
            if (world == null) throw new IllegalStateException();
            Expr facts = world.getAllReachableFacts();
            Expr primary = BehaviorFeedback.selected(world, predicate).call();
            Set<String> strings = BehaviorFeedback.stringConstants(primary);
            strings.addAll(BehaviorFeedback.stringConstants(facts));
            for (Sig sig : world.getAllReachableUserDefinedSigs()) {
                for (Expr fact : sig.getFacts()) strings.addAll(BehaviorFeedback.stringConstants(fact));
                for (Sig.Field field : sig.getFields())
                    strings.addAll(BehaviorFeedback.stringConstants(field.decl().expr));
            }
            // Alloy fixes String atoms from literals mentioned by a command.
            // Keep the primary universe in the facts-only query as well, and
            // prevent a new candidate literal from making the facts unsat.
            for (String literal : strings)
                facts = facts.and(ExprConstant.Op.STRING.make(Pos.UNKNOWN, literal).in(Sig.STRING));
            if (!solve(world, facts, scope).satisfiable()) return rejected("INCONSISTENT_FACTS", -1);
            for (int index = 1; index < candidates.size(); index++) {
                // Reparse only the independently checked expression in the
                // primary world, so all compared expressions share signatures.
                Expr candidate = CompUtil.parseOneExpression_fromString(world, "{\n" + candidates.get(index) + "\n}");
                if (!candidate.type().is_bool) return rejected("INVALID_MODEL", index);
                if (!strings.containsAll(BehaviorFeedback.stringConstants(candidate)))
                    return rejected("UNSUPPORTED_STRING_UNIVERSE", index);
                Expr counterexample = facts.and(primary.iff(candidate).not());
                if (solve(world, counterexample, scope).satisfiable()) return rejected("NOT_EQUIVALENT", index);
            }
            return new JSONObject().put("status", "ok").put("scope", scope).put("bitwidth", 5)
                    .put("maxSequence", scope).put("minTrace", 1).put("maxTrace", 10)
                    .put("solver", "SAT4J").put("moduleFacts", true).put("factsSatisfiable", true)
                    .put("oracleCount", oracles.size()).put("correctCount", correct.size())
                    .put("evaluatedCandidates", candidates.size()).put("equivalenceChecks", candidates.size() - 1)
                    .put("equivalence", "bounded");
        } catch (Rejected error) {
            return rejected(error.code, error.candidateIndex);
        } catch (Throwable error) {
            return rejected("VALIDATION_FAILED", -1);
        }
    }

    private static CompModule checkedModel(String prefix, String suffix, String body, String predicate,
            int candidateIndex) throws Rejected {
        if (!contained(body)) throw new Rejected("BODY_CONTAINMENT", candidateIndex);
        String source = prefix + body + suffix;
        if (source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES)
            throw new Rejected("INVALID_REQUEST", candidateIndex);
        CompModule world;
        try { world = CompUtil.parseEverything_fromString(A4Reporter.NOP, source); }
        catch (Err error) { throw new Rejected("INVALID_MODEL", candidateIndex); }
        if (!bundledDependencies(world)) throw new Rejected("UNSUPPORTED_EXTERNAL_MODULE", candidateIndex);
        Func selected;
        try { selected = BehaviorFeedback.selected(world, predicate); }
        catch (RuntimeException error) { throw new Rejected("UNSUPPORTED_PREDICATE", candidateIndex); }
        int[] range;
        try { range = BehaviorFeedback.bodyRange(selected, source); }
        catch (RuntimeException error) { throw new Rejected("BODY_CONTAINMENT", candidateIndex); }
        // The parser must identify exactly the supplied body wrapper. Neither
        // comments nor delimiters may change which declaration owns the body.
        int opening = prefix.length() - 2;
        int closing = prefix.length() + body.length() + 2;
        if (range[0] != opening || range[1] != closing
                || !source.substring(0, range[0] + 2).equals(prefix)
                || !source.substring(range[1] - 2).equals(suffix))
            throw new Rejected("BODY_CONTAINMENT", candidateIndex);
        if (BehaviorFeedback.hasUnsafeDependencies(world, selected))
            throw new Rejected("UNSUPPORTED_DEPENDENCY", candidateIndex);
        return world;
    }

    static boolean bundledDependencies(CompModule root) {
        String prefix = Util.jarPrefix();
        try {
            URL alloyArchive = CompUtil.class.getProtectionDomain().getCodeSource().getLocation();
            for (CompModule imported : root.getAllReachableModules()) {
                if (imported == root) continue;
                String filename = imported.pos().filename;
                // Alloy's official marker makes Util.readAll use JAR resources,
                // unlike a path beside its temporary generated root module.
                if (filename == null || !filename.startsWith(prefix)) return false;
                String name = filename.substring(prefix.length()).replace('\\', '/');
                if (!name.matches("models/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_.-]+\\.als")) return false;
                URL resource = CompUtil.class.getClassLoader().getResource(name);
                if (resource == null || !resource.getProtocol().equals("jar")) return false;
                if (!(resource.openConnection() instanceof JarURLConnection archive)
                        || !archive.getJarFileURL().toURI().equals(alloyArchive.toURI())) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static A4Solution solve(CompModule world, Expr expression, int scope) throws Err {
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        // The installed Alloy constructor's final integer is "expects", not
        // a string scope. Explicit trace bounds avoid relying on defaults.
        Command unboundedTrace = new Command(false, scope, 5, scope, expression);
        boolean temporal = CompUtil.isTemporalModel(world.getAllReachableSigs(), unboundedTrace);
        // Alloy rejects a steps scope on a static model, whose single state is
        // already inside the declared 1..10 profile. Temporal queries receive
        // explicit bounds rather than translator defaults.
        Command command = new Command(null, null, "private-import-validation", false,
                scope, 5, scope, temporal ? 1 : -1, temporal ? 10 : -1, -1,
                null, null, expression, null);
        if (command.overall != scope || command.bitwidth != 5 || command.maxseq != scope
                || command.minprefix != (temporal ? 1 : -1) || command.maxprefix != (temporal ? 10 : -1))
            throw new IllegalStateException();
        A4Solution result = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, world.getAllReachableSigs(),
                command, options);
        if (result == null) throw new IllegalStateException();
        return result;
    }

    private static List<String> bodies(JSONArray values) {
        if (values.length() > MAX_REFERENCES) throw new IllegalArgumentException();
        List<String> result = new ArrayList<>();
        for (Object value : values) result.add(text(value, MAX_BODY_BYTES, false));
        return result;
    }

    private static String text(Object value, int maximum, boolean allowEmpty) {
        if (!(value instanceof String text) || !allowEmpty && text.isBlank() || text.indexOf('\0') >= 0
                || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
                || text.getBytes(StandardCharsets.UTF_8).length > maximum) throw new IllegalArgumentException();
        return text;
    }

    /** Alloy comments/strings do not contribute delimiters to the body stack. */
    private static boolean contained(String body) {
        ArrayDeque<Character> stack = new ArrayDeque<>();
        boolean line = false, block = false, string = false, escaped = false;
        for (int index = 0; index < body.length(); index++) {
            char current = body.charAt(index);
            char next = index + 1 < body.length() ? body.charAt(index + 1) : '\0';
            if (line) { if (current == '\n' || current == '\r') line = false; continue; }
            if (block) { if (current == '*' && next == '/') { block = false; index++; } continue; }
            if (string) {
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') string = false;
                continue;
            }
            if (current == '/' && next == '/' || current == '-' && next == '-') { line = true; index++; }
            else if (current == '/' && next == '*') { block = true; index++; }
            else if (current == '"') string = true;
            else if (current == '(' || current == '[' || current == '{') stack.push(current);
            else if (current == ')' || current == ']' || current == '}') {
                if (stack.isEmpty()) return false;
                char opening = stack.pop();
                if (current == ')' && opening != '(' || current == ']' && opening != '['
                        || current == '}' && opening != '{') return false;
            }
        }
        return stack.isEmpty() && !block && !string;
    }

    private static JSONObject rejected(String code, int candidateIndex) {
        JSONObject result = new JSONObject().put("status", "rejected").put("code", code);
        if (candidateIndex >= 0) result.put("candidateIndex", candidateIndex);
        return result;
    }

    private static final class Rejected extends Exception {
        final String code;
        final int candidateIndex;
        Rejected(String code, int candidateIndex) { this.code = code; this.candidateIndex = candidateIndex; }
    }
}
