package is.fivefivefive.CanDis;

import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.alloy4.Version;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import java.util.Collections;
import kodkod.engine.satlab.SATFactory;
import parser.ast.nodes.ModelUnit;
import parser.util.AlloyUtil;

/** Guards the local parser overlay and the upgraded dependency classpath. */
public final class DependencyCompatibilityTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        check(Version.version().startsWith("6.2.0"), "Alloy runtime version");
        var loader = DependencyCompatibilityTest.class.getClassLoader();
        for (String resource : new String[] {
                "edu/mit/csail/sdg/parser/CompUtil.class", "org/slf4j/LoggerFactory.class",
                "org/apache/commons/cli/Options.class", "org/json/JSONObject.class"}) {
            check(Collections.list(loader.getResources(resource)).size() == 1,
                    "Shadowed dependency: " + resource);
        }
        var module = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                module dependency_probe
                sig A {}
                var sig Live in A {}
                pred candidate { always (Live in A) }
                fun domain: set A { A }
                assert stable { always (Live in A) }
                run candidate for 2 but 2 steps
                check stable for 2 but 2 steps
                """);
        var model = new ModelUnit(null, module);
        check(model.getModuleDecl().getModelName().equals("dependency_probe"), "Module name");
        check(model.getAssertDeclList().size() == 1, "Assertion adapter");
        check(model.getPredDeclList().size() == 1, "Predicate adapter");
        check(model.getFunDeclList().size() == 1, "Function adapter");
        check(model.getRunCmdList().size() == 1 && model.getCheckCmdList().size() == 1,
                "Command adapter");
        var options = new A4Options();
        options.solver = SATFactory.get("sat4j");
        AlloyUtil.loadMiniSatSolver(options.solver);
        for (var command : module.getAllCommands()) {
            var result = TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,
                    module.getAllReachableSigs(), command, options);
            check(result.satisfiable() == !command.check, "Temporal SAT/check result");
        }
        check(SATFactory.find("acgn-missing-solver").isEmpty(), "Unknown solver fails closed");
        System.out.println("DependencyCompatibilityTest: " + checks + " checks passed");
    }
}
