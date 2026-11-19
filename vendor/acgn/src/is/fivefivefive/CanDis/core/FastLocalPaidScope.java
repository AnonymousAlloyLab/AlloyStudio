package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Eligibility only: retained local binders are neither hoisted nor paid outer links. */
public final class FastLocalPaidScope {
    private static final int MAX_VISITS = 4096;
    private static final int MAX_DEPTH = 128;
    private static final long ROOT_OWNER = Long.MIN_VALUE;
    private final Map<String,QuantiVar> locals = new HashMap<>();
    private final Set<Long> selectedOwners = new HashSet<>();
    private final Map<QuantiVar,SlotOrigin> slotOrigins = new IdentityHashMap<>();
    private final Map<Long,LocalPlan> sourcePlans = new HashMap<>();
    private final Map<Long,QuantiVar> sourceVariables = new HashMap<>();
    private final Map<EGraphNode,QuantiVar> lexicalBindings = new IdentityHashMap<>();
    private final Map<Long,EGraphNode> sourceCalls = new HashMap<>();
    private final ExactAlloyType module;
    private int visits;
    private int mapVisits;
    private boolean checkingSource = true;

    private record LocalSlot(QuantiVar binding,long variable) { }
    private record LocalGroup(List<LocalSlot> slots,long declaration,EGraphNode domain,EGraphNode.Opcode opcode) { }
    private record LocalPlan(List<LocalGroup> groups,long parent) { }
    private record SlotOrigin(long owner,long parent,long declaration,long variable,int ordinal) { }

    private FastLocalPaidScope(ExactAlloyType module) { this.module=module; }

    static boolean supports(NormalForm form) {
        return checkedOwnerCount(form)>0;
    }

    static int checkedOwnerCount(NormalForm form) {
        var outer=new HashMap<String,QuantiVar>();
        for(var q:form.getMatrixQuantiVars()) if(outer.put(q.getName(),q)!=null) return 0;
        if(outer.isEmpty()) return 0;
        var check=new FastLocalPaidScope(form.getMatrixQuantiVars().get(0).getExactAlloyType());
        for(var q:form.getLocalQuantiVars()) {
            if(outer.containsKey(q.getName()) || check.locals.put(q.getName(),q)!=null) return 0;
        }
        if(!check.walk(form.getCertificationMatrixEGraph(),outer,0,ROOT_OWNER) || check.sourcePlans.isEmpty()) return 0;
        // Distinct retained owners may share a parent. walk checks each actual
        // owner identity, parent edge and ordered slots; no scope is exchanged.
        check.checkingSource=false;
        return check.walk(form.getMatrixEGraph(),outer,0,ROOT_OWNER)
                && check.resolvedVariables(form.getCertificationMatrixEGraph())
                && check.resolvedVariables(form.getMatrixEGraph())?check.selectedOwners.size():0;
    }

    private boolean resolvedVariables(EGraphNode root) {
        var ref=root.getEClassRef();
        return resolvedVariables(ref,ref.getSlotMap(),0);
    }

