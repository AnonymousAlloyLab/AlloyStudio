package is.fivefivefive.CanDis.theory;

import java.util.*;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;

/** Accounts for a distinct original JOIN beneath an idempotently removed
 * Boolean or relational SET operand. The absent certificate is retained, never
 * rebound to the surviving node. Contracts: GuardedJoinDuplicate.lean and
 * GuardedJoinRelationalDuplicate.lean. */
public final class GuardedJoinDuplicateEvidence {
    record Operand(EGraphNode node, String path, Map<String,String> slots) { }
    record Invocation(String content, Map<String,String> slots) { }
    private record Survivor(Operand source, Operand live) { }
    private record Frame(EGraphNode source, EGraphNode live, Map<String,Operand> originals,
            Map<Long,List<Operand>> liveAtoms, Map<Invocation,List<Survivor>> survivors) { }

    /** One immutable-input indexing session per bind, never a cross-run cache. */
    static final class Index {
        final List<NormalForm> forms;
        final Map<String,GuardedJoinSourceFiber.Original> originals;
        final List<Map<Long,List<EGraphNode>>> live = new ArrayList<>();
        private final Map<String,Optional<Frame>> frames = new HashMap<>();
        private final Map<EGraphNode,String> content = new IdentityHashMap<>();
        private final Map<Integer,Boolean> phaseInvocations = new HashMap<>();

        Index(List<NormalForm> forms, Map<String,GuardedJoinSourceFiber.Original> originals,
                Map<EGraphNode,Integer> nodes) {
            this.forms=forms;this.originals=originals;
            for(int i=0;i<forms.size();i++)live.add(new HashMap<>());
            for(var entry:nodes.entrySet()) {
                var node=entry.getKey();
                live.get(entry.getValue()).computeIfAbsent(node.getSourceOccurrenceLineage(),k->new ArrayList<>()).add(node);
            }
        }
        Invocation invocation(Operand atom) {
            return new Invocation(content.computeIfAbsent(atom.node(),EGraphNode::semanticInvocationContentKey),atom.slots());
        }
        private Frame frame(int phase,String path) {
            return frames.computeIfAbsent(path,key->Optional.ofNullable(build(phase,key))).orElse(null);
        }
        private Frame build(int phase,String path) {
            var source=originals.get(path).node();if(!duplicateOwner(source))return null;
            var matches=live.get(phase).getOrDefault(source.getSourceOccurrenceLineage(),List.of());
            require(matches.size()<=1,"Ambiguous duplicate owner lineage");
            if(matches.isEmpty()&&!relationalOwner(source))return null;
            var flattened=duplicateOperands(source,path,source);if(flattened==null)return null;
            var originalAtoms=new LinkedHashMap<String,Operand>();
            for(var atom:flattened)originalAtoms.put(atom.path(),atom);
            var repair=matches.isEmpty()?collapsedOwner(phase,source,originalAtoms.values()):matches.get(0);
            if(repair==null)return null;
            if(!EGraphNode.sameSemanticInvocation(source,repair))return null;
            var repaired=duplicateOperands(repair,path,source);if(repaired==null)return null;
            var liveAtoms=new HashMap<Long,List<Operand>>();
            for(var atom:repaired)
                liveAtoms.computeIfAbsent(atom.node().getSourceOccurrenceLineage(),k->new ArrayList<>()).add(atom);
            var survivors=new HashMap<Invocation,List<Survivor>>();
            for(var atom:originalAtoms.values())for(var current:liveAtoms.getOrDefault(atom.node().getSourceOccurrenceLineage(),List.of()))
                if(invocation(atom).equals(invocation(current)))
                    survivors.computeIfAbsent(invocation(atom),k->new ArrayList<>()).add(new Survivor(atom,current));
            return new Frame(source,repair,originalAtoms,liveAtoms,survivors);
        }
        private EGraphNode collapsedOwner(int phase,EGraphNode source,Collection<Operand> atoms) {
            if(!relationalOwner(source)||atoms.isEmpty())return null;
            Invocation support=null;EGraphNode survivor=null;
            for(var atom:atoms) {
                var key=invocation(atom);
                if(support!=null&&!support.equals(key))return null;
                support=key;
                for(var current:live.get(phase).getOrDefault(atom.node().getSourceOccurrenceLineage(),List.of())) {
                    if(survivor!=null&&survivor!=current)return null;
                    survivor=current;
                }
            }
            if(survivor==null)return null;
            // Anchor the vanished local owner in the unchanged, same-arena phase
            // quotient as well as its actual original surviving descendant.
            if(!phaseInvocations.computeIfAbsent(phase,i->EGraphNode.sameSemanticInvocation(
                    forms.get(i).getCertificationMatrixEGraph(),forms.get(i).getMatrixEGraph())))return null;
            return survivor;
        }
    }
    private final NormalForm owner;
    private final int phase;
    private final NormalForm.TemporalOp phaseRole;
    private final String phasePath;
    private final String ancestorPath, removedAtomPath, retainedAtomPath;
    private final GuardedJoinCertificate removed, retained;
    private final EGraphNode originalOwner, repairOwner, repairAtom, repairJoin;

