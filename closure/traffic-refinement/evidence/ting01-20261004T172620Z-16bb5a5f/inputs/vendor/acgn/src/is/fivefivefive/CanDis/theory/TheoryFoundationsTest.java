package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic Phase B algebra, property, and adversarial conformance tests. */
public final class TheoryFoundationsTest {
    private static final long SEED = 555_202_608_16L;
    private static int checks;

    private TheoryFoundationsTest() {
    }

    public static void main(String[] args) {
        testTypeGrammarAndSlots();
        testFiniteContextsAndCanonicalAlphabets();
        testEmbeddingPredicatesAndRejections();
        testRenamingAndPermutationGroupoid();
        testEmbeddingComposition();
        testInvocationAndSupport();
        testGeneratedEmbeddingProperties();
        testExhaustiveSmallPermutations();
        testOnlyRenamingsExposeInverse();
        System.out.println("TheoryFoundationsTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testTypeGrammarAndSlots() {
        GraphType alpha = GraphType.typeVariable("a");
        GraphType user = GraphType.constructor("User");
        GraphType project = GraphType.constructor("Project");
        GraphType relation = GraphType.relation(user, project);
        GraphType arrow = GraphType.arrow(GraphType.INT, relation);
        GraphType container = GraphType.constructor("Option", arrow);

        check(alpha.equals(GraphType.typeVariable("a")), "Type variables are structural");
        check(!alpha.equals(GraphType.typeVariable("b")), "Distinct type variables differ");
        check(relation.equals(GraphType.relation(user, project)), "Relations are structural");
        check(!relation.equals(GraphType.relation(project, user)), "Relation column order matters");
        check(container.toString().contains("Option"), "Constructed type has a stable rendering");
        check(GraphType.INT.compareTo(GraphType.BOOL) != 0, "Primitive types have a total order");
        expectThrows(IllegalArgumentException.class, () -> GraphType.relation(Collections.emptyList()));
        expectThrows(IllegalArgumentException.class, () -> GraphType.constructor("  "));

        TypedSlot intSource = TypedSlot.source(GraphType.INT, 0);
        TypedSlot boolSource = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot free = TypedSlot.canonicalFree(GraphType.INT, 0);
        TypedSlot bound = TypedSlot.canonicalBound(GraphType.INT, 0);
        check(!intSource.equals(boolSource), "Slot type participates in identity");
        check(!intSource.equals(free), "Source and canonical slots are disjoint");
        check(!free.equals(bound), "Canonical free and bound alphabets are disjoint");
        check(TypedSlot.of(GraphType.INT, SlotAlphabet.SOURCE, BigInteger.TEN)
                .ordinal().equals(BigInteger.TEN), "Slot ordinals are unbounded values");
        expectThrows(IllegalArgumentException.class,
                () -> TypedSlot.of(GraphType.INT, SlotAlphabet.SOURCE, -1));
    }

    private static void testFiniteContextsAndCanonicalAlphabets() {
        GraphType user = GraphType.constructor("User");
        TypedSlot i0 = TypedSlot.source(GraphType.INT, 0);
        TypedSlot i1 = TypedSlot.source(GraphType.INT, 1);
        TypedSlot b0 = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot u0 = TypedSlot.source(user, 0);
        TypedSlotContext context = TypedSlotContext.of(u0, i1, b0, i0);
        TypedSlotContext reordered = TypedSlotContext.of(i0, b0, u0, i1);

        check(context.equals(reordered), "Context equality is independent of insertion order");
        check(context.size() == 4, "Context stores each slot once");
        check(context.typeCounts().get(GraphType.INT) == 2, "Context counts slots by type");
        check(context.slotsOfType(GraphType.INT).size() == 2, "Typed projection is complete");
        expectThrows(IllegalArgumentException.class, () -> TypedSlotContext.of(i0, i0));
        expectThrows(UnsupportedOperationException.class,
                () -> context.slots().add(TypedSlot.source(GraphType.INT, 9)));

        TypedSlotContext free = context.canonicalFreeContext();
        TypedSlotContext bound = context.canonicalBoundContext();
        check(free.typeCounts().equals(context.typeCounts()),
                "Canonical free context preserves every per-type cardinality");
        check(bound.typeCounts().equals(context.typeCounts()),
                "Canonical bound context preserves every per-type cardinality");
        check(Collections.disjoint(free.slots(), bound.slots()),
                "Canonical free and bound contexts are disjoint");
        check(free.contains(TypedSlot.canonicalFree(GraphType.INT, 0))
                        && free.contains(TypedSlot.canonicalFree(GraphType.INT, 1)),
                "Canonical context uses the first slots of each type");

        TypedSlotContext occupied = TypedSlotContext.of(
                TypedSlot.canonicalFree(GraphType.INT, 0),
                TypedSlot.canonicalFree(GraphType.INT, 2));
        check(CanonicalSlotAlphabet.fresh(
                        GraphType.INT, SlotAlphabet.CANONICAL_FREE, occupied)
                        .equals(TypedSlot.canonicalFree(GraphType.INT, 1)),
                "Fresh-slot policy chooses the least available ordinal");
        expectThrows(IllegalArgumentException.class,
                () -> CanonicalSlotAlphabet.canonicalContext(context, SlotAlphabet.SOURCE));
    }

    private static void testEmbeddingPredicatesAndRejections() {
        TypedSlot s0 = TypedSlot.source(GraphType.INT, 0);
        TypedSlot s1 = TypedSlot.source(GraphType.INT, 1);
        TypedSlot sb = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot t0 = TypedSlot.source(GraphType.INT, 10);
        TypedSlot t1 = TypedSlot.source(GraphType.INT, 11);
        TypedSlot t2 = TypedSlot.source(GraphType.INT, 12);
        TypedSlot tb = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlotContext source = TypedSlotContext.of(s0, s1, sb);
        TypedSlotContext target = TypedSlotContext.of(t0, t1, t2, tb);
        Map<TypedSlot, TypedSlot> map = mapOf(s0, t1, s1, t0, sb, tb);
        TypedEmbedding embedding = TypedEmbedding.of(source, target, map);

        check(embedding.isTypePreserving(), "Valid embedding preserves types");
        check(embedding.isInjective(), "Valid embedding is injective");
        check(!embedding.isOntoDeclaredCodomain(), "Proper embedding is not onto");
        check(!embedding.isRenaming(), "Proper embedding is not mislabeled as a renaming");
        check(!embedding.isPermutation(), "Proper embedding is not a permutation");
        check(embedding.image().equals(TypedSlotContext.of(t0, t1, tb)),
                "Embedding image is exact");
        check(TypedEmbedding.isTypePreserving(source, target, map),
                "Executable type-preservation predicate accepts a valid map");
        check(TypedEmbedding.isInjective(source, map),
                "Executable injection predicate accepts a valid map");
        check(!TypedEmbedding.isOntoDeclaredCodomain(target, map),
                "Executable surjectivity predicate detects weakening");
        check(!TypedEmbedding.isRenaming(source, target, map),
                "Executable renaming predicate rejects a proper embedding");
        expectThrows(IllegalStateException.class, embedding::asRenaming);
        expectThrows(UnsupportedOperationException.class,
                () -> embedding.mapping().put(s0, t0));
        expectThrows(IllegalArgumentException.class,
                () -> embedding.apply(TypedSlot.source(GraphType.INT, 99)));

        Map<TypedSlot, TypedSlot> missing = mapOf(s0, t0, sb, tb);
        expectThrows(IllegalArgumentException.class,
                () -> TypedEmbedding.of(source, target, missing));

        Map<TypedSlot, TypedSlot> duplicate = mapOf(s0, t0, s1, t0, sb, tb);
        check(!TypedEmbedding.isInjective(source, duplicate),
                "Executable injection predicate detects duplicate images");
        expectThrows(IllegalArgumentException.class,
                () -> TypedEmbedding.of(source, target, duplicate));

        Map<TypedSlot, TypedSlot> crossType = mapOf(s0, tb, s1, t0, sb, t1);
        check(!TypedEmbedding.isTypePreserving(source, target, crossType),
                "Executable type predicate detects cross-type mappings");
        expectThrows(IllegalArgumentException.class,
                () -> TypedEmbedding.of(source, target, crossType));

        TypedSlot outside = TypedSlot.source(GraphType.INT, 100);
        Map<TypedSlot, TypedSlot> outsideCodomain = mapOf(s0, outside, s1, t0, sb, tb);
        expectThrows(IllegalArgumentException.class,
                () -> TypedEmbedding.of(source, target, outsideCodomain));

        Map<TypedSlot, TypedSlot> extraKey = new LinkedHashMap<>(map);
        extraKey.put(TypedSlot.source(GraphType.INT, 5), t2);
        expectThrows(IllegalArgumentException.class,
                () -> TypedEmbedding.of(source, target, extraKey));
    }

    private static void testRenamingAndPermutationGroupoid() {
        TypedSlot s0 = TypedSlot.source(GraphType.INT, 0);
        TypedSlot s1 = TypedSlot.source(GraphType.INT, 1);
        TypedSlot sb = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot t0 = TypedSlot.source(GraphType.INT, 10);
        TypedSlot t1 = TypedSlot.source(GraphType.INT, 11);
        TypedSlot tb = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlotContext source = TypedSlotContext.of(s0, s1, sb);
        TypedSlotContext target = TypedSlotContext.of(t0, t1, tb);
        TypedRenaming renaming = TypedRenaming.of(
                source, target, mapOf(s0, t1, s1, t0, sb, tb));

        check(renaming.isRenaming(), "Onto typed embedding is a renaming");
        check(TypedEmbedding.isRenaming(source, target, renaming.mapping()),
                "Executable renaming predicate accepts a typed bijection");
        check(!renaming.isPermutation(), "Different contexts do not form a permutation");
        check(renaming.andThen(renaming.inverse()).equals(TypedRenaming.identity(source)),
                "Renaming followed by inverse is source identity");
        check(renaming.inverse().andThen(renaming).equals(TypedRenaming.identity(target)),
                "Inverse followed by renaming is target identity");
        expectThrows(IllegalStateException.class, renaming::asPermutation);

        TypedPermutation swap = TypedPermutation.of(
                source, mapOf(s0, s1, s1, s0, sb, sb));
        check(swap.isPermutation(), "Same-context typed bijection is a permutation");
        check(TypedEmbedding.isPermutation(source, swap.mapping()),
                "Executable permutation predicate accepts a typed automorphism");
        check(swap.inverse().equals(swap), "A transposition is self-inverse");
        check(swap.andThen(swap).equals(TypedPermutation.identity(source)),
                "Permutation composition retains the subtype and group identity");

        TypedSlot extra = TypedSlot.source(GraphType.INT, 12);
        TypedSlotContext largerTarget = target.plus(extra);
        expectThrows(IllegalArgumentException.class,
                () -> TypedRenaming.of(source, largerTarget,
                        mapOf(s0, t0, s1, t1, sb, tb)));
    }

    private static void testEmbeddingComposition() {
        TypedSlot s = TypedSlot.source(GraphType.INT, 0);
        TypedSlot t = TypedSlot.source(GraphType.INT, 10);
        TypedSlot tx = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlot u = TypedSlot.source(GraphType.INT, 20);
        TypedSlot ux = TypedSlot.source(GraphType.BOOL, 20);
        TypedSlot v = TypedSlot.source(GraphType.INT, 30);
        TypedSlot vx = TypedSlot.source(GraphType.BOOL, 30);
        TypedSlotContext source = TypedSlotContext.singleton(s);
        TypedSlotContext middle = TypedSlotContext.of(t, tx);
        TypedSlotContext next = TypedSlotContext.of(u, ux);
        TypedSlotContext last = TypedSlotContext.of(v, vx);
        TypedEmbedding first = TypedEmbedding.of(source, middle, mapOf(s, t));
        TypedRenaming second = TypedRenaming.of(middle, next, mapOf(t, u, tx, ux));
        TypedRenaming third = TypedRenaming.of(next, last, mapOf(u, v, ux, vx));

        TypedEmbedding composed = TypedEmbedding.compose(second, first);
        check(composed.source().equals(source) && composed.codomain().equals(next),
                "Composition preserves declared endpoints");
        check(composed.apply(s).equals(u), "Composition direction is e o m");
        check(TypedEmbedding.identity(source).andThen(first).equals(first),
                "Left identity holds");
        check(first.andThen(TypedEmbedding.identity(middle)).equals(first),
                "Right identity holds");
        check(first.andThen(second).andThen(third)
                        .equals(first.andThen(second.andThen(third))),
                "Embedding composition is associative");
        expectThrows(IllegalArgumentException.class, () -> first.andThen(third));
    }

    private static void testInvocationAndSupport() {
        GraphType user = GraphType.constructor("User");
        GraphType output = GraphType.relation(user);
        TypedSlot x = TypedSlot.source(user, 0);
        TypedSlot y = TypedSlot.source(user, 1);
        TypedSlot z = TypedSlot.source(user, 2);
        TypedSlot spare = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlotContext interfaceSlots = TypedSlotContext.of(x, y);
        TypedSlotContext caller = TypedSlotContext.of(y, z, spare);
        TypedEClassInterface eclass = new TypedEClassInterface(
                EClassId.of(7), output, interfaceSlots);
        TypedEmbedding invocationMap = TypedEmbedding.of(
                interfaceSlots, caller, mapOf(x, z, y, y));
        TypedInvocation invocation = new TypedInvocation(eclass, invocationMap);

        check(invocation.outputType().equals(output), "Invocation output comes from its e-class");
        check(invocation.callerContext().equals(caller), "Invocation records caller context");
        check(invocation.support().equals(TypedSlotContext.of(y, z)),
                "Invocation support is exactly the embedding image");
        check(TypedInvocation.identity(eclass).support().equals(interfaceSlots),
                "Identity invocation exposes the complete interface");

        TypedSlot y2 = TypedSlot.source(user, 11);
        TypedSlot z2 = TypedSlot.source(user, 12);
        TypedSlot spare2 = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlot extra = TypedSlot.source(GraphType.INT, 10);
        TypedSlotContext nextCaller = TypedSlotContext.of(y2, z2, spare2, extra);
        TypedEmbedding action = TypedEmbedding.of(
                caller, nextCaller, mapOf(y, y2, z, z2, spare, spare2));
        TypedInvocation acted = invocation.act(action);
        check(acted.outputType().equals(output), "Invocation action preserves output type");
        check(acted.embedding().apply(x).equals(z2)
                        && acted.embedding().apply(y).equals(y2),
                "Invocation action composes caller embedding after invocation embedding");
        check(acted.support().equals(action.imageOf(invocation.support())),
                "Invocation support is equivariant under embedding action");

        check(SlotSupport.slot(x).equals(TypedSlotContext.singleton(x)),
                "Slot support is a singleton");
        TypedEClassInterface spareClass = new TypedEClassInterface(
                EClassId.of(9), GraphType.BOOL, TypedSlotContext.singleton(spare));
        TypedInvocation spareInvocation = TypedInvocation.identity(spareClass);
        List<HasSlotSupport> values = Arrays.asList(invocation, spareInvocation);
        check(SlotSupport.union(values).equals(TypedSlotContext.of(y, z, spare)),
                "Container support is union");
        check(SlotSupport.bind(y, TypedSlotContext.of(y, z)).equals(TypedSlotContext.of(z)),
                "Binder support removes its bound coordinate");

        TypedEClassInterface wrongInterface = new TypedEClassInterface(
                EClassId.of(8), output, TypedSlotContext.singleton(x));
        expectThrows(IllegalArgumentException.class,
                () -> new TypedInvocation(wrongInterface, invocationMap));
        expectThrows(IllegalArgumentException.class,
                () -> invocation.act(TypedEmbedding.identity(interfaceSlots)));
    }

    private static void testGeneratedEmbeddingProperties() {
        Random random = new Random(SEED);
        GraphType user = GraphType.constructor("GeneratedUser");
        List<GraphType> types = Arrays.asList(GraphType.INT, GraphType.BOOL, user);
        for (int round = 0; round < 128; round++) {
            List<TypedSlot> sourceSlots = new ArrayList<>();
            int sourceSize = random.nextInt(9);
            for (int i = 0; i < sourceSize; i++) {
                sourceSlots.add(TypedSlot.source(
                        types.get(random.nextInt(types.size())), round * 100L + i));
            }
            TypedSlotContext source = TypedSlotContext.of(sourceSlots);
            TypedSlotContext middle = expandedContext(source, types, 10_000L + round * 100L);
            TypedSlotContext next = expandedContext(middle, types, 20_000L + round * 100L);
            TypedSlotContext last = expandedContext(next, types, 30_000L + round * 100L);
            TypedEmbedding first = randomEmbedding(source, middle, random);
            TypedEmbedding second = randomEmbedding(middle, next, random);
            TypedEmbedding third = randomEmbedding(next, last, random);

            check(first.isTypePreserving() && first.isInjective(),
                    "Generated first embedding is valid");
            check(first.andThen(second).andThen(third)
                            .equals(first.andThen(second.andThen(third))),
                    "Generated embedding composition is associative");
            check(TypedEmbedding.identity(source).andThen(first).equals(first),
                    "Generated embedding obeys left identity");
            check(first.andThen(TypedEmbedding.identity(middle)).equals(first),
                    "Generated embedding obeys right identity");

            List<TypedSlot> selected = new ArrayList<>();
            int index = 0;
            for (TypedSlot slot : source) {
                if ((index++ & 1) == 0) {
                    selected.add(slot);
                }
            }
            TypedSlotContext support = TypedSlotContext.of(selected);
            check(first.andThen(second).imageOf(support)
                            .equals(second.imageOf(first.imageOf(support))),
                    "Generated support image respects composition");
        }
    }

    private static TypedSlotContext expandedContext(
            TypedSlotContext source,
            List<GraphType> allTypes,
            long ordinalBase) {
        List<TypedSlot> result = new ArrayList<>();
        long ordinal = ordinalBase;
        for (TypedSlot slot : source) {
            result.add(TypedSlot.source(slot.type(), ordinal++));
        }
        for (GraphType type : allTypes) {
            result.add(TypedSlot.source(type, ordinal++));
        }
        return TypedSlotContext.of(result);
    }

    private static TypedEmbedding randomEmbedding(
            TypedSlotContext source,
            TypedSlotContext codomain,
            Random random) {
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        for (Map.Entry<GraphType, Integer> entry : source.typeCounts().entrySet()) {
            List<TypedSlot> inputs = source.slotsOfType(entry.getKey());
            List<TypedSlot> outputs = new ArrayList<>(codomain.slotsOfType(entry.getKey()));
            Collections.shuffle(outputs, random);
            for (int i = 0; i < inputs.size(); i++) {
                mapping.put(inputs.get(i), outputs.get(i));
            }
        }
        return TypedEmbedding.of(source, codomain, mapping);
    }

    private static void testExhaustiveSmallPermutations() {
        for (int size = 0; size <= 5; size++) {
            List<TypedSlot> slots = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                slots.add(TypedSlot.source(GraphType.INT, i));
            }
            TypedSlotContext context = TypedSlotContext.of(slots);
            List<List<TypedSlot>> permutations = new ArrayList<>();
            enumeratePermutations(new ArrayList<>(), new ArrayList<>(slots), permutations);
            for (List<TypedSlot> images : permutations) {
                Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
                for (int i = 0; i < slots.size(); i++) {
                    mapping.put(slots.get(i), images.get(i));
                }
                TypedPermutation permutation = TypedPermutation.of(context, mapping);
                check(permutation.andThen(permutation.inverse())
                                .equals(TypedPermutation.identity(context)),
                        "Exhaustive permutation has a right inverse");
                check(permutation.inverse().andThen(permutation)
                                .equals(TypedPermutation.identity(context)),
                        "Exhaustive permutation has a left inverse");
            }
        }
    }

