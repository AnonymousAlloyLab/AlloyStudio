package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Eligibility only: retained local binders are neither hoisted nor paid outer links. */
public final class FastLocalPaidScope {
    private static final int MAX_VISITS = 4096;
    private static final int MAX_DEPTH = 128;
    private static final long ROOT_OWNER = Long.MIN_VALUE;
    private final Map<String,QuantiVar> locals = new HashMap<>();
    private final Set<String> usedOwners = new HashSet<>();
    private final Map<Long,LocalPlan> sourcePlans = new HashMap<>();
    private final Map<Long,QuantiVar> sourceVariables = new HashMap<>();
    private final Map<Long,EGraphNode> sourceCalls = new HashMap<>();
    private final Set<QuantiVar> outerBindings = Collections.newSetFromMap(new IdentityHashMap<>());
    private final ExactAlloyType module;
    private int visits;
    private boolean checkingSource = true;

    private record LocalSlot(QuantiVar binding,long variable) { }
    private record LocalGroup(List<LocalSlot> slots,long declaration,EGraphNode domain) { }
    private record LocalPlan(List<LocalGroup> groups,long parent) { }

    private FastLocalPaidScope(ExactAlloyType module) { this.module=module; }

    static boolean supports(NormalForm form) {
        return checkedOwnerCount(form)>0;
    }

    static int checkedOwnerCount(NormalForm form) {
        var outer=new HashMap<String,QuantiVar>();
        for(var q:form.getMatrixQuantiVars()) if(outer.put(q.getName(),q)!=null) return 0;
        if(outer.isEmpty()) return 0;
        var check=new FastLocalPaidScope(form.getMatrixQuantiVars().get(0).getExactAlloyType());
        check.outerBindings.addAll(form.getMatrixQuantiVars());
        for(var q:form.getLocalQuantiVars()) {
            if(outer.containsKey(q.getName()) || check.locals.put(q.getName(),q)!=null) return 0;
        }
        if(!check.walk(form.getCertificationMatrixEGraph(),outer,0,ROOT_OWNER) || check.sourcePlans.isEmpty()) return 0;
        // Distinct retained owners may share a parent. walk checks each actual
        // owner identity, parent edge and ordered slots; no scope is exchanged.
        if(check.sourcePlans.size()>1) {
            for(var plan:check.sourcePlans.values())
                if((plan.groups().get(0).slots().get(0).binding().getQuantifier()!=QuantiVar.Quantifier.SOME
                        && plan.groups().get(0).slots().get(0).binding().getQuantifier()!=QuantiVar.Quantifier.ALL)) return 0;
        }
        check.checkingSource=false;
        check.usedOwners.clear();
        return check.walk(form.getMatrixEGraph(),outer,0,ROOT_OWNER)
                && check.usedOwners.size()==check.sourcePlans.values().stream()
                        .flatMap(p->p.groups().stream()).mapToInt(g->g.slots().size()).sum()?check.sourcePlans.size():0;
    }

    /** Actual nodes from a checked retained scope, not a permission by opcode.
     * A comparison view is checked separately against its unchanged source. */
    public static Set<EGraphNode> checkedCalls(NormalForm form) {
        var result=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        if(!form.isFrozenForCertification() || form.getTemporalOp()!=NormalForm.TemporalOp.NONE
                || !form.getTemporalChildren().isEmpty() || !form.getParams().isEmpty()
                || !form.getInheritedQuantiVars().isEmpty() || !form.getPhaseLocalBindingImports().isEmpty()
                || checkedOwnerCount(form)==0) return result;
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<EGraphNode>();pending.add(form.getCertificationMatrixEGraph());pending.add(form.getMatrixEGraph());
        while(!pending.isEmpty()) {
            var n=pending.remove();if(!seen.add(n))continue;
            if(seen.size()>MAX_VISITS)throw new IllegalStateException("SCOPED_CALL_NODE_BUDGET");
            if(n.getOpcode()==EGraphNode.Opcode.CALL)result.add(n);
            pending.addAll(n.getChildren());
        }
        return Collections.unmodifiableSet(result);
    }

    private boolean typed(EGraphNode n,QuantiVar q) {
        var type=q.getExactAlloyType();
        return type!=null && type.hasParserAuthenticatedAncestry()
                && type.sharesParserModuleAuthorityWith(module)
                && type.sameOccurrenceEvidenceAs(n.getExactAlloyType());
    }

    private static QuantiVar.Quantifier localKind(EGraphNode.Opcode opcode) {
        return switch(opcode) {
            case FORALL -> QuantiVar.Quantifier.ALL;
            case EXISTS -> QuantiVar.Quantifier.SOME;
            case ONE -> QuantiVar.Quantifier.ONE;
            case LONE -> QuantiVar.Quantifier.LONE;
            default -> null;
        };
    }

