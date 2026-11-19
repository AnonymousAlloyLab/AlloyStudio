package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.ReplayValidationException;

/** Mixed-stage dispatch and selected-native authority controls, not corpus certification. */
public final class SharedActionBoundaryTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static JSONObject action(String stage,Object source,Object target){return new JSONObject().put("stage",stage)
            .put("kind","node").put("cost",0).put("source",source).put("target",target);}
    private static JSONArray repeated(String side){return new JSONArray().put(new JSONObject().put("endpoint",side)
            .put("certifiedParentPath","$").put("members",new JSONArray(List.of(0,1))));}
    private static JSONObject scan(JSONArray source,JSONArray target,JSONArray actions){
        return NativeOperationComparison.ambiguousAction(source,target,actions);
    }
    private static void rejected(JSONArray actions,String why){
        try{scan(repeated("source"),repeated("target"),actions);throw new AssertionError(why);}
        catch(ReplayValidationException expected){check(true,why);}
    }
    private static void dispatch(){
        var empty=new JSONArray();
        var mixed=new JSONArray().put(action("declarationPermutation",new JSONArray(List.of(1,0)),new JSONArray(List.of(0))))
                .put(action("declaration",0,JSONObject.NULL)).put(action("matrix","$/2","$/3"));
        String before=mixed.toString();
        check(scan(empty,empty,mixed)==null,"mixed stream with empty classes");
        check(scan(repeated("source"),repeated("target"),mixed)==null,"nonmatrix ordinals are not matrix paths");
        mixed.put(action("matrix","$/0/2",JSONObject.NULL));
        check(scan(repeated("source"),empty,mixed).getInt("actionIndex")==3,"source path blocked at original index");
        mixed.put(3,action("matrix",JSONObject.NULL,"$/1"));
        check(scan(empty,repeated("target"),mixed).getInt("actionIndex")==3,"target path blocked at original index");
        var prior=new JSONArray(before);
        for(int i=0;i<prior.length();i++)check(prior.getJSONObject(i).similar(mixed.getJSONObject(i)),"decoder does not rewrite action "+i);
        for(Object bad:List.of(1,new JSONArray(List.of(0)),"0","$/x",true))
            rejected(new JSONArray().put(action("matrix",bad,JSONObject.NULL)),"bad matrix coordinate");
        for(Object bad:List.of("0",-1,1.5,new JSONArray(List.of(0)),true))
            rejected(new JSONArray().put(action("declaration",bad,JSONObject.NULL)),"bad declaration coordinate");
        rejected(new JSONArray().put(action("unknown","$","$")),"unknown stage");
        rejected(new JSONArray().put(action("declarationPermutation",new JSONArray(List.of(-1)),empty)),"negative permutation index");
        rejected(new JSONArray().put(action("declarationPermutation",empty,empty).put("cost",1)),"paid permutation");
        rejected(new JSONArray().put(action("matrix","$/0","$"))
                .put(action("matrix",1,"$")),"validate malformed tail after first blocked action");
    }
    private static int ordinal(JSONObject atom,boolean certified){
        String address=atom.getString("occurrence");
        return certified?new JSONArray(address).getInt(4):Integer.parseInt(address.substring(address.lastIndexOf('/')+1));
    }
    private static JSONArray expected(JSONObject arm,boolean certified){
        var program=arm.getJSONArray("nativeDeclarationPrograms").getJSONObject(certified?1:0);
        var source=new JSONArray();var target=new JSONArray();
        for(Object item:program.getJSONArray("source"))source.put(ordinal((JSONObject)item,certified));
        for(Object item:program.getJSONArray("target"))target.put(ordinal((JSONObject)item,certified));
        var out=new JSONArray().put(new JSONObject().put("stage","declarationPermutation").put("cost",0).put("source",source).put("target",target));
        for(Object item:program.getJSONArray("operations")){
            var op=(JSONObject)item; var row=new JSONObject().put("stage","declaration").put("kind",op.getString("kind")).put("cost",op.getInt("cost"));
            for(String side:List.of("source","target")){
                row.put(side,op.isNull(side)?JSONObject.NULL:ordinal(op.getJSONObject(side),certified));
                row.put(side+"Tuple",op.isNull(side)?JSONObject.NULL:op.getJSONObject(side).getJSONArray("tuple"));
            }
            out.put(row);
        }
        return out;
    }
    private static JSONArray declarationPrefix(JSONArray actions){
        var out=new JSONArray();for(Object item:actions)if(!((JSONObject)item).getString("stage").equals("matrix"))out.put(item);return out;
    }
    private static void status(NativeOperationComparison.Result r,NativeOperationComparison.Status wanted,String why){
        check(r.status()==wanted,why+": "+r.status()+" "+r.reason()+" "+r.evidence().opt("error"));
    }
    public static void main(String[] args)throws Exception{
        dispatch();
        Path output=Path.of(args[2]);Files.createDirectory(output);var summary=new JSONArray();
        for(Object item:new JSONArray(Files.readString(Path.of(args[0])))){
            var row=(JSONObject)item;var old=new JSONObject(Files.readString(Path.of(row.getString("file"))));
            var actions=old.getJSONObject("comparison").getJSONArray("fastActions");
            check(scan(new JSONArray(),new JSONArray(),actions)==null,"historical mixed-stage stream decodes");
        }
        var manifest=new JSONObject(Files.readString(Path.of(args[1])));
        for(Object item:manifest.getJSONArray("directions")){
            var row=(JSONObject)item;var id=row.getJSONObject("identity");int d=row.getInt("direction");
            var pair=LocalOwnerTraceTest.prepare(id);var a=pair.get(d);var b=pair.get(1-d);
            var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var result=NativeOperationComparison.compare(a,b,fast,cert);var evidence=result.evidence();
            String name=id.getString("id")+"-"+d;Files.writeString(output.resolve(name+".json"),evidence.toString(),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED || result.status()==NativeOperationComparison.Status.MISMATCH,
                    name+" native replay checked: "+result.status()+" "+evidence.opt("error"));
            var report=evidence.getJSONObject("diagnostic");
            check(expected(fast.json(),false).similar(declarationPrefix(evidence.getJSONArray("fastActions"))),name+" Fast selected program, including original order");
            check(expected(cert.json(),true).similar(declarationPrefix(evidence.getJSONArray("certifiedActions"))),name+" certified selected program, including original order");
            check(fast.distance()==cert.distance(),name+" unchanged equal cost");
            var bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0).put("cost",-1);
            status(NativeOperationComparison.compareCaptured(a,b,bad),NativeOperationComparison.Status.INVALID_REPLAY,"native charge tamper");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("source").getJSONObject(0).put("occurrence","foreign");
            status(NativeOperationComparison.compareCaptured(a,b,bad),NativeOperationComparison.Status.INVALID_REPLAY,"native occurrence tamper");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"foreign");
            status(NativeOperationComparison.compareCaptured(a,b,bad),NativeOperationComparison.Status.INVALID_REPLAY,"native type tamper");
            bad=copy(report);bad.getJSONObject("fast").put("nativeDeclarationPrograms",42);
            var error=NativeOperationComparison.compareCaptured(a,b,bad);
            status(error,NativeOperationComparison.Status.ERROR,"unexpected schema error is not invalid path evidence");
            check(error.evidence().has("diagnostic")&&error.evidence().getString("errorType").equals("org.json.JSONException"),"retain internal failure and inputs");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("operations").getJSONObject(0).put("cost",999);
            status(NativeOperationComparison.compareCaptured(a,b,fast,cert,bad),NativeOperationComparison.Status.INVALID_REPLAY,"immutable selected record tamper");
            summary.put(new JSONObject().put("case",name).put("status",result.status()).put("distance",fast.distance()));
        }
        Files.writeString(output.resolve("checks.json"),new JSONObject().put("checks",checks).put("cases",summary).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("SharedActionBoundaryTest: "+checks+" checks passed; "+summary.length()+" fresh directions");
    }
}
