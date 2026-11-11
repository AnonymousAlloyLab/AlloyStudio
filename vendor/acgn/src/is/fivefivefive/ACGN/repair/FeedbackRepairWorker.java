package is.fivefivefive.ACGN.repair;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** One killable process per task. The supervisor enforces a request deadline. */
public final class FeedbackRepairWorker {
    private FeedbackRepairWorker() { }

    public static void main(String[] args) throws Exception {
        PrintStream output = System.out; System.setOut(System.err);
        FeedbackRepairSession session = null;
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                Object id = JSONObject.NULL;
                JSONObject reply;
                try {
                    JSONObject request = new JSONObject(line); id = request.get("id");
                    String op = request.getString("op");
                    if (op.equals("load")) {
                        if (session != null) throw new IllegalArgumentException("ONE_TASK_PER_WORKER");
                        String source = Files.readString(Path.of(request.getString("path")), StandardCharsets.UTF_8);
                        String oracle = request.getString("oracle");
                        RepairTask task = RepairTask.forFeedback(source, request.getString("target"),
                                request.getInt("command"), Set.of(oracle));
                        if (request.has("contract") && !request.getString("contract").equals("local-feedback-repair-v1")) {
                            throw new IllegalArgumentException("UNKNOWN_CONTRACT");
                        }
                        session = request.has("contract")
                                ? new FeedbackRepairSession(task, oracle, request.getInt("budget"), request.getInt("queryBudget"), true)
                                : new FeedbackRepairSession(task, oracle, request.getInt("budget"));
                        reply = new JSONObject().put("observation", session.observation());
                    } else if (op.equals("decide")) {
                        if (session == null) throw new IllegalStateException("LOAD_FIRST");
                        reply = new JSONObject().put("observation", session.decide(request.getJSONObject("decision")));
                    } else if (op.equals("catalog")) {
                        reply = new JSONObject().put("operators", new JSONArray(FeedbackSourceGraph.OPERATORS))
                                .put("canonicalOperators", new JSONArray(FeedbackCanonicalGraph.OPERATORS))
                                .put("controls", new JSONArray(java.util.Arrays.stream(FeedbackRepairSession.Control.values()).map(Enum::name).toList()))
                                .put("families", new JSONArray(java.util.Arrays.stream(FeedbackDraft.Family.values()).map(Enum::name).toList()));
                    } else throw new IllegalArgumentException("OPERATION_UNSUPPORTED");
                    reply.put("ok", true);
                } catch (Exception error) {
                    error.printStackTrace(System.err);
                    // Error diagnostics belong to the supervisor, never policy tensors.
                    reply = new JSONObject().put("ok", false).put("error", error.toString());
                }
                output.println(reply.put("id", id).put("protocol", RepairWorkerMain.VERSION)); output.flush();
            }
        }
    }
}
