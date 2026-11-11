package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.RepairView.Node;

/** Finite original-phase/local-owner bridge, preserving selected native paths. */
public final class TemporalLocalOwnerTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reject(Runnable action,String why) {
        checks++;try{action.run();}catch(IllegalArgumentException expected){rejections++;return;}
        throw new AssertionError("Accepted tampered evidence: "+why);
    }
    private static void mutate(Object object,String field,Object bad,Runnable action,String why)throws Exception {
        var f=object.getClass().getDeclaredField(field);f.setAccessible(true);var old=f.get(object);
        try{f.set(object,bad);reject(action,why);}finally{f.set(object,old);}
    }
    private static int localControls(LocalOwnerCorrespondence owners,EGraphNode fast,Node cert)throws Exception {
        int count=0;
        if(cert.isVariable()&&cert.bindingIndex()<0) {
            Runnable check=()->TemporalOperationCorrespondence.localVariable(owners,fast,cert);
            check.run();
            mutate(cert,"lexicalVariable","@local[999:999]",check,"lexical owner/tuple");
            mutate(cert,"bindingIndex",0,check,"local replaced by outer");
            mutate(cert,"observationType",is.fivefivefive.CanDis.theory.GraphType.BOOL,check,"local projected type");
            mutate(fast,"sourceOccurrenceLineage",-99L,check,"foreign original occurrence");
            mutate(fast,"alphaName","@fast-local:999:999",check,"foreign Fast coordinate");
            check.run();count++;
        }
        var cs=FastChainGeometry.children(fast);
        check(cs.size()==cert.children().size(),"actual paired child geometry");
        for(int i=0;i<cs.size();i++)count+=localControls(owners,cs.get(i),cert.children().get(i));
        return count;
    }
    public static void main(String[] args)throws Exception {
        var rows=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object item:rows) {
            var row=(JSONObject)item;var id=row.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);
            var projections=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            var coverage=new HashSet<String>();
            for(int d=0;d<2;d++) {
                var c=CanonicalRepairTraces.certified(projections.get(d),projections.get(1-d));
                check(c.replay(c.json())==c.distance(),"certified actual path first");
                var f=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));
                check(f.replay(f.json())==f.distance(),"Fast actual path");
                String name=id.getString("id").replace('/','_')+"-"+d+".json";
                var receipt=new JSONObject().put("identity",id).put("direction",d).put("fast",f.json()).put("certified",c.json());
                Files.writeString(out.resolve(name+".native"),receipt.toString(),StandardOpenOption.CREATE_NEW);
                var result=NativeOperationComparison.compare(pair.get(d),pair.get(1-d),f,c);
                receipt.put("comparison",result.evidence());
                Files.writeString(out.resolve(name),receipt.toString(),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"original production bridge "+name+" "+result.reason()+" "+result.evidence().opt("error"));
                if(row.has("expected"))check(f.distance()==row.getInt("expected")&&c.distance()==row.getInt("expected"),"unchanged selected charges");
                if(row.has("prior")) {
                    var prior=new JSONObject(Files.readString(Path.of(row.getJSONArray("prior").getString(d))));
                    if(prior.has("diagnostic"))prior=prior.getJSONObject("diagnostic");
                    var retention=new JSONObject();
                    for(String arm:List.of("fast","certified")) {
                        var old=new JSONObject(prior.getJSONObject(arm).toString());old.remove("diagnosticStatus");
                        retention.put(arm,LocalOwnerTraceTest.sameExecution(old,arm.equals("fast")?f.json():c.json()));checks++;
                    }
                    receipt.put("priorNativePathRetention",retention);
                }
                var controls=new JSONArray();
                if(row.optBoolean("local")) {
                    for(int e=0;e<2;e++) {
                        var forms=CanonicalDistance.prepare(pair.get(e).normalizedForms()).comparisonForms();
                        for(int phase=0;phase<forms.size();phase++) {
                            var form=forms.get(phase);var owners=LocalOwnerCorrespondence.check(form.comparisonSource(),form.getMatrixEGraph());
                            int count=localControls(owners,form.getMatrixEGraph(),projections.get(e).repairView().phases().get(phase).matrix());
                            if(count>0){coverage.add(d+":"+e);controls.put(new JSONObject().put("direction",d).put("endpoint",e).put("phase",phase).put("localOccurrences",count).put("tamperedRejected",count*5));}
                        }
                    }
                    var diagnostic=result.evidence().getJSONObject("diagnostic");
                    for(String arm:List.of("fast","certified"))for(String mode:List.of("owner-phase","cost","order","construction","source")) {
                        var bad=new JSONObject(diagnostic.toString());var x=bad.getJSONObject(arm);
                        switch(mode) {
                            case "owner-phase" -> x.getJSONArray("sourceBindingContexts").getJSONObject(0).put("phase",999);
                            case "cost" -> x.getJSONArray("orderedNativeOperations").getJSONObject(0).put("cost",999);
                            case "order" -> x.getJSONArray("orderedNativeOperations").getJSONObject(0).put("payloadPath","foreign");
                            case "construction" -> x.put("constructionLink","foreign");
                            case "source" -> x.put("sourceProducerObservationDigest","foreign");
                        }
                        var invalid=NativeOperationComparison.compareCaptured(pair.get(d),pair.get(1-d),f,c,bad);
                        check(invalid.status()==NativeOperationComparison.Status.INVALID_REPLAY,"tampered selected authority "+mode);rejections++;
                        controls.put(new JSONObject().put("arm",arm).put("mode",mode).put("rejected",true));
                    }
                    check(NativeOperationComparison.compare(pair.get(d),pair.get(1-d),f,c).status()==NativeOperationComparison.Status.MATCHED,"restored originals still matched");
                }
                receipt.put("controls",controls);Files.writeString(out.resolve(name),receipt.toString());
                results.put(new JSONObject().put("identity",id).put("direction",d).put("status",result.status()).put("receipt",name).put("fast",f.distance()).put("certified",c.distance()));
            }
            if(row.optBoolean("local")) {
                var expected=new HashSet<String>();
                for(Object endpoint:row.getJSONArray("localEndpoints"))for(int d=0;d<2;d++)expected.add(d+":"+endpoint);
                check(!expected.isEmpty()&&coverage.equals(expected),"every applicable endpoint in both directions");
            }
        }
        Files.writeString(out.resolve("results.json"),new JSONObject().put("checks",checks).put("tamperedEvidenceRejections",rejections).put("directions",results.length()).put("rows",results).put("queueCleared",false).put("publicCertification","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("TemporalLocalOwnerTest: "+checks+" checks, "+rejections+" tampered-evidence rejections, "+results.length()+" directions");
    }
}
