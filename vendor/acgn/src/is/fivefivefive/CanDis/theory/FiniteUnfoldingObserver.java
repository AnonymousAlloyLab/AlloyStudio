package is.fivefivefive.CanDis.theory;

/**
 * Independent normalization or finite-model observation used by the Phase H
 * oracle. Returning a structural key keeps comparisons deterministic.
 */
@FunctionalInterface
public interface FiniteUnfoldingObserver {
    StructuralKey observe(FiniteUnfoldingTree unfolding);
}
