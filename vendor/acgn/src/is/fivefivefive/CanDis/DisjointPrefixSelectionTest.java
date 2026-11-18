package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;
import parser.ast.nodes.ModelUnit;

/** Selection and replay share the original disjoint-group authority. */
public final class DisjointPrefixSelectionTest {
    private static int checks, negatives;
    private static void check(boolean value,String why) { checks++;if(!value)throw new AssertionError(why); }
    @SuppressWarnings("unchecked")
    private static List<int[]> orders(NormalForm source,NormalForm comparison,List<QuantiVar> bindings) {
        try {
            var m=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit")
                .getDeclaredMethod("orders",NormalForm.class,NormalForm.class,List.class);
            m.setAccessible(true);return (List<int[]>)m.invoke(null,source,comparison,bindings);
        }catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new AssertionError(e);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static boolean admits(NormalForm source,NormalForm comparison,List<QuantiVar> bindings,int[] p) {
        return orders(source,comparison,bindings).stream().anyMatch(q->Arrays.equals(p,q));
    }
    private static void rejected(Runnable work,String why) {
        negatives++;
        try{work.run();}catch(IllegalArgumentException|IllegalStateException expected){checks++;return;}
        throw new AssertionError(why);
    }
    private static void corrupt(Object target,String field,Object value,Runnable work)throws Exception {
        var f=target.getClass().getDeclaredField(field);f.setAccessible(true);var old=f.get(target);
        try{f.set(target,value);work.run();}finally{f.set(target,old);}
    }
    private static void forbid(NormalForm s,NormalForm c,List<QuantiVar> qs,int[] p) {
        rejected(()->{if(!admits(s,c,qs,p))throw new IllegalArgumentException("not admitted");},"forged swap accepted");
    }
    private static Canonical.Prepared prepare(String text)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("p"),module);
        visitor.visit(new ModelUnit(null,module),null);
        return Canonical.prepare(visitor.getForest().get(visitor.getForestId("p")));
    }
    private static void variants()throws Exception {
        for(String kind:List.of("all","some")) {
            var p=prepare("sig A {r:set A} pred p {"+kind+" disj x,y:A | x in y.r}");
            var s=p.normalizedForms().get(0);var c=CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);
            check(admits(s,c,s.getMatrixQuantiVars(),new int[]{1,0}),"same-group "+kind+" variant");
        }
        var p=prepare("sig A {r:set A} pred p {all disj x,y:A | all disj z,w:A | x in y.r and z in w.r}");
        var s=p.normalizedForms().get(0);var c=CanonicalDistance.prepare(p.normalizedForms()).comparisonForms().get(0);var qs=s.getMatrixQuantiVars();
        check(qs.size()==4,"two original groups");
        for(var permutation:orders(s,c,qs))for(int i=0;i<permutation.length;i++)
            check(qs.get(i).getBindingPath().equals(qs.get(permutation[i]).getBindingPath()),"no cross-group move");
        forbid(s,c,qs,new int[]{2,1,0,3});
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void invalid(Canonical.Prepared a,Canonical.Prepared b,JSONObject bad,String why){
        negatives++;check(NativeOperationComparison.compareCaptured(a,b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    public static void main(String[] args)throws Exception {
        var id=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectories(out);
        long start=System.nanoTime();var pair=LocalOwnerTraceTest.prepare(id);var source=pair.get(0);var target=pair.get(1);
        var s=target.normalizedForms().get(0);var c=CanonicalDistance.prepare(target.normalizedForms()).comparisonForms().get(0);var qs=s.getMatrixQuantiVars();int[] swap={0,2,1};
        check(qs.size()==3&&qs.get(0).getTypeName().equals("Person")&&qs.get(1).getTypeName().equals("Project"),"actual typed prefix");
        check(qs.get(1).getDisjointnessClass()==1&&qs.get(2).getDisjointnessClass()==1,"source disjoint class1, descriptor class0");
        check(qs.stream().anyMatch(q->q.getDisjointnessClass()!=0),"exact old early filter rejects");
        check(admits(s,c,qs,swap),"new source-certified target swap");
        var one=source.normalizedForms().get(0);var oneComparison=CanonicalDistance.prepare(source.normalizedForms()).comparisonForms().get(0);
        check(orders(one,oneComparison,one.getMatrixQuantiVars()).size()==1,"source ONE remains ordered");
        String work=System.getProperty("acgn.maxCanonicalWorkItems");
        try{System.setProperty("acgn.maxCanonicalWorkItems","1");check(admits(s,c,qs,swap),"Fast needs no full canonical construction");}
        finally{if(work==null)System.clearProperty("acgn.maxCanonicalWorkItems");else System.setProperty("acgn.maxCanonicalWorkItems",work);}
        var q=qs.get(2);
        corrupt(q,"disjointnessClass",2,()->forbid(s,c,qs,swap));
        corrupt(q,"bindingPath","foreign",()->forbid(s,c,qs,swap));
        corrupt(q,"exactAlloyType",qs.get(0).getExactAlloyType(),()->forbid(s,c,qs,swap));
        corrupt(q,"typeName","ForeignDomain",()->forbid(s,c,qs,swap));
        corrupt(q,"deBruijnKey",qs.get(0).getDeBruijnKey(),()->forbid(s,c,qs,swap));
        corrupt(q,"quantifier",QuantiVar.Quantifier.ONE,()->forbid(s,c,qs,swap));
        corrupt(q,"cardinality",QuantiVar.Cardinality.SET,()->forbid(s,c,qs,swap));
        corrupt(c,"temporalOp",NormalForm.TemporalOp.ALWAYS,()->forbid(s,c,qs,swap));
        var foreign=LocalOwnerTraceTest.prepare(id).get(1);var fc=CanonicalDistance.prepare(foreign.normalizedForms()).comparisonForms().get(0);
        forbid(s,fc,qs,swap);var duplicate=new ArrayList<>(qs);duplicate.set(2,qs.get(1));forbid(s,c,duplicate,swap);
        check(admits(s,c,qs,swap),"fresh source validation restored after tampering");
        var rows=new JSONArray();
        for(int d=0;d<2;d++) {
            var a=pair.get(d);var b=pair.get(1-d);var ca=CanonicalAlloyPipeline.prepare(a);var cb=CanonicalAlloyPipeline.prepare(b);
            var fast=CanonicalRepairTraces.fast(a,b);var cert=CanonicalRepairTraces.certified(ca,cb);var fj=fast.json();var cj=cert.json();
            var order=fj.getInt("distance")<cj.getInt("distance")?List.of("fast","certified"):List.of("certified","fast");
            for(var arm:order)if(arm.equals("fast"))fast.replay(fj);else cert.replay(cj);
            var result=NativeOperationComparison.compare(a,b,fast,cert);var evidence=result.evidence();
            Files.writeString(out.resolve(d+".json"),evidence.toString(2),StandardOpenOption.CREATE_NEW);
            check(result.status()==NativeOperationComparison.Status.MATCHED,"actual native action equality "+result.status()+" "+result.reason());
            check(fj.getInt("distance")==31&&cj.getInt("distance")==31,"actual total31");
            check(fj.getInt("matrixDistance")==27&&cj.getInt("matrixDistance")==27,"actual matrix27");
            check(fj.getInt("quantifierDistance")==4&&cj.getInt("quantifierDistance")==4,"four retained declaration payments");
            check(Canonical.distance(a,b)==31&&QuotientRepairDistance.distance(ca.repairView(),cb.repairView())==31,"scalar and captured selection agree");
            check(evidence.getBoolean("sourceCertificatesChecked")&&evidence.getBoolean("fixedChoiceReplaysChecked")&&evidence.getBoolean("endpointCorrespondenceChecked"),"source/native/endpoint checks retained");
            var diagnostic=evidence.getJSONObject("diagnostic");
            check(NativeOperationComparison.compareCaptured(a,b,diagnostic).status()==NativeOperationComparison.Status.MATCHED,"independent captured replay");
            var bad=copy(diagnostic);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(a,b,bad,"foreign source");
            bad=copy(diagnostic);bad.getJSONObject("certified").getJSONObject("selectedDeclarationPermutation").getJSONArray(d==0?"right":"left").put(1,0);invalid(a,b,bad,"nonbijective swap");
            bad=copy(diagnostic);bad.getJSONObject("fast").getJSONArray("phases").getJSONObject(0).getJSONObject("receipt").put("cost",26);invalid(a,b,bad,"corrupt matrix charge");
            bad=copy(diagnostic);bad.getJSONObject("certified").put("quantifierDistance",3);invalid(a,b,bad,"missing declaration payment");
            bad=copy(diagnostic);var domainAtom=bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0);
            var admission=new JSONArray(domainAtom.getString("admission"));admission.put(0,"foreign-domain");domainAtom.put("admission",admission.toString());invalid(a,b,bad,"foreign declaration domain");
            bad=copy(diagnostic);var dependencyAtom=bad.getJSONObject("certified").getJSONObject("declarationSlice").getJSONArray("components").getJSONObject(1).getJSONObject("receipt").getJSONArray("source").getJSONObject(0);
            admission=new JSONArray(dependencyAtom.getString("admission"));admission.put(2,new JSONArray().put(0));dependencyAtom.put("admission",admission.toString());invalid(a,b,bad,"forged declaration dependency");
            rows.put(new JSONObject().put("direction",d).put("status",result.status()).put("replayOrder",order).put("fast",31).put("certified",31));
        }
        double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"natural file both directions and controls below60seconds");
        variants();
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("negativeControls",negatives).put("fileSeconds",seconds).put("directions",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DisjointPrefixSelectionTest: "+checks+" checks, "+negatives+" negative controls; both directions MATCHED31/31; file "+seconds+"s");
    }
}
