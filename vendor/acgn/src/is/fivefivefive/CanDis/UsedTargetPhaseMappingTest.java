package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Global paid links and matrix-use projections have distinct authority. */
public final class UsedTargetPhaseMappingTest {
    private static int checks,negative;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static String hash(String s)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static JSONObject surface(JSONObject arm){var out=new JSONObject();for(String k:List.of("metricVersion","phases","nativeDeclarationPrograms","nativeTemporalProgram","nativeDeclarationRepresentationSteps","orderedNativeOperations","selectedGlobalMapping","selectedDeclarationPermutation","selectedQuantifierCorrespondence"))if(arm.has(k))out.put(k,arm.get(k));return out;}
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);boolean before=args.length>1&&args[1].equals("before");
        Path src=Path.of("classified-data/classroom_fol/both/rD5XxghwfPYFKxQdn_inv14.als");String text=Files.readString(src);
        check(hash(text).equals("6bef782c0e3498f9ceeba0d2c85c6ba4b82cad146b2ed2b55f381d6a7da07dd0"),"natural source hash");
        var input=new JSONObject().put("source",src.toAbsolutePath().toString()).put("sha256",hash(text)).put("left","inv14").put("right","inv14c");
        var cases=new LinkedHashMap<String,JSONObject>();cases.put("natural",input);
        if(!before){Path variant=out.resolve("renamed-unused.als");String renamed=text.replace("s : Student, g : Group","s : Student, unused : Group");check(!renamed.equals(text),"variant actually renamed unused outer binder");Files.writeString(variant,renamed,StandardOpenOption.CREATE_NEW);cases.put("synthetic-renamed-unused",new JSONObject().put("source",variant.toString()).put("sha256",hash(renamed)).put("left","inv14").put("right","inv14c"));}
        var results=new JSONArray();
        for(var entry:cases.entrySet()){var pair=LocalOwnerTraceTest.prepare(entry.getValue());for(int d=0;d<2;d++){
            var a=pair.get(d);var b=pair.get(1-d);var fast=CanonicalRepairTraces.fast(a,b);var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var result=NativeOperationComparison.compare(a,b,fast,cert);var e=result.evidence();Files.writeString(out.resolve(entry.getKey()+"-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            if(before){check(result.status()==(d==0?NativeOperationComparison.Status.MATCHED:NativeOperationComparison.Status.INVALID_REPLAY),"exact old gate result");if(d==1)check(e.getString("error").equals("Global/phase map conflict"),"exact old refusal");continue;}
            check(result.status()==NativeOperationComparison.Status.MATCHED,"actual path "+result.reason());
            for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(e.getBoolean(flag),flag);
            var report=e.getJSONObject("diagnostic");var ca=report.getJSONObject("certified");var phase=ca.getJSONArray("phases").getJSONObject(0);
            check(ca.getJSONArray("selectedGlobalMapping").similar(new JSONArray(new int[]{1,0})),"actual global paid correspondence");
            check(phase.getJSONArray("alphaMapping").similar(new JSONArray(d==0?new int[]{1,0}:new int[]{-1,0})),"actual used-target projection");
            check(ca.getInt("distance")==41&&ca.getInt("matrixDistance")==40&&ca.getInt("quantifierDistance")==1,"unchanged charges");
            check(NativeOperationComparison.compareCaptured(a,b,fast,cert,report).status()==NativeOperationComparison.Status.MATCHED,"immutable native replay");
            for(int mode=0;mode<9;mode++){
                var bad=new JSONObject(report.toString());var c=bad.getJSONObject("certified");var p=c.getJSONArray("phases").getJSONObject(0);var decl=c.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt");
                switch(mode){
                    case 0 -> c.getJSONArray("selectedGlobalMapping").put(0,-1);
                    case 1 -> c.getJSONArray("selectedGlobalMapping").put(0,0);
                    case 2 -> p.getJSONArray("alphaMapping").put(1,-1);
                    case 3 -> p.getJSONArray("alphaMapping").put(0,999);
                    case 4 -> c.put("selectedQuantifierCorrespondence",new JSONObject());
                    case 5 -> decl.getJSONArray("source").getJSONObject(0).put("occurrence","foreign-owner");
                    case 6 -> decl.getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case 7 -> p.put("phase",1);
                    case 8 -> p.getJSONObject("program").getJSONArray("operations").getJSONObject(0).put("premise","foreign");
                }
                negative++;check(NativeOperationComparison.compareCaptured(a,b,fast,cert,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,"invalid link/owner/payment/phase/receipt "+mode);
            }
            if(entry.getKey().equals("natural")&&args.length==3){var old=new JSONObject(Files.readString(Path.of(args[1+d])));var preserved=new JSONObject();for(String arm:List.of("fast","certified"))preserved.put(arm,LocalOwnerTraceTest.sameExecution(surface(old.getJSONObject(arm)),surface(report.getJSONObject(arm))));Files.writeString(out.resolve("native-preservation-"+d+".json"),preserved.toString(2),StandardOpenOption.CREATE_NEW);}
            results.put(new JSONObject().put("input",entry.getValue()).put("natural",entry.getKey().equals("natural")).put("direction",d).put("status",result.status().name()));
        }}
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negative).put("directions",before?2:results.length()).put("oldGate",before).put("results",results).put("publicCertification","BLOCKED");Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}
