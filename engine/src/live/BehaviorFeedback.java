package live;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.ast.*;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.*;
import is.fivefivefive.ACGN.learn.Hyperparams;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Bounded, fact-aware adaptation of ACGN Rewarder with private-source-free witnesses. */
public final class BehaviorFeedback {
    private static final int MAX_INPUT_BYTES = 1_048_576;
    private static final int SCOPE = Hyperparams.SCOPE;
    private static final int POOL_SIZE = Hyperparams.POOL_SIZE;
    private static final int EXAMPLES = 3;
    private static final int MAX_STATES = 10;
    private static final int MAX_SIGNATURES = 128;
    private static final int MAX_RELATIONS = 128;
    private static final int MAX_ATOMS_PER_SIGNATURE = 128;
    private static final int MAX_TUPLES_PER_RELATION = 512;
    private static final int MAX_ARITY = 8;
    private static final int MAX_LABEL = 256;
    private static final int MAX_TUPLES = 2048;
    private BehaviorFeedback() { }

    public static void main(String[] args) throws Exception {
        PrintStream wire = System.out;
        System.setOut(new PrintStream(OutputStream.nullOutputStream()));
        System.setErr(new PrintStream(OutputStream.nullOutputStream()));
        JSONObject response;
        try {
            byte[] input = System.in.readNBytes(MAX_INPUT_BYTES + 1);
            response = input.length > MAX_INPUT_BYTES
                    ? failure("invalid_request", "REQUEST_TOO_LARGE")
                    : evaluate(new JSONObject(new String(input, StandardCharsets.UTF_8)));
        } catch (Throwable error) {
            response = failure("engine_error", "BEHAVIOR_FAILURE");
        }
        wire.println(response.toString());
    }

