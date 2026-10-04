package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Compatible weakening of two represented finite terms into one typed context. */
public final class FiniteUnfoldingCommonWeakening {
    private final FiniteUnfoldingIndexTrace left;
    private final FiniteUnfoldingIndexTrace right;
    private final TypedSlotContext commonContext;
    private final TypedEmbedding leftToCommon;
    private final TypedEmbedding rightToCommon;
    private final TypedEmbedding sharedRootWeakening;
    private final StructuralKey structuralKey;

    private FiniteUnfoldingCommonWeakening(
            FiniteUnfoldingIndexTrace left,
            FiniteUnfoldingIndexTrace right) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        if (!left.rootInvocation().callerContext().equals(
                right.rootInvocation().callerContext())) {
            throw new IllegalArgumentException(
                    "Common finite-unfolding weakening requires one root context");
        }
        this.commonContext = left.finalContext().union(right.finalContext());
        this.leftToCommon = TypedEmbedding.inclusion(
                left.finalContext(), commonContext);
        this.rightToCommon = TypedEmbedding.inclusion(
                right.finalContext(), commonContext);
        TypedEmbedding leftRoot = left.finalWeakening().andThen(leftToCommon);
        TypedEmbedding rightRoot = right.finalWeakening().andThen(rightToCommon);
        if (!leftRoot.equals(rightRoot)) {
            throw new IllegalArgumentException(
                    "Two final weakenings disagree on their shared root context");
        }
        this.sharedRootWeakening = leftRoot;
        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-common-weakening",
                Arrays.asList(
                        TheoryKeys.context(commonContext),
                        TheoryKeys.embedding(leftToCommon),
                        TheoryKeys.embedding(rightToCommon),
                        TheoryKeys.embedding(sharedRootWeakening)));
    }

    public static FiniteUnfoldingCommonWeakening between(
            FiniteUnfoldingTree left,
            FiniteUnfoldingTree right) {
        return new FiniteUnfoldingCommonWeakening(
                Objects.requireNonNull(left, "left").indexTrace(),
                Objects.requireNonNull(right, "right").indexTrace());
    }

    static FiniteUnfoldingCommonWeakening between(
            FiniteUnfoldingIndexTrace left,
            FiniteUnfoldingIndexTrace right) {
        return new FiniteUnfoldingCommonWeakening(left, right);
    }

    public FiniteUnfoldingIndexTrace left() {
        return left;
    }

    public FiniteUnfoldingIndexTrace right() {
        return right;
    }

    public TypedSlotContext commonContext() {
        return commonContext;
    }

    public TypedEmbedding leftToCommon() {
        return leftToCommon;
    }

    public TypedEmbedding rightToCommon() {
        return rightToCommon;
    }

    /** Equal restriction of both weakenings to the original root context. */
    public TypedEmbedding sharedRootWeakening() {
        return sharedRootWeakening;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }
}
