package is.fivefivefive.CanDis.metric;

import java.util.*;
import is.fivefivefive.CanDis.core.*;
import is.fivefivefive.CanDis.metric.RepairView.*;

/** Authority for one captured program only; never selects another path or map. */
final class SelectedDeclarationCompletion {
    private final RepairView left,right;
    private final Set<Long> reads=new HashSet<>();
    private final List<Binding> source,target;
    private final Set<Node> sourceNodes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Node> targetNodes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<EGraphNode> originalSourceNodes=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<EGraphNode> originalTargetNodes=Collections.newSetFromMap(new IdentityHashMap<>());

    SelectedDeclarationCompletion(RepairView left,RepairView right,MatrixDecision<Node> selected,int[] mapping) {
        this.left=left;this.right=right;
        DeclarationReplay.require(left.phases().size()==1&&right.phases().size()==1,"Fixed completion phase coverage");
        source=left.phases().get(0).bindings();target=right.phases().get(0).bindings();
        DeclarationReplay.require(mapping.length==source.size(),"Fixed completion map coverage");
        for(int i=0;i<source.size();i++)owned(left,i,source.get(i));
        for(int i=0;i<target.size();i++)owned(right,i,target.get(i));
        collect(left.phases().get(0).matrix(),sourceNodes);collect(right.phases().get(0).matrix(),targetNodes);
        originalNodes(left.originalPhaseSource(0).getMatrixEGraph(),originalSourceNodes);
        originalNodes(right.originalPhaseSource(0).getMatrixEGraph(),originalTargetNodes);
        DeclarationReplay.require(selected.source()==left.phases().get(0).matrix()
                &&selected.target()==right.phases().get(0).matrix(),"Foreign selected program roots");
        footprint(selected,mapping,new int[]{4096},0);
    }

    private static void collect(Node n,Set<Node> seen) {
        if(n==null||!seen.add(n))return;
        DeclarationReplay.require(seen.size()<=4096,"Fixed completion node budget");
        for(var child:n.children())collect(child,seen);for(var choice:n.certifiedAlternatives())collect(choice,seen);
    }

    private static void owned(RepairView view,int vector,Binding binding) {
        var form=view.originalPhaseSource(0);var qs=form.getMatrixQuantiVars();var q=binding.originalBinding();var d=binding.declaration();
        DeclarationReplay.require(form.isFrozenForCertification()&&form.getTemporalOp()==NormalForm.TemporalOp.NONE
                &&form.getTemporalChildren().isEmpty()&&binding==view.phases().get(0).bindings().get(vector)
                &&binding.role()==BindingRole.MATRIX&&binding.ownerPhase()==0&&binding.ownerContext().isEmpty()
                &&binding.ordinal()<qs.size()&&qs.get(binding.ordinal())==q&&q.isFrozenForCertification()
                &&binding.bindingPath().equals(q.getBindingPath())&&q.getExactAlloyType()!=null
                &&q.getExactAlloyType().hasParserAuthenticatedAncestry()
                &&d.quantifier().equals(q.getQuantifier().name())&&d.cardinality().equals(q.getCardinality().name())
                &&d.disjointnessClass()==q.getDisjointnessClass()&&d.observationType()!=null&&d.observationDomain()!=null
                &&d.dependencies().isEmpty(),"Foreign original binding/source/type/owner association");
    }

    private static void originalNodes(EGraphNode root,Set<EGraphNode> actual) {
        var pending=new ArrayDeque<EGraphNode>();pending.add(root);
        while(!pending.isEmpty()){var raw=pending.remove();if(!actual.add(raw))continue;DeclarationReplay.require(actual.size()<=4096,"Original variable census budget");pending.addAll(raw.getChildren());}
    }

    private void variable(RepairView view,Node n) {
        if(!n.isVariable()||n.bindingIndex()<0)return;
        int index=n.bindingIndex();var bindings=view.phases().get(0).bindings();
        DeclarationReplay.require(index<bindings.size(),"Foreign selected variable index");
        var binding=bindings.get(index);owned(view,index,binding);var q=binding.originalBinding();var original=n.originalVariableSource();
        DeclarationReplay.require(original!=null&&original.getOpcode()==EGraphNode.Opcode.VARIABLE
                &&Objects.equals(original.getAlphaName(),q.getName())&&original.getExactAlloyType()!=null
                &&q.getExactAlloyType().sameOccurrenceEvidenceAs(original.getExactAlloyType()),"Foreign selected variable source object");
        var actual=view==left?originalSourceNodes:originalTargetNodes;
        DeclarationReplay.require(actual.contains(original),"Foreign same-metadata variable source object");
    }

    private void footprint(MatrixDecision<Node> op,int[] mapping,int[] budget,int depth) {
        DeclarationReplay.require(op!=null&&--budget[0]>=0&&depth<=64,"Fixed selected program budget");
        DeclarationReplay.require(op.source()==null||sourceNodes.contains(op.source()),"Foreign selected source node object");
        DeclarationReplay.require(op.target()==null||targetNodes.contains(op.target()),"Foreign selected target node object");
        if(op.kind().equals("select")){footprint(op.selected(),mapping,budget,depth+1);return;}
        if(op.kind().equals("node")){
            var a=op.source();var b=op.target();variable(left,a);variable(right,b);
            if(a.operator().equals(b.operator())&&a.isVariable()&&b.isVariable()&&a.bindingIndex()>=0&&b.bindingIndex()>=0){
                int i=a.bindingIndex(),j=b.bindingIndex();reads.add(pair(i,j));
                DeclarationReplay.require(op.headCost()==(mapping[i]==j?0:1),"Original variable comparison charge");
            }
            for(var edge:op.children())footprint(edge.decision(),mapping,budget,depth+1);
        }else DeclarationReplay.require(op.kind().equals("delete")||op.kind().equals("insert"),"Foreign selected action kind");
    }

    boolean allows(int sourceVector,Binding sourceBinding,int targetVector,Binding targetBinding) {
        DeclarationReplay.require(sourceVector>=0&&sourceVector<source.size()&&targetVector>=0&&targetVector<target.size()
                &&source.get(sourceVector)==sourceBinding&&target.get(targetVector)==targetBinding,"Foreign completion binding object");
        owned(left,sourceVector,sourceBinding);owned(right,targetVector,targetBinding);
        return !reads.contains(pair(sourceVector,targetVector));
    }

    private static long pair(int a,int b){return ((long)a<<32)|(b&0xffffffffL);}
}
