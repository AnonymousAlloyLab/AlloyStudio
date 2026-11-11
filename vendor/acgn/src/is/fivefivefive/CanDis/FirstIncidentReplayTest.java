package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.PhaseReplay;
import parser.ast.nodes.ModelUnit;

/** The actual stopped incident; numerical coverage must never become parity authority. */
public final class FirstIncidentReplayTest {
    private static int checks;
    private static void check(boolean yes) { checks++; if (!yes) throw new AssertionError("check " + checks); }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static JSONObject capture(CompModule m,String a,String b) {
        return DistanceReplayDiagnostics.capture(prepare(m,a),prepare(m,b));
    }
    public static void main(String[] args) throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of(
                "classified-data/classroom_fol/both/2KmASqDBmqEueukiz_inv5.als")));
        for (boolean reverse : new boolean[]{false,true}) {
            JSONObject report = capture(module,reverse ? "inv5c" : "inv5",reverse ? "inv5" : "inv5c");
            check(report.getString("status").equals("BLOCKED") && !report.getBoolean("complete"));
            check(!report.getBoolean("parityAuthority") && !report.getBoolean("quantifierReplayChecked")
                    && !report.getBoolean("temporalReplayChecked"));
            JSONArray tuples = null;
            for (String name : new String[]{"fast","certified"}) {
                JSONObject arm = report.getJSONObject(name);
                check(arm.getInt("distance") == 10 && arm.getInt("matrixDistance") == 8
                        && arm.getInt("quantifierDistance") == 2 && arm.getInt("temporalDistance") == 0);
                check(arm.getBoolean("numericalCostCoverage") && arm.getInt("orderedDiagnosticCost") == 10);
                check(!arm.getBoolean("publicSequenceLinked") && !arm.getBoolean("complete"));
                JSONObject q = arm.getJSONObject("declarationSlice");
                check(q.getBoolean("sliceReplayChecked") && q.getInt("replayedCost") == 2);
                check(!q.getBoolean("orderingAdmissionChecked") && !q.getBoolean("matrixChoiceAdmissionChecked"));
                check(arm.getJSONObject("temporalIdentitySlice").getBoolean("sliceReplayChecked"));
                JSONArray current = new JSONArray();
                for (Object component : q.getJSONArray("components")) {
                    JSONArray ops = ((JSONObject)component).getJSONObject("receipt").getJSONArray("operations");
                    for (Object operation : ops) {
                        JSONObject op = (JSONObject)operation;
                        check(op.getInt("cost") == 1 && op.getString("kind").equals(reverse ? "insert" : "delete"));
                        current.put(op.getJSONObject(reverse ? "target" : "source").getJSONArray("tuple"));
                    }
                }
                check(current.similar(new JSONArray("[[\"ALL\",\"Class\",\"ONE\",\"0\"],[\"ALL\",\"Person\",\"ONE\",\"0\"]]")));
                if (tuples != null) check(tuples.similar(current));
                tuples = current;
                check(PhaseReplay.verifyDiagnosticOrder(q,arm.getJSONArray("phases"),
                        arm.getJSONArray("orderedDiagnosticActions")) == 10);
                JSONArray bad = new JSONArray(arm.getJSONArray("orderedDiagnosticActions").toString());
                bad.getJSONObject(0).put("receiptPath","/wrong/occurrence");
                try { PhaseReplay.verifyDiagnosticOrder(q,arm.getJSONArray("phases"),bad);
                    throw new AssertionError("Unlinked action accepted");
                } catch (IllegalArgumentException expected) { checks++; }
            }
            JSONObject a = matrixRoot(report.getJSONObject("fast")), b = matrixRoot(report.getJSONObject("certified"));
            // Only opcodes, costs, ordering flags and child indices are compared.
            // Native labels/binding identities differ; this is NOT public-plan equality.
            check(shape(a).similar(shape(b)));
            check(report.getJSONObject("certified").getJSONObject("declarationSlice")
                    .getBoolean("selectedPaidCorrespondenceChecked"));
        }
        CompModule paid = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r: set A }
                pred universals { all x:A | some x.r }
                pred existentials { some x:A | some x.r }
                """);
        JSONObject paidResult = capture(paid,"universals","existentials").getJSONObject("certified");
        JSONObject q = paidResult.getJSONObject("declarationSlice");
        if (!q.optBoolean("sliceReplayChecked")) throw new AssertionError(q.toString());
        check(q.getInt("cost") == 1 && q.getBoolean("selectedPaidCorrespondenceChecked"));
        check(!q.getJSONObject("selectedPaidCorrespondence").isEmpty());
        check(!paidResult.getBoolean("publicSequenceLinked"));
        System.out.println("FirstIncidentReplayTest: " + checks + " checks passed");
    }
    private static JSONObject matrixRoot(JSONObject arm) {
        return arm.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation");
    }
    private static JSONObject shape(JSONObject node) {
        JSONObject result = new JSONObject().put("kind",node.getString("kind")).put("cost",node.getInt("cost"))
                .put("sourceOpcode",opcode(node.getString("sourceHead"))).put("targetOpcode",opcode(node.getString("targetHead")));
        if (node.has("headCost")) result.put("headCost",node.getInt("headCost")).put("unordered",node.getBoolean("unordered"));
        JSONArray children = new JSONArray();
        if (node.has("children")) for (Object value : node.getJSONArray("children")) {
            JSONObject edge = (JSONObject)value;
            children.put(new JSONObject().put("leftIndex",edge.getInt("leftIndex"))
                    .put("rightIndex",edge.getInt("rightIndex")).put("edit",shape(edge.getJSONObject("edit"))));
        }
        return result.put("children",children);
    }
    private static String opcode(String label) { return label.isEmpty() ? "" : new JSONArray(label).getString(0); }
}
