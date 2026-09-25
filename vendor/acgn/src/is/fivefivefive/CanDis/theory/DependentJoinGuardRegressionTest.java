package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A2-06: bounded execution of the actual producer guard, not parser refinement
 * or a claim that every accepted arity word is a well-typed JOIN expression.
 * Compiler-resolved extraction and public verifier replay are separate checks.
 */
public final class DependentJoinGuardRegressionTest {
    private static final GraphType A = GraphType.constructor("AlloySig:GuardA");
    private static final GraphType B = GraphType.constructor("AlloySig:GuardB");
    private static final GraphType C = GraphType.constructor("AlloySig:GuardC");
    private static final List<GraphType> PRODUCTS = List.of(
            GraphType.relation(A), GraphType.relation(A, B), GraphType.relation(A, B, C));
    private static int checks;
    private static int exhaustiveCases;
    private static int longerCases;
    private static int familyCases;
    private static int invalidCases;

    private DependentJoinGuardRegressionTest() {
    }

    public static void main(String[] args) {
        runAssertions();
        System.out.println("DependentJoinGuardRegressionTest passed: " + checks + " checks"
                + " (" + exhaustiveCases + " exhaustive cases, " + longerCases
                + " longer cases, " + familyCases + " retained-family cases, "
                + invalidCases + " invalid-input cases)");
    }

    static int runAssertions() {
        checks = 0;
        exhaustiveCases = 0;
        longerCases = 0;
        familyCases = 0;
        invalidCases = 0;
        for (DependentChainKind kind : DependentChainKind.values()) {
            for (int length = 0; length <= 7; length++) {
                enumerate(kind, length, new ArrayList<>());
            }
        }
        // 2 * sum(3^length, length = 0..7), including both short preconditions.
        check(exhaustiveCases == 6560, "The exhaustive matrix must contain 6560 cases");
        testLongerPositions();
        testRetainedFamilyArities();
        testInvalidInputs();
        return checks;
    }

    private static void enumerate(
            DependentChainKind kind, int remaining, List<Integer> word) {
        if (remaining == 0) {
            assertGuard(kind, word, products(word), "exhaustive " + kind + " " + word);
            exhaustiveCases++;
            return;
        }
        for (int arity = 1; arity <= 3; arity++) {
            word.add(arity);
            enumerate(kind, remaining - 1, word);
            word.remove(word.size() - 1);
        }
    }

    private static List<GraphType> products(List<Integer> word) {
        return word.stream().map(arity -> PRODUCTS.get(arity - 1)).toList();
    }

    // Independent of the production loop, relationArity, and isRelationFamily.
    private static boolean oracle(DependentChainKind kind, List<Integer> arities) {
        return arities.size() >= 2 && (kind == DependentChainKind.ARROW
                || arities.subList(1, arities.size() - 1).stream()
                        .allMatch(arity -> arity >= 2));
    }

