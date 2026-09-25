package is.fivefivefive.CanDis.assurance;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Exact structural decomposition of a declared logical-contract data tree.
 * Predicate and sort symbols are uninterpreted data, not admitted primitive
 * authorities. An Atom denotes a data leaf, not evidence of semantic atomicity.
 * This API does not establish truth, prose correspondence, or requirement closure.
 * It performs no parsing, normalization, or scope/type validation: nonnegative
 * de Bruijn arguments are retained verbatim, including in open formulas.
 */
public final class ContractDecomposition {
    private ContractDecomposition() {
    }

    public enum BinaryKind {
        AND, OR, IMPLIES, IFF
    }

    public enum Quantifier {
        ALL, SOME
    }

    public sealed interface Formula permits Atom, Not, Binary, Quantified {
    }

    public record Atom(String predicate, List<Integer> deBruijnArguments)
            implements Formula {
        public Atom {
            requireSymbol(predicate, "predicate");
            deBruijnArguments = List.copyOf(deBruijnArguments);
            for (int argument : deBruijnArguments) {
                if (argument < 0) {
                    throw new IllegalArgumentException("Negative de Bruijn argument: " + argument);
                }
            }
        }
    }

    public record Not(Formula body) implements Formula {
        public Not {
            Objects.requireNonNull(body, "body");
        }
    }

    public record Binary(BinaryKind kind, Formula left, Formula right) implements Formula {
        public Binary {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    public record Quantified(Quantifier quantifier, String sort, Formula body)
            implements Formula {
        public Quantified {
            Objects.requireNonNull(quantifier, "quantifier");
            requireSymbol(sort, "sort");
            Objects.requireNonNull(body, "body");
        }
    }

    public sealed interface Skeleton
            permits Hole, NotSkeleton, BinarySkeleton, QuantifiedSkeleton {
    }

    public record Hole() implements Skeleton {
    }

    public record NotSkeleton(Skeleton body) implements Skeleton {
        public NotSkeleton {
            Objects.requireNonNull(body, "body");
        }
    }

    public record BinarySkeleton(BinaryKind kind, Skeleton left, Skeleton right)
            implements Skeleton {
        public BinarySkeleton {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    public record QuantifiedSkeleton(Quantifier quantifier, String sort, Skeleton body)
            implements Skeleton {
        public QuantifiedSkeleton {
            Objects.requireNonNull(quantifier, "quantifier");
            requireSymbol(sort, "sort");
            Objects.requireNonNull(body, "body");
        }
    }

    /** Tape length is checked by reconstruction, not asserted by this record. */
    public record Decomposition(Skeleton skeleton, List<Atom> atoms) {
        public Decomposition {
            Objects.requireNonNull(skeleton, "skeleton");
            atoms = List.copyOf(atoms);
        }
    }

    /** Replaces each atom occurrence with a hole, retaining left-to-right DFS order. */
    public static Decomposition decompose(Formula original) {
        Objects.requireNonNull(original, "original");
        List<Atom> atoms = new ArrayList<>();
        Skeleton skeleton = skeleton(original, atoms);
        return new Decomposition(skeleton, atoms);
    }

    /**
     * Refills holes in left-to-right DFS order without any logical rewriting.
     * @throws IllegalArgumentException if the tape is too short or has leftover atoms
     */
    public static Formula reconstruct(Decomposition candidate) {
        Objects.requireNonNull(candidate, "candidate");
        Iterator<Atom> atoms = candidate.atoms().iterator();
        Formula result = refill(candidate.skeleton(), atoms);
        if (atoms.hasNext()) {
            throw new IllegalArgumentException("Atom tape has leftover atoms");
        }
        return result;
    }

    /**
     * Compares the original with the candidate's exact reconstruction using record
     * equality, not logical equivalence. A malformed tape rejects as in reconstruct.
     */
    public static boolean matches(Formula original, Decomposition candidate) {
        Objects.requireNonNull(original, "original");
        return original.equals(reconstruct(candidate));
    }

    private static Skeleton skeleton(Formula formula, List<Atom> atoms) {
        if (formula instanceof Atom atom) {
            atoms.add(atom);
            return new Hole();
        }
        if (formula instanceof Not not) {
            return new NotSkeleton(skeleton(not.body(), atoms));
        }
        if (formula instanceof Binary binary) {
            return new BinarySkeleton(binary.kind(),
                    skeleton(binary.left(), atoms), skeleton(binary.right(), atoms));
        }
        Quantified quantified = (Quantified) formula;
        return new QuantifiedSkeleton(quantified.quantifier(), quantified.sort(),
                skeleton(quantified.body(), atoms));
    }

    private static Formula refill(Skeleton skeleton, Iterator<Atom> atoms) {
        if (skeleton instanceof Hole) {
            if (!atoms.hasNext()) {
                throw new IllegalArgumentException("Atom tape underflow");
            }
            return atoms.next();
        }
        if (skeleton instanceof NotSkeleton not) {
            return new Not(refill(not.body(), atoms));
        }
        if (skeleton instanceof BinarySkeleton binary) {
            return new Binary(binary.kind(),
                    refill(binary.left(), atoms), refill(binary.right(), atoms));
        }
        QuantifiedSkeleton quantified = (QuantifiedSkeleton) skeleton;
        return new Quantified(quantified.quantifier(), quantified.sort(),
                refill(quantified.body(), atoms));
    }

    private static void requireSymbol(String symbol, String name) {
        Objects.requireNonNull(symbol, name);
        if (symbol.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
