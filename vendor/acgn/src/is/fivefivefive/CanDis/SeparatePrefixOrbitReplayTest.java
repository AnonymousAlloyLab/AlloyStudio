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

public final class SeparatePrefixOrbitReplayTest {
    private static int checks;
    private static void check(boolean p,String why){checks++;if(!p)throw new AssertionError(why);}
    private record Pair(Canonical.Prepared a,Canonical.Prepared b,CanonicalAlloyPipeline.Prepared ca,CanonicalAlloyPipeline.Prepared cb){Pair reverse(){return new Pair(b,a,cb,ca);}}
    private static List<Canonical.Prepared> prepare(String text,String...names)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var v=new MASGVisitor(new GlobalVariables(),Set.of(names),module);v.visit(new ModelUnit(null,module),null);
        var result=new ArrayList<Canonical.Prepared>();for(var name:names)result.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));return result;
    }
    private static Pair source(String stem,String name)throws Exception {
        var forms=prepare(Files.readString(Path.of("classified-data",stem.startsWith("xJnit")?"coursesOld":"classroom_fol","both",stem+".als")),name,name+"c");
        return new Pair(forms.get(0),forms.get(1),CanonicalAlloyPipeline.prepare(forms.get(0)),CanonicalAlloyPipeline.prepare(forms.get(1)));
    }
    private static Object call(String name,Class<?>[] types,Object...args) {
        try{var m=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit").getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}
        catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e.getCause());}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    @SuppressWarnings("unchecked") private static List<int[]> orders(NormalForm original,NormalForm view,List<QuantiVar> qs){return (List<int[]>)call("orders",new Class[]{NormalForm.class,NormalForm.class,List.class},original,view,qs);}
    private static NormalForm original(Canonical.Prepared p){return p.normalizedForms().get(0);}
    private static NormalForm view(Canonical.Prepared p){return CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);}
    private static void reject(Runnable r,String why){checks++;try{r.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);}
    private static void field(Object o,String n,Object value){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.set(o,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void invalid(Pair p,JSONObject r,String why){check(NativeOperationComparison.compareCaptured(p.a,p.b,r).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);}
    public static void main(String[] args)throws Exception {
        Path out=args.length==0?Files.createTempDirectory("separate-prefix-"):Path.of(args[0]);Files.createDirectories(out);
        var p=source("hJWX73Rdw3RziF3uu_inv14","inv14");var original=original(p.a);var qs=original.getMatrixQuantiVars();
        check(qs.size()==4&&original.getLocalQuantiVars().isEmpty(),"closed four-slot source");
        check(!qs.get(0).getBindingPath().equals(qs.get(1).getBindingPath()),"actual distinct declaration occurrence paths");
        check(orders(original,view(p.a),qs).size()==2,"identity plus actual separately-owned certified transposition");
        for(int d=0;d<2;d++) {
            var q=d==0?p:p.reverse();var result=NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb);var e=result.evidence();
            Files.writeString(out.resolve("after-"+d+".json"),e.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"ordered native correspondence: "+e.opt("firstDifference"));
            check(result.bindScanCosts(new int[]{17,0,3,14},new int[]{17,0,3,14}).status()==NativeOperationComparison.Status.MATCHED,"own17 attaining costs");
            check(e.getInt("fastActionCount")==22&&e.getInt("certifiedActionCount")==22&&e.getJSONArray("fastActions").similar(e.getJSONArray("certifiedActions")),"all22 ordered actions incl zero permutation, keeps and deleted/inserted owners");
            check(e.getBoolean("sourceCertificatesChecked")&&e.getBoolean("endpointCorrespondenceChecked")&&e.getBoolean("fixedChoiceReplaysChecked"),"actual source/type/owner/certificate checks");
            var report=e.getJSONObject("diagnostic");var fast=report.getJSONObject("fast");
            check(NativeOperationComparison.compareCaptured(q.a,q.b,report).status()==NativeOperationComparison.Status.MATCHED,"independent fixed-choice replay");
            var permutation=fast.getJSONObject("selectedGroupedDeclarationPermutation");
            check(permutation.getJSONArray("source").similar(new JSONArray(d==0?new int[]{1,0,2,3}:new int[]{0,1}))&&permutation.getJSONArray("target").similar(new JSONArray(d==0?new int[]{0,1}:new int[]{1,0,2,3})),"source versus target exchange");
            check(fast.getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(d==0?Map.of("_q1","_q0"):Map.of("_q0","_q1"))),"selected paid link is transported original occurrence");
            check(fast.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(Map.of("_q0","_q1","_q1","_q0"))),"matrix map linked to paid and permutation path");
            check(!e.getBoolean("complete")&&!e.getBoolean("publicSequenceLinked")&&!e.getBoolean("javaLeanCorrespondenceChecked"),"full public proof blocked");
            var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(q,bad,"foreign source");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q0");invalid(q,bad,"paid substitution");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("selectedGroupedDeclarationPermutation").getJSONArray(d==0?"source":"target").put(0,0);invalid(q,bad,"selected permutation substitution");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("connectingPermutation").getJSONArray(d==0?"source":"target").put(0,0);invalid(q,bad,"emitted permutation nonbijection");
            bad=copy(report);bad.getJSONObject("fast").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(0).getJSONObject("receipt").getJSONArray("source").getJSONObject(0).put("admission","foreign owner");invalid(q,bad,"forged source declaration");
            bad=copy(report);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",13);invalid(q,bad,"cost substitution");
        }
        var types=new Class[]{NormalForm.class,NormalForm.class,List.class,int[].class};
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{0,0,2,3}),"nonbijection not an original automorphism");
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{2,1,0,3}),"mixed kind/carrier exchange rejected");
        reject(()->call("requireOrder",types,original,view(p.a),qs,new int[]{0,1,3,2}),"different typed domains rejected");
        var foreign=source("hJWX73Rdw3RziF3uu_inv14","inv14");
        reject(()->orders(original,view(foreign.a),qs),"foreign original certificate ledger");
        var order=new ArrayList<>(qs);order.set(0,original(foreign.a).getMatrixQuantiVars().get(0));
        reject(()->orders(original,view(p.a),order),"foreign parser-module occurrence in order");
        var clone=new QuantiVar(qs.get(0).getId(),qs.get(0).getName(),qs.get(0).getTypeName());
        for(var f:QuantiVar.class.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);f.set(clone,f.get(qs.get(0)));}
        check(clone.equals(qs.get(0))&&clone!=qs.get(0),"same-id forgery exercises identity boundary");
        var copied=view(p.a);var copiedQs=new ArrayList<>(qs);copiedQs.set(0,clone);field(copied,"matrixQuantiVars",List.copyOf(copiedQs));
        reject(()->orders(original,copied,qs),"same-id foreign comparison occurrence");
        for(String name:List.of("params","inheritedQuantiVars")){var v=view(p.a);field(v,name,List.of(qs.get(0)));check(orders(original,v,qs).size()==1,"unsupported "+name);}
        var temporal=view(p.a);field(temporal,"temporalOp",NormalForm.TemporalOp.ALWAYS);check(orders(original,temporal,qs).size()==1,"temporal boundary");
        String model="""
                sig P { r:set P, f:set B }
                sig B {}
                pred allSame { all x:P | all y:P | x->y in r }
                pred someSame { some x:P | some y:P | x->y in r }
                pred mixed { all x:P | some y:P | x->y in r }
                pred barrier { all x:P | some y:P | all z:P | x->y in r and y->z in r }
                pred different { all x:P | all y:B | x->y in f }
                pred disjoint { all disj x,y:P | x->y in r }
                pred sets { all x,y:set P | x->y in r }
                """;
        for(String name:List.of("allSame","someSame","mixed","barrier","different","disjoint","sets")) {
            var a=prepare(model,name).get(0);var f=original(a);var choices=orders(f,view(a),f.getMatrixQuantiVars());
            if(name.equals("allSame")||name.equals("someSame")) {
                check(f.getMatrixQuantiVars().size()==2&&!f.getMatrixQuantiVars().get(0).getBindingPath().equals(f.getMatrixQuantiVars().get(1).getBindingPath()),"actual separate source paths "+name);
                check(choices.size()==2,"source-proven same-kind independent exchange "+name);
            } else check(choices.size()==1,"no extra order for actual negative "+name);
        }
        var controls=new JSONArray();
        for(var row:List.of(new String[]{"ft2Q8x9bQa59gFBR4_inv14","inv14","17"},new String[]{"Wq9Au27ys6iRip6CD_inv14","inv14","36"},new String[]{"SASEHD8jPo2HgsuoJ_inv14","inv14","27"},new String[]{"85tWoTeBhoEDhCKmw_inv14","inv14","37"},new String[]{"JoNXaXfKNJzGh3BFa_inv12","inv12","22"},new String[]{"8dTfKqTs26w6fL87K_inv15","inv15","18"},new String[]{"YKy8LYsKdapW3xqbn_inv14","inv14","20"},new String[]{"xJnitBpFv9K74WNvf_inv14","inv14","49"})) {
            var control=source(row[0],row[1]);
            for(int d=0;d<2;d++){
                var q=d==0?control:control.reverse();var report=DistanceReplayDiagnostics.capture(q.a,q.b,q.ca,q.cb);
                for(String arm:List.of("fast","certified"))check(report.getJSONObject(arm).getInt("distance")==Integer.parseInt(row[2])&&report.getJSONObject(arm).getBoolean("matrixReplayChecked")&&report.getJSONObject(arm).getJSONObject("declarationSlice").getBoolean("selectedMatrixDeclarationLinksChecked"),"prior cost and joint native linkage "+row[0]);
                if(row[0].startsWith("ft2")||row[0].startsWith("Wq")||row[0].startsWith("SASE")||row[0].startsWith("85t")||row[0].startsWith("JoNX"))check(NativeOperationComparison.compare(q.a,q.b,q.ca,q.cb).status()==NativeOperationComparison.Status.MATCHED,"prior complete native action slice "+row[0]);
                if(row[0].startsWith("ft2"))Files.writeString(out.resolve("grouped-control-"+d+".json"),report.toString(2),StandardOpenOption.CREATE_NEW);
            }
            controls.put(new JSONObject().put("source",row[0]).put("distance",Integer.parseInt(row[2])));
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("controls",controls).put("certificationStatus","BLOCKED").toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("SeparatePrefixOrbitReplayTest: "+checks+" checks passed;17/actions22 both directions; grouped control retained");
    }
}
