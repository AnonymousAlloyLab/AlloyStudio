package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.Err;
import edu.mit.csail.sdg.alloy4.ErrorSyntax;
import edu.mit.csail.sdg.alloy4.ErrorType;
import edu.mit.csail.sdg.ast.ExprQt;

/** Migration controller. Not yet a complete feedback-intent-path-v1 training runtime. */
public final class FeedbackRepairSession {
    public enum Control { BEGIN, COPY, SYMBOL, VARIABLE, DRAFT_VARIABLE, CONSTANT, OPERATOR,
        QUANTIFIER, COMMIT, DELETE, ABORT_DRAFT, VALIDATE, STOP, ABSTAIN, DECLARATION, FEEDBACK_SYMBOL }
    public enum Status { READY, APPLIED, REJECTED, STALE, COUNTEREXAMPLE,
        SOLVED_WITHIN_PROFILE, VACUOUS, UNRESOLVED, ABSTAINED, CLOSED, EXHAUSTED }
    public static final String VERSION = "feedback-executor-canonical-v4";
    private final RepairTask task;
    private final String oracle;
    private final RepairEnvironment environment;
    private final JSONObject initial;
    private final List<JSONObject> responses = new ArrayList<>();
    private final JSONArray history = new JSONArray();
    private RepairEvaluator evaluator;
    private RepairEnvironment.State state;
    private FeedbackSourceGraph graph;
    private FeedbackDraft draft;
    private int revision, remaining;
    private boolean closed;
    private Status status = Status.READY;
    private String lastFailure = "";
    private final boolean localRecovery;
    private final int queryLimit;
    private int queries, preparationAttempts;
    private record FailedAttempt(int revision, int site, String candidate) { }
    // Task, parser, theory and profile are immutable for this session. Exact source
    // equality (not a digest alone) completes the retry key.
    private final java.util.Set<FailedAttempt> stableFailures = new java.util.HashSet<>();

    public FeedbackRepairSession(RepairTask task, String oracle, int budget) {
        this(task, oracle, budget, 256, false);
    }

    FeedbackRepairSession(RepairTask task, String oracle, int budget, int queryLimit, boolean localRecovery) {
        if (budget < 1 || budget > 256) throw new IllegalArgumentException("DECISION_BUDGET_INVALID");
        if (queryLimit < 1 || queryLimit > 256) throw new IllegalArgumentException("QUERY_BUDGET_INVALID");
        this.queryLimit = queryLimit; this.localRecovery = localRecovery;
        this.task = task; this.oracle = oracle; remaining = budget;
        environment = new RepairEnvironment(task, budget);
        state = environment.initial();
        graph = new FeedbackSourceGraph(state);
        initial = graph.json();
    }

    public JSONObject observation() {
        JSONObject worlds = FeedbackWorldTables.encode(responses, graph);
        JSONArray draftRows = draft == null ? new JSONArray() : draft.rows();
        JSONArray draftDeclarations = draft == null ? new JSONArray() : draft.declarationRows();
        JSONObject actions = FeedbackActions.choices(graph, draft, feedbackSymbols(), !closed && remaining > 0);
        if (localRecovery) {
            JSONArray controls = actions.getJSONArray("controls");
            for (int i = controls.length() - 1; i >= 0; i--) {
                int code = controls.getJSONArray(i).getInt(0);
                if ((code == Control.VALIDATE.ordinal() || code == Control.STOP.ordinal()) && queries >= queryLimit
                        || code == Control.COMMIT.ordinal() && repeatedDraft()) controls.remove(i);
            }
        }
        int rowCount = graph.rowCount() + draftRows.length() + draftDeclarations.length()
                + history.length() + FeedbackActions.rows(actions);
        for (String key : worlds.keySet()) if (worlds.get(key) instanceof JSONArray a) rowCount += a.length();
        for (String key : initial.keySet()) rowCount += initial.getJSONArray(key).length();
        if (rowCount > FeedbackSourceGraph.MAX_ROWS) throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
        return new JSONObject().put("schema", VERSION).put("initial", new JSONObject(initial.toString()))
                .put("current", graph.json()).put("feedback", worlds).put("draft", draftRows)
                .put("actions", actions)
                .put("draftDeclarations", draftDeclarations)
                .put("history", new JSONArray(history.toString())).put("revision", revision)
                .put("turn", history.length())
                .put("remaining", remaining).put("status", status.ordinal()).put("closed", closed);
    }

