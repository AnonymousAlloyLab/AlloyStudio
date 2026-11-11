package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;

/** Ordered retained declaration groups: paid outer path, not local exchange. */
public final class OrderedLocalPaidAdmissionTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static boolean admitted(NormalForm f)throws Exception {
        var m=Class.forName("is.fivefivefive.CanDis.core.FastLocalPaidScope").getDeclaredMethod("checkedOwnerCount",NormalForm.class);
        m.setAccessible(true);return (int)m.invoke(null,f)>0;
    }
    private static EGraphNode owner(EGraphNode n) {
        if(n.getChildren().stream().filter(c->c.getOpcode()==EGraphNode.Opcode.GENERICRELDECL).count()>1)return n;
        for(var c:n.getChildren()){var found=owner(c);if(found!=null)return found;}return null;
    }
    private static void reject(Runnable action,String why) {
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejections++;return;}
        throw new AssertionError("Accepted tampered evidence: "+why);
    }
    private static JSONObject samePath(JSONObject original,JSONObject current) {
        var before=new JSONObject(original.toString());var after=new JSONObject(current.toString());
        boolean emptyPaidEvidence=!before.has("selectedPaidDeclarationCorrespondence")
                &&after.has("selectedPaidDeclarationCorrespondence");
        if(emptyPaidEvidence) {
            check(before.getInt("quantifierDistance")==0&&after.getInt("quantifierDistance")==0,
                    "empty paid metadata may only extend a zero-charge declaration path");
            check(after.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"no newly selected paid link hidden");
            check(!before.getJSONObject("declarationSlice").getBoolean("selectedPaidCorrespondenceChecked")
                    &&after.getJSONObject("declarationSlice").getBoolean("selectedPaidCorrespondenceChecked"),"exact empty-map premise-check transition");
            // Only this redundant proof metadata is new. The comparison below
            // still requires every declaration/matrix/ordered operation unchanged.
            after.remove("selectedPaidDeclarationCorrespondence");
            after.getJSONObject("declarationSlice").put("selectedPaidCorrespondenceChecked",false);
        }
        return LocalOwnerTraceTest.sameExecution(before,after).put("emptyPaidMetadataExtensionChecked",emptyPaidEvidence);
    }
    private static JSONArray scopeControls(Canonical.Prepared endpoint,int endpointIndex,int direction)throws Exception {
        var form=CanonicalDistance.prepare(endpoint.normalizedForms()).comparisonForms().get(0);
        var n=owner(form.getMatrixEGraph());var result=new JSONArray();
        if(n==null)return result;
        check(admitted(form),"original ordered owner admitted");
        for(String mode:List.of("owner","kind","declaration","domain","domainIdentity","variable","type","capture","groupOrder")) {
            Object object=n;String field="sourceOccurrenceLineage";Object corrupt=-99L;
            var declaration=n.getChildren().get(1);var domain=declaration.getChildren().get(0);var variable=declaration.getChildren().get(1);
            switch(mode) {
                case "kind" -> {field="opcode";corrupt=EGraphNode.Opcode.ONE;}
                case "declaration" -> object=declaration;
                case "domain" -> object=domain;
                case "domainIdentity" -> {object=domain.getChildren().get(0);field="semanticIdentity";corrupt="foreign-domain";}
                case "variable" -> object=variable;
                case "type" -> {object=variable;field="exactAlloyType";corrupt=ExactAlloyType.unaryRelation("Person");}
                case "capture" -> {object=variable;field="alphaName";corrupt=form.getMatrixQuantiVars().get(0).getName();}
                case "groupOrder" -> {
                    field="childClasses";var childField=EGraphNode.class.getDeclaredField(field);childField.setAccessible(true);
                    var children=new ArrayList<>((List<?>)childField.get(n));Collections.swap(children,0,1);corrupt=children;
                }
            }
            var f=object.getClass().getDeclaredField(field);f.setAccessible(true);var original=f.get(object);
            try{f.set(object,corrupt);check(!admitted(form),"reject ordered scope "+mode);rejections++;}
            finally{f.set(object,original);}
            check(admitted(form),"restored scope "+mode);
            result.put(new JSONObject().put("mode",mode).put("endpoint",endpointIndex).put("direction",direction).put("rejected",true));
        }
        return result;
    }
    public static void main(String[] args)throws Exception {
        var rows=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object item:rows) {
            var row=(JSONObject)item;var id=row.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);
            var certified=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            var covered=new HashSet<String>();
            for(int d=0;d<2;d++) {
                var c=CanonicalRepairTraces.certified(certified.get(d),certified.get(1-d));
                check(c.replay(c.json())==c.distance(),"certified selected path first");
                var f=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));check(f.replay(f.json())==f.distance(),"Fast native replay");
                var evidence=new JSONObject().put("identity",id).put("direction",d).put("fast",f.json()).put("certified",c.json());
                String name=id.getString("id").replace('/','_')+"-"+d+".json";
                Files.writeString(out.resolve(name+".native"),evidence.toString(),StandardOpenOption.CREATE_NEW);
                var comparison=NativeOperationComparison.compare(pair.get(d),pair.get(1-d),f,c);
                evidence.put("comparison",comparison.evidence());
                Files.writeString(out.resolve(name),evidence.toString(),StandardOpenOption.CREATE_NEW);
                check(comparison.status()==NativeOperationComparison.Status.MATCHED,"production correspondence "+name+": "+comparison.reason());
                if(row.has("expected"))check(f.distance()==row.getInt("expected")&&c.distance()==row.getInt("expected"),"retained expected distance "+name);
                var controls=new JSONArray();
                if(row.optBoolean("orderedLocal")) {
                    check(f.json().has("selectedPaidDeclarationCorrespondence"),"actual paid path retained");
                    check(LocalOwnerTraceTest.crud(f.json(),comparison.evidence(),true).similar(
                            LocalOwnerTraceTest.crud(c.json(),comparison.evidence(),false)),"entire mapped matrix operation sequence");
                    for(int endpoint=0;endpoint<2;endpoint++) {
                        var scoped=scopeControls(pair.get(endpoint),endpoint,d);
                        if(!scoped.isEmpty())covered.add(d+":"+endpoint);
                        for(Object control:scoped)controls.put(control);
                    }
                    for(var trace:List.of(f,c))for(String mode:List.of("cost","source","premise","construction","declaration","order")) {
                        var bad=trace.json();var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
                        switch(mode) {
                            case "cost" -> op.put("cost",99);
                            case "source" -> op.put("source","foreign-owner");
                            case "premise" -> op.put("premise","foreign-premise");
                            case "construction" -> bad.put("constructionLink","foreign-construction");
                            case "declaration" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).put("selectedCorrespondence",new JSONArray().put("foreign"));
                            case "order" -> bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                        }
                        reject(()->trace.replay(bad),mode);controls.put(new JSONObject().put("arm",trace==f?"fast":"certified").put("mode",mode).put("rejected",true));
                    }
                    check(f.replay(f.json())==f.distance()&&c.replay(c.json())==c.distance(),"original paths remain valid after tampering");
                }
                if(row.has("priorDirectory")) {
                    var old=new JSONObject(Files.readString(Path.of(row.getString("priorDirectory"),name)));
                    var before=old.has("diagnostic")?old.getJSONObject("diagnostic"):old;
                    var retained=new JSONObject();
                    for(String arm:row.optBoolean("newAdmission")?List.of("certified"):List.of("fast","certified")) {
                        var original=new JSONObject(before.getJSONObject(arm).toString());original.remove("diagnosticStatus");
                        retained.put(arm,samePath(original,arm.equals("fast")?f.json():c.json()));checks++;
                    }
                    evidence.put("priorNativePathRetention",retained);
                }
                evidence.put("controls",controls);Files.writeString(out.resolve(name),evidence.toString());
                results.put(new JSONObject().put("identity",id).put("direction",d).put("status",comparison.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name).put("controls",controls.length()));
            }
            if(row.optBoolean("orderedLocal")) {
                var expected=new HashSet<String>();
                for(int e=0;e<2;e++)if(owner(CanonicalDistance.prepare(pair.get(e).normalizedForms()).comparisonForms().get(0).getMatrixEGraph())!=null)
                    for(int d=0;d<2;d++)expected.add(d+":"+e);
                check(!expected.isEmpty()&&covered.equals(expected),"each actual ordered endpoint in both directions");
            }
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("tamperedEvidenceRejections",rejections).put("rows",results).put("queueCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("OrderedLocalPaidAdmissionTest: "+checks+" checks, "+rejections+" tampered-evidence rejections, "+results.length()+" directions");
    }
}
