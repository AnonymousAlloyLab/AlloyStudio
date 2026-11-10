package is.fivefivefive.CanDis.core;

import java.util.*;
import java.lang.reflect.Field;
import edu.mit.csail.sdg.alloy4.A4Reporter;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.ACGN.util.GlobalVariables;
import is.fivefivefive.ACGN.visitor.MASGVisitor;
import is.fivefivefive.CanDis.ir.IRAgent;
import is.fivefivefive.CanDis.theory.TheoryAlloyAdapter;
import is.fivefivefive.CanDis.metric.RepairProjection;
import is.fivefivefive.CanDis.theory.CanonicalizationDomainException;
import parser.ast.nodes.ModelUnit;

public final class FastPrefixSourceTest {
    private static int checks;
    private static final String SOURCE = "sig A {r:set A} pred p {all x:A | all y:A | x in y.r}";
    private record Endpoint(NormalForm source, NormalForm comparison) { }
    private static void check(boolean ok,String why) {
        checks++; if(!ok) throw new AssertionError(why);
    }
    private static Endpoint endpoint() throws Exception {
        var module=CompUtil.parseEverything_fromString(A4Reporter.NOP,
                SOURCE);
        var visitor=new MASGVisitor(new GlobalVariables(),Set.of("p"),module);
        visitor.visit(new ModelUnit(null,module),null);
        var agent=new IRAgent(visitor.getForest().get(visitor.getForestId("p")));
        agent.computeNormalForm();
        var forms=agent.normalForms();
        var prepared=CanonicalDistance.prepare(forms);
        return new Endpoint(forms.get(0),prepared.comparisonForms().get(0));
    }
    private static void reject(Runnable work,String why) {
        checks++;
        try {work.run();} catch(IllegalArgumentException|IllegalStateException expected) {return;}
        throw new AssertionError(why);
    }
    private static void rejectExactly(Runnable work,Class<? extends RuntimeException> type,String message) {
        checks++;
        try {work.run();} catch(RuntimeException expected) {
            if(expected.getClass()==type && message.equals(expected.getMessage())) return;
            throw new AssertionError("Wrong rejection: "+expected,expected);
        }
        throw new AssertionError("Expected "+message);
    }
    private static EGraphNode field(EGraphNode n) {
        if(n.getChildren().isEmpty() && n.getExactAlloyType()!=null
                && n.getExactAlloyType().kind()==ExactAlloyType.Kind.RELATION
                && n.getExactAlloyType().relationArity()==2)return n;
        for(var c:n.getChildren()){var found=field(c);if(found!=null)return found;}
        return null;
    }
    private static EGraphNode booleanOperand(EGraphNode n) {
        for(var c:n.getChildren()){var found=booleanOperand(c);if(found!=null)return found;}
        return n.getMetatype()==EGraphNode.Metatype.BOOLEAN ? n : null;
    }
    private static void corrupt(Object target,String name,Object value,Runnable check) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);
        Object original=field.get(target);
        try {field.set(target,value);check.run();} finally {field.set(target,original);}
    }
    private static EGraphNode variable(EGraphNode n,String name) {
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE && name.equals(n.getAlphaName()))return n;
        for(var c:n.getChildren()){var found=variable(c,name);if(found!=null)return found;}
        return null;
    }
    public static void main(String[] args) throws Exception {
        var e=endpoint();var foreign=endpoint();
        var full=TheoryAlloyAdapter.adapt(List.of(e.source));
        RepairProjection.project(full,List.of(e.source));
        var prefix=FastPrefixSource.checkedPlan(e.source,e.comparison);
        check(prefix.descriptor().structuralKey().equals(full.phaseBinderDescriptors().get(0).structuralKey()),
                "exact old descriptor, not carrier spelling");
        check(prefix.sourceToCoordinate().equals(full.phaseSourceCoordinates().get(0)),"same occurrence coordinates");
        check(FastGroupedDeclarationOrbit.orders(e.source,e.comparison,e.source.getMatrixQuantiVars()).size()==2,
                "separate singleton source declarations retain exchange");
        var qs=e.source.getMatrixQuantiVars();
        check(prefix.sourceToCoordinate().equals(List.of(0,1)),"Lean source coordinate instance");
        check(qs.stream().allMatch(v->v.getQuantifier()==QuantiVar.Quantifier.ALL
                && v.getCardinality()==QuantiVar.Cardinality.ONE && v.getTypeName().equals("A")
                && v.getExactAlloyType().hasParserAuthenticatedAncestry()),"Lean typed independent ALL instance");
        check(!qs.get(0).getBindingPath().equals(qs.get(1).getBindingPath()),"distinct source owners retained");
        check(prefix.descriptor().coordinates().stream().allMatch(c->c.dependencies().isEmpty()),
                "Lean independent domains");
        var swap=FastGroupedDeclarationOrbit.orders(e.source,e.comparison,qs).get(1);
        check(Arrays.equals(swap,new int[]{1,0}),"Lean permutation instance");
        var paid=Map.of(qs.get(0).getName(),qs.get(0).getName(),qs.get(1).getName(),qs.get(1).getName());
        var transported=FastGroupedDeclarationOrbit.transport(qs,qs,paid,swap,new int[]{0,1});
        check(transported.equals(Map.of(qs.get(1).getName(),qs.get(0).getName(),
                qs.get(0).getName(),qs.get(1).getName())),"actual Java occurrence transport equals Lean swap");
        check(FastGroupedDeclarationOrbit.transport(qs,qs,transported,swap,new int[]{0,1}).equals(paid),
                "both-direction transport restores original owners");
        var receipt=new org.json.JSONObject().put("source",SOURCE)
                .put("sourceSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .put("phase",0).put("sourceCoordinates",prefix.sourceToCoordinate()).put("swap",swap)
                .put("ownerPaths",qs.stream().map(QuantiVar::getBindingPath).toList())
                .put("typedSlots",qs.stream().map(QuantiVar::getDeBruijnKey).toList())
                .put("transport",transported).put("oldRejection","canon_G work exceeds configured bound 1");
        check(receipt.getString("sourceSha256").equals("5365eba78a72ecb9952c98492691c14ef8a3683e704c08bc3808498ed4232ce0"),
                "Lean exact source hash");
        check(qs.stream().map(QuantiVar::getBindingPath).toList().equals(List.of(
                "root/predicate[0]/decl[0]@pos","root/predicate[0]/body[1]/decl[0]@pos")),"Lean concrete owners");
        check(qs.stream().map(QuantiVar::getDeBruijnKey).toList().equals(List.of(
                "root/predicate[0]/decl[0]@pos#0:ONE:A","root/predicate[0]/body[1]/decl[0]@pos#0:ONE:A")),
                "Lean concrete typed slots");
        reject(()->FastGroupedDeclarationOrbit.transport(qs,qs,paid,new int[]{0,0},swap),
                "non-bijective transport rejected");
        System.out.println("correspondence="+receipt);
        reject(()->FastPrefixSource.checkedPlan(e.source,foreign.comparison),"foreign source rejected");
        var q=e.source.getMatrixQuantiVars().get(0);
        var n=Objects.requireNonNull(variable(e.source.getCertificationMatrixEGraph(),q.getName()));
        corrupt(n,"exactAlloyType",ExactAlloyType.unaryRelation(q.getTypeName()),
                ()->reject(()->FastPrefixSource.checkedPlan(e.source,e.comparison),"unauthenticated singleton type"));
        corrupt(n,"semanticIdentity","foreign-owner",
                ()->reject(()->FastPrefixSource.checkedPlan(e.source,e.comparison),"foreign singleton alias"));
        var current=Objects.requireNonNull(variable(e.comparison.getMatrixEGraph(),q.getName()));
        corrupt(current,"alphaName",e.source.getMatrixQuantiVars().get(1).getName(),
                ()->reject(()->FastPrefixSource.checkedPlan(e.source,e.comparison),"capturing outer occurrence"));
        var relation=Objects.requireNonNull(field(e.source.getCertificationMatrixEGraph()));
        corrupt(relation,"exactAlloyType",null,()->{
            String message="Theory-faithful adaptation requires an exact occurrence type for "+relation.getOpcode();
            rejectExactly(()->TheoryAlloyAdapter.adapt(List.of(e.source)),IllegalStateException.class,message);
            rejectExactly(()->FastPrefixSource.checkedPlan(e.source,e.comparison),IllegalStateException.class,message);
        });
        corrupt(Objects.requireNonNull(booleanOperand(e.source.getCertificationMatrixEGraph())),
                "exactAlloyType",qs.get(0).getExactAlloyType(),()->{
            String message="Boolean IR node carries a non-Boolean exact Alloy type";
            rejectExactly(()->TheoryAlloyAdapter.adapt(List.of(e.source)),IllegalStateException.class,message);
            rejectExactly(()->FastPrefixSource.checkedPlan(e.source,e.comparison),IllegalStateException.class,message);
        });
        var orphan=Objects.requireNonNull(booleanOperand(e.source.getCertificationMatrixEGraph()));
        corrupt(orphan,"opcode",EGraphNode.Opcode.GENERICRELDECL,()->{
            orphan.requireAdmittedArity();
            check(FastPaidDeclarationAlignment.supports(List.of(e.source)),
                    "ordinary no-local eligibility is not declaration owner authority");
            rejectExactly(()->FastPrefixSource.checkedPlan(e.source,e.comparison),IllegalArgumentException.class,
                    "Unowned prefix declaration");
        });
        String prior=System.getProperty("acgn.maxCanonicalWorkItems");
        try {
            System.setProperty("acgn.maxCanonicalWorkItems","1");
            rejectExactly(()->TheoryAlloyAdapter.adapt(List.of(e.source)),CanonicalizationDomainException.class,
                    "canon_G work exceeds configured bound 1");
            check(FastGroupedDeclarationOrbit.orders(e.source,e.comparison,e.source.getMatrixQuantiVars()).size()==2,
                    "prefix exchange needs no full graph construction");
        } finally {
            if(prior==null)System.clearProperty("acgn.maxCanonicalWorkItems");
            else System.setProperty("acgn.maxCanonicalWorkItems",prior);
        }
        check(FastPrefixSource.checkedPlan(e.source,e.comparison).descriptor().structuralKey()
                .equals(prefix.descriptor().structuralKey()),"negative controls leave original source intact");
        System.out.println("FastPrefixSourceTest: "+checks+" checks passed");
    }
}
