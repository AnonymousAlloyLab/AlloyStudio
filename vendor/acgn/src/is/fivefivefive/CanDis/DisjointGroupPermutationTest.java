package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.BiPredicate;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Source-issued same-group exchange, not erasure of the disjoint guard. */
public final class DisjointGroupPermutationTest {
    private static int checks;
    private static final String[][] CASES={
        {"coursesOld/both/kLzfvyMDPAnJRjcE6_inv15.als","inv15","67a204fc9617461b8a7accaaa3aa9c66f477926d7a0a20d35e4b09d792e7b8f6"},
        {"coursesOld/correct/T3spZ7j3BkNNjJZZb_inv15.als","inv15","7216152e65405cec9093a39617a1685759dba8c4108b35fbc5ff932fcd7f8680"},
        {"cv_v1/under/RFxzwXAqhbugWecZi_inv4.als","inv4","c5fcffc79ce9e4102a44aa3a5be31c3e3c92031fa0c4bb7b6f3c1a81a1b8804f"}
    };
    private static void check(boolean p,String why) {checks++;if(!p)throw new AssertionError(why);}
    private static Method method(String owner,String name,Class<?>... types)throws Exception {
        var m=Class.forName(owner).getDeclaredMethod(name,types);m.setAccessible(true);return m;
    }
    private static final String CORE="is.fivefivefive.CanDis.core.";
    private static final String METRIC="is.fivefivefive.CanDis.metric.";
    @SuppressWarnings("unchecked")
    private static BiPredicate<Integer,Integer> admission(NormalForm form,List<QuantiVar> ordered)throws Exception {
        return (BiPredicate<Integer,Integer>)method(CORE+"FastDeclarationPermutation","admission",NormalForm.class,List.class)
            .invoke(null,form,ordered);
    }
    private static boolean certified(RepairView.Binding a,RepairView.Binding b)throws Exception {
        return (boolean)method(METRIC+"CertifiedDeclarationOrbit","exchangeable",RepairView.Binding.class,RepairView.Binding.class)
            .invoke(null,a,b);
    }
    @FunctionalInterface private interface Test {boolean run()throws Exception;}
    private static void changed(Object object,String name,Object value,Test valid)throws Exception {
        var f=object.getClass().getDeclaredField(name);f.setAccessible(true);var old=f.get(object);
        try {f.set(object,value);check(!valid.run(),"reject changed "+name);}
        finally {f.set(object,old);}
    }
    private static void admissionControls(Canonical.Prepared p,CanonicalAlloyPipeline.Prepared cp)throws Exception {
        var form=p.normalizedForms().get(0);var qs=form.getMatrixQuantiVars();
        var bs=cp.repairView().phases().get(0).bindings();boolean found=false;
        for(int i=0;i<qs.size();i++)for(int j=i+1;j<qs.size();j++) {
            var a=qs.get(i);var b=qs.get(j);
            if(a.getDisjointnessClass()==0 || !a.getBindingPath().equals(b.getBindingPath()))continue;
            found=true;final int x=i,y=j;
            Test fast=()->admission(form,qs).test(x,y);
            check(fast.run(),"actual Fast same-disjoint-group admission");
            check(!(fast.run()&&a.getDisjointnessClass()==0&&b.getDisjointnessClass()==0),"exact old Fast zero-group conjunct rejects");
            changed(b,"disjointnessClass",a.getDisjointnessClass()+1,fast);
            changed(b,"bindingPath","foreign-owner",fast);
            changed(b,"quantifier",QuantiVar.Quantifier.ONE,fast);
            changed(b,"exactAlloyType",null,fast);
            var foreign=new QuantiVar(b.getId(),b.getName(),b.getTypeName());
            for(var f:QuantiVar.class.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);f.set(foreign,f.get(b));}
            var altered=new ArrayList<>(qs);altered.set(j,foreign);
            check(!admission(form,altered).test(i,j),"equal numeric id is not source issuance");
            check(fast.run(),"Fast mutation controls restored");
            var ca=bs.stream().filter(v->v.ordinal()==x).findFirst().orElseThrow();
            var cb=bs.stream().filter(v->v.ordinal()==y).findFirst().orElseThrow();
            Test cert=()->certified(ca,cb);
            check(cert.run(),"actual certified same-disjoint-group admission");
            check(!(cert.run()&&ca.declaration().disjointnessClass()==0),"exact old certified zero-group conjunct rejects");
            check(ca.declaration().sameCertifiedPayload(cb.declaration())
                &&ca.declaration().observationType()!=null&&ca.declaration().observationDomain()!=null
                &&ca.originalBinding()!=null&&cb.originalBinding()!=null,"structured original payload evidence");
            changed(cb,"ownerPhase",99,cert);changed(cb,"ownerContext","foreign",cert);
            changed(cb,"bindingPath","foreign-group",cert);changed(cb,"certifiedOrbit",List.of(cb.coordinate()),cert);
            changed(cb,"prenexPathErasureCertified",false,cert);
            changed(cb.declaration(),"dependencies",List.of(99),cert);
            changed(cb.declaration(),"observationType",null,cert);
            changed(cb.declaration(),"observationDomain",null,cert);
            changed(cb.declaration(),"disjointnessClass",ca.declaration().disjointnessClass()+1,cert);
            check(cert.run(),"certified mutation controls restored");
        }
        check(found,"fixture actually has an issued disjoint group");
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static void invalid(Canonical.Prepared a,Canonical.Prepared b,JSONObject bad,String why) {
        check(NativeOperationComparison.compareCaptured(a,b,bad).status()==NativeOperationComparison.Status.INVALID_REPLAY,why);
    }
    public static void main(String[] args)throws Exception {
        Path out=args.length==0?Files.createTempDirectory("disjoint-permutation-"):Path.of(args[0]);Files.createDirectories(out);
        var rows=new JSONArray();
        for(int c=0;c<CASES.length;c++) {
            var f=CASES[c];var id=new JSONObject().put("source",Path.of("classified-data",f[0]).toAbsolutePath().toString())
                .put("sha256",f[2]).put("left",f[1]).put("right",f[1]+"c");
            var pair=LocalOwnerTraceTest.prepare(id);
            for(int d=0;d<2;d++) {
                long start=System.nanoTime();var a=pair.get(d);var b=pair.get(1-d);
                var ea=CanonicalAlloyPipeline.prepare(a);var eb=CanonicalAlloyPipeline.prepare(b);
                var fast=CanonicalRepairTraces.fast(a,b);var cert=CanonicalRepairTraces.certified(ea,eb);
                var fj=fast.json();var cj=cert.json();
                if(fj.getInt("distance")<=cj.getInt("distance")){fast.replay(fj);cert.replay(cj);}else{cert.replay(cj);fast.replay(fj);}
                var result=NativeOperationComparison.compare(a,b,fast,cert);
                Files.writeString(out.resolve(c+"-"+d+".json"),result.evidence().toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,f[0]+"/"+d+" "+result.status()+" "+result.reason());
                check(Canonical.distance(a,b)==fj.getInt("distance"),"Fast scalar/capture selection parity");
                check(QuotientRepairDistance.distance(ea.repairView(),eb.repairView())==cj.getInt("distance"),"scalar/capture selection parity");
                var report=result.evidence().getJSONObject("diagnostic");
                check(NativeOperationComparison.compareCaptured(a,b,report).status()==NativeOperationComparison.Status.MATCHED,"independent captured replay");
                admissionControls(a,ea);
                var bad=copy(report);bad.getJSONArray("nativeOperationEndpoints").put(0,"foreign");invalid(a,b,bad,"source binding");
                bad=copy(report);bad.getJSONObject("certified").getJSONArray("selectedGlobalMapping").put(0,999);invalid(a,b,bad,"foreign map");
                bad=copy(report);bad.getJSONObject("certified").getJSONObject("selectedDeclarationPermutation").getJSONArray("left").put(0,999);invalid(a,b,bad,"foreign permutation");
                bad=copy(report);bad.getJSONObject("fast").getJSONArray("nativeDeclarationPrograms").getJSONObject(0)
                    .getJSONArray("source").getJSONObject(0).getJSONArray("tuple").put(3,"999");invalid(a,b,bad,"disjoint guard cannot be retagged");
                bad=copy(report);bad.getJSONObject("certified").put("matrixDistance",cj.getInt("matrixDistance")+1);invalid(a,b,bad,"payment");
                double seconds=(System.nanoTime()-start)/1e9;check(seconds<60,"natural direction including corrupt controls under sixty seconds");
                rows.put(new JSONObject().put("identity",id).put("direction",d).put("status",result.status())
                    .put("fast",fj.getInt("distance")).put("certified",cj.getInt("distance")).put("seconds",seconds));
            }
        }
        Files.writeString(out.resolve("checks.json"),new JSONObject().put("checks",checks).put("directions",rows).toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("DisjointGroupPermutationTest: "+checks+" checks passed; six matched directions; public certification BLOCKED");
    }
}