    private GuardedJoinDuplicateEvidence(NormalForm owner, int phase, String ancestorPath,
            Operand removedAtom, Operand retainedAtom, GuardedJoinCertificate removed,
            GuardedJoinCertificate retained, EGraphNode originalOwner,
            EGraphNode repairOwner, EGraphNode repairAtom, EGraphNode repairJoin) {
        this.owner=owner; this.phase=phase; this.ancestorPath=ancestorPath;
        phaseRole=owner.getTemporalOp(); phasePath=owner.getPhasePath();
        removedAtomPath=removedAtom.path(); retainedAtomPath=retainedAtom.path();
        this.removed=removed; this.retained=retained; this.originalOwner=originalOwner;
        this.repairOwner=repairOwner; this.repairAtom=repairAtom; this.repairJoin=repairJoin;
    }

    static GuardedJoinDuplicateEvidence establish(Index index, int phase, GuardedJoinCertificate removed,
            Map<EGraphNode,GuardedJoinSourceFiber> fibers) {
        var form=index.forms.get(phase);
        String ancestor=parent(removed.sourceOccurrencePath());
        while (ancestor!=null) {
            var frame=index.frame(phase,ancestor);
            if(frame!=null) {
                for(String atomPath=removed.sourceOccurrencePath();atomPath!=null&&!atomPath.equals(ancestor);atomPath=parent(atomPath)) {
                    var absent=frame.originals().get(atomPath);
                    if(absent!=null) {
                        String tail=removed.sourceOccurrencePath().substring(absent.path().length());
                        for(var kept:frame.survivors().getOrDefault(index.invocation(absent),List.of())) {
                            if(absent.path().equals(kept.source().path()))continue;
                            var originalJoin=index.originals.get(kept.source().path()+tail);
                            if(originalJoin==null)continue;
                            var join=descendant(kept.live().node(),tail);var fiber=fibers.get(join);
                            if(fiber==null)continue;
                            for(var proof:fiber.premises()) {
                                if(!proof.sourceOccurrencePath().equals(kept.source().path()+tail))continue;
                                var evidence=new GuardedJoinDuplicateEvidence(form,phase,ancestor,absent,kept.source(),
                                        removed,proof,frame.source(),frame.live(),kept.live().node(),join);
                                evidence.requireMatches(index);
                                return evidence;
                            }
                        }
                        break;
                    }
                }
            }
            ancestor=parent(ancestor);
        }
        return null;
    }

    public void requireMatches(List<NormalForm> forms) {
        var originals=new HashMap<String,GuardedJoinSourceFiber.Original>();
        for(String path=ancestorPath;path!=null;path=parent(path))
            originals.put(path,new GuardedJoinSourceFiber.Original(at(path),phase));
        var nodes=new IdentityHashMap<EGraphNode,Integer>();var pending=new ArrayDeque<EGraphNode>();
        require(phase>=0 && phase<forms.size() && forms.get(phase)==owner,"Duplicate JOIN owner phase changed");
        pending.add(owner.getMatrixEGraph());
        while(!pending.isEmpty()) { var node=pending.remove();if(nodes.putIfAbsent(node,phase)==null)pending.addAll(node.getChildren()); }
        requireMatches(new Index(forms,originals,nodes));
    }

