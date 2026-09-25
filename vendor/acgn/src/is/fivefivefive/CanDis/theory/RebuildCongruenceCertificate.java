package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Retained proof object for one deterministic {@code canon_G} rebuild step. */
public final class RebuildCongruenceCertificate extends TypedEqualityCertificate {
    private final CanonicalizationResult result;
    private final TypedEqualityCertificate replayDerivation;

    private RebuildCongruenceCertificate(
            CanonicalizationResult result,
            TypedEqualityCertificate replayDerivation) {
        super(
                CertificateCategory.FORWARD_CONGRUENCE,
                TypedCertificateEndpoint.node(result.source()),
                TypedCertificateEndpoint.node(result.shape().node().act(
                        result.ambientTransport())),
                Collections.singletonList(replayDerivation),
                Collections.singletonList(result.structuralKey()));
        this.result = Objects.requireNonNull(result, "result");
        this.replayDerivation = Objects.requireNonNull(
                replayDerivation, "replayDerivation");
        verifyLocal();
    }

    static TypedEqualityCertificate create(
            TypedSlottedPortEGraph graph,
            CanonicalizationResult result) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(result, "result");
        if (!result.verifyWitness(graph)) {
            throw new IllegalArgumentException(
                    "A rebuild certificate requires a replayable canonicalization witness");
        }
        TypedCertificateEndpoint left = TypedCertificateEndpoint.node(result.source());
        TypedCertificateEndpoint right = TypedCertificateEndpoint.node(
                result.shape().node().act(result.ambientTransport()));
        if (left.equals(right)) {
            return EqualityCertificates.reflexive(left);
        }

