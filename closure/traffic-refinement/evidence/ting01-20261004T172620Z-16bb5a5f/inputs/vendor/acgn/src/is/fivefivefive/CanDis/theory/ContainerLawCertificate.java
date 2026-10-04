package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.Objects;

/** Independently authorized evidence for one exact indexed container law. */
public final class ContainerLawCertificate extends TypedEqualityCertificate {
    public enum Law {
        ASSOCIATIVITY,
        COMMUTATIVITY,
        IDEMPOTENCY,
        UNIT
    }

    public enum Authority {
        ALLOY_PROFILE_THEORY,
        TEST_ONLY
    }

    private static final SemanticProfile TEST_PROFILE = new SemanticProfile(
            4,
            SemanticProfile.OverflowMode.FORBID,
            "test-only-temporal",
            "test-only-rewrite",
            "test-only-signature");

    private final PortSchema schema;
    private final Law law;
    private final CertificateOrigin origin;
    private final Authority authority;
    private final SemanticProfile semanticProfile;
    private final String operatorIdentity;
    private final GraphType resultType;
    private final PortPath schemaPath;
    private final StructuralKey lawParameter;
    private final String sourceTheoryDigest;
    private final StructuralKey lawIndex;
    private final StructuralKey leftSourceEndpoint;
    private final StructuralKey rightSourceEndpoint;

    private ContainerLawCertificate(
            PortSchema schema,
            Law law,
            CertificateOrigin origin,
            Authority authority,
            SemanticProfile semanticProfile,
            String operatorIdentity,
            GraphType resultType,
            PortPath schemaPath,
            StructuralKey lawParameter,
            String sourceTheoryDigest) {
        super(
                CertificateCategory.CONTAINER_LAW,
                TypedCertificateEndpoint.containerPattern(
                        Objects.requireNonNull(schema, "schema"),
                        Objects.requireNonNull(law, "law"),
                        "left",
                        lawIndex(schema, law, authority, semanticProfile, operatorIdentity,
                                resultType, schemaPath, lawParameter, sourceTheoryDigest)),
                TypedCertificateEndpoint.containerPattern(
                        schema,
                        law,
                        "right",
                        lawIndex(schema, law, authority, semanticProfile, operatorIdentity,
                                resultType, schemaPath, lawParameter, sourceTheoryDigest)),
                Collections.emptyList(),
                Arrays.asList(
                        lawIndex(schema, law, authority, semanticProfile, operatorIdentity,
                                resultType, schemaPath, lawParameter, sourceTheoryDigest),
                        Objects.requireNonNull(origin, "origin").structuralKey()));
        this.schema = schema;
        this.law = law;
        this.origin = origin;
        this.authority = Objects.requireNonNull(authority, "authority");
        this.semanticProfile = Objects.requireNonNull(semanticProfile, "semanticProfile");
        this.operatorIdentity = requireText(operatorIdentity, "operatorIdentity");
        this.resultType = Objects.requireNonNull(resultType, "resultType");
        this.schemaPath = Objects.requireNonNull(schemaPath, "schemaPath");
        this.lawParameter = Objects.requireNonNull(lawParameter, "lawParameter");
        this.sourceTheoryDigest = requireText(sourceTheoryDigest, "sourceTheoryDigest");
        this.lawIndex = lawIndex(schema, law, authority, semanticProfile, operatorIdentity,
                resultType, schemaPath, lawParameter, sourceTheoryDigest);
        this.leftSourceEndpoint = sourceEndpoint(lawIndex, "left");
        this.rightSourceEndpoint = sourceEndpoint(lawIndex, "right");
        verifyLocal();
    }

    static ContainerLawCertificate trustedAlloy(
            PortSchema schema,
            Law law,
            CertificateOrigin origin,
            SemanticProfile semanticProfile,
            String operatorIdentity,
            GraphType resultType,
            PortPath schemaPath,
            StructuralKey lawParameter,
            String sourceTheoryDigest) {
        return new ContainerLawCertificate(
                schema, law, origin, Authority.ALLOY_PROFILE_THEORY,
                semanticProfile, operatorIdentity, resultType, schemaPath,
                lawParameter, sourceTheoryDigest);
    }

    /** Package-scoped fixture; production artifacts reject this authority. */
    static ContainerLawCertificate testFixture(
            PortSchema schema,
            Law law,
            CertificateOrigin origin) {
        return new ContainerLawCertificate(
                schema,
                law,
                origin,
                Authority.TEST_ONLY,
                TEST_PROFILE,
                "TEST_ONLY/" + origin.sourceArtifact() + "/" + origin.declarationId(),
                elementType(schema),
                PortPath.at(0),
                StructuralKey.leaf("test-only-law-parameter", law.name()),
                "test-only-container-law-theory-v1");
    }

