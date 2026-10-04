package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Complete structural result {@code (K,p,sigma,iota,omega,xi)} of {@code canon_G}. */
public final class CanonicalizationResult {
    private final LeaderKernelResult leaderKernel;
    private final CanonicalShape shape;
    private final TypedRenaming witness;
    private final TypedEmbedding ambientTransport;
    private final CanonicalizationMetrics metrics;
    private final StructuralKey structuralKey;

    CanonicalizationResult(
            LeaderKernelResult leaderKernel,
            CanonicalShape shape,
            TypedRenaming witness,
            CanonicalizationMetrics metrics) {
        this.leaderKernel = Objects.requireNonNull(leaderKernel, "leaderKernel");
        this.shape = Objects.requireNonNull(shape, "shape");
        this.witness = Objects.requireNonNull(witness, "witness");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (!shape.exactSlots().equals(witness.source())) {
            throw new IllegalArgumentException(
                    "Canonical shape slots must equal the witness source");
        }
        if (!leaderKernel.effectiveSupport().equals(witness.codomain())) {
            throw new IllegalArgumentException(
                    "Canonical witness must instantiate into the effective support");
        }
        if (!leaderKernel.kernel().operator().equals(shape.node().operator())
                || !leaderKernel.kernel().outputType().equals(shape.outputType())) {
            throw new IllegalArgumentException(
                    "Canonicalization must preserve the instantiated operator and output type");
        }
        this.ambientTransport = witness.andThen(leaderKernel.inclusion());
        if (!shape.exactSlots().equals(ambientTransport.source())
                || !leaderKernel.source().context().equals(ambientTransport.codomain())) {
            throw new IllegalArgumentException(
                    "Ambient transport must map the canonical context into the source context");
        }
        this.structuralKey = StructuralKey.branch(
                "canonicalization-result",
                Arrays.asList(
                        leaderKernel.structuralKey(),
                        shape.structuralKey(),
                        TheoryKeys.embedding(witness),
                        TheoryKeys.embedding(ambientTransport)));
    }

    public TypedENode source() {
        return leaderKernel.source();
    }

    /** The exact post-find kernel {@code K_G(n)}. */
    public TypedENode kernel() {
        return leaderKernel.kernel();
    }

    public LeaderKernelResult leaderKernel() {
        return leaderKernel;
    }

    public TypedSlotContext effectiveSupport() {
        return leaderKernel.effectiveSupport();
    }

    public CanonicalShape shape() {
        return shape;
    }

    public CanonicalShape canonicalShape() {
        return shape;
    }

    /** The effective-support renaming {@code sigma : Can(Delta) -> Delta}. */
    public TypedRenaming witness() {
        return witness;
    }

    public TypedRenaming effectiveRenaming() {
        return witness;
    }

    public TypedRenaming sigma() {
        return witness;
    }

    /** The exact inclusion {@code iota : Delta -> Gamma_0}. */
    public TypedEmbedding inclusion() {
        return leaderKernel.inclusion();
    }

    public TypedEmbedding iota() {
        return inclusion();
    }

    /** The ambient embedding {@code omega = iota o sigma}. */
    public TypedEmbedding ambientTransport() {
        return ambientTransport;
    }

    public TypedEmbedding omega() {
        return ambientTransport;
    }

    /** Certificate-free source-to-kernel provenance {@code xi}. */
    public LeaderKernelTrace trace() {
        return leaderKernel.trace();
    }

    public LeaderKernelTrace xi() {
        return trace();
    }

    public CanonicalizationMetrics metrics() {
        return metrics;
    }

    public boolean verifyWitness(TypedSlottedPortEGraph graph) {
        return TypedAlphaEquivalence.graphRelativeNodes(
                graph, shape.node(), kernel(), witness);
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CanonicalizationResult)) {
            return false;
        }
        CanonicalizationResult result = (CanonicalizationResult) other;
        return leaderKernel.equals(result.leaderKernel)
                && shape.equals(result.shape)
                && witness.equals(result.witness)
                && ambientTransport.equals(result.ambientTransport);
    }

    @Override
    public int hashCode() {
        return Objects.hash(leaderKernel, shape, witness, ambientTransport);
    }

    @Override
    public String toString() {
        return shape + " instantiated by " + witness
                + " and transported by " + ambientTransport;
    }
}
