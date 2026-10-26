package is.fivefivefive.CanDis;

import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;
import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Bounded, name-free occurrence index of the already prepared learner graph.
 *
 * This is the selected canonical representative, not an enumeration of private
 * references, rewrite alternatives, or intermediate repair states. Addresses
 * retain child-edge occurrences; a repeated nodeId denotes Java object sharing,
 * not a claim that distinct nodes belong to different equivalence classes.
 * The owner invokes this optional projection only AFTER its metric budget ends.
 */
public final class LearnerStructure {
    public static final int MAX_NODES = 2048;
    public static final int MAX_PHASES = 128;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_BYTES = 262144;
    public static final int MAX_MILLIS = 25;
    private static final int MAX_BINDINGS = 4096;
    private static final Set<String> CONTROL = Set.of("and", "or", "not", "implies", "iff", "if-then-else");
    private static final Set<String> COMPARISON = Set.of("=", "!=", ">", ">=", "in", "<", "<=", "!>", "!>=", "!in", "!<", "!<=", "disj");
    private static final Set<String> RELATION = Set.of("->", ".", "<:", ":>", "&", "++", "~", "^");
    private static final Set<String> MULTIPLICITY = Set.of("some", "no", "one", "lone", "all", "set", "exactly", "#");
    private static final Set<String> ARITHMETIC = Set.of("/", "%", "<<", ">>", ">>>", "int", "Int", "sum");
    private static final Set<String> TEMPORAL = Set.of("'", "before", "historically", "once", "always", "eventually", "after", "until", "releases", "since", "triggered");
    private static final Set<String> AMBIGUOUS = Set.of("+", "-", "*");
    private static final Set<String> QUANTIFIERS = Set.of("all", "some", "no", "one", "lone", "sum");

    private LearnerStructure() { }

    public static JSONObject export(Canonical.Prepared learner) {
        return export(learner, MAX_NODES, MAX_DEPTH, MAX_BYTES, MAX_MILLIS);
    }

    /** Explicit smaller bounds are used by finite regression witnesses only. */
    static JSONObject export(Canonical.Prepared learner, int nodes, int depth, int bytes, int millis) {
        if (WorkBudget.active()) throw new IllegalStateException("Optional structure export needs a closed metric budget");
        if (nodes < 1 || nodes > MAX_NODES || depth < 0 || depth > MAX_DEPTH
                || bytes < 1024 || bytes > MAX_BYTES || millis < 1 || millis > MAX_MILLIS)
            throw new IllegalArgumentException("Invalid structure limits");
        Index index = new Index(nodes, depth, bytes, millis);
        try {
            List<NormalForm> forms = learner.normalizedForms();
            for (int phase = 0; phase < forms.size(); phase++) {
                index.guard();
                if (phase >= Math.min(nodes, MAX_PHASES)) throw new Limit("node_limit");
                NormalForm form = forms.get(phase);
                List<QuantiVar> bindings = form.getMatrixQuantiVars();
                if (bindings.size() > MAX_BINDINGS) throw new Limit("node_limit");
                index.phase(phase, bindings.size());
                @SuppressWarnings("unchecked") List<QuantiVar> ordered =
                        (List<QuantiVar>) Pinned.BINDING_ORDER.invoke(null, bindings);
                for (int binding = 0; binding < ordered.size(); binding++) {
                    QuantiVar variable = ordered.get(binding);
                    String op = variable.getQuantifier().name().toLowerCase(Locale.ROOT);
                    index.add(variable, address(phase, binding, List.of()), "BINDING",
                            QUANTIFIERS.contains(op) ? op : null, 0);
                }
                index.matrix(form.getMatrixEGraph(), phase, new ArrayList<>());
            }
            Object delegate = Pinned.DELEGATE.get(learner);
            index.temporal(Pinned.TREE.get(delegate), new ArrayList<>());
        } catch (Limit limit) {
            index.reason = limit.reason;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // No exceptions or reflected values leave this optional boundary.
            return unavailable("unavailable");
        }
        return index.result();
    }

    public static JSONObject unavailable(String reason) {
        return envelope("unavailable", new JSONArray(), new JSONArray())
                .put("reason", reason.equals("byte_limit") ? reason : "unavailable");
    }

    private static JSONObject envelope(String status, JSONArray nodes, JSONArray phases) {
        return new JSONObject().put("version", 1).put("status", status)
                .put("representation", "learner-canonical-occurrences")
                .put("nodes", nodes).put("phases", phases)
                .put("limits", new JSONObject().put("nodes", MAX_NODES).put("depth", MAX_DEPTH)
                        .put("bytes", MAX_BYTES).put("millis", MAX_MILLIS).put("phases", MAX_PHASES));
    }

    private static JSONArray address(Object phase, Object binding, List<Integer> inside) {
        return new JSONArray().put(phase).put(binding).put(new JSONArray(inside));
    }

    private static final class Limit extends RuntimeException {
        final String reason;
        Limit(String reason) { super(null, null, false, false); this.reason = reason; }
    }

