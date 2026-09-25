package is.fivefivefive.CanDis.theory;

import java.util.List;
import java.util.Objects;

/** Executable indexed structural and graph-relative typed alpha relations. */
public final class TypedAlphaEquivalence {
    private TypedAlphaEquivalence() {
    }

    public static boolean structuralNodes(
            TypedENode left,
            TypedENode right,
            TypedRenaming renaming) {
        CertificateVerifier.requireCertifiedAlphaNode(left);
        CertificateVerifier.requireCertifiedAlphaNode(right);
        return nodesRelated(null, false, left, right, renaming);
    }

    public static boolean structuralPorts(
            PortValue left,
            PortValue right,
            TypedRenaming renaming) {
        CertificateVerifier.requireCertifiedAlphaPort(left);
        CertificateVerifier.requireCertifiedAlphaPort(right);
        return portsRelated(null, false, left, right, renaming);
    }

    public static boolean graphRelativeNodes(
            TypedSlottedPortEGraph graph,
            TypedENode left,
            TypedENode right,
            TypedRenaming renaming) {
        Objects.requireNonNull(graph, "graph");
        synchronized (graph) {
            graph.requireQuiescentForCanonicalization();
            return nodesRelated(graph, true, left, right, renaming);
        }
    }

    public static boolean graphRelativePorts(
            TypedSlottedPortEGraph graph,
            PortValue left,
            PortValue right,
            TypedRenaming renaming) {
        Objects.requireNonNull(graph, "graph");
        synchronized (graph) {
            graph.requireQuiescentForCanonicalization();
            return portsRelated(graph, true, left, right, renaming);
        }
    }

