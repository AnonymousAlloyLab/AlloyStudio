package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Executable witness for the reachability premise of Theorem 1. */
public final class FiniteUnfoldingEqualityWitness {
    private final TypedInvocation left;
    private final TypedInvocation right;
    private final TypedFindResult leftFind;
    private final TypedFindResult rightFind;
    private final TypedPermutation leaderSymmetry;
    private final TypedEqualityCertificate symmetryDerivation;
    private final TypedEqualityCertificate reachabilityCertificate;
    private final StructuralKey structuralKey;

    private FiniteUnfoldingEqualityWitness(
            TypedInvocation left,
            TypedInvocation right,
            TypedFindResult leftFind,
            TypedFindResult rightFind,
            TypedPermutation leaderSymmetry,
            TypedEqualityCertificate symmetryDerivation,
            TypedEqualityCertificate reachabilityCertificate) {
        this.left = Objects.requireNonNull(left, "left");
        this.right = Objects.requireNonNull(right, "right");
        this.leftFind = Objects.requireNonNull(leftFind, "leftFind");
        this.rightFind = Objects.requireNonNull(rightFind, "rightFind");
        this.leaderSymmetry = Objects.requireNonNull(leaderSymmetry, "leaderSymmetry");
        this.symmetryDerivation = Objects.requireNonNull(
                symmetryDerivation, "symmetryDerivation");
        this.reachabilityCertificate = Objects.requireNonNull(
                reachabilityCertificate, "reachabilityCertificate");
        CertificateVerifier.verify(symmetryDerivation);
        CertificateVerifier.verify(reachabilityCertificate);
        if (!reachabilityCertificate.leftEndpoint().equals(
                    TypedCertificateEndpoint.invocation(left))
                || !reachabilityCertificate.rightEndpoint().equals(
                    TypedCertificateEndpoint.invocation(right))) {
            throw new IllegalArgumentException(
                    "Reachability certificate does not equate the requested invocations");
        }
        this.structuralKey = StructuralKey.branch(
                "finite-unfolding-equality-witness",
                Arrays.asList(
                        TheoryKeys.invocation(left),
                        TheoryKeys.invocation(right),
                        leftFind.structuralKey(),
                        rightFind.structuralKey(),
                        TheoryKeys.embedding(leaderSymmetry),
                        symmetryDerivation.structuralKey(),
                        reachabilityCertificate.structuralKey()));
    }

    static FiniteUnfoldingEqualityWitness establish(
            TypedSlottedPortEGraph graph,
            CoherentWitnessFamily family,
            TypedInvocation left,
            TypedInvocation right) {
        Objects.requireNonNull(graph, "graph");
        graph.requireCurrentWitnessFamily(Objects.requireNonNull(family, "family"));
        TypedInvocation checkedLeft = Objects.requireNonNull(left, "left");
        TypedInvocation checkedRight = Objects.requireNonNull(right, "right");
        if (!checkedLeft.outputType().equals(checkedRight.outputType())
                || !checkedLeft.callerContext().equals(checkedRight.callerContext())) {
            throw new IllegalArgumentException(
                    "Finite-unfolding equality requires one result type and caller context");
        }

        TypedFindResult leftFind = graph.findForFiniteUnfolding(checkedLeft);
        TypedFindResult rightFind = graph.findForFiniteUnfolding(checkedRight);
        TypedInvocation leftLeader = leftFind.leaderInvocation();
        TypedInvocation rightLeader = rightFind.leaderInvocation();
        if (!leftLeader.eclass().equals(rightLeader.eclass())) {
            throw new IllegalArgumentException(
                    "Invocations do not reach the same typed leader");
        }

        TypedEClassRecord leaderRecord = graph.eclass(leftLeader.eclass().id());
        TypedSymmetryGroup group = leaderRecord.symmetryGroup();
        TypedPermutation[] selected = {null};
        group.forEachElement(permutation -> {
            if (selected[0] != null) {
                return;
            }
            TypedEmbedding rightAfterPermutation = permutation.andThen(
                    rightLeader.embedding());
            if (leftLeader.embedding().equals(rightAfterPermutation)) {
                selected[0] = permutation;
            }
        });
        if (selected[0] == null) {
            throw new IllegalArgumentException(
                    "Leader embeddings are not related by a certified leader symmetry");
        }

        TypedEqualityCertificate symmetry = group.derivationFor(
                leaderRecord.interfaceView(), selected[0]);
        TypedEqualityCertificate rightToLeftLeader = EqualityCertificates.rename(
                symmetry, rightLeader.embedding());
        TypedEqualityCertificate leftToRightLeader = EqualityCertificates.symmetric(
                rightToLeftLeader);
        TypedEqualityCertificate reachability = EqualityCertificates.transitive(
                leftFind.parentCertificate(), leftToRightLeader);
        reachability = EqualityCertificates.transitive(
                reachability,
                EqualityCertificates.symmetric(rightFind.parentCertificate()));
        reachability = EqualityCertificates.orient(
                reachability,
                TypedCertificateEndpoint.invocation(checkedLeft),
                TypedCertificateEndpoint.invocation(checkedRight));
        CertificateVerifier.verify(reachability);
        graph.requireCurrentWitnessFamily(family);
        return new FiniteUnfoldingEqualityWitness(
                checkedLeft,
                checkedRight,
                leftFind,
                rightFind,
                selected[0],
                symmetry,
                reachability);
    }

    public TypedInvocation left() {
        return left;
    }

    public TypedInvocation right() {
        return right;
    }

    public TypedFindResult leftFind() {
        return leftFind;
    }

    public TypedFindResult rightFind() {
        return rightFind;
    }

    public TypedPermutation leaderSymmetry() {
        return leaderSymmetry;
    }

    public TypedEqualityCertificate symmetryDerivation() {
        return symmetryDerivation;
    }

    public TypedEqualityCertificate reachabilityCertificate() {
        return reachabilityCertificate;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public String toString() {
        return left + " ==[" + leaderSymmetry.mapping() + "] " + right;
    }
}
