package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONObject;
import org.json.JSONArray;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Expr;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.A4Solution;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;

/** Profile-bound feedback; sample agreement and certificate replay are distinct. */
public final class RepairEvaluator {
    public static final int DEFAULT_CAPACITY = 100;
    private final RepairTask task;
    private final RepairTask.EvaluatorContext model;
    private final Expr context;
    private final int capacity;
    private final List<Entry> positive = new ArrayList<>();
    private final List<Entry> negative = new ArrayList<>();
    private final RepairFeedback.Answer contextAnswer;
    private int version;
    private int solverCalls;
    private boolean positiveAbsent;
    private boolean negativeAbsent;

    private record Entry(String key, A4Solution instance) { }
    public record Snapshot(int version, String hash, List<Entry> positive, List<Entry> negative) {
        public Snapshot { positive = List.copyOf(positive); negative = List.copyOf(negative); }
    }
    public record Score(int positiveCount, int negativeCount, int positiveErrors, int negativeErrors,
                        int potential, int bankVersion, String bankHash) {
        public JSONObject json() {
            return new JSONObject().put("positiveCount", positiveCount).put("negativeCount", negativeCount)
                    .put("positiveErrors", positiveErrors).put("negativeErrors", negativeErrors)
                    .put("potential", potential).put("bankVersion", bankVersion).put("bankHash", bankHash);
        }
    }
    public record Validation(RepairFeedback.Verdict verdict, RepairFeedback.Answer extra,
                             RepairFeedback.Answer missing, int oldVersion, int newVersion) {
        public JSONObject json() {
            return new JSONObject().put("verdict", verdict.name()).put("extra", extra.name())
                    .put("missing", missing.name()).put("oldVersion", oldVersion).put("newVersion", newVersion)
                    .put("certificateReplay", "NOT_REQUESTED");
        }
    }

    public RepairEvaluator(RepairTask task, String oracle, int capacity, int seedsPerClass) {
        this(task, oracle, capacity, seedsPerClass, false);
    }

    /** Explicit-query mode: no target-selected examples precede the first query. */
    public static RepairEvaluator withoutPrefetch(RepairTask task, String oracle) {
        return new RepairEvaluator(task, oracle, 8, 0, true);
    }

    private RepairEvaluator(RepairTask task, String oracle, int capacity, int seedsPerClass,
                            boolean queryOnly) {
        if (capacity < 1 || capacity > 1000 || seedsPerClass < (queryOnly ? 0 : 1)
                || seedsPerClass > capacity) {
            throw new IllegalArgumentException("Invalid retained-bank bounds");
        }
        this.task = task;
        this.capacity = capacity;
        model = task.evaluatorContext(oracle);
        context = model.module().getAllReachableFacts().and(model.command().formula);
        A4Solution feasible = solve(context);
        contextAnswer = answer(feasible);
        if (contextAnswer == RepairFeedback.Answer.SAT && seedsPerClass > 0) {
            positiveAbsent = seed(true, seedsPerClass);
            negativeAbsent = seed(false, seedsPerClass);
        }
    }

    private boolean seed(boolean label, int count) {
        A4Solution solution = solve(context.and(label ? model.oracle() : model.oracle().not()));
        if (!solution.satisfiable()) return true;
        for (int i = 0; i < count && solution.satisfiable(); i++) {
            retain(label, solution);
            if (i + 1 < count) { solverCalls++; solution = solution.next(); }
            if (solution == null) throw new IllegalStateException("Solver enumeration returned null");
        }
        return false;
    }

    public Snapshot snapshot() {
        List<String> keys = new ArrayList<>();
        positive.forEach(e -> keys.add("positive:" + e.key));
        negative.forEach(e -> keys.add("negative:" + e.key));
        return new Snapshot(version, RepairTask.digest(task.id(), task.semanticProfile().fingerprint(),
                String.join("\n", keys)), positive, negative);
    }

