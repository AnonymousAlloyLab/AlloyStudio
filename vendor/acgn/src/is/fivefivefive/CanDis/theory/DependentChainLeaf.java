package is.fivefivefive.CanDis.theory;

import java.util.List;
import java.util.Objects;

import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Ordered typed leaf of a dependent chain. */
public final class DependentChainLeaf implements DependentChainInput {
    private final OnePort port;
    private final DependentTypeDag outputTypeDag;
    private final DependentChainTheory.LeafTypeRule typeRule;
    private final StructuralKey typeProof;
    private final StructuralKey structuralKey;
    private final ExactAlloyType parserSourceAuthority;

    public DependentChainLeaf(OnePort port) {
        this(port, Objects.requireNonNull(port, "port").schema().type());
    }

    public DependentChainLeaf(OnePort port, GraphType relationType) {
        this(port, DependentTypeDag.fromRelationFamilyType(
                Objects.requireNonNull(relationType, "relationType")));
    }

    public DependentChainLeaf(
            OnePort port,
            GraphType relationType,
            List<DependentColumnEvidence> outputColumns) {
        this(
                port,
                DependentTypeDag.exactAlternative(
                        relationType, outputColumns));
    }

    public DependentChainLeaf(
            OnePort port,
            DependentTypeDag outputTypeDag) {
        this(port, outputTypeDag, null);
    }

    DependentChainLeaf(
            OnePort port,
            DependentTypeDag outputTypeDag,
            ExactAlloyType parserSourceAuthority) {
        this.port = Objects.requireNonNull(port, "port");
        this.outputTypeDag = Objects.requireNonNull(outputTypeDag, "outputTypeDag");
        this.parserSourceAuthority = parserSourceAuthority;
        GraphType relationType = outputTypeDag.relationType();
        if (parserSourceAuthority == null) {
            this.typeRule = DependentChainTheory.requireLeafTypeProof(
                    port.schema().type(), relationType);
            this.typeProof = DependentChainTheory.leafTypeProof(
                    typeRule, port.schema().type(), relationType);
        } else {
            this.typeRule = DependentChainTheory.requireLeafTypeProof(
                    port.schema().type(), outputTypeDag, parserSourceAuthority);
            this.typeProof = DependentChainTheory.leafTypeProof(
                    typeRule,
                    port.schema().type(),
                    outputTypeDag,
                    parserSourceAuthority);
        }
        this.structuralKey = StructuralKey.branch(
                "dependent-chain-leaf-v4",
                List.of(
                        port.structuralKey(),
                        typeProof,
                        outputTypeDag.structuralKey()));
    }

    public OnePort port() {
        return port;
    }

    public DependentChainTheory.LeafTypeRule typeRule() {
        return typeRule;
    }

    public StructuralKey typeProof() {
        return typeProof;
    }

    @Override
    public TypedSlotContext context() {
        return port.context();
    }

    @Override
    public DependentTypeDag outputTypeDag() {
        return outputTypeDag;
    }

    @Override
    public List<OnePort> leaves() {
        return List.of(port);
    }

    @Override
    public DependentChainLeaf act(TypedEmbedding embedding) {
        return new DependentChainLeaf(
                port.act(embedding), outputTypeDag, parserSourceAuthority);
    }

    @Override
    public StructuralKey structuralKey() {
        return structuralKey;
    }

    StructuralKey columnEvidenceKey() {
        return outputTypeDag.structuralKey();
    }

    static StructuralKey columnEvidenceKey(
            List<DependentColumnEvidence> columns) {
        return StructuralKey.branch(
                "dependent-chain-column-evidence-v1",
                columns.stream()
                        .map(DependentColumnEvidence::structuralKey)
                        .toList());
    }
}
