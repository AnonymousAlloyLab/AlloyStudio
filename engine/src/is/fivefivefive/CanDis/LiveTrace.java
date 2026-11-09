package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.FastChainGeometry;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Trace the pinned Fast Rewrite metric, retaining private labels only for replay.
 * The framework's public explanatory trace does not expose optimal assignments.
 * This bridge reuses its binder alignment and atomic costs, then reconstructs
 * the same ordered DP / unordered assignment choices with learner-node pointers.
 */
public final class LiveTrace {
    private static final Class<?> METADATA = nested("EGraphMetadata");
    private static final Method ALIGN = method("bestCoherentMatrixAlignment", List.class, List.class, METADATA, METADATA);
    private static final Method MAP = method("bestVariableMapping", NormalForm.class, NormalForm.class, Map.class, Set.class, Set.class);
    private static final Method UPDATE = method("nodeUpdateCost", EGraphNode.class, EGraphNode.class, Map.class, METADATA, METADATA);
    private static final Method VARIABLE = method("variableName", EGraphNode.class);
    private static final Method ATOM = method("atomIdentity", EGraphNode.class);
    private static final Method CALL = method("callIdentity", EGraphNode.class);
    private static final Method RENDER = method("eGraphFormula", EGraphNode.class);
    private static final Method QUANTIFIER_ORDER = method("canonicalQuantifierOrder", List.class);
    private static final Method QUANTIFIER_UPDATE = method("quantifierUpdateCost", QuantiVar.class, QuantiVar.class);
    private static final Method QUANTIFIER_RENDER = method("quantifierFormula", QuantiVar.class);
    private static final Map<String, String> OPERATORS = operators();
    private static final Pattern MATRIX_PATH = Pattern.compile("^normalForm\\[([0-9]+)\\]\\.matrix((?:\\.child\\[[0-9]+\\])*)$");
    private static final Pattern CHILD_PATH = Pattern.compile("\\.child\\[([0-9]+)\\]");
    private static final Pattern BINDING_PATH = Pattern.compile("^normalForm\\[([0-9]+)\\]\\.quantifier\\[([0-9]+)\\]$");

    private LiveTrace() { }

    /** Resolve the learner occurrence used by the metric, never a text match. */
    public static EGraphNode learnerNode(Canonical.Prepared learner, String path) {
        Matcher match = MATRIX_PATH.matcher(path);
        if (!match.matches()) return null;
        try {
            EGraphNode node = comparisonForms(learner).get(Integer.parseInt(match.group(1))).getMatrixEGraph();
            Matcher child = CHILD_PATH.matcher(match.group(2));
            while (child.find()) {
                if (node == null) return null;
                node = node.getChildren().get(Integer.parseInt(child.group(1)));
            }
            return node;
        } catch (IndexOutOfBoundsException | NumberFormatException error) { return null; }
    }

    /** Quantifier paths use the metric's ordering, which may differ from display order. */
    @SuppressWarnings("unchecked")
    public static QuantiVar learnerBinding(Canonical.Prepared learner, String path) {
        Matcher match = BINDING_PATH.matcher(path);
        if (!match.matches()) return null;
        try {
            NormalForm form = comparisonForms(learner).get(Integer.parseInt(match.group(1)));
            List<QuantiVar> bindings = (List<QuantiVar>) invoke(QUANTIFIER_ORDER, form.getMatrixQuantiVars());
            return bindings.get(Integer.parseInt(match.group(2)));
        } catch (IndexOutOfBoundsException | NumberFormatException error) { return null; }
    }

    public static final class Result {
        public final JSONArray temporal = new JSONArray();
        public final JSONArray matrix = new JSONArray();
        public final JSONArray quantifier = new JSONArray();
        public boolean matrixReplayVerified;
        public boolean quantifierCostVerified;
    }

    public static Result reconstruct(Canonical.Prepared learner, Canonical.Prepared reference,
                                     CanonicalDistance.DistanceBreakdown expected) {
        return nativeProjection(learner, reference, expected);
    }

