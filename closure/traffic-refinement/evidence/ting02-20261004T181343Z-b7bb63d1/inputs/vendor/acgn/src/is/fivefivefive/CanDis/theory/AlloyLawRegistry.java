package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Independently fixed source theory for production Alloy container laws. */
public final class AlloyLawRegistry {
    public static final String VERSION = "alloy-container-law-theory-v3";
    private static final String THEORY_TEXT = String.join("\n",
            "AND:Set+:A,C,I",
            "OR:Set+:A,C,I",
            "PLUS:Set+:A,C,I",
            "INTERSECT:Set+:A,C,I",
            "RELATIONAL-INT-CARRIER:PLUS,INTERSECT=Set<One(Int)>;parser-opcode-authority",
            "IPLUS:forbid=Bag2:C;modular=Bag+:A,C",
            "MUL:forbid=Bag2:C;modular=Bag+:A,C",
            "EQUALS:Bag2:C",
            "NOT_EQUALS:Bag2:C",
            "IFF:Bag2:C",
            "DISJOINT:Bag+:C");
    public static final String SOURCE_THEORY_DIGEST = sha256(VERSION + "\n" + THEORY_TEXT);

    private static final Set<Opcode> FLAT_SET = EnumSet.of(
            Opcode.AND, Opcode.OR, Opcode.PLUS, Opcode.INTERSECT);
    private static final Set<Opcode> FIXED_C = EnumSet.of(
            Opcode.EQUALS, Opcode.NOT_EQUALS, Opcode.IFF);

    private AlloyLawRegistry() {
    }

