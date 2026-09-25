package is.fivefivefive.CanDis.theory;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;

/** Deterministic Phase G rebuilding and mutation-trace gate. */
public final class TheoryRebuildTest {
    private static final long SEED = 555_202_608_22L;
    private static final GraphType USER = GraphType.constructor("User");
    private static int checks;

    private TheoryRebuildTest() {
    }

    public static void main(String[] args) {
        testFiniteRebuildProcessingBound();
        testCertifiedCollisionRebuild();
        testIncomparableCollisionBucket();
        testSameRevisionWitnessChangeInvalidatesCollisionMemo();
        testUnexpectedCollisionErrorDoesNotPoisonMemo();
        testCertifiedInterfaceRestriction();
        testRestrictionObligationIsNotInferred();
        testSymmetryStabilizerTransport();
        testUnionAbsorbsTypedShapesAndSymmetries();
        testFlexiblePortRebuild();
        testOpaqueChildrenAreNotUnfolded();
        testDirtyOrderIndependence();
        testGeneratedLegalMutationTraces();
        testIllegalMutationRejection();
        System.out.println("TheoryRebuildTest passed: " + checks
                + " checks; deterministic seed=" + SEED);
    }

    private static void testFiniteRebuildProcessingBound() {
        for (int records = 0; records <= 8; records++) {
            for (int dirty = 0; dirty <= records; dirty++) {
                for (int leaders = 0; leaders <= 8; leaders++) {
                    long expected = dirty + (long) records
                            * Math.max(0, leaders - 1);
                    check(TypedSlottedPortEGraph.rebuildProcessingBudget(
                                    dirty, records, leaders) == expected,
                            "rebuild budget follows the finite record/leader measure");
                }
            }
        }
        expectThrows(IllegalArgumentException.class,
                () -> TypedSlottedPortEGraph.rebuildProcessingBudget(2, 1, 1));
        expectThrows(IllegalArgumentException.class,
                () -> TypedSlottedPortEGraph.rebuildProcessingBudget(-1, 0, 0));
    }

    /** A changed occurrence witness invalidates a negative pair in the same rebuild. */
    private static void testSameRevisionWitnessChangeInvalidatesCollisionMemo() {
        TypedSlot left = TypedSlot.canonicalFree(GraphType.INT, 0);
        TypedSlot right = TypedSlot.canonicalFree(GraphType.INT, 1);
        TypedSlotContext ambient = TypedSlotContext.of(left, right);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "same-revision-collision-memo",
                Arrays.asList(
                        new OnePortSchema(GraphType.INT),
                        new OnePortSchema(GraphType.INT)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                operator,
                ambient,
                Arrays.asList(
                        OnePort.slot(ambient, left),
                        OnePort.slot(ambient, right))));
        TypedEClassInterface leftOwner = new TypedEClassInterface(
                EClassId.of(41000), GraphType.BOOL,
                TypedSlotContext.singleton(left));
        TypedEClassInterface rightOwner = new TypedEClassInterface(
                EClassId.of(41001), GraphType.BOOL,
                TypedSlotContext.singleton(right));
        ShapeWitness identityLeft = new ShapeWitness(
                ambient, ambient, leftOwner.exposedSlots(),
                TypedRenaming.of(ambient, ambient, mapOf(left, left, right, right)));
        ShapeWitness identityRight = new ShapeWitness(
                ambient, ambient, rightOwner.exposedSlots(),
                TypedRenaming.of(ambient, ambient, mapOf(left, left, right, right)));
        ShapeWitness swappedRight = new ShapeWitness(
                ambient, ambient, rightOwner.exposedSlots(),
                TypedRenaming.of(ambient, ambient, mapOf(left, right, right, left)));

        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        admitShape(graph, leftOwner, shape, identityLeft, "memo-left", 0);
        admitShape(graph, rightOwner, shape, identityRight, "memo-right", 1);
        check(invokeCollisionResolution(graph, shape) == 0,
                "Initially incomparable witnesses populate the negative collision memo");

        invokePrivate(
                graph,
                "removeStoredRecord",
                new Class<?>[] {ParentRecordKey.class},
                new ParentRecordKey(rightOwner.id(), shape));
        invokePrivate(
                graph,
                "installStoredRecord",
                new Class<?>[] {
                    EClassId.class,
                    CanonicalShape.class,
                    ShapeWitness.class,
                    TypedEqualityCertificate.class
                },
                rightOwner.id(),
                shape,
                swappedRight,
                shapeEquation(rightOwner, shape, swappedRight, "memo-right-swapped", 2));

