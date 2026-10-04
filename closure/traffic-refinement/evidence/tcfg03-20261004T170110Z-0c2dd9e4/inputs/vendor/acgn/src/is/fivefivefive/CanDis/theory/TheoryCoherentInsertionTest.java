package is.fivefivefive.CanDis.theory;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused gate for coherent replay and the certified source-insertion boundary. */
public final class TheoryCoherentInsertionTest {
    private static final GraphType USER = GraphType.constructor("User");
    private static int checks;

    private TheoryCoherentInsertionTest() {
    }

    public static void main(String[] args) {
        testFreshInsertionAndCertifiedCollision();
        testSetDeduplicationReplay();
        testBinderSafeExactContextReplay();
        testMissingChildRejectedBeforeMutation();
        System.out.println("TheoryCoherentInsertionTest passed: " + checks + " checks");
    }

    private static void testFreshInsertionAndCertifiedCollision() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        TypedENode source = constantNode("coherent-constant");
        CoherentWitnessFamily emptyFamily = graph.coherentWitnessFamily();
        check(emptyFamily.shapeCoherence().isEmpty()
                        && emptyFamily.parentCoherence().isEmpty()
                        && emptyFamily.symmetryCoherence().isEmpty(),
                "The empty strict graph has an empty coherent family");

        CertifiedInsertionResult first = graph.insertNode(source, emptyFamily);
        check(!first.collided() && graph.status() == GraphStatus.QUIESCENT,
                "A fresh canonical key publishes one quiescent owner");
        check(first.insertedClass().exposedSlots().equals(
                        first.canonicalization().structural().effectiveSupport()),
                "Fresh insertion exposes exactly Delta_n");
        CertificateVerifier.verify(first.sourceToReturnedInvocation());

        int classesBeforeStaleAttempt = graph.classes().size();
        expectThrows(IllegalStateException.class,
                () -> graph.insertNode(source, emptyFamily));
        check(graph.classes().size() == classesBeforeStaleAttempt,
                "A stale coherent family fails before graph mutation");

