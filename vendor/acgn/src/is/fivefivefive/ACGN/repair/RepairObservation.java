package is.fivefivefive.ACGN.repair;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.ast.*;
import is.fivefivefive.CanDis.metric.RepairView;

/** Structured, read-only policy projection. Contains neither oracle text nor authority. */
public final class RepairObservation {
    public static final String SCHEMA = "repair-structured-graphs-v1";
    public static final int MAX_NODES = 4096;
    private final JSONArray nodes = new JSONArray();
    private final JSONArray edges = new JSONArray();
    private final Map<String, Integer> sourcePaths = new HashMap<>();

    private RepairObservation() { }

    public static JSONObject encode(RepairEnvironment.State state, RepairActionEnumerator.Menu menu,
                                    RepairEvaluator.Score score) {
        RepairObservation builder = new RepairObservation();
        int root = builder.node("ROOT", "", "", 0, 0, 0);
        builder.edge(root, builder.source(state.candidate.expression(), "root", 0, new IdentityHashMap<>()), "SOURCE", 0);
        builder.edge(root, builder.temporal(state.repairView().temporalRoot(), 0), "TEMPORAL", 0);
        for (RepairView.Phase phase : state.repairView().phases()) {
            int phaseId = builder.node("PHASE", "", "", phase.bindings().size(), 0, 0);
            builder.edge(root, phaseId, "PHASE", 0);
            int[] bindings = new int[phase.bindings().size()];
            for (int i = 0; i < bindings.length; i++) {
                RepairView.Binding binding = phase.bindings().get(i);
                RepairView.Declaration decl = binding.declaration();
                bindings[i] = builder.node("BINDING:" + decl.quantifier(), decl.type(),
                        binding.role() + ":" + decl.cardinality(), decl.disjointnessClass() >= 0 ? 1 : 0,
                        binding.certifiedOrbit().size(), 0);
                builder.edge(phaseId, bindings[i], "BINDING", i);
            }
            for (int i = 0; i < bindings.length; i++) {
                for (int dependency : phase.bindings().get(i).declaration().dependencies()) {
                    if (dependency >= 0 && dependency < bindings.length) builder.edge(bindings[i], bindings[dependency], "DEPENDS", 0);
                }
                for (int j = 0; j < i; j++) {
                    int d = phase.bindings().get(i).declaration().disjointnessClass();
                    if (d >= 0 && d == phase.bindings().get(j).declaration().disjointnessClass()) {
                        builder.edge(bindings[i], bindings[j], "DISJOINT", 0);
                    }
                }
            }
            if (phase.matrix() != null) builder.edge(phaseId, builder.matrix(phase.matrix(), bindings, 0), "MATRIX", 0);
        }
        JSONArray actions = new JSONArray();
        for (RepairAction action : menu.actions()) {
            Integer occurrence = builder.sourcePaths.get(action.path());
            if (occurrence == null) throw new IllegalStateException("Action has no source occurrence");
            actions.put(new JSONObject().put("node", occurrence).put("kind", action.kind().name())
                    .put("operation", action.operation()).put("numeric", new JSONArray(new double[] {
                        Math.min(1.0, (action.end() - action.start()) / 1000.0),
                        Math.min(1.0, action.replacement().length() / 1000.0)})));
        }
        return new JSONObject().put("schema", SCHEMA).put("nodes", builder.nodes).put("edges", builder.edges)
                .put("actions", actions).put("numeric", new JSONArray(new double[] {
                    Math.min(1.0, state.control().remaining() / 32.0),
                    Math.min(1.0, state.control().edits() / 32.0), score.potential() / 1000.0,
                    Math.min(1.0, score.positiveCount() / 100.0), Math.min(1.0, score.negativeCount() / 100.0),
                    score.positiveCount() == 0 ? 0 : (double) score.positiveErrors() / score.positiveCount(),
                    score.negativeCount() == 0 ? 0 : (double) score.negativeErrors() / score.negativeCount(),
                    menu.truncated() ? 1 : 0}));
    }

