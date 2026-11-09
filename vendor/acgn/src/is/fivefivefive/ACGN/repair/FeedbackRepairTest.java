package is.fivefivefive.ACGN.repair;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.ast.*;

/** Bounded supported-path checks; not whole-program Java/Lean refinement. */
public final class FeedbackRepairTest {
    private static int checks;
    private static final String SOURCE = "sig S { r: set S }\n"
            + "sig Hidden { secret: set Hidden }\n"
            + "pred candidate { all x: S | some x.r }\n"
            + "pred oracle { all y: S | no y.r }\n"
            + "pred unused { some Hidden }\nrun {} for 3 but 4 Int\n";
    private FeedbackRepairTest() { }

    public static void main(String[] args) {
        FeedbackRepairSession a = session(SOURCE, 64);
        JSONObject initial = a.observation();
        check(initial.getJSONObject("feedback").getJSONArray("worlds").isEmpty(), "Initial feedback empty");
        check(a.solverCalls() == 0, "No initial target queries or seeds");
        check(FeedbackSourceGraph.OPERATORS.size() <= 128, "Operator vocabulary bound");
        check(!initial.toString().contains("Hidden") && !initial.toString().contains("secret"), "No unused inventory");
        check(!initial.toString().contains("oracle") && !initial.has("score") && !initial.has("sourceHash"), "No privileged sidecar");
        numericTables(initial.getJSONObject("initial")); numericTables(initial.getJSONObject("current"));
        numericTables(initial.getJSONObject("actions"));
        check(has(initial.getJSONObject("actions"), "controls", FeedbackRepairSession.Control.BEGIN.ordinal()),
                "Initial action heads expose BEGIN");
        check(!has(initial.getJSONObject("actions"), "controls", FeedbackRepairSession.Control.COMMIT.ordinal()),
                "No COMMIT without draft");
        FeedbackRepairSession mutated = session(SOURCE.replace("all y: S | no y.r", "some S")
                .replace("some Hidden", "no Hidden").replace("secret", "privateInventory"), 64);
        check(initial.similar(mutated.observation()), "Private target/helper/unused-name mutations leave initial packet unchanged");
        FeedbackRepairSession alpha = session(SOURCE.replace("all x: S | some x.r", "all renamed: S | some renamed.r"), 64);
        JSONObject left = initial.getJSONObject("current"), right = alpha.observation().getJSONObject("current");
        // Character offsets are controller-only transport and may differ after alpha renaming.
        left.remove("occurrences"); right.remove("occurrences");
        check(left.similar(right), "Alpha-renamed source graph/type/binding tables agree");
        initial = a.observation();
        call(a, FeedbackRepairSession.Control.VALIDATE);
        JSONObject response = a.observation();
        check(response.getInt("remaining") == 63 && a.solverCalls() == 3, "Validation costs one decision and three initial SAT calls");
        JSONArray worlds = response.getJSONObject("feedback").getJSONArray("worlds");
        check(worlds.length() > 0, "Actual counterexamples returned");
        for (Object world : worlds) {
            JSONArray row = (JSONArray) world;
            check(row.getInt(2) != row.getInt(3) && row.getInt(1) == 0, "Query worlds disagree and bind queried revision");
        }
        check(initial.getJSONObject("feedback").getJSONArray("worlds").isEmpty(), "Earlier packet remains immutable");
        numericTables(response.getJSONObject("feedback"));

        int qt = find(a, ExprQt.class);
        int sig = symbol(a, false), field = symbol(a, true);
        call(a, FeedbackRepairSession.Control.BEGIN, "site", qt, "family", FeedbackDraft.Family.BINDING_UPDATE.ordinal());
        JSONObject choices = a.observation().getJSONObject("actions");
        check(choices.getJSONArray("operators").isEmpty() && choices.getJSONArray("copies").isEmpty()
                && !choices.getJSONArray("quantifiers").isEmpty(), "Binding root has quantifier head only");
        check(choices.getJSONArray("holes").getJSONArray(0).getInt(2) == FeedbackDraft.BOOL,
                "Root hole publishes Boolean sort");
        call(a, FeedbackRepairSession.Control.QUANTIFIER, "operator", FeedbackSourceGraph.opcode("Q:ALL"),
                "count", 1, "disjoint", 0, "cardinality", 0);
        choices = a.observation().getJSONObject("actions");
        check(choices.getJSONArray("draftVariables").isEmpty(), "Mask excludes own-domain binder use");
        check(choices.getJSONArray("holes").getJSONArray(0).getInt(2) == FeedbackDraft.REL,
                "Domain hole publishes relational sort");
        String before = a.body();
        call(a, FeedbackRepairSession.Control.DRAFT_VARIABLE, "pointer", 0);
        check(status(a) == FeedbackRepairSession.Status.REJECTED && a.body().equals(before), "New binder not visible in its own domain");
        call(a, FeedbackRepairSession.Control.SYMBOL, "pointer", sig);
        call(a, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:NO"));
        call(a, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("B:JOIN"));
        check(has(a.observation().getJSONObject("actions"), "draftVariables", 0), "Body exposes introduced binder pointer");
        call(a, FeedbackRepairSession.Control.DRAFT_VARIABLE, "pointer", 0);
        call(a, FeedbackRepairSession.Control.SYMBOL, "pointer", field);
        choices = a.observation().getJSONObject("actions");
        check(choices.getJSONArray("holes").isEmpty()
                && has(choices, "controls", FeedbackRepairSession.Control.COMMIT.ordinal()), "Complete draft exposes COMMIT");
        check(a.body().equals(before), "Draft does not modify committed source");
        call(a, FeedbackRepairSession.Control.COMMIT);
        check(status(a) == FeedbackRepairSession.Status.APPLIED && !a.body().equals(before), "Atomic new binder plus uses commit");
        check(a.observation().getInt("revision") == 1, "Successful commit advances revision");
        check(a.emittedSource().equals(SOURCE.replace(" all x: S | some x.r ", a.body())), "Only editable body changed");
        a.decide(new JSONObject().put("control", FeedbackRepairSession.Control.COMMIT.ordinal()).put("revision", 0)
                .put("turn", a.observation().getInt("turn")));
        check(status(a) == FeedbackRepairSession.Status.STALE && a.observation().getInt("revision") == 1, "Stale decision cannot commit");
        call(a, FeedbackRepairSession.Control.STOP);
        check(status(a) == FeedbackRepairSession.Status.SOLVED_WITHIN_PROFILE, "Rebuilt repair passes both bounded directions");
        check(a.solverCalls() == 5, "No silent validation between source edits");
        JSONObject ended = a.observation();
        check(ended.getJSONObject("actions").getJSONArray("controls").isEmpty(), "Terminal masks have no actions");
        call(a, FeedbackRepairSession.Control.VALIDATE);
        check(ended.similar(a.observation()), "Terminal sessions unchanged");

        FeedbackRepairSession incorrectStop = session(SOURCE, 4);
        call(incorrectStop, FeedbackRepairSession.Control.STOP);
        check(status(incorrectStop) == FeedbackRepairSession.Status.COUNTEREXAMPLE
                && incorrectStop.observation().getBoolean("closed"), "STOP alone is not success");
        FeedbackRepairSession invalid = session(SOURCE, 12);
        call(invalid, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.MATRIX_UPDATE.ordinal());
        call(invalid, FeedbackRepairSession.Control.CONSTANT, "operator", 5, "value", 1);
        call(invalid, FeedbackRepairSession.Control.COMMIT);
        check(status(invalid) == FeedbackRepairSession.Status.REJECTED && invalid.body().contains("all x"), "Type-invalid commit rolls back");
        check(invalid.observation().getInt("remaining") == 9 && invalid.solverCalls() == 0, "Rejected commits spend fuel without querying target");
        call(invalid, FeedbackRepairSession.Control.ABORT_DRAFT);
        check(invalid.observation().getJSONArray("draft").isEmpty(), "Draft abort clears only draft");
        invalid.decide(new JSONObject().put("control", 1.5).put("revision", 0));
        check(status(invalid) == FeedbackRepairSession.Status.REJECTED, "Fractional control is not silently rounded");
        invalid.decide(new JSONObject().put("control", 4294967296L).put("revision", 0));
        check(status(invalid) == FeedbackRepairSession.Status.REJECTED, "Overflow is not silently wrapped");
        check(invalid.observation().getInt("remaining") == 6, "Malformed decisions count against budget");

        FeedbackRepairSession used = session(SOURCE, 8);
        int owner = find(used, ExprQt.class);
        int body = used.sourceGraph().children.get(owner).get(used.sourceGraph().children.get(owner).size() - 1);
        call(used, FeedbackRepairSession.Control.DELETE, "site", owner, "pointer", body,
                "family", FeedbackDraft.Family.BINDING_DELETE.ordinal());
        check(status(used) == FeedbackRepairSession.Status.REJECTED, "Deleting a used binding is rejected");
        FeedbackRepairSession unused = session(SOURCE.replace("some x.r", "some S"), 8);
        owner = find(unused, ExprQt.class);
        body = unused.sourceGraph().children.get(owner).get(unused.sourceGraph().children.get(owner).size() - 1);
        call(unused, FeedbackRepairSession.Control.DELETE, "site", owner, "pointer", body,
                "family", FeedbackDraft.Family.BINDING_DELETE.ordinal());
        check(status(unused) == FeedbackRepairSession.Status.APPLIED && !unused.body().contains("all"), "Unused binding deletion is source-realized");
        FeedbackRepairSession escape = session(SOURCE, 8);
        int var = find(escape, ExprVar.class);
        call(escape, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.MATRIX_UPDATE.ordinal());
        choices = escape.observation().getJSONObject("actions");
        check(!has(choices, "copies", var), "Mask excludes escaping open-term copy");
        check(choices.getJSONArray("symbols").isEmpty(), "Boolean hole excludes relational symbol leaves");
        call(escape, FeedbackRepairSession.Control.COPY, "pointer", var);
        check(status(escape) == FeedbackRepairSession.Status.REJECTED, "Open term cannot escape its scope");
        FeedbackRepairSession bounded = session(SOURCE, 1);
        call(bounded, FeedbackRepairSession.Control.ABORT_DRAFT);
        JSONObject exhausted = bounded.observation(); call(bounded, FeedbackRepairSession.Control.VALIDATE);
        check(exhausted.getJSONObject("actions").getJSONArray("controls").isEmpty(), "Exhausted masks have no actions");
        check(exhausted.similar(bounded.observation()) && bounded.solverCalls() == 0, "No calls after budget exhaustion");
        check(!initial.toString().contains("schema\":[]"), "No module schema fallback");
        String temporalSource = "sig File {}\nvar sig Trash in File {}\n"
                + "pred candidate { all f: File | (once f in Trash) implies (always f in Trash) }\n"
                + "pred oracle { no none }\nrun {} for 3 but 3 steps\n";
        FeedbackRepairSession temporal = session(temporalSource, 32);
        JSONObject table = temporal.observation().getJSONObject("current");
        check(table.getJSONArray("phases").length() == 3, "Once and always have sibling source phases");
        JSONArray imported = table.getJSONArray("bindings");
        check(imported.length() == 3 && imported.getJSONArray(1).getInt(7) == 0
                && imported.getJSONArray(2).getInt(7) == 0
                && imported.getJSONArray(1).getInt(6) != imported.getJSONArray(2).getInt(6),
                "Outer binder imports retain one origin but phase-distinct identities");
        call(temporal, FeedbackRepairSession.Control.VALIDATE);
        check(status(temporal) == FeedbackRepairSession.Status.COUNTEREXAMPLE, "Temporal feedback query reaches solver");
        JSONArray traces = temporal.observation().getJSONObject("feedback").getJSONArray("worlds");
        check(traces.length() > 0 && traces.getJSONArray(0).getInt(4) > 0, "Trace length and loop are explicit");
        int once = -1;
        for (int i = 0; i < temporal.sourceGraph().expressions.size(); i++) {
            if (temporal.sourceGraph().expressions.get(i) instanceof ExprUnary u && u.op == ExprUnary.Op.ONCE) once = i;
        }
        int sub = temporal.sourceGraph().children.get(once).get(0);
        call(temporal, FeedbackRepairSession.Control.BEGIN, "site", once, "family", FeedbackDraft.Family.TEMPORAL_UPDATE.ordinal());
        choices = temporal.observation().getJSONObject("actions");
        check(!has(choices, "operators", FeedbackSourceGraph.opcode("U:NOT"))
                && has(choices, "operators", FeedbackSourceGraph.opcode("U:ALWAYS")), "Temporal root mask retains its family");
        call(temporal, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:EVENTUALLY"));
        call(temporal, FeedbackRepairSession.Control.COPY, "pointer", sub);
        call(temporal, FeedbackRepairSession.Control.COMMIT);
        check(status(temporal) == FeedbackRepairSession.Status.APPLIED, "Temporal replacement rebinds outer use through source provenance");
        FeedbackRepairSession simple = session(SOURCE.replace("all x: S | some x.r", "some S"), 64);
        call(simple, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.MATRIX_INSERT.ordinal());
        call(simple, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("B:AND"));
        call(simple, FeedbackRepairSession.Control.COPY, "pointer", 0);
        call(simple, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("B:OR"));
        call(simple, FeedbackRepairSession.Control.COPY, "pointer", 0);
        call(simple, FeedbackRepairSession.Control.CONSTANT, "operator", 4);
        call(simple, FeedbackRepairSession.Control.COMMIT);
        check(status(simple) == FeedbackRepairSession.Status.APPLIED, "Variadic Boolean construction uses semantic choices, not arity tokens");
        int and = findBinary(simple, ExprBinary.Op.AND);
        if (and < 0) and = find(simple, ExprList.class);
        call(simple, FeedbackRepairSession.Control.DELETE, "site", and, "pointer", simple.sourceGraph().children.get(and).get(0),
                "family", FeedbackDraft.Family.MATRIX_DELETE.ordinal());
        check(status(simple) == FeedbackRepairSession.Status.APPLIED, "Matrix deletion is a checked transaction");
        call(simple, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.BINDING_INSERT.ordinal());
        call(simple, FeedbackRepairSession.Control.QUANTIFIER, "operator", FeedbackSourceGraph.opcode("Q:ALL"),
                "count", 2, "disjoint", 1, "cardinality", 0);
        call(simple, FeedbackRepairSession.Control.SYMBOL, "pointer", symbol(simple, false));
        call(simple, FeedbackRepairSession.Control.COPY, "pointer", 0);
        call(simple, FeedbackRepairSession.Control.COMMIT);
        check(status(simple) == FeedbackRepairSession.Status.APPLIED && simple.body().contains("disj"), "Disjoint binding group can be inserted atomically");
        call(simple, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.TEMPORAL_INSERT.ordinal());
        call(simple, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:ALWAYS"));
        call(simple, FeedbackRepairSession.Control.COPY, "pointer", 0);
        call(simple, FeedbackRepairSession.Control.COMMIT);
        check(status(simple) == FeedbackRepairSession.Status.APPLIED, "Temporal insertion through the opt-in adapter");
        int temporalNode = -1;
        for (int i = 0; i < simple.sourceGraph().expressions.size(); i++) {
            if (FeedbackSourceGraph.temporal(simple.sourceGraph().expressions.get(i))) { temporalNode = i; break; }
        }
        call(simple, FeedbackRepairSession.Control.DELETE, "site", temporalNode,
                "pointer", simple.sourceGraph().children.get(temporalNode).get(0),
                "family", FeedbackDraft.Family.TEMPORAL_DELETE.ordinal());
        check(status(simple) == FeedbackRepairSession.Status.APPLIED, "Temporal deletion through checked source replay: "
                + simple.lastFailure() + "\nbody=" + simple.body() + "\nchild="
                + simple.sourceGraph().source(simple.sourceGraph().children.get(temporalNode).get(0)));
        JSONObject oldTurn = new JSONObject().put("control", FeedbackRepairSession.Control.ABORT_DRAFT.ordinal())
                .put("revision", simple.observation().getInt("revision")).put("turn", simple.observation().getInt("turn"));
        simple.decide(oldTurn); simple.decide(oldTurn);
        check(status(simple) == FeedbackRepairSession.Status.STALE, "Old draft-turn decisions cannot be replayed at a different hole");
        FeedbackRepairSession vacuous = session(SOURCE.replace("run {}", "fact { some S and no S }\nrun {}"), 2);
        call(vacuous, FeedbackRepairSession.Control.STOP);
        check(status(vacuous) == FeedbackRepairSession.Status.VACUOUS, "Infeasible context never supplies a repair success");
        String traceBase = "sig File {}\nvar sig Trash in File {}\n"
                + "pred candidate { some Trash }\npred oracle { no Trash }\nrun {} for 3 but 3 steps\n";
        for (String op : new String[] {"AFTER", "ALWAYS", "EVENTUALLY", "BEFORE", "HISTORICALLY", "ONCE"}) {
            FeedbackRepairSession t = session(traceBase, 8);
            call(t, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.TEMPORAL_INSERT.ordinal());
            call(t, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:" + op));
            call(t, FeedbackRepairSession.Control.COPY, "pointer", 0);
            call(t, FeedbackRepairSession.Control.COMMIT);
            check(status(t) == FeedbackRepairSession.Status.APPLIED, "Unary temporal constructor " + op + ": " + t.lastFailure());
        }
        for (String op : new String[] {"UNTIL", "RELEASES", "SINCE", "TRIGGERED"}) {
            FeedbackRepairSession t = session(traceBase, 8);
            call(t, FeedbackRepairSession.Control.BEGIN, "site", 0, "family", FeedbackDraft.Family.TEMPORAL_INSERT.ordinal());
            call(t, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("B:" + op));
            call(t, FeedbackRepairSession.Control.COPY, "pointer", 0);
            call(t, FeedbackRepairSession.Control.COPY, "pointer", 0);
            call(t, FeedbackRepairSession.Control.COMMIT);
            check(status(t) == FeedbackRepairSession.Status.APPLIED, "Binary temporal constructor " + op + ": " + t.lastFailure());
            check(t.observation().getJSONObject("current").getJSONArray("phases").length() == 3,
                    "Binary temporal siblings stay distinct for " + op);
        }
        FeedbackRepairSession prime = session(traceBase, 8);
        int sigNode = find(prime, Sig.class);
        call(prime, FeedbackRepairSession.Control.BEGIN, "site", sigNode, "family", FeedbackDraft.Family.TEMPORAL_INSERT.ordinal());
        call(prime, FeedbackRepairSession.Control.OPERATOR, "operator", FeedbackSourceGraph.opcode("U:PRIME"));
        call(prime, FeedbackRepairSession.Control.COPY, "pointer", sigNode);
        call(prime, FeedbackRepairSession.Control.COMMIT);
        check(status(prime) == FeedbackRepairSession.Status.APPLIED, "PRIME uses Alloy postfix syntax: " + prime.lastFailure());
        String delimited = "/* ) ] } */ (some S) and (some S) // ( [ {\n";
        FeedbackSourceSpans scanner = new FeedbackSourceSpans(delimited);
        int coreStart = delimited.indexOf("some S"), coreEnd = delimited.lastIndexOf("some S") + "some S".length();
        int[] complete = scanner.complete(coreStart, coreEnd);
        check(delimited.substring(complete[0], complete[1]).equals("(some S) and (some S)"), "Paired span ignores comment delimiters");
        System.out.println("FeedbackRepairTest passed: " + checks + " checks");
    }

    private static FeedbackRepairSession session(String source, int budget) {
        return new FeedbackRepairSession(RepairTask.forFeedback(source, "candidate", 0, Set.of("oracle")), "oracle", budget);
    }
    private static boolean has(JSONObject choices, String table, int value) {
        for (Object raw : choices.getJSONArray(table)) if (((JSONArray) raw).getInt(0) == value) return true;
        return false;
    }
    private static FeedbackRepairSession.Status status(FeedbackRepairSession session) {
        return FeedbackRepairSession.Status.values()[session.observation().getInt("status")];
    }
    private static void call(FeedbackRepairSession session, FeedbackRepairSession.Control control, Object... pairs) {
        JSONObject decision = new JSONObject().put("control", control.ordinal()).put("revision", session.observation().getInt("revision"))
                .put("turn", session.observation().getInt("turn"));
        for (int i = 0; i < pairs.length; i += 2) decision.put((String) pairs[i], pairs[i+1]);
        session.decide(decision);
    }
    private static int find(FeedbackRepairSession session, Class<?> type) {
        for (int i = 0; i < session.sourceGraph().expressions.size(); i++) if (type.isInstance(session.sourceGraph().expressions.get(i))) return i;
        throw new AssertionError("Missing expression " + type);
    }
    private static int symbol(FeedbackRepairSession session, boolean field) {
        for (int i = 0; i < session.sourceGraph().symbols.size(); i++) {
            var s = session.sourceGraph().symbols.get(i);
            if (field ? s instanceof Sig.Field : s instanceof Sig sig && !sig.builtin) return i;
        }
        throw new AssertionError("Missing symbol");
    }
    private static int findBinary(FeedbackRepairSession session, ExprBinary.Op op) {
        for (int i = 0; i < session.sourceGraph().expressions.size(); i++) {
            if (session.sourceGraph().expressions.get(i) instanceof ExprBinary b && b.op == op) return i;
        }
        return -1;
    }
    private static void numericTables(JSONObject tables) {
        for (String key : tables.keySet()) {
            Object value = tables.get(key);
            if (value instanceof Integer) continue;
            check(value instanceof JSONArray, "Table contains arrays only: " + key);
            for (Object raw : (JSONArray) value) {
                JSONArray row = (JSONArray) raw;
                if (row.length() > 16) throw new AssertionError("Row exceeds 16 cells");
                for (Object cell : row) if (!(cell instanceof Integer)) throw new AssertionError("Noninteger table cell");
            }
        }
    }
    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
