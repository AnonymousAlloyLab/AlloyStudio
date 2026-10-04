package is.fivefivefive.CanDis.theory;

/** Well-typed graph value for one explicit port schema in a caller context. */
public sealed interface PortValue extends HasSlotSupport permits
        OnePort, SeqPort, BagPort, SetPort, BindPort, BindBlockPort {
    PortSchema schema();

    TypedSlotContext context();

    PortValue act(TypedEmbedding embedding);

    StructuralKey structuralKey();
}