    public Score score(RepairEnvironment.State candidate, Snapshot snapshot) {
        requireOwner(candidate);
        if (snapshot.version != version || !snapshot.hash.equals(snapshot().hash)) {
            throw new IllegalArgumentException("Rescore after a bank change; stale snapshot refused");
        }
        Expr expression = expression(candidate);
        int positiveErrors = 0;
        int negativeErrors = 0;
        for (Entry entry : snapshot.positive) if (!evaluate(entry.instance, expression)) positiveErrors++;
        for (Entry entry : snapshot.negative) if (evaluate(entry.instance, expression)) negativeErrors++;
        int total = 0;
        int classes = 0;
        if (!snapshot.positive.isEmpty()) {
            total += 1000 * (snapshot.positive.size() - positiveErrors) / snapshot.positive.size(); classes++;
        }
        if (!snapshot.negative.isEmpty()) {
            total += 1000 * (snapshot.negative.size() - negativeErrors) / snapshot.negative.size(); classes++;
        }
        return new Score(snapshot.positive.size(), snapshot.negative.size(), positiveErrors, negativeErrors,
                classes == 0 ? 0 : total / classes, snapshot.version, snapshot.hash);
    }

    public Validation validate(RepairEnvironment.State candidate) {
        return validate(candidate, null);
    }

    public record Query(Validation validation, JSONArray witnesses) { }

    // Private realization of a label already selected from actual returned worlds.
    Expr returnedSymbol(String label) {
        for (Sig sig : model.module().getAllReachableSigs()) {
            if (sig.label.equals(label)) return sig;
            for (Sig.Field field : sig.getFields()) if ((sig.label + "." + field.label).equals(label)) return field;
        }
        throw new IllegalArgumentException("RETURNED_SYMBOL_NOT_RESOLVED");
    }

    /** Worlds here come from this query, not the retained scoring bank. */
    public Query query(RepairEnvironment.State candidate) {
        JSONArray returned = new JSONArray();
        return new Query(validate(candidate, returned), returned);
    }

    private Validation validate(RepairEnvironment.State candidate, JSONArray returned) {
        requireOwner(candidate);
        int oldVersion = version;
        if (contextAnswer != RepairFeedback.Answer.SAT) {
            return new Validation(RepairFeedback.Verdict.VACUOUS, RepairFeedback.Answer.UNKNOWN,
                    RepairFeedback.Answer.UNKNOWN, oldVersion, version);
        }
        Expr expression = expression(candidate);
        A4Solution extra = solve(context.and(expression).and(model.oracle().not()));
        A4Solution missing = solve(context.and(model.oracle()).and(expression.not()));
        RepairFeedback.Verdict verdict = RepairFeedback.classify(true, contextAnswer, answer(extra), answer(missing));
        if (extra.satisfiable()) retain(false, extra);
        if (missing.satisfiable()) retain(true, missing);
        if (returned != null) {
            if (extra.satisfiable()) returned.put(witness(extra, expression, false));
            if (missing.satisfiable()) returned.put(witness(missing, expression, true));
        }
        return new Validation(verdict, answer(extra), answer(missing), oldVersion, version);
    }

    private JSONObject witness(A4Solution instance, Expr candidate, boolean expected) {
        JSONObject types = new JSONObject();
        for (Sig sig : model.module().getAllReachableSigs()) {
            types.put(sig.label, witnessType(sig.type()));
            for (Sig.Field field : sig.getFields()) {
                types.put(sig.label + "." + field.label, witnessType(field.type()));
            }
        }
        JSONArray states = new JSONArray();
        int length = Math.max(1, instance.getTraceLength());
        for (int time = 0; time < length; time++) {
            JSONObject relations = new JSONObject();
            for (Sig sig : model.module().getAllReachableSigs()) {
                relations.put(sig.label, tuples(instance.eval(sig, time)));
                for (Sig.Field field : sig.getFields()) {
                    relations.put(sig.label + "." + field.label, tuples(instance.eval(field, time)));
                }
            }
            states.put(relations);
        }
        boolean actual = evaluate(instance, candidate);
        if (actual == expected) throw new IllegalStateException("Query witness is not a counterexample");
        return new JSONObject().put("expected", expected).put("actual", actual)
                .put("states", states).put("loop", instance.getLoopState()).put("types", types);
    }

    private static JSONArray witnessType(edu.mit.csail.sdg.ast.Type type) {
        JSONArray products = new JSONArray();
        for (var product : type.fold()) {
            JSONArray columns = new JSONArray();
            for (Sig sig : product) columns.put(sig.label);
            products.put(columns);
        }
        return products;
    }

    public JSONObject bankStatus() {
        Snapshot snapshot = snapshot();
        return new JSONObject().put("version", version).put("hash", snapshot.hash)
                .put("capacityPerClass", capacity).put("positiveCount", positive.size())
                .put("negativeCount", negative.size()).put("positiveAbsent", positiveAbsent)
                .put("negativeAbsent", negativeAbsent).put("context", contextAnswer.name())
                .put("solverCalls", solverCalls).put("profile", task.semanticProfile().fingerprint());
    }

