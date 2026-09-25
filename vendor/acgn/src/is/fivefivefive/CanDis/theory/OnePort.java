package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Value of {@code Port_Gamma(One(tau))}. */
public final class OnePort implements PortValue {
    private final OnePortSchema schema;
    private final TypedSlotContext context;
    private final PortLeaf leaf;

    public OnePort(OnePortSchema schema, TypedSlotContext context, PortLeaf leaf) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        this.leaf = Objects.requireNonNull(leaf, "leaf");
        if (!schema.type().equals(leaf.type())) {
            throw new IllegalArgumentException("One-port leaf type does not match its schema");
        }
        if (leaf instanceof SlotPortLeaf
                && !context.contains(((SlotPortLeaf) leaf).slot())) {
            throw new IllegalArgumentException("One-port slot must belong to its caller context");
        }
        if (leaf instanceof InvocationPortLeaf
                && !context.equals(((InvocationPortLeaf) leaf).invocation().callerContext())) {
            throw new IllegalArgumentException(
                    "One-port invocation caller context must equal the port context");
        }
    }

    public static OnePort slot(TypedSlotContext context, TypedSlot slot) {
        return new OnePort(new OnePortSchema(slot.type()), context, new SlotPortLeaf(slot));
    }

    public static OnePort invocation(TypedSlotContext context, TypedInvocation invocation) {
        return new OnePort(
                new OnePortSchema(invocation.outputType()),
                context,
                new InvocationPortLeaf(invocation));
    }

    @Override
    public OnePortSchema schema() {
        return schema;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public PortLeaf leaf() {
        return leaf;
    }

    @Override
    public TypedSlotContext support() {
        return leaf.support();
    }

    @Override
    public OnePort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        return new OnePort(schema, embedding.codomain(), leaf.act(embedding));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "port/one",
                Arrays.asList(
                        schema.structuralKey(),
                        TheoryKeys.context(context),
                        leaf.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OnePort)) {
            return false;
        }
        OnePort port = (OnePort) other;
        return schema.equals(port.schema)
                && context.equals(port.context)
                && leaf.equals(port.leaf);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, context, leaf);
    }

    @Override
    public String toString() {
        return leaf.toString();
    }
}