    public static JSONObject evaluate(JSONObject request) {
        String studentSource, oracleSource, studentBody, predicate;
        try {
            studentSource = required(request, "studentSource");
            oracleSource = required(request, "oracleSource");
            studentBody = request.getString("studentBody");
            predicate = required(request, "predicate");
            if (!predicate.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            return failure("invalid_request", "INVALID_REQUEST");
        }
        CompModule learner;
        Func learnerTarget;
        try {
            // Do not let a learner call resolve to a private oracle predicate.
            learner = CompUtil.parseEverything_fromString(A4Reporter.NOP, studentSource);
            learnerTarget = selected(learner, predicate);
            if (hasUnsafeDependencies(learner, learnerTarget))
                return failure("unsupported", "RECURSIVE_OR_CONTEXT_DEPENDENCY");
        } catch (Err error) {
            return failure("invalid", "INVALID_LEARNER");
        } catch (RuntimeException error) {
            return failure("unsupported", "UNSUPPORTED_PREDICATE");
        }
        try {
            CompModule world = CompUtil.parseEverything_fromString(A4Reporter.NOP, oracleSource);
            Func oracleTarget = selected(world, predicate);
            if (hasUnsafeDependencies(world, oracleTarget))
                return failure("unsupported", "RECURSIVE_OR_CONTEXT_DEPENDENCY");
            int[] learnerBody = bodyRange(learnerTarget, studentSource);
            int[] oracleBody = bodyRange(oracleTarget, oracleSource);
            if (!studentSource.substring(learnerBody[0] + 1, learnerBody[1] - 1).strip().equals(studentBody.strip())
                    || !maskedBody(studentSource, learnerBody).equals(maskedBody(oracleSource, oracleBody)))
                return failure("invalid_request", "CONTEXT_MISMATCH");

            // Shared helpers cannot depend on the selected predicate. Parsing the
            // independently checked learner body here therefore shares signature
            // identity without changing the meaning of any learner function call.
            Expr student = CompUtil.parseOneExpression_fromString(world, "{\n" + studentBody + "\n}");
            Expr oracle = oracleTarget.call();
            Expr facts = world.getAllReachableFacts();
            Set<String> oracleStrings = stringConstants(oracle);
            oracleStrings.addAll(stringConstants(facts));
            for (Sig sig : world.getAllReachableUserDefinedSigs()) {
                for (Expr fact : sig.getFacts()) oracleStrings.addAll(stringConstants(fact));
                for (Sig.Field field : sig.getFields()) oracleStrings.addAll(stringConstants(field.decl().expr));
            }
            // Rewarder's sample universe comes from oracle commands. A new
            // learner-only String atom cannot be evaluated in those solutions;
            // do not silently treat it as absent or change the sampled universe.
            if (!oracleStrings.containsAll(stringConstants(student)))
                return failure("unsupported", "STUDENT_STRING_UNIVERSE");
            A4Solution positive = solve(world, facts.and(oracle));
            A4Solution negative = solve(world, facts.and(oracle.not()));
            int[] positiveCounts = sample(positive, student, true);
            int[] negativeCounts = sample(negative, student, false);

            String[] ids = {"both", "undercoverage", "overcoverage", "neither"};
            boolean[] oracleValues = {true, true, false, false};
            boolean[] studentValues = {true, false, true, false};
            JSONArray categories = new JSONArray();
            for (int i = 0; i < ids.length; i++) {
                Expr category = facts.and(oracleValues[i] ? oracle : oracle.not())
                        .and(studentValues[i] ? student : student.not());
                categories.put(category(world, learner, category, ids[i], oracleValues[i], studentValues[i]));
            }
            int semanticCounterexamples = 0;
            boolean available = positiveCounts[0] > 0 && negativeCounts[0] > 0;
            if (available && positiveCounts[0] == positiveCounts[1] && negativeCounts[0] == negativeCounts[1]) {
                // Like Rewarder, count each direction once only after every
                // sampled classification succeeds. Here facts apply to this
                // check as well as the sampling, per the portal contract.
                if (categories.getJSONObject(1).getString("status").equals("sat")) semanticCounterexamples++;
                if (categories.getJSONObject(2).getString("status").equals("sat")) semanticCounterexamples++;
            }
            Object score = JSONObject.NULL;
            if (available) {
                double reward = (double) (positiveCounts[1] * negativeCounts[1])
                        / (positiveCounts[0] * negativeCounts[0] + semanticCounterexamples);
                score = Math.round(reward * 1000.0) / 1000.0;
            }
            return new JSONObject().put("status", "ok").put("metric", "acgn-reward")
                    .put("score", score).put("scoreStatus", available ? "ok" : "unavailable")
                    .put("scoreReason", available ? "OK" : positiveCounts[0] == 0
                            ? "ORACLE_POSITIVE_UNSAT" : "ORACLE_NEGATIVE_UNSAT")
                    .put("scope", new JSONObject().put("overall", SCOPE).put("bitwidth", SCOPE)
                            .put("maxSequence", SCOPE).put("poolSize", POOL_SIZE).put("minTrace", 1)
                            .put("maxTrace", 10).put("moduleFacts", true))
                    .put("sampling", new JSONObject().put("positiveTested", positiveCounts[0])
                            .put("positiveAccepted", positiveCounts[1]).put("negativeTested", negativeCounts[0])
                            .put("negativeRejected", negativeCounts[1]).put("semanticCounterexamples", semanticCounterexamples))
                    .put("categories", categories);
        } catch (Throwable error) {
            return failure("engine_error", "BEHAVIOR_FAILURE");
        }
    }

    private static A4Solution solve(CompModule world, Expr expression) throws Err {
        A4Options options = new A4Options();
        options.solver = A4Options.SatSolver.SAT4J;
        A4Solution solution = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP, world.getAllReachableSigs(),
                new Command(true, SCOPE, SCOPE, SCOPE, expression), options);
        if (solution == null) throw new IllegalStateException();
        return solution;
    }

    private static int[] sample(A4Solution solution, Expr student, boolean expected) throws Err {
        int tested = 0, matched = 0;
        while (tested < POOL_SIZE && solution != null && solution.satisfiable()) {
            Object evaluation = solution.eval(student);
            if (!(evaluation instanceof Boolean)) throw new IllegalArgumentException();
            if (((Boolean) evaluation) == expected) matched++;
            tested++;
            solution = solution.next();
        }
        return new int[] {tested, matched};
    }

    private static JSONObject category(CompModule world, CompModule learner, Expr expression, String id,
            boolean oracle, boolean student) throws Err {
        A4Solution solution = solve(world, expression);
        JSONArray instances = new JSONArray();
        while (instances.length() < EXAMPLES && solution != null && solution.satisfiable()) {
            instances.put(instance(solution, learner));
            solution = solution.next();
        }
        return new JSONObject().put("id", id).put("oracle", oracle).put("student", student)
                .put("status", instances.isEmpty() ? "unsat" : "sat")
                .put("enumerationComplete", solution == null || !solution.satisfiable())
                .put("instances", instances);
    }

