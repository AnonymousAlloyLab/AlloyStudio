package is.fivefivefive.CanDis.core;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.ast.Command;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.CanonicalAlloyPipeline;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Closed phase idempotence, unchanged authority and metric coordinate controls. */
public final class ClosedTemporalPhaseTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++; if (!value) throw new AssertionError(label);
    }
    private record Fixture(List<NormalForm> source, CanonicalDistance.Prepared fast,
            CanonicalAlloyPipeline.Prepared certified) { }
    private static Fixture prepare(CompModule module, String predicate) {
        return prepare(module, predicate, true);
    }
    private static Fixture prepare(CompModule module, String predicate, boolean certify) {
        var visitor = new MASGVisitor(new GlobalVariables(),Set.of(predicate),module);
        visitor.visit(new ModelUnit(null,module),null);
        var graph = visitor.getForest().get(visitor.getForestId(predicate));
        var agent = new IRAgent(graph); agent.computeNormalForm();
        var source = List.copyOf(agent.normalForms());
        List<String> before = CanonicalDistance.sourceIrTemporalFol(source);
        var fast = CanonicalDistance.prepare(source);
        check(before.equals(CanonicalDistance.sourceIrTemporalFol(source)), "original IR unchanged: "+predicate);
        return new Fixture(source, fast, certify ? CanonicalAlloyPipeline.prepare(graph) : null);
    }
    private static List<EGraphNode> refs(EGraphNode root) {
        var result = new ArrayList<EGraphNode>(); collect(root,result); return result;
    }
    private static void collect(EGraphNode node,List<EGraphNode> refs) {
        if (node.getOpcode() == EGraphNode.Opcode.REF) refs.add(node);
        for (EGraphNode child : node.getChildren()) collect(child,refs);
    }
    private static void equal(Fixture left, Fixture right, String label) {
        check(CanonicalDistance.distance(left.fast,right.fast) == 0
                && CanonicalDistance.distance(right.fast,left.fast) == 0, label+" Fast0 both directions");
        check(CanonicalAlloyPipeline.distance(left.certified,right.certified) == 0
                && CanonicalAlloyPipeline.distance(right.certified,left.certified) == 0, label+" certified0 both directions");
    }
    private static void noPrune(Fixture fixture,String label) {
        check(fixture.source.size() == fixture.fast.normalFormCount(), label+" not pruned");
    }
    public static void main(String[] args) throws Exception {
        CompModule original = CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of(
                "classified-data/classroom_fol/both/4J8sS2Kr8KrhgsN6R_inv4.als")));
        var left = prepare(original,"inv4"); var right = prepare(original,"inv4c");
        check(left.source.size() == 3 && left.fast.normalFormCount() == 2, "source3, metric2");
        var distance = CanonicalDistance.evaluate(left.fast,right.fast);
        check(distance.distance() == 11 && distance.temporalDistance() == 1
                && distance.quantifierDistance() == 0 && distance.matrixDistance() == 10, "Fast11=[1,0,10]");
        check(CanonicalDistance.distance(right.fast,left.fast) == 11, "reverse Fast11");
        check(CanonicalAlloyPipeline.distance(left.certified,right.certified) == 11
                && CanonicalAlloyPipeline.distance(right.certified,left.certified) == 11, "certified11 unchanged");
        var projected = NormalForm.fastComparisonViews(left.source);
        var surviving = refs(projected.get(0).getMatrixEGraph()).get(0);
        var originalRef = refs(left.source.get(0).getMatrixEGraph()).stream()
                .filter(r -> r.temporalReferenceAuthorityId() == surviving.temporalReferenceAuthorityId()).findFirst().orElseThrow();
        check(originalRef.getSourceName().equals(surviving.getSourceName()), "surviving original reference retained");
        check(left.source.get(0).getTemporalChildren().size() == 2, "certified owner child list unchanged");
        left.source.get(0).requireAdmittedTemporalTree(); checks++;

        CompModule module = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A }
                sig B in A {}
                pred duplicate { (always no B) and (always no B) }
                pred single { always no B }
                pred mixed { (always no B) and (always no B) and (eventually some A) }
                pred trimmed { (always no B) and (eventually some A) }
                pred binaryTail { (always no B) and (always no B) and ((some A) until (no B)) }
                pred binaryTrimmed { (always no B) and ((some A) until (no B)) }
                pred differentOp { (always no B) and (eventually no B) }
                pred differentBody { (always no B) and (always some B) }
                pred differentParent { always (always no B) and eventually (always no B) }
                pred scoped { all x:A | (always some x.r) and (always some x.r) }
                pred parameter[x:A] { (always some x.r) and (always some x.r) }
                pred localOwner { one x:A | (always some x.r) and (always some x.r) }
                pred orderedParent { (always no B) implies (always no B) }
                pred disjunction { (always no B) or (always no B) }
                """);
        equal(prepare(module,"duplicate"),prepare(module,"single"),"duplicate");
        equal(prepare(module,"disjunction"),prepare(module,"single"),"OR duplicate");
        var mixed = prepare(module,"mixed"); var trimmed = prepare(module,"trimmed"); equal(mixed,trimmed,"mixed tail");
        check(mixed.source.size() == 4 && mixed.fast.normalFormCount() == 3, "one closed phase removed before tail");
        var mixedViews = NormalForm.fastComparisonViews(mixed.source);
        var tail = refs(mixedViews.get(0).getMatrixEGraph()).stream()
                .filter(r -> "temporal[2:1]".equals(r.getSourceName())).findFirst().orElseThrow();
        check("temporal[1:1]".equals(tail.comparisonTemporalReferenceName()), "tail coordinate transported, source name retained");
        check(refs(mixed.source.get(0).getMatrixEGraph()).stream().anyMatch(r ->
                r.temporalReferenceAuthorityId() == tail.temporalReferenceAuthorityId()
                && "temporal[2:1]".equals(r.comparisonTemporalReferenceName())), "tail source authority unchanged");
        mixed.source.get(0).requireAdmittedTemporalTree(); checks++;
        equal(prepare(module,"binaryTail"),prepare(module,"binaryTrimmed"),"binary temporal tail intact");
        for (String name : List.of("differentOp","differentBody","differentParent","scoped","parameter","localOwner","orderedParent"))
            noPrune(prepare(module,name,false),name);

        EGraphNode bad = new EGraphNode(40001,EGraphNode.Opcode.CONSTANT,List.of(),false,0,false,EGraphNode.Metatype.BOOLEAN);
        EGraphNode wrapper = new EGraphNode(40002,EGraphNode.Opcode.TEMPORALROOT,List.of(bad),false,1,false,EGraphNode.Metatype.BOOLEAN);
        check(EGraphNode.closedFastMatrixKey(wrapper) == null, "missing child type rejected");
        bad.setExactAlloyType(ExactAlloyType.boolType());
        check(EGraphNode.closedFastMatrixKey(wrapper) != null, "explicit Boolean root contract");
        wrapper.setExactAlloyType(ExactAlloyType.relation(List.of("A")));
        check(EGraphNode.closedFastMatrixKey(wrapper) == null, "wrong wrapper result type rejected");
        EGraphNode plainRef = new EGraphNode(40003,EGraphNode.Opcode.REF,List.of(),false,0,false,EGraphNode.Metatype.BOOLEAN);
        plainRef.setSourceName("sameSpelling"); plainRef.setSemanticIdentity("ownerA");
        var identity = CanonicalDistance.class.getDeclaredMethod("atomIdentity",EGraphNode.class);
        identity.setAccessible(true);
        check(!plainRef.hasComparisonTemporalReferenceName(), "ordinary REF has no metric override");
        check("ownerA".equals(identity.invoke(null,plainRef)), "ordinary REF semantic identity precedes spelling");
        plainRef.setSemanticIdentity("ownerB");
        check("ownerB".equals(identity.invoke(null,plainRef)), "same-spelled foreign REF remains distinct");
        plainRef.setSourceName("otherSpelling");
        check("ownerB".equals(identity.invoke(null,plainRef)), "ordinary REF identity survives display rename");
        EGraphNode repeated = bad;
        for (int i=0; i<12; i++) {
            repeated = new EGraphNode(40010+i,EGraphNode.Opcode.AND,List.of(repeated,repeated),true,2,false,EGraphNode.Metatype.BOOLEAN);
            repeated.setExactAlloyType(ExactAlloyType.boolType());
        }
        EGraphNode expanded = new EGraphNode(40030,EGraphNode.Opcode.TEMPORALROOT,List.of(repeated),false,1,false,EGraphNode.Metatype.BOOLEAN);
        check(EGraphNode.closedFastMatrixKey(expanded) == null, "repeated DAG expanded key budget fails closed");
        var options = new A4Options(); options.solver = kodkod.engine.satlab.SATFactory.get("sat4j");
        for (int scope : new int[]{1,2,4}) {
            // Calls hide temporal operators from Alloy 6.1's mode detector.
            // Keep the actual temporal AST in the command, with mutable B.
            var temporal = CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                    sig A {}
                    var sig B in A {}
                    run { not (((always no B) and (always no B) and (eventually some A))
                        iff ((always no B) and (eventually some A))) }
                    for %d but 2..4 steps
                    run { no B and after some B } for %d but 2..4 steps
                    """.formatted(scope,scope));
            Command equality = temporal.getAllCommands().get(0);
            Command changing = temporal.getAllCommands().get(1);
            for (Command command : List.of(equality,changing)) {
                check(CompUtil.isTemporalModel(temporal.getAllReachableSigs(),command), "temporal mode detected");
                check(command.minprefix == 2 && command.maxprefix == 4, "explicit trace bounds2..4");
            }
            check(!TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,temporal.getAllReachableSigs(),
                    equality,options).satisfiable(), "bounded temporal mixed-tail equality "+scope);
            check(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,temporal.getAllReachableSigs(),
                    changing,options).satisfiable(), "mutable-time control SAT, impossible statically "+scope);
        }
        System.out.println("ClosedTemporalPhaseTest: "+checks+" checks passed");
    }
}
