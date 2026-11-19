package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

/** Native program retention is separate from the authenticated linking order. */
public final class RetainedPrefixCandidateTest {
    private static int checks, negatives;
    private static void check(boolean value,String why) {
        checks++;if(!value)throw new AssertionError(why);
    }
    private static JSONObject copy(JSONObject value) { return new JSONObject(value.toString()); }
    private static void invalid(Canonical.Prepared a,Canonical.Prepared b,JSONObject report,String why) {
        negatives++;
        check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    private static void pair(List<Canonical.Prepared> pair,Path out,String id)throws Exception {
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);
            var fast=CanonicalRepairTraces.fast(a,b);
            var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var replay=new JSONObject();
            QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),replay);
            var result=NativeOperationComparison.compare(a,b,fast,cert);var e=result.evidence();
            Files.writeString(out.resolve(id+"-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"native action correspondence: "+result.reason());
            check(Canonical.distance(a,b)==fast.distance(),"numeric/captured equality");
            check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("endpointCorrespondenceChecked")
                    &&e.getBoolean("fixedChoiceReplaysChecked"),"source and replay premises");
            var report=e.getJSONObject("diagnostic");var f=report.getJSONObject("fast");
            check(f.getInt("quantifierDistance")==1,"positive one-sided declaration payment");
            check(f.getJSONObject("selectedPaidDeclarationCorrespondence").isEmpty(),"zero keeps are not charged updates");
            check(!f.has("selectedGroupedDeclarationPermutation"),"original native program retained");
            check(f.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0)
                    .has("connectingPermutation"),"separate authenticated linking order");
            check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked"),"no public promotion");
            check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.MATCHED,"independent fixed replay");
            var bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0)
                    .getJSONObject("alphaMapping").put("_q0","_q0");invalid(a,b,bad,"substituted matrix map");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence")
                    .put("_q0","_q1");invalid(a,b,bad,"invented paid update");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("source").getJSONObject(0).put("admission","foreign owner");invalid(a,b,bad,"foreign source occurrence");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0)
                    .getJSONObject("receipt").put("cost",0);invalid(a,b,bad,"copied matrix scalar");
            bad=copy(report);var p=bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components")
                    .getJSONObject(0).getJSONObject("connectingPermutation").getJSONArray("source");
            p.put(0,p.getInt(1));invalid(a,b,bad,"nonbijective linking order");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("operations").getJSONObject(0).put("cost",0);invalid(a,b,bad,"unpaid one-sided slot");
        }
    }
    private static List<Canonical.Prepared> synthetic(boolean nested)throws Exception {
        String prefix=nested?"all u:Segment | all v:Hub | all lamp:Green |":"all u:Segment,v:Hub,lamp:Green |";
        String source="sig Segment {next:set Segment, lamps:set Lamp} sig Hub in Segment {} sig Lamp {} sig Green extends Lamp {} "
                +"pred pOriginal {"+prefix+" lamp in u.lamps and no v.lamps} "
                +"pred pTarget {all hub:Hub, segment:next.hub | some segment.lamps & Green}";
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,source);
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("pOriginal","pTarget"),module);visitor.visit(new ModelUnit(null,module),null);
        return List.of(Canonical.prepare(visitor.getForest().get(visitor.getForestId("pOriginal"))),
                Canonical.prepare(visitor.getForest().get(visitor.getForestId("pTarget"))));
    }
    public static void main(String[] args)throws Exception {
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));Path out=Path.of(args[1]);Files.createDirectory(out);
        for(Object raw:manifest.getJSONArray("cases")) {
            var c=(JSONObject)raw;if(!c.getString("role").equals("observed-failure"))continue;
            var identity=c.getJSONObject("identity");pair(LocalOwnerTraceTest.prepare(identity),out,identity.getString("id"));
        }
        pair(synthetic(false),out,"renamed");pair(synthetic(true),out,"nested");
        System.out.println("RetainedPrefixCandidateTest: "+checks+" checks, "+negatives+" negative controls");
    }
}
