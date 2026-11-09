package is.fivefivefive.ACGN.repair;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** Small, explicitly non-learning entry point. Never writes the input model. */
public final class RepairExplorer {
    private RepairExplorer() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 8) {
            throw new IllegalArgumentException("Usage: RepairExplorer INPUT TARGET COMMAND_INDEX "
                    + "FORBIDDEN_NAME_OR_DASH BEAM_OR_RANDOM SEED ATTEMPTS NEW_OUTPUT_JSON");
        }
        Path output = Path.of(args[7]);
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        int attempts = Integer.parseInt(args[6]);
        RepairTask task = RepairTask.fromFile(Path.of(args[0]), args[1], Integer.parseInt(args[2]),
                args[3].equals("-") ? Set.of() : Set.of(args[3]));
        RepairEnvironment environment = new RepairEnvironment(task, attempts);
        RepairSearch.Result search = RepairSearch.explore(environment, RepairSearch.Mode.valueOf(args[4]),
                Long.parseLong(args[5]), attempts, 4, 3);
        JSONObject report = new JSONObject();
        report.put("schemaVersion", RepairTask.VERSION);
        report.put("mode", "bounded-exploration-not-learning");
        report.put("taskId", task.id());
        report.put("profile", task.semanticProfile().fingerprint());
        report.put("theory", RepairTask.THEORY);
        report.put("policy", args[4]);
        report.put("seed", Long.parseLong(args[5]));
        report.put("attempts", search.attempts());
        report.put("rejected", search.rejected());
        report.put("truncatedMenus", search.truncatedMenus());
        report.put("aborted", search.aborted());
        report.put("semanticValidation", "NOT_RUN");
        report.put("certificateReplay", "NOT_REQUESTED");
        JSONArray candidates = new JSONArray();
        for (RepairEnvironment.State state : search.candidates()) {
            JSONObject candidate = new JSONObject();
            candidate.put("body", state.body());
            candidate.put("sourceHash", state.candidateHash());
            candidate.put("observationDigest", state.observationDigest());
            candidate.put("appliedEdits", state.control().edits());
            candidate.put("repairSize", state.repairSize());
            candidate.put("distanceFromOriginal", environment.distanceFromInitial(state));
            candidates.put(candidate);
        }
        report.put("candidates", candidates);
        Files.writeString(output, report.toString(2) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        if (!search.aborted().isEmpty()) throw new IllegalStateException("Exploration aborted: " + search.aborted());
        System.out.println("Exploration report: " + output);
    }
}
