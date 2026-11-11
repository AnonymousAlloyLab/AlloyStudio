package is.fivefivefive.ACGN.repair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.ast.*;

/** Exact numeric source tables. Source text and display names stay in the executor. */
final class FeedbackSourceGraph {
    static final int MAX_ROWS = 8192;
    static final List<String> OPERATORS = operators();
    final RepairEnvironment.State state;
    final List<Expr> expressions = new ArrayList<>();
    final List<ExprHasName> variables = new ArrayList<>();
    final List<Map<Expr, Integer>> scopes = new ArrayList<>();
    final List<int[]> spans = new ArrayList<>();
    final List<List<Integer>> children = new ArrayList<>();
    final List<Expr> symbols = new ArrayList<>();
    final List<Type> exactTypes = new ArrayList<>();
    private final Map<Expr, Integer> symbolIds = new IdentityHashMap<>();
    private final Map<Type, Integer> typeIds = new HashMap<>();
    private final JSONObject packet = new JSONObject();
    private final JSONArray nodes = new JSONArray(), ports = new JSONArray(), types = new JSONArray();
    private final JSONArray typePorts = new JSONArray(), symbolRows = new JSONArray(), parents = new JSONArray();
    private final JSONArray bindings = new JSONArray(), scopeRows = new JSONArray(), occurrences = new JSONArray();
    private final JSONArray phases = new JSONArray();
    private int nextDisjoint;
    private final FeedbackSourceSpans sourceSpans;

    FeedbackSourceGraph(RepairEnvironment.State state) {
        this.state = state;
        sourceSpans = new FeedbackSourceSpans(state.body());
        phases.put(row(-1, -1, -1));
        visit(state.candidate.expression(), new LinkedHashMap<>(), -1, 0, 0);
        packet.put("nodes", nodes).put("ports", ports).put("types", types).put("typePorts", typePorts)
                .put("symbols", symbolRows).put("symbolParents", parents).put("bindings", bindings)
                .put("scopes", scopeRows).put("occurrences", occurrences).put("phases", phases);
        new FeedbackCanonicalGraph(this, packet);
        if (rowCount() > MAX_ROWS) throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
    }

    JSONObject json() { return new JSONObject(packet.toString()); }
    int rowCount() {
        int canonical = 0;
        for (String key : packet.keySet()) if (key.startsWith("canonical")) canonical += packet.getJSONArray(key).length();
        return canonical + nodes.length() + ports.length() + types.length() + typePorts.length()
                + symbolRows.length() + parents.length() + bindings.length() + scopeRows.length() + occurrences.length() + phases.length();
    }
    static JSONArray row(int... values) {
        JSONArray r = new JSONArray();
        for (int value : values) r.put(value);
        return r;
    }
    static int opcode(String name) {
        int id = OPERATORS.indexOf(name);
        if (id < 0) throw new RepairTask.UnsupportedInput("OPERATOR_UNSUPPORTED");
        return id;
    }

    private static List<String> operators() {
        List<String> result = new ArrayList<>(List.of("SIG", "FIELD", "VAR", "CALL", "LET", "ITE"));
        for (var op : ExprUnary.Op.values()) result.add("U:" + op.name());
        for (var op : ExprBinary.Op.values()) result.add("B:" + op.name());
        for (var op : ExprList.Op.values()) result.add("L:" + op.name());
        for (var op : ExprQt.Op.values()) result.add("Q:" + op.name());
        for (var op : ExprConstant.Op.values()) result.add("C:" + op.name());
        if (result.size() > 128) throw new IllegalStateException("Operator dictionary exceeds 128 entries");
        return List.copyOf(result);
    }

    private int type(Type type) {
        Integer old = typeIds.get(type);
        if (old != null) return old;
        int id = types.length();
        typeIds.put(type, id);
        exactTypes.add(type);
        types.put(row(type.is_bool ? 1 : 0, type.is_int() ? 1 : 0,
                type.is_small_int() ? 1 : 0, type.arity()));
        int product = 0;
        for (List<Sig.PrimSig> columns : type.fold()) {
            for (int column = 0; column < columns.size(); column++) {
                typePorts.put(row(id, product, column, symbol(columns.get(column))));
                requireBudget();
            }
            product++;
        }
        return id;
    }

