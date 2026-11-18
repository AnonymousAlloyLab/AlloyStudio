package is.fivefivefive.CanDis;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.TraceResources;

/** Original local declaration occurrences transported to a comparison tree.
 * Regrouping retains checked original objects; no slot is exchanged or hoisted. */
final class LocalOwnerCorrespondence {
    private record Slot(QuantiVar binding,long occurrence,int declaration,int tuple) { }
    private record Group(EGraphNode.Opcode opcode,long occurrence,long domain,List<Slot> slots) { }
    private record Owner(EGraphNode.Opcode opcode,long parent,List<Group> groups) { }
    private record CallSite(long lineage,long owner) { }
    private final Map<String,QuantiVar> locals=new HashMap<>();
    private final Map<Long,QuantiVar> occurrences=new HashMap<>();
    private final Map<Long,Owner> owners=new LinkedHashMap<>();
    private final Map<Long,StructuralDeclarationOrigin> structuralDeclarations=new HashMap<>();
    private final Map<CallSite,EGraphNode> sourceCalls=new HashMap<>();
    private final List<Map.Entry<CallSite,EGraphNode>> comparisonCalls=new ArrayList<>();
    private final Map<EGraphNode,List<QuantiVar>> visibleAt=new IdentityHashMap<>();
    private final Map<EGraphNode,List<QuantiVar>> callSlots=new IdentityHashMap<>();
    private final TraceResources resources=new TraceResources();
    private final NormalForm source;
    private EGraphNode comparisonRoot;

