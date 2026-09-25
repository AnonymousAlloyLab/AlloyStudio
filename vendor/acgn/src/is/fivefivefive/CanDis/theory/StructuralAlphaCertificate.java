package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Definitional alpha transport between node endpoints in one typed context. */
public final class StructuralAlphaCertificate extends TypedEqualityCertificate {
    private final TypedENode left;
    private final TypedENode right;

    private StructuralAlphaCertificate(TypedENode left, TypedENode right) {
        super(
                CertificateCategory.STRUCTURAL_ALPHA,
                TypedCertificateEndpoint.node(left),
                TypedCertificateEndpoint.node(right),
                Collections.emptyList(),
                java.util.Arrays.asList(left.structuralKey(), right.structuralKey()));
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        verifyLocal();
    }

    static TypedEqualityCertificate create(TypedENode left, TypedENode right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        if (left.equals(right)) {
            return EqualityCertificates.reflexive(
                    TypedCertificateEndpoint.node(left));
        }
        TypedRenaming identity = TypedRenaming.identity(left.context());
        if (!TypedAlphaEquivalence.structuralNodes(left, right, identity)) {
            throw new IllegalArgumentException(
                    "Structural alpha transport requires exact typed alpha-equivalence");
        }
        StructuralAlphaCertificate result = new StructuralAlphaCertificate(left, right);
        CertificateVerifier.verify(result);
        return result;
    }

    @Override
    void verifyLocal() {
        if (left.equals(right)
                || !left.context().equals(right.context())
                || !left.outputType().equals(right.outputType())
                || !TypedAlphaEquivalence.structuralNodes(
                        left, right, TypedRenaming.identity(left.context()))) {
            throw new IllegalStateException("Malformed structural-alpha certificate");
        }
    }
}
