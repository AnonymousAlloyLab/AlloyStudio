package is.fivefivefive.CanDis.theory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Producer fixtures for complete orbit ordering and polymorphic operator identity. */
public final class Phase6SemanticOrderProducerRegressionTest {
    private static final GraphType T = GraphType.constructor("T");
    private static int checks;

    private Phase6SemanticOrderProducerRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: Phase6SemanticOrderProducerRegressionTest <output-dir>");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);

        Fixture canonical = orderedPair(false, "canonical");
        Fixture reversed = orderedPair(true, "reversed");
        check(canonical.result().shape().equals(reversed.result().shape()),
                "alpha variants have one canonical shape");
        check(!canonical.result().kernel().equals(reversed.result().kernel()),
                "counterexample kernels retain distinct source order");
        check(isIdentity(canonical.result().witness()),
                "canonical source selects the identity witness");
        check(!isIdentity(reversed.result().witness()),
                "reversed source selects the nonidentity witness");
        check(reversed.result().shape().node().act(reversed.result().witness())
                        .equals(reversed.result().kernel()),
                "selected witness reconstructs the reversed source exactly");

        canonical.session().write(output.resolve("canonical.acgncert"));
        reversed.session().write(output.resolve("reversed.acgncert"));
        polymorphic(true).write(output.resolve("polymorphic.acgncert"));
        polymorphic(false).write(output.resolve("monomorphic.acgncert"));
        int[][] permutations = {
                {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        for (int index = 0; index < permutations.length; index++) {
            orderedTriple(permutations[index], "order-" + index)
                    .session().write(output.resolve("order-" + index + ".acgncert"));
        }

        System.out.println("Phase6SemanticOrderProducerRegressionTest: "
                + checks + " checks passed; fixtures=" + output);
    }

    private static Fixture orderedPair(boolean reverse, String label)
            throws Exception {
        TypedSlot first = TypedSlot.canonicalFree(T, 0);
        TypedSlot second = TypedSlot.canonicalFree(T, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "phase6-ordered-pair",
                List.of(new OnePortSchema(T), new OnePortSchema(T)),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        List<OnePort> ports = reverse
                ? List.of(OnePort.slot(context, second), OnePort.slot(context, first))
                : List.of(OnePort.slot(context, first), OnePort.slot(context, second));
        return fixture(
                TypedENode.construct(operator, context, ports),
                "fixture/phase6-" + label + ".als",
                "pred " + label + " { phase6_ordered_pair }");
    }

    private static CertificateExportSession polymorphic(boolean generic)
            throws Exception {
        GraphType parameter = GraphType.typeVariable("a");
        InstantiatedOperator operator = generic
                ? new OperatorDeclaration(
                        "phase6-polymorphic",
                        List.of("a"),
                        List.of(new OnePortSchema(parameter)),
                        GraphType.BOOL,
                        Map.of(),
                        null).instantiate(Map.of("a", T))
                : OperatorDeclaration.monomorphic(
                        "phase6-polymorphic",
                        List.of(new OnePortSchema(T)),
                        GraphType.BOOL,
                        Map.of(),
                        null).instantiateMonomorphic();
        TypedSlot slot = TypedSlot.canonicalFree(T, 0);
        TypedSlotContext context = TypedSlotContext.singleton(slot);
        return fixture(
                TypedENode.construct(
                        operator, context, List.of(OnePort.slot(context, slot))),
                "fixture/phase6-" + (generic ? "polymorphic" : "monomorphic")
                        + ".als",
                "pred p { phase6_polymorphic }").session();
    }

    private static Fixture orderedTriple(int[] order, String label)
            throws Exception {
        TypedSlot first = TypedSlot.canonicalFree(T, 0);
        TypedSlot second = TypedSlot.canonicalFree(T, 1);
        TypedSlot third = TypedSlot.canonicalFree(T, 2);
        List<TypedSlot> slots = List.of(first, second, third);
        TypedSlotContext context = TypedSlotContext.of(first, second, third);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "phase6-ordered-triple",
                List.of(new OnePortSchema(T), new OnePortSchema(T), new OnePortSchema(T)),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return fixture(
                TypedENode.construct(
                        operator,
                        context,
                        List.of(
                                OnePort.slot(context, slots.get(order[0])),
                                OnePort.slot(context, slots.get(order[1])),
                                OnePort.slot(context, slots.get(order[2])))),
                "fixture/phase6-" + label + ".als",
                "pred " + label.replace('-', '_') + " { phase6_ordered_triple }");
    }

    private static Fixture fixture(
            TypedENode source,
            String inputIdentifier,
            String inputText) throws Exception {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        CertifiedInsertionResult insertion = graph.insertNode(
                source, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(1, 8))
                .enumerate(insertion.returnedInvocation()).get(0);
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                insertion.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                SemanticProfile.alloyOverflowForbidding());
        CertificateExportSession session = new CertificateExportSession(
                sink,
                graph,
                artifact,
                unfolding.normalizedTermKey(),
                Map.of(),
                CertificateProvenance.capture(
                        inputIdentifier,
                        inputText.getBytes(StandardCharsets.UTF_8),
                        "phase6-semantic-order-regression;"
                                + CertificateTheoryManifest.VERSION),
                "phase6-semantic-order-regression");
        return new Fixture(insertion.canonicalization().structural(), session);
    }

    private static boolean isIdentity(TypedRenaming renaming) {
        return renaming.equals(TypedRenaming.identity(renaming.source()));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record Fixture(
            CanonicalizationResult result,
            CertificateExportSession session) {
    }
}
