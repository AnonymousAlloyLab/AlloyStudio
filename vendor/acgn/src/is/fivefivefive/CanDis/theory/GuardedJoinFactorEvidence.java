package is.fivefivefive.CanDis.theory;

import java.util.*;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;
import static is.fivefivefive.CanDis.theory.GuardedJoinDuplicateEvidence.*;

/** Retains absent JOIN premises across checked Boolean factoring, without
 * changing JOIN ports or treating unrelated owner occurrences as duplicates.
 * Contract: GuardedJoinFactoring.factor_or / factor_and. */
public final class GuardedJoinFactorEvidence {
    private record Frame(EGraphNode source, EGraphNode live, Map<String,Operand> commonSources,
            Map<Invocation,List<Operand>> commonLive) { }

    static final class Index {
        final GuardedJoinDuplicateEvidence.Index shared;
        private final Map<String,Optional<Frame>> frames=new HashMap<>();
        Index(GuardedJoinDuplicateEvidence.Index shared) { this.shared=shared; }
        private Set<Invocation> support(List<Operand> atoms) {
            var set=new HashSet<Invocation>();for(var atom:atoms)set.add(shared.invocation(atom));return set;
        }
        private Frame frame(int phase,String path) {
            return frames.computeIfAbsent(path,p->Optional.ofNullable(build(phase,p))).orElse(null);
        }
        private Frame build(int phase,String path) {
            var original=shared.originals.get(path);if(original==null||original.phase()!=phase)return null;
            var source=original.node();if(!booleanOwner(source))return null;
            var matches=shared.live.get(phase).getOrDefault(source.getSourceOccurrenceLineage(),List.of());
            require(matches.size()<=1,"Ambiguous factoring owner lineage");if(matches.isEmpty())return null;
            var live=matches.get(0);var outer=source.getOpcode();
            var inner=outer==EGraphNode.Opcode.OR?EGraphNode.Opcode.AND:EGraphNode.Opcode.OR;
            if(!booleanOwner(live)||live.getOpcode()!=inner||!source.getSemanticProfile().equals(live.getSemanticProfile()))return null;
            var sourceRoot=new Operand(source,path,source.getEClassRef().getSlotMap());
            var liveRoot=new Operand(live,path,live.getEClassRef().getSlotMap());
            var branches=operands(sourceRoot,outer).stream().map(b->operands(b,inner)).toList();
            var factors=operands(liveRoot,inner);
            // There is no distributive search: compare the actual factored shape.
            for(var residual:factors) {
                if(residual.node().getOpcode()!=outer||!booleanOwner(residual.node()))continue;
                var common=new ArrayList<>(factors);common.remove(residual);if(common.isEmpty())continue;
                var commonSupport=support(common);
                var expected=new HashSet<Set<Invocation>>();boolean matchesAll=true;
                for(var branch:branches) {
                    var keys=support(branch);if(!keys.containsAll(commonSupport)) {matchesAll=false;break;}
                    keys.removeAll(commonSupport);expected.add(Set.copyOf(keys));
                }
                if(!matchesAll)continue;
                var actual=new HashSet<Set<Invocation>>();
                for(var branch:operands(residual,outer))actual.add(Set.copyOf(support(operands(branch,inner))));
                if(!actual.equals(expected))continue;
                var originals=new LinkedHashMap<String,Operand>();
                for(var branch:branches)for(var atom:branch)
                    if(commonSupport.contains(shared.invocation(atom)))originals.put(atom.path(),atom);
                var retained=new HashMap<Invocation,List<Operand>>();
                for(var atom:common)retained.computeIfAbsent(shared.invocation(atom),k->new ArrayList<>()).add(atom);
                return new Frame(source,live,originals,retained);
            }
            return null;
        }
    }

    private final NormalForm owner;
    private final int phase;
    private final NormalForm.TemporalOp phaseRole;
    private final String phasePath, ancestorPath, removedAtomPath, retainedAtomPath;
    private final EGraphNode sourceOwner, liveOwner, liveAtom, liveJoin;
    private final GuardedJoinCertificate removed, retained;

    private GuardedJoinFactorEvidence(Index index,int phase,String path,Frame frame,Operand absent,
            Operand kept,Operand current,EGraphNode join,GuardedJoinCertificate removed,GuardedJoinCertificate retained) {
        owner=index.shared.forms.get(phase);this.phase=phase;phaseRole=owner.getTemporalOp();phasePath=owner.getPhasePath();
        ancestorPath=path;removedAtomPath=absent.path();retainedAtomPath=kept.path();
        sourceOwner=frame.source();liveOwner=frame.live();liveAtom=current.node();liveJoin=join;
        this.removed=removed;this.retained=retained;
    }