    public PortSchema schema() { return schema; }
    public Law law() { return law; }
    public CertificateOrigin origin() { return origin; }
    public Authority authority() { return authority; }
    public SemanticProfile semanticProfile() { return semanticProfile; }
    public String operatorIdentity() { return operatorIdentity; }
    public GraphType resultType() { return resultType; }
    public PortPath schemaPath() { return schemaPath; }
    public StructuralKey lawParameter() { return lawParameter; }
    public String sourceTheoryDigest() { return sourceTheoryDigest; }
    public StructuralKey lawIndex() { return lawIndex; }
    public StructuralKey leftSourceEndpoint() { return leftSourceEndpoint; }
    public StructuralKey rightSourceEndpoint() { return rightSourceEndpoint; }

    public boolean appliesTo(PortSchema candidate) {
        return schema.equals(candidate);
    }

    public boolean appliesTo(
            SemanticProfile profile,
            String operator,
            GraphType result,
            PortPath path,
            PortSchema candidate) {
        return semanticProfile.equals(profile)
                && operatorIdentity.equals(operator)
                && resultType.equals(result)
                && schemaPath.equals(path)
                && schema.equals(candidate);
    }

    @Override
    void verifyLocal() {
        if (origin.kind() != CertificateOrigin.Kind.SIGNATURE_CONTAINER_LAW) {
            throw new IllegalStateException(
                    "Container law requires signature-law provenance");
        }
        boolean allowed;
        switch (law) {
            case ASSOCIATIVITY:
                allowed = isContainer(schema);
                break;
            case COMMUTATIVITY:
                allowed = schema instanceof BagPortSchema || schema instanceof SetPortSchema;
                break;
            case IDEMPOTENCY:
                allowed = schema instanceof SetPortSchema;
                break;
            case UNIT:
                allowed = arityPolicy(schema) != null && arityPolicy(schema).admitsZero();
                break;
            default:
                allowed = false;
        }
        if (!allowed) {
            throw new IllegalStateException(
                    "Container law is incompatible with its port schema");
        }
        if (!leftSourceEndpoint.equals(sourceEndpoint(lawIndex, "left"))
                || !rightSourceEndpoint.equals(sourceEndpoint(lawIndex, "right"))) {
            throw new IllegalStateException("Container law source endpoints do not match its index");
        }
        if (authority == Authority.ALLOY_PROFILE_THEORY
                && !AlloyLawRegistry.accepts(this)) {
            throw new IllegalStateException(
                    "Container law is not admitted by the fixed Alloy source theory");
        }
    }

    private static StructuralKey lawIndex(
            PortSchema schema,
            Law law,
            Authority authority,
            SemanticProfile semanticProfile,
            String operatorIdentity,
            GraphType resultType,
            PortPath schemaPath,
            StructuralKey lawParameter,
            String sourceTheoryDigest) {
        return StructuralKey.of(
                "container-law-index-v2",
                Arrays.asList(
                        Objects.requireNonNull(authority, "authority").name(),
                        requireText(operatorIdentity, "operatorIdentity"),
                        Objects.requireNonNull(schemaPath, "schemaPath").toString(),
                        Objects.requireNonNull(law, "law").name(),
                        requireText(sourceTheoryDigest, "sourceTheoryDigest")),
                Arrays.asList(
                        Objects.requireNonNull(semanticProfile, "semanticProfile").structuralKey(),
                        TheoryKeys.type(Objects.requireNonNull(resultType, "resultType")),
                        Objects.requireNonNull(schema, "schema").structuralKey(),
                        Objects.requireNonNull(lawParameter, "lawParameter")));
    }

    private static StructuralKey sourceEndpoint(StructuralKey index, String side) {
        return StructuralKey.of(
                "container-law-source-endpoint",
                Collections.singletonList(side),
                Collections.singletonList(index));
    }

    private static GraphType elementType(PortSchema schema) {
        PortSchema element = OperatorDeclaration.elementSchema(schema);
        if (!(element instanceof OnePortSchema)) {
            throw new IllegalArgumentException(
                    "A test container law requires One-typed elements");
        }
        return ((OnePortSchema) element).type();
    }

    private static boolean isContainer(PortSchema schema) {
        return schema instanceof SeqPortSchema
                || schema instanceof BagPortSchema
                || schema instanceof SetPortSchema;
    }

    private static ArityPolicy arityPolicy(PortSchema schema) {
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

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
