package is.fivefivefive.CanDis;

final class ExperimentMemoryBudget {
    static final int MAX_MEMORY_INTENSIVE_WORKERS = 16;
    static final long WORKER_HEAP_BYTES = 384L * 1024L * 1024L;
    static final long HEAP_RESERVE_BYTES = 512L * 1024L * 1024L;

    private ExperimentMemoryBudget() {
    }

    static int effectiveWorkers(int requestedWorkers) {
        return effectiveWorkers(requestedWorkers, Runtime.getRuntime().maxMemory());
    }

    static int effectiveWorkers(int requestedWorkers, long maximumHeapBytes) {
        long usableHeap = Math.max(
                WORKER_HEAP_BYTES,
                maximumHeapBytes - HEAP_RESERVE_BYTES);
        long heapBound = Math.max(1L, usableHeap / WORKER_HEAP_BYTES);
        return Math.max(1, Math.min(
                Math.min(Math.max(1, requestedWorkers), MAX_MEMORY_INTENSIVE_WORKERS),
                (int) Math.min(Integer.MAX_VALUE, heapBound)));
    }
}
