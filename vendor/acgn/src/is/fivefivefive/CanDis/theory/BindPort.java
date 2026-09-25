package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Capture-safe value of {@code Port_Gamma(Bind(tau,kappa))}. */
public final class BindPort implements PortValue {
    private final BindPortSchema schema;
    private final TypedSlotContext context;
    private final TypedSlot boundSlot;
    private final PortValue body;

    public BindPort(
            BindPortSchema schema,
            TypedSlotContext context,
            TypedSlot boundSlot,
            PortValue body) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        this.boundSlot = Objects.requireNonNull(boundSlot, "boundSlot");
        this.body = Objects.requireNonNull(body, "body");
        if (!schema.boundType().equals(boundSlot.type())) {
            throw new IllegalArgumentException("Binder slot type does not match Bind schema");
        }
        if (context.contains(boundSlot)) {
            throw new IllegalArgumentException("Binder slot must be fresh for its caller context");
        }
        if (!schema.bodySchema().equals(body.schema())) {
            throw new IllegalArgumentException("Binder body schema mismatch");
        }
        if (!context.plus(boundSlot).equals(body.context())) {
            throw new IllegalArgumentException(
                    "Binder body context must be caller context plus the bound slot");
        }
    }

    @Override
    public BindPortSchema schema() {
        return schema;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public TypedSlot boundSlot() {
        return boundSlot;
    }

    public PortValue body() {
        return body;
    }

    @Override
    public TypedSlotContext support() {
        return body.support().without(boundSlot);
    }

    @Override
    public BindPort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        TypedSlot fresh = CanonicalSlotAlphabet.fresh(
                schema.boundType(), SlotAlphabet.CANONICAL_BOUND, embedding.codomain());
        TypedEmbedding extended = embedding.disjointExtension(boundSlot, fresh);
        return new BindPort(
                schema,
                embedding.codomain(),
                fresh,
                body.act(extended));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch(
                "port/bind",
                Arrays.asList(
                        schema.structuralKey(),
                        TheoryKeys.context(context),
                        TheoryKeys.slot(boundSlot),
                        body.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BindPort)) {
            return false;
        }
        BindPort port = (BindPort) other;
        return schema.equals(port.schema)
                && context.equals(port.context)
                && boundSlot.equals(port.boundSlot)
                && body.equals(port.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, context, boundSlot, body);
    }

    @Override
    public String toString() {
        return "bind " + boundSlot + "." + body;
    }
}
