package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Finite ordered-block correspondence; retains every native step, including
 * identity coordinate reads and selected identity-orbit bookkeeping. */
public final class OrderedPhaseCorrespondenceTest {
    private static int checks;
    private static void require(boolean ok,String why){checks++;if(!ok)throw new IllegalArgumentException(why);}
    private static void reject(Runnable test,String why){checks++;try{test.run();}catch(IllegalArgumentException|IllegalStateException|JSONException expected){return;}throw new AssertionError("Accepted "+why);}
    private static JSONObject at(JSONObject trace,String path) {
        Object value=trace;for(String key:path.substring(1).split("/"))
            value=value instanceof JSONObject object?object.get(key):((JSONArray)value).get(Integer.parseInt(key));
        return (JSONObject)value;
    }
    private static JSONArray blocks(NativeRepairTrace trace,boolean certified,Canonical.Prepared a,Canonical.Prepared b,
            JSONObject payment,JSONArray matrix,JSONArray declarations) {
        JSONObject json=trace.json();
        require(trace.replay(json)==trace.distance(),"Independent original selected execution");
        JSONArray result=new JSONArray();JSONObject block=null;
        int matrixIndex=0,declarationIndex=0,total=0;
        var seen=new HashSet<String>();
        for(int i=0;i<json.getJSONArray("orderedNativeOperations").length();i++) {
            var row=json.getJSONArray("orderedNativeOperations").getJSONObject(i);
            String component=row.getString("component"),path=row.getString("payloadPath");
            require(seen.add(path),"Ordered payload consumed exactly once");
            var payload=at(json,path);require(payload.getInt("cost")==row.getInt("cost"),"Ordered charge/payload link");
            total=Math.addExact(total,row.getInt("cost"));
            if(block==null||!block.getString("component").equals(component)) {
                block=new JSONObject().put("component",component).put("entries",new JSONArray())
                        .put("effect",component.equals("declaration-representation")
                                ?new JSONObject().put("source",new JSONArray()).put("target",new JSONArray()):new JSONArray());
                result.put(block);
            }
            var entry=new JSONObject().put("orderedIndex",i).put("row",row).put("payload",payload);
            block.getJSONArray("entries").put(entry);
            if(component.equals("declaration-representation")) {
                require(payload.getInt("cost")==0,"Declaration representation is zero cost");
                var state=block.getJSONObject("effect");entry.put("before",new JSONObject(state.toString()));
                if(payload.getString("kind").equals("metric-coordinate-order")) {
                    String side=payload.getString("side");int index=payload.getInt("component");
                    var atoms=json.getJSONArray("nativeDeclarationPrograms").getJSONObject(index).getJSONArray(side);
                    var occurrences=payload.getJSONArray("originalOccurrencesInSelectedOrder");
                    require(atoms.length()==occurrences.length(),"Selected atom/address coverage");
                    for(int j=0;j<atoms.length();j++) {
                        var atom=atoms.getJSONObject(j);require(atom.getString("occurrence").equals(occurrences.getString(j)),"Selected original atom order");
                        state.getJSONArray(side).put(ImportedAciPatternTest.declaration(atom,certified,side.equals("source")?a:b,payment,side));
                    }
                    entry.put("effectPremise",atoms.isEmpty()?"Checked empty coordinate read: identity":"Checked original typed atoms in selected order");
                } else {
                    require(payload.getString("kind").equals("selected-certified-orbit"),"Unpaired non-coordinate representation step");
                    require(payload.getJSONObject("selection").similar(json.getJSONObject("selectedDeclarationPermutation")),"Actual selected orbit link");
                    for(String side:List.of("left","right")) {
                        var permutation=payload.getJSONObject("selection").getJSONArray(side);
                        for(int j=0;j<permutation.length();j++)require(permutation.getInt(j)==j,"Finite witness has identity orbit; nonidentity needs explicit effect correspondence");
                    }
                    entry.put("effectPremise","Checked selected identity orbit; original native replay validates selection/admission");
                }
                entry.put("after",new JSONObject(state.toString()));
                entry.put("identityEffect",entry.getJSONObject("before").similar(entry.getJSONObject("after")));
            } else {
                Object effect;
                switch(component) {
                    case "matrix" -> {require(matrixIndex<matrix.length(),"Matrix operation coverage");effect=matrix.getJSONObject(matrixIndex++);}
                    case "declaration" -> {require(declarationIndex<declarations.length(),"Declaration operation coverage");effect=declarations.getJSONObject(declarationIndex++);}
                    case "temporal", "temporal-read" -> effect=payload;
                    case "phase-representation" -> {
                        require(payload.getInt("cost")==0,"Phase representation zero cost");
                        String side=payload.getString("side");var original=(side.equals("source")?a:b).normalizedForms();
                        var rows=payload.getJSONArray("phases");var used=new HashSet<Integer>();
                        for(int p=0;p<rows.length();p++) {
                            var coordinate=rows.getJSONObject(p);int old=coordinate.getInt("originalPhase");
                            require(old>=0&&old<original.size()&&used.add(old)&&coordinate.getInt("comparisonPhase")==p,"Phase order original injection");
                            var form=original.get(old);
                            require(form.getPhasePath().equals(coordinate.getString("sourcePath"))&&form.getTemporalOp().name().equals(coordinate.getString("typedRole")),"Original phase occurrence/type");
                        }
                        if(certified) {
                            var construction=json.getJSONObject("constructionDerivations").getJSONObject(side);
                            require(construction.getBoolean("premisesCheckedInProcess")
                                    &&construction.getJSONObject("projectionPhaseRepresentation").similar(payload)
                                    &&construction.getString("projectionObservationDigest").equals(json.getString(side+"ProducerObservationDigest")),"Actual source construction/projection/operation link");
                            entry.put("constructionLink","/constructionDerivations/"+side+"/projectionPhaseRepresentation");
                        }
                        effect=payload;
                    }
                    default -> throw new IllegalArgumentException("Unpaired actual ordered operation "+i+" "+component);
                }
                block.getJSONArray("effect").put(effect);
            }
        }
        require(matrixIndex==matrix.length()&&declarationIndex==declarations.length(),"All component operations consumed in native order");
        require(total==trace.distance(),"Full ordered charge sum");
        return result;
    }
    static JSONObject compare(NativeRepairTrace fast,NativeRepairTrace certified,Canonical.Prepared a,Canonical.Prepared b,
            JSONObject payments,JSONArray fastMatrix,JSONArray certifiedMatrix,JSONArray fastDeclarations,JSONArray certifiedDeclarations) {
        var x=blocks(fast,false,a,b,payments,fastMatrix,fastDeclarations);
        var y=blocks(certified,true,a,b,payments,certifiedMatrix,certifiedDeclarations);
        require(x.length()==y.length(),"Ordered block count "+x.length()+"/"+y.length());
        int fastCount=0,certifiedCount=0;
        for(int i=0;i<x.length();i++) {
            var l=x.getJSONObject(i);var r=y.getJSONObject(i);
            require(l.getString("component").equals(r.getString("component")),"First differing ordered block "+i);
            Object le=l.get("effect"),re=r.get("effect");
            require(le instanceof JSONObject?((JSONObject)le).similar(re):((JSONArray)le).similar(re),"First differing block effect "+i+" "+l.getString("component"));
            fastCount+=l.getJSONArray("entries").length();certifiedCount+=r.getJSONArray("entries").length();
        }
        for(var trace:List.of(fast,certified))for(String mode:List.of("missing","duplicate","cost","order")) {
            var bad=trace.json();var ops=bad.getJSONArray("orderedNativeOperations");
            switch(mode) {
                case "missing" -> ops.remove(0);
                case "duplicate" -> ops.put(ops.getJSONObject(0));
                case "cost" -> ops.getJSONObject(0).put("cost",1);
                case "order" -> {Object first=ops.get(0);ops.put(0,ops.get(1));ops.put(1,first);}
            }
            reject(()->trace.replay(bad),"entire selected order "+mode);
        }
        if(certified.json().has("nativePhaseRepresentationSteps"))for(String field:List.of("originalPhase","typedRole","sourcePath","constructionLink")) {
            var bad=certified.json();var step=bad.getJSONArray("nativePhaseRepresentationSteps").getJSONObject(0);
            if(field.equals("constructionLink"))bad.getJSONObject("constructionDerivations").getJSONObject(step.getString("side")).remove("projectionPhaseRepresentation");
            else step.getJSONArray("phases").getJSONObject(0).put(field,field.equals("originalPhase")?999:"foreign");
            reject(()->certified.replay(bad),"certified representation premise "+field);
        }
        return new JSONObject().put("fast",x).put("certified",y).put("fastOperationsConsumed",fastCount).put("certifiedOperationsConsumed",certifiedCount)
                .put("allNativeEntriesRetained",true).put("orderedBlockEffectsEqual",true)
                .put("literalOneToOneOperationLists",false).put("publicCertification","BLOCKED");
    }
    public static void main(String[] args)throws Exception {
        ImportedAciPatternTest.run(args);
        Files.writeString(Path.of(args[0],"ordered-checks.json"),new JSONObject().put("checks",checks).put("queueCleared",false)
                .put("claim","Finite ordered block correspondence with all native identity operations retained").toString(),StandardOpenOption.CREATE_NEW);
        System.out.println("OrderedPhaseCorrespondenceTest: "+checks+" ordered controls plus retained imported/ACI checks");
    }
}
