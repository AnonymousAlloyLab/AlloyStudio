package is.fivefivefive.CanDis.core;

import java.nio.file.Files;
import java.util.*;
import org.json.JSONObject;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import edu.mit.csail.sdg.translator.A4Options;
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.Canonical;
import is.fivefivefive.CanDis.DistanceReplayDiagnostics;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import parser.ast.nodes.ModelUnit;

/** Inventory identity is source owner/branches, never body equality or REF spelling. */
public final class TemporalOccurrenceAccountingTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if (!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private record Fixture(List<NormalForm> forms,TheoryAlloyAdapter.Result evidence,RepairView view,Canonical.Prepared prepared) { }
    private static Fixture prepare(CompModule module,String name) {
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);
        visitor.visit(new ModelUnit(null,module),null);
        var graph=visitor.getForest().get(visitor.getForestId(name));
        var agent=new IRAgent(graph); agent.computeNormalForm();
        var forms=List.copyOf(agent.normalForms());
        String before=CanonicalDistance.sourceIrTemporalFol(forms).toString();
        var evidence=TheoryAlloyAdapter.adapt(forms);
        var view=RepairProjection.project(evidence,forms);
        check(before.equals(CanonicalDistance.sourceIrTemporalFol(forms).toString()),"source untouched");
        return new Fixture(forms,evidence,view,Canonical.prepare(graph));
    }
    private static void refs(EGraphNode n,List<EGraphNode> out) {
        if(n.getOpcode()==EGraphNode.Opcode.REF) out.add(n);
        for(var c:n.getChildren()) refs(c,out);
    }
    private static List<EGraphNode> refs(NormalForm owner) {
        List<EGraphNode> refs=new ArrayList<>(); refs(owner.getCertificationMatrixEGraph(),refs); return refs;
    }
    private static int uses(RepairView.Node node,String temporal) {
        int count=temporal.equals(node.observationTemporalOperator())?1:0;
        for(var child:node.children()) count+=uses(child,temporal); return count;
    }
    private static int count(RepairView.TemporalNode tree,String label) {
        int count=tree.label().equals(label)?1:0;
        for(var child:tree.children()) count+=count(child,label); return count;
    }
    public static void main(String[] args) throws Exception {
        var original=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(ScopedTemporalShorterReplayTest.SOURCE));
        var a=prepare(original,"inv18"); var b=prepare(original,"inv18c");
        for(boolean reverse:new boolean[]{false,true}) {
            JSONObject report=DistanceReplayDiagnostics.capture(reverse?b.prepared:a.prepared,reverse?a.prepared:b.prepared);
            for(String arm:List.of("fast","certified")) {
                var r=report.getJSONObject(arm);
                check(r.getInt("distance")==21 && r.getInt("temporalDistance")==1
                        && r.getInt("quantifierDistance")==0 && r.getInt("matrixDistance")==20,"21=[1,0,20] "+arm);
                check(r.getBoolean("numericalCostCoverage") && !r.getBoolean("complete"),"native only replay");
            }
        }
        check(a.forms.size()==3 && a.view.phases().size()==3,"no source phase removed");
        var sourceRefs=refs(a.forms.get(1));
        check(sourceRefs.size()==2 && sourceRefs.get(0).temporalReferenceAuthorityId()>0
                && sourceRefs.get(0).temporalReferenceAuthorityId()==sourceRefs.get(1).temporalReferenceAuthorityId(),
                "IFF repeats one actual owner-issued occurrence");
        check(uses(a.view.phases().get(1).matrix(),"AFTER")==2,"both matrix uses remain");
        check(count(a.view.temporalRoot(),"AFTER")==1,"one AFTER occurrence in inventory");
        var inherited=a.view.phases().get(2).bindings().get(0);
        check(inherited.role()==RepairView.BindingRole.INHERITED && inherited.ownerPhase()==1
                && inherited.declaration().sameCertifiedPayload(a.view.phases().get(1).quantifiers().get(0)),
                "inherited binder stays attached to original owner");
        a.forms.get(0).requireAdmittedTemporalTree(); checks++;
        var foreign=prepare(original,"inv18");
        reject(() -> RepairProjection.project(a.evidence,foreign.forms),"lookalike source bundle cannot substitute");
        var field=NormalForm.class.getDeclaredField("matrixEGraphRoot"); field.setAccessible(true);
        field.set(foreign.forms.get(1),sourceRefs.get(0));
        reject(() -> foreign.forms.get(0).requireAdmittedTemporalTree(),"foreign owner REF rejected");

        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                var sig File {}
                var sig Protected, Trash in File {}
                pred binary { always all p:Protected | (p in File iff ((p in Trash) releases (p in Protected))) }
                pred separate { always all p:Protected | (p in File iff after p in Trash) and (p in Trash iff after p in Trash) }
                pred differentOp { always all p:Protected | (after p in Trash) and (eventually p in Trash) }
                pred differentOpIff { always all p:Protected | (p in File iff after p in Trash) and (p in Trash iff eventually p in Trash) }
                pred differentOwner { (after all p:Protected | (p in File iff after p in Trash))
                    and (always all q:Protected | (q in File iff after q in Trash)) }
                pred scoped { always ((all p:Protected | p in File iff after p in Trash)
                    and (all q:Protected | q in File iff after q in Protected)) }
                """);
        var binary=prepare(module,"binary");
        System.out.println("BINARY_SOURCE_OPS "+binary.forms.stream().map(f -> f.getTemporalOp().name()).toList()
                +" RELEASES="+count(binary.view.temporalRoot(),"RELEASES")+" UNTIL="+count(binary.view.temporalRoot(),"UNTIL")
                +" phases="+binary.view.phases().size()+" uses="+uses(binary.view.phases().get(1).matrix(),"UNTIL"));
        check(binary.forms.get(2).getTemporalOp()==NormalForm.TemporalOp.UNTILL
                && binary.forms.get(3).getTemporalOp()==NormalForm.TemporalOp.UNTILR
                && count(binary.view.temporalRoot(),"UNTIL")==1,"one normalized binary source occurrence");
        check(uses(binary.view.phases().get(1).matrix(),"UNTIL")>0,"binary reference retains source operator");
        check(binary.view.phases().size()==4,"both binary branch phases retained");
        var separate=prepare(module,"separate");
        check(count(separate.view.temporalRoot(),"AFTER")==2,"separate source occurrences with equal bodies remain distinct");
        var different=prepare(module,"differentOp");
        check(count(different.view.temporalRoot(),"AFTER")==1 && count(different.view.temporalRoot(),"EVENTUALLY")==1,
                "different temporal operators remain distinct");
        var polarity=prepare(module,"differentOpIff");
        var sourceOperators=polarity.forms.get(1).getTemporalChildren().stream().map(f -> f.getTemporalOp().name()).sorted().toList();
        var projectedOperators=polarity.view.temporalRoot().children().get(0).children().stream().map(RepairView.TemporalNode::label).sorted().toList();
        System.out.println("POLARITY_SOURCE_OPS "+sourceOperators+" PROJECTED "+projectedOperators);
        check(sourceOperators.size()==2 && new HashSet<>(sourceOperators).size()==2
                && projectedOperators.equals(sourceOperators),"distinct normalized source operators/branch groups preserved");
        var owners=prepare(module,"differentOwner");
        check(owners.view.temporalRoot().children().size()==2
                && owners.view.temporalRoot().children().stream().allMatch(t -> t.children().size()==1),
                "same local REF names in different parents remain separate");
        var scoped=prepare(module,"scoped");
        check(count(scoped.view.temporalRoot(),"AFTER")==2,"distinct scoped binders not merged");
        var options=new A4Options(); options.solver=kodkod.engine.satlab.SATFactory.get("sat4j");
        for(int scope:new int[]{1,2,4}) {
            var bounded=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                    var sig File {}
                    var sig Protected, Trash in File {}
                    run { not ((always all p:Protected | (p in File-Protected iff after p in Trash)) iff
                        (always all p:Protected | ((p in File-Protected or not after p in Trash)
                            and (p not in File-Protected or after p in Trash)))) } for %d but 2..4 steps
                    run { some File and no Trash and after some Trash } for %d but 2..4 steps
                    """.formatted(scope,scope));
            for(var command:bounded.getAllCommands()) {
                check(CompUtil.isTemporalModel(bounded.getAllReachableSigs(),command),"actual temporal mode");
                check(command.minprefix==2 && command.maxprefix==4,"explicit trace bounds");
            }
            check(!TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,bounded.getAllReachableSigs(),
                    bounded.getAllCommands().get(0),options).satisfiable(),"IFF expansion bounded UNSAT "+scope);
            check(TranslateAlloyToKodkod.execute_command(A4Reporter.NOP,bounded.getAllReachableSigs(),
                    bounded.getAllCommands().get(1),options).satisfiable(),"mutable-time SAT control "+scope);
        }
        System.out.println("TemporalOccurrenceAccountingTest: "+checks+" checks passed");
    }
}
