package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Bounded, read-only artifact oracle for the indexed finite-unfolding relation. */
public final class BoundedFiniteUnfoldingOracle {
    public static final String VERSION = "typed-finite-unfolding-oracle-v2";

    private final TypedSlottedPortEGraph graph;
    private final CoherentWitnessFamily witnessFamily;
    private final FiniteUnfoldingBounds bounds;
    private final Map<ParentRecordKey, TypedEqualityCertificate> shapeCoherence;

    private BoundedFiniteUnfoldingOracle(
            TypedSlottedPortEGraph graph,
            CoherentWitnessFamily witnessFamily,
            FiniteUnfoldingBounds bounds) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.witnessFamily = Objects.requireNonNull(witnessFamily, "witnessFamily");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        graph.requireCurrentWitnessFamily(witnessFamily);
        this.shapeCoherence = witnessFamily.shapeCoherence();
    }

    public static BoundedFiniteUnfoldingOracle create(
            TypedSlottedPortEGraph graph,
            CoherentWitnessFamily witnessFamily,
            FiniteUnfoldingBounds bounds) {
        synchronized (Objects.requireNonNull(graph, "graph")) {
            return new BoundedFiniteUnfoldingOracle(graph, witnessFamily, bounds);
        }
    }

    public String version() {
        return VERSION;
    }

    public FiniteUnfoldingBounds bounds() {
        return bounds;
    }

    /** Enumerates complete trees only; a depth frontier is never emitted as a term. */
    public List<FiniteUnfoldingTree> enumerate(TypedInvocation root) {
        synchronized (graph) {
            graph.requireCurrentWitnessFamily(witnessFamily);
            Map<StructuralKey, List<FiniteUnfoldingTree>> memo = new TreeMap<>();
            List<FiniteUnfoldingTree> result = enumerate(
                    Objects.requireNonNull(root, "root"),
                    bounds.maximumDepth(),
                    memo);
            graph.requireCurrentWitnessFamily(witnessFamily);
            return result;
        }
    }

    /** Establishes the exact find/symmetry premise before any unfolding work. */
    public FiniteUnfoldingEqualityWitness establishEquality(
            TypedInvocation left,
            TypedInvocation right) {
        synchronized (graph) {
            return FiniteUnfoldingEqualityWitness.establish(
                    graph, witnessFamily, left, right);
        }
    }

    /** Uses structural alpha plus the declared A/AC/ACI laws as the observation. */
    public FiniteUnfoldingConformanceReport validateNormalized(
            TypedInvocation left,
            TypedInvocation right) {
        return validate(left, right, FiniteUnfoldingTree::normalizedTermKey);
    }

    /**
     * Evaluates every bounded representation on both sides with an independent
     * observer. The report conforms exactly when all observed values agree.
     */
    public FiniteUnfoldingConformanceReport validate(
            TypedInvocation left,
            TypedInvocation right,
            FiniteUnfoldingObserver observer) {
        synchronized (graph) {
            graph.requireCurrentWitnessFamily(witnessFamily);
            FiniteUnfoldingEqualityWitness equality =
                    FiniteUnfoldingEqualityWitness.establish(
                            graph, witnessFamily, left, right);
            Map<StructuralKey, List<FiniteUnfoldingTree>> memo = new TreeMap<>();
            List<FiniteUnfoldingTree> leftTrees = enumerate(
                    equality.left(), bounds.maximumDepth(), memo);
            List<FiniteUnfoldingTree> rightTrees = equality.left().equals(equality.right())
                    ? leftTrees
                    : enumerate(equality.right(), bounds.maximumDepth(), memo);
            if (leftTrees.isEmpty() || rightTrees.isEmpty()) {
                throw new IllegalStateException(
                        "No complete finite unfolding witnesses Rep_G within " + bounds);
            }
            FiniteUnfoldingConformanceReport report =
                    new FiniteUnfoldingConformanceReport(
                            equality, bounds, leftTrees, rightTrees, observer);
            graph.requireCurrentWitnessFamily(witnessFamily);
            return report;
        }
    }

    private List<FiniteUnfoldingTree> enumerate(
            TypedInvocation root,
            int remainingDepth,
            Map<StructuralKey, List<FiniteUnfoldingTree>> memo) {
        StructuralKey memoKey = StructuralKey.of(
                "finite-unfolding-memo",
                Collections.singletonList(Integer.toString(remainingDepth)),
                Collections.singletonList(TheoryKeys.invocation(root)));
        List<FiniteUnfoldingTree> remembered = memo.get(memoKey);
        if (remembered != null) {
            return remembered;
        }
        if (remainingDepth == 0) {
            List<FiniteUnfoldingTree> empty = Collections.emptyList();
            memo.put(memoKey, empty);
            return empty;
        }

        TypedEClassRecord owner = graph.eclass(root.eclass().id());
        if (!owner.interfaceView().equals(root.eclass())) {
            throw new IllegalArgumentException(
                    "Finite-unfolding root carries stale or forged e-class metadata");
        }
        NavigableMap<StructuralKey, FiniteUnfoldingTree> unique = new TreeMap<>();
        for (Map.Entry<CanonicalShape, ShapeWitness> stored
                : owner.shapeWitnesses().entrySet()) {
            CanonicalShape shape = stored.getKey();
            ShapeWitness witness = stored.getValue();
            ParentRecordKey recordKey = new ParentRecordKey(owner.id(), shape);
            TypedEqualityCertificate ec = shapeCoherence.get(recordKey);
            if (ec == null) {
                throw new IllegalStateException(
                        "Finite unfolding lost the retained EC for " + recordKey);
            }
            TypedENode restored = shape.node().act(witness.instantiatingRenaming());
            List<TypedInvocation> leaves = FiniteUnfoldingTree.invocationLeaves(restored);
            List<List<FiniteUnfoldingTree>> combinations = new ArrayList<>();
            combinations.add(Collections.emptyList());
            for (TypedInvocation leaf : leaves) {
                List<FiniteUnfoldingTree> choices = enumerate(
                        leaf, remainingDepth - 1, memo);
                if (choices.isEmpty()) {
                    combinations = Collections.emptyList();
                    break;
                }
                combinations = extend(combinations, choices);
            }
            for (List<FiniteUnfoldingTree> children : combinations) {
                FiniteUnfoldingTree tree = FiniteUnfoldingTree.create(
                        root, owner, shape, witness, ec, children);
                putUnique(unique, tree);
                requireWithinLimit(unique.size());
            }
        }
        List<FiniteUnfoldingTree> result = Collections.unmodifiableList(
                new ArrayList<>(unique.values()));
        memo.put(memoKey, result);
        return result;
    }

    private List<List<FiniteUnfoldingTree>> extend(
            List<List<FiniteUnfoldingTree>> prefixes,
            List<FiniteUnfoldingTree> choices) {
        long prospective = (long) prefixes.size() * choices.size();
        if (prospective > bounds.maximumUnfoldings()) {
            throw new IllegalStateException(
                    "Finite-unfolding cross product exceeds "
                            + bounds.maximumUnfoldings());
        }
        List<List<FiniteUnfoldingTree>> result = new ArrayList<>((int) prospective);
        for (List<FiniteUnfoldingTree> prefix : prefixes) {
            for (FiniteUnfoldingTree choice : choices) {
                List<FiniteUnfoldingTree> combined = new ArrayList<>(prefix.size() + 1);
                combined.addAll(prefix);
                combined.add(choice);
                result.add(Collections.unmodifiableList(combined));
            }
        }
        return result;
    }

    private void putUnique(
            NavigableMap<StructuralKey, FiniteUnfoldingTree> target,
            FiniteUnfoldingTree tree) {
        FiniteUnfoldingTree prior = target.putIfAbsent(tree.enumerationKey(), tree);
        if (prior != null && !prior.equals(tree)) {
            throw new IllegalStateException(
                    "Structural key collision between unequal finite unfoldings");
        }
    }

    private void requireWithinLimit(int count) {
        if (count > bounds.maximumUnfoldings()) {
            throw new IllegalStateException(
                    "Finite-unfolding enumeration exceeds "
                            + bounds.maximumUnfoldings());
        }
    }
}
