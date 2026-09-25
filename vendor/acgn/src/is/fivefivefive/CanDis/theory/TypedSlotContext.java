package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable finite typed slot context. */
public final class TypedSlotContext implements Iterable<TypedSlot>, Comparable<TypedSlotContext> {
    private static final TypedSlotContext EMPTY = new TypedSlotContext(new TreeSet<>());

    private final NavigableSet<TypedSlot> slots;

    private TypedSlotContext(NavigableSet<TypedSlot> trustedSlots) {
        this.slots = Collections.unmodifiableNavigableSet(trustedSlots);
    }

    public static TypedSlotContext empty() {
        return EMPTY;
    }

    public static TypedSlotContext singleton(TypedSlot slot) {
        return of(Collections.singleton(Objects.requireNonNull(slot, "slot")));
    }

    public static TypedSlotContext of(TypedSlot... slots) {
        Objects.requireNonNull(slots, "slots");
        List<TypedSlot> values = new ArrayList<>(slots.length);
        Collections.addAll(values, slots);
        return of(values);
    }

    public static TypedSlotContext of(Collection<TypedSlot> slots) {
        Objects.requireNonNull(slots, "slots");
        if (slots.isEmpty()) {
            return EMPTY;
        }
        NavigableSet<TypedSlot> copied = new TreeSet<>();
        for (TypedSlot slot : slots) {
            TypedSlot nonNull = Objects.requireNonNull(slot, "slot");
            if (!copied.add(nonNull)) {
                throw new IllegalArgumentException("Duplicate slot in context: " + nonNull);
            }
        }
        return new TypedSlotContext(copied);
    }

    private static TypedSlotContext fromSet(NavigableSet<TypedSlot> slots) {
        return slots.isEmpty() ? EMPTY : new TypedSlotContext(slots);
    }

    public int size() {
        return slots.size();
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public boolean contains(TypedSlot slot) {
        return slots.contains(slot);
    }

    public boolean containsAll(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        return slots.containsAll(other.slots);
    }

    public boolean isSubcontextOf(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        return other.containsAll(this);
    }

    public NavigableSet<TypedSlot> slots() {
        return slots;
    }

    public TypedSlotContext plus(TypedSlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (slots.contains(slot)) {
            return this;
        }
        NavigableSet<TypedSlot> result = new TreeSet<>(slots);
        result.add(slot);
        return fromSet(result);
    }

    public TypedSlotContext without(TypedSlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (!slots.contains(slot)) {
            return this;
        }
        NavigableSet<TypedSlot> result = new TreeSet<>(slots);
        result.remove(slot);
        return fromSet(result);
    }

    public TypedSlotContext minus(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        if (other.isEmpty()) {
            return this;
        }
        NavigableSet<TypedSlot> result = new TreeSet<>(slots);
        result.removeAll(other.slots);
        return result.size() == slots.size() ? this : fromSet(result);
    }

    public boolean isDisjoint(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        TypedSlotContext smaller = size() <= other.size() ? this : other;
        TypedSlotContext larger = smaller == this ? other : this;
        for (TypedSlot slot : smaller) {
            if (larger.contains(slot)) {
                return false;
            }
        }
        return true;
    }

    public TypedSlotContext union(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        if (other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        NavigableSet<TypedSlot> result = new TreeSet<>(slots);
        result.addAll(other.slots);
        return fromSet(result);
    }

    public List<TypedSlot> slotsOfType(GraphType type) {
        Objects.requireNonNull(type, "type");
        List<TypedSlot> result = new ArrayList<>();
        for (TypedSlot slot : slots) {
            if (slot.type().equals(type)) {
                result.add(slot);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public Map<GraphType, Integer> typeCounts() {
        Map<GraphType, Integer> counts = new TreeMap<>();
        for (TypedSlot slot : slots) {
            counts.put(slot.type(), counts.getOrDefault(slot.type(), 0) + 1);
        }
        return Collections.unmodifiableMap(counts);
    }

    public TypedSlotContext canonicalFreeContext() {
        return CanonicalSlotAlphabet.canonicalContext(this, SlotAlphabet.CANONICAL_FREE);
    }

    public TypedSlotContext canonicalBoundContext() {
        return CanonicalSlotAlphabet.canonicalContext(this, SlotAlphabet.CANONICAL_BOUND);
    }

    @Override
    public Iterator<TypedSlot> iterator() {
        return slots.iterator();
    }

    @Override
    public int compareTo(TypedSlotContext other) {
        Objects.requireNonNull(other, "other");
        Iterator<TypedSlot> left = iterator();
        Iterator<TypedSlot> right = other.iterator();
        while (left.hasNext() && right.hasNext()) {
            int compared = left.next().compareTo(right.next());
            if (compared != 0) {
                return compared;
            }
        }
        return Integer.compare(size(), other.size());
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TypedSlotContext
                && slots.equals(((TypedSlotContext) other).slots);
    }

    @Override
    public int hashCode() {
        return slots.hashCode();
    }

    @Override
    public String toString() {
        return slots.toString();
    }
}