    /** Project only learner pointers and safe operator names from the upstream's
     * checked fixed-choice replay. Private endpoint payloads never leave here. */
    @SuppressWarnings("unchecked")
    private static Result nativeProjection(Canonical.Prepared learner, Canonical.Prepared reference,
            CanonicalDistance.DistanceBreakdown expected) {
        var trace = Canonical.evaluateWithTrace(learner, reference);
        if (trace.distance() != expected.distance() || trace.matrixDistance() != expected.matrixDistance()
                || trace.quantifierDistance() != expected.quantifierDistance()
                || trace.temporalDistance() != expected.temporalDistance())
            throw new IllegalStateException("Native trace selection changed");
        JSONObject evidence = trace.json();
        if (!evidence.getBoolean("metricRepresentationExecutionChecked"))
            throw new IllegalStateException("Native metric replay incomplete");
        List<NormalForm> left = (List<NormalForm>) field(field(learner, "delegate"), "normalForms");
        List<NormalForm> right = (List<NormalForm>) field(field(reference, "delegate"), "normalForms");
        Result result = new Result();
        JSONArray phases = evidence.getJSONArray("phases");
        for (int phase = 0; phase < phases.length(); phase++) {
            NormalForm form = phase < left.size() ? left.get(phase) : null;
            EGraphNode source = form == null ? null : form.getMatrixEGraph();
            EGraphNode target = phase < right.size() ? right.get(phase).getMatrixEGraph() : null;
            int originalPhase = phase;
            String prefix = "normalForm[" + originalPhase + "].matrix";
            JSONArray actions = phases.getJSONObject(phase).getJSONObject("program").getJSONArray("operations");
            Map<String, String> targetAnchors = new HashMap<>();
            for (Object value : actions) {
                JSONObject action = (JSONObject) value;
                if (!action.isNull("source") && !action.isNull("target"))
                    targetAnchors.put(action.getString("target"), action.getString("source"));
            }
            for (Object value : actions) {
                WorkBudget.charge(1);
                JSONObject action = (JSONObject) value;
                int cost = action.getInt("cost");
                if (cost == 0) continue;
                if (cost != 1) throw new IllegalStateException("Non-atomic native matrix edit");
                boolean insertion = action.isNull("source"), deletion = action.isNull("target");
                String address = insertion ? null : action.getString("source");
                if (insertion) {
                    String parent = action.getString("target");
                    while (parent.contains("/")) {
                        parent = parent.substring(0, parent.lastIndexOf("/child/"));
                        if (targetAnchors.containsKey(parent)) { address = targetAnchors.get(parent); break; }
                    }
                }
                Located selected = address == null ? new Located(null, prefix) : metricNode(source, address, prefix);
                EGraphNode replacement = deletion || insertion ? null : metricNode(target, action.getString("target"), "").node();
                if (selected.node() != null) {
                    EGraphNode original = learnerNode(learner, selected.path());
                    if (original == null || original.getOpcode() != selected.node().getOpcode())
                        throw new IllegalStateException("Native learner occurrence cannot be resolved: " + selected.path()
                                + ": " + selected.node().getOpcode() + " vs " + (original == null ? "missing" : original.getOpcode()));
                }
                result.matrix.put(matrixOperation(insertion ? "insert" : deletion ? "delete" : "replace",
                        selected.node(), replacement, selected.path(), insertion ? "insertion-anchor" : "affected"));
            }
            JSONArray declarations = evidence.getJSONArray("nativeDeclarationPrograms").getJSONObject(phase).getJSONArray("operations");
            for (Object value : declarations) {
                WorkBudget.charge(1);
                JSONObject action = (JSONObject) value;
                if (action.getInt("cost") == 0) continue;
                if (action.getInt("cost") != 1) throw new IllegalStateException("Non-atomic native declaration edit");
                QuantiVar binding = null; int ordinal = 0;
                if (!action.isNull("source") && form != null) {
                    JSONObject atom = action.getJSONObject("source");
                    String[] occurrence = atom.getString("occurrence").split("/");
                    if (occurrence.length != 4 || !occurrence[0].equals("phase")
                            || !occurrence[1].equals(Integer.toString(phase)) || !occurrence[2].equals("declaration"))
                        throw new IllegalStateException("Invalid native binding address");
                    binding = form.getMatrixQuantiVars().get(Integer.parseInt(occurrence[3]));
                    List<QuantiVar> ordered = (List<QuantiVar>) invoke(QUANTIFIER_ORDER, form.getMatrixQuantiVars());
                    ordinal = ordered.indexOf(binding);
                    if (ordinal < 0) throw new IllegalStateException("Native binding lost its display coordinate");
                    if (binding == null) throw new IllegalStateException("Missing native binding occurrence");
                }
                String kind = action.isNull("source") ? "insert" : action.isNull("target") ? "delete" : "modify";
                JSONObject op = base(kind, "quantifier", "normalForm[" + originalPhase + "].quantifier[" + ordinal + "]");
                if (binding != null) op.put("sourceTerm", shorten(renderBinding(binding)))
                        .put("sourceOperator", binding.getQuantifier().name().toLowerCase(Locale.ROOT))
                        .put("sourceNodeKind", "binding").put("sourceRole", "affected");
                if (kind.equals("modify") && !action.isNull("target")) {
                    String operator = action.getJSONObject("target").getJSONArray("tuple").getString(0).toLowerCase(Locale.ROOT);
                    if (Set.of("all", "some", "no", "one", "lone", "sum").contains(operator)
                            && (binding == null || !operator.equals(binding.getQuantifier().name().toLowerCase(Locale.ROOT))))
                        op.put("replacementOperator", operator);
                }
                String hint = kind.equals("insert") ? "Check for a missing variable declaration; choose the variable and its set yourself."
                        : kind.equals("delete") ? "Consider whether this variable declaration is needed."
                        : op.has("replacementOperator") ? "Compare how " + op.getString("sourceOperator") + " and " + op.getString("replacementOperator") + " affect this variable declaration."
                        : "Review which set this variable ranges over, how many values it can hold, and whether variables must have different values.";
                op.put("action", hint).put("description", hint)
                        .put("reason", "The comparison points to a difference in how a variable is introduced or used.")
                        .put("nextStep", "Inspect this declaration, try one change, and check again.");
                result.quantifier.put(op);
            }
        }
        JSONArray temporal = evidence.getJSONObject("nativeTemporalProgram").getJSONArray("operations");
        for (int i = 0; i < temporal.length(); i++) {
            JSONObject action = temporal.getJSONObject(i);
            String kind = action.getString("kind").equals("update") ? "replace" : action.getString("kind");
            JSONObject op = base(kind, "temporal", "temporal");
            String before = action.optString("from", action.optString("label", "")).toLowerCase(Locale.ROOT);
            String after = action.optString("to", "").toLowerCase(Locale.ROOT);
            Set<String> operators = Set.of("always", "eventually", "after", "before", "historically", "once", "until", "releases", "since", "triggered", "and", "or", "not", "implies", "iff");
            if (operators.contains(before) && !kind.equals("insert")) op.put("sourceOperator", before).put("sourceTerm", before);
            if (operators.contains(after) && kind.equals("replace")) op.put("replacementOperator", after);
            String hint = kind.equals("insert") ? "Consider whether a temporal condition is missing."
                    : kind.equals("delete") ? "Consider whether this temporal condition is needed."
                    : "Review when this condition should hold across the trace.";
            op.put("action", hint).put("description", hint).put("reason", "The comparison points to a difference in the temporal structure.")
                    .put("nextStep", "Inspect the temporal operators, try one change, and check again.");
            result.temporal.put(op);
        }
        if (result.matrix.length() != expected.matrixDistance() || result.quantifier.length() != expected.quantifierDistance()
                || result.temporal.length() != expected.temporalDistance())
            throw new IllegalStateException("Native projected operation cost mismatch");
        result.matrixReplayVerified = true; result.quantifierCostVerified = true;
        return result;
    }

