package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/** Structurally checked law declaration with optional Phase F certificates. */
public final class ContainerLawDeclaration {
    public enum Kind {
        NONE,
        SEQ,
        BAG,
        SET
    }

    private static final ContainerLawDeclaration NONE = new ContainerLawDeclaration(
            Kind.NONE,
            false,
            false,
            false,
            false,
            Collections.emptyMap());

    private final Kind kind;
    private final boolean associative;
    private final boolean commutative;
    private final boolean idempotent;
    private final UnitLicense unitLicense;
    private final NavigableMap<ContainerLawCertificate.Law, ContainerLawCertificate>
            certificates;

    private ContainerLawDeclaration(
            Kind kind,
            boolean associative,
            boolean commutative,
            boolean idempotent,
            boolean hasUnit,
            Map<ContainerLawCertificate.Law, ContainerLawCertificate> certificates) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.associative = associative;
        this.commutative = commutative;
        this.idempotent = idempotent;
        this.unitLicense = hasUnit ? UnitLicense.EXPLICIT : UnitLicense.ABSENT;
        boolean quotientCommutative = kind == Kind.BAG || kind == Kind.SET;
        boolean quotientIdempotent = kind == Kind.SET;
        if (commutative != quotientCommutative || idempotent != quotientIdempotent) {
            throw new IllegalArgumentException(
                    "Sibling quotient and C/I declarations disagree for " + kind);
        }
        Objects.requireNonNull(certificates, "certificates");
        NavigableMap<ContainerLawCertificate.Law, ContainerLawCertificate> copied =
                new TreeMap<>();
        for (Map.Entry<ContainerLawCertificate.Law, ContainerLawCertificate> entry
                : certificates.entrySet()) {
            ContainerLawCertificate certificate = Objects.requireNonNull(
                    entry.getValue(), "container law certificate");
            if (entry.getKey() != certificate.law()) {
                throw new IllegalArgumentException(
                        "Container certificate is stored under the wrong law");
            }
            CertificateVerifier.verifyContainerLaw(certificate);
            copied.put(entry.getKey(), certificate);
        }
        this.certificates = Collections.unmodifiableNavigableMap(copied);
    }

    public static ContainerLawDeclaration none() {
        return NONE;
    }

    /** Structural Phase C/E declaration; strict Phase F use rejects missing proofs. */
    public static ContainerLawDeclaration of(
            Kind kind,
            boolean associative,
            boolean commutative,
            boolean idempotent,
            boolean hasUnit) {
        return new ContainerLawDeclaration(
                kind,
                associative,
                commutative,
                idempotent,
                hasUnit,
                Collections.emptyMap());
    }

    public static ContainerLawDeclaration certified(
            PortSchema schema,
            List<? extends ContainerLawCertificate> sourceCertificates) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(sourceCertificates, "certificates");
        NavigableMap<ContainerLawCertificate.Law, ContainerLawCertificate> indexed =
                new TreeMap<>();
        for (ContainerLawCertificate certificate : sourceCertificates) {
            ContainerLawCertificate checked = Objects.requireNonNull(
                    certificate, "container law certificate");
            CertificateVerifier.verifyContainerLaw(checked);
            if (!checked.appliesTo(schema)) {
                throw new IllegalArgumentException(
                        "Container certificate names a different schema");
            }
            if (indexed.putIfAbsent(checked.law(), checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate certificate for " + checked.law());
            }
        }
        Kind kind = kindFor(schema);
        boolean associative = indexed.containsKey(
                ContainerLawCertificate.Law.ASSOCIATIVITY);
        boolean commutative = kind == Kind.BAG || kind == Kind.SET;
        boolean idempotent = kind == Kind.SET;
        boolean hasUnit = indexed.containsKey(ContainerLawCertificate.Law.UNIT);
        ContainerLawDeclaration declaration = new ContainerLawDeclaration(
                kind,
                associative,
                commutative,
                idempotent,
                hasUnit,
                indexed);
        declaration.validateAgainst(schema);
        declaration.requireCertified();
        return declaration;
    }

    public Kind kind() {
        return kind;
    }

    public boolean associative() {
        return associative;
    }

    public boolean commutative() {
        return commutative;
    }

    public boolean idempotent() {
        return idempotent;
    }

    public boolean hasUnit() {
        return unitLicense == UnitLicense.EXPLICIT;
    }

    public UnitLicense unitLicense() {
        return unitLicense;
    }

    public Map<ContainerLawCertificate.Law, ContainerLawCertificate> certificates() {
        return certificates;
    }

    public boolean hasCertifiedLaws() {
        try {
            requireCertified();
            return true;
        } catch (IllegalStateException exception) {
            return false;
        }
    }

    void requireCertified() {
        if (kind == Kind.NONE) {
            return;
        }
        requireCertificate(ContainerLawCertificate.Law.ASSOCIATIVITY, associative);
        requireCertificate(ContainerLawCertificate.Law.COMMUTATIVITY, commutative);
        requireCertificate(ContainerLawCertificate.Law.IDEMPOTENCY, idempotent);
        requireCertificate(ContainerLawCertificate.Law.UNIT, hasUnit());
        int expected = (associative ? 1 : 0)
                + (commutative ? 1 : 0)
                + (idempotent ? 1 : 0)
                + (hasUnit() ? 1 : 0);
        if (certificates.size() != expected) {
            throw new IllegalStateException(
                    "Container declaration contains an undeclared law certificate");
        }
    }

    private void requireCertificate(
            ContainerLawCertificate.Law law,
            boolean required) {
        if (certificates.containsKey(law) != required) {
            throw new IllegalStateException(
                    "Container declaration certificate mismatch for " + law);
        }
    }

    void validateAgainst(PortSchema schema) {
        Objects.requireNonNull(schema, "schema");
        Kind expected = kindFor(schema);
        if (kind != expected) {
            throw new IllegalArgumentException(
                    "Container law kind " + kind + " does not match schema " + schema.kind());
        }
        boolean expectedCommutative = expected == Kind.BAG || expected == Kind.SET;
        boolean expectedIdempotent = expected == Kind.SET;
        if (commutative != expectedCommutative
                || idempotent != expectedIdempotent) {
            throw new IllegalArgumentException(
                    "Container law flags do not match sibling quotient " + expected);
        }
        if (expected == Kind.NONE && hasUnit()) {
            throw new IllegalArgumentException(
                    "A fixed port cannot declare a container unit");
        }
        if (hasUnit() && !arityPolicy(schema).admitsZero()) {
            throw new IllegalArgumentException(
                    "A unit law requires a port that admits zero children");
        }
        for (ContainerLawCertificate certificate : certificates.values()) {
            if (!certificate.appliesTo(schema)) {
                throw new IllegalArgumentException(
                        "Container certificate names a different schema");
            }
        }
    }

    void validateEvidenceFor(
            String operator,
            GraphType resultType,
            PortPath path,
            PortSchema schema,
            boolean requireProductionAuthority) {
        validateAgainst(schema);
        SemanticProfile commonProfile = null;
        for (ContainerLawCertificate certificate : certificates.values()) {
            if (requireProductionAuthority
                    && certificate.authority()
                            != ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY) {
                throw new IllegalStateException(
                        "Production node theory contains fixture law evidence");
            }
            if (certificate.authority()
                    == ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY) {
                if (!certificate.appliesTo(
                        certificate.semanticProfile(),
                        operator,
                        resultType,
                        path,
                        schema)) {
                    throw new IllegalStateException(
                            "Container law evidence does not match its enclosing operator instance");
                }
                if (commonProfile == null) {
                    commonProfile = certificate.semanticProfile();
                } else if (!commonProfile.equals(certificate.semanticProfile())) {
                    throw new IllegalStateException(
                            "One operator declaration mixes semantic profiles");
                }
            }
        }
    }

    private static Kind kindFor(PortSchema schema) {
        switch (schema.kind()) {
            case SEQ:
                return Kind.SEQ;
            case BAG:
                return Kind.BAG;
            case SET:
                return Kind.SET;
            default:
                return Kind.NONE;
        }
    }

    static ArityPolicy arityPolicy(PortSchema schema) {
        if (schema instanceof SeqPortSchema) {
            return ((SeqPortSchema) schema).arityPolicy();
        }
        if (schema instanceof BagPortSchema) {
            return ((BagPortSchema) schema).arityPolicy();
        }
        if (schema instanceof SetPortSchema) {
            return ((SetPortSchema) schema).arityPolicy();
        }
        return null;
    }

    public StructuralKey structuralKey() {
        List<StructuralKey> evidence = new ArrayList<>();
        for (Map.Entry<ContainerLawCertificate.Law, ContainerLawCertificate> entry
                : certificates.entrySet()) {
            evidence.add(StructuralKey.of(
                    "container-law-evidence",
                    Collections.singletonList(entry.getKey().name()),
                    Collections.singletonList(entry.getValue().lawIndex())));
        }
        return StructuralKey.of(
                "container-laws",
                Arrays.asList(
                        kind.name(),
                        Boolean.toString(associative),
                        Boolean.toString(commutative),
                        Boolean.toString(idempotent),
                        unitLicense.name()),
                evidence);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof ContainerLawDeclaration)) {
            return false;
        }
        ContainerLawDeclaration laws = (ContainerLawDeclaration) other;
        return kind == laws.kind
                && associative == laws.associative
                && commutative == laws.commutative
                && idempotent == laws.idempotent
                && unitLicense == laws.unitLicense
                && certificates.equals(laws.certificates);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind, associative, commutative, idempotent, unitLicense, certificates);
    }

    @Override
    public String toString() {
        return kind + "[A=" + associative + ",C=" + commutative
                + ",I=" + idempotent + ",unit=" + unitLicense
                + ",certs=" + certificates.size() + "]";
    }
}
