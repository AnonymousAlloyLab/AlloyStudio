package is.fivefivefive.CanDis.theory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import edu.mit.csail.sdg.ast.Sig;
import edu.mit.csail.sdg.ast.Sig.PrimSig;
import edu.mit.csail.sdg.parser.CompModule;
import edu.mit.csail.sdg.parser.CompUtil;
import is.fivefivefive.ACGN.alloy.CallSymbol;
import is.fivefivefive.ACGN.alloy.ExactAlloyType;
import is.fivefivefive.CanDis.core.CallMetadata;
import is.fivefivefive.CanDis.core.EGraphNode;
import is.fivefivefive.CanDis.core.EGraphNode.Metatype;
import is.fivefivefive.CanDis.core.EGraphNode.Opcode;

/** Producer-side deterministic fixtures for the exact certificate bridge slice. */
public final class CertificateBundleWriterTest {
    private static final GraphType T = GraphType.constructor("T");
    private static int checks;

    private CertificateBundleWriterTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException(
                    "usage: CertificateBundleWriterTest <output-dir>");
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        Path coverage = output.resolve("schema-v8-coverage");
        Files.createDirectories(coverage);

        CertificateExportSession nullary = singleFresh(constant("nullary"));
        CertificateExportSession slot = singleFresh(slotLeaf("slot-only"));
        CertificateExportSession parent = parentPathFixture();
        CertificateExportSession equivalentLeft = singleFresh(
                constant("pair-equivalent"),
                "fixture/pair-equivalent-left.als",
                "pred left { pair_equivalent }");
        CertificateExportSession equivalentRight = singleFresh(
                constant("pair-equivalent"),
                "fixture/pair-equivalent-right.als",
                "pred right { pair_equivalent }");
        CertificateExportSession nonEquivalent = singleFresh(
                constant("pair-non-equivalent"),
                "fixture/pair-non-equivalent.als",
                "pred right { pair_non_equivalent }");
        CertificateWriteMetrics nullaryMetrics = exportTwice(
                nullary, output, "nullary");
        check(nullaryMetrics.globalFreeRenamingCandidates() == 1
                        && nullaryMetrics.localQuotientWorkItems() == 0
                        && nullaryMetrics.serializedCanonicalOrbitCandidates() == 1,
                "nullary counters separate global, local, and serialized orbit units");
        exportTwice(slot, output, "slot-only");
        exportTwice(parent, output, "parent-path");
        exportTwice(singleFresh(bindNode()), coverage, "bind");
        exportTwice(singleFresh(bindBlockNode()), coverage, "bind-block");
        exportTwice(singleFresh(symmetricBindBlockNode()), coverage, "bind-block-symmetric");
        exportTwice(dualSymmetricBindBlockFixture(), coverage, "bind-block-dual");
        CertificateWriteMetrics nestedMetrics = exportTwice(
                singleFresh(nestedSameDescriptorBindBlockNode()),
                coverage,
                "bind-block-nested-same-descriptor");
        check(nestedMetrics.serializedCanonicalOrbitCandidates() == 4,
                "two nested binary binder groups serialize an exact four-member orbit");
        exportTwice(certifiedFlatAndFixture(false), coverage, "flat-and");
        exportTwice(certifiedFlatAndFixture(true), coverage, "flat-and-alt");
        exportTwice(certifiedContainerEqualsFixture(), coverage, "container-equals");
        exportTwice(dependentJoinFixture(), coverage, "relation-columns");
        exportTwice(dependentFamilyJoinFixture(), coverage, "relation-family");
        exportTwice(dependentEmptyJoinFixture(), coverage, "relation-empty");
        exportTwice(
                dependentEmptyInteriorJoinFixture(),
                coverage,
                "relation-empty-interior");
        CertificateExportSession callOccurrence = callOccurrenceFixture();
        boolean missingCallRejected = false;
        try {
            callOccurrence.artifact().withCallOccurrenceCertificates(List.of());
        } catch (IllegalArgumentException expected) {
            missingCallRejected = expected.getMessage().contains(
                    "CALL source occurrences and evidence differ");
        }
        check(missingCallRejected,
                "producer artifact construction must reject missing CALL provenance");
        exportTwice(callOccurrence, coverage, "call-occurrence");
        CertificateExportSession nestedCallOccurrence = nestedCallOccurrenceFixture();
        boolean partialNestedCallRejected = false;
        try {
            nestedCallOccurrence.artifact().withCallOccurrenceCertificates(
                    nestedCallOccurrence.artifact().callOccurrenceCertificates()
                            .subList(0, 1));
        } catch (IllegalArgumentException expected) {
            partialNestedCallRejected = expected.getMessage().contains(
                    "CALL source occurrences and evidence differ");
        }
        check(partialNestedCallRejected,
                "producer artifact construction must reject one omitted nested CALL");
        exportTwice(nestedCallOccurrence, coverage, "call-occurrence-nested");
        CertificateWriteMetrics repeatedSlotMetrics = exportTwice(
                repeatedSameTypeFixture(), coverage, "repeated-same-type-slot");
        check(repeatedSlotMetrics.globalFreeRenamingCandidates() == 2
                        && repeatedSlotMetrics.serializedCanonicalOrbitCandidates() == 2,
                "two same-type free slots serialize both typed renamings");
        exportOnce(equivalentLeft, output, "pair-equivalent-left");
        exportOnce(equivalentRight, output, "pair-equivalent-right");
        exportOnce(nonEquivalent, output, "pair-non-equivalent");

