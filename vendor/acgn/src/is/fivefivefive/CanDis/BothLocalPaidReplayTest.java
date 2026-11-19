package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.*;

/** Issued production traces for paid outer edits beneath retained local owners. */
public final class BothLocalPaidReplayTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static CanonicalDistance.Prepared delegate(Canonical.Prepared p)throws Exception{
        var f=p.getClass().getDeclaredField("delegate");f.setAccessible(true);return (CanonicalDistance.Prepared)f.get(p);
    }
    private static boolean gate(String name,List<NormalForm> a,List<NormalForm> b)throws Exception{
        var m=Class.forName("is.fivefivefive.CanDis.core.FastPaidDeclarationAlignment").getDeclaredMethod(name,List.class,List.class);
        m.setAccessible(true);return (Boolean)m.invoke(null,a,b);
    }
    private static void localNames(EGraphNode node,Set<String> names){
        if(node.hasComparisonLocalCoordinate())names.add(node.getAlphaName());
        for(var child:node.getChildren())localNames(child,names);
    }
    private static JSONObject copy(JSONObject j){return new JSONObject(j.toString());}
    private static JSONObject variable(JSONObject d){
        if(d.optString("kind").equals("select"))return variable(d.getJSONObject("selected"));
        if(d.getString("sourceHead").contains("VARIABLE")&&d.getString("targetHead").contains("VARIABLE"))return d;
        if(d.has("children"))for(Object value:d.getJSONArray("children")){
            var found=variable(((JSONObject)value).getJSONObject("edit"));if(found!=null)return found;
        }
        return null;
    }
    private static void rejectNative(NativeRepairTrace trace,JSONObject json,String name){
        checks++;
        try{trace.replay(json);}catch(IllegalArgumentException|IllegalStateException expected){return;}
        throw new AssertionError("Issued trace accepted "+name);
    }
    private static JSONObject direction(Canonical.Prepared a,Canonical.Prepared b)throws Exception{
        var af=delegate(a).comparisonForms();var bf=delegate(b).comparisonForms();
        check(!gate("supportsPair",af,bf),"Old direct route remains unchanged");
        check(gate("supportsSupplementalPair",af,bf),"Actual supplemental branch admitted");
        var fast=CanonicalRepairTraces.fast(a,b);
        var cert=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
        var fj=fast.json();var cj=cert.json();
        if(fast.distance()<=cert.distance()){
            check(fast.replay(fj)==fast.distance(),"Issued Fast shorter-first replay");check(cert.replay(cj)==cert.distance(),"Issued certified replay");
        }else{
            check(cert.replay(cj)==cert.distance(),"Issued certified shorter-first replay");check(fast.replay(fj)==fast.distance(),"Issued Fast replay");
        }
        check(fast.distance()==32&&cert.distance()==32,"Actual selected paid candidate, not old33");
        var paid=fj.getJSONObject("selectedPaidDeclarationCorrespondence");
        var mapping=fj.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping");
        var outerA=af.get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var outerB=bf.get(0).getMatrixQuantiVars().stream().map(QuantiVar::getName).toList();
        var localA=new TreeSet<String>();var localB=new TreeSet<String>();
        localNames(af.get(0).getMatrixEGraph(),localA);localNames(bf.get(0).getMatrixEGraph(),localB);
        check(!localA.isEmpty()&&!localB.isEmpty(),"Live local occurrences on both endpoints");
        check(Collections.disjoint(localA,outerA)&&Collections.disjoint(localB,outerB),"Actual fresh local/outer aliases");
        check(!paid.isEmpty(),"Selected nonempty paid diagonal map");
        for(String key:paid.keySet())check(outerA.contains(key)&&outerB.contains(paid.getString(key))
                && paid.getString(key).equals(mapping.getString(key)),"Paid outer vector membership and actual map precedence");
        check(new HashSet<>(mapping.toMap().values()).size()==mapping.length(),"Actual selected map injective");
        var comparison=NativeOperationComparison.compare(a,b,fast,cert);
        check(comparison.status()==NativeOperationComparison.Status.MATCHED,"Source-linked selected actions: "+comparison.reason()+" "+comparison.evidence());
        var report=comparison.evidence().getJSONObject("diagnostic");
        check(NativeOperationComparison.compareCaptured(a,b,fast,cert,copy(report)).status()==NativeOperationComparison.Status.MATCHED,
                "Independent diagnostic is bound to issued native traces");
        var controls=new JSONArray();
        for(String mutation:List.of("missing-paid","foreign-occurrence","local-source","local-target")){
            var corrupt=copy(report);var arm=corrupt.getJSONObject("fast");
            var phase=arm.getJSONArray("phases").getJSONObject(0);var map=phase.getJSONObject("alphaMapping");
            if(mutation.equals("missing-paid"))arm.put("selectedPaidDeclarationCorrespondence",new JSONObject());
            else if(mutation.equals("foreign-occurrence")){
                var v=variable(phase.getJSONObject("receipt").getJSONObject("derivation"));check(v!=null,"Actual variable decision exists");v.put("sourceSha256","foreign");
            }else{
                String key=paid.keys().next(),target=map.getString(key);
                if(mutation.equals("local-source")){map.remove(key);map.put(localA.first(),target);}
                else map.put(key,localB.first());
            }
            rejectNative(fast,arm,mutation);
            var rejected=NativeOperationComparison.compareCaptured(a,b,corrupt);
            check(rejected.status()==NativeOperationComparison.Status.INVALID_REPLAY,"Independent rejection "+mutation);
            controls.put(new JSONObject().put("mutation",mutation).put("reason",rejected.reason()).put("error",rejected.evidence().optString("error")));
        }
        check(fast.json().similar(fj)&&cert.json().similar(cj),"Negative controls leave issued traces unchanged");
        return new JSONObject().put("nativeFast",fj).put("nativeCertified",cj).put("comparison",comparison.evidence()).put("controls",controls)
                .put("paid",paid).put("actualMapping",mapping).put("outerSource",outerA).put("outerTarget",outerB)
                .put("localSource",localA).put("localTarget",localB).put("publicCertification","BLOCKED");
    }
    public static void main(String[] args)throws Exception{
        var out=Path.of(args[1]);Files.createDirectory(out);
        for(Object item:new JSONArray(Files.readString(Path.of(args[0])))){
            var identity=(JSONObject)item;long start=System.nanoTime();var pair=LocalOwnerTraceTest.prepare(identity);
            for(int dir=0;dir<2;dir++)Files.writeString(out.resolve(identity.getString("id")+"-"+dir+".json"),
                    direction(pair.get(dir),pair.get(1-dir)).put("identity",identity).put("direction",dir).toString(),StandardOpenOption.CREATE_NEW);
            check((System.nanoTime()-start)/1e9<60,"Natural file including both directions and controls below60seconds");
        }
        System.out.println("BothLocalPaidReplayTest: "+checks+" checks passed");
    }
}
