package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.Objects;

/** Trusted, typed leaf for one equation in {@code E}. */
public final class InputEquationCertificate extends TypedEqualityCertificate {
    private final CertificateOrigin origin;

    public InputEquationCertificate(
            CertificateOrigin origin,
            TypedCertificateEndpoint leftEndpoint,
            TypedCertificateEndpoint rightEndpoint) {
        super(
                categoryFor(Objects.requireNonNull(origin, "origin")),
                leftEndpoint,
                rightEndpoint,
                Collections.emptyList(),
                Collections.singletonList(origin.structuralKey()));
        this.origin = origin;
        verifyLocal();
    }

    public static InputEquationCertificate betweenInvocations(
            CertificateOrigin origin,
            TypedInvocation left,
            TypedInvocation right) {
        return new InputEquationCertificate(
                origin,
                TypedCertificateEndpoint.invocation(left),
                TypedCertificateEndpoint.invocation(right));
    }

    private static CertificateCategory categoryFor(CertificateOrigin origin) {
        if (origin.kind() == CertificateOrigin.Kind.INPUT_EQUATION) {
            return CertificateCategory.INPUT_EQUATION;
        }
        if (origin.kind() == CertificateOrigin.Kind.REWRITE_AXIOM) {
            return CertificateCategory.REWRITE_AXIOM;
        }
        throw new IllegalArgumentException(
                "InputEquationCertificate requires an input or rewrite origin");
    }

    public CertificateOrigin origin() {
        return origin;
    }

    @Override
    void verifyLocal() {
        if (sort().kind() != CertificateSort.Kind.TERM) {
            throw new IllegalStateException("Input equations must equate typed terms");
        }
        if (categoryFor(origin) != category()) {
            throw new IllegalStateException("Input equation origin/category mismatch");
        }
    }
}
