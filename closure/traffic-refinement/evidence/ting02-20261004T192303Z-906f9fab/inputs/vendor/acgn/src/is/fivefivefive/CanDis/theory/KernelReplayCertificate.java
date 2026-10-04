package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Witness-indexed replay of {@code xi_n} to the dependent certificate {@code d_n^w}. */
public final class KernelReplayCertificate extends TypedEqualityCertificate {
    private final CoherentWitnessFamily witnessFamily;
    private final LeaderKernelResult leaderKernel;
    private final TypedEqualityCertificate replayDerivation;

    private KernelReplayCertificate(
            CoherentWitnessFamily witnessFamily,
            LeaderKernelResult leaderKernel,
            TypedEqualityCertificate replayDerivation) {
        super(
                CertificateCategory.KERNEL_REPLAY,
                TypedCertificateEndpoint.node(leaderKernel.source()),
                TypedCertificateEndpoint.node(
                        leaderKernel.kernel().act(leaderKernel.inclusion())),
                Collections.singletonList(replayDerivation),
                java.util.Arrays.asList(
                        witnessFamily.structuralKey(),
                        leaderKernel.structuralKey()));
        this.witnessFamily = Objects.requireNonNull(witnessFamily, "witnessFamily");
        this.leaderKernel = Objects.requireNonNull(leaderKernel, "leaderKernel");
        this.replayDerivation = Objects.requireNonNull(
                replayDerivation, "replayDerivation");
        verifyLocal();
    }

    static KernelReplayCertificate create(
            TypedSlottedPortEGraph graph,
            CoherentWitnessFamily witnessFamily,
            LeaderKernelResult leaderKernel) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(witnessFamily, "witnessFamily");
        Objects.requireNonNull(leaderKernel, "leaderKernel");
        graph.requireCurrentWitnessFamily(witnessFamily);
        LeaderKernelResult current = graph.extractLeaderKernel(
                leaderKernel.source());
        if (!current.equals(leaderKernel)) {
            throw new IllegalArgumentException(
                    "Kernel replay requires the current structural leader trace");
        }
        for (TypedFindResult find : leaderKernel.trace().findResults()) {
            witnessFamily.parentCoherence(find.normalizedInvocation().eclass().id());
            CertificateVerifier.verify(find.parentCertificate());
        }

        List<TypedEqualityCertificate> portCertificates = new ArrayList<>();
        for (int index = 0; index < leaderKernel.trace().portTraces().size(); index++) {
            LeaderPortTrace trace = leaderKernel.trace().portTraces().get(index);
            TypedEqualityCertificate certificate = replayPort(
                    graph,
                    leaderKernel.source().operator(),
                    PortPath.at(index),
                    trace);
            if (!trace.sourcePort().equals(trace.normalizedPort())) {
                portCertificates.add(certificate);
            }
        }

        TypedEqualityCertificate nodeReplay;
        TypedENode source = leaderKernel.source();
        TypedENode target = leaderKernel.ambientLeaderNode();
        if (source.equals(target)) {
            nodeReplay = EqualityCertificates.reflexive(
                    TypedCertificateEndpoint.node(source));
        } else {
            nodeReplay = CongruenceCertificate.nodes(source, target, portCertificates);
        }
        TypedENode widenedKernel = leaderKernel.kernel().act(
                leaderKernel.inclusion());
        TypedEqualityCertificate exactContextTransport =
                StructuralAlphaCertificate.create(target, widenedKernel);
        TypedEqualityCertificate oriented = target.equals(widenedKernel)
                ? nodeReplay
                : EqualityCertificates.transitive(
                        nodeReplay, exactContextTransport);
        TypedCertificateEndpoint expectedRight = TypedCertificateEndpoint.node(
                widenedKernel);
        oriented = EqualityCertificates.orient(
                oriented, TypedCertificateEndpoint.node(source), expectedRight);
        CertificateVerifier.verify(oriented);
        KernelReplayCertificate result = new KernelReplayCertificate(
                witnessFamily, leaderKernel, oriented);
        CertificateVerifier.verify(result);
        return result;
    }

    private static TypedEqualityCertificate replayPort(
            TypedSlottedPortEGraph graph,
            InstantiatedOperator operator,
            PortPath path,
            LeaderPortTrace trace) {
        PortValue source = trace.sourcePort();
        PortValue normalized = trace.normalizedPort();
        if (source.equals(normalized)) {
            return EqualityCertificates.reflexive(
                    TypedCertificateEndpoint.port(source));
        }
        switch (trace.kind()) {
            case SLOT:
                throw new IllegalStateException("A slot replay cannot change its endpoint");
            case INVOCATION:
                TypedEqualityCertificate parent = trace.findResult()
                        .orElseThrow(() -> new IllegalStateException(
                                "Invocation replay is missing its find result"))
                        .parentCertificate();
                return CongruenceCertificate.ports(
                        source, normalized, Collections.singletonList(parent));
            case SEQ:
            case BAG:
            case SET:
                List<TypedEqualityCertificate> children = new ArrayList<>();
                for (LeaderPortTrace child : trace.children()) {
                    children.add(replayPort(graph, operator, path.child(), child));
                }
                return ContainerNormalizationCertificate.create(
                        source,
                        normalized,
                        trace.containerNormalization().orElseThrow(
                                () -> new IllegalStateException(
                                        "Container replay is missing its normalization trace")),
                        operator,
                        path,
                        children,
                        graph.requiresProductionTheoryAuthority());
            case BIND:
            case BIND_BLOCK:
                TypedEqualityCertificate body = replayPort(
                        graph, operator, path.child(), trace.children().get(0));
                return CongruenceCertificate.ports(
                        source, normalized, Collections.singletonList(body));
            default:
                throw new IllegalStateException(
                        "Unhandled kernel replay trace " + trace.kind());
        }
    }

    public CoherentWitnessFamily witnessFamily() {
        return witnessFamily;
    }

    public LeaderKernelResult leaderKernel() {
        return leaderKernel;
    }

    public TypedEqualityCertificate replayDerivation() {
        return replayDerivation;
    }

    @Override
    void verifyLocal() {
        TypedCertificateEndpoint expectedLeft = TypedCertificateEndpoint.node(
                leaderKernel.source());
        TypedCertificateEndpoint expectedRight = TypedCertificateEndpoint.node(
                leaderKernel.kernel().act(leaderKernel.inclusion()));
        if (!leftEndpoint().equals(expectedLeft)
                || !rightEndpoint().equals(expectedRight)
                || !replayDerivation.leftEndpoint().equals(expectedLeft)
                || !replayDerivation.rightEndpoint().equals(expectedRight)) {
            throw new IllegalStateException("Malformed witness-indexed kernel replay");
        }
    }
}
