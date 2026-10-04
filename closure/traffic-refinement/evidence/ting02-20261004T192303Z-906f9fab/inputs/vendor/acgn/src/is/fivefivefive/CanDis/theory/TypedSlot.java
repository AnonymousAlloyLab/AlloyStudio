package is.fivefivefive.CanDis.theory;

import java.math.BigInteger;
import java.util.Objects;

/** A slot whose type and alphabet are part of its identity. */
public final class TypedSlot implements Comparable<TypedSlot> {
    private final GraphType type;
    private final SlotAlphabet alphabet;
    private final BigInteger ordinal;

    private TypedSlot(GraphType type, SlotAlphabet alphabet, BigInteger ordinal) {
        this.type = Objects.requireNonNull(type, "type");
        this.alphabet = Objects.requireNonNull(alphabet, "alphabet");
        this.ordinal = Objects.requireNonNull(ordinal, "ordinal");
        if (ordinal.signum() < 0) {
            throw new IllegalArgumentException("Slot ordinal must be non-negative");
        }
    }

    public static TypedSlot of(GraphType type, SlotAlphabet alphabet, long ordinal) {
        return of(type, alphabet, BigInteger.valueOf(ordinal));
    }

    public static TypedSlot of(GraphType type, SlotAlphabet alphabet, BigInteger ordinal) {
        return new TypedSlot(type, alphabet, ordinal);
    }

    public static TypedSlot source(GraphType type, long ordinal) {
        return of(type, SlotAlphabet.SOURCE, ordinal);
    }

    public static TypedSlot canonicalFree(GraphType type, long ordinal) {
        return of(type, SlotAlphabet.CANONICAL_FREE, ordinal);
    }

    public static TypedSlot canonicalBound(GraphType type, long ordinal) {
        return of(type, SlotAlphabet.CANONICAL_BOUND, ordinal);
    }

    public GraphType type() {
        return type;
    }

    public SlotAlphabet alphabet() {
        return alphabet;
    }

    public BigInteger ordinal() {
        return ordinal;
    }

    @Override
    public int compareTo(TypedSlot other) {
        Objects.requireNonNull(other, "other");
        int compared = type.compareTo(other.type);
        if (compared != 0) {
            return compared;
        }
        compared = Integer.compare(alphabet.ordinal(), other.alphabet.ordinal());
        return compared != 0 ? compared : ordinal.compareTo(other.ordinal);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedSlot)) {
            return false;
        }
        TypedSlot slot = (TypedSlot) other;
        return type.equals(slot.type)
                && alphabet == slot.alphabet
                && ordinal.equals(slot.ordinal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, alphabet, ordinal);
    }

    @Override
    public String toString() {
        String prefix;
        switch (alphabet) {
            case SOURCE:
                prefix = "$s";
                break;
            case CANONICAL_FREE:
                prefix = "$f";
                break;
            case CANONICAL_BOUND:
                prefix = "$b";
                break;
            default:
                throw new IllegalStateException("Unhandled alphabet " + alphabet);
        }
        return prefix + ordinal + ":" + type;
    }
}
