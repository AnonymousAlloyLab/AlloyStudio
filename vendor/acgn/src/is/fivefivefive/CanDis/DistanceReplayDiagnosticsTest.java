package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

/** Native-cost conservation and explicit incompleteness on bounded real inputs. */
public final class DistanceReplayDiagnosticsTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static Canonical.Prepared prepare(CompModule module, String name) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static JSONObject capture(CompModule module, String left, String right, int fast, int certified) {
        JSONObject report = DistanceReplayDiagnostics.capture(prepare(module,left), prepare(module,right));
        check(report.getString("schema").equals(DistanceReplayDiagnostics.SCHEMA));
        check(report.getString("status").equals("BLOCKED") && !report.getBoolean("complete"));
        check(!report.getBoolean("parityAuthority") && !report.getBoolean("quantifierReplayChecked"));
        for (String arm : new String[]{"fast","certified"}) {
            JSONObject result = report.getJSONObject(arm);
            if (!result.optBoolean("matrixReplayChecked")) throw new AssertionError(result.toString());
            check(result.getInt("matrixDistance") == result.getInt("replayedMatrixCost"));
            check(result.getInt("distance") == (arm.equals("fast") ? fast : certified));
            check(!result.getBoolean("complete"));
        }
        return report;
    }
    public static void main(String[] args) throws Exception {
        CompModule outlier = CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of(
                "classified-data/coursesOld/both/xJnitBpFv9K74WNvf_inv14.als")));
        JSONObject report = capture(outlier,"inv14","inv14c",49,49);
        JSONObject f = branch(report,"fast"), c = branch(report,"certified");
        check(f.getInt("cost") == 40 && c.getInt("cost") == 40);
        check(f.getString("sourceHead").startsWith("[\"ONE\"")
                && c.getString("sourceHead").startsWith("[\"ONE\""));
        check(f.getString("targetHead").startsWith("[\"IN\"")
                && c.getString("targetHead").startsWith("[\"IN\""));
        capture(outlier,"inv14c","inv14",49,49);
        CompModule reduced = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig W { workers: set Worker }
                sig Worker {}
                sig H,R in Worker {}
                pred paired {
                  all h,r:Worker | all w:W |
                    h in H and r in R and h in w.workers implies r not in w.workers
                  all h,r:Worker | all w:W |
                    h in H and r in R and r in w.workers implies h not in w.workers
                }
                pred single {
                  all h,r:Worker | all w:W |
                    h in H and r in R and h in w.workers implies r not in w.workers
                }
                """);
        capture(reduced,"paired","single",0,0);
        capture(reduced,"single","paired",0,0);
        System.out.println("DistanceReplayDiagnosticsTest: " + checks + " checks passed");
    }
    private static JSONObject branch(JSONObject report,String arm) {
        return report.getJSONObject(arm).getJSONArray("phases").getJSONObject(0)
                .getJSONObject("receipt").getJSONObject("derivation").getJSONArray("children")
                .getJSONObject(0).getJSONObject("edit").getJSONArray("children")
                .getJSONObject(0).getJSONObject("edit");
    }
}
