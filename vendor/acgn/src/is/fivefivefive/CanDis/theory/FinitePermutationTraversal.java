package is.fivefivefive.CanDis.theory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

/** Deterministic subgroup traversal through a finite stabilizer chain. */
final class FinitePermutationTraversal {
    private static final long DEFAULT_MAX_ELEMENTS = 100_000L;

    private FinitePermutationTraversal() {
    }

    @FunctionalInterface
    interface CheckedConsumer<E extends Exception> {
        void accept(TypedPermutation permutation) throws E;
    }

    @FunctionalInterface
    private interface CheckedVisitor<E extends Exception> {
        boolean visit(TypedPermutation permutation) throws E;
    }

    static <E extends Exception> long forEach(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators,
            Function<TypedPermutation, StructuralKey> key,
            CheckedConsumer<E> consumer) throws E {
        Objects.requireNonNull(consumer, "consumer");
        TraversalPlan plan = TraversalPlan.create(context, generators, key);
        long[] count = {0L};
        traverse(plan.root, plan.identity, candidate -> {
            count[0] = Math.addExact(count[0], 1L);
            if (count[0] > plan.maximumElements) {
                throw new CanonicalizationDomainException(
                        "Permutation-group closure exceeds configured bound "
                                + plan.maximumElements);
            }
            consumer.accept(candidate);
            return true;
        });
        if (count[0] != plan.groupOrder) {
            throw new IllegalStateException(
                    "Stabilizer traversal did not emit its exact group order");
        }
        return count[0];
    }

    static long maximumElements() {
        long maximum = Long.getLong(
                "acgn.maxGroupElements", DEFAULT_MAX_ELEMENTS);
        if (maximum <= 0) {
            throw new IllegalStateException(
                    "acgn.maxGroupElements must be positive");
        }
        return maximum;
    }

    static boolean anyMatch(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators,
            Function<TypedPermutation, StructuralKey> key,
            Predicate<TypedPermutation> predicate) {
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(context, "context");
        TypedPermutation identity = TypedPermutation.identity(context);
        if (predicate.test(identity)) {
            return true;
        }
        TraversalPlan plan = TraversalPlan.create(context, generators, key);
        long[] count = {1L};
        boolean[] skippedIdentity = {false};
        return !traverse(plan.root, plan.identity, candidate -> {
            if (!skippedIdentity[0] && candidate.equals(identity)) {
                skippedIdentity[0] = true;
                return true;
            }
            count[0] = Math.addExact(count[0], 1L);
            if (count[0] > plan.maximumElements) {
                throw new CanonicalizationDomainException(
                        "Permutation-group search exceeds configured bound "
                                + plan.maximumElements);
            }
            return !predicate.test(candidate);
        });
    }

    static TraversalMetrics metrics(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators,
            Function<TypedPermutation, StructuralKey> key) {
        TraversalPlan plan = TraversalPlan.create(context, generators, key);
        return new TraversalMetrics(
                plan.groupOrder,
                plan.levelCount,
                plan.maximumOrbitWidth,
                plan.retainedTransversals,
                plan.retainedStrongGenerators);
    }

    private static <E extends Exception> boolean traverse(
            StabilizerLevel level,
            TypedPermutation identity,
            CheckedVisitor<E> visitor) throws E {
        if (level == null) {
            return visitor.visit(identity);
        }
        return traverse(level.stabilizer, identity, stabilizerElement -> {
            for (TypedPermutation representative : level.transversals) {
                if (!visitor.visit(stabilizerElement.andThen(representative))) {
                    return false;
                }
            }
            return true;
        });
    }

    private static final class TraversalPlan {
        private final TypedPermutation identity;
        private final StabilizerLevel root;
        private final long maximumElements;
        private final long groupOrder;
        private final int levelCount;
        private final int maximumOrbitWidth;
        private final int retainedTransversals;
        private final int retainedStrongGenerators;

        private TraversalPlan(
                TypedPermutation identity,
                StabilizerLevel root,
                long maximumElements) {
            this.identity = identity;
            this.root = root;
            this.maximumElements = maximumElements;
            this.groupOrder = root == null ? 1L : root.groupOrder;
            this.levelCount = root == null ? 0 : root.levelCount;
            this.maximumOrbitWidth = root == null ? 1 : root.maximumOrbitWidth;
            this.retainedTransversals = root == null ? 0 : root.retainedTransversals;
            this.retainedStrongGenerators = root == null
                    ? 0 : root.retainedStrongGenerators;
        }

