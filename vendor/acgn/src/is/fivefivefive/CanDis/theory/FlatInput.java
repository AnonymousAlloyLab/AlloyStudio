package is.fivefivefive.CanDis.theory;

/** Explicit source operand accepted by the visible-syntax flat constructor. */
public sealed interface FlatInput permits FlatLeaf, FlatApplication {
    GraphType outputType();

    TypedSlotContext context();

    StructuralKey structuralKey();
}
