package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** A well-typed e-node over the fixed canonical free and bound slot alphabets. */
public final class CanonicalShape implements Comparable<CanonicalShape> {
    private final TypedENode node;
    private final StructuralKey structuralKey;

    private CanonicalShape(TypedENode node) {
        this.node = Objects.requireNonNull(node, "node");
        if (!node.context().equals(node.support())) {
            throw new IllegalArgumentException(
                    "A canonical shape context must equal its exact free-slot support");
        }
        if (!node.context().equals(node.context().canonicalFreeContext())) {
            throw new IllegalArgumentException(
                    "A canonical shape must use the fixed canonical free-slot context");
        }
        for (PortValue port : node.ports()) {
            validateCanonicalPort(port, node.context(), TypedSlotContext.empty());
        }
        this.structuralKey = StructuralKey.branch(
                "canonical-shape", Collections.singletonList(node.structuralKey()));
    }

    public static CanonicalShape of(TypedENode node) {
        return new CanonicalShape(node);
    }

    private static void validateCanonicalPort(
            PortValue port,
            TypedSlotContext freeSlots,
            TypedSlotContext boundSlots) {
        TypedSlotContext expectedContext = freeSlots.union(boundSlots);
        if (!port.context().equals(expectedContext)) {
            throw new IllegalArgumentException(
                    "Canonical port context does not match its free and bound coordinates");
        }
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof SlotPortLeaf) {
                TypedSlot slot = ((SlotPortLeaf) leaf).slot();
                boolean canonicalFree = freeSlots.contains(slot)
                        && slot.alphabet() == SlotAlphabet.CANONICAL_FREE;
                boolean canonicalBound = boundSlots.contains(slot)
                        && slot.alphabet() == SlotAlphabet.CANONICAL_BOUND;
                if (!canonicalFree && !canonicalBound) {
                    throw new IllegalArgumentException(
                            "Canonical shape slot uses the wrong alphabet or scope");
                }
            }
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                validateCanonicalPort(element, freeSlots, boundSlots);
            }
            return;
        }
        if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                validateCanonicalPort(element, freeSlots, boundSlots);
            }
            return;
        }
        if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                validateCanonicalPort(element, freeSlots, boundSlots);
            }
            return;
        }
        if (port instanceof BindPort) {
            BindPort binder = (BindPort) port;
            TypedSlot expectedBound = CanonicalSlotAlphabet.fresh(
                    binder.schema().boundType(),
                    SlotAlphabet.CANONICAL_BOUND,
                    expectedContext);
            if (!expectedBound.equals(binder.boundSlot())) {
                throw new IllegalArgumentException(
                        "Canonical binder must use the fixed least fresh bound coordinate");
            }
            validateCanonicalPort(
                    binder.body(), freeSlots, boundSlots.plus(binder.boundSlot()));
            return;
        }
        if (port instanceof BindBlockPort) {
            BindBlockPort block = (BindBlockPort) port;
            TypedRenaming expectedOccurrence = block.schema().descriptor()
                    .freshOccurrenceRenaming(expectedContext);
            if (!expectedOccurrence.equals(block.descriptorToOccurrence())) {
                throw new IllegalArgumentException(
                        "Canonical binder block must use its fixed fresh occurrence context");
            }
            validateCanonicalPort(
                    block.body(), freeSlots, boundSlots.union(block.boundContext()));
            return;
        }
        throw new IllegalStateException("Unhandled port value " + port.getClass().getName());
    }

    public TypedENode node() {
        return node;
    }

    public GraphType outputType() {
        return node.outputType();
    }

    public TypedSlotContext exactSlots() {
        return node.support();
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public int compareTo(CanonicalShape other) {
        return structuralKey.compareTo(Objects.requireNonNull(other, "other").structuralKey);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalShape && node.equals(((CanonicalShape) other).node);
    }

    @Override
    public int hashCode() {
        return node.hashCode();
    }

    @Override
    public String toString() {
        return structuralKey.stableString();
    }
}