        private static TraversalPlan create(
                TypedSlotContext context,
                List<? extends TypedPermutation> generators,
                Function<TypedPermutation, StructuralKey> key) {
            Objects.requireNonNull(context, "context");
            Objects.requireNonNull(generators, "generators");
            Objects.requireNonNull(key, "key");
            long maximum = maximumElements();
            TypedPermutation identity = TypedPermutation.identity(context);
            List<TypedPermutation> steps = normalizedSteps(
                    context, generators, key, identity);
            WorkBudget budget = new WorkBudget(maximum, context.size());
            StabilizerLevel root = buildLevel(
                    context,
                    new ArrayList<>(context.slots()),
                    steps,
                    key,
                    identity,
                    0,
                    maximum,
                    budget);
            return new TraversalPlan(identity, root, maximum);
        }
    }

    private static StabilizerLevel buildLevel(
            TypedSlotContext context,
            List<TypedSlot> slots,
            List<TypedPermutation> generators,
            Function<TypedPermutation, StructuralKey> key,
            TypedPermutation identity,
            int start,
            long maximum,
            WorkBudget budget) {
        int baseIndex = firstMovedSlot(slots, generators, start);
        if (baseIndex < 0) {
            return null;
        }
        TypedSlot base = slots.get(baseIndex);
        Map<TypedSlot, TypedPermutation> transversalByImage = new TreeMap<>();
        Deque<TypedSlot> pending = new ArrayDeque<>();
        transversalByImage.put(base, identity);
        pending.add(base);
        while (!pending.isEmpty()) {
            TypedSlot image = pending.removeFirst();
            TypedPermutation representative = transversalByImage.get(image);
            for (TypedPermutation step : generators) {
                budget.consume();
                TypedPermutation candidate = representative.andThen(step);
                TypedSlot candidateImage = candidate.apply(base);
                if (!transversalByImage.containsKey(candidateImage)) {
                    if (transversalByImage.size() >= context.size()) {
                        throw new IllegalStateException(
                                "A permutation orbit exceeds its finite slot context");
                    }
                    transversalByImage.put(candidateImage, candidate);
                    pending.addLast(candidateImage);
                }
            }
        }

        List<TypedPermutation> transversals = new ArrayList<>(
                transversalByImage.values());
        transversals.sort(Comparator.comparing(key));
        Map<StructuralKey, TypedPermutation> stabilizerGenerators = new TreeMap<>();
        for (TypedPermutation representative : transversals) {
            for (TypedPermutation step : generators) {
                budget.consume();
                TypedPermutation advanced = representative.andThen(step);
                TypedPermutation targetRepresentative = transversalByImage.get(
                        advanced.apply(base));
                if (targetRepresentative == null) {
                    throw new IllegalStateException(
                            "A Schreier target is absent from the completed orbit");
                }
                TypedPermutation schreier = advanced.andThen(
                        targetRepresentative.inverse());
                if (!schreier.apply(base).equals(base)) {
                    throw new IllegalStateException(
                            "A Schreier generator does not stabilize its base slot");
                }
                putUniqueNonidentity(
                        stabilizerGenerators, schreier, key, identity);
            }
        }
        List<TypedPermutation> nextGenerators = normalizedSteps(
                context,
                new ArrayList<>(stabilizerGenerators.values()),
                key,
                identity);
        StabilizerLevel stabilizer = buildLevel(
                context,
                slots,
                nextGenerators,
                key,
                identity,
                baseIndex + 1,
                maximum,
                budget);
        long stabilizerOrder = stabilizer == null ? 1L : stabilizer.groupOrder;
        long groupOrder;
        try {
            groupOrder = Math.multiplyExact(
                    transversals.size(), stabilizerOrder);
        } catch (ArithmeticException overflow) {
            throw new CanonicalizationDomainException(
                    "Permutation-group order exceeds the configured bound");
        }
        if (groupOrder > maximum) {
            throw new CanonicalizationDomainException(
                    "Permutation-group closure exceeds configured bound " + maximum);
        }
        return new StabilizerLevel(
                List.copyOf(transversals),
                stabilizer,
                groupOrder,
                nextGenerators.size());
    }