    /** Concrete labelled observations, not oracle text or a certificate. */
    public JSONArray witnesses() {
        JSONArray result = new JSONArray();
        for (boolean label : new boolean[] { true, false }) {
            for (Entry entry : label ? positive : negative) {
                JSONObject relations = new JSONObject();
                for (Sig sig : model.module().getAllReachableSigs()) {
                    if (sig.builtin) continue;
                    relations.put(sig.label, entry.instance.eval(sig).toString());
                    for (Sig.Field field : sig.getFields()) {
                        relations.put(sig.label + "." + field.label, entry.instance.eval(field).toString());
                    }
                }
                result.put(new JSONObject().put("key", entry.key).put("expected", label)
                        .put("relations", relations));
            }
        }
        return result;
    }

    /** Exact user-signature/field tuples for the static learning projection. */
    public JSONArray witnessTuples(RepairEnvironment.State candidate) {
        requireOwner(candidate);
        Expr expression = expression(candidate);
        JSONArray result = new JSONArray();
        for (boolean label : new boolean[] { true, false }) {
            for (Entry entry : label ? positive : negative) {
                JSONObject relations = new JSONObject();
                for (Sig sig : model.module().getAllReachableSigs()) {
                    if (sig.builtin) continue;
                    relations.put(sig.label, tuples(entry.instance.eval(sig)));
                    for (Sig.Field field : sig.getFields()) {
                        relations.put(sig.label + "." + field.label, tuples(entry.instance.eval(field)));
                    }
                }
                result.put(new JSONObject().put("expected", label)
                        .put("actual", evaluate(entry.instance, expression)).put("relations", relations));
            }
        }
        return result;
    }

    private static JSONArray tuples(Object value) {
        if (!(value instanceof edu.mit.csail.sdg.translator.A4TupleSet set)) {
            throw new IllegalStateException("Expected relational instance value");
        }
        JSONArray result = new JSONArray();
        for (var tuple : set) {
            JSONArray row = new JSONArray();
            for (int i = 0; i < tuple.arity(); i++) row.put(tuple.atom(i));
            result.put(row);
        }
        return result;
    }

    private void requireOwner(RepairEnvironment.State candidate) {
        if (candidate.task != task) throw new IllegalArgumentException("Candidate/evaluator context mismatch");
    }

    private Expr expression(RepairEnvironment.State candidate) {
        Expr expression = CompUtil.parseOneExpression_fromString(model.module(), "{" + candidate.body() + "}");
        if (!expression.type().is_bool || !expression.errors.isEmpty()) {
            throw new IllegalStateException("Accepted candidate is not a formula in the evaluator context");
        }
        return expression;
    }

    private A4Solution solve(Expr expression) {
        A4Options options = new A4Options();
        options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        solverCalls++;
        A4Solution solution = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                model.module().getAllReachableSigs(), model.command().change(expression), options);
        if (solution == null) throw new IllegalStateException("Solver returned null, not UNSAT");
        return solution;
    }

    private static RepairFeedback.Answer answer(A4Solution solution) {
        return solution.satisfiable() ? RepairFeedback.Answer.SAT : RepairFeedback.Answer.UNSAT;
    }

    private static boolean evaluate(A4Solution solution, Expr expression) {
        Object value = solution.eval(expression);
        if (!(value instanceof Boolean)) throw new IllegalStateException("Instance evaluation did not return Boolean");
        return (Boolean) value;
    }

    private void retain(boolean label, A4Solution solution) {
        if (!solution.satisfiable() || evaluate(solution, model.oracle()) != label
                || !evaluate(solution, context)) throw new IllegalStateException("Invalid context/label witness");
        List<String> valuations = new ArrayList<>();
        for (Sig signature : model.module().getAllReachableSigs()) {
            valuations.add(signature.label + "=" + solution.eval(signature));
            for (Sig.Field field : signature.getFields()) valuations.add(signature.label + "." + field.label + "=" + solution.eval(field));
        }
        valuations.sort(Comparator.naturalOrder());
        String key = RepairTask.digest(String.join("\n", valuations));
        List<Entry> bank = label ? positive : negative;
        if (bank.stream().anyMatch(e -> e.key.equals(key))) return;
        bank.add(0, new Entry(key, solution));
        if (bank.size() > capacity) bank.remove(bank.size() - 1);
        version++;
    }
}
