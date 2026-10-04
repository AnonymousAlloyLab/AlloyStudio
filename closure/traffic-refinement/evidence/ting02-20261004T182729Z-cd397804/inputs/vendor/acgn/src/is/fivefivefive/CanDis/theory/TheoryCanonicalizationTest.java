package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic Phase E alpha, canon_G, and differential tests. */
public final class TheoryCanonicalizationTest {
    private static final long SEED = 555_202_608_19L;
    private static final GraphType USER = GraphType.constructor("User");
    private static int checks;

    private TheoryCanonicalizationTest() {
    }

    public static void main(String[] args) {
        testBoundedTypedRenamingEnumeration();
        testProductionCandidateWitnessTieAndRetention();
        testStructuralAlphaGroupoidAndBinders();
        testGraphRelativeRelationDistinctions();
        testContainerCanonicalizationAndGlobalSetRenaming();
        testLeaderNormalizationAndSymmetry();
        testBinderBlockQuotientFirst();
        testNestedBinderBlockQuotient();
        testNestedSameDescriptorOccurrences();
        testStabilizerTraversalDifferential();
        testGeneratedSymmetryDifferential();
        testGeneratedDifferentialCanonicalization();
        testDeterminismAndIdempotence();
        testBooleanBottomAndMetrics();
        testDirtyRejectionAndSupportContraction();
        testCanonicalizerBoundary();
        System.out.println("TheoryCanonicalizationTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testProductionCandidateWitnessTieAndRetention() {
        TypedSlot first = TypedSlot.canonicalFree(USER, 0);
        TypedSlot second = TypedSlot.canonicalFree(USER, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        SeqPortSchema schema = new SeqPortSchema(new OnePortSchema(USER));
        InstantiatedOperator operator = operator(
                "producer-witness-tie", List.of(schema), GraphType.BOOL);
        CanonicalShape shape = CanonicalShape.of(sequenceNode(
                operator, schema, context, List.of(first, second)));
        TypedRenaming identity = TypedRenaming.identity(context);
        TypedRenaming swap = TypedRenaming.of(
                context, context, mapOf(first, second, second, first));
        check(ProductionGraphCanonicalizer.compareCandidatePairForTesting(
                        shape, identity, shape, swap) < 0,
                "Production equal-shape candidates use the canonical witness tie-break");
        check(ProductionGraphCanonicalizer.compareCandidatePairForTesting(
                        shape, swap, shape, identity) > 0,
                "Production witness tie ordering is antisymmetric");

        Class<?> bestCandidate = Arrays.stream(
                        ProductionGraphCanonicalizer.class.getDeclaredClasses())
                .filter(type -> "BestCandidate".equals(type.getSimpleName()))
                .findFirst().orElseThrow();
        List<Field> retainedState = Arrays.stream(bestCandidate.getDeclaredFields())
                .filter(field -> !field.isSynthetic())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList();
        check(retainedState.size() == 1
                        && retainedState.get(0).getType() == LeastOption.class,
                "Production BestCandidate retains only one explicit minimum option");
        for (Field field : ProductionGraphCanonicalizer.class.getDeclaredFields()) {
            check(!java.util.Collection.class.isAssignableFrom(field.getType())
                            && !java.util.Map.class.isAssignableFrom(field.getType()),
                    "Production canonicalizer has no persistent candidate collection field");
        }
    }

    private static void testBoundedTypedRenamingEnumeration() {
        List<TypedSlot> sourceSlots = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            sourceSlots.add(TypedSlot.source(USER, index + 20));
        }
        TypedSlotContext source = TypedSlotContext.of(sourceSlots);
        TypedSlotContext canonical = source.canonicalFreeContext();
        long[] count = {0L};
        TypedRenamingEnumerator.forEach(canonical, source, renaming -> {
            check(renaming.isRenaming(), "Every enumerated map is a renaming");
            check(renaming.isTypePreserving(), "Every enumerated map preserves types");
            count[0]++;
        });
        check(count[0] == 5_040L, "Seven same-typed slots enumerate all 7! bijections");

        long[] impossible = {0L};
        TypedRenamingEnumerator.forEach(
                TypedSlotContext.singleton(TypedSlot.source(GraphType.INT, 0)),
                TypedSlotContext.singleton(TypedSlot.source(GraphType.BOOL, 0)),
                ignored -> impossible[0]++);
        check(impossible[0] == 0L, "Cross-type contexts have no typed renaming");

        String priorBound = System.getProperty("acgn.maxGlobalRenamings");
        try {
            System.setProperty("acgn.maxGlobalRenamings", "1");
            TypedSlot first = TypedSlot.source(USER, 100);
            TypedSlot second = TypedSlot.source(USER, 101);
            TypedSlotContext repeated = TypedSlotContext.of(first, second);
            expectThrows(
                    CanonicalizationDomainException.class,
                    () -> TypedRenamingEnumerator.forEach(
                            repeated.canonicalFreeContext(), repeated, ignored -> { }));
        } finally {
            if (priorBound == null) {
                System.clearProperty("acgn.maxGlobalRenamings");
            } else {
                System.setProperty("acgn.maxGlobalRenamings", priorBound);
            }
        }

        SeqPortSchema schema = new SeqPortSchema(new OnePortSchema(USER));
        InstantiatedOperator operator = operator(
                "seven", Collections.singletonList(schema), GraphType.BOOL);
        List<PortValue> elements = new ArrayList<>();
        for (TypedSlot slot : sourceSlots) {
            elements.add(OnePort.slot(source, slot));
        }
        TypedENode node = TypedENode.construct(
                operator,
                source,
                Collections.singletonList(new SeqPort(schema, source, elements)));
        CanonicalizationResult seven = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), node);
        check(seven.metrics().globalFreeRenamingCandidates() == 5_040L
                        && seven.metrics().localQuotientWorkItems() == 35_280L,
                "seven-slot metrics separate 7! global candidates from seven local leaves each");
    }

    private static void testStructuralAlphaGroupoidAndBinders() {
        TypedSlot x = TypedSlot.source(USER, 0);
        TypedSlot y = TypedSlot.source(USER, 1);
        TypedSlot a = TypedSlot.source(USER, 10);
        TypedSlot b = TypedSlot.source(USER, 11);
        TypedSlot u = TypedSlot.source(USER, 20);
        TypedSlot v = TypedSlot.source(USER, 21);
        TypedSlotContext first = TypedSlotContext.of(x, y);
        TypedSlotContext second = TypedSlotContext.of(a, b);
        TypedSlotContext third = TypedSlotContext.of(u, v);
        SeqPortSchema schema = new SeqPortSchema(new OnePortSchema(USER));
        InstantiatedOperator operator = operator(
                "ordered-pair", Collections.singletonList(schema), GraphType.BOOL);
        TypedENode n1 = sequenceNode(operator, schema, first, Arrays.asList(x, y));
        TypedRenaming rho1 = TypedRenaming.of(first, second, mapOf(x, b, y, a));
        TypedRenaming rho2 = TypedRenaming.of(second, third, mapOf(a, v, b, u));
        TypedENode n2 = n1.act(rho1);
        TypedENode n3 = n2.act(rho2);

        check(TypedAlphaEquivalence.structuralNodes(
                        n1, n1, TypedRenaming.identity(first)),
                "Structural alpha is reflexive");
        check(TypedAlphaEquivalence.structuralNodes(n1, n2, rho1),
                "Structural alpha applies one indexed free renaming");
        check(TypedAlphaEquivalence.structuralNodes(n2, n1, rho1.inverse()),
                "Structural alpha is symmetric with inverse index");
        check(TypedAlphaEquivalence.structuralNodes(n1, n3, rho1.andThen(rho2)),
                "Structural alpha composes its typed indices");

        TypedSlot free = TypedSlot.source(USER, 30);
        TypedSlot renamedFree = TypedSlot.source(USER, 31);
        TypedSlot bound = TypedSlot.source(USER, 90);
        TypedSlot renamedBound = TypedSlot.source(USER, 91);
        TypedSlotContext freeContext = TypedSlotContext.singleton(free);
        TypedSlotContext renamedContext = TypedSlotContext.singleton(renamedFree);
        SeqPortSchema bodySchema = new SeqPortSchema(new OnePortSchema(USER));
        BindPortSchema bindSchema = new BindPortSchema(USER, bodySchema);
        InstantiatedOperator binderOperator = operator(
                "binder", Collections.singletonList(bindSchema), GraphType.BOOL);
        BindPort leftBinder = binder(
                bindSchema, freeContext, free, bound, bodySchema);
        BindPort rightBinder = binder(
                bindSchema, renamedContext, renamedFree, renamedBound, bodySchema);
        TypedENode leftNode = TypedENode.construct(
                binderOperator, freeContext, Collections.singletonList(leftBinder));
        TypedENode rightNode = TypedENode.construct(
                binderOperator, renamedContext, Collections.singletonList(rightBinder));
        TypedRenaming freeRename = TypedRenaming.of(
                freeContext, renamedContext, mapOf(free, renamedFree));
        check(TypedAlphaEquivalence.structuralNodes(
                        leftNode, rightNode, freeRename),
                "Binder alpha accepts a fresh same-typed bound coordinate");
        CanonicalizationResult leftCanonical = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), leftNode);
        CanonicalizationResult rightCanonical = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), rightNode);
        check(leftCanonical.shape().equals(rightCanonical.shape()),
                "Alpha-renamed binders have one canonical shape");
        BindPort canonicalBinder = (BindPort) leftCanonical.shape().node().ports().get(0);
        check(canonicalBinder.boundSlot().equals(TypedSlot.canonicalBound(USER, 0)),
                "Canonical binders use the least fresh typed bound coordinate");

        InstantiatedOperator different = operator(
                "different-binder", Collections.singletonList(bindSchema), GraphType.BOOL);
        TypedENode differentNode = TypedENode.construct(
                different, renamedContext, Collections.singletonList(rightBinder));
        check(!TypedAlphaEquivalence.structuralNodes(
                        leftNode, differentNode, freeRename),
                "Different operator symbols are not structural alpha equivalents");
    }

    private static void testGraphRelativeRelationDistinctions() {
        TypedSlot x = TypedSlot.source(USER, 40);
        TypedSlot y = TypedSlot.source(USER, 41);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedEClassInterface parent = new TypedEClassInterface(
                EClassId.of(100), GraphType.BOOL, context);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(101), GraphType.BOOL, context);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(parent));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
        graph.linkLeadersForPhaseD(new ParentStep(
                child,
                new TypedInvocation(parent, TypedRenaming.identity(context))));
        graph.sealEmptyShapeFixtureForPhaseE();

        OnePortSchema oneBool = new OnePortSchema(GraphType.BOOL);
        InstantiatedOperator wrapper = operator(
                "wrapper", Collections.singletonList(oneBool), GraphType.BOOL);
        TypedENode childNode = TypedENode.construct(
                wrapper,
                context,
                Collections.singletonList(OnePort.invocation(
                        context, TypedInvocation.identity(child))));
        TypedENode parentNode = TypedENode.construct(
                wrapper,
                context,
                Collections.singletonList(OnePort.invocation(
                        context, TypedInvocation.identity(parent))));
        TypedRenaming identity = TypedRenaming.identity(context);
        check(!TypedAlphaEquivalence.structuralNodes(childNode, parentNode, identity),
                "Structural alpha keeps different e-class identifiers distinct");
        check(TypedAlphaEquivalence.graphRelativeNodes(graph, childNode, parentNode, identity),
                "Graph-relative alpha uses a shared leader");
        check(childNode.ports().get(0).equals(childNode.ports().get(0)),
                "Java equals remains structural and graph independent");

        TypedEClassInterface unsymmetric = new TypedEClassInterface(
                EClassId.of(102), GraphType.BOOL, context);
        TypedSlottedPortEGraph identityGraph = TypedSlottedPortEGraph.structuralFixture();
        identityGraph.registerRecordForPhaseD(TypedEClassRecord.empty(unsymmetric));
        TypedInvocation direct = TypedInvocation.identity(unsymmetric);
        TypedInvocation swapped = new TypedInvocation(
                unsymmetric,
                TypedPermutation.of(context, mapOf(x, y, y, x)));
        TypedENode directNode = TypedENode.construct(
                wrapper, context, Collections.singletonList(OnePort.invocation(context, direct)));
        TypedENode swappedNode = TypedENode.construct(
                wrapper, context, Collections.singletonList(OnePort.invocation(context, swapped)));
        check(!TypedAlphaEquivalence.graphRelativeNodes(
                        identityGraph, directNode, swappedNode, identity),
                "Same e-class membership alone does not equate different invocations");
    }

    private static void testContainerCanonicalizationAndGlobalSetRenaming() {
        TypedSlot x = TypedSlot.source(USER, 50);
        TypedSlot y = TypedSlot.source(USER, 51);
        TypedSlot a = TypedSlot.source(USER, 60);
        TypedSlot b = TypedSlot.source(USER, 61);
        TypedSlotContext source = TypedSlotContext.of(x, y);
        TypedSlotContext renamed = TypedSlotContext.of(a, b);
        OnePortSchema one = new OnePortSchema(USER);
        SeqPortSchema tuple = new SeqPortSchema(one);
        SetPortSchema setSchema = new SetPortSchema(tuple);
        InstantiatedOperator setOperator = operator(
                "set-of-pairs", Collections.singletonList(setSchema), GraphType.BOOL);

        SeqPort xy = sequence(tuple, source, x, y);
        SeqPort yx = sequence(tuple, source, y, x);
        SetPort pairSet = new SetPort(setSchema, source, Arrays.asList(xy, yx));
        TypedENode sourceNode = TypedENode.construct(
                setOperator, source, Collections.singletonList(pairSet));

        SeqPort ab = sequence(tuple, renamed, a, b);
        SeqPort ba = sequence(tuple, renamed, b, a);
        SetPort renamedSet = new SetPort(setSchema, renamed, Arrays.asList(ba, ab));
        TypedENode renamedNode = TypedENode.construct(
                setOperator, renamed, Collections.singletonList(renamedSet));

        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        CanonicalizationResult left = assertDifferential(graph, sourceNode);
        CanonicalizationResult right = assertDifferential(graph, renamedNode);
        check(left.shape().equals(right.shape()),
                "One global free renaming gives alpha-equivalent sets one shape");
        SetPort canonicalSet = (SetPort) left.shape().node().ports().get(0);
        check(canonicalSet.elements().size() == 2,
                "Set elements are not independently alpha-normalized into a false duplicate");

        BagPortSchema bagSchema = new BagPortSchema(one);
        InstantiatedOperator bagOperator = operator(
                "bag", Collections.singletonList(bagSchema), GraphType.BOOL);
        BagPort bag = new BagPort(
                bagSchema,
                source,
                Arrays.asList(
                        OnePort.slot(source, y),
                        OnePort.slot(source, x),
                        OnePort.slot(source, x)));
        CanonicalizationResult bagResult = assertDifferential(
                graph,
                TypedENode.construct(
                        bagOperator, source, Collections.singletonList(bag)));
        BagPort canonicalBag = (BagPort) bagResult.shape().node().ports().get(0);
        check(canonicalBag.occurrences().size() == 3,
                "Canonical bags retain multiplicity");

        SetPortSchema flatSchema = new SetPortSchema(one);
        Map<PortPath, ContainerLawDeclaration> flatLaws = new LinkedHashMap<>();
        flatLaws.put(PortPath.at(0), ContainerLawDeclaration.of(
                ContainerLawDeclaration.Kind.SET, true, true, true, false));
        InstantiatedOperator flatOperator = OperatorDeclaration.monomorphic(
                "flat-union",
                Collections.singletonList(flatSchema),
                USER,
                flatLaws,
                0).instantiateMonomorphic();
        FlatApplication flatSource = new FlatApplication(
                flatOperator,
                source,
                Arrays.asList(
                        new FlatLeaf(OnePort.slot(source, x)),
                        new FlatLeaf(OnePort.slot(source, y)),
                        new FlatLeaf(OnePort.slot(source, x))));
        TypedENode flatNode = TypedENode.flatConstruct(
                flatSource,
                ignored -> {
                    throw new AssertionError("A leaf-only flat source needs no sealer");
                });
        CanonicalizationResult flatResult = assertDifferential(graph, flatNode);
        check(flatResult.shape().node().operator().equals(flatOperator),
                "Canonical reconstruction retains the flat operator declaration");
        check(((SetPort) flatResult.shape().node().ports().get(0)).elements().size() == 2,
                "Canonical reconstruction retains already-flat ACI semantics");
    }

    private static void testLeaderNormalizationAndSymmetry() {
        TypedSlot x = TypedSlot.source(USER, 70);
        TypedSlot y = TypedSlot.source(USER, 71);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedPermutation swap = TypedPermutation.of(context, mapOf(x, y, y, x));
        TypedSymmetryGroup symmetry = TypedSymmetryGroup.generatedForPhaseD(
                context, Collections.singletonList(swap));
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(200), GraphType.BOOL, context);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(201), GraphType.BOOL, context);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.of(
                leader, Collections.emptyMap(), symmetry));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
        graph.linkLeadersForPhaseD(new ParentStep(
                child,
                new TypedInvocation(leader, TypedRenaming.identity(context))));
        graph.sealEmptyShapeFixtureForPhaseE();

        OnePortSchema oneBool = new OnePortSchema(GraphType.BOOL);
        SeqPortSchema sequence = new SeqPortSchema(oneBool);
        InstantiatedOperator operator = operator(
                "invocations", Collections.singletonList(sequence), GraphType.BOOL);
        TypedInvocation childIdentity = TypedInvocation.identity(child);
        TypedInvocation leaderSwapped = new TypedInvocation(leader, swap);
        SeqPort leftPort = new SeqPort(
                sequence,
                context,
                Arrays.asList(
                        OnePort.invocation(context, childIdentity),
                        OnePort.invocation(context, leaderSwapped)));
        SeqPort rightPort = new SeqPort(
                sequence,
                context,
                Arrays.asList(
                        OnePort.invocation(context, TypedInvocation.identity(leader)),
                        OnePort.invocation(context, TypedInvocation.identity(leader))));
        TypedENode left = TypedENode.construct(
                operator, context, Collections.singletonList(leftPort));
        TypedENode right = TypedENode.construct(
                operator, context, Collections.singletonList(rightPort));

        CanonicalizationResult leftResult = assertDifferential(graph, left);
        CanonicalizationResult rightResult = assertDifferential(graph, right);
        check(leftResult.shape().equals(rightResult.shape()),
                "Canonicalization combines leader normalization and recorded symmetry");
        check(leftResult.verifyWitness(graph),
                "Leader and symmetry witness replays graph-relatively");
        for (PortValue value : ((SeqPort) leftResult.shape().node().ports().get(0)).elements()) {
            TypedInvocation invocation = ((InvocationPortLeaf) ((OnePort) value).leaf()).invocation();
            check(invocation.eclass().equals(leader),
                    "Canonical invocation leaves reference only their leader");
        }

        SetPortSchema setSchema = new SetPortSchema(oneBool);
        InstantiatedOperator setOperator = operator(
                "invocation-set", Collections.singletonList(setSchema), GraphType.BOOL);
        SetPort invocationSet = new SetPort(
                setSchema,
                context,
                Arrays.asList(
                        OnePort.invocation(context, TypedInvocation.identity(leader)),
                        OnePort.invocation(context, leaderSwapped)));
        CanonicalizationResult setResult = assertDifferential(
                graph,
                TypedENode.construct(
                        setOperator, context, Collections.singletonList(invocationSet)));
        check(((SetPort) setResult.shape().node().ports().get(0)).elements().size() == 1,
                "Set removes graph-relative duplicate symmetry-orbit elements");

        BagPortSchema bagSchema = new BagPortSchema(oneBool);
        InstantiatedOperator bagOperator = operator(
                "invocation-bag", Collections.singletonList(bagSchema), GraphType.BOOL);
        BagPort invocationBag = new BagPort(
                bagSchema,
                context,
                Arrays.asList(
                        OnePort.invocation(context, TypedInvocation.identity(leader)),
                        OnePort.invocation(context, leaderSwapped)));
        CanonicalizationResult bagResult = assertDifferential(
                graph,
                TypedENode.construct(
                        bagOperator, context, Collections.singletonList(invocationBag)));
        check(((BagPort) bagResult.shape().node().ports().get(0)).occurrences().size() == 2,
                "Bag retains both occurrences after symmetry-orbit minimization");
    }

    private static void testBinderBlockQuotientFirst() {
        TypedSlot descriptorFirst = TypedSlot.canonicalBound(USER, 0);
        TypedSlot descriptorSecond = TypedSlot.canonicalBound(USER, 1);
        TypedSlotContext descriptorContext = TypedSlotContext.of(
                descriptorFirst, descriptorSecond);
        StructuralKey domain = StructuralKey.leaf("binder-domain", "User");
        BinderCoordinateDescriptor first = new BinderCoordinateDescriptor(
                descriptorFirst,
                domain,
                "ALL",
                "SET",
                0,
                TypedSlotContext.empty());
        BinderCoordinateDescriptor second = new BinderCoordinateDescriptor(
                descriptorSecond,
                domain,
                "ALL",
                "SET",
                0,
                TypedSlotContext.empty());
        TypedPermutation swap = TypedPermutation.of(
                descriptorContext,
                mapOf(
                        descriptorFirst, descriptorSecond,
                        descriptorSecond, descriptorFirst));
        BinderBlockDescriptor symmetric = new BinderBlockDescriptor(
                Arrays.asList(first, second), Collections.singletonList(swap));
        OnePortSchema oneUser = new OnePortSchema(USER);
        SeqPortSchema bodySchema = new SeqPortSchema(oneUser);
        BindBlockPortSchema blockSchema = new BindBlockPortSchema(
                symmetric, bodySchema);

        TypedSlot free = TypedSlot.source(USER, 600);
        TypedSlotContext context = TypedSlotContext.singleton(free);
        TypedRenaming occurrence = symmetric.freshOccurrenceRenaming(context);
        List<TypedSlot> bound = new ArrayList<>(occurrence.codomain().slots());
        TypedSlotContext bodyContext = context.union(occurrence.codomain());
        BindBlockPort direct = new BindBlockPort(
                blockSchema,
                context,
                occurrence,
                new SeqPort(
                        bodySchema,
                        bodyContext,
                        Arrays.asList(
                                OnePort.slot(bodyContext, free),
                                OnePort.slot(bodyContext, bound.get(0)),
                                OnePort.slot(bodyContext, bound.get(1)))));
        BindBlockPort permuted = new BindBlockPort(
                blockSchema,
                context,
                occurrence,
                new SeqPort(
                        bodySchema,
                        bodyContext,
                        Arrays.asList(
                                OnePort.slot(bodyContext, free),
                                OnePort.slot(bodyContext, bound.get(1)),
                                OnePort.slot(bodyContext, bound.get(0)))));

        SetPortSchema setSchema = new SetPortSchema(blockSchema);
        InstantiatedOperator setOperator = operator(
                "set-of-binder-blocks",
                Collections.singletonList(setSchema),
                GraphType.BOOL);
        TypedENode setNode = TypedENode.construct(
                setOperator,
                context,
                Collections.singletonList(new SetPort(
                        setSchema, context, Arrays.asList(direct, permuted))));
        check(((SetPort) setNode.ports().get(0)).elements().size() == 2,
                "Structurally distinct block occurrences enter the local quotient separately");
        CanonicalizationResult setResult = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), setNode);
        check(((SetPort) setResult.shape().node().ports().get(0)).elements().size() == 1,
                "Set deduplicates binder-block orbit mates after local quotienting");

        BagPortSchema bagSchema = new BagPortSchema(blockSchema);
        InstantiatedOperator bagOperator = operator(
                "bag-of-binder-blocks",
                Collections.singletonList(bagSchema),
                GraphType.BOOL);
        CanonicalizationResult bagResult = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(),
                TypedENode.construct(
                        bagOperator,
                        context,
                        Collections.singletonList(new BagPort(
                                bagSchema, context, Arrays.asList(direct, permuted)))));
        BagPort canonicalBag = (BagPort) bagResult.shape().node().ports().get(0);
        check(canonicalBag.occurrences().size() == 2
                        && canonicalBag.occurrences().get(0)
                                .equals(canonicalBag.occurrences().get(1)),
                "Bag retains both locally quotient-equal block occurrences");

        BinderBlockDescriptor identityOnly = new BinderBlockDescriptor(
                Arrays.asList(first, second), Collections.emptyList());
        BindBlockPortSchema identityBlockSchema = new BindBlockPortSchema(
                identityOnly, bodySchema);
        BindBlockPort identityDirect = new BindBlockPort(
                identityBlockSchema, context, occurrence, direct.body());
        BindBlockPort identityPermuted = new BindBlockPort(
                identityBlockSchema, context, occurrence, permuted.body());
        SetPortSchema identitySetSchema = new SetPortSchema(identityBlockSchema);
        InstantiatedOperator identitySetOperator = operator(
                "identity-set-of-binder-blocks",
                Collections.singletonList(identitySetSchema),
                GraphType.BOOL);
        CanonicalizationResult identityResult = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(),
                TypedENode.construct(
                        identitySetOperator,
                        context,
                        Collections.singletonList(new SetPort(
                                identitySetSchema,
                                context,
                                Arrays.asList(identityDirect, identityPermuted)))));
        check(((SetPort) identityResult.shape().node().ports().get(0))
                        .elements().size() == 2,
                "Identity-only Aut(beta) never guesses a same-typed block permutation");
    }

    private static void testGeneratedDifferentialCanonicalization() {
        Random random = new Random(SEED);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        for (int trial = 0; trial < 160; trial++) {
            int slotCount = 1 + random.nextInt(4);
            List<TypedSlot> slots = new ArrayList<>();
            for (int index = 0; index < slotCount; index++) {
                slots.add(TypedSlot.source(USER, 1_000 + trial * 8L + index));
            }
            TypedSlotContext context = TypedSlotContext.of(slots);
            List<PortValue> occurrences = new ArrayList<>();
            for (TypedSlot slot : slots) {
                occurrences.add(OnePort.slot(context, slot));
            }
            int extras = random.nextInt(4);
            for (int index = 0; index < extras; index++) {
                occurrences.add(OnePort.slot(
                        context, slots.get(random.nextInt(slots.size()))));
            }
            Collections.shuffle(occurrences, random);

            OnePortSchema element = new OnePortSchema(USER);
            int kind = random.nextInt(3);
            PortSchema schema;
            PortValue value;
            if (kind == 0) {
                schema = new SeqPortSchema(element);
                value = new SeqPort((SeqPortSchema) schema, context, occurrences);
            } else if (kind == 1) {
                schema = new BagPortSchema(element);
                value = new BagPort((BagPortSchema) schema, context, occurrences);
            } else {
                schema = new SetPortSchema(element);
                value = new SetPort((SetPortSchema) schema, context, occurrences);
            }
            InstantiatedOperator operator = operator(
                    "generated-" + kind,
                    Collections.singletonList(schema),
                    GraphType.BOOL);
            TypedENode node = TypedENode.construct(
                    operator, context, Collections.singletonList(value));
            CanonicalizationResult result = assertDifferential(graph, node);
            check(result.verifyWitness(graph), "Generated canonical witness replays");

            List<TypedSlot> targets = new ArrayList<>();
            for (int index = 0; index < slotCount; index++) {
                targets.add(TypedSlot.source(USER, 10_000 + trial * 8L + index));
            }
            Collections.shuffle(targets, random);
            TypedSlotContext targetContext = TypedSlotContext.of(targets);
            Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
            for (int index = 0; index < slots.size(); index++) {
                mapping.put(slots.get(index), targets.get(index));
            }
            TypedRenaming alpha = TypedRenaming.of(context, targetContext, mapping);
            TypedENode renamed = node.act(alpha);
            CanonicalizationResult renamedResult = assertDifferential(graph, renamed);
            check(result.shape().equals(renamedResult.shape()),
                    "Generated alpha variants share one canonical shape");
        }


        TypedSlot x = TypedSlot.source(USER, 50_000);
        TypedSlot y = TypedSlot.source(USER, 50_001);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        SeqPortSchema sequence = new SeqPortSchema(new OnePortSchema(USER));
        InstantiatedOperator pattern = operator(
                "pattern", Collections.singletonList(sequence), GraphType.BOOL);
        TypedENode first = sequenceNode(
                pattern, sequence, context, Arrays.asList(x, x, y));
        TypedENode second = sequenceNode(
                pattern, sequence, context, Arrays.asList(x, y, x));
        CanonicalizationResult firstResult = assertDifferential(graph, first);
        CanonicalizationResult secondResult = assertDifferential(graph, second);
        check(!firstResult.shape().equals(secondResult.shape()),
                "Non-alpha-equivalent occurrence patterns retain different shapes");
        check(!existsGraphRelativeRenaming(graph, first, second),
                "Different canonical patterns have no graph-relative typed renaming");
    }

    private static void testNestedBinderBlockQuotient() {
        StructuralKey domain = StructuralKey.leaf("binder-domain", "User");
        TypedSlot innerFirst = TypedSlot.canonicalBound(USER, 0);
        TypedSlot innerSecond = TypedSlot.canonicalBound(USER, 1);
        TypedSlotContext innerDescriptorContext = TypedSlotContext.of(
                innerFirst, innerSecond);
        BinderCoordinateDescriptor innerFirstCoordinate =
                new BinderCoordinateDescriptor(
                        innerFirst, domain, "ALL", "SET", 0,
                        TypedSlotContext.empty());
        BinderCoordinateDescriptor innerSecondCoordinate =
                new BinderCoordinateDescriptor(
                        innerSecond, domain, "ALL", "SET", 0,
                        TypedSlotContext.empty());
        BinderBlockDescriptor innerDescriptor = new BinderBlockDescriptor(
                Arrays.asList(innerFirstCoordinate, innerSecondCoordinate),
                Collections.singletonList(TypedPermutation.of(
                        innerDescriptorContext,
                        mapOf(
                                innerFirst, innerSecond,
                                innerSecond, innerFirst))));

        TypedSlot outerSlot = TypedSlot.canonicalBound(USER, 0);
        BinderBlockDescriptor outerDescriptor = new BinderBlockDescriptor(
                Collections.singletonList(new BinderCoordinateDescriptor(
                        outerSlot,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        TypedSlotContext.empty())),
                Collections.emptyList());
        SeqPortSchema innerBodySchema = new SeqPortSchema(new OnePortSchema(USER));
        BindBlockPortSchema innerBlockSchema = new BindBlockPortSchema(
                innerDescriptor, innerBodySchema);
        SetPortSchema outerBodySchema = new SetPortSchema(innerBlockSchema);
        BindBlockPortSchema outerBlockSchema = new BindBlockPortSchema(
                outerDescriptor, outerBodySchema);

        TypedSlot free = TypedSlot.source(USER, 650);
        TypedSlotContext freeContext = TypedSlotContext.singleton(free);
        TypedRenaming outerOccurrence = outerDescriptor.freshOccurrenceRenaming(
                freeContext);
        TypedSlot outerBound = outerOccurrence.codomain().slots().first();
        TypedSlotContext outerBodyContext = freeContext.union(
                outerOccurrence.codomain());
        TypedRenaming innerOccurrence = innerDescriptor.freshOccurrenceRenaming(
                outerBodyContext);
        List<TypedSlot> innerBound = new ArrayList<>(
                innerOccurrence.codomain().slots());
        TypedSlotContext innerBodyContext = outerBodyContext.union(
                innerOccurrence.codomain());
        BindBlockPort innerDirect = new BindBlockPort(
                innerBlockSchema,
                outerBodyContext,
                innerOccurrence,
                new SeqPort(
                        innerBodySchema,
                        innerBodyContext,
                        Arrays.asList(
                                OnePort.slot(innerBodyContext, free),
                                OnePort.slot(innerBodyContext, outerBound),
                                OnePort.slot(innerBodyContext, innerBound.get(0)),
                                OnePort.slot(innerBodyContext, innerBound.get(1)))));
        BindBlockPort innerSwapped = new BindBlockPort(
                innerBlockSchema,
                outerBodyContext,
                innerOccurrence,
                new SeqPort(
                        innerBodySchema,
                        innerBodyContext,
                        Arrays.asList(
                                OnePort.slot(innerBodyContext, free),
                                OnePort.slot(innerBodyContext, outerBound),
                                OnePort.slot(innerBodyContext, innerBound.get(1)),
                                OnePort.slot(innerBodyContext, innerBound.get(0)))));
        BindBlockPort outerBlock = new BindBlockPort(
                outerBlockSchema,
                freeContext,
                outerOccurrence,
                new SetPort(
                        outerBodySchema,
                        outerBodyContext,
                        Arrays.asList(innerDirect, innerSwapped)));
        InstantiatedOperator operator = operator(
                "nested-binder-blocks",
                Collections.singletonList(outerBlockSchema),
                GraphType.BOOL);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        CanonicalizationResult result = assertDifferential(
                graph,
                TypedENode.construct(
                        operator,
                        freeContext,
                        Collections.singletonList(outerBlock)));
        BindBlockPort canonicalOuter = (BindBlockPort) result.shape()
                .node().ports().get(0);
        SetPort canonicalBody = (SetPort) canonicalOuter.body();
        check(canonicalBody.elements().size() == 1,
                "Nested block orbits are minimized before their enclosing Set");
        check(result.verifyWitness(graph),
                "Nested block quotient retains a graph-relative typed witness");
    }

    private static void testGeneratedSymmetryDifferential() {
        TypedSlot x = TypedSlot.source(USER, 700);
        TypedSlot y = TypedSlot.source(USER, 701);
        TypedSlot z = TypedSlot.source(USER, 702);
        TypedSlotContext context = TypedSlotContext.of(x, y, z);
        TypedPermutation xy = TypedPermutation.of(
                context, mapOf(x, y, y, x, z, z));
        TypedPermutation yz = TypedPermutation.of(
                context, mapOf(x, x, y, z, z, y));
        TypedSymmetryGroup group = TypedSymmetryGroup.generatedForPhaseD(
                context, Arrays.asList(xy, yz));
        check(group.elements().size() == 6,
                "Two adjacent swaps generate the full typed S3 orbit");
        check(new java.util.LinkedHashSet<>(group.elements()).size() == 6,
                "Stabilizer traversal emits every S3 element exactly once");
        FinitePermutationTraversal.TraversalMetrics traversal =
                FinitePermutationTraversal.metrics(
                        context, group.generators(), TheoryKeys::embedding);
        check(traversal.groupOrder() == 6L,
                "Stabilizer-chain orbit order equals the emitted S3 cardinality");
        check(traversal.levelCount() <= context.size()
                        && traversal.maximumOrbitWidth() <= context.size(),
                "Stabilizer traversal retains only context-bounded orbit transversals");
        check(traversal.retainedTransversals() < traversal.groupOrder(),
                "S3 traversal does not retain one visited entry per group element");
        TypedEClassInterface eclass = new TypedEClassInterface(
                EClassId.of(250), GraphType.BOOL, context);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.of(
                eclass, Collections.emptyMap(), group));

        Random random = new Random(SEED ^ 0x5A5A5A5AL);
        OnePortSchema element = new OnePortSchema(GraphType.BOOL);
        List<TypedPermutation> permutations = group.elements();
        for (int trial = 0; trial < 18; trial++) {
            List<PortValue> values = new ArrayList<>();
            int occurrences = 1 + random.nextInt(3);
            for (int index = 0; index < occurrences; index++) {
                TypedPermutation permutation = permutations.get(
                        random.nextInt(permutations.size()));
                values.add(OnePort.invocation(
                        context, new TypedInvocation(eclass, permutation)));
            }

            int kind = trial % 3;
            PortSchema schema;
            PortValue port;
            if (kind == 0) {
                schema = new SeqPortSchema(element);
                port = new SeqPort((SeqPortSchema) schema, context, values);
            } else if (kind == 1) {
                schema = new BagPortSchema(element);
                port = new BagPort((BagPortSchema) schema, context, values);
            } else {
                schema = new SetPortSchema(element);
                port = new SetPort((SetPortSchema) schema, context, values);
            }
            InstantiatedOperator operator = operator(
                    "symmetry-generated-" + kind,
                    Collections.singletonList(schema),
                    GraphType.BOOL);
            CanonicalizationResult result = assertDifferential(
                    graph,
                    TypedENode.construct(
                            operator, context, Collections.singletonList(port)));
            check(result.verifyWitness(graph),
                    "Generated S3 symmetry witness replays");
        }
    }

    private static void testStabilizerTraversalDifferential() {
        List<TypedSlot> slots = List.of(
                TypedSlot.source(USER, 800),
                TypedSlot.source(USER, 801),
                TypedSlot.source(USER, 802),
                TypedSlot.source(USER, 803));
        TypedSlotContext context = TypedSlotContext.of(slots);
        List<TypedPermutation> universe = new ArrayList<>();
        TypedRenamingEnumerator.forEach(
                context, context, renaming -> universe.add(renaming.asPermutation()));
        check(universe.size() == 24,
                "Four same-typed slots expose the complete S4 reference universe");

        Random random = new Random(SEED ^ 0x6A6A6A6AL);
        for (int trial = 0; trial < 96; trial++) {
            List<TypedPermutation> generators = new ArrayList<>();
            int generatorCount = trial % 5;
            for (int index = 0; index < generatorCount; index++) {
                generators.add(universe.get(random.nextInt(universe.size())));
            }
            java.util.Set<StructuralKey> expected = referencePermutationClosure(
                    context, generators);
            java.util.Set<StructuralKey> observed = new java.util.TreeSet<>();
            long emitted = FinitePermutationTraversal.forEach(
                    context,
                    generators,
                    TheoryKeys::embedding,
                    permutation -> check(observed.add(TheoryKeys.embedding(permutation)),
                            "Stabilizer traversal must not duplicate a subgroup element"));
            check(emitted == observed.size(),
                    "Stabilizer traversal count equals its unique emitted action count");
            check(observed.equals(expected),
                    "Stabilizer traversal equals the bounded materialized subgroup oracle");
            java.util.Set<StructuralKey> searched = new java.util.TreeSet<>();
            check(!FinitePermutationTraversal.anyMatch(
                            context,
                            generators,
                            TheoryKeys::embedding,
                            permutation -> {
                                check(searched.add(TheoryKeys.embedding(permutation)),
                                        "Any-match traversal must visit each action at most once");
                                return false;
                            }),
                    "An all-false subgroup search reports no match");
            check(searched.equals(expected),
                    "Any-match traversal visits the same complete subgroup once");
            FinitePermutationTraversal.TraversalMetrics metrics =
                    FinitePermutationTraversal.metrics(
                            context, generators, TheoryKeys::embedding);
            check(metrics.groupOrder() == expected.size(),
                    "Stabilizer-chain order equals the reference subgroup cardinality");
            check(metrics.levelCount() <= context.size()
                            && metrics.maximumOrbitWidth() <= context.size(),
                    "Every retained stabilizer level is bounded by the slot context");
        }

        List<TypedSlot> sevenSlots = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            sevenSlots.add(TypedSlot.source(USER, 900 + index));
        }
        TypedSlotContext sevenContext = TypedSlotContext.of(sevenSlots);
        List<TypedPermutation> adjacentSwaps = new ArrayList<>();
        for (int index = 0; index + 1 < sevenSlots.size(); index++) {
            Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
            for (TypedSlot slot : sevenSlots) {
                mapping.put(slot, slot);
            }
            TypedSlot left = sevenSlots.get(index);
            TypedSlot right = sevenSlots.get(index + 1);
            mapping.put(left, right);
            mapping.put(right, left);
            adjacentSwaps.add(TypedPermutation.of(sevenContext, mapping));
        }
        FinitePermutationTraversal.TraversalMetrics sevenMetrics =
                FinitePermutationTraversal.metrics(
                        sevenContext, adjacentSwaps, TheoryKeys::embedding);
        check(sevenMetrics.groupOrder() == 5_040L,
                "Adjacent swaps generate the complete streamed S7 group");
        check(sevenMetrics.maximumOrbitWidth() == 7
                        && sevenMetrics.levelCount() == 6,
                "S7 stabilizer state is bounded by seven orbit images and six levels");
        check(sevenMetrics.retainedTransversals() < sevenMetrics.groupOrder(),
                "S7 traversal does not retain one visited object per orbit candidate");
        check(FinitePermutationTraversal.forEach(
                        sevenContext, adjacentSwaps, TheoryKeys::embedding,
                        ignored -> { }) == 5_040L,
                "S7 stabilizer traversal streams every element exactly once");
    }

    private static java.util.Set<StructuralKey> referencePermutationClosure(
            TypedSlotContext context,
            List<? extends TypedPermutation> generators) {
        List<TypedPermutation> steps = new ArrayList<>();
        for (TypedPermutation generator : generators) {
            steps.add(generator);
            steps.add(generator.inverse());
        }
        steps.sort(java.util.Comparator.comparing(TheoryKeys::embedding));
        java.util.Set<StructuralKey> seen = new java.util.TreeSet<>();
        java.util.Deque<TypedPermutation> pending = new java.util.ArrayDeque<>();
        TypedPermutation identity = TypedPermutation.identity(context);
        seen.add(TheoryKeys.embedding(identity));
        pending.add(identity);
        while (!pending.isEmpty()) {
            TypedPermutation current = pending.removeFirst();
            for (TypedPermutation step : steps) {
                TypedPermutation candidate = current.andThen(step);
                if (seen.add(TheoryKeys.embedding(candidate))) {
                    pending.addLast(candidate);
                }
            }
        }
        return seen;
    }

    private static void testNestedSameDescriptorOccurrences() {
        StructuralKey domain = StructuralKey.leaf("binder-domain", "User");
        TypedSlot coordinate = TypedSlot.canonicalBound(USER, 0);
        BinderBlockDescriptor descriptor = new BinderBlockDescriptor(
                List.of(new BinderCoordinateDescriptor(
                        coordinate,
                        domain,
                        "ALL",
                        "SET",
                        BinderCoordinateDescriptor.NO_DISJOINTNESS_CLASS,
                        TypedSlotContext.empty())),
                List.of());
        SeqPortSchema innerBodySchema = new SeqPortSchema(
                new OnePortSchema(USER));
        BindBlockPortSchema innerSchema = new BindBlockPortSchema(
                descriptor, innerBodySchema);
        BindBlockPortSchema outerSchema = new BindBlockPortSchema(
                descriptor, innerSchema);

        TypedSlot free = TypedSlot.source(USER, 690);
        TypedSlotContext freeContext = TypedSlotContext.singleton(free);
        TypedRenaming outerOccurrence = descriptor.freshOccurrenceRenaming(
                freeContext);
        TypedSlotContext outerBodyContext = freeContext.union(
                outerOccurrence.codomain());
        TypedRenaming innerOccurrence = descriptor.freshOccurrenceRenaming(
                outerBodyContext);
        check(!outerOccurrence.codomain().equals(innerOccurrence.codomain())
                        && outerOccurrence.codomain().union(
                                innerOccurrence.codomain()).size()
                                == outerOccurrence.codomain().size()
                                        + innerOccurrence.codomain().size(),
                "Nested uses of one descriptor receive distinct fresh contexts");
        TypedSlotContext innerBodyContext = outerBodyContext.union(
                innerOccurrence.codomain());
        BindBlockPort inner = new BindBlockPort(
                innerSchema,
                outerBodyContext,
                innerOccurrence,
                new SeqPort(
                        innerBodySchema,
                        innerBodyContext,
                        List.of(
                                OnePort.slot(innerBodyContext, free),
                                OnePort.slot(
                                        innerBodyContext,
                                        outerOccurrence.apply(coordinate)),
                                OnePort.slot(
                                        innerBodyContext,
                                        innerOccurrence.apply(coordinate)))));
        BindBlockPort outer = new BindBlockPort(
                outerSchema,
                freeContext,
                outerOccurrence,
                inner);
        InstantiatedOperator operator = operator(
                "nested-same-descriptor",
                List.of(outerSchema),
                GraphType.BOOL);
        TypedENode node = TypedENode.construct(
                operator, freeContext, List.of(outer));
        CanonicalizationResult result = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), node);
        BindBlockPort canonicalOuter = (BindBlockPort) result.shape()
                .node().ports().get(0);
        BindBlockPort canonicalInner = (BindBlockPort) canonicalOuter.body();
        check(!canonicalOuter.descriptorToOccurrence().codomain().equals(
                        canonicalInner.descriptorToOccurrence().codomain()),
                "Canonicalization preserves nested occurrence identity");
        check(canonicalOuter.schema().descriptor().equals(
                        canonicalInner.schema().descriptor()),
                "Nested occurrence identity does not alter the shared descriptor");

        TypedSlot renamedFree = TypedSlot.source(USER, 691);
        TypedRenaming alpha = TypedRenaming.of(
                freeContext,
                TypedSlotContext.singleton(renamedFree),
                Map.of(free, renamedFree));
        CanonicalizationResult renamed = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), node.act(alpha));
        check(result.shape().equals(renamed.shape()),
                "Nested same-descriptor occurrences survive alpha comparison");
    }

    private static void testBooleanBottomAndMetrics() {
        InstantiatedOperator bottom = operator(
                "ALLOY/FALSE", List.of(), GraphType.BOOL);
        TypedENode node = TypedENode.construct(
                bottom, TypedSlotContext.empty(), List.of());
        CanonicalizationResult result = assertDifferential(
                TypedSlottedPortEGraph.structuralFixture(), node);
        check(result.shape().node().equals(node),
                "Boolean bottom is a valid selected canonical shape");
        CanonicalizationMetrics metrics = result.metrics();
        check(metrics.inputSerializedBytes() > 0
                        && metrics.kernelSerializedBytes() > 0
                        && metrics.globalFreeRenamingCandidates() == 1
                        && metrics.localQuotientWorkItems() == 0,
                "Canonicalization exposes deterministic size and orbit counters");
        check(metrics.retainedTraceLength()
                        == metrics.retainedFindOccurrences()
                                + metrics.retainedParentSteps()
                                + metrics.retainedContainerNormalizations(),
                "Retained trace length is the exact counter decomposition");
    }

    private static void testDeterminismAndIdempotence() {
        TypedSlot x = TypedSlot.source(USER, 80);
        TypedSlot y = TypedSlot.source(USER, 81);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        BagPortSchema schema = new BagPortSchema(new OnePortSchema(USER));
        InstantiatedOperator operator = operator(
                "deterministic", Collections.singletonList(schema), GraphType.BOOL);
        TypedENode node = TypedENode.construct(
                operator,
                context,
                Collections.singletonList(new BagPort(
                        schema,
                        context,
                        Arrays.asList(
                                OnePort.slot(context, y),
                                OnePort.slot(context, x),
                                OnePort.slot(context, y)))));
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        CanonicalizationResult first = graph.canonicalize(node);
        CanonicalizationResult second = graph.canonicalize(node);
        check(first.equals(second), "Repeated production canonicalization is deterministic");

        CanonicalizationResult idempotent = graph.canonicalize(first.shape().node());
        check(first.shape().equals(idempotent.shape()),
                "Canonicalizing a canonical shape is shape-idempotent");
        check(idempotent.witness().equals(TypedRenaming.identity(first.shape().exactSlots())),
                "An already least shape receives the identity witness");
        check(ProductionGraphCanonicalizer.VERSION.equals(
                        ProductionGraphCanonicalizer.instance().version()),
                "Production canonicalizer exposes a stable version identifier");
        check(ExhaustiveGraphCanonicalizer.VERSION.equals(
                        ExhaustiveGraphCanonicalizer.instance().version()),
                "Reference canonicalizer exposes a stable version identifier");
    }

    private static void testDirtyRejectionAndSupportContraction() {
        TypedSlot x = TypedSlot.source(USER, 90);
        TypedSlot y = TypedSlot.source(USER, 91);
        TypedSlotContext twoSlots = TypedSlotContext.of(x, y);
        TypedSlotContext oneSlot = TypedSlotContext.singleton(x);
        TypedEClassInterface parent = new TypedEClassInterface(
                EClassId.of(300), GraphType.BOOL, oneSlot);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(301), GraphType.BOOL, twoSlots);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(parent));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
        graph.linkLeadersForPhaseD(new ParentStep(
                child,
                new TypedInvocation(
                        parent, TypedEmbedding.inclusion(oneSlot, twoSlots))));

        InstantiatedOperator wrapper = operator(
                "shrinking-wrapper",
                Collections.singletonList(new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL);
        TypedENode node = TypedENode.construct(
                wrapper,
                twoSlots,
                Collections.singletonList(OnePort.invocation(
                        twoSlots, TypedInvocation.identity(child))));
        expectThrows(IllegalStateException.class, () -> graph.canonicalize(node));
        graph.sealEmptyShapeFixtureForPhaseE();
        CanonicalizationResult result = assertDifferential(graph, node);
        TypedSlotContext canonicalEffective = oneSlot.canonicalFreeContext();
        check(result.source().equals(node)
                        && result.kernel().context().equals(oneSlot)
                        && result.effectiveSupport().equals(oneSlot),
                "Canonicalization retains the source and normalizes its exact effective kernel");
        check(result.shape().exactSlots().equals(canonicalEffective)
                        && result.witness().source().equals(canonicalEffective)
                        && result.witness().codomain().equals(oneSlot),
                "sigma is a typed bijection from Can(Delta) to the effective support");
        check(result.inclusion().equals(TypedEmbedding.inclusion(oneSlot, twoSlots))
                        && !result.inclusion().isRenaming(),
                "iota is retained as a proper typed inclusion");
        check(result.ambientTransport().equals(
                        result.witness().andThen(result.inclusion()))
                        && !result.ambientTransport().isRenaming(),
                "omega is exactly iota after sigma and remains a proper embedding");
        check(result.trace().equals(result.leaderKernel().trace())
                        && result.verifyWitness(graph),
                "The complete result retains xi and replays its effective witness");
        check(result.canonicalShape().equals(result.shape())
                        && result.sigma().equals(result.witness())
                        && result.iota().equals(result.inclusion())
                        && result.omega().equals(result.ambientTransport())
                        && result.xi().equals(result.trace()),
                "The complete result exposes all formal tuple components directly");

        TypedSlot z = TypedSlot.source(USER, 92);
        TypedSlotContext oneAmbientSlot = TypedSlotContext.singleton(z);
        TypedSlotContext empty = TypedSlotContext.empty();
        TypedEClassInterface emptyParent = new TypedEClassInterface(
                EClassId.of(302), GraphType.BOOL, empty);
        TypedEClassInterface oneSlotChild = new TypedEClassInterface(
                EClassId.of(303), GraphType.BOOL, oneAmbientSlot);
        TypedSlottedPortEGraph emptyKernelGraph = TypedSlottedPortEGraph.structuralFixture();
        emptyKernelGraph.registerRecordForPhaseD(TypedEClassRecord.empty(emptyParent));
        emptyKernelGraph.registerRecordForPhaseD(TypedEClassRecord.empty(oneSlotChild));
        emptyKernelGraph.linkLeadersForPhaseD(new ParentStep(
                oneSlotChild,
                new TypedInvocation(
                        emptyParent,
                        TypedEmbedding.inclusion(empty, oneAmbientSlot))));
        emptyKernelGraph.sealEmptyShapeFixtureForPhaseE();
        TypedENode emptyKernelSource = TypedENode.construct(
                wrapper,
                oneAmbientSlot,
                Collections.singletonList(OnePort.invocation(
                        oneAmbientSlot, TypedInvocation.identity(oneSlotChild))));
        CanonicalizationResult emptyKernelResult = assertDifferential(
                emptyKernelGraph, emptyKernelSource);
        check(emptyKernelResult.effectiveSupport().isEmpty()
                        && emptyKernelResult.shape().exactSlots().isEmpty()
                        && emptyKernelResult.sigma().equals(
                                TypedRenaming.identity(empty)),
                "Canonicalization admits contraction to the empty effective context");
        check(!emptyKernelResult.iota().isRenaming()
                        && emptyKernelResult.omega().equals(emptyKernelResult.iota()),
                "Empty sigma composes to the proper empty-to-ambient transport");
    }

    private static void testCanonicalizerBoundary() {
        check(TypedGraphCanonicalizer.class.isInterface(),
                "Canonicalizer integration boundary is an interface");
        for (Constructor<?> constructor : ProductionGraphCanonicalizer.class
                .getDeclaredConstructors()) {
            check(Modifier.isPrivate(constructor.getModifiers()),
                    "Production canonicalizer construction is controlled");
        }
        for (Constructor<?> constructor : ExhaustiveGraphCanonicalizer.class
                .getDeclaredConstructors()) {
            check(Modifier.isPrivate(constructor.getModifiers()),
                    "Reference canonicalizer construction is controlled");
        }
        check(!CanonicalizationResult.class.isAssignableFrom(CanonicalShape.class),
                "A canonical shape is distinct from its instantiating witness result");
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        check("typed-slotted-port-egraph".equals(graph.engineIdentifier()),
                "Exact engine exposes an unambiguous integration identifier");
        check(ProductionGraphCanonicalizer.VERSION.equals(graph.canonicalizerVersion()),
                "Exact engine exposes the production canonicalizer version");
    }

    private static CanonicalizationResult assertDifferential(
            TypedSlottedPortEGraph graph,
            TypedENode node) {
        CanonicalizationResult reference = ExhaustiveGraphCanonicalizer.instance()
                .canonicalize(graph, node);
        CanonicalizationResult production = ProductionGraphCanonicalizer.instance()
                .canonicalize(graph, node);
        check(reference.equals(production),
                "Reference and production canon_G return the same shape and witness");
        check(reference.metrics().equals(production.metrics()),
                "Reference and production count the same global and local work units");
        check(production.equals(graph.canonicalize(node)),
                "Graph canonicalization delegates to the production canon_G");
        return production;
    }

    private static boolean existsGraphRelativeRenaming(
            TypedSlottedPortEGraph graph,
            TypedENode left,
            TypedENode right) {
        boolean[] found = {false};
        TypedRenamingEnumerator.forEach(
                left.context(),
                right.context(),
                renaming -> {
                    if (!found[0] && TypedAlphaEquivalence.graphRelativeNodes(
                            graph, left, right, renaming)) {
                        found[0] = true;
                    }
                });
        return found[0];
    }

    private static TypedENode sequenceNode(
            InstantiatedOperator operator,
            SeqPortSchema schema,
            TypedSlotContext context,
            List<TypedSlot> slots) {
        List<PortValue> ports = new ArrayList<>();
        for (TypedSlot slot : slots) {
            ports.add(OnePort.slot(context, slot));
        }
        return TypedENode.construct(
                operator,
                context,
                Collections.singletonList(new SeqPort(schema, context, ports)));
    }

    private static SeqPort sequence(
            SeqPortSchema schema,
            TypedSlotContext context,
            TypedSlot... slots) {
        List<PortValue> elements = new ArrayList<>();
        for (TypedSlot slot : slots) {
            elements.add(OnePort.slot(context, slot));
        }
        return new SeqPort(schema, context, elements);
    }

    private static BindPort binder(
            BindPortSchema schema,
            TypedSlotContext context,
            TypedSlot free,
            TypedSlot bound,
            SeqPortSchema bodySchema) {
        TypedSlotContext bodyContext = context.plus(bound);
        SeqPort body = new SeqPort(
                bodySchema,
                bodyContext,
                Arrays.asList(
                        OnePort.slot(bodyContext, free),
                        OnePort.slot(bodyContext, bound)));
        return new BindPort(schema, context, bound, body);
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
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Slot mapping requires source/target pairs");
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