    private int node(String op, String type, String container, int arity, int depth, int domain) {
        if (nodes.length() >= MAX_NODES) throw new RepairTask.UnsupportedInput("Structured observation exceeds node bound");
        int id = nodes.length();
        nodes.put(new JSONObject().put("categories", new JSONArray(List.of(op, type, container)))
                .put("numeric", new JSONArray(new double[] {Math.min(arity, 64) / 64.0,
                    Math.min(depth, 128) / 128.0, domain})));
        return id;
    }

    private void edge(int from, int to, String kind, int position) {
        edges.put(new JSONArray(List.of(from, to, kind, position)));
    }

    private int temporal(RepairView.TemporalNode node, int depth) {
        int id = node("TEMPORAL:" + node.label(), "FORMULA", "SEQUENCE", node.children().size(), depth, 1);
        for (int i = 0; i < node.children().size(); i++) edge(id, temporal(node.children().get(i), depth + 1), "TEMPORAL_CHILD", i);
        return id;
    }

    private int matrix(RepairView.Node node, int[] bindings, int depth) {
        int id = node("CANONICAL:" + node.operator(), "", node.containerKind().name(), node.children().size(), depth, 1);
        if (node.isVariable() && node.bindingIndex() >= 0) edge(id, bindings[node.bindingIndex()], "USE", 0);
        if (!node.isVariable() && node.payload() != null && node.children().isEmpty()) {
            edge(id, node("ATOM", node.payload(), "", 0, depth + 1, 1), "PAYLOAD", 0);
        }
        for (int i = 0; i < node.children().size(); i++) {
            edge(id, matrix(node.children().get(i), bindings, depth + 1), "CHILD", node.orderInsensitive() ? 0 : i + 1);
        }
        for (RepairView.Node alternate : node.certifiedAlternatives()) edge(id, matrix(alternate, bindings, depth + 1), "ALTERNATIVE", 0);
        return id;
    }

    private int source(Expr expression, String path, int depth, Map<Expr, Integer> scope) {
        String opcode = expression.getClass().getSimpleName();
        if (expression instanceof ExprUnary u) opcode = u.op.name();
        if (expression instanceof ExprBinary b) opcode = b.op.name();
        if (expression instanceof ExprList l) opcode = l.op.name();
        if (expression instanceof ExprQt q) opcode = q.op.name();
        if (expression instanceof ExprConstant c) opcode = c.op.name();
        if (expression instanceof ExprCall c) opcode = "CALL:" + c.fun.label;
        if (expression instanceof Sig s) opcode = "SIG:" + s.label;
        if (expression instanceof Sig.Field f) opcode = "FIELD:" + f.label;
        List<Expr> children = RepairTask.children(expression);
        int id = node("SOURCE:" + opcode, expression.type().toString(), "SEQUENCE", children.size(), depth, 0);
        sourcePaths.put(path, id);
        if (expression instanceof ExprVar && scope.containsKey(expression)) edge(id, scope.get(expression), "USE", 0);
        if (expression instanceof ExprQt q) {
            Map<Expr, Integer> nested = new IdentityHashMap<>(scope);
            int index = 0;
            for (Decl decl : q.decls) {
                int domain = source(decl.expr, path + "/" + index++, depth + 1, nested);
                edge(id, domain, "DOMAIN", index);
                for (ExprHasName variable : decl.names) {
                    int binder = node("SOURCE_BINDER:" + q.op.name(), variable.type().toString(),
                            decl.disjoint == null ? "ORDINARY" : "DISJOINT", 0, depth + 1, 0);
                    nested.put(variable, binder);
                    edge(id, binder, "DECLARATION", index);
                    edge(binder, domain, "DOMAIN", 0);
                }
            }
            edge(id, source(q.sub, path + "/" + index, depth + 1, nested), "BODY", 0);
        } else if (expression instanceof ExprLet l) {
            int value = source(l.expr, path + "/0", depth + 1, scope);
            edge(id, value, "VALUE", 0);
            Map<Expr, Integer> nested = new IdentityHashMap<>(scope);
            nested.put(l.var, value);
            edge(id, source(l.sub, path + "/1", depth + 1, nested), "BODY", 0);
        } else {
            for (int i = 0; i < children.size(); i++) edge(id, source(children.get(i), path + "/" + i, depth + 1, scope), "SOURCE_CHILD", i + 1);
        }
        return id;
    }
}
