package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.metric.NativeRepairTrace;

/** Source-qualified selection authorization and unchanged native-path retention. */
public final class CapturedSelectionAuthorityTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    private static void reject(Canonical.Prepared a,Canonical.Prepared b,NativeRepairTrace f,NativeRepairTrace c,
                               JSONObject report,String mode,JSONArray controls) {
        var result=NativeOperationComparison.compareCaptured(a,b,f,c,report);
        check(result.status()==NativeOperationComparison.Status.INVALID_REPLAY,"Expected authority rejection "+mode+": "+result.status()+" "+result.reason());
        rejections++;
        controls.put(new JSONObject().put("mode",mode).put("status",result.status()).put("reason",result.reason())
                .put("error",result.evidence().opt("error")));
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]),prior=Path.of(args[2]);Files.createDirectory(out);
        var identities=new JSONArray(Files.readString(Path.of(args[1])));
        var sources=new LinkedHashMap<String,JSONObject>();
        for(Object value:identities) {
            var row=(JSONObject)value;var id=row.getJSONObject("identity");
            String key=new JSONArray().put(id.getString("source")).put(id.getString("sha256")).put(id.getString("left")).put(id.getString("right")).toString();
            sources.putIfAbsent(key,id);
        }
        var results=new JSONArray();
        for(var id:sources.values()) {
            var pair=LocalOwnerTraceTest.prepare(id);
            var certifiedEndpoints=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            var fast=new NativeRepairTrace[2];var certified=new NativeRepairTrace[2];
            for(int d=0;d<2;d++) {
                certified[d]=CanonicalRepairTraces.certified(certifiedEndpoints.get(d),certifiedEndpoints.get(1-d));
                check(certified[d].replay(certified[d].json())==certified[d].distance(),"selected certified path first");
                fast[d]=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));
                check(fast[d].replay(fast[d].json())==fast[d].distance(),"selected Fast path");
            }
            for(int d=0;d<2;d++) {
                var a=pair.get(d);var b=pair.get(1-d);var f=fast[d];var c=certified[d];
                String name=id.getString("id").replace('/','_')+"-"+d+".json";
                var before=new JSONObject(Files.readString(prior.resolve(name)));
                var normal=NativeOperationComparison.compare(a,b,f,c);
                check(normal.status()==NativeOperationComparison.Status.MATCHED,"normal production retention "+name);
                var report=normal.evidence().getJSONObject("diagnostic");
                var checked=NativeOperationComparison.compareCaptured(a,b,f,c,report);
                check(checked.status()==NativeOperationComparison.Status.MATCHED,"valid exact immutable authorities "+name);
                check(!checked.evidence().getBoolean("complete")&&!checked.evidence().getBoolean("javaLeanCorrespondenceChecked"),"public boundary unchanged");
                for(String arm:List.of("fast","certified")) {
                    var old=copy(before.getJSONObject("diagnostic").getJSONObject(arm));old.remove("diagnosticStatus");
                    LocalOwnerTraceTest.sameExecution(old,arm.equals("fast")?f.json():c.json());checks++;
                }
                var controls=new JSONArray();var originalF=f.json();var originalC=c.json();
                reject(a,b,null,c,report,"null-fast",controls);
                reject(a,b,f,null,report,"null-certified",controls);
                reject(a,b,null,null,report,"null-both",controls);
                boolean targeted=Set.of("original","dependent","identical","binary-import","source-order-two","source-order-three").contains(id.getString("id"));
                if(targeted) {
                    // Reverse nonidentity endpoints preserve the scalar but not the selected program.
                    if(f.distance()>0) {
                        check(f.distance()==fast[1-d].distance()&&c.distance()==certified[1-d].distance(),"foreign reverse same scalar");
                        check(!f.json().similar(fast[1-d].json())&&!c.json().similar(certified[1-d].json()),"foreign reverse has distinct selected authority");
                        reject(a,b,fast[1-d],c,report,"same-cost-reverse-fast",controls);
                        reject(a,b,f,certified[1-d],report,"same-cost-reverse-certified",controls);
                        reject(a,b,fast[1-d],certified[1-d],report,"same-cost-reverse-both",controls);
                        for(String arm:List.of("fast","certified")) {
                            var changed=copy(report);changed.put(arm,(arm.equals("fast")?fast[1-d]:certified[1-d]).json().put("diagnosticStatus","MATRIX_REPLAY_CHECKED"));
                            reject(a,b,f,c,changed,"foreign-same-cost-report-"+arm,controls);
                        }
                        var targetFast=CanonicalRepairTraces.fast(b,b);
                        var targetCertified=CanonicalRepairTraces.certified(certifiedEndpoints.get(1-d),certifiedEndpoints.get(1-d));
                        reject(a,b,targetFast,c,report,"same-target-fast",controls);
                        reject(a,b,f,targetCertified,report,"same-target-certified",controls);
                    }
                    for(String arm:List.of("fast","certified"))for(String mode:List.of("cost","order","zero","type","owner","construction","selection","marker")) {
                        var corrupt=copy(report);var x=corrupt.getJSONObject(arm);
                        switch(mode) {
                            case "cost" -> x.put("distance",x.getInt("distance")+1);
                            case "order" -> {var ops=x.getJSONArray("orderedNativeOperations");Object first=ops.get(0);ops.put(0,ops.get(1));ops.put(1,first);}
                            case "zero" -> x.getJSONObject("nativeTemporalProgram").put("zeroCostReads",new JSONArray());
                            case "type" -> {var payload=x.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).getJSONObject("before");var head=new JSONArray(payload.getString("head"));head.put(1,"ForeignType");payload.put("head",head.toString());}
                            case "owner" -> x.getJSONArray("sourceBindingContexts").getJSONObject(0).put("phase",999);
                            case "construction" -> x.put("constructionLink","foreign");
                            case "selection" -> x.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray().put("foreign"));
                            case "marker" -> x.put("diagnosticStatus","UNVERIFIED");
                        }
                        reject(a,b,f,c,corrupt,arm+"-"+mode,controls);
                    }
                }
                check(originalF.similar(f.json())&&originalC.similar(c.json()),"rejected reports cannot mutate immutable authority");
                check(NativeOperationComparison.compareCaptured(a,b,f,c,report).status()==NativeOperationComparison.Status.MATCHED,"valid authority after corruption controls");
                var evidence=normal.evidence().put("identity",id).put("direction",d).put("controls",controls)
                        .put("capturedSelectionAuthorizationChecked",true).put("priorReceipt",prior.resolve(name).toString());
                Files.writeString(out.resolve(name),evidence.toString(),StandardOpenOption.CREATE_NEW);
                results.put(new JSONObject().put("identity",id).put("direction",d).put("before",before.getString("status"))
                        .put("after",normal.status()).put("fast",f.distance()).put("certified",c.distance()).put("receipt",name).put("rejections",controls.length()));
            }
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("rejections",rejections).put("directions",results.length()).put("rows",results)
                .put("queueCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("CapturedSelectionAuthorityTest: "+checks+" checks, "+rejections+" rejections, "+results.length()+" directions");
    }
}
