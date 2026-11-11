package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Original occurrence/type/scope checks for paid outer edits beneath retained binders. */
public final class FastNestedPaidScopeTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){checks++;try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(why);}
    private static List<NormalForm> prepare(CompModule m,String name){
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
        var a=new IRAgent(v.getForest().get(v.getForestId(name)));a.computeNormalForm();
        var f=List.copyOf(a.normalForms());f.forEach(NormalForm::freezeForCertification);return f;
    }
    private static void field(Object x,String name,Object value){try{var f=x.getClass().getDeclaredField(name);f.setAccessible(true);f.set(x,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static List<EGraphNode> nodes(EGraphNode n){var out=new ArrayList<EGraphNode>();out.add(n);for(var c:n.getChildren())out.addAll(nodes(c));return out;}
    private static EGraphNode owner(NormalForm f){return nodes(f.getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.ONE
            && n.getChildren().size()==2 && n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL).findFirst().orElseThrow();}
    private static void corrupt(List<NormalForm> source,List<NormalForm> target,Consumer<NormalForm> edit,String why){
        var copy=NormalForm.fastComparisonViews(source);edit.accept(copy.get(0));
        check(!FastNestedPaidScope.supportsPair(copy,NormalForm.fastComparisonViews(target)),why);
    }
    public static void main(String[] args)throws Exception{
        String text=Files.readString(Path.of("classified-data/coursesOld/both/xJnitBpFv9K74WNvf_inv14.als"));
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var a=prepare(m,"inv14");var b=prepare(m,"inv14c");
        check(FastNestedPaidScope.supportsPair(a,b) && FastNestedPaidScope.supportsPair(b,a),"original endpoints both directions");
        check(FastNestedPaidScope.supportsPair(NormalForm.fastComparisonViews(a),NormalForm.fastComparisonViews(b)),"detached comparison occurrence transfer");
        check(!FastNestedPaidScope.supportsPair(a,a),"no new local/local alignment");
        check(b.get(0).getMatrixQuantiVars().stream().map(QuantiVar::getDisjointnessClass).toList().equals(List.of(0,1,1)),"target disjointness retained");
        corrupt(a,b,f->field(owner(f),"sourceOccurrenceLineage",-1L),"foreign local owner");
        corrupt(a,b,f->field(owner(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign declaration variable");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain");
        corrupt(a,b,f->field(owner(f),"opcode",EGraphNode.Opcode.EXISTS),"ONE cannot become SOME");
        corrupt(a,b,f->field(owner(f).getChildren().get(0),"opcode",EGraphNode.Opcode.DISJ),"local disjoint grouping not admitted");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"set-valued local declaration not admitted");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",null),"missing local parser type");
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local coordinate capture");
        corrupt(a,b,f->{var n=nodes(owner(f).getChildren().get(1)).stream().filter(x->x.getOpcode()==EGraphNode.Opcode.VARIABLE
                && x.hasComparisonLocalCoordinate()).findFirst().orElseThrow();field(n,"alphaName","_q0");},"local use cannot become outer reference");
        corrupt(a,b,f->{var n=nodes(f.getMatrixEGraph()).stream().filter(x->x.getOpcode()==EGraphNode.Opcode.VARIABLE
                && "_q0".equals(x.getAlphaName())).findFirst().orElseThrow();field(n,"comparisonLocalCoordinate",true);},"outer reference cannot acquire local marker");
        var foreign=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,text),"inv14");
        corrupt(a,b,f->field(f,"certificationMatrixEGraphRoot",foreign.get(0).getCertificationMatrixEGraph()),"foreign original source ledger");
        var foreignType=owner(foreign.get(0)).getChildren().get(0).getChildren().get(1).getExactAlloyType();
        corrupt(a,b,f->field(owner(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",foreignType),"foreign parser-module type");
        for(boolean deep:new boolean[]{false,true}){
            var copy=NormalForm.fastComparisonViews(a);EGraphNode n=new EGraphNode(99000,EGraphNode.Opcode.CONSTANT,List.of(),false,0,false,EGraphNode.Metatype.BOOLEAN);
            for(int i=0;i<(deep?70:14);i++)n=new EGraphNode(99001+i,EGraphNode.Opcode.AND,deep?List.of(n):List.of(n,n),false,2,false,EGraphNode.Metatype.BOOLEAN);
            field(copy.get(0),"certificationMatrixEGraphRoot",n);
            reject(()->FastNestedPaidScope.supportsPair(copy,b),deep?"depth budget":"expanded DAG visit budget");
        }
        var controls=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig A { r:set A }
                pred base { all x:A | some x }
                pred parameter[p:A] { all x:A | one y:A | all z:A-y | z in y.r and p in x.r }
                pred temporal { all x:A | always (one y:A | all z:A-y | z in y.r and x in z.r) }
                pred dependent { all x:A | one y:A,z:y.r | all t:A-z | t in x.r }
                """);
        var base=prepare(controls,"base");
        for(String name:List.of("parameter","temporal","dependent"))
            check(!FastNestedPaidScope.supportsPair(prepare(controls,name),base),"unsupported scope "+name);
        check(FastNestedPaidScope.supportsPair(a,b),"source remains intact after corrupt-copy tests");
        System.out.println("FastNestedPaidScopeTest: "+checks+" checks passed");
    }
}
