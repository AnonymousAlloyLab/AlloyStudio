package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.QuotientRepairDistance;
import parser.ast.nodes.ModelUnit;

/** Match-before-skip traversal of existing certified scope candidates. */
public final class ScopeBlockTieReplayTest {
    private static int checks;
    private static void check(boolean value,String reason) {
        checks++;
        if(!value) throw new AssertionError(reason);
    }
    private record Pair(Canonical.Prepared left,Canonical.Prepared right,
            CanonicalAlloyPipeline.Prepared exactLeft,CanonicalAlloyPipeline.Prepared exactRight) {
        Pair reverse() { return new Pair(right,left,exactRight,exactLeft); }
    }
    private static Pair prepare(Path source,String name) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(source));
        // Match the batch's shared callable visitor, including original owner IDs.
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name,name+"c"),module);
        visitor.visit(new ModelUnit(null,module),null);
        var left=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
        var right=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name+"c")));
        return new Pair(left,right,CanonicalAlloyPipeline.prepare(left),CanonicalAlloyPipeline.prepare(right));
    }
    private static void invalid(Pair p,JSONObject report,String reason) {
        var result=NativeOperationComparison.compareCaptured(p.left,p.right,report);
        check(result.status()==NativeOperationComparison.Status.INVALID_REPLAY,reason+": "+result.reason());
    }
    private static JSONObject copy(JSONObject report) { return new JSONObject(report.toString()); }
    private static JSONObject declaration(JSONObject report) {
        return report.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components")
                .getJSONObject(1).getJSONObject("receipt");
    }
    public static void main(String[] args) throws Exception {
        Path out=args.length==0?Files.createTempDirectory("scope-block-tie-"):Path.of(args[0]);
        Files.createDirectories(out);
        Path source=Path.of("classified-data/classroom_fol/both/4g8G2qHXAbDJFACM7_inv15.als");
        var p=prepare(source,"inv15");
        check(p.left.normalizedForms().get(0).getMatrixQuantiVars().stream().map(q->q.getQuantifier().name()).toList()
                .equals(List.of("ALL","SOME","SOME","ALL")),"actual separated compatible source blocks");
        var bindings=p.exactLeft.repairView().phases().get(0).bindings();
        var target=p.exactRight.repairView().phases().get(0).bindings().get(0);
        for(int slot:List.of(0,3)) {
            var binding=bindings.get(slot);
            check(binding.declaration().sameCertifiedPayload(target.declaration()),"source-linked same typed/domain payload");
            check(binding.prenexPathErasureCertified() && target.prenexPathErasureCertified()
                    && binding.ownerPhase()==target.ownerPhase() && binding.ownerContext().equals(target.ownerContext()),"certified prenex and temporal owner premises");
        }
        check(bindings.get(0).declaration().exchangeClass()!=bindings.get(3).declaration().exchangeClass(),"do not merge separate blocks");
        for(int direction=0;direction<2;direction++) {
            var q=direction==0?p:p.reverse();
            var result=NativeOperationComparison.compare(q.left,q.right,q.exactLeft,q.exactRight);
            var evidence=result.evidence();
            Files.writeString(out.resolve("after-"+direction+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"all ordered original-coordinate actions: "+evidence.opt("firstDifference"));
            check(evidence.getInt("fastActionCount")==23 && evidence.getInt("certifiedActionCount")==23,"preserve all paid and zero actions");
            check(evidence.getBoolean("sourceCertificatesChecked") && evidence.getBoolean("fixedChoiceReplaysChecked"),"actual certificate/replay premises checked");
            check(evidence.getJSONArray("fastActions").getJSONObject(0).getString("kind").equals("keep"),"earlier compatible declaration retained");
            var report=evidence.getJSONObject("diagnostic");
            check(NativeOperationComparison.compareCaptured(q.left,q.right,report).status()==NativeOperationComparison.Status.MATCHED,"independent original endpoint replay");
            check(result.bindScanCosts(new int[]{49,0,3,46},new int[]{49,0,3,46}).status()==NativeOperationComparison.Status.MATCHED,"native components unchanged");
            int[] selected=direction==0?new int[]{0,-1,-1,-1}:new int[]{0};
            var cert=report.getJSONObject("certified");
            check(new JSONArray(selected).similar(cert.getJSONArray("selectedGlobalMapping")),"selected actual earlier scope mapping");
            int[] later=direction==0?new int[]{-1,-1,-1,0}:new int[]{3};
            int replay=QuotientRepairDistance.verifyFixedMatrixReplay(q.exactLeft.repairView(),q.exactRight.repairView(),selected,later,
                    cert.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt"));
            check(replay==46,"later admitted mapping also replays every matrix action; genuine tie not a cost change");
            check(!evidence.getBoolean("complete") && !evidence.getBoolean("publicSequenceLinked")
                    && !evidence.getBoolean("javaLeanCorrespondenceChecked"),"full certification still blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(q,bad,"foreign source");
            bad=copy(report);declaration(bad).getJSONArray("source").getJSONObject(0).put("occurrence","foreign-owner");invalid(q,bad,"foreign owner");
            bad=copy(report);declaration(bad).getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");invalid(q,bad,"foreign literal declaration type");
            bad=copy(report);declaration(bad).getJSONArray("operations").getJSONObject(0).put("kind","delete");invalid(q,bad,"false delete in place of keep");
            bad=copy(report);bad.getJSONObject("certified").getJSONArray("selectedGlobalMapping").put(0,999);invalid(q,bad,"foreign mapping coordinate");
            bad=copy(report);bad.getJSONObject("certified").getJSONObject("selectedDeclarationPermutation").getJSONArray("left").put(0,999);invalid(q,bad,"unadmitted permutation");
        }
        var controls=new JSONArray();
        // Consumer snapshots, not invocations of the old regression mains.
        for(var row:List.of(
                new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27",""},
                new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22",""},
                new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20",""},
                new String[]{"7KzgkuDqY7Q4ZfQ4Z_inv14","inv14","19","[-1,0,-1,1]"},
                new String[]{"7TYSDPnXqtud3bjku_inv12","inv12","27","[-1,0,-1]"},
                new String[]{"83RWHQkLc3bdocWg4_inv12","inv12","15","[-1,-1,0]"},
                new String[]{"TA943B2hFMmbp96Pj_inv8","inv8","8","[-1,0]"},
                new String[]{"8dTfKqTs26w6fL87K_inv15","inv15","18","[-1,-1,0]"},
                new String[]{"2S5sD2BA5mvLdj46b_inv12","inv12","14","[0,-1,-1,-1]"},
                new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49",""})) {
            String folder=row[0].startsWith("xJnit")?"coursesOld":"classroom_fol";
            var control=prepare(Path.of("classified-data",folder,"both",row[0]+".als"),row[1]);
            var report=DistanceReplayDiagnostics.capture(control.left,control.right,control.exactLeft,control.exactRight);
            for(String arm:List.of("fast","certified")) {
                var replay=report.getJSONObject(arm);
                check(replay.getInt("distance")==Integer.parseInt(row[2]) && replay.getBoolean("matrixReplayChecked"),"prior family native endpoint "+row[0]+" "+arm);
                check(replay.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior paid/zero action linkage "+row[0]);
            }
            var mapping=report.getJSONObject("certified").getJSONArray("selectedGlobalMapping");
            if(!row[3].isEmpty())check(mapping.similar(new JSONArray(row[3])),"existing consumer map retained "+row[0]);
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])).put("certifiedMap",mapping));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls)
                .put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("ScopeBlockTieReplayTest: "+checks+" checks passed; original49 both directions/actions23; ten prior native-family controls; public certification BLOCKED");
    }
}
