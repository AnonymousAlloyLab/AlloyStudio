package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import is.fivefivefive.CanDis.core.EGraphNode;

/** Original occurrence -> realized node, by local rotations and insertion-linked
 * congruence. Derived intermediate terms are never additional source roots. */
public final class GuardedJoinCertificate extends TypedEqualityCertificate {
    public static final String VERSION = "source-linked-guarded-join-rotation-v2-path";
    private final GuardedJoinTree source, target;
    private final List<List<Integer>> steps;
    private final SemanticProfile profile;
    private final StructuralKey commitment;
    private final long lineage;
    private final String sourcePath;
    private final String transferContent;
    private final GuardedJoinNodeCertificate realization;

    private GuardedJoinCertificate(GuardedJoinTree source, GuardedJoinTree target,
            List<List<Integer>> steps, SemanticProfile profile, StructuralKey commitment,
            long lineage, String sourcePath, String transferContent, GuardedJoinNodeCertificate realization) {
        super(CertificateCategory.GUARDED_JOIN_ROTATION,
                TypedCertificateEndpoint.guardedJoin(source, profile, commitment),
                TypedCertificateEndpoint.node(realization.node()), List.of(realization),
                List.of(StructuralKey.of(VERSION, steps.stream().map(Object::toString).toList(), List.of())));
        this.source = source; this.target = target;
        this.steps = steps.stream().map(List::copyOf).toList();
        this.profile = Objects.requireNonNull(profile); this.commitment = commitment;
        this.lineage = lineage; this.transferContent = Objects.requireNonNull(transferContent);
        this.sourcePath = Objects.requireNonNull(sourcePath);
        this.realization = realization;
        CertificateVerifier.verify(this);
    }
    static GuardedJoinCertificate create(EGraphNode occurrence, String path,
            GuardedJoinTree source, SemanticProfile profile, GuardedJoinNodeCertificate realization) {
        List<List<Integer>> steps = new ArrayList<>();
        GuardedJoinTree target = normalize(source, List.of(), steps);
        StructuralKey commitment = TheoryAlloyAdapter.DependentChainSourceBinding.sourceOccurrenceCommitment(
                occurrence, path, source.structuralKey());
        var result = new GuardedJoinCertificate(source, target, steps, profile, commitment,
                occurrence.getSourceOccurrenceLineage(), path, occurrence.dependentChainTransferContentCommitment(), realization);
        result.requireOriginalMatches(occurrence, path);
        return result;
    }
    static GuardedJoinTree normalized(GuardedJoinTree source) { return normalize(source, List.of(), new ArrayList<>()); }
    private static GuardedJoinTree normalize(GuardedJoinTree tree, List<Integer> path,
            List<List<Integer>> steps) {
        if (tree.isLeaf()) return tree;
        tree = GuardedJoinTree.join(normalize(tree.left(), childPath(path, 0), steps),
                normalize(tree.right(), childPath(path, 1), steps));
        while (!tree.right().isLeaf()
                && AlloyTypeBridge.relationArity(tree.right().left().type().relationType()) >= 2) {
            if (steps.size() >= 1024) throw new IllegalArgumentException("JOIN rotation budget exceeded");
            tree = tree.rotate(); steps.add(List.copyOf(path));
            tree = GuardedJoinTree.join(normalize(tree.left(), childPath(path, 0), steps), tree.right());
        }
        return tree;
    }
    private static List<Integer> childPath(List<Integer> path, int side) {
        var result = new ArrayList<>(path); result.add(side); return List.copyOf(result);
    }
    @Override void verifyLocal() {
        if (!profile.isAdmissibleAlloyProfile() || lineage <= 0 || sourcePath.isBlank() || steps.size() > 1024)
            throw new IllegalArgumentException("Unadmitted JOIN proof");
        var replay = source;
        for (var path : steps) replay = replay.rotateAt(path);
        if (!replay.structuralKey().equals(target.structuralKey())
                || !target.structuralKey().equals(realization.tree().structuralKey())
                || !profile.equals(realization.semanticProfile())
                || !leftEndpoint().equals(TypedCertificateEndpoint.guardedJoin(source, profile, commitment))
                || !rightEndpoint().equals(TypedCertificateEndpoint.node(realization.node())))
            throw new IllegalArgumentException("JOIN replay endpoints differ");
    }
    public void requireMatches(EGraphNode occurrence) {
        if (occurrence.getSourceOccurrenceLineage() != lineage
                || !transferContent.equals(occurrence.dependentChainTransferContentCommitment()))
            throw new IllegalArgumentException("JOIN proof names another source occurrence/content");
        match(source, occurrence);
    }
    public void requireOriginalMatches(EGraphNode occurrence, String path) {
        requireMatches(occurrence);
        if (!sourcePath.equals(path) || !commitment.equals(
                TheoryAlloyAdapter.DependentChainSourceBinding.sourceOccurrenceCommitment(
                        occurrence, path, source.structuralKey())))
            throw new IllegalArgumentException("JOIN proof names another original source path/content");
    }
    private static void match(GuardedJoinTree tree, EGraphNode occurrence) {
        if (occurrence.getExactAlloyType() == null
                || !AlloyTypeBridge.graphType(occurrence.getExactAlloyType()).equals(tree.type().relationType()))
            throw new IllegalArgumentException("JOIN source type changed");
        if (tree.isLeaf()) {
            if (occurrence.getOpcode() == EGraphNode.Opcode.JOIN)
                throw new IllegalArgumentException("JOIN source leaf changed association");
        } else {
            if (occurrence.getOpcode() != EGraphNode.Opcode.JOIN || occurrence.getChildren().size() != 2)
                throw new IllegalArgumentException("JOIN source association changed");
            match(tree.left(), occurrence.getChildren().get(0));
            match(tree.right(), occurrence.getChildren().get(1));
        }
    }
    public GuardedJoinTree source() { return source; }
    public GuardedJoinTree target() { return target; }
    public List<List<Integer>> steps() { return steps; }
    public SemanticProfile semanticProfile() { return profile; }
    public StructuralKey sourceOccurrenceCommitment() { return commitment; }
    public long sourceOccurrenceLineage() { return lineage; }
    public String sourceOccurrencePath() { return sourcePath; }
    public GuardedJoinNodeCertificate realization() { return realization; }
}