    private boolean retainedDomain(EGraphNode domain,QuantiVar q,Map<String,QuantiVar> visible,
            int depth,long parent) {
        if(domain.getOpcode()!=EGraphNode.Opcode.ONE || domain.getChildren().size()!=1
                || !typed(domain,q)) return false;
        var expression=domain.getChildren().get(0);
        if(!typed(expression,q)) return false;
        if(expression.getOpcode()==EGraphNode.Opcode.GLOBALBINDING)
            return expression.getChildren().isEmpty();
        // A retained unary carrier-minus-outer domain is not hoisted or erased.
        // Check its dependency before introducing the local owner (Lean:
        // RetainedDifferenceDomain.no_capture/domain_transport).
        if(expression.getOpcode()!=EGraphNode.Opcode.MINUS || expression.getChildren().size()!=2
                || q.getExactAlloyType().relationArity()!=1) return false;
        var carrier=expression.getChildren().get(0); var dependency=expression.getChildren().get(1);
        var outer=visible.get(dependency.getAlphaName());
        return carrier.getOpcode()==EGraphNode.Opcode.GLOBALBINDING && carrier.getChildren().isEmpty()
                && typed(carrier,q) && dependency.getOpcode()==EGraphNode.Opcode.VARIABLE
                && typed(dependency,q) && outerBindings.contains(outer)
                && walk(dependency,visible,depth+1,parent);
    }

    /** Called only after both endpoints satisfy the at-most-five-node domain grammar. */
    private static boolean sameDomain(EGraphNode original,EGraphNode comparison) {
        if(original.getOpcode()!=comparison.getOpcode()
                || original.getSourceOccurrenceLineage()!=comparison.getSourceOccurrenceLineage()
                || !original.getExactAlloyType().sameOccurrenceEvidenceAs(comparison.getExactAlloyType())
                || !Objects.equals(original.getSemanticIdentity(),comparison.getSemanticIdentity())
                || !Objects.equals(original.getSourceName(),comparison.getSourceName())
                || original.getChildren().size()!=comparison.getChildren().size()) return false;
        for(int i=0;i<original.getChildren().size();i++)
            if(!sameDomain(original.getChildren().get(i),comparison.getChildren().get(i))) return false;
        return true;
    }

