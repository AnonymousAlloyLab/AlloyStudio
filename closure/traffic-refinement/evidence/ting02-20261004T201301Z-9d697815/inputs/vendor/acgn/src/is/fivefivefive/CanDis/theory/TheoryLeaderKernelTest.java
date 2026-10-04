package is.fivefivefive.CanDis.theory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic Phase DA leader-kernel and structural-provenance gate. */
public final class TheoryLeaderKernelTest {
    private static final long SEED = 555_202_608_20L;
    private static final GraphType USER = GraphType.constructor("User");
    private static int checks;

    private TheoryLeaderKernelTest() {
    }

    public static void main(String[] args) {
        testStrictSupportKernelAndTrace();
        testContainerNormalizationProvenance();
        testUnaryBinderScopePreservation();
        testBinderBlockScopePreservation();
        testEmptyKernelAndInputGuards();
        testGeneratedPathAndDeterminismProperties();
        System.out.println("TheoryLeaderKernelTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testStrictSupportKernelAndTrace() {
        TypedSlot x = TypedSlot.source(USER, 0);
        TypedSlot y = TypedSlot.source(USER, 1);
        TypedSlotContext ambient = TypedSlotContext.of(x, y);
        TypedSlotContext effective = TypedSlotContext.singleton(x);
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(400), GraphType.BOOL, effective);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(401), GraphType.BOOL, ambient);
        TypedSlottedPortEGraph graph = linkedGraph(
                leader,
                child,
                TypedEmbedding.inclusion(effective, ambient));

        InstantiatedOperator wrapper = operator(
                "phase-da-wrap",
                Collections.singletonList(new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL);
        TypedENode source = TypedENode.construct(
                wrapper,
                ambient,
                Collections.singletonList(OnePort.invocation(
                        ambient, TypedInvocation.identity(child))));

        expectThrows(IllegalStateException.class, () -> graph.extractLeaderKernel(source));
        graph.sealEmptyShapeFixtureForPhaseE();
        LeaderKernelResult result = graph.extractLeaderKernel(source);
        check(result.source().equals(source), "Phase DA retains the exact source node");
        check(result.ambientLeaderNode().context().equals(ambient),
                "Post-find syntax remains in the original ambient context");
        check(result.ambientLeaderNode().support().equals(effective),
                "Leader normalization computes the effective support");
        check(result.kernel().context().equals(effective)
                        && result.kernel().support().equals(effective),
                "The leader kernel is represented in its exact context");
        check(result.inclusion().equals(TypedEmbedding.inclusion(effective, ambient)),
                "The ambient transport is exactly the typed inclusion");
        check(!result.inclusion().isRenaming() && result.supportContracted(),
                "A proper inclusion is retained as an embedding, never a renaming");

        OnePort ambientPort = (OnePort) result.ambientLeaderNode().ports().get(0);
        TypedInvocation ambientInvocation = ((InvocationPortLeaf) ambientPort.leaf())
                .invocation();
        check(ambientInvocation.eclass().equals(leader)
                        && ambientInvocation.callerContext().equals(ambient),
                "The ambient result references the leader without unfolding it");
        OnePort kernelPort = (OnePort) result.kernel().ports().get(0);
        TypedInvocation kernelInvocation = ((InvocationPortLeaf) kernelPort.leaf())
                .invocation();
        check(kernelInvocation.eclass().equals(leader)
                        && kernelInvocation.callerContext().equals(effective),
                "Exact-context restriction narrows only the invocation codomain");
        check(kernelInvocation.embedding().isRenaming(),
                "The one-slot kernel invocation remains an exact typed map");

        LeaderKernelTrace trace = result.trace();
        check(trace.findResults().size() == 1
                        && trace.findResults().get(0).parentPath().steps().size() == 1,
                "xi retains the primitive proper-parent path");
        check(trace.containerNormalizations().isEmpty(),
                "An atomic invocation introduces no fictitious container step");
        check(result.kernel().act(result.inclusion()).equals(result.ambientLeaderNode()),
                "Applying iota reconstructs the ambient leader syntax in the nonbinding case");
        expectThrows(UnsupportedOperationException.class,
                () -> trace.findResults().clear());

        LeaderKernelResult repeated = graph.extractLeaderKernel(source);
        check(result.equals(repeated)
                        && result.structuralKey().equals(repeated.structuralKey()),
                "Repeated extraction survives path compression deterministically");
        CanonicalizationResult canonical = graph.canonicalize(source);
        check(canonical.kernel().equals(result.kernel())
                        && canonical.inclusion().equals(result.inclusion())
                        && canonical.trace().equals(result.trace()),
                "Phase E consumes the exact Phase DA kernel and provenance");
        check(!canonical.ambientTransport().isRenaming()
                        && canonical.ambientTransport().codomain().equals(ambient),
                "Phase E retains strict support contraction as a proper ambient embedding");
        check(LeaderKernelExtractor.VERSION.equals(graph.leaderKernelVersion()),
                "The graph exposes the Phase DA algorithm version");
    }

