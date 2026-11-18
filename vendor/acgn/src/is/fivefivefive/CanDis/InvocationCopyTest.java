package is.fivefivefive.CanDis;

import java.lang.reflect.*;
import java.util.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Original authority and fresh invocation seals on actual local orbit copies. */
final class InvocationCopyTest {
    private static int checks;
    private static void check(boolean ok,String why) { checks++;if(!ok)throw new AssertionError(why); }
    private static Field field(Object value,String name)throws Exception {
        var f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static EGraphNode rename(EGraphNode node,Map<String,String> names)throws Exception {
        var m=EGraphNode.class.getDeclaredMethod("comparisonRenameSubtree",EGraphNode.class,Map.class,int[].class);
        m.setAccessible(true);return (EGraphNode)m.invoke(null,node,names,new int[]{2_000_000});
    }
    private static void mutation(Object object,String name,Object replacement,Runnable test)throws Exception {
        var f=field(object,name);var saved=f.get(object);f.set(object,replacement);
        try { test.run(); } finally { f.set(object,saved); }
    }
    private static void checkGroup(NormalForm phase,EGraphNode node)throws Exception {
        var origin=phase.structuralDeclarationOrigin(node.comparisonDeclarationSource());
        check(origin!=null&&origin.admits(node),"original structural authority survives orbit copy");
        var clone=rename(node,Map.of());
        check(origin.admits(clone),"identity copy retains source and seals actual new targets");
        var generic=EGraphNode.class.getDeclaredMethod("comparisonWithChildren",EGraphNode.class,List.class);
        generic.setAccessible(true);
        check(!origin.admits((EGraphNode)generic.invoke(null,node,node.getChildren())),"general copy cannot mint invocation authority");
        var seal=field(node,"comparisonDeclarationInvocations");var saved=seal.get(node);seal.set(node,null);
        try { check(!rename(node,Map.of()).hasUnchangedComparisonDeclarationInvocations(),"missing seal cannot be reissued"); }
        finally { seal.set(node,saved); }
        var ref=node.getChildClasses().get(0);var slots=field(ref,"slotMap");var old=slots.get(ref);
        slots.set(ref,Map.of("foreign-slot","foreign-owner"));
        try { check(!rename(node,Map.of()).hasUnchangedComparisonDeclarationInvocations(),"altered nonempty ambient map cannot be reissued"); }
        finally { slots.set(ref,old); }
        var children=field(node,"childClasses");var oldChildren=children.get(node);
        var changed=new ArrayList<>(node.getChildClasses());Collections.swap(changed,1,2);children.set(node,changed);
        try {check(!rename(node,Map.of()).hasUnchangedComparisonDeclarationInvocations(),"reordered invocation targets cannot be reissued");}
        finally {children.set(node,oldChildren);}
        changed=new ArrayList<>(node.getChildClasses());changed.set(1,changed.get(0));children.set(node,changed);
        try {check(!rename(node,Map.of()).hasUnchangedComparisonDeclarationInvocations(),"substituted EClass cannot be reissued");}
        finally {children.set(node,oldChildren);}
        mutation(clone.getChildClasses().get(0),"slotMap",Map.of("foreign","slot"),
                ()->check(!origin.admits(clone),"post-copy invocation tamper rejected"));
        mutation(clone,"comparisonDeclarationSource",null,()->check(!origin.admits(clone),"foreign original pointer rejected"));
        mutation(clone.getChildren().get(1),"exactAlloyType",ExactAlloyType.boolType(),
                ()->check(!origin.admits(clone),"copied slot type mutation rejected"));
        mutation(origin.originalOwner(),"opcode",EGraphNode.Opcode.ONE,()->check(!origin.admits(clone),"changed original owner rejected"));
        mutation(phase,"phasePath","foreign-phase",()->check(!origin.admits(clone),"changed source phase rejected"));
        var alpha=origin.originalDeclaration().getChildren().get(1).getAlphaName();
        var binding=phase.getLocalQuantiVars().stream().filter(q->q.getName().equals(alpha)).findFirst().orElseThrow();
        mutation(binding,"disjointnessClass",0,()->check(!origin.admits(clone),"erased disjointness rejected"));
        var frame=binding.getDeclarationFrames().get(0);
        mutation(frame,"dependencies",List.of(binding),()->check(!origin.admits(clone),"substituted dependency identity rejected"));
        mutation(frame,"owner",null,()->check(!origin.admits(clone),"substituted frame owner rejected"));
        check(origin.admits(node)&&origin.admits(clone),"restored source and copy authority");
    }
    static void run(Canonical.Prepared source)throws Exception {
        var metric=CanonicalDistance.prepare(source.normalizedForms());int groups=0;
        for(int p=0;p<source.normalizedForms().size();p++) {
            var phase=source.normalizedForms().get(p);
            var pending=new ArrayDeque<EGraphNode>();pending.add(metric.comparisonForms().get(p).getMatrixEGraph());
            var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
            while(!pending.isEmpty()) {
                var node=pending.remove();if(!seen.add(node))continue;
                if(node.getOpcode()==EGraphNode.Opcode.DISJ){checkGroup(phase,node);groups++;}
                for(var child:node.getChildren())if(child.getOpcode()==EGraphNode.Opcode.DISJ) {
                    var a=child.getChildren().get(1);var b=child.getChildren().get(2);
                    var capturing=rename(node,Map.of(a.getAlphaName(),b.getAlphaName()));
                    check(FastRepresentativeAuthentication.fastActionSignature(capturing)==null,
                            "invocation seal alone does not authorize a capturing alpha map");
                }
                pending.addAll(metric.fastAlternatives(node));pending.addAll(node.getChildren());
            }
        }
        check(groups>=2,"primary and nonidentity structural alternatives covered");
        System.out.println("InvocationCopyTest: "+checks+" checks passed, groups="+groups);
    }
}
