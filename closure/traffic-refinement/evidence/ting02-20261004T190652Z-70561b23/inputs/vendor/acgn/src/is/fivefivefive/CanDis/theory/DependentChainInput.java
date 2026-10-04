package is.fivefivefive.CanDis.theory;

import java.util.List;

/** One leaf or binary source association in a dependent JOIN/ARROW chain. */
public sealed interface DependentChainInput permits
        DependentChainLeaf, DependentChainApplication {
    TypedSlotContext context();

    DependentTypeDag outputTypeDag();

    default GraphType outputType() {
        return outputTypeDag().relationType();
    }

    /** Compatibility view for callers that explicitly require one product. */
    default List<DependentColumnEvidence> outputColumns() {
        if (outputTypeDag().alternatives().size() != 1) {
            throw new IllegalStateException(
                    "A relation-family DAG has more than one correlated product");
        }
        return outputTypeDag().alternatives().get(0);
    }

    List<OnePort> leaves();

    DependentChainInput act(TypedEmbedding embedding);

    StructuralKey structuralKey();
}
