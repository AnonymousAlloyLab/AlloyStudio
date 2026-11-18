package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Retained universal groups use the existing source-object checker. */
public final class UniversalDeclarationProjectionTest {
    private static int checks, negatives;
    private static void check(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    private static String reject(Runnable run, String why) {
        checks++; negatives++;
        try { run.run(); } catch (IllegalArgumentException | IllegalStateException e) { return e.toString(); }
        throw new AssertionError("Accepted " + why);
    }
    private static void field(Object o, String name, Object value) {
        try { var f=o.getClass().getDeclaredField(name); f.setAccessible(true); f.set(o,value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static EGraphNode occurrence(EGraphNode n,long id) {
        if(n.getSourceOccurrenceLineage()==id)return n;
        for(var c:n.getChildren()){var found=occurrence(c,id);if(found!=null)return found;}
        return null;
    }
    private static void owners(EGraphNode n,List<EGraphNode> out) {
        if(n.getOpcode()==EGraphNode.Opcode.FORALL && n.getChildren().stream().anyMatch(c ->
                LocalOwnerCorrespondence.declaration(c) && c.comparisonDeclarationGroups(n).size()>1))out.add(n);
        for(var c:n.getChildren())owners(c,out);
    }
    private static EGraphNode grouped(EGraphNode owner) {
        return owner.getChildren().stream().filter(c -> LocalOwnerCorrespondence.declaration(c)
                && c.comparisonDeclarationGroups(owner).size()>1).findFirst().orElseThrow();
    }
    private static EGraphNode copy(NormalForm nf) {
        return CanonicalDistance.prepare(List.of(nf)).comparisonForms().get(0).getMatrixEGraph();
    }
    private static void ownerControls(NormalForm nf,long ownerId,JSONArray controls) {
        var root=copy(nf);var owner=occurrence(root,ownerId);var group=grouped(owner);
        var originals=group.comparisonDeclarationGroups(owner);
        var source=LocalOwnerCorrespondence.check(nf,nf.getCertificationMatrixEGraph()).json();
        var current=LocalOwnerCorrespondence.check(nf,root).json();
        check(source.similar(current),"exact source groups, domain occurrences, ordered typed slots and parent owners");
        var first=originals.get(0);
        boolean oldTuple=first.getOpcode()==group.getOpcode()
            &&first.getSourceOccurrenceLineage()==group.getSourceOccurrenceLineage()
            &&first.getChildren().get(0).getSourceOccurrenceLineage()==group.getChildren().get(0).getSourceOccurrenceLineage()
            &&first.getChildren().size()==group.getChildren().size();
        check(!oldTuple,"exact old tuple gate rejects the actual regrouped universal");
        check(owner.getOpcode()!=EGraphNode.Opcode.COMPREHENSION,"exact old capture branch excluded FORALL");
        controls.put(new JSONObject().put("control","old-reject-new-accept").put("owner",ownerId)
            .put("oldTupleGate",oldTuple).put("oldCaptureGate",false).put("original",source).put("current",current));
        var foreignRoot=copy(nf);var foreignOwner=occurrence(foreignRoot,ownerId);var foreign=grouped(foreignOwner);
        check(owner!=foreignOwner&&owner.getSourceOccurrenceLineage()==foreignOwner.getSourceOccurrenceLineage(),"same metadata distinct owners");
        controls.put(new JSONObject().put("control","same-metadata-foreign-owner").put("rejection",
            reject(()->group.comparisonDeclarationGroups(foreignOwner),"foreign owner")));
        var slot=group.getChildren().get(1);var foreignSlot=foreign.getChildren().get(1);
        check(slot!=foreignSlot&&slot.getSourceOccurrenceLineage()==foreignSlot.getSourceOccurrenceLineage()
            &&slot.getAlphaName().equals(foreignSlot.getAlphaName())
            &&slot.getExactAlloyType().sameOccurrenceEvidenceAs(foreignSlot.getExactAlloyType()),"same metadata distinct slots");
        var children=new ArrayList<>(group.getChildClasses());children.set(1,foreign.getChildClasses().get(1));field(group,"childClasses",children);
        controls.put(new JSONObject().put("control","same-metadata-foreign-slot").put("rejection",
            reject(()->LocalOwnerCorrespondence.check(nf,root),"foreign slot")));
        var changes=new LinkedHashMap<String,Consumer<EGraphNode>>();
        changes.put("missing-receipt",o->field(grouped(o),"comparisonDeclarationGrouping",null));
        changes.put("owner-occurrence",o->field(o,"sourceOccurrenceLineage",-91L));
        changes.put("owner-kind",o->field(o,"opcode",EGraphNode.Opcode.EXISTS));
        changes.put("group-occurrence",o->field(grouped(o),"sourceOccurrenceLineage",-92L));
        changes.put("domain-occurrence",o->field(grouped(o).getChildren().get(0),"sourceOccurrenceLineage",-93L));
        changes.put("domain-meaning",o->field(grouped(o).getChildren().get(0).getChildren().get(0),"semanticIdentity","Foreign"));
        changes.put("slot-type",o->field(grouped(o).getChildren().get(1),"exactAlloyType",ExactAlloyType.unaryRelation("Foreign")));
        changes.put("slot-occurrence",o->field(grouped(o).getChildren().get(1),"sourceOccurrenceLineage",-94L));
        changes.put("captured-coordinate",o->field(grouped(o).getChildren().get(1),"alphaName","@fast-local:99:0"));
        changes.put("slot-order",o->{var g=grouped(o);var cs=new ArrayList<>(g.getChildClasses());Collections.swap(cs,1,2);field(g,"childClasses",cs);});
        changes.put("duplicate-slot",o->{var g=grouped(o);var cs=new ArrayList<>(g.getChildClasses());cs.set(2,cs.get(1));field(g,"childClasses",cs);});
        changes.put("missing-slot",o->{var g=grouped(o);var cs=new ArrayList<>(g.getChildClasses());cs.remove(2);field(g,"childClasses",cs);});
        changes.put("disjoint-substitution",o->field(grouped(o),"opcode",EGraphNode.Opcode.DISJ));
        changes.put("body-before-declaration",o->{var cs=new ArrayList<>(o.getChildClasses());Collections.swap(cs,0,cs.size()-1);field(o,"childClasses",cs);});
        for(var change:changes.entrySet()) {
            var copied=copy(nf);change.getValue().accept(occurrence(copied,ownerId));
            controls.put(new JSONObject().put("control",change.getKey()).put("rejection",
                reject(()->LocalOwnerCorrespondence.check(nf,copied),change.getKey())));
        }
    }
    private static void traceControls(NativeRepairTrace trace,JSONArray controls) {
        for(String mode:List.of("cost","source","payload","premise","operation-order","construction")) {
            var bad=trace.json();
            if(mode.equals("construction")) {
                if(!bad.has("constructionDerivations"))continue;
                bad.getJSONObject("constructionDerivations").getJSONObject("source").put("projectionObservationDigest","foreign");
            } else {
                var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
                switch(mode) {
                    case "cost"->op.put("cost",99);
                    case "source"->op.put("source","foreign");
                    case "payload"->op.getJSONObject("before").put("nativeEvidence","foreign typed owner");
                    case "premise"->op.put("premise","foreign");
                    case "operation-order"->bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                }
            }
            controls.put(new JSONObject().put("control",mode).put("arm",trace.json().getString("arm"))
                .put("rejection",reject(()->trace.replay(bad),mode)).put("tampered",bad));
        }
        check(trace.replay(trace.json())==trace.distance(),"original native replay remains valid");
    }
    public static void main(String[] args)throws Exception {
        var id=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(id);var controls=new JSONArray();
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var fast=CanonicalRepairTraces.fast(a,b);
            var result=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),result.toString(),StandardOpenOption.CREATE_NEW);
            boolean ff=fast.distance()<=certified.distance();var order=ff?List.of(fast,certified):List.of(certified,fast);
            result.put("replayOrder",new JSONArray().put(ff?"fast":"certified").put(ff?"certified":"fast"));
            for(var trace:order)check(trace.replay(trace.json())==trace.distance(),"shorter-first native replay");
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);result.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),result.toString(),StandardOpenOption.CREATE_NEW);
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"selected source-linked paths: "+comparison.reason());
            traceControls(fast,controls);traceControls(certified,controls);
        }
        int owners=0;
        for(var endpoint:pair)for(var nf:endpoint.normalizedForms()) {
            var found=new ArrayList<EGraphNode>();owners(copy(nf),found);
            for(var owner:found){ownerControls(nf,owner.getSourceOccurrenceLineage(),controls);owners++;}
        }
        check(owners>0,"actual natural universal regrouping exercised");
        Files.writeString(out.resolve("controls.json"),controls.toString(2),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("result.json"),new JSONObject().put("status","PASS").put("checks",checks)
            .put("negatives",negatives).put("directions",2).put("owners",owners).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("UNIVERSAL_DECLARATION_PASS checks="+checks+" negatives="+negatives);
    }
}