        assertUnsupportedPreservesTarget(
                singleFresh(sourceAlphabetLeaf()),
                coverage.resolve("unsupported-alpha.acgncert"));
        assertUnsupportedPreservesTarget(
                multipleUnfoldingsFixture(),
                coverage.resolve("unsupported-multiple-unfoldings.acgncert"));
        assertUnsupportedPreservesTarget(
                insertionCollisionFixture(),
                coverage.resolve("unsupported-insert-collision.acgncert"));
        assertUnsupportedPreservesTarget(
                parentPathFixture(true),
                coverage.resolve("unsupported-indirect-parent.acgncert"));
        for (ContainerLawDeclaration.Kind kind : List.of(
                ContainerLawDeclaration.Kind.SEQ,
                ContainerLawDeclaration.Kind.BAG,
                ContainerLawDeclaration.Kind.SET)) {
            assertUnsupportedPreservesTarget(
                    containerRegistryFixture(kind),
                    coverage.resolve("unsupported-" + kind.name().toLowerCase()
                            + ".acgncert"));
        }
        for (CertificateTraceEvent.Kind kind : List.of(
                CertificateTraceEvent.Kind.ADD_SYMMETRY,
                CertificateTraceEvent.Kind.RESTRICT_INTERFACE,
                CertificateTraceEvent.Kind.REBUILD_RECORD,
                CertificateTraceEvent.Kind.PATH_COMPRESSION,
                CertificateTraceEvent.Kind.UNION)) {
            assertMalformedEventRejected(kind);
        }

