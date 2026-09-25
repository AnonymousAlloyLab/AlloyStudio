package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/** Typed proof of one parent assignment {@code w_a = m . w_b}. */
public final class ParentEdgeCertificate extends TypedEqualityCertificate {
    private final TypedEClassInterface child;
    private final TypedInvocation parentInvocation;
    private final TypedEqualityCertificate endpointDerivation;

    public ParentEdgeCertificate(
            TypedEClassInterface child,
            TypedInvocation parentInvocation,
            TypedEqualityCertificate endpointDerivation) {
        this(build(child, parentInvocation, endpointDerivation));
    }

    private ParentEdgeCertificate(Build build) {
        super(
                CertificateCategory.PARENT_EDGE,
                build.left,
                build.right,
                Collections.singletonList(build.derivation),
                Arrays.asList(
                        TheoryKeys.eclass(build.child),
                        TheoryKeys.invocation(build.parentInvocation)));
        this.child = build.child;
        this.parentInvocation = build.parentInvocation;
        this.endpointDerivation = build.derivation;
        verifyLocal();
    }

    private static Build build(
            TypedEClassInterface child,
            TypedInvocation parentInvocation,
            TypedEqualityCertificate endpointDerivation) {
        Objects.requireNonNull(child, "child");
        Objects.requireNonNull(parentInvocation, "parentInvocation");
        new ParentStep(child, parentInvocation);
        TypedCertificateEndpoint left = TypedCertificateEndpoint.eclassWitness(child);
        TypedCertificateEndpoint right = TypedCertificateEndpoint.invocation(parentInvocation);
        CertificateVerifier.verify(Objects.requireNonNull(
                endpointDerivation, "endpointDerivation"));
        CertificateVerifier.requirePermittedUnionDerivation(endpointDerivation);
        TypedEqualityCertificate oriented = EqualityCertificates.orient(
                endpointDerivation, left, right);
        return new Build(child, parentInvocation, left, right, oriented);
    }

    public TypedEClassInterface child() {
        return child;
    }

    public TypedInvocation parentInvocation() {
        return parentInvocation;
    }

    public TypedEClassInterface parent() {
        return parentInvocation.eclass();
    }

    public TypedEmbedding embedding() {
        return parentInvocation.embedding();
    }

    public TypedEqualityCertificate endpointDerivation() {
        return endpointDerivation;
    }

    @Override
    void verifyLocal() {
        if (!leftEndpoint().equals(TypedCertificateEndpoint.eclassWitness(child))
                || !rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(parentInvocation))) {
            throw new IllegalStateException("Malformed parent-edge certificate endpoints");
        }
        CertificateVerifier.requirePermittedUnionDerivation(endpointDerivation);
    }

    private static final class Build {
        private final TypedEClassInterface child;
        private final TypedInvocation parentInvocation;
        private final TypedCertificateEndpoint left;
        private final TypedCertificateEndpoint right;
        private final TypedEqualityCertificate derivation;

        private Build(
                TypedEClassInterface child,
                TypedInvocation parentInvocation,
                TypedCertificateEndpoint left,
                TypedCertificateEndpoint right,
                TypedEqualityCertificate derivation) {
            this.child = child;
            this.parentInvocation = parentInvocation;
            this.left = left;
            this.right = right;
            this.derivation = derivation;
        }
    }
}
