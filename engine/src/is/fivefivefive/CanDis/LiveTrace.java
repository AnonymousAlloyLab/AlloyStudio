package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.core.NormalForm;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Trace the pinned Fast Rewrite metric, retaining private labels only for replay.
 * The framework's public explanatory trace does not expose optimal assignments.
 * This bridge reuses its binder alignment and atomic costs, then reconstructs
 * the same ordered DP / unordered assignment choices with learner-node pointers.
 */
public final class LiveTrace {
    private static final Class<?> METADATA = nested("EGraphMetadata");
    private static final Method ALIGN = method("bestCoherentMatrixAlignment", List.class, List.class, METADATA, METADATA);
    private static final Method MAP = method("bestVariableMapping", NormalForm.class, NormalForm.class, Map.class, Set.class, Set.class);
    private static final Method UPDATE = method("nodeUpdateCost", EGraphNode.class, EGraphNode.class, Map.class);
    private static final Method VARIABLE = method("variableName", EGraphNode.class);
    private static final Method ATOM = method("atomIdentity", EGraphNode.class);
    private static final Method CALL = method("callIdentity", EGraphNode.class);
    private static final Method RENDER = method("eGraphFormula", EGraphNode.class);
    private static final Method QUANTIFIER_ORDER = method("canonicalQuantifierOrder", List.class);
    private static final Method QUANTIFIER_UPDATE = method("quantifierUpdateCost", QuantiVar.class, QuantiVar.class);
    private static final Method QUANTIFIER_RENDER = method("quantifierFormula", QuantiVar.class);
    private static final Map<String, String> OPERATORS = operators();

    private LiveTrace() { }

    public static final class Result {
        public final JSONArray matrix = new JSONArray();
        public final JSONArray quantifier = new JSONArray();
        public boolean matrixReplayVerified;
        public boolean quantifierCostVerified;
    }

    public static Result reconstruct(Canonical.Prepared learner, Canonical.Prepared reference,
                                     CanonicalDistance.DistanceBreakdown expected) {
        List<NormalForm> left = learner.normalizedForms(), right = reference.normalizedForms();
        Result result = new Result();
        Object alignment = invoke(ALIGN, left, right, null, null);
        @SuppressWarnings("unchecked") Map<String, String> fixed = (Map<String, String>) field(alignment, "mapping");
        @SuppressWarnings("unchecked") Set<String> lockedLeft = (Set<String>) field(alignment, "lockedLeftNames");
        @SuppressWarnings("unchecked") Set<String> lockedRight = (Set<String>) field(alignment, "lockedRightNames");
        int total = 0;
        for (int phase = 0; phase < Math.max(left.size(), right.size()); phase++) {
            EGraphNode l = phase < left.size() ? left.get(phase).getMatrixEGraph() : null;
            EGraphNode r = phase < right.size() ? right.get(phase).getMatrixEGraph() : null;
            @SuppressWarnings("unchecked") Map<String, String> mapping = l != null && r != null
                    ? (Map<String, String>) invoke(MAP, left.get(phase), right.get(phase), fixed, lockedLeft, lockedRight)
                    : Map.of();
            Context context = new Context(mapping, result.matrix);
            int cost = context.distance(l, r);
            int startCount = result.matrix.length();
            ReplayNode replayed = context.trace(l, r, "normalForm[" + phase + "].matrix", null, null);
            if (result.matrix.length() - startCount != cost || !Objects.equals(replayed, context.snapshot(r, false))) {
                throw new IllegalStateException("Matrix reconstruction failed its private metric-view replay");
            }
            total = Math.addExact(total, cost);
            quantifierTrace(phase < left.size() ? left.get(phase) : null,
                    phase < right.size() ? right.get(phase) : null, phase, result.quantifier);
        }
        if (total != expected.matrixDistance() || result.quantifier.length() != expected.quantifierDistance()) {
            throw new IllegalStateException("Reconstructed operations disagree with the authoritative component cost");
        }
        result.matrixReplayVerified = true;
        result.quantifierCostVerified = true;
        return result;
    }

