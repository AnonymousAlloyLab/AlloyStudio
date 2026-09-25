package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** A normalized nonflat container node paired with its concrete source replay. */
public final class CertifiedContainerConstruction {
    private final TypedENode node;
    private final ContainerConstructionCertificate certificate;

    CertifiedContainerConstruction(
            TypedENode node,
            ContainerConstructionCertificate certificate) {
        this.node = Objects.requireNonNull(node, "node");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        if (!TypedCertificateEndpoint.node(node).equals(certificate.rightEndpoint())) {
            throw new IllegalArgumentException(
                    "Container construction certificate proves another target node");
        }
    }

    public TypedENode node() {
        return node;
    }

    public ContainerConstructionCertificate certificate() {
        return certificate;
    }
}
