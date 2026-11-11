package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Actual source lineage, lexical ownership and immutable local seeds. */
public final class FastLocalPaidScopeTest {
    private static int checks;
    private static void check(boolean value,String why) { checks++; if(!value) throw new AssertionError(why); }
    private static void reject(Runnable action,String why) {
        checks++; try { action.run(); } catch(IllegalArgumentException | IllegalStateException expected) { return; }
        throw new AssertionError(why);
    }
    private static List<NormalForm> prepare(edu.mit.csail.sdg.parser.CompModule m,String name) {
        var v=new MASGVisitor(new GlobalVariables(),Set.of(name),m);v.visit(new ModelUnit(null,m),null);
        var a=new IRAgent(v.getForest().get(v.getForestId(name)));a.computeNormalForm();
        var result=List.copyOf(a.normalForms());result.forEach(NormalForm::freezeForCertification);return result;
    }
    private static void field(Object target,String name,Object value) {
        try { var f=target.getClass().getDeclaredField(name); f.setAccessible(true);f.set(target,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private static List<EGraphNode> nodes(EGraphNode root) {
        var out=new ArrayList<EGraphNode>(); out.add(root);
        for(var c:root.getChildren()) out.addAll(nodes(c));return out;
    }
    private static EGraphNode binder(NormalForm form) {
        return nodes(form.getMatrixEGraph()).stream().filter(n->n.getOpcode()==EGraphNode.Opcode.EXISTS).findFirst().orElseThrow();
    }
    private static void corrupt(List<NormalForm> original,Consumer<NormalForm> change,String why) {
        var copy=NormalForm.fastComparisonViews(original);change.accept(copy.get(0));
        check(!FastPaidDeclarationAlignment.supports(copy),why);
    }
    public static void main(String[] args) throws Exception {
        String text=Files.readString(Path.of("classified-data/classroom_fol/both/7TYSDPnXqtud3bjku_inv12.als"));
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var source=prepare(module,"inv12"); var target=prepare(module,"inv12c");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"actual source supports outer paid path");
        check(FastPaidDeclarationAlignment.supportsPair(target,source),"reverse source supports outer paid path");
        check(FastPaidDeclarationAlignment.supportsPair(NormalForm.fastComparisonViews(source),NormalForm.fastComparisonViews(target)),
                "metric local coordinate transfer licensed by exact source occurrence");
        check(!FastPaidDeclarationAlignment.supportsPair(source,source),"local/local alignment remains outside new slice");
        for(var q:source.get(0).getLocalQuantiVars()) {
            check(q.isFrozenForCertification(),"all local seeds frozen, including inactive seed");
            reject(()->q.setBindingPath("foreign"),"local owner immutable");
            reject(()->q.setCardinality(QuantiVar.Cardinality.SET),"local multiplicity immutable");
            reject(()->q.setQuantifier(QuantiVar.Quantifier.ALL),"local quantifier immutable");
        }
        corrupt(source,f->field(binder(f),"sourceOccurrenceLineage",-1L),"foreign local owner lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign declaration variable lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain lineage");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","alloy/signature/Student"),"same carrier different domain");
        corrupt(source,f->field(binder(f),"opcode",EGraphNode.Opcode.FORALL),"unsupported local quantifier");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"unsupported local multiplicity");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"alphaName","_q1"),"local declaration captures outer name");
        corrupt(source,f->{
            var local=binder(f).getChildren().get(0).getChildren().get(1).getAlphaName();
            var outside=f.getMatrixEGraph().getChildren().get(0).getChildren().get(0).getChildren().get(0);
            field(outside,"alphaName",local);
        },"local escapes outside owner");
        corrupt(source,f->{
            var use=nodes(binder(f).getChildren().get(1)).stream()
                    .filter(n->n.getOpcode()==EGraphNode.Opcode.VARIABLE && n.getAlphaName().startsWith("@fast-local:")).findFirst().orElseThrow();
            field(use,"alphaName","_q1");
        },"local use substituted with outer binder despite matching carrier");
        var foreign=prepare(CompUtil.parseEverything_fromString(A4Reporter.NOP,text),"inv12");
        var foreignVar=binder(foreign.get(0)).getChildren().get(0).getChildren().get(1);
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",foreignVar.getExactAlloyType()),
                "foreign parser-module type evidence");
        corrupt(source,f->field(binder(f).getChildren().get(0).getChildren().get(1),"exactAlloyType",null),"missing local type evidence");
        corrupt(source,f->field(f,"certificationMatrixEGraphRoot",foreign.get(0).getCertificationMatrixEGraph()),
                "another original source cannot license this metric occurrence");
        var reduced=CompUtil.parseEverything_fromString(A4Reporter.NOP,"""
                sig P { r:set P }
                sig S, T, U in P {}
                pred retained { some p:S | p in p.r implies (some t:T | p in t.r) }
                pred nested { some p:S | p in p.r implies (some t:T | p in t.r implies (some u:U | u in t.r and t in u.r)) }
                pred disjoint { some p:P | p in p.r implies (some disj t,u:T | p in t.r and u in t.r) }
                pred setLocal { some p:P | p in p.r implies (some t:set T | p in t.r) }
                pred parameter[p:P] { some q:P | q in p.r implies (some t:T | q in t.r) }
                pred temporal { some p:P | always (p in p.r implies (some t:T | p in t.r)) }
                """);
        check(FastPaidDeclarationAlignment.supports(prepare(reduced,"retained")),"reduced retained local source");
        var nested=prepare(reduced,"nested");
        check(FastLocalPaidScope.checkedOwnerCount(nested.get(0))==2
                && FastPaidDeclarationAlignment.supports(nested),"source-linked retained EXISTS chain admitted without hoisting");
        for(String name:List.of("disjoint","setLocal","parameter","temporal")) {
            var forms=prepare(reduced,name);
            check(!FastPaidDeclarationAlignment.supports(forms),"unsupported scope "+name+" "+CanonicalDistance.sourceIrTemporalFol(forms));
        }
        var budget=NormalForm.fastComparisonViews(source).get(0);
        EGraphNode repeated=new EGraphNode(89999,EGraphNode.Opcode.VARIABLE,List.of(),false,0,false,EGraphNode.Metatype.ATOMIC);
        repeated.setAlphaName("_q0");
        repeated.setExactAlloyType(source.get(0).getMatrixQuantiVars().get(0).getExactAlloyType());
        for(int i=0;i<14;i++) repeated=new EGraphNode(90000+i,EGraphNode.Opcode.AND,List.of(repeated,repeated),false,2,false,EGraphNode.Metatype.BOOLEAN);
        field(budget,"certificationMatrixEGraphRoot",repeated);
        reject(()->FastPaidDeclarationAlignment.supports(List.of(budget)),"repeated-DAG expanded visit budget");
        check(FastPaidDeclarationAlignment.supportsPair(source,target),"original authorities remain unchanged after corrupt-copy checks");
        System.out.println("FastLocalPaidScopeTest: "+checks+" checks passed; scoped eligibility only");
    }
}
