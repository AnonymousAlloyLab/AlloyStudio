package is.fivefivefive.CanDis.core.egraph;

public interface AblationEngine {
    Result compare(AlloyTerm left, AlloyTerm right);

    final class Result {
        public final int distance;
        public final boolean equivalent;
        public final EGraphStats stats;

        public Result(int distance, EGraphStats stats) {
            if (distance < 0) {
                throw new IllegalArgumentException("Distance cannot be negative");
            }
            this.distance = distance;
            this.equivalent = distance == 0;
            this.stats = stats;
        }
    }
}
