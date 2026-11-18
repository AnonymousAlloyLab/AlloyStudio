package is.fivefivefive.CanDis.metric;

import java.util.*;
import java.util.function.Function;
import org.json.*;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.OrderedTreeEditDistance;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.metric.RepairView.Node;
import is.fivefivefive.CanDis.metric.RepairView.TemporalNode;

/** Source-bound REF/topology adapter, without certificates or projected matrices.
 * A prepared side is immutable; each comparison owns its selected payment state. */
public final class FastTemporalReferences {
    public record Shape(String label,List<NormalForm> branches,int incomingRole,List<Shape> children) {
        public Shape { branches=List.copyOf(branches);children=List.copyOf(children); }
    }
    private record Reference(int phase,Node node) { }
    private final TemporalNode root;
    private final List<Set<Node>> references;
    private final IdentityHashMap<EGraphNode,Reference> descriptors=new IdentityHashMap<>();
    private final Set<EGraphNode> legacyReferences=Collections.newSetFromMap(new IdentityHashMap<>());
    private record SourceReferences(Map<Long,Set<Long>> authenticated,Map<Long,List<EGraphNode>> legacy) { }

    public FastTemporalReferences(List<NormalForm> forms,Shape shape,
            Function<EGraphNode,List<EGraphNode>> alternatives,Function<EGraphNode,GraphType> type) {
        var indices=new IdentityHashMap<NormalForm,Integer>();
        for(int i=0;i<forms.size();i++)
            require(indices.put(forms.get(i).comparisonSource(),i)==null,"Repeated Fast source phase");
        root=tree(shape,null,indices);
        var phases=new ArrayList<Set<Node>>();
        for(int phase=0;phase<forms.size();phase++) {
            var form=forms.get(phase);
            var sourceLineages=sourceLineages(form.comparisonSource());
            var refs=Collections.newSetFromMap(new IdentityHashMap<Node,Boolean>());
            var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
            var pending=new ArrayDeque<EGraphNode>();
            if(form.getMatrixEGraph()!=null)pending.push(form.getMatrixEGraph());
            while(!pending.isEmpty()) {
                var node=pending.pop();if(!seen.add(node))continue;
                pending.addAll(node.getChildren());pending.addAll(alternatives.apply(node));
                if(node.getOpcode()!=EGraphNode.Opcode.REF)continue;
                if(!node.hasTemporalReferenceAuthority()) {
                    require(sourceLineages.legacy().getOrDefault(node.getSourceOccurrenceLineage(),List.of()).stream()
                            .anyMatch(original->Objects.equals(original.getSourceName(),node.getSourceName())
                                    &&Objects.equals(original.getExactAlloyType(),node.getExactAlloyType())),
                            "Source REF lost its authority marker");
                    legacyReferences.add(node);continue;
                }
                var origin=form.temporalReferenceOrigin(node);
                require(origin.owner()==form.comparisonSource(),"Fast REF owner changed");
                require(sourceLineages.authenticated().getOrDefault(origin.authorityId(),Set.of()).contains(node.getSourceOccurrenceLineage()),
                        "Fast REF lost original occurrence lineage");
                require(GraphType.BOOL.equals(type.apply(node)),"Fast REF must have exact Boolean type");
                var branches=origin.branches().stream().map(branch->index(indices,branch)).toList();
                String operator=RepairProjection.naturalTemporalLabel(origin.branches().get(0).getTemporalOp());
                var descriptor=new Node("REF",node.getSourceName(),"temporal-phase"+branches+"/"+operator,
                        null,-1,RepairView.ContainerKind.ONE,false,List.of())
                        .withObservation(GraphType.BOOL,branches,operator).withReferenceOrigin(origin);
                require(descriptors.put(node,new Reference(phase,descriptor))==null,"Fast REF reused across owners");
                refs.add(descriptor);
            }
            phases.add(Collections.unmodifiableSet(refs));
        }
        references=List.copyOf(phases);
    }

    private static SourceReferences sourceLineages(NormalForm source) {
        var result=new HashMap<Long,Set<Long>>();
        var legacy=new HashMap<Long,List<EGraphNode>>();
        var seen=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<EGraphNode>();if(source.getMatrixEGraph()!=null)pending.push(source.getMatrixEGraph());
        while(!pending.isEmpty()) {
            var node=pending.pop();if(!seen.add(node))continue;pending.addAll(node.getChildren());
            if(node.getOpcode()==EGraphNode.Opcode.REF) {
                if(node.hasTemporalReferenceAuthority()) {
                    var origin=source.temporalReferenceOrigin(node);
                    result.computeIfAbsent(origin.authorityId(),unused->new HashSet<>()).add(node.getSourceOccurrenceLineage());
                }else legacy.computeIfAbsent(node.getSourceOccurrenceLineage(),unused->new ArrayList<>()).add(node);
            }
        }
        return new SourceReferences(result,legacy);
    }

    private static int index(IdentityHashMap<NormalForm,Integer> indices,NormalForm source) {
        Integer phase=indices.get(source);
        require(phase!=null,"Authenticated REF branch absent from Fast phases");
        return phase;
    }
    private static TemporalNode tree(Shape shape,NormalForm owner,IdentityHashMap<NormalForm,Integer> indices) {
        var branches=shape.branches().stream().map(NormalForm::comparisonSource).toList();
        var children=new ArrayList<TemporalNode>();
        for(var child:shape.children()) {
            require(child.incomingRole()>=0&&child.incomingRole()<branches.size(),"Fast temporal incoming role");
            children.add(tree(child,branches.get(child.incomingRole()),indices));
        }
        return new TemporalNode(shape.label(),children,owner==null?-1:index(indices,owner),
                branches.stream().map(branch->index(indices,branch)).toList(),owner,branches);
    }
    public Payment select(FastTemporalReferences target,OrderedTreeEditDistance.Alignment alignment) {
        return new Payment(this,target,alignment);
    }
    public static final class Payment {
        private final FastTemporalReferences source,target;
        private final SelectedTemporalPayments selected;
        private Payment(FastTemporalReferences source,FastTemporalReferences target,
                OrderedTreeEditDistance.Alignment alignment) {
            this.source=source;this.target=target;
            selected=SelectedTemporalPayments.from(source.root,target.root,source.references,target.references,alignment);
            // Resolve every authenticated occurrence before either exact or paid discounts.
            for(var reference:source.descriptors.values())selected.referenceEvidence(reference.phase(),reference.node());
            for(var reference:target.descriptors.values())selected.referenceEvidence(reference.phase(),reference.node());
        }
        /** Null is reserved for two source-unbound synthetic fixture references. */
        public Integer updateCost(EGraphNode left,EGraphNode right) {
            var a=source.descriptors.get(left);var b=target.descriptors.get(right);
            require(a!=null||source.legacyReferences.contains(left),"Unresolved source REF object");
            require(b!=null||target.legacyReferences.contains(right),"Unresolved target REF object");
            if(a==null||b==null)return a==null&&b==null?null:1;
            if(a.phase()!=b.phase())return 1;
            return a.node().observationPhases().equals(b.node().observationPhases())
                    &&a.node().observationTemporalOperator().equals(b.node().observationTemporalOperator())
                    ||selected.pays(a.phase(),a.node(),b.node())?0:1;
        }
        public JSONObject json() { return selected.json(); }
        public void requireReceipt(JSONObject temporal,JSONObject receipt) { selected.requireReceipt(temporal,receipt); }
    }
    private static void require(boolean condition,String message) {
        if(!condition)throw new IllegalArgumentException(message);
    }
}
