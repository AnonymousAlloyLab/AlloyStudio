package is.fivefivefive.CanDis.theory;

import java.util.Objects;

/** Typed flat target paired with its concrete source-law replay. */
public final class CertifiedFlatConstruction {
    private final TypedENode node;
    private final OnePort singleton;
    private final FlatConstructionCertificate certificate;

    CertifiedFlatConstruction(
            TypedENode node,
            FlatConstructionCertificate certificate) {
        this.node = Objects.requireNonNull(node, "node");
        this.singleton = null;
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        if (!certificate.target().equals(node)) {
            throw new IllegalArgumentException(
                    "Flat construction certificate reaches another node");
        }
    }

    CertifiedFlatConstruction(
            OnePort singleton,
            FlatConstructionCertificate certificate) {
        this.node = null;
        this.singleton = Objects.requireNonNull(singleton, "singleton");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        if (!certificate.collapsedToSingleton()
                || !certificate.singletonTarget().equals(singleton)) {
            throw new IllegalArgumentException(
                    "Flat construction certificate proves another singleton target");
        }
    }

    public boolean collapsedToSingleton() { return singleton != null; }
    public TypedENode node() {
        if (node == null) {
            throw new IllegalStateException(
                    "This flat construction collapsed to its sole operand");
        }
        return node;
    }
    public OnePort singleton() {
        if (singleton == null) {
            throw new IllegalStateException(
                    "This flat construction retains an operator node");
        }
        return singleton;
    }
    public FlatConstructionCertificate certificate() { return certificate; }
}