    private static boolean nodesRelated(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            TypedENode left,
            TypedENode right,
            TypedRenaming renaming) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(renaming, "renaming");
        if (!left.context().equals(renaming.source())
                || !right.context().equals(renaming.codomain())
                || !left.operator().equals(right.operator())
                || !left.outputType().equals(right.outputType())
                || left.ports().size() != right.ports().size()) {
            return false;
        }
        for (int index = 0; index < left.ports().size(); index++) {
            if (!portsRelated(
                    graph,
                    graphRelative,
                    left.ports().get(index),
                    right.ports().get(index),
                    renaming)) {
                return false;
            }
        }
        return true;
    }

    private static boolean portsRelated(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            PortValue left,
            PortValue right,
            TypedRenaming renaming) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        Objects.requireNonNull(renaming, "renaming");
        if (!left.context().equals(renaming.source())
                || !right.context().equals(renaming.codomain())
                || !left.schema().equals(right.schema())
                || !left.getClass().equals(right.getClass())) {
            return false;
        }
        if (left instanceof OnePort) {
            return onePortsRelated(
                    graph,
                    graphRelative,
                    (OnePort) left,
                    (OnePort) right,
                    renaming);
        }
        if (left instanceof SeqPort) {
            return orderedRelated(
                    graph,
                    graphRelative,
                    ((SeqPort) left).elements(),
                    ((SeqPort) right).elements(),
                    renaming);
        }
        if (left instanceof BagPort) {
            return perfectlyMatched(
                    graph,
                    graphRelative,
                    ((BagPort) left).occurrences(),
                    ((BagPort) right).occurrences(),
                    renaming);
        }
        if (left instanceof SetPort) {
            List<PortValue> leftElements = ((SetPort) left).elements();
            List<PortValue> rightElements = ((SetPort) right).elements();
            return everyHasMate(
                    graph, graphRelative, leftElements, rightElements, renaming)
                    && everyHasMate(
                            graph, graphRelative, rightElements, leftElements, renaming.inverse());
        }
        if (left instanceof BindPort) {
            BindPort leftBinder = (BindPort) left;
            BindPort rightBinder = (BindPort) right;
            if (!leftBinder.boundSlot().type().equals(rightBinder.boundSlot().type())) {
                return false;
            }
            TypedRenaming extended = renaming.disjointExtension(
                    leftBinder.boundSlot(), rightBinder.boundSlot()).asRenaming();
            return portsRelated(
                    graph,
                    graphRelative,
                    leftBinder.body(),
                    rightBinder.body(),
                    extended);
        }
        if (left instanceof BindBlockPort) {
            return blocksRelated(
                    graph,
                    graphRelative,
                    (BindBlockPort) left,
                    (BindBlockPort) right,
                    renaming);
        }
        throw new IllegalStateException("Unhandled port value " + left.getClass().getName());
    }

    private static boolean blocksRelated(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            BindBlockPort left,
            BindBlockPort right,
            TypedRenaming freeRenaming) {
        BinderAutomorphismGroup group = graphRelative
                ? graph.binderGroupForCanonicalization(left.schema().descriptor())
                : left.schema().descriptor().automorphisms();
        return group.anyMatch(automorphism -> {
            TypedRenaming boundAlignment = left.descriptorToOccurrence()
                    .inverse()
                    .andThen(automorphism)
                    .andThen(right.descriptorToOccurrence());
            TypedRenaming extended = freeRenaming
                    .disjointUnion(boundAlignment)
                    .asRenaming();
            if (portsRelated(
                    graph,
                    graphRelative,
                    left.body(),
                    right.body(),
                    extended)) {
                return true;
            }
            return false;
        });
    }

    private static boolean onePortsRelated(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            OnePort left,
            OnePort right,
            TypedRenaming renaming) {
        PortLeaf leftLeaf = left.leaf();
        PortLeaf rightLeaf = right.leaf();
        if (!leftLeaf.getClass().equals(rightLeaf.getClass())) {
            return false;
        }
        if (leftLeaf instanceof SlotPortLeaf) {
            return renaming.apply(((SlotPortLeaf) leftLeaf).slot())
                    .equals(((SlotPortLeaf) rightLeaf).slot());
        }
        TypedInvocation leftInvocation = ((InvocationPortLeaf) leftLeaf).invocation();
        TypedInvocation rightInvocation = ((InvocationPortLeaf) rightLeaf).invocation();
        if (!graphRelative) {
            return leftInvocation.eclass().equals(rightInvocation.eclass())
                    && leftInvocation.embedding().andThen(renaming)
                            .equals(rightInvocation.embedding());
        }

        TypedFindResult leftFind = graph.findForCanonicalization(leftInvocation);
        TypedFindResult rightFind = graph.findForCanonicalization(rightInvocation);
        TypedInvocation leftLeader = leftFind.leaderInvocation();
        TypedInvocation rightLeader = rightFind.leaderInvocation();
        if (!leftLeader.eclass().equals(rightLeader.eclass())) {
            return false;
        }
        TypedEmbedding renamedLeft = leftLeader.embedding().andThen(renaming);
        TypedSymmetryGroup group = graph.symmetryGroupForCanonicalization(
                leftLeader.eclass());
        return group.anyMatch(permutation -> {
            TypedEmbedding permutedRight = permutation.andThen(rightLeader.embedding());
            if (renamedLeft.equals(permutedRight)) {
                return true;
            }
            return false;
        });
    }

    private static boolean orderedRelated(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            List<PortValue> left,
            List<PortValue> right,
            TypedRenaming renaming) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!portsRelated(
                    graph, graphRelative, left.get(index), right.get(index), renaming)) {
                return false;
            }
        }
        return true;
    }

    private static boolean perfectlyMatched(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            List<PortValue> left,
            List<PortValue> right,
            TypedRenaming renaming) {
        if (left.size() != right.size()) {
            return false;
        }
        int[] matchedLeft = new int[right.size()];
        java.util.Arrays.fill(matchedLeft, -1);
        for (int leftIndex = 0; leftIndex < left.size(); leftIndex++) {
            if (!augment(
                    graph,
                    graphRelative,
                    left,
                    right,
                    renaming,
                    leftIndex,
                    new boolean[right.size()],
                    matchedLeft)) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            List<PortValue> left,
            List<PortValue> right,
            TypedRenaming renaming,
            int leftIndex,
            boolean[] seen,
            int[] matchedLeft) {
        for (int rightIndex = 0; rightIndex < right.size(); rightIndex++) {
            if (seen[rightIndex]
                    || !portsRelated(
                            graph,
                            graphRelative,
                            left.get(leftIndex),
                            right.get(rightIndex),
                            renaming)) {
                continue;
            }
            seen[rightIndex] = true;
            if (matchedLeft[rightIndex] < 0
                    || augment(
                            graph,
                            graphRelative,
                            left,
                            right,
                            renaming,
                            matchedLeft[rightIndex],
                            seen,
                            matchedLeft)) {
                matchedLeft[rightIndex] = leftIndex;
                return true;
            }
        }
        return false;
    }

    private static boolean everyHasMate(
            TypedSlottedPortEGraph graph,
            boolean graphRelative,
            List<PortValue> source,
            List<PortValue> target,
            TypedRenaming renaming) {
        for (PortValue value : source) {
            boolean found = false;
            for (PortValue candidate : target) {
                if (portsRelated(graph, graphRelative, value, candidate, renaming)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }
}
