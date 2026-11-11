package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Actual Z4 paths plus renamed structural instances and retained-check negatives. */
public final class HeterogeneousPrefixReplayTest {
    private static int checks,negative;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static JSONObject component(JSONObject report,String arm){return report.getJSONObject(arm).getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(arm.equals("fast")?0:1);}
    private static JSONObject nativeSurface(JSONObject arm) {
        var out=new JSONObject();
        for(String key:List.of("metricVersion","phases","nativeDeclarationPrograms","nativeTemporalProgram",
                "nativeDeclarationRepresentationSteps","orderedNativeOperations","selectedTemporalReferencePayments",
                "selectedDeclarationPermutation","selectedQuantifierCorrespondence","selectedPaidDeclarationCorrespondence"))
            if(arm.has(key))out.put(key,arm.get(key));
        return out;
    }
    private static void invalid(Canonical.Prepared a,Canonical.Prepared b,JSONObject report,String why){
        negative++;check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        Path natural=Path.of("classified-data/classroom_fol/both/Z4ALKzmgFaiaFhcbW_inv14.als");String text=Files.readString(natural);
        check(hash(text).equals("3d7c4a48a4cb4c31a3fc798955c6d49483b8383841077aca41d6da1bde999f5f"),"exact natural source");
        var cases=new LinkedHashMap<String,Path>();cases.put("natural-Z4",natural);
        Path renamed=out.resolve("renamed.als");Files.writeString(renamed,text.replace("Person","PersonActor").replace("Class","ClassRoom").replace("Group","GroupTeam"),StandardOpenOption.CREATE_NEW);cases.put("synthetic-renamed",renamed);
        var results=new JSONArray();
        for(var entry:cases.entrySet()) {
            var input=new JSONObject().put("source",entry.getValue().toAbsolutePath().toString()).put("sha256",hash(Files.readString(entry.getValue())))
                    .put("left","inv14").put("right","inv14c");
            var ps=LocalOwnerTraceTest.prepare(input);
            for(int direction=0;direction<2;direction++) {
                var a=ps.get(direction);var b=ps.get(1-direction);
                var result=NativeOperationComparison.compare(a,b,CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                var evidence=result.evidence();Files.writeString(out.resolve(entry.getKey()+"-"+direction+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"actual typed path "+evidence.opt("reason"));
                for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(evidence.getBoolean(flag),flag);
                var report=evidence.getJSONObject("diagnostic");
                check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.MATCHED,"replay exact captured paths");
                for(String arm:List.of("fast","certified")) {
                    var nativeArm=report.getJSONObject(arm);check(nativeArm.getInt("distance")==16&&nativeArm.getInt("quantifierDistance")==3&&nativeArm.getInt("matrixDistance")==13,"native costs unchanged");
                    var c=component(report,arm);var perm=c.getJSONObject("connectingPermutation");
                    String side=direction==0?"target":"source",proof=side+"Presentation";
                    check(perm.getString("admission").equals("INDEPENDENT_TYPED_PREFIX_PRESENTATION"),"presentation, not typed-slot automorphism");
                    var swap=perm.getJSONObject(proof).getJSONArray("adjacentExchanges");check(swap.length()==1,"one actual independent exchange");
                    check(!swap.getJSONObject(0).getJSONObject("left").getString("type").equals(swap.getJSONObject(0).getJSONObject("right").getString("type")),"heterogeneous carriers retain identity");
                    for(String field:List.of("sourceOccurrence","owner","type","domain","phase","quantifier","dependencies","identity")) {
                        var bad=copy(report);var endpoint=component(bad,arm).getJSONObject("connectingPermutation").getJSONObject(proof).getJSONArray("adjacentExchanges").getJSONObject(0).getJSONObject("left");
                        endpoint.put(field,field.equals("dependencies")?new JSONArray().put(0):field.equals("phase")?1:"foreign");
                        invalid(a,b,bad,"substituted "+field);
                    }
                    var bad=copy(report);component(bad,arm).getJSONObject("connectingPermutation").remove("sourcePresentation");invalid(a,b,bad,"missing presentation cannot bypass old type gate");
                    bad=copy(report);component(bad,arm).getJSONObject("connectingPermutation").getJSONArray(side).put(0,0);invalid(a,b,bad,"nonbijection");
                    bad=copy(report);component(bad,arm).getJSONObject("receipt").getJSONArray(direction==0?"target":"source").getJSONObject(0).put("occurrence","foreign-same-type");invalid(a,b,bad,"same-type wrong occurrence");
                    bad=copy(report);bad.getJSONObject(arm).getJSONObject(arm.equals("fast")?"selectedPaidDeclarationCorrespondence":"selectedQuantifierCorrespondence").put(arm.equals("fast")?"_q0":"0",0);invalid(a,b,bad,"wrong payment");
                    bad=copy(report);component(bad,arm).getJSONObject("receipt").getJSONArray("operations").getJSONObject(0).put("cost",1);invalid(a,b,bad,"wrong zero charge");
                }
                if(entry.getKey().equals("natural-Z4")&&args.length==3) {
                    var old=new JSONObject(Files.readString(Path.of(args[1+direction])));var preserved=new JSONObject();
                    for(String arm:List.of("fast","certified"))preserved.put(arm,LocalOwnerTraceTest.sameExecution(nativeSurface(old.getJSONObject(arm)),nativeSurface(report.getJSONObject(arm))));
                    Files.writeString(out.resolve("native-path-preservation-"+direction+".json"),preserved.toString(2),StandardOpenOption.CREATE_NEW);
                }
                results.put(new JSONObject().put("input",input).put("natural",entry.getKey().equals("natural-Z4")).put("direction",direction).put("status",result.status().name()));
            }
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negative).put("directions",results.length()).put("results",results).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}