        TypedEqualityCertificate replay = replayCanonicalization(graph, result);
        replay = EqualityCertificates.orient(replay, left, right);
        CertificateVerifier.verify(replay);
        RebuildCongruenceCertificate certificate =
                new RebuildCongruenceCertificate(result, replay);
        CertificateVerifier.verify(certificate);
        return certificate;
    }

    private static TypedEqualityCertificate replayCanonicalization(
            TypedSlottedPortEGraph graph,
            CanonicalizationResult result) {
        TypedEqualityCertificate sourceToKernel = replayLeaderKernel(
                graph, result.leaderKernel());
        TypedENode widenedKernel = result.kernel().act(result.inclusion());
        TypedENode widenedCanonical = result.shape().node().act(
                result.ambientTransport());
        TypedEqualityCertificate kernelToCanonical = replayGraphRelativeNodes(
                graph, widenedKernel, widenedCanonical);
        return EqualityCertificates.transitive(
                sourceToKernel,
                kernelToCanonical);
    }

    private static TypedEqualityCertificate replayLeaderKernel(
            TypedSlottedPortEGraph graph,
            LeaderKernelResult leaderKernel) {
        List<TypedEqualityCertificate> changedPorts = new ArrayList<>();
        List<LeaderPortTrace> traces = leaderKernel.trace().portTraces();
        for (int index = 0; index < traces.size(); index++) {
            LeaderPortTrace trace = traces.get(index);
            TypedEqualityCertificate certificate = replayLeaderPort(
                    graph,
                    leaderKernel.source().operator(),
                    PortPath.at(index),
                    trace);
            if (!trace.sourcePort().equals(trace.normalizedPort())) {
                changedPorts.add(certificate);
            }
        }

        TypedENode source = leaderKernel.source();
        TypedENode ambientLeader = leaderKernel.ambientLeaderNode();
        TypedEqualityCertificate sourceToAmbient = source.equals(ambientLeader)
                ? EqualityCertificates.reflexive(TypedCertificateEndpoint.node(source))
                : CongruenceCertificate.nodes(source, ambientLeader, changedPorts);
        TypedENode widenedKernel = leaderKernel.kernel().act(
                leaderKernel.inclusion());
        if (ambientLeader.equals(widenedKernel)) {
            return sourceToAmbient;
        }
        return EqualityCertificates.transitive(
                sourceToAmbient,
                StructuralAlphaCertificate.create(ambientLeader, widenedKernel));
    }

    private static TypedEqualityCertificate replayLeaderPort(
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
                    children.add(replayLeaderPort(
                            graph, operator, path.child(), child));
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
                TypedEqualityCertificate body = replayLeaderPort(
                        graph, operator, path.child(), trace.children().get(0));
                return CongruenceCertificate.ports(
                        source, normalized, Collections.singletonList(body));
            default:
                throw new IllegalStateException(
                        "Unhandled leader replay trace " + trace.kind());
        }
    }

    private static TypedEqualityCertificate replayGraphRelativeNodes(
            TypedSlottedPortEGraph graph,
            TypedENode left,
            TypedENode right) {
        TypedRenaming identity = TypedRenaming.identity(left.context());
        if (!TypedAlphaEquivalence.graphRelativeNodes(graph, left, right, identity)) {
            throw new IllegalArgumentException(
                    "Canonical rebuild endpoints are not graph-relative alpha-equivalent");
        }
        List<PortValue> alignedPorts = new ArrayList<>(left.ports().size());
        List<TypedEqualityCertificate> changedPorts = new ArrayList<>();
        for (int index = 0; index < left.ports().size(); index++) {
            PortAlignment alignment = alignPort(
                    graph,
                    left.operator(),
                    PortPath.at(index),
                    left.ports().get(index),
                    right.ports().get(index),
                    identity);
            alignedPorts.add(alignment.aligned);
            if (!left.ports().get(index).equals(alignment.aligned)) {
                changedPorts.add(alignment.derivation);
            }
        }
        TypedENode aligned = left.rebuildCanonicalCandidate(
                left.context(), alignedPorts);
        TypedEqualityCertificate leftToAligned = left.equals(aligned)
                ? EqualityCertificates.reflexive(TypedCertificateEndpoint.node(left))
                : CongruenceCertificate.nodes(left, aligned, changedPorts);
        if (!TypedAlphaEquivalence.structuralNodes(aligned, right, identity)) {
            throw new IllegalStateException(
                    "Replayed graph equalities did not leave structural alpha endpoints");
        }
        if (aligned.equals(right)) {
            return leftToAligned;
        }
        return EqualityCertificates.transitive(
                leftToAligned, StructuralAlphaCertificate.create(aligned, right));
    }

    private static PortAlignment alignPort(
            TypedSlottedPortEGraph graph,
            InstantiatedOperator operator,
            PortPath path,
            PortValue left,
            PortValue right,
            TypedRenaming renaming) {
        if (!TypedAlphaEquivalence.graphRelativePorts(
                graph, left, right, renaming)) {
            throw new IllegalArgumentException(
                    "Port endpoints are not graph-relative alpha-equivalent");
        }
        if (left instanceof OnePort) {
            return alignOne(graph, (OnePort) left, (OnePort) right, renaming);
        }
        if (left instanceof SeqPort) {
            return alignContainer(
                    graph, operator, path, left, right, renaming,
                    orderedMatches(
                            ((SeqPort) left).elements(),
                            ((SeqPort) right).elements()));
        }
        if (left instanceof BagPort) {
            List<PortValue> leftValues = ((BagPort) left).occurrences();
            List<PortValue> rightValues = ((BagPort) right).occurrences();
            return alignContainer(
                    graph, operator, path, left, right, renaming,
                    perfectMatches(graph, leftValues, rightValues, renaming));
        }
        if (left instanceof SetPort) {
            List<PortValue> leftValues = ((SetPort) left).elements();
            List<PortValue> rightValues = ((SetPort) right).elements();
            if (leftValues.size() < rightValues.size()) {
                throw new IllegalStateException(
                        "Canonical Set replay cannot introduce new occurrences");
            }
            return alignContainer(
                    graph, operator, path, left, right, renaming,
                    coveringMatches(graph, leftValues, rightValues, renaming));
        }
        if (left instanceof BindPort) {
            BindPort leftBind = (BindPort) left;
            BindPort rightBind = (BindPort) right;
            TypedRenaming extended = renaming.disjointExtension(
                    leftBind.boundSlot(), rightBind.boundSlot()).asRenaming();
            PortAlignment body = alignPort(
                    graph,
                    operator,
                    path.child(),
                    leftBind.body(),
                    rightBind.body(),
                    extended);
            PortValue aligned = new BindPort(
                    leftBind.schema(),
                    leftBind.context(),
                    leftBind.boundSlot(),
                    body.aligned);
            return liftedAlignment(left, aligned, body.derivation);
        }
        if (left instanceof BindBlockPort) {
            BindBlockPort leftBlock = (BindBlockPort) left;
            BindBlockPort rightBlock = (BindBlockPort) right;
            TypedRenaming extended = matchingBlockRenaming(
                    graph, leftBlock, rightBlock, renaming);
            PortAlignment body = alignPort(
                    graph,
                    operator,
                    path.child(),
                    leftBlock.body(),
                    rightBlock.body(),
                    extended);
            PortValue aligned = new BindBlockPort(
                    leftBlock.schema(),
                    leftBlock.context(),
                    leftBlock.descriptorToOccurrence(),
                    body.aligned);
            return liftedAlignment(left, aligned, body.derivation);
        }
        throw new IllegalStateException(
                "Unhandled graph-relative port " + left.getClass().getName());
    }

    private static PortAlignment alignOne(
            TypedSlottedPortEGraph graph,
            OnePort left,
            OnePort right,
            TypedRenaming renaming) {
        PortValue aligned = right.act(renaming.inverse());
        if (left.equals(aligned)) {
            return PortAlignment.identity(left);
        }
        if (!(left.leaf() instanceof InvocationPortLeaf)
                || !(((OnePort) aligned).leaf() instanceof InvocationPortLeaf)) {
            throw new IllegalStateException(
                    "Only invocation leaves can require graph-relative alignment");
        }
        TypedInvocation leftInvocation =
                ((InvocationPortLeaf) left.leaf()).invocation();
        TypedInvocation alignedInvocation = ((InvocationPortLeaf)
                ((OnePort) aligned).leaf()).invocation();
        TypedEqualityCertificate invocation = replayInvocationEquality(
                graph, leftInvocation, alignedInvocation);
        return new PortAlignment(
                aligned,
                CongruenceCertificate.ports(
                        left, aligned, Collections.singletonList(invocation)));
    }

    private static TypedEqualityCertificate replayInvocationEquality(
            TypedSlottedPortEGraph graph,
            TypedInvocation left,
            TypedInvocation right) {
        TypedFindResult leftFind = graph.findForCanonicalization(left);
        TypedFindResult rightFind = graph.findForCanonicalization(right);
        TypedInvocation leftLeader = leftFind.leaderInvocation();
        TypedInvocation rightLeader = rightFind.leaderInvocation();
        if (!leftLeader.eclass().equals(rightLeader.eclass())) {
            throw new IllegalArgumentException(
                    "Invocation replay reached different leader e-classes");
        }
        TypedSymmetryGroup group = graph.symmetryGroupForCanonicalization(
                leftLeader.eclass());
        final TypedPermutation[] witness = new TypedPermutation[1];
        boolean found = group.anyMatch(permutation -> {
            if (permutation.andThen(rightLeader.embedding()).equals(
                    leftLeader.embedding())) {
                witness[0] = permutation;
                return true;
            }
            return false;
        });
        if (!found) {
            throw new IllegalArgumentException(
                    "Invocation replay lacks a certified symmetry witness");
        }

        TypedEqualityCertificate rightLeaderToLeftLeader = EqualityCertificates.rename(
                group.derivationFor(leftLeader.eclass(), witness[0]),
                rightLeader.embedding());
        TypedEqualityCertificate leftToRightLeader = EqualityCertificates.transitive(
                leftFind.parentCertificate(),
                EqualityCertificates.symmetric(rightLeaderToLeftLeader));
        TypedEqualityCertificate replay = EqualityCertificates.transitive(
                leftToRightLeader,
                EqualityCertificates.symmetric(rightFind.parentCertificate()));
        return EqualityCertificates.orient(
                replay,
                TypedCertificateEndpoint.invocation(left),
                TypedCertificateEndpoint.invocation(right));
    }

    private static PortAlignment alignContainer(
            TypedSlottedPortEGraph graph,
            InstantiatedOperator operator,
            PortPath path,
            PortValue left,
            PortValue right,
            TypedRenaming renaming,
            int[] rightForLeft) {
        List<PortValue> leftValues = elements(left);
        List<PortValue> rightValues = elements(right);
        if (rightForLeft.length != leftValues.size()) {
            throw new IllegalArgumentException("Container match does not cover its source");
        }
        List<PortValue> alignedValues = new ArrayList<>(leftValues.size());
        List<TypedEqualityCertificate> childDerivations = new ArrayList<>(
                leftValues.size());
        for (int index = 0; index < leftValues.size(); index++) {
            int target = rightForLeft[index];
            if (target < 0 || target >= rightValues.size()) {
                throw new IllegalArgumentException("Container match has an invalid target");
            }
            PortAlignment child = alignPort(
                    graph,
                    operator,
                    path.child(),
                    leftValues.get(index),
                    rightValues.get(target),
                    renaming);
            alignedValues.add(child.aligned);
            childDerivations.add(child.derivation);
        }
        PortValue aligned = rebuildContainer(left, alignedValues);
        ContainerNormalizationTrace trace = ContainerNormalizationTrace.of(
                left, alignedValues, aligned);
        TypedEqualityCertificate derivation = ContainerNormalizationCertificate.create(
                left,
                aligned,
                trace,
                operator,
                path,
                childDerivations,
                graph.requiresProductionTheoryAuthority());
        if (!TypedAlphaEquivalence.structuralPorts(aligned, right, renaming)) {
            throw new IllegalStateException(
                    "Container matching did not leave structural alpha endpoints");
        }
        return new PortAlignment(aligned, derivation);
    }

    private static PortAlignment liftedAlignment(
            PortValue source,
            PortValue aligned,
            TypedEqualityCertificate child) {
        if (source.equals(aligned)) {
            return PortAlignment.identity(source);
        }
        return new PortAlignment(
                aligned,
                CongruenceCertificate.ports(
                        source, aligned, Collections.singletonList(child)));
    }

    private static TypedRenaming matchingBlockRenaming(
            TypedSlottedPortEGraph graph,
            BindBlockPort left,
            BindBlockPort right,
            TypedRenaming freeRenaming) {
        BinderAutomorphismGroup group = graph.binderGroupForCanonicalization(
                left.schema().descriptor());
        final TypedRenaming[] result = new TypedRenaming[1];
        boolean found = group.anyMatch(automorphism -> {
            TypedRenaming boundAlignment = left.descriptorToOccurrence()
                    .inverse()
                    .andThen(automorphism)
                    .andThen(right.descriptorToOccurrence());
            TypedRenaming extended = freeRenaming
                    .disjointUnion(boundAlignment)
                    .asRenaming();
            if (TypedAlphaEquivalence.graphRelativePorts(
                    graph, left.body(), right.body(), extended)) {
                result[0] = extended;
                return true;
            }
            return false;
        });
        if (!found) {
            throw new IllegalArgumentException(
                    "Binder block lacks a certified matching automorphism");
        }
        return result[0];
    }

    private static int[] orderedMatches(
            List<? extends PortValue> left,
            List<? extends PortValue> right) {
        if (left.size() != right.size()) {
            throw new IllegalArgumentException("Sequence alignment requires equal arity");
        }
        int[] result = new int[left.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = index;
        }
        return result;
    }

    private static int[] perfectMatches(
            TypedSlottedPortEGraph graph,
            List<? extends PortValue> left,
            List<? extends PortValue> right,
            TypedRenaming renaming) {
        if (left.size() != right.size()) {
            throw new IllegalArgumentException("Bag alignment requires equal arity");
        }
        int[] leftForRight = new int[right.size()];
        java.util.Arrays.fill(leftForRight, -1);
        for (int leftIndex = 0; leftIndex < left.size(); leftIndex++) {
            if (!augment(
                    graph,
                    left,
                    right,
                    renaming,
                    leftIndex,
                    new boolean[right.size()],
                    leftForRight)) {
                throw new IllegalArgumentException("Bag alignment has no perfect matching");
            }
        }
        int[] result = new int[left.size()];
        for (int rightIndex = 0; rightIndex < leftForRight.length; rightIndex++) {
            result[leftForRight[rightIndex]] = rightIndex;
        }
        return result;
    }

    private static boolean augment(
            TypedSlottedPortEGraph graph,
            List<? extends PortValue> left,
            List<? extends PortValue> right,
            TypedRenaming renaming,
            int leftIndex,
            boolean[] seen,
            int[] leftForRight) {
        for (int rightIndex = 0; rightIndex < right.size(); rightIndex++) {
            if (seen[rightIndex]
                    || !TypedAlphaEquivalence.graphRelativePorts(
                            graph,
                            left.get(leftIndex),
                            right.get(rightIndex),
                            renaming)) {
                continue;
            }
            seen[rightIndex] = true;
            if (leftForRight[rightIndex] < 0
                    || augment(
                            graph,
                            left,
                            right,
                            renaming,
                            leftForRight[rightIndex],
                            seen,
                            leftForRight)) {
                leftForRight[rightIndex] = leftIndex;
                return true;
            }
        }
        return false;
    }

    private static int[] coveringMatches(
            TypedSlottedPortEGraph graph,
            List<? extends PortValue> left,
            List<? extends PortValue> right,
            TypedRenaming renaming) {
        int[] rightForLeft = new int[left.size()];
        java.util.Arrays.fill(rightForLeft, -1);
        int[] rightForMatchedLeft = perfectInjectionFromRight(
                graph, left, right, renaming);
        for (int leftIndex = 0; leftIndex < rightForMatchedLeft.length; leftIndex++) {
            if (rightForMatchedLeft[leftIndex] >= 0) {
                rightForLeft[leftIndex] = rightForMatchedLeft[leftIndex];
            }
        }
        for (int leftIndex = 0; leftIndex < left.size(); leftIndex++) {
            if (rightForLeft[leftIndex] >= 0) {
                continue;
            }
            for (int rightIndex = 0; rightIndex < right.size(); rightIndex++) {
                if (TypedAlphaEquivalence.graphRelativePorts(
                        graph,
                        left.get(leftIndex),
                        right.get(rightIndex),
                        renaming)) {
                    rightForLeft[leftIndex] = rightIndex;
                    break;
                }
            }
            if (rightForLeft[leftIndex] < 0) {
                throw new IllegalArgumentException("Set source has no target mate");
            }
        }
        return rightForLeft;
    }

    private static int[] perfectInjectionFromRight(
            TypedSlottedPortEGraph graph,
            List<? extends PortValue> left,
            List<? extends PortValue> right,
            TypedRenaming renaming) {
        int[] rightForLeft = new int[left.size()];
        java.util.Arrays.fill(rightForLeft, -1);
        for (int rightIndex = 0; rightIndex < right.size(); rightIndex++) {
            if (!augmentReverse(
                    graph,
                    left,
                    right,
                    renaming,
                    rightIndex,
                    new boolean[left.size()],
                    rightForLeft)) {
                throw new IllegalArgumentException(
                        "Set target cannot be covered by distinct source occurrences");
            }
        }
        return rightForLeft;
    }

    private static boolean augmentReverse(
            TypedSlottedPortEGraph graph,
            List<? extends PortValue> left,
            List<? extends PortValue> right,
            TypedRenaming renaming,
            int rightIndex,
            boolean[] seen,
            int[] rightForLeft) {
        for (int leftIndex = 0; leftIndex < left.size(); leftIndex++) {
            if (seen[leftIndex]
                    || !TypedAlphaEquivalence.graphRelativePorts(
                            graph,
                            left.get(leftIndex),
                            right.get(rightIndex),
                            renaming)) {
                continue;
            }
            seen[leftIndex] = true;
            if (rightForLeft[leftIndex] < 0
                    || augmentReverse(
                            graph,
                            left,
                            right,
                            renaming,
                            rightForLeft[leftIndex],
                            seen,
                            rightForLeft)) {
                rightForLeft[leftIndex] = rightIndex;
                return true;
            }
        }
        return false;
    }

    private static List<PortValue> elements(PortValue port) {
        if (port instanceof SeqPort) {
            return ((SeqPort) port).elements();
        }
        if (port instanceof BagPort) {
            return ((BagPort) port).occurrences();
        }
        if (port instanceof SetPort) {
            return ((SetPort) port).elements();
        }
        throw new IllegalArgumentException("Port is not a flexible-arity container");
    }

    private static PortValue rebuildContainer(
            PortValue source,
            List<? extends PortValue> elements) {
        if (source instanceof SeqPort) {
            return new SeqPort((SeqPortSchema) source.schema(), source.context(), elements);
        }
        if (source instanceof BagPort) {
            return new BagPort((BagPortSchema) source.schema(), source.context(), elements);
        }
        if (source instanceof SetPort) {
            return new SetPort((SetPortSchema) source.schema(), source.context(), elements);
        }
        throw new IllegalArgumentException("Port is not a flexible-arity container");
    }

    public CanonicalizationResult result() {
        return result;
    }

    /** Complete retained derivation from the rebuild source to its canonical endpoint. */
    public TypedEqualityCertificate replayDerivation() {
        return replayDerivation;
    }

    @Override
    void verifyLocal() {
        TypedCertificateEndpoint expectedLeft = TypedCertificateEndpoint.node(
                result.source());
        TypedCertificateEndpoint expectedRight = TypedCertificateEndpoint.node(
                result.shape().node().act(result.ambientTransport()));
        if (!leftEndpoint().equals(expectedLeft)
                || !rightEndpoint().equals(expectedRight)
                || leftEndpoint().equals(rightEndpoint())
                || premises().size() != 1
                || premises().get(0) != replayDerivation
                || !replayDerivation.leftEndpoint().equals(expectedLeft)
                || !replayDerivation.rightEndpoint().equals(expectedRight)) {
            throw new IllegalStateException("Malformed rebuild-congruence certificate");
        }
    }

    private static final class PortAlignment {
        private final PortValue aligned;
        private final TypedEqualityCertificate derivation;

        private PortAlignment(
                PortValue aligned,
                TypedEqualityCertificate derivation) {
            this.aligned = Objects.requireNonNull(aligned, "aligned");
            this.derivation = Objects.requireNonNull(derivation, "derivation");
            CertificateVerifier.verify(derivation);
        }

        private static PortAlignment identity(PortValue port) {
            return new PortAlignment(
                    port,
                    EqualityCertificates.reflexive(
                            TypedCertificateEndpoint.port(port)));
        }
    }
}
