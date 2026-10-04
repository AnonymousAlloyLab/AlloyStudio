package is.fivefivefive.CanDis.theory;

import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Focused producer gate for exact schema-v10 transition evidence. */
public final class Phase4ProducerTransitionEvidenceTest {
    private static int checks;

    private Phase4ProducerTransitionEvidenceTest() {
    }

    public static void main(String[] args) {
        testCollisionInsertionEvidence();
        testDirectUnionRehomeEvidence();
        testRestrictionRejectsForgedPostEquation();
        testRebuildDuplicateRetirementEvidence();
        testRebuildGeneratedUnionEvidence();
        testUnionRejectsForgedDirtyQueue();
        testUnionRejectsSilentSymmetryMutation();
        testRebuildRejectsPreCallIntervalExpansion();
        testSnapshotRejectsMalformedParentTopology();
        testSnapshotRejectsMalformedLedgers();
        testTraceSinkRequiresClosedRebuildIntervals();
        testEventKindPayloadPairing();
        System.out.println("Phase4ProducerTransitionEvidenceTest passed: "
                + checks + " checks");
    }

    private static void testCollisionInsertionEvidence() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedENode source = constantNode("phase4-insertion");
        graph.insertNode(source, graph.coherentWitnessFamily());
        CertifiedInsertionResult second = graph.insertNode(
                source, graph.coherentWitnessFamily());