    private int symbol(Expr expression) {
        Integer old = symbolIds.get(expression);
        if (old != null) return old;
        int id = symbols.size();
        symbols.add(expression);
        symbolIds.put(expression, id);
        symbolRows.put(new JSONArray());
        int kind, owner = -1, builtin = -1;
        if (expression instanceof Sig sig) {
            kind = sig instanceof Sig.SubsetSig ? 1 : 0;
            if (sig == Sig.UNIV) builtin = 0;
            else if (sig == Sig.NONE) builtin = 1;
            else if (sig == Sig.SIGINT) builtin = 2;
            else if (sig == Sig.SEQIDX) builtin = 3;
            else if (sig == Sig.STRING) builtin = 4;
            if (sig instanceof Sig.PrimSig p && p.parent != null) parents.put(row(id, symbol(p.parent)));
            if (sig instanceof Sig.SubsetSig s) for (Sig p : s.parents) parents.put(row(id, symbol(p)));
        } else if (expression instanceof Sig.Field field) {
            kind = 2;
            owner = symbol(field.sig);
        } else throw new RepairTask.UnsupportedInput("SYMBOL_UNSUPPORTED");
        int abstractSig = expression instanceof Sig sig && sig.isAbstract != null ? 1 : 0;
        int multiplicity = expression instanceof Sig sig ? (sig.isOne != null ? 1
                : sig.isLone != null ? 2 : sig.isSome != null ? 3 : 0) : 0;
        int mutable = expression instanceof Sig sig ? (sig.isVariable != null ? 1 : 0)
                : expression instanceof Sig.Field field && field.isVariable != null ? 1 : 0;
        symbolRows.put(id, row(kind, type(expression.type()), owner, builtin, abstractSig, multiplicity, mutable));
        return id;
    }

    private int visit(Expr expr, Map<Expr, Integer> scope, int context, int depth, int phase) {
        edu.mit.csail.sdg.alloy4.Pos occurrence = null;
        while (expr instanceof ExprUnary unary && unary.op == ExprUnary.Op.NOOP) {
            occurrence = unary.pos;
            expr = unary.sub;
            if (++depth > 128) throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
        }
        if (depth > 128 || rowCount() + scope.size() + 2 > MAX_ROWS) {
            throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
        }
        String op;
        int payload = -1;
        if (expr instanceof ExprUnary u) op = "U:" + u.op.name();
        else if (expr instanceof ExprBinary b) op = "B:" + b.op.name();
        else if (expr instanceof ExprList l) op = "L:" + l.op.name();
        else if (expr instanceof ExprQt q) op = "Q:" + q.op.name();
        else if (expr instanceof ExprConstant c) {
            if (c.op == ExprConstant.Op.STRING) throw new RepairTask.UnsupportedInput("STRING_LITERAL_UNSUPPORTED");
            op = "C:" + c.op.name(); payload = c.num;
        } else if (expr instanceof Sig) { op = "SIG"; payload = symbol(expr); }
        else if (expr instanceof Sig.Field) { op = "FIELD"; payload = symbol(expr); }
        else if (expr instanceof ExprVar) {
            op = "VAR";
            if (!scope.containsKey(expr)) throw new RepairTask.UnsupportedInput("UNBOUND_SOURCE_VARIABLE");
            payload = scope.get(expr);
        } else if (expr instanceof ExprLet) op = "LET";
        else if (expr instanceof ExprITE) op = "ITE";
        else if (expr instanceof ExprCall) {
            // CALL declaration/type tables are not yet part of this observation schema.
            throw new RepairTask.UnsupportedInput("CALL_TABLE_UNSUPPORTED");
        } else throw new RepairTask.UnsupportedInput("SOURCE_NODE_UNSUPPORTED");
        int id = expressions.size();
        expressions.add(expr);
        scopes.add(new LinkedHashMap<>(scope));
        children.add(new ArrayList<>());
        edu.mit.csail.sdg.alloy4.Pos location = (expr instanceof ExprHasName || expr instanceof Sig) && occurrence != null
                ? occurrence : expr.span();
        int[] span = location.toStartEnd(state.task.source(state.body()));
        int start = -1, end = -1;
        if (id == 0) { start = 0; end = state.body().length(); }
        else if (span != null && location.filename.equals(RepairTask.VIRTUAL_FILE)) {
            start = span[0] - state.task.bodyStart(); end = span[1] - state.task.bodyStart();
            if (start < 0 || end > state.body().length() || start >= end) { start = -1; end = -1; }
        }
        if (start >= 0) {
            int[] complete = sourceSpans.complete(start, end); start = complete[0]; end = complete[1];
        }
        spans.add(new int[] {start, end});
        nodes.put(row(opcode(op), type(expr.type()), payload, phase, context));
        occurrences.put(row(id, start < 0 ? 0 : 1));
        for (int binding : scope.values()) scopeRows.put(row(id, binding));
        if (expr instanceof ExprQt q) {
            Map<Expr, Integer> nested = new LinkedHashMap<>(scope);
            int position = 0;
            for (Decl d : q.decls) {
                int domain = visit(d.expr, nested, id, depth + 1, phase);
                edge(id, domain, position++, 1);
                int disj = d.disjoint == null ? -1 : nextDisjoint++;
                int disj2 = d.disjoint2 == null ? -1 : nextDisjoint++;
                for (ExprHasName variable : d.names) {
                    int binder = variables.size();
                    variables.add(variable);
                    bindings.put(row(id, type(variable.type()), opcode("Q:" + q.op.name()), domain, disj,
                            disj2, phase, -1));
                    nested.entrySet().removeIf(e -> e.getKey() instanceof ExprHasName name
                            && name.label.equals(variable.label));
                    nested.put(variable, binder);
                }
            }
            edge(id, visit(q.sub, nested, id, depth + 1, phase), position, 2);
        } else if (expr instanceof ExprLet let) {
            int value = visit(let.expr, scope, context, depth + 1, phase);
            edge(id, value, 0, 1);
            Map<Expr, Integer> nested = new LinkedHashMap<>(scope);
            int binder = variables.size(); variables.add(let.var);
            bindings.put(row(id, type(let.var.type()), opcode("LET"), value, -1, -1, phase, -1));
            nested.entrySet().removeIf(e -> e.getKey() instanceof ExprHasName name && name.label.equals(let.var.label));
            nested.put(let.var, binder);
            edge(id, visit(let.sub, nested, id, depth + 1, phase), 1, 2);
        } else {
            int position = 0;
            for (Expr child : RepairTask.children(expr)) {
                int childPhase = phase;
                Map<Expr, Integer> nested = scope;
                if (temporal(expr)) {
                    childPhase = phases.length();
                    phases.put(row(phase, id, position));
                    nested = new LinkedHashMap<>();
                    for (var entry : scope.entrySet()) {
                        int imported = variables.size(); variables.add((ExprHasName) entry.getKey());
                        JSONArray owner = bindings.getJSONArray(entry.getValue());
                        bindings.put(row(owner.getInt(0), owner.getInt(1), owner.getInt(2), owner.getInt(3),
                                owner.getInt(4), owner.getInt(5), childPhase, entry.getValue()));
                        nested.put(entry.getKey(), imported);
                    }
                }
                edge(id, visit(child, nested, context, depth + 1, childPhase), position++, 0);
            }
        }
        return id;
    }

