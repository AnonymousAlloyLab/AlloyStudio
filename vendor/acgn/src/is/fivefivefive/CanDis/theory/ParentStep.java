package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** One historical non-root assignment {@code U(a)=m*b} with optional setup provenance. */
public final class ParentStep {
    private final TypedEClassInterface child;
    private final TypedInvocation parentInvocation;
    private final ParentEdgeCertificate certificate;
    private final StructuralKey structuralKey;

    /** Structural-only constructor retained inside the theory package for Phase D fixtures. */
    ParentStep(
            TypedEClassInterface child,
            TypedInvocation parentInvocation) {
        this(child, parentInvocation, null);
    }

    private ParentStep(
            TypedEClassInterface child,
            TypedInvocation parentInvocation,
            ParentEdgeCertificate certificate) {
        this.child = Objects.requireNonNull(child, "child");
        this.parentInvocation = Objects.requireNonNull(
                parentInvocation, "parentInvocation");
        this.certificate = certificate;
        if (child.id().equals(parentInvocation.eclass().id())) {
            throw new IllegalArgumentException("A non-root parent step must change e-class id");
        }
        if (!child.outputType().equals(parentInvocation.outputType())) {
            throw new IllegalArgumentException("A parent step must preserve the e-class output type");
        }
        if (!child.exposedSlots().equals(parentInvocation.callerContext())) {
            throw new IllegalArgumentException(
                    "A parent embedding must map the parent interface into the child interface");
        }
        java.util.List<StructuralKey> children = new java.util.ArrayList<>();
        children.add(TheoryKeys.eclass(child));
        children.add(TheoryKeys.invocation(parentInvocation));
        if (certificate != null) {
            CertificateVerifier.verifyParentEdge(certificate);
            if (!child.equals(certificate.child())
                    || !parentInvocation.equals(certificate.parentInvocation())) {
                throw new IllegalArgumentException(
                        "Parent-edge certificate endpoints differ from the parent step");
            }
            children.add(certificate.structuralKey());
        }
        this.structuralKey = StructuralKey.branch("parent-step", children);
    }

    public static ParentStep certified(ParentEdgeCertificate certificate) {
        ParentEdgeCertificate checked = Objects.requireNonNull(certificate, "certificate");
        CertificateVerifier.verifyParentEdge(checked);
        return new ParentStep(
                checked.child(), checked.parentInvocation(), checked);
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

    public boolean hasCertificate() {
        return certificate != null;
    }

    public ParentEdgeCertificate certificate() {
        if (certificate == null) {
            throw new IllegalStateException(
                    "Structural Phase D parent step has no equality certificate");
        }
        return certificate;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ParentStep)) {
            return false;
        }
        ParentStep step = (ParentStep) other;
        return child.equals(step.child)
                && parentInvocation.equals(step.parentInvocation)
                && Objects.equals(certificate, step.certificate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(child, parentInvocation, certificate);
    }

    @Override
    public String toString() {
        return "U(" + child.id() + ")=" + parentInvocation;
    }
}