    private LocalOwnerCorrespondence(NormalForm source) {
        this.source=source;
        for(var q:source.getLocalQuantiVars()) {
            require(q.isFrozenForCertification(),"Mutable local binding");
            require(locals.put(q.getName(),q)==null,"Ambiguous original local binding");
        }
        walk(source.getCertificationMatrixEGraph(),true,0,List.of());
    }
    static LocalOwnerCorrespondence check(NormalForm source,EGraphNode comparison) {
        var result=new LocalOwnerCorrespondence(source);
        result.walk(comparison,false,0,List.of());
        result.comparisonRoot=comparison;
        return result;
    }
    static boolean declaration(EGraphNode n) {
        return switch(n.getOpcode()) {case GENERICRELDECL,DISJ,VAR,DISJVAR -> true;default -> false;};
    }
    Set<EGraphNode> checkedCalls() {
        indexInvocations(source.getCertificationMatrixEGraph());
        indexInvocations(comparisonRoot);
        var result=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        for(var original:sourceCalls.values()) {
            original.requirePristineCertificationSource();
            CallMetadata.require(original);
            result.add(original);
        }
        for(var entry:comparisonCalls) {
            var original=sourceCalls.get(entry.getKey());var current=entry.getValue();
            if(original!=null && BoundCallEvidence.sameSourceScopedCall(original,current)
                    && sameOuterVariables(original,current)
                    && sameBindings(callSlots.get(original),callSlots.get(current)))result.add(current);
        }
        return Collections.unmodifiableSet(result);
    }
    private boolean sameOuterVariables(EGraphNode original,EGraphNode comparison) {
        if(original.getOpcode()==EGraphNode.Opcode.VARIABLE
                && !occurrences.containsKey(original.getSourceOccurrenceLineage())
                && (comparison.hasComparisonLocalCoordinate()
                    || !Objects.equals(original.getAlphaName(),comparison.getAlphaName())))return false;
        for(int i=0;i<original.getChildren().size();i++)
            if(!sameOuterVariables(original.getChildren().get(i),comparison.getChildren().get(i)))return false;
        return true;
    }
    private static boolean sameBindings(List<QuantiVar> a,List<QuantiVar> b) {
        if(a==null||b==null||a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(a.get(i)!=b.get(i))return false;
        return true;
    }
    private record Invocation(EGraphNode node,Map<String,String> slots) { }
    private static Invocation childInvocation(EGraphNode.EClassRef child,Map<String,String> parent) {
        var canonical=child.canonical();
        require(canonical.getEClass()==child.getEClass(),"Call representative differs from its canonical invocation");
        var composed=new LinkedHashMap<String,String>();
        // Like an unregistered EClassRef.canonical(), restrict obsolete keys;
        // every currently exposed slot must still have an actual caller target.
        for(var slot:child.getEClass().getSlots()) {
            var caller=child.getSlotMap().get(slot);
            require(caller!=null,"Incomplete call invocation map");
            var target=parent.get(caller);
            require(target!=null,"Call invocation escapes caller slots");
            require(target.equals(parent.get(canonical.getSlotMap().get(slot))),"Call canonical slot transport differs");
            composed.put(slot,target);
        }
        return new Invocation(child.getEClass().getRepresentative(),Map.copyOf(composed));
    }
    private void indexInvocations(EGraphNode root) {
        var calls=new IdentityHashMap<EGraphNode,Boolean>();
        if(!containsCall(root,calls))return;
        var pending=new ArrayDeque<Invocation>();
        pending.push(childInvocation(root.getEClassRef(),root.getEClassRef().getSlotMap()));
        while(!pending.isEmpty()) {
            resources.spend(64);var invocation=pending.pop();var n=invocation.node();
            if(n.getOpcode()==EGraphNode.Opcode.CALL) {
                var slots=new ArrayList<QuantiVar>();argumentSlots(invocation,slots);
                var previous=callSlots.putIfAbsent(n,List.copyOf(slots));
                require(previous==null||sameBindings(previous,slots),"Ambiguous effective call invocation");
            }
            for(var child:n.getChildClasses())if(containsCall(child.getEClass().getRepresentative(),calls))
                pending.push(childInvocation(child,invocation.slots()));
        }
    }
    private boolean containsCall(EGraphNode n,Map<EGraphNode,Boolean> memo) {
        var found=memo.get(n);if(found!=null)return found;
        resources.spend(64);boolean result=n.getOpcode()==EGraphNode.Opcode.CALL;
        for(var child:n.getChildren())result|=containsCall(child,memo);
        memo.put(n,result);return result;
    }
    private void argumentSlots(Invocation invocation,List<QuantiVar> slots) {
        resources.spend(64);var n=invocation.node();
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE) {
            String name=invocation.slots().get(n.getAlphaName()==null?n.getSourceName():n.getAlphaName());
            require(name!=null,"Unmapped call variable");
            var candidates=new ArrayList<QuantiVar>();
            for(var qs:List.of(source.getParams(),source.getInheritedQuantiVars(),source.getMatrixQuantiVars()))
                for(var q:qs)if(q.getName().equals(name))candidates.add(q);
            for(var q:visibleAt.getOrDefault(n,List.of()))
                if(q.getName().equals(name)||("@fast-local:"+coordinate(q)).equals(name))candidates.add(q);
            require(candidates.size()==1,"Call variable lacks a unique visible owned slot");
            var binding=candidates.get(0);
            require(binding.isFrozenForCertification()&&binding.getExactAlloyType()!=null
                    &&binding.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType()),"Call effective slot type authority");
            slots.add(binding);
        }
        for(var child:n.getChildClasses())argumentSlots(childInvocation(child,invocation.slots()),slots);
    }
    StructuralDeclarationOrigin structuralOrigin(EGraphNode comparison){
        var origin=structuralDeclarations.get(comparison.getSourceOccurrenceLineage());
        require(origin!=null&&origin.admits(comparison),"Missing unchanged original structural declaration objects");return origin;
    }
    private static void require(boolean ok,String message) {
        if(!ok)throw new IllegalArgumentException("LOCAL_OWNER_CORRESPONDENCE: "+message);
    }
    private static boolean contains(List<QuantiVar> qs,QuantiVar q) {
        return qs.stream().anyMatch(x->x==q);
    }
    private QuantiVar variable(EGraphNode n,boolean original,List<QuantiVar> visible) {
        var q=original?locals.get(n.getAlphaName()):occurrences.get(n.getSourceOccurrenceLineage());
        if(q==null)return null; // Prefix variables are checked by the outer binding bridge.
        require(contains(visible,q),"Local occurrence escapes its actual owner");
        require(q.getExactAlloyType()!=null && q.getExactAlloyType().equals(n.getExactAlloyType()),"Local occurrence type");
        require(n.getExactAlloyType()!=null && (!n.getExactAlloyType().hasParserAuthenticatedAncestry()
                || q.getExactAlloyType().sameOccurrenceEvidenceAs(n.getExactAlloyType())),"Foreign local type authority");
        if(original) {
            var prior=occurrences.putIfAbsent(n.getSourceOccurrenceLineage(),q);
            require(prior==null||prior==q,"Ambiguous original local occurrence");
        } else {
            var key=coordinate(q);
            require(n.hasComparisonLocalCoordinate()?n.getAlphaName().equals("@fast-local:"+key)
                    :q.getName().equals(n.getAlphaName()),"Substituted local owner or tuple coordinate");
        }
        return q;
    }
    QuantiVar originalBinding(EGraphNode n) {
        var q=occurrences.get(n.getSourceOccurrenceLineage());
        require(q!=null,"Missing original local occurrence");
        return q;
    }
    static String coordinate(QuantiVar q) {
        var m=java.util.regex.Pattern.compile("local#([0-9]+)#([0-9]+):.*").matcher(q.getDeBruijnKey());
        require(m.matches(),"Missing original lexical coordinate");return m.group(1)+":"+m.group(2);
    }
    private void walk(EGraphNode n,boolean original,long parent,List<QuantiVar> visible) {
        resources.spend(64);
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE)
            visibleAt.merge(n,List.copyOf(visible),(a,b)->a.stream().filter(q->contains(b,q)).toList());
        if(n.getOpcode()==EGraphNode.Opcode.CALL) {
            var site=new CallSite(n.getSourceOccurrenceLineage(),parent);
            if(original) {
                var previous=sourceCalls.putIfAbsent(site,n);
                require(previous==null||BoundCallEvidence.sameSourceScopedCall(previous,n),"Ambiguous original call occurrence");
            } else comparisonCalls.add(Map.entry(site,n));
        }
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE)variable(n,original,visible);
        var cs=n.getChildren();
        if(!original) {
            var restored=new ArrayList<EGraphNode>();
            for(var c:cs) {
                if(declaration(c))restored.addAll(c.comparisonDeclarationGroups(n));
                else restored.add(c);
            }
            cs=restored;
        }
        if(cs.stream().noneMatch(LocalOwnerCorrespondence::declaration)) {
            for(var c:cs)walk(c,original,parent,visible);
            return;
        }
        var prior=owners.get(n.getSourceOccurrenceLineage());
        if(!original)require(prior!=null&&prior.parent()==parent&&prior.opcode()==n.getOpcode(),"Foreign local owner/parent/operator");
        var groups=new ArrayList<Group>();var scope=new ArrayList<>(visible);int ordinal=0,index=0;String ownerPath=null;
        while(index<cs.size()&&declaration(cs.get(index))) {
            var d=cs.get(index);var dc=d.getChildren();
            if(original&&d.getOpcode()!=EGraphNode.Opcode.GENERICRELDECL){
                var origin=source.structuralDeclarationOrigin(d);if(origin!=null)structuralDeclarations.put(d.getSourceOccurrenceLineage(),origin);
            }
            require(dc.size()>=2,"Declaration lacks domain or slots");
            // Dependencies are visited before this group's slots enter scope.
            walk(dc.get(0),original,parent,scope);
            Group old=null;
            if(!original) {
                require(index<prior.groups().size(),"Added declaration group");old=prior.groups().get(index);
                require(old.opcode()==d.getOpcode()&&old.occurrence()==d.getSourceOccurrenceLineage()
                        &&old.domain()==dc.get(0).getSourceOccurrenceLineage()&&old.slots().size()==dc.size()-1,
                        "Substituted declaration/domain/tuple");
            }
            var slots=new ArrayList<Slot>();String groupPath=null;
            for(int j=1;j<dc.size();j++) {
                var v=dc.get(j);require(v.getOpcode()==EGraphNode.Opcode.VARIABLE,"Nonvariable declaration slot");
                var q=original?locals.get(v.getAlphaName()):old.slots().get(j-1).binding();
                require(q!=null&&!contains(scope,q),"Missing or captured source slot");
                require(q.getExactAlloyType()!=null&&q.getExactAlloyType().hasParserAuthenticatedAncestry()
                        &&q.getExactAlloyType().sameOccurrenceEvidenceAs(v.getExactAlloyType()),"Declaration slot type authority");
                require(coordinate(q).endsWith(":"+ordinal++),"Original ordered local ordinal");
                if(groupPath==null)groupPath=q.getBindingPath();
                require(!groupPath.isEmpty()&&groupPath.equals(q.getBindingPath()),"Grouped slots have different owners");
                String suffix="/local-decl["+index+"]";
                require(groupPath.endsWith(suffix)&&q.getDeBruijnKey().endsWith("@"+groupPath),"Declaration path/slot authority");
                String context=groupPath.substring(0,groupPath.length()-suffix.length());
                if(ownerPath==null)ownerPath=context;
                require(ownerPath.equals(context),"Declarations belong to different owners");
                if(!original)require(old.slots().get(j-1).occurrence()==v.getSourceOccurrenceLineage(),"Substituted tuple occurrence");
                scope.add(q);variable(v,original,scope);
                slots.add(new Slot(q,v.getSourceOccurrenceLineage(),index,j-1));
            }
            groups.add(new Group(d.getOpcode(),d.getSourceOccurrenceLineage(),dc.get(0).getSourceOccurrenceLineage(),List.copyOf(slots)));index++;
        }
        require(index<cs.size(),"Local owner has no body");
        if(!original)require(index==prior.groups().size(),"Dropped declaration group");
        var plan=new Owner(n.getOpcode(),parent,List.copyOf(groups));
        if(original) {
            var existing=owners.putIfAbsent(n.getSourceOccurrenceLineage(),plan);
            // Identity of each binding, not QuantiVar's numeric-id equality.
            if(existing!=null)require(same(existing,plan),"Ambiguous source owner");
        }
        for(;index<cs.size();index++) {
            require(!declaration(cs.get(index)),"Declaration after body");
            walk(cs.get(index),original,n.getSourceOccurrenceLineage(),scope);
        }
    }
    private static boolean same(Owner a,Owner b) {
        if(a.opcode()!=b.opcode()||a.parent()!=b.parent()||a.groups().size()!=b.groups().size())return false;
        for(int i=0;i<a.groups().size();i++) {
            var x=a.groups().get(i);var y=b.groups().get(i);
            if(x.opcode()!=y.opcode()||x.occurrence()!=y.occurrence()||x.domain()!=y.domain()||x.slots().size()!=y.slots().size())return false;
            for(int j=0;j<x.slots().size();j++)if(x.slots().get(j).binding()!=y.slots().get(j).binding()
                    ||x.slots().get(j).occurrence()!=y.slots().get(j).occurrence())return false;
        }
        return true;
    }
    JSONObject json() {
        var rows=new JSONArray();
        for(var entry:owners.entrySet()) {var groups=new JSONArray();
            for(var g:entry.getValue().groups()){var slots=new JSONArray();for(var s:g.slots())slots.put(new JSONObject()
                    .put("occurrence",s.occurrence()).put("declaration",s.declaration()).put("tuple",s.tuple())
                    .put("originalAlpha",s.binding().getName()).put("ownerPath",s.binding().getBindingPath())
                    .put("lexicalCoordinate",coordinate(s.binding())).put("kind",s.binding().getQuantifier())
                    .put("cardinality",s.binding().getCardinality()).put("disjointness",s.binding().getDisjointnessClass())
                    .put("type",s.binding().getExactAlloyType().stableString()));
                groups.put(new JSONObject().put("opcode",g.opcode()).put("occurrence",g.occurrence()).put("domainOccurrence",g.domain()).put("slots",slots));}
            rows.put(new JSONObject().put("ownerOccurrence",entry.getKey()).put("parentOccurrence",entry.getValue().parent())
                    .put("opcode",entry.getValue().opcode()).put("orderedDeclarations",groups));}
        return new JSONObject().put("owners",rows).put("sourceOccurrenceTransportChecked",true).put("semanticExchangeClaimed",false);
    }
}
