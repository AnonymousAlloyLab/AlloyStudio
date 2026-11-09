package is.fivefivefive.CanDis.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;

/** Ordered JOIN/plain-product edit operands; does not mutate or certify the source tree. */
public final class FastChainGeometry {
    public static final String VERSION = "fast-guarded-chain-geometry-v2-plain-product";
    private static final int MAX_VISITS = 4096;
    private FastChainGeometry() { }

    public static List<EGraphNode> children(EGraphNode root) {
        List<EGraphNode> original = root.getChildren();
        if (root.getOpcode() == EGraphNode.Opcode.ARROW) return productChildren(root, original);
        if (root.getOpcode() != EGraphNode.Opcode.JOIN || original.size() != 2)
            return original;
        List<EGraphNode> leaves = new ArrayList<>();
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        pending.push(root);
        int visits = 0;
        while (!pending.isEmpty()) {
            if (++visits > MAX_VISITS) throw new IllegalStateException("FAST_CHAIN_GEOMETRY_BUDGET");
            EGraphNode node = pending.pop();
            if (!relation(node.getExactAlloyType())) return original;
            if (node.getOpcode() != EGraphNode.Opcode.JOIN) {
                leaves.add(node);
                continue;
            }
            List<EGraphNode> children = node.getChildren();
            if (children.size() != 2 || !relation(children.get(0).getExactAlloyType())
                    || !relation(children.get(1).getExactAlloyType())) return original;
            try {
                ExactAlloyType derived = ExactAlloyType.parserCertifiedRelationalJoin(
                        children.get(0).getExactAlloyType(), children.get(1).getExactAlloyType());
                if (!derived.sameOccurrenceEvidenceAs(node.getExactAlloyType())) return original;
            } catch (IllegalArgumentException unsupported) { return original; }
            pending.push(children.get(1));
            pending.push(children.get(0));
        }
        if (leaves.size() <= 2) return original;
        // Unary endpoints are allowed; unary interior operands are not associative.
        for (int i = 1; i + 1 < leaves.size(); i++)
            if (leaves.get(i).getExactAlloyType().relationArity() < 2) return original;
        try {
            ExactAlloyType folded = leaves.get(0).getExactAlloyType();
            for (int i = 1; i < leaves.size(); i++)
                folded = ExactAlloyType.parserCertifiedRelationalJoin(folded, leaves.get(i).getExactAlloyType());
            if (!folded.sameOccurrenceEvidenceAs(root.getExactAlloyType())) return original;
        } catch (IllegalArgumentException unsupported) { return original; }
        return List.copyOf(leaves);
    }

    private static List<EGraphNode> productChildren(EGraphNode root, List<EGraphNode> original) {
        if (original.size() != 2) return original;
        List<EGraphNode> leaves = new ArrayList<>();
        ArrayDeque<EGraphNode> pending = new ArrayDeque<>();
        pending.push(root);
        int visits = 0;
        while (!pending.isEmpty()) {
            if (++visits > MAX_VISITS) throw new IllegalStateException("FAST_PRODUCT_GEOMETRY_BUDGET");
            EGraphNode node = pending.pop();
            if (!singleProduct(node.getExactAlloyType())) return original;
            if (node.getOpcode() != EGraphNode.Opcode.ARROW) {
                leaves.add(node);
                continue;
            }
            List<EGraphNode> children = node.getChildren();
            if (children.size() != 2) return original;
            ExactAlloyType derived = productType(children.get(0).getExactAlloyType(), children.get(1).getExactAlloyType());
            if (derived == null || !derived.sameOccurrenceEvidenceAs(node.getExactAlloyType())) return original;
            pending.push(children.get(1));
            pending.push(children.get(0));
        }
        if (leaves.size() <= 2) return original;
        ExactAlloyType folded = leaves.get(0).getExactAlloyType();
        for (int i=1; i<leaves.size(); i++) {
            folded = productType(folded,leaves.get(i).getExactAlloyType());
            if (folded == null) return original;
        }
        return folded.sameOccurrenceEvidenceAs(root.getExactAlloyType()) ? List.copyOf(leaves) : original;
    }

    // Deliberately bounded single-product slice: no correlated-family Cartesian
    // expansion. Plain ARROW only; multiplicity-qualified opcodes stay barriers.
    private static boolean singleProduct(ExactAlloyType type) {
        return relation(type) && type.relationArity() > 0 && type.relationArity() <= 64
                && type.alternatives().size() <= 1;
    }

    private static ExactAlloyType productType(ExactAlloyType left, ExactAlloyType right) {
        if (!singleProduct(left) || !singleProduct(right)
                || left.relationArity() > 64 - right.relationArity()) return null;
        try { return ExactAlloyType.parserCertifiedCartesianProduct(List.of(left,right)); }
        catch (IllegalArgumentException unsupported) { return null; }
    }

    private static boolean relation(ExactAlloyType type) {
        return type != null && (type.kind() == ExactAlloyType.Kind.RELATION
                || type.kind() == ExactAlloyType.Kind.EMPTY_RELATION);
    }
}
