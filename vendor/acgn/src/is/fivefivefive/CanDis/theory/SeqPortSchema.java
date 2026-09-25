package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Schema {@code Seq^epsilon(kappa)}. */
public final class SeqPortSchema implements PortSchema {
    private final ArityPolicy arityPolicy;
    private final PortSchema elementSchema;
    private final List<PortSchema> positionalElementSchemas;

    /** Shorthand for the nonempty schema {@code Seq+(kappa)}. */
    public SeqPortSchema(PortSchema elementSchema) {
        this(ContainerEmptiness.K_PLUS, elementSchema);
    }

    public SeqPortSchema(ContainerEmptiness emptiness, PortSchema elementSchema) {
        this(Objects.requireNonNull(emptiness, "emptiness").admitsEmpty()
                ? ArityPolicy.zeroOrMore()
                : ArityPolicy.nonemptyVariadic(), elementSchema);
    }

    public SeqPortSchema(ArityPolicy arityPolicy, PortSchema elementSchema) {
        this.arityPolicy = Objects.requireNonNull(arityPolicy, "arityPolicy");
        this.elementSchema = Objects.requireNonNull(elementSchema, "elementSchema");
        this.positionalElementSchemas = List.of();
    }

    private SeqPortSchema(List<? extends PortSchema> positionalElementSchemas) {
        Objects.requireNonNull(positionalElementSchemas, "positionalElementSchemas");
        if (positionalElementSchemas.size() < 2) {
            throw new IllegalArgumentException(
                    "A dependent sequence requires at least two positions");
        }
        List<PortSchema> copied = new ArrayList<>();
        for (PortSchema schema : positionalElementSchemas) {
            copied.add(Objects.requireNonNull(schema, "positional element schema"));
        }
        this.arityPolicy = ArityPolicy.exact(copied.size());
        this.elementSchema = null;
        this.positionalElementSchemas = Collections.unmodifiableList(copied);
    }

    /** Ordered positional sequence whose element schema depends on its index. */
    public static SeqPortSchema dependent(
            List<? extends PortSchema> positionalElementSchemas) {
        return new SeqPortSchema(positionalElementSchemas);
    }

    public boolean isDependent() {
        return elementSchema == null;
    }

    public List<PortSchema> positionalElementSchemas() {
        return positionalElementSchemas;
    }

    public PortSchema schemaAt(int index) {
        if (index < 0 || (isDependent() && index >= positionalElementSchemas.size())) {
            throw new IndexOutOfBoundsException("sequence index " + index);
        }
        return isDependent() ? positionalElementSchemas.get(index) : elementSchema;
    }

    public ContainerEmptiness emptiness() {
        return arityPolicy.admitsZero()
                ? ContainerEmptiness.K_ZERO : ContainerEmptiness.K_PLUS;
    }

    public ArityPolicy arityPolicy() {
        return arityPolicy;
    }

    public SiblingQuotient siblingQuotient() {
        return SiblingQuotient.ORDERED_SEQUENCE;
    }

    public PortSchema elementSchema() {
        if (isDependent()) {
            throw new IllegalStateException(
                    "A dependent sequence has one schema per position");
        }
        return elementSchema;
    }

    @Override
    public Kind kind() {
        return Kind.SEQ;
    }

    @Override
    public Set<String> typeVariables() {
        if (!isDependent()) {
            return elementSchema.typeVariables();
        }
        Set<String> variables = new LinkedHashSet<>();
        for (PortSchema schema : positionalElementSchemas) {
            variables.addAll(schema.typeVariables());
        }
        return Collections.unmodifiableSet(variables);
    }

    @Override
    public SeqPortSchema substitute(Map<String, GraphType> substitution) {
        if (isDependent()) {
            return dependent(positionalElementSchemas.stream()
                    .map(schema -> schema.substitute(substitution)).toList());
        }
        return new SeqPortSchema(arityPolicy, elementSchema.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        List<StructuralKey> children = new ArrayList<>();
        children.add(arityPolicy.structuralKey());
        if (isDependent()) {
            children.addAll(positionalElementSchemas.stream()
                    .map(PortSchema::structuralKey).toList());
        } else {
            children.add(elementSchema.structuralKey());
        }
        return StructuralKey.of(
                isDependent() ? "schema/dependent-seq" : "schema/seq",
                Collections.singletonList(siblingQuotient().name()),
                children);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SeqPortSchema
                && arityPolicy.equals(((SeqPortSchema) other).arityPolicy)
                && Objects.equals(elementSchema, ((SeqPortSchema) other).elementSchema)
                && positionalElementSchemas.equals(
                        ((SeqPortSchema) other).positionalElementSchemas);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind(), arityPolicy, elementSchema, positionalElementSchemas);
    }

    @Override
    public String toString() {
        return isDependent()
                ? "Seq" + positionalElementSchemas
                : "Seq" + arityPolicy + "(" + elementSchema + ")";
    }
}