    /** A representative's spelling is not its invocation. Resolve the actual
     * edge chain to the owned binding authenticated by the lexical walk. */
    private boolean resolvedVariables(EGraphNode.EClassRef ref,Map<String,String> parent,int depth) {
        if(++mapVisits>MAX_VISITS || depth>MAX_DEPTH)
            throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_LOCAL_SCOPE_BUDGET");
        var canonical=ref.canonical();
        if(canonical.getEClass()!=ref.getEClass())return false;
        var effective=new HashMap<String,String>();
        // canonical() restricts obsolete keys on unregistered e-classes, but
        // every live slot must have a complete, agreeing caller resolution.
        for(var slot:ref.getEClass().getSlots()) {
            var caller=ref.getSlotMap().get(slot);
            var target=parent.get(caller);
            if(target==null || !target.equals(parent.get(canonical.getSlotMap().get(slot))))return false;
            effective.put(slot,target);
        }
        var node=ref.getEClass().getRepresentative();
        if(node.getOpcode()==EGraphNode.Opcode.VARIABLE) {
            var name=effective.get(node.getAlphaName());
            var expected=sourceVariables.get(node.getSourceOccurrenceLineage());
            // Fresh original bindings have one alias per lexical scope. Check
            // this invocation's actual representative, not a sibling's name.
            if(name==null || expected==null || lexicalBindings.get(node)!=expected
                    || !name.equals(node.getAlphaName()))return false;
        }
        for(var child:node.getChildClasses())if(!resolvedVariables(child,effective,depth+1))return false;
        return true;
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
            if(seen.size()>MAX_VISITS)throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("SCOPED_CALL_NODE_BUDGET");
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
            case COMPREHENSION -> QuantiVar.Quantifier.COMPREHENSION;
            default -> null;
        };
    }

    /** Exact retained expression, not a rewrite or free cross-carrier transport. */
    private static boolean sameDomain(EGraphNode original,EGraphNode comparison) {
        if(original.getOpcode()!=comparison.getOpcode()
                || original.getMetatype()!=comparison.getMetatype()
                || original.isOrderInsensitive()!=comparison.isOrderInsensitive()
                || original.isFlexibleArity()!=comparison.isFlexibleArity()
                || original.getMaxArity()!=comparison.getMaxArity()
                || original.getSourceOccurrenceLineage()!=comparison.getSourceOccurrenceLineage()
                || original.getExactAlloyType()==null
                || !original.getExactAlloyType().sameOccurrenceEvidenceAs(comparison.getExactAlloyType())
                || !original.getSemanticProfile().equals(comparison.getSemanticProfile())
                || !Objects.equals(original.getSemanticIdentity(),comparison.getSemanticIdentity())
                || !Objects.equals(original.getSourceName(),comparison.getSourceName())
                || (original.getOpcode()!=EGraphNode.Opcode.VARIABLE
                    && !Objects.equals(original.getAlphaName(),comparison.getAlphaName()))
                || original.getChildren().size()!=comparison.getChildren().size()) return false;
        if(original.getOpcode()==EGraphNode.Opcode.CALL
                && !original.sharesCallSourceAnchorWith(comparison)) return false;
        for(int i=0;i<original.getChildren().size();i++)
            if(!sameDomain(original.getChildren().get(i),comparison.getChildren().get(i))) return false;
        return true;
    }

    private boolean walk(EGraphNode n,Map<String,QuantiVar> visible,int depth,long parent) {
        return walk(n,visible,depth,parent,false);
    }

    private boolean walk(EGraphNode n,Map<String,QuantiVar> visible,int depth,long parent,boolean negated) {
        if(++visits>MAX_VISITS || depth>MAX_DEPTH)
            throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("PAID_DECLARATION_LOCAL_SCOPE_BUDGET");
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
                if(prior!=null && prior!=q)return false;
            } else if(sourceVariables.get(n.getSourceOccurrenceLineage())!=q)return false;
            var prior=lexicalBindings.putIfAbsent(n,q);
            return prior==null || prior==q;
        }
        var children=n.getChildren();
        if(!checkingSource) {
            // Restore the captured ordered groups before checking their actual
            // source owners and domains; a merged display tuple is not a scope.
            var restored=new ArrayList<EGraphNode>();
            for(var child:children)
                restored.addAll(child.comparisonDeclarationGroups(n));
            children=restored;
        }
        boolean declares=children.stream().anyMatch(c->switch(c.getOpcode()) {
            case GENERICRELDECL,DISJ,VAR,DISJVAR -> true;
            default -> false;
        });
        if(declares || n.getOpcode()==EGraphNode.Opcode.EXISTS || n.getOpcode()==EGraphNode.Opcode.FORALL) {
            var kind=localKind(n.getOpcode());
            if(negated)kind=kind==QuantiVar.Quantifier.ONE?QuantiVar.Quantifier.NOTONE
                    :kind==QuantiVar.Quantifier.LONE?QuantiVar.Quantifier.NOTLONE:null;
            if(kind==null) return false;
            if(children.size()<2) return false;
            var plan=sourcePlans.get(n.getSourceOccurrenceLineage());
            int groupCount=children.size()-1;
            if(plan==null && !checkingSource
                    || plan!=null && (plan.parent()!=parent || plan.groups().size()!=groupCount))return false;
            var scoped=new HashMap<>(visible);var groups=new ArrayList<LocalGroup>();
            int ordinal=0;String ownerPath=null;
            for(int groupIndex=0;groupIndex<groupCount;groupIndex++) {
            var decl=children.get(groupIndex);
            boolean disjoint=decl.getOpcode()==EGraphNode.Opcode.DISJ;
            if((!disjoint && decl.getOpcode()!=EGraphNode.Opcode.GENERICRELDECL)
                    || decl.getChildren().size()<2) return false;
            var domain=decl.getChildren().get(0);
            if(domain.getOpcode()!=EGraphNode.Opcode.ONE || domain.getChildren().size()!=1)return false;
            var previous=plan==null?null:plan.groups().get(groupIndex);
            if(previous!=null && (previous.slots().size()!=decl.getChildren().size()-1
                    || previous.opcode()!=decl.getOpcode()))return false;
            // RetainedDomainAdmission: check the domain once in the enclosing
            // scope, before any slot from this declaration group is introduced.
            if(!walk(domain.getChildren().get(0),scoped,depth+1,parent))return false;
            var slots=new ArrayList<LocalSlot>();String bindingPath=null;Integer disjointGroup=null;
            for(int i=1;i<decl.getChildren().size();i++) {
              var variable=decl.getChildren().get(i);
              if(variable.getOpcode()!=EGraphNode.Opcode.VARIABLE)return false;
              var q=checkingSource ? locals.get(variable.getAlphaName()) : previous.slots().get(i-1).binding();
              String name=variable.getAlphaName();
              if(q==null || name==null || !q.isFrozenForCertification() || scoped.containsKey(name)
                    || q.getBindingPath().isEmpty() || q.getQuantifier()!=kind
                    || q.getCardinality()!=QuantiVar.Cardinality.ONE
                    || (disjoint?q.getDisjointnessClass()<=0:q.getDisjointnessClass()!=0)
                    || !typed(variable,q) || !typed(domain,q) || !typed(domain.getChildren().get(0),q)) return false;
              if(disjointGroup==null)disjointGroup=q.getDisjointnessClass();
              if(disjointGroup!=q.getDisjointnessClass())return false;
              // Cloned uses of one source binder are visits, not fresh owners.
              // Register the exact slot origin; still check every body and edge.
              var origin=new SlotOrigin(n.getSourceOccurrenceLineage(),parent,
                      decl.getSourceOccurrenceLineage(),variable.getSourceOccurrenceLineage(),ordinal);
              var issued=slotOrigins.get(q);
              if(checkingSource && issued==null)slotOrigins.put(q,origin);
              else if(!origin.equals(issued))return false;
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
            if(previous!=null && (previous.declaration()!=decl.getSourceOccurrenceLineage()
                    || !sameSlots(previous.slots(),slots)
                    || !sameDomain(previous.domain(),domain))) return false;
            groups.add(new LocalGroup(List.copyOf(slots),decl.getSourceOccurrenceLineage(),domain,decl.getOpcode()));
            // Fast's local spelling differs from the original alpha name. The
            // actual owner/declaration/variable lineage licenses that transfer.
            // All ordered tuple slots share this owner, never an outer scope.
            for(int i=1;i<decl.getChildren().size();i++)
                if(!walk(decl.getChildren().get(i),scoped,depth+1,n.getSourceOccurrenceLineage()))return false;
            }
            if(checkingSource)sourcePlans.putIfAbsent(n.getSourceOccurrenceLineage(),
                    new LocalPlan(List.copyOf(groups),parent));
            else selectedOwners.add(n.getSourceOccurrenceLineage());
            return walk(children.get(groupCount),scoped,depth+1,n.getSourceOccurrenceLineage());
        }
        switch(n.getOpcode()) {
            case GENERICRELDECL, DISJ, VAR, DISJVAR -> { return false; }
            default -> { }
        }
        if(n.getOpcode()==EGraphNode.Opcode.NOT && children.size()==1) {
            var child=children.get(0);
            if(child.getChildren().size()>1 && (child.getOpcode()==EGraphNode.Opcode.ONE
                    || child.getOpcode()==EGraphNode.Opcode.LONE))
                return walk(child,visible,depth+1,parent,true);
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
