package is.fivefivefive.CanDis.theory;

import java.util.List;
import java.util.Objects;

/** Order-insensitive, multiplicity-preserving bag port. */
public final class BagPort implements PortValue {
    private final BagPortSchema schema;
    private final TypedSlotContext context;
    private final List<PortValue> occurrences;

    public BagPort(
            BagPortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> occurrences) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        PortValues.requireAdmissibleCardinality(schema.arityPolicy(), occurrences);
        this.occurrences = PortValues.immutableBag(
                schema.elementSchema(), context, occurrences);
    }

    @Override
    public BagPortSchema schema() {
        return schema;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    public List<PortValue> occurrences() {
        return occurrences;
    }

    public boolean isEmpty() {
        return occurrences.isEmpty();
    }

    @Override
    public TypedSlotContext support() {
        return PortValues.support(occurrences);
    }

    @Override
    public BagPort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        return new BagPort(schema, embedding.codomain(), PortValues.act(occurrences, embedding));
    }

    @Override
    public StructuralKey structuralKey() {
        return PortValues.key("port/bag", schema, context, occurrences);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof BagPort)) {
            return false;
        }
        BagPort port = (BagPort) other;
        return schema.equals(port.schema)
                && context.equals(port.context)
                && occurrences.equals(port.occurrences);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, context, occurrences);
    }

    @Override
    public String toString() {
        return "Bag" + occurrences;
    }
}