    private static int firstMovedSlot(
            List<TypedSlot> slots,
            List<TypedPermutation> generators,
            int start) {
        for (int index = start; index < slots.size(); index++) {
            TypedSlot slot = slots.get(index);
            for (TypedPermutation generator : generators) {
                if (!generator.apply(slot).equals(slot)) {
                    return index;
                }
            }
        }
        return -1;
    }

    private static List<TypedPermutation> normalizedSteps(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators,
            Function<TypedPermutation, StructuralKey> key,
            TypedPermutation identity) {
        Map<StructuralKey, TypedPermutation> result = new TreeMap<>();
        for (TypedPermutation value : generators) {
            TypedPermutation generator = Objects.requireNonNull(
                    value, "permutation generator");
            if (!context.equals(generator.source())
                    || !context.equals(generator.codomain())) {
                throw new IllegalArgumentException(
                        "Every generator must permute the declared context");
            }
            putUniqueNonidentity(result, generator, key, identity);
            putUniqueNonidentity(result, generator.inverse(), key, identity);
        }
        return List.copyOf(result.values());
    }

    private static void putUniqueNonidentity(
            Map<StructuralKey, TypedPermutation> target,
            TypedPermutation permutation,
            Function<TypedPermutation, StructuralKey> key,
            TypedPermutation identity) {
        if (identity.equals(permutation)) {
            return;
        }
        StructuralKey permutationKey = key.apply(permutation);
        TypedPermutation prior = target.putIfAbsent(permutationKey, permutation);
        if (prior != null && !prior.equals(permutation)) {
            throw new IllegalStateException(
                    "Permutation key collision between unequal actions");
        }
    }

    static final class TraversalMetrics {
        private final long groupOrder;
        private final int levelCount;
        private final int maximumOrbitWidth;
        private final int retainedTransversals;
        private final int retainedStrongGenerators;

        private TraversalMetrics(
                long groupOrder,
                int levelCount,
                int maximumOrbitWidth,
                int retainedTransversals,
                int retainedStrongGenerators) {
            this.groupOrder = groupOrder;
            this.levelCount = levelCount;
            this.maximumOrbitWidth = maximumOrbitWidth;
            this.retainedTransversals = retainedTransversals;
            this.retainedStrongGenerators = retainedStrongGenerators;
        }

        long groupOrder() {
            return groupOrder;
        }

        int levelCount() {
            return levelCount;
        }

        int maximumOrbitWidth() {
            return maximumOrbitWidth;
        }

        int retainedTransversals() {
            return retainedTransversals;
        }

        int retainedStrongGenerators() {
            return retainedStrongGenerators;
        }
    }

    private static final class StabilizerLevel {
        private final List<TypedPermutation> transversals;
        private final StabilizerLevel stabilizer;
        private final long groupOrder;
        private final int levelCount;
        private final int maximumOrbitWidth;
        private final int retainedTransversals;
        private final int retainedStrongGenerators;

        private StabilizerLevel(
                List<TypedPermutation> transversals,
                StabilizerLevel stabilizer,
                long groupOrder,
                int strongGenerators) {
            this.transversals = transversals;
            this.stabilizer = stabilizer;
            this.groupOrder = groupOrder;
            this.levelCount = 1 + (stabilizer == null ? 0 : stabilizer.levelCount);
            this.maximumOrbitWidth = Math.max(
                    transversals.size(),
                    stabilizer == null ? 1 : stabilizer.maximumOrbitWidth);
            this.retainedTransversals = Math.addExact(
                    transversals.size(),
                    stabilizer == null ? 0 : stabilizer.retainedTransversals);
            this.retainedStrongGenerators = Math.addExact(
                    strongGenerators,
                    stabilizer == null ? 0 : stabilizer.retainedStrongGenerators);
        }
    }

    private static final class WorkBudget {
        private final long maximum;
        private long consumed;

        private WorkBudget(long maximumElements, int contextSize) {
            long factor = Math.max(1L, contextSize);
            try {
                this.maximum = Math.multiplyExact(maximumElements, factor);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "Permutation traversal work bound overflows", overflow);
            }
        }

        private void consume() {
            consumed = Math.addExact(consumed, 1L);
            if (consumed > maximum) {
                throw new CanonicalizationDomainException(
                        "Stabilizer-chain construction exceeds configured work bound "
                                + maximum);
            }
        }
    }
}
