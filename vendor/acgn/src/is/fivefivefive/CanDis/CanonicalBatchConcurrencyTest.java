package is.fivefivefive.CanDis;

public final class CanonicalBatchConcurrencyTest {
    private static int checks;

    private CanonicalBatchConcurrencyTest() {
    }

    public static void main(String[] args) {
        require(limit(16, 8) == 16,
                "the supervised 8 GiB configuration admits all sixteen workers");
        require(limit(32, 4) == 9, "a 4 GiB heap admits nine memory-intensive workers");
        require(limit(32, 2) == 4, "a 2 GiB heap admits four memory-intensive workers");
        require(limit(32, 1) == 1, "a 1 GiB heap retains the fixed reserve and one worker");
        require(limit(1, 64) == 1, "the requested worker count remains an upper bound");
        require(limit(32, 64) == 16, "the implementation cap remains sixteen workers");
        require(ExperimentMemoryBudget.effectiveWorkers(0, 0) == 1,
                "invalid low bounds still fail toward one worker");
        System.out.println("CanonicalBatchConcurrencyTest: " + checks + " checks passed.");
    }

    private static int limit(int workers, long heapGiB) {
        return ExperimentMemoryBudget.effectiveWorkers(
                workers, heapGiB * 1024L * 1024L * 1024L);
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
