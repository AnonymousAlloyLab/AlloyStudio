package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Bounded refinement checks for FlatExposure.lean; no independent wire claim. */
public final class FlatOperandExposureTest {
    private static int checks;

    public static void main(String[] args) {
        var profile = SemanticProfile.alloyOverflowForbidding();
        var context = TypedSlotContext.empty();
        var graph = new TypedSlottedPortEGraph(profile, new RecordingCertificateTraceSink());
        var operator = operator(Opcode.OR, profile);
        OnePort a = constant(graph, "a");
        OnePort b = constant(graph, "b");
        OnePort c = constant(graph, "c");
        NodeSealer unused = node -> { throw new AssertionError("unexpected sealing"); };
        var child = TypedENode.flatConstructCertified(new FlatApplication(operator, context,
                List.of(new FlatLeaf(b), new FlatLeaf(c))), unused, profile);
        var inserted = graph.insertNodeConstructed(child, graph.coherentWitnessFamily());
        OnePort sealed = OnePort.invocation(context, inserted.returnedInvocation());
        var proof = inserted.sourceToReturnedInvocation();
        var exposure = new FlatOperandExposure(sealed, child.node(), proof, profile);
        var source = new FlatApplication(operator, context, List.of(new FlatLeaf(a), new FlatLeaf(sealed)));
        var ordinary = TypedENode.flatConstructCertified(source, unused, profile);
        var composed = TypedENode.flatConstructCertified(source, unused, profile, Map.of(sealed, exposure));
        check(((SetPort) ordinary.node().ports().get(0)).elements().size() == 2,
                "opaque leaf stays opaque without an insertion witness");
        check(((SetPort) composed.node().ports().get(0)).elements().size() == 3,
                "checked leaf opens to its two operands");
        check(composed.certificate().source() == source, "original source retained");
        check(composed.certificate().leftEndpoint().equals(ordinary.certificate().leftEndpoint()),
                "no replacement source endpoint");
        check(composed.certificate().premises().contains(proof), "insertion proof retained as premise");
        check(composed.certificate().hasExposures(), "composition marked explicitly");
        CertificateVerifier.verify(composed.certificate());
        check(true, "composed certificate verifies");
        reject(() -> new FlatOperandExposure(a, child.node(), proof, profile), "wrong sealed leaf");
        reject(() -> new FlatOperandExposure(sealed, ordinary.node(), proof, profile), "wrong expanded node");
        reject(() -> new FlatOperandExposure(sealed, child.node(),
                EqualityCertificates.reflexive(TypedCertificateEndpoint.node(child.node())), profile),
                "normalization is not an insertion proof");
        reject(() -> new FlatOperandExposure(sealed, child.node(), proof, SemanticProfile.alloyModular()),
                "wrong semantic profile");
        var larger = TypedSlotContext.singleton(TypedSlot.source(GraphType.BOOL, 0));
        var embedded = sealed.act(TypedEmbedding.inclusion(context, larger));
        reject(() -> new FlatOperandExposure(embedded, child.node(), proof, profile),
                "wrong caller context");
        var slot = TypedSlot.source(GraphType.INT, 1);
        reject(() -> new FlatOperandExposure(OnePort.slot(TypedSlotContext.singleton(slot), slot),
                child.node(), proof, profile), "wrong result type and non-invocation");
        reject(() -> TypedENode.flatConstructCertified(source, unused, profile, Map.of(a, exposure)),
                "wrong occurrence lookup key");
        var andSource = new FlatApplication(operator(Opcode.AND, profile), context,
                List.of(new FlatLeaf(a), new FlatLeaf(sealed)));
        var mixed = TypedENode.flatConstructCertified(andSource, unused, profile, Map.of(sealed, exposure));
        check(!mixed.certificate().hasExposures()
                && ((SetPort) mixed.node().ports().get(0)).elements().size() == 2,
                "different Boolean head remains sealed");
        var repeated = new FlatApplication(operator, context,
                List.of(new FlatLeaf(sealed), new FlatLeaf(sealed)));
        var dedup = TypedENode.flatConstructCertified(repeated, unused, profile, Map.of(sealed, exposure));
        check(((SetPort) dedup.node().ports().get(0)).elements().size() == 2,
                "repeated occurrences expand and then deduplicate");
        check(dedup.certificate().premises().stream().filter(p -> p == proof).count() == 2,
                "both occurrence substitutions retained");
        var nested = new FlatApplication(operator, context, List.of(new FlatLeaf(a), source));
        var nestedResult = TypedENode.flatConstructCertified(nested, unused, profile, Map.of(sealed, exposure));
        check(((SetPort) nestedResult.node().ports().get(0)).elements().size() == 3,
                "nested source context preserves all siblings");
        System.out.println("FlatOperandExposureTest: " + checks + " checks passed");
    }

    private static InstantiatedOperator operator(Opcode opcode, SemanticProfile profile) {
        var schema = new SetPortSchema(new OnePortSchema(GraphType.BOOL));
        var path = PortPath.at(0);
        String name = "ALLOY/" + opcode.name();
        var certificates = new ArrayList<ContainerLawCertificate>();
        for (var law : List.of(ContainerLawCertificate.Law.ASSOCIATIVITY,
                ContainerLawCertificate.Law.COMMUTATIVITY, ContainerLawCertificate.Law.IDEMPOTENCY)) {
            certificates.add(AlloyLawRegistry.issue(profile, opcode, name, GraphType.BOOL, path, schema, law));
        }
        return OperatorDeclaration.monomorphic(name, List.of(schema), GraphType.BOOL,
                Map.of(path, ContainerLawDeclaration.certified(schema, certificates)), 0).instantiateMonomorphic();
    }

    private static OnePort constant(TypedSlottedPortEGraph graph, String name) {
        var node = TypedENode.construct(OperatorDeclaration.monomorphic(name, List.of(),
                GraphType.BOOL, Map.of(), null).instantiateMonomorphic(), TypedSlotContext.empty(), List.of());
        return OnePort.invocation(TypedSlotContext.empty(),
                graph.insertNode(node, graph.coherentWitnessFamily()).returnedInvocation());
    }

    private static void reject(Runnable action, String label) {
        try { action.run(); } catch (IllegalArgumentException expected) {
            check(true, label);
            return;
        }
        throw new AssertionError("Accepted " + label);
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
