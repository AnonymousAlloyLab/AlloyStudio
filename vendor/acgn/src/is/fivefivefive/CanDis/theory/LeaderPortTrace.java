package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One recursively checked branch of a structural leader-kernel trace. */
public final class LeaderPortTrace {
    public enum Kind {
        SLOT,
        INVOCATION,
        SEQ,
        BAG,
        SET,
        BIND,
        BIND_BLOCK
    }

    private final Kind kind;
    private final PortValue sourcePort;
    private final PortValue normalizedPort;
    private final List<LeaderPortTrace> children;
    private final TypedFindResult findResult;
    private final ContainerNormalizationTrace containerNormalization;
    private final StructuralKey structuralKey;

    private LeaderPortTrace(
            Kind kind,
            PortValue sourcePort,
            PortValue normalizedPort,
            List<? extends LeaderPortTrace> children,
            TypedFindResult findResult,
            ContainerNormalizationTrace containerNormalization) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourcePort = Objects.requireNonNull(sourcePort, "sourcePort");
        this.normalizedPort = Objects.requireNonNull(normalizedPort, "normalizedPort");
        Objects.requireNonNull(children, "children");
        List<LeaderPortTrace> copied = new ArrayList<>(children.size());
        for (LeaderPortTrace child : children) {
            copied.add(Objects.requireNonNull(child, "child trace"));
        }
        this.children = Collections.unmodifiableList(copied);
        this.findResult = findResult;
        this.containerNormalization = containerNormalization;
        validate();
        this.structuralKey = buildStructuralKey();
    }

    static LeaderPortTrace slot(OnePort source) {
        return new LeaderPortTrace(
                Kind.SLOT, source, source, Collections.emptyList(), null, null);
    }

    static LeaderPortTrace invocation(
            OnePort source,
            OnePort normalized,
            TypedFindResult findResult) {
        return new LeaderPortTrace(
                Kind.INVOCATION,
                source,
                normalized,
                Collections.emptyList(),
                Objects.requireNonNull(findResult, "findResult"),
                null);
    }

    static LeaderPortTrace container(
            PortValue source,
            PortValue normalized,
            List<? extends LeaderPortTrace> children,
            ContainerNormalizationTrace normalization) {
        Kind kind;
        if (source instanceof SeqPort) {
            kind = Kind.SEQ;
        } else if (source instanceof BagPort) {
            kind = Kind.BAG;
        } else if (source instanceof SetPort) {
            kind = Kind.SET;
        } else {
            throw new IllegalArgumentException("Source is not a container port");
        }
        return new LeaderPortTrace(
                kind, source, normalized, children, null,
                Objects.requireNonNull(normalization, "normalization"));
    }

    static LeaderPortTrace bind(
            BindPort source,
            BindPort normalized,
            LeaderPortTrace body) {
        return new LeaderPortTrace(
                Kind.BIND,
                source,
                normalized,
                Collections.singletonList(body),
                null,
                null);
    }

    static LeaderPortTrace bindBlock(
            BindBlockPort source,
            BindBlockPort normalized,
            LeaderPortTrace body) {
        return new LeaderPortTrace(
                Kind.BIND_BLOCK,
                source,
                normalized,
                Collections.singletonList(body),
                null,
                null);
    }

    private void validate() {
        if (!sourcePort.schema().equals(normalizedPort.schema())
                || !sourcePort.context().equals(normalizedPort.context())) {
            throw new IllegalArgumentException(
                    "Leader normalization must preserve port schema and ambient context");
        }
        switch (kind) {
            case SLOT:
                requireNoAuxiliaryData();
                requireSlotTrace();
                break;
            case INVOCATION:
                requireInvocationTrace();
                break;
            case SEQ:
            case BAG:
            case SET:
                requireContainerTrace();
                break;
            case BIND:
                requireBindTrace();
                break;
            case BIND_BLOCK:
                requireBindBlockTrace();
                break;
            default:
                throw new IllegalStateException("Unhandled leader trace kind " + kind);
        }
    }

    private void requireNoAuxiliaryData() {
        if (!children.isEmpty() || findResult != null || containerNormalization != null) {
            throw new IllegalArgumentException("Atomic slot trace cannot carry child provenance");
        }
    }

    private void requireSlotTrace() {
        if (!(sourcePort instanceof OnePort)
                || !(((OnePort) sourcePort).leaf() instanceof SlotPortLeaf)
                || !sourcePort.equals(normalizedPort)) {
            throw new IllegalArgumentException("Slot trace must preserve one atomic slot");
        }
    }

    private void requireInvocationTrace() {
        if (!(sourcePort instanceof OnePort) || !(normalizedPort instanceof OnePort)
                || !(((OnePort) sourcePort).leaf() instanceof InvocationPortLeaf)
                || !(((OnePort) normalizedPort).leaf() instanceof InvocationPortLeaf)
                || !children.isEmpty() || findResult == null
                || containerNormalization != null) {
            throw new IllegalArgumentException("Invocation trace has malformed provenance");
        }
        TypedInvocation source = ((InvocationPortLeaf) ((OnePort) sourcePort).leaf())
                .invocation();
        TypedInvocation normalized = ((InvocationPortLeaf) ((OnePort) normalizedPort).leaf())
                .invocation();
        if (!source.equals(findResult.originalInvocation())
                || !normalized.equals(findResult.leaderInvocation())) {
            throw new IllegalArgumentException(
                    "Invocation trace endpoints must equal its retained find result");
        }
    }

    private void requireContainerTrace() {
        if (findResult != null || containerNormalization == null) {
            throw new IllegalArgumentException("Container trace requires only normalization data");
        }
        List<PortValue> sources = elements(sourcePort);
        List<PortValue> normalizedChildren = new ArrayList<>(children.size());
        if (sources.size() != children.size()) {
            throw new IllegalArgumentException("Container trace must cover every source occurrence");
        }
        for (int index = 0; index < children.size(); index++) {
            LeaderPortTrace child = children.get(index);
            if (!sources.get(index).equals(child.sourcePort())) {
                throw new IllegalArgumentException(
                        "Container trace child is not aligned with source occurrence order");
            }
            normalizedChildren.add(child.normalizedPort());
        }
        ContainerNormalizationTrace expected = ContainerNormalizationTrace.of(
                sourcePort, normalizedChildren, normalizedPort);
        if (!expected.equals(containerNormalization)) {
            throw new IllegalArgumentException("Container normalization trace is inconsistent");
        }
        if ((kind == Kind.SEQ) != (sourcePort instanceof SeqPort)
                || (kind == Kind.BAG) != (sourcePort instanceof BagPort)
                || (kind == Kind.SET) != (sourcePort instanceof SetPort)) {
            throw new IllegalArgumentException("Container trace kind does not match its port");
        }
    }

    private void requireBindTrace() {
        if (!(sourcePort instanceof BindPort) || !(normalizedPort instanceof BindPort)
                || children.size() != 1 || findResult != null
                || containerNormalization != null) {
            throw new IllegalArgumentException("Unary binder trace is malformed");
        }
        BindPort source = (BindPort) sourcePort;
        BindPort normalized = (BindPort) normalizedPort;
        LeaderPortTrace body = children.get(0);
        if (!source.boundSlot().equals(normalized.boundSlot())
                || !source.body().equals(body.sourcePort())
                || !normalized.body().equals(body.normalizedPort())) {
            throw new IllegalArgumentException(
                    "Leader extraction must preserve the unary binder occurrence");
        }
    }

    private void requireBindBlockTrace() {
        if (!(sourcePort instanceof BindBlockPort)
                || !(normalizedPort instanceof BindBlockPort)
                || children.size() != 1 || findResult != null
                || containerNormalization != null) {
            throw new IllegalArgumentException("Binder-block trace is malformed");
        }
        BindBlockPort source = (BindBlockPort) sourcePort;
        BindBlockPort normalized = (BindBlockPort) normalizedPort;
        LeaderPortTrace body = children.get(0);
        if (!source.descriptorToOccurrence().equals(normalized.descriptorToOccurrence())
                || !source.body().equals(body.sourcePort())
                || !normalized.body().equals(body.normalizedPort())) {
            throw new IllegalArgumentException(
                    "Leader extraction must preserve the complete binder-block occurrence");
        }
    }

    private StructuralKey buildStructuralKey() {
        List<StructuralKey> parts = new ArrayList<>();
        parts.add(StructuralKey.branch(
                "leader-port-trace/source",
                Collections.singletonList(sourcePort.structuralKey())));
        parts.add(StructuralKey.branch(
                "leader-port-trace/normalized",
                Collections.singletonList(normalizedPort.structuralKey())));
        if (findResult != null) {
            parts.add(findResult.structuralKey());
        }
        if (containerNormalization != null) {
            parts.add(containerNormalization.structuralKey());
        }
        for (LeaderPortTrace child : children) {
            parts.add(child.structuralKey());
        }
        return StructuralKey.of(
                "leader-port-trace",
                Collections.singletonList(kind.name()),
                parts);
    }

    private static List<PortValue> elements(PortValue port) {
        if (port instanceof SeqPort) {
            return ((SeqPort) port).elements();
        }
        if (port instanceof BagPort) {
            return ((BagPort) port).occurrences();
        }
        if (port instanceof SetPort) {
            return ((SetPort) port).elements();
        }
        throw new IllegalArgumentException("Port is not a container");
    }

    void collectFindResults(List<TypedFindResult> target) {
        if (findResult != null) {
            target.add(findResult);
        }
        for (LeaderPortTrace child : children) {
            child.collectFindResults(target);
        }
    }

    void collectContainerNormalizations(List<ContainerNormalizationTrace> target) {
        for (LeaderPortTrace child : children) {
            child.collectContainerNormalizations(target);
        }
        if (containerNormalization != null) {
            target.add(containerNormalization);
        }
    }

    public Kind kind() {
        return kind;
    }

    public PortValue sourcePort() {
        return sourcePort;
    }

    public PortValue normalizedPort() {
        return normalizedPort;
    }

    public List<LeaderPortTrace> children() {
        return children;
    }

    public Optional<TypedFindResult> findResult() {
        return Optional.ofNullable(findResult);
    }

    public Optional<ContainerNormalizationTrace> containerNormalization() {
        return Optional.ofNullable(containerNormalization);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LeaderPortTrace)) {
            return false;
        }
        LeaderPortTrace trace = (LeaderPortTrace) other;
        return kind == trace.kind
                && sourcePort.equals(trace.sourcePort)
                && normalizedPort.equals(trace.normalizedPort)
                && children.equals(trace.children)
                && Objects.equals(findResult, trace.findResult)
                && Objects.equals(containerNormalization, trace.containerNormalization);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind, sourcePort, normalizedPort, children,
                findResult, containerNormalization);
    }

    @Override
    public String toString() {
        return kind + Arrays.asList(sourcePort, normalizedPort).toString();
    }
}
