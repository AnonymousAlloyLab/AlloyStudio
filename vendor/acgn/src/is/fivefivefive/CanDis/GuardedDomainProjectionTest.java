package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

public final class GuardedDomainProjectionTest {
    private static int checks,negatives;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static void negative(boolean ok,String message){negatives++;check(ok,message);}
    private static Object get(Object o,String field)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String field,Object value)throws Exception{var f=o.getClass().getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
    private static Method method(Class<?> cls,String name,Class<?>...args)throws Exception{var m=cls.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static CanonicalDistance.Prepared delegate(Canonical.Prepared p)throws Exception{return (CanonicalDistance.Prepared)get(p,"delegate");}
    private static void reject(Runnable action,String message){negatives++;checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Accepted "+message);}
    private static EGraphNode find(EGraphNode root,EGraphNode.Opcode op){if(root.getOpcode()==op)return root;for(var c:root.getChildren()){var n=find(c,op);if(n!=null)return n;}return null;}
    public static void main(String[]args)throws Exception {
        var identity=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(identity);var results=new JSONArray();
        var projection=method(NormalForm.class,"guardedProjectionMatch",NormalForm.class,QuantiVar.class,QuantiVar.class);
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);var da=delegate(a);var db=delegate(b);
            var af=da.comparisonForms().get(1);var bf=db.comparisonForms().get(1);
            var aq=af.getMatrixQuantiVars().get(0);var bq=bf.getMatrixQuantiVars().get(0);
            var selected=new IdentityHashMap<QuantiVar,QuantiVar>();selected.put(aq,bq);
            var frame=method(CanonicalDistance.class,"sameDeclarationFrame",QuantiVar.class,QuantiVar.class,Map.class,Set.class);
            negative(!(Boolean)frame.invoke(null,aq,bq,selected,new HashSet<QuantiVar>()),"strict original domains still reject");
            Object match=projection.invoke(af,bf,aq,bq);check(match!=null,"actual owner-issued projection");
            var valid=method(match.getClass(),"valid");check((Boolean)valid.invoke(match),"immutable source and selected body authority");
            var evidence=(JSONObject)method(match.getClass(),"evidence").invoke(match);
            check(evidence.getJSONArray("sourceReads").length()==2&&evidence.getJSONArray("targetReads").length()==2,"owner/current and history reads retained");
            var sf=evidence.getJSONArray("sourceFrames").getJSONObject(0);var tf=evidence.getJSONArray("targetFrames").getJSONObject(0);
            check(!sf.getString("sourceDomain").equals(tf.getString("sourceDomain")),"receipt retains distinct original domains");
            check(sf.getString("carrier").equals(tf.getString("carrier"))&&sf.getJSONObject("loweredBody").similar(tf.getJSONObject("loweredBody")),"computed guarded model links retained");
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==1&&certified.distance()==1,"computed single charged declaration path");
            check(Canonical.distance(a,b)==1,"numeric/captured path agree");
            check(fast.json().getInt("quantifierDistance")==1&&fast.json().getInt("matrixDistance")==0&&fast.json().getInt("temporalDistance")==0,"one payment, no duplicate read charge");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"original source/selected operation correspondence");
            check(fast.json().getJSONArray("guardedDomainProjections").length()==1,"selected projection retained");
            var detached=fast.json();detached.getJSONArray("guardedDomainProjections").getJSONObject(0).put("sourceRoot",-1);
            check(fast.replay(fast.json())==1,"detached JSON mutation cannot alter expected authority");
            for(String mode:List.of("guard","domain","phase","payment","owner","type","dependencies","coherent-endpoint-projection","operator","lineage","extra","reorder")) {
                var bad=fast.json();
                switch(mode) {
                    case "guard" -> bad.getJSONArray("guardedDomainProjections").getJSONObject(0).put("sourceReads",new JSONArray());
                    case "domain" -> bad.getJSONArray("guardedDomainProjections").getJSONObject(0).put("sourceBinding","foreign");
                    case "phase" -> bad.getJSONArray("phases").getJSONObject(2).put("phase",1);
                    case "payment" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(1).getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case "owner","type" -> bad.getJSONArray("sourceBindingContexts").getJSONObject(1).getJSONArray("bindings").getJSONObject(0).put(mode,"foreign");
                    case "dependencies" -> bad.getJSONArray("guardedDomainProjections").getJSONObject(0).put("dependencyOwners",new JSONArray().put("foreign"));
                    case "operator","lineage" -> bad.getJSONArray("guardedDomainProjections").getJSONObject(0).getJSONArray("sourceReads").getJSONObject(0)
                        .put(mode.equals("operator")?"temporalOperator":"variableOccurrence",mode.equals("operator")?"HISTORICALLY":999999);
                    case "extra" -> bad.getJSONArray("guardedDomainProjections").getJSONObject(0).put("extra",true);
                    case "reorder" -> {var reads=bad.getJSONArray("guardedDomainProjections").getJSONObject(0).getJSONArray("sourceReads");var first=reads.getJSONObject(0);reads.put(0,reads.getJSONObject(1));reads.put(1,first);}
                    case "coherent-endpoint-projection" -> {
                        bad.getJSONArray("guardedDomainProjections").getJSONObject(0).put("sourceRoot",999999);
                        bad.getJSONArray("phases").getJSONObject(1).getJSONObject("program").getJSONArray("operations").getJSONObject(3).getJSONObject("before").put("head","foreign");
                    }
                }
                reject(()->fast.replay(bad),mode);
            }
            var root=af.getMatrixEGraph();var guard=find(root,EGraphNode.Opcode.NOT_IN);check(guard!=null,"actual owner-time guard node");
            Object type=get(guard.getChildren().get(1),"exactAlloyType");
            set(guard.getChildren().get(1),"exactAlloyType",ExactAlloyType.boolType());
            negative(projection.invoke(af,bf,aq,bq)==null,"forged guard signature type");set(guard.getChildren().get(1),"exactAlloyType",type);
            set(af,"matrixEGraphRoot",find(root,EGraphNode.Opcode.REF));
            negative(!(Boolean)valid.invoke(match),"erased owner guard cannot be licensed by old projection");set(af,"matrixEGraphRoot",root);
            var history=da.comparisonForms().get(2);var historyRoot=history.getMatrixEGraph();
            set(history,"matrixEGraphRoot",root);
            negative(projection.invoke(af,bf,aq,bq)==null,"guard moved into history");set(history,"matrixEGraphRoot",historyRoot);
            var foreign=delegate(LocalOwnerTraceTest.prepare(identity).get(direction)).comparisonForms().get(1);
            set(af,"matrixEGraphRoot",foreign.getMatrixEGraph());
            negative(!(Boolean)valid.invoke(match),"coherent foreign endpoint plus genuine foreign projection cannot replace pinned source");set(af,"matrixEGraphRoot",root);
            negative(projection.invoke(af,bf,foreign.getMatrixQuantiVars().get(0),bq)==null,"foreign selected declaration object");
            Object old=get(aq,"cardinality");set(aq,"cardinality",QuantiVar.Cardinality.SET);
            negative(projection.invoke(af,bf,aq,bq)==null,"unmodeled cardinality is not metadata authority");set(aq,"cardinality",old);
            set(aq,"disjointnessClass",1);negative(projection.invoke(af,bf,aq,bq)==null,"unmodeled disjointness");set(aq,"disjointnessClass",0);
            check((Boolean)valid.invoke(match),"all negative mutations restored");
            results.put(new JSONObject().put("direction",direction).put("fast",fast.distance()).put("certified",certified.distance()).put("projection",evidence));
        }
        repeatedDependencies(out);
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
    private static void repeatedDependencies(Path out)throws Exception {
        String text="var sig File { var link:set File }\nvar sig Trash in File {}\n"
            +"pred a { once all x,y:File,z:x.link - (y.link - x.link) | eventually (x in Trash and y in Trash and z in Trash) }\n"
            +"pred b { eventually some x,y:File,z:x.link - (y.link - x.link) | eventually (x in Trash and y in Trash and z in Trash) }\n";
        var source=out.resolve("repeated-dependencies.als");Files.writeString(source,text,StandardOpenOption.CREATE_NEW);
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var row=new JSONObject().put("source",source.toString()).put("sha256",hash).put("left","a").put("right","b");
        var pair=LocalOwnerTraceTest.prepare(row);var q=delegate(pair.get(0)).comparisonForms().get(1).getMatrixQuantiVars().get(2);
        var occurrences=q.getDeclarationFrames().get(0).dependencies();
        check(occurrences.size()==3&&occurrences.get(0)==occurrences.get(2),"actual repeated domain occurrences retained");
        var cls=Class.forName("is.fivefivefive.CanDis.core.CanonicalDistance$DependencyOccurrences");
        Object env=method(cls,"of",List.class).invoke(null,occurrences);
        check(((List<?>)get(env,"owners")).size()==2,"unique-owner environment has two owners");
        check(get(env,"occurrenceToOwner").equals(List.of(0,1,0)),"complete occurrence-to-owner map, not dropped repeats");
        check((Boolean)method(cls,"covers",List.class).invoke(env,occurrences),"legitimate repeated reads accepted");
        negative(!(Boolean)method(cls,"covers",List.class).invoke(env,List.of(occurrences.get(0),occurrences.get(1))),"erased occurrence rejected");
        Files.writeString(out.resolve("repeated-dependencies.json"),row.put("occurrenceToOwner",new JSONArray((List<?>)get(env,"occurrenceToOwner"))).toString(2),StandardOpenOption.CREATE_NEW);
    }
}