        check(invokeCollisionResolution(graph, shape) == 1,
                "A same-revision occurrence-witness change invalidates the negative memo");
        graph.rebuild();
        check(graph.hashBucketsSnapshot().get(shape).size() == 1,
                "Fresh collision resolution prevents false quiescence");
        graph.checkInvariants();
    }

    /** Only a proved no-embedding result may populate the negative memo. */
    private static void testUnexpectedCollisionErrorDoesNotPoisonMemo() {
        TypedSlot left = TypedSlot.canonicalFree(GraphType.INT, 0);
        TypedSlot right = TypedSlot.canonicalFree(GraphType.INT, 1);
        TypedSlotContext ambient = TypedSlotContext.of(left, right);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "unexpected-collision-error",
                Arrays.asList(
                        new OnePortSchema(GraphType.INT),
                        new OnePortSchema(GraphType.INT)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                operator,
                ambient,
                Arrays.asList(
                        OnePort.slot(ambient, left),
                        OnePort.slot(ambient, right))));
        TypedEClassInterface leftOwner = new TypedEClassInterface(
                EClassId.of(41010), GraphType.BOOL,
                TypedSlotContext.singleton(left));
        TypedEClassInterface rightOwner = new TypedEClassInterface(
                EClassId.of(41011), GraphType.BOOL,
                TypedSlotContext.singleton(right));
        ShapeWitness leftWitness = new ShapeWitness(
                ambient,
                ambient,
                leftOwner.exposedSlots(),
                TypedRenaming.of(
                        ambient, ambient, mapOf(left, left, right, right)));
        ShapeWitness rightWitness = new ShapeWitness(
                ambient,
                ambient,
                rightOwner.exposedSlots(),
                TypedRenaming.of(
                        ambient, ambient, mapOf(left, left, right, right)));
        ShapeWitness swappedRight = new ShapeWitness(
                ambient,
                ambient,
                rightOwner.exposedSlots(),
                TypedRenaming.of(
                        ambient, ambient, mapOf(left, right, right, left)));

        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        admitShape(graph, leftOwner, shape, leftWitness, "error-left", 0);
        admitShape(graph, rightOwner, shape, rightWitness, "error-right", 1);
        ParentRecordKey leftKey = new ParentRecordKey(leftOwner.id(), shape);
        ParentRecordKey rightKey = new ParentRecordKey(rightOwner.id(), shape);
        invokePrivate(
                graph,
                "removeStoredRecord",
                new Class<?>[] {ParentRecordKey.class},
                rightKey);
        invokePrivate(
                graph,
                "installStoredRecord",
                new Class<?>[] {
                    EClassId.class,
                    CanonicalShape.class,
                    ShapeWitness.class,
                    TypedEqualityCertificate.class
                },
                rightOwner.id(),
                shape,
                swappedRight,
                shapeEquation(
                        rightOwner,
                        shape,
                        swappedRight,
                        "error-right-swapped",
                        2));
        InputEquationCertificate validLeft = shapeEquation(
                leftOwner, shape, leftWitness, "error-left-valid", 3);
        InputEquationCertificate wrongOwner = shapeEquation(
                rightOwner, shape, swappedRight, "error-wrong-owner", 4);
        NavigableMap<ParentRecordKey, TypedEqualityCertificate> certificates =
                privateMap(graph, "shapeCertificates");
        certificates.put(leftKey, wrongOwner);

        expectThrows(
                IllegalArgumentException.class,
                () -> invokeCollisionResolution(graph, shape));
        certificates.put(leftKey, validLeft);
        check(invokeCollisionResolution(graph, shape) == 1,
                "An unexpected certificate error propagates without poisoning the memo");
        graph.rebuild();
        graph.checkInvariants();
    }

    /** Theorem 1(6): historical invocations are transported, then rebuilt. */
    private static void testCertifiedInterfaceRestriction() {
        TypedSlot x = TypedSlot.source(USER, 10);
        TypedSlot y = TypedSlot.source(USER, 11);
        TypedSlot p = TypedSlot.source(USER, 20);
        TypedSlot q = TypedSlot.source(USER, 21);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(20), GraphType.BOOL, TypedSlotContext.of(x, y));
        TypedEClassInterface owner = emptyClass(21, GraphType.BOOL);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(child);

        CanonicalShape shape = contextualInvocationShape(
                "restricted-child", child);
        ShapeWitness witness = contextualWitness(
                shape.exactSlots(), TypedSlotContext.of(p, q), owner.exposedSlots());
        admitShape(graph, owner, shape, witness, "restricted-parent", 0);

        InterfaceRestrictionCertificate restriction = restriction(
                graph.eclass(child.id()), TypedSlotContext.singleton(x),
                "child-independent-y", 0);
        TypedInvocation historical = new TypedInvocation(
                child,
                TypedEmbedding.of(
                        child.exposedSlots(),
                        TypedSlotContext.of(p, q),
                        mapOf(x, p, y, q)));
        graph.restrictInterfaceCertified(restriction);
        check(graph.dirtyParentCount() == 1,
                "Restricting a child recursively dirties its stored parent");
        TypedFindResult normalized = graph.findWithProvenance(historical);
        check(normalized.normalizedInvocation().eclass().equals(
                        restriction.restrictedInterface())
                        && normalized.parentCertificate().leftEndpoint().equals(
                                TypedCertificateEndpoint.invocation(historical)),
                "Historical invocation metadata has a replayable restriction proof");

        long hashRebuildsBefore = graph.hashIndexRebuildCount();
        graph.rebuild();
        check(graph.hashIndexRebuildCount() == hashRebuildsBefore + 1,
                "Interface-changing rebuild performs one observable exact hash-index rebuild");
        TypedEClassRecord rebuiltOwner = graph.eclass(owner.id());
        check(rebuiltOwner.shapeWitnesses().size() == 1
                        && rebuiltOwner.shapeWitnesses().firstKey()
                                .exactSlots().size() == 1,
                "Certified child restriction contracts the rebuilt parent support");
        graph.checkInvariants();
    }

    /** A proper find embedding proposes, but never proves, owner restriction. */
    private static void testRestrictionObligationIsNotInferred() {
        TypedSlot x = TypedSlot.source(USER, 30);
        TypedSlot y = TypedSlot.source(USER, 31);
        TypedSlot p = TypedSlot.source(USER, 40);
        TypedSlot q = TypedSlot.source(USER, 41);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(30), GraphType.BOOL, TypedSlotContext.of(x, y));
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(31), GraphType.BOOL, TypedSlotContext.of(p, q));
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(child);
        CanonicalShape shape = contextualInvocationShape(
                "restriction-obligation", child);
        ShapeWitness witness = contextualWitness(
                shape.exactSlots(), owner.exposedSlots(), owner.exposedSlots());
        admitShape(graph, owner, shape, witness, "owner-with-two-slots", 0);

        graph.restrictInterfaceCertified(restriction(
                graph.eclass(child.id()), TypedSlotContext.singleton(x),
                "child-drop-y", 0));
        StructuralKey beforeRejectedRebuild = graph.stateStructuralKey();
        expectThrows(InterfaceRestrictionRequiredException.class, graph::rebuild);
        check(graph.status() == GraphStatus.DIRTY
                        && graph.dirtyParentCount() == 1
                        && beforeRejectedRebuild.equals(graph.stateStructuralKey()),
                "Rejected support contraction preserves the complete dirty state");

        graph.restrictInterfaceCertified(restriction(
                graph.eclass(owner.id()), TypedSlotContext.singleton(p),
                "owner-drop-q", 1));
        graph.rebuild();
        check(graph.eclass(owner.id()).exposedSlots().equals(
                        TypedSlotContext.singleton(p))
                        && graph.status() == GraphStatus.QUIESCENT,
                "Independent owner factorization discharges the rebuild obligation");
    }

    /** Restriction keeps exactly the certified subgroup stabilizing the new interface. */
    private static void testSymmetryStabilizerTransport() {
        TypedSlot x = TypedSlot.source(USER, 50);
        TypedSlot y = TypedSlot.source(USER, 51);
        TypedSlot z = TypedSlot.source(USER, 52);
        TypedSlotContext context = TypedSlotContext.of(x, y, z);
        TypedEClassInterface eclass = new TypedEClassInterface(
                EClassId.of(40), GraphType.BOOL, context);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(eclass);
        graph.addSymmetryCertified(eclass.id(), symmetry(
                eclass,
                TypedPermutation.of(context, mapOf(x, y, y, x, z, z)),
                "xy", 0));
        graph.addSymmetryCertified(eclass.id(), symmetry(
                eclass,
                TypedPermutation.of(context, mapOf(x, x, y, z, z, y)),
                "yz", 1));
        graph.rebuild();
        check(graph.eclass(eclass.id()).symmetryGroup().elements().size() == 6,
                "Two certified transpositions generate S3 before restriction");

        graph.restrictInterfaceCertified(restriction(
                graph.eclass(eclass.id()), TypedSlotContext.of(x, y),
                "drop-z", 0));
        check(graph.eclass(eclass.id()).symmetryGroup().elements().size() == 2,
                "Restriction transports the exact S2 setwise stabilizer");
        graph.rebuild();
        graph.checkInvariants();
    }

    /** Definition 5: union leaves no shapes on nonleaders and transports stabilizers. */
    private static void testUnionAbsorbsTypedShapesAndSymmetries() {
        TypedSlot cx = TypedSlot.source(USER, 60);
        TypedSlot cy = TypedSlot.source(USER, 61);
        TypedSlot px = TypedSlot.source(USER, 70);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(50), GraphType.BOOL, TypedSlotContext.of(cx, cy));
        TypedEClassInterface parent = new TypedEClassInterface(
                EClassId.of(51), GraphType.BOOL, TypedSlotContext.singleton(px));
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        CanonicalShape childShape = binarySlotShape("typed-shape");
        ShapeWitness childWitness = contextualWitness(
                childShape.exactSlots(), child.exposedSlots(), child.exposedSlots());
        admitShape(graph, child, childShape, childWitness, "typed-child-shape", 0);
        graph.registerEmptyClassForPhaseF(parent);
        TypedEmbedding parentInChild = TypedEmbedding.of(
                parent.exposedSlots(), child.exposedSlots(), mapOf(px, cy));
        graph.unionCertified(parentEquation(
                child,
                new TypedInvocation(parent, parentInChild),
                "typed-proper-union",
                0));
        check(graph.eclass(child.id()).shapeWitnesses().isEmpty()
                        && graph.eclass(parent.id()).shapeWitnesses().size() == 1,
                "Certified union atomically moves every child shape to its leader");
        ShapeWitness moved = graph.eclass(parent.id()).shapeWitnesses().get(childShape);
        check(moved.exposedInterface().equals(parent.exposedSlots())
                        && moved.ambientSupport().contains(px),
                "Moved shape witness makes parent interface coordinates literal");
        graph.rebuild();
        graph.checkInvariants();

        TypedSlot ax = TypedSlot.source(USER, 80);
        TypedSlot ay = TypedSlot.source(USER, 81);
        TypedSlot bx = TypedSlot.source(USER, 90);
        TypedSlot by = TypedSlot.source(USER, 91);
        TypedSlotContext childContext = TypedSlotContext.of(ax, ay);
        TypedSlotContext parentContext = TypedSlotContext.of(bx, by);
        TypedEClassInterface symmetricChild = new TypedEClassInterface(
                EClassId.of(52), GraphType.BOOL, childContext);
        TypedEClassInterface symmetricParent = new TypedEClassInterface(
                EClassId.of(53), GraphType.BOOL, parentContext);
        TypedSlottedPortEGraph symmetryGraph = new TypedSlottedPortEGraph();
        symmetryGraph.registerEmptyClassForPhaseF(symmetricChild);
        symmetryGraph.registerEmptyClassForPhaseF(symmetricParent);
        symmetryGraph.addSymmetryCertified(symmetricChild.id(), symmetry(
                symmetricChild,
                TypedPermutation.of(childContext, mapOf(ax, ay, ay, ax)),
                "union-child-swap",
                0));
        symmetryGraph.rebuild();
        TypedEmbedding onto = TypedEmbedding.of(
                parentContext, childContext, mapOf(bx, ax, by, ay));
        symmetryGraph.unionCertified(parentEquation(
                symmetricChild,
                new TypedInvocation(symmetricParent, onto),
                "union-symmetry",
                0));
        check(symmetryGraph.eclass(symmetricParent.id())
                        .symmetryGroup().elements().size() == 2,
                "Union transports the certified symmetry stabilizing the parent image");
        symmetryGraph.rebuild();
        symmetryGraph.checkInvariants();
    }

    /** Section 3.7: Seq retains order, Bag multiplicity, and Set idempotency. */
    private static void testFlexiblePortRebuild() {
        for (PortSchema.Kind kind : Arrays.asList(
                PortSchema.Kind.SEQ,
                PortSchema.Kind.BAG,
                PortSchema.Kind.SET)) {
            TypedEClassInterface left = emptyClass(60 + kind.ordinal() * 10, GraphType.BOOL);
            TypedEClassInterface right = emptyClass(61 + kind.ordinal() * 10, GraphType.BOOL);
            TypedEClassInterface owner = emptyClass(62 + kind.ordinal() * 10, GraphType.BOOL);
            TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.certifiedFixture();
            graph.registerEmptyClassForPhaseF(left);
            graph.registerEmptyClassForPhaseF(right);
            CanonicalShape shape = flatInvocationShape(
                    "phase-g-" + kind.name().toLowerCase(), kind, left, right);
            admitEmptyShape(graph, owner, shape, "container-" + kind.name());
            graph.unionCertified(parentEquation(
                    left, TypedInvocation.identity(right), "container-child", kind.ordinal()));
            graph.rebuild();

            CanonicalShape rebuilt = graph.eclass(owner.id()).shapeWitnesses().firstKey();
            PortValue port = rebuilt.node().ports().get(0);
            int size = port instanceof SeqPort
                    ? ((SeqPort) port).elements().size()
                    : port instanceof BagPort
                            ? ((BagPort) port).occurrences().size()
                            : ((SetPort) port).elements().size();
            check(size == (kind == PortSchema.Kind.SET ? 1 : 2),
                    kind + " rebuild obeys its declared duplicate policy");
            graph.checkInvariants();
        }
    }

    /** The flat contract stops at an opaque invocation during rebuilding. */
    private static void testOpaqueChildrenAreNotUnfolded() {
        TypedEClassInterface atom = emptyClass(500, GraphType.BOOL);
        TypedEClassInterface hidden = emptyClass(501, GraphType.BOOL);
        TypedEClassInterface replacement = emptyClass(502, GraphType.BOOL);
        TypedEClassInterface outer = emptyClass(503, GraphType.BOOL);
        TypedSlottedPortEGraph graph = TypedSlottedPortEGraph.certifiedFixture();
        graph.registerEmptyClassForPhaseF(atom);
        CanonicalShape hiddenShape = flatInvocationShape(
                "opaque-flat", PortSchema.Kind.SET, atom, atom);
        admitEmptyShape(graph, hidden, hiddenShape, "hidden-flat");
        graph.registerEmptyClassForPhaseF(replacement);
        CanonicalShape outerShape = flatSingleInvocationShape(
                hiddenShape.node().operator(), hidden);
        admitEmptyShape(graph, outer, outerShape, "outer-flat");

        graph.unionCertified(parentEquation(
                hidden,
                TypedInvocation.identity(replacement),
                "hide-same-head",
                0));
        graph.rebuild();
        CanonicalShape rebuiltOuter = graph.eclass(outer.id())
                .shapeWitnesses().firstKey();
        SetPort values = (SetPort) rebuiltOuter.node().ports().get(0);
        check(values.elements().size() == 1
                        && ((InvocationPortLeaf) ((OnePort) values.elements().get(0)).leaf())
                                .invocation().eclass().id().equals(replacement.id()),
                "Rebuild leaderizes but does not unfold a hidden same-headed class");
    }

    /** Proposition 3: canonical keys do not depend on dirty processing order. */
    private static void testDirtyOrderIndependence() {
        TypedSlottedPortEGraph forward = collisionFanInGraph();
        TypedSlottedPortEGraph reverse = collisionFanInGraph();
        RebuildReport forwardReport = forward.rebuild();
        RebuildReport reverseReport = reverse.rebuildReverseForTesting();
        check(forward.hashConsSnapshot().equals(reverse.hashConsSnapshot()),
                "Forward and reverse dirty orders produce identical canonical hash-conses");
        check(forwardReport.certifiedUnions() == reverseReport.certifiedUnions()
                        && forwardReport.processedRecords()
                                == reverseReport.processedRecords(),
                "Dirty-order variants perform the same finite administrative work");
        for (long id = 100; id <= 102; id++) {
            TypedInvocation left = TypedInvocation.identity(
                    forward.eclass(EClassId.of(id)).interfaceView());
            TypedInvocation right = TypedInvocation.identity(
                    reverse.eclass(EClassId.of(id)).interfaceView());
            check(forward.findWithProvenance(left).leaderInvocation().eclass().id()
                            .equals(reverse.findWithProvenance(right)
                                    .leaderInvocation().eclass().id()),
                    "Dirty-order variants choose the same deterministic owner leader");
        }
    }

    /** Gate G: random legal fixed batches check all invariants after every transition. */
    private static void testGeneratedLegalMutationTraces() {
        Random random = new Random(SEED);
        for (int round = 0; round < 48; round++) {
            int count = 2 + random.nextInt(5);
            long base = 1_000L + round * 100L;
            TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
            InstantiatedOperator wrap = fixedOperator(
                    "generated-wrap", new OnePortSchema(GraphType.BOOL), GraphType.BOOL);
            TypedEClassInterface[] leaves = new TypedEClassInterface[count];
            for (int index = 0; index < count; index++) {
                leaves[index] = emptyClass(base + index, GraphType.BOOL);
                graph.registerEmptyClassForPhaseF(leaves[index]);
            }
            for (int index = 0; index < count; index++) {
                admitEmptyShape(
                        graph,
                        emptyClass(base + 20 + index, GraphType.BOOL),
                        invocationShape(wrap, leaves[index]),
                        "generated-" + round + "-" + index);
                graph.checkInvariants();
            }

            java.util.List<Integer> order = new ArrayList<>();
            for (int index = 1; index < count; index++) {
                order.add(index);
            }
            Collections.shuffle(order, random);
            for (int index : order) {
                graph.unionCertified(parentEquation(
                        leaves[index],
                        TypedInvocation.identity(leaves[0]),
                        "generated-leaf-union-" + round,
                        index));
                graph.findWithProvenance(TypedInvocation.identity(leaves[index]));
                graph.checkInvariants();
            }
            RebuildReport report = graph.rebuild();
            check(graph.status() == GraphStatus.QUIESCENT
                            && graph.dirtyParentCount() == 0
                            && report.processedRecords() >= count - 1,
                    "Generated legal trace reaches finite rebuild quiescence");
            graph.checkInvariants();
        }

        for (int round = 0; round < 24; round++) {
            long base = 20_000L + round * 10L;
            TypedSlot x = TypedSlot.source(USER, base);
            TypedSlot y = TypedSlot.source(USER, base + 1);
            TypedSlot z = TypedSlot.source(USER, base + 2);
            TypedSlotContext context = TypedSlotContext.of(x, y, z);
            TypedEClassInterface eclass = new TypedEClassInterface(
                    EClassId.of(base), GraphType.BOOL, context);
            TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
            graph.registerEmptyClassForPhaseF(eclass);
            java.util.List<TypedPermutation> generators = new ArrayList<>(Arrays.asList(
                    TypedPermutation.of(context, mapOf(x, y, y, x, z, z)),
                    TypedPermutation.of(context, mapOf(x, x, y, z, z, y))));
            Collections.shuffle(generators, random);
            for (int index = 0; index < generators.size(); index++) {
                graph.addSymmetryCertified(eclass.id(), symmetry(
                        eclass,
                        generators.get(index),
                        "generated-symmetry-" + round,
                        index));
                graph.checkInvariants();
            }
            graph.rebuild();
            TypedInvocation historical = TypedInvocation.identity(eclass);
            graph.restrictInterfaceCertified(restriction(
                    graph.eclass(eclass.id()),
                    TypedSlotContext.of(x, y),
                    "generated-restriction-" + round,
                    0));
            TypedFindResult normalized = graph.findWithProvenance(historical);
            check(normalized.normalizedInvocation().eclass().exposedSlots()
                            .equals(TypedSlotContext.of(x, y)),
                    "Generated restriction trace normalizes historical invocations");
            graph.rebuild();
            graph.checkInvariants();
        }
    }

    private static void testIllegalMutationRejection() {
        TypedEClassInterface child = emptyClass(9_000, GraphType.BOOL);
        TypedEClassInterface owner = emptyClass(9_001, GraphType.BOOL);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(child);
        CanonicalShape shape = invocationShape(fixedOperator(
                "illegal-wrap", new OnePortSchema(GraphType.BOOL), GraphType.BOOL), child);
        ShapeWitness witness = new ShapeWitness(
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedRenaming.identity(TypedSlotContext.empty()));
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(TypedSlotContext.empty()));
        expectThrows(IllegalArgumentException.class,
                () -> graph.admitFixedBatchRecordCertified(record, Collections.emptyMap()));
        check(!graph.classes().containsKey(owner.id()),
                "Rejected admission cannot contaminate graph state");
        graph.checkInvariants();
    }

    /** Section 3.7: child union dirties parents and collision rebuild is certified. */
    private static void testCertifiedCollisionRebuild() {
        TypedEClassInterface left = emptyClass(1, GraphType.BOOL);
        TypedEClassInterface right = emptyClass(2, GraphType.BOOL);
        TypedEClassInterface leftOwner = emptyClass(10, GraphType.BOOL);
        TypedEClassInterface rightOwner = emptyClass(11, GraphType.BOOL);
        InstantiatedOperator wrap = fixedOperator(
                "phase-g-wrap", new OnePortSchema(GraphType.BOOL), GraphType.BOOL);

        CanonicalShape leftShape = invocationShape(wrap, left);
        CanonicalShape rightShape = invocationShape(wrap, right);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        admitEmptyShape(graph, leftOwner, leftShape, "left-wrapper");
        admitEmptyShape(graph, rightOwner, rightShape, "right-wrapper");
        check(graph.parentUsesSnapshot().get(left.id()).size() == 1,
                "Reverse parent index records one left wrapper");

        ParentEdgeCertificate edge = parentEquation(
                left, TypedInvocation.identity(right), "merge-leaves", 0);
        graph.unionCertified(edge);
        check(graph.status() == GraphStatus.DIRTY && graph.dirtyParentCount() == 1,
                "A child union enqueues exactly its affected parent record");
        expectThrows(IllegalStateException.class, () -> graph.hashOwner(rightShape));

        long orientationAttempts = graph.collisionOrientationAttemptsForTesting();
        RebuildReport report = graph.rebuild();
        check(graph.status() == GraphStatus.QUIESCENT
                        && graph.dirtyParentCount() == 0,
                "Rebuild reaches a zero-dirty quiescent state");
        check(report.processedRecords() == 1
                        && report.collisions() == 1
                        && report.certifiedUnions() == 1,
                "Canonical collision produces exactly one certified owner union");
        check(graph.collisionOrientationAttemptsForTesting()
                        - orientationAttempts == 2,
                "A compatible exact-shape collision evaluates both directed orientations");
        check(graph.retiredShapeRecordsSnapshot().size() == 1,
                "The absorbed duplicate shape retains one owner-qualified retirement proof");
        RetiredShapeRecordCertificate retirement = graph
                .retiredShapeRecordsSnapshot().values().iterator().next();
        retirement.verify();
        check(retirement.retiredRecord().shape().equals(rightShape)
                        && retirement.retainedRecord().shape().equals(rightShape),
                "Retirement evidence binds the exact collided canonical shape");
        EClassId owner = graph.hashOwner(rightShape);
        check(owner != null && graph.isLeader(owner),
                "Rebuilt canonical key has one leader owner");
        graph.checkInvariants();

        StructuralKey settled = graph.stateStructuralKey();
        RebuildReport repeated = graph.rebuild();
        check(repeated.processedRecords() == 0
                        && settled.equals(graph.stateStructuralKey()),
                "Rebuild is idempotent at quiescence");
    }

    private static void testIncomparableCollisionBucket() {
        TypedSlot integer = TypedSlot.canonicalFree(GraphType.INT, 0);
        TypedSlot truth = TypedSlot.canonicalFree(GraphType.BOOL, 0);
        TypedSlotContext ambient = TypedSlotContext.of(integer, truth);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                "incomparable-owner-shape",
                Arrays.asList(
                        new OnePortSchema(GraphType.INT),
                        new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        CanonicalShape shape = CanonicalShape.of(TypedENode.construct(
                operator,
                ambient,
                Arrays.asList(
                        OnePort.slot(ambient, integer),
                        OnePort.slot(ambient, truth))));

        TypedEClassInterface integerOwner = new TypedEClassInterface(
                EClassId.of(12_000),
                GraphType.BOOL,
                TypedSlotContext.singleton(integer));
        TypedEClassInterface booleanOwner = new TypedEClassInterface(
                EClassId.of(12_001),
                GraphType.BOOL,
                TypedSlotContext.singleton(truth));
        TypedRenaming identity = TypedRenaming.identity(ambient);
        ShapeWitness integerWitness = new ShapeWitness(
                ambient, ambient, integerOwner.exposedSlots(), identity);
        ShapeWitness booleanWitness = new ShapeWitness(
                ambient, ambient, booleanOwner.exposedSlots(), identity);

        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        admitShape(graph, integerOwner, shape, integerWitness,
                "incomparable-int-owner", 0);
        admitShape(graph, booleanOwner, shape, booleanWitness,
                "incomparable-bool-owner", 1);
        check(graph.status() == GraphStatus.QUIESCENT
                        && graph.hashBucketsSnapshot().get(shape).size() == 2,
                "Equal shapes with incomparable typed interfaces coexist at quiescence");
        check(graph.isLeader(integerOwner.id()) && graph.isLeader(booleanOwner.id()),
                "An unproved shape collision does not install a union-find parent");
        StructuralKey before = graph.stateStructuralKey();
        RebuildReport report = graph.rebuild();
        check(report.processedRecords() == 0
                        && report.certifiedUnions() == 0
                        && before.equals(graph.stateStructuralKey()),
                "An incomparable ownership bucket reaches deterministic quiescence");
        graph.restrictInterfaceCertified(restriction(
                graph.eclass(integerOwner.id()),
                TypedSlotContext.empty(),
                "incomparable-owner-revision",
                2));
        RebuildReport afterRestriction = graph.rebuild();
        check(afterRestriction.certifiedUnions() == 1
                        && graph.hashBucketsSnapshot().get(shape).size() == 1,
                "An incompatibility memo expires when a later coherence revision "
                        + "makes one directed collision proof admissible");
        check(graph.retiredShapeRecordsSnapshot().size() == 1,
                "A later compatible union retires the duplicate owner record explicitly");
        graph.checkInvariants();
    }

    private static TypedEClassInterface emptyClass(long id, GraphType type) {
        return new TypedEClassInterface(
                EClassId.of(id), type, TypedSlotContext.empty());
    }

    private static InstantiatedOperator fixedOperator(
            String name,
            PortSchema schema,
            GraphType output) {
        return OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(schema),
                output,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
    }

    private static CanonicalShape invocationShape(
            InstantiatedOperator operator,
            TypedEClassInterface target) {
        TypedSlotContext empty = TypedSlotContext.empty();
        return CanonicalShape.of(TypedENode.construct(
                operator,
                empty,
                Collections.singletonList(OnePort.invocation(
                        empty, TypedInvocation.identity(target)))));
    }

    private static CanonicalShape contextualInvocationShape(
            String operatorName,
            TypedEClassInterface target) {
        TypedSlotContext canonical = target.exposedSlots().canonicalFreeContext();
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        int index = 0;
        for (TypedSlot targetSlot : target.exposedSlots()) {
            mapping.put(targetSlot, canonical.slotsOfType(targetSlot.type()).get(index++));
        }
        TypedInvocation invocation = new TypedInvocation(
                target,
                TypedEmbedding.of(target.exposedSlots(), canonical, mapping));
        InstantiatedOperator operator = fixedOperator(
                operatorName, new OnePortSchema(target.outputType()), target.outputType());
        return CanonicalShape.of(TypedENode.construct(
                operator,
                canonical,
                Collections.singletonList(OnePort.invocation(canonical, invocation))));
    }

    private static CanonicalShape flatInvocationShape(
            String name,
            PortSchema.Kind kind,
            TypedEClassInterface left,
            TypedEClassInterface right) {
        OnePortSchema element = new OnePortSchema(GraphType.BOOL);
        PortSchema schema;
        java.util.List<ContainerLawCertificate.Law> laws = new ArrayList<>();
        laws.add(ContainerLawCertificate.Law.ASSOCIATIVITY);
        if (kind == PortSchema.Kind.SEQ) {
            schema = new SeqPortSchema(ContainerEmptiness.K_PLUS, element);
        } else if (kind == PortSchema.Kind.BAG) {
            schema = new BagPortSchema(ContainerEmptiness.K_PLUS, element);
            laws.add(ContainerLawCertificate.Law.COMMUTATIVITY);
        } else if (kind == PortSchema.Kind.SET) {
            schema = new SetPortSchema(ContainerEmptiness.K_PLUS, element);
            laws.add(ContainerLawCertificate.Law.COMMUTATIVITY);
            laws.add(ContainerLawCertificate.Law.IDEMPOTENCY);
        } else {
            throw new IllegalArgumentException("Not a flexible port kind: " + kind);
        }
        java.util.List<ContainerLawCertificate> certificates = new ArrayList<>();
        CertificateOrigin origin = CertificateOrigin.containerLaw(
                "phase-g", name, 0);
        for (ContainerLawCertificate.Law law : laws) {
            certificates.add(ContainerLawCertificate.testFixture(schema, law, origin));
        }
        Map<PortPath, ContainerLawDeclaration> declaration = new LinkedHashMap<>();
        declaration.put(
                PortPath.at(0),
                ContainerLawDeclaration.certified(schema, certificates));
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Collections.singletonList(schema),
                GraphType.BOOL,
                declaration,
                0).instantiateMonomorphic();
        TypedSlotContext empty = TypedSlotContext.empty();
        FlatApplication application = new FlatApplication(
                operator,
                empty,
                Arrays.asList(
                        new FlatLeaf(OnePort.invocation(
                                empty, TypedInvocation.identity(left))),
                        new FlatLeaf(OnePort.invocation(
                                empty, TypedInvocation.identity(right)))));
        return CanonicalShape.of(TypedENode.flatConstruct(
                application,
                ignored -> {
                    throw new AssertionError("Leaf-only flat source cannot invoke its sealer");
                }));
    }

    private static CanonicalShape flatSingleInvocationShape(
            InstantiatedOperator operator,
            TypedEClassInterface target) {
        TypedSlotContext empty = TypedSlotContext.empty();
        FlatApplication application = new FlatApplication(
                operator,
                empty,
                Collections.singletonList(new FlatLeaf(OnePort.invocation(
                        empty, TypedInvocation.identity(target)))));
        return CanonicalShape.of(TypedENode.flatConstruct(
                application,
                ignored -> {
                    throw new AssertionError("Leaf-only flat source cannot invoke its sealer");
                }));
    }

    private static CanonicalShape binarySlotShape(String name) {
        TypedSlot first = TypedSlot.canonicalFree(USER, 0);
        TypedSlot second = TypedSlot.canonicalFree(USER, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                Arrays.asList(new OnePortSchema(USER), new OnePortSchema(USER)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        return CanonicalShape.of(TypedENode.construct(
                operator,
                context,
                Arrays.asList(
                        OnePort.slot(context, first),
                        OnePort.slot(context, second))));
    }

    private static TypedSlottedPortEGraph collisionFanInGraph() {
        TypedEClassInterface a = emptyClass(90, GraphType.BOOL);
        TypedEClassInterface b = emptyClass(91, GraphType.BOOL);
        TypedEClassInterface c = emptyClass(92, GraphType.BOOL);
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph();
        graph.registerEmptyClassForPhaseF(a);
        graph.registerEmptyClassForPhaseF(b);
        graph.registerEmptyClassForPhaseF(c);
        InstantiatedOperator wrap = fixedOperator(
                "order-wrap", new OnePortSchema(GraphType.BOOL), GraphType.BOOL);
        admitEmptyShape(graph, emptyClass(100, GraphType.BOOL),
                invocationShape(wrap, a), "order-a");
        admitEmptyShape(graph, emptyClass(101, GraphType.BOOL),
                invocationShape(wrap, b), "order-b");
        admitEmptyShape(graph, emptyClass(102, GraphType.BOOL),
                invocationShape(wrap, c), "order-c");
        graph.unionCertified(parentEquation(
                a, TypedInvocation.identity(c), "order-a-c", 0));
        graph.unionCertified(parentEquation(
                b, TypedInvocation.identity(c), "order-b-c", 1));
        return graph;
    }

    private static ShapeWitness contextualWitness(
            TypedSlotContext canonical,
            TypedSlotContext ambient,
            TypedSlotContext exposed) {
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        Map<GraphType, Integer> offsets = new LinkedHashMap<>();
        for (TypedSlot slot : canonical) {
            int offset = offsets.getOrDefault(slot.type(), 0);
            mapping.put(slot, ambient.slotsOfType(slot.type()).get(offset));
            offsets.put(slot.type(), offset + 1);
        }
        return new ShapeWitness(
                canonical,
                ambient,
                exposed,
                TypedRenaming.of(canonical, ambient, mapping));
    }

    private static void admitEmptyShape(
            TypedSlottedPortEGraph graph,
            TypedEClassInterface owner,
            CanonicalShape shape,
            String label) {
        TypedSlotContext empty = TypedSlotContext.empty();
        ShapeWitness witness = new ShapeWitness(
                empty, empty, empty, TypedRenaming.identity(empty));
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(empty));
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-g", label, 0),
                TypedCertificateEndpoint.node(shape.node()),
                TypedCertificateEndpoint.eclassWitness(owner));
        graph.admitFixedBatchRecordCertified(
                record, Collections.singletonMap(shape, equation));
    }

    private static void admitShape(
            TypedSlottedPortEGraph graph,
            TypedEClassInterface owner,
            CanonicalShape shape,
            ShapeWitness witness,
            String label,
            int index) {
        TypedEClassRecord record = TypedEClassRecord.of(
                owner,
                Collections.singletonMap(shape, witness),
                TypedSymmetryGroup.identity(owner.exposedSlots()));
        InputEquationCertificate equation = shapeEquation(
                owner, shape, witness, label, index);
        graph.admitFixedBatchRecordCertified(
                record, Collections.singletonMap(shape, equation));
    }

    private static InputEquationCertificate shapeEquation(
            TypedEClassInterface owner,
            CanonicalShape shape,
            ShapeWitness witness,
            String label,
            int index) {
        TypedEmbedding ownerInAmbient = TypedEmbedding.inclusion(
                owner.exposedSlots(), witness.ambientSupport());
        return new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-g", label, index),
                TypedCertificateEndpoint.node(
                        shape.node().act(witness.instantiatingRenaming())),
                TypedCertificateEndpoint.invocation(
                        new TypedInvocation(owner, ownerInAmbient)));
    }

    private static int invokeCollisionResolution(
            TypedSlottedPortEGraph graph,
            CanonicalShape shape) {
        return (Integer) invokePrivate(
                graph,
                "resolveShapeCollisions",
                new Class<?>[] {CanonicalShape.class},
                shape);
    }

    private static Object invokePrivate(
            Object receiver,
            String name,
            Class<?>[] parameterTypes,
            Object... arguments) {
        try {
            Method method = receiver.getClass().getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new AssertionError("Private collision transition failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Collision regression cannot reach its boundary", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static <K, V> NavigableMap<K, V> privateMap(
            Object receiver,
            String name) {
        try {
            Field field = receiver.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (NavigableMap<K, V>) field.get(receiver);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(
                    "Collision regression cannot inspect its memo boundary",
                    exception);
        }
    }

    private static InterfaceRestrictionCertificate restriction(
            TypedEClassRecord record,
            TypedSlotContext restricted,
            String label,
            int index) {
        TypedCertificateEndpoint reduced = TypedCertificateEndpoint.restrictedWitness(
                record.interfaceView(), restricted);
        TypedEmbedding inclusion = TypedEmbedding.inclusion(
                restricted, record.exposedSlots());
        InputEquationCertificate factorization = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-g", label, index),
                TypedCertificateEndpoint.eclassWitness(record.interfaceView()),
                reduced.act(inclusion));
        return new InterfaceRestrictionCertificate(record, restricted, factorization);
    }

    private static SymmetryCertificate symmetry(
            TypedEClassInterface eclass,
            TypedPermutation permutation,
            String label,
            int index) {
        TypedInvocation left = TypedInvocation.identity(eclass);
        TypedInvocation right = new TypedInvocation(eclass, permutation);
        InputEquationCertificate equation = InputEquationCertificate.betweenInvocations(
                CertificateOrigin.rewriteAxiom("phase-g", label, index),
                left,
                right);
        return new SymmetryCertificate(left, right, equation);
    }

    private static ParentEdgeCertificate parentEquation(
            TypedEClassInterface child,
            TypedInvocation parent,
            String label,
            int index) {
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase-g", label, index),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.invocation(parent));
        return new ParentEdgeCertificate(child, parent, equation);
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
}
