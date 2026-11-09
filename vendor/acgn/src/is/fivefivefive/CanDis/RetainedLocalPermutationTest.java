package is.fivefivefive.CanDis;
import java.nio.file.*;
import java.util.*;
import java.lang.reflect.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Source-certified outer exchange leaves retained local owners in place. */
public final class RetainedLocalPermutationTest {
    private static int checks,rejections;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static Object call(String name,Class<?>[] types,Object...args)throws Exception {
        var m=Class.forName("is.fivefivefive.CanDis.core.FastGroupedDeclarationOrbit").getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(null,args);}catch(InvocationTargetException e){if(e.getCause() instanceof RuntimeException r)throw r;throw e;}
    }
    private static void require(NormalForm source,NormalForm view,int[] order)throws Exception {
        call("requireOrder",new Class[]{NormalForm.class,NormalForm.class,List.class,int[].class},source,view,source.getMatrixQuantiVars(),order);
    }
    @FunctionalInterface private interface Action {void run()throws Exception;}
    private static void reject(Action action,String why)throws Exception {
        checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){rejections++;return;}throw new AssertionError("Accepted "+why);
    }
    public static void main(String[] args)throws Exception {
        OrderedLocalPaidAdmissionTest.main(args);
        var family=new JSONArray(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);var controls=new JSONArray();
        for(Object item:family){var row=(JSONObject)item;if(!row.has("orbitEndpoints"))continue;
            var id=row.getJSONObject("identity");var pair=LocalOwnerTraceTest.prepare(id);var covered=new HashSet<String>();
            for(int d=0;d<2;d++)for(Object e:row.getJSONArray("orbitEndpoints")){
                int endpoint=((Number)e).intValue();var source=pair.get(endpoint).normalizedForms().get(0);var view=CanonicalDistance.prepare(pair.get(endpoint).normalizedForms()).comparisonForms().get(0);
                check(!source.getLocalQuantiVars().isEmpty(),"actual retained local endpoint");require(source,view,new int[]{1,0});checks++;
                reject(()->require(source,view,new int[]{0,0}),"nonbijection");
                var foreign=LocalOwnerTraceTest.prepare(id).get(endpoint);var foreignView=CanonicalDistance.prepare(foreign.normalizedForms()).comparisonForms().get(0);
                reject(()->require(source,foreignView,new int[]{1,0}),"foreign original-owner authority");
                var field=NormalForm.class.getDeclaredField("localBindingSeeds");field.setAccessible(true);var original=field.get(view);
                try{field.set(view,new LinkedHashMap<>());reject(()->require(source,view,new int[]{1,0}),"erased live local metadata");}finally{field.set(view,original);}
                require(source,view,new int[]{1,0});checks++;covered.add(d+":"+endpoint);
                controls.put(new JSONObject().put("identity",id).put("direction",d).put("endpoint",endpoint).put("sourceCertifiedSwap",true).put("tamperedRejected",3));
            }
            var expected=new HashSet<String>();for(Object e:row.getJSONArray("orbitEndpoints"))for(int d=0;d<2;d++)expected.add(d+":"+e);
            check(covered.equals(expected)&&!covered.isEmpty(),"every prepared retained endpoint in both directions");
            if(!row.optBoolean("selectedSwap"))continue;
            var certified=List.of(CanonicalAlloyPipeline.prepare(pair.get(0)),CanonicalAlloyPipeline.prepare(pair.get(1)));
            for(int d=0;d<2;d++){
                var c=CanonicalRepairTraces.certified(certified.get(d),certified.get(1-d));check(c.replay(c.json())==c.distance(),"shorter actual certified path first");
                var f=CanonicalRepairTraces.fast(pair.get(d),pair.get(1-d));check(f.replay(f.json())==f.distance(),"actual Fast selected path");
                var p=f.json().getJSONObject("selectedGroupedDeclarationPermutation");
                check(p.getJSONArray(d==0?"source":"target").similar(new JSONArray(new int[]{1,0})),"same selected outer swap");
                var expectedPaid=d==0?Map.of("_q1","_q0"):Map.of("_q0","_q1");
                check(f.json().getJSONObject("selectedPaidDeclarationCorrespondence").similar(new JSONObject(expectedPaid)),"paid link transported with actual swap");
                for(var trace:List.of(f,c))for(String mode:List.of("permutation","paidMap")){
                    var bad=trace.json();
                    if(trace==f){if(mode.equals("permutation"))bad.getJSONObject("selectedGroupedDeclarationPermutation").getJSONArray(d==0?"source":"target").put(0,0);else bad.getJSONObject("selectedPaidDeclarationCorrespondence").put("_q0","_q0");}
                    else {if(mode.equals("permutation"))bad.getJSONObject("selectedDeclarationPermutation").getJSONArray(d==0?"left":"right").put(0,0);else bad.getJSONObject("selectedQuantifierCorrespondence").put("0",0);}
                    reject(()->trace.replay(bad),"forged selected "+mode);
                    controls.put(new JSONObject().put("identity",id).put("direction",d).put("arm",trace==f?"fast":"certified").put("mode",mode).put("rejected",true));
                }
                check(NativeOperationComparison.compare(pair.get(d),pair.get(1-d),f,c).status()==NativeOperationComparison.Status.MATCHED,"checked actual paths after tampering");
            }
        }
        var previous=new JSONObject(Files.readString(out.resolve("results.json")));
        var result=new JSONObject(previous.toString()).put("checks",previous.getInt("checks")+checks).put("tamperedEvidenceRejections",previous.getInt("tamperedEvidenceRejections")+rejections).put("localPermutationControls",controls);
        Files.writeString(out.resolve("permutation-results.json"),result.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("RetainedLocalPermutationTest: "+result.getInt("checks")+" checks, "+result.getInt("tamperedEvidenceRejections")+" tampered-evidence rejections, "+result.getJSONArray("rows").length()+" directions");
    }
}