    private boolean walk(EGraphNode n,Map<String,QuantiVar> visible,int depth,long parent) {
        if(++visits>MAX_VISITS || depth>MAX_DEPTH)
            throw new IllegalStateException("PAID_DECLARATION_LOCAL_SCOPE_BUDGET");
        if(n.getOpcode()==EGraphNode.Opcode.REF) return false;
        if(n.getOpcode()==EGraphNode.Opcode.CALL) {
            var c=CallMetadata.require(n);var type=n.getExactAlloyType();
            boolean resultType=type!=null &&
                    (c.kind().equals("call/expression") && type.kind()==ExactAlloyType.Kind.RELATION
                        && type.hasParserAuthenticatedAncestry() && type.sharesParserModuleAuthorityWith(module)
                    || c.kind().equals("call/formula") && type.kind()==ExactAlloyType.Kind.BOOL);
            if(n.isOrderInsensitive() || !resultType)return false;
            var original=sourceCalls.get(n.getSourceOccurrenceLineage());
            if(checkingSource) {
                if(original==null)sourceCalls.put(n.getSourceOccurrenceLineage(),n);
                else if(!BoundCallEvidence.sameScopedCall(original,n))return false;
            } else if(original==null || !BoundCallEvidence.sameScopedCall(original,n))return false;
            // Arguments are still traversed below in the actual lexical scope.
        }
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
            var q=visible.get(n.getAlphaName());
            if(q==null || !n.getChildren().isEmpty() || q.getExactAlloyType()==null
                    || !q.getExactAlloyType().hasParserAuthenticatedAncestry()
                    || !q.getExactAlloyType().sharesParserModuleAuthorityWith(module)
                    || !q.getExactAlloyType().equals(n.getExactAlloyType())) return false;
            // NormalForm's generated domain guards use synthetic type values.
            // Authority stays on the original scoped binding, never that value.
            if(n.getExactAlloyType().hasParserAuthenticatedAncestry() && !typed(n,q)) return false;
            if(checkingSource) {
                var prior=sourceVariables.putIfAbsent(n.getSourceOccurrenceLineage(),q);
                return prior==null || prior==q;
            }
            return sourceVariables.get(n.getSourceOccurrenceLineage())==q;
        }
        boolean declares=n.getChildren().stream().anyMatch(c->switch(c.getOpcode()) {
            case GENERICRELDECL,DISJ,VAR,DISJVAR -> true;
            default -> false;
        });
        if(declares || n.getOpcode()==EGraphNode.Opcode.EXISTS || n.getOpcode()==EGraphNode.Opcode.FORALL) {
            var kind=localKind(n.getOpcode());
            if(kind==null) return false;
            if(n.getChildren().size()<2) return false;
            var plan=sourcePlans.get(n.getSourceOccurrenceLineage());
            int groupCount=n.getChildren().size()-1;
            if(!checkingSource && (plan==null || plan.parent()!=parent || plan.groups().size()!=groupCount))return false;
            var scoped=new HashMap<>(visible);var groups=new ArrayList<LocalGroup>();
            int ordinal=0;String ownerPath=null;
            for(int groupIndex=0;groupIndex<groupCount;groupIndex++) {
            var decl=n.getChildren().get(groupIndex);
            if(decl.getOpcode()!=EGraphNode.Opcode.GENERICRELDECL || decl.getChildren().size()<2) return false;
            var domain=decl.getChildren().get(0);
            var previous=checkingSource?null:plan.groups().get(groupIndex);
            if(!checkingSource && previous.slots().size()!=decl.getChildren().size()-1)return false;
            // Each domain is checked before this declaration's tuple enters scope.
            var domainScope=new HashMap<>(scoped);var slots=new ArrayList<LocalSlot>();String bindingPath=null;
            for(int i=1;i<decl.getChildren().size();i++) {
              var variable=decl.getChildren().get(i);
              if(variable.getOpcode()!=EGraphNode.Opcode.VARIABLE)return false;
              var q=checkingSource ? locals.get(variable.getAlphaName()) : previous.slots().get(i-1).binding();
              String name=variable.getAlphaName();
              if(q==null || name==null || !q.isFrozenForCertification() || scoped.containsKey(name)
                    || q.getBindingPath().isEmpty() || q.getQuantifier()!=kind
                    || q.getCardinality()!=QuantiVar.Cardinality.ONE || q.getDisjointnessClass()!=0
                    || !typed(variable,q) || !retainedDomain(domain,q,domainScope,depth,parent)
                    || !usedOwners.add(q.getName())) return false;
              if(bindingPath==null)bindingPath=q.getBindingPath();
              if(!bindingPath.equals(q.getBindingPath()))return false;
              if((groupCount>1 || decl.getChildren().size()>2) && !q.getDeBruijnKey().matches("local#[0-9]+#"+ordinal+":ONE:.*@"+java.util.regex.Pattern.quote(bindingPath)))return false;
              if(groupCount>1) {
                  String suffix="/local-decl["+groupIndex+"]";
                  if(!bindingPath.endsWith(suffix))return false;
                  String context=bindingPath.substring(0,bindingPath.length()-suffix.length());
                  if(ownerPath==null)ownerPath=context;
                  if(!ownerPath.equals(context))return false;
              }
              ordinal++;
              slots.add(new LocalSlot(q,variable.getSourceOccurrenceLineage()));scoped.put(name,q);
            }
            if(!checkingSource && (previous.declaration()!=decl.getSourceOccurrenceLineage()
                    || !sameSlots(previous.slots(),slots)
                    || !sameDomain(previous.domain(),domain))) return false;
            groups.add(new LocalGroup(List.copyOf(slots),decl.getSourceOccurrenceLineage(),domain));
            // Fast's local spelling differs from the original alpha name. The
            // actual owner/declaration/variable lineage licenses that transfer.
            // All ordered tuple slots share this owner, never an outer scope.
            for(int i=1;i<decl.getChildren().size();i++)
                if(!walk(decl.getChildren().get(i),scoped,depth+1,n.getSourceOccurrenceLineage()))return false;
            }
            if(checkingSource && sourcePlans.putIfAbsent(n.getSourceOccurrenceLineage(),
                    new LocalPlan(List.copyOf(groups),parent))!=null)return false;
            return walk(n.getChildren().get(groupCount),scoped,depth+1,n.getSourceOccurrenceLineage());
        }
        switch(n.getOpcode()) {
            case GENERICRELDECL, DISJ, VAR, DISJVAR -> { return false; }
            default -> { }
        }
        for(var child:n.getChildren()) if(!walk(child,visible,depth+1,parent)) return false;
        return true;
    }

    private static boolean sameSlots(List<LocalSlot> a,List<LocalSlot> b) {
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(a.get(i).binding()!=b.get(i).binding()
                || a.get(i).variable()!=b.get(i).variable())return false;
        return true;
    }
}
