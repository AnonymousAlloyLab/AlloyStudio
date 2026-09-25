package is.fivefivefive.CanDis.core.egraph;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Minimum unit-cost rooted-tree edit distance between retained e-graph roots. */
final class EGraphEditDistance {
    private EGraphEditDistance() {
    }

    static int between(AlloyTerm left, AlloyTerm right) {
        return new Context().distance(left, right);
    }

    static int minimum(Collection<AlloyTerm> leftRoots, Collection<AlloyTerm> rightRoots) {
        if (leftRoots.isEmpty() || rightRoots.isEmpty()) {
            throw new IllegalArgumentException("Both e-graphs must retain at least one root");
        }
        Context context = new Context();
        int best = Integer.MAX_VALUE;
        for (AlloyTerm left : leftRoots) {
            for (AlloyTerm right : rightRoots) {
                if (Math.abs(left.size() - right.size()) >= best) {
                    continue;
                }
                best = Math.min(best, context.distance(left, right));
                if (best == 0) {
                    return 0;
                }
            }
        }
        return best;
    }

    private static final class Context {
        private final Map<AlloyTerm, Map<AlloyTerm, Integer>> memo = new IdentityHashMap<>();

        private int distance(AlloyTerm left, AlloyTerm right) {
            if (left == right || left.equals(right)) {
                return 0;
            }
            Map<AlloyTerm, Integer> rightMemo = memo.computeIfAbsent(
                    left, ignored -> new IdentityHashMap<>());
            Integer cached = rightMemo.get(right);
            if (cached != null) {
                return cached;
            }

            int result = nodeUpdateCost(left, right)
                    + childDistance(left.children(), right.children());
            rightMemo.put(right, result);
            return result;
        }

        private int childDistance(List<AlloyTerm> left, List<AlloyTerm> right) {
            int[] previous = new int[right.size() + 1];
            int[] current = new int[right.size() + 1];
            for (int j = 1; j <= right.size(); j++) {
                previous[j] = previous[j - 1] + right.get(j - 1).size();
            }
            for (int i = 1; i <= left.size(); i++) {
                AlloyTerm leftChild = left.get(i - 1);
                current[0] = previous[0] + leftChild.size();
                for (int j = 1; j <= right.size(); j++) {
                    AlloyTerm rightChild = right.get(j - 1);
                    int delete = previous[j] + leftChild.size();
                    int insert = current[j - 1] + rightChild.size();
                    int update = previous[j - 1] + distance(leftChild, rightChild);
                    current[j] = Math.min(update, Math.min(delete, insert));
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }
            return previous[right.size()];
        }

        private static int nodeUpdateCost(AlloyTerm left, AlloyTerm right) {
            return left.head().equals(right.head()) && left.atom().equals(right.atom()) ? 0 : 1;
        }
    }
}
