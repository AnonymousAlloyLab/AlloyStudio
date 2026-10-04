package is.fivefivefive.CanDis.theory;

/** Strongly typed e-class identifier for the theory-faithful graph path. */
public final class EClassId implements Comparable<EClassId> {
    private final long value;

    private EClassId(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("E-class identifier must be non-negative");
        }
        this.value = value;
    }

    public static EClassId of(long value) {
        return new EClassId(value);
    }

    public long value() {
        return value;
    }

    @Override
    public int compareTo(EClassId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof EClassId && value == ((EClassId) other).value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "e" + value;
    }
}
