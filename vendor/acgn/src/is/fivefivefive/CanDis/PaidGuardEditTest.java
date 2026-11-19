package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

public final class PaidGuardEditTest {
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
        var matchMethod=method(NormalForm.class,"paidGuardEditMatch",NormalForm.class,QuantiVar.class,QuantiVar.class);
        var old=method(NormalForm.class,"guardedProjectionMatch",NormalForm.class,QuantiVar.class,QuantiVar.class);
        for(int direction=0;direction<2;direction++) {
            var a=pair.get(direction);var b=pair.get(1-direction);var da=delegate(a);var db=delegate(b);
            var af=da.comparisonForms().get(1);var bf=db.comparisonForms().get(1);
            var aq=af.getMatrixQuantiVars().get(0);var bq=bf.getMatrixQuantiVars().get(0);
            negative(old.invoke(af,bf,aq,bq)==null,"old equal-body/HISTORICALLY gate rejects");
            Object match=matchMethod.invoke(af,bf,aq,bq);check(match!=null,"source-issued intersection/guard-edit authority");
            var valid=method(match.getClass(),"valid");check((Boolean)valid.invoke(match),"actual unchanged future subtree authority");
            var evidence=(JSONObject)method(match.getClass(),"evidence").invoke(match);
            var sourceFuture=da.comparisonForms().get(2);var targetFuture=db.comparisonForms().get(2);
            check(evidence.getJSONObject("sourceRetainedRead").getLong("occurrence")!=evidence.getLong("paidGuardOccurrence")
                &&evidence.getJSONObject("targetRetainedRead").getLong("occurrence")!=evidence.getLong("paidGuardOccurrence"),"deleted guard and retained read have distinct occurrences");
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==6&&certified.distance()==6,"computed one declaration plus five owner edits");
            check(Canonical.distance(a,b)==6,"numeric and captured choices agree");
            check(fast.json().getInt("temporalDistance")==0&&fast.json().getInt("quantifierDistance")==1&&fast.json().getInt("matrixDistance")==5,"no scalar substitution or duplicate payment");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"source-linked ordered native effects");
            var ops=fast.json().getJSONArray("phases").getJSONObject(1).getJSONObject("program").getJSONArray("operations");
            check(ops.length()==6,"complete six owner operations");
            for(int i=0;i<6;i++)check(ops.getJSONObject(i).getInt("cost")==(i==0?0:1),"exact per-operation charge "+i);
            check(ops.getJSONObject(1).getString("kind").equals("update"),"selected body-head update");
            for(int i=2;i<6;i++)check(ops.getJSONObject(i).getString("kind").equals(direction==0?"delete":"insert"),"ordered guard CRUD "+i);
            for(Object value:fast.json().getJSONArray("phases").getJSONObject(2).getJSONObject("program").getJSONArray("operations"))
                check(((JSONObject)value).getInt("cost")==0,"whole unchanged future tree, not just REF metadata");
            check(fast.json().getJSONArray("selectedPaidGuardEdits").length()==1,"exact selected guard edit retained");
            for(String mode:List.of("extra","missing","order","root","charge","foreign-before","payment","phase","future","frame","coherent","dependency")) {
                var bad=fast.json();var owner=bad.getJSONArray("phases").getJSONObject(1).getJSONObject("program").getJSONArray("operations");
                switch(mode) {
                    case "extra" -> owner.put(new JSONObject(owner.getJSONObject(5).toString()));
                    case "missing" -> owner.remove(5);
                    case "order" -> {var first=owner.getJSONObject(2);owner.put(2,owner.getJSONObject(3));owner.put(3,first);}
                    case "root" -> owner.remove(0);
                    case "charge" -> owner.getJSONObject(2).put("cost",0);
                    case "foreign-before" -> owner.getJSONObject(1).getJSONObject(direction==0?"before":"after").put("head","foreign");
                    case "payment" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(1).getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case "phase" -> bad.getJSONArray("phases").getJSONObject(2).put("phase",1);
                    case "future" -> bad.getJSONArray("phases").getJSONObject(2).getJSONObject("program").getJSONArray("operations").getJSONObject(2).put("cost",1);
                    case "frame" -> bad.getJSONArray("selectedPaidGuardEdits").getJSONObject(0).put("sourceOwner","foreign");
                    case "dependency" -> bad.getJSONArray("selectedPaidGuardEdits").getJSONObject(0).getJSONObject("sourceDependencies").put("occurrenceToOwner",new JSONArray().put(0));
                    case "coherent" -> {
                        bad.getJSONArray("selectedPaidGuardEdits").getJSONObject(0).getJSONObject("sourceRetainedRead").put("occurrence",999999);
                        var op=bad.getJSONArray("phases").getJSONObject(2).getJSONObject("program").getJSONArray("operations").getJSONObject(2);
                        var nativeEvidence=new JSONObject(op.getJSONObject("before").getString("nativeEvidence"));nativeEvidence.put("sourceOccurrenceLineage",999999);
                        op.getJSONObject("before").put("nativeEvidence",nativeEvidence.toString());
                    }
                }
                reject(()->fast.replay(bad),mode);
            }
            var read=find(sourceFuture.getMatrixEGraph(),EGraphNode.Opcode.IN);check(read!=null,"actual entire future membership subtree");
            set(read,"opcode",EGraphNode.Opcode.NOT_IN);
            negative(!(Boolean)valid.invoke(match),"same REF metadata with changed future body rejects");
            reject(()->fast.replay(fast.json()),"changed actual future body even with untouched submitted program");set(read,"opcode",EGraphNode.Opcode.IN);
            Object exact=get(read.getChildren().get(0),"exactAlloyType");set(read.getChildren().get(0),"exactAlloyType",ExactAlloyType.boolType());
            negative(!(Boolean)valid.invoke(match),"retained read type substitution");set(read.getChildren().get(0),"exactAlloyType",exact);
            var sr=sourceFuture.getMatrixEGraph();var tr=targetFuture.getMatrixEGraph();
            Object st=get(sr,"exactAlloyType"),tt=get(tr,"exactAlloyType");
            set(sr,"exactAlloyType",aq.getExactAlloyType());set(tr,"exactAlloyType",bq.getExactAlloyType());
            negative(!(Boolean)valid.invoke(match),"coherent forged non-Boolean phase-root types");
            reject(()->fast.replay(fast.json()),"forged roots cannot inherit a fake Boolean default");
            set(sr,"exactAlloyType",st);set(tr,"exactAlloyType",tt);
            Object role=get(sourceFuture,"temporalOp");set(sourceFuture,"temporalOp",NormalForm.TemporalOp.HISTORICALLY);
            negative(!(Boolean)valid.invoke(match),"future role change");set(sourceFuture,"temporalOp",role);
            var foreign=delegate(LocalOwnerTraceTest.prepare(identity).get(direction)).comparisonForms().get(1);
            negative(matchMethod.invoke(af,bf,foreign.getMatrixQuantiVars().get(0),bq)==null,"same metadata foreign selected declaration");
            var root=af.getMatrixEGraph();set(af,"matrixEGraphRoot",foreign.getMatrixEGraph());
            negative(!(Boolean)valid.invoke(match),"foreign endpoint and genuine foreign source authority");set(af,"matrixEGraphRoot",root);
            Object cardinality=get(aq,"cardinality");set(aq,"cardinality",QuantiVar.Cardinality.SET);
            negative(!(Boolean)valid.invoke(match),"unmodeled multiplicity");set(aq,"cardinality",cardinality);
            set(aq,"disjointnessClass",1);negative(!(Boolean)valid.invoke(match),"unmodeled disjointness");set(aq,"disjointnessClass",0);
            check((Boolean)valid.invoke(match)&&fast.replay(fast.json())==6,"restored actual authorities and detached replay");
            results.put(new JSONObject().put("direction",direction).put("fast",fast.distance()).put("certified",certified.distance()).put("authority",evidence));
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results).put("publicCertification","BLOCKED");
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",2));
    }
}
