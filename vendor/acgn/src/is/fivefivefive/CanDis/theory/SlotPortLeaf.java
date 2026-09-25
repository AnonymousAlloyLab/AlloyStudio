package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Slot summand of {@code Port_Gamma(One(tau))}. */
public final class SlotPortLeaf implements PortLeaf {
    private final TypedSlot slot;

    public SlotPortLeaf(TypedSlot slot) {
        this.slot = Objects.requireNonNull(slot, "slot");
    }

    public TypedSlot slot() {
        return slot;
    }

    @Override
    public GraphType type() {
        return slot.type();
    }

    @Override
    public TypedSlotContext support() {
        return TypedSlotContext.singleton(slot);
    }

    @Override
    public SlotPortLeaf act(TypedEmbedding embedding) {
        return new SlotPortLeaf(Objects.requireNonNull(embedding, "embedding").apply(slot));
    }

    @Override
    public StructuralKey structuralKey() {
        return StructuralKey.branch("port-leaf/slot", Collections.singletonList(TheoryKeys.slot(slot)));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SlotPortLeaf && slot.equals(((SlotPortLeaf) other).slot);
    }

    @Override
    public int hashCode() {
        return slot.hashCode();
    }

    @Override
    public String toString() {
        return slot.toString();
    }
}