    private static final class Index {
        final int maxNodes, maxDepth, maxBytes;
        final long started = System.nanoTime(), nanos;
        final JSONArray nodes = new JSONArray(), phases = new JSONArray();
        final Map<Object, String> ids = new IdentityHashMap<>();
        final Set<Object> active = Collections.newSetFromMap(new IdentityHashMap<>());
        // All projected strings are fixed ASCII tokens. Reserving envelope and
        // delimiters lets each row be charged before it enters retained output.
        int bytes = 1024;
        String reason;

        Index(int nodes, int depth, int bytes, int millis) {
            maxNodes = nodes; maxDepth = depth; maxBytes = bytes; nanos = millis * 1_000_000L;
        }

        void guard() {
            long elapsed = System.nanoTime() - started;
            if (elapsed < 0 || elapsed >= nanos) throw new Limit("time_limit");
        }

        void phase(int phase, int count) {
            JSONObject row = new JSONObject().put("index", phase).put("bindingCount", count);
            reserve(row);
            phases.put(row);
        }

        void reserve(JSONObject row) {
            guard();
            int size = row.toString().length() + 1;
            if (size > maxBytes - bytes) throw new Limit("byte_limit");
            bytes += size;
        }

        void add(Object identity, JSONArray address, String category, String operator, int childCount) {
            guard();
            if (nodes.length() >= maxNodes) throw new Limit("node_limit");
            String id = ids.computeIfAbsent(identity, ignored -> "n" + ids.size());
            JSONObject row = new JSONObject().put("address", address).put("nodeId", id)
                    .put("category", category).put("childCount", childCount);
            if (operator != null) row.put("operator", operator);
            reserve(row);
            nodes.put(row);
        }

        void enter(Object node, List<Integer> path) {
            guard();
            if (path.size() > maxDepth) throw new Limit("depth_limit");
            if (node == null || !active.add(node)) throw new Limit("unavailable");
        }

        void matrix(EGraphNode node, int phase, List<Integer> path) {
            enter(node, path);
            List<EGraphNode> children = node.getChildren();
            String operator = LiveTrace.operator(node);
            add(node, address(phase, "M", path), category(node, operator), operator, children.size());
            for (int child = 0; child < children.size(); child++) {
                path.add(child);
                matrix(children.get(child), phase, path);
                path.remove(path.size() - 1);
            }
            active.remove(node);
        }

        void temporal(Object node, List<Integer> path) throws ReflectiveOperationException {
            enter(node, path);
            @SuppressWarnings("unchecked") List<Object> children = (List<Object>) Pinned.CHILDREN.get(node);
            String operator = ((String) Pinned.LABEL.get(node)).toLowerCase(Locale.ROOT);
            add(node, address("T", "_", path), "TEMPORAL", TEMPORAL.contains(operator) ? operator : null,
                    children.size());
            for (int child = 0; child < children.size(); child++) {
                path.add(child);
                temporal(children.get(child), path);
                path.remove(path.size() - 1);
            }
            active.remove(node);
        }

        JSONObject result() {
            JSONObject result = envelope(reason == null ? "ok" : "partial", nodes, phases);
            if (reason != null) result.put("reason", reason);
            return result;
        }
    }

    /** Same precedence as LiveTrace sourceNodeKind and repair_grammar._subject. */
    private static String category(EGraphNode node, String operator) {
        switch (node.getOpcode()) {
            case VARIABLE: return "VARIABLE_USE";
            case GLOBALBINDING, REF: return "REFERENCE";
            case CONSTANT: return "CONSTANT";
            case CALL: return "CALL";
            default: break;
        }
        if (operator == null) return "STRUCTURE";
        if (CONTROL.contains(operator)) return "CONTROL";
        if (COMPARISON.contains(operator)) return "COMPARISON";
        if (RELATION.contains(operator) || operator.contains("->")) return "RELATION";
        if (MULTIPLICITY.contains(operator)) return "MULTIPLICITY";
        if (ARITHMETIC.contains(operator)) return "ARITHMETIC";
        if (TEMPORAL.contains(operator)) return "TEMPORAL";
        if (AMBIGUOUS.contains(operator)) return "OPERATOR";
        return "STRUCTURE";
    }

    /** Read the pinned Prepared snapshot rather than reconstructing its tree.
     * This avoids numbering drift from merged binary temporal branches. */
    private static final class Pinned {
        static final Field DELEGATE = field(Canonical.Prepared.class, "delegate");
        static final Field TREE = field(CanonicalDistance.Prepared.class, "temporalTree");
        static final Class<?> TEMPORAL_TREE = TREE.getType();
        static final Field LABEL = field(TEMPORAL_TREE, "label");
        static final Field CHILDREN = field(TEMPORAL_TREE, "children");
        static final Method BINDING_ORDER = bindingOrder();
        private static Field field(Class<?> type, String name) {
            try { Field value = type.getDeclaredField(name); value.setAccessible(true); return value; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned learner structure unavailable"); }
        }
        private static Method bindingOrder() {
            try {
                Method method = CanonicalDistance.class.getDeclaredMethod("canonicalQuantifierOrder", List.class);
                method.setAccessible(true);
                return method;
            } catch (ReflectiveOperationException error) { throw new IllegalStateException("Pinned learner bindings unavailable"); }
        }
    }
}
