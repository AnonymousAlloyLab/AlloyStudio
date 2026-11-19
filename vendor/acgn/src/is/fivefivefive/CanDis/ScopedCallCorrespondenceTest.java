package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Source-bound opaque-call correspondence; no call unfolding or paid-edit admission. */
public final class ScopedCallCorrespondenceTest {
    private static int checks,negatives;
    private static void check(boolean ok,String why) {checks++;if(!ok)throw new AssertionError(why);}
    private record State(List<Canonical.Prepared> pair,NormalForm form,EGraphNode comparison,EGraphNode call) {}
    private static List<EGraphNode> nodes(EGraphNode root,EGraphNode.Opcode opcode) {
        var out=new ArrayList<EGraphNode>();var todo=new ArrayDeque<EGraphNode>();todo.add(root);
        while(!todo.isEmpty()) {var n=todo.remove();if(n.getOpcode()==opcode)out.add(n);todo.addAll(n.getChildren());}return out;
    }
    private static State state(JSONObject identity) throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var form=pair.get(0).normalizedForms().get(0);
        var comparison=CanonicalDistance.prepare(pair.get(0).normalizedForms()).comparisonForms().get(0).getMatrixEGraph();
        var calls=nodes(comparison,EGraphNode.Opcode.CALL);check(calls.size()==1,"retained single source call");
        return new State(pair,form,comparison,calls.get(0));
    }
    private static void field(Object target,String name,Object value) {
        try {var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void corrupt(JSONObject identity,Consumer<State> edit,String why) throws Exception {
        var s=state(identity);edit.accept(s);boolean accepted;
        try {accepted=LocalOwnerCorrespondence.check(s.form(),s.comparison()).checkedCalls().contains(s.call());}
        catch(IllegalArgumentException|IllegalStateException expected){accepted=false;}
        negatives++;check(!accepted,why);
    }
    private static EGraphNode.EClassRef callEdge(State s) {
        var pending=new ArrayDeque<EGraphNode>();pending.add(s.comparison());
        while(!pending.isEmpty()) {var n=pending.remove();for(var r:n.getChildClasses()) {
            if(r.getEClass().getRepresentative()==s.call())return r;
            pending.add(r.getEClass().getRepresentative());
        }}throw new AssertionError("missing incoming call invocation");
    }
    private static void invocationControls(JSONObject identity,Path out) throws Exception {
        var s=state(identity);var incoming=callEdge(s);var old=new LinkedHashMap<>(incoming.getSlotMap());
        var before=new ArrayList<>(s.call().getChildren());
        for(var child:s.call().getChildClasses()) {
            var renamed=new LinkedHashMap<String,String>();
            child.getSlotMap().forEach((k,v)->renamed.put(k,"middle:"+v));
            field(child,"slotMap",Map.copyOf(renamed));
        }
        field(s.call().getEClass(),"slotsDirty",true);
        var inverse=new LinkedHashMap<String,String>();old.forEach((k,v)->inverse.put("middle:"+k,v));
        field(incoming,"slotMap",s.call().getEClass().invoke(inverse).getSlotMap());
        check(LocalOwnerCorrespondence.check(s.form(),s.comparison()).checkedCalls().contains(s.call()),
                "authentic nonidentity maps compose to the original owned slots");
        check(before.equals(s.call().getChildren()),"nonidentity transport leaves ordered representatives unchanged");
        var stale=state(identity);var staleEdge=callEdge(stale);var extra=new LinkedHashMap<>(staleEdge.getSlotMap());
        extra.put("obsolete-call-slot","obsolete-caller-slot");field(staleEdge,"slotMap",Map.copyOf(extra));
        check(LocalOwnerCorrespondence.check(stale.form(),stale.comparison()).checkedCalls().contains(stale.call()),
                "retained extra keys do not change a live call invocation");
        EGraphNode.EClassRef unrelated=null;var pending=new ArrayDeque<EGraphNode>();pending.add(stale.comparison());
        while(unrelated==null&&!pending.isEmpty())for(var ref:pending.remove().getChildClasses()) {
            if(nodes(ref.getEClass().getRepresentative(),EGraphNode.Opcode.CALL).isEmpty()) {unrelated=ref;break;}
            pending.add(ref.getEClass().getRepresentative());
        }
        check(unrelated!=null,"actual call-free branch found below wrappers");
        var extraUnrelated=new LinkedHashMap<>(unrelated.getSlotMap());extraUnrelated.put("obsolete","unused");
        field(unrelated,"slotMap",Map.copyOf(extraUnrelated));
        check(LocalOwnerCorrespondence.check(stale.form(),stale.comparison()).checkedCalls().contains(stale.call()),
                "unrelated call-free branch does not acquire an invocation gate");
        String text=Files.readString(Path.of(identity.getString("source")));
        String changed=text.replace("s : c.grades.Grade","s : Course");
        check(!text.equals(changed),"same-typed source variant changes the parsed declaration");
        var fixture=out.resolve("same-typed-prefix.als");Files.writeString(fixture,changed,StandardOpenOption.CREATE_NEW);
        String sha=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(changed.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var sameType=new JSONObject(identity.toString()).put("source",fixture.toString()).put("sha256",sha);
        corrupt(sameType,x->{
            var edge=callEdge(x);var swapped=new LinkedHashMap<>(edge.getSlotMap());
            var a=nodes(x.call(),EGraphNode.Opcode.VARIABLE).stream().filter(n->"_q0".equals(n.getAlphaName())).findFirst().orElseThrow();
            var b=nodes(x.call(),EGraphNode.Opcode.VARIABLE).stream().filter(n->"_q1".equals(n.getAlphaName())).findFirst().orElseThrow();
            check(a.getExactAlloyType().equals(b.getExactAlloyType()),"swapped slots have identical types");
            check(LocalOwnerCorrespondence.check(x.form(),x.comparison()).checkedCalls().contains(x.call()),"same-typed variant admitted before map corruption");
            swapped.put("_q1","_q0");swapped.put("_q0","_q1");
            field(edge,"slotMap",x.call().getEClass().invoke(swapped).getSlotMap());
        },"same-typed swapped invocation rejected without changing representatives or alpha");
        corrupt(identity,x->{var edge=callEdge(x);var forged=new LinkedHashMap<>(edge.getSlotMap());
            forged.put("_q1","foreign");field(edge,"slotMap",x.call().getEClass().invoke(forged).getSlotMap());
        },"forged invocation target rejected");
    }
    public static void main(String[] args) throws Exception {
        var identity=new JSONObject(Files.readString(Path.of(args[0]))).getJSONObject("identity");
        var out=Path.of(args[1]);Files.createDirectories(out);long start=System.nanoTime();var s=state(identity);
        var original=nodes(s.form().getCertificationMatrixEGraph(),EGraphNode.Opcode.CALL).get(0);
        check(!FastLocalPaidScope.checkedCalls(s.form()).contains(original),"exact old scoped route rejects");
        check(!s.form().getLocalQuantiVars().isEmpty(),"old census rejects other call routes with locals");
        for(var root:List.of(s.form().getMatrixEGraph(),s.comparison())) {
            var admitted=LocalOwnerCorrespondence.check(s.form(),root).checkedCalls();
            check(admitted.contains(original),"original source call bound");
            for(var call:nodes(root,EGraphNode.Opcode.CALL))check(admitted.contains(call),"actual call with checked local owner admitted");
        }
        var rows=new JSONArray();
        for(int direction=0;direction<2;direction++) {
            var a=s.pair().get(direction);var b=s.pair().get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            int f=fast.json().getInt("distance"),c=certified.json().getInt("distance");
            for(var trace:f<c?List.of(fast,certified):List.of(certified,fast)) {
                check(trace.replay(trace.json())==trace.json().getInt("distance"),"actual selected native replay");
                boolean rejected=false;try {trace.replay(new JSONObject(trace.json().toString()).put("distance",-1));}
                catch(IllegalArgumentException|IllegalStateException expected){rejected=true;}check(rejected,"corrupt cost rejected");
            }
            var comparison=LocalOwnerTraceTest.compareSelected(a,b,fast,certified);
            var row=new JSONObject().put("direction",direction).put("fast",fast.json()).put("certified",certified.json())
                .put("status",comparison.status()).put("reason",comparison.reason()).put("comparison",comparison.evidence());
            Files.writeString(out.resolve("direction-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(f==40&&c==40,"native selected costs unchanged");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"checked comparison preserved: "+comparison.reason());
            check(comparison.evidence().getJSONArray("fastActions").similar(comparison.evidence().getJSONArray("certifiedActions")),
                    "actual typed action sequence equal in both paths");
            rows.put(new JSONObject().put("direction",direction).put("status",comparison.status()).put("reason",comparison.reason()));
        }
        double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"original file both directions below 60 seconds");
        corrupt(identity,x->field(x.call(),"semanticIdentity","foreign/gte"),"callee substitution");
        corrupt(identity,x->field(x.call(),"sourceOccurrenceLineage",-1L),"call lineage substitution");
        corrupt(identity,x->field(x.call(),"callOccurrenceId",-1L),"parser occurrence substitution");
        corrupt(identity,x->field(x.call(),"callSourceAnchor",new Object()),"foreign source anchor");
        corrupt(identity,x->field(x.call(),"declaredArity",1),"arity corruption");
        corrupt(identity,x->field(x.call(),"exactAlloyType",null),"result type corruption");
        corrupt(identity,x->{var cs=new ArrayList<>(x.call().getChildClasses());Collections.reverse(cs);field(x.call(),"childClasses",cs);},"ordered arguments reversed");
        corrupt(identity,x->field(nodes(x.call(),EGraphNode.Opcode.VARIABLE).stream().filter(n->n.getAlphaName().equals("_q0")).findFirst().orElseThrow(),"alphaName","_q1"),"outer variable capture");
        corrupt(identity,x->field(nodes(x.call(),EGraphNode.Opcode.VARIABLE).stream().filter(EGraphNode::hasComparisonLocalCoordinate).findFirst().orElseThrow(),"alphaName","@fast-local:8:0"),"local coordinate capture");
        corrupt(identity,x->field(nodes(x.comparison(),EGraphNode.Opcode.EXISTS).get(0),"sourceOccurrenceLineage",-1L),"local owner substitution");
        invocationControls(identity,out);
        var report=new JSONObject().put("identity",identity).put("checks",checks).put("negativeControls",negatives)
            .put("fileSeconds",seconds).put("results",rows).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),report.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("ScopedCallCorrespondenceTest: "+checks+" checks, "+negatives+" negative controls; "+seconds+"s");
    }
}
