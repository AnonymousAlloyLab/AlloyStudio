package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Source-authenticated prefix CALLs use the existing scoped invocation checker. */
public final class PrefixCallCorrespondenceTest {
    private static int checks, negatives;
    private record Located(EGraphNode node,List<Integer> branch) { }
    private record State(List<Canonical.Prepared> pair,NormalForm source,EGraphNode root,EGraphNode call) { }
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void visit(EGraphNode node,List<Integer> branch,List<Located> out) {
        if(node.getOpcode()==EGraphNode.Opcode.CALL)out.add(new Located(node,branch));
        for(int i=0;i<node.getChildren().size();i++) {
            var next=new ArrayList<>(branch);next.add(i);visit(node.getChildren().get(i),List.copyOf(next),out);
        }
    }
    private static List<Located> calls(EGraphNode root) {
        var out=new ArrayList<Located>();visit(root,List.of(),out);return out;
    }
    private static State state(JSONObject id) throws Exception {
        var pair=LocalOwnerTraceTest.prepare(id);var source=pair.get(0).normalizedForms().get(0);
        check(source.getLocalQuantiVars().isEmpty(),"actual prefix-only scope");
        var closed=ClosedCallEvidence.from(source);var bound=BoundCallEvidence.from(source);
        var oldScoped=FastLocalPaidScope.checkedCalls(source);
        var rejected=calls(source.getCertificationMatrixEGraph()).stream().filter(c->
            !oldScoped.contains(c.node())&&!ClosedCallEvidence.closed(c.node())&&!bound.admits(c.node())
                &&!closed.admitsNullary(c.node(),c.branch())).findFirst().orElseThrow();
        var root=CanonicalDistance.prepare(pair.get(0).normalizedForms()).comparisonForms().get(0).getMatrixEGraph();
        var call=calls(root).stream().map(Located::node).filter(c->c.getSourceOccurrenceLineage()==rejected.node().getSourceOccurrenceLineage())
                .findFirst().orElseThrow();
        check(call!=rejected.node()&&BoundCallEvidence.sameSourceScopedCall(rejected.node(),call),"distinct Fast object, actual parser anchor");
        check(LocalOwnerCorrespondence.check(source,root).checkedCalls().contains(call),"replacement checks original typed invocation");
        check(LocalOwnerCorrespondence.check(source,source.getMatrixEGraph()).checkedCalls().contains(rejected.node()),"original source admitted");
        return new State(pair,source,root,call);
    }
    private static void field(Object target,String name,Object value) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    private static void corrupt(JSONObject id,Consumer<State> edit,String why) throws Exception {
        var s=state(id);edit.accept(s);boolean accepted;
        try {accepted=LocalOwnerCorrespondence.check(s.source(),s.root()).checkedCalls().contains(s.call());}
        catch(IllegalArgumentException|IllegalStateException expected){accepted=false;}
        negatives++;check(!accepted,why);
    }
    private static EGraphNode.EClassRef incoming(State s) {
        var todo=new ArrayDeque<EGraphNode>();todo.add(s.root());
        while(!todo.isEmpty())for(var ref:todo.remove().getChildClasses()) {
            if(ref.getEClass().getRepresentative()==s.call())return ref;
            todo.add(ref.getEClass().getRepresentative());
        }
        throw new AssertionError("Missing CALL invocation edge");
    }
    private static void maps(JSONObject id) throws Exception {
        var s=state(id);if(s.call().getChildren().isEmpty())return;
        var edge=incoming(s);var before=new LinkedHashMap<>(edge.getSlotMap());
        check(!before.isEmpty(),"actual compound argument reads a prefix slot");
        for(var child:s.call().getChildClasses()) {
            var changed=new LinkedHashMap<String,String>();child.getSlotMap().forEach((k,v)->changed.put(k,"middle:"+v));
            field(child,"slotMap",Map.copyOf(changed));
        }
        field(s.call().getEClass(),"slotsDirty",true);
        var composed=new LinkedHashMap<String,String>();before.forEach((k,v)->composed.put("middle:"+k,v));
        field(edge,"slotMap",s.call().getEClass().invoke(composed).getSlotMap());
        check(LocalOwnerCorrespondence.check(s.source(),s.root()).checkedCalls().contains(s.call()),"valid nonidentity invocation composition");
        corrupt(id,x->{var ref=incoming(x);var changed=new LinkedHashMap<>(ref.getSlotMap());
            changed.replaceAll((k,v)->"foreign-prefix-slot");field(ref,"slotMap",Map.copyOf(changed));},"foreign effective slot rejected");
        corrupt(id,x->{var children=new ArrayList<>(x.call().getChildClasses());Collections.reverse(children);
            field(x.call(),"childClasses",children);},"ordered compound arguments cannot be reversed");
    }
    public static void main(String[] args) throws Exception {
        var input=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object value:input) {
            var id=(JSONObject)value;long started=System.nanoTime();var s=state(id);
            for(int direction=0;direction<2;direction++) {
                var a=s.pair().get(direction);var b=s.pair().get(1-direction);
                var fast=CanonicalRepairTraces.fast(a,b);
                var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                for(var trace:fast.distance()<certified.distance()?List.of(fast,certified):List.of(certified,fast)) {
                    check(trace.replay(trace.json())==trace.distance(),"selected native execution");
                    boolean rejected=false;try {trace.replay(trace.json().put("distance",-1));}
                    catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}
                    negatives++;check(rejected,"tampered native total rejected");
                }
                var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,certified);
                var row=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast.json())
                    .put("certified",certified.json()).put("status",comparison.status()).put("reason",comparison.reason())
                    .put("comparison",comparison.evidence());
                Files.writeString(out.resolve(id.getString("id")+"-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
                check(comparison.status()==NativeOperationComparison.Status.MATCHED||comparison.status()==NativeOperationComparison.Status.MISMATCH,
                    "source correspondence resolved, not relabeled: "+comparison.reason());
                results.put(new JSONObject().put("id",id.getString("id")).put("direction",direction)
                    .put("status",comparison.status()).put("fast",fast.distance()).put("certified",certified.distance()));
            }
            double seconds=(System.nanoTime()-started)/1e9;check(seconds<60,"both native paths and directions under 60 seconds");
            corrupt(id,x->field(x.call(),"callSourceAnchor",new Object()),"foreign parser anchor");
            corrupt(id,x->field(x.call(),"sourceOccurrenceLineage",-1L),"foreign source lineage");
            corrupt(id,x->field(x.call(),"callOccurrenceId",-1L),"foreign parser occurrence");
            corrupt(id,x->field(x.call(),"semanticIdentity","foreign/callee"),"substituted callee");
            corrupt(id,x->field(x.call(),"declaredArity",999),"wrong declared arity");
            corrupt(id,x->field(x.call(),"exactAlloyType",null),"missing result type");
            maps(id);
        }
        var report=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("results",results)
            .put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),report.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println(report);
    }
}