    private static final class Context {
        private final Map<String, String> mapping;
        private final JSONArray output;
        private final IdentityHashMap<EGraphNode, Integer> sizes = new IdentityHashMap<>();
        private final IdentityHashMap<EGraphNode, IdentityHashMap<EGraphNode, Integer>> memo = new IdentityHashMap<>();
        private int replayFault;
        private boolean faultApplied;
        Context(Map<String, String> mapping, JSONArray output) { this.mapping = mapping; this.output = output; }

        int size(EGraphNode node) {
            if (node == null) return 0;
            Integer known = sizes.get(node);
            if (known != null) return known;
            int size = 1;
            for (EGraphNode child : node.getChildren()) size = Math.addExact(size, size(child));
            sizes.put(node, size);
            return size;
        }

        int update(EGraphNode left, EGraphNode right) { return (Integer) invoke(UPDATE, left, right, mapping); }

        int distance(EGraphNode left, EGraphNode right) {
            if (left == null) return size(right);
            if (right == null) return size(left);
            IdentityHashMap<EGraphNode, Integer> row = memo.computeIfAbsent(left, ignored -> new IdentityHashMap<>());
            Integer known = row.get(right);
            if (known != null) return known;
            List<EGraphNode> lc = left.getChildren(), rc = right.getChildren();
            int childCost;
            if (unordered(left, right)) {
                int[][] costs = assignmentCosts(lc, rc);
                int[] matching = assignment(costs);
                childCost = 0;
                for (int i = 0; i < matching.length; i++) childCost = Math.addExact(childCost, costs[i][matching[i]]);
            } else childCost = orderedCosts(lc, rc)[lc.size()][rc.size()];
            int cost = Math.addExact(update(left, right), childCost);
            row.put(right, cost);
            return cost;
        }

        private boolean unordered(EGraphNode left, EGraphNode right) {
            return left.getOpcode() == right.getOpcode() && left.isOrderInsensitive() && right.isOrderInsensitive();
        }

        int[][] orderedCosts(List<EGraphNode> left, List<EGraphNode> right) {
            int[][] dp = new int[left.size() + 1][right.size() + 1];
            for (int i = 1; i <= left.size(); i++) dp[i][0] = dp[i - 1][0] + size(left.get(i - 1));
            for (int j = 1; j <= right.size(); j++) dp[0][j] = dp[0][j - 1] + size(right.get(j - 1));
            for (int i = 1; i <= left.size(); i++) for (int j = 1; j <= right.size(); j++) {
                dp[i][j] = Math.min(dp[i - 1][j - 1] + distance(left.get(i - 1), right.get(j - 1)),
                        Math.min(dp[i - 1][j] + size(left.get(i - 1)), dp[i][j - 1] + size(right.get(j - 1))));
            }
            return dp;
        }

        int[][] assignmentCosts(List<EGraphNode> left, List<EGraphNode> right) {
            int n = left.size() + right.size();
            int[][] costs = new int[n][n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                costs[i][j] = i < left.size() ? (j < right.size() ? distance(left.get(i), right.get(j)) : size(left.get(i)))
                        : j < right.size() ? size(right.get(j)) : 0;
            }
            return costs;
        }

