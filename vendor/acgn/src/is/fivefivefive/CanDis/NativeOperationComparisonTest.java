package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.metric.PhaseReplay;
import parser.ast.nodes.ModelUnit;

public final class NativeOperationComparisonTest {
    private static int checks;
    private static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    record Pair(Canonical.Prepared left,Canonical.Prepared right,CanonicalAlloyPipeline.Prepared exactLeft,CanonicalAlloyPipeline.Prepared exactRight) { }
    static Pair pair(String text,String left,String right) throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var p=new ArrayList<Canonical.Prepared>();
        for(var name:List.of(left,right)) {
            var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);visitor.visit(new ModelUnit(null,module),null);
            p.add(Canonical.prepare(visitor.getForest().get(visitor.getForestId(name))));
        }
        return new Pair(p.get(0),p.get(1),CanonicalAlloyPipeline.prepare(p.get(0)),CanonicalAlloyPipeline.prepare(p.get(1)));
    }
    static NativeOperationComparison.Result compare(Pair p) {return NativeOperationComparison.compare(p.left,p.right,p.exactLeft,p.exactRight);}
    private static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    static JSONObject reorder(JSONObject original) {
        var report=copy(original);var fast=report.getJSONObject("fast");var root=fast.getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").getJSONObject("derivation");
        if(!reorderNode(root))throw new AssertionError("No independent unordered action order in fixture");
        fast.put("orderedDiagnosticActions",PhaseReplay.diagnosticOrder(fast.getJSONObject("temporalSlice"),fast.getJSONObject("declarationSlice"),fast.getJSONArray("phases")));
        return report;
    }
    private static boolean reorderNode(JSONObject node) {
        if(!node.getString("kind").equals("node"))return false;
        var children=node.getJSONArray("children");
        if(node.getBoolean("unordered") && children.length()>1) {
            var reversed=new JSONArray();for(int i=children.length()-1;i>=0;i--)reversed.put(children.get(i));node.put("children",reversed);return true;
        }
        for(Object item:children)if(reorderNode(((JSONObject)item).getJSONObject("edit")))return true;
        return false;
    }
    static Pair simple() throws Exception {return pair("sig A { r,s:set A } pred inv { some r and some s } pred invc { no r and no s }","inv","invc");}
    private static boolean groupedOwner(is.fivefivefive.CanDis.core.EGraphNode n) {
        var declarations=n.getChildren().stream().filter(c->c.getOpcode()==is.fivefivefive.CanDis.core.EGraphNode.Opcode.GENERICRELDECL).toList();
        if(declarations.size()>1 || declarations.stream().anyMatch(d->d.getChildren().size()>2))return true;
        return n.getChildren().stream().anyMatch(NativeOperationComparisonTest::groupedOwner);
    }
    static NativeOperationComparison.Result differing(Pair p,NativeOperationComparison.Result captured) {
        if(captured.status()==NativeOperationComparison.Status.MISMATCH)return captured;
        return NativeOperationComparison.compareCaptured(p.left,p.right,reorder(captured.evidence().getJSONObject("diagnostic")));
    }
    private static void invalid(Pair p,JSONObject report,String why){
        var result=NativeOperationComparison.compareCaptured(p.left,p.right,report);
        check(result.status()==NativeOperationComparison.Status.INVALID_REPLAY,why+": "+result.evidence());
    }
    public static void main(String[] args)throws Exception {
        Path root=args.length==0?Files.createTempDirectory("native-operation-comparison-"):Path.of(args[0]);Files.createDirectories(root);
        var evidence=new JSONArray();
        for(var witness:List.of(new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"})) {
            String text=Files.readString(Path.of("classified-data/classroom_fol/both",witness[0]+".als"));var p=pair(text,witness[1],witness[1]+"c");
            for(int direction=0;direction<2;direction++) {
                var q=direction==0?p:new Pair(p.right,p.left,p.exactRight,p.exactLeft);var result=compare(q);
                Files.writeString(root.resolve(witness[0]+"-"+direction+".json"),result.evidence().toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"natural source typed replay/operation equality "+witness[0]+" "+direction+": "+result.evidence());
                var report=result.evidence().getJSONObject("diagnostic");int total=Integer.parseInt(witness[2]);
                check(report.getJSONObject("fast").getInt("distance")==total && report.getJSONObject("certified").getInt("distance")==total,"natural scalar unchanged");
                check(NativeOperationComparison.compareCaptured(q.left,q.right,report).status()==NativeOperationComparison.Status.MATCHED,"independent captured replay");
                var alias=copy(report);var immutable=NativeOperationComparison.compareCaptured(q.left,q.right,alias);
                alias.getJSONObject("fast").put("distance",-1);
                check(immutable.evidence().getJSONObject("diagnostic").getJSONObject("fast").getInt("distance")==total,"checked result retains immutable receipt snapshot");
                var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign-source");invalid(q,bad,"source substitution");
                bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("foreign","_q0");invalid(q,bad,"binder corruption");
                bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0)
                        .getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"foreign-type");invalid(q,bad,"type corruption");
                bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",-1);invalid(q,bad,"matrix charge corruption");
                bad=copy(report);bad.getJSONObject("certified").getJSONObject("selectedDeclarationPermutation").getJSONArray("left").put(0,999);invalid(q,bad,"source permutation corruption");
                bad=copy(report);bad.getJSONObject("fast").put("declarationSlice",new JSONObject().put("status","BLOCKED"));
                check(NativeOperationComparison.compareCaptured(q.left,q.right,bad).status()==NativeOperationComparison.Status.UNCHECKABLE,"missing receipt not matched or invented mismatch");
                int[] drift={total+1,0,report.getJSONObject("fast").getInt("quantifierDistance"),report.getJSONObject("fast").getInt("matrixDistance")+1};
                check(result.bindScanCosts(drift,drift).status()==NativeOperationComparison.Status.INVALID_REPLAY,"both recaptured arms drift from exact scan arrays");
                evidence.put(new JSONObject().put("source",witness[0]).put("direction",direction).put("status",result.status().name()));
            }
        }
        var temporal=pair("sig A {} pred inv { always some A } pred invc { eventually no A }","inv","invc");
        var temporalResult=compare(temporal);
        check(temporalResult.status()==NativeOperationComparison.Status.MATCHED
                &&temporalResult.evidence().getBoolean("orderedBlockEffectsEqual")
                &&temporalResult.evidence().getBoolean("allNativeEntriesRetained"),"source-linked temporal native blocks including zero operations");
        var grouped=pair("sig A { r:set A } pred inv { all x:A | some x.r implies (one y,z:A | y in x.r and z in y.r) } pred invc { some A }","inv","invc");
        check(groupedOwner(grouped.left.normalizedForms().get(0).getCertificationMatrixEGraph()),"negative fixture actually retains grouped local source owner");
        var groupedResult=compare(grouped);
        Files.writeString(root.resolve("grouped-boundary.json"),groupedResult.evidence().toString(2),StandardOpenOption.CREATE_NEW);
        check(groupedResult.status()==NativeOperationComparison.Status.MATCHED
                && groupedResult.evidence().getBoolean("sourceCertificatesChecked")
                && groupedResult.evidence().getJSONArray("fastActions").similar(groupedResult.evidence().getJSONArray("certifiedActions")),
                "retained grouped owner uses its actual selected representative: "+groupedResult.evidence());
        var multiplicity=pair("sig A { r:set A } pred inv { all x:set A, y:A | y in x.r } pred invc { some A }","inv","invc");
        var mixedPrefix=multiplicity.left.normalizedForms().get(0).getMatrixQuantiVars();
        check(mixedPrefix.size()==2 && mixedPrefix.get(0).getQuantifier()==mixedPrefix.get(1).getQuantifier()
                && mixedPrefix.get(0).getCardinality()!=mixedPrefix.get(1).getCardinality(),"negative fixture actually retains mixed-multiplicity prefix run");
        var multiplicityResult=compare(multiplicity);
        Files.writeString(root.resolve("multiplicity-boundary.json"),multiplicityResult.evidence().toString(2),StandardOpenOption.CREATE_NEW);
        check(multiplicityResult.status()==NativeOperationComparison.Status.UNCHECKABLE
                && multiplicityResult.reason().equals("MIXED_MULTIPLICITY_ORDER_UNSUPPORTED"),"unsupported multiplicity ordering is not a replay defect");
        try { NativeOperationComparison.uniqueOccurrence(List.of("same","same"),"same",Set.of());throw new AssertionError("Ambiguous occurrence matched"); }
        catch(RuntimeException expected){check(expected.getMessage().equals("AMBIGUOUS_OCCURRENCE_BIJECTION"),"ambiguity has explicit unchecked reason");}
        check(NativeOperationComparison.uniqueOccurrence(List.of("other","same"),"same",Set.of())==1,"unique occurrence bridge");
        var p=simple();var captured=compare(p);
        Files.writeString(root.resolve("reduced-captured.json"),captured.evidence().toString(2),StandardOpenOption.CREATE_NEW);
        check(captured.status()==NativeOperationComparison.Status.MATCHED || captured.status()==NativeOperationComparison.Status.MISMATCH,"reduced fixture fully checked");
        var different=differing(p,captured);
        Files.writeString(root.resolve("different-valid-actions.json"),different.evidence().toString(2),StandardOpenOption.CREATE_NEW);
        check(different.status()==NativeOperationComparison.Status.MISMATCH,"equal costs but distinct independently valid action orders");
        check(different.evidence().has("firstDifference") && different.evidence().getBoolean("fixedChoiceReplaysChecked"),"first action discrepancy after independent replay");
        evidence.put(new JSONObject().put("reducedCapturedStatus",captured.status().name()).put("syntheticReorderingUsed",captured.status()!=NativeOperationComparison.Status.MISMATCH));
        Files.writeString(root.resolve("checks.json"),new JSONObject().put("checks",checks).put("cases",evidence).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("NativeOperationComparisonTest: "+checks+" checks passed; real reduced capture="+captured.status());
    }
}