    private void requireMatches(Index index) {
        var forms=index.forms;
        require(phase>=0 && phase<forms.size() && forms.get(phase)==owner && owner.isFrozenForCertification(),
                "Duplicate JOIN owner phase changed");
        require(owner.getTemporalOp()==phaseRole && owner.getPhasePath().equals(phasePath),
                "Duplicate JOIN temporal coordinate changed");
        require(at(ancestorPath)==originalOwner && duplicateOwner(originalOwner),"Duplicate SET owner changed");
        var frame=index.frame(phase,ancestorPath);
        require(frame!=null && frame.source()==originalOwner && frame.live()==repairOwner,"Duplicate Boolean quotient changed");
        var absent=frame.originals().get(removedAtomPath);var kept=frame.originals().get(retainedAtomPath);
        require(absent!=null && kept!=null && !absent.path().equals(kept.path())
                && index.invocation(absent).equals(index.invocation(kept)),"Not identical original Boolean invocations");
        var live=frame.liveAtoms().getOrDefault(repairAtom.getSourceOccurrenceLineage(),List.of());
        require(live.size()==1 && live.get(0).node()==repairAtom
                && repairAtom.getSourceOccurrenceLineage()==kept.node().getSourceOccurrenceLineage()
                && index.invocation(kept).equals(index.invocation(live.get(0))),"Duplicate survivor atom changed");
        require(removed.sourceOccurrencePath().equals(removedAtomPath)
                || removed.sourceOccurrencePath().startsWith(removedAtomPath+"/child/"),"Duplicate JOIN left its source atom");
        String tail=removed.sourceOccurrencePath().substring(removedAtomPath.length());
        require(retained.sourceOccurrencePath().equals(retainedAtomPath+tail)
                && descendant(repairAtom,tail)==repairJoin,"Duplicate JOIN relative port changed");
        CertificateVerifier.verify(removed); CertificateVerifier.verify(retained);
        var source=at(removed.sourceOccurrencePath()); var survivor=at(retained.sourceOccurrencePath());
        source.requirePristineCertificationSource(); survivor.requirePristineCertificationSource();
        removed.requireOriginalMatches(source,removed.sourceOccurrencePath());
        retained.requireOriginalMatches(survivor,retained.sourceOccurrencePath());
        retained.requireMatches(repairJoin);
        require(!index.live.get(phase).containsKey(removed.sourceOccurrenceLineage()),"Duplicate source occurrence is still live");
        require(removed.sourceOccurrenceLineage()!=retained.sourceOccurrenceLineage()
                && removed.semanticProfile().equals(retained.semanticProfile())
                && removed.semanticProfile().equals(originalOwner.getSemanticProfile())
                && removed.source().context().equals(retained.source().context())
                && removed.source().structuralKey().equals(retained.source().structuralKey())
                && removed.target().structuralKey().equals(retained.target().structuralKey())
                && removed.rightEndpoint().equals(retained.rightEndpoint()),"Duplicate JOIN certificate authority differs");
    }

    public GuardedJoinCertificate removedPremise() { return removed; }
    public GuardedJoinCertificate retainedPremise() { return retained; }
    public String ancestorPath() { return ancestorPath; }
    public int ownerPhase() { return phase; }