        ReplayNode trace(EGraphNode left, EGraphNode right, String path, EGraphNode insertionAnchor, String anchorPath) {
            if (left == null) return insert(right, insertionAnchor, anchorPath == null ? path : anchorPath);
            if (right == null) { delete(left, path); return null; }
            String replayLabel = label(left, true);
            if (update(left, right) != 0) {
                if (replayFault != 0 && !faultApplied) {
                    faultApplied = true;
                    if (replayFault == 2) {
                        output.put(matrixOperation("replace", left, right, path, "affected"));
                        replayLabel = "deliberately-corrupted-private-replacement";
                    }
                } else {
                    output.put(matrixOperation("replace", left, right, path, "affected"));
                    replayLabel = label(right, false);
                }
            }
            List<EGraphNode> lc = left.getChildren(), rc = right.getChildren();
            ReplayNode[] rebuilt = new ReplayNode[rc.size()];
            if (unordered(left, right)) {
                int[] matching = assignment(assignmentCosts(lc, rc));
                for (int i = 0; i < matching.length; i++) {
                    int j = matching[i];
                    if (i < lc.size() && j < rc.size())
                        rebuilt[j] = trace(lc.get(i), rc.get(j), path + ".child[" + i + "]", left, path);
                    else if (i < lc.size()) delete(lc.get(i), path + ".child[" + i + "]");
                    else if (j < rc.size()) rebuilt[j] = insert(rc.get(j), left, path);
                }
            } else {
                int[][] dp = orderedCosts(lc, rc);
                int i = lc.size(), j = rc.size();
                while (i > 0 || j > 0) {
                    // Prefer a real aligned node on ties; unlike a text diff this
                    // retains the exact learner occurrence selected by the metric.
                    if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + distance(lc.get(i - 1), rc.get(j - 1))) {
                        rebuilt[j - 1] = trace(lc.get(i - 1), rc.get(j - 1), path + ".child[" + (i - 1) + "]", left, path);
                        i--; j--;
                    } else if (i > 0 && dp[i][j] == dp[i - 1][j] + size(lc.get(i - 1))) {
                        delete(lc.get(--i), path + ".child[" + i + "]");
                    } else if (j > 0 && dp[i][j] == dp[i][j - 1] + size(rc.get(j - 1))) {
                        rebuilt[j - 1] = insert(rc.get(j - 1), left, path); j--;
                    } else throw new IllegalStateException("No optimal child transition");
                }
            }
            return new ReplayNode(replayLabel, List.of(rebuilt));
        }