        CertificateTraceEvent event = sink.events().get(1);
        check(event.kind() == CertificateTraceEvent.Kind.INSERT_COLLISION,
                "Second source occurrence is retained as a collision insertion");
        CertificateTracePayload.Insertion payload =
                (CertificateTracePayload.Insertion) event.payload();
        check(payload.result().canonicalization().sourceReplay().leftEndpoint().equals(
                        TypedCertificateEndpoint.node(source))
                        && payload.result().shapeEquation().rightEndpoint().equals(
                                TypedCertificateEndpoint.eclassWitness(
                                        payload.result().insertedClass())),
                "Insertion retains its exact source replay and fresh shape equation");
        check(payload.generatedSubtransitions().size() == 1
                        && payload.generatedSubtransitions().get(0)
                                .retirements().size() == 1,
                "Collision insertion retains its generated union and retirement");
        RetiredShapeRecordCertificate retirement = payload.generatedSubtransitions()
                .get(0).retirements().get(0);
        check(retirement.cause()
                        == RetiredShapeRecordCertificate.Cause.OWNER_UNION
                        && retirement.retiredEquation() != null
                        && retirement.replacementEquation() != null
                        && retirement.retainedEquation() != null,
                "Insertion retirement binds old, candidate, and retained equations");
        expectThrows(IllegalArgumentException.class,
                () -> new CertificateTracePayload.Insertion(second));
        List<CertificateTracePayload.Union> padded = new java.util.ArrayList<>(
                payload.generatedSubtransitions());
        padded.add(standaloneEmptyUnion("unexecuted-insertion-union", 800, 801));
        CertificateTracePayload.Insertion forged =
                new CertificateTracePayload.Insertion(second, padded);
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(event.after(), forged));

        CertificateTraceSnapshot deletedRetirementLedger = cloneSnapshot(
                event.after(),
                event.after().shapeCertificates(),
                Collections.emptyMap());
        check(!deletedRetirementLedger.stateKey().equals(event.after().stateKey()),
                "Snapshot keys are derived from the complete retirement ledger");
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(
                        deletedRetirementLedger, event.payload()));
        assertContinuous(sink.events());
    }

    private static void testDirectUnionRehomeEvidence() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface child = emptyClass(100);
        TypedEClassInterface parent = emptyClass(101);
        CanonicalShape first = CanonicalShape.of(constantNode("phase4-rehome-a"));
        CanonicalShape second = CanonicalShape.of(constantNode("phase4-rehome-b"));
        admitShapes(graph, child, List.of(first, second), "rehome-child");
        graph.registerEmptyClassForPhaseF(parent);

        ParentEdgeCertificate edge = parentEquation(
                child, parent, "rehome-edge", 0);
        graph.unionCertified(edge);
        CertificateTracePayload.Union payload =
                (CertificateTracePayload.Union) sink.events().get(0).payload();
        check(payload.certificate().equals(edge)
                        && payload.revisionIncrement()
                        && payload.rehomes().size() == 2
                        && payload.retirements().isEmpty(),
                "Direct union retains one exact rehome disposition");
        CertificateTracePayload.ShapeRehome rehome = payload.rehomes().get(0);
        check(rehome.original().key().owner().equals(child.id())
                        && rehome.replacement().key().owner().equals(parent.id())
                        && rehome.original().key().shape().equals(
                                rehome.replacement().key().shape()),
                "Union rehome preserves the shape while changing its owner");
        check(sink.events().get(0).after().liveShapeRecords()
                        .containsKey(rehome.replacement().key()),
                "Rehome replacement is live in the exact post-snapshot");
        expectThrows(IllegalArgumentException.class,
                () -> new CertificateTracePayload.Union(
                        edge,
                        List.of(payload.rehomes().get(1), payload.rehomes().get(0)),
                        payload.retirements()));
        TypedSlot stale = TypedSlot.source(GraphType.BOOL, 99);
        TypedSlotContext staleContext = TypedSlotContext.singleton(stale);
        ParentEdgeCertificate staleEdge = parentEquation(
                new TypedEClassInterface(child.id(), GraphType.BOOL, staleContext),
                new TypedEClassInterface(parent.id(), GraphType.BOOL, staleContext),
                "stale-rehome-edge",
                0);
        expectThrows(IllegalArgumentException.class,
                () -> new CertificateTracePayload.Union(
                        staleEdge, payload.rehomes(), payload.retirements()));
        expectThrows(IllegalStateException.class,
                () -> sink.events().get(0).before().verifyConservationTo(
                        sink.events().get(0).after(),
                        new CertificateTracePayload.Union(edge)));
        CertificateTracePayload.Union wrongRevision =
                new CertificateTracePayload.Union(
                        payload.certificate(), payload.rehomes(), payload.retirements());
        expectThrows(IllegalStateException.class,
                () -> sink.events().get(0).before().verifyConservationTo(
                        sink.events().get(0).after(), wrongRevision));
        assertContinuous(sink.events());
    }

    private static void testRestrictionRejectsForgedPostEquation() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedSlot x = TypedSlot.source(GraphType.BOOL, 300);
        TypedSlot y = TypedSlot.source(GraphType.BOOL, 301);
        TypedEClassInterface owner = new TypedEClassInterface(
                EClassId.of(500), GraphType.BOOL, TypedSlotContext.of(x, y));
        CanonicalShape shape = binarySlotShape("restriction-post-equation");
        ShapeWitness witness = contextualWitness(
                shape.exactSlots(), owner.exposedSlots(), owner.exposedSlots());
        admitShape(graph, owner, shape, witness, "restriction-old", 0);
        InterfaceRestrictionCertificate restriction = restriction(
                graph.eclass(owner.id()), TypedSlotContext.singleton(x),
                "restriction-factor", 0);
        graph.restrictInterfaceCertified(restriction);
        CertificateTraceEvent event = sink.events().get(0);
        CertificateTracePayload.ShapeRecord actual =
                event.after().liveShapeRecords().values().iterator().next();
        InputEquationCertificate unrelated = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "phase4-v8", "unrelated-restriction-post", 99),
                actual.ownerEquation().leftEndpoint(),
                actual.ownerEquation().rightEndpoint());
        Map<ParentRecordKey, TypedEqualityCertificate> forgedEquations =
                new LinkedHashMap<>(event.after().shapeCertificates());
        forgedEquations.put(actual.key(), unrelated);
        CertificateTraceSnapshot forgedAfter = cloneSnapshot(
                event.after(), forgedEquations, event.after().retiredShapeRecords());
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(
                        forgedAfter, event.payload()));
    }

    private static void testRebuildDuplicateRetirementEvidence() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface left = emptyClass(200);
        TypedEClassInterface right = emptyClass(201);
        TypedEClassInterface owner = emptyClass(210);
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        InstantiatedOperator wrap = unaryOperator("phase4-local-duplicate");
        CanonicalShape leftShape = invocationShape(wrap, left);
        CanonicalShape rightShape = invocationShape(wrap, right);
        admitShapes(graph, owner, List.of(leftShape, rightShape), "local-duplicate");

        graph.unionCertified(parentEquation(left, right, "merge-local", 0));
        RebuildReport report = graph.rebuild();
        CertificateTracePayload.RebuildRecord rebuild = onlyRebuildRecord(sink.events());
        check(rebuild.hasExactEvidence()
                        && rebuild.rebuildRoot() instanceof RebuildCongruenceCertificate,
                "Rebuild retains its exact old record and root congruence proof");
        check(rebuild.replacementRecord() == null
                        && rebuild.retirement() != null
                        && rebuild.retirement().cause()
                                == RetiredShapeRecordCertificate.Cause.REBUILD_DUPLICATE,
                "Same-owner canonical duplicate is retired against its live record");
        check(rebuild.retirement().replacementRecord().equals(
                        rebuild.retirement().retainedRecord())
                        && report.generatedSubtransitions().isEmpty(),
                "Rebuild retirement names the candidate and retained record identity");
        CertificateTraceEvent rebuildEvent = rebuildEvent(sink.events());
        expectThrows(IllegalStateException.class,
                () -> rebuildEvent.before().verifyConservationTo(
                        rebuildEvent.after(),
                        new CertificateTracePayload.RebuildRecord(
                                rebuild.original(), true, false, false)));
        expectThrows(IllegalArgumentException.class,
                () -> new CertificateTracePayload.RebuildRecord(
                        rebuild.originalRecord(),
                        rebuild.canonicalization(),
                        rebuild.rebuildRoot(),
                        rebuild.replacementRecord(),
                        rebuild.retirement(),
                        rebuild.generatedSubtransitions(),
                        !rebuild.changed()));
        assertContinuous(sink.events());
    }

    private static void testUnionRejectsForgedDirtyQueue() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface child = emptyClass(900);
        TypedEClassInterface parent = emptyClass(901);
        admitShapes(
                graph,
                child,
                List.of(CanonicalShape.of(constantNode("forged-dirty"))),
                "forged-dirty");
        graph.registerEmptyClassForPhaseF(parent);
        graph.unionCertified(parentEquation(child, parent, "forged-dirty", 0));
        CertificateTraceEvent event = sink.events().get(0);
        check(event.after().dirtyParents().isEmpty(),
                "Dirty-queue forgery fixture has an empty honest queue");
        Set<ParentRecordKey> forgedDirty = new LinkedHashSet<>();
        forgedDirty.add(event.after().liveShapeRecords().keySet().iterator().next());
        CertificateTraceSnapshot forged = cloneSnapshot(
                event.after(), event.after().classes(), forgedDirty);
        check(!forged.stateKey().equals(event.after().stateKey()),
                "Snapshot identity binds the exact dirty queue");
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(forged, event.payload()));
    }

    private static void testUnionRejectsSilentSymmetryMutation() {
        TypedSlot first = TypedSlot.canonicalFree(GraphType.BOOL, 0);
        TypedSlot second = TypedSlot.canonicalFree(GraphType.BOOL, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        TypedEClassInterface child = new TypedEClassInterface(
                EClassId.of(910), GraphType.BOOL, context);
        TypedEClassInterface parent = new TypedEClassInterface(
                EClassId.of(911), GraphType.BOOL, context);
        CanonicalShape shape = binarySlotShape("forged-symmetry");
        ShapeWitness witness = contextualWitness(context, context, context);

        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        admitShape(graph, child, shape, witness, "forged-symmetry", 0);
        graph.registerEmptyClassForPhaseF(parent);
        graph.unionCertified(parentEquation(child, parent, "forged-symmetry", 1));
        CertificateTraceEvent event = sink.events().get(0);

        Map<TypedSlot, TypedSlot> swapMap = new LinkedHashMap<>();
        swapMap.put(first, second);
        swapMap.put(second, first);
        TypedPermutation swap = TypedPermutation.of(context, swapMap);
        TypedInvocation identity = TypedInvocation.identity(parent);
        TypedInvocation swapped = new TypedInvocation(parent, swap);
        SymmetryCertificate extra = new SymmetryCertificate(
                identity,
                swapped,
                InputEquationCertificate.betweenInvocations(
                        CertificateOrigin.rewriteAxiom(
                                "phase4-v8", "silent-symmetry", 0),
                        identity,
                        swapped));
        Map<EClassId, TypedEClassRecord> forgedClasses =
                new LinkedHashMap<>(event.after().classes());
        TypedEClassRecord honestParent = forgedClasses.get(parent.id());
        forgedClasses.put(
                parent.id(),
                honestParent.withSymmetryGroup(
                        TypedSymmetryGroup.certified(parent, List.of(extra))));
        CertificateTraceSnapshot forged = cloneSnapshot(
                event.after(), forgedClasses, event.after().dirtyParents());
        check(!forged.stateKey().equals(event.after().stateKey()),
                "Snapshot identity binds the exact class symmetry state");
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(forged, event.payload()));
    }

    private static void testRebuildRejectsPreCallIntervalExpansion() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface left = emptyClass(920);
        TypedEClassInterface right = emptyClass(921);
        TypedEClassInterface owner = emptyClass(922);
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        InstantiatedOperator wrap = unaryOperator("rebuild-boundary");
        admitShapes(
                graph,
                owner,
                List.of(invocationShape(wrap, left), invocationShape(wrap, right)),
                "rebuild-boundary");
        graph.unionCertified(parentEquation(left, right, "rebuild-boundary", 0));
        graph.rebuild();

        List<CertificateTraceEvent> events = sink.events();
        CertificateTraceEvent directUnion = events.get(0);
        CertificateTraceEvent start = events.get(1);
        CertificateTraceEvent completion = completionEvent(events);
        RebuildReport actual = ((CertificateTracePayload.RebuildComplete)
                completion.payload()).report();
        check(start.kind() == CertificateTraceEvent.Kind.REBUILD_START
                        && actual.firstEventSequence() == start.sequence(),
                "Rebuild report starts at its retained no-op boundary");

        List<CertificateTracePayload.Union> padded = new ArrayList<>();
        padded.add((CertificateTracePayload.Union) directUnion.payload());
        padded.addAll(actual.generatedSubtransitions());
        int maximumDirty = 0;
        for (int index = 0; index < events.size() - 1; index++) {
            maximumDirty = Math.max(
                    maximumDirty, events.get(index).before().dirtyParents().size());
        }
        RebuildReport forged = new RebuildReport(
                actual.processedRecords(),
                actual.changedKeys(),
                Math.incrementExact(actual.collisions()),
                Math.incrementExact(actual.certifiedUnions()),
                maximumDirty,
                padded,
                directUnion.sequence(),
                actual.processedTransitions());
        CertificateTraceEvent forgedCompletion = new CertificateTraceEvent(
                completion.sequence(),
                CertificateTraceEvent.Kind.REBUILD_COMPLETE,
                completion.before(),
                completion.after(),
                new CertificateTracePayload.RebuildComplete(forged));
        RecordingCertificateTraceSink replay = new RecordingCertificateTraceSink();
        for (CertificateTraceEvent event : events) {
            if (event == completion) {
                break;
            }
            replay.append(event);
        }
        expectThrows(IllegalStateException.class,
                () -> replay.append(forgedCompletion));
    }

    private static void testSnapshotRejectsMalformedParentTopology() {
        TypedEClassInterface first = emptyClass(930);
        TypedEClassInterface second = emptyClass(931);
        Map<EClassId, TypedEClassRecord> classes = new LinkedHashMap<>();
        classes.put(first.id(), TypedEClassRecord.empty(first));
        classes.put(second.id(), TypedEClassRecord.empty(second));
        Map<EClassId, ParentAssignment> parents = new LinkedHashMap<>();
        parents.put(first.id(), ParentAssignment.direct(ParentStep.certified(
                parentEquation(first, second, "cyclic-parent", 0))));
        parents.put(second.id(), ParentAssignment.direct(ParentStep.certified(
                parentEquation(second, first, "cyclic-parent", 1))));

        expectThrows(IllegalStateException.class,
                () -> emptySnapshot(classes, parents));

        TypedEClassInterface absent = emptyClass(932);
        Map<EClassId, TypedEClassRecord> singletonClasses = Map.of(
                first.id(), TypedEClassRecord.empty(first));
        Map<EClassId, ParentAssignment> dangling = Map.of(
                first.id(), ParentAssignment.direct(ParentStep.certified(
                        parentEquation(first, absent, "absent-parent", 0))));
        expectThrows(IllegalStateException.class,
                () -> emptySnapshot(singletonClasses, dangling));

        Map<EClassId, ParentAssignment> uncertified = Map.of(
                first.id(), ParentAssignment.direct(new ParentStep(
                        first, TypedInvocation.identity(second))));
        Map<EClassId, ParentAssignment> withSecondRoot = new LinkedHashMap<>(
                uncertified);
        withSecondRoot.put(second.id(), ParentAssignment.root(second));
        expectThrows(IllegalArgumentException.class,
                () -> emptySnapshot(classes, withSecondRoot));

        TypedEClassInterface middle = emptyClass(933);
        ParentPath retainedPath = ParentPath.direct(ParentStep.certified(
                        parentEquation(first, middle, "compressed-parent", 0)))
                .andThen(ParentPath.direct(ParentStep.certified(
                        parentEquation(middle, second, "compressed-parent", 1))));
        Map<EClassId, ParentAssignment> absentIntermediate = new LinkedHashMap<>();
        absentIntermediate.put(first.id(), ParentAssignment.compressed(retainedPath));
        absentIntermediate.put(second.id(), ParentAssignment.root(second));
        expectThrows(IllegalArgumentException.class,
                () -> emptySnapshot(classes, absentIntermediate));

        Map<EClassId, TypedEClassRecord> triple = new LinkedHashMap<>(classes);
        triple.put(middle.id(), TypedEClassRecord.empty(middle));
        Map<EClassId, ParentAssignment> inconsistentIntermediate =
                new LinkedHashMap<>(absentIntermediate);
        inconsistentIntermediate.put(middle.id(), ParentAssignment.root(middle));
        expectThrows(IllegalArgumentException.class,
                () -> emptySnapshot(triple, inconsistentIntermediate));
    }

    private static void testSnapshotRejectsMalformedLedgers() {
        EClassId phantom = EClassId.of(940);
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Map.of(phantom, Collections.emptySet()),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap()));
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Map.of(phantom, Collections.emptyList()),
                Collections.emptyMap()));

        RecordingCertificateTraceSink insertionSink =
                new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph insertionGraph =
                new TypedSlottedPortEGraph(insertionSink);
        CertifiedInsertionResult insertion = insertionGraph.insertNode(
                constantNode("orphan-insertion"),
                insertionGraph.coherentWitnessFamily());
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Map.of(phantom, insertion)));

        TypedSlot slot = TypedSlot.source(GraphType.BOOL, 941);
        TypedEClassInterface restrictedOwner = new TypedEClassInterface(
                EClassId.of(942),
                GraphType.BOOL,
                TypedSlotContext.singleton(slot));
        InterfaceRestrictionCertificate orphanRestriction = restriction(
                TypedEClassRecord.empty(restrictedOwner),
                TypedSlotContext.empty(),
                "orphan-restriction",
                0);
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Map.of(phantom, List.of(orphanRestriction)),
                Collections.emptyMap()));

        TypedEClassInterface absent = emptyClass(943);
        TypedEClassInterface owner = emptyClass(944);
        CanonicalShape shape = invocationShape(
                unaryOperator("absent-live-invocation"), absent);
        ShapeWitness witness = new ShapeWitness(
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedSlotContext.empty(),
                TypedRenaming.identity(TypedSlotContext.empty()));
        TypedEClassRecord ownerRecord = TypedEClassRecord.of(
                owner,
                Map.of(shape, witness),
                TypedSymmetryGroup.identity(TypedSlotContext.empty()));
        ParentRecordKey liveKey = new ParentRecordKey(owner.id(), shape);
        TypedEqualityCertificate liveEquation = new InputEquationCertificate(
                CertificateOrigin.inputEquation(
                        "phase4-v8", "absent-live-invocation", 0),
                TypedCertificateEndpoint.node(shape.node()),
                TypedCertificateEndpoint.eclassWitness(owner));
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Map.of(owner.id(), ownerRecord),
                Map.of(owner.id(), ParentAssignment.root(owner)),
                Map.of(shape, Set.of(owner.id())),
                Map.of(liveKey, liveEquation),
                Collections.emptyMap(),
                Map.of(absent.id(), Set.of(liveKey)),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap()));

        RecordingCertificateTraceSink retirementSink =
                new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph retirementGraph =
                new TypedSlottedPortEGraph(retirementSink);
        TypedENode duplicate = constantNode("orphan-retirement");
        retirementGraph.insertNode(
                duplicate, retirementGraph.coherentWitnessFamily());
        retirementGraph.insertNode(
                duplicate, retirementGraph.coherentWitnessFamily());
        Map<ParentRecordKey, RetiredShapeRecordCertificate> retirements =
                retirementSink.events().get(1).after().retiredShapeRecords();
        expectThrows(IllegalArgumentException.class, () -> new CertificateTraceSnapshot(
                0,
                GraphStatus.DIRTY,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                retirements,
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap()));
    }

    private static void testTraceSinkRequiresClosedRebuildIntervals() {
        RecordingCertificateTraceSink unionSource =
                new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph unionGraph = new TypedSlottedPortEGraph(unionSource);
        TypedEClassInterface child = emptyClass(950);
        TypedEClassInterface parent = emptyClass(951);
        unionGraph.registerEmptyClassForPhaseF(child);
        unionGraph.registerEmptyClassForPhaseF(parent);
        unionGraph.unionCertified(parentEquation(child, parent, "unscoped-union", 0));
        CertificateTraceEvent publicUnion = unionSource.events().get(0);
        CertificateTracePayload.Union publicPayload =
                (CertificateTracePayload.Union) publicUnion.payload();
        CertificateTracePayload.Union zeroRevision = new CertificateTracePayload.Union(
                publicPayload.certificate(),
                publicPayload.rehomes(),
                publicPayload.retirements());
        CertificateTraceSnapshot zeroRevisionAfter = cloneSnapshotWithRevision(
                publicUnion.after(), publicUnion.before().revision());
        CertificateTraceEvent unscoped = new CertificateTraceEvent(
                0,
                CertificateTraceEvent.Kind.UNION,
                publicUnion.before(),
                zeroRevisionAfter,
                zeroRevision);
        expectThrows(IllegalStateException.class,
                () -> new RecordingCertificateTraceSink().append(unscoped));

        RecordingCertificateTraceSink source = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(source);
        TypedEClassInterface left = emptyClass(952);
        TypedEClassInterface right = emptyClass(953);
        TypedEClassInterface owner = emptyClass(954);
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        admitShapes(
                graph,
                owner,
                List.of(
                        invocationShape(unaryOperator("sink-rebuild"), left),
                        invocationShape(unaryOperator("sink-rebuild"), right)),
                "sink-rebuild");
        graph.unionCertified(parentEquation(left, right, "sink-rebuild", 0));
        graph.rebuild();
        List<CertificateTraceEvent> sourceEvents = source.events();
        CertificateTraceEvent start = eventOfKind(
                sourceEvents, CertificateTraceEvent.Kind.REBUILD_START);
        CertificateTraceEvent record = eventOfKind(
                sourceEvents, CertificateTraceEvent.Kind.REBUILD_RECORD);
        CertificateTraceEvent completion = eventOfKind(
                sourceEvents, CertificateTraceEvent.Kind.REBUILD_COMPLETE);

        RecordingCertificateTraceSink open = new RecordingCertificateTraceSink();
        open.append(renumber(start, 0));
        expectThrows(IllegalStateException.class, open::events);
        expectThrows(IllegalStateException.class,
                () -> new RecordingCertificateTraceSink().append(renumber(record, 0)));
        expectThrows(IllegalStateException.class,
                () -> new RecordingCertificateTraceSink().append(
                        renumber(completion, 0)));
    }

    private static CertificateTraceEvent eventOfKind(
            List<CertificateTraceEvent> events,
            CertificateTraceEvent.Kind kind) {
        for (CertificateTraceEvent event : events) {
            if (event.kind() == kind) {
                return event;
            }
        }
        throw new AssertionError("Missing trace event " + kind);
    }

    private static CertificateTraceEvent renumber(
            CertificateTraceEvent event,
            long sequence) {
        return new CertificateTraceEvent(
                sequence,
                event.kind(),
                event.before(),
                event.after(),
                event.payload());
    }

    private static CertificateTraceSnapshot cloneSnapshotWithRevision(
            CertificateTraceSnapshot source,
            long revision) {
        return new CertificateTraceSnapshot(
                revision,
                source.status(),
                source.classes(),
                source.parents(),
                source.hashCons(),
                source.shapeCertificates(),
                source.retiredShapeRecords(),
                source.parentUses(),
                source.dirtyParents(),
                source.restrictions(),
                source.insertions());
    }

    private static CertificateTraceSnapshot emptySnapshot(
            Map<EClassId, TypedEClassRecord> classes,
            Map<EClassId, ParentAssignment> parents) {
        return new CertificateTraceSnapshot(
                0,
                GraphStatus.QUIESCENT,
                classes,
                parents,
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptySet(),
                Collections.emptyMap(),
                Collections.emptyMap());
    }

    private static void testRebuildGeneratedUnionEvidence() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        TypedEClassInterface left = emptyClass(300);
        TypedEClassInterface right = emptyClass(301);
        TypedEClassInterface leftOwner = emptyClass(310);
        TypedEClassInterface rightOwner = emptyClass(311);
        graph.registerEmptyClassForPhaseF(left);
        graph.registerEmptyClassForPhaseF(right);
        InstantiatedOperator wrap = unaryOperator("phase4-generated-union");
        CanonicalShape leftShape = invocationShape(wrap, left);
        CanonicalShape rightShape = invocationShape(wrap, right);
        admitShapes(graph, leftOwner, Collections.singletonList(leftShape), "left-owner");
        admitShapes(graph, rightOwner, Collections.singletonList(rightShape), "right-owner");

        graph.unionCertified(parentEquation(left, right, "merge-children", 0));
        RebuildReport report = graph.rebuild();
        CertificateTracePayload.RebuildRecord rebuild = onlyRebuildRecord(sink.events());
        check(rebuild.replacementRecord() != null
                        && rebuild.generatedSubtransitions().size() == 1,
                "Rebuild record retains its replacement and generated collision union");
        CertificateTracePayload.Union nested = rebuild.generatedSubtransitions().get(0);
        check(nested.retirements().size() == 1
                        && nested.certificate().equals(
                                nested.retirements().get(0).parentEdge()),
                "Nested union binds its installed edge and duplicate retirement");
        check(report.generatedSubtransitions().equals(
                        rebuild.generatedSubtransitions())
                        && report.certifiedUnions() == 1,
                "Rebuild report retains the same generated transition ledger");
        CertificateTraceEvent event = rebuildEvent(sink.events());
        CertificateTracePayload.RebuildRecord padded =
                new CertificateTracePayload.RebuildRecord(
                        rebuild.originalRecord(),
                        rebuild.canonicalization(),
                        rebuild.rebuildRoot(),
                        rebuild.replacementRecord(),
                        rebuild.retirement(),
                        List.of(standaloneEmptyUnion(
                                "unexecuted-rebuild-union", 810, 811)),
                        true);
        expectThrows(IllegalStateException.class,
                () -> event.before().verifyConservationTo(event.after(), padded));
        expectThrows(IllegalArgumentException.class,
                () -> new RebuildReport(-1, -2, 0, 0, -3));
        expectThrows(IllegalArgumentException.class,
                () -> new RebuildReport(0, 0, 7, 0, 0));
        CertificateTraceEvent completion = completionEvent(sink.events());
        RebuildReport actualReport =
                ((CertificateTracePayload.RebuildComplete) completion.payload()).report();
        RebuildReport shiftedInterval = new RebuildReport(
                actualReport.processedRecords(),
                actualReport.changedKeys(),
                actualReport.collisions(),
                actualReport.certifiedUnions(),
                actualReport.maximumDirtyRecords(),
                actualReport.generatedSubtransitions(),
                Math.incrementExact(actualReport.firstEventSequence()),
                actualReport.processedTransitions());
        CertificateTraceEvent forgedCompletion = new CertificateTraceEvent(
                completion.sequence(),
                CertificateTraceEvent.Kind.REBUILD_COMPLETE,
                completion.before(),
                completion.after(),
                new CertificateTracePayload.RebuildComplete(shiftedInterval));
        RecordingCertificateTraceSink replaySink = new RecordingCertificateTraceSink();
        for (CertificateTraceEvent prior : sink.events()) {
            if (prior == completion) {
                break;
            }
            replaySink.append(prior);
        }
        expectThrows(IllegalStateException.class,
                () -> replaySink.append(forgedCompletion));
        assertContinuous(sink.events());
    }

    private static void testEventKindPayloadPairing() {
        RecordingCertificateTraceSink sink = new RecordingCertificateTraceSink();
        TypedSlottedPortEGraph graph = new TypedSlottedPortEGraph(sink);
        graph.insertNode(constantNode("kind-payload"), graph.coherentWitnessFamily());
        CertificateTraceEvent actual = sink.events().get(0);
        expectThrows(IllegalArgumentException.class,
                () -> new CertificateTraceEvent(
                        0,
                        CertificateTraceEvent.Kind.REBUILD_COMPLETE,
                        actual.before(),
                        actual.after(),
                        actual.payload()));
    }

    private static CertificateTracePayload.RebuildRecord onlyRebuildRecord(
            List<CertificateTraceEvent> events) {
        return (CertificateTracePayload.RebuildRecord) rebuildEvent(events).payload();
    }

    private static CertificateTraceEvent rebuildEvent(
            List<CertificateTraceEvent> events) {
        CertificateTracePayload.RebuildRecord found = null;
        CertificateTraceEvent foundEvent = null;
        for (CertificateTraceEvent event : events) {
            if (event.kind() == CertificateTraceEvent.Kind.REBUILD_RECORD) {
                if (found != null) {
                    throw new AssertionError("Expected one rebuild-record event");
                }
                found = (CertificateTracePayload.RebuildRecord) event.payload();
                foundEvent = event;
            }
        }
        if (found == null) {
            throw new AssertionError("Missing rebuild-record event");
        }
        return foundEvent;
    }

    private static CertificateTraceEvent completionEvent(
            List<CertificateTraceEvent> events) {
        for (CertificateTraceEvent event : events) {
            if (event.kind() == CertificateTraceEvent.Kind.REBUILD_COMPLETE) {
                return event;
            }
        }
        throw new AssertionError("Missing rebuild-completion event");
    }

    private static void assertContinuous(List<CertificateTraceEvent> events) {
        for (int index = 0; index < events.size(); index++) {
            CertificateTraceEvent event = events.get(index);
            check(event.sequence() == index,
                    "Trace sequence is consecutive at event " + index);
            if (index != 0) {
                check(events.get(index - 1).after().stateKey().equals(
                                event.before().stateKey()),
                        "Trace states are continuous at event " + index);
            }
        }
    }

    private static TypedEClassInterface emptyClass(long id) {
        return new TypedEClassInterface(
                EClassId.of(id), GraphType.BOOL, TypedSlotContext.empty());
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
            List<CanonicalShape> shapes,
            String label) {
        Map<CanonicalShape, ShapeWitness> witnesses = new LinkedHashMap<>();
        Map<CanonicalShape, TypedEqualityCertificate> equations = new LinkedHashMap<>();
        TypedSlotContext empty = TypedSlotContext.empty();
        for (int index = 0; index < shapes.size(); index++) {
            CanonicalShape shape = shapes.get(index);
            ShapeWitness witness = new ShapeWitness(
                    empty, empty, empty, TypedRenaming.identity(empty));
            witnesses.put(shape, witness);
            equations.put(shape, new InputEquationCertificate(
                    CertificateOrigin.inputEquation("phase4-v8", label, index),
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
            TypedEClassInterface parent,
            String label,
            int index) {
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase4-v8", label, index),
                TypedCertificateEndpoint.eclassWitness(child),
                TypedCertificateEndpoint.eclassWitness(parent));
        return new ParentEdgeCertificate(
                child, TypedInvocation.identity(parent), equation);
    }

    private static CertificateTracePayload.Union standaloneEmptyUnion(
            String label,
            long childId,
            long parentId) {
        return new CertificateTracePayload.Union(parentEquation(
                emptyClass(childId), emptyClass(parentId), label, 0));
    }

    private static CertificateTraceSnapshot cloneSnapshot(
            CertificateTraceSnapshot source,
            Map<ParentRecordKey, TypedEqualityCertificate> shapeCertificates,
            Map<ParentRecordKey, RetiredShapeRecordCertificate> retirements) {
        return new CertificateTraceSnapshot(
                source.revision(),
                source.status(),
                source.classes(),
                source.parents(),
                source.hashCons(),
                shapeCertificates,
                retirements,
                source.parentUses(),
                source.dirtyParents(),
                source.restrictions(),
                source.insertions());
    }

    private static CertificateTraceSnapshot cloneSnapshot(
            CertificateTraceSnapshot source,
            Map<EClassId, TypedEClassRecord> classes,
            Set<ParentRecordKey> dirty) {
        return new CertificateTraceSnapshot(
                source.revision(),
                source.status(),
                classes,
                source.parents(),
                source.hashCons(),
                source.shapeCertificates(),
                source.retiredShapeRecords(),
                source.parentUses(),
                dirty,
                source.restrictions(),
                source.insertions());
    }

    private static CanonicalShape binarySlotShape(String name) {
        TypedSlot first = TypedSlot.canonicalFree(GraphType.BOOL, 0);
        TypedSlot second = TypedSlot.canonicalFree(GraphType.BOOL, 1);
        TypedSlotContext context = TypedSlotContext.of(first, second);
        InstantiatedOperator operator = OperatorDeclaration.monomorphic(
                name,
                List.of(new OnePortSchema(GraphType.BOOL),
                        new OnePortSchema(GraphType.BOOL)),
                GraphType.BOOL,
                Collections.emptyMap(),
                null).instantiateMonomorphic();
        return CanonicalShape.of(TypedENode.construct(
                operator,
                context,
                List.of(OnePort.slot(context, first), OnePort.slot(context, second))));
    }

    private static ShapeWitness contextualWitness(
            TypedSlotContext canonical,
            TypedSlotContext ambient,
            TypedSlotContext exposed) {
        Map<TypedSlot, TypedSlot> mapping = new LinkedHashMap<>();
        int index = 0;
        for (TypedSlot slot : canonical) {
            mapping.put(slot, ambient.slotsOfType(slot.type()).get(index++));
        }
        return new ShapeWitness(
                canonical,
                ambient,
                exposed,
                TypedRenaming.of(canonical, ambient, mapping));
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
        TypedEmbedding ownerInAmbient = TypedEmbedding.inclusion(
                owner.exposedSlots(), witness.ambientSupport());
        InputEquationCertificate equation = new InputEquationCertificate(
                CertificateOrigin.inputEquation("phase4-v8", label, index),
                TypedCertificateEndpoint.node(
                        shape.node().act(witness.instantiatingRenaming())),
                TypedCertificateEndpoint.invocation(
                        new TypedInvocation(owner, ownerInAmbient)));
        graph.admitFixedBatchRecordCertified(
                record, Collections.singletonMap(shape, equation));
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
                CertificateOrigin.inputEquation("phase4-v8", label, index),
                TypedCertificateEndpoint.eclassWitness(record.interfaceView()),
                reduced.act(inclusion));
        return new InterfaceRestrictionCertificate(
                record, restricted, factorization);
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
                    "Expected " + expected.getSimpleName() + " but received "
                            + throwable.getClass().getSimpleName(),
                    throwable);
        }
        throw new AssertionError("Expected " + expected.getSimpleName());
    }
}
