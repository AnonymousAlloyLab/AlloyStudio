package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/** Deterministic bounded generator presentation of one finite permutation group. */
final class CanonicalPermutationPresentation {
    private static final long DEFAULT_MAX_WORK_ITEMS = 1_000_000L;

    private CanonicalPermutationPresentation() {
    }

    /**
     * Complete order key for one producer canonical-orbit candidate.  Keeping
     * the witness in the key matters when two admissible embeddings expose the
     * same canonical shape.
     */
    static StructuralKey orbitCandidateOrder(
            CanonicalShape candidate,
            TypedEmbedding witness) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(witness, "witness");
        return StructuralKey.branch(
                "canonical-orbit-candidate-v1",
                List.of(
                        candidate.structuralKey(),
                        TheoryKeys.witnessOrder(witness)));
    }

    static List<TypedPermutation> of(
            TypedSlotContext context,
            List<? extends TypedPermutation> sourceGenerators,
            Function<TypedPermutation, StructuralKey> key) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sourceGenerators, "sourceGenerators");
        Objects.requireNonNull(key, "key");

        List<TypedPermutation> selected = new ArrayList<>();
        Set<StructuralKey> selectedClosure = new TreeSet<>();
        selectedClosure.add(key.apply(TypedPermutation.identity(context)));
        long[] work = {0L};

        while (true) {
            TypedPermutation[] leastMissing = {null};
            FinitePermutationTraversal.forEach(
                    context,
                    sourceGenerators,
                    key,
                    candidate -> {
                        consume(work);
                        StructuralKey candidateKey = key.apply(candidate);
                        if (!selectedClosure.contains(candidateKey)
                                && (leastMissing[0] == null
                                        || candidateKey.compareTo(
                                                key.apply(leastMissing[0])) < 0)) {
                            leastMissing[0] = candidate;
                        }
                    });
            if (leastMissing[0] == null) {
                return Collections.unmodifiableList(selected);
            }
            selected.add(leastMissing[0]);
            selectedClosure.clear();
            FinitePermutationTraversal.forEach(
                    context,
                    selected,
                    key,
                    candidate -> {
                        consume(work);
                        selectedClosure.add(key.apply(candidate));
                    });
        }
    }

    private static void consume(long[] work) {
        work[0] = Math.addExact(work[0], 1L);
        long maximum = Long.getLong(
                "acgn.maxGroupPresentationWork", DEFAULT_MAX_WORK_ITEMS);
        if (maximum <= 0) {
            throw new IllegalStateException(
                    "acgn.maxGroupPresentationWork must be positive");
        }
        if (work[0] > maximum) {
            throw new CanonicalizationDomainException(
                    "Canonical group presentation exceeds configured work bound "
                            + maximum);
        }
    }
}
