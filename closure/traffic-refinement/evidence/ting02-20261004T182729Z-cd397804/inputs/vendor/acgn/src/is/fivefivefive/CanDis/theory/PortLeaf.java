package is.fivefivefive.CanDis.theory;

/** One-port leaf: either a typed slot or an opaque typed invocation. */
public sealed interface PortLeaf permits SlotPortLeaf, InvocationPortLeaf {
    GraphType type();

    TypedSlotContext support();

    PortLeaf act(TypedEmbedding embedding);

    StructuralKey structuralKey();
}
