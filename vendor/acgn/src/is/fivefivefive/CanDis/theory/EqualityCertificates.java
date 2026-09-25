package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/** Checked constructors for the standard equational proof rules. */
public final class EqualityCertificates {
    private EqualityCertificates() {
    }

    public static TypedEqualityCertificate reflexive(TypedCertificateEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return DerivedEqualityCertificate.reflexive(endpoint);
    }

    public static TypedEqualityCertificate symmetric(TypedEqualityCertificate premise) {
        return DerivedEqualityCertificate.symmetric(
                Objects.requireNonNull(premise, "premise"));
    }

    public static TypedEqualityCertificate transitive(
            TypedEqualityCertificate first,
            TypedEqualityCertificate second) {
        return DerivedEqualityCertificate.transitive(
                Objects.requireNonNull(first, "first"),
                Objects.requireNonNull(second, "second"));
    }

    public static TypedEqualityCertificate rename(
            TypedEqualityCertificate premise,
            TypedEmbedding embedding) {
        return DerivedEqualityCertificate.rename(
                Objects.requireNonNull(premise, "premise"),
                Objects.requireNonNull(embedding, "embedding"));
    }

    /** Restricts an equality whose two endpoints factor through one inclusion. */
    public static TypedEqualityCertificate restrict(
            TypedEqualityCertificate premise,
            TypedCertificateEndpoint leftRestricted,
            TypedCertificateEndpoint rightRestricted,
            TypedEmbedding inclusion) {
        return DerivedEqualityCertificate.restrict(
                Objects.requireNonNull(premise, "premise"),
                Objects.requireNonNull(leftRestricted, "leftRestricted"),
                Objects.requireNonNull(rightRestricted, "rightRestricted"),
                Objects.requireNonNull(inclusion, "inclusion"));
    }

    static TypedEqualityCertificate orient(
            TypedEqualityCertificate certificate,
            TypedCertificateEndpoint left,
            TypedCertificateEndpoint right) {
        Objects.requireNonNull(certificate, "certificate");
        if (certificate.leftEndpoint().equals(left)
                && certificate.rightEndpoint().equals(right)) {
            return certificate;
        }
        if (certificate.leftEndpoint().equals(right)
                && certificate.rightEndpoint().equals(left)) {
            return symmetric(certificate);
        }
        throw new IllegalArgumentException(
                "Certificate endpoints do not match the required equation");
    }
}

final class DerivedEqualityCertificate extends TypedEqualityCertificate {
    private final TypedEqualityCertificate first;
    private final TypedEqualityCertificate second;
    private final TypedEmbedding embedding;

    private DerivedEqualityCertificate(
            CertificateCategory category,
            TypedCertificateEndpoint left,
            TypedCertificateEndpoint right,
            TypedEqualityCertificate first,
            TypedEqualityCertificate second,
            TypedEmbedding embedding) {
        super(
                category,
                left,
                right,
                second == null
                        ? (first == null
                                ? Collections.emptyList()
                                : Collections.singletonList(first))
                        : Arrays.asList(first, second),
                embedding == null
                        ? Collections.emptyList()
                        : Collections.singletonList(TheoryKeys.embedding(embedding)));
        this.first = first;
        this.second = second;
        this.embedding = embedding;
        verifyLocal();
    }

    static DerivedEqualityCertificate reflexive(TypedCertificateEndpoint endpoint) {
        return new DerivedEqualityCertificate(
                CertificateCategory.REFLEXIVITY,
                endpoint,
                endpoint,
                null,
                null,
                null);
    }

    static DerivedEqualityCertificate symmetric(TypedEqualityCertificate premise) {
        return new DerivedEqualityCertificate(
                CertificateCategory.EQUATIONAL_SYMMETRY,
                premise.rightEndpoint(),
                premise.leftEndpoint(),
                premise,
                null,
                null);
    }

    static DerivedEqualityCertificate transitive(
            TypedEqualityCertificate first,
            TypedEqualityCertificate second) {
        if (!first.rightEndpoint().equals(second.leftEndpoint())) {
            throw new IllegalArgumentException(
                    "Transitivity requires identical middle endpoints");
        }
        return new DerivedEqualityCertificate(
                CertificateCategory.TRANSITIVITY,
                first.leftEndpoint(),
                second.rightEndpoint(),
                first,
                second,
                null);
    }

    static DerivedEqualityCertificate rename(
            TypedEqualityCertificate premise,
            TypedEmbedding embedding) {
        if (!premise.context().equals(embedding.source())) {
            throw new IllegalArgumentException(
                    "Certificate renaming must start at the premise context");
        }
        return new DerivedEqualityCertificate(
                CertificateCategory.RENAMING,
                premise.leftEndpoint().act(embedding),
                premise.rightEndpoint().act(embedding),
                premise,
                null,
                embedding);
    }

    static DerivedEqualityCertificate restrict(
            TypedEqualityCertificate premise,
            TypedCertificateEndpoint leftRestricted,
            TypedCertificateEndpoint rightRestricted,
            TypedEmbedding inclusion) {
        if (!leftRestricted.context().equals(inclusion.source())
                || !rightRestricted.context().equals(inclusion.source())
                || !premise.context().equals(inclusion.codomain())
                || !leftRestricted.act(inclusion).equals(premise.leftEndpoint())
                || !rightRestricted.act(inclusion).equals(premise.rightEndpoint())) {
            throw new IllegalArgumentException(
                    "Context restriction requires both endpoints to factor through the inclusion");
        }
        return new DerivedEqualityCertificate(
                CertificateCategory.CONTEXT_RESTRICTION,
                leftRestricted,
                rightRestricted,
                premise,
                null,
                inclusion);
    }

    @Override
    void verifyLocal() {
        switch (category()) {
            case REFLEXIVITY:
                if (first != null || second != null || embedding != null
                        || !leftEndpoint().equals(rightEndpoint())) {
                    throw new IllegalStateException("Malformed reflexivity certificate");
                }
                return;
            case EQUATIONAL_SYMMETRY:
                if (first == null || second != null || embedding != null
                        || !leftEndpoint().equals(first.rightEndpoint())
                        || !rightEndpoint().equals(first.leftEndpoint())) {
                    throw new IllegalStateException("Malformed symmetry-rule certificate");
                }
                return;
            case TRANSITIVITY:
                if (first == null || second == null || embedding != null
                        || !first.rightEndpoint().equals(second.leftEndpoint())
                        || !leftEndpoint().equals(first.leftEndpoint())
                        || !rightEndpoint().equals(second.rightEndpoint())) {
                    throw new IllegalStateException("Malformed transitivity certificate");
                }
                return;
            case RENAMING:
                if (first == null || second != null || embedding == null
                        || !first.context().equals(embedding.source())
                        || !leftEndpoint().equals(first.leftEndpoint().act(embedding))
                        || !rightEndpoint().equals(first.rightEndpoint().act(embedding))) {
                    throw new IllegalStateException("Malformed renaming certificate");
                }
                return;
            case CONTEXT_RESTRICTION:
                if (first == null || second != null || embedding == null
                        || !leftEndpoint().context().equals(embedding.source())
                        || !rightEndpoint().context().equals(embedding.source())
                        || !first.context().equals(embedding.codomain())
                        || !leftEndpoint().act(embedding).equals(first.leftEndpoint())
                        || !rightEndpoint().act(embedding).equals(first.rightEndpoint())) {
                    throw new IllegalStateException(
                            "Malformed context-restriction certificate");
                }
                return;
            default:
                throw new IllegalStateException("Not a derived proof-rule category");
        }
    }
}
