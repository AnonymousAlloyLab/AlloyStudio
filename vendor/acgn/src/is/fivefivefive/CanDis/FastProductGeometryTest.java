package is.fivefivefive.CanDis;

import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.FastChainGeometry;
import parser.ast.nodes.ModelUnit;

/** Actual first-stop pair and guarded plain-product edit geometry. */
public final class FastProductGeometryTest {
    private static int checks;
    private static void check(boolean value,String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static Canonical.Prepared prepare(CompModule module,String name) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
    }
    private static void pair(CompModule module,String lhs,String rhs,int cost) {
        var a = prepare(module,lhs); var b = prepare(module,rhs);
        var sourceA = Canonical.certificationIrTemporalFol(a);
        var sourceB = Canonical.certificationIrTemporalFol(b);
        for (boolean reverse : new boolean[]{false,true}) {
            JSONObject report = DistanceReplayDiagnostics.capture(reverse ? b : a, reverse ? a : b);
            for (String arm : List.of("fast","certified")) {
                JSONObject result = report.getJSONObject(arm);
                check(result.getInt("distance") == cost, lhs+"/"+rhs+" "+arm+" cost="+result.getInt("distance"));
                check(result.getBoolean("numericalCostCoverage"), "native numeric replay coverage");
                check(!result.getBoolean("complete") && !result.getBoolean("publicSequenceLinked"), "no public authority");
                if (lhs.equals("inv12")) check(result.getInt("temporalDistance") == 0
                        && result.getInt("quantifierDistance") == 3 && result.getInt("matrixDistance") == 11,
                        "actual14=[0,3,11]");
            }
        }
        check(sourceA.equals(Canonical.certificationIrTemporalFol(a))
                && sourceB.equals(Canonical.certificationIrTemporalFol(b)), "source certificate trees unchanged");
    }
    private static EGraphNode product(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.ARROW
                && node.getChildren().stream().anyMatch(c -> c.getOpcode() == EGraphNode.Opcode.ARROW)) return node;
        for (EGraphNode child : node.getChildren()) {
            EGraphNode found = product(child); if (found != null) return found;
        }
        return null;
    }
    private static EGraphNode product(Canonical.Prepared prepared) {
        return Objects.requireNonNull(product(prepared.normalizedForms().get(0).getMatrixEGraph()));
    }
    private static EGraphNode copy(EGraphNode source,int[] id) {
        List<EGraphNode> children = source.getChildren().stream().map(c -> copy(c,id)).toList();
        EGraphNode result = new EGraphNode(++id[0],source.getOpcode(),children,false,children.size(),false,source.getMetatype());
        result.setExactAlloyType(source.getExactAlloyType());
        return result;
    }
    public static void main(String[] args) throws Exception {
        var natural = CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(ProductShorterReplayTest.SOURCE));
        pair(natural,"inv12","inv12c",14);
        String model = """
                sig A { r:set A }
                sig B, C, D {}
                pred left { some ((A->B)->C) }
                pred right { some (A->(B->C)) }
                pred shortProduct { some (A->B) }
                pred repeated { some (A->A->A) }
                pred shorterRepeated { some (A->A) }
                pred ordered { some (A->B->C) }
                pred reordered { some (B->A->C) }
                pred scopedLeft { all x:A | some ((x->B)->C) }
                pred scopedRight { all x:A | some (x->(B->C)) }
                pred family { some ((A+B)->C->D) }
                pred joinBarrier { some ((A.r)->B->C) }
                """;
        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP,model);
        pair(module,"left","right",0);
        pair(module,"left","shortProduct",2);
        pair(module,"repeated","shorterRepeated",2);
        pair(module,"scopedLeft","scopedRight",0);
        var ordered = prepare(module,"ordered"); var reordered = prepare(module,"reordered");
        check(Canonical.distance(ordered,reordered) > 0 && Canonical.distance(reordered,ordered) > 0,
                "product order is not quotiented");
        EGraphNode original = product(prepare(module,"right"));
        List<EGraphNode> exposed = FastChainGeometry.children(original);
        check(original.getChildren().size() == 2 && exposed.size() == 3, "source binary, metric sequence3");
        List<EGraphNode> expected = new ArrayList<>();
        for (EGraphNode child : original.getChildren()) {
            if (child.getOpcode() == EGraphNode.Opcode.ARROW) expected.addAll(child.getChildren());
            else expected.add(child);
        }
        for (int i=0; i<3; i++) check(exposed.get(i) == expected.get(i), "exact ordered operand occurrence retained");
        check(FastChainGeometry.children(product(prepare(module,"repeated"))).size() == 3, "repetition retained");
        check(FastChainGeometry.children(product(prepare(module,"family"))).size() == 2, "multi-product family unsupported");
        var barrier = FastChainGeometry.children(product(prepare(module,"joinBarrier")));
        check(barrier.size() == 3 && barrier.get(0).getOpcode() == EGraphNode.Opcode.JOIN, "JOIN remains atomic product operand");
        var foreignModule = CompUtil.parseEverything_fromString(A4Reporter.NOP,model);
        EGraphNode foreign = product(prepare(foreignModule,"right"));
        EGraphNode.beginGraph();
        try {
            EGraphNode copied = copy(original,new int[]{0});
            check(FastChainGeometry.children(copied).size() == 3, "same parser evidence accepted");
            copied.setExactAlloyType(foreign.getExactAlloyType());
            check(FastChainGeometry.children(copied).size() == 2, "foreign parser root rejected");
            copied.setExactAlloyType(original.getExactAlloyType());
            EGraphNode nested = copied.getChildren().stream().filter(c -> c.getOpcode() == EGraphNode.Opcode.ARROW).findFirst().orElseThrow();
            ExactAlloyType nestedType = nested.getExactAlloyType();
            nested.setExactAlloyType(null);
            check(FastChainGeometry.children(copied).size() == 2, "missing intermediate type rejected");
            nested.setExactAlloyType(ExactAlloyType.relation(nestedType.alternatives().get(0)));
            check(FastChainGeometry.children(copied).size() == 2, "untrusted lookalike intermediate rejected");
            nested.setExactAlloyType(nestedType);
            copied.setExactAlloyType(nestedType);
            check(FastChainGeometry.children(copied).size() == 2, "wrong result type rejected");
            copied.setExactAlloyType(original.getExactAlloyType());
            EGraphNode qualified = new EGraphNode(4000,EGraphNode.Opcode.ANY_ARROW_ONE,nested.getChildren(),false,2,false,nested.getMetatype());
            qualified.setExactAlloyType(nestedType);
            List<EGraphNode> operands = copied.getChildren().stream().map(c -> c == nested ? qualified : c).toList();
            EGraphNode qualifiedTail = new EGraphNode(4001,EGraphNode.Opcode.ARROW,operands,false,2,false,copied.getMetatype());
            qualifiedTail.setExactAlloyType(copied.getExactAlloyType());
            check(FastChainGeometry.children(qualifiedTail).size() == 2, "multiplicity-qualified inner arrow is a barrier");
            EGraphNode qualifiedRoot = new EGraphNode(4002,EGraphNode.Opcode.ONE_ARROW_ANY,copied.getChildren(),false,2,false,copied.getMetatype());
            qualifiedRoot.setExactAlloyType(copied.getExactAlloyType());
            check(FastChainGeometry.children(qualifiedRoot).size() == 2, "multiplicity-qualified root not flattened");
            copied.setExactAlloyType(ExactAlloyType.relation(Collections.nCopies(65,"A")));
            check(FastChainGeometry.children(copied).size() == 2, "arity budget fails closed");
        } finally { EGraphNode.endGraph(); }
        var options = new A4Options(); options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        for (int scope : new int[]{1,2,4}) {
            var bounded = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                    sig A, B, C {}
                    run { ((A->B)->C) != (A->(B->C)) } for %d
                    run { some A and some B and A->B != B->A } for %d
                    """.formatted(scope,scope));
            check(!TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,bounded.getAllReachableSigs(),
                    bounded.getAllCommands().get(0),options).satisfiable(), "bounded product reassociation UNSAT "+scope);
            check(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,bounded.getAllReachableSigs(),
                    bounded.getAllCommands().get(1),options).satisfiable(), "order counterexample SAT "+scope);
        }
        System.out.println("FastProductGeometryTest: "+checks+" checks passed");
    }
}
