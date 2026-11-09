package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import parser.ast.nodes.ModelUnit;

/** Bounded original-source orbit admission and native operation regression. */
public final class GroupedPaidOrbitReplayTest {
    private static int checks;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private record Pair(Canonical.Prepared a,Canonical.Prepared b,CanonicalAlloyPipeline.Prepared ca,CanonicalAlloyPipeline.Prepared cb) {
        Pair reverse(){return new Pair(b,a,cb,ca);}
    }
    private static Pair prepare(String text,String name)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name,name+"c"),module);v.visit(new ModelUnit(null,module),null);
        var a=Canonical.prepare(v.getForest().get(v.getForestId(name)));var b=Canonical.prepare(v.getForest().get(v.getForestId(name+"c")));
        return new Pair(a,b,CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
    }
    private static Pair source(String stem,String name)throws Exception {
        return prepare(Files.readString(Path.of("classified-data",stem.startsWith("xJnit")?"coursesOld":"classroom_fol","both",stem+".als")),name);
    }
    private static Object call(String method,Class<?>[] ts,Object...args) {
        try {
            var m=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit").getDeclaredMethod(method,ts);m.setAccessible(true);return m.invoke(null,args);
        } catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private static List<int[]> orders(NormalForm source,NormalForm view,List<QuantiVar> order) {
        return (List<int[]>)call("orders",new Class[]{NormalForm.class,NormalForm.class,List.class},source,view,order);
    }
    private static void reject(Runnable action,String why) {
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);
    }
    private static void field(Object target,String name,Object value) {
        try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static NormalForm view(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void invalid(Pair p,JSONObject j,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,j).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    public static void main(String[] args)throws Exception {
        Path out=args.length==0?Files.createTempDirectory("grouped-orbit-"):Path.of(args[0]);Files.createDirectories(out);
        var p=source("ft2Q8x9bQa59gFBR4_inv14","inv14");
        var original=p.a.normalizedForms().get(0);var qs=original.getMatrixQuantiVars();
        check(qs.size()==4&&original.getLocalQuantiVars().isEmpty(),"actual closed four-slot prefix");
        check(qs.get(0).getBindingPath().equals(qs.get(1).getBindingPath())&&qs.get(0).getQuantifier()==QuantiVar.Quantifier.ALL&&qs.get(1).getQuantifier()==QuantiVar.Quantifier.ALL,"actual same grouped ALL owner");
        var admitted=orders(original,view(p.a),qs);
        check(admitted.size()==2&&Arrays.equals(admitted.get(1),new int[]{1,0,2,3}),"exact certified identity plus grouped swap, other kinds fixed");
        for(int d=0;d<2;d++) {
            var q=d==0?p:p.reverse();var result=NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb);var e=result.evidence();
            Files.writeString(out.resolve("after-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"typed ordered native actions: "+e.opt("firstDifference"));
            check(result.bindScanCosts(new int[]{17,0,3,14},new int[]{17,0,3,14}).status()==NativeOperationComparison.Status.MATCHED,"own attaining17 decomposition");
            check(e.getInt("fastActionCount")==22&&e.getInt("certifiedActionCount")==22&&e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all22 actions including exchange, zero keep and deletes/inserts");
            check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("fixedChoiceReplaysChecked")&&e.getBoolean("endpointCorrespondenceChecked"),"actual original certificate/type/owner checks");
            var report=e.getJSONObject("diagnostic");var fast=report.getJSONObject("fast");
            check(NativeOperationComparison.compareCaptured(q.a,q.b,report).status()==NativeOperationComparison.Status.MATCHED,"independent fixed-choice replayer");
            var permutation=fast.getJSONObject("selectedGroupedDeclarationPermutation");
            check(permutation.getJSONArray("source").similar(new JSONArray(d==0?new int[]{1,0,2,3}:new int[]{0,1}))&&permutation.getJSONArray("target").similar(new JSONArray(d==0?new int[]{0,1}:new int[]{1,0,2,3})),"source versus target exchange direction");
            check(fast.getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(d==0?Map.of("_q1","_q0"):Map.of("_q0","_q1"))),"paid path transported with original occurrence permutation");
            check(fast.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(Map.of("_q0","_q1","_q1","_q0"))),"selected matrix uses the same transported correspondence");
            check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"public certification blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(q,bad,"foreign source");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q0");invalid(q,bad,"bad paid map");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedGroupedDeclarationPermutation").getJSONArray(d==0?"source":"target").put(0,0);invalid(q,bad,"selected order must equal emitted order");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("connectingPermutation").getJSONArray(d==0?"source":"target").put(0,0);invalid(q,bad,"forged nonbijection certificate claim");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(1,"ForeignType");invalid(q,bad,"source type substitution");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",13);invalid(q,bad,"wrong per-action total");
        }
        var types=new Class[]{NormalForm.class,NormalForm.class,List.class,int[].class};
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{0,0,2,3}),"nonbijection rejected");
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{2,1,0,3}),"ALL/SOME and carrier crossing rejected");
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{0,1,3,2}),"different independent domain slots not grouped exchange");
        var foreign=source("ft2Q8x9bQa59gFBR4_inv14","inv14");
        reject(()->orders(original,view(foreign.a),qs),"foreign source/comparison ledger");
        var fakeOrder=new ArrayList<>(qs);fakeOrder.set(0,foreign.a.normalizedForms().get(0).getMatrixQuantiVars().get(0));
        reject(()->orders(original,view(p.a),fakeOrder),"foreign typed occurrence in order");
        var sameId=new QuantiVar(qs.get(0).getId(),qs.get(0).getName(),qs.get(0).getTypeName());
        for(var f:QuantiVar.class.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);f.set(sameId,f.get(qs.get(0)));}
        var badView=view(p.a);var list=new ArrayList<>(qs);list.set(0,sameId);field(badView,"matrixQuantiVars",List.copyOf(list));
        check(sameId.equals(qs.get(0))&&sameId!=qs.get(0),"same numeric id is not source authority");
        reject(()->orders(original,badView,qs),"same-id foreign comparison binding");
        for(String field:List.of("params","inheritedQuantiVars")) {
            var v=view(p.a);field(v,field,List.of(qs.get(0)));
            check(orders(original,v,qs).size()==1,"no new orbit for "+field);
        }
        var temporal=view(p.a);field(temporal,"temporalOp",NormalForm.TemporalOp.ALWAYS);
        check(orders(original,temporal,qs).size()==1,"temporal owner not widened");
        var budgetClass=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit$Budget");
        var ctor=budgetClass.getDeclaredConstructor(long.class);ctor.setAccessible(true);var budget=ctor.newInstance(2L);
        var spend=budgetClass.getDeclaredMethod("spend");spend.setAccessible(true);spend.invoke(budget);spend.invoke(budget);
        boolean exhausted=false;try{spend.invoke(budget);}catch(InvocationTargetException ex){exhausted=ex.getCause() instanceof IllegalStateException;}
        check(exhausted,"finite shared product budget fails before another evaluation");
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"Wq9Au27ys6iRip6CD_inv14","inv14","36"},new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"85tWoTeBhoEDhCKmw_inv14","inv14","37"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"8dTfKqTs26w6fL87K_inv15","inv15","18"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"})) {
            var control=source(row[0],row[1]);
            for(int d=0;d<2;d++) {
                var q=d==0?control:control.reverse();var report=DistanceReplayDiagnostics.capture(q.a,q.b,q.ca,q.cb);
                for(String arm:List.of("fast","certified"))check(report.getJSONObject(arm).getInt("distance")==Integer.parseInt(row[2])&&report.getJSONObject(arm).getBoolean("matrixReplayChecked")&&report.getJSONObject(arm).getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior scalar and selected declaration/body replay "+row[0]);
                if(row[0].startsWith("Wq")||row[0].startsWith("SASE")||row[0].startsWith("85t")||row[0].startsWith("JoNX"))check(NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb).status()==NativeOperationComparison.Status.MATCHED,"prior full native action slice "+row[0]);
            }
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("GroupedPaidOrbitReplayTest: "+checks+" checks passed; actual17/actions22 both directions; source-certified grouped swaps");
    }
}
