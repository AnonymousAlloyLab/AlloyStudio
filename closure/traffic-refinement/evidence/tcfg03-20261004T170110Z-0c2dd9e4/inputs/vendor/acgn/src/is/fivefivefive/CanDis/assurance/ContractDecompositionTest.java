package is.fivefivefive.CanDis.assurance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import is.fivefivefive.CanDis.assurance.ContractDecomposition.Atom;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Binary;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.BinaryKind;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.BinarySkeleton;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Decomposition;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Formula;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Hole;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Not;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.NotSkeleton;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Quantified;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.QuantifiedSkeleton;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Quantifier;
import is.fivefivefive.CanDis.assurance.ContractDecomposition.Skeleton;

/** Standalone JDK-only structural checks; these do not discharge A-01. */
public final class ContractDecompositionTest {
    private int checks;

    private ContractDecompositionTest() {
    }

    public static void main(String[] args) {
        ContractDecompositionTest test = new ContractDecompositionTest();
        test.testFlatAndNested();
        test.testTapeLength();
        test.testDistinctAndDuplicateLeaves();
        test.testLogicalSkeleton();
        test.testQuantifiersAndSharedWitness();
        test.testImmutableData();
        test.testInvalidData();
        test.testBoundedRoundTrips();
        System.out.println("ContractDecompositionTest passed: " + test.checks + " checks");
    }

    private void testFlatAndNested() {
        Atom a = new Atom("A", List.of());
        Atom b = new Atom("B", List.of(0));
        Atom c = new Atom("C", List.of(1, 0, 1));
        check(ContractDecomposition.decompose(a).equals(
                new Decomposition(new Hole(), List.of(a))), "flat atom has one hole and leaf");
        check(ContractDecomposition.reconstruct(
                new Decomposition(new Hole(), List.of(a))).equals(a), "flat atom refills");

        for (BinaryKind kind : BinaryKind.values()) {
            Formula formula = new Binary(kind, a, b);
            Decomposition expected = new Decomposition(
                    new BinarySkeleton(kind, new Hole(), new Hole()), List.of(a, b));
            check(ContractDecomposition.decompose(formula).equals(expected),
                    kind + " retains its binary kind and DFS leaves");
            check(ContractDecomposition.matches(formula, expected), kind + " reconstructs exactly");
        }

        Formula nested = new Quantified(Quantifier.ALL, "User",
                new Binary(BinaryKind.IMPLIES, new Not(a),
                        new Quantified(Quantifier.SOME, "Resource",
                                new Binary(BinaryKind.IFF,
                                        new Binary(BinaryKind.AND, b, c),
                                        new Binary(BinaryKind.OR, a, new Not(b))))));
        Skeleton expected = new QuantifiedSkeleton(Quantifier.ALL, "User",
                new BinarySkeleton(BinaryKind.IMPLIES, new NotSkeleton(new Hole()),
                        new QuantifiedSkeleton(Quantifier.SOME, "Resource",
                                new BinarySkeleton(BinaryKind.IFF,
                                        new BinarySkeleton(BinaryKind.AND, new Hole(), new Hole()),
                                        new BinarySkeleton(BinaryKind.OR, new Hole(),
                                                new NotSkeleton(new Hole()))))));
        Decomposition parts = ContractDecomposition.decompose(nested);
        check(parts.skeleton().equals(expected), "nested logical skeleton is exact");
        check(parts.atoms().equals(List.of(a, b, c, a, b)), "nested tape has exact DFS order");
        check(ContractDecomposition.matches(nested, parts), "nested formula reconstructs exactly");
    }

