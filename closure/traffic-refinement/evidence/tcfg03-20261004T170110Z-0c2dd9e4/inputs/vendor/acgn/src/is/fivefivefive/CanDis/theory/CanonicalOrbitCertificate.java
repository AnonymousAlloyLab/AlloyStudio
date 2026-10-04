package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Certified graph-relative alpha-orbit step from a canonical shape to its kernel. */
public final class CanonicalOrbitCertificate extends TypedEqualityCertificate {
    private final CanonicalizationResult result;
    private final CoherentWitnessFamily witnessFamily;

    private CanonicalOrbitCertificate(
            CanonicalizationResult result,
            CoherentWitnessFamily witnessFamily,
            List<? extends TypedEqualityCertificate> premises) {
        super(
                CertificateCategory.CANONICAL_ORBIT,
                TypedCertificateEndpoint.node(
                        result.shape().node().act(result.witness())),
                TypedCertificateEndpoint.node(result.kernel()),
                premises,
                java.util.Arrays.asList(
                        result.structuralKey(),
                        witnessFamily.structuralKey()));
        this.result = Objects.requireNonNull(result, "result");
        this.witnessFamily = Objects.requireNonNull(
                witnessFamily, "witnessFamily");
        verifyLocal();
    }

    static CanonicalOrbitCertificate create(
            TypedSlottedPortEGraph graph,
            CoherentWitnessFamily witnessFamily,
            CanonicalizationResult result) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(witnessFamily, "witnessFamily");
        Objects.requireNonNull(result, "result");
        graph.requireCurrentWitnessFamily(witnessFamily);
        if (!result.verifyWitness(graph)) {
            throw new IllegalArgumentException(
                    "A canonical orbit certificate requires a valid graph-relative witness");
        }

        NavigableMap<StructuralKey, TypedEqualityCertificate> exactPremises =
                new TreeMap<>();
        Set<EClassId> invocations = new TreeSet<>();
        collectInvocationIds(result.shape().node(), invocations);
        collectInvocationIds(result.kernel(), invocations);
        for (EClassId id : invocations) {
            add(exactPremises, witnessFamily.parentCoherence(id));
            for (TypedEqualityCertificate certificate
                    : witnessFamily.symmetryCoherence(id)) {
                add(exactPremises, certificate);
            }
        }
        for (ContainerLawDeclaration declaration
                : result.kernel().operator().containerLaws().values()) {
            declaration.requireCertified();
            for (TypedEqualityCertificate certificate
                    : declaration.certificates().values()) {
                add(exactPremises, certificate);
            }
        }
        collectBinderCertificates(result.shape().node(), exactPremises);
        collectBinderCertificates(result.kernel(), exactPremises);

        CanonicalOrbitCertificate certificate = new CanonicalOrbitCertificate(
                result,
                witnessFamily,
                Collections.unmodifiableList(
                        new ArrayList<>(exactPremises.values())));
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static void add(
            Map<StructuralKey, TypedEqualityCertificate> target,
            TypedEqualityCertificate certificate) {
        CertificateVerifier.verify(certificate);
        TypedEqualityCertificate prior = target.putIfAbsent(
                certificate.structuralKey(), certificate);
        if (prior != null && !prior.equals(certificate)) {
            throw new IllegalStateException(
                    "Certificate structural-key collision in canonical orbit proof");
        }
    }

    private static void collectInvocationIds(TypedENode node, Set<EClassId> target) {
        for (PortValue port : node.ports()) {
            collectInvocationIds(port, target);
        }
    }

    private static void collectInvocationIds(PortValue port, Set<EClassId> target) {
        if (port instanceof OnePort) {
            PortLeaf leaf = ((OnePort) port).leaf();
            if (leaf instanceof InvocationPortLeaf) {
                target.add(((InvocationPortLeaf) leaf).invocation().eclass().id());
            }
            return;
        }
        for (PortValue child : children(port)) {
            collectInvocationIds(child, target);
        }
    }

    private static void collectBinderCertificates(
            TypedENode node,
            Map<StructuralKey, TypedEqualityCertificate> target) {
        for (BinderOccurrenceAutomorphismCertificate certificate
                : BinderOccurrenceProofs.collect(node)) {
            add(target, certificate);
        }
    }

    private static List<PortValue> children(PortValue port) {
        if (port instanceof SeqPort) {
            return ((SeqPort) port).elements();
        }
        if (port instanceof BagPort) {
            return ((BagPort) port).occurrences();
        }
        if (port instanceof SetPort) {
            return ((SetPort) port).elements();
        }
        if (port instanceof BindPort) {
            return Collections.singletonList(((BindPort) port).body());
        }
        if (port instanceof BindBlockPort) {
            return Collections.singletonList(((BindBlockPort) port).body());
        }
        return Collections.emptyList();
    }

    public CanonicalizationResult result() {
        return result;
    }

    public CoherentWitnessFamily witnessFamily() {
        return witnessFamily;
    }

    @Override
    void verifyLocal() {
        if (!leftEndpoint().equals(TypedCertificateEndpoint.node(
                    result.shape().node().act(result.witness())))
                || !rightEndpoint().equals(
                        TypedCertificateEndpoint.node(result.kernel()))) {
            throw new IllegalStateException("Malformed canonical-orbit certificate");
        }
    }
}
