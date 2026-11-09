package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Binary JOIN syntax: unlike a flat chain, unary interior leaves are retained. */
public final class GuardedJoinTree {
    private final DependentChainLeaf leaf;
    private final GuardedJoinTree left, right;
    private final DependentTypeDag type;
    private final TypedSlotContext context;
    private final int size, depth;
    private final StructuralKey key;

    private GuardedJoinTree(DependentChainLeaf leaf, GuardedJoinTree left, GuardedJoinTree right) {
        this.leaf = leaf; this.left = left; this.right = right;
        if (leaf != null) {
            type = leaf.outputTypeDag(); context = leaf.context(); size = depth = 1;
            key = StructuralKey.branch("guarded-join-leaf-v1", List.of(leaf.structuralKey()));
        } else {
            Objects.requireNonNull(left); Objects.requireNonNull(right);
            if (!left.context.equals(right.context)) throw new IllegalArgumentException("JOIN contexts differ");
            size = Math.addExact(1, Math.addExact(left.size, right.size));
            depth = 1 + Math.max(left.depth, right.depth);
            if (size > 127 || depth > 64) throw new IllegalArgumentException("Guarded JOIN tree budget exceeded");
            context = left.context;
            var combination = DependentTypeDag.combine(DependentChainKind.JOIN, left.type, right.type);
            type = combination.result();
            key = StructuralKey.branch("guarded-join-branch-v1", List.of(left.key, right.key,
                    type.structuralKey(), StructuralKey.branch("cases", combination.cases().stream()
                            .map(DependentTypeDag.CombinationCase::structuralKey).toList())));
        }
        // Deliberately bounded initial admission: correlated unions/empty types
        // remain with the existing dependent-chain implementation.
        if (type.alternatives().size() != 1) throw new IllegalArgumentException("Guarded JOIN requires one exact product");
    }
    public static GuardedJoinTree leaf(DependentChainLeaf leaf) {
        return new GuardedJoinTree(Objects.requireNonNull(leaf), null, null);
    }
    public static GuardedJoinTree join(GuardedJoinTree left, GuardedJoinTree right) {
        return new GuardedJoinTree(null, left, right);
    }
    public boolean isLeaf() { return leaf != null; }
    public DependentChainLeaf leaf() { return leaf; }
    public GuardedJoinTree left() { return left; }
    public GuardedJoinTree right() { return right; }
    public DependentTypeDag type() { return type; }
    public TypedSlotContext context() { return context; }
    public StructuralKey structuralKey() { return key; }
    public List<GuardedJoinTree> leaves() {
        var result = new ArrayList<GuardedJoinTree>(); collect(this, result); return List.copyOf(result);
    }
    public List<GuardedJoinTree> presentationOperands() {
        if (isLeaf()) throw new IllegalArgumentException("A JOIN leaf has no operands");
        List<GuardedJoinTree> operands = leaves();
        try {
            DependentChainTheory.requireSoundFlatteningEvidence(DependentChainKind.JOIN,
                    operands.stream().map(GuardedJoinTree::leaf).toList());
            if (!DependentTypeDag.fold(DependentChainKind.JOIN,
                    operands.stream().map(GuardedJoinTree::type).toList()).equals(type))
                throw new IllegalArgumentException("Guarded JOIN presentation changed its result DAG");
            return operands;
        } catch (DependentChainTheory.UnsupportedFlattening unsupported) {
            return List.of(left, right);
        }
    }
    private static void collect(GuardedJoinTree tree, List<GuardedJoinTree> result) {
        if (tree.isLeaf()) result.add(tree);
        else { collect(tree.left, result); collect(tree.right, result); }
    }
    /** One locally typed rotation; this never licenses flattening every leaf. */
    public GuardedJoinTree rotate() {
        if (isLeaf() || right.isLeaf()
                || AlloyTypeBridge.relationArity(right.left.type.relationType()) < 2)
            throw new IllegalArgumentException("JOIN rotation needs a non-unary middle operand");
        var rotated = join(join(left, right.left), right.right);
        if (!type.equals(rotated.type)) throw new IllegalArgumentException("JOIN rotation changed result DAG");
        return rotated;
    }
    public GuardedJoinTree rotateAt(List<Integer> path) {
        if (path.size() > 64) throw new IllegalArgumentException("JOIN path budget exceeded");
        return rotateAt(path, 0);
    }
    private GuardedJoinTree rotateAt(List<Integer> path, int offset) {
        if (offset == path.size()) return rotate();
        if (isLeaf()) throw new IllegalArgumentException("JOIN path enters a leaf");
        int side = path.get(offset);
        if (side == 0) return join(left.rotateAt(path, offset+1), right);
        if (side == 1) return join(left, right.rotateAt(path, offset+1));
        throw new IllegalArgumentException("JOIN path is not binary");
    }
}
