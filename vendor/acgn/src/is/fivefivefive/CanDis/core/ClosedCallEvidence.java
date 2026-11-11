package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Closed imported expression calls are opaque to binder edits, never unfolded. */
public final class ClosedCallEvidence {
    private final Map<Long,EGraphNode> originals;
    private ClosedCallEvidence(Map<Long,EGraphNode> originals){this.originals=Map.copyOf(originals);}

    public static ClosedCallEvidence from(NormalForm form) {
        if(!form.isFrozenForCertification() || form.getCertificationMatrixEGraph()==null)
            throw new IllegalArgumentException("Closed calls require a frozen original source");
        var calls=new HashMap<Long,EGraphNode>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<EGraphNode>();pending.add(form.getCertificationMatrixEGraph());
        while(!pending.isEmpty()) {
            var node=pending.remove();if(!seen.add(node))continue;
            if(seen.size()>4096)throw new IllegalStateException("CLOSED_CALL_SOURCE_BUDGET");
            if(node.getOpcode()==EGraphNode.Opcode.CALL) {
                var prior=calls.putIfAbsent(node.getSourceOccurrenceLineage(),node);
                if(calls.size()>64)throw new IllegalStateException("CLOSED_CALL_COUNT_BUDGET");
                if(prior!=null && !sameOccurrence(prior,node))
                    throw new IllegalArgumentException("Conflicting original call lineage");
            }
            pending.addAll(node.getChildren());
        }
        return new ClosedCallEvidence(calls);
    }

    public boolean admits(EGraphNode node) {
        if(node==null || node.getOpcode()!=EGraphNode.Opcode.CALL)return false;
        var original=originals.get(node.getSourceOccurrenceLineage());
        return original!=null && closed(original) && closed(node) && sameOccurrence(original,node);
    }

    public static boolean closed(EGraphNode node) {
        var call=CallMetadata.require(node);var type=node.getExactAlloyType();
        if(!"call/expression".equals(call.kind()) || call.authority()!=CallSymbol.ArityAuthority.TYPECHECKED_IMPORT
                || call.arity()>64 || node.isOrderInsensitive()
                || type==null || type.kind()!=ExactAlloyType.Kind.RELATION
                || !type.hasParserAuthenticatedAncestry())return false;
        for(var child:node.getChildren()) {
            var childType=child.getExactAlloyType();
            if(child.getOpcode()!=EGraphNode.Opcode.GLOBALBINDING || !child.getChildren().isEmpty()
                    || child.getSemanticIdentity()==null || child.getAlphaName()!=null
                    || childType==null || !childType.sharesParserModuleAuthorityWith(type))return false;
        }
        return true;
    }

    private static boolean sameOccurrence(EGraphNode a,EGraphNode b) {
        var x=CallMetadata.require(a);var y=CallMetadata.require(b);
        if(a.getSourceOccurrenceLineage()!=b.getSourceOccurrenceLineage()
                || x.occurrenceId()!=y.occurrenceId() || !CallMetadata.semanticKey(a).equals(CallMetadata.semanticKey(b))
                || !x.sourceName().equals(y.sourceName()) || !a.getSemanticProfile().equals(b.getSemanticProfile())
                || a.getExactAlloyType()==null || !a.getExactAlloyType().sameOccurrenceEvidenceAs(b.getExactAlloyType()))return false;
        for(int i=0;i<a.getChildren().size();i++) {
            var p=a.getChildren().get(i);var q=b.getChildren().get(i);
            if(p.getSourceOccurrenceLineage()!=q.getSourceOccurrenceLineage() || p.getOpcode()!=q.getOpcode()
                    || !Objects.equals(p.getSourceName(),q.getSourceName())
                    || !Objects.equals(p.getSemanticIdentity(),q.getSemanticIdentity())
                    || p.getExactAlloyType()==null || !p.getExactAlloyType().sameOccurrenceEvidenceAs(q.getExactAlloyType()))return false;
        }
        return true;
    }
}
