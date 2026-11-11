package is.fivefivefive.CanDis.theory;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import is.fivefivefive.CanDis.core.QuantiVar;

/** Original JOIN premise discharged beneath a checked Boolean tautology.
 * This source/repair correspondence is an in-process check, not wire authority
 * or an additional trusted equality root in the typed construction ledger. */
public final class GuardedJoinErasureEvidence {
    private static final int MAX_NODES=4096, MAX_DEPTH=64, MAX_LEAVES=128;
    private final NormalForm owner;
    private final int phase;
    private final GuardedJoinCertificate premise;
    private final String ancestorPath;
    private final EGraphNode originalAncestor, repairedAncestor;

    private GuardedJoinErasureEvidence(NormalForm owner,int phase,GuardedJoinCertificate premise,
            String path,EGraphNode original,EGraphNode repaired) {
        this.owner=owner;this.phase=phase;this.premise=premise;
        ancestorPath=path;originalAncestor=original;repairedAncestor=repaired;
    }

    static GuardedJoinErasureEvidence establish(List<NormalForm> forms,int phase,GuardedJoinCertificate premise) {
        var form=forms.get(phase);requireScope(form);
        String path=premise.sourceOccurrencePath();
        EGraphNode join=at(form,phase,path);premise.requireOriginalMatches(join,path);
        while(path.lastIndexOf("/child/")>=0) {
            path=path.substring(0,path.lastIndexOf("/child/"));
            var original=at(form,phase,path);
            if(original.getOpcode()!=EGraphNode.Opcode.OR)continue;
            var repaired=findLineage(form.getMatrixEGraph(),original.getSourceOccurrenceLineage());
            if(repaired==null || !isTrue(repaired))continue;
            if(!tautology(form,original))continue;
            var evidence=new GuardedJoinErasureEvidence(form,phase,premise,path,original,repaired);
            evidence.requireMatches(forms);return evidence;
        }
        throw new IllegalArgumentException("Absent guarded JOIN has no checked Boolean erasure ancestor");
    }

    public void requireMatches(List<NormalForm> forms) {
        require(phase>=0 && phase<forms.size() && forms.get(phase)==owner,"Erasure owner phase changed");
        requireScope(owner);CertificateVerifier.verify(premise);
        var original=at(owner,phase,premise.sourceOccurrencePath());
        original.requirePristineCertificationSource();
        premise.requireOriginalMatches(original,premise.sourceOccurrencePath());
        require(premise.sourceOccurrencePath().startsWith(ancestorPath+"/child/"),"Erasure is not a source ancestor");
        require(at(owner,phase,ancestorPath)==originalAncestor,"Erasure original source changed");
        require(findLineage(owner.getMatrixEGraph(),originalAncestor.getSourceOccurrenceLineage())==repairedAncestor,
                "Erasure repaired occurrence changed");
        require(isTrue(repairedAncestor) && originalAncestor.getSemanticProfile().equals(repairedAncestor.getSemanticProfile())
                && originalAncestor.getExactAlloyType().equals(repairedAncestor.getExactAlloyType()),"Erasure result type/profile changed");
        require(tautology(owner,originalAncestor),"Erasure lacks original typed complement premises");
    }
    public GuardedJoinCertificate premise(){return premise;}
    public String ancestorPath(){return ancestorPath;}
    public long ancestorLineage(){return originalAncestor.getSourceOccurrenceLineage();}
    public int ownerPhase(){return phase;}

