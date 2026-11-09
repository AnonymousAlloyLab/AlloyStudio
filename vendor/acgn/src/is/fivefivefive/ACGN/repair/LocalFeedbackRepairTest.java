package is.fivefivefive.ACGN.repair;

import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

public final class LocalFeedbackRepairTest {
    private static int checks;
    private static final String SOURCE = "sig S {}\npred candidate { some S }\npred oracle { no S }\nrun {} for 3\n";
    private static FeedbackRepairSession session(String source) {
        return new FeedbackRepairSession(RepairTask.forFeedback(source, "candidate", 0, Set.of("oracle")), "oracle", 64, 4, true);
    }
    private static JSONObject call(FeedbackRepairSession s, int control, Object... pairs) {
        JSONObject before = s.observation();
        JSONObject action = new JSONObject().put("control", control).put("revision", before.getInt("revision"))
                .put("turn", before.getInt("turn"));
        for (int i = 0; i < pairs.length; i += 2) action.put((String) pairs[i], pairs[i+1]);
        return s.decide(action);
    }
    private static void invalid(FeedbackRepairSession s) {
        call(s, 0, "site", 0, "family", 8);
        call(s, 6, "operator", FeedbackSourceGraph.opcode("U:NOT"));
        call(s, 5, "operator", 0, "value", 0);
        call(s, 8);
    }
    private static boolean control(FeedbackRepairSession s, int code) {
        for (Object raw : s.observation().getJSONObject("actions").getJSONArray("controls")) {
            if (((JSONArray) raw).getInt(0) == code) return true;
        }
        return false;
    }
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) {
        FeedbackRepairSession s = session(SOURCE);
        String original = s.body();
        invalid(s);
        check(s.observation().getInt("status") == 2 && s.body().equals(original), "Invalid source is rolled back");
        check(s.preparationAttempts() == 1 && !control(s, 8), "Stable retry masked after first check");
        int remaining = s.observation().getInt("remaining");
        call(s, 8);
        check(s.preparationAttempts() == 1 && s.observation().getInt("remaining") == remaining - 1,
                "Direct repeated commit consumes decision without rechecking");
        call(s, 10); invalid(s);
        check(s.preparationAttempts() == 1, "Abort does not erase stable failure");
        check(s.solverCalls() == 0, "Local rejection has no target query");
        call(s, 10); call(s, 0, "site", 0, "family", 8);
        call(s, 6, "operator", FeedbackSourceGraph.opcode("U:NO")); call(s, 1, "pointer", 1); call(s, 8);
        check(s.preparationAttempts() == 2 && s.observation().getInt("status") == 1, "Changed draft commits");
        check(!s.observation().getBoolean("closed"), "Commit is not success");
        call(s, 12);
        check(s.observation().getInt("status") == 5, "Real final two-direction check passes");

        FeedbackRepairSession budget = session(SOURCE);
        for (int i = 0; i < 4; i++) call(budget, 11);
        int calls = budget.solverCalls(); call(budget, 11);
        check(budget.queryCount() == 4 && budget.solverCalls() == calls && !control(budget, 11), "Queries bounded in Java");
        FeedbackRepairSession hidden = session(SOURCE.replace("pred oracle { no S }", "pred oracle { one S }"));
        check(session(SOURCE).observation().similar(hidden.observation()), "Private oracle absent from initial public state");

        RepairTask task = RepairTask.forFeedback(SOURCE, "candidate", 0, Set.of("oracle"));
        JSONObject teacher = LocalFeedbackTeacherMain.compile(task, "oracle", 64, 4, new JSONArray(), null, false);
        check(teacher.getString("status").equals("VALIDATED_LOCAL_CONTINUATION"), "Local teacher validated");
        check(teacher.getJSONArray("labels").toList().stream().anyMatch(x -> x instanceof java.util.Map<?, ?> m
                && Integer.valueOf(1).equals(m.get("control"))), "Teacher reuses source via COPY");
        boolean refused = false;
        try { LocalFeedbackTeacherMain.compile(task, "oracle", 64, 4, new JSONArray(), new JSONObject(), false); }
        catch (IllegalArgumentException expected) { refused = expected.getMessage().equals("REPLAY_DIVERGED"); }
        check(refused, "Unbound snapshot rejected");
        String quantified = "sig S {r: set S}\npred candidate { some S }\n"
                + "pred oracle { all other: S | no other.r }\nrun {} for 3\n";
        RepairTask q = RepairTask.forFeedback(quantified, "candidate", 0, Set.of("oracle"));
        JSONObject qt = LocalFeedbackTeacherMain.compile(q, "oracle", 128, 4, new JSONArray(), null, false);
        check(qt.getJSONArray("edits").length() == 1, "Alpha-renamed completed teacher is not reconstructed again");
        String local = "sig S {}\npred candidate { some S and lone S }\n"
                + "pred oracle { no S and lone S }\nrun {} for 3\n";
        RepairTask l = RepairTask.forFeedback(local, "candidate", 0, Set.of("oracle"));
        JSONObject lt = LocalFeedbackTeacherMain.compile(l, "oracle", 128, 4, new JSONArray(), null, false);
        check(!lt.getJSONArray("edits").getJSONObject(0).getBoolean("root"), "Shared outer context retained");
        System.out.println("LocalFeedbackRepairTest passed: " + checks + " checks");
    }
}
