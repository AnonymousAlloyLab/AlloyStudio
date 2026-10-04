import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import edu.mit.csail.sdg.alloy4.Pos;
import edu.mit.csail.sdg.alloy4.A4Preferences;
import java.nio.file.Files;
import java.nio.file.Paths;
import edu.mit.csail.sdg.ast.Func;
import org.json.JSONArray;
import org.json.JSONObject;
import pt.haslab.mutation.PruneReason;
import pt.haslab.mutation.mutator.Mutator;
import pt.haslab.util.ExprToString;
import pt.haslab.util.RepairChecker;
import pt.haslab.util.Repairer;

/** Instrumentation only: the original TAR algorithm and mutator hints are unmodified. */
public final class TarRunner {
    private static boolean configured = false;

    private static synchronized void configureSemantics() {
        if (configured) return;
        // TAR initializes its options from the Alloy GUI preference, whose
        // upstream default prevents integer overflow. The shared corpus uses
        // ordinary bounded Alloy integer semantics (noOverflow=false).
        // Require caller-owned isolation before touching the supported API.
        String root = System.getProperty("java.util.prefs.userRoot");
        if (root == null || root.isEmpty() || !Files.isDirectory(Paths.get(root))) {
            throw new IllegalStateException("An isolated preference root is required");
        }
        A4Preferences.NoOverflow.set(false);
        if (A4Preferences.NoOverflow.get()) throw new IllegalStateException("Overflow policy was not applied");
        configured = true;
    }

    public static JSONObject evaluate(String file, int depth, int timeout) {
        long start = System.nanoTime();
        JSONObject result = new JSONObject();
        try {
            configureSemantics();
            result.put("no_overflow", false);
            result.put("solver", "MiniSatJNI");
            if (depth < 0 || timeout <= 0) throw new IllegalArgumentException();
            Repairer repairer = RepairChecker.attemptRepair(file, depth, 1000L * timeout, false, true);
            result.put("max_depth", depth).put("timeout", timeout);
            result.put("elapsed", repairer.getElapsedMillis());
            result.put("solved", repairer.solution.isPresent());
            result.put("timed_out", repairer.getRepairStatus() == Repairer.RepairStatus.TIMEOUT);
            JSONObject stats = new JSONObject();
            stats.put("#cex", repairer.counterexamples.size());
            stats.put("#attempted_candidates", repairer.num_attempted_candidates);
            stats.put("#prunned_cex", repairer.getPrunnedBy(PruneReason.PREVIOUS_CEX));
            stats.put("#prunned_ext", repairer.getPrunnedBy(PruneReason.EXTENSIONALITY));
            stats.put("#prunned_type_error", repairer.getPrunnedBy(PruneReason.TYPE_ERROR));
            stats.put("#generated", repairer.mutationStepper.candidates.size());
            result.put("stats", stats);
            if (repairer.solution.isPresent()) {
                result.put("depth", repairer.solution.get().mutators.size());
                JSONObject solution = new JSONObject();
                for (Func function : repairer.funcOriginalBody.keySet()) {
                    solution.put(function.label.replace("this/", ""), ExprToString.exprToString(function.getBody()));
                }
                result.put("solution", solution);
                JSONArray trace = new JSONArray();
                for (Mutator mutation : repairer.solution.get().mutators) {
                    JSONObject step = new JSONObject();
                    step.put("operation", mutation.getClass().getSimpleName());
                    step.put("name", mutation.name);
                    step.put("hint", mutation.hint().isPresent() ? mutation.hint().get() : JSONObject.NULL);
                    Pos position = mutation.original.expr.pos();
                    step.put("line", position.y).put("column", position.x);
                    step.put("end_line", position.y2).put("end_column", position.x2);
                    trace.put(step);
                }
                result.put("native_trace", trace);
            }
        } catch (Throwable error) {
            result.put("solved", false);
            result.put("error", error.getClass().getName());
        }
        result.put("api_seconds", (System.nanoTime() - start) / 1e9);
        return result;
    }

    public static void main(String[] arguments) throws Exception {
        PrintStream wire = System.out;
        // Solver/debug chatter is never allowed on the JSON wire. The worker
        // is sequential, so redirecting this process-wide stream is sufficient.
        System.setOut(System.err);
        if (arguments.length == 1 && arguments[0].equals("--jsonl")) {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line;
            while ((line = input.readLine()) != null) {
                JSONObject result;
                try {
                    JSONObject request = new JSONObject(line);
                    result = evaluate(request.getString("file"), request.getInt("depth"), request.getInt("timeout"));
                } catch (Throwable error) {
                    result = new JSONObject().put("solved", false).put("error", error.getClass().getName());
                }
                wire.println(result.toString());
                wire.flush();
            }
            return;
        }
        if (arguments.length != 3) throw new IllegalArgumentException();
        JSONObject result = evaluate(arguments[0], Integer.parseInt(arguments[1]), Integer.parseInt(arguments[2]));
        wire.println(result.toString());
        wire.flush();
        System.exit(result.optBoolean("solved") ? 0 : result.optBoolean("timed_out") ? 1 : result.has("error") ? 3 : 2);
    }
}