    /** Only learner-declared relation labels and concrete tuples cross the wire. */
    private static JSONObject instance(A4Solution solution, CompModule learner) {
        Set<String> allowedSignatures = new java.util.HashSet<>();
        Set<String> allowedFields = new java.util.HashSet<>();
        for (Sig sig : learner.getAllReachableUserDefinedSigs()) {
            allowedSignatures.add(sig.label);
            for (Sig.Field field : sig.getFields()) allowedFields.add(sig.label + "/" + field.label);
        }
        List<Sig> signatures = new ArrayList<>();
        for (Sig sig : solution.getAllReachableSigs()) {
            if (!sig.builtin && allowedSignatures.contains(sig.label)) signatures.add(sig);
        }
        signatures.sort(Comparator.comparing(sig -> sig.label));
        int traceLength = solution.getTraceLength();
        boolean truncated = traceLength > MAX_STATES || signatures.size() > MAX_SIGNATURES;
        JSONArray states = new JSONArray();
        java.util.Map<String, String> stringAtoms = new java.util.LinkedHashMap<>();
        for (int state = 0; state < Math.min(traceLength, MAX_STATES); state++) {
            JSONArray sigRows = new JSONArray(), relationRows = new JSONArray();
            int tuplesRemaining = MAX_TUPLES;
            for (Sig sig : signatures.subList(0, Math.min(signatures.size(), MAX_SIGNATURES))) {
                if (publicLabel(sig.label).length() > MAX_LABEL) { truncated = true; continue; }
                JSONArray atoms = new JSONArray();
                for (A4Tuple tuple : solution.eval(sig, state)) {
                    if (tuplesRemaining-- <= 0 || atoms.length() >= MAX_ATOMS_PER_SIGNATURE) { truncated = true; break; }
                    if (displayAtom(tuple, 0, stringAtoms).length() > MAX_LABEL) { truncated = true; continue; }
                    atoms.put(displayAtom(tuple, 0, stringAtoms));
                }
                sigRows.put(new JSONObject().put("label", publicLabel(sig.label)).put("atoms", atoms));
                List<Sig.Field> fields = new ArrayList<>();
                for (Sig.Field field : sig.getFields()) {
                    if (allowedFields.contains(sig.label + "/" + field.label)) fields.add(field);
                }
                fields.sort(Comparator.comparing(field -> field.label));
                for (Sig.Field field : fields) {
                    if (relationRows.length() >= MAX_RELATIONS) { truncated = true; break; }
                    String label = publicLabel(sig.label) + "." + field.label;
                    A4TupleSet values = solution.eval(field, state);
                    if (label.length() > MAX_LABEL || values.arity() > MAX_ARITY) { truncated = true; continue; }
                    JSONArray tuples = new JSONArray();
                    for (A4Tuple tuple : values) {
                        if (tuplesRemaining-- <= 0 || tuples.length() >= MAX_TUPLES_PER_RELATION) { truncated = true; break; }
                        JSONArray columns = new JSONArray();
                        boolean omitted = false;
                        for (int column = 0; column < tuple.arity(); column++) {
                            String atom = displayAtom(tuple, column, stringAtoms);
                            if (atom.length() > MAX_LABEL) { omitted = true; break; }
                            columns.put(atom);
                        }
                        if (omitted) truncated = true;
                        else tuples.put(columns);
                    }
                    relationRows.put(new JSONObject().put("label", label)
                            .put("arity", values.arity()).put("tuples", tuples));
                }
            }
            states.put(new JSONObject().put("index", state).put("signatures", sigRows).put("relations", relationRows));
        }
        return new JSONObject().put("traceLength", traceLength).put("loopState", solution.getLoopState())
                .put("states", states).put("truncated", truncated).put("stringsAnonymized", !stringAtoms.isEmpty());
    }

    private static String displayAtom(A4Tuple tuple, int column, java.util.Map<String, String> stringAtoms) {
        String atom = tuple.atom(column);
        // Literal values can originate exclusively in the private oracle. Keep
        // their identity stable through the trace, without disclosing contents.
        if (tuple.sig(column) == Sig.STRING)
            return stringAtoms.computeIfAbsent(atom, ignored -> "String$" + stringAtoms.size());
        return atom;
    }

    private static String publicLabel(String label) {
        return label.startsWith("this/") ? label.substring(5) : label;
    }

    private static Func selected(CompModule module, String name) {
        Func result = null;
        for (Func function : module.getAllFunc()) {
            if (function.label.equals(name) || function.label.equals("this/" + name)) {
                if (result != null || !function.isPred || function.count() != 0) throw new IllegalArgumentException();
                result = function;
            }
        }
        if (result == null) throw new IllegalArgumentException();
        return result;
    }

    private static int[] bodyRange(Func function, String source) {
        int[] range = function.getBody().span().toStartEnd(source);
        if (range == null || range.length != 2 || range[0] < 0 || range[1] > source.length()
                || source.charAt(range[0]) != '{' || source.charAt(range[1] - 1) != '}')
            throw new IllegalArgumentException();
        return range;
    }

