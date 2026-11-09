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
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.FastChainGeometry;
import parser.ast.nodes.ModelUnit;

/** Source-backed regression for the P05 binary/sequence edit-unit mismatch. */
public final class FastChainGeometryTest {
    private static int checks;
    private static void check(boolean yes, String why) {
        checks++;
        if (!yes) throw new AssertionError(why);
    }
    private static Canonical.Prepared prepare(CompModule module, String name) {
        var visitor = new MASGVisitor(new GlobalVariables(), Set.of(name), module);
        visitor.visit(new ModelUnit(null, module), null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static int maxChain(EGraphNode node) {
        int max = node.getOpcode() == EGraphNode.Opcode.JOIN ? FastChainGeometry.children(node).size() : 0;
        for (EGraphNode child : node.getChildren()) max = Math.max(max, maxChain(child));
        return max;
    }
    private static EGraphNode chain(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.JOIN && FastChainGeometry.children(node).size() == 3) return node;
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = chain(child);
            if (found != null) return found;
        }
        return null;
    }
    private static EGraphNode copyGeometry(EGraphNode source, int[] id) {
        var children = source.getChildren().stream().map(child -> copyGeometry(child,id)).toList();
        var copy = new EGraphNode(++id[0], source.getOpcode(), children, false, children.size(), false,
                EGraphNode.Metatype.SET);
        copy.setExactAlloyType(source.getExactAlloyType());
        return copy;
    }
    private static void pair(CompModule module, String lhs, String rhs, int expected) {
        var a = prepare(module, lhs);
        var b = prepare(module, rhs);
        var originalA = Canonical.certificationIrTemporalFol(a);
        var originalB = Canonical.certificationIrTemporalFol(b);
        for (boolean reverse : new boolean[]{false,true}) {
            JSONObject report = DistanceReplayDiagnostics.capture(reverse ? b : a, reverse ? a : b);
            for (String arm : new String[]{"fast", "certified"}) {
                JSONObject result = report.getJSONObject(arm);
                check(result.getInt("distance") == expected, lhs + "/" + rhs + " " + arm + " " + result);
                check(result.getBoolean("numericalCostCoverage"), "Cost replay coverage");
                check(!result.getBoolean("complete"), "No public authority from native replay");
            }
        }
        check(originalA.equals(Canonical.certificationIrTemporalFol(a))
                && originalB.equals(Canonical.certificationIrTemporalFol(b)), "Source syntax is not mutated");
    }
    public static void main(String[] args) throws Exception {
        CompModule reduced = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                Files.readString(Path.of("docs/distance-parity-20261107/chain-cost.als")));
        pair(reduced, "lhs", "rhs", 1);
        var left = prepare(reduced,"lhs");
        check(left.normalizedForms().stream().anyMatch(n -> maxChain(n.getMatrixEGraph()) == 3),
                "Admitted three-operand chain exposed");
        EGraphNode original = chain(left.normalizedForms().get(0).getMatrixEGraph());
        CompModule foreignModule = CompUtil.parseEverything_fromString(A4Reporter.NOP,
                Files.readString(Path.of("docs/distance-parity-20261107/chain-cost.als")));
        EGraphNode foreign = chain(prepare(foreignModule,"lhs").normalizedForms().get(0).getMatrixEGraph());
        EGraphNode.beginGraph();
        try {
            EGraphNode copied = copyGeometry(original, new int[]{0});
            check(FastChainGeometry.children(copied).size() == 3, "Original parser authority admitted");
            copied.setExactAlloyType(foreign.getExactAlloyType());
            check(FastChainGeometry.children(copied).size() == 2, "Equal type from another parser module refused");
            copied.setExactAlloyType(original.getExactAlloyType());
            copied.getChildren().get(0).setExactAlloyType(null);
            check(FastChainGeometry.children(copied).size() == 2, "Missing intermediate type refused");
        } finally { EGraphNode.endGraph(); }
        CompModule variants = CompUtil.parseEverything_fromString(A4Reporter.NOP, """
                sig A { r, s: set A }
                one sig M in A {}
                pred twice { some ((A.r).r) }
                pred singleOccurrence { some A.r }
                pred reordered { some ((A.r).s) }
                pred reverseOrder { some ((A.s).r) }
                pred guardedLeft { some ((r.s).r) }
                pred guardedRight { some (r.(s.r)) }
                pred blockedLeft { some ((r.M).s) }
                pred blockedRight { some (r.(M.s)) }
                """);
        pair(variants, "twice", "singleOccurrence", 1);
        pair(variants, "guardedLeft", "guardedRight", 0);
        var repeated = prepare(variants,"twice");
        check(repeated.normalizedForms().stream().anyMatch(n -> maxChain(n.getMatrixEGraph()) == 3),
                "Repeated operand is retained");
        var orderA = prepare(variants,"reordered");
        var orderB = prepare(variants,"reverseOrder");
        check(Canonical.distance(orderA,orderB) > 0, "JOIN order is not quotiented");
        for (String name : new String[]{"blockedLeft","blockedRight"}) {
            var blocked = prepare(variants,name);
            check(blocked.normalizedForms().stream().allMatch(n -> maxChain(n.getMatrixEGraph()) <= 2),
                    "Unary interior must retain binary boundary");
        }
        var ba = prepare(variants,"blockedLeft");
        var bb = prepare(variants,"blockedRight");
        check(Canonical.distance(ba,bb) > 0, "Unsound unary-middle collapse rejected");
        CompModule natural = CompUtil.parseEverything_fromString(A4Reporter.NOP, Files.readString(Path.of(
                "classified-data/productionLine_v1/correct/Nc6rBGbJRWcFSpGY8_inv2.als")));
        pair(natural, "inv2", "inv2c", 1);
        System.out.println("FastChainGeometryTest: " + checks + " checks passed");
    }
}
