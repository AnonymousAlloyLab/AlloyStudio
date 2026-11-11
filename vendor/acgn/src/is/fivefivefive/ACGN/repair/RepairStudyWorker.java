package is.fivefivefive.ACGN.repair;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.ast.Func;
import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.alloy4.Err;

/** Isolated finite search protocol. Solver success is profile-bounded, not certificate replay. */
public final class RepairStudyWorker {
    private RepairTask task;
    private RepairEnvironment environment;
    private RepairEvaluator evaluator;
    private final Map<Integer, RepairEnvironment.State> states = new HashMap<>();
    private int used;
    private int budget;

    public static void main(String[] args) throws Exception {
        PrintStream output = System.out;
        System.setOut(System.err);
        RepairStudyWorker worker = new RepairStudyWorker();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                Object id = JSONObject.NULL;
                JSONObject reply;
                try {
                    JSONObject request = new JSONObject(line);
                    id = request.get("id");
                    reply = worker.handle(request).put("ok", true);
                } catch (Exception error) {
                    reply = new JSONObject().put("ok", false).put("error", error.toString());
                    error.printStackTrace(System.err);
                }
                output.println(reply.put("id", id).put("protocol", RepairWorkerMain.VERSION));
                output.flush();
            }
        }
    }

    private JSONObject handle(JSONObject r) throws Exception {
        if (r.getString("op").equals("load")) return load(r);
        if (task == null) throw new IllegalStateException("Load first");
        if (r.getString("op").equals("witnesses")) return new JSONObject().put("witnesses", evaluator.witnesses());
        if (used >= budget) throw new IllegalArgumentException("Attempt budget exhausted");
        used++;
        RepairEnvironment.State candidate;
        try {
            if (r.getString("op").equals("propose")) candidate = environment.propose(r.getString("body"));
            else if (r.getString("op").equals("step")) {
                RepairEnvironment.State parent = states.get(r.getInt("node"));
                if (parent == null) throw new IllegalArgumentException("Unknown parent");
                var actions = environment.actions(parent).actions();
                var transition = environment.step(parent, actions.get(r.getInt("action")));
                if (transition.failure() == RepairEnvironment.Failure.CONSTRUCTION_ERROR
                        || transition.failure() == RepairEnvironment.Failure.RESOURCE_LIMIT) {
                    throw new IllegalStateException(transition.detail());
                }
                if (transition.outcome() != RepairKernel.Outcome.APPLIED) {
                    return new JSONObject().put("status", "INVALID").put("used", used)
                            .put("detail", transition.failure().name());
                }
                candidate = transition.state();
            } else throw new IllegalArgumentException("Unknown operation");
        } catch (RepairTask.UnsupportedInput | Err invalid) {
            return new JSONObject().put("status", "INVALID").put("used", used).put("detail", invalid.toString());
        }
        states.put(used, candidate);
        return observe(used, candidate, r.optBoolean("force", false));
    }

    private JSONObject load(JSONObject r) throws Exception {
        if (task != null) throw new IllegalArgumentException("One task per process");
        budget = r.getInt("budget");
        if (budget < 1 || budget > 1024) throw new IllegalArgumentException("Invalid budget");
        String source = Files.readString(Path.of(r.getString("path")), StandardCharsets.UTF_8);
        String oracle = r.getString("oracle");
        task = RepairTask.fromSource(source, r.getString("target"), r.getInt("command"), Set.of(oracle));
        int horizon = r.getInt("horizon");
        if (horizon < 1 || horizon > 32) throw new IllegalArgumentException("Invalid horizon");
        environment = new RepairEnvironment(task, horizon);
        evaluator = new RepairEvaluator(task, oracle, 100, 2);
        states.put(0, environment.initial());
        JSONObject reply = observe(0, environment.initial(), true);
        var context = task.evaluatorContext(oracle);
        JSONArray schema = new JSONArray();
        for (Sig sig : context.module().getAllReachableSigs()) {
            if (sig.builtin) continue;
            JSONObject entry = new JSONObject().put("sig", sig.label).put("type", sig.type().toString());
            JSONArray fields = new JSONArray();
            for (Sig.Field f : sig.getFields()) fields.put(new JSONObject().put("name", f.label)
                    .put("type", f.decl().expr.toString()));
            entry.put("fields", fields);
            schema.put(entry);
        }
        reply.put("schema", schema).put("facts", context.module().getAllReachableFacts().toString())
                .put("witnesses", evaluator.witnesses());
        if (r.getString("split").equals("train")) {
            for (Func f : context.module().getAllFunc()) if (f.label.equals(RepairTask.qualify(oracle))) {
                int[] range = f.getBody().span().toStartEnd(source);
                if (range == null || source.charAt(range[0]) != '{' || source.charAt(range[1]-1) != '}') {
                    throw new IllegalStateException("Teacher lacks a braced source range");
                }
                reply.put("teacher", source.substring(range[0]+1, range[1]-1));
            }
        }
        return reply;
    }

    private JSONObject observe(int node, RepairEnvironment.State state, boolean force) {
        var score = evaluator.score(state, evaluator.snapshot());
        RepairEvaluator.Validation validation = null;
        if (force || score.potential() == 1000) validation = evaluator.validate(state);
        String status = "CANDIDATE";
        if (validation != null) {
            if (validation.verdict() == RepairFeedback.Verdict.VACUOUS
                    || validation.verdict() == RepairFeedback.Verdict.UNRESOLVED) {
                throw new IllegalStateException("Unusable validator result: " + validation.verdict());
            }
            status = validation.verdict().name();
        }
        score = evaluator.score(state, evaluator.snapshot());
        JSONArray actions = new JSONArray();
        for (RepairAction a : environment.actions(state).actions()) {
            actions.put(new JSONObject().put("kind", a.kind().name()).put("operation", a.operation())
                    .put("expected", a.expected()).put("replacement", a.replacement())
                    .put("start", a.start()).put("end", a.end()).put("path", a.path()));
        }
        JSONObject result = new JSONObject().put("node", node).put("used", used).put("budget", budget)
                .put("status", status).put("body", state.body()).put("candidateHash", state.candidateHash())
                .put("sourceHash", task.id()).put("profileHash", task.semanticProfile().fingerprint())
                .put("score", score.json()).put("bank", evaluator.bankStatus()).put("actions", actions);
        if (validation != null) result.put("validation", validation.json());
        return result;
    }
}
