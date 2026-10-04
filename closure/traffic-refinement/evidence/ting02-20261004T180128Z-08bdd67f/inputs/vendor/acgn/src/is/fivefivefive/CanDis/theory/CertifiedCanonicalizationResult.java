package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/** Complete coherent-prefix result {@code (K,p,sigma,iota,omega,xi,d^w)}. */
public final class CertifiedCanonicalizationResult {
    private final CanonicalizationResult structural;
    private final KernelReplayCertificate kernelCertificate;
    private final TypedEqualityCertificate sourceConstruction;
    private final TypedEqualityCertificate sourceReplay;
    private final StructuralKey structuralKey;

    CertifiedCanonicalizationResult(
            CanonicalizationResult structural,
            KernelReplayCertificate kernelCertificate) {
        this(structural, kernelCertificate, null);
    }

    CertifiedCanonicalizationResult(
            CanonicalizationResult structural,
            KernelReplayCertificate kernelCertificate,
            TypedEqualityCertificate sourceConstruction) {
        this.structural = Objects.requireNonNull(structural, "structural");
        this.kernelCertificate = Objects.requireNonNull(
                kernelCertificate, "kernelCertificate");
        if (!structural.leaderKernel().equals(kernelCertificate.leaderKernel())) {
            throw new IllegalArgumentException(
                    "The structural and dependent canonicalization records disagree");
        }
        CertificateVerifier.verify(kernelCertificate);
        this.sourceConstruction = sourceConstruction;
        if (sourceConstruction == null) {
            this.sourceReplay = kernelCertificate;
        } else {
            CertificateVerifier.verify(sourceConstruction);
            TypedENode target = constructionTarget(sourceConstruction);
            if (!target.inExactSupportContext().equals(structural.source())) {
                throw new IllegalArgumentException(
                        "Concrete source construction reaches another canonicalization source");
            }
            TypedEmbedding inclusion = TypedEmbedding.inclusion(
                    structural.source().context(), target.context());
            TypedEqualityCertificate widenedKernel = EqualityCertificates.rename(
                    kernelCertificate, inclusion);
            this.sourceReplay = EqualityCertificates.transitive(
                    sourceConstruction, widenedKernel);
            CertificateVerifier.verify(this.sourceReplay);
        }
        this.structuralKey = StructuralKey.branch(
                "certified-canonicalization-result",
                sourceConstruction == null
                        ? Arrays.asList(
                                structural.structuralKey(),
                                kernelCertificate.structuralKey())
                        : Arrays.asList(
                                structural.structuralKey(),
                                kernelCertificate.structuralKey(),
                                sourceConstruction.structuralKey(),
                                sourceReplay.structuralKey()));
    }

    public CanonicalizationResult structural() {
        return structural;
    }

    public TypedENode kernel() {
        return structural.kernel();
    }

    public CanonicalShape shape() {
        return structural.shape();
    }

    public TypedRenaming sigma() {
        return structural.sigma();
    }

    public TypedEmbedding iota() {
        return structural.iota();
    }

    public TypedEmbedding omega() {
        return structural.omega();
    }

    public LeaderKernelTrace xi() {
        return structural.xi();
    }

    public KernelReplayCertificate d() {
        return kernelCertificate;
    }

    public Optional<TypedEqualityCertificate> sourceConstruction() {
        return Optional.ofNullable(sourceConstruction);
    }

    /** Proof from the concrete source application to the widened leader kernel. */
    public TypedEqualityCertificate sourceReplay() {
        return sourceReplay;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public String toString() {
        return structural + " with " + kernelCertificate;
    }

    private static TypedENode constructionTarget(
            TypedEqualityCertificate construction) {
        if (construction instanceof FlatConstructionCertificate) {
            FlatConstructionCertificate flat = (FlatConstructionCertificate) construction;
            if (flat.collapsedToSingleton()) {
                throw new IllegalArgumentException(
                        "A singleton construction has no canonicalization node");
            }
            return flat.target();
        }
        if (construction instanceof ContainerConstructionCertificate) {
            return ((ContainerConstructionCertificate) construction).target();
        }
        if (construction instanceof DependentChainCertificate) {
            return ((DependentChainCertificate) construction).target();
        }
        throw new IllegalArgumentException(
                "A certified canonicalization requires concrete construction evidence");
    }
}
