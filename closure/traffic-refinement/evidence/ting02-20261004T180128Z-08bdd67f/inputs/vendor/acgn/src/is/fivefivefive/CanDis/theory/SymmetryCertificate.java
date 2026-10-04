package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/** Checked derivation of one generator equation {@code w_a = pi . w_a}. */
public final class SymmetryCertificate extends TypedEqualityCertificate {
    public enum ProvenanceKind {
        INPUT_EQUATION,
        REWRITE_AXIOM,
        FORWARD_CONGRUENCE,
        COMPOSED
    }

    private final TypedEClassInterface eclass;
    private final TypedInvocation leftInvocation;
    private final TypedInvocation rightInvocation;
    private final TypedPermutation inducedPermutation;
    private final TypedEqualityCertificate originatingEquality;
    private final ProvenanceKind provenanceKind;

    public SymmetryCertificate(
            TypedInvocation leftInvocation,
            TypedInvocation rightInvocation,
            TypedEqualityCertificate originatingEquality) {
        this(build(leftInvocation, rightInvocation, originatingEquality));
    }

    private SymmetryCertificate(Build build) {
        super(
                CertificateCategory.SLOT_SYMMETRY,
                build.canonicalLeft,
                build.canonicalRight,
                Collections.singletonList(build.originatingEquality),
                Arrays.asList(
                        TheoryKeys.invocation(build.leftInvocation),
                        TheoryKeys.invocation(build.rightInvocation),
                        TheoryKeys.embedding(build.inducedPermutation),
                        StructuralKey.leaf(
                                "symmetry-provenance-category",
                                build.provenanceKind.name())));
        this.eclass = build.eclass;
        this.leftInvocation = build.leftInvocation;
        this.rightInvocation = build.rightInvocation;
        this.inducedPermutation = build.inducedPermutation;
        this.originatingEquality = build.originatingEquality;
        this.provenanceKind = build.provenanceKind;
        verifyLocal();
    }

    private static Build build(
            TypedInvocation left,
            TypedInvocation right,
            TypedEqualityCertificate origin) {
        Objects.requireNonNull(left, "leftInvocation");
        Objects.requireNonNull(right, "rightInvocation");
        Objects.requireNonNull(origin, "originatingEquality");
        if (!left.eclass().equals(right.eclass())
                || !left.callerContext().equals(right.callerContext())) {
            throw new IllegalArgumentException(
                    "Symmetry endpoints must invoke one e-class in one caller context");
        }
        if (!left.embedding().isRenaming() || !right.embedding().isRenaming()) {
            throw new IllegalArgumentException(
                    "Symmetry cancellation requires bijective endpoint embeddings");
        }
        CertificateVerifier.verify(origin);
        TypedEqualityCertificate oriented = EqualityCertificates.orient(
                origin,
                TypedCertificateEndpoint.invocation(left),
                TypedCertificateEndpoint.invocation(right));
        TypedRenaming leftInverse = left.embedding().asRenaming().inverse();
        TypedPermutation induced = right.embedding()
                .andThen(leftInverse)
                .asRenaming()
                .asPermutation();
        if (induced.equals(TypedPermutation.identity(left.eclass().exposedSlots()))) {
            throw new IllegalArgumentException(
                    "Identity does not need a symmetry generator certificate");
        }
        TypedCertificateEndpoint canonicalLeft =
                TypedCertificateEndpoint.eclassWitness(left.eclass());
        TypedCertificateEndpoint canonicalRight =
                TypedCertificateEndpoint.invocation(new TypedInvocation(
                        left.eclass(), induced));
        return new Build(
                left.eclass(), left, right, induced, oriented,
                provenanceKind(origin), canonicalLeft, canonicalRight);
    }

    private static ProvenanceKind provenanceKind(TypedEqualityCertificate certificate) {
        boolean input = CertificateVerifier.containsCategory(
                certificate, CertificateCategory.INPUT_EQUATION);
        boolean rewrite = CertificateVerifier.containsCategory(
                certificate, CertificateCategory.REWRITE_AXIOM);
        boolean congruence = CertificateVerifier.containsCategory(
                certificate, CertificateCategory.FORWARD_CONGRUENCE);
        int categories = (input ? 1 : 0) + (rewrite ? 1 : 0) + (congruence ? 1 : 0);
        if (categories != 1) {
            return ProvenanceKind.COMPOSED;
        }
        if (input) {
            return ProvenanceKind.INPUT_EQUATION;
        }
        if (rewrite) {
            return ProvenanceKind.REWRITE_AXIOM;
        }
        return ProvenanceKind.FORWARD_CONGRUENCE;
    }

    public TypedEClassInterface eclass() {
        return eclass;
    }

    public TypedInvocation leftInvocation() {
        return leftInvocation;
    }

    public TypedInvocation rightInvocation() {
        return rightInvocation;
    }

    public TypedPermutation inducedPermutation() {
        return inducedPermutation;
    }

    public TypedEqualityCertificate originatingEquality() {
        return originatingEquality;
    }

    public ProvenanceKind provenanceKind() {
        return provenanceKind;
    }

    @Override
    void verifyLocal() {
        if (!eclass.exposedSlots().equals(inducedPermutation.source())
                || !eclass.exposedSlots().equals(inducedPermutation.codomain())
                || !leftEndpoint().equals(TypedCertificateEndpoint.eclassWitness(eclass))
                || !rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(new TypedInvocation(
                                eclass, inducedPermutation)))) {
            throw new IllegalStateException("Malformed slot-symmetry certificate");
        }
    }

    private static final class Build {
        private final TypedEClassInterface eclass;
        private final TypedInvocation leftInvocation;
        private final TypedInvocation rightInvocation;
        private final TypedPermutation inducedPermutation;
        private final TypedEqualityCertificate originatingEquality;
        private final ProvenanceKind provenanceKind;
        private final TypedCertificateEndpoint canonicalLeft;
        private final TypedCertificateEndpoint canonicalRight;

        private Build(
                TypedEClassInterface eclass,
                TypedInvocation leftInvocation,
                TypedInvocation rightInvocation,
                TypedPermutation inducedPermutation,
                TypedEqualityCertificate originatingEquality,
                ProvenanceKind provenanceKind,
                TypedCertificateEndpoint canonicalLeft,
                TypedCertificateEndpoint canonicalRight) {
            this.eclass = eclass;
            this.leftInvocation = leftInvocation;
            this.rightInvocation = rightInvocation;
            this.inducedPermutation = inducedPermutation;
            this.originatingEquality = originatingEquality;
            this.provenanceKind = provenanceKind;
            this.canonicalLeft = canonicalLeft;
            this.canonicalRight = canonicalRight;
        }
    }
}
