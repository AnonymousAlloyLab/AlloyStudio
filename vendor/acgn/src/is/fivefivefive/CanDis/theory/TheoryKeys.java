package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Complete structural keys shared by the Phase B-D carrier values. */
final class TheoryKeys {
    private TheoryKeys() {
    }

    static StructuralKey type(GraphType type) {
        List<StructuralKey> arguments = new ArrayList<>();
        for (GraphType argument : type.arguments()) {
            arguments.add(type(argument));
        }
        return StructuralKey.of(
                "type/" + type.kind().name(),
                type.symbol() == null
                        ? Collections.emptyList()
                        : Collections.singletonList(type.symbol()),
                arguments);
    }

    static StructuralKey slot(TypedSlot slot) {
        return StructuralKey.of(
                "slot",
                Arrays.asList(slot.alphabet().name(), slot.ordinal().toString()),
                Collections.singletonList(type(slot.type())));
    }

    static StructuralKey context(TypedSlotContext context) {
        List<StructuralKey> slots = new ArrayList<>(context.size());
        for (TypedSlot slot : context) {
            slots.add(slot(slot));
        }
        return StructuralKey.branch("context", slots);
    }

    static StructuralKey embedding(TypedEmbedding embedding) {
        List<StructuralKey> parts = new ArrayList<>();
        parts.add(context(embedding.source()));
        parts.add(context(embedding.codomain()));
        for (Map.Entry<TypedSlot, TypedSlot> entry : embedding.mapping().entrySet()) {
            parts.add(StructuralKey.branch(
                    "map-entry",
                    Arrays.asList(slot(entry.getKey()), slot(entry.getValue()))));
        }
        return StructuralKey.branch("embedding", parts);
    }

    /**
     * Declared canonical-witness order shared with the wire verifier. Slot
     * names are source identity, so sorting and comparing the complete mapping
     * as text is deterministic without relying on content-hash order.
     */
    static StructuralKey witnessOrder(TypedEmbedding embedding) {
        List<TypedSlot> sources = new ArrayList<>(embedding.source().slots());
        sources.sort(java.util.Comparator.comparing(TypedSlot::toString));
        List<String> mapping = new ArrayList<>(sources.size() * 2);
        for (TypedSlot source : sources) {
            mapping.add(source.toString());
            mapping.add(embedding.apply(source).toString());
        }
        return StructuralKey.of(
                "canonical-witness-order-v1",
                mapping,
                Arrays.asList(
                        context(embedding.source()),
                        context(embedding.codomain())));
    }

    /** Compact action key for a permutation whose context is stored by its owner. */
    static StructuralKey permutationAction(
            TypedSlotContext context,
            TypedPermutation permutation) {
        if (!context.equals(permutation.source())
                || !context.equals(permutation.codomain())) {
            throw new IllegalArgumentException(
                    "A compact permutation action requires its owning context");
        }
        List<TypedSlot> slots = new ArrayList<>(context.slots());
        Map<TypedSlot, Integer> indices = new java.util.HashMap<>();
        for (int index = 0; index < slots.size(); index++) {
            indices.put(slots.get(index), index);
        }
        List<String> image = new ArrayList<>(slots.size());
        for (TypedSlot slot : slots) {
            image.add(Integer.toString(indices.get(permutation.apply(slot))));
        }
        return StructuralKey.of(
                "permutation-action", image, Collections.emptyList());
    }

    static StructuralKey eclass(TypedEClassInterface eclass) {
        return StructuralKey.of(
                "eclass",
                Collections.singletonList(Long.toString(eclass.id().value())),
                Arrays.asList(type(eclass.outputType()), context(eclass.exposedSlots())));
    }

    static StructuralKey invocation(TypedInvocation invocation) {
        return StructuralKey.branch(
                "invocation",
                Arrays.asList(eclass(invocation.eclass()), embedding(invocation.embedding())));
    }
}
