package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Structured identity of an admitted equation or signature axiom. */
public final class CertificateOrigin {
    public enum Kind {
        INPUT_EQUATION,
        REWRITE_AXIOM,
        SIGNATURE_CONTAINER_LAW,
        SIGNATURE_BINDER_AUTOMORPHISM
    }

    private final Kind kind;
    private final String sourceArtifact;
    private final String declarationId;
    private final int ordinal;
    private final StructuralKey structuralKey;

    private CertificateOrigin(
            Kind kind,
            String sourceArtifact,
            String declarationId,
            int ordinal) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.sourceArtifact = requireText(sourceArtifact, "sourceArtifact");
        this.declarationId = requireText(declarationId, "declarationId");
        if (ordinal < 0) {
            throw new IllegalArgumentException("Certificate origin ordinal must be nonnegative");
        }
        this.ordinal = ordinal;
        this.structuralKey = StructuralKey.of(
                "certificate-origin",
                Arrays.asList(
                        kind.name(), sourceArtifact, declarationId,
                        Integer.toString(ordinal)),
                java.util.Collections.emptyList());
    }

    public static CertificateOrigin inputEquation(
            String sourceArtifact,
            String equationId,
            int ordinal) {
        return new CertificateOrigin(
                Kind.INPUT_EQUATION, sourceArtifact, equationId, ordinal);
    }

    public static CertificateOrigin rewriteAxiom(
            String ruleSet,
            String ruleId,
            int ordinal) {
        return new CertificateOrigin(
                Kind.REWRITE_AXIOM, ruleSet, ruleId, ordinal);
    }

    public static CertificateOrigin containerLaw(
            String signatureId,
            String operatorPath,
            int ordinal) {
        return new CertificateOrigin(
                Kind.SIGNATURE_CONTAINER_LAW,
                signatureId,
                operatorPath,
                ordinal);
    }

    public static CertificateOrigin binderAutomorphism(
            String signatureId,
            String descriptorId,
            int ordinal) {
        return new CertificateOrigin(
                Kind.SIGNATURE_BINDER_AUTOMORPHISM,
                signatureId,
                descriptorId,
                ordinal);
    }

    private static String requireText(String value, String label) {
        Objects.requireNonNull(value, label);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    public Kind kind() {
        return kind;
    }

    public String sourceArtifact() {
        return sourceArtifact;
    }

    public String declarationId() {
        return declarationId;
    }

    public int ordinal() {
        return ordinal;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CertificateOrigin)) {
            return false;
        }
        CertificateOrigin origin = (CertificateOrigin) other;
        return kind == origin.kind
                && sourceArtifact.equals(origin.sourceArtifact)
                && declarationId.equals(origin.declarationId)
                && ordinal == origin.ordinal;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, sourceArtifact, declarationId, ordinal);
    }

    @Override
    public String toString() {
        return kind + ":" + sourceArtifact + ":" + declarationId + "#" + ordinal;
    }
}
