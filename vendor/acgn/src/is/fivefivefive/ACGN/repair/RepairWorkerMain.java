package is.fivefivefive.ACGN.repair;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated, killable JVM. Only framed JSON replies use its original stdout. */
public final class RepairWorkerMain {
    public static final String VERSION = "repair-worker-v1";
    private RepairTask task;
    private RepairEnvironment environment;
    private RepairEvaluator evaluator;
    private RepairEnvironment.State state;
    private RepairEvaluator.Snapshot snapshot;
    private RepairEvaluator.Score score;
    private RepairActionEnumerator.Menu menu;
    private String token;
    private int episode;
    private boolean terminal = true;

    public static void main(String[] args) throws Exception {
        PrintStream protocol = System.out;
        System.setOut(System.err);
        RepairWorkerMain worker = new RepairWorkerMain();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject reply;
                Object id = JSONObject.NULL;
                try {
                    JSONObject request = new JSONObject(line);
                    id = request.get("id");
                    reply = worker.handle(request).put("ok", true);
                } catch (Exception failure) {
                    worker.terminal = true;
                    reply = new JSONObject().put("ok", false).put("status", "ABORTED")
                            .put("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
                    failure.printStackTrace(System.err);
                }
                protocol.println(reply.put("id", id).put("protocol", VERSION).toString());
                protocol.flush();
            }
        }
    }

    private JSONObject handle(JSONObject request) throws Exception {
        return switch (request.getString("op")) {
            case "load" -> load(request);
            case "reset" -> reset();
            case "step" -> step(request);
            default -> throw new IllegalArgumentException("Unknown worker operation");
        };
    }

    private JSONObject load(JSONObject request) throws Exception {
        if (task != null) throw new IllegalArgumentException("One task per worker");
        String oracle = request.getString("oracle");
        Set<String> forbidden = new HashSet<>();
        forbidden.add(oracle);
        JSONArray exclusions = request.optJSONArray("forbidden");
        if (exclusions != null) for (Object value : exclusions) forbidden.add((String) value);
        int horizon = request.getInt("horizon");
        if (horizon < 1 || horizon > 32) throw new IllegalArgumentException("Horizon must be in [1,32]");
        task = RepairTask.fromFile(Path.of(request.getString("path")), request.getString("target"),
                request.getInt("command"), forbidden);
        environment = new RepairEnvironment(task, horizon);
        evaluator = new RepairEvaluator(task, oracle, request.optInt("capacity", RepairEvaluator.DEFAULT_CAPACITY),
                request.optInt("seedsPerClass", 2));
        return new JSONObject().put("taskId", task.id()).put("profile", task.semanticProfile().fingerprint())
                .put("theory", RepairTask.THEORY).put("bank", evaluator.bankStatus());
    }

    private JSONObject reset() {
        if (environment == null) throw new IllegalStateException("Load a task first");
        episode++;
        state = environment.initial();
        RepairEvaluator.Validation validation = evaluator.validate(state);
        terminal = validation.verdict() != RepairFeedback.Verdict.COUNTEREXAMPLE;
        snapshot = evaluator.snapshot();
        score = evaluator.score(state, snapshot);
        return observation().put("validation", validation.json()).put("episode", episode)
                .put("initial", true).put("status", terminal ? validation.verdict().name() : "RUNNING");
    }

    private JSONObject step(JSONObject request) {
        if (terminal || !request.getString("token").equals(token)) throw new IllegalArgumentException("Closed or stale rollout");
        int index = request.getInt("action");
        if (index < 0 || index >= menu.actions().size()) throw new IllegalArgumentException("Action index outside menu");
        RepairAction action = menu.actions().get(index);
        RepairEnvironment.Transition transition = environment.step(state, action);
        if (transition.failure() == RepairEnvironment.Failure.CONSTRUCTION_ERROR
                || transition.failure() == RepairEnvironment.Failure.RESOURCE_LIMIT) {
            throw new IllegalStateException(transition.failure() + ": " + transition.detail());
        }
        int oldPotential = score.potential();
        String oldToken = token;
        state = transition.state();
        score = evaluator.score(state, snapshot);
        terminal = state.control().stopped() || state.control().remaining() == 0;
        String status = terminal ? "EXHAUSTED" : "RUNNING";
        RepairEvaluator.Validation validation = null;
        if (terminal || score.potential() == 1000) {
            validation = evaluator.validate(state);
            if (validation.verdict() == RepairFeedback.Verdict.UNRESOLVED
                    || validation.verdict() == RepairFeedback.Verdict.VACUOUS) {
                throw new IllegalStateException("Unusable semantic validation: " + validation.verdict());
            }
            if (validation.verdict() == RepairFeedback.Verdict.SOLVED_WITHIN_PROFILE) {
                terminal = true; status = "SOLVED_WITHIN_PROFILE";
            } else if (validation.newVersion() != validation.oldVersion()) {
                terminal = true; status = "BANK_CHANGED";
            }
        }
        int base = status.equals("SOLVED_WITHIN_PROFILE") ? 1000
                : transition.outcome() == RepairKernel.Outcome.REJECTED ? -50 : -10;
        int reward = RepairFeedback.shaped(base, oldPotential, terminal ? 0 : score.potential());
        JSONObject reply = observation().put("status", status).put("reward", reward)
                .put("baseReward", base).put("oldPotential", oldPotential)
                .put("newPotential", terminal ? 0 : score.potential())
                .put("previousToken", oldToken).put("actionIndex", index).put("transition", transition.outcome().name())
                .put("failure", transition.failure().name()).put("episode", episode)
                .put("selectedAction", new JSONObject().put("kind", action.kind().name())
                    .put("operation", action.operation()).put("path", action.path())
                    .put("start", action.start()).put("end", action.end())
                    .put("expected", action.expected()).put("replacement", action.replacement()));
        if (validation != null) reply.put("validation", validation.json());
        if (terminal) reply.put("candidateBody", state.body())
                .put("distanceFromOriginal", environment.distanceFromInitial(state));
        return reply;
    }

    private JSONObject observation() {
        menu = environment.actions(state);
        token = RepairTask.digest(task.id(), state.candidateHash(), Integer.toString(episode),
                Integer.toString(state.control().remaining()), snapshot.hash());
        JSONObject reply = new JSONObject().put("terminal", terminal).put("token", token)
                .put("remaining", state.control().remaining())
                .put("score", score.json()).put("bank", evaluator.bankStatus())
                .put("sourceHash", state.candidateHash()).put("canonicalDigest", state.observationDigest())
                .put("edits", state.control().edits()).put("certificateReplay", "NOT_REQUESTED");
        if (!terminal) reply.put("observation", RepairObservation.encode(state, menu, score));
        return reply;
    }
}
