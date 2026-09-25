package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Structural Phase DA result {@code (K_G(n), iota_n, xi_n)}. */
public final class LeaderKernelResult {
    private final TypedENode source;
    private final TypedENode ambientLeaderNode;
    private final TypedENode kernel;
    private final TypedEmbedding inclusion;
    private final LeaderKernelTrace trace;
    private final StructuralKey structuralKey;

    LeaderKernelResult(
            TypedENode source,
            TypedENode ambientLeaderNode,
            TypedENode kernel,
            TypedEmbedding inclusion,
            LeaderKernelTrace trace) {
        this.source = Objects.requireNonNull(source, "source");
        this.ambientLeaderNode = Objects.requireNonNull(
                ambientLeaderNode, "ambientLeaderNode");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        this.inclusion = Objects.requireNonNull(inclusion, "inclusion");
        this.trace = Objects.requireNonNull(trace, "trace");
        validate();
        this.structuralKey = StructuralKey.branch(
                "leader-kernel-result",
                Arrays.asList(
                        source.structuralKey(),
                        ambientLeaderNode.structuralKey(),
                        kernel.structuralKey(),
                        TheoryKeys.embedding(inclusion),
                        trace.structuralKey()));
    }

    private void validate() {
        if (!source.context().equals(source.support())) {
            throw new IllegalArgumentException(
                    "Leader-kernel extraction requires exact source context");
        }
        if (!trace.source().equals(source)
                || !trace.ambientLeaderNode().equals(ambientLeaderNode)) {
            throw new IllegalArgumentException("Leader-kernel trace endpoints do not match result");
        }
        if (!source.operator().equals(kernel.operator())
                || !source.outputType().equals(kernel.outputType())) {
            throw new IllegalArgumentException("Leader-kernel extraction must preserve node type");
        }
        TypedSlotContext effective = ambientLeaderNode.support();
        if (!kernel.context().equals(effective)
                || !kernel.support().equals(effective)) {
            throw new IllegalArgumentException(
                    "Leader kernel must be represented in its exact effective context");
        }
        TypedENode expectedKernel = ExactContextRestrictor.restrictToSupport(
                ambientLeaderNode);
        if (!expectedKernel.equals(kernel)) {
            throw new IllegalArgumentException(
                    "Leader kernel is not the exact-context view of normalized syntax");
        }
        TypedEmbedding expectedInclusion = TypedEmbedding.inclusion(
                effective, source.context());
        if (!expectedInclusion.equals(inclusion)) {
            throw new IllegalArgumentException(
                    "Leader-kernel ambient transport must be the typed inclusion");
        }
    }

    public TypedENode source() {
        return source;
    }

    /** The post-find syntax before its ambient context is narrowed. */
    public TypedENode ambientLeaderNode() {
        return ambientLeaderNode;
    }

    /** The exact-context leader kernel {@code K_G(n)}. */
    public TypedENode kernel() {
        return kernel;
    }

    public TypedSlotContext effectiveSupport() {
        return kernel.context();
    }

    /** The typed inclusion {@code iota_n : Delta_n -> Gamma_0}. */
    public TypedEmbedding inclusion() {
        return inclusion;
    }

    /** Certificate-free structural provenance {@code xi_n}. */
    public LeaderKernelTrace trace() {
        return trace;
    }

    public boolean supportContracted() {
        return !effectiveSupport().equals(source.context());
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof LeaderKernelResult)) {
            return false;
        }
        LeaderKernelResult result = (LeaderKernelResult) other;
        return source.equals(result.source)
                && ambientLeaderNode.equals(result.ambientLeaderNode)
                && kernel.equals(result.kernel)
                && inclusion.equals(result.inclusion)
                && trace.equals(result.trace);
    }

    @Override
    public int hashCode() {
        return Objects.hash(source, ambientLeaderNode, kernel, inclusion, trace);
    }

    @Override
    public String toString() {
        return "leader-kernel[" + source.context() + " -> "
                + effectiveSupport() + ", " + trace + "]";
    }
}
