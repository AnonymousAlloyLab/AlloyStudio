package is.fivefivefive.CanDis;

/** Shared worker-count policy for every e-graph ablation arm. */
final class AblationParallelism {
    static final int MAX_WORKERS = 32;
    static final String POLICY = "min(requested, logical processors, 32)";

    private AblationParallelism() {
    }

    static int logicalProcessors() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    static int defaultWorkers() {
        return Math.min(MAX_WORKERS, logicalProcessors());
    }

    static int effectiveWorkers(int requested) {
        return Math.max(1, Math.min(requested, defaultWorkers()));
    }
}
