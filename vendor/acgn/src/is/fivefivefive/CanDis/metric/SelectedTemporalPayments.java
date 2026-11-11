package is.fivefivefive.CanDis.metric;

import java.util.*;
import org.json.*;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;

/** Immutable links from original projected phase edges to one selected temporal path.
 * Matrix slots and the temporal skeleton are separate metric components. A slot
 * may reconstruct its typed target edge from the entire selected skeleton edit;
 * that need not be the same occurrence pair chosen by temporal tree matching.
 * No optimization or source-construction authority is supplied here. A fallback
 * demand lazily executes and retains the selected typed derivation once. */
final class SelectedTemporalPayments {
    private record Edge(int owner, List<Integer> branches) { }
    private record LocalEdge(int owner, int slot, int arity) { }
    private static final class Occurrence {
        final TemporalNode node;
        final Occurrence parent;
        int postorder, leftmost;
        Occurrence(TemporalNode node,Occurrence parent) {this.node=node;this.parent=parent;}
        int parentPostorder() {return parent==null?0:parent.postorder;}
        int ownerRole() {
            if(parent==null)return -1;
            for(int i=0;i<parent.node.sourceBranches().size();i++)
                if(parent.node.sourceBranches().get(i)==node.sourceOwner())return i;
            return -1;
        }
        int sourceSlot() {
            if(node.sourceOwner()==null||node.sourceBranches().isEmpty())return -1;
            var children=node.sourceOwner().getTemporalChildren();
            for(int i=0;i<children.size();i++)if(children.get(i)==node.sourceBranches().get(0))return i;
            return -1;
        }
        LocalEdge localEdge() {return new LocalEdge(node.ownerPhase(),sourceSlot(),node.sourceBranches().size());}
    }
    static final SelectedTemporalPayments NONE = new SelectedTemporalPayments();
    private final List<Occurrence> left,right;
    private final Map<Edge,List<Occurrence>> leftEdges,rightEdges;
    private final Map<Integer,Integer> selected;
    private final Map<Integer,Integer> inverse;
    private final List<Set<Node>> leftReferences,rightReferences;
    private final Map<LocalEdge,List<Occurrence>> rightLocalEdges;
    private final OrderedTreeEditDistance.Alignment alignment;
    private TemporalNode sourceRoot,targetRoot;
    private JSONObject typedDerivation;
    private String derivationFailure;
    private boolean derivationAttempted;
    private final IdentityHashMap<Object,Integer> captureObjects = new IdentityHashMap<>();

    private int register(Object object) {
        return captureObjects.computeIfAbsent(Objects.requireNonNull(object), unused -> captureObjects.size() + 1);
    }
    private int captured(Object object) {
        Integer id = captureObjects.get(object);
        require(id != null, "Unregistered selected capture object");
        return id;
    }

