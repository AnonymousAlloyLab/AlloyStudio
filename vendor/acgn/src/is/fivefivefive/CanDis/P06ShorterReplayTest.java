package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import parser.ast.nodes.ModelUnit;

/** Archived shorter receipt replay against fresh endpoints; never public admission. */
public final class P06ShorterReplayTest {
    private static final Path SOURCE = Path.of("classified-data/classroom_fol/both/4J8sS2Kr8KrhgsN6R_inv4.als");
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private static void reject(Runnable action, String label) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(label);
    }
    private static Canonical.Prepared prepare(CompModule m, String p) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(p), m);
        visitor.visit(new ModelUnit(null,m),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(p)));
    }
    private static void requireClosed(RepairView view) {
        for (var phase : view.phases()) {
            if (!phase.bindings().isEmpty() || !phase.quantifiers().isEmpty())
                throw new IllegalArgumentException("Scoped/imported/parameter binding outside this replay slice");
            requireClosed(phase.matrix(), 0);
        }
    }
    private static void requireClosed(Node node, int depth) {
        if (node == null) return;
        if (depth > 64 || node.isVariable() || !node.certifiedAlternatives().isEmpty())
            throw new IllegalArgumentException("Variable or representative choice outside this replay slice");
        for (Node child : node.children()) requireClosed(child, depth+1);
    }
    private static MatrixReplay<Node> checker() {
        return new MatrixReplay<>(new MatrixReplay.NativeCost<Node>() {
            public String label(Node n) {
                return new JSONArray().put(n.operator()).put(n.semanticPayload()).put(n.bindingIndex())
                        .put(String.valueOf(n.lexicalVariable())).put(n.containerKind().toString())
                        .put(n.orderInsensitive()).put("[]").toString();
            }
            public List<Node> children(Node n) { return n.children(); }
            public List<Node> alternatives(Node n) { return List.of(); }
            public boolean unordered(Node a, Node b) {
                return a.operator().equals(b.operator()) && a.orderInsensitive() && b.orderInsensitive();
            }
            public int size(Node n) {
                if (n == null) return 0;
                int sum = 1; for (Node child : n.children()) sum = Math.addExact(sum,size(child)); return sum;
            }
            public int update(Node a, Node b) {
                if (a.isVariable() || b.isVariable()) throw new IllegalArgumentException("Unadmitted binding");
                return a.operator().equals(b.operator()) && Objects.equals(a.semanticPayload(),b.semanticPayload()) ? 0 : 1;
            }
            public int distance(Node a, Node b) { throw new AssertionError("Replay must not recompute a minimum"); }
        });
    }
    public static void main(String[] args) throws Exception {
        if (args.length > 2) throw new IllegalArgumentException("[ARCHIVED_RECEIPT [FRESH_OUTPUT_JSON]]");
        byte[] bytes = Files.readAllBytes(SOURCE);
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
        var source = prepare(module,"inv4"); var target = prepare(module,"inv4c");
        var left = CanonicalAlloyPipeline.prepare(source).repairView();
        var right = CanonicalAlloyPipeline.prepare(target).repairView();
        JSONObject report = args.length == 0 ? DistanceReplayDiagnostics.capture(source,target)
                : new JSONObject(Files.readString(Path.of(args[0])));
        if (args.length != 0) check(report.getString("sourceSha256").equals(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes))), "archived source hash");
        JSONObject arm = report.getJSONObject("certified");
        requireClosed(left); requireClosed(right);
        check(arm.getJSONArray("selectedGlobalMapping").isEmpty()
                && arm.getJSONObject("selectedQuantifierCorrespondence").isEmpty(), "empty correspondence only");
        JSONArray phases = arm.getJSONArray("phases");
        check(phases.length() == Math.max(left.phases().size(),right.phases().size()), "phase coverage");
        int matrix = 0;
        for (int i=0; i<phases.length(); i++) {
            check(phases.getJSONObject(i).getInt("phase") == i
                    && phases.getJSONObject(i).getJSONArray("alphaMapping").isEmpty(), "phase identity and mapping");
            Node a = i < left.phases().size() ? left.phases().get(i).matrix() : null;
            Node b = i < right.phases().size() ? right.phases().get(i).matrix() : null;
            JSONObject receipt = phases.getJSONObject(i).getJSONObject("receipt");
            matrix += checker().verify(a,b,receipt);
            JSONObject wrong = new JSONObject(receipt.toString()).put("cost",receipt.getInt("cost")+1);
            reject(() -> checker().verify(a,b,wrong), "changed matrix cost rejected");
            JSONObject swapped = new JSONObject(receipt.toString()).put("source", receipt.getJSONArray("target"));
            reject(() -> checker().verify(a,b,swapped), "changed matrix endpoint rejected");
        }
        check(matrix == 10, "archived matrix replay10 without search");
        JSONObject temporal = arm.getJSONObject("temporalSlice");
        int time = TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),
                RepairView.TemporalNode::label,RepairView.TemporalNode::children,temporal);
        check(time == 1, "one executable ALWAYS deletion");
        JSONObject wrongTime = new JSONObject(temporal.toString());
        wrongTime.getJSONArray("operations").getJSONObject(0).put("label","EVENTUALLY");
        reject(() -> TemporalReplay.verify(left.temporalRoot(),right.temporalRoot(),
                RepairView.TemporalNode::label,RepairView.TemporalNode::children,wrongTime), "changed temporal operator rejected");
        var declarations = arm.getJSONObject("declarationSlice");
        JSONArray components = declarations.getJSONArray("components");
        check(components.length() == phases.length()+1, "parameter and quantifier receipt coverage");
        for (Object item : components) check(DeclarationReplay.verify(List.of(),List.of(),
                ((JSONObject)item).getJSONObject("receipt")) == 0, "empty declaration replay");
        int total = PhaseReplay.verifyDiagnosticOrder(temporal,declarations,phases,
                arm.getJSONArray("orderedDiagnosticActions"));
        check(total == 11 && total == matrix+time && total == arm.getInt("distance"), "native endpoints/cost11");
        check(!report.getBoolean("complete") && !report.getBoolean("sourceCertificateWireReplayChecked")
                && !report.getBoolean("publicSequenceLinked"), "missing full alignment is NOT a pass");
        CompModule scoped = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A }
                pred scoped { all x:A | always some x.r }
                pred parameter[x:A] { always some x.r }
                """);
        reject(() -> requireClosed(CanonicalAlloyPipeline.prepare(prepare(scoped,"scoped")).repairView()),
                "scoped inherited binding is not closed");
        reject(() -> requireClosed(CanonicalAlloyPipeline.prepare(prepare(scoped,"parameter")).repairView()),
                "parameter binding is not closed");
        JSONObject result = new JSONObject().put("status","BLOCKED_FULL_ALIGNMENT")
                .put("nativeReplayChecked",true).put("nativeCost",total).put("matrixCost",matrix)
                .put("temporalCost",time).put("publicSequenceLinked",false).put("complete",false)
                .put("checks",checks).put("blockingReasons",report.getJSONArray("blockingReasons"));
        if (args.length == 2) Files.writeString(Path.of(args[1]),result.toString(2)+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("P06ShorterReplayTest: "+checks+" checks passed; full alignment BLOCKED");
        for (var form : source.normalizedForms()) System.out.println("SOURCE_PHASE "+form.getPhasePath()
                +" params="+form.getParams().size()+" quantifiers="+form.getMatrixQuantiVars().size()
                +" inherited="+form.getInheritedQuantiVars().size()+" locals="+form.getLocalQuantiVars().size()
                +" imports="+form.getPhaseLocalBindingImports().size()+" children="+form.getTemporalChildren().size());
    }
}
