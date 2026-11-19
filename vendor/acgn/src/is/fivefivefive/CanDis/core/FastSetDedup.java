package is.fivefivefive.CanDis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.json.JSONArray;
import org.json.JSONObject;

import is.fivefivefive.CanDis.WorkBudget;
import is.fivefivefive.CanDis.theory.AlloyTypeBridge;
import is.fivefivefive.CanDis.theory.GraphType;
import is.fivefivefive.CanDis.theory.SiblingQuotient;

/**
 * SET deduplication after the owner-local orbit has been exposed.
 *
 * <p>Only order-insensitive idempotent SET children are deduplicated. Two
 * children are equal when some admitted owner-local representative pair is
 * structurally equal on every metric field; the retained child is the first
 * source-order occurrence. Ordered children, BAG multiplicity, unequal terms,
 * paid declaration actions and declaration arities are not touched. The
 * eliminated duplicate is recorded with its retained counterpart.
 */
final class FastSetDedup {
    private static final int MAX_COMPARISON_STEPS = 1_000_000;

    private FastSetDedup() {
    }

    record Result(EGraphNode root, JSONArray evidence) { }

    static Result dedup(EGraphNode root, Map<EGraphNode, List<EGraphNode>> alternatives) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(alternatives, "alternatives");
        var rewritten = new IdentityHashMap<EGraphNode, EGraphNode>();
        var evidence = new JSONArray();
        EGraphNode result = rewrite(root, alternatives, rewritten, evidence, "$");
        return new Result(result, evidence);
    }

    private static EGraphNode rewrite(
            EGraphNode node,
            Map<EGraphNode, List<EGraphNode>> alternatives,
            IdentityHashMap<EGraphNode, EGraphNode> rewritten,
            JSONArray evidence,
            String path) {
        WorkBudget.charge(1);
        EGraphNode remembered = rewritten.get(node);
        if (remembered != null) {
            return remembered;
        }
        List<EGraphNode> sourceChildren = node.getChildren();
        List<EGraphNode> children = new ArrayList<>(sourceChildren.size());
        List<Integer> retainedIndices = null;
        boolean changed = false;
        for (int index = 0; index < sourceChildren.size(); index++) {
            EGraphNode child = rewrite(sourceChildren.get(index), alternatives,
                    rewritten, evidence, path + "/" + index);
            children.add(child);
            changed |= child != sourceChildren.get(index);
        }
        if (isSet(node) && children.size() > 1) {
            List<EGraphNode> retained = new ArrayList<>(children.size());
            List<Integer> retainedSource = new ArrayList<>(children.size());
            int[] budget = new int[]{MAX_COMPARISON_STEPS};
            for (int index = 0; index < children.size(); index++) {
                EGraphNode candidate = children.get(index);
                boolean duplicate = false;
                for (int kept = 0; kept < retained.size(); kept++) {
                    if (sameModuloOrbit(retained.get(kept), candidate, alternatives, budget)) {
                        duplicate = true;
                        evidence.put(new JSONObject()
                                .put("path", path)
                                .put("operator", node.getOpcode().name())
                                .put("retainedIndex", retainedSource.get(kept))
                                .put("droppedIndex", index)
                                .put("retainedHead", retained.get(kept).getOpcode().name())
                                .put("droppedHead", candidate.getOpcode().name())
                                .put("orderInsensitive", true)
                                .put("bagMultiplicityTouched", false));
                        break;
                    }
                }
                if (!duplicate) {
                    retained.add(candidate);
                    retainedSource.add(index);
                }
            }
            if (retained.size() != children.size()) {
                children = retained;
                retainedIndices = retainedSource;
                changed = true;
            }
        }
        EGraphNode result = changed
                ? EGraphNode.comparisonWithChildren(node, children,
                        retainedIndices == null ? identityIndices(children.size()) : retainedIndices)
                : node;
        rewritten.put(node, result);
        return result;
    }

    private static List<Integer> identityIndices(int size) {
        List<Integer> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) result.add(index);
        return result;
    }

    private static boolean isSet(EGraphNode node) {
        return node.isOrderInsensitive()
                && node.getSiblingQuotient()
                        == SiblingQuotient.COMMUTATIVE_IDEMPOTENT_SET;
    }

    /**
     * Structural equality modulo the owner-local orbit. A hash or a scalar
     * distance is never used as equality.
     */
    static boolean sameModuloOrbit(
            EGraphNode left, EGraphNode right,
            Map<EGraphNode, List<EGraphNode>> alternatives, int[] budget) {
        WorkBudget.charge(1);
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (--budget[0] < 0) {
            throw new is.fivefivefive.CanDis.metric.TraceResources.Exhausted("FAST_SET_DEDUP_BUDGET");
        }
        List<EGraphNode> leftAlternatives = alternatives.getOrDefault(left, List.of());
        List<EGraphNode> rightAlternatives = alternatives.getOrDefault(right, List.of());
        if (!leftAlternatives.isEmpty() || !rightAlternatives.isEmpty()) {
            List<EGraphNode> leftCandidates = leftAlternatives.isEmpty()
                    ? List.of(left) : leftAlternatives;
            List<EGraphNode> rightCandidates = rightAlternatives.isEmpty()
                    ? List.of(right) : rightAlternatives;
            for (EGraphNode leftCandidate : leftCandidates) {
                for (EGraphNode rightCandidate : rightCandidates) {
                    if (sameModuloOrbit(leftCandidate, rightCandidate, alternatives, budget)) {
                        return true;
                    }
                }
            }
            return false;
        }
        if (left.getOpcode() != right.getOpcode()
                || left.getMetatype() != right.getMetatype()
                || left.isOrderInsensitive() != right.isOrderInsensitive()
                || left.getFlexibleArityKind() != right.getFlexibleArityKind()
                || left.getSiblingQuotient() != right.getSiblingQuotient()
                || !Objects.equals(metricSemanticKey(left), metricSemanticKey(right))) {
            return false;
        }
        List<EGraphNode> leftChildren = left.getChildren();
        List<EGraphNode> rightChildren = right.getChildren();
        if (leftChildren.size() != rightChildren.size()) {
            return false;
        }
        if (isDeclaration(left)) {
            // A declaration group binds a set of tuple slots over one shared
            // domain. Source slot order is not part of the binding identity; the
            // body references still have to agree exactly below.
            if (leftChildren.isEmpty() || rightChildren.isEmpty()) return false;
            if (!sameModuloOrbit(leftChildren.get(0), rightChildren.get(0),
                    alternatives, budget)) {
                return false;
            }
            boolean[] used = new boolean[rightChildren.size()];
            used[0] = true;
            return matchRange(1, leftChildren, rightChildren, used, alternatives, budget);
        }
        if (!left.isOrderInsensitive()) {
            for (int index = 0; index < leftChildren.size(); index++) {
                if (!sameModuloOrbit(leftChildren.get(index), rightChildren.get(index),
                        alternatives, budget)) {
                    return false;
                }
            }
            return true;
        }
        boolean[] used = new boolean[rightChildren.size()];
        return matchChildren(0, leftChildren, rightChildren, used, alternatives, budget);
    }

    /**
     * The metric label of a comparison node, mirroring the certified repair
     * payload: variable coordinates are local alpha names; source scopes on
     * binder declarations are not part of the semantic label; atoms retain
     * identity and result type.
     */
    private static String metricSemanticKey(EGraphNode node) {
        switch (node.getOpcode()) {
            case VARIABLE:
                return "V|" + String.valueOf(node.getAlphaName());
            case GENERICRELDECL:
            case DISJ:
            case VAR:
            case DISJVAR:
                return "D|structure=certified-local-declaration";
            case GLOBALBINDING: {
                String identity = node.getSemanticIdentity() == null
                        ? String.valueOf(node.getSourceName()) : node.getSemanticIdentity();
                return "G|" + identity + "|" + graphType(node);
            }
            case CONSTANT:
            case REF:
            case SHADOW:
                return "C|" + String.valueOf(node.getSourceName()) + "|" + graphType(node);
            case CALL:
                return "K|" + CallMetadata.semanticKey(node) + "|" + graphType(node);
            default:
                return "O|" + node.getOpcode().name() + "|" + graphType(node);
        }
    }

    private static GraphType graphType(EGraphNode node) {
        if (node.getMetatype() == EGraphNode.Metatype.BOOLEAN) return GraphType.BOOL;
        if (node.getOpcode() == EGraphNode.Opcode.CARDINALITY
                || node.getOpcode() == EGraphNode.Opcode.CAST2INT) return GraphType.INT;
        if (node.getExactAlloyType() == null) return null;
        return AlloyTypeBridge.graphType(node.getExactAlloyType());
    }

    private static boolean isDeclaration(EGraphNode node) {
        EGraphNode.Opcode opcode = node.getOpcode();
        return opcode == EGraphNode.Opcode.GENERICRELDECL
                || opcode == EGraphNode.Opcode.DISJ
                || opcode == EGraphNode.Opcode.VAR
                || opcode == EGraphNode.Opcode.DISJVAR;
    }

    private static boolean matchRange(
            int index, List<EGraphNode> left, List<EGraphNode> right, boolean[] used,
            Map<EGraphNode, List<EGraphNode>> alternatives, int[] budget) {
        if (index == left.size()) return true;
        for (int candidate = 1; candidate < right.size(); candidate++) {
            WorkBudget.charge(1);
            if (used[candidate]) continue;
            if (!sameModuloOrbit(left.get(index), right.get(candidate), alternatives, budget)) {
                continue;
            }
            used[candidate] = true;
            if (matchRange(index + 1, left, right, used, alternatives, budget)) return true;
            used[candidate] = false;
        }
        return false;
    }

    private static boolean matchChildren(
            int index, List<EGraphNode> left, List<EGraphNode> right, boolean[] used,
            Map<EGraphNode, List<EGraphNode>> alternatives, int[] budget) {
        if (index == left.size()) return true;
        for (int candidate = 0; candidate < right.size(); candidate++) {
            WorkBudget.charge(1);
            if (used[candidate]) continue;
            if (!sameModuloOrbit(left.get(index), right.get(candidate), alternatives, budget)) {
                continue;
            }
            used[candidate] = true;
            if (matchChildren(index + 1, left, right, used, alternatives, budget)) {
                return true;
            }
            used[candidate] = false;
        }
        return false;
    }
}