    private SelectedTemporalPayments() {
        left=right=List.of();leftEdges=rightEdges=Map.of();selected=inverse=Map.of();
        leftReferences=rightReferences=List.of();
        rightLocalEdges=Map.of();
        alignment=new OrderedTreeEditDistance.Alignment(0,List.of());
    }
    private SelectedTemporalPayments(RepairView a,RepairView b,OrderedTreeEditDistance.Alignment alignment) {
        this.alignment=Objects.requireNonNull(alignment);
        register(this);
        sourceRoot=a.temporalRoot();targetRoot=b.temporalRoot();
        leftReferences=references(a);rightReferences=references(b);
        left=index(a.temporalRoot());right=index(b.temporalRoot());
        registerReferences(a);registerReferences(b);
        for(var occurrences:List.of(left,right))for(var occurrence:occurrences) {
            register(occurrence.node);
            if(occurrence.node.sourceOwner()!=null)register(occurrence.node.sourceOwner());
            for(var branch:occurrence.node.sourceBranches())register(branch);
        }
        leftEdges=edges(left,a.phases().size());rightEdges=edges(right,b.phases().size());
        var local=new HashMap<LocalEdge,List<Occurrence>>();
        for(var o:right)if(o.parent!=null&&o.sourceSlot()>=0)
            local.computeIfAbsent(o.localEdge(),unused->new ArrayList<>()).add(o);
        local.replaceAll((key,value)->List.copyOf(value));rightLocalEdges=Map.copyOf(local);
        var map=new LinkedHashMap<Integer,Integer>();
        var xs=new ArrayList<Integer>();var ys=new ArrayList<Integer>();int updates=0;
        int priorLeft=0,priorRight=0;
        for(var match:alignment.matches()) {
            int x=match.leftPostorder(),y=match.rightPostorder();
            require(x>priorLeft&&y>priorRight&&x<=left.size()&&y<=right.size(),"Selected temporal map order/bounds");
            var l=left.get(x-1);var r=right.get(y-1);
            // Ordered matching descendants occupy the same contiguous suffix.
            require(lowerBound(xs,l.leftmost)==lowerBound(ys,r.leftmost),"Selected temporal map ancestry");
            map.put(x,y);xs.add(x);ys.add(y);priorLeft=x;priorRight=y;
            if(!l.node.label().equals(r.node.label()))updates=Math.addExact(updates,1);
        }
        int cost=Math.addExact(Math.subtractExact(Math.addExact(left.size(),right.size()),Math.multiplyExact(2,map.size())),updates);
        require(cost==alignment.cost(),"Selected temporal map paid cost");selected=Map.copyOf(map);
        var reverse=new HashMap<Integer,Integer>();map.forEach((x,y)->reverse.put(y,x));inverse=Map.copyOf(reverse);
    }
    static SelectedTemporalPayments from(RepairView left,RepairView right,OrderedTreeEditDistance.Alignment alignment) {
        return new SelectedTemporalPayments(left,right,alignment);
    }
    private void registerReferences(RepairView view) {
        var seen=Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
        for(var phase:view.phases()) {
            var pending=new ArrayDeque<Node>();if(phase.matrix()!=null)pending.push(phase.matrix());
            while(!pending.isEmpty()) {
                var node=pending.pop();if(!seen.add(node))continue;
                if(node.isTemporalReference()) {
                    register(node);
                    var origin=node.referenceOrigin();
                    if(origin!=null) {
                        register(origin.reference());register(origin.owner());
                        for(var branch:origin.branches())register(branch);
                    }
                }
                pending.addAll(node.children());pending.addAll(node.certifiedAlternatives());
            }
        }
    }

    /** Captures the identity join, not a second admission or cost algorithm. */
    Object referenceEvidence(int phase,Node node) {
        if(!node.isTemporalReference())return JSONObject.NULL;
        var origin=node.referenceOrigin();
        if(origin==null)return new JSONObject().put("status","UNBOUND_SOURCE_OBJECT");
        var source=referenceEndpoints(phase,node,leftReferences,leftEdges);
        var target=referenceEndpoints(phase,node,rightReferences,rightEdges);
        require(!source.isEmpty()||!target.isEmpty(),"Foreign matrix REF object in selected capture");
        return new JSONObject().put("status","BOUND_ACTUAL_OBJECTS")
            .put("matrixObject",captured(node)).put("sourceReferenceObject",captured(origin.reference()))
            .put("paymentObject",captured(this)).put("authorityId",origin.authorityId())
            .put("parserOccurrence",origin.parserOccurrenceId()).put("sourceOccurrence",origin.sourceOccurrenceLineage())
            .put("sourceReferenceLineage",origin.reference().getSourceOccurrenceLineage())
            .put("ownerObject",captured(origin.owner())).put("ownerSourcePath",origin.owner().getPhasePath())
            .put("branchObjects",new JSONArray(origin.branches().stream().map(this::captured).toList()))
            .put("sourceEndpoints",source).put("targetEndpoints",target);
    }