    public static ContainerLawCertificate issue(
            SemanticProfile profile,
            Opcode opcode,
            String operatorIdentity,
            GraphType resultType,
            PortPath path,
            PortSchema schema,
            ContainerLawCertificate.Law law) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(opcode, "opcode");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(law, "law");
        String expectedIdentity = "ALLOY/" + opcode;
        if (!expectedIdentity.equals(operatorIdentity)) {
            throw new IllegalStateException(
                    "Alloy law identity must be exact: " + expectedIdentity);
        }
        StructuralKey parameter = lawParameter(
                profile, opcode, resultType, path, schema, law);
        if (!admitted(profile, opcode, resultType, path, schema, law)) {
            throw new IllegalStateException(
                    "The fixed Alloy source theory does not admit " + law
                            + " for " + operatorIdentity);
        }
        CertificateOrigin origin = CertificateOrigin.containerLaw(
                VERSION + "/" + SOURCE_THEORY_DIGEST,
                declarationId(operatorIdentity, path, law, parameter),
                law.ordinal());
        return ContainerLawCertificate.trustedAlloy(
                schema,
                law,
                origin,
                profile,
                operatorIdentity,
                resultType,
                path,
                parameter,
                SOURCE_THEORY_DIGEST);
    }

    static boolean accepts(ContainerLawCertificate certificate) {
        if (certificate.authority()
                != ContainerLawCertificate.Authority.ALLOY_PROFILE_THEORY
                || !SOURCE_THEORY_DIGEST.equals(certificate.sourceTheoryDigest())) {
            return false;
        }
        Opcode opcode = opcodeFor(certificate.operatorIdentity());
        if (opcode == null) {
            return false;
        }
        StructuralKey expectedParameter = lawParameter(
                certificate.semanticProfile(),
                opcode,
                certificate.resultType(),
                certificate.schemaPath(),
                certificate.schema(),
                certificate.law());
        CertificateOrigin expectedOrigin = CertificateOrigin.containerLaw(
                VERSION + "/" + SOURCE_THEORY_DIGEST,
                declarationId(
                        certificate.operatorIdentity(),
                        certificate.schemaPath(),
                        certificate.law(),
                        expectedParameter),
                certificate.law().ordinal());
        return certificate.lawParameter().equals(expectedParameter)
                && certificate.origin().equals(expectedOrigin)
                && admitted(
                        certificate.semanticProfile(),
                        opcode,
                        certificate.resultType(),
                        certificate.schemaPath(),
                        certificate.schema(),
                        certificate.law());
    }

    private static boolean admitted(
            SemanticProfile profile,
            Opcode opcode,
            GraphType resultType,
            PortPath path,
            PortSchema schema,
            ContainerLawCertificate.Law law) {
        if (!profile.isAdmissibleAlloyProfile()) {
            return false;
        }
        if (!PortPath.at(0).equals(path)) {
            return false;
        }
        PortSchema element = OperatorDeclaration.elementSchema(schema);
        if (!(element instanceof OnePortSchema)) {
            return false;
        }
        GraphType elementType = ((OnePortSchema) element).type();
        ArityPolicy arities = arityPolicy(schema);
        if (FLAT_SET.contains(opcode)) {
            boolean exactCarrier = opcode == Opcode.AND || opcode == Opcode.OR
                    ? GraphType.BOOL.equals(resultType)
                            && GraphType.BOOL.equals(elementType)
                    : (AlloyTypeBridge.isCommutativeRelationCarrier(resultType)
                                    || GraphType.INT.equals(resultType))
                            && elementType.equals(resultType);
            return exactCarrier
                    && schema instanceof SetPortSchema
                    && arities.equals(ArityPolicy.nonemptyVariadic())
                    && (law == ContainerLawCertificate.Law.ASSOCIATIVITY
                            || law == ContainerLawCertificate.Law.COMMUTATIVITY
                            || law == ContainerLawCertificate.Law.IDEMPOTENCY);
        }
        if (opcode == Opcode.IPLUS || opcode == Opcode.MUL) {
            if (!GraphType.INT.equals(resultType) || !GraphType.INT.equals(elementType)
                    || !(schema instanceof BagPortSchema)) {
                return false;
            }
            if (profile.overflowMode() == SemanticProfile.OverflowMode.MODULAR) {
                return arities.equals(ArityPolicy.nonemptyVariadic())
                        && (law == ContainerLawCertificate.Law.ASSOCIATIVITY
                                || law == ContainerLawCertificate.Law.COMMUTATIVITY);
            }
            return arities.equals(ArityPolicy.exact(2))
                    && law == ContainerLawCertificate.Law.COMMUTATIVITY;
        }
        if (FIXED_C.contains(opcode)) {
            boolean exactCarrier = opcode != Opcode.IFF
                    || GraphType.BOOL.equals(elementType);
            return exactCarrier
                    && GraphType.BOOL.equals(resultType)
                    && schema instanceof BagPortSchema
                    && arities.equals(ArityPolicy.exact(2))
                    && law == ContainerLawCertificate.Law.COMMUTATIVITY;
        }
        if (opcode == Opcode.DISJOINT) {
            return GraphType.BOOL.equals(resultType)
                    && AlloyTypeBridge.isCommutativeRelationCarrier(elementType)
                    && schema instanceof BagPortSchema
                    && arities.equals(ArityPolicy.nonemptyVariadic())
                    && law == ContainerLawCertificate.Law.COMMUTATIVITY;
        }
        return false;
    }

    private static StructuralKey lawParameter(
            SemanticProfile profile,
            Opcode opcode,
            GraphType resultType,
            PortPath path,
            PortSchema schema,
            ContainerLawCertificate.Law law) {
        String family;
        switch (law) {
            case ASSOCIATIVITY:
                family = "all-legal-outer-nested-arities-and-splice-positions";
                break;
            case COMMUTATIVITY:
                family = "all-admitted-sibling-permutations";
                break;
            case IDEMPOTENCY:
                family = "all-admitted-quotient-surjections";
                break;
            case UNIT:
                family = "exact-empty-fold-deletion";
                break;
            default:
                throw new IllegalStateException("Unhandled law " + law);
        }
        return StructuralKey.of(
                "alloy-law-parameter-v1",
                Arrays.asList(opcode.name(), path.toString(), law.name(), family),
                Arrays.asList(
                        profile.structuralKey(),
                        TheoryKeys.type(resultType),
                        schema.structuralKey()));
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
        throw new IllegalArgumentException("Not a container schema: " + schema);
    }

    private static Opcode opcodeFor(String operatorIdentity) {
        for (Opcode opcode : Opcode.values()) {
            if (("ALLOY/" + opcode).equals(operatorIdentity)) {
                return opcode;
            }
        }
        return null;
    }

    private static String declarationId(
            String operator,
            PortPath path,
            ContainerLawCertificate.Law law,
            StructuralKey parameter) {
        return operator + "@" + path + ":" + law + ":" + sha256(parameter.stableString());
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    input.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
