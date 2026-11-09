package is.fivefivefive.CanDis;

import java.util.Set;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

/** Real parser/engine checks for inserted and deleted native temporal phases. */
public final class NativePhaseReplayTest {
    private static int checks;
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static void check(boolean value,JSONObject evidence) {
        checks++;
        if (!value) throw new AssertionError("Phase replay check " + checks + ": " + evidence.toString(2));
    }
    public static void main(String[] args) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                var sig S {}
                pred plain { some S }
                pred forever { always some S }
                pred future { eventually some S }
                pred layered { some S and always some S }
                pred nested { eventually always some S }
                pred quantified { all x:S | x in S and eventually x in S }
                pred untilEmpty { some S until no S }
                """);
        String[][] pairs = {{"plain","forever"},{"forever","future"},{"plain","layered"},
                {"plain","nested"},{"plain","quantified"},{"plain","untilEmpty"}};
        boolean sawTemporalEdit = false, sawPhaseEdit = false;
        for (String[] pair : pairs) for (int direction = 0; direction < 2; direction++) {
            JSONObject report = DistanceReplayDiagnostics.capture(prepare(module,pair[direction]),
                    prepare(module,pair[1-direction]));
            for (String name : new String[]{"fast","certified"}) {
                JSONObject arm = report.getJSONObject(name);
                check(arm.optBoolean("matrixReplayChecked"),arm);
                check(arm.getJSONObject("temporalSlice").optBoolean("sliceReplayChecked"),arm);
                check(arm.getJSONObject("declarationSlice").optBoolean("sliceReplayChecked"),arm);
                check(arm.optBoolean("numericalCostCoverage"),arm);
                check(arm.getInt("distance") == arm.getInt("orderedDiagnosticCost"),arm);
                check(!arm.getBoolean("complete") && !arm.getBoolean("publicSequenceLinked"),arm);
                sawTemporalEdit |= arm.getInt("temporalDistance") > 0;
                for (Object value : arm.getJSONArray("phases")) {
                    JSONObject receipt = ((JSONObject)value).getJSONObject("receipt");
                    sawPhaseEdit |= receipt.getJSONArray("source").isEmpty()
                            || receipt.getJSONArray("target").isEmpty();
                }
            }
        }
        check(sawTemporalEdit,new JSONObject().put("sawTemporalEdit",sawTemporalEdit));
        check(sawPhaseEdit,new JSONObject().put("sawPhaseEdit",sawPhaseEdit));
        System.out.println("NativePhaseReplayTest: " + checks + " checks passed");
    }
}
