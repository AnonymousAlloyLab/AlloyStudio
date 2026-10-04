package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** The stored {@code (T_a,p,rho_a,p)} witness with all contexts kept explicit. */
public final class ShapeWitness {
    private final TypedSlotContext exactSlots;
    private final TypedSlotContext ambientSupport;
    private final TypedSlotContext exposedInterface;
    private final TypedRenaming instantiatingRenaming;
    private final StructuralKey structuralKey;

    public ShapeWitness(
            TypedSlotContext exactSlots,
            TypedSlotContext ambientSupport,
            TypedSlotContext exposedInterface,
            TypedRenaming instantiatingRenaming) {
        this.exactSlots = Objects.requireNonNull(exactSlots, "exactSlots");
        this.ambientSupport = Objects.requireNonNull(ambientSupport, "ambientSupport");
        this.exposedInterface = Objects.requireNonNull(exposedInterface, "exposedInterface");
        this.instantiatingRenaming = Objects.requireNonNull(
                instantiatingRenaming, "instantiatingRenaming");
        if (!exposedInterface.isSubcontextOf(ambientSupport)) {
            throw new IllegalArgumentException(
                    "The exposed e-class interface must be a subcontext of ambient support");
        }
        if (!exactSlots.equals(instantiatingRenaming.source())
                || !ambientSupport.equals(instantiatingRenaming.codomain())) {
            throw new IllegalArgumentException(
                    "The instantiating witness must rename exact shape slots onto ambient support");
        }
        this.structuralKey = StructuralKey.branch(
                "shape-witness",
                Arrays.asList(
                        TheoryKeys.context(exactSlots),
                        TheoryKeys.context(ambientSupport),
                        TheoryKeys.context(exposedInterface),
                        TheoryKeys.embedding(instantiatingRenaming)));
    }

    public TypedSlotContext exactSlots() {
        return exactSlots;
    }

    public TypedSlotContext ambientSupport() {
        return ambientSupport;
    }

    public TypedSlotContext exposedInterface() {
        return exposedInterface;
    }

    public TypedRenaming instantiatingRenaming() {
        return instantiatingRenaming;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ShapeWitness)) {
            return false;
        }
        ShapeWitness witness = (ShapeWitness) other;
        return exactSlots.equals(witness.exactSlots)
                && ambientSupport.equals(witness.ambientSupport)
                && exposedInterface.equals(witness.exposedInterface)
                && instantiatingRenaming.equals(witness.instantiatingRenaming);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                exactSlots, ambientSupport, exposedInterface, instantiatingRenaming);
    }

    @Override
    public String toString() {
        return "exact=" + exactSlots + ", ambient=" + ambientSupport
                + ", exposed=" + exposedInterface
                + ", rho=" + instantiatingRenaming.mapping();
    }
}
