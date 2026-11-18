package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.CanonicalDistance;

/** Paired reads constrain alignment; whole-phase edits retain their full ledger. */
public final class AlignedPhaseReadCensusTest {
    private static int checks, negatives;
    private static void check(boolean ok, String why) {
        checks++; if (!ok) throw new AssertionError(why);
    }
    private static Object get(Object value, String name) throws Exception {
        var f=value.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(value);
    }
    private static void set(Object value, String name, Object next) throws Exception {
        var f=value.getClass().getDeclaredField(name); f.setAccessible(true); f.set(value,next);
    }
    private static Object call(Object value, String name, Object... args) throws Exception {
        var type=value instanceof Class<?> c?c:value.getClass();
        var m=Arrays.stream(type.getDeclaredMethods()).filter(x->x.getName().equals(name)
            && x.getParameterCount()==args.length).findFirst().orElseThrow();
        m.setAccessible(true); return m.invoke(value instanceof Class<?>?null:value,args);
    }
    private static void reject(Runnable action, String why) {
        checks++; negatives++;
        try { action.run(); } catch (IllegalArgumentException|IllegalStateException expected) { return; }
        throw new AssertionError("Accepted corrupted "+why);
    }
    @SuppressWarnings("unchecked")
    private static void pairedControls(List<Canonical.Prepared> pair, JSONObject id) throws Exception {
        var left=(CanonicalDistance.Prepared)get(pair.get(0),"delegate");
        var right=(CanonicalDistance.Prepared)get(pair.get(1),"delegate");
        int aligned=Math.min(left.comparisonForms().size(),right.comparisonForms().size());
        var space=call(CanonicalDistance.class,"coherentVariableSpace",left.comparisonForms().subList(0,aligned));
        var values=(Map<String,Object>)get(space,"variables");
        check(values.size()==1,"one actual paired read binder");
        var v=values.values().iterator().next();
        var expected=new HashSet<Integer>(); for(Object p:id.getJSONArray("pairedUses"))expected.add((Integer)p);
        check(get(v,"phases").equals(expected),"paired phase read census exact");
        check(get(v,"ownerPhase").equals(0),"lexical owner retained before imported reads");
        boolean coherent=expected.size()>1||!expected.contains(0);
        check((Boolean)call(v,"isCoherent")==coherent,"only actual cross-phase reads locked");
        var again=call(CanonicalDistance.class,"coherentVariableSpace",left.comparisonForms().subList(0,aligned));
        var same=((Map<String,Object>)get(again,"variables")).values().iterator().next();
        check((Boolean)call(v,"compatibleWith",same),"original owner remains compatible");
        set(same,"ownerPhase",999); negatives++;
        check(!(Boolean)call(v,"compatibleWith",same),"foreign owner is not free");
        set(same,"ownerPhase",0); set(same,"ambiguous",true); negatives++;
        check(!(Boolean)call(v,"compatibleWith",same),"ambiguous source identity rejected");
        if(id.optBoolean("prefixAndSuffix")) {
            var full=call(CanonicalDistance.class,"coherentVariableSpace",left.comparisonForms());
            var fv=((Map<String,Object>)get(full,"variables")).values().iterator().next();
            check(((Set<?>)get(fv,"phases")).size()>expected.size(),"suffix read exercised too");
        }
    }
    public static void main(String[] args) throws Exception {
        var family=new JSONArray(Files.readString(Path.of(args[0])));
        var out=Path.of(args[1]); Files.createDirectory(out); var rows=new JSONArray();
        for(Object raw:family) {
            var id=(JSONObject)raw; long start=System.nanoTime(); var pair=LocalOwnerTraceTest.prepare(id);
            if(id.has("pairedUses"))pairedControls(pair,id);
            for(int d=0;d<2;d++) {
                var a=pair.get(d); var b=pair.get(1-d); String old=System.getProperty("acgn.maxCanonicalWorkItems");
                is.fivefivefive.CanDis.metric.NativeRepairTrace fast;
                try { System.setProperty("acgn.maxCanonicalWorkItems","1"); fast=CanonicalRepairTraces.fast(a,b); }
                finally { if(old==null)System.clearProperty("acgn.maxCanonicalWorkItems"); else System.setProperty("acgn.maxCanonicalWorkItems",old); }
                var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
                var order=new JSONObject();
                QueueProbe.orderedReplay(fast.distance(),cert.distance(),()->fast.replay(fast.json()),()->cert.replay(cert.json()),order);
                var result=NativeOperationComparison.compare(a,b,fast,cert);
                var row=new JSONObject().put("identity",id).put("direction",d).put("fast",fast.json())
                    .put("certified",cert.json()).put("comparison",result.evidence()).put("replayOrder",order);
                Files.writeString(out.resolve(id.getString("id")+"-"+d+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
                check(result.status()==NativeOperationComparison.Status.MATCHED,"action equality: "+result.status()+" "+result.reason());
                check(Canonical.distance(a,b)==fast.distance()&&fast.distance()==cert.distance(),"numeric/captured path costs agree");
                if(id.has("expectedDistance"))check(fast.distance()==id.getInt("expectedDistance"),"retained distance");
                for(String f:List.of("sourceCertificatesChecked","endpointCorrespondenceChecked","fixedChoiceReplaysChecked"))
                    check(result.evidence().getBoolean(f),f);
                if(id.optBoolean("unpairedIncident")) {
                    check(fast.distance()==31&&fast.json().getInt("matrixDistance")==29
                        &&fast.json().getInt("quantifierDistance")==1&&fast.json().getInt("temporalDistance")==1,"unchanged components");
                    for(Object phase:fast.json().getJSONArray("phases"))check(((JSONObject)phase).getJSONObject("alphaMapping").isEmpty(),"irrelevant map absent");
                    var effects=new JSONArray();
                    for(Object block:result.evidence().getJSONArray("fastBlocks")) {
                        var bk=(JSONObject)block;if(bk.getString("component").equals("matrix"))for(Object effect:bk.getJSONArray("effect"))effects.put(effect);
                    }
                    check(effects.length()==30,"all source matrix actions retained");
                    int suffix=0,cost=0,guards=0,calls=0;
                    for(Object effect:effects) {
                        var e=(JSONObject)effect;var endpoint=e.optJSONObject(d==0?"source":"target");
                        if(endpoint!=null&&endpoint.getJSONObject("phase").getString("typedRole").equals("ALWAYS")) {suffix++;cost+=e.getInt("cost");check(e.isNull(d==0?"target":"source"),"suffix stays one-sided");}
                        for(String side:List.of("source","target")) {var ep=e.optJSONObject(side);if(ep==null)continue;String op=ep.getString("operator");if(op.equals("NOT_IN"))guards++;if(op.equals("CALL"))calls++;}
                    }
                    check(suffix==14&&cost==14&&guards==1&&calls==2,"suffix, dependent guard and CALL evidence retained");
                    for(String mode:List.of("omission","cost","payload","phase","coordinate")) {
                        var bad=fast.json();var phase=bad.getJSONArray("phases").getJSONObject(1);var ops=phase.getJSONObject("program").getJSONArray("operations");
                        switch(mode) {
                            case "omission" -> ops.remove(ops.length()-1);
                            case "cost" -> ops.getJSONObject(0).put("cost",0);
                            case "payload" -> ops.getJSONObject(0).getJSONObject(d==0?"before":"after").put("nativeEvidence","foreign");
                            case "phase" -> phase.put("phase",999);
                            case "coordinate" -> bad.getJSONArray("sourceBindingContexts").getJSONObject(0).getJSONArray("bindings").getJSONObject(0).put("slot","foreign");
                        }
                        reject(()->fast.replay(bad),mode);
                    }
                }
                if(id.has("pairedUses")) {
                    boolean mapped=false;
                    var bad=fast.json();
                    for(Object phase:bad.getJSONArray("phases")) {
                        var p=(JSONObject)phase;if(!p.getJSONObject("alphaMapping").isEmpty()) {p.put("alphaMapping",new JSONObject());mapped=true;break;}
                    }
                    check(mapped,"actual paired read has a selected mapping");
                    reject(()->fast.replay(bad),"dropped relevant mapping");
                    for(String field:List.of("owner","type")) {
                        var forged=fast.json();forged.getJSONArray("sourceBindingContexts").getJSONObject(0).getJSONArray("bindings").getJSONObject(0).put(field,"foreign");
                        reject(()->fast.replay(forged),field);
                    }
                }
                rows.put(new JSONObject().put("id",id.getString("id")).put("direction",d).put("status",result.status()).put("distance",fast.distance()));
            }
            check(System.nanoTime()-start<60_000_000_000L,"whole file including controls below60seconds");
        }
        var receipt=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",rows);
        Files.writeString(out.resolve("checks.json"),receipt.toString(2),StandardOpenOption.CREATE_NEW);
        System.out.println("AlignedPhaseReadCensusTest: "+checks+" checks, "+negatives+" negative controls; "+rows.length()+" MATCHED directions");
    }
}
