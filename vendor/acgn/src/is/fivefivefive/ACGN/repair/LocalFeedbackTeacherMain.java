package is.fivefivefive.ACGN.repair;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import edu.mit.csail.sdg.ast.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline training labels only. This class is never called by the policy worker. */
public final class LocalFeedbackTeacherMain {
    private LocalFeedbackTeacherMain() { }
    private record Edit(int site, Expr target) { }

    private static Expr strip(Expr expr) {
        while (expr instanceof ExprUnary u && u.op == ExprUnary.Op.NOOP) expr = u.sub;
        return expr;
    }

    // Proposal matching only: a final real Alloy check remains mandatory.
    static String structuralKey(Expr expr) { return key(expr, new IdentityHashMap<>()).toString(); }
    private static JSONArray key(Expr expr, Map<Expr, Integer> scope) {
        expr = strip(expr);
        JSONArray out = new JSONArray();
        if (expr instanceof Sig s) return out.put("sig").put(s.label);
        if (expr instanceof Sig.Field f) return out.put("field").put(f.sig.label).put(f.label);
        if (expr instanceof ExprVar v) return out.put("var").put(scope.containsKey(v) ? scope.get(v) : v.label);
        if (expr instanceof ExprConstant c) return out.put("constant").put(c.op.name()).put(c.num);
        if (expr instanceof ExprQt q) {
            Map<Expr, Integer> nested = new IdentityHashMap<>(scope);
            out.put("qt").put(q.op.name());
            for (Decl d : q.decls) {
                out.put(new JSONArray().put(d.names.size()).put(d.disjoint != null).put(d.disjoint2 != null)
                        .put(key(d.expr, nested)));
                for (ExprHasName n : d.names) nested.put(n, nested.size());
            }
            return out.put(key(q.sub, nested));
        }
        if (expr instanceof ExprUnary u) out.put("unary").put(u.op.name());
        else if (expr instanceof ExprBinary b) out.put("binary").put(b.op.name());
        else if (expr instanceof ExprList l) out.put("list").put(l.op.name());
        else return out.put("unsupported").put(expr.getClass().getName()).put(expr.toString());
        for (Expr child : RepairTask.children(expr)) out.put(key(child, scope));
        return out;
    }

    private static boolean sameHeader(Expr a, Expr b) {
        if (a instanceof ExprUnary x && b instanceof ExprUnary y) return x.op == y.op;
        if (a instanceof ExprBinary x && b instanceof ExprBinary y) return x.op == y.op;
        if (a instanceof ExprList x && b instanceof ExprList y) return x.op == y.op;
        if (a instanceof ExprQt x && b instanceof ExprQt y) {
            if (x.op != y.op || x.decls.size() != y.decls.size()) return false;
            for (int i = 0; i < x.decls.size(); i++) {
                Decl p = x.decls.get(i), q = y.decls.get(i);
                if (p.names.size() != q.names.size() || (p.disjoint == null) != (q.disjoint == null)
                        || (p.disjoint2 == null) != (q.disjoint2 == null)
                        || !structuralKey(p.expr).equals(structuralKey(q.expr))) return false;
                for (int j = 0; j < p.names.size(); j++) if (!p.names.get(j).label.equals(q.names.get(j).label)) return false;
            }
            return true;
        }
        return false;
    }

    private static Edit difference(FeedbackSourceGraph graph, int site, Expr target) {
        target = strip(target);
        Expr old = graph.expressions.get(site);
        if (structuralKey(old).equals(structuralKey(target))) return null;
        List<Expr> children = RepairTask.children(target);
        if (sameHeader(old, target) && graph.children.get(site).size() == children.size()) {
            for (int i = 0; i < children.size(); i++) {
                Edit edit = difference(graph, graph.children.get(site).get(i), children.get(i));
                if (edit != null && graph.spans.get(edit.site)[0] >= 0) return edit;
            }
        }
        return new Edit(site, target);
    }

    private static void outerBindings(Expr expr, Map<Expr, Integer> result,
                                      Map<Expr, Integer> available, Set<Expr> local) {
        expr = strip(expr);
        if (expr instanceof ExprVar var && !local.contains(var)) {
            List<Map.Entry<Expr, Integer>> matches = available.entrySet().stream()
                    .filter(e -> e.getKey() instanceof ExprHasName n && n.label.equals(var.label)).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("OUTER_BINDING_ALIGNMENT_UNAVAILABLE");
            result.put(expr, -matches.get(0).getValue() - 1);
            return;
        }
        if (expr instanceof ExprQt q) {
            Set<Expr> nested = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            nested.addAll(local);
            for (Decl decl : q.decls) {
                outerBindings(decl.expr, result, available, nested);
                nested.addAll(decl.names);
            }
            outerBindings(q.sub, result, available, nested);
        } else for (Expr child : RepairTask.children(expr)) outerBindings(child, result, available, local);
    }