    private void requireBudget() {
        if (rowCount() > MAX_ROWS) throw new RepairTask.UnsupportedInput("INPUT_BUDGET_EXCEEDED");
    }

    static boolean temporal(Expr expr) {
        return expr instanceof ExprUnary u && Set.of(ExprUnary.Op.AFTER, ExprUnary.Op.ALWAYS,
                ExprUnary.Op.EVENTUALLY, ExprUnary.Op.BEFORE, ExprUnary.Op.HISTORICALLY,
                ExprUnary.Op.ONCE, ExprUnary.Op.PRIME).contains(u.op)
                || expr instanceof ExprBinary b && Set.of(ExprBinary.Op.UNTIL, ExprBinary.Op.RELEASES,
                ExprBinary.Op.SINCE, ExprBinary.Op.TRIGGERED).contains(b.op);
    }

    private void edge(int parent, int child, int position, int role) {
        children.get(parent).add(child);
        ports.put(row(parent, child, position, role));
        requireBudget();
    }

    String source(int node) {
        int[] span = spans.get(node);
        if (span[0] < 0) throw new IllegalArgumentException("SOURCE_OCCURRENCE_UNAVAILABLE");
        return state.body().substring(span[0], span[1]);
    }

    Set<Integer> freeBindings(int node) {
        Set<Integer> used = new java.util.HashSet<>();
        Set<Integer> local = new java.util.HashSet<>();
        collect(node, used, local);
        used.removeAll(local);
        return used;
    }

    int bindingOrigin(int binding) {
        while (bindings.getJSONArray(binding).getInt(7) >= 0) binding = bindings.getJSONArray(binding).getInt(7);
        return binding;
    }

    boolean canCopy(int source, int destination) {
        Set<Integer> available = new java.util.HashSet<>();
        for (int binding : scopes.get(destination).values()) available.add(bindingOrigin(binding));
        for (int used : freeBindings(source)) if (!available.contains(bindingOrigin(used))) return false;
        return true;
    }

    private void collect(int node, Set<Integer> used, Set<Integer> local) {
        if (expressions.get(node) instanceof ExprVar) used.add(nodes.getJSONArray(node).getInt(2));
        for (int i = 0; i < bindings.length(); i++) if (bindings.getJSONArray(i).getInt(0) == node) local.add(i);
        for (int child : children.get(node)) collect(child, used, local);
    }
}