        System.out.println("CertificateBundleWriterTest passed: " + checks
                + " checks; fixtures=" + output);
    }

    private static CertificateExportSession singleFresh(TypedENode source) {
        return singleFresh(
                source,
                "fixture/" + source.operator().structuralKey().stableString(),
                source.structuralKey().stableString());
    }

    private static CertificateExportSession callOccurrenceFixture() {
        String callee = "fixture/call";
        String kind = "call/formula";
        String authority = CallSymbol.ArityAuthority.DECLARATION.name();
        GraphType atom = GraphType.constructor("AlloySig:A");
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedInvocation first = graph.insertNode(
                relationConstant("ALLOY/GLOBALBINDING/a", atom),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation second = graph.insertNode(
                relationConstant("ALLOY/GLOBALBINDING/b", atom),
                graph.coherentWitnessFamily()).returnedInvocation();
        List<OnePort> arguments = List.of(
                OnePort.invocation(TypedSlotContext.empty(), first),
                OnePort.invocation(TypedSlotContext.empty(), second));
        TypedENode source = TypedENode.construct(
                OperatorDeclaration.monomorphic(
                        "ALLOY/CALL/" + callee + "/2/" + kind + "/" + authority,
                        List.of(new OnePortSchema(atom), new OnePortSchema(atom)),
                        GraphType.BOOL,
                        Map.of(),
                        null).instantiateMonomorphic(),
                TypedSlotContext.empty(),
                arguments);
        EGraphNode firstSource = new EGraphNode(
                1702, Opcode.CONSTANT, List.of(), false, 0, false, Metatype.SET);
        firstSource.setSourceName("a");
        EGraphNode secondSource = new EGraphNode(
                1703, Opcode.CONSTANT, List.of(), false, 0, false, Metatype.SET);
        secondSource.setSourceName("b");
        EGraphNode occurrence = new EGraphNode(
                1701,
                Opcode.CALL,
                List.of(firstSource, secondSource),
                false,
                2,
                false,
                Metatype.BOOLEAN,
                SemanticProfile.alloyOverflowForbidding());
        occurrence.setSourceName("call");
        occurrence.setSemanticIdentity(callee);
        occurrence.setSourceType(kind);
        occurrence.setCallOccurrenceId(17L);
        occurrence.setDeclaredArity(2);
        occurrence.setCallArityAuthority(authority);
        CallOccurrenceCertificate call = CallOccurrenceCertificate.create(
                CallMetadata.require(occurrence),
                "phase/0/matrix",
                source,
                arguments);
        CertifiedInsertionResult insertion = graph.insertNode(
                source, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 16))
                .enumerate(insertion.returnedInvocation()).get(0);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        ConstructionSourceLedger.Builder sourceLedger =
                ConstructionSourceLedger.builder(profile);
        sourceLedger.recordCall(call);
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                insertion.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(call),
                sourceLedger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/call-occurrence.als",
                            "pred p { call[] }".getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-call-occurrence-v1;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-call-occurrence-v1");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession nestedCallOccurrenceFixture() {
        String callee = "fixture/nested-call";
        String kind = "call/expression";
        String authority = CallSymbol.ArityAuthority.DECLARATION.name();
        GraphType atom = GraphType.constructor("AlloySig:A");
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedInvocation argumentInvocation = graph.insertNode(
                relationConstant("ALLOY/GLOBALBINDING/a", atom),
                graph.coherentWitnessFamily()).returnedInvocation();
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "ALLOY/CALL/" + callee + "/1/" + kind + "/" + authority,
                List.of(new OnePortSchema(atom)),
                atom,
                Map.of(),
                null).instantiateMonomorphic();
        OnePort innerArgument = OnePort.invocation(
                TypedSlotContext.empty(), argumentInvocation);
        TypedENode inner = TypedENode.construct(
                operator, TypedSlotContext.empty(), List.of(innerArgument));
        TypedInvocation innerInvocation = graph.insertNode(
                inner, graph.coherentWitnessFamily()).returnedInvocation();
        OnePort outerArgument = OnePort.invocation(
                TypedSlotContext.empty(), innerInvocation);
        TypedENode outer = TypedENode.construct(
                operator, TypedSlotContext.empty(), List.of(outerArgument));

        EGraphNode sourceArgument = new EGraphNode(
                1802, Opcode.CONSTANT, List.of(), false, 0, false, Metatype.SET);
        sourceArgument.setSourceName("a");
        EGraphNode innerOccurrence = callOccurrenceSource(
                1801, sourceArgument, callee, kind, authority, 18L);
        EGraphNode outerOccurrence = callOccurrenceSource(
                1800, innerOccurrence, callee, kind, authority, 19L);
        CallOccurrenceCertificate innerCall = CallOccurrenceCertificate.create(
                CallMetadata.require(innerOccurrence),
                "phase/0/matrix/0",
                inner,
                List.of(innerArgument));
        CallOccurrenceCertificate outerCall = CallOccurrenceCertificate.create(
                CallMetadata.require(outerOccurrence),
                "phase/0/matrix",
                outer,
                List.of(outerArgument));

        CertifiedInsertionResult insertion = graph.insertNode(
                outer, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(3, 32))
                .enumerate(insertion.returnedInvocation()).get(0);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        ConstructionSourceLedger.Builder sourceLedger =
                ConstructionSourceLedger.builder(profile);
        sourceLedger.recordCall(innerCall);
        sourceLedger.recordCall(outerCall);
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                insertion.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(innerCall, outerCall),
                sourceLedger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/call-occurrence-nested.als",
                            "fun f[x: A]: A { x }\npred p { f[f[A]] = A }"
                                    .getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-call-occurrence-nested-v1;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-call-occurrence-nested-v1");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static EGraphNode callOccurrenceSource(
            int id,
            EGraphNode argument,
            String callee,
            String kind,
            String authority,
            long occurrenceId) {
        EGraphNode occurrence = new EGraphNode(
                id,
                Opcode.CALL,
                List.of(argument),
                false,
                1,
                false,
                Metatype.SET,
                SemanticProfile.alloyOverflowForbidding());
        occurrence.setSourceName("call");
        occurrence.setSemanticIdentity(callee);
        occurrence.setSourceType(kind);
        occurrence.setCallOccurrenceId(occurrenceId);
        occurrence.setDeclaredArity(1);
        occurrence.setCallArityAuthority(authority);
        return occurrence;
    }

    private static CertificateExportSession singleFresh(
            TypedENode source,
            String inputIdentifier,
            String inputContent) {
        FreshFixture fixture = freshFixture(source);
        return session(
                fixture.sink(),
                fixture.graph(),
                fixture.insertion().returnedInvocation(),
                fixture.family(),
                List.of(fixture.unfolding()),
                Map.of(),
                inputIdentifier,
                inputContent);
    }

    private static FreshFixture freshFixture(TypedENode source) {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        CertifiedInsertionResult insertion = graph.insertNode(
                source, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        List<FiniteUnfoldingTree> trees = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(1, 8))
                .enumerate(insertion.returnedInvocation());
        check(trees.size() == 1 && trees.get(0).height() == 1,
                "single fresh insertion has one height-one unfolding");
        return new FreshFixture(sink, graph, insertion, family, trees.get(0));
    }

    private static CertificateExportSession parentPathFixture() {
        return parentPathFixture(false);
    }

    private static CertificateExportSession parentPathFixture(boolean indirectEdge) {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        CertifiedInsertionResult left = graph.insertNode(
                slotLeaf("left"), graph.coherentWitnessFamily());
        CertifiedInsertionResult right = graph.insertNode(
                slotLeaf("right"), graph.coherentWitnessFamily());

        TypedInvocation parent = TypedInvocation.identity(left.insertedClass());
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "certificate-writer-fixture", "right=left", 0),
                TypedCertificateEndpoint.eclassWitness(right.insertedClass()),
                TypedCertificateEndpoint.invocation(parent));
        TypedEqualityCertificate derivation = equation;
        if (indirectEdge) {
            derivation = EqualityCertificates.transitive(
                    equation,
                    EqualityCertificates.reflexive(
                            TypedCertificateEndpoint.invocation(parent)));
        }
        ParentEdgeCertificate edge = new ParentEdgeCertificate(
                right.insertedClass(), parent, derivation);
        graph.unionCertified(edge);
        RebuildReport rebuild = graph.rebuild();
        check(rebuild.processedRecords() == 0
                        && rebuild.changedKeys() == 0
                        && graph.status() == GraphStatus.QUIESCENT,
                "leaf union needs only REBUILD_COMPLETE");

        TypedSlotContext gamma = right.insertedClass().exposedSlots();
        OnePortSchema schema = new OnePortSchema(GraphType.BOOL);
        InstantiatedOperator wrapper = OperatorDeclaration.monomorphic(
                "wrap",
                List.of(schema),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        TypedENode wrapperSource = TypedENode.construct(
                wrapper,
                gamma,
                List.of(OnePort.invocation(
                        gamma, TypedInvocation.identity(right.insertedClass()))));
        CertifiedInsertionResult wrapped = graph.insertNode(
                wrapperSource, graph.coherentWitnessFamily());
        List<TypedFindResult> finds = wrapped.canonicalization()
                .structural().xi().findResults();
        check(finds.size() == 1 && finds.get(0).parentPath().steps().size() == 1,
                "wrapper retains one genuine parent step");
        check(finds.get(0).parentPath().steps().get(0).certificate().equals(edge),
                "wrapper parent path retains the certified union edge");

        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        List<FiniteUnfoldingTree> trees = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 16))
                .enumerate(wrapped.returnedInvocation());
        FiniteUnfoldingTree selected = trees.stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing height-two unfolding"));
        check(selected.invocationChildren().size() == 1,
                "wrapper unfolding has its exact representative child");
        check(sink.events().stream().map(CertificateTraceEvent::kind).toList().equals(
                        List.of(
                                CertificateTraceEvent.Kind.INSERT_FRESH,
                                CertificateTraceEvent.Kind.INSERT_FRESH,
                                CertificateTraceEvent.Kind.UNION,
                                CertificateTraceEvent.Kind.REBUILD_START,
                                CertificateTraceEvent.Kind.REBUILD_COMPLETE,
                                CertificateTraceEvent.Kind.INSERT_FRESH)),
                "parent fixture retains the exact six-event history");
        return session(
                sink,
                graph,
                wrapped.returnedInvocation(),
                family,
                List.of(selected),
                Map.of(),
                "fixture/parent-path",
                "parent-path-fixture");
    }

    private static CertificateExportSession repeatedSameTypeFixture() {
        TypedSlot first = TypedSlot.canonicalFree(T, 0);
        TypedSlot second = TypedSlot.canonicalFree(T, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "two-slots",
                List.of(new OnePortSchema(T), new OnePortSchema(T)),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        TypedENode source = TypedENode.construct(
                operator,
                context,
                List.of(OnePort.slot(context, first), OnePort.slot(context, second)));
        return singleFresh(source);
    }

    private static CertificateExportSession multipleUnfoldingsFixture() {
        FreshFixture fixture = freshFixture(constant("multiple-unfoldings"));
        return session(
                fixture.sink(),
                fixture.graph(),
                fixture.insertion().returnedInvocation(),
                fixture.family(),
                List.of(fixture.unfolding(), fixture.unfolding()),
                Map.of(),
                "fixture/multiple-unfoldings",
                "multiple-unfoldings-fixture");
    }

    private static CertificateExportSession insertionCollisionFixture() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedENode source = constant("collision");
        graph.insertNode(source, graph.coherentWitnessFamily());
        CertifiedInsertionResult collision = graph.insertNode(
                source, graph.coherentWitnessFamily());
        check(collision.collided(), "duplicate source creates a retained collision");
        graph.rebuild();
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree tree = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(1, 8))
                .enumerate(collision.returnedInvocation()).get(0);
        return session(
                sink,
                graph,
                collision.returnedInvocation(),
                family,
                List.of(tree),
                Map.of(),
                "fixture/insertion-collision",
                "insertion-collision-fixture");
    }

    private static CertificateExportSession containerRegistryFixture(
            ContainerLawDeclaration.Kind kind) {
        FreshFixture fixture = freshFixture(constant("container-" + kind.name()));
        ContainerLawDeclaration declaration = switch (kind) {
            case SEQ -> ContainerLawDeclaration.of(kind, true, false, false, false);
            case BAG -> ContainerLawDeclaration.of(kind, true, true, false, false);
            case SET -> ContainerLawDeclaration.of(kind, true, true, true, false);
            default -> throw new IllegalArgumentException("not a container kind: " + kind);
        };
        return session(
                fixture.sink(),
                fixture.graph(),
                fixture.insertion().returnedInvocation(),
                fixture.family(),
                List.of(fixture.unfolding()),
                Map.of(kind.name(), List.of(declaration)),
                "fixture/container-" + kind.name(),
                "container-" + kind.name());
    }

    private static CertificateExportSession certifiedFlatAndFixture(
            boolean alternateGrouping) {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        TypedSlotContext context = TypedSlotContext.empty();
        PortPath path = PortPath.at(0);
        SetPortSchema schema = new SetPortSchema(new OnePortSchema(GraphType.BOOL));
        List<ContainerLawCertificate> certificates = List.of(
                AlloyLawRegistry.issue(
                        profile,
                        Opcode.AND,
                        "ALLOY/AND",
                        GraphType.BOOL,
                        path,
                        schema,
                        ContainerLawCertificate.Law.ASSOCIATIVITY),
                AlloyLawRegistry.issue(
                        profile,
                        Opcode.AND,
                        "ALLOY/AND",
                        GraphType.BOOL,
                        path,
                        schema,
                        ContainerLawCertificate.Law.COMMUTATIVITY),
                AlloyLawRegistry.issue(
                        profile,
                        Opcode.AND,
                        "ALLOY/AND",
                        GraphType.BOOL,
                        path,
                        schema,
                        ContainerLawCertificate.Law.IDEMPOTENCY));
        ContainerLawDeclaration declaration = ContainerLawDeclaration.certified(
                schema, certificates);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "ALLOY/AND",
                List.of(schema),
                GraphType.BOOL,
                Map.of(path, declaration),
                0).instantiateMonomorphic();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        CertifiedInsertionResult first = graph.insertNode(
                constant("and-a"), graph.coherentWitnessFamily());
        CertifiedInsertionResult second = graph.insertNode(
                constant("and-b"), graph.coherentWitnessFamily());
        CertifiedInsertionResult third = graph.insertNode(
                constant("and-c"), graph.coherentWitnessFamily());
        FlatLeaf a = new FlatLeaf(OnePort.invocation(
                context, first.returnedInvocation()));
        FlatLeaf b = new FlatLeaf(OnePort.invocation(
                context, second.returnedInvocation()));
        FlatLeaf c = new FlatLeaf(OnePort.invocation(
                context, third.returnedInvocation()));
        FlatApplication source = alternateGrouping
                ? new FlatApplication(
                        operator,
                        context,
                        List.of(new FlatApplication(operator, context, List.of(a, b)), c))
                : new FlatApplication(
                        operator,
                        context,
                        List.of(a, new FlatApplication(operator, context, List.of(b, c))));
        ConstructionSourceLedger.Builder ledger = ConstructionSourceLedger.builder(profile);
        ledger.recordFlat(source);
        CertifiedFlatConstruction construction = TypedENode.flatConstructCertified(
                source,
                node -> {
                    throw new AssertionError("same-head flat source unexpectedly sealed a node");
                },
                profile);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 16))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing flat-AND height-two unfolding"));
        Map<String, List<ContainerLawDeclaration>> laws = Map.of(
                operator.operator(), List.of(declaration));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                laws,
                List.of(construction.certificate()),
                List.of(),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    laws,
                    CertificateProvenance.capture(
                            alternateGrouping
                                    ? "fixture/flat-and-alt" : "fixture/flat-and",
                            (alternateGrouping
                                    ? "and[and[a, b], c]" : "and[a, and[b, c]]")
                                    .getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession certifiedContainerEqualsFixture() {
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        TypedSlotContext context = TypedSlotContext.empty();
        PortPath path = PortPath.at(0);
        BagPortSchema schema = new BagPortSchema(
                ArityPolicy.exact(2), new OnePortSchema(GraphType.BOOL));
        ContainerLawCertificate certificate = AlloyLawRegistry.issue(
                profile,
                Opcode.EQUALS,
                "ALLOY/EQUALS",
                GraphType.BOOL,
                path,
                schema,
                ContainerLawCertificate.Law.COMMUTATIVITY);
        ContainerLawDeclaration declaration = ContainerLawDeclaration.certified(
                schema, List.of(certificate));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "ALLOY/EQUALS",
                List.of(schema),
                GraphType.BOOL,
                Map.of(path, declaration),
                null).instantiateMonomorphic();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        CertifiedInsertionResult left = graph.insertNode(
                constant("equals-left"), graph.coherentWitnessFamily());
        CertifiedInsertionResult right = graph.insertNode(
                constant("equals-right"), graph.coherentWitnessFamily());
        List<PortValue> sourceOccurrences = List.of(
                OnePort.invocation(context, right.returnedInvocation()),
                OnePort.invocation(context, left.returnedInvocation()));
        ConstructionSourceLedger.Builder ledger = ConstructionSourceLedger.builder(profile);
        ledger.recordContainer(operator, path, context, sourceOccurrences);
        CertifiedContainerConstruction construction =
                TypedENode.constructContainerCertified(
                        operator, path, context, sourceOccurrences, profile);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 16))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing equality height-two unfolding"));
        Map<String, List<ContainerLawDeclaration>> laws = Map.of(
                operator.operator(), List.of(declaration));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                laws,
                List.of(),
                List.of(construction.certificate()),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    laws,
                    CertificateProvenance.capture(
                            "fixture/container-equals",
                            "equals[right, left]".getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession dependentJoinFixture() throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig S {}\n"
                        + "sig Parent {}\n"
                        + "sig Child extends Parent {}\n"
                        + "sig V {}\n"
                        + "sig T {}\n");
        PrimSig sSig = signature(module, "S");
        PrimSig parentSig = signature(module, "Parent");
        PrimSig childSig = signature(module, "Child");
        PrimSig vSig = signature(module, "V");
        PrimSig tSig = signature(module, "T");
        ExactAlloyType firstExact = ExactAlloyType.fromParser(
                sSig.type().product(parentSig.type()), module);
        ExactAlloyType secondExact = ExactAlloyType.fromParser(
                childSig.type().product(vSig.type()), module);
        ExactAlloyType thirdExact = ExactAlloyType.fromParser(
                Sig.UNIV.type().product(tSig.type()), module);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        GraphType firstType = AlloyTypeBridge.graphType(firstExact);
        GraphType secondType = AlloyTypeBridge.graphType(secondExact);
        GraphType thirdType = AlloyTypeBridge.graphType(thirdExact);
        TypedSlotContext context = TypedSlotContext.empty();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        TypedInvocation firstInvocation = graph.insertNode(
                relationConstant("join-first", firstType),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation secondInvocation = graph.insertNode(
                relationConstant("join-second", secondType),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation thirdInvocation = graph.insertNode(
                relationConstant("join-third", thirdType),
                graph.coherentWitnessFamily()).returnedInvocation();
        DependentChainLeaf first = new DependentChainLeaf(
                OnePort.invocation(context, firstInvocation),
                firstType,
                AlloyTypeBridge.dependentColumns(firstExact));
        DependentChainLeaf second = new DependentChainLeaf(
                OnePort.invocation(context, secondInvocation),
                secondType,
                AlloyTypeBridge.dependentColumns(secondExact));
        DependentChainLeaf third = new DependentChainLeaf(
                OnePort.invocation(context, thirdInvocation),
                thirdType,
                AlloyTypeBridge.dependentColumns(thirdExact));
        DependentChainApplication source = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainApplication(
                        DependentChainKind.JOIN, first, second),
                third);

        ConstructionSourceLedger.Builder ledger =
                ConstructionSourceLedger.builder(profile);
        StructuralKey sourceOccurrenceCommitment = StructuralKey.of(
                "alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/dependent-join/0"),
                List.of(
                        StructuralKey.branch(
                                "alloy-dependent-chain-typed-source-v1",
                                List.of(source.structuralKey())),
                        StructuralKey.leaf(
                                "alloy-dependent-chain-source-content-v1",
                                source.structuralKey().stableString())));
        ledger.recordDependentChain(source, sourceOccurrenceCommitment);
        CertifiedDependentChainConstruction construction =
                TypedENode.constructDependentChainCertified(
                        source, profile, sourceOccurrenceCommitment);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dependent JOIN unfolding"));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(construction.certificate()),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/dependent-join",
                            "(s.u).v".getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession dependentFamilyJoinFixture()
            throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig P {}\n"
                        + "sig A extends P {}\n"
                        + "sig B extends P {}\n"
                        + "sig C {}\n");
        ExactAlloyType leftExact = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "(A->A) + (B->B)").type(),
                module);
        ExactAlloyType rightExact = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(
                        module, "(A->C) + (B->C)").type(),
                module);
        DependentTypeDag leftDag = AlloyTypeBridge.dependentTypeDag(leftExact);
        DependentTypeDag rightDag = AlloyTypeBridge.dependentTypeDag(rightExact);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        TypedSlotContext context = TypedSlotContext.empty();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        TypedInvocation leftInvocation = graph.insertNode(
                relationConstant("family-join-left", leftDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation rightInvocation = graph.insertNode(
                relationConstant("family-join-right", rightDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        DependentChainApplication source = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainLeaf(
                        OnePort.invocation(context, leftInvocation), leftDag),
                new DependentChainLeaf(
                        OnePort.invocation(context, rightInvocation), rightDag));

        ConstructionSourceLedger.Builder ledger =
                ConstructionSourceLedger.builder(profile);
        StructuralKey sourceOccurrenceCommitment = StructuralKey.of(
                "alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/dependent-family-join/0"),
                List.of(
                        StructuralKey.branch(
                                "alloy-dependent-chain-typed-source-v1",
                                List.of(source.structuralKey())),
                        StructuralKey.leaf(
                                "alloy-dependent-chain-source-content-v1",
                                source.structuralKey().stableString())));
        ledger.recordDependentChain(source, sourceOccurrenceCommitment);
        CertifiedDependentChainConstruction construction =
                TypedENode.constructDependentChainCertified(
                        source, profile, sourceOccurrenceCommitment);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dependent family JOIN unfolding"));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(construction.certificate()),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/dependent-family-join",
                            "((A->A)+(B->B)).((A->C)+(B->C))"
                                    .getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession dependentEmptyJoinFixture()
            throws Exception {
        CompModule module = CompUtil.parseEverything_fromString(null,
                "sig A {}\nsig B {}\nsig C {}\nsig Owner { f: seq A }\n");
        ExactAlloyType leftExact = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "Owner.f").type(),
                module);
        ExactAlloyType rightExact = ExactAlloyType.fromParser(
                CompUtil.parseOneExpression_fromString(module, "B->C").type(),
                module);
        DependentTypeDag leftDag = AlloyTypeBridge.dependentTypeDag(leftExact);
        DependentTypeDag rightDag = AlloyTypeBridge.dependentTypeDag(rightExact);
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        TypedSlotContext context = TypedSlotContext.empty();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        TypedInvocation leftInvocation = graph.insertNode(
                relationConstant("empty-join-left", leftDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation rightInvocation = graph.insertNode(
                relationConstant("empty-join-right", rightDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        DependentChainApplication source = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainLeaf(
                        OnePort.invocation(context, leftInvocation), leftDag),
                new DependentChainLeaf(
                        OnePort.invocation(context, rightInvocation), rightDag));

        ConstructionSourceLedger.Builder ledger =
                ConstructionSourceLedger.builder(profile);
        StructuralKey sourceOccurrenceCommitment = StructuralKey.of(
                "alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/dependent-empty-join/0"),
                List.of(
                        StructuralKey.branch(
                                "alloy-dependent-chain-typed-source-v1",
                                List.of(source.structuralKey())),
                        StructuralKey.leaf(
                                "alloy-dependent-chain-source-content-v1",
                                source.structuralKey().stableString())));
        ledger.recordDependentChain(source, sourceOccurrenceCommitment);
        CertifiedDependentChainConstruction construction =
                TypedENode.constructDependentChainCertified(
                        source, profile, sourceOccurrenceCommitment);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dependent empty JOIN unfolding"));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(construction.certificate()),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/dependent-empty-join",
                            "Owner.f.(B->C)".getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static CertificateExportSession dependentEmptyInteriorJoinFixture() {
        GraphType a = GraphType.constructor("AlloySig:A");
        GraphType b = GraphType.constructor("AlloySig:B");
        GraphType c = GraphType.constructor("AlloySig:C");
        GraphType d = GraphType.constructor("AlloySig:D");
        DependentTypeDag leftDag = DependentTypeDag.exactRelation(
                GraphType.relation(a, b));
        DependentTypeDag emptyDag = DependentTypeDag.empty(2);
        DependentTypeDag rightDag = DependentTypeDag.exactRelation(
                GraphType.relation(c, d));
        SemanticProfile profile = SemanticProfile.alloyOverflowForbidding();
        TypedSlotContext context = TypedSlotContext.empty();

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(profile, sink);
        TypedInvocation leftInvocation = graph.insertNode(
                relationConstant("empty-interior-left", leftDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation emptyInvocation = graph.insertNode(
                relationConstant("empty-interior-middle", emptyDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        TypedInvocation rightInvocation = graph.insertNode(
                relationConstant("empty-interior-right", rightDag.relationType()),
                graph.coherentWitnessFamily()).returnedInvocation();
        DependentChainApplication first = new DependentChainApplication(
                DependentChainKind.JOIN,
                new DependentChainLeaf(
                        OnePort.invocation(context, leftInvocation), leftDag),
                new DependentChainLeaf(
                        OnePort.invocation(context, emptyInvocation), emptyDag));
        DependentChainApplication source = new DependentChainApplication(
                DependentChainKind.JOIN,
                first,
                new DependentChainLeaf(
                        OnePort.invocation(context, rightInvocation), rightDag));

        ConstructionSourceLedger.Builder ledger =
                ConstructionSourceLedger.builder(profile);
        StructuralKey sourceOccurrenceCommitment = StructuralKey.of(
                "alloy-dependent-chain-source-occurrence-v1",
                List.of("fixture/dependent-empty-interior-join/0"),
                List.of(
                        StructuralKey.branch(
                                "alloy-dependent-chain-typed-source-v1",
                                List.of(source.structuralKey())),
                        StructuralKey.leaf(
                                "alloy-dependent-chain-source-content-v1",
                                source.structuralKey().stableString())));
        ledger.recordDependentChain(source, sourceOccurrenceCommitment);
        CertifiedDependentChainConstruction construction =
                TypedENode.constructDependentChainCertified(
                        source, profile, sourceOccurrenceCommitment);
        CertifiedInsertionResult root = graph.insertNodeConstructed(
                construction, graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(2, 32))
                .enumerate(root.returnedInvocation()).stream()
                .filter(tree -> tree.height() == 2)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dependent empty-interior JOIN unfolding"));
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root.returnedInvocation(),
                graph.classes(),
                family,
                List.of(unfolding),
                Map.of(),
                List.of(),
                List.of(),
                List.of(construction.certificate()),
                ledger.build(),
                profile);
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfolding.normalizedTermKey(),
                    Map.of(),
                    CertificateProvenance.capture(
                            "fixture/dependent-empty-interior-join",
                            "(A->B).none.(C->D)".getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static PrimSig signature(CompModule module, String name) {
        for (Sig candidate : module.getAllReachableSigs()) {
            if (candidate instanceof PrimSig
                    && (candidate.label.equals(name)
                            || candidate.label.endsWith("/" + name))) {
                return (PrimSig) candidate;
            }
        }
        throw new AssertionError("missing parser signature " + name);
    }

    private static CertificateExportSession dualSymmetricBindBlockFixture() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(
                SemanticProfile.alloyOverflowForbidding(), sink);
        graph.insertNode(
                symmetricBindBlockNode("bind-block-dual-left", 0, null),
                graph.coherentWitnessFamily());
        CertifiedInsertionResult right = graph.insertNode(
                symmetricBindBlockNode("bind-block-dual-right", 0, null),
                graph.coherentWitnessFamily());
        CoherentWitnessFamily family = graph.coherentWitnessFamily();
        FiniteUnfoldingTree unfolding = graph.finiteUnfoldingOracle(
                family, new FiniteUnfoldingBounds(1, 32))
                .enumerate(right.returnedInvocation()).stream()
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "missing dual binder-block unfolding"));
        return session(
                sink,
                graph,
                right.returnedInvocation(),
                family,
                List.of(unfolding),
                Map.of(),
                "fixture/bind-block-dual",
                "dual symmetric binder occurrences");
    }

    private static void assertMalformedEventRejected(
            CertificateTraceEvent.Kind kind) {
        FreshFixture fixture = freshFixture(constant("event-" + kind.name()));
        CertificateTraceSnapshot snapshot = fixture.graph().certificateTraceSnapshot();
        try {
            new CertificateTraceEvent(
                    1,
                    kind,
                    snapshot,
                    snapshot,
                    new CertificateTracePayload.Insertion(fixture.insertion()));
            throw new AssertionError(
                    "mismatched " + kind + " event payload was accepted");
        } catch (IllegalArgumentException exception) {
            check(exception.getMessage().contains("cannot carry"),
                    "mismatched " + kind + " event is rejected by construction");
        }
    }

    private static CertificateExportSession session(
            RecordingCertificateTraceSink sink,
            TypedSlottedPortEGraph graph,
            TypedInvocation root,
            CoherentWitnessFamily family,
            List<? extends FiniteUnfoldingTree> unfoldings,
            Map<String, ? extends List<ContainerLawDeclaration>> containerLaws,
            String inputIdentifier,
            String inputContent) {
        CertifiedSemanticArtifact artifact = new CertifiedSemanticArtifact(
                root,
                graph.classes(),
                family,
                unfoldings,
                Map.of(),
                SemanticProfile.alloyOverflowForbidding());
        try {
            return new CertificateExportSession(
                    sink,
                    graph,
                    artifact,
                    unfoldings.get(0).normalizedTermKey(),
                    containerLaws,
                    CertificateProvenance.capture(
                            inputIdentifier,
                            inputContent.getBytes(StandardCharsets.UTF_8),
                            "certificate-writer-fixture-v3;"
                                    + CertificateTheoryManifest.VERSION),
                    "certificate-writer-fixture-v3");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static TypedENode constant(String name) {
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.emptyList(),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, TypedSlotContext.empty(), Collections.emptyList());
    }

    private static TypedENode orderedRelationConstant() {
        GraphType source = GraphType.constructor("AlloySig:S");
        GraphType target = GraphType.constructor("AlloySig:T");
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "ordered-relation",
                Collections.emptyList(),
                GraphType.relation(List.of(source, target)),
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, TypedSlotContext.empty(), Collections.emptyList());
    }

    private static TypedENode relationConstant(String name, GraphType type) {
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.emptyList(),
                type,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, TypedSlotContext.empty(), Collections.emptyList());
    }

    private static TypedENode slotLeaf(String name) {
        TypedSlot slot = TypedSlot.canonicalFree(T, 0);
        TypedSlotContext context = TypedSlotContext.singleton(slot);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                List.of(new OnePortSchema(T)),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, context, List.of(OnePort.slot(context, slot)));
    }

    private static TypedENode sourceAlphabetLeaf() {
        TypedSlot slot = TypedSlot.source(T, 0);
        TypedSlotContext context = TypedSlotContext.singleton(slot);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "source-alpha",
                List.of(new OnePortSchema(T)),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(
                operator, context, List.of(OnePort.slot(context, slot)));
    }

    private static TypedENode bindNode() {
        TypedSlotContext context = TypedSlotContext.empty();
        TypedSlot bound = TypedSlot.canonicalBound(T, 0);
        TypedSlotContext bodyContext = TypedSlotContext.singleton(bound);
        BindPortSchema schema = new BindPortSchema(T, new OnePortSchema(T));
        BindPort port = new BindPort(
                schema,
                context,
                bound,
                OnePort.slot(bodyContext, bound));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "bind",
                List.of(schema),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(operator, context, List.of(port));
    }

    private static TypedENode bindBlockNode() {
        TypedSlotContext context = TypedSlotContext.empty();
        TypedSlot bound = TypedSlot.canonicalBound(T, 0);
        BinderCoordinateDescriptor coordinate = new BinderCoordinateDescriptor(
                bound,
                StructuralKey.leaf("fixture-domain", "T"),
                "ALL",
                "SET",
                BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                TypedSlotContext.empty());
        BinderBlockDescriptor descriptor = BinderBlockDescriptor.certified(
                List.of(coordinate), List.of());
        BindBlockPortSchema schema = new BindBlockPortSchema(
                descriptor, new OnePortSchema(T));
        TypedRenaming occurrence = descriptor.freshOccurrenceRenaming(context);
        TypedSlot occurrenceSlot = occurrence.codomain().iterator().next();
        BindBlockPort port = new BindBlockPort(
                schema,
                context,
                occurrence,
                OnePort.slot(occurrence.codomain(), occurrenceSlot));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "bind-block",
                List.of(schema),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(operator, context, List.of(port));
    }

    private static TypedENode symmetricBindBlockNode() {
        return symmetricBindBlockNode("bind-block-symmetric", 0, null);
    }

    private static TypedENode nestedSameDescriptorBindBlockNode() {
        TypedSlotContext context = TypedSlotContext.empty();
        TypedSlot first = TypedSlot.canonicalBound(T, 0);
        TypedSlot second = TypedSlot.canonicalBound(T, 1);
        StructuralKey domain = StructuralKey.leaf("fixture-domain", "T");
        List<BinderCoordinateDescriptor> coordinates = List.of(
                new BinderCoordinateDescriptor(
                        first,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        context),
                new BinderCoordinateDescriptor(
                        second,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        context));
        TypedSlotContext descriptorContext = TypedSlotContext.of(first, second);
        TypedPermutation swap = TypedPermutation.of(
                descriptorContext, Map.of(first, second, second, first));
        BinderAutomorphismCertificate swapCertificate =
                new BinderAutomorphismCertificate(
                        coordinates,
                        swap,
                        CertificateOrigin.binderAutomorphism(
                                TheoryAlloyAdapter.SIGNATURE_VERSION,
                                "alloy-binder-block",
                                0));
        BinderBlockDescriptor descriptor = BinderBlockDescriptor.certified(
                coordinates, List.of(swapCertificate));

        BindBlockPortSchema innerSchema = new BindBlockPortSchema(
                descriptor, new OnePortSchema(T));
        BindBlockPortSchema outerSchema = new BindBlockPortSchema(
                descriptor, innerSchema);

        TypedRenaming outerOccurrence = descriptor.freshOccurrenceRenaming(context);
        TypedSlotContext outerBodyContext = context.union(
                outerOccurrence.codomain());
        TypedRenaming innerOccurrence = descriptor.freshOccurrenceRenaming(
                outerBodyContext);
        TypedSlotContext innerBodyContext = outerBodyContext.union(
                innerOccurrence.codomain());
        BindBlockPort inner = new BindBlockPort(
                innerSchema,
                outerBodyContext,
                innerOccurrence,
                OnePort.slot(
                        innerBodyContext,
                        innerOccurrence.apply(first)));
        BindBlockPort outer = new BindBlockPort(
                outerSchema,
                context,
                outerOccurrence,
                inner);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "bind-block-nested-same-descriptor",
                List.of(outerSchema),
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(operator, context, List.of(outer));
    }

    private static TypedENode symmetricBindBlockNode(
            String operatorName,
            int binderPortIndex,
            TypedInvocation filler) {
        TypedSlotContext context = TypedSlotContext.empty();
        TypedSlot first = TypedSlot.canonicalBound(T, 0);
        TypedSlot second = TypedSlot.canonicalBound(T, 1);
        StructuralKey domain = StructuralKey.leaf("fixture-domain", "T");
        List<BinderCoordinateDescriptor> coordinates = List.of(
                new BinderCoordinateDescriptor(
                        first,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        context),
                new BinderCoordinateDescriptor(
                        second,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        context));
        TypedSlotContext bound = TypedSlotContext.of(first, second);
        TypedPermutation swap = TypedPermutation.of(
                bound, Map.of(first, second, second, first));
        BinderAutomorphismCertificate swapCertificate =
                new BinderAutomorphismCertificate(
                        coordinates,
                        swap,
                        CertificateOrigin.binderAutomorphism(
                                TheoryAlloyAdapter.SIGNATURE_VERSION,
                                "alloy-binder-block",
                                0));
        BinderBlockDescriptor descriptor = BinderBlockDescriptor.certified(
                coordinates, List.of(swapCertificate));
        BindBlockPortSchema schema = new BindBlockPortSchema(
                descriptor, new OnePortSchema(T));
        TypedRenaming occurrence = descriptor.freshOccurrenceRenaming(context);
        BindBlockPort port = new BindBlockPort(
                schema,
                context,
                occurrence,
                OnePort.slot(occurrence.codomain(), occurrence.apply(first)));
        List<PortSchema> schemas = new java.util.ArrayList<>();
        List<PortValue> ports = new java.util.ArrayList<>();
        if (filler != null && binderPortIndex == 1) {
            schemas.add(new OnePortSchema(GraphType.BOOL));
            ports.add(OnePort.invocation(context, filler));
        }
        schemas.add(schema);
        ports.add(port);
        if (filler != null && binderPortIndex == 0) {
            schemas.add(new OnePortSchema(GraphType.BOOL));
            ports.add(OnePort.invocation(context, filler));
        }
        if (binderPortIndex < 0 || binderPortIndex > (filler == null ? 0 : 1)) {
            throw new IllegalArgumentException("invalid binder port index");
        }
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                operatorName,
                schemas,
                GraphType.BOOL,
                Map.of(),
                null).instantiateMonomorphic();
        return TypedENode.construct(operator, context, ports);
    }

    private static CertificateWriteMetrics exportTwice(
            CertificateExportSession session,
            Path output,
            String name) throws IOException {
        Path first = output.resolve(name + "-a.acgncert");
        Path second = output.resolve(name + "-b.acgncert");
        CertificateWriteMetrics firstMetrics = session.write(first);
        CertificateWriteMetrics secondMetrics = session.write(second);
        check(java.util.Arrays.equals(
                        Files.readAllBytes(first), Files.readAllBytes(second)),
                name + " exports are byte deterministic");
        check(firstMetrics.equals(secondMetrics),
                name + " export counters are deterministic");
        check(firstMetrics.certificateBytes() == Files.size(first)
                        && firstMetrics.inputSerializedBytes() > 0
                        && firstMetrics.kernelSerializedBytes() > 0
                        && firstMetrics.globalFreeRenamingCandidates() > 0
                        && firstMetrics.localQuotientWorkItems() >= 0
                        && firstMetrics.serializedCanonicalOrbitCandidates() > 0,
                name + " export counters describe the emitted bundle");
        return firstMetrics;
    }

    private static void exportOnce(
            CertificateExportSession session,
            Path output,
            String name) throws IOException {
        session.write(output.resolve(name + ".acgncert"));
    }

    private static void assertUnsupportedPreservesTarget(
            CertificateExportSession unsupported,
            Path target) throws IOException {
        assertUnsupportedPreservesTarget(unsupported, target, null);
    }

    private static void assertUnsupportedPreservesTarget(
            CertificateExportSession unsupported,
            Path target,
            String expectedReason) throws IOException {
        byte[] sentinel = "sentinel-certificate-output".getBytes(StandardCharsets.UTF_8);
        Files.write(target, sentinel);
        try {
            unsupported.write(target);
            throw new AssertionError("unsupported export unexpectedly succeeded");
        } catch (IOException exception) {
            check(exception.getMessage().startsWith("UNCHECKABLE: "),
                    "unsupported export is classified UNCHECKABLE");
            if (expectedReason != null) {
                check(exception.getMessage().contains(expectedReason),
                        "unsupported export reports its exact missing v8 evidence");
            }
        }
        check(java.util.Arrays.equals(sentinel, Files.readAllBytes(target)),
                "unsupported export leaves a pre-existing target unchanged");
        Files.delete(target);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private record FreshFixture(
            RecordingCertificateTraceSink sink,
            TypedSlottedPortEGraph graph,
            CertifiedInsertionResult insertion,
            CoherentWitnessFamily family,
            FiniteUnfoldingTree unfolding) {
    }
}
