package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Schema {@code Set^epsilon(kappa)}. */
public final class SetPortSchema implements PortSchema {
    private final ArityPolicy arityPolicy;
    private final PortSchema elementSchema;

    /** Shorthand for the nonempty schema {@code Set+(kappa)}. */
    public SetPortSchema(PortSchema elementSchema) {
        this(ContainerEmptiness.K_PLUS, elementSchema);
    }

    public SetPortSchema(ContainerEmptiness emptiness, PortSchema elementSchema) {
        this(Objects.requireNonNull(emptiness, "emptiness").admitsEmpty()
                ? ArityPolicy.zeroOrMore()
                : ArityPolicy.nonemptyVariadic(), elementSchema);
    }

    public SetPortSchema(ArityPolicy arityPolicy, PortSchema elementSchema) {
        this.arityPolicy = Objects.requireNonNull(arityPolicy, "arityPolicy");
        this.arityPolicy.requirePositiveDownwardClosure("A set port");
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
        return SiblingQuotient.COMMUTATIVE_IDEMPOTENT_SET;
    }

    public PortSchema elementSchema() {
        return elementSchema;
    }

    @Override
    public Kind kind() {
        return Kind.SET;
    }

    @Override
    public Set<String> typeVariables() {
        return elementSchema.typeVariables();
    }

    @Override
    public SetPortSchema substitute(Map<String, GraphType> substitution) {
        return new SetPortSchema(arityPolicy, elementSchema.substitute(substitution));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.of(
                "schema/set",
                Collections.singletonList(siblingQuotient().name()),
                java.util.Arrays.asList(
                        arityPolicy.structuralKey(), elementSchema.structuralKey()));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SetPortSchema
                && arityPolicy.equals(((SetPortSchema) other).arityPolicy)
                && elementSchema.equals(((SetPortSchema) other).elementSchema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind(), arityPolicy, elementSchema);
    }

    @Override
    public String toString() {
        return "Set" + arityPolicy + "(" + elementSchema + ")";
    }
}
