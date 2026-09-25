package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Objects;

/** Leader invocation and retained primitive parent path returned by typed find. */
public final class TypedFindResult {
    private final TypedInvocation originalInvocation;
    private final TypedInvocation normalizedInvocation;
    private final TypedInvocation leaderInvocation;
    private final ParentPath parentPath;
    private final TypedEqualityCertificate normalizationCertificate;
    private final StructuralKey structuralKey;

    public TypedFindResult(
            TypedInvocation originalInvocation,
            TypedInvocation leaderInvocation,
            ParentPath parentPath) {
        this(
                originalInvocation,
                originalInvocation,
                leaderInvocation,
                parentPath,
                EqualityCertificates.reflexive(
                        TypedCertificateEndpoint.invocation(originalInvocation)));
    }

    TypedFindResult(
            TypedInvocation originalInvocation,
            TypedInvocation normalizedInvocation,
            TypedInvocation leaderInvocation,
            ParentPath parentPath,
            TypedEqualityCertificate normalizationCertificate) {
        this.originalInvocation = Objects.requireNonNull(
                originalInvocation, "originalInvocation");
        this.normalizedInvocation = Objects.requireNonNull(
                normalizedInvocation, "normalizedInvocation");
        this.leaderInvocation = Objects.requireNonNull(
                leaderInvocation, "leaderInvocation");
        this.parentPath = Objects.requireNonNull(parentPath, "parentPath");
        this.normalizationCertificate = Objects.requireNonNull(
                normalizationCertificate, "normalizationCertificate");
        CertificateVerifier.verify(normalizationCertificate);
        if (!normalizationCertificate.leftEndpoint().equals(
                    TypedCertificateEndpoint.invocation(originalInvocation))
                || !normalizationCertificate.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(normalizedInvocation))) {
            throw new IllegalArgumentException(
                    "Invocation normalization certificate has the wrong endpoints");
        }
        if (!normalizedInvocation.eclass().equals(parentPath.start())) {
            throw new IllegalArgumentException("Find path must start at the normalized e-class");
        }
        if (!leaderInvocation.eclass().equals(parentPath.end())) {
            throw new IllegalArgumentException("Find path must end at the returned leader");
        }
        TypedEmbedding expected = parentPath.compositeEmbedding()
                .andThen(normalizedInvocation.embedding());
        if (!expected.equals(leaderInvocation.embedding())) {
            throw new IllegalArgumentException(
                    "Find result embedding must be caller embedding composed with the parent path");
        }
        if (!originalInvocation.outputType().equals(leaderInvocation.outputType())) {
            throw new IllegalArgumentException("Find must preserve invocation output type");
        }
        if (!originalInvocation.callerContext().equals(leaderInvocation.callerContext())) {
            throw new IllegalArgumentException("Find must preserve the invocation caller context");
        }
        this.structuralKey = StructuralKey.branch(
                "typed-find-result",
                Arrays.asList(
                        TheoryKeys.invocation(originalInvocation),
                        TheoryKeys.invocation(normalizedInvocation),
                        TheoryKeys.invocation(leaderInvocation),
                        parentPath.structuralKey(),
                        normalizationCertificate.structuralKey()));
    }

    public TypedInvocation originalInvocation() {
        return originalInvocation;
    }

    public TypedInvocation leaderInvocation() {
        return leaderInvocation;
    }

    /** Current-interface form of a possibly historical stored invocation. */
    public TypedInvocation normalizedInvocation() {
        return normalizedInvocation;
    }

    public TypedEmbedding composedEmbedding() {
        return leaderInvocation.embedding();
    }

    public ParentPath parentPath() {
        return parentPath;
    }

    public boolean hasParentCertificate() {
        return parentPath.hasCertificates();
    }

    /** Replays the retained parent path in the original invocation's caller context. */
    public TypedEqualityCertificate parentCertificate() {
        TypedEqualityCertificate pathCertificate = parentPath.composedCertificate();
        TypedEqualityCertificate transported = EqualityCertificates.rename(
                pathCertificate, normalizedInvocation.embedding());
        TypedEqualityCertificate result = normalizationCertificate.leftEndpoint().equals(
                        normalizationCertificate.rightEndpoint())
                ? transported
                : EqualityCertificates.transitive(
                        normalizationCertificate, transported);
        if (!result.leftEndpoint().equals(
                    TypedCertificateEndpoint.invocation(originalInvocation))
                || !result.rightEndpoint().equals(
                        TypedCertificateEndpoint.invocation(leaderInvocation))) {
            throw new IllegalStateException(
                    "Find certificate does not match the returned invocation endpoints");
        }
        return result;
    }

    public StructuralKey structuralKey() {
        return structuralKey;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof TypedFindResult)) {
            return false;
        }
        TypedFindResult result = (TypedFindResult) other;
        return originalInvocation.equals(result.originalInvocation)
                && normalizedInvocation.equals(result.normalizedInvocation)
                && leaderInvocation.equals(result.leaderInvocation)
                && parentPath.equals(result.parentPath)
                && normalizationCertificate.equals(result.normalizationCertificate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                originalInvocation,
                normalizedInvocation,
                leaderInvocation,
                parentPath,
                normalizationCertificate);
    }

    @Override
    public String toString() {
        return originalInvocation + " => " + leaderInvocation;
    }
}
