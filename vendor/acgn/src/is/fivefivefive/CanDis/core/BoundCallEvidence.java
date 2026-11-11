package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Original declared or parser-resolved imported calls with ordered,
 * noncapturing prefix arguments. Does not unfold functions or move local owners. */
public final class BoundCallEvidence {
    private final Map<Long,EGraphNode> originals=new HashMap<>();
    private final Map<String,QuantiVar> bindings=new HashMap<>();
    private final ExactAlloyType module;
    private final boolean eligible;

    private BoundCallEvidence(NormalForm form) {
        var qs=form.getMatrixQuantiVars();
        module=qs.isEmpty()?null:qs.get(0).getExactAlloyType();
        boolean valid=form.isFrozenForCertification() && form.getCertificationMatrixEGraph()!=null
                && module!=null && module.hasParserAuthenticatedAncestry()
                && form.getTemporalOp()==NormalForm.TemporalOp.NONE && form.getTemporalChildren().isEmpty()
                && form.getParams().isEmpty() && form.getInheritedQuantiVars().isEmpty()
                && form.getPhaseLocalBindingImports().isEmpty() && form.getLocalQuantiVars().isEmpty();
        for(var q:qs) {
            valid &= q.isFrozenForCertification() && !q.getBindingPath().isEmpty()
                    && q.getExactAlloyType()!=null && q.getExactAlloyType().hasParserAuthenticatedAncestry()
                    && q.getExactAlloyType().sharesParserModuleAuthorityWith(module);
            if(bindings.put(q.getName(),q)!=null)valid=false;
        }
        eligible=valid;
        if(!eligible)return;
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<EGraphNode>();pending.add(form.getCertificationMatrixEGraph());
        while(!pending.isEmpty()) {
            var n=pending.remove();if(!seen.add(n))continue;
            if(seen.size()>4096)throw new IllegalStateException("BOUND_CALL_SOURCE_BUDGET");
            if(n.getOpcode()==EGraphNode.Opcode.CALL) {
                var old=originals.putIfAbsent(n.getSourceOccurrenceLineage(),n);
                if(originals.size()>64)throw new IllegalStateException("BOUND_CALL_COUNT_BUDGET");
                if(old!=null && (!shape(old) || !shape(n) || !same(old,n,new int[]{512},0)))
                    throw new IllegalArgumentException("Conflicting original bound call lineage");
            }
            pending.addAll(n.getChildren());
        }
    }

    public static BoundCallEvidence from(NormalForm form){return new BoundCallEvidence(form);}

    public boolean admits(EGraphNode node) {
        if(!eligible || node==null || node.getOpcode()!=EGraphNode.Opcode.CALL)return false;
        var original=originals.get(node.getSourceOccurrenceLineage());
        return original!=null && shape(original) && shape(node) && same(original,node,new int[]{512},0);
    }

    private boolean typed(EGraphNode n,boolean argument) {
        var type=n.getExactAlloyType();
        return type!=null && (type.kind()==ExactAlloyType.Kind.RELATION
                || (argument && type.kind()==ExactAlloyType.Kind.EMPTY_RELATION))
                && type.hasParserAuthenticatedAncestry() && type.sharesParserModuleAuthorityWith(module);
    }

    private boolean shape(EGraphNode n) {
        var c=CallMetadata.require(n);
        // Bool has no relation ancestry. Its authority comes from the retained
        // call occurrence and checked ordered arguments, not a fabricated carrier.
        boolean resultType=c.kind().equals("call/expression") && typed(n,false)
                || c.kind().equals("call/formula") && n.getExactAlloyType()!=null
                    && n.getExactAlloyType().kind()==ExactAlloyType.Kind.BOOL;
        if((c.authority()!=CallSymbol.ArityAuthority.DECLARATION
                && c.authority()!=CallSymbol.ArityAuthority.TYPECHECKED_IMPORT)
                || c.arity()==0 || c.arity()>64 || n.isOrderInsensitive() || !resultType)return false;
        int[] budget={511};
        for(var arg:n.getChildren())if(!argument(arg,budget,1))return false;
        return true;
    }

    private boolean argument(EGraphNode n,int[] budget,int depth) {
        spend(budget,depth);
        // An exact empty argument keeps its source expression and arity. It is
        // not a type wildcard or permission to erase the argument/binder tree.
        if(!typed(n,true))return false;
        return switch(n.getOpcode()) {
            case VARIABLE -> {
                var q=bindings.get(n.getAlphaName());
                yield q!=null && n.getChildren().isEmpty()
                        && q.getOriginalNames().contains(n.getSemanticIdentity())
                        && q.getOriginalNames().contains(n.getSourceName())
                        && q.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType());
            }
            case GLOBALBINDING -> n.getChildren().isEmpty() && n.getAlphaName()==null
                    && n.getSourceName()!=null && !n.getSourceName().isBlank();
            case JOIN,MINUS -> n.getChildren().size()==2 && !n.isOrderInsensitive()
                    && argument(n.getChildren().get(0),budget,depth+1)
                    && argument(n.getChildren().get(1),budget,depth+1);
            default -> false;
        };
    }

    // Ordered exact source-occurrence correspondence, not projected equality.
    private static boolean same(EGraphNode a,EGraphNode b,int[] budget,int depth) {
        return same(a,b,budget,depth,false);
    }

    /** Only for a caller that separately checks every variable's visible owner. */
    static boolean sameScopedCall(EGraphNode original,EGraphNode comparison) {
        return same(original,comparison,new int[]{512},0,true);
    }

    private static boolean same(EGraphNode a,EGraphNode b,int[] budget,int depth,boolean scoped) {
        spend(budget,depth);
        if(a.getSourceOccurrenceLineage()!=b.getSourceOccurrenceLineage() || a.getOpcode()!=b.getOpcode()
                || a.getMetatype()!=b.getMetatype() || a.isOrderInsensitive()!=b.isOrderInsensitive()
                || a.isFlexibleArity()!=b.isFlexibleArity() || a.getMaxArity()!=b.getMaxArity()
                || (!(scoped && a.getOpcode()==EGraphNode.Opcode.VARIABLE)
                    && !Objects.equals(a.getAlphaName(),b.getAlphaName()))
                || !Objects.equals(a.getSourceName(),b.getSourceName())
                || !Objects.equals(a.getSemanticIdentity(),b.getSemanticIdentity())
                || !a.getSemanticProfile().equals(b.getSemanticProfile())
                || a.getExactAlloyType()==null || !a.getExactAlloyType().sameOccurrenceEvidenceAs(b.getExactAlloyType())
                || a.getChildren().size()!=b.getChildren().size())return false;
        if(a.getOpcode()==EGraphNode.Opcode.CALL) {
            var x=CallMetadata.require(a);var y=CallMetadata.require(b);
            if(x.occurrenceId()!=y.occurrenceId() || !CallMetadata.semanticKey(a).equals(CallMetadata.semanticKey(b)))return false;
        }
        for(int i=0;i<a.getChildren().size();i++)if(!same(a.getChildren().get(i),b.getChildren().get(i),budget,depth+1,scoped))return false;
        return true;
    }

    private static void spend(int[] budget,int depth) {
        if(--budget[0]<0 || depth>32)throw new IllegalStateException("BOUND_CALL_ARGUMENT_BUDGET");
    }
}
