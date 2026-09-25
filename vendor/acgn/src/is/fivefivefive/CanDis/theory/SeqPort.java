package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Ordered, multiplicity-preserving sequence port. */
public final class SeqPort implements PortValue {
    private final SeqPortSchema schema;
    private final TypedSlotContext context;
    private final List<PortValue> elements;

    public SeqPort(
            SeqPortSchema schema,
            TypedSlotContext context,
            List<? extends PortValue> elements) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.context = Objects.requireNonNull(context, "context");
        PortValues.requireAdmissibleCardinality(schema.arityPolicy(), elements);
        if (schema.isDependent()) {
            List<PortValue> copied = new ArrayList<>();
            for (int index = 0; index < elements.size(); index++) {
                PortValue element = Objects.requireNonNull(
                        elements.get(index), "sequence element");
                if (!schema.schemaAt(index).equals(element.schema())
                        || !context.equals(element.context())) {
                    throw new IllegalArgumentException(
                            "Dependent sequence element " + index
                                    + " has another schema or context");
                }
                copied.add(element);
            }
            this.elements = Collections.unmodifiableList(copied);
        } else {
            this.elements = PortValues.immutableSequence(
                    schema.elementSchema(), context, elements);
        }
    }

    @Override
    public SeqPortSchema schema() {
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
    public SeqPort act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException("Port action source must equal its caller context");
        }
        return new SeqPort(schema, embedding.codomain(), PortValues.act(elements, embedding));
    }

    @Override
    public StructuralKey structuralKey() {
        return PortValues.key("port/seq", schema, context, elements);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof SeqPort)) {
            return false;
        }
        SeqPort port = (SeqPort) other;
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
        return "Seq" + elements;
    }
}
