package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** Stable identity of one stored parent record in the Phase G dirty queue. */
public final class ParentRecordKey implements Comparable<ParentRecordKey> {
    private final EClassId owner;
    private final CanonicalShape shape;
    private final StructuralKey structuralKey;

    public ParentRecordKey(EClassId owner, CanonicalShape shape) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.structuralKey = StructuralKey.branch(
                "parent-record-key",
                java.util.Arrays.asList(
                        StructuralKey.leaf("owner", Long.toString(owner.value())),
                        shape.structuralKey()));
    }

    public EClassId owner() {
        return owner;
    }

    public CanonicalShape shape() {
        return shape;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public int compareTo(ParentRecordKey other) {
        Objects.requireNonNull(other, "other");
        int compared = owner.compareTo(other.owner);
        return compared != 0 ? compared : shape.compareTo(other.shape);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ParentRecordKey
                && owner.equals(((ParentRecordKey) other).owner)
                && shape.equals(((ParentRecordKey) other).shape);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, shape);
    }

    @Override
    public String toString() {
        return owner + ":" + shape.structuralKey().stableString();
    }
}
