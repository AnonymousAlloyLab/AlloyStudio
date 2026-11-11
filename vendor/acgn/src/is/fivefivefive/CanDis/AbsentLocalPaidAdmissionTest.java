package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.*;

/** Source-hashed dead-local metadata admission, selected-path and retention controls. */
public final class AbsentLocalPaidAdmissionTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static boolean absent(NormalForm form)throws Exception {
        var method=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment")
                .getDeclaredMethod("localsAbsentFromSnapshots",NormalForm.class);
        method.setAccessible(true);return (boolean)method.invoke(null,form);
    }
    private static List<EGraphNode> variables(EGraphNode root) {
        var out=new ArrayList<EGraphNode>();var todo=new ArrayDeque<EGraphNode>();todo.add(root);
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        while(!todo.isEmpty()){var n=todo.remove();if(!seen.add(n))continue;if(n.getOpcode()==EGraphNode.Opcode.VARIABLE)out.add(n);todo.addAll(n.getChildren());}return out;
    }
    private static void reject(Runnable action,String mode) {
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejections++;return;}
        throw new AssertionError("Accepted "+mode);
    }
    private static JSONArray censusControls(List<Canonical.Prepared> pair,Canonical.Prepared prepared,
            int endpoint,int direction,Set<String> coverage)throws Exception {
        check(prepared==pair.get(endpoint),"actual indexed prepared endpoint, not a repeated first endpoint");
        var out=new JSONArray();var form=CanonicalDistance.prepare(prepared.normalizedForms()).comparisonForms().get(0);
        check(absent(form),"complete dual snapshot census required");
        for(int side=0;side<2;side++)for(String mode:List.of("alphaName","sourceName","semanticIdentity","sourceOccurrenceLineage","exactAlloyType","opcode")) {
            var node=variables(side==0?form.getCertificationMatrixEGraph():form.getMatrixEGraph()).get(0);
            var field=EGraphNode.class.getDeclaredField(mode);field.setAccessible(true);var original=field.get(node);
            Object corrupt=switch(mode){
                case "alphaName" -> form.getLocalQuantiVars().isEmpty()?"@foreign-local":form.getLocalQuantiVars().get(0).getName();
                case "sourceOccurrenceLineage" -> -99L;
                case "exactAlloyType" -> ExactAlloyType.unaryRelation("Person");
                case "opcode" -> EGraphNode.Opcode.GENERICRELDECL;
                default -> "foreign-owner/source";
            };
            try{field.set(node,corrupt);check(!absent(form),"rejected census "+side+" "+mode);rejections++;}
            finally{field.set(node,original);}
            check(absent(form),"restored actual census");out.put(new JSONObject().put("snapshot",side).put("mode",mode).put("rejected",true)
                    .put("preparedEndpoint",endpoint).put("direction",direction));
        }
        check(coverage.add(direction+":"+endpoint),"unique completed direction/endpoint census control");
        return out;
    }
    public static void main(String[] args)throws Exception {
        var rows=new JSONArray(Files.readString(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object value:rows) {
            var row=(JSONObject)value;var id=row.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);
            var endpoints=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            boolean newlyAdmitted=row.optBoolean("newAdmission");
            var censusCoverage=new HashSet<String>();
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);
                var c=CanonicalRepairTraces.certified(endpoints.get(d),endpoints.get(1-d));check(c.replay(c.json())==c.distance(),"certified selected path first");
                var f=CanonicalRepairTraces.fast(a,b);check(f.replay(f.json())==f.distance(),"Fast same-selection execution");
                var evidence=new JSONObject().put("identity",id).put("direction",d).put("fast",f.json()).put("certified",c.json());
                String name=id.getString("id").replace('/','_')+"-"+d+".json";
                // Retain actual programs even if the later production bridge fails.
                Files.writeString(out.resolve(name+".native"),evidence.toString(),StandardOpenOption.CREATE_NEW);
                var result=NativeOperationComparison.compare(a,b,f,c);evidence.put("comparison",result.evidence());
                check(result.status()==NativeOperationComparison.Status.MATCHED,"production correspondence "+name+": "+result.reason());
                check(f.distance()==row.getInt("expected")&&c.distance()==row.getInt("expected"),"expected selected components total "+name);
                var controls=new JSONArray();
                if(newlyAdmitted) {
                    var complete=LocalOwnerTraceTest.crud(f.json(),result.evidence(),true);
                    check(complete.similar(LocalOwnerTraceTest.crud(c.json(),result.evidence(),false)),"entire mapped matrix CRUD sequence");
                    check(result.evidence().getJSONArray("fastActions").similar(result.evidence().getJSONArray("certifiedActions")),"paid and zero action ordering");
                    evidence.put("mappedMatrixOperations",complete);
                    for(int endpoint=0;endpoint<2;endpoint++)
                        for(Object control:censusControls(pair,pair.get(endpoint),endpoint,d,censusCoverage))controls.put(control);
                    var metadata=pair.get(0).normalizedForms().get(0).getLocalQuantiVars();
                    check(!metadata.isEmpty(),"metadata retained after execution");
                    if(f.distance()!=0)check(f.json().has("selectedPaidDeclarationCorrespondence"),"actual paid selected map retained");
                    for(var trace:List.of(f,c))for(String mode:List.of("cost","address","payload","premise","order","construction","declaration")) {
                        var bad=trace.json();var op=bad.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0);
                        switch(mode) {
                            case "cost" -> op.put("cost",99);
                            case "address" -> op.put("source","foreign");
                            case "payload" -> op.getJSONObject("before").put("nativeEvidence","foreign owner/type/source");
                            case "premise" -> op.put("premise","foreign");
                            case "order" -> bad.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                            case "construction" -> bad.put("constructionLink","foreign");
                            case "declaration" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).put("selectedCorrespondence",new JSONArray().put("foreign"));
                        }
                        reject(()->trace.replay(bad),mode);controls.put(new JSONObject().put("arm",trace==f?"fast":"certified").put("mode",mode).put("rejected",true));
                    }
                    check(f.replay(f.json())==f.distance()&&c.replay(c.json())==c.distance(),"immutable paths after corruption");
                }
                if(row.has("priorDirectory")) {
                    var old=new JSONObject(Files.readString(Path.of(row.getString("priorDirectory"),name)));
                    var before=old.has("diagnostic")?old.getJSONObject("diagnostic"):old;
                    var transports=new JSONObject();
                    for(String arm:List.of("fast","certified")) {
                        var original=new JSONObject(before.getJSONObject(arm).toString());original.remove("diagnosticStatus");
                        transports.put(arm,LocalOwnerTraceTest.sameExecution(original,arm.equals("fast")?f.json():c.json()));checks++;
                    }
                    evidence.put("priorNativePathRetention",transports);
                }
                evidence.put("controls",controls);Files.writeString(out.resolve(name),evidence.toString(),StandardOpenOption.CREATE_NEW);
                results.put(new JSONObject().put("identity",id).put("direction",d).put("status",result.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name).put("controls",controls.length()));
            }
            if(newlyAdmitted)check(censusCoverage.equals(Set.of("0:0","0:1","1:0","1:1")),
                    "both prepared endpoints exercised in both directions");
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("rejections",rejections).put("rows",results).put("queueCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("AbsentLocalPaidAdmissionTest: "+checks+" checks, "+rejections+" rejections, "+results.length()+" directions");
    }
}
