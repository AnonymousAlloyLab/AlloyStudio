package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Recursive verifier and admission predicates for the closed certificate algebra. */
public final class CertificateVerifier {
    public static final String VERSION = "typed-certificate-algebra-v3";

    private CertificateVerifier() {
    }

    public static String version() {
        return VERSION;
    }

    public static void verify(TypedEqualityCertificate certificate) {
        verify(
                Objects.requireNonNull(certificate, "certificate"),
                Collections.newSetFromMap(new IdentityHashMap<>()),
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void verify(
            TypedEqualityCertificate certificate,
            Set<TypedEqualityCertificate> visiting,
            Set<TypedEqualityCertificate> verified) {
        if (verified.contains(certificate)) {
            return;
        }
        if (!visiting.add(certificate)) {
            throw new IllegalArgumentException("Certificate derivation contains a cycle");
        }
        if (!certificate.leftEndpoint().context().equals(
                    certificate.rightEndpoint().context())
                || !certificate.leftEndpoint().sort().equals(
                        certificate.rightEndpoint().sort())) {
            throw new IllegalArgumentException("Certificate endpoints are not well typed");
        }
        for (TypedEqualityCertificate premise : certificate.premises()) {
            verify(premise, visiting, verified);
        }
        certificate.verifyLocal();
        visiting.remove(certificate);
        verified.add(certificate);
    }

    public static void requirePermittedUnionDerivation(
            TypedEqualityCertificate certificate) {
        verify(certificate);
        if (!containsCategory(certificate, CertificateCategory.INPUT_EQUATION)
                && !containsCategory(certificate, CertificateCategory.REWRITE_AXIOM)
                && !containsCategory(certificate, CertificateCategory.FORWARD_CONGRUENCE)) {
            throw new IllegalArgumentException(
                    "A union derivation must originate in E or forward congruence");
        }
    }

    public static boolean containsCategory(
            TypedEqualityCertificate certificate,
            CertificateCategory category) {
        Objects.requireNonNull(certificate, "certificate");
        Objects.requireNonNull(category, "category");
        return containsCategory(
                certificate,
                category,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static boolean containsCategory(
            TypedEqualityCertificate certificate,
            CertificateCategory category,
            Set<TypedEqualityCertificate> visited) {
        if (!visited.add(certificate)) {
            return false;
        }
        if (certificate.category() == category) {
            return true;
        }
        for (TypedEqualityCertificate premise : certificate.premises()) {
            if (containsCategory(premise, category, visited)) {
                return true;
            }
        }
        return false;
    }

    public static void verifyParentEdge(ParentEdgeCertificate certificate) {
        verify(Objects.requireNonNull(certificate, "certificate"));
        requirePermittedUnionDerivation(certificate.endpointDerivation());
    }

    public static void verifySymmetry(SymmetryCertificate certificate) {
        verify(Objects.requireNonNull(certificate, "certificate"));
    }

    public static void verifyInterfaceRestriction(
            InterfaceRestrictionCertificate certificate) {
        verify(Objects.requireNonNull(certificate, "certificate"));
    }

    public static void verifyContainerLaw(ContainerLawCertificate certificate) {
        verify(Objects.requireNonNull(certificate, "certificate"));
    }

    public static void verifyBinderAutomorphism(
            BinderAutomorphismCertificate certificate) {
        verify(Objects.requireNonNull(certificate, "certificate"));
    }

    static void requireCertifiedNodeTheory(TypedENode node) {
        Objects.requireNonNull(node, "node");
        for (ContainerLawDeclaration declaration
                : node.operator().containerLaws().values()) {
            declaration.requireCertified();
            for (ContainerLawCertificate certificate
                    : declaration.certificates().values()) {
                verifyContainerLaw(certificate);
            }
        }
        for (PortSchema schema : node.operator().portSchemas()) {
            requireCertifiedSchema(schema);
        }
    }

    static void requireProductionNodeTheory(
            TypedENode node,
            SemanticProfile semanticProfile) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(semanticProfile, "semanticProfile");
        OperatorDeclaration operator = node.operator().declaration();
        for (Map.Entry<PortPath, ContainerLawDeclaration> entry
                : operator.containerLaws().entrySet()) {
            ContainerLawDeclaration declaration = entry.getValue();
            declaration.requireCertified();
            declaration.validateEvidenceFor(
                    operator.operator(),
                    node.outputType(),
                    entry.getKey(),
                    operator.schemaAt(entry.getKey()),
                    true);
            for (ContainerLawCertificate certificate
                    : declaration.certificates().values()) {
                if (!semanticProfile.equals(certificate.semanticProfile())) {
                    throw new IllegalStateException(
                            "Node theory certificate uses another semantic profile");
                }
                verifyContainerLaw(certificate);
            }
        }
        for (PortSchema schema : node.operator().portSchemas()) {
            requireCertifiedSchema(schema);
        }
    }

    static void requireCertifiedAlphaNode(TypedENode node) {
        Objects.requireNonNull(node, "node");
        for (PortValue port : node.ports()) {
            requireCertifiedAlphaPort(port);
        }
    }

    static void requireCertifiedAlphaPort(PortValue port) {
        Objects.requireNonNull(port, "port");
        if (port instanceof BindBlockPort) {
            BinderBlockDescriptor descriptor = ((BindBlockPort) port)
                    .schema().descriptor();
            descriptor.automorphisms().requireCertifiedFor(descriptor);
            requireCertifiedAlphaPort(((BindBlockPort) port).body());
            return;
        }
        if (port instanceof BindPort) {
            requireCertifiedAlphaPort(((BindPort) port).body());
            return;
        }
        if (port instanceof SeqPort) {
            for (PortValue element : ((SeqPort) port).elements()) {
                requireCertifiedAlphaPort(element);
            }
            return;
        }
        if (port instanceof BagPort) {
            for (PortValue element : ((BagPort) port).occurrences()) {
                requireCertifiedAlphaPort(element);
            }
            return;
        }
        if (port instanceof SetPort) {
            for (PortValue element : ((SetPort) port).elements()) {
                requireCertifiedAlphaPort(element);
            }
        }
    }

    private static void requireCertifiedSchema(PortSchema schema) {
        Objects.requireNonNull(schema, "schema");
        if (schema instanceof BindBlockPortSchema) {
            BinderBlockDescriptor descriptor = ((BindBlockPortSchema) schema).descriptor();
            if (!descriptor.hasCertifiedAutomorphisms()) {
                throw new IllegalStateException(
                        "Binder-block schema contains an uncertified automorphism generator");
            }
            for (BinderAutomorphismCertificate certificate
                    : descriptor.automorphisms().generatorCertificates()) {
                verifyBinderAutomorphism(certificate);
            }
            descriptor.automorphisms().requireCertifiedFor(descriptor);
            requireCertifiedSchema(((BindBlockPortSchema) schema).bodySchema());
            return;
        }
        if (schema instanceof BindPortSchema) {
            requireCertifiedSchema(((BindPortSchema) schema).bodySchema());
            return;
        }
        if (schema instanceof SeqPortSchema) {
            SeqPortSchema sequence = (SeqPortSchema) schema;
            if (sequence.isDependent()) {
                for (PortSchema positional : sequence.positionalElementSchemas()) {
                    requireCertifiedSchema(positional);
                }
            } else {
                requireCertifiedSchema(sequence.elementSchema());
            }
            return;
        }
        if (schema instanceof BagPortSchema) {
            requireCertifiedSchema(((BagPortSchema) schema).elementSchema());
            return;
        }
        if (schema instanceof SetPortSchema) {
            requireCertifiedSchema(((SetPortSchema) schema).elementSchema());
        }
    }
}
