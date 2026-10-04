import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import org.json.JSONObject;

/** Independent repair validation with Alloy Studio's production Alloy runtime. */
public final class VerifyRepair {
    public static void main(String[] arguments) {
        long start = System.nanoTime();
        JSONObject result = new JSONObject();
        try {
            if (arguments.length != 1) throw new IllegalArgumentException();
            CompModule model = CompUtil.parseEverything_fromFile(A4Reporter.NOP, null, arguments[0]);
            Command selected = null;
            for (Command command : model.getAllCommands()) {
                if (command.check && (command.label.equals("correct") || command.label.equals("this/correct"))) {
                    if (selected != null) throw new IllegalArgumentException("Ambiguous original correctness check");
                    selected = command;
                }
            }
            if (selected == null) throw new IllegalArgumentException("Missing original correctness check");
            A4Options options = new A4Options();
            options.solver = A4Options.SatSolver.SAT4J;
            options.noOverflow = false;
            // Alloy's parsed command already includes all module facts and the
            // negated assertion. Preserve its exact scopes and trace bounds.
            boolean counterexample = TranslateAlloyToKodkod.execute_command(
                A4Reporter.NOP, model.getAllReachableSigs(), selected, options).satisfiable();
            result.put("status", "checked");
            result.put("verified_correct", !counterexample);
            result.put("counterexample_found", counterexample);
            result.put("solver", "SAT4J");
            result.put("no_overflow", options.noOverflow);
            result.put("module_facts", true);
            result.put("scope", selected.overall);
            result.put("bitwidth", selected.bitwidth);
            result.put("maxseq", selected.maxseq);
            result.put("minprefix", selected.minprefix);
            result.put("maxprefix", selected.maxprefix);
        } catch (Throwable error) {
            result.put("status", "validation_error");
            result.put("verified_correct", JSONObject.NULL);
            result.put("error_class", error.getClass().getName());
        }
        result.put("wall_s", (System.nanoTime() - start) / 1e9);
        System.out.println(result.toString());
    }
}