    private JSONArray referenceEndpoints(int phase,Node node,List<Set<Node>> references,
            Map<Edge,List<Occurrence>> edges) {
        var out=new JSONArray();
        if(phase<0||phase>=references.size()||!references.get(phase).contains(node))return out;
        var origin=node.referenceOrigin();
        var occurrences=edges.get(new Edge(phase,node.observationPhases()));
        require(occurrences!=null&&!occurrences.isEmpty(),"Selected REF lacks source temporal edge");
        for(var occurrence:occurrences) {
            var temporal=occurrence.node;
            require(origin.owner()==temporal.sourceOwner(),"Selected REF source owner object changed");
            require(origin.branches().size()==temporal.sourceBranches().size(),"Selected REF branch arity changed");
            for(int i=0;i<origin.branches().size();i++)
                require(origin.branches().get(i)==temporal.sourceBranches().get(i),"Selected REF branch object/order changed");
            require(origin.sourceSlot()==occurrence.sourceSlot(),"Selected REF original slot changed");
            require(temporal.label().equals(node.observationTemporalOperator()),"Selected REF operator changed");
            out.put(endpoints(List.of(occurrence)).getJSONObject(0));
        }
        return out;
    }

    private static List<Set<Node>> references(RepairView view) {
        var result=new ArrayList<Set<Node>>();
        for(var phase:view.phases()) {
            var seen=Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
            var refs=Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
            var pending=new ArrayDeque<Node>();if(phase.matrix()!=null)pending.push(phase.matrix());
            while(!pending.isEmpty()) {
                var n=pending.pop();if(!seen.add(n))continue;
                if(n.isTemporalReference())refs.add(n);
                pending.addAll(n.children());pending.addAll(n.certifiedAlternatives());
            }
            result.add(Collections.unmodifiableSet(refs));
        }
        return List.copyOf(result);
    }
    private static int lowerBound(List<Integer> values,int value) {
        int lo=0,hi=values.size();while(lo<hi){int mid=(lo+hi)>>>1;if(values.get(mid)<value)lo=mid+1;else hi=mid;}return lo;
    }
    private static List<Occurrence> index(TemporalNode root) {
        var out=new ArrayList<Occurrence>();
        record Frame(Occurrence occurrence,boolean exit) { }
        var stack=new ArrayDeque<Frame>();stack.push(new Frame(new Occurrence(root,null),false));
        while(!stack.isEmpty()) {
            var f=stack.pop();var o=f.occurrence;
            if(f.exit){o.postorder=out.size()+1;if(o.node.children().isEmpty())o.leftmost=o.postorder;out.add(o);continue;}
            o.leftmost=out.size()+1;stack.push(new Frame(o,true));
            var children=o.node.children();for(int i=children.size()-1;i>=0;i--)stack.push(new Frame(new Occurrence(children.get(i),o),false));
        }
        return List.copyOf(out);
    }
    private static Map<Edge,List<Occurrence>> edges(List<Occurrence> nodes,int phaseCount) {
        var result=new HashMap<Edge,List<Occurrence>>();
        var sources=new HashMap<Integer,NormalForm>();
        var locators=new IdentityHashMap<NormalForm,Integer>();
        for(var o:nodes) {
            if(o.node.branchPhases().isEmpty())continue; // Unannotated test-only trees have no payment authority.
            for(int i=0;i<o.node.branchPhases().size();i++) {
                int phase=o.node.branchPhases().get(i);var source=o.node.sourceBranches().get(i);
                require(phase>=0&&phase<phaseCount,"Temporal phase endpoint outside view");
                var previous=sources.putIfAbsent(phase,source);var ordinal=locators.putIfAbsent(source,phase);
                require((previous==null||previous==source)&&(ordinal==null||ordinal==phase),"Temporal locator/source identity collision");
            }
            if(o.parent==null){require(o.node.ownerPhase()==-1,"Temporal root owner");continue;}
            require(o.node.ownerPhase()>=0&&o.node.ownerPhase()<phaseCount&&o.ownerRole()>=0,"Temporal edge parent/branch ownership");
            require(o.parent.node.branchPhases().get(o.ownerRole())==o.node.ownerPhase(),"Temporal owner locator lost original source role");
            result.computeIfAbsent(new Edge(o.node.ownerPhase(),o.node.branchPhases()),ignored->new ArrayList<>()).add(o);
        }
        result.replaceAll((key,value)->List.copyOf(value));return Map.copyOf(result);
    }
    boolean pays(int phase,Node a,Node b) {
        if(phase<0||phase>=leftReferences.size()||phase>=rightReferences.size()
                ||!leftReferences.get(phase).contains(a)||!rightReferences.get(phase).contains(b)
                ||!a.isTemporalReference()||!b.isTemporalReference()
                ||!GraphType.BOOL.equals(a.observationType())||!GraphType.BOOL.equals(b.observationType()))return false;
        var as=leftEdges.get(new Edge(phase,a.observationPhases()));
        var bs=rightEdges.get(new Edge(phase,b.observationPhases()));
        if(as==null||bs==null||as.size()!=bs.size())return false;
        var consumed=new HashSet<Occurrence>();
        for(var x:as) {
            if(!x.node.label().equals(a.observationTemporalOperator()))return false;
            Occurrence chosen=null;
            for(var y:bs)if(!consumed.contains(y)&&y.node.label().equals(b.observationTemporalOperator())
                    && transport(x,y)!=null) {
                if(chosen!=null)return false; // Ambiguous payment is not arbitrary occurrence selection.
                chosen=y;
            }
            if(chosen==null)return false;consumed.add(chosen);
        }
        return true;
    }
    private String transport(Occurrence x,Occurrence y) {
        if(x.sourceSlot()<0||x.sourceSlot()!=y.sourceSlot()
                ||x.node.sourceBranches().size()!=y.node.sourceBranches().size())return null;
        String matched=matchedTransport(x,y);
        if(matched!=null)return matched;
        // An unchanged skeleton cannot redirect a matrix edge merely because
        // two original source lists used the same local slot. ACI permutations
        // must retain the selected occurrence, not substitute another sibling.
        return alignment.cost()>0 && x.localEdge().equals(y.localEdge()) && reconstructsTypedEdges()
                ? "selected-skeleton-reconstruction" : null;
    }
    private boolean reconstructsTypedEdges() {
        if(!derivationAttempted) {
            derivationAttempted=true;
            try {
                var program=TemporalReplay.capture(sourceRoot,targetRoot,alignment.cost(),
                        TemporalNode::label,TemporalNode::children,alignment);
                typedDerivation=TypedTemporalDerivation.capture(sourceRoot,targetRoot,program);
            } catch(IllegalArgumentException failure) {
                // Reject this concrete unestablished discount, not the predicate.
                derivationFailure=failure.getMessage();
            }
        }
        return typedDerivation!=null;
    }
    private String matchedTransport(Occurrence x,Occurrence y) {
        if(!Objects.equals(selected.get(x.parentPostorder()),y.parentPostorder())
                ||x.ownerRole()!=y.ownerRole())return null;
        if(Objects.equals(selected.get(x.postorder),y.postorder))return "selected-match";
        Integer source=inverse.get(y.postorder);
        if(source!=null&&deletedPath(x,left.get(source-1),selected))return "selected-delete-promotion";
        Integer target=selected.get(x.postorder);
        if(target!=null&&deletedPath(y,right.get(target-1),inverse))return "selected-insert-wrapping";
        return null;
    }
    // A concrete selected deletion promotes all its ordered children. Walk only
    // original typed parent links, and require every intervening node deleted.
    private static boolean deletedPath(Occurrence outer,Occurrence retained,Map<Integer,Integer> matches) {
        if(outer==retained||matches.containsKey(outer.postorder))return false;
        for(var p=retained.parent;p!=null;p=p.parent) {
            if(matches.containsKey(p.postorder))return false;
            if(p==outer)return true;
        }
        return false;
    }
    private static JSONObject phase(NormalForm source,int ordinal) {
        return new JSONObject().put("ordinal",ordinal).put("sourcePath",source.getPhasePath())
                .put("kind",source.getTemporalOp().name());
    }
    private JSONArray endpoints(List<Occurrence> occurrences) {
        var out=new JSONArray();for(var o:occurrences) {
            var phases=new JSONArray();for(int i=0;i<o.node.sourceBranches().size();i++)
                phases.put(phase(o.node.sourceBranches().get(i),o.node.branchPhases().get(i)));
            out.put(new JSONObject()
                .put("temporalObject",captured(o.node))
                .put("ownerObject",o.node.sourceOwner()==null?JSONObject.NULL:captured(o.node.sourceOwner()))
                .put("branchObjects",new JSONArray(o.node.sourceBranches().stream().map(this::captured).toList()))
                .put("postorder",o.postorder).put("ownerPostorder",o.parentPostorder()).put("ownerRole",o.ownerRole())
                .put("ownerPhase",o.node.ownerPhase()).put("branches",new JSONArray(o.node.branchPhases())).put("operator",o.node.label())
                .put("sourceLocalSlot",o.sourceSlot())
                .put("typedOwner",o.node.sourceOwner()==null?JSONObject.NULL:phase(o.node.sourceOwner(),o.node.ownerPhase()))
                .put("typedBranches",phases));
        }return out;
    }
    private JSONArray matches() {
        var out=new JSONArray();for(var m:alignment.matches())out.put(new JSONArray().put(m.leftPostorder()).put(m.rightPostorder()));return out;
    }
    private static JSONArray path(Occurrence outer,Occurrence retained) {
        var out=new JSONArray();for(var p=retained.parent;p!=null;p=p.parent) {
            out.put(p.postorder);if(p==outer)break;
        }return out;
    }
    private JSONArray transports() {
        var out=new JSONArray();
        // Tree postorder fixes receipt order independently of hash-map iteration.
        for(var x:left)if(x.parent!=null) {
            var candidates=rightLocalEdges.get(x.localEdge());
            if(candidates==null)continue;
            for(var y:candidates) {
                String kind=transport(x,y);if(kind==null)continue;
                var item=new JSONObject().put("sourceOccurrence",x.postorder).put("targetOccurrence",y.postorder)
                        .put("kind",kind).put("sourceOwner",x.parentPostorder()).put("targetOwner",y.parentPostorder())
                        .put("ownerRole",x.ownerRole()).put("targetOwnerRole",y.ownerRole())
                        .put("sourceLocalSlot",x.sourceSlot()).put("targetLocalSlot",y.sourceSlot())
                        .put("branchArity",x.node.sourceBranches().size());
                if(kind.equals("selected-delete-promotion"))item.put("deletedSourceOccurrences",path(x,left.get(inverse.get(y.postorder)-1)));
                if(kind.equals("selected-insert-wrapping"))item.put("insertedTargetOccurrences",path(y,right.get(selected.get(x.postorder)-1)));
                out.put(item);
            }
        }return out;
    }
    JSONObject json() {
        var links=transports();
        return new JSONObject().put("schema","selected-temporal-payment-v3-skeleton-reconstruction").put("source",endpoints(left))
            .put("captureObject",this==NONE?JSONObject.NULL:captured(this))
            .put("sourceMatrixReferences",referenceObjects(leftReferences))
            .put("targetMatrixReferences",referenceObjects(rightReferences))
            .put("target",endpoints(right)).put("selectedCorrespondence",matches()).put("temporalCost",alignment.cost())
            .put("reconstructionPremise","Complete selected temporal execution and original typed endpoint resolution; matrix slot equality is not temporal operator equality")
            .put("referenceTransports",links)
            .put("typedDerivation",typedDerivation==null?JSONObject.NULL:new JSONObject(typedDerivation.toString()))
            .put("typedDerivationFailure",derivationFailure==null?JSONObject.NULL:derivationFailure);}
    private JSONArray referenceObjects(List<Set<Node>> references) {
        var out=new JSONArray();
        for(var phase:references)out.put(new JSONArray(phase.stream().map(this::captured).sorted().toList()));
        return out;
    }
    void requireReceipt(JSONObject temporal,JSONObject links) {
        require(json().similar(links),"Selected temporal payment source/owner/correspondence changed");
        require(matches().similar(temporal.getJSONArray("selectedCorrespondence"))
                &&temporal.getInt("cost")==alignment.cost(),"Matrix discount lost actual selected temporal program");
        if(typedDerivation!=null)TypedTemporalDerivation.verify(sourceRoot,targetRoot,temporal,links.getJSONObject("typedDerivation"));
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
}
