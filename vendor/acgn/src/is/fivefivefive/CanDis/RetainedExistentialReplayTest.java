package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

public final class RetainedExistentialReplayTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private record Pair(Canonical.Prepared left,Canonical.Prepared right,CanonicalAlloyPipeline.Prepared exactLeft,CanonicalAlloyPipeline.Prepared exactRight){Pair reverse(){return new Pair(right,left,exactRight,exactLeft);}}
    private static Pair prepare(Path path,String name)throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(path));
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name,name+"c"),m);v.visit(new ModelUnit(null,m),null);
        var a=Canonical.prepare(v.getForest().get(v.getForestId(name)));var b=Canonical.prepare(v.getForest().get(v.getForestId(name+"c")));
        return new Pair(a,b,CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
    }
    private static JSONObject copy(JSONObject o){return new JSONObject(o.toString());}
    private static void invalid(Pair p,JSONObject receipt,String why){check(NativeOperationComparison.compareCaptured(p.left,p.right,receipt).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    public static void main(String[] args)throws Exception {
        Path out=args.length==0?Files.createTempDirectory("retained-exists-"):Path.of(args[0]);Files.createDirectories(out);
        var p=prepare(Path.of("classified-data/classroom_fol/both/85tWoTeBhoEDhCKmw_inv14.als"),"inv14");
        for(int direction=0;direction<2;direction++) {
            var q=direction==0?p:p.reverse();var result=NativeOperationComparison.compare(q.left,q.right,q.exactLeft,q.exactRight);var evidence=result.evidence();
            Files.writeString(out.resolve("after-"+direction+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"all ordered typed actions equal: "+evidence.opt("firstDifference"));
            check(result.bindScanCosts(new int[]{37,0,2,35},new int[]{37,0,2,35}).status()==NativeOperationComparison.Status.MATCHED,"native scalar unchanged both directions");
            check(evidence.getInt("fastActionCount")==20 && evidence.getInt("certifiedActionCount")==20,"shorter actual sequence, including zero structural actions");
            var actions=evidence.getJSONArray("fastActions");for(int i=0;i<2;i++)check(actions.getJSONObject(i).getString("kind").equals("update")&&actions.getJSONObject(i).getInt("cost")==1,"two actual paid declaration updates");
            var report=evidence.getJSONObject("diagnostic");
            check(NativeOperationComparison.compareCaptured(q.left,q.right,report).status()==NativeOperationComparison.Status.MATCHED,"independent native replay on original source/type/owner/certificate premises");
            check(report.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(Map.of("_q0","_q0","_q1","_q1"))),"explicit paid correspondence, not scalar copy");
            check(!evidence.getBoolean("complete")&&!evidence.getBoolean("publicSequenceLinked")&&!evidence.getBoolean("javaLeanCorrespondenceChecked"),"public certification remains blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(q,bad,"foreign source");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q1");invalid(q,bad,"paid link corruption");
            bad=copy(report);bad.getJSONObject("certified").getJSONArray("selectedGlobalMapping").put(0,999);invalid(q,bad,"foreign matrix owner");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");invalid(q,bad,"declaration type corruption");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",34);invalid(q,bad,"matrix charge corruption");
        }
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"4g8G2qHXAbDJFACM7_inv15","inv15","49"},new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"})) {
            var q=prepare(Path.of("classified-data",row[0].startsWith("xJnit")?"coursesOld":"classroom_fol","both",row[0]+".als"),row[1]);
            var report=DistanceReplayDiagnostics.capture(q.left,q.right,q.exactLeft,q.exactRight);
            for(String arm:List.of("fast","certified")) {
                var r=report.getJSONObject(arm);check(r.getInt("distance")==Integer.parseInt(row[2])&&r.getBoolean("matrixReplayChecked"),"prior scalar/native replay "+row[0]);
                check(r.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior actual declaration links "+row[0]);
            }
            if(row[0].startsWith("4g8") || row[0].startsWith("SASE") || row[0].startsWith("JoNX"))
                check(NativeOperationComparison.compare(q.left,q.right,q.exactLeft,q.exactRight).status()==NativeOperationComparison.Status.MATCHED,"prior fully compared native actions "+row[0]);
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("RetainedExistentialReplayTest: "+checks+" checks passed; original37/actions20 both directions; prior native controls retained");
    }
}
