package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Certificate-free structural provenance {@code xi_n} for leader extraction. */
public final class LeaderKernelTrace {
    private final TypedENode source;
    private final TypedENode ambientLeaderNode;
    private final List<LeaderPortTrace> portTraces;
    private final List<TypedFindResult> findResults;
    private final List<ContainerNormalizationTrace> containerNormalizations;
    private final StructuralKey structuralKey;

    LeaderKernelTrace(
            TypedENode source,
            TypedENode ambientLeaderNode,
            List<? extends LeaderPortTrace> portTraces) {
        this.source = Objects.requireNonNull(source, "source");
        this.ambientLeaderNode = Objects.requireNonNull(
                ambientLeaderNode, "ambientLeaderNode");
        Objects.requireNonNull(portTraces, "portTraces");
        List<LeaderPortTrace> copied = new ArrayList<>(portTraces.size());
        for (LeaderPortTrace trace : portTraces) {
            copied.add(Objects.requireNonNull(trace, "port trace"));
        }
        this.portTraces = Collections.unmodifiableList(copied);
        validateEndpoints();

        List<TypedFindResult> finds = new ArrayList<>();
        List<ContainerNormalizationTrace> normalizations = new ArrayList<>();
        for (LeaderPortTrace trace : copied) {
            trace.collectFindResults(finds);
            trace.collectContainerNormalizations(normalizations);
        }
        this.findResults = Collections.unmodifiableList(finds);
        this.containerNormalizations = Collections.unmodifiableList(normalizations);
        this.structuralKey = buildStructuralKey();
    }

    private void validateEndpoints() {
        if (!source.operator().equals(ambientLeaderNode.operator())
                || !source.outputType().equals(ambientLeaderNode.outputType())
                || !source.context().equals(ambientLeaderNode.context())) {
            throw new IllegalArgumentException(
                    "Leader trace must preserve node head, output type, and ambient context");
        }
        if (source.ports().size() != portTraces.size()) {
            throw new IllegalArgumentException("Leader trace must cover every node port");
        }
        List<PortValue> normalizedPorts = new ArrayList<>(portTraces.size());
        for (int index = 0; index < portTraces.size(); index++) {
            LeaderPortTrace trace = portTraces.get(index);
            if (!source.ports().get(index).equals(trace.sourcePort())) {
                throw new IllegalArgumentException(
                        "Leader trace port is not aligned with its source node");
            }
            normalizedPorts.add(trace.normalizedPort());
        }
        TypedENode reconstructed = source.rebuildCanonicalCandidate(
                source.context(), normalizedPorts);
        if (!reconstructed.equals(ambientLeaderNode)) {
            throw new IllegalArgumentException(
                    "Leader trace does not reconstruct its ambient normalized node");
        }
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> children = new ArrayList<>(portTraces.size() + 2);
        children.add(StructuralKey.branch(
                "leader-kernel-trace/source",
                Collections.singletonList(source.structuralKey())));
        children.add(StructuralKey.branch(
                "leader-kernel-trace/ambient-leader",
                Collections.singletonList(ambientLeaderNode.structuralKey())));
        for (LeaderPortTrace trace : portTraces) {
            children.add(trace.structuralKey());
        }
        return StructuralKey.branch("leader-kernel-trace", children);
    }

    public TypedENode source() {
        return source;
    }

    /** Leader-normalized syntax still declared in the original ambient context. */
    public TypedENode ambientLeaderNode() {
        return ambientLeaderNode;
    }

    public List<LeaderPortTrace> portTraces() {
        return portTraces;
    }

    /** Find results in deterministic source-tree order, including identity paths. */
    public List<TypedFindResult> findResults() {
        return findResults;
    }

    /** Container steps in deterministic bottom-up replay order. */
    public List<ContainerNormalizationTrace> containerNormalizations() {
        return containerNormalizations;
    }

    public boolean isStructuralIdentity() {
        return source.equals(ambientLeaderNode);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LeaderKernelTrace)) {
            return false;
        }
        LeaderKernelTrace trace = (LeaderKernelTrace) other;
        return source.equals(trace.source)
                && ambientLeaderNode.equals(trace.ambientLeaderNode)
                && portTraces.equals(trace.portTraces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, ambientLeaderNode, portTraces);
    }

    @Override
    public String toString() {
        return "xi(" + findResults.size() + " finds, "
                + containerNormalizations.size() + " containers)";
    }
}
