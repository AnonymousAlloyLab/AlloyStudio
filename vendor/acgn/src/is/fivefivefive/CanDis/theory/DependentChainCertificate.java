package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Exact source-association proof for one heterogeneous JOIN/ARROW chain. */
public final class DependentChainCertificate extends TypedEqualityCertificate {
    private final DependentChainApplication source;
    private final TypedENode target;
    private final SemanticProfile semanticProfile;
    private final List<GraphType> operandTypes;
    private final StructuralKey sourceOccurrenceCommitment;
    private final StructuralKey theoryIndex;

    private DependentChainCertificate(Build build) {
        super(
                CertificateCategory.DEPENDENT_CHAIN_NORMALIZATION,
                TypedCertificateEndpoint.dependentChainApplication(
                        build.source,
                        build.semanticProfile,
                        build.sourceOccurrenceCommitment),
                TypedCertificateEndpoint.node(build.target),
                List.of(),
                build.details);
        this.source = build.source;
        this.target = build.target;
        this.semanticProfile = build.semanticProfile;
        this.operandTypes = build.operandTypes;
        this.sourceOccurrenceCommitment = build.sourceOccurrenceCommitment;
        this.theoryIndex = build.theoryIndex;
        verifyLocal();
    }

    static DependentChainCertificate createProduction(
            DependentChainApplication source,
            TypedENode target,
            SemanticProfile semanticProfile) {
        return createProduction(
                source,
                target,
                semanticProfile,
                StructuralKey.branch(
                        "dependent-chain-semantic-source-v1",
                        List.of(source.structuralKey())));
    }

    static DependentChainCertificate createProduction(
            DependentChainApplication source,
            TypedENode target,
            SemanticProfile semanticProfile,
            StructuralKey sourceOccurrenceCommitment) {
        DependentChainCertificate certificate = new DependentChainCertificate(
                build(source, target, semanticProfile, sourceOccurrenceCommitment));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static Build build(
            DependentChainApplication source,
            TypedENode target,
            SemanticProfile semanticProfile,
            StructuralKey sourceOccurrenceCommitment) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        Objects.requireNonNull(
                sourceOccurrenceCommitment, "sourceOccurrenceCommitment");
        if (!semanticProfile.isAdmissibleAlloyProfile()) {
            throw new IllegalArgumentException(
                    "Dependent-chain production requires an authorized Alloy profile");
        }
        if (!source.context().equals(target.context())
                || !source.outputType().equals(target.outputType())
                || !target.operator().operator().equals(
                        source.kind().operatorIdentity())
                || target.operator().usesFlatConstruction()
                || target.ports().size() != 1
                || !(target.ports().get(0) instanceof SeqPort)
                || !((SeqPort) target.ports().get(0)).schema().isDependent()) {
            throw new IllegalArgumentException(
                    "Dependent source and target do not share one exact chain instance");
        }
        List<OnePort> leaves = source.leaves();
        SeqPort targetSequence = (SeqPort) target.ports().get(0);
        if (targetSequence.elements().size() != leaves.size()) {
            throw new IllegalArgumentException(
                    "Dependent target does not retain every ordered source leaf");
        }
        List<GraphType> operandTypes = new ArrayList<>();
        for (int index = 0; index < leaves.size(); index++) {
            OnePort leaf = leaves.get(index);
            if (!leaf.equals(targetSequence.elements().get(index))) {
                throw new IllegalArgumentException(
                        "Dependent target changed source operand role " + index);
            }
            operandTypes.add(source.leafTypes().get(index));
        }
        DependentTypeDag result = DependentTypeDag.fold(
                source.kind(),
                source.leafInputs().stream()
                        .map(DependentChainLeaf::outputTypeDag)
                        .toList());
        if (!result.equals(source.outputTypeDag())
                || !result.relationType().equals(target.outputType())) {
            throw new IllegalArgumentException(
                    "Dependent target claims another correlated result family");
        }
        StructuralKey theoryIndex = DependentChainTheory.proofIndex(source);
        List<StructuralKey> details = List.of(
                semanticProfile.structuralKey(),
                StructuralKey.leaf(
                        "dependent-chain-theory", DependentChainTheory.DIGEST),
                theoryIndex,
                source.structuralKey(),
                sourceOccurrenceCommitment,
                target.structuralKey());
        return new Build(
                source,
                target,
                semanticProfile,
                Collections.unmodifiableList(operandTypes),
                sourceOccurrenceCommitment,
                theoryIndex,
                details);
    }

    public DependentChainApplication source() { return source; }
    public TypedENode target() { return target; }
    public SemanticProfile semanticProfile() { return semanticProfile; }
    public List<GraphType> operandTypes() { return operandTypes; }
    public StructuralKey sourceOccurrenceCommitment() {
        return sourceOccurrenceCommitment;
    }
    public StructuralKey theoryIndex() { return theoryIndex; }

    @Override
    void verifyLocal() {
        Build rebuilt = build(
                source,
                target,
                semanticProfile,
                sourceOccurrenceCommitment);
        if (!operandTypes.equals(rebuilt.operandTypes)
                || !sourceOccurrenceCommitment.equals(
                        rebuilt.sourceOccurrenceCommitment)
                || !theoryIndex.equals(rebuilt.theoryIndex)
                || !leftEndpoint().equals(
                        TypedCertificateEndpoint.dependentChainApplication(
                                source,
                                semanticProfile,
                                sourceOccurrenceCommitment))
                || !rightEndpoint().equals(TypedCertificateEndpoint.node(target))) {
            throw new IllegalStateException(
                    "Malformed dependent-chain construction certificate");
        }
    }

    private record Build(
            DependentChainApplication source,
            TypedENode target,
            SemanticProfile semanticProfile,
            List<GraphType> operandTypes,
            StructuralKey sourceOccurrenceCommitment,
            StructuralKey theoryIndex,
            List<StructuralKey> details) {
    }
}
