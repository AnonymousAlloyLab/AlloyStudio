package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Capture-safe value of {@code Port_Gamma(BindBlock(beta,kappa))}. */
public final class BindBlockPort implements PortValue {
    private final BindBlockPortSchema schema;
    private final TypedSlotContext context;
    private final TypedRenaming descriptorToOccurrence;
    private final PortValue body;

    public BindBlockPort(
            BindBlockPortSchema schema,
            TypedSlotContext context,
            TypedRenaming descriptorToOccurrence,
            PortValue body) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        this.descriptorToOccurrence = Objects.requireNonNull(
                descriptorToOccurrence, "descriptorToOccurrence");
        this.body = Objects.requireNonNull(body, "body");
        if (!schema.descriptor().boundContext().equals(descriptorToOccurrence.source())) {
            throw new IllegalArgumentException(
                    "Binder-block occurrence map must start at Delta_beta");
        }
        if (!context.isDisjoint(descriptorToOccurrence.codomain())) {
            throw new IllegalArgumentException(
                    "Binder-block occurrence context must be fresh for its caller");
        }
        for (TypedSlot slot : descriptorToOccurrence.codomain()) {
            if (slot.alphabet() != SlotAlphabet.CANONICAL_BOUND) {
                throw new IllegalArgumentException(
                        "Binder-block occurrences must use the canonical bound alphabet");
            }
        }
        if (!schema.bodySchema().equals(body.schema())) {
            throw new IllegalArgumentException("Binder-block body schema mismatch");
        }
        if (!context.union(descriptorToOccurrence.codomain()).equals(body.context())) {
            throw new IllegalArgumentException(
                    "Binder-block body context must be caller context plus its occurrence context");
        }
    }

    @Override
    public BindBlockPortSchema schema() {
        return schema;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public TypedRenaming descriptorToOccurrence() {
        return descriptorToOccurrence;
    }

    public TypedSlotContext boundContext() {
        return descriptorToOccurrence.codomain();
    }

    public PortValue body() {
        return body;
    }

    @Override
    public TypedSlotContext support() {
        return body.support().minus(boundContext());
    }

    @Override
    public BindBlockPort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        TypedRenaming targetOccurrence = schema.descriptor()
                .freshOccurrenceRenaming(embedding.codomain());
        TypedRenaming oldToTarget = descriptorToOccurrence
                .inverse()
                .andThen(targetOccurrence);
        TypedEmbedding extended = embedding.disjointUnion(oldToTarget);
        return new BindBlockPort(
                schema,
                embedding.codomain(),
                targetOccurrence,
                body.act(extended));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "port/bind-block",
                Arrays.asList(
                        schema.structuralKey(),
                        TheoryKeys.context(context),
                        TheoryKeys.embedding(descriptorToOccurrence),
                        body.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BindBlockPort)) {
            return false;
        }
        BindBlockPort port = (BindBlockPort) other;
        return schema.equals(port.schema)
                && context.equals(port.context)
                && descriptorToOccurrence.equals(port.descriptorToOccurrence)
                && body.equals(port.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, context, descriptorToOccurrence, body);
    }

    @Override
    public String toString() {
        return "bind[" + schema.descriptor() + "]" + boundContext() + "." + body;
    }
}
