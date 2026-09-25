package is.fivefivefive.CanDis.canonical;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import is.fivefivefive.CanDis.theory.StructuralKey;

/**
 * Diagnostic TED between deterministic canonical observations. This is not the
 * quotient-aware repair metric and no isometry claim is made for it.
 */
public final class CanonicalRepresentativeTreeDistance {
    public static final String VERSION = "canonical-representative-ted-v1";

    private CanonicalRepresentativeTreeDistance() {
    }

    public static StructuralKey projection(CanonicalObservation observation) {
        Objects.requireNonNull(observation, "observation");
        return semanticProjection(observation.key());
    }

    public static int size(CanonicalObservation observation) {
        return treeSize(projection(observation), new IdentityHashMap<>());
    }

    public static int distance(CanonicalObservation left, CanonicalObservation right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.equivalentTo(right)) {
            return 0;
        }
        return treeDistance(
                projection(left),
                projection(right),
                new IdentityHashMap<>(),
                new IdentityHashMap<>());
    }

    private static StructuralKey semanticProjection(StructuralKey key) {
        if (!"canonical-alloy-form".equals(key.tag())
                && !key.tag().startsWith("finite-term/")) {
            throw new IllegalArgumentException(
                    "Representative TED requires a finite-term key, found " + key.tag());
        }
        List<String> scalars = new ArrayList<>(key.scalars());
        StringBuilder layout = new StringBuilder(key.children().size());
        List<StructuralKey> children = new ArrayList<>();
        for (int index = 0; index < key.children().size(); index++) {
            StructuralKey child = key.children().get(index);
            if ("canonical-alloy-form".equals(child.tag())
                    || child.tag().startsWith("finite-term/")) {
                layout.append('T');
                children.add(semanticProjection(child));
            } else {
                layout.append('M');
                scalars.add(index + ":" + child.stableString());
            }
        }
        scalars.add("layout=" + layout);
        return StructuralKey.of(key.tag(), scalars, children);
    }

    private static int treeDistance(
            StructuralKey left,
            StructuralKey right,
            Map<StructuralKey, IdentityHashMap<StructuralKey, Integer>> memo,
            Map<StructuralKey, Integer> sizes) {
        IdentityHashMap<StructuralKey, Integer> rightMemo = memo.get(left);
        Integer remembered = rightMemo == null ? null : rightMemo.get(right);
        if (remembered != null) {
            return remembered;
        }
        int result = left.tag().equals(right.tag())
                && left.scalars().equals(right.scalars()) ? 0 : 1;
        int leftCount = left.children().size();
        int rightCount = right.children().size();
        int[][] forest = new int[leftCount + 1][rightCount + 1];
        for (int i = 1; i <= leftCount; i++) {
            forest[i][0] = Math.addExact(
                    forest[i - 1][0], treeSize(left.children().get(i - 1), sizes));
        }
        for (int j = 1; j <= rightCount; j++) {
            forest[0][j] = Math.addExact(
                    forest[0][j - 1], treeSize(right.children().get(j - 1), sizes));
        }
        for (int i = 1; i <= leftCount; i++) {
            StructuralKey leftChild = left.children().get(i - 1);
            for (int j = 1; j <= rightCount; j++) {
                StructuralKey rightChild = right.children().get(j - 1);
                int delete = Math.addExact(forest[i - 1][j], treeSize(leftChild, sizes));
                int insert = Math.addExact(forest[i][j - 1], treeSize(rightChild, sizes));
                int update = Math.addExact(
                        forest[i - 1][j - 1],
                        treeDistance(leftChild, rightChild, memo, sizes));
                forest[i][j] = Math.min(update, Math.min(delete, insert));
            }
        }
        result = Math.addExact(result, forest[leftCount][rightCount]);
        if (rightMemo == null) {
            rightMemo = new IdentityHashMap<>();
            memo.put(left, rightMemo);
        }
        rightMemo.put(right, result);
        return result;
    }

    private static int treeSize(StructuralKey key, Map<StructuralKey, Integer> sizes) {
        Integer remembered = sizes.get(key);
        if (remembered != null) {
            return remembered;
        }
        int result = 1;
        for (StructuralKey child : key.children()) {
            result = Math.addExact(result, treeSize(child, sizes));
        }
        sizes.put(key, result);
        return result;
    }
}
