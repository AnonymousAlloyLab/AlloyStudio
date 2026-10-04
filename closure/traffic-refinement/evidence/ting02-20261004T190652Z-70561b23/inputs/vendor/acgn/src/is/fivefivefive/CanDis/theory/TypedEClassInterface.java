package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** Immutable view of an e-class identifier, output type, and exposed interface. */
public final class TypedEClassInterface {
    private final EClassId id;
    private final GraphType outputType;
    private final TypedSlotContext exposedSlots;

    public TypedEClassInterface(
            EClassId id,
            GraphType outputType,
            TypedSlotContext exposedSlots) {
        this.id = Objects.requireNonNull(id, "id");
        this.outputType = Objects.requireNonNull(outputType, "outputType");
        this.exposedSlots = Objects.requireNonNull(exposedSlots, "exposedSlots");
    }

    public EClassId id() {
        return id;
    }

    public GraphType outputType() {
        return outputType;
    }

    public TypedSlotContext exposedSlots() {
        return exposedSlots;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedEClassInterface)) {
            return false;
        }
        TypedEClassInterface eclass = (TypedEClassInterface) other;
        return id.equals(eclass.id)
                && outputType.equals(eclass.outputType)
                && exposedSlots.equals(eclass.exposedSlots);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, outputType, exposedSlots);
    }

    @Override
    public String toString() {
        return id + ":" + exposedSlots + " => " + outputType;
    }
}