        CoherentWitnessFamily current = graph.coherentWitnessFamily();
        check(current.shapeCoherence().size() == 1
                        && current.parentCoherence().size() == 1
                        && current.symmetryCoherence().size() == 1,
                "The coherent snapshot reconstructs EC, PC, and SC");
        CertifiedInsertionResult second = graph.insertNode(source, current);
        check(second.collided() && second.collisionEdge().isPresent(),
                "An equal canonical key produces a certified collision edge");
        check(countCategory(
                        second.collisionEdge().get(),
                        CertificateCategory.KERNEL_REPLAY) >= 2,
                "Collision provenance retains both source-to-kernel replays");
        check(graph.status() == GraphStatus.DIRTY,
                "A collision enters the ordinary Phase G dirty lifecycle");
        graph.rebuild();
        check(graph.status() == GraphStatus.QUIESCENT
                        && graph.hashOwner(second.canonicalization().shape()) != null,
                "Rebuild republishes the unique canonical key");
        check(graph.insertionHistorySnapshot().size() == 2,
                "Both complete source records remain in graph provenance");
    }

    private static void testSetDeduplicationReplay() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        CertifiedInsertionResult left = graph.insertNode(
                constantNode("dedup-left"), graph.coherentWitnessFamily());
        CertifiedInsertionResult right = graph.insertNode(
                constantNode("dedup-right"), graph.coherentWitnessFamily());

        TypedEClassInterface child = right.insertedClass();
        TypedEClassInterface parent = left.insertedClass();
        InputEquationCertificate input = new InputEquationCertificate(
                CertificateOrigin.inputEquation("coherent-test", "left=right", 0),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.eclassWitness(parent));
        graph.unionCertified(new ParentEdgeCertificate(
                child, TypedInvocation.identity(parent), input));
        graph.rebuild();

        OnePortSchema element = new OnePortSchema(GraphType.BOOL);
        SetPortSchema setSchema = new SetPortSchema(element);
        InstantiatedOperator operator = setOperator(setSchema);
        TypedSlotContext empty = TypedSlotContext.empty();
        List<PortValue> sourceOccurrences = Arrays.asList(
                OnePort.invocation(empty, TypedInvocation.identity(child)),
                OnePort.invocation(empty, TypedInvocation.identity(parent)));
        CertifiedContainerConstruction source =
                TypedENode.constructContainerCertified(
                        operator,
                        PortPath.at(0),
                        empty,
                        sourceOccurrences,
                        SemanticProfile.alloyOverflowForbidding());

        CertifiedCanonicalizationResult result = graph.canonicalizeCertifiedConstructed(
                source, graph.coherentWitnessFamily());
        check(result.sourceConstruction().orElseThrow().equals(source.certificate())
                        && result.sourceReplay().leftEndpoint().equals(
                                source.certificate().leftEndpoint()),
                "Certified canonicalization retains the exact ordered source endpoint");
        ContainerNormalizationTrace trace = result.xi()
                .containerNormalizations().get(0);
        check(trace.deduplicated() && trace.outputOccurrences().size() == 1,
                "Leader replay records graph-induced Set deduplication");
        check(CertificateVerifier.containsCategory(
                        result.d(), CertificateCategory.CONTAINER_NORMALIZATION),
                "d_n^w contains the concrete ACI normalization proof");
        check(result.d().rightEndpoint().equals(TypedCertificateEndpoint.node(
                        result.kernel().act(result.iota()))),
                "Set replay has the exact dependent endpoint");
    }

    private static void testBinderSafeExactContextReplay() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        TypedSlot bound = TypedSlot.canonicalBound(USER, 9);
        TypedSlotContext empty = TypedSlotContext.empty();
        TypedSlotContext bodyContext = empty.plus(bound);
        BindPortSchema schema = new BindPortSchema(
                USER, new OnePortSchema(USER));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "binder-replay",
                Collections.singletonList(schema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        BindPort binder = new BindPort(
                schema,
                empty,
                bound,
                OnePort.slot(bodyContext, bound));
        TypedENode source = TypedENode.construct(
                operator, empty, Collections.singletonList(binder));

        CertifiedCanonicalizationResult result = graph.canonicalizeCertified(
                source, graph.coherentWitnessFamily());
        check(CertificateVerifier.containsCategory(
                        result.d(), CertificateCategory.STRUCTURAL_ALPHA),
                "Exact-context widening records capture-safe binder alpha transport");
        check(result.d().leftEndpoint().equals(
                        TypedCertificateEndpoint.node(source))
                        && result.d().rightEndpoint().equals(
                                TypedCertificateEndpoint.node(
                                        result.kernel().act(result.iota()))),
                "Binder replay checks both dependent endpoints exactly");
    }

    private static void testMissingChildRejectedBeforeMutation() {
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        TypedSlotContext empty = TypedSlotContext.empty();
        TypedEClassInterface missing = new TypedEClassInterface(
                EClassId.of(99), GraphType.BOOL, empty);
        OnePortSchema schema = new OnePortSchema(GraphType.BOOL);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "missing-child",
                Collections.singletonList(schema),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        TypedENode source = TypedENode.construct(
                operator,
                empty,
                Collections.singletonList(
                        OnePort.invocation(empty, TypedInvocation.identity(missing))));

        expectThrows(IllegalArgumentException.class,
                () -> graph.insertNode(source, family));
        check(graph.classes().isEmpty()
                        && graph.hashConsSnapshot().isEmpty()
                        && graph.insertionHistorySnapshot().isEmpty(),
                "A missing child is rejected before U, M, H, or provenance changes");
    }

    private static TypedENode constantNode(String name) {
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.emptyList(),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, TypedSlotContext.empty(), Collections.emptyList());
    }

    private static InstantiatedOperator setOperator(SetPortSchema schema) {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        List<ContainerLawCertificate> certificates = Arrays.asList(
                AlloyLawRegistry.issue(
                        profile, is.fivefivefive.CanDis.core.EGraphNode.Opcode.AND,
                        "ALLOY/AND", GraphType.BOOL, PortPath.at(0), schema,
                        ContainerLawCertificate.Law.ASSOCIATIVITY),
                AlloyLawRegistry.issue(
                        profile, is.fivefivefive.CanDis.core.EGraphNode.Opcode.AND,
                        "ALLOY/AND", GraphType.BOOL, PortPath.at(0), schema,
                        ContainerLawCertificate.Law.COMMUTATIVITY),
                AlloyLawRegistry.issue(
                        profile, is.fivefivefive.CanDis.core.EGraphNode.Opcode.AND,
                        "ALLOY/AND", GraphType.BOOL, PortPath.at(0), schema,
                        ContainerLawCertificate.Law.IDEMPOTENCY));
        Map<PortPath, ContainerLawDeclaration> laws = new LinkedHashMap<>();
        laws.put(PortPath.at(0), ContainerLawDeclaration.certified(
                schema, certificates));
        return OperatorDeclaration.monomorphic(
                "ALLOY/AND",
                Collections.singletonList(schema),
                GraphType.BOOL,
                laws,
                null).instantiateMonomorphic();
    }

    private static int countCategory(
            TypedEqualityCertificate certificate,
            CertificateCategory category) {
        return countCategory(
                certificate,
                category,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static int countCategory(
            TypedEqualityCertificate certificate,
            CertificateCategory category,
            Set<TypedEqualityCertificate> visited) {
        if (!visited.add(certificate)) {
            return 0;
        }
        int count = certificate.category() == category ? 1 : 0;
        for (TypedEqualityCertificate premise : certificate.premises()) {
            count += countCategory(premise, category, visited);
        }
        return count;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            Runnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName()
                            + " but received " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
