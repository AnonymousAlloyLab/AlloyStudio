package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.*;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

/** Partial-map extension, not a new alpha candidate or source equivalence. */
public final class UnusedDeclarationCompletionTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    private static void reject(Runnable r,String why) {
        checks++; try { r.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static JSONObject copy(JSONObject x) { return new JSONObject(x.toString()); }
    private static Canonical.Prepared prepare(CompModule m,String name) {
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m); v.visit(new ModelUnit(null,m),null);
        return Canonical.prepare(v.getForest().get(v.getForestId(name)));
    }
    private static void units() {
        var a=List.of(new DeclarationReplay.Atom("source/owner/0",List.of("ALL","A","ONE","0"),"source-domain"));
        var b=List.of(new DeclarationReplay.Atom("target/owner/0",List.of("ALL","A","ONE","0"),"target-domain"));
        for(boolean sourceSeen:new boolean[]{false,true}) for(boolean targetSeen:new boolean[]{false,true}) {
            Set<String> l=sourceSeen?Set.of("a"):Set.of(),r=targetSeen?Set.of("b"):Set.of();
            var joint=new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),l,r,true);
            if(sourceSeen && targetSeen) {
                reject(()->DeclarationReplay.capture(a,b,new int[][]{{0,1},{1,0}},(i,j)->joint.allows(i,j,0)),"observed/observed cannot complete");
                continue;
            }
            var receipt=DeclarationReplay.capture(a,b,new int[][]{{0,1},{1,0}},(i,j)->joint.allows(i,j,0));
            var completion=joint.completeAndVerify(a,b,receipt);
            check(completion.getJSONObject("completedMapping").getString("a").equals("b"),"explicit fixed completed map");
            joint.verifyCompletion(a,b,receipt,completion);
            var bad=copy(completion); bad.getJSONArray("absencePremises").getJSONObject(0).put("sourceAbsent",sourceSeen);
            reject(()->joint.verifyCompletion(a,b,receipt,bad),"forged absence premise");
            var map=copy(completion); map.getJSONObject("completedMapping").put("a","foreign");
            reject(()->joint.verifyCompletion(a,b,receipt,map),"substituted completed mapping");
            var foreign=copy(receipt); foreign.getJSONArray("source").getJSONObject(0).put("occurrence","other-owner");
            reject(()->joint.completeAndVerify(a,b,foreign),"foreign owner");
        }
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of(),Set.of("b")).allows(0,0,0),
                "absence alone without scope admission is insufficient");
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("a","c"),Set.of(),Set.of("b"),true).allows(0,0,0),"cannot rebind source");
        check(!new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of("c","b"),Set.of(),Set.of("b"),true).allows(0,0,0),"cannot steal target");
        var changed=List.of(new DeclarationReplay.Atom("target/owner/0",List.of("ALL","B","ONE","0"),"other-domain"));
        var paid=DeclarationReplay.capture(a,changed,new int[][]{{0,1},{1,1}},(i,j)->true);
        reject(()->new DeclarationMatrixLink(List.of("a"),List.of("b"),Map.of(),Set.of(),Set.of(),true)
                .completeAndVerify(a,changed,paid),"nonzero diagonal needs paid admission, not absence");
    }
    private static JSONObject affected() throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(BoundVariableShorterReplayTest.SOURCE));
        var p=prepare(m,"inv14"); var q=prepare(m,"inv14c");
        var out=new JSONObject();
        for(boolean reverse:new boolean[]{false,true}) {
            var a=reverse?q:p; var b=reverse?p:q;
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            fast.replay(fast.json());certified.replay(certified.json());
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"actual current original-coordinate actions");
            var report=comparison.evidence().getJSONObject("diagnostic");
            check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.MATCHED,"independent endpoint replay");
            for(String name:List.of("fast","certified")) {
                var arm=report.getJSONObject(name);
                // The frozen pre-patch build already has two live declarations,
                // cost 22 = 1 + 21, and no absent-slot completion on this source.
                check(arm.getInt("distance")==22 && arm.getInt("quantifierDistance")==1
                        && arm.getInt("matrixDistance")==21 && arm.getBoolean("numericalCostCoverage"),"current baseline components "+name);
                var receipt=arm.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(name.equals("fast")?0:1).getJSONObject("receipt");
                check(receipt.getJSONArray("source").length()==2 && receipt.getJSONArray("target").length()==2
                        && !receipt.has("matrixCorrespondenceCompletion"),"no orphan slot or fabricated absence receipt");
                check(arm.getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"all declaration links checked");
                for(String mode:List.of("owner","type","payment","mapping","operation")) {
                    var bad=copy(report);var data=bad.getJSONObject(name);
                    var program=data.getJSONArray("nativeDeclarationPrograms").getJSONObject(name.equals("fast")?0:1);
                    switch(mode) {
                        case "owner" -> program.getJSONArray("source").getJSONObject(0).put("occurrence","foreign");
                        case "type" -> program.getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");
                        case "payment" -> data.put("distance",23);
                        case "mapping" -> data.getJSONArray("phases").getJSONObject(0).put("alphaMapping",new JSONArray());
                        case "operation" -> program.getJSONArray("operations").remove(0);
                    }
                    check(NativeOperationComparison.compareCaptured(a,b,fast,certified,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,
                            "current native evidence rejects "+name+" "+mode);
                }
                check(!report.getBoolean("complete") && !report.getBoolean("publicSequenceLinked"),"public proof remains blocked");
            }
            out.put(reverse?"reverse":"forward",report);
        }
        return out;
    }
    private static void consumers(String file,String left,String right,int expected) throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,Files.readString(Path.of("classified-data",file)));
        var a=prepare(m,left); var b=prepare(m,right);
        for(boolean reverse:new boolean[]{false,true}) {
            var report=DistanceReplayDiagnostics.capture(reverse?b:a,reverse?a:b);
            for(String name:List.of("fast","certified")) {
                var arm=report.getJSONObject(name);
                check(arm.getInt("distance")==expected && arm.getBoolean("numericalCostCoverage"),"consumer scalar and declaration components "+file+" "+name);
                check(arm.getJSONObject("declarationSlice").has("components"),"consumer components remain emitted");
            }
        }
    }
    private static void temporalGuard() throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,"var sig T {} pred temporal { all t:T | always t in T }");
        var p=prepare(m,"temporal");
        var fast=CanonicalDistance.prepare(p.normalizedForms());
        var method=CanonicalDistance.class.getDeclaredMethod("closedPrefix",CanonicalDistance.Prepared.class); method.setAccessible(true);
        check(!(Boolean)method.invoke(null,fast),"temporal/inherited Fast owner is outside absence admission");
        var evidence=TheoryAlloyAdapter.adapt(p.normalizedForms(),p.semanticProfile());
        var view=RepairProjection.project(evidence,p.normalizedForms());
        var certified=QuotientRepairDistance.class.getDeclaredMethod("closedPrefix",RepairView.class); certified.setAccessible(true);
        check(!(Boolean)certified.invoke(null,view),"temporal/inherited certified owner is outside absence admission");
    }
    public static void main(String[] args) throws Exception {
        boolean withConsumer=args.length==2 && args[0].equals("--with-bound-consumer");
        if(args.length>1 && !withConsumer) throw new IllegalArgumentException("[OUTPUT] or --with-bound-consumer OUTPUT");
        units(); temporalGuard(); var result=affected();
        // Development-only validation. The combined runner has its own single entry.
        if(withConsumer) BoundVariableShorterReplayTest.main(new String[0]);
        consumers("coursesOld/both/xJnitBpFv9K74WNvf_inv14.als","inv14","inv14c",49);
        consumers("classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12.als","inv12","inv12c",22);
        consumers("classroom_fol/both/2KmASqDBmqEueukiz_inv5.als","inv5","inv5c",10);
        result.put("checks",checks).put("complete",false);
        if(args.length>0) Files.writeString(Path.of(args[withConsumer?1:0]),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("UnusedDeclarationCompletionTest: "+checks+" checks passed; absence unit controls and current native22 both directions; public BLOCKED");
    }
}
