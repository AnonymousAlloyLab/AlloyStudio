package is.fivefivefive.CanDis.theory;

import java.util.Arrays;

/** Finite resource bound for the executable {@code Rep_G} oracle. */
public final class FiniteUnfoldingBounds {
    private final int maximumDepth;
    private final int maximumUnfoldings;
    private final StructuralKey structuralKey;

    public FiniteUnfoldingBounds(int maximumDepth, int maximumUnfoldings) {
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("Finite-unfolding depth must be positive");
        }
        if (maximumUnfoldings < 1) {
            throw new IllegalArgumentException("Finite-unfolding count bound must be positive");
        }
        this.maximumDepth = maximumDepth;
        this.maximumUnfoldings = maximumUnfoldings;
        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-bounds",
                Arrays.asList(
                        StructuralKey.leaf("maximum-depth", Integer.toString(maximumDepth)),
                        StructuralKey.leaf(
                                "maximum-unfoldings",
                                Integer.toString(maximumUnfoldings))));
    }

    public int maximumDepth() {
        return maximumDepth;
    }

    public int maximumUnfoldings() {
        return maximumUnfoldings;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FiniteUnfoldingBounds
                && maximumDepth == ((FiniteUnfoldingBounds) other).maximumDepth
                && maximumUnfoldings == ((FiniteUnfoldingBounds) other).maximumUnfoldings;
    }

    @Override
    public int hashCode() {
        return 31 * maximumDepth + maximumUnfoldings;
    }

    @Override
    public String toString() {
        return "depth<=" + maximumDepth + ", unfoldings<=" + maximumUnfoldings;
    }
}
