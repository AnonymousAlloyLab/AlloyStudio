package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.TreeSet;

/** Immutable result of one bounded executable Theorem 1 comparison. */
public final class FiniteUnfoldingConformanceReport {
    private final FiniteUnfoldingEqualityWitness equalityWitness;
    private final FiniteUnfoldingBounds bounds;
    private final List<FiniteUnfoldingTree> leftUnfoldings;
    private final List<FiniteUnfoldingTree> rightUnfoldings;
    private final NavigableSet<StructuralKey> leftNormalizedTerms;
    private final NavigableSet<StructuralKey> rightNormalizedTerms;
    private final NavigableSet<StructuralKey> leftObservations;
    private final NavigableSet<StructuralKey> rightObservations;
    private final List<FiniteUnfoldingCommonWeakening> commonWeakenings;
    private final boolean conformant;
    private final StructuralKey structuralKey;

    FiniteUnfoldingConformanceReport(
            FiniteUnfoldingEqualityWitness equalityWitness,
            FiniteUnfoldingBounds bounds,
            List<? extends FiniteUnfoldingTree> leftUnfoldings,
            List<? extends FiniteUnfoldingTree> rightUnfoldings,
            FiniteUnfoldingObserver observer) {
        this.equalityWitness = Objects.requireNonNull(
                equalityWitness, "equalityWitness");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        this.leftUnfoldings = immutableNonempty(leftUnfoldings, "leftUnfoldings");
        this.rightUnfoldings = immutableNonempty(rightUnfoldings, "rightUnfoldings");
        FiniteUnfoldingObserver checkedObserver = Objects.requireNonNull(
                observer, "observer");

        this.leftNormalizedTerms = normalizedTerms(this.leftUnfoldings);
        this.rightNormalizedTerms = normalizedTerms(this.rightUnfoldings);
        this.leftObservations = observations(this.leftUnfoldings, checkedObserver);
        this.rightObservations = observations(this.rightUnfoldings, checkedObserver);
        this.commonWeakenings = commonWeakenings(
                this.leftUnfoldings, this.rightUnfoldings);
        NavigableSet<StructuralKey> combined = new TreeSet<>(leftObservations);
        combined.addAll(rightObservations);
        this.conformant = combined.size() == 1;

        List<StructuralKey> parts = new ArrayList<>();
        parts.add(equalityWitness.structuralKey());
        parts.add(bounds.structuralKey());
        parts.add(StructuralKey.leaf(
                "left-unfolding-count", Integer.toString(this.leftUnfoldings.size())));
        parts.add(StructuralKey.leaf(
                "right-unfolding-count", Integer.toString(this.rightUnfoldings.size())));
        parts.add(StructuralKey.branch(
                "left-normalized-terms", new ArrayList<>(leftNormalizedTerms)));
        parts.add(StructuralKey.branch(
                "right-normalized-terms", new ArrayList<>(rightNormalizedTerms)));
        parts.add(StructuralKey.branch(
                "left-observations", new ArrayList<>(leftObservations)));
        parts.add(StructuralKey.branch(
                "right-observations", new ArrayList<>(rightObservations)));
        List<StructuralKey> weakeningKeys = new ArrayList<>(commonWeakenings.size());
        for (FiniteUnfoldingCommonWeakening weakening : commonWeakenings) {
            weakeningKeys.add(weakening.structuralKey());
        }
        parts.add(StructuralKey.branch("common-weakenings", weakeningKeys));
        parts.add(StructuralKey.leaf("conformant", Boolean.toString(conformant)));
        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-conformance-report", parts);
    }

    private static List<FiniteUnfoldingTree> immutableNonempty(
            List<? extends FiniteUnfoldingTree> source,
            String name) {
        Objects.requireNonNull(source, name);
        if (source.isEmpty()) {
            throw new IllegalArgumentException(
                    "Conformance requires at least one complete bounded unfolding per side");
        }
        List<FiniteUnfoldingTree> result = new ArrayList<>(source.size());
        for (FiniteUnfoldingTree tree : source) {
            result.add(Objects.requireNonNull(tree, "finite unfolding"));
        }
        return Collections.unmodifiableList(result);
    }

    private static NavigableSet<StructuralKey> normalizedTerms(
            List<FiniteUnfoldingTree> unfoldings) {
        NavigableSet<StructuralKey> result = new TreeSet<>();
        for (FiniteUnfoldingTree unfolding : unfoldings) {
            result.add(unfolding.normalizedTermKey());
        }
        return Collections.unmodifiableNavigableSet(result);
    }

    private static NavigableSet<StructuralKey> observations(
            List<FiniteUnfoldingTree> unfoldings,
            FiniteUnfoldingObserver observer) {
        NavigableSet<StructuralKey> result = new TreeSet<>();
        for (FiniteUnfoldingTree unfolding : unfoldings) {
            result.add(Objects.requireNonNull(
                    observer.observe(unfolding), "finite-unfolding observation"));
        }
        return Collections.unmodifiableNavigableSet(result);
    }

    private static List<FiniteUnfoldingCommonWeakening> commonWeakenings(
            List<FiniteUnfoldingTree> left,
            List<FiniteUnfoldingTree> right) {
        NavigableMap<TypedSlotContext, FiniteUnfoldingIndexTrace> leftContexts =
                representativeContexts(left);
        NavigableMap<TypedSlotContext, FiniteUnfoldingIndexTrace> rightContexts =
                representativeContexts(right);
        List<FiniteUnfoldingCommonWeakening> result = new ArrayList<>(
                leftContexts.size() * rightContexts.size());
        for (FiniteUnfoldingIndexTrace leftTrace : leftContexts.values()) {
            for (FiniteUnfoldingIndexTrace rightTrace : rightContexts.values()) {
                result.add(FiniteUnfoldingCommonWeakening.between(
                        leftTrace, rightTrace));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static NavigableMap<TypedSlotContext, FiniteUnfoldingIndexTrace>
            representativeContexts(List<FiniteUnfoldingTree> unfoldings) {
        NavigableMap<TypedSlotContext, FiniteUnfoldingIndexTrace> result =
                new java.util.TreeMap<>();
        for (FiniteUnfoldingTree tree : unfoldings) {
            result.putIfAbsent(tree.indexTrace().finalContext(), tree.indexTrace());
        }
        return result;
    }

    public FiniteUnfoldingEqualityWitness equalityWitness() {
        return equalityWitness;
    }

    public FiniteUnfoldingBounds bounds() {
        return bounds;
    }

    public List<FiniteUnfoldingTree> leftUnfoldings() {
        return leftUnfoldings;
    }

    public List<FiniteUnfoldingTree> rightUnfoldings() {
        return rightUnfoldings;
    }

    public NavigableSet<StructuralKey> leftNormalizedTerms() {
        return leftNormalizedTerms;
    }

    public NavigableSet<StructuralKey> rightNormalizedTerms() {
        return rightNormalizedTerms;
    }

    public NavigableSet<StructuralKey> leftObservations() {
        return leftObservations;
    }

    public NavigableSet<StructuralKey> rightObservations() {
        return rightObservations;
    }

    /** One compatibility witness per distinct left/right final-context pair. */
    public List<FiniteUnfoldingCommonWeakening> commonWeakenings() {
        return commonWeakenings;
    }

    public boolean conformant() {
        return conformant;
    }

    public void requireConformant() {
        if (!conformant) {
            throw new IllegalStateException(
                    "Bounded finite unfoldings disagree: left=" + leftObservations
                            + ", right=" + rightObservations);
        }
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }
}
