package is.fivefivefive.ACGN.repair;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import edu.mit.csail.sdg.ast.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline label compiler only. Never reachable through the public policy worker. */
public final class FeedbackTeacherMain {
    final FeedbackRepairSession session;
    final JSONArray labels = new JSONArray();
    private int nextBinder;
    private final boolean reuse;

    private FeedbackTeacherMain(FeedbackRepairSession session) { this(session, false); }
    FeedbackTeacherMain(FeedbackRepairSession session, boolean reuse) { this.session = session; this.reuse = reuse; }

    void emit(FeedbackRepairSession.Control control, Object... pairs) {
        JSONObject before = session.observation();
        if (before.getBoolean("closed") || before.getInt("remaining") <= 0) {
            throw new IllegalArgumentException("NO_PATH_WITHIN_BUDGET");
        }
        JSONObject label = new JSONObject().put("control", control.ordinal());
        for (int i = 0; i < pairs.length; i += 2) label.put((String) pairs[i], pairs[i+1]);
        JSONObject action = new JSONObject(label.toString()).put("revision", before.getInt("revision"))
                .put("turn", before.getInt("turn"));
        JSONObject after = session.decide(action);
        var status = FeedbackRepairSession.Status.values()[after.getInt("status")];
        if (status == FeedbackRepairSession.Status.REJECTED || status == FeedbackRepairSession.Status.STALE
                || status == FeedbackRepairSession.Status.UNRESOLVED) {
            throw new IllegalArgumentException("SOURCE_UNREALIZABLE:" + control + ":" + session.lastFailure());
        }
        labels.put(label);
    }