    /** Same immutable phase view used by Canonical.irTemporalFol and its metric. */
    @SuppressWarnings("unchecked")
    public static List<NormalForm> comparisonForms(Canonical.Prepared prepared) {
        return (List<NormalForm>) field(field(prepared, "delegate"), "normalForms");
    }

    private record Located(EGraphNode node, String path) { }
    private static Located metricNode(EGraphNode node, String address, String prefix) {
        String[] parts = address.split("/");
        if (!parts[0].equals("root") || parts.length % 2 != 1) throw new IllegalStateException("Invalid native node address");
        String path = prefix;
        for (int i = 1; i < parts.length; i += 2) {
            if (node == null || !parts[i].equals("child")) throw new IllegalStateException("Unsupported native node edge");
            int child = Integer.parseInt(parts[i + 1]);
            List<EGraphNode> children = FastChainGeometry.children(node);
            List<String> suffixes = new ArrayList<>();
            collectMetricPaths(node, children, suffixes, "", new int[] {0}, true);
            if (suffixes.size() != children.size()) throw new IllegalStateException("Metric geometry lost source paths");
            path += suffixes.get(child); node = children.get(child);
        }
        return new Located(node, path);
    }
    private static void collectMetricPaths(EGraphNode node, List<EGraphNode> wanted,
            List<String> paths, String path, int[] cursor, boolean root) {
        WorkBudget.charge(1);
        if (!root && cursor[0] < wanted.size() && node == wanted.get(cursor[0])) {
            paths.add(path); cursor[0]++; return;
        }
        for (int i = 0; i < node.getChildren().size(); i++)
            collectMetricPaths(node.getChildren().get(i), wanted, paths, path + ".child[" + i + "]", cursor, false);
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
            WorkBudget.charge(1 + node.getChildren().size());
            int size = 1;
            for (EGraphNode child : node.getChildren()) size = Math.addExact(size, size(child));
            sizes.put(node, size);
            return size;
        }

