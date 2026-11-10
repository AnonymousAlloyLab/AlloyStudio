package is.fivefivefive.CanDis.core;

import java.util.*;
import java.util.function.Consumer;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.ir.IRAgent;
import parser.ast.nodes.ModelUnit;

/** Invalid source inventory and post-reduction transport controls. */
public final class UnusedBindingEvidenceTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++; if(!ok) throw new AssertionError(why); }
    private static final class Observed extends RuntimeException { }
    private static EGraphNode declaration(EGraphNode node) {
        if(node.getOpcode()==EGraphNode.Opcode.GENERICRELDECL) return node;
        for(var child:node.getChildren()) { var found=declaration(child); if(found!=null)return found; }
        return null;
    }
    private static EGraphNode conjunction(EGraphNode node) {
        if (node.getOpcode() == EGraphNode.Opcode.AND) return node;
        for (var child : node.getChildren()) {
            var found = conjunction(child); if (found != null) return found;
        }
        return null;
    }
    private static void rejectsUnpaidSimplification() throws Exception {
        try {
            agent("all x:A | x in x.r and not (x in x.r)").computeNormalForm((stage, form, forms) -> {
                if (!stage.equals("begin-unused-binding-descope")) return;
                var node = conjunction(form.getMatrixEGraph());
                check(node != null, "Boolean control exists before simplification");
                var children = new ArrayList<>(node.getChildren());
                boolean rejected = false;
                is.fivefivefive.CanDis.WorkBudget.begin(0);
                try { node.simplifyBooleanAfterChildren(); }
                catch (is.fivefivefive.CanDis.WorkBudget.Exhausted expected) { rejected = true; }
                finally { is.fivefivefive.CanDis.WorkBudget.end(); }
                check(rejected, "zero fuel refuses the new simplifier entry point");
                check(node.getOpcode() == EGraphNode.Opcode.AND && node.getChildren().equals(children),
                        "exhaustion occurs before Boolean rewriting");
                throw new Observed();
            });
            throw new AssertionError("missing simplifier observation");
        } catch (Observed expected) { }
    }
    private static IRAgent agent(String body) throws Exception {
        var m=CompUtil.parseEverything_fromString(A4Reporter.NOP,"sig A {r:set A} pred p {"+body+"}");
        var v=new MASGVisitor(new GlobalVariables(),Set.of("p"),m);
        v.visit(new ModelUnit(null,m),null);
        return new IRAgent(v.getForest().get(v.getForestId("p")));
    }
    private static NormalForm prepared(String body) throws Exception {
        var agent=agent(body); agent.computeNormalForm();
        agent.normalForms().forEach(NormalForm::freezeForCertification);
        return agent.normalForms().get(0);
    }
    private static void rejectsRemoval(Consumer<EGraphNode> corrupt) throws Exception {
        try {
            agent("all x,y:A | x in x.r").computeNormalForm((stage,form,forms)->{
                if(stage.equals("begin-unused-binding-descope")) corrupt.accept(declaration(form.getMatrixEGraph()));
                if(stage.equals("alpha-beta-branch")) {
                    check(form.getUnusedBindingReductions().isEmpty(),"corrupt inventory must not disappear");
                    throw new Observed();
                }
            });
            throw new AssertionError("missing observation");
        } catch(Observed expected) { }
    }
    private static void rejectsTransport(Consumer<EGraphNode> corrupt) throws Exception {
        try {
            agent("all dead,x,y:A | x in y.r").computeNormalForm((stage,form,forms)->{
                if(stage.equals("alpha-beta-branch")) corrupt.accept(declaration(form.getMatrixEGraph()));
            });
        } catch(IllegalStateException expected) {
            check(expected.getMessage().startsWith("Unused binding projection"),"transport verifier is the rejecting boundary");
            return;
        }
        throw new AssertionError("corrupt projection accepted");
    }
    public static void main(String[] args) throws Exception {
        rejectsUnpaidSimplification();
        rejectsRemoval(d->d.getChildren().get(2).setSemanticIdentity(d.getChildren().get(1).getSemanticIdentity()));
        rejectsRemoval(d->d.getChildren().get(2).preserveSourceOccurrenceLineageFrom(d.getChildren().get(1)));
        var foreign=prepared("all x:A | x in x.r").getMatrixQuantiVars().get(0).getExactAlloyType();
        rejectsRemoval(d->d.getChildren().get(2).setExactAlloyType(foreign));
        rejectsTransport(d->d.getChildren().get(1).setSourceName("forged-owner"));
        rejectsTransport(d->d.preserveSourceOccurrenceLineageFrom(d.getChildren().get(1)));
        rejectsTransport(d->{var children=new ArrayList<>(d.getChildren()); Collections.swap(children,1,2);d.setNormalizedChildren(children);});
        var shadowed=prepared("all x,x,y:A | x in y.r");
        check(shadowed.getMatrixQuantiVars().size()==2,"repeated display name has a distinct dead source slot");
        check(shadowed.getUnusedBindingReductions().get(0).retainedSourceSlots().equals(List.of(1,2)),
                "body resolves repeated spelling to the final lexical x");
        var branches=prepared("(all dead,x:A | x in x.r) and (all spare,y:A | y->y in r)");
        check(branches.getUnusedBindingProjections().size()==2,"both reduced branches transported");
        check(branches.getMatrixQuantiVars().size()==1,"compatible branches share their selected slot");
        for (var mapping : branches.getUnusedBindingProjections()) {
            var selected=branches.getMatrixQuantiVars().get(0);
            check(mapping.normalizedSlot().equals(selected.getDeBruijnKey()),"selected branch representative, not provisional slot");
            check(selected.getOriginalNames().contains(mapping.sourceIdentity()),"shared slot retains each source identity");
        }
        for(String body:List.of("all dead,x,y:A | x in y.r","all x,dead,y:A | x in y.r","all x,y,dead:A | x in y.r")) {
            var f=prepared(body); var reduction=f.getUnusedBindingReductions().get(0);
            var maps=f.getUnusedBindingProjections();
            check(maps.size()==2,"two checked transports");
            for(int i=0;i<2;i++) {
                var q=f.getMatrixQuantiVars().get(i);var mapping=maps.get(i);
                check(mapping.sourceSlot()==reduction.retainedSourceSlots().get(i),"original source ordinal");
                check(mapping.normalizedSlot().equals(q.getDeBruijnKey()),"actual typed canonical coordinate");
                check(q.getOriginalNames().contains(mapping.sourceIdentity()),"source identity not rebound");
                check(q.getExactAlloyType().stableString().equals(mapping.exactType()),"type not weakened");
            }
        }
        System.out.println("UnusedBindingEvidenceTest: "+checks+" checks passed");
    }
}