    private EGraphNode at(String path) {
        String prefix="phase/"+phase+"/matrix";
        require(path.equals(prefix)||path.startsWith(prefix+"/child/"),"Foreign duplicate source path");
        return descendant(owner.getCertificationMatrixEGraph(),path.substring(prefix.length()));
    }
    static EGraphNode descendant(EGraphNode node,String tail) {
        if (tail.isEmpty()) return node;
        String[] parts=tail.split("/");
        require(parts.length%2==1 && parts[0].isEmpty(),"Malformed duplicate path");
        for (int i=1;i<parts.length;i+=2) {
            require(parts[i].equals("child"),"Malformed duplicate child path");
            int child=Integer.parseInt(parts[i+1]);
            require(child>=0 && child<node.getChildren().size(),"Duplicate child missing");
            node=node.getChildren().get(child);
        }
        return node;
    }
    private static List<Operand> operands(EGraphNode root,String path,EGraphNode.Opcode opcode) {
        return operands(new Operand(root,path,root.getEClassRef().getSlotMap()),opcode);
    }
    static List<Operand> operands(Operand root,EGraphNode.Opcode opcode) {
        return operands(root,opcode,null);
    }
    private static List<Operand> duplicateOperands(EGraphNode root,String path,EGraphNode owner) {
        return operands(new Operand(root,path,root.getEClassRef().getSlotMap()),owner.getOpcode(),
                relationalOwner(owner)?owner.getExactAlloyType():null);
    }
    private static List<Operand> operands(Operand root,EGraphNode.Opcode opcode,ExactAlloyType relationType) {
        var result=new ArrayList<Operand>(); var pending=new ArrayDeque<Operand>();
        boolean homogeneous=true;
        pending.push(root);
        while (!pending.isEmpty()) {
            var current=pending.pop(); var node=current.node();
            var type=node.getExactAlloyType();
            require(type!=null && (relationType==null?type.kind()==ExactAlloyType.Kind.BOOL
                            :relationalType(type)&&type.relationArity()==relationType.relationArity())
                    && node.getSemanticProfile().equals(root.node().getSemanticProfile()),"Untyped duplicate operand");
            if(relationType!=null&&!relationType.equals(type))homogeneous=false;
            if (node.getOpcode()!=opcode) { result.add(current); continue; }
            require(relationType==null?booleanOwner(node):relationalOwner(node),"Unlicensed duplicate SET owner");
            var children=node.getChildClasses();
            for (int i=children.size()-1;i>=0;i--) {
                var child=children.get(i); var slots=new LinkedHashMap<String,String>();
                for (var entry:child.getSlotMap().entrySet()) {
                    String target=current.slots().get(entry.getValue());
                    require(target!=null,"Duplicate Boolean slot transport missing");slots.put(entry.getKey(),target);
                }
                pending.push(new Operand(child.getEClass().getRepresentative(),current.path()+"/child/"+i,Map.copyOf(slots)));
            }
        }
        // A heterogeneous union/intersection may be valid but cannot instantiate
        // this homogeneous law. Continue the ancestor search, never accept it.
        return homogeneous?result:null;
    }
    static boolean booleanOwner(EGraphNode n) {
        return (n.getOpcode()==EGraphNode.Opcode.AND || n.getOpcode()==EGraphNode.Opcode.OR)
                && n.isSetFlexibleArity() && n.getMetatype()==EGraphNode.Metatype.BOOLEAN
                && n.getExactAlloyType()!=null && n.getExactAlloyType().kind()==ExactAlloyType.Kind.BOOL
                && n.getSemanticProfile().isAdmissibleAlloyProfile();
    }
    private static boolean relationalOwner(EGraphNode n) {
        var type=n.getExactAlloyType();
        return (n.getOpcode()==EGraphNode.Opcode.PLUS || n.getOpcode()==EGraphNode.Opcode.INTERSECT)
                && n.isSetFlexibleArity() && n.getMetatype()==EGraphNode.Metatype.SET && type!=null
                && relationalType(type)
                && n.getSemanticProfile().isAdmissibleAlloyProfile();
    }
    private static boolean relationalType(ExactAlloyType type) {
        return type.kind()==ExactAlloyType.Kind.RELATION || type.kind()==ExactAlloyType.Kind.EMPTY_RELATION;
    }
    private static boolean duplicateOwner(EGraphNode n) { return booleanOwner(n)||relationalOwner(n); }
    static String parent(String path) {
        int i=path.lastIndexOf("/child/");return i<0?null:path.substring(0,i);
    }
    private static void require(boolean ok,String why) { if(!ok)throw new IllegalArgumentException(why); }
}