    static GuardedJoinFactorEvidence establish(Index index,int phase,GuardedJoinCertificate removed,
            Map<EGraphNode,GuardedJoinSourceFiber> fibers) {
        for(String path=parent(removed.sourceOccurrencePath());path!=null;path=parent(path)) {
            var frame=index.frame(phase,path);if(frame==null)continue;
            for(var absent:frame.commonSources().values()) {
                if(!removed.sourceOccurrencePath().startsWith(absent.path()+"/child/"))continue;
                var key=index.shared.invocation(absent);String tail=removed.sourceOccurrencePath().substring(absent.path().length());
                for(var current:frame.commonLive().getOrDefault(key,List.of()))for(var kept:frame.commonSources().values()) {
                    if(kept.path().equals(absent.path())||kept.node().getSourceOccurrenceLineage()!=current.node().getSourceOccurrenceLineage()
                            ||!index.shared.invocation(kept).equals(key))continue;
                    var join=descendant(current.node(),tail);var fiber=fibers.get(join);if(fiber==null)continue;
                    for(var proof:fiber.premises())if(proof.sourceOccurrencePath().equals(kept.path()+tail)) {
                        var result=new GuardedJoinFactorEvidence(index,phase,path,frame,absent,kept,current,join,removed,proof);
                        result.requireMatches(index);return result;
                    }
                }
            }
        }
        return null;
    }

    public void requireMatches(List<NormalForm> forms) {
        require(phase>=0&&phase<forms.size()&&forms.get(phase)==owner,"Factoring JOIN owner phase changed");
        var originals=new HashMap<String,GuardedJoinSourceFiber.Original>();
        originals.put(ancestorPath,new GuardedJoinSourceFiber.Original(at(ancestorPath),phase));
        var nodes=new IdentityHashMap<EGraphNode,Integer>();var pending=new ArrayDeque<EGraphNode>();pending.add(owner.getMatrixEGraph());
        while(!pending.isEmpty()) {var node=pending.remove();if(nodes.putIfAbsent(node,phase)==null)pending.addAll(node.getChildren());}
        requireMatches(new Index(new GuardedJoinDuplicateEvidence.Index(forms,originals,nodes)));
    }
    private void requireMatches(Index index) {
        require(index.shared.forms.get(phase)==owner&&owner.isFrozenForCertification()
                &&owner.getTemporalOp()==phaseRole&&owner.getPhasePath().equals(phasePath),"Factoring phase changed");
        var frame=index.frame(phase,ancestorPath);
        require(frame!=null&&frame.source()==sourceOwner&&frame.live()==liveOwner,"Factoring support changed");
        var absent=frame.commonSources().get(removedAtomPath);var kept=frame.commonSources().get(retainedAtomPath);
        require(absent!=null&&kept!=null&&!removedAtomPath.equals(retainedAtomPath)
                &&index.shared.invocation(absent).equals(index.shared.invocation(kept)),"Factoring source atoms differ");
        var current=frame.commonLive().getOrDefault(index.shared.invocation(kept),List.of());
        require(current.stream().anyMatch(a->a.node()==liveAtom)
                &&liveAtom.getSourceOccurrenceLineage()==kept.node().getSourceOccurrenceLineage(),"Factoring survivor changed");
        require(removed.sourceOccurrencePath().startsWith(removedAtomPath+"/child/"),"Factoring JOIN left source atom");
        String tail=removed.sourceOccurrencePath().substring(removedAtomPath.length());
        require(retained.sourceOccurrencePath().equals(retainedAtomPath+tail)&&descendant(liveAtom,tail)==liveJoin,"Factoring relative JOIN port changed");
        CertificateVerifier.verify(removed);CertificateVerifier.verify(retained);
        var source=at(removed.sourceOccurrencePath());var survivor=at(retained.sourceOccurrencePath());
        source.requirePristineCertificationSource();survivor.requirePristineCertificationSource();
        removed.requireOriginalMatches(source,removed.sourceOccurrencePath());retained.requireOriginalMatches(survivor,retained.sourceOccurrencePath());
        retained.requireMatches(liveJoin);
        require(!index.shared.live.get(phase).containsKey(removed.sourceOccurrenceLineage()),"Factored source occurrence still live");
        require(removed.sourceOccurrenceLineage()!=retained.sourceOccurrenceLineage()
                &&removed.semanticProfile().equals(retained.semanticProfile())&&removed.semanticProfile().equals(sourceOwner.getSemanticProfile())
                &&removed.source().context().equals(retained.source().context())
                &&removed.source().structuralKey().equals(retained.source().structuralKey())
                &&removed.target().structuralKey().equals(retained.target().structuralKey())
                &&removed.rightEndpoint().equals(retained.rightEndpoint()),"Factoring JOIN certificate authority differs");
    }
    private EGraphNode at(String path) {
        String prefix="phase/"+phase+"/matrix";
        require(path.equals(prefix)||path.startsWith(prefix+"/child/"),"Foreign factoring source path");
        return descendant(owner.getCertificationMatrixEGraph(),path.substring(prefix.length()));
    }
    public GuardedJoinCertificate removedPremise() { return removed; }
    public GuardedJoinCertificate retainedPremise() { return retained; }
    public String ancestorPath() { return ancestorPath; }
    private static void require(boolean ok,String why) { if(!ok)throw new IllegalArgumentException(why); }
}
