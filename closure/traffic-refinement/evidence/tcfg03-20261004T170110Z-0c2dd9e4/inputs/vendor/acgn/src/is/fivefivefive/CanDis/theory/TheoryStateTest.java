package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Deterministic Phase D state, renamed-find, and path-compression tests. */
public final class TheoryStateTest {
    private static final long SEED = 555_202_608_18L;
    private static final GraphType USER = GraphType.constructor("User");
    private static final GraphType USER_REL = GraphType.relation(USER);
    private static final GraphType USER_PAIR = GraphType.relation(USER, USER);
    private static int checks;

    private TheoryStateTest() {
    }

    public static void main(String[] args) {
        testCanonicalShapesAndWitnesses();
        testTypedSymmetryCarrier();
        testLazyGroupClosureBoundary();
        testParentDirectionAndCompression();
        testGeneratedRenamedFindProperties();
        testGeneratedBranchingForests();
        testGraphOwnershipAndQuiescence();
        testMutationBoundary();
        System.out.println("TheoryStateTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testCanonicalShapesAndWitnesses() {
        ShapeFixture fixture = shapeFixture(EClassId.of(10));
        check(fixture.shape.outputType().equals(USER_PAIR),
                "Canonical shape retains its typed node output");
        check(fixture.shape.exactSlots().equals(fixture.canonicalContext),
                "Canonical shape retains its exact free-slot context");
        check(fixture.witness.exactSlots().equals(fixture.canonicalContext),
                "Shape witness records exact canonical slots");
        check(fixture.witness.ambientSupport().equals(fixture.ambientContext),
                "Shape witness records ambient support separately");
        check(fixture.witness.exposedInterface().equals(fixture.exposedContext),
                "Shape witness records the smaller exposed interface separately");
        check(fixture.record.shapeWitnesses().get(fixture.shape).equals(fixture.witness),
                "B_a maps a canonical shape to its exact witness");
        check(fixture.record.symmetryGroup().elements().size() == 1,
                "A fresh e-class starts with only identity symmetry");

        expectThrows(IllegalArgumentException.class, () -> new ShapeWitness(
                fixture.canonicalContext,
                fixture.ambientContext,
                TypedSlotContext.singleton(TypedSlot.source(USER, 99)),
                fixture.witness.instantiatingRenaming()));
        expectThrows(IllegalArgumentException.class, () -> new ShapeWitness(
                fixture.canonicalContext,
                fixture.ambientContext,
                fixture.exposedContext,
                TypedRenaming.identity(fixture.canonicalContext)));

        TypedEClassInterface wrongOutput = new TypedEClassInterface(
                EClassId.of(11), GraphType.BOOL, fixture.exposedContext);
        expectThrows(IllegalArgumentException.class, () -> TypedEClassRecord.of(
                wrongOutput,
                Collections.singletonMap(fixture.shape, fixture.witness),
                TypedSymmetryGroup.identity(fixture.exposedContext)));
        TypedEClassInterface wrongInterface = new TypedEClassInterface(
                EClassId.of(12), USER_PAIR,
                TypedSlotContext.singleton(TypedSlot.source(USER, 1)));
        expectThrows(IllegalArgumentException.class, () -> TypedEClassRecord.of(
                wrongInterface,
                Collections.singletonMap(fixture.shape, fixture.witness),
                TypedSymmetryGroup.identity(wrongInterface.exposedSlots())));

        TypedSlot source = TypedSlot.source(USER, 0);
        TypedSlotContext sourceContext = TypedSlotContext.singleton(source);
        OperatorDeclaration identity = OperatorDeclaration.monomorphic(
                "identity",
                Collections.singletonList(new OnePortSchema(USER)),
                USER,
                Collections.emptyMap(),
                null);
        TypedENode sourceNode = TypedENode.construct(
                identity.instantiateMonomorphic(),
                sourceContext,
                Collections.singletonList(OnePort.slot(sourceContext, source)));
        expectThrows(IllegalArgumentException.class, () -> CanonicalShape.of(sourceNode));

        TypedSlot skipped = TypedSlot.canonicalFree(USER, 1);
        TypedSlotContext skippedContext = TypedSlotContext.singleton(skipped);
        TypedENode skippedNode = TypedENode.construct(
                identity.instantiateMonomorphic(),
                skippedContext,
                Collections.singletonList(OnePort.slot(skippedContext, skipped)));
        expectThrows(IllegalArgumentException.class, () -> CanonicalShape.of(skippedNode));

        BindPortSchema bindSchema = new BindPortSchema(USER, new OnePortSchema(USER));
        OperatorDeclaration binderOperator = OperatorDeclaration.monomorphic(
                "binder",
                Collections.singletonList(bindSchema),
                USER,
                Collections.emptyMap(),
                null);
        TypedSlot canonicalBound = TypedSlot.canonicalBound(USER, 0);
        TypedSlotContext canonicalBodyContext = TypedSlotContext.singleton(canonicalBound);
        BindPort canonicalBinder = new BindPort(
                bindSchema,
                TypedSlotContext.empty(),
                canonicalBound,
                OnePort.slot(canonicalBodyContext, canonicalBound));
        CanonicalShape boundShape = CanonicalShape.of(TypedENode.construct(
                binderOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(canonicalBinder)));
        check(boundShape.exactSlots().isEmpty(),
                "Canonical binder removes its bound coordinate from free support");

        TypedSlot sourceBound = TypedSlot.source(USER, 50);
        TypedSlotContext sourceBodyContext = TypedSlotContext.singleton(sourceBound);
        BindPort nonCanonicalBinder = new BindPort(
                bindSchema,
                TypedSlotContext.empty(),
                sourceBound,
                OnePort.slot(sourceBodyContext, sourceBound));
        TypedENode nonCanonicalBoundNode = TypedENode.construct(
                binderOperator.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(nonCanonicalBinder));
        expectThrows(IllegalArgumentException.class,
                () -> CanonicalShape.of(nonCanonicalBoundNode));
    }

    private static void testTypedSymmetryCarrier() {
        TypedSlot x = TypedSlot.source(USER, 0);
        TypedSlot y = TypedSlot.source(USER, 1);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedPermutation swap = TypedPermutation.of(context, mapOf(x, y, y, x));
        TypedSymmetryGroup identity = TypedSymmetryGroup.identity(context);
        check(identity.elements().size() == 1,
                "Same-typed slots alone do not create a nontrivial symmetry");
        check(!identity.contains(swap),
                "Identity group rejects an uncertified same-type swap");

        TypedSymmetryGroup generated = TypedSymmetryGroup.generatedForPhaseD(
                context, Collections.singletonList(swap));
        check(generated.generators().equals(Collections.singletonList(swap)),
                "Typed symmetry group retains its generating set");
        check(generated.elements().size() == 2 && generated.contains(swap),
                "Typed transposition closes to the two-element subgroup");
        for (TypedPermutation left : generated.elements()) {
            check(generated.contains(left.inverse()),
                    "Typed symmetry closure contains every inverse");
            for (TypedPermutation right : generated.elements()) {
                check(generated.contains(left.andThen(right)),
                        "Typed symmetry closure is closed under composition");
            }
        }
        expectThrows(UnsupportedOperationException.class,
                () -> generated.elements().clear());

        TypedSlot z = TypedSlot.source(USER, 2);
        TypedSlotContext otherContext = TypedSlotContext.of(x, z);
        TypedPermutation otherSwap = TypedPermutation.of(
                otherContext, mapOf(x, z, z, x));
        expectThrows(IllegalArgumentException.class,
                () -> TypedSymmetryGroup.generatedForPhaseD(
                        context, Collections.singletonList(otherSwap)));
    }

    private static void testLazyGroupClosureBoundary() {
        TypedSlot x = TypedSlot.source(USER, 80);
        TypedSlot y = TypedSlot.source(USER, 81);
        TypedSlotContext context = TypedSlotContext.of(x, y);
        TypedPermutation swap = TypedPermutation.of(context, mapOf(x, y, y, x));
        String prior = System.getProperty("acgn.maxGroupElements");
        try {
            System.setProperty("acgn.maxGroupElements", "1");
            TypedSymmetryGroup group = TypedSymmetryGroup.generatedForPhaseD(
                    context, Collections.singletonList(swap));
            check(group.generators().size() == 1,
                    "group construction retains generators without traversing closure");
            check(group.contains(TypedPermutation.identity(context)),
                    "bounded membership returns immediately for the identity");
            expectThrows(CanonicalizationDomainException.class,
                    () -> group.contains(swap));
            expectThrows(CanonicalizationDomainException.class, group::elements);
        } finally {
            if (prior == null) {
                System.clearProperty("acgn.maxGroupElements");
            } else {
                System.setProperty("acgn.maxGroupElements", prior);
            }
        }
    }

    private static void testParentDirectionAndCompression() {
        TypedSlot ai0 = TypedSlot.source(USER, 0);
        TypedSlot ai1 = TypedSlot.source(USER, 1);
        TypedSlot ab = TypedSlot.source(GraphType.BOOL, 0);
        TypedSlot bi = TypedSlot.source(USER, 10);
        TypedSlot bb = TypedSlot.source(GraphType.BOOL, 10);
        TypedSlot li = TypedSlot.source(USER, 20);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(20), USER_REL, TypedSlotContext.of(ai0, ai1, ab));
        TypedEClassInterface middle = new TypedEClassInterface(
                EClassId.of(21), USER_REL, TypedSlotContext.of(bi, bb));
        TypedEClassInterface leader = new TypedEClassInterface(
                EClassId.of(22), USER_REL, TypedSlotContext.singleton(li));

        TypedEmbedding middleIntoChild = TypedEmbedding.of(
                middle.exposedSlots(),
                child.exposedSlots(),
                mapOf(bi, ai1, bb, ab));
        TypedEmbedding leaderIntoMiddle = TypedEmbedding.of(
                leader.exposedSlots(),
                middle.exposedSlots(),
                mapOf(li, bi));
        ParentStep childToMiddle = new ParentStep(
                child, new TypedInvocation(middle, middleIntoChild));
        ParentStep middleToLeader = new ParentStep(
                middle, new TypedInvocation(leader, leaderIntoMiddle));
        ParentPath expectedPath = ParentPath.direct(childToMiddle)
                .andThen(ParentPath.direct(middleToLeader));
        check(expectedPath.compositeEmbedding().apply(li).equals(ai1),
                "Parent path composes from leader interface into child interface");
        expectThrows(IllegalArgumentException.class, () -> ParentPath.direct(childToMiddle)
                .andThen(ParentPath.direct(childToMiddle)));
        expectThrows(IllegalArgumentException.class,
                () -> new ParentStep(child, TypedInvocation.identity(middle)));

        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(child));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(middle));
        graph.registerRecordForPhaseD(TypedEClassRecord.empty(leader));
        graph.linkLeadersForPhaseD(childToMiddle);
        graph.linkLeadersForPhaseD(middleToLeader);

        TypedSlot gi0 = TypedSlot.source(USER, 100);
        TypedSlot gi1 = TypedSlot.source(USER, 101);
        TypedSlot gb = TypedSlot.source(GraphType.BOOL, 100);
        TypedSlotContext caller = TypedSlotContext.of(gi0, gi1, gb);
        TypedEmbedding childIntoCaller = TypedEmbedding.of(
                child.exposedSlots(), caller, mapOf(ai0, gi0, ai1, gi1, ab, gb));
        TypedInvocation invocation = new TypedInvocation(child, childIntoCaller);

        TypedFindResult before = graph.findWithoutCompressionForTesting(invocation);
        TypedFindResult compressed = graph.findWithProvenance(invocation);
        check(before.equals(compressed),
                "Path compression preserves leader invocation and primitive provenance path");
        check(compressed.leaderInvocation().eclass().equals(leader),
                "Typed find returns the current leader");
        check(compressed.composedEmbedding().apply(li).equals(gi1),
                "Typed find composes caller and parent embeddings in formal direction");
        check(compressed.parentPath().steps().equals(
                        Arrays.asList(childToMiddle, middleToLeader)),
                "Find retains both original parent steps after compression");
        ParentAssignment childAssignment = graph.parentAssignments().get(child.id());
        check(childAssignment.parentInvocation().eclass().equals(leader),
                "Path compression installs a direct current edge to the leader");
        check(childAssignment.provenancePath().steps().size() == 2,
                "Compressed assignment does not erase its primitive path");
        check(graph.findWithProvenance(invocation).equals(compressed),
                "Repeated compressed find is idempotent");
        graph.checkInvariants();

        expectThrows(IllegalArgumentException.class,
                () -> graph.linkLeadersForPhaseD(childToMiddle));
        TypedEClassInterface spoof = new TypedEClassInterface(
                child.id(), GraphType.BOOL, TypedSlotContext.empty());
        expectThrows(IllegalArgumentException.class,
                () -> graph.findWithProvenance(TypedInvocation.identity(spoof)));
    }

    private static void testGeneratedRenamedFindProperties() {
        Random random = new Random(SEED);
        for (int round = 0; round < 96; round++) {
            int width = 1 + random.nextInt(4);
            int length = 2 + random.nextInt(5);
            List<TypedEClassInterface> interfaces = new ArrayList<>();
            TypedRenamedUnionFind unionFind = new TypedRenamedUnionFind();
            for (int depth = 0; depth < length; depth++) {
                List<TypedSlot> slots = new ArrayList<>();
                for (int column = 0; column < width; column++) {
                    slots.add(TypedSlot.source(
                            USER, round * 10_000L + depth * 100L + column));
                }
                TypedEClassInterface eclass = new TypedEClassInterface(
                        EClassId.of(1_000L + round * 10L + depth),
                        USER_REL,
                        TypedSlotContext.of(slots));
                interfaces.add(eclass);
                unionFind.register(eclass);
            }

            ParentPath expectedPath = ParentPath.identity(interfaces.get(0));
            for (int depth = 0; depth + 1 < interfaces.size(); depth++) {
                TypedEClassInterface child = interfaces.get(depth);
                TypedEClassInterface parent = interfaces.get(depth + 1);
                List<TypedSlot> targets = new ArrayList<>(child.exposedSlots().slots());
                Collections.shuffle(targets, random);
                Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
                int index = 0;
                for (TypedSlot slot : parent.exposedSlots()) {
                    mapping.put(slot, targets.get(index++));
                }
                ParentStep step = new ParentStep(
                        child,
                        new TypedInvocation(parent, TypedEmbedding.of(
                                parent.exposedSlots(), child.exposedSlots(), mapping)));
                unionFind.linkRoots(step);
                expectedPath = expectedPath.andThen(ParentPath.direct(step));
            }

            List<TypedSlot> callerSlots = new ArrayList<>();
            for (int column = 0; column < width; column++) {
                callerSlots.add(TypedSlot.source(
                        USER, 10_000_000L + round * 100L + column));
            }
            Collections.shuffle(callerSlots, random);
            Map<TypedSlot, TypedSlot> callerMap = new LinkedHashMap<>();
            int index = 0;
            for (TypedSlot slot : interfaces.get(0).exposedSlots()) {
                callerMap.put(slot, callerSlots.get(index++));
            }
            TypedEmbedding callerEmbedding = TypedEmbedding.of(
                    interfaces.get(0).exposedSlots(),
                    TypedSlotContext.of(callerSlots),
                    callerMap);
            TypedInvocation invocation = new TypedInvocation(
                    interfaces.get(0), callerEmbedding);
            TypedFindResult before = unionFind.findWithoutCompression(invocation);
            TypedFindResult after = unionFind.findWithProvenance(invocation);
            check(before.equals(after),
                    "Generated path compression preserves the complete find result");
            check(after.parentPath().equals(expectedPath),
                    "Generated find retains the expected ordered primitive path");
            check(after.composedEmbedding().equals(
                            expectedPath.compositeEmbedding().andThen(callerEmbedding)),
                    "Generated find computes caller o parent-path embedding");
            check(unionFind.assignment(interfaces.get(0).id())
                            .parentInvocation().eclass().equals(interfaces.get(length - 1)),
                    "Generated compression points directly to the leader");
            unionFind.checkInvariants();
            check(unionFind.findWithProvenance(invocation).equals(after),
                    "Generated path compression is idempotent");
        }
    }

    private static void testGeneratedBranchingForests() {
        Random random = new Random(SEED ^ 0x5deece66dL);
        for (int round = 0; round < 64; round++) {
            int size = 8 + random.nextInt(12);
            TypedRenamedUnionFind unionFind = new TypedRenamedUnionFind();
            List<TypedEClassInterface> interfaces = new ArrayList<>();
            int[] parent = new int[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
                TypedSlot slot = TypedSlot.source(
                        USER, 20_000_000L + round * 1_000L + index);
                TypedEClassInterface eclass = new TypedEClassInterface(
                        EClassId.of(10_000L + round * 100L + index),
                        USER_REL,
                        TypedSlotContext.singleton(slot));
                interfaces.add(eclass);
                unionFind.register(eclass);
            }
            for (int childIndex = 0; childIndex + 1 < size; childIndex++) {
                if (!random.nextBoolean()) {
                    continue;
                }
                int parentIndex = childIndex + 1 + random.nextInt(size - childIndex - 1);
                TypedEClassInterface child = interfaces.get(childIndex);
                TypedEClassInterface next = interfaces.get(parentIndex);
                TypedSlot childSlot = child.exposedSlots().slots().first();
                TypedSlot parentSlot = next.exposedSlots().slots().first();
                unionFind.linkRoots(new ParentStep(
                        child,
                        new TypedInvocation(next, TypedEmbedding.of(
                                next.exposedSlots(),
                                child.exposedSlots(),
                                mapOf(parentSlot, childSlot)))));
                parent[childIndex] = parentIndex;
                unionFind.checkInvariants();
            }

            for (int index = 0; index < size; index++) {
                int expectedLeader = index;
                int expectedLength = 0;
                while (parent[expectedLeader] != expectedLeader) {
                    expectedLeader = parent[expectedLeader];
                    expectedLength++;
                }
                TypedEClassInterface start = interfaces.get(index);
                TypedSlot startSlot = start.exposedSlots().slots().first();
                TypedSlot callerSlot = TypedSlot.source(
                        USER, 30_000_000L + round * 1_000L + index);
                TypedSlotContext caller = TypedSlotContext.singleton(callerSlot);
                TypedInvocation invocation = new TypedInvocation(
                        start,
                        TypedEmbedding.of(
                                start.exposedSlots(), caller, mapOf(startSlot, callerSlot)));
                TypedFindResult before = unionFind.findWithoutCompression(invocation);
                TypedFindResult after = unionFind.findWithProvenance(invocation);
                check(before.equals(after),
                        "Branching-forest compression preserves find and provenance");
                check(after.leaderInvocation().eclass().equals(interfaces.get(expectedLeader)),
                        "Branching-forest find reaches the expected leader");
                check(after.parentPath().steps().size() == expectedLength,
                        "Branching-forest provenance retains every historical edge");
                TypedSlot leaderSlot = interfaces.get(expectedLeader)
                        .exposedSlots().slots().first();
                check(after.composedEmbedding().apply(leaderSlot).equals(callerSlot),
                        "Branching-forest embedding reaches the original caller slot");
            }
            unionFind.checkInvariants();
            TypedEClassInterface first = interfaces.get(0);
            expectThrows(IllegalArgumentException.class, () -> unionFind.register(
                    new TypedEClassInterface(
                            first.id(), GraphType.BOOL, TypedSlotContext.empty())));
        }
    }

    private static void testGraphOwnershipAndQuiescence() {
        ShapeFixture fixture = shapeFixture(EClassId.of(300));
        TypedSlottedPortEGraph empty = TypedSlottedPortEGraph.structuralFixture();
        check(empty.hashOwner(fixture.shape) == null
                        && empty.hashBucketsSnapshot().isEmpty(),
                "A shape with no live owner has no ownership bucket");

        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.structuralFixture();
        graph.registerRecordForPhaseD(fixture.record);
        check(graph.status() == GraphStatus.QUIESCENT,
                "An isolated registered record preserves quiescence");
        check(graph.hashOwner(fixture.shape).equals(fixture.record.id()),
                "H maps a leader-owned canonical shape to its owner");
        check(graph.hashConsSnapshot().size() == 1,
                "Quiescent H contains every and only leader-owned shapes");
        expectThrows(UnsupportedOperationException.class, () -> graph.classes().clear());
        expectThrows(UnsupportedOperationException.class,
                () -> graph.parentAssignments().clear());

        TypedEClassInterface duplicateInterface = new TypedEClassInterface(
                EClassId.of(301), USER_PAIR, fixture.exposedContext);
        TypedEClassRecord duplicate = TypedEClassRecord.of(
                duplicateInterface,
                Collections.singletonMap(fixture.shape, fixture.witness),
                TypedSymmetryGroup.identity(fixture.exposedContext));
        graph.registerRecordForPhaseD(duplicate);
        check(graph.classes().size() == 2
                        && graph.hashBucketsSnapshot().get(fixture.shape).equals(
                                new java.util.TreeSet<>(Arrays.asList(
                                        fixture.record.id(), duplicate.id()))),
                "Equal-shape leaders coexist in one deterministic ownership bucket");
        check(!graph.hashBucketsSnapshot().get(fixture.shape).isEmpty()
                        && graph.hashBucketsSnapshot().get(fixture.shape).stream()
                                .allMatch(graph::isLeader),
                "Every stored ownership bucket is nonempty and contains live leaders");

        TypedEClassInterface parentInterface = new TypedEClassInterface(
                EClassId.of(302), USER_PAIR, TypedSlotContext.empty());
        TypedEClassRecord parentRecord = TypedEClassRecord.empty(parentInterface);
        graph.registerRecordForPhaseD(parentRecord);

        TypedSlottedPortEGraph reverseOrder = TypedSlottedPortEGraph.structuralFixture();
        reverseOrder.registerRecordForPhaseD(parentRecord);
        reverseOrder.registerRecordForPhaseD(duplicate);
        reverseOrder.registerRecordForPhaseD(fixture.record);
        check(graph.stateStructuralKey().equals(reverseOrder.stateStructuralKey()),
                "Quiescent state key is independent of record insertion order");

        ParentStep link = new ParentStep(
                fixture.record.interfaceView(),
                new TypedInvocation(
                        parentInterface,
                        TypedEmbedding.inclusion(
                                parentInterface.exposedSlots(), fixture.exposedContext)));
        graph.linkLeadersForPhaseD(link);
        check(graph.status() == GraphStatus.DIRTY,
                "A parent mutation explicitly dirties graph-wide hash-cons state");
        expectThrows(IllegalStateException.class, () -> graph.hashOwner(fixture.shape));
        expectThrows(IllegalStateException.class, graph::hashConsSnapshot);
        graph.checkInvariants();

        TypedEClassInterface missing = new TypedEClassInterface(
                EClassId.of(399), USER, TypedSlotContext.empty());
        OnePort invocationPort = OnePort.invocation(
                TypedSlotContext.empty(), TypedInvocation.identity(missing));
        OperatorDeclaration use = OperatorDeclaration.monomorphic(
                "use",
                Collections.singletonList(new OnePortSchema(USER)),
                USER,
                Collections.emptyMap(),
                null);
        CanonicalShape missingChildShape = CanonicalShape.of(TypedENode.construct(
                use.instantiateMonomorphic(),
                TypedSlotContext.empty(),
                Collections.singletonList(invocationPort)));
        ShapeWitness emptyWitness = new ShapeWitness(
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedRenaming.identity(TypedSlotContext.empty()));
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(398), USER, TypedSlotContext.empty());
        TypedEClassRecord missingChildRecord = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(missingChildShape, emptyWitness),
                TypedSymmetryGroup.identity(TypedSlotContext.empty()));
        TypedSlottedPortEGraph bottomUp = TypedSlottedPortEGraph.structuralFixture();
        expectThrows(IllegalArgumentException.class,
                () -> bottomUp.registerRecordForPhaseD(missingChildRecord));
        check(bottomUp.classes().isEmpty(),
                "Missing-child rejection leaves graph state unchanged");
    }

    private static void testMutationBoundary() {
        for (Field field : TypedSlottedPortEGraph.class.getDeclaredFields()) {
            check(Modifier.isPrivate(field.getModifiers()),
                    "Every graph state field is private to the graph mutation boundary");
        }
        for (Method method : TypedSlottedPortEGraph.class.getDeclaredMethods()) {
            if (method.getName().endsWith("ForPhaseD")) {
                check(!Modifier.isPublic(method.getModifiers()),
                        "Phase D setup/link primitives are not public mutation APIs");
            }
        }
        check(!Modifier.isPublic(TypedRenamedUnionFind.class.getModifiers()),
                "Raw renamed union-find cannot bypass its graph owner");
        try {
            Method generated = TypedSymmetryGroup.class.getDeclaredMethod(
                    "generatedForPhaseD", TypedSlotContext.class, List.class);
            check(!Modifier.isPublic(generated.getModifiers()),
                    "Uncertified nontrivial symmetry construction is not public");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static ShapeFixture shapeFixture(EClassId id) {
        TypedSlot f0 = TypedSlot.canonicalFree(USER, 0);
        TypedSlot f1 = TypedSlot.canonicalFree(USER, 1);
        TypedSlotContext canonical = TypedSlotContext.of(f0, f1);
        OperatorDeclaration pair = OperatorDeclaration.monomorphic(
                "pair",
                Arrays.asList(new OnePortSchema(USER), new OnePortSchema(USER)),
                USER_PAIR,
                Collections.emptyMap(),
                null);
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                pair.instantiateMonomorphic(),
                canonical,
                Arrays.asList(OnePort.slot(canonical, f0), OnePort.slot(canonical, f1))));

        TypedSlot s0 = TypedSlot.source(USER, 0);
        TypedSlot s1 = TypedSlot.source(USER, 1);
        TypedSlotContext ambient = TypedSlotContext.of(s0, s1);
        TypedSlotContext exposed = TypedSlotContext.singleton(s0);
        TypedRenaming witnessRenaming = TypedRenaming.of(
                canonical, ambient, mapOf(f0, s0, f1, s1));
        ShapeWitness witness = new ShapeWitness(
                canonical, ambient, exposed, witnessRenaming);
        TypedEClassInterface eclass = new TypedEClassInterface(id, USER_PAIR, exposed);
        TypedEClassRecord record = TypedEClassRecord.of(
                eclass,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(exposed));
        return new ShapeFixture(shape, witness, record, canonical, ambient, exposed);
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
            ThrowingRunnable operation) {
        checks++;
        try {
            operation.run();
        } catch (Throwable throwable) {
            if (expected.isInstance(throwable)) {
                return;
            }
            throw new AssertionError(
                    "Expected " + expected.getSimpleName() + " but got " + throwable,
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class ShapeFixture {
        private final CanonicalShape shape;
        private final ShapeWitness witness;
        private final TypedEClassRecord record;
        private final TypedSlotContext canonicalContext;
        private final TypedSlotContext ambientContext;
        private final TypedSlotContext exposedContext;

        private ShapeFixture(
                CanonicalShape shape,
                ShapeWitness witness,
                TypedEClassRecord record,
                TypedSlotContext canonicalContext,
                TypedSlotContext ambientContext,
                TypedSlotContext exposedContext) {
            this.shape = shape;
            this.witness = witness;
            this.record = record;
            this.canonicalContext = canonicalContext;
            this.ambientContext = ambientContext;
            this.exposedContext = exposedContext;
        }
    }
}