    private void testTapeLength() {
        Atom a = new Atom("A", List.of());
        Atom b = new Atom("B", List.of());
        Formula original = new Binary(BinaryKind.AND, a, b);
        Skeleton skeleton = ContractDecomposition.decompose(original).skeleton();
        for (List<Atom> tape : List.of(List.<Atom>of(), List.of(a), List.of(a, b, a))) {
            Decomposition candidate = new Decomposition(skeleton, tape);
            expectThrows(IllegalArgumentException.class,
                    () -> ContractDecomposition.reconstruct(candidate));
            expectThrows(IllegalArgumentException.class,
                    () -> ContractDecomposition.matches(original, candidate));
        }
        expectThrows(IllegalArgumentException.class, () -> ContractDecomposition.reconstruct(
                new Decomposition(new Hole(), List.of())));
        expectThrows(IllegalArgumentException.class, () -> ContractDecomposition.reconstruct(
                new Decomposition(new Hole(), List.of(a, b))));
        check(ContractDecomposition.matches(original, new Decomposition(skeleton, List.of(a, b))),
                "exact tape length succeeds");
    }

    private void testDistinctAndDuplicateLeaves() {
        Atom a = new Atom("A", List.of(0));
        Atom b = new Atom("B", List.of(0));
        Formula original = new Binary(BinaryKind.AND, a, b);
        Skeleton skeleton = ContractDecomposition.decompose(original).skeleton();
        check(!ContractDecomposition.matches(original,
                new Decomposition(skeleton, List.of(b, a))), "swapped distinct leaves do not match");
        check(!ContractDecomposition.matches(original,
                new Decomposition(skeleton, List.of(a, a))), "duplicated replacement loses B");
        check(!ContractDecomposition.matches(original,
                new Decomposition(skeleton, List.of(a, new Atom("B", List.of(1))))),
                "changed de Bruijn index does not match");
        check(!ContractDecomposition.matches(new Atom("P", List.of(0, 1)),
                ContractDecomposition.decompose(new Atom("P", List.of(1, 0)))),
                "argument order is significant");
        check(!ContractDecomposition.matches(a,
                ContractDecomposition.decompose(new Atom("A", List.of(0, 0)))),
                "argument multiplicity is significant");

        Formula duplicate = new Binary(BinaryKind.AND, a, a);
        Decomposition parts = ContractDecomposition.decompose(duplicate);
        check(parts.atoms().equals(List.of(a, a)), "duplicate requirement occurrences remain on tape");
        check(ContractDecomposition.matches(duplicate, parts), "duplicate requirements round trip");
        check(!ContractDecomposition.matches(duplicate, ContractDecomposition.decompose(a)),
                "idempotent contraction is not structural reconstruction");
        check(!ContractDecomposition.matches(original, ContractDecomposition.decompose(a)),
                "lost conjunct does not match even with a well-sized candidate tape");
    }

    private void testLogicalSkeleton() {
        Atom a = new Atom("A", List.of());
        Atom b = new Atom("B", List.of());
        Atom c = new Atom("C", List.of());
        for (BinaryKind originalKind : BinaryKind.values()) {
            Formula original = new Binary(originalKind, a, b);
            for (BinaryKind candidateKind : BinaryKind.values()) {
                Decomposition candidate = ContractDecomposition.decompose(
                        new Binary(candidateKind, a, b));
                check(ContractDecomposition.matches(original, candidate)
                                == (originalKind == candidateKind),
                        originalKind + " versus " + candidateKind + " compares exact kinds");
            }
        }
        Formula implication = new Binary(BinaryKind.IMPLIES, a, b);
        check(!ContractDecomposition.matches(implication, ContractDecomposition.decompose(
                new Binary(BinaryKind.IMPLIES, b, a))), "reversed implication does not match");
        check(!ContractDecomposition.matches(implication, ContractDecomposition.decompose(
                new Binary(BinaryKind.IMPLIES, new Not(a), b))), "antecedent polarity is preserved");
        check(!ContractDecomposition.matches(implication, ContractDecomposition.decompose(
                new Binary(BinaryKind.IMPLIES, a, new Not(b)))), "consequent polarity is preserved");
        check(!ContractDecomposition.matches(new Not(a), ContractDecomposition.decompose(a)),
                "lost negation does not match");
        check(!ContractDecomposition.matches(a, ContractDecomposition.decompose(new Not(new Not(a)))),
                "double negation is not normalized");
        check(!ContractDecomposition.matches(
                new Binary(BinaryKind.AND, new Binary(BinaryKind.AND, a, b), c),
                ContractDecomposition.decompose(
                        new Binary(BinaryKind.AND, a, new Binary(BinaryKind.AND, b, c)))),
                "conjunction association remains exact");
    }

