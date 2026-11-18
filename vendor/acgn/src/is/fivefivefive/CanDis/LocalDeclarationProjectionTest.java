package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Actual source-group retention and invalid-evidence controls. */
public final class LocalDeclarationProjectionTest {
    private static int checks, negatives;
    private static void check(boolean ok,String why) {
        checks++;if(!ok)throw new AssertionError(why);
    }
    private static String reject(Runnable run,String why) {
        checks++;negatives++;
        try {run.run();} catch(IllegalArgumentException|IllegalStateException ex) {return ex.toString();}
        throw new AssertionError("Accepted "+why);
    }
    private static void field(Object object,String name,Object value) {
        try {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static EGraphNode comprehension(EGraphNode root) {
        if(root.getOpcode()==EGraphNode.Opcode.COMPREHENSION)return root;
        for(var c:root.getChildren()){var result=comprehension(c);if(result!=null)return result;}
        return null;
    }
    private static EGraphNode group(EGraphNode root) {return comprehension(root).getChildren().get(0);}
    private static void regrouping(NormalForm source,JSONArray evidence) {
        var original=comprehension(source.getCertificationMatrixEGraph());
        if(original==null)return;
        var root=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0).getMatrixEGraph();
        var owner=comprehension(root);var merged=group(root);
        var groups=merged.comparisonDeclarationGroups(owner);
        if(groups.size()==1)return;
        var before=LocalOwnerCorrespondence.check(source,source.getCertificationMatrixEGraph()).json();
        var after=LocalOwnerCorrespondence.check(source,root).json();
        check(before.similar(after),"regrouping retains every original domain/group/ordered slot");
        var first=original.getChildren().get(0);
        boolean oldGate=first.getOpcode()==merged.getOpcode()
            &&first.getSourceOccurrenceLineage()==merged.getSourceOccurrenceLineage()
            &&first.getChildren().get(0).getSourceOccurrenceLineage()==merged.getChildren().get(0).getSourceOccurrenceLineage()
            &&first.getChildren().size()==merged.getChildren().size();
        check(!oldGate,"exact old tuple gate rejects this sound regrouped source");
        evidence.put(new JSONObject().put("control","old-reject-new-accept").put("oldTupleGate",oldGate)
            .put("original",before).put("regrouped",after));
        var foreignRoot=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0).getMatrixEGraph();
        var foreignOwner=comprehension(foreignRoot);var foreign=group(foreignRoot);
        evidence.put(new JSONObject().put("control","foreign-owner-object").put("rejection",
            reject(()->merged.comparisonDeclarationGroups(foreignOwner),"foreign owner with identical metadata")));
        var slot=merged.getChildren().get(1);var foreignSlot=foreign.getChildren().get(1);
        check(slot!=foreignSlot&&slot.getSourceOccurrenceLineage()==foreignSlot.getSourceOccurrenceLineage()
            &&slot.getExactAlloyType().sameOccurrenceEvidenceAs(foreignSlot.getExactAlloyType())
            &&slot.getAlphaName().equals(foreignSlot.getAlphaName()),"same metadata, distinct actual objects");
        var children=new ArrayList<>(merged.getChildClasses());children.set(1,foreign.getChildClasses().get(1));
        field(merged,"childClasses",children);
        evidence.put(new JSONObject().put("control","same-metadata-foreign-slot-object").put("rejection",
            reject(()->LocalOwnerCorrespondence.check(source,root),"foreign slot object")));
        var noReceiptRoot=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0).getMatrixEGraph();
        field(group(noReceiptRoot),"comparisonDeclarationGrouping",null);
        evidence.put(new JSONObject().put("control","missing-grouping-receipt").put("rejection",
            reject(()->LocalOwnerCorrespondence.check(source,noReceiptRoot),"missing grouping receipt")));
        var wrongDomainRoot=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0).getMatrixEGraph();
        var domainLeaf=group(wrongDomainRoot).getChildren().get(0).getChildren().get(0);
        check(domainLeaf.getSemanticIdentity()!=null,"domain has semantic authority separate from display name");
        String originalIdentity=domainLeaf.getSemanticIdentity();
        field(domainLeaf,"semanticIdentity","ForeignState");
        evidence.put(new JSONObject().put("control","same-occurrence-wrong-domain").put("rejection",
            reject(()->LocalOwnerCorrespondence.check(source,wrongDomainRoot),"wrong domain"))
            .put("originalSemanticIdentity",originalIdentity).put("tamperedSemanticIdentity",domainLeaf.getSemanticIdentity()));
    }
    private static void corrupt(NormalForm source,JSONArray evidence) {
        var changes=new LinkedHashMap<String,Consumer<EGraphNode>>();
        changes.put("owner",r->field(comprehension(r),"sourceOccurrenceLineage",-91L));
        changes.put("group",r->field(group(r),"sourceOccurrenceLineage",-92L));
        changes.put("domain",r->field(group(r).getChildren().get(0),"sourceOccurrenceLineage",-93L));
        changes.put("slot-occurrence",r->field(group(r).getChildren().get(1),"sourceOccurrenceLineage",-94L));
        changes.put("slot-type",r->field(group(r).getChildren().get(1),"exactAlloyType",ExactAlloyType.unaryRelation("Foreign")));
        changes.put("slot-coordinate",r->field(group(r).getChildren().get(1),"alphaName","@fast-local:99:0"));
        changes.put("tuple-order",r->{var g=group(r);var cs=new ArrayList<>(g.getChildClasses());Collections.swap(cs,1,2);field(g,"childClasses",cs);});
        changes.put("missing-slot",r->{var g=group(r);var cs=new ArrayList<>(g.getChildClasses());cs.remove(2);field(g,"childClasses",cs);});
        changes.put("duplicate-slot",r->{var g=group(r);var cs=new ArrayList<>(g.getChildClasses());cs.set(2,cs.get(1));field(g,"childClasses",cs);});
        changes.put("disjoint-substitution",r->field(group(r),"opcode",EGraphNode.Opcode.DISJ));
        changes.put("body-before-declaration",r->{var o=comprehension(r);var cs=new ArrayList<>(o.getChildClasses());Collections.swap(cs,0,1);field(o,"childClasses",cs);});
        for(var entry:changes.entrySet()) {
            var root=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0).getMatrixEGraph();
            entry.getValue().accept(root);
            evidence.put(new JSONObject().put("control",entry.getKey()).put("rejection",reject(()->LocalOwnerCorrespondence.check(source,root),entry.getKey())));
        }
    }
    private static void corruptTrace(NativeRepairTrace trace,JSONArray evidence) {
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
            evidence.put(new JSONObject().put("arm",trace.json().getString("arm")).put("control",mode)
                .put("rejection",reject(()->trace.replay(bad),mode)).put("tampered",bad));
        }
        check(trace.replay(trace.json())==trace.distance(),"immutable native authority survives tampering");
    }
    public static void main(String[] args)throws Exception {
        var identity=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(identity);var controls=new JSONArray();
        var original=pair.get(1).normalizedForms().get(0);
        var copied=CanonicalDistance.prepare(List.of(original)).comparisonForms().get(0).getMatrixEGraph();
        var before=LocalOwnerCorrespondence.check(original,original.getCertificationMatrixEGraph()).json();
        var after=LocalOwnerCorrespondence.check(original,copied).json();
        check(before.similar(after),"actual owner/domain/type/declaration/ordered tuple occurrence correspondence");
        var oldGroup=group(original.getCertificationMatrixEGraph());var newGroup=group(copied);
        check(oldGroup.getChildren().size()==3&&newGroup.getChildren().size()==3,"actual two-column group remains one group");
        check(oldGroup.getSourceOccurrenceLineage()==newGroup.getSourceOccurrenceLineage(),"original group occurrence");
        Files.writeString(out.resolve("source-owner-link.json"),new JSONObject().put("identity",identity).put("before",before).put("after",after).toString(2),StandardOpenOption.CREATE_NEW);
        corrupt(original,controls);
        for(var endpoint:pair)for(var form:endpoint.normalizedForms())regrouping(form,controls);
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var fast=CanonicalRepairTraces.fast(a,b);
            var evidence=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
            boolean fastFirst=fast.distance()<=certified.distance();
            evidence.put("replayOrder",new JSONArray().put(fastFirst?"fast":"certified").put(fastFirst?"certified":"fast"));
            var completed=new JSONArray();evidence.put("replayCompleted",completed);
            for(var trace:fastFirst?List.of(fast,certified):List.of(certified,fast)) {
                String arm=trace==fast?"fast":"certified";evidence.put("replayStarted",arm);
                check(trace.replay(trace.json())==trace.distance(),"shorter-first native replay "+arm);completed.put(arm);
            }
            var result=NativeOperationComparison.compare(a,b,fast,certified);evidence.put("comparison",result.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"actual selected operations and premises in direction "+direction+": "+result.reason());
            corruptTrace(fast,controls);corruptTrace(certified,controls);
        }
        Files.writeString(out.resolve("controls.json"),controls.toString(2),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("result.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives)
            .put("directions",2).put("status","PASS").put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("LOCAL_DECLARATION_PROJECTION_PASS checks="+checks+" negatives="+negatives);
    }
}