    private static void testContainerNormalizationProvenance() {
        TypedSlot x = TypedSlot.source(USER, 20);
        TypedSlotContext context = TypedSlotContext.singleton(x);
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(420), GraphType.BOOL, context);
        TypedEClassInterface first = new TypedEClassInterface(
                EClassId.of(421), GraphType.BOOL, context);
        TypedEClassInterface second = new TypedEClassInterface(
                EClassId.of(422), GraphType.BOOL, context);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(leader));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(first));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(second));
        graph.linkLeadersForPhaseD(new ParentStep(
                first,
                new TypedInvocation(leader, TypedRenaming.identity(context))));
        graph.linkLeadersForPhaseD(new ParentStep(
                second,
                new TypedInvocation(leader, TypedRenaming.identity(context))));
        graph.sealEmptyShapeFixtureForPhaseE();

        OnePortSchema one = new OnePortSchema(GraphType.BOOL);
        SeqPortSchema sequenceSchema = new SeqPortSchema(one);
        BagPortSchema bagSchema = new BagPortSchema(one);
        SetPortSchema setSchema = new SetPortSchema(one);
        InstantiatedOperator operator = operator(
                "phase-da-containers",
                Arrays.asList(sequenceSchema, bagSchema, setSchema),
                GraphType.BOOL);
        List<PortValue> twoChildren = Arrays.asList(
                OnePort.invocation(context, TypedInvocation.identity(first)),
                OnePort.invocation(context, TypedInvocation.identity(second)));
        TypedENode source = TypedENode.construct(
                operator,
                context,
                Arrays.asList(
                        new SeqPort(sequenceSchema, context, twoChildren),
                        new BagPort(bagSchema, context, twoChildren),
                        new SetPort(setSchema, context, twoChildren)));

        LeaderKernelResult result = graph.extractLeaderKernel(source);
        SeqPort sequence = (SeqPort) result.kernel().ports().get(0);
        BagPort bag = (BagPort) result.kernel().ports().get(1);
        SetPort set = (SetPort) result.kernel().ports().get(2);
        check(sequence.elements().size() == 2,
                "Sequence normalization preserves both positions");
        check(bag.occurrences().size() == 2
                        && bag.occurrences().get(0).equals(bag.occurrences().get(1)),
                "Bag normalization preserves both quotient-equal occurrences");
        check(set.elements().size() == 1,
                "Set normalization deduplicates post-find equal structural classes");
        check(result.trace().findResults().size() == 6,
                "xi retains one find path for every source occurrence token");

        List<ContainerNormalizationTrace> steps = result.trace()
                .containerNormalizations();
        check(steps.size() == 3,
                "xi records every declared Seq, Bag, and Set normalization");
        ContainerNormalizationTrace sequenceStep = steps.get(0);
        ContainerNormalizationTrace bagStep = steps.get(1);
        ContainerNormalizationTrace setStep = steps.get(2);
        check(sequenceStep.kind() == PortSchema.Kind.SEQ
                        && sequenceStep.outputFibers().equals(
                                Arrays.asList(
                                        Collections.singletonList(0),
                                        Collections.singletonList(1))),
                "Sequence provenance is pointwise and ordered");
        check(bagStep.kind() == PortSchema.Kind.BAG
                        && bagStep.inputOccurrences().size() == 2
                        && bagStep.outputOccurrences().size() == 2
                        && !bagStep.deduplicated(),
                "Bag provenance is a multiplicity-preserving occurrence bijection");
        check(setStep.kind() == PortSchema.Kind.SET
                        && setStep.outputFibers().equals(
                                Collections.singletonList(Arrays.asList(0, 1)))
                        && setStep.deduplicated(),
                "Set provenance retains the complete idempotence fiber");
        check(result.trace().portTraces().get(2).children().size() == 2,
                "Set deduplication does not erase child provenance");
        expectThrows(UnsupportedOperationException.class,
                () -> setStep.outputFibers().get(0).add(2));
    }

    private static void testUnaryBinderScopePreservation() {
        TypedSlot x = TypedSlot.source(USER, 40);
        TypedSlot y = TypedSlot.source(USER, 41);
        TypedSlot bound = TypedSlot.canonicalBound(USER, 20);
        TypedSlot leaderFree = TypedSlot.source(USER, 42);
        TypedSlot leaderBound = TypedSlot.source(USER, 43);
        TypedSlotContext freeContext = TypedSlotContext.of(x, y);
        TypedSlotContext bodyContext = freeContext.plus(bound);
        TypedSlotContext leaderContext = TypedSlotContext.of(leaderFree, leaderBound);
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(440), GraphType.BOOL, leaderContext);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(441), GraphType.BOOL, bodyContext);
        TypedEmbedding parentEmbedding = TypedEmbedding.of(
                leaderContext,
                bodyContext,
                mapOf(leaderFree, x, leaderBound, bound));
        TypedSlottedPortEGraph graph = linkedGraph(leader, child, parentEmbedding);
        graph.sealEmptyShapeFixtureForPhaseE();

        BindPortSchema binderSchema = new BindPortSchema(
                USER, new OnePortSchema(GraphType.BOOL));
        InstantiatedOperator operator = operator(
                "phase-da-bind",
                Collections.singletonList(binderSchema),
                GraphType.BOOL);
        BindPort binder = new BindPort(
                binderSchema,
                freeContext,
                bound,
                OnePort.invocation(bodyContext, TypedInvocation.identity(child)));
        TypedENode source = TypedENode.construct(
                operator, freeContext, Collections.singletonList(binder));

        LeaderKernelResult result = graph.extractLeaderKernel(source);
        check(result.effectiveSupport().equals(TypedSlotContext.singleton(x)),
                "A parent path may remove an unused free binder coordinate");
        BindPort kernelBinder = (BindPort) result.kernel().ports().get(0);
        check(kernelBinder.context().equals(TypedSlotContext.singleton(x))
                        && kernelBinder.boundSlot().equals(bound),
                "Exact-context restriction preserves the unary binder occurrence");
        check(kernelBinder.body().context().equals(TypedSlotContext.of(x, bound)),
                "The narrowed body retains its fresh bound coordinate");
        check(result.trace().portTraces().get(0).kind() == LeaderPortTrace.Kind.BIND
                        && result.trace().portTraces().get(0).children().get(0).kind()
                                == LeaderPortTrace.Kind.INVOCATION,
                "xi mirrors the binder and its opaque invocation body");

        TypedENode widened = result.kernel().act(result.inclusion());
        check(TypedAlphaEquivalence.structuralNodes(
                        widened,
                        result.ambientLeaderNode(),
                        TypedRenaming.identity(freeContext)),
                "Applying iota reconstructs binder syntax up to capture-safe alpha-renaming");
    }

    private static void testBinderBlockScopePreservation() {
        TypedSlot x = TypedSlot.source(USER, 60);
        TypedSlot y = TypedSlot.source(USER, 61);
        TypedSlot descriptorSlot = TypedSlot.canonicalBound(USER, 0);
        BinderCoordinateDescriptor coordinate = new BinderCoordinateDescriptor(
                descriptorSlot,
                StructuralKey.leaf("binder-domain", "User"),
                "ALL",
                "SET",
                BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                TypedSlotContext.empty());
        BinderBlockDescriptor descriptor = new BinderBlockDescriptor(
                Collections.singletonList(coordinate),
                Collections.emptyList());
        TypedSlotContext freeContext = TypedSlotContext.of(x, y);
        TypedRenaming occurrence = descriptor.freshOccurrenceRenaming(freeContext);
        TypedSlot bound = occurrence.codomain().slots().first();
        TypedSlotContext bodyContext = freeContext.union(occurrence.codomain());
        TypedSlot leaderFree = TypedSlot.source(USER, 62);
        TypedSlot leaderBound = TypedSlot.source(USER, 63);
        TypedSlotContext leaderContext = TypedSlotContext.of(leaderFree, leaderBound);
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(460), GraphType.BOOL, leaderContext);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(461), GraphType.BOOL, bodyContext);
        TypedSlottedPortEGraph graph = linkedGraph(
                leader,
                child,
                TypedEmbedding.of(
                        leaderContext,
                        bodyContext,
                        mapOf(leaderFree, x, leaderBound, bound)));
        graph.sealEmptyShapeFixtureForPhaseE();

        BindBlockPortSchema blockSchema = new BindBlockPortSchema(
                descriptor, new OnePortSchema(GraphType.BOOL));
        InstantiatedOperator operator = operator(
                "phase-da-bind-block",
                Collections.singletonList(blockSchema),
                GraphType.BOOL);
        BindBlockPort block = new BindBlockPort(
                blockSchema,
                freeContext,
                occurrence,
                OnePort.invocation(bodyContext, TypedInvocation.identity(child)));
        TypedENode source = TypedENode.construct(
                operator, freeContext, Collections.singletonList(block));

        LeaderKernelResult result = graph.extractLeaderKernel(source);
        BindBlockPort kernelBlock = (BindBlockPort) result.kernel().ports().get(0);
        check(result.effectiveSupport().equals(TypedSlotContext.singleton(x)),
                "Binder-block kernel support excludes the dropped free coordinate");
        check(kernelBlock.descriptorToOccurrence().equals(occurrence)
                        && kernelBlock.boundContext().equals(occurrence.codomain()),
                "Phase DA preserves the complete binder-block occurrence alignment");
        check(kernelBlock.body().context().equals(
                        TypedSlotContext.singleton(x).union(occurrence.codomain())),
                "Block context narrowing retains every descriptor-bound coordinate");
        check(result.trace().portTraces().get(0).kind()
                        == LeaderPortTrace.Kind.BIND_BLOCK,
                "xi represents BindBlock as a first-class trace constructor");
    }

    private static void testEmptyKernelAndInputGuards() {
        SeqPortSchema emptySchema = new SeqPortSchema(
                ContainerEmptiness.K_ZERO, new OnePortSchema(USER));
        Map<PortPath, ContainerLawDeclaration> unitLaw = new LinkedHashMap<>();
        unitLaw.put(
                PortPath.at(0),
                ContainerLawDeclaration.of(
                        ContainerLawDeclaration.Kind.SEQ,
                        true,
                        false,
                        false,
                        true));
        InstantiatedOperator emptyOperator = OperatorDeclaration.monomorphic(
                "phase-da-empty",
                Collections.singletonList(emptySchema),
                GraphType.BOOL,
                unitLaw,
                null).instantiateMonomorphic();
        TypedENode empty = TypedENode.construct(
                emptyOperator,
                TypedSlotContext.empty(),
                Collections.singletonList(new SeqPort(
                        emptySchema, TypedSlotContext.empty(), Collections.emptyList())));
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        LeaderKernelResult result = graph.extractLeaderKernel(empty);
        check(result.effectiveSupport().isEmpty()
                        && result.inclusion().isRenaming()
                        && !result.supportContracted(),
                "An empty exact kernel receives the identity empty inclusion");
        check(result.trace().containerNormalizations().get(0)
                        .outputFibers().isEmpty(),
                "K0 normalization has an explicit empty structural trace");

        TypedSlot x = TypedSlot.source(USER, 80);
        TypedSlot y = TypedSlot.source(USER, 81);
        TypedSlotContext nonExact = TypedSlotContext.of(x, y);
        InstantiatedOperator oneOperator = operator(
                "phase-da-nonexact",
                Collections.singletonList(new OnePortSchema(USER)),
                USER);
        TypedENode node = TypedENode.construct(
                oneOperator,
                nonExact,
                Collections.singletonList(OnePort.slot(nonExact, x)));
        expectThrows(IllegalArgumentException.class, () -> graph.extractLeaderKernel(node));

        TypedEClassInterface unknown = new TypedEClassInterface(
                EClassId.of(499), GraphType.BOOL, TypedSlotContext.empty());
        InstantiatedOperator unknownOperator = operator(
                "phase-da-unknown",
                Collections.singletonList(new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL);
        TypedENode unknownNode = TypedENode.construct(
                unknownOperator,
                TypedSlotContext.empty(),
                Collections.singletonList(OnePort.invocation(
                        TypedSlotContext.empty(), TypedInvocation.identity(unknown))));
        expectThrows(IllegalArgumentException.class,
                () -> graph.extractLeaderKernel(unknownNode));
    }

    private static void testGeneratedPathAndDeterminismProperties() {
        for (int round = 0; round < 32; round++) {
            List<TypedSlot> slots = new ArrayList<>();
            int width = 2 + round % 3;
            for (int index = 0; index < width; index++) {
                slots.add(TypedSlot.source(USER, 1_000 + round * 10 + index));
            }
            TypedSlotContext childContext = TypedSlotContext.of(slots);
            TypedSlotContext middleContext = TypedSlotContext.of(
                    slots.subList(0, width - 1));
            TypedSlotContext leaderContext = TypedSlotContext.singleton(slots.get(0));
            TypedEClassInterface child = new TypedEClassInterface(
                    EClassId.of(1_000 + round * 3L), GraphType.BOOL, childContext);
            TypedEClassInterface middle = new TypedEClassInterface(
                    EClassId.of(1_001 + round * 3L), GraphType.BOOL, middleContext);
            TypedEClassInterface leader = new TypedEClassInterface(
                    EClassId.of(1_002 + round * 3L), GraphType.BOOL, leaderContext);
            ParentStep firstStep = new ParentStep(
                    child,
                    new TypedInvocation(
                            middle, TypedEmbedding.inclusion(middleContext, childContext)));
            ParentStep secondStep = new ParentStep(
                    middle,
                    new TypedInvocation(
                            leader, TypedEmbedding.inclusion(leaderContext, middleContext)));
            TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
            graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
            graph.registerRecordForPhaseD(TypedEClassRecord.empty(middle));
            graph.registerRecordForPhaseD(TypedEClassRecord.empty(leader));
            graph.linkLeadersForPhaseD(firstStep);
            graph.linkLeadersForPhaseD(secondStep);
            graph.sealEmptyShapeFixtureForPhaseE();

            InstantiatedOperator operator = operator(
                    "phase-da-generated-" + round,
                    Collections.singletonList(new OnePortSchema(GraphType.BOOL)),
                    GraphType.BOOL);
            TypedENode source = TypedENode.construct(
                    operator,
                    childContext,
                    Collections.singletonList(OnePort.invocation(
                            childContext, TypedInvocation.identity(child))));
            LeaderKernelResult beforeCompression = graph.extractLeaderKernel(source);
            LeaderKernelResult afterCompression = graph.extractLeaderKernel(source);
            TypedFindResult find = beforeCompression.trace().findResults().get(0);
            check(find.parentPath().steps().equals(Arrays.asList(firstStep, secondStep)),
                    "Generated xi retains both primitive path steps");
            check(find.composedEmbedding().image().equals(leaderContext),
                    "Generated find composes to the leader image");
            check(beforeCompression.effectiveSupport().equals(leaderContext),
                    "Generated kernel uses exactly the post-find support");
            check(beforeCompression.inclusion().equals(
                            TypedEmbedding.inclusion(leaderContext, childContext)),
                    "Generated kernel retains the proper ambient inclusion");
            check(beforeCompression.kernel().act(beforeCompression.inclusion())
                            .equals(beforeCompression.ambientLeaderNode()),
                    "Generated nonbinding kernel reconstructs under inclusion");
            check(beforeCompression.equals(afterCompression),
                    "Generated path compression preserves the complete Phase DA result");
            graph.checkInvariants();
        }
    }

    private static TypedSlottedPortEGraph linkedGraph(
            TypedEClassInterface leader,
            TypedEClassInterface child,
            TypedEmbedding leaderIntoChild) {
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(leader));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
        graph.linkLeadersForPhaseD(new ParentStep(
                child, new TypedInvocation(leader, leaderIntoChild)));
        return graph;
    }

    private static InstantiatedOperator operator(
            String name,
            List<PortSchema> schemas,
            GraphType output) {
        Map<PortPath, ContainerLawDeclaration> laws = new LinkedHashMap<>();
        for (int index = 0; index < schemas.size(); index++) {
            collectLaws(schemas.get(index), PortPath.at(index), laws);
        }
        return OperatorDeclaration.monomorphic(
                name, schemas, output, laws, null).instantiateMonomorphic();
    }

    private static void collectLaws(
            PortSchema schema,
            PortPath path,
            Map<PortPath, ContainerLawDeclaration> laws) {
        PortSchema child = null;
        if (schema instanceof SeqPortSchema) {
            laws.put(path, ContainerLawDeclaration.of(
                    ContainerLawDeclaration.Kind.SEQ, false, false, false, false));
            child = ((SeqPortSchema) schema).elementSchema();
        } else if (schema instanceof BagPortSchema) {
            laws.put(path, ContainerLawDeclaration.of(
                    ContainerLawDeclaration.Kind.BAG, false, true, false, false));
            child = ((BagPortSchema) schema).elementSchema();
        } else if (schema instanceof SetPortSchema) {
            laws.put(path, ContainerLawDeclaration.of(
                    ContainerLawDeclaration.Kind.SET, false, true, true, false));
            child = ((SetPortSchema) schema).elementSchema();
        } else if (schema instanceof BindPortSchema) {
            child = ((BindPortSchema) schema).bodySchema();
        } else if (schema instanceof BindBlockPortSchema) {
            child = ((BindBlockPortSchema) schema).bodySchema();
        }
        if (child != null) {
            collectLaws(child, path.child(), laws);
        }
    }

    private static Map<TypedSlot, TypedSlot> mapOf(TypedSlot... entries) {
        if ((entries.length & 1) != 0) {
            throw new IllegalArgumentException("mapOf requires key/value pairs");
        }
        Map<TypedSlot, TypedSlot> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put(entries[index], entries[index + 1]);
        }
        return result;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expectThrows(
            Class<? extends Throwable> expected,
            Runnable action) {
        checks++;
        try {
            action.run();
        } catch (Throwable thrown) {
            if (expected.isInstance(thrown)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but saw " + thrown,
                    thrown);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