        ReplayNode insert(EGraphNode node, EGraphNode learnerAnchor, String path) {
            if (node == null) return null;
            output.put(matrixOperation("insert", learnerAnchor, null, path, "insertion-anchor"));
            List<ReplayNode> children = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) children.add(insert(child, learnerAnchor, path));
            return new ReplayNode(label(node, false), List.copyOf(children));
        }

        void delete(EGraphNode node, String path) {
            output.put(matrixOperation("delete", node, null, path, "affected"));
            for (int i = 0; i < node.getChildren().size(); i++) delete(node.getChildren().get(i), path + ".child[" + i + "]");
        }

        String label(EGraphNode node, boolean learner) {
            String identity = "";
            switch (node.getOpcode()) {
                case VARIABLE:
                    String name = (String) invoke(VARIABLE, node);
                    identity = learner ? mapping.getOrDefault(name, name) : name;
                    break;
                case GLOBALBINDING: case CONSTANT: case REF: identity = (String) invoke(ATOM, node); break;
                case CALL: identity = (String) invoke(CALL, node); break;
                default: break;
            }
            return node.getOpcode().name() + ":" + String.valueOf(identity);
        }

        ReplayNode snapshot(EGraphNode node, boolean learner) {
            if (node == null) return null;
            List<ReplayNode> children = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) children.add(snapshot(child, learner));
            return new ReplayNode(label(node, learner), List.copyOf(children));
        }
    }

    private record ReplayNode(String label, List<ReplayNode> children) { }

    /** Negative controls for the verifier: omit or corrupt a real replacement. */
    public static boolean replayRejectsTamperedReplacement(Canonical.Prepared learner, Canonical.Prepared reference) {
        NormalForm left = learner.normalizedForms().get(0), right = reference.normalizedForms().get(0);
        @SuppressWarnings("unchecked") Map<String, String> mapping = (Map<String, String>) invoke(MAP, left, right, Map.of(), Set.of(), Set.of());
        for (int fault : new int[] {1, 2}) {
            Context context = new Context(mapping, new JSONArray());
            context.replayFault = fault;
            ReplayNode replayed = context.trace(left.getMatrixEGraph(), right.getMatrixEGraph(), "normalForm[0].matrix", null, null);
            if (!context.faultApplied || Objects.equals(replayed, context.snapshot(right.getMatrixEGraph(), false))) return false;
        }
        return true;
    }

    /** Independent exhaustive check of small assignment witnesses used by trace DP. */
    public static boolean assignmentWitnessesPass() {
        java.util.Random random = new java.util.Random(49578);
        for (int size = 0; size <= 5; size++) for (int fixture = 0; fixture < 12; fixture++) {
            int[][] costs = new int[size][size];
            for (int[] row : costs) for (int j = 0; j < size; j++) row[j] = random.nextInt(9);
            int[] witness = assignment(costs);
            boolean[] used = new boolean[size];
            int actual = 0;
            for (int i = 0; i < size; i++) {
                if (witness[i] < 0 || witness[i] >= size || used[witness[i]]) return false;
                used[witness[i]] = true; actual += costs[i][witness[i]];
            }
            if (actual != bruteAssignment(costs, 0, new boolean[size])) return false;
        }
        return true;
    }

    private static int bruteAssignment(int[][] costs, int row, boolean[] used) {
        if (row == costs.length) return 0;
        int best = Integer.MAX_VALUE;
        for (int column = 0; column < costs.length; column++) if (!used[column]) {
            used[column] = true;
            best = Math.min(best, costs[row][column] + bruteAssignment(costs, row + 1, used));
            used[column] = false;
        }
        return best;
    }

    /** Hungarian assignment with explicit row-to-column witness, including dummy nodes. */
    private static int[] assignment(int[][] costs) {
        int n = costs.length;
        long[] u = new long[n + 1], v = new long[n + 1];
        int[] p = new int[n + 1], way = new int[n + 1];
        for (int row = 1; row <= n; row++) {
            p[0] = row;
            int column = 0;
            long[] minimum = new long[n + 1]; Arrays.fill(minimum, Long.MAX_VALUE);
            boolean[] used = new boolean[n + 1];
            do {
                used[column] = true;
                int currentRow = p[column], next = 0;
                long delta = Long.MAX_VALUE;
                for (int candidate = 1; candidate <= n; candidate++) if (!used[candidate]) {
                    long reduced = costs[currentRow - 1][candidate - 1] - u[currentRow] - v[candidate];
                    if (reduced < minimum[candidate]) { minimum[candidate] = reduced; way[candidate] = column; }
                    if (minimum[candidate] < delta) { delta = minimum[candidate]; next = candidate; }
                }
                for (int candidate = 0; candidate <= n; candidate++) {
                    if (used[candidate]) { u[p[candidate]] += delta; v[candidate] -= delta; }
                    else if (minimum[candidate] != Long.MAX_VALUE) minimum[candidate] -= delta;
                }
                column = next;
            } while (p[column] != 0);
            do { int prior = way[column]; p[column] = p[prior]; column = prior; } while (column != 0);
        }
        int[] result = new int[n];
        for (int column = 1; column <= n; column++) result[p[column] - 1] = column - 1;
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void quantifierTrace(NormalForm left, NormalForm right, int phase, JSONArray output) {
        List<QuantiVar> l = left == null ? List.of() : (List<QuantiVar>) invoke(QUANTIFIER_ORDER, left.getMatrixQuantiVars());
        List<QuantiVar> r = right == null ? List.of() : (List<QuantiVar>) invoke(QUANTIFIER_ORDER, right.getMatrixQuantiVars());
        int[][] dp = new int[l.size() + 1][r.size() + 1];
        for (int i = 0; i <= l.size(); i++) dp[i][0] = i;
        for (int j = 0; j <= r.size(); j++) dp[0][j] = j;
        for (int i = 1; i <= l.size(); i++) for (int j = 1; j <= r.size(); j++)
            dp[i][j] = Math.min(dp[i - 1][j - 1] + (Integer) invoke(QUANTIFIER_UPDATE, l.get(i - 1), r.get(j - 1)),
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
        int i = l.size(), j = r.size();
        while (i > 0 || j > 0) {
            String kind; QuantiVar source = null, target = null;
            if (i > 0 && j > 0 && dp[i][j] == dp[i - 1][j - 1] + (Integer) invoke(QUANTIFIER_UPDATE, l.get(i - 1), r.get(j - 1))) {
                source = l.get(--i); target = r.get(--j);
                if ((Integer) invoke(QUANTIFIER_UPDATE, source, target) == 0) continue;
                kind = "modify";
            } else if (i > 0 && dp[i][j] == dp[i - 1][j] + 1) { source = l.get(--i); kind = "delete"; }
            else { j--; kind = "insert"; }
            JSONObject op = base(kind, "quantifier", "normalForm[" + phase + "].quantifier[" + i + "]");
            if (source != null) {
                op.put("sourceTerm", shorten(renderBinding(source)))
                        .put("sourceOperator", source.getQuantifier().name().toLowerCase(Locale.ROOT))
                        .put("sourceNodeKind", "binding").put("sourceRole", "affected");
            }
            String replacement = target == null ? null : quantifierOperator(target);
            if (replacement != null && source.getQuantifier() != target.getQuantifier()) op.put("replacementOperator", replacement);
            String action = kind.equals("insert") ? "Introduce one quantified binding; its variable and domain remain hidden."
                    : kind.equals("delete") ? "Remove this quantified binding from the canonical prefix."
                    : op.has("replacementOperator") ? "Change this binding's quantifier from " + op.getString("sourceOperator") + " to " + replacement + "."
                    : "Revise this binding's domain, multiplicity, or disjointness; required terms remain hidden.";
            op.put("action", action).put("description", action)
                    .put("reason", "The aligned canonical binding tuple differs by one metric edit unit.")
                    .put("nextStep", "Find the corresponding declaration in your predicate and revise it; normalization can move bindings.");
            output.put(op);
        }
    }

    private static JSONObject matrixOperation(String kind, EGraphNode source, EGraphNode target, String path, String role) {
        JSONObject op = base(kind, "matrix", path);
        String sourceOperator = source == null ? null : operator(source);
        String nodeKind = source == null ? "structure" : nodeKind(source);
        if (source != null) op.put("sourceTerm", shorten(renderSource(source)))
                .put("sourceOperator", sourceOperator == null ? nodeKind : sourceOperator)
                .put("sourceNodeKind", nodeKind).put("sourceRole", role);
        else op.put("sourceTerm", "[no existing learner matrix in this phase]")
                .put("sourceOperator", "none").put("sourceNodeKind", "structure").put("sourceRole", "insertion-anchor");
        String replacement = target == null ? null : operator(target);
        if (kind.equals("replace") && replacement != null && source.getOpcode() != target.getOpcode())
            op.put("replacementOperator", replacement);
        String action;
        if (kind.equals("insert")) action = source == null
                ? "Add one node to a new canonical matrix; its required term remains hidden."
                : "Add one node within this canonical expression; its required term remains hidden.";
        else if (kind.equals("delete")) action = "Remove this " + (sourceOperator == null ? nodeKind : "“" + sourceOperator + "” operator") + " node from the canonical expression.";
        else if (op.has("replacementOperator")) action = "Replace " + (sourceOperator == null ? "this " + nodeKind : "“" + sourceOperator + "”") + " with the “" + replacement + "” operator in this expression.";
        else action = switch (nodeKind) {
            case "reference" -> "Use a different relation reference at this position; the required name remains hidden.";
            case "variable" -> "Revise which bound variable this occurrence refers to; the required binding remains hidden.";
            case "constant" -> "Change this constant; the required value remains hidden.";
            case "call" -> "Change which predicate or function is called here; the required name remains hidden.";
            default -> "Revise this canonical node; the required term remains hidden.";
        };
        String reason = kind.equals("insert") ? "The optimal matrix alignment includes an inserted node at this learner-side context."
                : kind.equals("delete") ? "The optimal matrix alignment removes this learner node."
                : "The optimal matrix alignment changes this learner node's operator or identity.";
        return op.put("action", action).put("description", action).put("reason", reason)
                .put("nextStep", kind.equals("insert") ? "Review this expression for a missing operand or connective. Insertions may form one larger expression."
                        : kind.equals("delete") ? "Repair or remove the affected subtree while preserving Alloy syntax; its nodes can account for several edit units."
                        : "Find this expression or its normalized equivalent in your predicate, make one change, and compare again.");
    }

    private static String renderSource(EGraphNode node) {
        List<EGraphNode> children = node.getChildren();
        switch (node.getOpcode()) {
            case VARIABLE: case GLOBALBINDING: case CONSTANT: case REF:
                return (String) invoke(RENDER, node);
            case PREDICATE: case FUNCTION: case TEMPORALROOT:
                if (children.size() == 1) return renderSource(children.get(0));
                break;
            case CALL: return (String) invoke(RENDER, node);
            default: break;
        }
        String token = operator(node);
        if (token == null || children.isEmpty()) return (String) invoke(RENDER, node);
        if (children.size() == 1) {
            String operand = renderSource(children.get(0));
            return node.getOpcode() == EGraphNode.Opcode.PRIME ? "(" + operand + ")'"
                    : "(" + token + " " + operand + ")";
        }
        List<String> terms = new ArrayList<>();
        for (EGraphNode child : children) terms.add(renderSource(child));
        return "(" + String.join(" " + token + " ", terms) + ")";
    }

    private static String renderBinding(QuantiVar source) {
        String name = source.getOriginalName() == null ? source.getName() : source.getOriginalName();
        return source.getQuantifier().name().toLowerCase(Locale.ROOT) + (source.isDisj() ? " disj " : " ")
                + name + " : " + source.getCardinality().name().toLowerCase(Locale.ROOT) + " " + source.getTypeName();
    }

    private static JSONObject base(String kind, String component, String path) {
        return new JSONObject().put("kind", kind).put("component", component).put("path", path)
                .put("cost", 1).put("aggregate", false);
    }

    private static String nodeKind(EGraphNode node) {
        return switch (node.getOpcode()) {
            case VARIABLE -> "variable";
            case GLOBALBINDING, REF -> "reference";
            case CONSTANT -> "constant";
            case CALL -> "call";
            case PREDICATE, FUNCTION, TEMPORALROOT, DUMMY, END, SHADOW -> "structure";
            default -> node.getMetatype() == EGraphNode.Metatype.BOOLEAN ? "formula" : "relation";
        };
    }

    public static String operator(EGraphNode node) { return OPERATORS.get(node.getOpcode().name()); }
    private static String quantifierOperator(QuantiVar q) {
        String result = q.getQuantifier().name().toLowerCase(Locale.ROOT);
        return Set.of("all", "some", "no", "one", "lone", "sum").contains(result) ? result : null;
    }
    private static Map<String, String> operators() {
        Map<String, String> result = new HashMap<>();
        for (String name : List.of("AND", "OR", "NOT", "IMPLIES", "IFF", "IN", "SOME", "NO", "ONE", "LONE", "BEFORE", "HISTORICALLY", "ONCE", "ALWAYS", "EVENTUALLY", "AFTER", "UNTIL", "RELEASES", "SINCE", "TRIGGERED", "EXACTLY", "SUM")) result.put(name, name.toLowerCase(Locale.ROOT));
        String[][] pairs = {{"EQUALS","="},{"NOT_EQUALS","!="},{"GT",">"},{"GTE",">="},{"LT","<"},{"LTE","<="},
                {"NOT_GT","!>"},{"NOT_GTE","!>="},{"NOT_IN","!in"},{"NOT_LT","!<"},{"NOT_LTE","!<="},
                {"ARROW","->"},{"JOIN","."},{"DOMAIN","<:"},{"RANGE",":>"},{"INTERSECT","&"},{"PLUSPLUS","++"},
                {"PLUS","+"},{"IPLUS","+"},{"MINUS","-"},{"IMINUS","-"},{"MUL","*"},{"DIV","/"},{"REM","%"},
                {"SHL","<<"},{"SHR",">>"},{"SHA",">>>"},{"SETOF","set"},{"TRANSPOSE","~"},{"CLOSURE","^"},
                {"RCLOSURE","*"},{"CARDINALITY","#"},{"CAST2INT","int"},{"CAST2SIGINT","Int"},{"PRIME","'"},
                {"FORALL","all"},{"EXISTS","some"},{"ITE","if-then-else"},{"DISJ","disj"},{"DISJOINT","disj"}};
        for (String[] pair : pairs) result.put(pair[0], pair[1]);
        return Map.copyOf(result);
    }
    private static String shorten(String term) { return term.length() <= 320 ? term : term.substring(0, 317) + "..."; }

    private static Class<?> nested(String name) {
        try { return Class.forName(CanonicalDistance.class.getName() + "$" + name); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned metric API unavailable", error); }
    }
    private static Method method(String name, Class<?>... args) {
        try { Method method = CanonicalDistance.class.getDeclaredMethod(name, args); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned metric API unavailable", error); }
    }
    private static Object invoke(Method method, Object... args) {
        try { return method.invoke(null, args); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned metric operation failed", error); }
    }
    private static Object field(Object instance, String name) {
        try { Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(instance); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned metric alignment unavailable", error); }
    }
}