    private static void assertGuard(DependentChainKind kind, List<Integer> arities,
            List<GraphType> types, String label) {
        check(types.size() == arities.size(), label + ": fixture length mismatch");
        List<GraphType> before = new ArrayList<>(types);
        boolean expected = oracle(kind, arities);
        Class<? extends RuntimeException> rejection = arities.size() < 2
                ? IllegalArgumentException.class : DependentChainTheory.UnsupportedFlattening.class;
        RuntimeException failure = null;
        try {
            DependentChainTheory.requireSoundFlattening(kind, types);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        if (expected) {
            check(failure == null, label + ": unexpected rejection " + failure);
        } else {
            check(failure != null && failure.getClass() == rejection,
                    label + ": expected " + rejection.getSimpleName() + ", got " + failure);
        }
        assertUnchanged(before, types, label);
    }

    private static void testLongerPositions() {
        for (DependentChainKind kind : DependentChainKind.values()) {
            for (int length : new int[] {8, 17, 64, 257}) {
                for (int background : new int[] {2, 3}) {
                    List<Integer> word = new ArrayList<>(Collections.nCopies(length, background));
                    assertLonger(kind, word, "all interiors " + background);
                    // Every position, especially 0, 1, length - 2, and length - 1.
                    for (int index = 0; index < length; index++) {
                        word.set(index, 1);
                        assertLonger(kind, word, "single unary at " + index);
                        word.set(index, background);
                    }
                    word.set(0, 1);
                    word.set(length - 1, 1);
                    assertLonger(kind, word, "both unary endpoints");
                }
            }
        }
    }

    private static void assertLonger(DependentChainKind kind, List<Integer> word, String label) {
        assertGuard(kind, word, products(word), "long " + kind + " n=" + word.size() + " " + label);
        longerCases++;
    }

    private static void testRetainedFamilyArities() {
        for (DependentChainKind kind : DependentChainKind.values()) {
            for (int arity : new int[] {1, 2, 3, 17}) {
                GraphType left = GraphType.relation(Collections.nCopies(arity, A));
                GraphType right = GraphType.relation(Collections.nCopies(arity, B));
                List<GraphType> alternatives = new ArrayList<>(List.of(left, right));
                Collections.sort(alternatives);
                GraphType union = GraphType.constructor("AlloyRelationUnion", alternatives);
                // Typed-empty arity is retained even though it has no alternatives.
                for (GraphType family : List.of(left, union, AlloyTypeBridge.emptyRelation(arity))) {
                    for (int index = 0; index < 9; index++) {
                        List<Integer> word = new ArrayList<>(Collections.nCopies(9, 2));
                        List<GraphType> types = new ArrayList<>(Collections.nCopies(9, PRODUCTS.get(1)));
                        word.set(index, arity);
                        types.set(index, family);
                        assertGuard(kind, word, types,
                                "family " + kind + " arity=" + arity + " index=" + index);
                        familyCases++;
                    }
                }
            }
            assertGuard(kind, List.of(1, Integer.MAX_VALUE, 1),
                    List.of(PRODUCTS.get(0), AlloyTypeBridge.emptyRelation(Integer.MAX_VALUE), PRODUCTS.get(0)),
                    "maximum retained empty arity " + kind);
            familyCases++;
        }
    }

    private static void testInvalidInputs() {
        assertInvalid(null, List.of(PRODUCTS.get(1), PRODUCTS.get(1)),
                NullPointerException.class, "null kind");
        List<GraphType> invalidTypes = Arrays.asList(
                null, GraphType.BOOL, GraphType.INT, A, GraphType.typeVariable("GuardT"),
                GraphType.arrow(A, B), GraphType.constructor("GuardUnknown"),
                GraphType.constructor("AlloyCarrier", A),
                GraphType.constructor("AlloyComparableCarrier", PRODUCTS.get(1)),
                GraphType.constructor("AlloyEmptyRelation$arity=0"),
                GraphType.constructor("AlloyEmptyRelation$arity=-1"),
                GraphType.constructor("AlloyEmptyRelation$arity=01"),
                GraphType.constructor("AlloyEmptyRelation$arity=2147483648"),
                GraphType.constructor("AlloyEmptyRelation$arity=2", A),
                GraphType.constructor("AlloyRelationUnion"),
                GraphType.constructor("AlloyRelationUnion", PRODUCTS.get(1)),
                GraphType.constructor("AlloyRelationUnion", PRODUCTS.get(1), PRODUCTS.get(1)),
                GraphType.constructor("AlloyRelationUnion", PRODUCTS.get(0), PRODUCTS.get(1)),
                GraphType.constructor("AlloyRelationUnion", GraphType.relation(B), GraphType.relation(A)),
                GraphType.constructor("AlloyRelationUnion", A, B));
        for (DependentChainKind kind : DependentChainKind.values()) {
            assertInvalid(kind, null, NullPointerException.class, "null operand list " + kind);
            for (GraphType invalid : invalidTypes) {
                // Endpoints and two-operand chains are exempt from the arity scan,
                // not from the producer's exact relation-family validation.
                for (int length : new int[] {2, 3, 9}) {
                    for (int index = 0; index < length; index++) {
                        List<GraphType> types = new ArrayList<>(Collections.nCopies(length, PRODUCTS.get(1)));
                        types.set(index, invalid);
                        assertInvalid(kind, types,
                                invalid == null ? NullPointerException.class : IllegalArgumentException.class,
                                "invalid " + kind + " n=" + length + " index=" + index + " type=" + invalid);
                    }
                }
            }
            // All operand validation precedes even an earlier failing interior.
            assertInvalid(kind, Arrays.asList(PRODUCTS.get(1), PRODUCTS.get(0), GraphType.BOOL),
                    IllegalArgumentException.class, "invalid trailing type precedes scan " + kind);
            assertInvalid(kind, Arrays.asList(PRODUCTS.get(1), PRODUCTS.get(0), null),
                    NullPointerException.class, "null trailing type precedes scan " + kind);
        }
    }

    private static void assertInvalid(DependentChainKind kind, List<GraphType> types,
            Class<? extends RuntimeException> expected, String label) {
        List<GraphType> before = types == null ? null : new ArrayList<>(types);
        RuntimeException failure = null;
        try {
            DependentChainTheory.requireSoundFlattening(kind, types);
        } catch (RuntimeException exception) {
            failure = exception;
        }
        check(failure != null && failure.getClass() == expected,
                label + ": expected " + expected.getSimpleName() + ", got " + failure);
        if (types != null) {
            assertUnchanged(before, types, label);
        }
        invalidCases++;
    }

    private static void assertUnchanged(List<GraphType> before, List<GraphType> after, String label) {
        check(before.size() == after.size(), label + ": guard changed operand count");
        for (int index = 0; index < before.size(); index++) {
            check(before.get(index) == after.get(index), label + ": guard replaced exact source type " + index);
        }
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
