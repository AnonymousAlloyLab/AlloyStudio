package live;

/**
 * Ordered pool accumulation using the generated finite choice and completion
 * policies. Cost validation, comparison, counting and iteration are Java code;
 * the policy bridge does not certify those operations or either distance metric.
 */
final class VerifiedPoolSelection<T> {
    record Result<T>(T value, int cost, int index, int evaluatedCandidates) { }

    private final int expectedCount;
    private int evaluatedCandidates;
    private T best;
    private int bestCost;
    private int bestIndex = -1;
    private boolean failed;

    // The adapters enforce their request limit (4096); the accumulator accepts
    // any positive count representable by int and never increments beyond it.
    VerifiedPoolSelection(int expectedCount) {
        if (expectedCount <= 0) throw new IllegalArgumentException("invalid candidate count");
        this.expectedCount = expectedCount;
    }

    void consider(int index, T candidate, int cost) {
        if (failed || index != evaluatedCandidates || evaluatedCandidates >= expectedCount
                || candidate == null || cost < 0) {
            failed = true;
            throw new IllegalArgumentException("invalid candidate evaluation");
        }
        boolean hasBest = bestIndex >= 0;
        // Both metric APIs return int. No narrowing or rounding is performed.
        // Strict comparison preserves the first reference at an equal cost.
        if (BridgePolicies.choose(hasBest, hasBest && cost < bestCost)) {
            best = candidate;
            bestCost = cost;
            bestIndex = index;
        }
        evaluatedCandidates++;
    }

    Result<T> result() {
        boolean nonempty = bestIndex >= 0;
        boolean complete = !failed && evaluatedCandidates == expectedCount;
        if (!BridgePolicies.finish(nonempty, complete))
            throw new IllegalArgumentException("incomplete candidate evaluation");
        return new Result<>(best, bestCost, bestIndex, evaluatedCandidates);
    }
}
