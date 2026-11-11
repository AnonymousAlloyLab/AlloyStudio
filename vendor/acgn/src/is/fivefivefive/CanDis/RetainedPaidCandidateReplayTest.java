package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import parser.ast.nodes.ModelUnit;

/** Candidate coverage, not receipt sorting: original typed endpoint replay. */
public final class RetainedPaidCandidateReplayTest {
    private static int checks;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private record Pair(Canonical.Prepared a,Canonical.Prepared b,CanonicalAlloyPipeline.Prepared ca,CanonicalAlloyPipeline.Prepared cb) {
        Pair reverse(){return new Pair(b,a,cb,ca);}
    }
    private static Pair prepare(String text,String name)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name,name+"c"),module);
        visitor.visit(new ModelUnit(null,module),null);
        var a=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name)));
        var b=Canonical.prepare(visitor.getForest().get(visitor.getForestId(name+"c")));
        return new Pair(a,b,CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
    }
    private static Pair source(String stem,String name)throws Exception {
        return prepare(Files.readString(Path.of("classified-data",stem.startsWith("xJnit")?"coursesOld":"classroom_fol","both",stem+".als")),name);
    }
    private static boolean supports(String method,List<NormalForm> a,List<NormalForm> b) {
        try {
            var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod(method,List.class,List.class);
            m.setAccessible(true);return (boolean)m.invoke(null,a,b);
        } catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static List<NormalForm> views(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms();}
    private static List<EGraphNode> owners(NormalForm f){
        var result=new ArrayList<EGraphNode>();var pending=new ArrayDeque<EGraphNode>();pending.add(f.getMatrixEGraph());
        while(!pending.isEmpty()) {
            var n=pending.removeFirst();
            if(n.getChildren().size()==2&&n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL)result.add(n);
            pending.addAll(n.getChildren());
        }
        return result;
    }
    private static void field(Object target,String name,Object value){
        try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void corrupt(Pair p,Consumer<NormalForm> edit,String why){
        var a=views(p.a);var b=views(p.b);edit.accept(a.get(0));
        check(!supports("supportsSupplementalPair",a,b)&&!supports("supportsSupplementalPair",b,a),why);
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void invalid(Pair p,JSONObject j,String why){
        check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    public static void main(String[] args)throws Exception {
        Path out=args.length==0?Files.createTempDirectory("retained-candidate-"):Path.of(args[0]);Files.createDirectories(out);
        var p=source("Wq9Au27ys6iRip6CD_inv14","inv14");
        for(int direction=0;direction<2;direction++) {
            var q=direction==0?p:p.reverse();var a=views(q.a);var b=views(q.b);
            check(!supports("supportsPair",a,b),"exclusive replacement route remains forbidden");
            check(supports("supportsSupplementalPair",a,b),"additional actual paid candidate admitted");
            var result=NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb);var e=result.evidence();
            Files.writeString(out.resolve("after-"+direction+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"typed ordered actions match: "+e.opt("firstDifference"));
            check(result.bindScanCosts(new int[]{36,0,1,35},new int[]{36,0,1,35}).status()==NativeOperationComparison.Status.MATCHED,"original scalar components");
            check(e.getInt("fastActionCount")==20&&e.getInt("certifiedActionCount")==20,"actual20 actions, no suppressed action");
            check(e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all ordered actions including zero keeps");
            check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"source certificate/type/binder premise replay");
            var report=e.getJSONObject("diagnostic");var fast=report.getJSONObject("fast");
            check(NativeOperationComparison.compareCaptured(q.a,q.b,report).status()==NativeOperationComparison.Status.MATCHED,"independent fixed-choice replay on original endpoints");
            check(fast.getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(Map.of("_q0","_q0"))),"actual paid first diagonal");
            check(fast.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(Map.of("_q0","_q0","_q1","_q1"))),"selected complete matrix mapping");
            check(!fast.getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).has("connectingPermutation"),"native emitter requires no exchange for this attaining path");
            for(int i=0;i<2;i++) {
                var action=e.getJSONArray("fastActions").getJSONObject(i);
                check(action.getString("stage").equals("declaration")&&action.getString("kind").equals(i==0?"update":"keep")&&action.getInt("cost")==1-i,"diagonal update then zero keep");
            }
            check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public proof remains blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(q,bad,"foreign endpoints");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q1");invalid(q,bad,"forged paid link");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").put("_q0","_q1");invalid(q,bad,"matrix binding substitution");
            bad=copy(report);bad.getJSONObject("certified").getJSONArray("selectedGlobalMapping").put(0,999);invalid(q,bad,"foreign certified coordinate");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");invalid(q,bad,"declaration type substitution");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",34);invalid(q,bad,"wrong matrix charge");
        }
        check(!supports("supportsSupplementalPair",views(p.a),views(p.a)),"no local/local widening");
        corrupt(p,f->field(owners(f).get(1),"sourceOccurrenceLineage",-1L),"foreign retained owner");
        corrupt(p,f->field(f,"matrixEGraphRoot",owners(f).get(1)),"wrong parent edge");
        corrupt(p,f->field(owners(f).get(1).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign local declaration");
        corrupt(p,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain occurrence");
        corrupt(p,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"capture of original outer binder");
        corrupt(p,f->field(owners(f).get(1),"opcode",EGraphNode.Opcode.LONE),"unsupported local kind");
        corrupt(p,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"set multiplicity");
        var foreign=source("Wq9Au27ys6iRip6CD_inv14","inv14");
        corrupt(p,f->field(f,"certificationMatrixEGraphRoot",foreign.a.normalizedForms().get(0).getCertificationMatrixEGraph()),"foreign original source authority");
        corrupt(p,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"exactAlloyType",owners(views(foreign.a).get(0)).get(1).getChildren().get(0).getChildren().get(1).getExactAlloyType()),"foreign parser module/type");
        corrupt(p,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal owner boundary");
        corrupt(p,f->field(f,"params",List.of(f.getMatrixQuantiVars().get(0))),"parameter boundary");
        corrupt(p,f->field(f,"inheritedQuantiVars",List.of(f.getMatrixQuantiVars().get(0))),"imported owner boundary");
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"85tWoTeBhoEDhCKmw_inv14","inv14","37"},new String[]{"4g8G2qHXAbDJFACM7_inv15","inv15","49"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"})) {
            var control=source(row[0],row[1]);
            for(int d=0;d<2;d++) {
                var q=d==0?control:control.reverse();var report=DistanceReplayDiagnostics.capture(q.a,q.b,q.ca,q.cb);
                for(String arm:List.of("fast","certified"))check(report.getJSONObject(arm).getInt("distance")==Integer.parseInt(row[2])&&report.getJSONObject(arm).getBoolean("matrixReplayChecked")&&report.getJSONObject(arm).getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior cost and native linkage "+row[0]);
                if(row[0].startsWith("SASE")||row[0].startsWith("85t")||row[0].startsWith("4g8")||row[0].startsWith("JoNX"))check(NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb).status()==NativeOperationComparison.Status.MATCHED,"prior ordered native actions "+row[0]);
                if(row[0].startsWith("SASE")) {
                    check(supports("supportsSupplementalPair",views(q.a),views(q.b)),"SASE also has extra candidate");
                    check(!report.getJSONObject("fast").has("selectedPaidDeclarationCorrespondence"),"cheaper source exchange family not discarded");
                    check(report.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(d==0?Map.of("_q0","_q1"):Map.of("_q1","_q0"))),"old SASE selected map retained");
                    Files.writeString(out.resolve("sase-"+d+".json"),report.toString(2),StandardOpenOption.CREATE_NEW);
                }
            }
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("RetainedPaidCandidateReplayTest: "+checks+" checks passed;36/actions20 both directions; cheaper SASE exchange preserved");
    }
}