    private static void requireScope(NormalForm form) {
        require(form.isFrozenForCertification() && form.getCertificationMatrixEGraph()!=null
                && form.getMatrixEGraph()!=null && form.getTemporalOp()==NormalForm.TemporalOp.NONE
                && form.getTemporalChildren().isEmpty() && form.getLocalQuantiVars().isEmpty()
                && form.getParams().isEmpty() && form.getInheritedQuantiVars().isEmpty()
                && form.getPhaseLocalBindingImports().isEmpty(),"Outside closed Boolean erasure scope");
    }
    private static boolean bool(EGraphNode n) {
        return n.getMetatype()==EGraphNode.Metatype.BOOLEAN && n.getExactAlloyType()!=null
                && n.getExactAlloyType().kind()==ExactAlloyType.Kind.BOOL
                && n.getSemanticProfile().isAdmissibleAlloyProfile();
    }
    private static boolean isTrue(EGraphNode n) {
        return bool(n) && n.getOpcode()==EGraphNode.Opcode.CONSTANT && n.getChildren().isEmpty()
                && "true".equals(n.getSourceName()) && "Bool".equals(n.getSourceType());
    }
    private static EGraphNode at(NormalForm form,int phase,String path) {
        String prefix="phase/"+phase+"/matrix";
        require(path.equals(prefix)||path.startsWith(prefix+"/child/"),"Foreign erasure source phase/path");
        var node=form.getCertificationMatrixEGraph();var tail=path.substring(prefix.length()).split("/");
        require(tail.length<=2*MAX_DEPTH+1,"Erasure source path budget");
        for(int i=1;i<tail.length;i+=2) {
            require(i+1<tail.length && tail[i].equals("child"),"Malformed erasure source path");
            int index=Integer.parseInt(tail[i+1]);
            require(index>=0 && index<node.getChildren().size(),"Erasure source child missing");
            node=node.getChildren().get(index);
        }
        return node;
    }
    private static EGraphNode findLineage(EGraphNode root,long lineage) {
        var visited=Collections.newSetFromMap(new IdentityHashMap<EGraphNode,Boolean>());
        var pending=new ArrayDeque<EGraphNode>();pending.add(root);EGraphNode found=null;
        while(!pending.isEmpty()) {
            var n=pending.removeFirst();if(!visited.add(n))continue;
            require(visited.size()<=MAX_NODES,"Erasure repaired node budget");
            if(n.getSourceOccurrenceLineage()==lineage) {
                require(found==null,"Ambiguous erased Boolean ancestor lineage");found=n;
            }
            pending.addAll(n.getChildren());
        }
        return found;
    }
    private static void disjuncts(EGraphNode n,List<EGraphNode> result,int[] visits,int depth,SemanticProfile profile) {
        require(++visits[0]<=MAX_NODES && depth<=MAX_DEPTH,"Erasure Boolean traversal budget");
        n.requirePristineCertificationSource();require(bool(n)&&profile.equals(n.getSemanticProfile()),"Untyped Boolean source ancestor");
        if(n.getOpcode()==EGraphNode.Opcode.OR) {
            require(!n.getChildren().isEmpty(),"Empty OR source");
            for(var child:n.getChildren())disjuncts(child,result,visits,depth+1,profile);
        } else {
            require(result.size()<MAX_LEAVES,"Erasure disjunct budget");result.add(n);
        }
    }
    private static boolean tautology(NormalForm form,EGraphNode root) {
        if(root.getOpcode()!=EGraphNode.Opcode.OR)return false;
        var leaves=new ArrayList<EGraphNode>();disjuncts(root,leaves,new int[]{0},0,root.getSemanticProfile());
        var visible=new HashMap<String,QuantiVar>();
        for(var q:form.getMatrixQuantiVars()) {
            require(q.isFrozenForCertification() && !q.getBindingPath().isEmpty()
                    && visible.put(q.getName(),q)==null,"Unadmitted erasure binder owner");
        }
        for(var a:leaves)if(a.getOpcode()==EGraphNode.Opcode.IN)
            for(var b:leaves)if(b.getOpcode()==EGraphNode.Opcode.NOT_IN && sameMembership(a,b,visible))return true;
        return false;
    }
    private static boolean sameMembership(EGraphNode a,EGraphNode b,Map<String,QuantiVar> visible) {
        if(a.getChildren().size()!=2 || b.getChildren().size()!=2)return false;
        var x=a.getChildren().get(0);var y=b.getChildren().get(0);
        var s=a.getChildren().get(1);var t=b.getChildren().get(1);
        var q=visible.get(x.getAlphaName());
        if(q==null || q!=visible.get(y.getAlphaName()) || q.getExactAlloyType()==null
                || !q.getExactAlloyType().hasParserAuthenticatedAncestry())return false;
        for(var v:List.of(x,y)) {
            v.requirePristineCertificationSource();
            if(v.getOpcode()!=EGraphNode.Opcode.VARIABLE || !v.getChildren().isEmpty()
                    || !q.getOriginalNames().contains(v.getSourceName()) || !q.getOriginalNames().contains(v.getSemanticIdentity())
                    || !q.getExactAlloyType().sameOccurrenceEvidenceAs(v.getExactAlloyType())
                    || !a.getSemanticProfile().equals(v.getSemanticProfile()))return false;
        }
        for(var v:List.of(s,t)) {
            v.requirePristineCertificationSource();
            if(v.getOpcode()!=EGraphNode.Opcode.GLOBALBINDING || !v.getChildren().isEmpty()
                    || v.getSemanticIdentity()==null || !v.getSemanticIdentity().startsWith("alloy/signature/")
                    || v.getExactAlloyType()==null || !v.getExactAlloyType().hasParserAuthenticatedAncestry()
                    || !q.getExactAlloyType().sameOccurrenceEvidenceAs(v.getExactAlloyType())
                    || !a.getSemanticProfile().equals(v.getSemanticProfile()))return false;
        }
        return s.getSemanticIdentity().equals(t.getSemanticIdentity()) && Objects.equals(s.getSourceName(),t.getSourceName());
    }
    private static void require(boolean value,String why){if(!value)throw new IllegalArgumentException(why);}
}