    void build(Expr expr, Map<Expr, Integer> scope, int depth) {
        if (depth > 128) throw new IllegalArgumentException("NO_PATH_WITHIN_BUDGET");
        while (expr instanceof ExprUnary u && u.op == ExprUnary.Op.NOOP) expr = u.sub;
        if (reuse && !usesDraftBinding(expr, scope)) {
            // A spelling match proposes a COPY; public scope masks and final
            // source/semantic replay, not spelling, decide admissibility.
            for (Object row : session.observation().getJSONObject("actions").getJSONArray("copies")) {
                int pointer = ((JSONArray) row).getInt(0);
                if (LocalFeedbackTeacherMain.structuralKey(session.sourceGraph().expressions.get(pointer))
                        .equals(LocalFeedbackTeacherMain.structuralKey(expr))) {
                    emit(FeedbackRepairSession.Control.COPY, "pointer", pointer); return;
                }
            }
        }
        if (expr instanceof Sig sig) {
            if (sig == Sig.NONE || sig == Sig.UNIV) {
                emit(FeedbackRepairSession.Control.CONSTANT, "operator", sig == Sig.NONE ? 0 : 1, "value", 0);
            } else emitSymbol(sig);
        } else if (expr instanceof Sig.Field field) {
            emitSymbol(field);
        } else if (expr instanceof ExprVar var) {
            Integer pointer = scope.get(var);
            if (pointer == null) throw new IllegalArgumentException("ACTION_UNSUPPORTED:ORACLE_BINDER");
            emit(pointer < 0 ? FeedbackRepairSession.Control.VARIABLE : FeedbackRepairSession.Control.DRAFT_VARIABLE,
                    "pointer", pointer < 0 ? -pointer - 1 : pointer);
        } else if (expr instanceof ExprConstant c) {
            int kind = switch (c.op) {
                case EMPTYNESS -> 0; case IDEN -> 2; case TRUE -> 3; case FALSE -> 4; case NUMBER -> 5;
                default -> throw new IllegalArgumentException("ACTION_UNSUPPORTED:CONSTANT:" + c.op);
            };
            emit(FeedbackRepairSession.Control.CONSTANT, "operator", kind, "value", kind == 5 ? c.num : 0);
        } else if (expr instanceof ExprUnary u) {
            emit(FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:" + u.op.name()));
            build(u.sub, scope, depth + 1);
        } else if (expr instanceof ExprBinary b) {
            emit(FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("B:" + b.op.name()));
            build(b.left, scope, depth + 1); build(b.right, scope, depth + 1);
        } else if (expr instanceof ExprList list && (list.op == ExprList.Op.AND || list.op == ExprList.Op.OR)) {
            if (list.args.isEmpty()) {
                emit(FeedbackRepairSession.Control.CONSTANT, "operator", list.op == ExprList.Op.AND ? 3 : 4, "value", 0);
            } else {
                for (int i = 0; i < list.args.size(); i++) {
                    if (i < list.args.size()-1) emit(FeedbackRepairSession.Control.OPERATOR, "operator",
                            FeedbackSourceGraph.opcode("B:" + list.op.name()));
                    build(list.args.get(i), scope, depth + 1);
                }
            }
        } else if (expr instanceof ExprQt qt) {
            Map<Expr, Integer> nested = new IdentityHashMap<>(scope);
            for (int i = 0; i < qt.decls.size(); i++) {
                Decl decl = qt.decls.get(i);
                if (decl.disjoint2 != null) throw new IllegalArgumentException("ACTION_UNSUPPORTED:DISJOINT_RELATIONAL_COLUMNS");
                Expr domain = decl.expr;
                while (domain instanceof ExprUnary u && u.op == ExprUnary.Op.NOOP) domain = u.sub;
                int cardinality = 0;
                if (domain instanceof ExprUnary u) {
                    cardinality = switch (u.op) {
                        case ONEOF -> 0; case SETOF -> 1; case LONEOF -> 2; case SOMEOF -> 3; default -> -1;
                    };
                    if (cardinality >= 0) domain = u.sub; else cardinality = 0;
                }
                if (i == 0) emit(FeedbackRepairSession.Control.QUANTIFIER, "operator", FeedbackSourceGraph.opcode("Q:" + qt.op.name()),
                        "count", decl.names.size(), "disjoint", decl.disjoint == null ? 0 : 1, "cardinality", cardinality);
                else emit(FeedbackRepairSession.Control.DECLARATION, "count", decl.names.size(),
                        "disjoint", decl.disjoint == null ? 0 : 1, "cardinality", cardinality);
                Map<Expr, Integer> following = new IdentityHashMap<>(nested);
                for (ExprHasName name : decl.names) following.put(name, nextBinder++);
                build(domain, nested, depth + 1); nested = following;
            }
            build(qt.sub, nested, depth + 1);
        } else throw new IllegalArgumentException("ACTION_UNSUPPORTED:" + expr.getClass().getSimpleName());
    }

    private static boolean usesDraftBinding(Expr expr, Map<Expr, Integer> scope) {
        if (expr instanceof ExprVar) return scope.getOrDefault(expr, -1) >= 0;
        for (Expr child : RepairTask.children(expr)) if (usesDraftBinding(child, scope)) return true;
        return false;
    }

    private void emitSymbol(Expr named) {
        for (int i = 0; i < session.sourceGraph().symbols.size(); i++) {
            Expr visible = session.sourceGraph().symbols.get(i);
            // Both expressions are resolved in the fixed task module; names are only
            // an offline locator into the already-public source namespace.
            if (sameSymbol(named, visible)) {
                emit(FeedbackRepairSession.Control.SYMBOL, "pointer", i); return;
            }
        }
        var returned = session.feedbackSymbols();
        for (int i = 0; i < returned.size(); i++) if (sameSymbol(named, returned.get(i))) {
            emit(FeedbackRepairSession.Control.FEEDBACK_SYMBOL, "pointer", i); return;
        }
        throw new IllegalArgumentException("ACTION_UNSUPPORTED:SYMBOL_NOT_PUBLIC");
    }
    private static boolean sameSymbol(Expr named, Expr visible) {
        return named instanceof Sig a && visible instanceof Sig b && a.label.equals(b.label)
                || named instanceof Sig.Field field && visible instanceof Sig.Field other
                && field.label.equals(other.label) && field.sig.label.equals(other.sig.label);
    }

    static JSONObject compile(RepairTask task, String oracle, int budget) {
        FeedbackTeacherMain teacher = new FeedbackTeacherMain(new FeedbackRepairSession(task, oracle, budget));
        teacher.emit(FeedbackRepairSession.Control.VALIDATE);
        if (teacher.session.observation().getInt("status") != FeedbackRepairSession.Status.SOLVED_WITHIN_PROFILE.ordinal()) {
            teacher.emit(FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.MATRIX_UPDATE.ordinal());
            teacher.build(task.evaluatorContext(oracle).oracle(), new IdentityHashMap<>(), 0);
            teacher.emit(FeedbackRepairSession.Control.COMMIT);
            teacher.emit(FeedbackRepairSession.Control.STOP);
        }
        if (teacher.session.observation().getInt("status") != FeedbackRepairSession.Status.SOLVED_WITHIN_PROFILE.ordinal()) {
            throw new IllegalArgumentException("VALIDATION_INCONCLUSIVE_OR_FAILED");
        }
        return new JSONObject().put("schema", "feedback-semantic-teacher-v1").put("status", "VALIDATED_LABEL_PATH")
                .put("labels", teacher.labels).put("optimality", "NOT_CLAIMED");
    }

    public static void main(String[] args) throws Exception {
        var output = System.out; System.setOut(System.err);
        if (args.length != 5) throw new IllegalArgumentException("Expected source target oracle command budget");
        JSONObject result;
        try {
            String source = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
            RepairTask task = RepairTask.forFeedback(source, args[1], Integer.parseInt(args[3]), Set.of(args[2]));
            result = compile(task, args[2], Integer.parseInt(args[4]));
        } catch (Exception error) {
            error.printStackTrace(System.err);
            result = new JSONObject().put("status", "NO_ADMITTED_PATH").put("reason", error.toString());
        }
        output.println(result);
    }
}