    public JSONObject decide(JSONObject decision) {
        if (closed || remaining == 0) return observation();
        remaining--;
        for (String key : decision.keySet()) {
            if (!java.util.Set.of("control", "revision", "turn", "site", "family", "pointer", "operator", "value",
                    "count", "disjoint", "cardinality").contains(key)
                    || !(decision.get(key) instanceof Integer)) {
                status = Status.REJECTED;
                history.put(FeedbackSourceGraph.row(revision, -1, -1, -1, -1, -1, -1, -1, -1, -1));
                return observation();
            }
        }
        int control = decision.optInt("control", -1);
        int requestedRevision = decision.optInt("revision", -1);
        int requestedTurn = decision.optInt("turn", -1);
        int turn = history.length();
        history.put(FeedbackSourceGraph.row(revision, control, decision.optInt("site", -1),
                decision.optInt("family", -1), decision.optInt("pointer", -1),
                decision.optInt("operator", -1), decision.optInt("value", -1),
                decision.optInt("count", -1), decision.optInt("disjoint", -1),
                decision.optInt("cardinality", -1)));
        if (requestedRevision != revision || requestedTurn != turn) { status = Status.STALE; return observation(); }
        status = Status.READY;
        lastFailure = "";
        try {
            if (control < 0 || control >= Control.values().length) throw new IllegalArgumentException("CONTROL_INVALID");
            switch (Control.values()[control]) {
                case BEGIN -> {
                    if (draft != null) throw new IllegalArgumentException("DRAFT_ACTIVE");
                    draft = new FeedbackDraft(graph, decision.getInt("site"),
                            FeedbackDraft.Family.values()[decision.getInt("family")]);
                }
                case COPY -> requireDraft().copy(decision.getInt("pointer"));
                case SYMBOL -> requireDraft().symbol(decision.getInt("pointer"));
                case FEEDBACK_SYMBOL -> {
                    int pointer = decision.getInt("pointer");
                    requireDraft().feedbackSymbol(pointer, feedbackSymbols().get(pointer));
                }
                case VARIABLE -> requireDraft().variable(decision.getInt("pointer"), false);
                case DRAFT_VARIABLE -> requireDraft().variable(decision.getInt("pointer"), true);
                case CONSTANT -> requireDraft().constant(decision.getInt("operator"), decision.optInt("value", 0));
                case OPERATOR -> requireDraft().operator(decision.getInt("operator"));
                case QUANTIFIER -> {
                    int disjoint = decision.getInt("disjoint");
                    if (disjoint != 0 && disjoint != 1) throw new IllegalArgumentException("DISJOINT_INVALID");
                    requireDraft().quantifier(decision.getInt("operator"),
                            decision.getInt("count"), disjoint == 1, decision.getInt("cardinality"));
                }
                case DECLARATION -> {
                    int disjoint = decision.getInt("disjoint");
                    if (disjoint != 0 && disjoint != 1) throw new IllegalArgumentException("DISJOINT_INVALID");
                    requireDraft().declaration(decision.getInt("count"), disjoint == 1, decision.getInt("cardinality"));
                }
                case COMMIT -> commit(requireDraft().site, draft.replacement());
                case DELETE -> {
                    if (draft != null) throw new IllegalArgumentException("DRAFT_ACTIVE");
                    int site = decision.getInt("site"), child = decision.getInt("pointer");
                    var family = FeedbackDraft.Family.values()[decision.getInt("family")];
                    if (family != FeedbackDraft.Family.MATRIX_DELETE && family != FeedbackDraft.Family.BINDING_DELETE
                            && family != FeedbackDraft.Family.TEMPORAL_DELETE) {
                        throw new IllegalArgumentException("DELETE_FAMILY_UNSUPPORTED");
                    }
                    if (family == FeedbackDraft.Family.TEMPORAL_DELETE && !FeedbackSourceGraph.temporal(graph.expressions.get(site))) {
                        throw new IllegalArgumentException("TEMPORAL_SITE_REQUIRED");
                    }
                    if (family == FeedbackDraft.Family.BINDING_DELETE && !(graph.expressions.get(site) instanceof ExprQt)) {
                        throw new IllegalArgumentException("BINDING_SITE_REQUIRED");
                    }
                    if (!graph.children.get(site).contains(child)
                            || !graph.canCopy(child, site)) {
                        throw new IllegalArgumentException("DELETE_SCOPE_MISMATCH");
                    }
                    commit(site, "(" + graph.source(child) + ")");
                }
                case ABORT_DRAFT -> draft = null;
                case VALIDATE, STOP -> {
                    if (draft != null) throw new IllegalArgumentException("DRAFT_ACTIVE");
                    if (queries >= queryLimit) throw new IllegalArgumentException("QUERY_BUDGET_EXHAUSTED");
                    queries++;
                    try {
                        if (evaluator == null) evaluator = RepairEvaluator.withoutPrefetch(task, oracle);
                        RepairEvaluator.Query result = evaluator.query(state);
                        responses.add(new JSONObject().put("revision", revision).put("witnesses", result.witnesses()));
                        status = Status.valueOf(result.validation().verdict().name());
                    } catch (Err inconclusive) {
                        responses.add(new JSONObject().put("revision", revision).put("witnesses", new JSONArray()));
                        status = Status.UNRESOLVED;
                        inconclusive.printStackTrace(System.err);
                    }
                    closed = status == Status.SOLVED_WITHIN_PROFILE || Control.values()[control] == Control.STOP;
                }
                case ABSTAIN -> { draft = null; closed = true; status = Status.ABSTAINED; }
            }
        } catch (RepairTask.UnsupportedInput | Err invalid) {
            status = Status.REJECTED;
            lastFailure = invalid.toString();
        } catch (IllegalArgumentException | IndexOutOfBoundsException | org.json.JSONException invalid) {
            status = Status.REJECTED;
            lastFailure = invalid.toString();
        }
        return observation();
    }

