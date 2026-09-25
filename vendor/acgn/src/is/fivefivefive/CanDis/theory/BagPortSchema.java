package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Schema {@code Bag^epsilon(kappa)}. */
public final class BagPortSchema implements PortSchema {
    private final ArityPolicy arityPolicy;
    private final PortSchema elementSchema;

    /** Shorthand for the nonempty schema {@code Bag+(kappa)}. */
    public BagPortSchema(PortSchema elementSchema) {
        this(ContainerEmptiness.K_PLUS, elementSchema);
    }

    public BagPortSchema(ContainerEmptiness emptiness, PortSchema elementSchema) {
        this(Objects.requireNonNull(emptiness, "emptiness").admitsEmpty()
                ? ArityPolicy.zeroOrMore()
                : ArityPolicy.nonemptyVariadic(), elementSchema);
    }

    public BagPortSchema(ArityPolicy arityPolicy, PortSchema elementSchema) {
        this.arityPolicy = Objects.requireNonNull(arityPolicy, "arityPolicy");
        this.elementSchema = Objects.requireNonNull(elementSchema, "elementSchema");
    }

    public ContainerEmptiness emptiness() {
        return arityPolicy.admitsZero()
                ? ContainerEmptiness.K_ZERO : ContainerEmptiness.K_PLUS;
    }

    public ArityPolicy arityPolicy() {
        return arityPolicy;
    }

    public SiblingQuotient siblingQuotient() {
        return SiblingQuotient.COMMUTATIVE_BAG;
    }

    public PortSchema elementSchema() {
        return elementSchema;
    }

    @Override
    public Kind kind() {
        return Kind.BAG;
    }

    @Override
    public Set<String> typeVariables() {
        return elementSchema.typeVariables();
    }

    @Override
    public BagPortSchema substitute(Map<String, GraphType> substitution) {
        return new BagPortSchema(arityPolicy, elementSchema.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.of(
                "schema/bag",
                Collections.singletonList(siblingQuotient().name()),
                java.util.Arrays.asList(
                        arityPolicy.structuralKey(), elementSchema.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BagPortSchema
                && arityPolicy.equals(((BagPortSchema) other).arityPolicy)
                && elementSchema.equals(((BagPortSchema) other).elementSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind(), arityPolicy, elementSchema);
    }

    @Override
    public String toString() {
        return "Bag" + arityPolicy + "(" + elementSchema + ")";
    }
}
