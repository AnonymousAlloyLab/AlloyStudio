package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import is.fivefivefive.CanDis.core.JoinSegmentation;

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
        requireAdmittedJoinType(type, "Guarded JOIN");
    }

    /**
     * Admission for one JOIN intermediate. A typed-empty family has zero
     * correlated alternatives but still retains its positive relation arity;
     * empty is not missing type. Families with more than one correlated product
     * remain OPEN until checked case-wise composition is implemented.
     */
    public static void requireAdmittedJoinType(DependentTypeDag type, String where) {
        Objects.requireNonNull(type, "type");
        if (type.alternatives().size() > 1) {
            throw new IllegalArgumentException(where + " requires one exact product");
        }
        if (type.alternatives().isEmpty()) {
            Integer retained = AlloyTypeBridge.emptyRelationArity(type.relationType());
            if (retained == null || retained <= 0 || retained != type.arity()) {
                throw new IllegalArgumentException(
                        where + " typed-empty family lost its retained positive arity");
            }
        }
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
    /**
     * Maximal ordered segments. Endpoints may be unary; a unary interior operand
     * is a barrier and becomes one nested typed segment at its parent. The
     * segment's exact type fold is checked against the retained result DAG. If the
     * checked segmentation cannot be established, the original binary children
     * are retained rather than flattened across the barrier.
     */
    public List<GuardedJoinTree> presentationOperands() {
        if (isLeaf()) throw new IllegalArgumentException("A JOIN leaf has no operands");
        var operands = JoinSegmentation.of(this,
                t -> t.isLeaf() ? List.of() : List.of(t.left, t.right),
                t -> t.type.arity(), 127).operands();
        return soundSegment(operands, type) ? operands : List.of(left, right);
    }

    private static boolean soundSegment(List<GuardedJoinTree> operands,
            DependentTypeDag expected) {
        if (operands.size() < 2) return false;
        try {
            List<Integer> arities = new ArrayList<>(operands.size());
            for (GuardedJoinTree operand : operands) {
                arities.add(AlloyTypeBridge.relationArity(operand.type().relationType()));
            }
            for (int index = 1; index + 1 < arities.size(); index++) {
                if (arities.get(index) < 2) return false;
            }
            return DependentTypeDag.fold(DependentChainKind.JOIN,
                    operands.stream().map(GuardedJoinTree::type).toList()).equals(expected);
        } catch (IllegalArgumentException unsupported) {
            return false;
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
