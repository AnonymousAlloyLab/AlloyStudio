package is.fivefivefive.CanDis.core;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Outer paid links through retained unary ONE/ALL scopes, never local alpha edits. */
final class FastNestedPaidScope {
    private static final int MAX_VISITS=4096, MAX_DEPTH=64;
    private record Variable(QuantiVar binding,ExactAlloyType type) { }
    private record Local(QuantiVar binding,long declaration,long variable,long domain) { }
    private final Map<String,QuantiVar> locals=new HashMap<>();
    private final Map<Long,Variable> variables=new HashMap<>();
    private final Map<Long,Local> owners=new HashMap<>();
    private final Set<Long> comparisonOwners=new HashSet<>();
    private final ExactAlloyType module;
    private int visits,ones,alls,exists;
    private final boolean existsOnly;
    private boolean original=true;

    private FastNestedPaidScope(ExactAlloyType module,boolean existsOnly) {
        this.module=module; this.existsOnly=existsOnly;
    }

    static boolean supportsPair(List<NormalForm> left,List<NormalForm> right) {
        if(left.size()!=1 || right.size()!=1) return false;
        boolean a=!left.get(0).getLocalQuantiVars().isEmpty(), b=!right.get(0).getLocalQuantiVars().isEmpty();
        return a!=b && supports(left.get(0),a) && supports(right.get(0),b);
    }

    private static boolean supports(NormalForm form,boolean nested) {
        return supports(form,nested,false);
    }

    /** Replay-only owner census, not admission to the scalar candidate search. */
    static boolean retainedExistentials(NormalForm form) {
        return supports(form,!form.getLocalQuantiVars().isEmpty(),true);
    }

    static Set<Long> retainedExistentialOwners(NormalForm form) {
        if(!retainedExistentials(form)) throw new IllegalArgumentException("Retained existential scope not checked");
        var result=new HashSet<Long>(); var pending=new ArrayDeque<EGraphNode>();
        pending.add(form.getCertificationMatrixEGraph());
        // The expanded visit/depth budget was checked by retainedExistentials.
        while(!pending.isEmpty()) {
            var n=pending.removeFirst();
            if(n.getOpcode()==EGraphNode.Opcode.EXISTS) result.add(n.getSourceOccurrenceLineage());
            pending.addAll(n.getChildren());
        }
        return Set.copyOf(result);
    }

    private static boolean supports(NormalForm form,boolean nested,boolean existsOnly) {
        if(!form.isFrozenForCertification() || form.getTemporalOp()!=NormalForm.TemporalOp.NONE
                || !form.getTemporalChildren().isEmpty() || !form.getParams().isEmpty()
                || !form.getInheritedQuantiVars().isEmpty() || !form.getPhaseLocalBindingImports().isEmpty()
                || form.getCertificationMatrixEGraph()==null || form.getMatrixEGraph()==null
                || form.getMatrixQuantiVars().isEmpty()) return false;
        var check=new FastNestedPaidScope(form.getMatrixQuantiVars().get(0).getExactAlloyType(),existsOnly);
        if(check.module==null) return false;
        var outer=new HashMap<String,QuantiVar>(); var slots=new HashMap<String,Integer>();
        for(var q:form.getMatrixQuantiVars()) {
            int slot=slots.merge(q.getBindingPath(),1,Integer::sum)-1;
            String type=q.getTypeName(); if(type==null) return false;
            if(type.startsWith("VAR_")) type=type.substring(4);
            if(!check.binding(q) || existsOnly && q.getDisjointnessClass()!=0 || q.getQuantifier()!=QuantiVar.Quantifier.ALL
                    || outer.put(q.getName(),q)!=null || !q.getDeBruijnKey().equals(
                            q.getBindingPath()+"#"+slot+":"+q.getCardinality()+":"+type)) return false;
        }
        for(var q:form.getLocalQuantiVars())
            if(!check.binding(q) || outer.containsKey(q.getName()) || check.locals.put(q.getName(),q)!=null) return false;
        if(!check.walk(form.getCertificationMatrixEGraph(),outer,Map.of(),0,0)) return false;
        if(existsOnly) {
            if(nested && check.exists==0) return false;
        } else if(nested ? check.ones==0 || check.alls==0 : !check.owners.isEmpty()) return false;
        check.original=false;
        return check.walk(form.getMatrixEGraph(),outer,Map.of(),0,0)
                && (!existsOnly || check.comparisonOwners.equals(check.owners.keySet()));
    }

    private boolean binding(QuantiVar q) {
        var t=q.getExactAlloyType();
        return q.isFrozenForCertification() && !q.getBindingPath().isEmpty()
                && q.getCardinality()==QuantiVar.Cardinality.ONE && q.getDisjointnessClass()>=0
                && t!=null && t.hasParserAuthenticatedAncestry() && t.sharesParserModuleAuthorityWith(module);
    }