    private void testQuantifiersAndSharedWitness() {
        Atom a = new Atom("A", List.of(0));
        Atom b = new Atom("B", List.of(0));
        Atom relation = new Atom("R", List.of(1, 0));
        Formula alternating = new Quantified(Quantifier.ALL, "User",
                new Quantified(Quantifier.SOME, "Resource", relation));
        check(ContractDecomposition.matches(alternating, ContractDecomposition.decompose(alternating)),
                "alternating quantifiers and argument references reconstruct");
        check(!ContractDecomposition.matches(alternating, ContractDecomposition.decompose(
                new Quantified(Quantifier.SOME, "User",
                        new Quantified(Quantifier.ALL, "Resource", relation)))),
                "changed quantifier alternation does not match");
        check(!ContractDecomposition.matches(alternating, ContractDecomposition.decompose(
                new Quantified(Quantifier.ALL, "User",
                        new Quantified(Quantifier.ALL, "Resource", relation)))),
                "changed inner quantifier does not match");
        check(!ContractDecomposition.matches(alternating, ContractDecomposition.decompose(
                new Quantified(Quantifier.ALL, "Resource",
                        new Quantified(Quantifier.SOME, "User", relation)))),
                "sorts remain attached to the exact quantifiers");
        check(!ContractDecomposition.matches(alternating, ContractDecomposition.decompose(
                new Quantified(Quantifier.ALL, "User",
                        new Quantified(Quantifier.SOME, "Other", relation)))),
                "changed sort does not match");

        Formula shared = new Quantified(Quantifier.SOME, "User", new Binary(BinaryKind.AND, a, b));
        Formula separate = new Binary(BinaryKind.AND,
                new Quantified(Quantifier.SOME, "User", a),
                new Quantified(Quantifier.SOME, "User", b));
        Decomposition sharedParts = ContractDecomposition.decompose(shared);
        Decomposition separateParts = ContractDecomposition.decompose(separate);
        check(sharedParts.atoms().equals(separateParts.atoms()), "shared/separate witnesses have same tape");
        check(!sharedParts.skeleton().equals(separateParts.skeleton()), "witness scope remains in skeleton");
        check(ContractDecomposition.matches(shared, sharedParts), "shared witness reconstructs intact");
        check(!ContractDecomposition.matches(shared, separateParts), "one witness cannot become two witnesses");
        check(!ContractDecomposition.matches(separate, sharedParts), "two witnesses cannot become one witness");
    }

    private void testImmutableData() {
        List<Integer> arguments = new ArrayList<>(List.of(0, 1));
        Atom atom = new Atom(" Predicate ", arguments);
        arguments.set(0, 9);
        check(atom.deBruijnArguments().equals(List.of(0, 1)), "atom copies argument list");
        check(atom.predicate().equals(" Predicate "), "predicate spelling is not normalized");
        expectThrows(UnsupportedOperationException.class, () -> atom.deBruijnArguments().add(2));
        List<Atom> tape = new ArrayList<>(List.of(atom));
        Decomposition parts = new Decomposition(new Hole(), tape);
        tape.clear();
        check(parts.atoms().equals(List.of(atom)), "decomposition copies atom tape");
        expectThrows(UnsupportedOperationException.class, () -> parts.atoms().clear());
        expectThrows(UnsupportedOperationException.class,
                () -> ContractDecomposition.decompose(atom).atoms().add(atom));
        Formula declared = new Quantified(Quantifier.SOME, " Sort ", atom);
        check(ContractDecomposition.matches(declared, ContractDecomposition.decompose(declared)),
                "uninterpreted symbols are preserved as declared data");
        check(!ContractDecomposition.matches(declared, ContractDecomposition.decompose(
                new Quantified(Quantifier.SOME, "Sort", atom))), "sort spelling is not normalized");
    }

