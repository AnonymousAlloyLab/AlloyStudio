package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Closed imported expression calls are opaque to binder edits, never unfolded. */
public final class ClosedCallEvidence {
    private final Map<Long,EGraphNode> originals;
    private final Map<List<Integer>,EGraphNode> branches;
    private ClosedCallEvidence(Map<Long,EGraphNode> originals,Map<List<Integer>,EGraphNode> branches){this.originals=Map.copyOf(originals);this.branches=Map.copyOf(branches);}
    private record Visit(EGraphNode node,List<Integer> branch) {}

    public static ClosedCallEvidence from(NormalForm form) {
        if(!form.isFrozenForCertification() || form.getCertificationMatrixEGraph()==null)
            throw new IllegalArgumentException("Closed calls require a frozen original source");
        var calls=new HashMap<Long,EGraphNode>();
        var branches=new HashMap<List<Integer>,EGraphNode>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<Visit>();pending.add(new Visit(form.getCertificationMatrixEGraph(),List.of()));
        while(!pending.isEmpty()) {
            var visit=pending.remove();var node=visit.node();
            if(node.getOpcode()==EGraphNode.Opcode.CALL)branches.put(visit.branch(),node);
            if(!seen.add(node))continue;
            if(seen.size()>4096)throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("CLOSED_CALL_SOURCE_BUDGET");
            if(node.getOpcode()==EGraphNode.Opcode.CALL) {
                var prior=calls.putIfAbsent(node.getSourceOccurrenceLineage(),node);
                if(calls.size()>64)throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("CLOSED_CALL_COUNT_BUDGET");
                if(prior!=null && !sameOccurrence(prior,node))
                    throw new IllegalArgumentException("Conflicting original call lineage");
            }
            for(int i=0;i<node.getChildren().size();i++) {
                var branch=new ArrayList<>(visit.branch());branch.add(i);
                pending.add(new Visit(node.getChildren().get(i),List.copyOf(branch)));
            }
        }
        return new ClosedCallEvidence(calls,branches);
    }

    /** Exact opaque nullary source correspondence, not a call equality rule. */
    public boolean admitsNullary(EGraphNode node,List<Integer> branch) {
        if(node==null || node.getOpcode()!=EGraphNode.Opcode.CALL)return false;
        var original=branches.get(branch);
        return original!=null && original.sharesCallSourceAnchorWith(node)
                && nullary(original) && nullary(node) && BoundCallEvidence.sameScopedCall(original,node);
    }

    private static boolean nullary(EGraphNode node) {
        var call=CallMetadata.require(node);var type=node.getExactAlloyType();
        if(call.arity()!=0 || !node.getChildren().isEmpty() || node.isOrderInsensitive()
                || (call.authority()!=CallSymbol.ArityAuthority.DECLARATION
                    && call.authority()!=CallSymbol.ArityAuthority.TYPECHECKED_IMPORT)
                || type==null)return false;
        return call.kind().equals("call/formula") ? type.kind()==ExactAlloyType.Kind.BOOL
                : type.kind()==ExactAlloyType.Kind.RELATION && type.hasParserAuthenticatedAncestry();
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
        int[] budget={511};
        for(var child:node.getChildren())if(!closedArgument(child,type,budget,1))return false;
        return true;
    }

    private static boolean closedArgument(EGraphNode node,ExactAlloyType module,int[] budget,int depth) {
        if(--budget[0]<0 || depth>32)
            throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("CLOSED_CALL_ARGUMENT_BUDGET");
        var type=node.getExactAlloyType();
        if(type==null || node.getAlphaName()!=null)return false;
        // Integer literals obtain source authority from the enclosing retained
        // CALL anchor and recursive same-occurrence check, not relation ancestry.
        if(node.getOpcode()==EGraphNode.Opcode.CONSTANT)
            return type.kind()==ExactAlloyType.Kind.INT && node.getChildren().isEmpty()
                    && node.getSourceName()!=null;
        if(!type.sharesParserModuleAuthorityWith(module))return false;
        if(node.getOpcode()==EGraphNode.Opcode.GLOBALBINDING)
            return node.getChildren().isEmpty() && node.getSourceName()!=null && !node.getSourceName().isBlank();
        if(node.getOpcode()!=EGraphNode.Opcode.JOIN && node.getOpcode()!=EGraphNode.Opcode.MINUS
                && node.getOpcode()!=EGraphNode.Opcode.PLUS)return false;
        if(node.getChildren().size()!=2)return false;
        if(node.getOpcode()!=EGraphNode.Opcode.PLUS && node.isOrderInsensitive())return false;
        return closedArgument(node.getChildren().get(0),module,budget,depth+1)
                && closedArgument(node.getChildren().get(1),module,budget,depth+1);
    }

    private static boolean sameOccurrence(EGraphNode a,EGraphNode b) {
        return BoundCallEvidence.sameClosedCall(a,b);
    }
}
