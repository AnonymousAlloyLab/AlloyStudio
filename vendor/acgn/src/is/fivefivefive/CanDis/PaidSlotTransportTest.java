package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.MatrixDecision;

/** Actual selected paid routes, with independent native replay and corrupted evidence. */
public final class PaidSlotTransportTest {
    private static int checks, negatives, routes, guardedRoutes;
    private static boolean priorGuardProbe;
    private static void check(boolean value,String why) {
        checks++; if (!value) throw new AssertionError(why);
    }
    private static Object get(Object object,String field) throws Exception {
        var f=object.getClass().getDeclaredField(field); f.setAccessible(true); return f.get(object);
    }
    private static void set(Object object,String field,Object value) throws Exception {
        var f=object.getClass().getDeclaredField(field); f.setAccessible(true); f.set(object,value);
    }
    private static void reject(Runnable run,String why) {
        checks++; negatives++;
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError("Accepted corrupted "+why);
    }
    private static void removeContextOrigins(EGraphNode node) throws Exception {
        set(node,"domainContextOrigin",null);
        for(var child:node.getChildren())removeContextOrigins(child);
    }
    private static void guardControls(NormalForm form, NormalForm.DeclarationFrame frame) throws Exception {
        var evidence=NormalForm.class.getDeclaredMethod("retainedDeclarationDomainEvidence",NormalForm.DeclarationFrame.class);
        evidence.setAccessible(true);
        var original=(JSONObject)evidence.invoke(form,frame);
        if(original==null || !original.getString("kind").equals("matrix-membership"))return;
        var clone=EGraphNode.class.getDeclaredMethod("fastComparisonMatrix",EGraphNode.class);clone.setAccessible(true);
        var matrix=(EGraphNode)get(form,"matrixEGraphRoot");
        var copied=(EGraphNode)clone.invoke(null,matrix);
        var guard=copied;
        var path=original.getString("path").split("/");
        for(int i=1;i<path.length;i+=2)guard=guard.getChildren().get(Integer.parseInt(path[i+1]));
        try {
            // Identical typed membership and dependencies, but no issuance token.
            if(!priorGuardProbe)set(guard,"domainGuardOrigin",null);
            set(form,"matrixEGraphRoot",copied);
            check((evidence.invoke(form,frame)!=null)==priorGuardProbe,"same-shaped unissued decoy");negatives++;
            // The real membership is present, but detached from its Boolean context.
            set(form,"matrixEGraphRoot",clone.invoke(null,matrix));
            var actual=(EGraphNode)get(form,"matrixEGraphRoot");
            for(int i=1;i<path.length;i+=2)actual=actual.getChildren().get(Integer.parseInt(path[i+1]));
            set(form,"matrixEGraphRoot",actual);
            check((evidence.invoke(form,frame)!=null)==priorGuardProbe,"guard under wrong Boolean context");negatives++;
            if(!priorGuardProbe) {
                var unbound=(EGraphNode)clone.invoke(null,matrix);
                var key=NormalForm.class.getDeclaredMethod("domainContextKey",EGraphNode.class);key.setAccessible(true);
                var before=key.invoke(null,unbound);
                removeContextOrigins(unbound);
                if(!before.equals(key.invoke(null,unbound))) {
                    set(form,"matrixEGraphRoot",unbound);
                    check(evidence.invoke(form,frame)==null,"unissued enclosing source context rejected");negatives++;
                }
            }
        } finally {set(form,"matrixEGraphRoot",matrix);}
        check(evidence.invoke(form,frame)!=null,"restored issued guard");
    }
    private static Set<String> frames(Canonical.Prepared prepared) throws Exception {
        var unmapped = new HashSet<String>();
        var delegate=(CanonicalDistance.Prepared)get(prepared,"delegate");
        var method=NormalForm.class.getDeclaredMethod("authenticDeclarationFrame",NormalForm.DeclarationFrame.class);
        method.setAccessible(true);
        var domain=NormalForm.class.getDeclaredMethod("retainedDeclarationDomain",NormalForm.DeclarationFrame.class);
        domain.setAccessible(true);
        for (var form:delegate.comparisonForms()) for(var q:form.getMatrixQuantiVars()) for(var frame:q.getDeclarationFrames()) {
            if (!(Boolean)method.invoke(form,frame)) continue;
            check(true,"original frame accepted");
            boolean retained=(Boolean)domain.invoke(form,frame);
            if(retained)guardControls(form,frame);
            if(!retained) {
                unmapped.add(q.getName());
                if(!priorGuardProbe) {
                    var key=NormalForm.class.getDeclaredMethod("domainContextKey",EGraphNode.class);key.setAccessible(true);
                    var original=get(form,"comparisonSource");
                    System.err.println("GUARD_CONTEXT "+new JSONObject().put("binding",q.getName())
                            .put("expected",String.valueOf(get(original==null?form:original,"issuedDomainContext")))
                            .put("actual",String.valueOf(key.invoke(null,get(form,"matrixEGraphRoot")))));
                }
            }
            if (retained && !frame.dependencies().isEmpty()) {
                var matrix=get(form,"matrixEGraphRoot");
                try {
                    set(form,"matrixEGraphRoot",null);
                    check(!(Boolean)domain.invoke(form,frame),"missing dependent-domain guard rejected"); negatives++;
                } finally { set(form,"matrixEGraphRoot",matrix); }
            }
            Object owner=get(frame,"owner");
            try {
                set(frame,"owner",new NormalForm());
                check(!(Boolean)method.invoke(form,frame),"same slot with foreign owner rejected"); negatives++;
            } finally { set(frame,"owner",owner); }
            Object ordinal=get(frame,"vectorIndex");
            try {
                set(frame,"vectorIndex",((Integer)ordinal)+1);
                check(!(Boolean)method.invoke(form,frame),"wrong original vector index rejected"); negatives++;
            } finally { set(frame,"vectorIndex",ordinal); }
        }
        return unmapped;
    }
    private static MatrixDecision<EGraphNode> removeGuard(MatrixDecision<EGraphNode> op,EGraphNode guard) {
        if(op.source()==guard||op.target()==guard)return MatrixDecision.whole(null,null,op.cost());
        var children=new ArrayList<MatrixDecision.Edge<EGraphNode>>();
        for(var edge:op.children())children.add(new MatrixDecision.Edge<>(edge.leftIndex(),edge.rightIndex(),removeGuard(edge.decision(),guard)));
        return new MatrixDecision<>(op.source(),op.target(),op.kind(),op.cost(),op.headCost(),op.unordered(),
                op.leftAlternative(),op.rightAlternative(),op.selected()==null?null:removeGuard(op.selected(),guard),children);
    }
    @SuppressWarnings("unchecked")
    private static void selectedGuardControls(Canonical.Prepared a,Canonical.Prepared b,JSONObject receipt) throws Exception {
        var capture=CanonicalDistance.class.getDeclaredMethod("captureDecisions",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class);
        capture.setAccessible(true);
        var alignment=get(get(capture.invoke(null,get(a,"delegate"),get(b,"delegate")),"decisions"),"alignment");
        var phases=(List<Object>)get(alignment,"phases");
        for(var route:(List<?>)get(alignment,"paidSlotRoutes")) {
            var guards=(List<?>)get(route,"guardChoices");
            if(guards.isEmpty())continue;
            var bindProgram=route.getClass().getDeclaredMethod("bindProgram",JSONObject.class);bindProgram.setAccessible(true);
            check((Boolean)bindProgram.invoke(route,receipt),"actual selected guard emission bound");
            int owner=(Integer)get(route,"owner");
            var binding=((JSONArray)get(route,"guardOperations")).getJSONObject(0);
            int index=binding.getInt("operationIndex");
            var missing=new JSONObject(receipt.toString());
            missing.getJSONArray("phases").getJSONObject(owner).getJSONObject("program").getJSONArray("operations").remove(index);
            check(!(Boolean)bindProgram.invoke(route,missing),"missing emitted guard rejected before snapshot");negatives++;
            var unpaid=new JSONObject(receipt.toString());
            var op=unpaid.getJSONArray("phases").getJSONObject(owner).getJSONObject("program").getJSONArray("operations").getJSONObject(index);
            op.put("cost",op.getInt("cost")+1);
            check(!(Boolean)bindProgram.invoke(route,unpaid),"changed emitted guard charge rejected before snapshot");negatives++;
            var misrouted=new JSONObject(receipt.toString());
            misrouted.getJSONArray("phases").getJSONObject(owner).getJSONObject("program").getJSONArray("operations").getJSONObject(index).put("target","root/foreign");
            check(!(Boolean)bindProgram.invoke(route,misrouted),"unselected guard occurrence rejected before snapshot");negatives++;
            var phase=phases.get(owner);
            var ctor=phase.getClass().getDeclaredConstructors()[0];ctor.setAccessible(true);
            var decision=(MatrixDecision<EGraphNode>)get(phase,"matrix");
            var guard=(EGraphNode)get(guards.get(0),"guard");
            var changed=new ArrayList<>(phases);
            changed.set(owner,ctor.newInstance(get(phase,"mapping"),removeGuard(decision,guard)));
            var bind=route.getClass().getDeclaredMethod("bind",List.class);bind.setAccessible(true);
            check(!(Boolean)bind.invoke(route,changed),"omitted selected guard cannot authorize transport");negatives++;
            check((Boolean)bind.invoke(route,phases)&&(Boolean)bindProgram.invoke(route,receipt),"restored selected guard");
        }
    }
    public static void main(String[] args) throws Exception {
        priorGuardProbe=args.length>2&&args[2].equals("prior-guard-probe");
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));
        var out=Path.of(args[1]); Files.createDirectory(out); var rows=new JSONArray();
        for(Object raw:manifest.getJSONArray("cases")) {
            var identity=(JSONObject)raw; var pair=LocalOwnerTraceTest.prepare(identity);
            var unmapped=List.of(frames(pair.get(0)),frames(pair.get(1)));
            boolean missing=!unmapped.get(0).isEmpty()||!unmapped.get(1).isEmpty();
            check(missing==identity.optBoolean("unmappedDomainControl",false),"domain transport expectation "+identity.getString("id")+" "+unmapped);
            for(int direction=0;direction<2;direction++) {
                var a=pair.get(direction); var b=pair.get(1-direction);
                var fast=CanonicalRepairTraces.fast(a,b);
                var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                var receipt=fast.json(); fast.replay(receipt); cert.replay(cert.json());
                if(!priorGuardProbe)selectedGuardControls(a,b,receipt);
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+"-native.json"),
                        new JSONObject().put("fast",receipt).put("certified",cert.json()).toString(),StandardOpenOption.CREATE_NEW);
                check(fast.distance()==Canonical.distance(a,b),"numeric/captured paths agree");
                var selected=receipt.optJSONArray("selectedPaidSlotRoutes");
                if(selected!=null && !selected.isEmpty()) {
                for(Object rawRoute:selected) {
                    var route=(JSONObject)rawRoute;
                    check(!unmapped.get(direction).contains(route.getString("source"))
                            && !unmapped.get(1-direction).contains(route.getString("target")),"unmapped domain cannot borrow a paid slot route");
                    guardedRoutes+=route.getJSONArray("domainGuards").length();
                }
                routes+=selected.length();
                var bad=fast.json(); bad.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0).put("owner",-1);
                reject(()->fast.replay(bad),"owner");
                var reads=selected.getJSONObject(0).getJSONArray("selectedReads");
                check(!reads.isEmpty(),"actual selected typed reads retained");
                var crossed=fast.json(); crossed.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0)
                        .getJSONArray("selectedReads").getJSONObject(0).put("targetPath","root/foreign");
                reject(()->fast.replay(crossed),"selected read pairing");
                var paid=fast.json(); paid.getJSONArray("nativeDeclarationPrograms").getJSONObject(selected.getJSONObject(0).getInt("owner")).put("cost",0);
                reject(()->fast.replay(paid),"declaration payment");
                var foreign=fast.json(); foreign.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0)
                        .getJSONArray("sourceFrames").getJSONObject(0).put("type","FORGED");
                reject(()->fast.replay(foreign),"typed original binding");
                var guard=fast.json(); guard.getJSONArray("selectedPaidSlotRoutes").getJSONObject(0)
                        .getJSONArray("domainGuards").put(new JSONObject().put("path","root/foreign"));
                reject(()->fast.replay(guard),"domain guard inventory");
                } else {
                    check(receipt.has("selectedPaidDeclarationCorrespondence"),"existing paid path retained on a tie");
                }
                var comparison=NativeOperationComparison.compare(a,b,fast,cert);
                var row=new JSONObject().put("identity",identity).put("direction",direction).put("fast",receipt)
                        .put("certified",cert.json()).put("comparison",comparison.evidence());
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
                rows.put(new JSONObject().put("identity",identity).put("direction",direction)
                        .put("fast",fast.distance()).put("certified",cert.distance()).put("status",comparison.status()).put("reason",comparison.reason()));
            }
        }
        check(routes>=4,"both temporal families use the new checked fallback in both directions");
        check(guardedRoutes>=2,"both directions exercise selected differing-domain guards");
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("routes",routes).put("guardedRoutes",guardedRoutes).put("rows",rows);
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("PaidSlotTransportTest: "+checks+" checks, "+negatives+" negative controls");
    }
}
