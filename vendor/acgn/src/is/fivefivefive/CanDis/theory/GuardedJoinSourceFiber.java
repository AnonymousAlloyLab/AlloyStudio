package is.fivefivefive.CanDis.theory;

import java.util.*;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.NormalForm;

/** All original occurrence proofs behind one shared repair node; no proof is
 * selected by lineage alone. Source paths remain in the construction ledger. */
public final class GuardedJoinSourceFiber {
    private static final int MAX_NODES = 100_000, MAX_DEPTH = 256;
    private final List<GuardedJoinCertificate> premises;
    private final GuardedJoinTree source, target;
    private final int ownerPhase;
    private record Original(EGraphNode node, int phase) { }

    private GuardedJoinSourceFiber(List<GuardedJoinCertificate> premises, int ownerPhase) {
        if (premises.isEmpty()) throw new IllegalArgumentException("Empty JOIN source fiber");
        this.premises = List.copyOf(premises);
        this.ownerPhase = ownerPhase;
        var first = premises.get(0);
        source = first.source(); target = first.target();
        Set<String> paths = new HashSet<>();
        for (var proof : premises) {
            CertificateVerifier.verify(proof);
            if (!paths.add(proof.sourceOccurrencePath())
                    || proof.sourceOccurrenceLineage() != first.sourceOccurrenceLineage()
                    || !proof.semanticProfile().equals(first.semanticProfile())
                    || !proof.source().structuralKey().equals(source.structuralKey())
                    || !proof.target().structuralKey().equals(target.structuralKey())
                    || !proof.rightEndpoint().equals(first.rightEndpoint()))
                throw new IllegalArgumentException("Conflicting guarded JOIN occurrence proofs");
        }
    }

    public void requireMatches(EGraphNode repairSource) {
        for (var proof : premises) proof.requireMatches(repairSource);
    }
    public GuardedJoinTree source() { return source; }
    public GuardedJoinTree target() { return target; }
    public List<GuardedJoinCertificate> premises() { return premises; }

    public static Map<EGraphNode, GuardedJoinSourceFiber> bind(
            CertifiedSemanticArtifact artifact, List<NormalForm> forms) {
        return bindWithEvidence(artifact,forms).live();
    }

    public record Binding(Map<EGraphNode,GuardedJoinSourceFiber> live,
            List<GuardedJoinErasureEvidence> erased) {
        public Binding {
            live=Collections.unmodifiableMap(new IdentityHashMap<>(live));
            erased=List.copyOf(erased);
        }
    }

    public static Binding bindWithEvidence(CertifiedSemanticArtifact artifact,List<NormalForm> forms) {
        if (artifact.guardedJoinConstructions().isEmpty()) return new Binding(Map.of(),List.of());
        Map<String, Original> originals = new HashMap<>();
        for (int i = 0; i < forms.size(); i++) {
            var root = forms.get(i).getCertificationMatrixEGraph();
            if (root != null) index(root, i, "phase/" + i + "/matrix", 0, originals);
        }
        Map<Long, List<GuardedJoinCertificate>> groups = new LinkedHashMap<>();
        Map<Long, Integer> owners = new HashMap<>();
        Set<String> usedPaths = new HashSet<>();
        for (var proof : artifact.guardedJoinConstructions()) {
            String path = proof.sourceOccurrencePath();
            var original = originals.get(path);
            if (original == null || !usedPaths.add(path))
                throw new IllegalArgumentException("Missing/repeated original JOIN occurrence path");
            original.node().requirePristineCertificationSource();
            proof.requireOriginalMatches(original.node(), path);
            var priorOwner = owners.putIfAbsent(proof.sourceOccurrenceLineage(), original.phase());
            if (priorOwner != null && priorOwner != original.phase())
                throw new IllegalArgumentException("Guarded JOIN ancestry crosses temporal owner phases");
            groups.computeIfAbsent(proof.sourceOccurrenceLineage(), k -> new ArrayList<>()).add(proof);
        }
        Map<Long, GuardedJoinSourceFiber> fibers = new HashMap<>();
        groups.forEach((lineage, proofs) -> fibers.put(lineage, new GuardedJoinSourceFiber(proofs, owners.get(lineage))));
        Map<EGraphNode, GuardedJoinSourceFiber> result = new IdentityHashMap<>();
        Set<Long> used = new HashSet<>();
        Map<EGraphNode, Integer> visited = new IdentityHashMap<>();
        ArrayDeque<Original> pending = new ArrayDeque<>();
        for (int i=0; i<forms.size(); i++) if (forms.get(i).getMatrixEGraph() != null)
            pending.add(new Original(forms.get(i).getMatrixEGraph(), i));
        while (!pending.isEmpty()) {
            var occurrence = pending.removeFirst();
            var node = occurrence.node();
            var priorOwner = visited.putIfAbsent(node, occurrence.phase());
            if (priorOwner != null) {
                if (priorOwner != occurrence.phase()) throw new IllegalArgumentException("Shared JOIN repair node crosses phases");
                continue;
            }
            if (visited.size() > MAX_NODES) throw new IllegalArgumentException("JOIN repair occurrence budget exceeded");
            var fiber = fibers.get(node.getSourceOccurrenceLineage());
            if (fiber != null) {
                if (fiber.ownerPhase != occurrence.phase())
                    throw new IllegalArgumentException("Guarded JOIN repair phase differs from original owner");
                fiber.requireMatches(node);
                result.put(node, fiber);
                used.add(node.getSourceOccurrenceLineage());
            }
            for (var child : node.getChildren()) pending.add(new Original(child, occurrence.phase()));
        }
        var erased=new ArrayList<GuardedJoinErasureEvidence>();
        for(var entry:groups.entrySet())if(!used.contains(entry.getKey())) {
            for(var proof:entry.getValue()) {
                if(erased.size()>=128)throw new IllegalArgumentException("JOIN Boolean erasure premise budget exceeded");
                erased.add(GuardedJoinErasureEvidence.establish(forms,owners.get(entry.getKey()),proof));
            }
        }
        return new Binding(result,erased);
    }

    private static void index(EGraphNode node, int phase, String path, int depth, Map<String, Original> output) {
        if (depth > MAX_DEPTH || output.size() >= MAX_NODES)
            throw new IllegalArgumentException("JOIN original occurrence budget exceeded");
        output.put(path, new Original(node, phase));
        var children = node.getChildren();
        for (int i = 0; i < children.size(); i++) index(children.get(i), phase, path + "/child/" + i, depth + 1, output);
    }
}
