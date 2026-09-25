package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Binary source association whose flattening is justified by exact relation columns. */
public final class DependentChainApplication implements DependentChainInput {
    private final DependentChainKind kind;
    private final DependentChainInput left;
    private final DependentChainInput right;
    private final TypedSlotContext context;
    private final DependentTypeDag outputTypeDag;
    private final List<DependentTypeDag.CombinationCase> combinationCases;
    private final List<OnePort> leaves;
    private final StructuralKey structuralKey;

    public DependentChainApplication(
            DependentChainKind kind,
            DependentChainInput left,
            DependentChainInput right) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        if (!left.context().equals(right.context())) {
            throw new IllegalArgumentException(
                    "Dependent chain branches use different caller contexts");
        }
        this.context = left.context();
        DependentTypeDag.ChainCombination combination =
                DependentTypeDag.combine(
                        kind, left.outputTypeDag(), right.outputTypeDag());
        this.outputTypeDag = combination.result();
        this.combinationCases = combination.cases();
        List<OnePort> ordered = new ArrayList<>();
        ordered.addAll(left.leaves());
        ordered.addAll(right.leaves());
        this.leaves = Collections.unmodifiableList(ordered);
        List<DependentChainLeaf> leafInputs = new ArrayList<>();
        collectLeafInputs(left, leafInputs);
        collectLeafInputs(right, leafInputs);
        DependentChainTheory.requireSoundFlatteningEvidence(kind, leafInputs);
        List<DependentTypeDag> leafDags = leafInputs.stream()
                .map(DependentChainLeaf::outputTypeDag)
                .toList();
        DependentTypeDag flattened = DependentTypeDag.fold(kind, leafDags);
        if (!outputTypeDag.equals(flattened)) {
            throw new IllegalArgumentException(
                    "Binary association and dependent-chain fold have different "
                            + "correlated type DAGs");
        }
        List<StructuralKey> keyChildren = new ArrayList<>(List.of(
                TheoryKeys.context(context),
                TheoryKeys.type(outputTypeDag.relationType()),
                outputTypeDag.structuralKey(),
                left.structuralKey(),
                right.structuralKey(),
                StructuralKey.branch(
                        "dependent-chain-combination-cases-v1",
                        combinationCases.stream()
                                .map(DependentTypeDag.CombinationCase::structuralKey)
                                .toList())));
        this.structuralKey = StructuralKey.of(
                "dependent-chain-application-v3",
                List.of(kind.name()),
                keyChildren);
    }

    public DependentChainKind kind() {
        return kind;
    }

    public DependentChainInput left() {
        return left;
    }

    public DependentChainInput right() {
        return right;
    }

    @Override
    public TypedSlotContext context() {
        return context;
    }

    @Override
    public DependentTypeDag outputTypeDag() {
        return outputTypeDag;
    }

    public DependentBoundaryCorrespondence boundaryCorrespondence() {
        if (combinationCases.size() != 1
                || combinationCases.get(0).boundary().isEmpty()) {
            throw new IllegalStateException(
                    "This chain step does not have one scalar boundary proof");
        }
        return combinationCases.get(0).boundary().get();
    }

    public List<DependentTypeDag.CombinationCase> combinationCases() {
        return combinationCases;
    }

    @Override
    public List<OnePort> leaves() {
        return leaves;
    }

    public List<GraphType> leafTypes() {
        List<GraphType> result = new ArrayList<>();
        collectLeafTypes(this, result);
        return Collections.unmodifiableList(result);
    }

    public List<DependentChainLeaf> leafInputs() {
        List<DependentChainLeaf> result = new ArrayList<>();
        collectLeafInputs(this, result);
        return Collections.unmodifiableList(result);
    }

    private static void collectLeafTypes(
            DependentChainInput input,
            List<GraphType> output) {
        if (input instanceof DependentChainLeaf) {
            output.add(input.outputType());
            return;
        }
        DependentChainApplication application =
                (DependentChainApplication) input;
        collectLeafTypes(application.left(), output);
        collectLeafTypes(application.right(), output);
    }

    private static void collectLeafInputs(
            DependentChainInput input,
            List<DependentChainLeaf> output) {
        if (input instanceof DependentChainLeaf) {
            output.add((DependentChainLeaf) input);
            return;
        }
        DependentChainApplication application =
                (DependentChainApplication) input;
        collectLeafInputs(application.left(), output);
        collectLeafInputs(application.right(), output);
    }

    @Override
    public DependentChainApplication act(TypedEmbedding embedding) {
        Objects.requireNonNull(embedding, "embedding");
        if (!context.equals(embedding.source())) {
            throw new IllegalArgumentException(
                    "Dependent chain action starts at another context");
        }
        return new DependentChainApplication(
                kind, left.act(embedding), right.act(embedding));
    }

    @Override
    public StructuralKey structuralKey() {
        return structuralKey;
    }
}
