package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Paid outer links beneath source-linked, ordered retained comprehensions. */
public final class RetainedComprehensionPaidTest {
    private static int checks, negatives;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static void field(Object object,String name,Object value) {
        try {var f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean supports(List<NormalForm> forms) {
        try {
            var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod("supports",List.class);
            m.setAccessible(true);return (boolean)m.invoke(null,forms);
        } catch(java.lang.reflect.InvocationTargetException e) {
            if(e.getCause() instanceof RuntimeException r)throw r;
            throw new AssertionError(e.getCause());
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static EGraphNode owner(EGraphNode root) {
        if(root.getOpcode()==EGraphNode.Opcode.COMPREHENSION)return root;
        for(var child:root.getChildren()){var found=owner(child);if(found!=null)return found;}
        return null;
    }
    private static EGraphNode group(NormalForm form) {return owner(form.getMatrixEGraph()).getChildren().get(0);}
    private static String rejected(Runnable action,String why) {
        checks++;negatives++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException e){return e.toString();}
        throw new AssertionError("Accepted "+why);
    }
    private static void corrupt(NormalForm source,JSONArray controls) {
        var changes=new LinkedHashMap<String,Consumer<NormalForm>>();
        changes.put("owner-occurrence",f->field(owner(f.getMatrixEGraph()),"sourceOccurrenceLineage",-1L));
        changes.put("group-occurrence",f->field(group(f),"sourceOccurrenceLineage",-2L));
        changes.put("domain-occurrence",f->field(group(f).getChildren().get(0),"sourceOccurrenceLineage",-3L));
        changes.put("slot-occurrence",f->field(group(f).getChildren().get(1),"sourceOccurrenceLineage",-4L));
        changes.put("slot-type",f->field(group(f).getChildren().get(1),"exactAlloyType",null));
        changes.put("outer-capture",f->field(group(f).getChildren().get(1),"alphaName","_q0"));
        changes.put("missing-captured-groups",f->field(group(f),"comparisonDeclarationGrouping",null));
        changes.put("ordered-column-swap",f->{var g=group(f);var cs=new ArrayList<>(g.getChildClasses());Collections.swap(cs,1,2);field(g,"childClasses",cs);});
        changes.put("foreign-domain",f->field(group(f).getChildren().get(0).getChildren().get(0),"semanticIdentity","foreign"));
        for(var change:changes.entrySet()) {
            var forms=CanonicalDistance.prepare(List.of(source)).comparisonForms();change.getValue().accept(forms.get(0));
            String outcome;
            try {check(!supports(forms),"invalid scope "+change.getKey());negatives++;outcome="false";}
            catch(IllegalArgumentException|IllegalStateException e){negatives++;outcome=e.toString();}
            controls.put(new JSONObject().put("control",change.getKey()).put("rejection",outcome));
        }
        var forms=CanonicalDistance.prepare(List.of(source)).comparisonForms();
        var foreign=CanonicalDistance.prepare(List.of(source)).comparisonForms();
        var g=group(forms.get(0));var other=group(foreign.get(0));
        var a=g.getChildren().get(1);var b=other.getChildren().get(1);
        check(a!=b && a.getSourceOccurrenceLineage()==b.getSourceOccurrenceLineage()
            && a.getAlphaName().equals(b.getAlphaName())
            && a.getExactAlloyType().sameOccurrenceEvidenceAs(b.getExactAlloyType()),"foreign object has identical serialized coordinates");
        controls.put(new JSONObject().put("control","same-metadata-foreign-owner").put("rejection",
            rejected(()->g.comparisonDeclarationGroups(owner(foreign.get(0).getMatrixEGraph())),"foreign owner object")));
        var cs=new ArrayList<>(g.getChildClasses());cs.set(1,other.getChildClasses().get(1));field(g,"childClasses",cs);
        controls.put(new JSONObject().put("control","same-metadata-foreign-slot").put("rejection",
            rejected(()->supports(forms),"foreign slot object")));
        check(supports(CanonicalDistance.prepare(List.of(source)).comparisonForms()),"original remains admissible after corrupted views");
    }
    private static void corruptTrace(NativeRepairTrace trace,JSONArray controls) {
        for(String mode:List.of("cost","source","type","premise","owner","payment","construction")) {
            var bad=trace.json();
            var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(4);
            switch(mode) {
                case "cost" -> op.put("cost",op.getInt("cost")+1);
                case "source" -> op.put("source","foreign");
                case "type" -> op.getJSONObject("before").put("head","foreign");
                case "premise" -> op.put("premise","foreign");
                case "owner" -> op.getJSONObject("before").put("nativeEvidence","foreign owner with same carrier");
                case "payment" -> {
                    if(bad.getString("arm").equals("fast"))
                        bad.getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q99");
                    else bad.getJSONArray("phases").getJSONObject(0).getJSONArray("alphaMapping").put(0,99);
                }
                case "construction" -> {
                    if(!bad.has("constructionDerivations"))continue;
                    bad.getJSONObject("constructionDerivations").getJSONObject("source").put("projectionObservationDigest","foreign");
                }
            }
            controls.put(new JSONObject().put("arm",trace.json().getString("arm")).put("control",mode)
                .put("rejection",rejected(()->trace.replay(bad),mode)).put("tampered",bad));
        }
    }
    public static void main(String[] args)throws Exception {
        var id=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(id);var controls=new JSONArray();
        var source=pair.get(0).normalizedForms().get(0);
        var view=CanonicalDistance.prepare(List.of(source)).comparisonForms().get(0);
        check(!source.getLocalQuantiVars().isEmpty() && source.getMatrixQuantiVars().stream().anyMatch(q->q.getDisjointnessClass()>0),"exact old disjoint/local conjunct rejected actual source");
        check(source.getLocalQuantiVars().stream().anyMatch(q->q.getQuantifier()==QuantiVar.Quantifier.COMPREHENSION),"actual old localKind omission");
        check(supports(List.of(source))&&supports(List.of(view)),"original and captured grouped view admitted by retained checks");
        check(group(view).comparisonDeclarationGroups(owner(view.getMatrixEGraph())).size()==2,"both original ordered declaration groups retained");
        var before=LocalOwnerCorrespondence.check(source,source.getCertificationMatrixEGraph()).json();
        var after=LocalOwnerCorrespondence.check(source,view.getMatrixEGraph()).json();
        check(before.similar(after),"exact owner/dependency/ordered slot correspondence");
        Files.writeString(out.resolve("owner-link.json"),new JSONObject().put("identity",id).put("before",before).put("after",after).toString(2),StandardOpenOption.CREATE_NEW);
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);
            var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("identity",id).put("direction",direction).put("fast",fast.json()).put("certified",cert.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),row);
            var result=NativeOperationComparison.compare(a,b,fast,cert);row.put("comparison",result.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),row.toString(),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==23&&cert.distance()==23,"actual paid path has declaration2 plus matrix21");
            check(result.status()==NativeOperationComparison.Status.MATCHED,"all selected operations/premises "+direction+": "+result.reason());
            check(fast.json().getJSONObject("selectedPaidDeclarationCorrespondence").getString("_q0").equals("_q0"),"actual charged declaration licenses selected read");
            corruptTrace(fast,controls);corruptTrace(cert,controls);
        }
        corrupt(source,controls);
        Files.writeString(out.resolve("controls.json"),controls.toString(2),StandardOpenOption.CREATE_NEW);
        Files.writeString(out.resolve("result.json"),new JSONObject().put("status","PASS").put("checks",checks).put("negativeControls",negatives)
            .put("directions",2).put("synthetic",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("RetainedComprehensionPaidTest: "+checks+" checks, "+negatives+" negatives");
    }
}