        int update(EGraphNode left, EGraphNode right) { return (Integer) invoke(UPDATE, left, right, mapping, null, null); }

        int distance(EGraphNode left, EGraphNode right) {
            if (left == null) return size(right);
            if (right == null) return size(left);
            WorkBudget.charge(1);
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
            WorkBudget.allocate(1);
            row.put(right, cost);
            return cost;
        }

        private boolean unordered(EGraphNode left, EGraphNode right) {
            return left.getOpcode() == right.getOpcode() && left.isOrderInsensitive() && right.isOrderInsensitive();
        }

        int[][] orderedCosts(List<EGraphNode> left, List<EGraphNode> right) {
            WorkBudget.chargeCells(left.size() + 1L, right.size() + 1L);
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
            WorkBudget.chargeCells(n, n);
            int[][] costs = new int[n][n];
            for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
                costs[i][j] = i < left.size() ? (j < right.size() ? distance(left.get(i), right.get(j)) : size(left.get(i)))
                        : j < right.size() ? size(right.get(j)) : 0;
            }
            return costs;
        }

        ReplayNode trace(EGraphNode left, EGraphNode right, String path, EGraphNode insertionAnchor, String anchorPath) {
            WorkBudget.charge(1);
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
            WorkBudget.charge(1);
            output.put(matrixOperation("insert", learnerAnchor, null, path, "insertion-anchor"));
            List<ReplayNode> children = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) children.add(insert(child, learnerAnchor, path));
            return new ReplayNode(label(node, false), List.copyOf(children));
        }

        void delete(EGraphNode node, String path) {
            WorkBudget.charge(1);
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
            WorkBudget.charge(1);
            List<ReplayNode> children = new ArrayList<>();
            for (EGraphNode child : node.getChildren()) children.add(snapshot(child, learner));
            return new ReplayNode(label(node, learner), List.copyOf(children));
        }
    }

    private record ReplayNode(String label, List<ReplayNode> children) { }

    /** Negative controls for the verifier: omit or corrupt a real replacement. */
    public static boolean replayRejectsTamperedReplacement(Canonical.Prepared learner, Canonical.Prepared reference) {
        NormalForm left = comparisonForms(learner).get(0), right = comparisonForms(reference).get(0);
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
            WorkBudget.charge(n + 1L);
            p[0] = row;
            int column = 0;
            long[] minimum = new long[n + 1]; Arrays.fill(minimum, Long.MAX_VALUE);
            boolean[] used = new boolean[n + 1];
            do {
                WorkBudget.charge(2L * (n + 1));
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
        WorkBudget.chargeCells(l.size() + 1L, r.size() + 1L);
        int[][] dp = new int[l.size() + 1][r.size() + 1];
        for (int i = 0; i <= l.size(); i++) dp[i][0] = i;
        for (int j = 0; j <= r.size(); j++) dp[0][j] = j;
        for (int i = 1; i <= l.size(); i++) for (int j = 1; j <= r.size(); j++)
            dp[i][j] = Math.min(dp[i - 1][j - 1] + (Integer) invoke(QUANTIFIER_UPDATE, l.get(i - 1), r.get(j - 1)),
                    Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
        int i = l.size(), j = r.size();
        while (i > 0 || j > 0) {
            WorkBudget.charge(1);
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
            String action = kind.equals("insert") ? "Check for a missing variable declaration; choose the variable and its set yourself."
                    : kind.equals("delete") ? "Consider whether this variable declaration is needed."
                    : op.has("replacementOperator") ? "Compare how " + op.getString("sourceOperator") + " and " + replacement + " affect this variable declaration."
                    : "Review which set this variable ranges over, how many values it can hold, and whether variables must have different values.";
            op.put("action", action).put("description", action)
                    .put("reason", "The comparison points to a difference in how a variable is introduced or used.")
                    .put("nextStep", "Look for the matching declaration in your code. It may appear in a different place in the simplified form; try one change and check again.");
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
        else op.put("sourceTerm", "[no matching part in your current expression]")
                .put("sourceOperator", "none").put("sourceNodeKind", "structure").put("sourceRole", "insertion-anchor");
        String replacement = target == null ? null : operator(target);
        if (kind.equals("replace") && replacement != null && source.getOpcode() != target.getOpcode())
            op.put("replacementOperator", replacement);
        String action;
        if (kind.equals("insert")) action = source == null
                ? "Check whether your predicate is missing a condition."
                : "Check for a missing part in this expression.";
        else if (kind.equals("delete")) action = "Consider removing this " + (sourceOperator == null ? "part of the expression" : "“" + sourceOperator + "” operator") + ".";
        else if (op.has("replacementOperator")) action = "Consider the “" + replacement + "” operator in place of " + (sourceOperator == null ? "this part of your expression" : "“" + sourceOperator + "”") + ".";
        else action = switch (nodeKind) {
            case "reference" -> "Check which relation name belongs here.";
            case "variable" -> "Check which declared variable you mean to use here.";
            case "constant" -> "Check whether this constant has the value you need.";
            case "call" -> "Check which predicate or function you want to call here.";
            default -> "Review this part of your expression.";
        };
        String reason = kind.equals("insert") ? "The comparison suggests that something is missing near this part of your expression."
                : kind.equals("delete") ? "The comparison suggests that this part of your expression may not be needed."
                : "The comparison suggests checking the operator, name, or value used here.";
        return op.put("action", action).put("description", action).put("reason", reason)
                .put("nextStep", kind.equals("insert") ? "Look for a missing value, relation, or operator. Several addition hints may belong to the same expression."
                        : kind.equals("delete") ? "Review the highlighted part and what surrounds it. If you remove it, check that the remaining expression still compiles."
                        : "Find the matching part of your code, consider one change, and check again. The simplified form may look different from what you typed.");
    }

    private static String renderSource(EGraphNode node) {
        WorkBudget.charge(1);
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
