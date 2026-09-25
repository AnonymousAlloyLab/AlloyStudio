package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** One canonical dependent-chain node paired with its exact source association. */
public final class CertifiedDependentChainConstruction {
    private final TypedENode node;
    private final DependentChainCertificate certificate;

    CertifiedDependentChainConstruction(
            TypedENode node,
            DependentChainCertificate certificate) {
        this.node = Objects.requireNonNull(node, "node");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        if (!TypedCertificateEndpoint.node(node).equals(
                certificate.rightEndpoint())) {
            throw new IllegalArgumentException(
                    "Dependent-chain certificate proves another target node");
        }
    }

    public TypedENode node() { return node; }
    public DependentChainCertificate certificate() { return certificate; }
}
