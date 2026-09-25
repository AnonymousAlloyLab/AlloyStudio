package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** One explicit indexed root step in a materialized {@code Rep_G} tree. */
public final class FiniteUnfoldingStepIndex {
    private final int ordinal;
    private final TypedInvocation invocationAtStep;
    private final CanonicalShape selectedShape;
    private final ShapeWitness shapeWitness;
    private final TypedEmbedding weakening;
    private final TypedEmbedding restoredExtension;
    private final TypedSlotContext freshCoordinates;
    private final TypedEmbedding finalWeakening;
    private final StructuralKey structuralKey;

    FiniteUnfoldingStepIndex(
            int ordinal,
            TypedInvocation invocationAtStep,
            CanonicalShape selectedShape,
            ShapeWitness shapeWitness,
            TypedEmbedding weakening,
            TypedEmbedding restoredExtension,
            TypedSlotContext freshCoordinates,
            TypedEmbedding finalWeakening) {
        if (ordinal < 0) {
            throw new IllegalArgumentException("Finite-unfolding step ordinal must be nonnegative");
        }
        this.ordinal = ordinal;
        this.invocationAtStep = Objects.requireNonNull(
                invocationAtStep, "invocationAtStep");
        this.selectedShape = Objects.requireNonNull(selectedShape, "selectedShape");
        this.shapeWitness = Objects.requireNonNull(shapeWitness, "shapeWitness");
        this.weakening = Objects.requireNonNull(weakening, "weakening");
        this.restoredExtension = Objects.requireNonNull(
                restoredExtension, "restoredExtension");
        this.freshCoordinates = Objects.requireNonNull(
                freshCoordinates, "freshCoordinates");
        this.finalWeakening = Objects.requireNonNull(
                finalWeakening, "finalWeakening");
        validate();

        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-step-index",
                Arrays.asList(
                        StructuralKey.leaf("preorder-ordinal", Integer.toString(ordinal)),
                        TheoryKeys.invocation(invocationAtStep),
                        selectedShape.structuralKey(),
                        shapeWitness.structuralKey(),
                        TheoryKeys.embedding(weakening),
                        TheoryKeys.embedding(restoredExtension),
                        TheoryKeys.context(freshCoordinates),
                        TheoryKeys.embedding(finalWeakening)));
    }

    private void validate() {
        if (!invocationAtStep.eclass().exposedSlots().equals(
                    shapeWitness.exposedInterface())
                || !selectedShape.exactSlots().equals(shapeWitness.exactSlots())) {
            throw new IllegalArgumentException(
                    "Indexed unfolding step does not match its class interface or shape");
        }
        if (!invocationAtStep.callerContext().equals(weakening.source())
                || !weakening.codomain().equals(restoredExtension.codomain())
                || !shapeWitness.ambientSupport().equals(restoredExtension.source())) {
            throw new IllegalArgumentException(
                    "Indexed unfolding step has incompatible caller, ambient, or target contexts");
        }
        TypedEmbedding exposedInWitness = TypedEmbedding.inclusion(
                shapeWitness.exposedInterface(), shapeWitness.ambientSupport());
        TypedEmbedding restoredExposed = exposedInWitness.andThen(restoredExtension);
        TypedEmbedding weakenedInvocation = invocationAtStep.embedding().andThen(weakening);
        if (!restoredExposed.equals(weakenedInvocation)) {
            throw new IllegalArgumentException(
                    "Restored extension does not satisfy mbar o inclusion = iota o m");
        }

        TypedSlotContext redundant = shapeWitness.ambientSupport().minus(
                shapeWitness.exposedInterface());
        TypedSlotContext expectedFresh = restoredExtension.imageOf(redundant);
        if (!freshCoordinates.equals(expectedFresh)
                || !freshCoordinates.isDisjoint(weakening.image())) {
            throw new IllegalArgumentException(
                    "Redundant coordinates must map to fresh same-typed slots");
        }
        if (!restoredExtension.isTypePreserving()
                || !weakening.isTypePreserving()
                || !finalWeakening.isTypePreserving()
                || !weakening.codomain().equals(finalWeakening.source())) {
            throw new IllegalArgumentException(
                    "Every finite-unfolding index map must be a composable typed embedding");
        }
    }

    public int ordinal() {
        return ordinal;
    }

    public TypedInvocation invocationAtStep() {
        return invocationAtStep;
    }

    public CanonicalShape selectedShape() {
        return selectedShape;
    }

    public ShapeWitness shapeWitness() {
        return shapeWitness;
    }

    /** The local {@code iota : Gamma -> Omega}. */
    public TypedEmbedding weakening() {
        return weakening;
    }

    /** The local {@code mbar : T_a,p -> Omega}. */
    public TypedEmbedding restoredExtension() {
        return restoredExtension;
    }

    public TypedSlotContext freshCoordinates() {
        return freshCoordinates;
    }

    /** Inclusion from this step's local Omega into the tree's final scoped context. */
    public TypedEmbedding finalWeakening() {
        return finalWeakening;
    }

    public TypedENode restoredNodeInFinalContext() {
        return selectedShape.node()
                .act(shapeWitness.instantiatingRenaming())
                .act(restoredExtension)
                .act(finalWeakening);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }
}
