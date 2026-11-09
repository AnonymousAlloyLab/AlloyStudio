package is.fivefivefive.CanDis;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.TraceResources;

/** Original local declaration occurrences transported to a comparison tree.
 * No declaration is exchanged, hoisted or erased by this correspondence. */
final class LocalOwnerCorrespondence {
    private record Slot(QuantiVar binding,long occurrence,int declaration,int tuple) { }
    private record Group(EGraphNode.Opcode opcode,long occurrence,long domain,List<Slot> slots) { }
    private record Owner(EGraphNode.Opcode opcode,long parent,List<Group> groups) { }
    private final Map<String,QuantiVar> locals=new HashMap<>();
    private final Map<Long,QuantiVar> occurrences=new HashMap<>();
    private final Map<Long,Owner> owners=new LinkedHashMap<>();
    private final TraceResources resources=new TraceResources();

    private LocalOwnerCorrespondence(NormalForm source) {
        for(var q:source.getLocalQuantiVars()) {
            require(q.isFrozenForCertification(),"Mutable local binding");
            require(locals.put(q.getName(),q)==null,"Ambiguous original local binding");
        }
        walk(source.getCertificationMatrixEGraph(),true,0,List.of());
    }
    static LocalOwnerCorrespondence check(NormalForm source,EGraphNode comparison) {
        var result=new LocalOwnerCorrespondence(source);
        result.walk(comparison,false,0,List.of());
        return result;
    }
    static boolean declaration(EGraphNode n) {
        return switch(n.getOpcode()) {case GENERICRELDECL,DISJ,VAR,DISJVAR -> true;default -> false;};
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
        if(n.getOpcode()==EGraphNode.Opcode.VARIABLE)variable(n,original,visible);
        var cs=n.getChildren();
        if(cs.stream().noneMatch(LocalOwnerCorrespondence::declaration)) {
            for(var c:cs)walk(c,original,parent,visible);
            return;
        }
        var prior=owners.get(n.getSourceOccurrenceLineage());
        if(!original)require(prior!=null&&prior.parent()==parent&&prior.opcode()==n.getOpcode(),"Foreign local owner/parent/operator");
        var groups=new ArrayList<Group>();var scope=new ArrayList<>(visible);int ordinal=0,index=0;String ownerPath=null;
        while(index<cs.size()&&declaration(cs.get(index))) {
            var d=cs.get(index);var dc=d.getChildren();
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