    private FeedbackDraft requireDraft() {
        if (draft == null) throw new IllegalArgumentException("NO_DRAFT");
        return draft;
    }
    private void commit(int site, String replacement) {
        int[] span = graph.spans.get(site);
        if (span[0] < 0) throw new IllegalArgumentException("SOURCE_OCCURRENCE_UNAVAILABLE");
        String candidate = state.body().substring(0, span[0]) + replacement + state.body().substring(span[1]);
        FailedAttempt attempt = new FailedAttempt(revision, site, candidate);
        if (localRecovery && stableFailures.contains(attempt)) throw new IllegalArgumentException("STABLE_LOCAL_RETRY");
        RepairEnvironment.State prepared;
        preparationAttempts++;
        try { prepared = environment.propose(candidate); }
        catch (ErrorSyntax | ErrorType deterministic) {
            if (localRecovery) stableFailures.add(attempt);
            throw deterministic;
        }
        FeedbackSourceGraph next = new FeedbackSourceGraph(prepared);
        int rows = next.rowCount() + history.length()
                + FeedbackActions.rows(FeedbackActions.choices(next, null, feedbackSymbols(), remaining > 0));
        for (String key : initial.keySet()) rows += initial.getJSONArray(key).length();
        JSONObject worlds = FeedbackWorldTables.encode(responses, next);
        for (String key : worlds.keySet()) if (worlds.get(key) instanceof JSONArray a) rows += a.length();
        if (rows > FeedbackSourceGraph.MAX_ROWS) throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
        state = prepared; graph = next; draft = null; revision++; status = Status.APPLIED;
    }

    private boolean repeatedDraft() {
        if (draft == null) return false;
        try {
            int[] span = graph.spans.get(draft.site);
            String candidate = state.body().substring(0, span[0]) + draft.replacement() + state.body().substring(span[1]);
            return stableFailures.contains(new FailedAttempt(revision, draft.site, candidate));
        } catch (IllegalArgumentException incomplete) { return false; }
    }

    // Controller-only diagnostics, deliberately absent from observation().
    String body() { return state.body(); }
    FeedbackSourceGraph sourceGraph() { return graph; }
    int solverCalls() { return evaluator == null ? 0 : evaluator.bankStatus().getInt("solverCalls"); }
    String emittedSource() { return environment.emitModule(state); }
    String lastFailure() { return lastFailure; }
    int queryCount() { return queries; }
    int queryLimit() { return queryLimit; }
    int preparationAttempts() { return preparationAttempts; }
    boolean draftActive() { return draft != null; }
    List<edu.mit.csail.sdg.ast.Expr> feedbackSymbols() {
        if (evaluator == null || responses.isEmpty()) return List.of();
        return FeedbackWorldTables.export(responses, graph).labels().stream()
                .map(evaluator::returnedSymbol).toList();
    }
}