    private static String maskedBody(String source, int[] range) {
        return source.substring(0, range[0]) + "{}" + source.substring(range[1]);
    }

    private static boolean hasUnsafeDependencies(CompModule module, Func target) {
        if (cyclic(target, new IdentityHashMap<>())) return true;
        for (CompModule reachable : module.getAllReachableModules()) {
            for (Func helper : reachable.getAllFunc()) {
                if (helper != target && dependsOn(helper, target, identitySet())) return true;
            }
            for (edu.mit.csail.sdg.alloy4.Pair<String, Expr> fact : reachable.getAllFacts()) {
                if (dependsOn(fact.b, target, identitySet())) return true;
            }
            for (Sig sig : reachable.getAllSigs()) {
                for (Expr fact : sig.getFacts()) if (dependsOn(fact, target, identitySet())) return true;
                for (Sig.Field field : sig.getFields()) {
                    if (dependsOn(field.decl().expr, target, identitySet())) return true;
                }
            }
        }
        return false;
    }

    private static Set<Func> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    private static boolean cyclic(Func function, IdentityHashMap<Func, Integer> marks) {
        Integer mark = marks.get(function);
        if (mark != null) return mark == 1;
        marks.put(function, 1);
        for (Func callee : calls(function)) if (cyclic(callee, marks)) return true;
        marks.put(function, 2);
        return false;
    }

    private static boolean dependsOn(Expr expression, Func target, Set<Func> seen) {
        for (Func callee : calls(expression)) {
            if (callee == target) return true;
            if (seen.add(callee) && dependsOn(callee, target, seen)) return true;
        }
        return false;
    }

    private static boolean dependsOn(Func function, Func target, Set<Func> seen) {
        for (Func callee : calls(function)) {
            if (callee == target) return true;
            if (seen.add(callee) && dependsOn(callee, target, seen)) return true;
        }
        return false;
    }

    private static List<Func> calls(Func function) {
        List<Func> functions = calls(function.getBody());
        for (Decl decl : function.decls) collectCalls(decl.expr, functions);
        if (function.returnDecl != null) collectCalls(function.returnDecl, functions);
        return functions;
    }

    /** Traverse expression children, never declaration-owned Sig/Field objects. */
    private static List<Func> calls(Expr expression) {
        List<Func> functions = new ArrayList<>();
        collectCalls(expression, functions);
        return functions;
    }

    private static void collectCalls(Expr expression, List<Func> functions) {
        walk(expression, node -> { if (node instanceof ExprCall call) functions.add(call.fun); });
    }

    private static Set<String> stringConstants(Expr expression) {
        Set<String> strings = new java.util.HashSet<>();
        collectStrings(expression, strings, identitySet());
        return strings;
    }

    private static void collectStrings(Expr expression, Set<String> strings, Set<Func> seen) {
        walk(expression, node -> {
            if (node instanceof ExprConstant constant && constant.op == ExprConstant.Op.STRING)
                strings.add(constant.string);
        });
        for (Func function : calls(expression)) {
            if (seen.add(function)) {
                collectStrings(function.getBody(), strings, seen);
                for (Decl decl : function.decls) collectStrings(decl.expr, strings, seen);
                if (function.returnDecl != null) collectStrings(function.returnDecl, strings, seen);
            }
        }
    }

    private static void walk(Expr expression, java.util.function.Consumer<Expr> consumer) {
        consumer.accept(expression);
        if (expression instanceof ExprCall call) {
            for (Expr arg : call.args) walk(arg, consumer);
        } else if (expression instanceof ExprUnary unary) walk(unary.sub, consumer);
        else if (expression instanceof ExprBinary binary) {
            walk(binary.left, consumer); walk(binary.right, consumer);
        } else if (expression instanceof ExprList list) {
            for (Expr arg : list.args) walk(arg, consumer);
        } else if (expression instanceof ExprITE ite) {
            walk(ite.cond, consumer); walk(ite.left, consumer); walk(ite.right, consumer);
        } else if (expression instanceof ExprLet let) {
            walk(let.expr, consumer); walk(let.sub, consumer);
        } else if (expression instanceof ExprQt quantified) {
            for (Decl decl : quantified.decls) walk(decl.expr, consumer);
            walk(quantified.sub, consumer);
        }
    }

    private static String required(JSONObject request, String key) {
        String value = request.getString(key);
        if (value.isBlank()) throw new IllegalArgumentException();
        return value;
    }

    private static JSONObject failure(String status, String code) {
        return new JSONObject().put("status", status).put("diagnostics", new JSONArray().put(
                new JSONObject().put("code", code).put("message", "Behavioral analysis is unavailable for this request.")));
    }
}
