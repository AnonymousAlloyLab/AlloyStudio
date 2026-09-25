package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Deterministic bounded streaming enumeration of typed context bijections. */
final class TypedRenamingEnumerator {
    private static final long DEFAULT_MAX_RENAMINGS = 100_000L;
    private static final int DEFAULT_MAX_RENAMING_DEPTH = 512;

    private TypedRenamingEnumerator() {
    }

    @FunctionalInterface
    interface CheckedConsumer<E extends Exception> {
        void accept(TypedRenaming renaming) throws E;
    }

    static void forEach(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Consumer<TypedRenaming> consumer) {
        forEachChecked(source, codomain, consumer::accept);
    }

    static <E extends Exception> long forEachChecked(
            TypedSlotContext source,
            TypedSlotContext codomain,
            CheckedConsumer<E> consumer) throws E {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(codomain, "codomain");
        Objects.requireNonNull(consumer, "consumer");
        if (!source.typeCounts().equals(codomain.typeCounts())) {
            return 0L;
        }
        List<TypedSlot> domain = new ArrayList<>(source.slots());
        List<TypedSlot> targets = new ArrayList<>(codomain.slots());
        if (domain.size() > maximumRenamingDepth()) {
            throw new CanonicalizationDomainException(
                    "Typed-renaming depth exceeds configured bound "
                            + maximumRenamingDepth());
        }
        long[] emitted = {0L};
        enumerate(
                source,
                codomain,
                domain,
                targets,
                0,
                new boolean[targets.size()],
                new LinkedHashMap<>(),
                emitted,
                consumer);
        return emitted[0];
    }

    private static <E extends Exception> void enumerate(
            TypedSlotContext source,
            TypedSlotContext codomain,
            List<TypedSlot> domain,
            List<TypedSlot> targets,
            int index,
            boolean[] used,
            Map<TypedSlot, TypedSlot> mapping,
            long[] emitted,
            CheckedConsumer<E> consumer) throws E {
        if (index == domain.size()) {
            emitted[0] = Math.addExact(emitted[0], 1L);
            if (emitted[0] > maximumRenamings()) {
                throw new CanonicalizationDomainException(
                        "Typed-renaming orbit exceeds configured bound "
                                + maximumRenamings());
            }
            consumer.accept(TypedRenaming.of(source, codomain, mapping));
            return;
        }
        TypedSlot slot = domain.get(index);
        for (int targetIndex = 0; targetIndex < targets.size(); targetIndex++) {
            TypedSlot target = targets.get(targetIndex);
            if (used[targetIndex] || !slot.type().equals(target.type())) {
                continue;
            }
            used[targetIndex] = true;
            mapping.put(slot, target);
            enumerate(
                    source,
                    codomain,
                    domain,
                    targets,
                    index + 1,
                    used,
                    mapping,
                    emitted,
                    consumer);
            mapping.remove(slot);
            used[targetIndex] = false;
        }
    }

    static long maximumRenamings() {
        long maximum = Long.getLong(
                "acgn.maxGlobalRenamings", DEFAULT_MAX_RENAMINGS);
        if (maximum <= 0) {
            throw new IllegalStateException(
                    "acgn.maxGlobalRenamings must be positive");
        }
        return maximum;
    }

    private static int maximumRenamingDepth() {
        int maximum = Integer.getInteger(
                "acgn.maxCanonicalRecursionDepth", DEFAULT_MAX_RENAMING_DEPTH);
        if (maximum <= 0) {
            throw new IllegalStateException(
                    "acgn.maxCanonicalRecursionDepth must be positive");
        }
        return maximum;
    }
}
