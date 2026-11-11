package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.*;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.metric.*;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import org.json.*;
import parser.ast.nodes.ModelUnit;

/** Separates scoped candidate coverage from the fixed-candidate cost boundary. */
public final class ScopedPaidAdmissionTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static List<NormalForm> prepare(CompModule m,String name){
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
        var a=new IRAgent(v.getForest().get(v.getForestId(name)));a.computeNormalForm();
        var f=List.copyOf(a.normalForms());f.forEach(NormalForm::freezeForCertification);return f;
    }
    private static Object invoke(Class<?> c,String name,Class<?>[] types,Object receiver,Object... args){
        try{var m=c.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(receiver,args);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static void field(Object x,String name,Object value){
        try{var f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value);}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    private static List<EGraphNode> nodes(EGraphNode n){
        var out=new ArrayList<EGraphNode>();out.add(n);for(var c:n.getChildren())out.addAll(nodes(c));return out;
    }
    private static EGraphNode owner(NormalForm f){
        return nodes(f.getMatrixEGraph()).stream().filter(n->n.getChildren().size()==2
                && n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL).findFirst().orElseThrow();
    }
    private static void corrupt(List<NormalForm> a,List<NormalForm> b,Consumer<NormalForm> edit,String why){
        var copy=NormalForm.fastComparisonViews(a);edit.accept(copy.get(0));
        check(!FastPaidDeclarationAlignment.supportsPair(copy,NormalForm.fastComparisonViews(b)),"ADMISSION rejects "+why);
    }
    @SuppressWarnings("unchecked")
    private static void candidates(List<NormalForm> a,List<NormalForm> b){
        var af=NormalForm.fastComparisonViews(a);var bf=NormalForm.fastComparisonViews(b);
        check(FastPaidDeclarationAlignment.supportsPair(af,bf),"ADMISSION source-linked single local route");
        check(!FastNestedPaidScope.supportsPair(af,bf),"ADMISSION existing nested route remains distinct");
        var av=RepairProjection.project(TheoryAlloyAdapter.adapt(a),a).phases().get(0);
        var bv=RepairProjection.project(TheoryAlloyAdapter.adapt(b),b).phases().get(0);
        var aq=a.get(0).getMatrixQuantiVars();var bq=b.get(0).getMatrixQuantiVars();
        var al=(List<QuantiVar>)invoke(CanonicalDistance.class,"canonicalQuantifierOrder",new Class<?>[]{List.class},null,aq);
        var bl=(List<QuantiVar>)invoke(CanonicalDistance.class,"canonicalQuantifierOrder",new Class<?>[]{List.class},null,bq);
        check(al.equals(aq)&&bl.equals(bq),"ORDER mixed prefix barriers remain in source order");
        int[][] dp=(int[][])invoke(CanonicalDistance.class,"quantificationDp",new Class<?>[]{List.class,List.class},null,al,bl);
        check(dp[al.size()][bl.size()]==3,"DECLARATION independent minimum3");
        var trace=new JSONArray();
        FastPaidDeclarationAlignment.enumerate(al,bl,dp,(x,y)->(Integer)invoke(CanonicalDistance.class,"quantifierUpdateCost",
                new Class<?>[]{QuantiVar.class,QuantiVar.class},null,x,y),map->{
            check(map.size()==1,"COVERAGE paid diagonal, no local map");
            int[] cm=new int[av.bindings().size()];Arrays.fill(cm,-1);
            for(var e:map.entrySet()){
                int i=0,j=0;while(!aq.get(i).getName().equals(e.getKey()))i++;while(!bq.get(j).getName().equals(e.getValue()))j++;
                final int x=i,y=j;
                var l=av.bindings().stream().filter(v->v.role()==RepairView.BindingRole.MATRIX&&v.ordinal()==x).findFirst().orElseThrow();
                var r=bv.bindings().stream().filter(v->v.role()==RepairView.BindingRole.MATRIX&&v.ordinal()==y).findFirst().orElseThrow();
                cm[l.coordinate()]=r.coordinate();
            }
            int fast=(Integer)invoke(CanonicalDistance.class,"eGraphDistance",new Class<?>[]{EGraphNode.class,EGraphNode.class,Map.class},
                    null,af.get(0).getMatrixEGraph(),bf.get(0).getMatrixEGraph(),map);
            int cert;
            try{
                var c=Class.forName("is.fivefivefive.CanDis.metric.QuotientRepairDistance$MatrixDistanceContext");
                var ctor=c.getDeclaredConstructor(int[].class);ctor.setAccessible(true);
                cert=(Integer)invoke(c,"distance",new Class<?>[]{RepairView.Node.class,RepairView.Node.class},ctor.newInstance((Object)cm),av.matrix(),bv.matrix());
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
            int expected=map.equals(Map.of("_q0","_q0"))?19:20;
            check(fast==expected&&cert==expected,"FIXED_COST same admitted candidate "+map);
            trace.put(new JSONObject().put("paid",map).put("fastFixed",fast).put("certifiedFixed",cert));
        });
        check(trace.length()==3,"COVERAGE all three minimum declaration paths");
        var replay=CanonicalDistance.replayWithSlices(CanonicalDistance.prepare(a),CanonicalDistance.prepare(b));
        check(replay.getInt("distance")==22,"SELECTION native22");
        check(replay.getJSONArray("phases").getJSONObject(0).getJSONObject("alphaMapping").similar(new JSONObject(Map.of("_q0","_q0"))),
                "SELECTION paid outer link, no local capture");
        System.out.println(new JSONObject().put("stage","scoped-admission-candidate-cost").put("candidates",trace));
    }
    public static void main(String[] args)throws Exception{
        String text=Files.readString(Path.of("classified-data/classroom_fol/both/JoNXaXfKNJzGh3BFa_inv12.als"));
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);var a=prepare(m,"inv12");var b=prepare(m,"inv12c");
        candidates(a,b);candidates(b,a);
        check(FastLocalPaidScope.supports(a.get(0)),"source local scope checked directly");
        check(!FastPaidDeclarationAlignment.supportsPair(a,a),"local/local remains unsupported");
        corrupt(a,b,f->field(owner(f),"sourceOccurrenceLineage",-1L),"owner");
        corrupt(a,b,f->field(owner(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"declaration");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"declared variable");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"domain occurrence");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","alloy/signature/Student"),"same-carrier domain substitution");
        corrupt(a,b,f->field(owner(f),"opcode",EGraphNode.Opcode.ONE),"LONE cannot become ONE");
        corrupt(a,b,f->field(owner(f).getChildren().get(0),"opcode",EGraphNode.Opcode.DISJ),"disjoint local");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"set local");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local declaration capture");
        corrupt(a,b,f->{var n=nodes(owner(f)).stream().filter(v->v.getOpcode()==EGraphNode.Opcode.VARIABLE&&v.hasComparisonLocalCoordinate()).reduce((x,y)->y).orElseThrow();
            field(n,"alphaName","_q0");},"local reference capture");
        corrupt(a,b,f->{var n=nodes(f.getMatrixEGraph()).stream().filter(v->v.getOpcode()==EGraphNode.Opcode.VARIABLE&&"_q0".equals(v.getAlphaName())).findFirst().orElseThrow();
            field(n,"alphaName",owner(f).getChildren().get(0).getChildren().get(1).getAlphaName());},"escaped local");
        var foreign=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,text),"inv12");
        corrupt(a,b,f->field(f,"certificationMatrixEGraphRoot",foreign.get(0).getCertificationMatrixEGraph()),"foreign source");
        var ft=owner(foreign.get(0)).getChildren().get(0).getChildren().get(1).getExactAlloyType();
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",ft),"foreign module/type");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",null),"missing type");
        corrupt(a,b,f->field(f,"params",List.of(a.get(0).getMatrixQuantiVars().get(0))),"parameter boundary");
        corrupt(a,b,f->field(f,"inheritedQuantiVars",List.of(a.get(0).getMatrixQuantiVars().get(0))),"inherited owner boundary");
        corrupt(a,b,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal operator boundary");
        corrupt(a,b,f->{
            try{
                var constructor=NormalForm.PhaseLocalBindingImport.class.getDeclaredConstructors()[0];constructor.setAccessible(true);
                var o=owner(a.get(0));String name=o.getChildren().get(0).getChildren().get(1).getAlphaName();
                var q=a.get(0).getLocalQuantiVars().stream().filter(v->v.getName().equals(name)).findFirst().orElseThrow();
                var imported=constructor.newInstance(q,a.get(0),o,o.getSourceOccurrenceLineage(),"owner","target",q.getBindingPath());
                field(f,"phaseLocalBindingImports",List.of(imported));
            }catch(ReflectiveOperationException e){throw new AssertionError(e);}
        },"phase-local import boundary");
        StringBuilder control=new StringBuilder("sig A { r:set A } sig T in A {}\npred base { all x:A | some x.r }\n");
        for(String outer:List.of("all","some","one","lone"))for(String local:List.of("all","some","one","lone"))
            control.append("pred p_").append(outer).append('_').append(local).append(" { ").append(outer)
                .append(" x:A | x in x.r implies (").append(local).append(" t:T | t in x.r) }\n");
        control.append("""
                pred parameter[p:A] { lone x:A | p in x.r implies (lone t:T | t in x.r) }
                pred temporal { lone x:A | always (x in x.r implies (lone t:T | t in x.r)) }
                pred grouped { lone x,y:A | x in y.r implies (lone t:T | t in x.r) }
                pred disjoint { all disj x,y:A | x in y.r implies (lone t:T | t in x.r) }
                pred setOuter { some x:set A | x in x.r implies (lone t:T | t in x.r) }
                pred nested { lone x:A | x in x.r implies (lone t:T | t in x.r implies (lone u:T | u in t.r)) }
                pred dependent { lone x:A | x in x.r implies (lone t:x.r | t in x.r) }
                pred localDisjoint { lone x:A | x in x.r implies (lone disj t,u:T | t in u.r) }
                """);
        var model=CompUtil.parseEverything_fromString(A4Reporter.NOP,control.toString());var base=prepare(model,"base");
        var retained=EnumSet.noneOf(QuantiVar.Quantifier.class);
        for(String outer:List.of("all","some","one","lone"))for(String local:List.of("all","some","one","lone")){
            var forms=prepare(model,"p_"+outer+"_"+local);
            check(FastPaidDeclarationAlignment.supportsPair(forms,base),"analogous admission "+outer+"/"+local);
            check(FastPaidDeclarationAlignment.supportsPair(base,forms),"analogous reverse "+outer+"/"+local);
            for(var n:nodes(forms.get(0).getMatrixEGraph()))if(n.getChildren().size()==2&&n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL)
                retained.add(switch(n.getOpcode()){case FORALL->QuantiVar.Quantifier.ALL;case EXISTS->QuantiVar.Quantifier.SOME;case ONE->QuantiVar.Quantifier.ONE;case LONE->QuantiVar.Quantifier.LONE;default->throw new AssertionError(n.getOpcode());});
        }
        System.out.println("normalized retained local kinds="+retained);
        check(retained.equals(EnumSet.of(QuantiVar.Quantifier.ONE,QuantiVar.Quantifier.LONE)),
                "analogous ALL/SOME normalized to prefixes; only observed retained kinds claimed here");
        for(String name:List.of("parameter","temporal","grouped","disjoint","setOuter","nested","dependent","localDisjoint")){
            var forms=prepare(model,name);
            check(!FastPaidDeclarationAlignment.supportsPair(forms,base),"unsupported "+name);
            check(!FastPaidDeclarationAlignment.supportsPair(base,forms),"unsupported reverse "+name);
        }
        check(FastPaidDeclarationAlignment.supportsPair(a,b),"original authorities unchanged");
        System.out.println("ScopedPaidAdmissionTest: "+checks+" checks passed");
    }
}
