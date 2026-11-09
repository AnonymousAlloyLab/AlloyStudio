package is.fivefivefive.CanDis.core;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

public final class RetainedExistentialAdmissionTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static List<NormalForm> prepare(String text,String name)throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,text);
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of(name),module);visitor.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(visitor.getForest().get(visitor.getForestId(name)));agent.computeNormalForm();
        var forms=List.copyOf(agent.normalForms());forms.forEach(NormalForm::freezeForCertification);return forms;
    }
    private static List<EGraphNode> nodes(EGraphNode n){var out=new ArrayList<EGraphNode>();out.add(n);for(var c:n.getChildren())out.addAll(nodes(c));return out;}
    private static List<EGraphNode> owners(NormalForm f){return nodes(f.getMatrixEGraph()).stream().filter(n->n.getChildren().size()==2&&n.getChildren().get(0).getOpcode()==EGraphNode.Opcode.GENERICRELDECL).toList();}
    private static void field(Object target,String name,Object value){try{var f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static Object invoke(String name,Class<?>[] types,Object... args){try{var m=CanonicalDistance.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(null,args);}catch(ReflectiveOperationException e){throw new AssertionError(e);}}
    private static void corrupt(List<NormalForm> source,List<NormalForm> target,Consumer<NormalForm> edit,String why){
        var copy=NormalForm.fastComparisonViews(source);edit.accept(copy.get(0));
        check(!FastPaidDeclarationAlignment.supportsPair(copy,NormalForm.fastComparisonViews(target)),why);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        String text=Files.readString(Path.of("classified-data/classroom_fol/both/85tWoTeBhoEDhCKmw_inv14.als"));
        var a=prepare(text,"inv14");var b=prepare(text,"inv14c");
        check(FastLocalPaidScope.checkedOwnerCount(a.get(0))==2,"actual two-owner original chain");
        check(owners(a.get(0)).stream().allMatch(n->n.getOpcode()==EGraphNode.Opcode.EXISTS),"actual normalized existential kinds");
        check(!FastNestedPaidScope.supportsPair(a,b),"older ONE/ALL route still distinct");
        for(var pair:List.of(List.of(a,b),List.of(b,a))) {
            var l=NormalForm.fastComparisonViews(pair.get(0));var r=NormalForm.fastComparisonViews(pair.get(1));
            check(FastPaidDeclarationAlignment.supportsPair(l,r),"source-linked candidate admission both directions");
            var qs=(List<QuantiVar>)invoke("canonicalQuantifierOrder",new Class[]{List.class},l.get(0).getMatrixQuantiVars());
            var rs=(List<QuantiVar>)invoke("canonicalQuantifierOrder",new Class[]{List.class},r.get(0).getMatrixQuantiVars());
            int[][] dp=(int[][])invoke("quantificationDp",new Class[]{List.class,List.class},qs,rs);
            check(dp[qs.size()][rs.size()]==2,"actual minimum declaration cost2");
            var paths=new ArrayList<Map<String,String>>();
            FastPaidDeclarationAlignment.enumerate(qs,rs,dp,(x,y)->(Integer)invoke("quantifierUpdateCost",new Class[]{QuantiVar.class,QuantiVar.class},x,y),paths::add);
            var identity=Map.of("_q0","_q0","_q1","_q1");
            check(paths.get(0).equals(identity),"ordinary diagonal-first paid path selected before equal-cost insert/delete");
            check(paths.stream().allMatch(m->m.keySet().stream().allMatch(n->n.startsWith("_q"))),"no local binder in paid links");
            int fixed=(Integer)invoke("eGraphDistance",new Class[]{EGraphNode.class,EGraphNode.class,Map.class},l.get(0).getMatrixEGraph(),r.get(0).getMatrixEGraph(),identity);
            check(fixed==35,"same admitted candidate fixed native matrix35");
        }
        check(!FastPaidDeclarationAlignment.supportsPair(a,a),"local/local remains unsupported");
        corrupt(a,b,f->field(owners(f).get(1),"sourceOccurrenceLineage",-1L),"foreign inner owner");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign declaration");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"sourceOccurrenceLineage",-1L),"foreign variable occurrence");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0),"sourceOccurrenceLineage",-1L),"foreign domain occurrence");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0).getChildren().get(0),"semanticIdentity","foreign/domain"),"same-type domain substitution");
        corrupt(a,b,f->field(owners(f).get(1),"opcode",EGraphNode.Opcode.LONE),"changed inner quantifier");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0),"opcode",EGraphNode.Opcode.DISJ),"disjoint local");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(0),"opcode",EGraphNode.Opcode.SETOF),"set multiplicity");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"alphaName","_q0"),"local captures outer");
        corrupt(a,b,f->field(f,"matrixEGraphRoot",owners(f).get(1)),"inner owner moved to root despite same lineage and domain");
        var foreign=prepare(text,"inv14");
        corrupt(a,b,f->field(f,"certificationMatrixEGraphRoot",foreign.get(0).getCertificationMatrixEGraph()),"foreign original source");
        corrupt(a,b,f->field(owners(f).get(1).getChildren().get(0).getChildren().get(1),"exactAlloyType",owners(foreign.get(0)).get(1).getChildren().get(0).getChildren().get(1).getExactAlloyType()),"foreign parser module/type");
        corrupt(a,b,f->field(f,"temporalOp",NormalForm.TemporalOp.ALWAYS),"temporal boundary");
        corrupt(a,b,f->field(f,"params",List.of(a.get(0).getMatrixQuantiVars().get(0))),"parameter boundary");
        corrupt(a,b,f->field(f,"inheritedQuantiVars",List.of(a.get(0).getMatrixQuantiVars().get(0))),"inherited owner boundary");
        String reduced="""
                sig P { r:set P }
                sig S, T, U in P {}
                pred base { all p:P | some p.r }
                pred nested { some p:S | p in p.r implies (some t:T | p in t.r implies (some u:U | u in t.r and t in u.r)) }
                pred disjoint { some p:P | p in p.r implies (some disj t,u:T | p in t.r and u in t.r) }
                pred setLocal { some p:P | p in p.r implies (some t:set T | p in t.r) }
                pred dependent { lone p:P | p in p.r implies (lone t:p.r | t in p.r) }
                pred nestedLone { lone p:P | p in p.r implies (lone t:T | p in t.r implies (lone u:U | u in t.r)) }
                """;
        var nested=prepare(reduced,"nested");var base=prepare(reduced,"base");
        check(FastLocalPaidScope.checkedOwnerCount(nested.get(0))==2,"legacy negative fixture now establishes actual EXISTS chain");
        check(FastPaidDeclarationAlignment.supportsPair(nested,base)&&FastPaidDeclarationAlignment.supportsPair(base,nested),"general retained chain admission");
        for(String name:List.of("disjoint","setLocal","dependent","nestedLone"))
            check(!FastPaidDeclarationAlignment.supportsPair(prepare(reduced,name),base),"retained unsupported "+name);
        var sase=Files.readString(Path.of("classified-data/classroom_fol/both/SASEHD8jPo2HgsuoJ_inv14.als"));
        var sa=prepare(sase,"inv14");var sb=prepare(sase,"inv14c");
        check(!FastPaidDeclarationAlignment.supportsPair(sa,sb)&&!FastPaidDeclarationAlignment.supportsPair(sb,sa),"symmetric retained prefix keeps existing certified-permutation completion route");
        check(FastPaidDeclarationAlignment.supportsPair(a,b),"original authorities unchanged by corrupted-copy checks");
        System.out.println("RetainedExistentialAdmissionTest: "+checks+" checks passed; actual candidate coverage/fixed35; source-parent-domain guards");
    }
}
