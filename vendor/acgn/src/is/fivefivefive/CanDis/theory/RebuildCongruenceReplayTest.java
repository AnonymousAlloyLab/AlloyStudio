package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused regression for independently replayable rebuild congruence. */
public final class RebuildCongruenceReplayTest {
    private static int checks;

    private RebuildCongruenceReplayTest() {
    }

    public static void main(String[] args) {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface left = emptyClass(900);
        TypedEClassInterface right = emptyClass(901);
        TypedEClassInterface owner = emptyClass(902);
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        InstantiatedOperator wrap = unaryOperator("rebuild-replay-wrap");
        admitShapes(
                graph,
                owner,
                List.of(invocationShape(wrap, left), invocationShape(wrap, right)));

        graph.unionCertified(parentEquation(left, right));
        graph.rebuild();
        RebuildCongruenceCertificate certificate = rebuildCertificate(sink.events());
        TypedEqualityCertificate replay = certificate.replayDerivation();

        check(certificate.premises().size() == 1
                        && certificate.premises().get(0) == replay,
                "Rebuild retains exactly its complete replay derivation");
        check(replay.leftEndpoint().equals(certificate.leftEndpoint())
                        && replay.rightEndpoint().equals(certificate.rightEndpoint()),
                "Replay derivation binds both exact rebuild endpoints");
        check(!(replay instanceof RebuildCongruenceCertificate)
                        && !containsType(replay, RebuildCongruenceCertificate.class)
                        && !containsType(replay, CanonicalOrbitCertificate.class),
                "Replay does not recursively trust either endpoint-only wrapper");
        check(countOrigin(replay, "merge-child") == 1,
                "Replay reaches the sole direct parent equation exactly once");
        CertificateVerifier.verify(replay);
        CertificateVerifier.verify(certificate);
        check(true, "The complete retained proof tree independently verifies");

        graph.insertNode(constantNode("post-rebuild-mutation"),
                graph.coherentWitnessFamily());
        CertificateVerifier.verify(certificate);
        check(true, "Replay remains valid without consulting mutable graph state");

        System.out.println("RebuildCongruenceReplayTest passed: "
                + checks + " checks");
    }

    private static RebuildCongruenceCertificate rebuildCertificate(
            List<CertificateTraceEvent> events) {
        for (CertificateTraceEvent event : events) {
            if (event.kind() != CertificateTraceEvent.Kind.REBUILD_RECORD) {
                continue;
            }
            TypedEqualityCertificate root =
                    ((CertificateTracePayload.RebuildRecord) event.payload())
                            .rebuildRoot();
            if (root instanceof RebuildCongruenceCertificate) {
                return (RebuildCongruenceCertificate) root;
            }
        }
        throw new AssertionError("Missing nonreflexive rebuild certificate");
    }

    private static boolean containsType(
            TypedEqualityCertificate root,
            Class<? extends TypedEqualityCertificate> type) {
        Set<TypedEqualityCertificate> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        return containsType(root, type, visited);
    }

    private static boolean containsType(
            TypedEqualityCertificate current,
            Class<? extends TypedEqualityCertificate> type,
            Set<TypedEqualityCertificate> visited) {
        if (!visited.add(current)) {
            return false;
        }
        for (TypedEqualityCertificate premise : current.premises()) {
            if (type.isInstance(premise)
                    || containsType(premise, type, visited)) {
                return true;
            }
        }
        return false;
    }

    private static int countOrigin(
            TypedEqualityCertificate root,
            String declarationId) {
        Set<TypedEqualityCertificate> visited = Collections.newSetFromMap(
                new IdentityHashMap<>());
        return countOrigin(root, declarationId, visited);
    }

    private static int countOrigin(
            TypedEqualityCertificate current,
            String declarationId,
            Set<TypedEqualityCertificate> visited) {
        if (!visited.add(current)) {
            return 0;
        }
        int count = current instanceof InputEquationCertificate
                && ((InputEquationCertificate) current).origin()
                        .declarationId().equals(declarationId) ? 1 : 0;
        for (TypedEqualityCertificate premise : current.premises()) {
            count += countOrigin(premise, declarationId, visited);
        }
        return count;
    }

    private static TypedEClassInterface emptyClass(long id) {
        return new TypedEClassInterface(
                EClassId.of(id), GraphType.BOOL, TypedSlotContext.empty());
    }

    private static InstantiatedOperator unaryOperator(String name) {
        return OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
    }

    private static CanonicalShape invocationShape(
            InstantiatedOperator operator,
            TypedEClassInterface child) {
        TypedSlotContext empty = TypedSlotContext.empty();
        return CanonicalShape.of(TypedENode.construct(
                operator,
                empty,
                Collections.singletonList(OnePort.invocation(
                        empty, TypedInvocation.identity(child)))));
    }

    private static void admitShapes(
            TypedSlottedPortEGraph graph,
            TypedEClassInterface owner,
            List<CanonicalShape> shapes) {
        Map<CanonicalShape, ShapeWitness> witnesses = new LinkedHashMap<>();
        Map<CanonicalShape, TypedEqualityCertificate> equations = new LinkedHashMap<>();
        TypedSlotContext empty = TypedSlotContext.empty();
        for (int index = 0; index < shapes.size(); index++) {
            CanonicalShape shape = shapes.get(index);
            witnesses.put(shape, new ShapeWitness(
                    empty, empty, empty, TypedRenaming.identity(empty)));
            equations.put(shape, new InputEquationCertificate(
                    CertificateOrigin.inputEquation(
                            "rebuild-replay-test", "stored-shape", index),
                    TypedCertificateEndpoint.node(shape.node()),
                    TypedCertificateEndpoint.eclassWitness(owner)));
        }
        graph.admitFixedBatchRecordCertified(
                TypedEClassRecord.of(
                        owner,
                        witnesses,
                        TypedSymmetryGroup.identity(empty)),
                equations);
    }

    private static ParentEdgeCertificate parentEquation(
            TypedEClassInterface child,
            TypedEClassInterface parent) {
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "rebuild-replay-test", "merge-child", 0),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.eclassWitness(parent));
        return new ParentEdgeCertificate(
                child, TypedInvocation.identity(parent), equation);
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

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
