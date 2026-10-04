package is.fivefivefive.CanDis.theory;

import java.util.List;
import java.util.Objects;

/** Order- and duplicate-insensitive structural set port. */
public final class SetPort implements PortValue {
    private final SetPortSchema schema;
    private final TypedSlotContext context;
    private final List<PortValue> elements;

    public SetPort(
            SetPortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> elements) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        PortValues.requireAdmissibleCardinality(schema.arityPolicy(), elements);
        this.elements = PortValues.immutableSet(schema.elementSchema(), context, elements);
        PortValues.requireAdmissibleCardinality(schema.arityPolicy(), this.elements);
    }

    @Override
    public SetPortSchema schema() {
        return schema;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public List<PortValue> elements() {
        return elements;
    }

    public boolean isEmpty() {
        return elements.isEmpty();
    }

    @Override
    public TypedSlotContext support() {
        return PortValues.support(elements);
    }

    @Override
    public SetPort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        return new SetPort(schema, embedding.codomain(), PortValues.act(elements, embedding));
    }

    @Override
    public StructuralKey structuralKey() {
        return PortValues.key("port/set", schema, context, elements);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SetPort)) {
            return false;
        }
        SetPort port = (SetPort) other;
        return schema.equals(port.schema)
                && context.equals(port.context)
                && elements.equals(port.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, context, elements);
    }

    @Override
    public String toString() {
        return "Set" + elements;
    }
}