    private boolean variable(EGraphNode n,Map<String,QuantiVar> visible,Map<String,String> names) {
        if(!n.getChildren().isEmpty() || n.getExactAlloyType()==null) return false;
        var old=variables.get(n.getSourceOccurrenceLineage());
        var q=original?visible.get(n.getAlphaName()):old==null?null:old.binding();
        if(q==null || visible.get(q.getName())!=q || !q.getExactAlloyType().equals(n.getExactAlloyType())) return false;
        var type=n.getExactAlloyType();
        if(type.hasParserAuthenticatedAncestry() && !type.sharesParserModuleAuthorityWith(module)) return false;
        if(original) {
            var previous=variables.putIfAbsent(n.getSourceOccurrenceLineage(),new Variable(q,type));
            return previous==null || previous.binding()==q && previous.type().sameOccurrenceEvidenceAs(type);
        }
        if(old.type().hasParserAuthenticatedAncestry()!=type.hasParserAuthenticatedAncestry()
                || old.type().hasParserAuthenticatedAncestry() && !old.type().sameOccurrenceEvidenceAs(type)) return false;
        String expected=names.getOrDefault(q.getName(),q.getName());
        return n.getAlphaName().equals(q.getName()) && !n.hasComparisonLocalCoordinate()
                || names.containsKey(q.getName()) && n.hasComparisonLocalCoordinate() && n.getAlphaName().equals(expected);
    }

    private boolean walk(EGraphNode n,Map<String,QuantiVar> visible,Map<String,String> names,int depth,int height) {
        if(++visits>MAX_VISITS || height>MAX_DEPTH) throw new IllegalStateException("NESTED_PAID_SCOPE_BUDGET");
        if(n.getOpcode()==EGraphNode.Opcode.CALL || n.getOpcode()==EGraphNode.Opcode.REF) return false;
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) return variable(n,visible,names);
        boolean hasDeclaration=n.getChildren().stream().anyMatch(c->c.getOpcode()==EGraphNode.Opcode.GENERICRELDECL
                || c.getOpcode()==EGraphNode.Opcode.DISJ || c.getOpcode()==EGraphNode.Opcode.VAR || c.getOpcode()==EGraphNode.Opcode.DISJVAR);
        if(hasDeclaration) {
            var kind=n.getOpcode();
            if((existsOnly ? kind!=EGraphNode.Opcode.EXISTS
                    : kind!=EGraphNode.Opcode.ONE && kind!=EGraphNode.Opcode.FORALL) || n.getChildren().size()!=2) return false;
            var decl=n.getChildren().get(0);
            if(decl.getOpcode()!=EGraphNode.Opcode.GENERICRELDECL || decl.getChildren().size()!=2) return false;
            var domain=decl.getChildren().get(0); var v=decl.getChildren().get(1);
            if(domain.getOpcode()!=EGraphNode.Opcode.ONE || domain.getChildren().size()!=1
                    || v.getOpcode()!=EGraphNode.Opcode.VARIABLE) return false;
            Local plan=owners.get(n.getSourceOccurrenceLineage());
            var q=original?locals.get(v.getAlphaName()):plan==null?null:plan.binding();
            if(q==null || visible.containsKey(q.getName()) || q.getDisjointnessClass()!=0
                    || q.getQuantifier()!=(kind==EGraphNode.Opcode.EXISTS?QuantiVar.Quantifier.SOME
                            :kind==EGraphNode.Opcode.ONE?QuantiVar.Quantifier.ONE:QuantiVar.Quantifier.ALL)
                    || !q.getExactAlloyType().sameOccurrenceEvidenceAs(v.getExactAlloyType())) return false;
            // Domain sees only the enclosing scope, never the variable it declares.
            if(!walk(domain,visible,names,depth+1,height+1)) return false;
            var current=new Local(q,decl.getSourceOccurrenceLineage(),v.getSourceOccurrenceLineage(),domain.getSourceOccurrenceLineage());
            if(original) {
                var previous=owners.putIfAbsent(n.getSourceOccurrenceLineage(),current);
                if(previous!=null && !previous.equals(current)) return false;
                if(kind==EGraphNode.Opcode.ONE) ones++;
                else if(kind==EGraphNode.Opcode.FORALL) alls++;
                else exists++;
            } else {
                if(!current.equals(plan)) return false;
                comparisonOwners.add(n.getSourceOccurrenceLineage());
            }
            var scoped=new HashMap<>(visible); scoped.put(q.getName(),q);
            var localNames=new HashMap<>(names); localNames.put(q.getName(),"@fast-local:"+depth+":0");
            return variable(v,scoped,localNames) && walk(n.getChildren().get(1),scoped,localNames,depth+1,height+1);
        }
        switch(n.getOpcode()) {
            case GENERICRELDECL,DISJ,VAR,DISJVAR -> { return false; }
            default -> { }
        }
        for(var child:n.getChildren()) if(!walk(child,visible,names,depth,height+1)) return false;
        return true;
    }
}