    private void testInvalidData() {
        Atom a = new Atom("A", List.of());
        Hole hole = new Hole();
        expectThrows(NullPointerException.class, () -> new Atom(null, List.of()));
        expectThrows(NullPointerException.class, () -> new Atom("A", null));
        expectThrows(NullPointerException.class, () -> new Atom("A", Arrays.asList(0, null)));
        expectThrows(IllegalArgumentException.class, () -> new Atom("A", List.of(-1)));
        expectThrows(IllegalArgumentException.class, () -> new Atom("A", List.of(0, -1)));
        for (String blank : List.of("", " ", "\t\n")) {
            expectThrows(IllegalArgumentException.class, () -> new Atom(blank, List.of()));
            expectThrows(IllegalArgumentException.class, () -> new Quantified(Quantifier.ALL, blank, a));
            expectThrows(IllegalArgumentException.class,
                    () -> new QuantifiedSkeleton(Quantifier.SOME, blank, hole));
        }
        expectThrows(NullPointerException.class, () -> new Not(null));
        expectThrows(NullPointerException.class, () -> new Binary(null, a, a));
        expectThrows(NullPointerException.class, () -> new Binary(BinaryKind.AND, null, a));
        expectThrows(NullPointerException.class, () -> new Binary(BinaryKind.AND, a, null));
        expectThrows(NullPointerException.class, () -> new Quantified(null, "S", a));
        expectThrows(NullPointerException.class, () -> new Quantified(Quantifier.ALL, null, a));
        expectThrows(NullPointerException.class, () -> new Quantified(Quantifier.ALL, "S", null));
        expectThrows(NullPointerException.class, () -> new NotSkeleton(null));
        expectThrows(NullPointerException.class, () -> new BinarySkeleton(null, hole, hole));
        expectThrows(NullPointerException.class, () -> new BinarySkeleton(BinaryKind.AND, null, hole));
        expectThrows(NullPointerException.class, () -> new BinarySkeleton(BinaryKind.AND, hole, null));
        expectThrows(NullPointerException.class, () -> new QuantifiedSkeleton(null, "S", hole));
        expectThrows(NullPointerException.class, () -> new QuantifiedSkeleton(Quantifier.SOME, null, hole));
        expectThrows(NullPointerException.class, () -> new QuantifiedSkeleton(Quantifier.SOME, "S", null));
        expectThrows(NullPointerException.class, () -> new Decomposition(null, List.of(a)));
        expectThrows(NullPointerException.class, () -> new Decomposition(hole, null));
        expectThrows(NullPointerException.class, () -> new Decomposition(hole, Arrays.asList(a, null)));
        expectThrows(NullPointerException.class, () -> ContractDecomposition.decompose(null));
        expectThrows(NullPointerException.class, () -> ContractDecomposition.reconstruct(null));
        expectThrows(NullPointerException.class,
                () -> ContractDecomposition.matches(null, new Decomposition(hole, List.of(a))));
        expectThrows(NullPointerException.class, () -> ContractDecomposition.matches(a, null));
    }

    private void testBoundedRoundTrips() {
        List<Formula> leaves = List.of(new Atom("A", List.of()), new Atom("B", List.of(0, 1)));
        List<Formula> formulas = leaves;
        for (int depth = 0; depth < 2; depth++) {
            List<Formula> next = new ArrayList<>(leaves);
            for (Formula body : formulas) {
                next.add(new Not(body));
                for (Quantifier quantifier : Quantifier.values()) {
                    next.add(new Quantified(quantifier, "S", body));
                }
            }
            for (BinaryKind kind : BinaryKind.values()) {
                for (Formula left : formulas) {
                    for (Formula right : formulas) {
                        next.add(new Binary(kind, left, right));
                    }
                }
            }
            formulas = next;
        }
        check(formulas.size() == 2378, "fixed depth-two enumeration stays bounded");
        for (Formula formula : formulas) {
            Decomposition parts = ContractDecomposition.decompose(formula);
            check(ContractDecomposition.reconstruct(parts).equals(formula), "bounded structural round trip");
            check(ContractDecomposition.decompose(formula).equals(parts), "bounded deterministic decomposition");
        }
    }

    private void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void expectThrows(Class<? extends RuntimeException> expected, Runnable action) {
        checks++;
        try {
            action.run();
        } catch (RuntimeException exception) {
            if (expected.isInstance(exception)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName(), exception);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
