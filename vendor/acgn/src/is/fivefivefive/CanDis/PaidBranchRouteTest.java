package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import parser.ast.nodes.ModelUnit;

public final class PaidBranchRouteTest {
    private static int checks,negatives;
    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    private static Object get(Object o,String name)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String name,Object value)throws Exception{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    private static Method method(Class<?> c,String name,Class<?>...args)throws Exception{var m=c.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static void reject(Runnable action,String message){checks++;negatives++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError("Accepted "+message);}
    private static CanonicalDistance.Prepared delegate(Canonical.Prepared p)throws Exception{return (CanonicalDistance.Prepared)get(p,"delegate");}
    private static void post(Object tree,List<Object> out)throws Exception{for(var child:(List<?>)get(tree,"children"))post(child,out);out.add(tree);}
    private static Map<Object,Object> selected(CanonicalDistance.Prepared a,CanonicalDistance.Prepared b)throws Exception{
        var captured=method(CanonicalDistance.class,"captureDecisions",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class).invoke(null,a,b);
        var alignment=(OrderedTreeEditDistance.Alignment)get(get(captured,"decisions"),"temporal");
        var xs=new ArrayList<Object>();var ys=new ArrayList<Object>();post(get(a,"temporalTree"),xs);post(get(b,"temporalTree"),ys);
        var result=new IdentityHashMap<Object,Object>();for(var m:alignment.matches())result.put(xs.get(m.leftPostorder()-1),ys.get(m.rightPostorder()-1));return result;
    }
    private static Object authority(CanonicalDistance.Prepared a,CanonicalDistance.Prepared b,Map<Object,Object> selected)throws Exception{
        var x=a.comparisonForms().get(1).getMatrixQuantiVars().get(0);var y=b.comparisonForms().get(1).getMatrixQuantiVars().get(0);
        var declarations=new IdentityHashMap<QuantiVar,QuantiVar>();declarations.put(x,y);
        return method(Class.forName("is.fivefivefive.CanDis.core.CanonicalDistance$PaidBranchRoute"),"authority",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class,int.class,QuantiVar.class,QuantiVar.class,Map.class,Map.class)
            .invoke(null,a,b,1,x,y,selected,declarations);
    }
    private static List<Canonical.Prepared> prepareText(String text,JSONObject identity)throws Exception{
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var pair=new ArrayList<Canonical.Prepared>();
        for(var name:List.of(identity.getString("left"),identity.getString("right"))){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);pair.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}return pair;
    }
    public static void main(String[] args)throws Exception{
        var identity=new JSONObject(Files.readString(Path.of(args[0])));var out=Path.of(args[1]);Files.createDirectory(out);
        var pair=LocalOwnerTraceTest.prepare(identity);var results=new JSONArray();
        for(int direction=0;direction<2;direction++){
            var a=pair.get(direction);var b=pair.get(1-direction);
            var da=delegate(a);var db=delegate(b);var selected=selected(da,db);
            check(!(Boolean)method(CanonicalDistance.class,"sameSelectedTemporalRoute",CanonicalDistance.Prepared.class,CanonicalDistance.Prepared.class,int.class,Map.class).invoke(null,da,db,2,selected),"old owner-count gate rejects exact natural route");
            var authority=authority(da,db,selected);check(authority!=null,"actual source-issued branch authority");
            var authorityValid=method(authority.getClass(),"authorityValid");
            var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("identity",identity).put("direction",direction).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("selected-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("replayed-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==8&&certified.distance()==8,"actual computed event1796 distances");
            check(Canonical.distance(a,b)==8,"numeric and captured agree");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"ordered authentic native effects");
            var json=fast.json();
            check(json.getInt("temporalDistance")==2&&json.getInt("quantifierDistance")==1&&json.getInt("matrixDistance")==5,"all paid charges retained");
            check(json.getJSONArray("selectedPaidBranchRoutes").length()==1,"checked selected branch authority");
            int total=0;int[] counts={2,2,4,4};
            for(int phase=0;phase<4;phase++){
                var ops=json.getJSONArray("phases").getJSONObject(phase).getJSONObject("program").getJSONArray("operations");
                check(ops.length()==counts[phase],"complete phase operations "+phase);total+=ops.length();
                for(int i=0;i<ops.length();i++)check(ops.getJSONObject(i).getInt("cost")==((phase==1&&i==1||phase==3)?1:0),"exact ordered charge "+phase+":"+i);
            }
            check(total==12,"no dropped native actions");
            for(String mode:List.of("missing","order","charge","ref","declaration","temporal","branch","dependency","coherent","temporal-order","temporal-substitution","declaration-missing","ref-substitution","retained-order")){
                var bad=fast.json();var phases=bad.getJSONArray("phases");
                var drop=phases.getJSONObject(3).getJSONObject("program").getJSONArray("operations");
                var frame=bad.getJSONArray("selectedPaidBranchRoutes").getJSONObject(0);
                switch(mode){
                    case "missing" -> drop.remove(3);
                    case "order" -> {var x=drop.get(0);drop.put(0,drop.get(1));drop.put(1,x);}
                    case "charge" -> drop.getJSONObject(3).put("cost",0);
                    case "ref" -> phases.getJSONObject(1).getJSONObject("program").getJSONArray("operations").getJSONObject(1).put("cost",0);
                    case "declaration" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(1).getJSONArray("operations").getJSONObject(0).put("cost",0);
                    case "temporal" -> bad.getJSONObject("nativeTemporalProgram").put("cost",0);
                    case "branch" -> frame.put("retainedRole",1);
                    case "dependency" -> frame.getJSONObject("source").getJSONObject("dependencies").put("occurrenceToOwner",new JSONArray().put(0));
                    case "coherent" -> {frame.put("retainedCost",1);phases.getJSONObject(2).getJSONObject("program").getJSONArray("operations").getJSONObject(2).put("cost",1);}
                    case "temporal-order" -> {var ops=bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations");var x=ops.get(0);ops.put(0,ops.get(1));ops.put(1,x);}
                    case "temporal-substitution" -> bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations").getJSONObject(0).put("kind","delete");
                    case "declaration-missing" -> bad.getJSONArray("nativeDeclarationPrograms").getJSONObject(1).getJSONArray("operations").remove(0);
                    case "ref-substitution" -> phases.getJSONObject(1).getJSONObject("program").getJSONArray("operations").getJSONObject(1).put("kind","delete");
                    case "retained-order" -> {var ops=phases.getJSONObject(2).getJSONObject("program").getJSONArray("operations");var x=ops.get(2);ops.put(2,ops.get(3));ops.put(3,x);}
                }
                reject(()->fast.replay(bad),mode);
            }
            var owner=da.comparisonForms().get(1);var q=owner.getMatrixQuantiVars().get(0);
            var root=da.comparisonForms().get(2).getMatrixEGraph();var read=root.getChildren().get(0);var variable=read.getChildren().get(0);
            var ref=owner.getMatrixEGraph().getChildren().get(0);
            var foreign=delegate(LocalOwnerTraceTest.prepare(identity).get(direction));
            var substitutions=new ArrayList<Object[]>();
            substitutions.add(new Object[]{read,"opcode",EGraphNode.Opcode.NOT_IN});
            substitutions.add(new Object[]{variable,"exactAlloyType",ExactAlloyType.boolType()});
            substitutions.add(new Object[]{root,"exactAlloyType",q.getExactAlloyType()});
            substitutions.add(new Object[]{variable,"sourceOccurrenceLineage",999999L});
            substitutions.add(new Object[]{owner,"temporalOp",NormalForm.TemporalOp.HISTORICALLY});
            substitutions.add(new Object[]{da.comparisonForms().get(2),"phasePath","foreign-phase"});
            substitutions.add(new Object[]{q,"cardinality",QuantiVar.Cardinality.SET});
            substitutions.add(new Object[]{q,"disjointnessClass",1});
            substitutions.add(new Object[]{q,"quantifier",QuantiVar.Quantifier.NO});
            substitutions.add(new Object[]{owner,"matrixEGraphRoot",foreign.comparisonForms().get(1).getMatrixEGraph()});
            substitutions.add(new Object[]{da.comparisonForms().get(2),"matrixEGraphRoot",foreign.comparisonForms().get(2).getMatrixEGraph()});
            substitutions.add(new Object[]{ref,"sourceName","0:2:foreign"});
            var frame=q.getDeclarationFrames().get(0);
            substitutions.add(new Object[]{frame,"dependencies",List.of(q)});
            substitutions.add(new Object[]{frame,"owner",foreign.comparisonForms().get(1)});
            var binary=(direction==0?da:db).comparisonForms().get(3).getMatrixEGraph().getChildren().get(0);
            substitutions.add(new Object[]{binary,"childClasses",List.of(((List<?>)get(binary,"childClasses")).get(0))});
            for(var substitution:substitutions){
                var object=substitution[0];var field=(String)substitution[1];var original=get(object,field);set(object,field,substitution[2]);
                try{check(!(Boolean)authorityValid.invoke(authority),"changed live "+field+" rejects");negatives++;reject(()->fast.replay(fast.json()),"live "+field+" with unchanged evidence");}
                finally{set(object,field,original);}
            }
            check((Boolean)authorityValid.invoke(authority)&&fast.replay(fast.json())==8,"restored authentic object graph");
            results.put(new JSONObject().put("direction",direction).put("fast",fast.distance()).put("certified",certified.distance()).put("authority",json.getJSONArray("selectedPaidBranchRoutes")));
        }
        // Derived right-role control checks authority, not a new natural metric pass or a phase permutation.
        var text=Files.readString(Path.of(identity.getString("source")));
        var derived=text.replace("f in Trash until f not in Trash","f not in Trash until f in Trash");
        check(!derived.equals(text),"derived source construction");Files.writeString(out.resolve("derived-right-role.als"),derived,StandardOpenOption.CREATE_NEW);
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,derived);var synthetic=new ArrayList<Canonical.Prepared>();
        for(var name:List.of(identity.getString("left"),identity.getString("right"))){var v=new MASGVisitor(new GlobalVariables(),Set.of(name),module);v.visit(new ModelUnit(null,module),null);synthetic.add(Canonical.prepare(v.getForest().get(v.getForestId(name))));}
        for(int direction=0;direction<2;direction++){
            var a=delegate(synthetic.get(direction));var b=delegate(synthetic.get(1-direction));var choices=selected(a,b);var control=authority(a,b,choices);
            check(control!=null&&(int)get(control,"side")==1&&(Boolean)method(control.getClass(),"authorityValid").invoke(control),"valid derived right-role authority");
            check(!(Boolean)method(control.getClass(),"route",int.class).invoke(control,2),"right-role authority cannot reorder fixed metric phase pairs");
            var broken=new IdentityHashMap<Object,Object>();broken.putAll(choices);broken.clear();
            check(authority(a,b,broken)==null,"foreign or missing selected temporal matches reject");negatives++;
        }
        var contextual=text.replace("pred inv12 {","pred inv12 { after (").replace("pred inv12c {","pred inv12c { before (")
            .replace("f not in Trash\n}","f not in Trash)\n}").replace("always f in Trash\n}","always f in Trash)\n}");
        check(!contextual.equals(text),"contextual source derivation");Files.writeString(out.resolve("derived-paid-context.als"),contextual,StandardOpenOption.CREATE_NEW);
        var contextPair=prepareText(contextual,identity);var contexts=new JSONArray();
        for(int direction=0;direction<2;direction++){
            var a=contextPair.get(direction);var b=contextPair.get(1-direction);var fast=CanonicalRepairTraces.fast(a,b);
            var certified=CanonicalRepairTraces.certified(CanonicalAlloyPipeline.prepare(a),CanonicalAlloyPipeline.prepare(b));
            var row=new JSONObject().put("direction",direction).put("derivedFrom",identity).put("fast",fast.json()).put("certified",certified.json());
            Files.writeString(out.resolve("context-selected-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            QueueProbe.orderedReplay(fast.distance(),certified.distance(),()->fast.replay(fast.json()),()->certified.replay(certified.json()),row);
            var comparison=NativeOperationComparison.compare(a,b,fast,certified);row.put("comparison",comparison.evidence());
            Files.writeString(out.resolve("context-replayed-"+direction+".json"),row.toString(2),StandardOpenOption.CREATE_NEW);
            check(fast.distance()==9&&certified.distance()==9&&Canonical.distance(a,b)==9,"paid context numeric/capture/replay9/9");
            check(comparison.status()==NativeOperationComparison.Status.MATCHED,"context native effects");
            var json=fast.json();var temporal=json.getJSONObject("nativeTemporalProgram");
            check(json.getInt("temporalDistance")==3&&json.getInt("quantifierDistance")==1&&json.getInt("matrixDistance")==5,"surrounding temporal charge retained");
            var payments=json.getJSONArray("selectedPaidBranchRoutes").getJSONObject(0).getJSONArray("temporalPayments");
            check(payments.length()==2&&temporal.getJSONArray("operations").length()==3,"two local updates plus one independent update");
            var extra=temporal.getJSONArray("operations").getJSONObject(2);
            check(extra.getString("kind").equals("update")&&extra.getInt("cost")==1
                &&extra.getString("from").equals(direction==0?"AFTER":"BEFORE")&&extra.getString("to").equals(direction==0?"BEFORE":"AFTER"),"actual extra ancestor update survives alignment");
            for(String mode:List.of("missing","wrong-object","wrong-path","wrong-kind","order","unpaid","plausible-total","coherent-path")){
                var bad=fast.json();var ops=bad.getJSONObject("nativeTemporalProgram").getJSONArray("operations");
                var proof=bad.getJSONArray("selectedPaidBranchRoutes").getJSONObject(0).getJSONArray("temporalPayments");
                switch(mode){
                    case "missing" -> ops.remove(0);
                    case "wrong-object" -> proof.getJSONObject(0).put("operationIndex",2);
                    case "wrong-path" -> ops.getJSONObject(0).put("path",new JSONArray().put(0));
                    case "wrong-kind" -> ops.getJSONObject(0).put("kind","delete");
                    case "order" -> {var x=ops.get(0);ops.put(0,ops.get(1));ops.put(1,x);}
                    case "unpaid" -> ops.getJSONObject(0).put("cost",0);
                    case "plausible-total" -> {ops.remove(0);ops.put(new JSONObject(ops.getJSONObject(1).toString()));}
                    case "coherent-path" -> {var path=new JSONArray(ops.getJSONObject(2).getJSONArray("path").toString());ops.getJSONObject(0).put("path",path);proof.getJSONObject(0).put("path",new JSONArray(path.toString()));}
                }
                reject(()->fast.replay(bad),"contextual local payment "+mode);
            }
            check(fast.replay(fast.json())==9,"complete unchanged contextual program replay");
            contexts.put(new JSONObject().put("direction",direction).put("fast",fast.distance()).put("certified",certified.distance()).put("localPayments",payments).put("surroundingUpdate",extra));
        }
        var summary=new JSONObject().put("checks",checks).put("negativeControls",negatives).put("directions",results).put("publicCertification","BLOCKED");
        summary.put("derivedContextDirections",contexts);
        Files.writeString(out.resolve("summary.json"),summary.toString(2),StandardOpenOption.CREATE_NEW);System.out.println(summary);
    }
}