    private static void enumeratePermutations(
            List<TypedSlot> prefix,
            List<TypedSlot> remaining,
            List<List<TypedSlot>> output) {
        if (remaining.isEmpty()) {
            output.add(new ArrayList<>(prefix));
            return;
        }
        for (int i = 0; i < remaining.size(); i++) {
            TypedSlot selected = remaining.remove(i);
            prefix.add(selected);
            enumeratePermutations(prefix, remaining, output);
            prefix.remove(prefix.size() - 1);
            remaining.add(i, selected);
        }
    }

    private static void testOnlyRenamingsExposeInverse() {
        check(TypedEmbedding.class.isSealed(),
                "Embedding hierarchy is closed against unchecked subtypes");
        check(TypedRenaming.class.isSealed(),
                "Renaming hierarchy is closed against unchecked subtypes");
        check(HasSlotSupport.class.isSealed(),
                "Support carrier hierarchy is closed against invented support");
        for (Method method : TypedEmbedding.class.getDeclaredMethods()) {
            check(!method.getName().equals("inverse"),
                    "A proper embedding API must not expose inverse");
        }
        boolean renamingHasInverse = false;
        for (Method method : TypedRenaming.class.getDeclaredMethods()) {
            renamingHasInverse |= method.getName().equals("inverse");
        }
        check(renamingHasInverse, "Renaming API exposes its valid inverse");
    }

    private static Map<TypedSlot, TypedSlot> mapOf(TypedSlot... entries) {
        if ((entries.length & 1) != 0) {
            throw new IllegalArgumentException("mapOf requires key/value pairs");
        }
        Map<TypedSlot, TypedSlot> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(entries[i], entries[i + 1]);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            ThrowingRunnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable failure) {
            if (expected.isInstance(failure)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + failure, failure);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
