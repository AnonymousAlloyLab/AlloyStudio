package is.fivefivefive.CanDis;

import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;

/** Single imported uses retain the existing lexical-owner mapping checks. */
public final class ImportedSingleUseBindingTest {
    private static int checks, negative;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static Object get(Object o,String name) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void set(Object o,String name,Object value) throws Exception {
        Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static Object call(Object o,String name,Class<?>[] types,Object... args) throws Exception {
        Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);
    }
    private static Object variable(Canonical.Prepared p) throws Exception {
        var delegate=(CanonicalDistance.Prepared)get(p,"delegate");
        Method method=CanonicalDistance.class.getDeclaredMethod("coherentVariableSpace",List.class);
        method.setAccessible(true);var space=method.invoke(null,delegate.comparisonForms());
        var variables=(Map<?,?>)get(space,"variables");check(variables.size()==1,"one actual used source binder");
        return variables.values().iterator().next();
    }
    private static void reject(Runnable action,String why) {
        negative++;checks++;try {action.run();} catch(IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError("Accepted corrupted "+why);
    }
    public static void main(String[] args) throws Exception {
        var identity=new JSONObject(Files.readString(Path.of(args[0])));
        var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(identity);
        var ownerEvidence=new JSONArray();
        for(int side=0;side<2;side++) {
            Object v=variable(pair.get(side));
            check(get(v,"phases").equals(Set.of(2)),"one actual matrix use");
            check(get(v,"ownerPhase").equals(side),"actual original lexical owner phase");
            check((Boolean)call(v,"isCoherent",new Class<?>[0]),"single imported use enters owner-aware route");
            check(!(Boolean)call(v,"compatibleWith",new Class<?>[]{v.getClass()},variable(pair.get(1-side))),"foreign owner cannot give a free import");negative++;
            ownerEvidence.put(new JSONObject().put("side",side).put("ownerPhase",get(v,"ownerPhase"))
                .put("uses",new JSONArray((Set<?>)get(v,"phases"))).put("oldNeedsOwner",false).put("newNeedsOwner",true));
            Object same=variable(pair.get(side));
            check((Boolean)call(v,"compatibleWith",new Class<?>[]{v.getClass()},same),"same original owner remains compatible");
            set(same,"ownerPhase",999);
            check(!(Boolean)call(v,"compatibleWith",new Class<?>[]{v.getClass()},same),"wrong owner phase rejected");negative++;
            Object foreign=variable(LocalOwnerTraceTest.prepare(identity).get(side));
            Object descriptor=get(foreign,"descriptor");
            call(v,"observeOwner",new Class<?>[]{descriptor.getClass(),int.class},descriptor,side);
            check((Boolean)get(v,"ambiguous"),"same-metadata foreign source object remains ambiguous");negative++;
            check(!(Boolean)call(v,"compatibleWith",new Class<?>[]{v.getClass()},variable(pair.get(side))),"ambiguous source object never selected");negative++;
        }
        var summaries=new JSONArray();
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var raw=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),raw.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),raw);
            var result=NativeOperationComparison.compare(a,b,fast,certified);raw.put("comparison",result.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),raw.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==3&&certified.distance()==3,"unlinked imported variable is charged, not a copied scalar");
            check(fast.json().getInt("quantifierDistance")==2&&fast.json().getInt("matrixDistance")==1,"existing delete/insert and explicit variable update");
            check(fast.json().getJSONArray("phases").getJSONObject(2).getJSONObject("alphaMapping").isEmpty(),"no fabricated cross-owner correspondence");
            check(result.status()==NativeOperationComparison.Status.MATCHED,"source/path correspondence: "+result.reason());
            for(String flag:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))check(result.evidence().getBoolean(flag),flag);
            for(String mode:List.of("owner","type","slot","phase","mapping","cost","premise","occurrence","declaration","construction")) {
                var bad=fast.json();var p=bad.getJSONArray("phases").getJSONObject(2);var op=p.getJSONObject("program").getJSONArray("operations").getJSONObject(2);
                switch(mode) {
                    case "owner","type","slot" -> bad.getJSONArray("sourceBindingContexts").getJSONObject(2).getJSONArray("bindings").getJSONObject(0).put(mode,"foreign");
                    case "phase" -> p.put("phase",999);
                    case "mapping" -> p.put("alphaMapping",new JSONObject().put("_q0","_q0"));
                    case "cost" -> op.put("cost",0);
                    case "premise" -> op.put("premise","foreign");
                    case "occurrence" -> {var e=new JSONObject(op.getJSONObject("before").getString("nativeEvidence"));e.put("sourceOccurrenceLineage",999999);op.getJSONObject("before").put("nativeEvidence",e.toString());}
                    case "declaration" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(0).getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case "construction" -> bad.put("constructionLink","foreign");
                }
                reject(()->fast.replay(bad),mode);
            }
            summaries.put(new JSONObject().put("direction",direction).put("status",result.status()).put("fast",fast.distance()).put("certified",certified.distance()));
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negative).put("identity",identity)
            .put("directions",summaries).put("ownerEvidence",ownerEvidence).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}
