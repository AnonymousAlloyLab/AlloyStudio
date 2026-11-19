package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.CanonicalDistance;
import is.fivefivefive.CanDis.core.QuantiVar;
import is.fivefivefive.CanDis.metric.DeclarationReplay;

/** Paid declaration locks are not a census of matrix reads. */
public final class PaidAlignmentExtensionTest {
    private static int checks, negatives;
    private static void check(boolean ok,String why) {
        checks++; if(!ok)throw new AssertionError(why);
    }
    private static Object get(Object value,String name) throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(value);
    }
    private static void set(Object value,String name,Object content) throws Exception {
        var field=value.getClass().getDeclaredField(name);field.setAccessible(true);field.set(value,content);
    }
    private static Method method(String name) {
        for(var m:CanonicalDistance.class.getDeclaredMethods())if(m.getName().equals(name)){
            m.setAccessible(true);return m;
        }
        throw new AssertionError(name);
    }
    private static Map<String,String> mapping(int encoding) {
        var result=new HashMap<String,String>();
        for(int x=0;x<3;x++,encoding/=4)if(encoding%4!=0)result.put("q"+x,"q"+(encoding%4-1));
        return result;
    }
    @SuppressWarnings("unchecked")
    private static void unions() throws Exception {
        var merge=method("mergePaidCorrespondence");
        for(int a=0;a<64;a++)for(int b=0;b<64;b++) {
            var old=mapping(a);var added=mapping(b);var before=Map.copyOf(old);var beforeAdded=Map.copyOf(added);
            boolean conflict=old.entrySet().stream().anyMatch(e->added.containsKey(e.getKey())&&!added.get(e.getKey()).equals(e.getValue()));
            var expected=new HashMap<>(old);expected.putAll(added);
            boolean valid=!conflict&&new HashSet<>(expected.values()).size()==expected.size();
            var actual=(Map<String,String>)merge.invoke(null,old,added);
            check(valid ? expected.equals(actual) : actual==null,"exact compatible injective union");
            check(old.equals(before)&&added.equals(beforeAdded),"inputs never mutated");
            if(!valid){negatives++;continue;}
            for(var e:old.entrySet())check(e.getValue().equals(actual.get(e.getKey())),"retained payment");
            for(var e:added.entrySet())check(e.getValue().equals(actual.get(e.getKey())),"added payment");
            for(int reads=0;reads<8;reads++)for(boolean reverse:new boolean[]{false,true}) {
                var fixed=reverse?new HashSet<>(actual.values()):new HashSet<>(actual.keySet());
                var locked=reverse?new HashSet<>(old.values()):new HashSet<>(old.keySet());
                var descriptors=new HashSet<String>();
                for(int x=0;x<3;x++)if((reads&(1<<x))!=0)descriptors.add("q"+x);
                locked.addAll(descriptors);locked.addAll(fixed);locked.removeAll(fixed);
                check(descriptors.containsAll(locked),"both residual search domains have descriptors");
            }
            actual.put("foreign","target");
            check(old.equals(before)&&added.equals(beforeAdded),"returned union does not alias inputs");
        }
        check(Map.of("q0","q1").equals(merge.invoke(null,null,Map.of("q0","q1"))),"ordinary coherent alignment has no prior paid map");
    }
    private static void reject(Runnable action,String why) {
        negatives++;checks++;
        try {action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Accepted tampered "+why);
    }
    @SuppressWarnings("unchecked")
    private static void selection(Canonical.Prepared left,Canonical.Prepared right) throws Exception {
        var a=(CanonicalDistance.Prepared)get(left,"delegate");
        var b=(CanonicalDistance.Prepared)get(right,"delegate");
        Class<?> alignment=Arrays.stream(CanonicalDistance.class.getDeclaredClasses())
            .filter(c->c.getSimpleName().equals("MatrixAlignment")).findFirst().orElseThrow();
        var ctor=alignment.getDeclaredConstructors()[0];ctor.setAccessible(true);
        var selected=ctor.newInstance(0,Map.of(),Set.of(),Set.of());
        var phases=(List<?>)method("selectedImportDeclarations").invoke(null,a,b,selected,null);
        var original=(List<QuantiVar>)get(phases.get(0),"source");
        check(original.size()==2,"two source declarations, including unread slot");
        // Selection-plumbing control only: an injected path must not silently
        // become the canonical default path. Its metric admissibility is not asserted.
        var alternate=List.of(new DeclarationReplay.Move(0,-1),new DeclarationReplay.Move(1,0),new DeclarationReplay.Move(-1,1));
        set(selected,"declarationPath",alternate);
        set(selected,"sourceOrder",new int[]{1,0});set(selected,"targetOrder",new int[]{0,1});
        phases=(List<?>)method("selectedImportDeclarations").invoke(null,a,b,selected,null);
        check(get(phases.get(0),"moves")==alternate,"actual selected path, not recomputed default");
        var transported=(List<QuantiVar>)get(phases.get(0),"source");
        check(transported.get(0)==original.get(1)&&transported.get(1)==original.get(0),"selected grouped order carries original typed slots");
    }
    public static void main(String[] args) throws Exception {
        unions();
        var manifest=new JSONObject(Files.readString(Path.of(args[0])));
        Path out=Path.of(args[1]);Files.createDirectory(out);
        var results=new JSONArray();
        for(Object value:manifest.getJSONArray("cases")) {
            var identity=(JSONObject)value;
            var pair=LocalOwnerTraceTest.prepare(identity);
            selection(pair.get(0),pair.get(1));
            for(int direction=0;direction<2;direction++) {
                var left=pair.get(direction);var right=pair.get(1-direction);
                var fast=CanonicalRepairTraces.fast(left,right);
                var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(left),CanonicalAlloyPipeline.prepare(right));
                fast.replay(fast.json());cert.replay(cert.json());
                var comparison=NativeOperationComparison.compare(left,right,fast,cert);
                var row=new JSONObject().put("identity",identity).put("direction",direction)
                    .put("fast",fast.json()).put("certified",cert.json()).put("comparison",comparison.evidence());
                Files.writeString(out.resolve(identity.getString("id")+"-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
                check(Canonical.distance(left,right)==fast.distance(),"numeric and captured payment selection agree");
                check(fast.distance()==18,"prior selected distance retained");
                check(comparison.status()==NativeOperationComparison.Status.MATCHED,"native/cross-path replay: "+comparison.reason());
                var paid=fast.json().getJSONObject("selectedPaidDeclarationCorrespondence");
                check(paid.length()==2&&paid.getString("_q0").equals("_q0")&&paid.getString("_q1").equals("_q1"),"unread declaration payment retained");
                var bad=fast.json();bad.getJSONObject("selectedPaidDeclarationCorrespondence").put("_q1","_q0");
                reject(()->fast.replay(bad),"paid ownership collision");
                var wrongCost=fast.json();wrongCost.put("distance",fast.distance()+1);
                reject(()->fast.replay(wrongCost),"distance");
                results.put(new JSONObject().put("id",identity.getString("id")).put("direction",direction).put("status",comparison.status()).put("distance",fast.distance()));
            }
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results);
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("PaidAlignmentExtensionTest: "+checks+" checks, "+negatives+" negative controls");
    }
}
