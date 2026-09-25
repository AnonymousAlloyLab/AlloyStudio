package is.fivefivefive.CanDis.theory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Deterministic allocator for the canonical free and bound slot alphabets. */
public final class CanonicalSlotAlphabet {
    private CanonicalSlotAlphabet() {
    }

    public static TypedSlotContext canonicalContext(
            TypedSlotContext source,
            SlotAlphabet alphabet) {
        Objects.requireNonNull(source, "source");
        requireCanonical(alphabet);
        List<TypedSlot> slots = new ArrayList<>(source.size());
        for (Map.Entry<GraphType, Integer> entry : source.typeCounts().entrySet()) {
            for (int index = 0; index < entry.getValue(); index++) {
                slots.add(TypedSlot.of(entry.getKey(), alphabet, index));
            }
        }
        return TypedSlotContext.of(slots);
    }

    public static TypedSlot fresh(
            GraphType type,
            SlotAlphabet alphabet,
            TypedSlotContext occupied) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occupied, "occupied");
        requireCanonical(alphabet);
        BigInteger ordinal = BigInteger.ZERO;
        while (occupied.contains(TypedSlot.of(type, alphabet, ordinal))) {
            ordinal = ordinal.add(BigInteger.ONE);
        }
        return TypedSlot.of(type, alphabet, ordinal);
    }

    private static void requireCanonical(SlotAlphabet alphabet) {
        Objects.requireNonNull(alphabet, "alphabet");
        if (alphabet == SlotAlphabet.SOURCE) {
            throw new IllegalArgumentException("SOURCE is not a canonical slot alphabet");
        }
    }
}