    static JSONObject compile(RepairTask task, String oracle, int budget, int queries,
                              JSONArray prefix, JSONObject expected, boolean recoveryOnly) {
        FeedbackRepairSession session = new FeedbackRepairSession(task, oracle, budget, queries, true);
        for (Object raw : prefix) {
            JSONObject action = new JSONObject(raw.toString());
            JSONObject before = session.observation();
            if (before.getBoolean("closed") || before.getInt("remaining") <= 0) {
                throw new IllegalArgumentException("ROLLIN_TERMINATED");
            }
            action.put("revision", before.getInt("revision")).put("turn", before.getInt("turn"));
            session.decide(action);
        }
        JSONObject snapshot = session.observation();
        if (expected != null && !snapshot.similar(expected)) throw new IllegalArgumentException("REPLAY_DIVERGED");
        FeedbackTeacherMain teacher = new FeedbackTeacherMain(session, true);
        if (session.draftActive()) {
            teacher.emit(FeedbackRepairSession.Control.ABORT_DRAFT);
            if (recoveryOnly) {
                if (session.draftActive() || !snapshot.getJSONObject("current").similar(session.observation().getJSONObject("current"))) {
                    throw new IllegalStateException("RECOVERY_POSTCONDITION");
                }
                return new JSONObject().put("status", "CHECKED_RECOVERY").put("labels", teacher.labels)
                        .put("snapshot", snapshot).put("kind", "recovery");
            }
        } else if (recoveryOnly) throw new IllegalArgumentException("NO_RECOVERY_LABEL");
        if (session.queryCount() == 0) teacher.emit(FeedbackRepairSession.Control.VALIDATE);
        Expr target = task.evaluatorContext(oracle).oracle();
        JSONArray edits = new JSONArray();
        if (session.observation().getInt("status") != FeedbackRepairSession.Status.SOLVED_WITHIN_PROFILE.ordinal()) {
            for (int count = 0; count < 8; count++) {
                Edit edit = difference(session.sourceGraph(), 0, target);
                if (edit == null) break;
                Map<Expr, Integer> outer = new IdentityHashMap<>();
                outerBindings(edit.target, outer, session.sourceGraph().scopes.get(edit.site), Set.of());
                FeedbackTeacherMain builder = new FeedbackTeacherMain(session, true);
                builder.emit(FeedbackRepairSession.Control.BEGIN, "site", edit.site,
                        "family", FeedbackDraft.Family.MATRIX_UPDATE.ordinal());
                builder.build(edit.target, outer, 0);
                builder.emit(FeedbackRepairSession.Control.COMMIT);
                for (Object label : builder.labels) teacher.labels.put(label);
                edits.put(new JSONObject().put("site", edit.site).put("root", edit.site == 0)
                        .put("copiedOperands", builder.labels.toList().stream()
                                .filter(x -> x instanceof Map<?, ?> m && Integer.valueOf(1).equals(m.get("control"))).count()));
            }
            teacher.emit(FeedbackRepairSession.Control.STOP);
        }
        if (session.observation().getInt("status") != FeedbackRepairSession.Status.SOLVED_WITHIN_PROFILE.ordinal()) {
            throw new IllegalArgumentException("NO_VALIDATED_LOCAL_CONTINUATION");
        }
        return new JSONObject().put("status", "VALIDATED_LOCAL_CONTINUATION").put("kind", "continuation")
                .put("labels", teacher.labels).put("snapshot", snapshot).put("edits", edits)
                .put("optimality", "NOT_CLAIMED");
    }

    public static void main(String[] args) throws Exception {
        var output = System.out; System.setOut(System.err);
        JSONObject result;
        try {
            JSONObject request = new JSONObject(Files.readString(Path.of(args[0]), StandardCharsets.UTF_8));
            if (!request.getString("split").equals("train")) throw new IllegalArgumentException("TRAIN_ONLY");
            String oracle = request.getString("oracle");
            RepairTask task = RepairTask.forFeedback(Files.readString(Path.of(request.getString("path")), StandardCharsets.UTF_8),
                    request.getString("target"), request.getInt("command"), Set.of(oracle));
            result = compile(task, oracle, request.getInt("budget"), request.getInt("queryBudget"),
                    request.optJSONArray("prefix") == null ? new JSONArray() : request.getJSONArray("prefix"),
                    request.optJSONObject("snapshot"), request.optBoolean("recoveryOnly", false));
        } catch (Exception error) {
            error.printStackTrace(System.err);
            result = new JSONObject().put("status", "NO_ADMITTED_LABEL").put("reason", error.toString());
        }
        output.println(result);
    }
}
