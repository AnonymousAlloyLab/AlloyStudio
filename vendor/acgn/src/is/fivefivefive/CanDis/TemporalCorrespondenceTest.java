package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Finite source-qualified production bridge transitions; no existing test main. */
public final class TemporalCorrespondenceTest {
    private static int checks,corruptions;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void bad(Canonical.Prepared a,Canonical.Prepared b,is.fivefivefive.CanDis.metric.NativeRepairTrace f,
                            is.fivefivefive.CanDis.metric.NativeRepairTrace c,JSONObject report,String why) {
        var result=NativeOperationComparison.compareCaptured(a,b,f,c,report);
        check(result.status()==NativeOperationComparison.Status.INVALID_REPLAY,"Corruption "+why+": "+result.status()+" "+result.reason());corruptions++;
    }
    private static JSONObject run(JSONObject identity,int d,Path out,JSONObject before)throws Exception {
        var pair=LocalOwnerTraceTest.prepare(identity);var a=pair.get(d);var b=pair.get(1-d);
        var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
        check(cert.replay(cert.json())==cert.distance(),"certified selected path first");
        var fast=CanonicalRepairTraces.fast(a,b);check(fast.replay(fast.json())==fast.distance(),"Fast actual selected path");
        var result=NativeOperationComparison.compare(a,b,fast,cert);var evidence=result.evidence();
        check(result.status()==NativeOperationComparison.Status.MATCHED,"Expected checked production correspondence: "+identity.getString("id")+" "+d+" "+result.reason()+" "+evidence.opt("correspondenceBoundary")+" "+evidence.opt("error"));
        check(!evidence.getBoolean("complete")&&!evidence.getBoolean("javaLeanCorrespondenceChecked"),"public/Java-Lean status remains blocked");
        var report=evidence.getJSONObject("diagnostic");
        if(before!=null)for(String arm:List.of("fast","certified")) {
            var old=new JSONObject(before.getJSONObject("diagnostic").getJSONObject(arm).toString());old.remove("diagnosticStatus");
            LocalOwnerTraceTest.sameExecution(old,arm.equals("fast")?fast.json():cert.json());checks++;
        }
        if(TemporalOperationCorrespondence.temporal(a)||TemporalOperationCorrespondence.temporal(b)) {
            check(evidence.getBoolean("orderedBlockEffectsEqual")&&evidence.getBoolean("allNativeEntriesRetained")&&!evidence.getBoolean("literalOneToOneOperationLists"),"complete ordered block claim, not literal-list equality");
            check(NativeOperationComparison.compareCaptured(a,b,fast,cert,report).status()==NativeOperationComparison.Status.MATCHED,"same retained choices recheck, no optimization");
            for(String arm:List.of("fast","certified"))for(String mode:List.of("charge","address","order","missing-zero","payload","owner","source","certificate","selected")) {
                var corrupt=new JSONObject(report.toString());var x=corrupt.getJSONObject(arm);
                switch(mode) {
                    case "charge" -> x.getJSONArray("orderedNativeOperations").getJSONObject(0).put("cost",999);
                    case "address" -> x.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","/foreign");
                    case "order" -> {var ops=x.getJSONArray("orderedNativeOperations");Object first=ops.get(0);ops.put(0,ops.get(1));ops.put(1,first);}
                    case "missing-zero" -> x.getJSONObject("nativeTemporalProgram").put("zeroCostReads",new JSONArray());
                    case "payload" -> x.getJSONArray("phases").getJSONObject(0).getJSONObject("program").getJSONArray("operations").getJSONObject(0).getJSONObject("before").put("head","foreign");
                    case "owner" -> x.getJSONArray("sourceBindingContexts").getJSONObject(0).put("phase",999);
                    case "source" -> x.put("sourceProducerObservationDigest","foreign");
                    case "certificate" -> {if(arm.equals("certified"))x.getJSONObject("constructionDerivations").getJSONObject("source").put("artifactRoot",999);else x.put("constructionLink","foreign");}
                    case "selected" -> x.getJSONObject("nativeTemporalProgram").put("selectedCorrespondence",new JSONArray());
                }
                bad(a,b,fast,cert,corrupt,arm+" "+mode);
            }
            var foreign=NativeOperationComparison.compareCaptured(b,a,fast,cert,report);
            check(foreign.status()!=NativeOperationComparison.Status.MATCHED,"actual foreign original endpoint objects cannot borrow a selected trace");
            var fcost=new int[]{fast.distance(),fast.temporalDistance(),fast.quantifierDistance(),fast.matrixDistance()};
            var ccost=new int[]{cert.distance(),cert.temporalDistance(),cert.quantifierDistance(),cert.matrixDistance()};
            fcost[0]++;ccost[0]++;
            check(result.bindScanCosts(fcost,ccost).status()==NativeOperationComparison.Status.INVALID_REPLAY,"both-arms scan drift remains rejected");
        }
        String name=identity.getString("id").replace('/','_')+"-"+d+".json";
        Files.writeString(out.resolve(name),evidence.put("identity",identity).put("direction",d).toString(),StandardOpenOption.CREATE_NEW);
        return new JSONObject().put("identity",identity).put("direction",d).put("before",before==null?"NEW_CONTROL":before.getString("status"))
                .put("after",result.status()).put("receipt",name).put("fast",fast.distance()).put("certified",cert.distance());
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]),before=Path.of(args[2]);Files.createDirectories(out);
        var rows=new JSONArray();var ids=new JSONArray(Files.readString(Path.of(args[1])));
        for(Object value:ids) {
            var row=(JSONObject)value;var id=row.getJSONObject("identity");int d=row.getInt("direction");
            String name=id.getString("id").replace('/','_')+"-"+d+".json";
            rows.put(run(id,d,out,new JSONObject(Files.readString(before.resolve(name)))));
        }
        Path source=out.resolve("temporal-identical.als");String text="var sig A {} pred a {always some A} pred b {always some A}";
        Files.writeString(source,text,StandardOpenOption.CREATE_NEW);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        var identity=new JSONObject().put("id","temporal-identical").put("source",source.toString()).put("sha256",hash).put("left","a").put("right","b");
        rows.put(run(identity,0,out,null));rows.put(run(identity,1,out,null));
        var consumers=new LinkedHashMap<String,String>();
        consumers.put("consumer-simple","sig A {} pred inv { always some A } pred invc { eventually no A }\n");
        consumers.put("consumer-closed-call","open util/ordering[A] sig A {} pred b {some A}pred a {all x:A | always x in max[A]}\n");
        consumers.put("consumer-bound-call","open util/ordering[A] sig A {r:set A} pred b {some A} pred a {all x:A | always x in max[x]}\n");
        for(var consumer:consumers.entrySet()) {
            Path file=out.resolve(consumer.getKey()+".als");Files.writeString(file,consumer.getValue(),StandardOpenOption.CREATE_NEW);
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            boolean simple=consumer.getKey().equals("consumer-simple");
            var id=new JSONObject().put("id",consumer.getKey()).put("source",file.toString()).put("sha256",digest).put("left",simple?"inv":"a").put("right",simple?"invc":"b");
            rows.put(run(id,0,out,null));rows.put(run(id,1,out,null));
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("corruptionsRejected",corruptions).put("directions",rows.length()).put("rows",rows)
                .put("queueCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("TemporalCorrespondenceTest: "+checks+" checks, "+corruptions+" rejected corruptions, "+rows.length()+" directions");
    }
}
